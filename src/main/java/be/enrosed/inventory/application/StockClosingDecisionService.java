package be.enrosed.inventory.application;

import be.enrosed.inventory.adapter.out.persistence.InventoryStore;
import be.enrosed.inventory.adapter.out.persistence.StockClosingDecisionEntity;
import be.enrosed.inventory.adapter.out.persistence.StockClosingEntity;
import be.enrosed.inventory.domain.ClosingDecisionKind;
import be.enrosed.inventory.domain.WriteDownReason;
import be.enrosed.shared.NotFoundException;
import be.enrosed.shared.UnprocessableBusinessRuleException;
import be.enrosed.shared.security.ActorRef;
import be.enrosed.shared.security.CurrentActor;
import be.enrosed.sourcing.domain.LotCost;
import be.enrosed.sourcing.domain.PurchasePayment;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The inputs of a closing that are the user's to give: what a credit is,
 * what is still owed, whether goods on the water are the company's, which
 * pieces carry a lower market value. Each one is stored with who decided and
 * when, and replaces the earlier one with the same subject; goods of third
 * parties and waardeverminderingen add up instead.
 *
 * Nothing is stored that can have no effect: input that can never be valid
 * is refused. The caller holds the closing row lock and computes afterwards.
 */
@ApplicationScoped
public class StockClosingDecisionService {

    public static final Set<String> BILLED = Set.of("BESTELD", "GELEVERD");
    public static final Set<String> TREATMENTS = Set.of("VERLAAGT", "BUITEN", "IN_BETALING");
    public static final Set<String> INVOICED = Set.of(FifoValuer.CHOICE_OUT, FifoValuer.CHOICE_STAYS, FifoValuer.CHOICE_GONE);
    public static final Set<String> STREAMS = Set.of("SUPPLIER", "LOGISTICS", "SEPARATE");

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final InventoryStore.Decisions decisions;
    private final CurrentActor actor;

    public StockClosingDecisionService(InventoryStore.Decisions decisions, CurrentActor actor) {
        this.decisions = decisions;
        this.actor = actor;
    }

    /* ---------------------------------------------------------------- shapes */

    public record Write(Long id, String kind, Long purchaseOrderId, Long salesOrderId, List<Long> salesOrderIds,
                        Long productId, Long movementId, Long creditId, String payee, String choice, Boolean flag,
                        Integer quantity, BigDecimal unitValueEur, BigDecimal amountEur, LocalDate decisionDate,
                        String reasonCode, String reason, String counterparty) {}

    /** A container as far as a decision about it is checked. */
    public record ContainerFacts(LocalDate receivedOn, Set<Long> creditIds) {}

    /** The date ownership or risk passed as a previous final closing stored it: no decision of a later year moves it. */
    public record FixedBorder(LocalDate date, int closingYear) {}

    /** What the closing knows about the subject of a decision, read by the service that owns the closing. */
    public interface Facts {

        /** Null when the container does not exist. */
        ContainerFacts container(long purchaseOrderId);

        /** Null when the previous closing did not fix the border of this container. */
        FixedBorder fixedBorder(long purchaseOrderId);

        /** The unpaid part of the Afspraak of one payee stream right now, before any accrual. */
        BigDecimal openAmount(long purchaseOrderId, String payee);

        boolean productExists(long productId);

        /** The own quantity of the product in the last compute; zero when it is not in the closing. */
        int ownQuantity(long productId);

        /** Whether the invoice is one this closing asks about. */
        boolean invoiceCandidate(long salesOrderId);

        /** The number of a sales document, for a refusal that names it; null when it does not exist. */
        String invoiceNumber(long salesOrderId);

        boolean movementListed(long movementId);
    }

    /* ------------------------------------------------------------------ write */

    /** Stores one decision, or one per invoice for an INVOICED write that names several. */
    @Transactional
    public List<StockClosingDecisionEntity> write(StockClosingEntity closing, Write write, Facts facts) {
        ClosingDecisionKind kind = write == null ? null : ClosingDecisionKind.of(write.kind());
        if (kind == null) throw new UnprocessableBusinessRuleException("Onbekende beslissing");
        String reason = write.reason() == null || write.reason().isBlank() ? null : cut(write.reason().strip(), 1000);
        List<StockClosingDecisionEntity> existing = of(closing.id);
        List<StockClosingDecisionEntity> stored = new ArrayList<>();

        switch (kind) {
            case ACCRUAL -> {
                requireContainer(write, facts);
                if (write.payee() == null || !STREAMS.contains(write.payee())) throw subject();
                if (write.amountEur() == null || write.amountEur().signum() < 0) {
                    throw new UnprocessableBusinessRuleException("Geef een bedrag van nul of meer");
                }
                requireReason(reason);
                StockClosingDecisionEntity row = find(existing, kind, d -> Objects.equals(d.purchaseOrderId, write.purchaseOrderId())
                        && Objects.equals(d.payee, write.payee()));
                row.purchaseOrderId = write.purchaseOrderId();
                row.payee = write.payee();
                row.amountEur = write.amountEur().setScale(2, RoundingMode.HALF_UP);
                row.flag = Boolean.TRUE.equals(write.flag());
                /* The amount is confirmed against what is open now; when that moves, the confirmation is asked again. */
                BigDecimal open = facts.openAmount(write.purchaseOrderId(), write.payee());
                row.basisAmountEur = (open == null ? BigDecimal.ZERO : open).setScale(2, RoundingMode.HALF_UP);
                stored.add(row);
            }
            case SUPPLIER_BILLED -> {
                requireContainer(write, facts);
                if (write.choice() == null || !BILLED.contains(write.choice())) throw subject();
                requireReason(reason);
                StockClosingDecisionEntity row = find(existing, kind, d -> Objects.equals(d.purchaseOrderId, write.purchaseOrderId()));
                row.purchaseOrderId = write.purchaseOrderId();
                row.choice = write.choice();
                stored.add(row);
            }
            case CREDIT_TREATMENT -> {
                ContainerFacts container = requireContainer(write, facts);
                if (write.creditId() == null) throw subject();
                if (write.choice() == null || !TREATMENTS.contains(write.choice())) {
                    throw new UnprocessableBusinessRuleException("Kies wat dit tegoed is");
                }
                if (!container.creditIds().contains(write.creditId())) {
                    throw new UnprocessableBusinessRuleException("Dit tegoed hoort niet bij deze container");
                }
                requireReason(reason);
                StockClosingDecisionEntity row = find(existing, kind, d -> Objects.equals(d.purchaseOrderId, write.purchaseOrderId())
                        && Objects.equals(d.creditId, write.creditId()));
                row.purchaseOrderId = write.purchaseOrderId();
                row.creditId = write.creditId();
                row.choice = write.choice();
                stored.add(row);
            }
            case OWNERSHIP_DATE -> {
                ContainerFacts container = requireContainer(write, facts);
                FixedBorder fixed = facts.fixedBorder(write.purchaseOrderId());
                if (fixed != null) throw fixed(fixed);
                if (write.decisionDate() == null) {
                    throw new UnprocessableBusinessRuleException("Geef de datum waarop eigendom of risico overging");
                }
                if (write.decisionDate().isAfter(closing.closingDate)
                        || (container.receivedOn() != null && write.decisionDate().isAfter(container.receivedOn()))) {
                    throw new UnprocessableBusinessRuleException(
                            "Die datum moet op of voor de ontvangstdatum en de afsluitdatum liggen");
                }
                requireReason(reason);
                StockClosingDecisionEntity row = find(existing, kind, d -> Objects.equals(d.purchaseOrderId, write.purchaseOrderId()));
                row.purchaseOrderId = write.purchaseOrderId();
                row.decisionDate = write.decisionDate();
                stored.add(row);
            }
            case TRANSIT -> {
                requireContainer(write, facts);
                boolean included = Boolean.TRUE.equals(write.flag());
                if (included) {
                    if (write.decisionDate() == null) {
                        throw new UnprocessableBusinessRuleException("Geef de datum waarop eigendom of risico overging");
                    }
                    if (write.decisionDate().isAfter(closing.closingDate)) {
                        throw new UnprocessableBusinessRuleException("Die datum moet op of voor de afsluitdatum liggen");
                    }
                    FixedBorder fixed = facts.fixedBorder(write.purchaseOrderId());
                    if (fixed != null && !fixed.date().equals(write.decisionDate())) throw fixed(fixed);
                }
                requireReason(reason);
                StockClosingDecisionEntity row = find(existing, kind, d -> Objects.equals(d.purchaseOrderId, write.purchaseOrderId()));
                row.purchaseOrderId = write.purchaseOrderId();
                row.flag = included;
                row.decisionDate = included ? write.decisionDate() : null;
                stored.add(row);
            }
            case PARTNER_CONTAINER -> {
                requireContainer(write, facts);
                requireReason(reason);
                StockClosingDecisionEntity row = find(existing, kind, d -> Objects.equals(d.purchaseOrderId, write.purchaseOrderId()));
                row.purchaseOrderId = write.purchaseOrderId();
                row.flag = Boolean.TRUE.equals(write.flag());
                stored.add(row);
            }
            case PARTNER_QUANTITY -> {
                requireContainer(write, facts);
                if (write.productId() == null) throw subject();
                if (write.quantity() == null || write.quantity() < 0) {
                    throw new UnprocessableBusinessRuleException("Aantal kan niet negatief zijn");
                }
                requireReason(reason);
                StockClosingDecisionEntity row = find(existing, kind, d -> Objects.equals(d.purchaseOrderId, write.purchaseOrderId())
                        && Objects.equals(d.productId, write.productId()));
                row.purchaseOrderId = write.purchaseOrderId();
                row.productId = write.productId();
                row.quantity = write.quantity();
                stored.add(row);
            }
            case INVOICED -> {
                Set<Long> invoices = new LinkedHashSet<>();
                if (write.salesOrderIds() != null) write.salesOrderIds().stream().filter(Objects::nonNull).forEach(invoices::add);
                if (write.salesOrderId() != null) invoices.add(write.salesOrderId());
                if (invoices.isEmpty()) throw subject();
                if (write.choice() == null || !INVOICED.contains(write.choice())) {
                    throw new UnprocessableBusinessRuleException("Kies wat er met de stuks van deze factuur was");
                }
                for (Long invoiceId : invoices) {
                    if (facts.invoiceCandidate(invoiceId)) continue;
                    String number = facts.invoiceNumber(invoiceId);
                    throw new UnprocessableBusinessRuleException("Factuur " + (number == null ? String.valueOf(invoiceId) : number)
                            + " hoort niet bij deze afsluiting");
                }
                if (!FifoValuer.CHOICE_OUT.equals(write.choice())) requireReason(reason);
                for (Long invoiceId : invoices) {
                    StockClosingDecisionEntity row = find(existing, kind, d -> Objects.equals(d.salesOrderId, invoiceId));
                    row.salesOrderId = invoiceId;
                    row.choice = write.choice();
                    stored.add(row);
                }
            }
            case THIRD_PARTY -> {
                if (write.productId() == null) throw subject();
                if (!facts.productExists(write.productId())) throw new NotFoundException("Product", write.productId());
                if (write.counterparty() == null || write.counterparty().isBlank()) {
                    throw new UnprocessableBusinessRuleException("Vermeld de eigenaar");
                }
                if (write.quantity() == null || write.quantity() < 1) {
                    throw new UnprocessableBusinessRuleException("Geef een aantal groter dan nul");
                }
                requireReason(reason);
                StockClosingDecisionEntity row = byId(existing, kind, write.id());
                row.productId = write.productId();
                row.quantity = write.quantity();
                row.counterparty = cut(write.counterparty().strip(), 255);
                stored.add(row);
            }
            case WRITE_DOWN -> {
                if (write.productId() == null) throw subject();
                if (!facts.productExists(write.productId())) throw new NotFoundException("Product", write.productId());
                WriteDownReason code = WriteDownReason.of(write.reasonCode());
                if (code == null) throw new UnprocessableBusinessRuleException("Kies een reden voor de waardevermindering");
                if (write.unitValueEur() == null || write.unitValueEur().signum() < 0) {
                    throw new UnprocessableBusinessRuleException("Marktwaarde per stuk kan niet negatief zijn");
                }
                if (write.quantity() != null && write.quantity() <= 0) {
                    throw new UnprocessableBusinessRuleException(
                            "Geef een aantal groter dan nul, of laat het leeg voor alle stuks");
                }
                requireReason(reason);
                StockClosingDecisionEntity row = byId(existing, kind, write.id());
                if (write.quantity() != null) {
                    int others = existing.stream().filter(d -> ClosingDecisionKind.WRITE_DOWN.name().equals(d.kind)
                                    && Objects.equals(d.productId, write.productId()) && d != row && d.quantity != null)
                            .mapToInt(d -> d.quantity).sum();
                    int own = facts.ownQuantity(write.productId());
                    if (others + write.quantity() > own) {
                        throw new InventoryRefusal("AFWAARDERING_TE_VEEL",
                                "De aantallen met een waardevermindering zijn samen meer dan de eigen voorraad (" + own + ")");
                    }
                }
                row.productId = write.productId();
                row.quantity = write.quantity();
                row.unitValueEur = write.unitValueEur().setScale(4, RoundingMode.HALF_UP);
                row.reasonCode = code.code();
                stored.add(row);
            }
            case MOVEMENT -> {
                if (write.movementId() == null || write.flag() == null || !facts.movementListed(write.movementId())) throw subject();
                requireReason(reason);
                StockClosingDecisionEntity row = find(existing, kind, d -> Objects.equals(d.movementId, write.movementId()));
                row.movementId = write.movementId();
                row.flag = write.flag();
                stored.add(row);
            }
            case VAT_CONFIRMATION -> {
                StockClosingDecisionEntity row = find(existing, kind, d -> true);
                row.flag = write.flag() == null || write.flag();
                stored.add(row);
            }
        }

        ActorRef who = actor.current();
        Instant now = Instant.now();
        for (StockClosingDecisionEntity row : stored) {
            row.closingId = closing.id;
            row.kind = kind.name();
            row.reason = reason;
            row.decidedBy = who.username();
            row.decidedByName = cut(who.displayName(), 120);
            row.decidedAt = now;
            if (row.id == null) decisions.persist(row);
        }
        decisions.flush();
        return stored;
    }

    /** Takes a decision back; the concept falls back on the proposal. */
    @Transactional
    public void delete(StockClosingEntity closing, long decisionId) {
        StockClosingDecisionEntity row = decisions.findById(decisionId);
        if (row == null || !Objects.equals(row.closingId, closing.id)) throw new NotFoundException("Beslissing", decisionId);
        decisions.delete(row);
        decisions.flush();
    }

    /** The decisions of a closing in the order they were first taken. */
    @Transactional
    public List<StockClosingDecisionEntity> of(long closingId) {
        return decisions.list("closingId = ?1 order by id", closingId);
    }

    /* ------------------------------------------------------------------ index */

    /** The decisions of one closing by what they are about. */
    public record Index(List<StockClosingDecisionEntity> all) {

        public static Index of(List<StockClosingDecisionEntity> all) {
            return new Index(all == null ? List.of() : all);
        }

        public List<StockClosingDecisionEntity> kind(ClosingDecisionKind kind) {
            return all.stream().filter(decision -> kind.name().equals(decision.kind)).toList();
        }

        public boolean any(ClosingDecisionKind... kinds) {
            return all.stream().anyMatch(decision -> List.of(kinds).stream().anyMatch(kind -> kind.name().equals(decision.kind)));
        }

        public StockClosingDecisionEntity container(ClosingDecisionKind kind, Long purchaseOrderId) {
            return kind(kind).stream().filter(decision -> Objects.equals(decision.purchaseOrderId, purchaseOrderId))
                    .findFirst().orElse(null);
        }

        public StockClosingDecisionEntity accrual(Long purchaseOrderId, String payee) {
            return kind(ClosingDecisionKind.ACCRUAL).stream().filter(decision -> Objects.equals(decision.purchaseOrderId, purchaseOrderId)
                    && Objects.equals(decision.payee, payee)).findFirst().orElse(null);
        }

        public StockClosingDecisionEntity credit(Long purchaseOrderId, Long creditId) {
            return kind(ClosingDecisionKind.CREDIT_TREATMENT).stream()
                    .filter(decision -> Objects.equals(decision.purchaseOrderId, purchaseOrderId)
                            && Objects.equals(decision.creditId, creditId)).findFirst().orElse(null);
        }

        public StockClosingDecisionEntity partnerQuantity(Long purchaseOrderId, Long productId) {
            return kind(ClosingDecisionKind.PARTNER_QUANTITY).stream()
                    .filter(decision -> Objects.equals(decision.purchaseOrderId, purchaseOrderId)
                            && Objects.equals(decision.productId, productId)).findFirst().orElse(null);
        }

        public StockClosingDecisionEntity invoice(Long salesOrderId) {
            return kind(ClosingDecisionKind.INVOICED).stream().filter(decision -> Objects.equals(decision.salesOrderId, salesOrderId))
                    .findFirst().orElse(null);
        }

        public StockClosingDecisionEntity movement(Long movementId) {
            return kind(ClosingDecisionKind.MOVEMENT).stream().filter(decision -> Objects.equals(decision.movementId, movementId))
                    .findFirst().orElse(null);
        }

        public boolean vatConfirmed() {
            return kind(ClosingDecisionKind.VAT_CONFIRMATION).stream().anyMatch(decision -> Boolean.TRUE.equals(decision.flag));
        }

        /** The accruals of one container as the lot cost reads them. */
        public Map<PurchasePayment.Payee, LotCost.Accrual> accruals(Long purchaseOrderId) {
            Map<PurchasePayment.Payee, LotCost.Accrual> accruals = new java.util.EnumMap<>(PurchasePayment.Payee.class);
            for (StockClosingDecisionEntity decision : kind(ClosingDecisionKind.ACCRUAL)) {
                if (!Objects.equals(decision.purchaseOrderId, purchaseOrderId) || decision.payee == null
                        || !STREAMS.contains(decision.payee) || decision.amountEur == null) continue;
                accruals.put(PurchasePayment.Payee.valueOf(decision.payee), new LotCost.Accrual(decision.amountEur,
                        Boolean.TRUE.equals(decision.flag), decision.basisAmountEur == null ? BigDecimal.ZERO : decision.basisAmountEur));
            }
            return accruals;
        }

        /** The treatment the user chose per credit of one container. */
        public Map<Long, LotCost.CreditTreatment> treatments(Long purchaseOrderId) {
            Map<Long, LotCost.CreditTreatment> treatments = new java.util.HashMap<>();
            for (StockClosingDecisionEntity decision : kind(ClosingDecisionKind.CREDIT_TREATMENT)) {
                if (!Objects.equals(decision.purchaseOrderId, purchaseOrderId) || decision.creditId == null
                        || decision.choice == null || !TREATMENTS.contains(decision.choice)) continue;
                treatments.put(decision.creditId, LotCost.CreditTreatment.valueOf(decision.choice));
            }
            return treatments;
        }
    }

    /* --------------------------------------------------------------- internals */

    private static ContainerFacts requireContainer(Write write, Facts facts) {
        if (write.purchaseOrderId() == null) throw subject();
        ContainerFacts container = facts.container(write.purchaseOrderId());
        if (container == null) throw subject();
        return container;
    }

    private static void requireReason(String reason) {
        if (reason == null) throw new UnprocessableBusinessRuleException("Geef een reden");
    }

    private static UnprocessableBusinessRuleException subject() {
        return new UnprocessableBusinessRuleException("Kies het onderwerp van deze beslissing");
    }

    private static UnprocessableBusinessRuleException fixed(FixedBorder border) {
        return new UnprocessableBusinessRuleException("De datum van eigendom of risico ligt vast sinds de afsluiting van "
                + border.closingYear() + ": " + DAY.format(border.date()) + ".");
    }

    /** The decision with this key, emptied of what the last write left; a new one when there is none. */
    private static StockClosingDecisionEntity find(List<StockClosingDecisionEntity> existing, ClosingDecisionKind kind,
                                                   java.util.function.Predicate<StockClosingDecisionEntity> key) {
        return existing.stream().filter(decision -> kind.name().equals(decision.kind)).filter(key).findFirst()
                .orElseGet(StockClosingDecisionEntity::new);
    }

    private static StockClosingDecisionEntity byId(List<StockClosingDecisionEntity> existing, ClosingDecisionKind kind, Long id) {
        if (id == null) return new StockClosingDecisionEntity();
        return existing.stream().filter(decision -> decision.id.equals(id) && kind.name().equals(decision.kind)).findFirst()
                .orElseThrow(() -> new NotFoundException("Beslissing", id));
    }

    private static String cut(String value, int length) {
        return value == null || value.length() <= length ? value : value.substring(0, length);
    }
}
