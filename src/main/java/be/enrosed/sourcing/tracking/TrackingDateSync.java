package be.enrosed.sourcing.tracking;

import be.enrosed.shared.audit.ActivityChangeDto;
import be.enrosed.shared.audit.ActivityChangeSet;
import be.enrosed.shared.audit.ActivityLogService;
import be.enrosed.sourcing.adapter.out.persistence.SourcingEntities.PurchaseOrderEntity;
import be.enrosed.sourcing.application.port.out.SourcingRepositories;
import be.enrosed.sourcing.domain.PurchaseOrder;
import be.enrosed.sourcing.domain.PurchaseOrderStatus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.transaction.Transactional;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.regex.Pattern;
import static be.enrosed.sourcing.tracking.ContainerTracking.*;

/** Validated transport dates only. Never invokes purchase lifecycle, payments, receipt or inventory. */
@ApplicationScoped
public class TrackingDateSync {
    @Inject EntityManager entities;
    @Inject ActivityLogService activity;
    @Inject SourcingRepositories.PurchaseOrders orders;
    public record Plan(LocalDate shippedOn, LocalDate expectedArrival, String error) {}
    /**
     * The ship's arrival at the port of destination counts from this many days after the departure day (a call
     * there right after the stored departure is not this voyage's arrival) up to this many (as a voyage's dates).
     */
    static final int ARRIVAL_EARLIEST_DAYS = 7, ARRIVAL_LATEST_DAYS = 180;

    /**
     * The order as it is stored now, read in its own transaction. Outside a transaction a request (and a job
     * pass) keeps the order as it first read it, so a check that just wrote new dates was answered with the old
     * ones ("de opgeslagen datum is nog niet aangepast" right after it was) and the date lookup that follows a
     * ship check got a baseline from before that check.
     */
    @Transactional
    public Optional<PurchaseOrder> reread(long orderId) { return orders.findById(orderId); }

    @Transactional
    public String shipment(PurchaseOrder baseline, Observation observation, Instant fetchedAt, String source) {
        Plan plan = shipmentPlan(baseline, observation, fetchedAt);
        if (plan.shippedOn() == null && plan.expectedArrival() == null) return plan.error();
        PurchaseOrderEntity current = current(baseline);
        if (current == null) return "De container is tijdens de controle gewijzigd of al ontvangen. De handmatig opgeslagen planning blijft behouden.";
        VesselLinkEntity link = entities.find(VesselLinkEntity.class, baseline.id());
        String suppliedImo = observation.dateEvidence() == null ? null : observation.dateEvidence().vesselImo();
        if (link != null && Objects.equals(link.referenceHash, VesselLinkStore.binding(current.trackingReference))
                && suppliedImo != null && !Objects.equals(link.imo, suppliedImo))
            return "Het schip van de datumbron komt niet overeen met het gekoppelde schip. De opgeslagen planning blijft behouden.";
        apply(current, plan, source);
        return plan.error();
    }

    /**
     * What the linked ship's port calls say about this container: the day it left the port of departure, and the
     * day it came in at the port of destination. The arrival is a fact about the ship the user linked ("schip
     * aangekomen"), never "container gelost". Both are looked for on every pass, so an arrival day an older screen
     * put back is repaired at the next one.
     */
    @Transactional
    public void vessel(PurchaseOrder baseline, VesselLinkStore.Link expectedLink, Observation observation, Instant sourceCheckedAt, Instant now) {
        LocalDate departed = vesselDeparture(baseline, expectedLink, observation, sourceCheckedAt, now);
        PortCall arrived = vesselArrival(baseline, expectedLink, observation, sourceCheckedAt, now);
        if (departed == null && arrived == null) return;
        PurchaseOrderEntity current = current(baseline);
        if (current == null) return;
        VesselLinkEntity link = entities.find(VesselLinkEntity.class, baseline.id());
        if (link == null || !Objects.equals(link.referenceHash, expectedLink.referenceHash())
                || !Objects.equals(link.imo, expectedLink.imo()) || !Objects.equals(link.linkedAt, expectedLink.linkedAt())) return;
        if (departed != null) apply(current, new Plan(departed, null, null), "Voydar · werkelijk vertrek uit vertrekhaven");
        if (arrived == null) return;
        if (VesselDirectory.differs(current.billVessel, observation.vessel())) {
            /* The final bill of lading names another ship than this feed (a mistyped IMO, a ship linked for a later
               leg): its call must not write "schip aangekomen" on this container, and an arrival of that ship
               written before the bill was taken over goes too. */
            List<ActivityChangeDto> changes = forgetPortArrival(current);
            if (!changes.isEmpty())
                activity.record(ActivityLogService.ACTION_UPDATED, ActivityLogService.ENTITY_PURCHASE_ORDER,
                        Long.toString(current.id), current.number, "Scheepsaankomst gewist: de B/L noemt een ander schip", changes);
            return;
        }
        /* By hand the hand date stays and only the fact is stored. */
        LocalDate arrival = Boolean.TRUE.equals(current.arrivalByHand) ? current.expectedArrival : arrived.day();
        write(current, current.shippedOn, arrival, arrived.day(), "Voydar · schip aangekomen in " + arrived.place());
    }

    /**
     * "Volg track &amp; trace" gave the container's day back while the ship is in: the day is the ship's arrival at
     * once, not at the next check. True when the order has such an arrival; no source's planning is applied then.
     */
    @Transactional
    public boolean adoptPortArrival(long orderId) {
        PurchaseOrderEntity current = entities.find(PurchaseOrderEntity.class, orderId, LockModeType.PESSIMISTIC_WRITE);
        if (current == null || current.deletedAt != null || current.portArrivedOn == null) return false;
        if (!Boolean.TRUE.equals(current.arrivalByHand))
            write(current, current.shippedOn, current.portArrivedOn, current.portArrivedOn, "de aankomst van het schip");
        return true;
    }

    private PurchaseOrderEntity current(PurchaseOrder baseline) {
        PurchaseOrderEntity current = entities.find(PurchaseOrderEntity.class, baseline.id(), LockModeType.PESSIMISTIC_WRITE);
        if (current == null || current.deletedAt != null || current.archivedAt != null
                || current.receivedOn != null || current.status == PurchaseOrderStatus.ONTVANGEN || current.status == PurchaseOrderStatus.CONCEPT) return null;
        // Network work used this exact header. A concurrent manual edit wins and is reconsidered next check.
        return Objects.equals(current.trackingReference, baseline.trackingReference())
                && Objects.equals(current.notes, baseline.notes()) && Objects.equals(recordedPort(current.departurePort), baseline.recordedDeparturePort())
                && Objects.equals(recordedPort(current.destinationPort), baseline.recordedDestinationPort()) && current.status == baseline.status()
                && Objects.equals(current.orderDate, baseline.orderDate())
                && Objects.equals(current.shippedOn, baseline.shippedOn()) && Objects.equals(current.expectedArrival, baseline.expectedArrival())
                ? current : null;
    }

    private static String recordedPort(String port) { return port == null || port.isBlank() ? null : port.strip(); }

    private void apply(PurchaseOrderEntity current, Plan plan, String source) {
        LocalDate departure = plan.shippedOn() == null ? current.shippedOn : plan.shippedOn();
        /* An arrival set by hand stays until "Volg track & trace" (ArrivalPlanStore); the departure still follows.
           So does the day the ship came in: an arrival that happened beats a source's planning. */
        boolean kept = Boolean.TRUE.equals(current.arrivalByHand) || current.portArrivedOn != null;
        LocalDate arrival = kept || plan.expectedArrival() == null ? current.expectedArrival : plan.expectedArrival();
        write(current, departure, arrival, current.portArrivedOn, source);
    }

    /** The one place tracking writes the order's transport dates: one activity record per source, none without a change. */
    private void write(PurchaseOrderEntity current, LocalDate departure, LocalDate arrival, LocalDate portArrived, String source) {
        var changes = ActivityChangeSet.create()
                .add("shippedOn", "Vertrekdatum", current.shippedOn, departure)
                .add("portArrivedOn", "Schip aangekomen", current.portArrivedOn, portArrived)
                .add("expectedArrival", "Verwachte aankomst", current.expectedArrival, arrival).build();
        if (changes.isEmpty()) return;
        if (!Objects.equals(current.expectedArrival, arrival)) {
            noteArrivalMove(current, arrival, Instant.now());
            /* Sources name a day; an hour typed for the day before does not move along with it. */
            current.expectedArrivalTime = null;
        }
        current.shippedOn = departure;
        current.portArrivedOn = portArrived;
        current.expectedArrival = arrival;
        activity.record(ActivityLogService.ACTION_UPDATED, ActivityLogService.ENTITY_PURCHASE_ORDER,
                Long.toString(current.id), current.number, "Transportdatums bijgewerkt uit " + source, changes);
    }

    /**
     * The arrival tracking moved, kept with the order. The first date tracking replaces stays "was" through later
     * moves, so the difference is always against the planning that stood; back on that date means not moved, and
     * a first date is not a move. The bell names a date only when it lies two days or more from the date it named
     * last (from the planning date the first time), so a carrier's one-day corrections stay quiet; arrival_notice_at
     * keeps when it did, so a quiet correction does not make the bell row look new. A date saved through the order
     * clears all four columns (PanacheSourcingRepositories.save).
     */
    static void noteArrivalMove(PurchaseOrderEntity order, LocalDate arrival, Instant now) {
        if (order.expectedArrival == null || arrival == null) return;
        LocalDate was = order.arrivalMovedFrom != null ? order.arrivalMovedFrom : order.expectedArrival;
        if (arrival.equals(was)) {
            order.arrivalMovedFrom = null; order.arrivalMovedAt = null; order.arrivalNoticeDate = null; order.arrivalNoticeAt = null;
            return;
        }
        order.arrivalMovedFrom = was;
        order.arrivalMovedAt = now;
        LocalDate named = order.arrivalNoticeDate != null ? order.arrivalNoticeDate : was;
        if (Math.abs(ChronoUnit.DAYS.between(named, arrival)) >= ArrivalMoveStore.NOTICE_DAYS) {
            order.arrivalNoticeDate = arrival;
            order.arrivalNoticeAt = now;
        }
    }

    /**
     * The ship's arrival is no longer this container's: another ship was linked, the ship was unlinked, or the
     * reference now names another shipment. The fact goes, and with it the day it put on the order: when the
     * expected arrival still is that day and was not set by hand, it goes back to the planning that stood before
     * tracking moved it (none when there was none), and that is the planning again, not a move. A day by hand, or
     * any other day, stays. Returns what changed for the activity log; empty when the order had no such arrival.
     */
    static List<ActivityChangeDto> forgetPortArrival(PurchaseOrderEntity order) {
        LocalDate arrived = order.portArrivedOn;
        if (arrived == null) return List.of();
        LocalDate expected = order.expectedArrival;
        order.portArrivedOn = null;
        if (arrived.equals(expected) && !Boolean.TRUE.equals(order.arrivalByHand)) {
            order.expectedArrival = order.arrivalMovedFrom;
            order.expectedArrivalTime = null;
            order.arrivalMovedFrom = null; order.arrivalMovedAt = null; order.arrivalNoticeDate = null; order.arrivalNoticeAt = null;
        }
        return ActivityChangeSet.create()
                .add("portArrivedOn", "Schip aangekomen", arrived, null)
                .add("expectedArrival", "Verwachte aankomst", expected, order.expectedArrival).build();
    }

    public static Plan shipmentPlan(PurchaseOrder order, Observation data, Instant now) {
        DateEvidence dates = data == null ? null : data.dateEvidence();
        if (dates == null) return rejected("De bron levert geen bevestigde transportdatums voor deze zending.");
        if (!fresh(data.providerCheckedAt(), now)) return rejected("De datumbron is ouder dan 24 uur of vermeldt geen geldige controletijd. De opgeslagen planning blijft behouden.");
        if (!sameShipment(order, dates)) return rejected("De datumbron hoort niet bij het opgeslagen container- of B/L-nummer.");
        if (!samePort(order.departurePort(), dates.originCode(), dates.originName())
                || !samePort(order.destinationPort(), dates.destinationCode(), dates.destinationName()))
            return rejected("De havens van de datumbron komen niet overeen met de route van deze container.");
        OffsetDateTime departedAt = offset(dates.actualDepartureAt());
        LocalDate departure = departedAt == null ? null : departedAt.toLocalDate();
        LocalDate start = date(dates.voyageStartedOn());
        if (start == null) start = departure;
        // "Today" is the Brussels day everywhere in tracking (as the arrival reminders count it), not the UTC day.
        LocalDate today = now.atZone(ContainerArrivalReminders.ZONE).toLocalDate();
        if (!voyageDate(order, start, today))
            return rejected("De datumbron kan niet aan de huidige reis worden gekoppeld. De opgeslagen planning blijft behouden.");
        if (departedAt != null && (departedAt.toInstant().isAfter(now.plusSeconds(300))
                || Math.abs(ChronoUnit.DAYS.between(start, departure)) > 3)) departure = null;
        LocalDate arrival = date(dates.expectedArrivalDate());
        if (arrival != null && (arrival.isBefore(today.minusDays(1)) || arrival.isBefore(start) || arrival.isAfter(start.plusDays(180))))
            arrival = null;
        return new Plan(departure, arrival, arrival == null
                ? "De bron levert geen bruikbare actuele aankomstdatum voor deze bestemming. De opgeslagen aankomst blijft behouden." : null);
    }

    static LocalDate vesselDeparture(PurchaseOrder order, VesselLinkStore.Link link, Observation data, Instant checkedAt, Instant now) {
        if (link == null || data == null || !fresh(checkedAt, now) || VesselLinkStore.matching(order, link) == null) return null;
        List<LocalDate> candidates = new ArrayList<>();
        for (Event event : data.events()) {
            if (!event.actual() || !"DEPARTURE".equals(event.kind())
                    || !samePort(order.departurePort(), event.locationCode(), event.location())) continue;
            OffsetDateTime at = offset(event.occurredAt());
            ZoneId zone = portZone(event.locationCode());
            if (at == null || zone == null || at.toInstant().isAfter(now.plusSeconds(300))) continue;
            LocalDate candidate = at.atZoneSameInstant(zone).toLocalDate();
            if (order.shippedOn() == null && at.toInstant().isBefore(link.linkedAt())) continue;
            if (voyageDate(order, candidate, now.atZone(zone).toLocalDate())) candidates.add(candidate);
        }
        // Several different departure days could be two voyages. Do not choose a plausible one silently.
        List<LocalDate> unique = candidates.stream().distinct().toList();
        return unique.size() == 1 ? unique.getFirst() : null;
    }

    /** A call of the linked ship at the order's port of destination: the day there and the place as the feed names it. */
    record PortCall(LocalDate day, String place) {}

    /**
     * The day the linked ship came in at the order's port of destination, or null. Only for a container that is
     * under way with a departure day, and only a call that can be this voyage's: the ship's earlier rounds and a
     * call at the destination right after the stored departure are not. The earliest such day wins: the ship
     * calls again on later rounds, the container came off at the first.
     */
    static PortCall vesselArrival(PurchaseOrder order, VesselLinkStore.Link link, Observation data, Instant checkedAt, Instant now) {
        if (link == null || data == null || !fresh(checkedAt, now) || VesselLinkStore.matching(order, link) == null) return null;
        if (order.status() != PurchaseOrderStatus.ONDERWEG || order.shippedOn() == null) return null;
        LocalDate from = order.shippedOn().plusDays(ARRIVAL_EARLIEST_DAYS), until = order.shippedOn().plusDays(ARRIVAL_LATEST_DAYS);
        PortCall earliest = null;
        Instant earliestAt = null;
        for (Event event : data.events()) {
            if (!event.actual() || !"ARRIVAL".equals(event.kind())
                    || !PortAreas.same(order.destinationPort(), event.locationCode(), event.location())) continue;
            OffsetDateTime at = offset(event.occurredAt());
            ZoneId zone = portZone(event.locationCode());
            if (at == null || zone == null || at.toInstant().isAfter(now.plusSeconds(300))) continue;
            LocalDate day = at.atZoneSameInstant(zone).toLocalDate();
            if (day.isBefore(from) || day.isAfter(until)) continue;
            if (earliest != null && (day.isAfter(earliest.day()) || (day.equals(earliest.day()) && !at.toInstant().isBefore(earliestAt)))) continue;
            String place = event.location() == null || event.location().isBlank() ? order.destinationPort() : event.location().strip();
            earliest = new PortCall(day, place);
            earliestAt = at.toInstant();
        }
        return earliest;
    }

    private static boolean voyageDate(PurchaseOrder order, LocalDate start, LocalDate today) {
        if (start == null || start.isAfter(today) || start.isBefore(today.minusDays(180))) return false;
        if (order.shippedOn() != null) return Math.abs(ChronoUnit.DAYS.between(order.shippedOn(), start)) <= 3;
        return order.orderDate() != null && !start.isBefore(order.orderDate());
    }
    static boolean fresh(Instant checkedAt, Instant now) {
        return checkedAt != null && !checkedAt.isBefore(now.minus(Duration.ofHours(24))) && !checkedAt.isAfter(now.plusSeconds(300));
    }
    /**
     * The source's shipment is the one in the reference. Everything the rule of 2026-10-26 accepted is still
     * accepted: first the Evergreen bill the date lookup itself read from the text, then that rule unchanged,
     * and only last the wider reading of 2026-11-10.
     */
    static boolean sameShipment(PurchaseOrder order, DateEvidence dates) {
        Recognised seen = recognise(order.trackingReference());
        if (seen.evergreenBl() != null && seen.evergreenBl().equalsIgnoreCase(Objects.toString(dates.bookingNumber(), ""))) {
            String box = "CONTAINER".equals(seen.kind()) ? seen.number() : seen.container();
            if (box == null || dates.containerNumber() == null || box.equalsIgnoreCase(dates.containerNumber())) return true;
        }
        Reference parsed = strictReference(order.trackingReference());
        if (parsed != null) return sameNumber(parsed, dates);
        String declared = Objects.toString(order.trackingReference(), "") + " " + Objects.toString(order.notes(), "");
        if (containsIdentifier(declared, dates.bookingNumber()) && (dates.containerNumber() == null || containsIdentifier(declared, dates.containerNumber()))) return true;
        Reference wider = reference(order.trackingReference());
        return wider != null && sameNumber(wider, dates);
    }
    private static boolean sameNumber(Reference parsed, DateEvidence dates) {
        return parsed.number().equalsIgnoreCase(parsed.container() ? Objects.toString(dates.containerNumber(), "") : Objects.toString(dates.bookingNumber(), ""));
    }
    private static boolean containsIdentifier(String declared, String identifier) {
        return identifier != null && !identifier.isBlank() && Pattern.compile("(?i)(?<![A-Z0-9])" + Pattern.quote(identifier) + "(?![A-Z0-9])").matcher(declared).find();
    }
    /** The order's port and a source's are the same place: a port and its terminals are one ({@link PortAreas}). */
    public static boolean samePort(String configured, String code, String name) {
        return PortAreas.same(configured, code, name);
    }
    private static ZoneId portZone(String code) {
        if (code == null || code.length() != 5) return null;
        return switch (code.substring(0, 2).toUpperCase(Locale.ROOT)) {
            case "CN" -> ZoneId.of("Asia/Shanghai"); case "SG" -> ZoneId.of("Asia/Singapore");
            case "NL" -> ZoneId.of("Europe/Amsterdam"); case "BE" -> ZoneId.of("Europe/Brussels");
            case "DE" -> ZoneId.of("Europe/Berlin"); case "GB" -> ZoneId.of("Europe/London");
            case "FR" -> ZoneId.of("Europe/Paris"); default -> null;
        };
    }
    private static LocalDate date(String value) { try { return value == null ? null : LocalDate.parse(value); } catch (DateTimeException ignored) { return null; } }
    private static OffsetDateTime offset(String value) { try { return value == null ? null : OffsetDateTime.parse(value); } catch (DateTimeException ignored) { return null; } }
    private static Plan rejected(String reason) { return new Plan(null, null, reason); }
}
