package be.enrosed.sourcing.tracking;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Provider-neutral, stored tracking observations. Fetch time is never a position observation time. */
public final class ContainerTracking {
    private ContainerTracking() {}
    public static final int INTERVAL_HOURS = 6;
    private static final Pattern CONTAINER = Pattern.compile("(?<![A-Z0-9])([A-Z]{4})[ -]?([0-9]{7})(?![A-Z0-9])");

    public record Reference(String key, String number, boolean container) {}
    public record Point(double lat, double lon) {}
    public record Position(double lat, double lon, String kind, String recordedAt) {}
    public record Event(String id, String status, String location, String occurredAt, boolean actual, String source, String sourceUrl) {
        public Event(String id, String status, String location, String occurredAt, boolean actual) {
            this(id, status, location, occurredAt, actual, null, null);
        }
    }
    public record Observation(String status, String carrier, String vessel, String eta, String location,
                              Position position, List<Point> route, List<Event> events,
                              Instant providerCheckedAt, Instant providerUpdatedAt) {}
    public record Row(long orderId, String orderNumber, String containerName, String trackingReference,
                      String orderStatus, String status, String statusLabel, String carrier, String vessel,
                      String eta, String location, Position position, List<Point> route, List<Event> events,
                      Instant checkedAt, Instant updatedAt, Instant nextCheckAt, Instant changedAt,
                      String error, String trackingUrl, Instant providerCheckedAt, Instant providerUpdatedAt,
                      String source, String vesselImo, boolean automatic) {}
    public record Feed(String provider, boolean configured, int intervalHours, List<Row> containers, boolean shipsGoConfigured) {}

    /** Recognise a number inside existing carrier links, but never fetch those user-controlled URLs. */
    public static Reference reference(String raw) {
        if (raw == null || raw.isBlank() || raw.length() > 500) return null;
        String value = raw.strip().toUpperCase(Locale.ROOT);
        // A forwarder's internal reference can look just like a master B/L. Explicit type avoids buying
        // tracking credits for arbitrary ERP text. Existing text is preserved and shown for verification.
        if (value.startsWith("BL:") || value.startsWith("BOOKING:")) {
            String number = value.substring(value.indexOf(':') + 1).strip();
            if (number.matches("[A-Z0-9/-]{6,60}")) return referenceOf(number, false);
            return null;
        }
        var matcher = CONTAINER.matcher(value);
        if (matcher.find()) {
            String number = matcher.group(1) + matcher.group(2);
            while (matcher.find()) if (!number.equals(matcher.group(1) + matcher.group(2))) return null;
            return referenceOf(number, true);
        }
        return null;
    }

    private static Reference referenceOf(String number, boolean container) {
        return new Reference(hash((container ? "CONTAINER:" : "BOOKING:") + number), number, container);
    }

    static String hash(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    static String sourceLink(String raw) {
        try {
            if (raw == null) return "https://www.track-trace.com/container";
            URI uri = URI.create(raw.strip());
            if ("https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null && uri.getUserInfo() == null)
                return uri.toASCIIString();
        } catch (IllegalArgumentException ignored) { }
        return "https://www.track-trace.com/container";
    }

    static boolean terminal(String status) { return "EMPTY_RETURN".equals(status); }

    public static String label(String status) {
        return switch (status == null ? "" : status) {
            case "UNCONFIGURED" -> "Trackingdienst nog niet gekoppeld";
            case "UNSUPPORTED" -> "Trackingreferentie aanvullen";
            case "VESSEL_TRACKING" -> "Schip gekoppeld";
            case "PENDING", "NEW" -> "Eerste controle afwachten";
            case "INPROGRESS" -> "Wachten op de rederij";
            case "BOOKED" -> "Geboekt";
            case "EMPTY_SHIPPER" -> "Lege container bij verzender";
            case "GATE_IN" -> "In de vertrekhaven";
            case "LOADED" -> "Geladen op het schip";
            case "SAILING" -> "Onderweg op zee";
            case "ARRIVED" -> "Schip aangekomen";
            case "DISCHARGED" -> "Gelost in aankomsthaven";
            case "GATE_OUT" -> "Vertrokken uit aankomsthaven";
            case "EMPTY_RETURN" -> "Lege container ingeleverd";
            case "UNTRACKED" -> "Geen tracking gevonden";
            case "ERROR" -> "Controle niet gelukt";
            default -> "Status nog niet bekend";
        };
    }
}
