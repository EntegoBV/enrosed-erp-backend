package be.enrosed.sourcing.tracking;

import be.enrosed.sales.application.NotificationService;
import be.enrosed.sourcing.domain.PurchaseOrder;
import be.enrosed.sourcing.domain.PurchaseOrderStatus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads the arrival tracking moved (purchase_order.arrival_moved_from, arrival_moved_at, arrival_notice_date,
 * arrival_notice_at). TrackingDateSync.noteArrivalMove is the only writer; a date saved through the order clears them.
 * The purchase domain record does not carry them, so no order save or order answer changes.
 */
@ApplicationScoped
public class ArrivalMoveStore {
    /** The bell speaks from this many days, in either direction. */
    static final int NOTICE_DAYS = 2;
    /** How long the notice stays in the feed after the last move (as the customer-cancel news). */
    static final Duration NOTICE_LIFE = Duration.ofDays(14);
    private static final String ORDER = "be.enrosed.sourcing.adapter.out.persistence.SourcingEntities$PurchaseOrderEntity";
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    @Inject EntityManager entities;

    /**
     * from: the planning that stood before tracking; at: the last move; noticed: the date the bell last named (two
     * days or more from the one before), or null; noticedAt: when it named it; portArrivedOn: the day the linked
     * ship came in at the port of destination (purchase_order.port_arrived_on), null while it is not in.
     */
    public record Move(LocalDate from, Instant at, LocalDate noticed, Instant noticedAt, LocalDate portArrivedOn) {}

    @Transactional
    public Map<Long, Move> all() {
        Map<Long, Move> moves = new HashMap<>();
        List<Object[]> rows = entities.createQuery("select o.id, o.arrivalMovedFrom, o.arrivalMovedAt, o.arrivalNoticeDate, o.arrivalNoticeAt, o.portArrivedOn from "
                + ORDER + " o where o.arrivalMovedFrom is not null", Object[].class).getResultList();
        for (Object[] row : rows) moves.put((Long) row[0], new Move((LocalDate) row[1], (Instant) row[2], (LocalDate) row[3], (Instant) row[4], (LocalDate) row[5]));
        return moves;
    }

    @Transactional
    public Move find(long orderId) {
        return entities.createQuery("select o.arrivalMovedFrom, o.arrivalMovedAt, o.arrivalNoticeDate, o.arrivalNoticeAt, o.portArrivedOn from " + ORDER
                        + " o where o.id = :id and o.arrivalMovedFrom is not null", Object[].class)
                .setParameter("id", orderId).getResultStream().findFirst()
                .map(row -> new Move((LocalDate) row[0], (Instant) row[1], (LocalDate) row[2], (Instant) row[3], (LocalDate) row[4])).orElse(null);
    }

    /** Signed days from the date that stood to the date now; null without a move, without a date or when equal. */
    static Integer days(Move move, LocalDate expectedArrival) {
        if (move == null || move.from() == null || expectedArrival == null || expectedArrival.equals(move.from())) return null;
        return Math.toIntExact(ChronoUnit.DAYS.between(move.from(), expectedArrival));
    }

    /** "3 dagen later", "1 dag eerder". */
    static String words(long days) {
        long count = Math.abs(days);
        return count + (count == 1 ? " dag " : " dagen ") + (days > 0 ? "later" : "eerder");
    }

    /**
     * The bell's one row for a moved arrival, or null. Its text is the arrival the order has now, against the planning
     * that stood ("Aankomst 15/11/2026 · 3 dagen later", "was 12/11/2026"), and it stands only while that lies two
     * days or more away, as the tint on the panel. Its identity and time belong to the date the bell last named
     * (arrival_notice_date, arrival_notice_at): a carrier's one-day correction changes the date in the title, not
     * the row, so a dismissed row stays dismissed (the frontend's key holds id and detail, not the title) and an old
     * row does not come back to the top with a new time. A move two days or more from the named date is a new row.
     *
     * <p>Once the linked ship is in and the order's day is that arrival, the day is no expectation any more: the row
     * reads "Schip aangekomen 15/11/2026 · 15 dagen eerder". While the order keeps another day (set by hand), the
     * row names that day and stays "Aankomst …": it must not say the ship came in on a day it did not.</p>
     */
    static NotificationService.Notification notice(PurchaseOrder order, Move move, Instant now) {
        Instant noticedAt = move == null ? null : move.noticedAt() != null ? move.noticedAt() : move.at();
        if (order == null || order.id() == null || move == null || move.from() == null || noticedAt == null || move.noticed() == null
                || move.noticed().equals(move.from()) || order.archivedAt() != null || order.receivedOn() != null
                || (order.status() != PurchaseOrderStatus.BESTELD && order.status() != PurchaseOrderStatus.ONDERWEG)
                || order.trackingReference() == null || order.trackingReference().isBlank() || order.expectedArrival() == null
                || noticedAt.isBefore(now.minus(NOTICE_LIFE))) return null;
        Integer days = days(move, order.expectedArrival());
        if (days == null || Math.abs(days) < NOTICE_DAYS) return null;
        String name = order.alias() == null || order.alias().isBlank() ? order.number() : order.alias();
        return new NotificationService.Notification(NotificationService.Kind.CONTAINER_AANKOMST, order.id(), order.number(), name,
                (order.expectedArrival().equals(move.portArrivedOn()) ? "Schip aangekomen " : "Aankomst ")
                        + order.expectedArrival().format(DAY) + " · " + words(days),
                "was " + move.from().format(DAY), false, noticedAt,
                "/purchasing/" + order.id() + "?tracking=1",
                ContainerTracking.hash("ARRIVAL_MOVED:" + order.id() + ":" + move.from() + ":" + move.noticed()));
    }
}
