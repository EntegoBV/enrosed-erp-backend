package be.enrosed.sourcing.tracking;

import be.enrosed.catalog.application.StockService;
import be.enrosed.catalog.domain.StockLocation;
import be.enrosed.shared.BusinessRuleException;
import be.enrosed.shared.audit.ActivityChangeDto;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The arrival set by hand (Emre, 2026-11-15: "aankomst container ook manueel kunnen zetten en aankomst Zaltbommel
 * manueel op dag en uur; Verhoeven moet het op zijn portaal terugzien"). It owns purchase_order.expected_arrival_time,
 * arrival_by_hand, warehouse_arrival_on, warehouse_arrival_time and departure_by_hand, writes expected_arrival when the
 * container's day is set here and shipped_on when the departure day is. The purchase domain record does not carry the
 * five columns, so the order form never sends them and a stale form cannot undo them (as {@link ArrivalMoveStore}).
 *
 * <p>The container's arrival is the day (and optionally the hour) it is expected at the port of arrival, the order's
 * expectedArrival. Set here while the order has track &amp; trace, it is a date by hand: tracking leaves it alone
 * ({@link TrackingDateSync}) until "Volg track &amp; trace" hands it back. Set before the order has track &amp; trace,
 * it stays the planning tracking may replace, as a date typed in the order form always was. The warehouse arrival
 * is the day and hour the container is expected at the place it is unloaded at ("Lossen op": Zaltbommel for the
 * main warehouse); nothing but this store writes it.</p>
 *
 * <p>The departure day (Emre, 2026-11-16, asked whether to add one: "Ja, voeg toe"): the order's shippedOn is the day
 * staff pressed "Container vertrokken", and only a source within three days of it or a final bill of lading could
 * correct it; a scanned bill and a ship feed that misses the call left it wrong for good. Set here on a container
 * under way, it is by hand (departure_by_hand): tracking no longer moves it ({@link TrackingDateSync}), as a source
 * that names another day within its window would put that day back at every pass. It stays until the day is written
 * through an order save (a status step back empties it, a bill of lading taken over writes its on-board day). A ship
 * arrival stored before that cannot be this voyage's for the new day goes with it.</p>
 */
@ApplicationScoped
public class ArrivalPlanStore {
    private static final String ORDER = "be.enrosed.sourcing.adapter.out.persistence.SourcingEntities$PurchaseOrderEntity";
    private static final Pattern HOUR = Pattern.compile("^([01]\\d|2[0-3]):[0-5]\\d$");
    private static final DateTimeFormatter HOUR_TEXT = DateTimeFormatter.ofPattern("HH:mm");
    /** The main warehouse's name when no MAIN location row exists (as the partner's container list says it). */
    private static final String MAIN_NAME = "Magazijn";
    /** The history lines about the departure; every other line of the record is about the arrival. */
    private static final Set<String> DEPARTURE_FIELDS = Set.of("shippedOn", "departureByHand");

    @Inject EntityManager entities;
    @Inject ActivityLogService activity;
    @Inject StockService stock;

    /**
     * What one order holds: the container's hour at the port, whether its day is by hand, the warehouse day and hour,
     * (since 2026-11-16, read here only: {@link TrackingDateSync} writes it) the day the linked ship came in at
     * the port of destination, and whether the departure day was set by hand in this sheet.
     */
    public record Plan(LocalTime containerAt, boolean byHand, LocalDate warehouseOn, LocalTime warehouseAt, LocalDate portArrivedOn,
                       boolean departureByHand) {
        public static final Plan NONE = new Plan(null, false, null, null, null, false);
    }

    /**
     * The block on the order's answer. Hours read "08:30". containerByHand: the container's day is by hand, so
     * tracking does not move it (it only says so while the order has track &amp; trace). portArrivedOn: the linked
     * ship arrived at the port of destination that day ("schip aangekomen"); null while it is not in.
     * departureByHand: the order's shippedOn was set by hand in this sheet, so tracking does not move it. Always a
     * boolean on a server that knows the departure by hand: the screens take its presence as that sign.
     */
    public record View(String containerAt, boolean containerByHand, LocalDate warehouseOn, String warehouseAt, LocalDate portArrivedOn,
                       boolean departureByHand) {
        public static View of(Plan plan) {
            Plan value = plan == null ? Plan.NONE : plan;
            return new View(hourText(value.containerAt()), value.byHand(), value.warehouseOn(), hourText(value.warehouseAt()),
                    value.portArrivedOn(), value.departureByHand());
        }
    }

    /**
     * The sheet's save. containerOn is the order's expectedArrival (null: no day yet); containerAt and warehouseAt are
     * "HH:mm" or empty. containerByHand counts only while the order has track &amp; trace: true keeps the day and hour
     * sent ("Zelf instellen"), false lets tracking follow ("Track &amp; trace volgen"): the day sent stays until the
     * source's next date replaces it, and the hour goes, as sources name a day. shippedOn is the departure day;
     * null or absent leaves the departure alone (older screens send none). The screen sends it only when the user
     * set the day, so a request that carries it means "by hand", also when it is the day already stored.
     */
    public record Request(LocalDate containerOn, String containerAt, Boolean containerByHand,
                          LocalDate warehouseOn, String warehouseAt, LocalDate shippedOn) {}

    /**
     * followed: the container's day went back to tracking now, so the caller lets tracking apply its date.
     * departureChanged: the departure day itself was written, so the caller lets tracking judge its dates against
     * the new day at once.
     */
    public record Result(boolean followed, boolean departureChanged) {}

    @Transactional
    public Map<Long, Plan> all() {
        Map<Long, Plan> plans = new HashMap<>();
        List<Object[]> rows = entities.createQuery("select o.id, o.expectedArrivalTime, o.arrivalByHand, o.warehouseArrivalOn, o.warehouseArrivalTime, o.portArrivedOn, o.departureByHand from "
                + ORDER + " o where o.expectedArrivalTime is not null or o.arrivalByHand = true or o.warehouseArrivalOn is not null"
                + " or o.portArrivedOn is not null or o.departureByHand = true", Object[].class).getResultList();
        for (Object[] row : rows) plans.put((Long) row[0], plan(row[1], row[2], row[3], row[4], row[5], row[6]));
        return plans;
    }

    @Transactional
    public Plan find(long orderId) {
        return entities.createQuery("select o.expectedArrivalTime, o.arrivalByHand, o.warehouseArrivalOn, o.warehouseArrivalTime, o.portArrivedOn, o.departureByHand from "
                        + ORDER + " o where o.id = :id", Object[].class)
                .setParameter("id", orderId).getResultStream().findFirst()
                .map(row -> plan(row[0], row[1], row[2], row[3], row[4], row[5])).orElse(Plan.NONE);
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

        /* The record's own arrival lines compare with the arrival read here, before the departure below can put it
           back: one line tells the day's change, never a second one in the opposite direction. */
        LocalDate arrivalBefore = order.expectedArrival;
        LocalTime hourBefore = order.expectedArrivalTime;
        /* The departure first: a ship arrival it takes back changes the stored day, and the container's day below is
           judged against the order as it stands after that (follow mode reads the stored day). */
        Departure departure = departure(order, request.shippedOn());

        boolean tracked = order.trackingReference != null && !order.trackingReference.isBlank();
        boolean wasByHand = Boolean.TRUE.equals(order.arrivalByHand);
        /* By hand only means something while track & trace could move the day, and only with a day to keep. */
        boolean byHand = tracked && containerOn != null && Boolean.TRUE.equals(request.containerByHand());
        boolean follow = tracked && !byHand;
        if (follow) {
            /* The day stays with (or goes back to) tracking: the stored day counts, not the screen's copy of it, which
               may be from before tracking moved it (a page left open). The sheet never lets that day be typed. The
               hour goes, as sources name a day; a hand day given back is replaced by adoptStoredDates. Once the
               linked ship is in, tracking's day is that arrival: a hand day given back lands on it at once. */
            containerOn = order.portArrivedOn != null ? order.portArrivedOn : order.expectedArrival;
            containerAt = null;
        }
        boolean dayMoved = !Objects.equals(order.expectedArrival, containerOn);

        String place = placeName(order.receivingLocationId);
        List<ActivityChangeDto> changes = new ArrayList<>(departure.changes());
        changes.addAll(ActivityChangeSet.create()
                .add("expectedArrival", "Aankomst container", arrivalBefore, containerOn)
                .add("expectedArrivalTime", "Uur aankomst container", hourText(hourBefore), hourText(containerAt))
                .add("arrivalByHand", "Aankomst container met de hand", tracked || wasByHand ? wasByHand : null, tracked || byHand ? byHand : null)
                .add("warehouseArrivalOn", "Aankomst " + place, order.warehouseArrivalOn, warehouseOn)
                .add("warehouseArrivalTime", "Uur aankomst " + place, hourText(order.warehouseArrivalTime), hourText(warehouseAt))
                .build());
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
                    Long.toString(order.id), order.number, summary(changes, follow && wasByHand), changes);
        return new Result(follow && wasByHand, departure.written());
    }

    /** What the departure part of a save did: its history lines and whether the day itself was written. */
    private record Departure(List<ActivityChangeDto> changes, boolean written) {
        static final Departure NONE = new Departure(List.of(), false);
    }

    /**
     * The departure day of the sheet's save. Only a container under way with a departure day has one to correct:
     * before "Container vertrokken" the departure step sets it. A request that carries the day means "by hand"
     * (the screen sends it only when the user set it), so the mark is set also when the day equals the stored one.
     * Refused in Dutch, before anything is written: a day after today (Brussels, as tracking counts the day) and a
     * day before the order date. A day that was written can make the ship's stored arrival a call that is not this
     * voyage's: it goes ({@link TrackingDateSync#forgetPortArrivalOutsideVoyage}), and of its changes only "Schip
     * aangekomen" joins the record, as the container's own line tells what became of the expected arrival.
     */
    private static Departure departure(PurchaseOrderEntity order, LocalDate day) {
        if (day == null) return Departure.NONE;
        LocalDate stored = order.shippedOn;
        boolean differs = !day.equals(stored);
        if (order.status != PurchaseOrderStatus.ONDERWEG || stored == null) {
            /* An equal day is a screen that sent the stored one: nothing to do. */
            if (differs) throw new BusinessRuleException("De vertrekdag kan je aanpassen zodra de container vertrokken is.");
            return Departure.NONE;
        }
        if (differs && day.isAfter(LocalDate.now(ContainerArrivalReminders.ZONE)))
            throw new BusinessRuleException("De vertrekdag kan niet in de toekomst liggen.");
        if (differs && order.orderDate != null && day.isBefore(order.orderDate))
            throw new BusinessRuleException("De vertrekdag kan niet vóór de orderdatum liggen.");
        boolean wasByHand = Boolean.TRUE.equals(order.departureByHand);
        List<ActivityChangeDto> changes = new ArrayList<>(ActivityChangeSet.create()
                .add("shippedOn", "Vertrokken op", stored, day)
                .add("departureByHand", "Vertrek met de hand", wasByHand ? Boolean.TRUE : null, Boolean.TRUE)
                .build());
        order.shippedOn = day;
        order.departureByHand = Boolean.TRUE;
        if (differs) {
            for (ActivityChangeDto change : TrackingDateSync.forgetPortArrivalOutsideVoyage(order))
                if ("portArrivedOn".equals(change.field())) changes.add(change);
        }
        return new Departure(List.copyOf(changes), differs);
    }

    /**
     * The record's summary. "Schip aangekomen" counts as an arrival line: the ship's arrival is what went, even when
     * the container's own day stayed (by hand).
     */
    private static String summary(List<ActivityChangeDto> changes, boolean followed) {
        boolean departure = changes.stream().anyMatch(change -> DEPARTURE_FIELDS.contains(change.field()));
        boolean arrival = changes.stream().anyMatch(change -> !DEPARTURE_FIELDS.contains(change.field()));
        if (departure) return arrival ? "Vertrek en aankomst bijgewerkt" : "Vertrek bijgewerkt";
        return followed ? "Aankomst container volgt weer track & trace" : "Aankomst bijgewerkt";
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

    private static Plan plan(Object containerAt, Object byHand, Object warehouseOn, Object warehouseAt, Object portArrivedOn,
                             Object departureByHand) {
        return new Plan((LocalTime) containerAt, Boolean.TRUE.equals(byHand), (LocalDate) warehouseOn, (LocalTime) warehouseAt,
                (LocalDate) portArrivedOn, Boolean.TRUE.equals(departureByHand));
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
