package be.enrosed.sourcing.tracking;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static be.enrosed.sourcing.tracking.ContainerTracking.*;

/** Official ShipsGo v2 API. No browser scraping, user-supplied destinations or notification followers. */
@ApplicationScoped
public class ShipsGoClient {
    private static final URI BASE = URI.create("https://api.shipsgo.com/v2/");
    private static final int MAX_RESPONSE_BYTES = 2_000_000;
    @Inject ObjectMapper json;
    @ConfigProperty(name = "enrosed.tracking.shipsgo.token") Optional<String> token;
    @ConfigProperty(name = "enrosed.tracking.enabled", defaultValue = "true") boolean enabled;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER).build();

    public boolean configured() { return enabled && token != null && token.filter(value -> !value.isBlank()).isPresent(); }

    public long create(Reference reference) {
        Map<String, String> body = new LinkedHashMap<>();
        // Stable purchase/reference identity: retries after a timeout never buy tracking twice.
        body.put("reference", "enrosed-" + reference.key());
        body.put(reference.container() ? "container_number" : "booking_number", reference.number());
        JsonNode result = request("ocean/shipments", body, true);
        long id = result.path("shipment").path("id").asLong(0);
        if (id <= 0) throw new TrackingFailure("ShipsGo gaf geen geldige zending terug.");
        return id;
    }

    public Observation fetch(long id, Reference reference) {
        JsonNode shipment = request("ocean/shipments/" + id, null, false).path("shipment");
        if (!shipment.isObject() || shipment.path("id").asLong(0) != id || text(shipment, "status") == null)
            throw new TrackingFailure("ShipsGo gaf onvolledige trackinggegevens terug.");
        String status = text(shipment, "status");
        JsonNode geo = Set.of("NEW", "INPROGRESS", "UNTRACKED").contains(status) ? json.createObjectNode()
                : request("ocean/shipments/" + id + "/geojson", null, false).path("geojson");
        return parse(shipment, geo, reference);
    }

    private JsonNode request(String path, Map<String, String> body, boolean acceptDuplicate) {
        if (!configured()) throw new TrackingFailure("Koppel eerst een ShipsGo API-sleutel.");
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(BASE.resolve(path)).timeout(Duration.ofSeconds(25))
                    .header("Accept", "application/json").header("X-Shipsgo-User-Token", token.orElseThrow());
            if (body != null) builder.header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
            var bytes = new java.io.ByteArrayOutputStream();
            var pending = http.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofByteArrayConsumer(chunk -> chunk.ifPresent(part -> {
                if ((long) bytes.size() + part.length > MAX_RESPONSE_BYTES)
                    throw new TrackingFailure("ShipsGo gaf een te groot antwoord terug.");
                bytes.writeBytes(part);
            })));
            try {
                // The deadline includes the complete body, not only arrival of HTTP headers.
                HttpResponse<Void> response = pending.get(25, TimeUnit.SECONDS);
                int status = response.statusCode();
                if ((status >= 200 && status < 300) || (acceptDuplicate && status == 409)) return json.readTree(bytes.toByteArray());
                throw new TrackingFailure(switch (status) {
                    case 401, 403 -> "ShipsGo weigert de API-sleutel. Controleer de API-toegang.";
                    case 402 -> "Het ShipsGo-tegoed is op. Vul trackingcredits aan.";
                    case 404 -> "ShipsGo heeft deze tracking nog niet beschikbaar.";
                    case 422 -> "ShipsGo herkent dit nummer niet. Controleer het containernummer of master-B/L bij de vervoerder.";
                    case 429 -> "ShipsGo vraagt om later opnieuw te controleren.";
                    default -> "ShipsGo is tijdelijk niet beschikbaar. De vorige gegevens blijven bewaard.";
                });
            } catch (TimeoutException timeout) {
                pending.cancel(true);
                throw new TrackingFailure("ShipsGo antwoordt niet op tijd. De vorige gegevens blijven bewaard.");
            } catch (InterruptedException interrupted) {
                pending.cancel(true);
                throw interrupted;
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new TrackingFailure("De trackingcontrole werd onderbroken. De vorige gegevens blijven bewaard.");
        } catch (IOException | IllegalArgumentException | ExecutionException failure) {
            // Never surface an upstream body, token, private URL or network exception to logs/UI.
            throw new TrackingFailure("ShipsGo kon niet worden bereikt. De vorige gegevens blijven bewaard.");
        }
    }

    static Observation parse(JsonNode shipment, JsonNode geo, Reference reference) {
        List<JsonNode> containers = new ArrayList<>();
        for (JsonNode container : shipment.path("containers")) {
            if (!reference.container() || reference.number().equals(text(container, "number"))) containers.add(container);
        }
        if (reference.container() && shipment.path("containers").isArray() && !shipment.path("containers").isEmpty() && containers.isEmpty())
            throw new TrackingFailure("ShipsGo antwoordde voor een ander containernummer. Controleer de trackingreferentie.");
        if (reference.container() && containers.isEmpty() && text(shipment, "container_number") != null
                && !reference.number().equals(text(shipment, "container_number")))
            throw new TrackingFailure("ShipsGo antwoordde voor een ander containernummer. Controleer de trackingreferentie.");
        String status = text(shipment, "status");
        if (containers.size() == 1) {
            String containerStatus = text(containers.getFirst(), "status");
            if (containerStatus != null && !"UNKNOWN".equals(containerStatus)) status = containerStatus;
        }
        List<Event> events = new ArrayList<>();
        JsonNode lastActual = null;
        Instant lastActualAt = null;
        for (JsonNode container : containers) for (JsonNode movement : container.path("movements")) {
            String time = iso(text(movement, "timestamp"));
            String event = text(movement, "event");
            String location = text(movement.path("location"), "name");
            boolean actual = "ACT".equals(text(movement, "status"));
            String key = text(container, "number") + ":" + event + ":" + time + ":" + location;
            events.add(new Event(hash(key).substring(0, 20), eventLabel(event), location, time, actual,
                    null, null, "DEPA".equals(event) ? "DEPARTURE" : "ARRV".equals(event) ? "ARRIVAL" : event,
                    text(movement.path("location"), "code")));
            Instant at = instant(time);
            if (actual && at != null && (lastActualAt == null || at.isAfter(lastActualAt))) {
                lastActual = movement;
                lastActualAt = at;
            }
        }
        events.sort(Comparator.comparing(Event::occurredAt, Comparator.nullsLast(Comparator.reverseOrder())));
        if (events.size() > 200) events = new ArrayList<>(events.subList(0, 200));
        String vessel = lastActual == null ? null : text(lastActual.path("vessel"), "name");
        String location = lastActual == null ? null : text(lastActual.path("location"), "name");
        Position position = null;
        List<Point> route = new ArrayList<>();
        for (JsonNode feature : geo.path("features")) {
            JsonNode geometry = feature.path("geometry"), properties = feature.path("properties");
            boolean current = "CURRENT".equals(text(properties, "status"));
            if ("LineString".equals(text(geometry, "type"))) {
                for (JsonNode coordinate : geometry.path("coordinates")) {
                    Point point = point(coordinate);
                    if (point != null && route.size() < 5_000) route.add(point);
                }
                Point point = current ? point(properties.path("current").path("coordinates")) : null;
                if (point != null) {
                    position = new Position(point.lat(), point.lon(), "VESSEL", null);
                    vessel = text(properties.path("vessel"), "name");
                    String departure = text(properties.path("events").path("DEPA").path("location"), "name");
                    String arrival = text(properties.path("events").path("ARRV").path("location"), "name");
                    location = departure != null && arrival != null ? departure + " → " + arrival : "Op zee";
                }
            } else if (current && "Point".equals(text(geometry, "type")) && (position == null || !"VESSEL".equals(position.kind()))) {
                Point point = point(geometry.path("coordinates"));
                if (point != null) {
                    location = text(properties.path("location"), "name");
                    // This is the provider's current port, not a GPS observation of the container.
                    position = new Position(point.lat(), point.lon(), "LAST_EVENT", null);
                }
            }
        }
        String eta = iso(text(shipment.path("route").path("port_of_discharge"), "date_of_discharge"));
        return new Observation(status, text(shipment.path("carrier"), "name"), vessel, eta, location, position,
                List.copyOf(route), List.copyOf(events), instant(text(shipment, "checked_at")), instant(text(shipment, "updated_at")),
                dateEvidence(shipment, containers));
    }

    private static DateEvidence dateEvidence(JsonNode shipment, List<JsonNode> containers) {
        if (containers.size() != 1) return null;
        JsonNode origin = shipment.path("route").path("port_of_loading").path("location");
        JsonNode destination = shipment.path("route").path("port_of_discharge").path("location");
        String originCode = text(origin, "code"), originName = text(origin, "name");
        List<JsonNode> departures = new ArrayList<>();
        List<String> startDates = new ArrayList<>();
        for (JsonNode movement : containers.getFirst().path("movements")) {
            if (!"ACT".equals(text(movement, "status"))) continue;
            JsonNode location = movement.path("location");
            boolean fromOrigin = originCode != null && originCode.equals(text(location, "code"))
                    || TrackingDateSync.samePort(originName, text(location, "code"), text(location, "name"));
            if (!fromOrigin) continue;
            String kind = text(movement, "event"), date = localDate(text(movement, "timestamp"));
            if (date == null) continue;
            if ("LOAD".equals(kind) || "DEPA".equals(kind)) startDates.add(date);
            if ("DEPA".equals(kind)) departures.add(movement);
        }
        JsonNode departure = departures.size() == 1 ? departures.getFirst() : null;
        String actualDeparture = departure == null ? null : text(departure, "timestamp");
        String vesselImo = departure == null || !departure.path("vessel").path("imo").isIntegralNumber()
                ? null : departure.path("vessel").path("imo").asText();
        return new DateEvidence(originCode, originName, text(destination, "code"), text(destination, "name"), actualDeparture,
                localDate(text(shipment.path("route").path("port_of_discharge"), "date_of_discharge")),
                vesselImo, departure == null ? null : text(departure, "voyage"), startDates.stream().sorted().findFirst().orElse(null),
                text(containers.getFirst(), "number"), text(shipment, "booking_number"));
    }

    private static String localDate(String value) {
        try { return value == null ? null : OffsetDateTime.parse(value).toLocalDate().toString(); }
        catch (java.time.format.DateTimeParseException invalid) { return null; }
    }

    private static Point point(JsonNode coordinate) {
        if (!coordinate.isArray() || coordinate.size() != 2 || !coordinate.get(0).isNumber() || !coordinate.get(1).isNumber()) return null;
        double lon = coordinate.get(0).doubleValue(), lat = coordinate.get(1).doubleValue();
        return Double.isFinite(lat) && Double.isFinite(lon) && lat >= -90 && lat <= 90 && lon >= -180 && lon <= 180 ? new Point(lat, lon) : null;
    }
    static String text(JsonNode object, String key) {
        JsonNode value = object.path(key);
        return value.isTextual() && !value.textValue().isBlank() ? value.textValue().strip() : null;
    }
    private static String iso(String value) { Instant parsed = instant(value); return parsed == null ? null : parsed.toString(); }
    private static Instant instant(String value) {
        if (value == null) return null;
        try { return OffsetDateTime.parse(value).toInstant(); }
        catch (java.time.format.DateTimeParseException ignored) {
            try { return LocalDateTime.parse(value.replace(' ', 'T')).toInstant(ZoneOffset.UTC); }
            catch (java.time.format.DateTimeParseException invalid) { return null; }
        }
    }
    private static String eventLabel(String event) {
        return switch (event == null ? "" : event) {
            case "EMSH" -> "Lege container bij verzender"; case "GTIN" -> "Aangemeld in haven";
            case "LOAD" -> "Geladen"; case "DEPA" -> "Vertrokken"; case "ARRV" -> "Aangekomen";
            case "DISC" -> "Gelost"; case "GTOT" -> "Haven verlaten"; case "EMRT" -> "Lege container ingeleverd";
            default -> "Transportmelding";
        };
    }
    public static final class TrackingFailure extends RuntimeException {
        public TrackingFailure(String message) { super(message); }
    }
}
