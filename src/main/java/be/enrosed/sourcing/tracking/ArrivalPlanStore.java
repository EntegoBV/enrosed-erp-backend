package be.enrosed.sourcing.tracking;

import be.enrosed.catalog.application.StockService;
import be.enrosed.catalog.domain.StockLocation;
import be.enrosed.shared.BusinessRuleException;
import be.enrosed.shared.audit.ActivityChangeSet;
import be.enrosed.shared.audit.ActivityLogService;
import be.enrosed.sourcing.adapter.out.persistence.SourcingEntities.PurchaseOrderEntity;
import be.enrosed.sourcing.domain.PurchaseOrderStatus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.transaction.Transactional;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * The arrival set by hand (Emre, 2026-11-15: "aankomst container ook manueel kunnen zetten en aankomst Zaltbommel
 * manueel op dag en uur; Verhoeven moet het op zijn portaal terugzien"). It owns purchase_order.expected_arrival_time,
 * arrival_by_hand, warehouse_arrival_on and warehouse_arrival_time, and writes expected_arrival when the container's
 * day is set here. The purchase domain record does not carry the four columns, so the order form never sends them
 * and a stale form cannot undo them (as {@link ArrivalMoveStore}).
 *
 * <p>The container's arrival is the day (and optionally the hour) it is expected at the port of arrival, the order's
 * expectedArrival. Set here while the order has track &amp; trace, it is a date by hand: tracking leaves it alone
 * ({@link TrackingDateSync}) until "Volg track &amp; trace" hands it back. Set before the order has track &amp; trace,
 * it stays the planning tracking may replace, as a date typed in the order form always was. The warehouse arrival
 * is the day and hour the container is expected at the place it is unloaded at ("Lossen op": Zaltbommel for the
 * main warehouse); nothing but this store writes it.</p>
 */
@ApplicationScoped
public class ArrivalPlanStore {
    private static final String ORDER = "be.enrosed.sourcing.adapter.out.persistence.SourcingEntities$PurchaseOrderEntity";
    private static final Pattern HOUR = Pattern.compile("^([01]\\d|2[0-3]):[0-5]\\d$");
    private static final DateTimeFormatter HOUR_TEXT = DateTimeFormatter.ofPattern("HH:mm");
    /** The main warehouse's name when no MAIN location row exists (as the partner's container list says it). */
    private static final String MAIN_NAME = "Magazijn";

    @Inject EntityManager entities;
    @Inject ActivityLogService activity;
    @Inject StockService stock;

    /** What one order holds: the container's hour at the port, whether its day is by hand, the warehouse day and hour. */
    public record Plan(LocalTime containerAt, boolean byHand, LocalDate warehouseOn, LocalTime warehouseAt) {
        public static final Plan NONE = new Plan(null, false, null, null);
    }

    /**
     * The block on the order's answer. Hours read "08:30". containerByHand: the container's day is by hand, so
     * tracking does not move it (it only says so while the order has track &amp; trace).
     */
    public record View(String containerAt, boolean containerByHand, LocalDate warehouseOn, String warehouseAt) {
        public static View of(Plan plan) {
            Plan value = plan == null ? Plan.NONE : plan;
            return new View(hourText(value.containerAt()), value.byHand(), value.warehouseOn(), hourText(value.warehouseAt()));
        }
    }

    /**
     * The sheet's save. containerOn is the order's expectedArrival (null: no day yet); containerAt and warehouseAt are
     * "HH:mm" or empty. containerByHand counts only while the order has track &amp; trace: true keeps the day and hour
     * sent ("Zelf instellen"), false lets tracking follow ("Track &amp; trace volgen"): the day sent stays until the
     * source's next date replaces it, and the hour goes, as sources name a day.
     */
    public record Request(LocalDate containerOn, String containerAt, Boolean containerByHand,
                          LocalDate warehouseOn, String warehouseAt) {}

    /** followed: the container's day went back to tracking now, so the caller lets tracking apply its date. */
    public record Result(boolean followed) {}

    @Transactional
    public Map<Long, Plan> all() {
        Map<Long, Plan> plans = new HashMap<>();
        List<Object[]> rows = entities.createQuery("select o.id, o.expectedArrivalTime, o.arrivalByHand, o.warehouseArrivalOn, o.warehouseArrivalTime from "
                + ORDER + " o where o.expectedArrivalTime is not null or o.arrivalByHand = true or o.warehouseArrivalOn is not null",
                Object[].class).getResultList();
        for (Object[] row : rows) plans.put((Long) row[0], plan(row[1], row[2], row[3], row[4]));
        return plans;
    }

    @Transactional
    public Plan find(long orderId) {
        return entities.createQuery("select o.expectedArrivalTime, o.arrivalByHand, o.warehouseArrivalOn, o.warehouseArrivalTime from "
                        + ORDER + " o where o.id = :id", Object[].class)
                .setParameter("id", orderId).getResultStream().findFirst()
                .map(row -> plan(row[0], row[1], row[2], row[3])).orElse(Plan.NONE);
    }

    @Transactional
    public Result set(long orderId, Request request) {
        if (request == null) throw new BusinessRuleException("Geef de aankomst mee");
        PurchaseOrderEntity order = entities.find(PurchaseOrderEntity.class, orderId, LockModeType.PESSIMISTIC_WRITE);
        if (order == null || order.deletedAt != null) throw new be.enrosed.shared.NotFoundException("Inkooporder", orderId);
        if (order.status == PurchaseOrderStatus.ONTVANGEN || order.receivedOn != null)
            throw new BusinessRuleException("Deze container is al ontvangen: de aankomst ligt vast");

        LocalTime containerAt = hour(request.containerAt(), "Het uur van de aankomst container");
        LocalTime warehouseAt = hour(request.warehouseAt(), "Het uur van de aankomst in " + placeName(order.receivingLocationId));
        LocalDate containerOn = request.containerOn();
        LocalDate warehouseOn = request.warehouseOn();
        if (containerAt != null && containerOn == null) throw new BusinessRuleException("Kies eerst de dag van de aankomst container");
        if (warehouseAt != null && warehouseOn == null)
            throw new BusinessRuleException("Kies eerst de dag van de aankomst in " + placeName(order.receivingLocationId));

        boolean tracked = order.trackingReference != null && !order.trackingReference.isBlank();
        boolean wasByHand = Boolean.TRUE.equals(order.arrivalByHand);
        /* By hand only means something while track & trace could move the day, and only with a day to keep. */
        boolean byHand = tracked && containerOn != null && Boolean.TRUE.equals(request.containerByHand());
        boolean follow = tracked && !byHand;
        if (follow) {
            /* The day stays with (or goes back to) tracking: the stored day counts, not the screen's copy of it, which
               may be from before tracking moved it (a page left open). The sheet never lets that day be typed. The
               hour goes, as sources name a day; a hand day given back is replaced by adoptStoredDates. */
            containerOn = order.expectedArrival;
            containerAt = null;
        }
        boolean dayMoved = !Objects.equals(order.expectedArrival, containerOn);

        String place = placeName(order.receivingLocationId);
        var changes = ActivityChangeSet.create()
                .add("expectedArrival", "Aankomst container", order.expectedArrival, containerOn)
                .add("expectedArrivalTime", "Uur aankomst container", hourText(order.expectedArrivalTime), hourText(containerAt))
                .add("arrivalByHand", "Aankomst container met de hand", tracked || wasByHand ? wasByHand : null, tracked || byHand ? byHand : null)
                .add("warehouseArrivalOn", "Aankomst " + place, order.warehouseArrivalOn, warehouseOn)
                .add("warehouseArrivalTime", "Uur aankomst " + place, hourText(order.warehouseArrivalTime), hourText(warehouseAt))
                .build();
        if (dayMoved) {
            /* As the order form: a day set by hand is the planning from then on, what tracking moved no longer counts. */
            order.arrivalMovedFrom = null;
            order.arrivalMovedAt = null;
            order.arrivalNoticeDate = null;
            order.arrivalNoticeAt = null;
        }
        order.expectedArrival = containerOn;
        order.expectedArrivalTime = containerAt;
        order.arrivalByHand = byHand ? Boolean.TRUE : null;
        order.warehouseArrivalOn = warehouseOn;
        order.warehouseArrivalTime = warehouseOn == null ? null : warehouseAt;
        if (!changes.isEmpty())
            activity.record(ActivityLogService.ACTION_UPDATED, ActivityLogService.ENTITY_PURCHASE_ORDER,
                    Long.toString(order.id), order.number, follow && wasByHand ? "Aankomst container volgt weer track & trace" : "Aankomst bijgewerkt", changes);
        return new Result(follow && wasByHand);
    }

    /** The place the container is unloaded at, as the order's "Lossen op" names it: Zaltbommel for the main warehouse. */
    String placeName(Long locationId) {
        StockLocation main = null;
        for (StockLocation location : stock.locations()) {
            if (location.id() == null) continue;
            if (locationId != null && locationId.equals(location.id()) && location.name() != null && !location.name().isBlank())
                return location.name().strip();
            if (StockLocation.MAIN_CODE.equals(location.code())) main = location;
        }
        return main != null && main.name() != null && !main.name().isBlank() ? main.name().strip() : MAIN_NAME;
    }

    private static Plan plan(Object containerAt, Object byHand, Object warehouseOn, Object warehouseAt) {
        return new Plan((LocalTime) containerAt, Boolean.TRUE.equals(byHand), (LocalDate) warehouseOn, (LocalTime) warehouseAt);
    }

    private static LocalTime hour(String value, String label) {
        if (value == null || value.isBlank()) return null;
        String text = value.strip();
        /* An <input type="time"> may send seconds ("08:30:00"); the hour and minute are what counts. */
        if (text.length() == 8 && text.endsWith(":00")) text = text.substring(0, 5);
        if (!HOUR.matcher(text).matches()) throw new BusinessRuleException(label + " moet uu:mm zijn, bijvoorbeeld 08:30");
        return LocalTime.parse(text);
    }

    static String hourText(LocalTime value) { return value == null ? null : value.format(HOUR_TEXT); }
}
