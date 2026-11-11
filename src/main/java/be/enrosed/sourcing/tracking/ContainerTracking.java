package be.enrosed.sourcing.tracking;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Provider-neutral, stored tracking observations. Fetch time is never a position observation time. */
public final class ContainerTracking {
    private ContainerTracking() {}
    public static final int INTERVAL_HOURS = 6;
    /** A manual check waits this long after the last attempt: the paid provider a minute, the free sources fifteen. */
    public static final int MANUAL_SECONDS_PAID = 60, MANUAL_SECONDS_FREE = 900;
    private static final Pattern CONTAINER = Pattern.compile("(?<![A-Z0-9])([A-Z]{4})[ -]?([0-9]{7})(?![A-Z0-9])");
    /* The rule of 2026-11-10 (docs/integrations/container-tracking.md, "What the field reads"). The frontend holds
       the same rule in features/purchasing/tracking-reference.ts: change both or neither. It works on normalised
       text, where a plain space is the only whitespace. */
    /** ISO 6346 prints the check digit apart ("TCNU 123456 5"); that form is a container number too. */
    private static final Pattern BOX = Pattern.compile("(?<![A-Z0-9])([A-Z]{4})[ -]?([0-9]{6})[ -]?([0-9])(?![A-Z0-9])");
    private static final Pattern BILL = Pattern.compile("(?<![A-Z0-9])EGLV *([0-9]{12})(?![A-Z0-9])");
    private static final Pattern LEADING_BILL = Pattern.compile("^EGLV *([0-9]{12})(?![A-Z0-9])");
    private static final Pattern LOOSE = Pattern.compile("^(B/L|BL|BOOKING|BOEKING)( *: *| +)");
    private static final Pattern NUMBER = Pattern.compile("[A-Z0-9/-]{6,60}");
    private static final Pattern LINK = Pattern.compile("HTTPS?://[^ ]+");
    private static final Pattern SPACE = Pattern.compile("[\\t\\n\\u000B\\f\\r\\u001C-\\u001F \\u0085\\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000]");
    private static final Pattern UNSEEN = Pattern.compile("[\\u200B\\uFEFF]");
    private static final Pattern EDGE_SPACES = Pattern.compile("^ +| +$");

    public record Reference(String key, String number, boolean container) {}
    /**
     * What the Track & trace text was read as. kind: EMPTY, CONTAINER, BL, BOOKING, MULTIPLE (several container
     * numbers), LINK (a web link without a number) or UNKNOWN. number is what the tracker follows. container is
     * the container number behind a B/L or booking number. checkDigitOk is the ISO 6346 check of the container in
     * this result, null without one. evergreenBl is the Evergreen bill the date lookup uses, null without one.
     */
    public record Recognised(String kind, String number, Boolean checkDigitOk, String container, String evergreenBl) {}
    public record Point(double lat, double lon) {}
    public record Position(double lat, double lon, String kind, String recordedAt) {}
    public record Event(String id, String status, String location, String occurredAt, boolean actual, String source, String sourceUrl,
                        String kind, String locationCode) {
        public Event(String id, String status, String location, String occurredAt, boolean actual) {
            this(id, status, location, occurredAt, actual, null, null, null, null);
        }
        public Event(String id, String status, String location, String occurredAt, boolean actual, String source, String sourceUrl) {
            this(id, status, location, occurredAt, actual, source, sourceUrl, null, null);
        }
    }
    /** Structured carrier evidence for column-only date synchronisation; missing evidence means keep the order. */
    public record DateEvidence(String originCode, String originName, String destinationCode, String destinationName,
                               String actualDepartureAt, String expectedArrivalDate, String vesselImo, String voyage,
                               String voyageStartedOn, String containerNumber, String bookingNumber) {}
    public record Observation(String status, String carrier, String vessel, String eta, String location,
                              Position position, List<Point> route, List<Event> events,
                              Instant providerCheckedAt, Instant providerUpdatedAt, DateEvidence dateEvidence) {
        public Observation(String status, String carrier, String vessel, String eta, String location,
                           Position position, List<Point> route, List<Event> events, Instant providerCheckedAt, Instant providerUpdatedAt) {
            this(status, carrier, vessel, eta, location, position, route, events, providerCheckedAt, providerUpdatedAt, null);
        }
    }
    public record Row(long orderId, String orderNumber, String containerName, String trackingReference,
                      String orderStatus, String status, String statusLabel, String carrier, String vessel,
                      String eta, String location, Position position, List<Point> route, List<Event> events,
                      Instant checkedAt, Instant updatedAt, Instant nextCheckAt, Instant changedAt,
                      String error, String trackingUrl, Instant providerCheckedAt, Instant providerUpdatedAt,
                      String source, String vesselImo, boolean automatic,
                      java.time.LocalDate orderExpectedArrival, java.time.LocalDate orderShippedOn,
                      String dateSource, Instant dateSourceCheckedAt, String dateError,
                      /* Since 2026-11-10; every one is null when it does not apply. */
                      Recognised recognised,
                      /** The last attempt failed: its message and when. A date that could not be applied is dateError.
                          failureDataAt: the failing source's last good answer (what is kept is from then), null without one. */
                      String failure, Instant failureAt, Instant failureDataAt,
                      /** Tracking moved the expected arrival: the date before, when, and the signed days to the date now. */
                      java.time.LocalDate arrivalMovedFrom, Instant arrivalMovedAt, Integer arrivalMovedDays,
                      /** From when a manual check does something again; null = now, or nothing to check. */
                      Instant manualCheckAt,
                      /** Only on the answer of a manual check: CHECKED, COOLDOWN, BUSY, OFF or NO_SOURCE. */
                      String refreshOutcome) {}
    public record Feed(String provider, boolean configured, int intervalHours, List<Row> containers, boolean shipsGoConfigured) {}

    /**
     * The reference the tracker follows. A text the parser of 2026-10-26 accepts keeps its key, so no stored
     * snapshot, provider registration or ship link moves; other texts are read by {@link #recognise(String)}.
     */
    public static Reference reference(String raw) {
        Reference strict = strictReference(raw);
        if (strict != null) return strict;
        Recognised seen = recognise(raw);
        return switch (seen.kind()) {
            case "CONTAINER" -> referenceOf(seen.number(), true);
            case "BL", "BOOKING" -> referenceOf(seen.number(), false);
            default -> null;
        };
    }

    /**
     * The parser of 2026-10-26, kept as it was so reminder identities and stored keys never move.
     * Recognise a number inside existing carrier links, but never fetch those user-controlled URLs.
     */
    static Reference strictReference(String raw) {
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

    /** Pasted text brings non-breaking and zero-width characters; after this a plain space is the only whitespace. */
    static String normalise(String raw) {
        if (raw == null) return "";
        String spaced = UNSEEN.matcher(SPACE.matcher(raw).replaceAll(" ")).replaceAll("");
        return EDGE_SPACES.matcher(spaced).replaceAll("");
    }

    /**
     * What the field holds, by one written rule. It never guesses: a bare number is only read as an Evergreen bill.
     * A text the parser of 2026-10-26 accepts reads as what that parser follows ({@link #reference(String)} asks it
     * first), so the line on the screen names the number the tracker follows.
     */
    public static Recognised recognise(String raw) {
        Recognised wide = wider(raw);
        Reference strict = strictReference(raw);
        if (strict == null) return wide;
        String kind = strict.container() ? "CONTAINER" : raw.strip().toUpperCase(Locale.ROOT).startsWith("BOOKING:") ? "BOOKING" : "BL";
        if (kind.equals(wide.kind()) && strict.number().equals(wide.number())) return wide;
        return new Recognised(kind, strict.number(), strict.container() ? BexyClient.validBox(strict.number()) : null, null, wide.evergreenBl());
    }

    /** The rule of 2026-11-10 on its own, for texts the parser of 2026-10-26 does not take. */
    private static Recognised wider(String raw) {
        String value = normalise(raw);
        if (value.isEmpty()) return new Recognised("EMPTY", null, null, null, null);
        if (raw.length() > 500) return new Recognised("UNKNOWN", null, null, null, null);
        value = value.toUpperCase(Locale.ROOT);
        // The date lookup's own judgement, never a second copy of its rule.
        BexyClient.Query lookup = BexyClient.query(raw);
        String evergreen = lookup == null ? null : lookup.bl();
        boolean booking = value.startsWith("BOOKING:");
        if (booking || value.startsWith("BL:")) {
            String[] typed = typedNumber(trim(value.substring(value.indexOf(':') + 1)), false);
            // An Evergreen bill somewhere behind the prefix is still looked up for dates: say so.
            List<String> found = boxes(typed == null ? value : typed[1]);
            if (typed == null && evergreen == null) return new Recognised("UNKNOWN", null, null, null, null);
            if (found.size() > 1) return new Recognised("MULTIPLE", null, null, null, evergreen);
            String box = found.isEmpty() ? null : found.get(0);
            return new Recognised(typed != null && booking ? "BOOKING" : "BL", typed == null ? evergreen : typed[0],
                    box == null ? null : BexyClient.validBox(box), box, evergreen);
        }
        List<String> found = boxes(value);
        if (found.size() == 1) return new Recognised("CONTAINER", found.get(0), BexyClient.validBox(found.get(0)), null, evergreen);
        if (found.size() > 1) return new Recognised("MULTIPLE", null, null, null, evergreen);
        Matcher loose = LOOSE.matcher(value);
        if (loose.lookingAt()) {
            String[] typed = typedNumber(value.substring(loose.end()), true);
            // Without a colon the word must look like a number of a carrier: "BL 10/11/2026" is a date, not a bill.
            boolean named = loose.group(2).indexOf(':') >= 0;
            if (typed != null && (named || (typed[0].length() >= 8 && typed[0].chars().anyMatch(ch -> ch >= 'A' && ch <= 'Z'))))
                return new Recognised(loose.group(1).startsWith("BO") ? "BOOKING" : "BL", typed[0], null, null, evergreen);
        }
        List<String> bills = distinct(BILL.matcher(value), true);
        if (bills.size() == 1) return new Recognised("BL", bills.get(0), null, null, evergreen);
        return new Recognised(LINK.matcher(value).matches() ? "LINK" : "UNKNOWN", null, null, null, evergreen);
    }

    private static String trim(String text) { return EDGE_SPACES.matcher(text).replaceAll(""); }
    private static boolean hasDigit(String text) { return text.chars().anyMatch(ch -> ch >= '0' && ch <= '9'); }
    private static List<String> boxes(String text) { return distinct(BOX.matcher(text), false); }
    private static List<String> distinct(Matcher matcher, boolean bill) {
        LinkedHashSet<String> found = new LinkedHashSet<>();
        while (matcher.find()) found.add(bill ? "EGLV" + matcher.group(1) : matcher.group(1) + matcher.group(2) + matcher.group(3));
        return new ArrayList<>(found);
    }
    /** The number behind a prefix and what follows it; null when there is none. */
    private static String[] typedNumber(String rest, boolean needsDigit) {
        if (NUMBER.matcher(rest).matches() && (!needsDigit || hasDigit(rest))) return new String[] { rest, "" };
        Matcher bill = LEADING_BILL.matcher(rest);
        if (bill.find()) return new String[] { "EGLV" + bill.group(1), rest.substring(bill.end()) };
        int cut = rest.indexOf(' ');
        String first = cut < 0 ? rest : rest.substring(0, cut);
        return NUMBER.matcher(first).matches() && hasDigit(first) ? new String[] { first, cut < 0 ? "" : rest.substring(cut) } : null;
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
            case "CARRIER_SCHEDULE" -> "Rederijplanning beschikbaar";
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
