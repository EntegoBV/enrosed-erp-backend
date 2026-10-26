package be.enrosed.sourcing.tracking;

import be.enrosed.sourcing.domain.PurchaseOrder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Pattern;
import static be.enrosed.sourcing.tracking.ContainerTracking.*;

/** Documented accountless MCP lookup. It reads Bexy's cache, never claims to refresh Evergreen. */
@ApplicationScoped
public class BexyClient {
    private static final URI ENDPOINT = URI.create("https://bexy.io/mcp/public");
    private static final String PROTOCOL = "2025-03-26";
    private static final int MAX_BYTES = 1_000_000;
    private static final Pattern BL = Pattern.compile("(?<![A-Z0-9])EGLV\\s*(\\d{12})(?![A-Z0-9])");
    private static final Pattern BOX = Pattern.compile("(?<![A-Z0-9])([A-Z]{4})[ -]?(\\d{7})(?![A-Z0-9])");
    @Inject ObjectMapper json;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    record Query(String bl, String box) {}
    record Reply(JsonNode body, String session) {}

    public static String identity(PurchaseOrder order) {
        Query query = query(order.trackingReference());
        return query == null ? null : "BL:" + query.bl() + "|CT:" + Objects.toString(query.box(), "");
    }

    public Observation fetch(PurchaseOrder order) {
        Query query = query(order.trackingReference());
        if (query == null) throw failure("Geen eenduidige Evergreen-B/L beschikbaar voor de datums.");
        Reply init = request(Map.of("jsonrpc", "2.0", "id", 1, "method", "initialize", "params", Map.of(
                "protocolVersion", PROTOCOL, "capabilities", Map.of(), "clientInfo", Map.of("name", "ENROSED-ContainerTracking", "version", "1.0"))), null);
        if (!PROTOCOL.equals(text(init.body().path("result"), "protocolVersion")))
            throw failure("De openbare datumbron gebruikt een niet-ondersteund protocol.");
        request(Map.of("jsonrpc", "2.0", "method", "notifications/initialized"), init.session());
        Reply reply = request(Map.of("jsonrpc", "2.0", "id", 2, "method", "tools/call", "params", Map.of(
                "name", "track-shipment", "arguments", Map.of("reference", query.bl()))), init.session());
        return parse(payload(reply.body()), order.trackingReference());
    }

    /** Require an explicit master B/L; container-owner prefixes alone do not identify a carrier. */
    static Query query(String raw) {
        if (raw == null || raw.isBlank() || raw.length() > 500) return null;
        String value = raw.toUpperCase(Locale.ROOT);
        Set<String> bills = new LinkedHashSet<>();
        var bl = BL.matcher(value);
        while (bl.find()) bills.add("EGLV" + bl.group(1));
        if (bills.size() != 1) return null;
        Set<String> boxes = new LinkedHashSet<>();
        var box = BOX.matcher(value);
        while (box.find()) {
            String number = box.group(1) + box.group(2);
            if (!validBox(number)) return null;
            boxes.add(number);
        }
        if (boxes.size() > 1) return null;
        return new Query(bills.iterator().next(), boxes.isEmpty() ? null : boxes.iterator().next());
    }

    Observation parse(JsonNode data, String rawReference) {
        Query query = query(rawReference);
        if (query == null || !data.isObject()) throw failure("De datumbron gaf geen eenduidige zending terug.");
        String returnedBl = text(data, "carrier_bill_of_lading");
        if (returnedBl == null || !query.bl().equals(returnedBl.replaceAll("\\s+", "").toUpperCase(Locale.ROOT)))
            throw failure("De B/L van de datumbron komt niet overeen met deze order.");
        JsonNode containers = data.path("containers");
        if (!containers.isArray() || containers.isEmpty() || containers.size() > 200)
            throw failure("De datumbron bevat geen bruikbare containers.");
        JsonNode selected = null;
        for (JsonNode candidate : containers) {
            if (query.box() == null ? containers.size() == 1 : query.box().equals(text(candidate, "container_no"))) {
                if (selected != null) throw failure("De datumbron geeft dezelfde container meermaals terug.");
                selected = candidate;
            }
        }
        if (selected == null || !validBox(text(selected, "container_no")))
            throw failure("De container van de datumbron komt niet overeen met deze order.");
        Instant asOf = instant(text(data, "data_as_of"));
        if (asOf == null) throw failure("De datumbron vermeldt niet wanneer de rederijgegevens zijn gecontroleerd.");
        String origin = location(data.path("port_of_loading"));
        String destination = location(data.path("port_of_discharge"));
        String eta = null;
        // Delivery can be inland. Only a carrier ETA explicitly ending at the discharge port is usable here.
        if ("carrier_eta".equals(text(data, "estimated_arrival_source"))
                && "port_of_discharge".equals(text(data.path("transit"), "scheduled_transit_to")))
            eta = date(text(data, "estimated_arrival"));
        String start = date(text(data, "estimated_departure"));
        String voyage = text(data, "vessel_voyage");
        Instant eventAt = instant(text(selected, "last_event_date"));
        String status = text(selected, "current_status");
        String link = safeLink(text(data, "tracking_url"));
        List<Event> events = status == null || eventAt == null ? List.of() : List.of(new Event(
                hash(query.bl() + ":" + selected.path("container_no").asText() + ":" + eventAt + ":" + status).substring(0, 24),
                status, null, eventAt.toString(), true, "Evergreen via bexy", link));
        DateEvidence dates = new DateEvidence(null, origin, null, destination, null, eta, null,
                voyage, start, text(selected, "container_no"), query.bl());
        // Loading on board is not an actual vessel departure. Keep actualDepartureAt absent.
        return new Observation("CARRIER_SCHEDULE", "Evergreen", voyage, eta, null, null, List.of(), events,
                asOf, eventAt, dates);
    }

    private JsonNode payload(JsonNode envelope) {
        JsonNode result = envelope.path("result");
        if (envelope.hasNonNull("error") || result.path("isError").asBoolean(false))
            throw failure("Bexy heeft nog geen actuele opgeslagen gegevens voor deze Evergreen-zending.");
        if (result.path("structuredContent").isObject()) return result.path("structuredContent");
        JsonNode content = result.path("content");
        if (content.isArray()) for (JsonNode item : content) {
            if (!"text".equals(text(item, "type"))) continue;
            try { JsonNode data = json.readTree(item.path("text").asText()); if (data != null && data.isObject()) return data; }
            catch (Exception ignored) { }
        }
        throw failure("De datumbron gaf geen geldige zendinggegevens terug.");
    }

    private Reply request(Map<String, Object> body, String session) {
        try {
            var builder = HttpRequest.newBuilder(ENDPOINT).timeout(Duration.ofSeconds(25))
                    .header("Accept", "application/json, text/event-stream").header("Content-Type", "application/json")
                    .header("MCP-Protocol-Version", PROTOCOL).header("User-Agent", "ENROSED-ContainerTracking/1.0 (+https://enrosed.com)")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
            if (session != null) builder.header("Mcp-Session-Id", session);
            var bytes = new ByteArrayOutputStream();
            var pending = http.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofByteArrayConsumer(chunk -> chunk.ifPresent(part -> {
                if ((long) bytes.size() + part.length > MAX_BYTES) throw failure("De datumbron gaf een te groot antwoord terug.");
                bytes.writeBytes(part);
            })));
            try {
                HttpResponse<Void> response = pending.get(25, TimeUnit.SECONDS);
                if (response.statusCode() < 200 || response.statusCode() >= 300)
                    throw failure(response.statusCode() == 429 ? "De datumbron vraagt om later opnieuw te controleren." : "De openbare datumbron is tijdelijk niet beschikbaar.");
                String id = response.headers().firstValue("Mcp-Session-Id").orElse(null);
                if (id != null && !id.matches("[A-Za-z0-9-]{1,200}")) throw failure("De datumbron gaf een ongeldige sessie terug.");
                String raw = bytes.toString(java.nio.charset.StandardCharsets.UTF_8);
                if (raw.isBlank()) return new Reply(json.createObjectNode(), id);
                if (response.headers().firstValue("Content-Type").orElse("").startsWith("text/event-stream")) {
                    raw = raw.lines().filter(line -> line.startsWith("data:")).map(line -> line.substring(5).strip()).filter(line -> !line.isBlank()).findFirst().orElse("");
                }
                return new Reply(json.readTree(raw), id);
            } catch (InterruptedException interrupted) {
                pending.cancel(true); Thread.currentThread().interrupt(); throw failure("De controle van aankomstdatums is onderbroken.");
            } catch (TimeoutException timeout) {
                pending.cancel(true); throw failure("De datumbron antwoordt niet op tijd.");
            } catch (ExecutionException failed) { throw failure("De datumbron kon niet worden opgehaald."); }
        } catch (ShipsGoClient.TrackingFailure known) { throw known; }
        catch (Exception failed) { throw failure("De datumbron gaf geen bruikbaar antwoord terug."); }
    }

    static boolean validBox(String value) {
        if (value == null || !value.matches("[A-Z]{3}[UJZ]\\d{7}")) return false;
        int sum = 0;
        for (int i = 0; i < 10; i++) {
            char ch = value.charAt(i);
            int n = Character.isDigit(ch) ? ch - '0' : ch - 'A' + 10 + (ch - 'A' + 9) / 10;
            sum += n * (1 << i);
        }
        return sum % 11 % 10 == value.charAt(10) - '0';
    }
    private static String text(JsonNode node, String key) {
        JsonNode value = node.path(key);
        return value.isTextual() && !value.asText().isBlank() && value.asText().length() <= 1000 ? value.asText().strip() : null;
    }
    private static String location(JsonNode node) { return text(node, "location"); }
    private static Instant instant(String value) { try { return value == null ? null : OffsetDateTime.parse(value).toInstant(); } catch (RuntimeException e) { return null; } }
    private static String date(String value) { try { return value == null ? null : OffsetDateTime.parse(value).toLocalDate().toString(); } catch (RuntimeException e) { return null; } }
    private static String safeLink(String value) {
        try {
            URI uri = URI.create(value);
            return "https".equals(uri.getScheme()) && "bexy.io".equals(uri.getHost()) && uri.getUserInfo() == null
                    && uri.getPort() == -1 && uri.getPath().matches("/track/[a-zA-Z0-9-]{1,100}") && uri.getQuery() == null && uri.getFragment() == null ? uri.toString() : "https://bexy.io/mcp";
        } catch (Exception ignored) { return "https://bexy.io/mcp"; }
    }
    private static ShipsGoClient.TrackingFailure failure(String message) { return new ShipsGoClient.TrackingFailure(message); }
}
