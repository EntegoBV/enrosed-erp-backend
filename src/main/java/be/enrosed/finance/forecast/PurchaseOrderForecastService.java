package be.enrosed.finance.forecast;

import be.enrosed.shared.BusinessRuleException;
import be.enrosed.shared.security.CurrentActor;
import be.enrosed.sourcing.application.PurchaseOrderService;
import be.enrosed.sourcing.domain.PurchaseOrder;
import be.enrosed.sourcing.domain.PurchaseOrderStatus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.transaction.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * The owner's forecast choices per container, shared by desk and phone. Every
 * control sends the whole set (a stored value repeated unchanged is fine), so
 * the rules that depend on the order's state only look at a value that
 * differs from the stored one: status and time move on while a choice stays.
 * No diary line and no activity record: these are forecast settings, not
 * facts about the container.
 */
@ApplicationScoped
public class PurchaseOrderForecastService {
    static final Set<String> PRICE_MODES = Set.of("MARKUP_PCT", "EUR_PER_UNIT", "LIST", "HISTORIC");
    static final Set<String> COST_BASES = Set.of("EXTERNAL", "PRICING");
    private static final ZoneId BRUSSELS = ZoneId.of("Europe/Brussels");
    private static final BigDecimal THOUSAND = new BigDecimal("1000");
    private static final BigDecimal HUNDRED_THOUSAND = new BigDecimal("100000");

    @Inject EntityManager em;
    @Inject PurchaseOrderService purchases;
    @Inject CurrentActor actor;

    /** The JSON of one order's choices; every field null (and no {@code updatedAt}) once they were cleared. */
    public record PurchaseOrderForecastView(Long orderId, String priceMode, BigDecimal markupPct,
                                            BigDecimal markupEurPerUnit, String costBasis, Integer sellWeeks,
                                            Integer paymentDays, Boolean seasonal, Boolean included,
                                            LocalDate expectedShipOn, LocalDate orderDateOverride, Instant updatedAt,
                                            String actor) {}

    /** The choices as the screen sends them; null = no choice, the forecast's default applies. */
    public record Write(String priceMode, BigDecimal markupPct, BigDecimal markupEurPerUnit, String costBasis,
                        Integer sellWeeks, Integer paymentDays, Boolean seasonal, Boolean included,
                        LocalDate expectedShipOn, LocalDate orderDateOverride) {
        boolean empty() {
            return priceMode == null && markupPct == null && markupEurPerUnit == null && costBasis == null
                    && sellWeeks == null && paymentDays == null && seasonal == null && included == null
                    && expectedShipOn == null && orderDateOverride == null;
        }
    }

    /** The rows of every order that is not in the trash, by order id; an order purged from the trash leaves a row nobody reads. */
    @SuppressWarnings("unchecked")
    public List<PurchaseOrderForecastView> list() {
        List<PurchaseOrderForecastEntity> rows = em.createNativeQuery("select f.* from purchase_order_forecast f"
                + " where exists (select 1 from purchase_order o where o.id = f.order_id and o.deleted_at is null)"
                + " order by f.order_id", PurchaseOrderForecastEntity.class).getResultList();
        return rows.stream().map(PurchaseOrderForecastService::view).toList();
    }

    @Transactional
    public PurchaseOrderForecastView save(long orderId, Write request) {
        /* The service filters the trash; a plain find would see a deleted order. */
        PurchaseOrder order = purchases.get(orderId);
        Write write = request == null ? new Write(null, null, null, null, null, null, null, null, null, null) : request;
        String priceMode = text(write.priceMode());
        if (priceMode != null && !PRICE_MODES.contains(priceMode)) {
            throw new BusinessRuleException("Kies hoe de verkoopprijs bepaald wordt");
        }
        BigDecimal markupPct = write.markupPct();
        if (markupPct != null && (markupPct.signum() < 0 || markupPct.compareTo(THOUSAND) > 0 || decimals(markupPct) > 2)) {
            throw new BusinessRuleException("Geef een opslag tussen 0 en 1000 %");
        }
        BigDecimal perUnit = write.markupEurPerUnit();
        if (perUnit != null && (perUnit.signum() < 0 || perUnit.compareTo(HUNDRED_THOUSAND) > 0 || decimals(perUnit) > 4)) {
            throw new BusinessRuleException("Geef een opslag per stuk van 0 of meer (maximaal vier decimalen)");
        }
        String costBasis = text(write.costBasis());
        if (costBasis != null && !COST_BASES.contains(costBasis)) {
            throw new BusinessRuleException("Kies de kostbasis: extern of met Enrosed-kost");
        }
        if (write.sellWeeks() != null && (write.sellWeeks() < 1 || write.sellWeeks() > 104)) {
            throw new BusinessRuleException("Verkocht in moet tussen 1 en 104 weken liggen");
        }
        if (write.paymentDays() != null && (write.paymentDays() < 0 || write.paymentDays() > 365)) {
            throw new BusinessRuleException("De betaaltermijn moet tussen 0 en 365 dagen liggen");
        }
        PurchaseOrderForecastEntity stored = em.find(PurchaseOrderForecastEntity.class, orderId, LockModeType.PESSIMISTIC_WRITE);
        PurchaseOrderForecastEntity before = stored == null ? new PurchaseOrderForecastEntity() : stored;
        if (order.partnerCustomerId() != null
                && (changed(priceMode, before.priceMode) || changed(markupPct, before.markupPct)
                || changed(perUnit, before.markupEurPerUnit) || changed(costBasis, before.costBasis)
                || changed(write.sellWeeks(), before.sellWeeks) || changed(write.paymentDays(), before.paymentDays)
                || changed(write.seasonal(), before.seasonal) || changed(write.included(), before.included)
                || changed(write.orderDateOverride(), before.orderDateOverride))) {
            throw new BusinessRuleException("Een partnercontainer volgt het voorschotschema; kies geen prijs of tempo");
        }
        if (changed(write.expectedShipOn(), before.expectedShipOn)) {
            if (order.status() == PurchaseOrderStatus.ONDERWEG || order.status() == PurchaseOrderStatus.ONTVANGEN) {
                throw new BusinessRuleException("Deze container is al vertrokken; de vertrekdatum staat op de order");
            }
            if (order.orderDate() != null && write.expectedShipOn().isBefore(order.orderDate())) {
                throw new BusinessRuleException("De verwachte vertrekdatum ligt vóór de besteldatum");
            }
        }
        if (changed(write.orderDateOverride(), before.orderDateOverride)) {
            if (order.status() != PurchaseOrderStatus.CONCEPT) {
                throw new BusinessRuleException("Alleen een concept heeft een geplande besteldatum");
            }
            if (write.orderDateOverride().isBefore(LocalDate.now(BRUSSELS))) {
                throw new BusinessRuleException("Kies een besteldatum vanaf vandaag");
            }
        }
        if (write.empty()) {
            if (stored != null) {
                em.remove(stored);
                em.flush();
            }
            return new PurchaseOrderForecastView(orderId, null, null, null, null, null, null, null, null, null, null, null, null);
        }
        PurchaseOrderForecastEntity row = stored;
        if (row == null) {
            row = new PurchaseOrderForecastEntity();
            row.orderId = orderId;
        }
        row.priceMode = priceMode;
        row.markupPct = markupPct == null ? null : markupPct.setScale(2, RoundingMode.UNNECESSARY);
        row.markupEurPerUnit = perUnit == null ? null : perUnit.setScale(4, RoundingMode.UNNECESSARY);
        row.costBasis = costBasis;
        row.sellWeeks = write.sellWeeks();
        row.paymentDays = write.paymentDays();
        row.seasonal = write.seasonal();
        row.included = write.included();
        row.expectedShipOn = write.expectedShipOn();
        row.orderDateOverride = write.orderDateOverride();
        row.updatedAt = Instant.now();
        row.actor = actor.current().displayName();
        if (stored == null) em.persist(row);
        em.flush();
        return view(row);
    }

    /** Back to the defaults; nothing to do when there was no choice. */
    @Transactional
    public void delete(long orderId) {
        PurchaseOrderForecastEntity row = em.find(PurchaseOrderForecastEntity.class, orderId, LockModeType.PESSIMISTIC_WRITE);
        if (row != null) {
            em.remove(row);
            em.flush();
        }
    }

    private static PurchaseOrderForecastView view(PurchaseOrderForecastEntity row) {
        return new PurchaseOrderForecastView(row.orderId, row.priceMode, row.markupPct, row.markupEurPerUnit, row.costBasis,
                row.sellWeeks, row.paymentDays, row.seasonal, row.included, row.expectedShipOn, row.orderDateOverride,
                row.updatedAt, row.actor);
    }

    private static String text(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static int decimals(BigDecimal value) {
        return Math.max(0, value.stripTrailingZeros().scale());
    }

    /** A value that the PUT sets and that the stored row does not already hold. */
    private static boolean changed(Object requested, Object stored) {
        if (requested == null) return false;
        if (requested instanceof BigDecimal amount) return !(stored instanceof BigDecimal held) || amount.compareTo(held) != 0;
        return !Objects.equals(requested, stored);
    }
}
