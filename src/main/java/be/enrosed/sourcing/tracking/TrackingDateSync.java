package be.enrosed.sourcing.tracking;

import be.enrosed.shared.audit.ActivityChangeSet;
import be.enrosed.shared.audit.ActivityLogService;
import be.enrosed.sourcing.adapter.out.persistence.SourcingEntities.PurchaseOrderEntity;
import be.enrosed.sourcing.domain.PurchaseOrder;
import be.enrosed.sourcing.domain.PurchaseOrderStatus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.transaction.Transactional;
import java.text.Normalizer;
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
    public record Plan(LocalDate shippedOn, LocalDate expectedArrival, String error) {}

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

    @Transactional
    public void vessel(PurchaseOrder baseline, VesselLinkStore.Link expectedLink, Observation observation, Instant sourceCheckedAt, Instant now) {
        LocalDate departed = vesselDeparture(baseline, expectedLink, observation, sourceCheckedAt, now);
        if (departed == null) return;
        PurchaseOrderEntity current = current(baseline);
        if (current == null) return;
        VesselLinkEntity link = entities.find(VesselLinkEntity.class, baseline.id());
        if (link == null || !Objects.equals(link.referenceHash, expectedLink.referenceHash())
                || !Objects.equals(link.imo, expectedLink.imo()) || !Objects.equals(link.linkedAt, expectedLink.linkedAt())) return;
        apply(current, new Plan(departed, null, null), "Voydar · werkelijk vertrek uit vertrekhaven");
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
        LocalDate arrival = plan.expectedArrival() == null ? current.expectedArrival : plan.expectedArrival();
        var changes = ActivityChangeSet.create()
                .add("shippedOn", "Vertrekdatum", current.shippedOn, departure)
                .add("expectedArrival", "Verwachte aankomst", current.expectedArrival, arrival).build();
        if (changes.isEmpty()) return;
        current.shippedOn = departure;
        current.expectedArrival = arrival;
        activity.record(ActivityLogService.ACTION_UPDATED, ActivityLogService.ENTITY_PURCHASE_ORDER,
                Long.toString(current.id), current.number, "Transportdatums bijgewerkt uit " + source, changes);
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
        if (!voyageDate(order, start, now.atZone(ZoneOffset.UTC).toLocalDate()))
            return rejected("De datumbron kan niet aan de huidige reis worden gekoppeld. De opgeslagen planning blijft behouden.");
        if (departedAt != null && (departedAt.toInstant().isAfter(now.plusSeconds(300))
                || Math.abs(ChronoUnit.DAYS.between(start, departure)) > 3)) departure = null;
        LocalDate arrival = date(dates.expectedArrivalDate());
        LocalDate today = now.atZone(ZoneOffset.UTC).toLocalDate();
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

    private static boolean voyageDate(PurchaseOrder order, LocalDate start, LocalDate today) {
        if (start == null || start.isAfter(today) || start.isBefore(today.minusDays(180))) return false;
        if (order.shippedOn() != null) return Math.abs(ChronoUnit.DAYS.between(order.shippedOn(), start)) <= 3;
        return order.orderDate() != null && !start.isBefore(order.orderDate());
    }
    static boolean fresh(Instant checkedAt, Instant now) {
        return checkedAt != null && !checkedAt.isBefore(now.minus(Duration.ofHours(24))) && !checkedAt.isAfter(now.plusSeconds(300));
    }
    private static boolean sameShipment(PurchaseOrder order, DateEvidence dates) {
        Reference parsed = reference(order.trackingReference());
        if (parsed != null) return parsed.number().equalsIgnoreCase(parsed.container() ? Objects.toString(dates.containerNumber(), "") : Objects.toString(dates.bookingNumber(), ""));
        String declared = Objects.toString(order.trackingReference(), "") + " " + Objects.toString(order.notes(), "");
        return containsIdentifier(declared, dates.bookingNumber()) && (dates.containerNumber() == null || containsIdentifier(declared, dates.containerNumber()));
    }
    private static boolean containsIdentifier(String declared, String identifier) {
        return identifier != null && !identifier.isBlank() && Pattern.compile("(?i)(?<![A-Z0-9])" + Pattern.quote(identifier) + "(?![A-Z0-9])").matcher(declared).find();
    }
    public static boolean samePort(String configured, String code, String name) {
        String expected = portName(configured);
        return !expected.isBlank() && (expected.equals(portName(name)) || expected.equals(portName(code)));
    }
    private static String portName(String value) {
        if (value == null) return "";
        String result = value.strip().split(",", 2)[0].replaceFirst("\\s*\\([A-Za-z]{2}\\)\\s*$", "");
        result = Normalizer.normalize(result, Normalizer.Form.NFKD).replaceAll("\\p{M}", "")
                .toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
        return switch (result) { case "ANTWERPEN" -> "ANTWERP"; case "NINGBOZHOUSHAN" -> "NINGBO"; default -> result; };
    }
    private static ZoneId portZone(String code) {
        if (code == null || code.length() != 5) return null;
        return switch (code.substring(0, 2).toUpperCase(Locale.ROOT)) {
            case "CN" -> ZoneId.of("Asia/Shanghai"); case "SG" -> ZoneId.of("Asia/Singapore");
            case "NL" -> ZoneId.of("Europe/Amsterdam"); case "BE" -> ZoneId.of("Europe/Brussels");
            case "DE" -> ZoneId.of("Europe/Berlin"); case "GB" -> ZoneId.of("Europe/London"); default -> null;
        };
    }
    private static LocalDate date(String value) { try { return value == null ? null : LocalDate.parse(value); } catch (DateTimeException ignored) { return null; } }
    private static OffsetDateTime offset(String value) { try { return value == null ? null : OffsetDateTime.parse(value); } catch (DateTimeException ignored) { return null; } }
    private static Plan rejected(String reason) { return new Plan(null, null, reason); }
}
