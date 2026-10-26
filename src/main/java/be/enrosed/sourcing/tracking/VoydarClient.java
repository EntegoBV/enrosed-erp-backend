package be.enrosed.sourcing.tracking;

import jakarta.enterprise.context.ApplicationScoped;
import org.w3c.dom.*;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Pattern;
import static be.enrosed.sourcing.tracking.ContainerTracking.*;

/** Documented accountless Atom feed, not the JSON API or the embedded map's private requests. */
@ApplicationScoped
public class VoydarClient {
    private static final String ATOM = "http://www.w3.org/2005/Atom";
    private static final int MAX_BYTES = 1_000_000;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    public record Result(Observation observation, String etag, String lastModified) {}

    public Result fetch(String imo, TrackingStore.Snapshot prior) {
        if (!VesselLinkStore.validImo(imo)) throw new ShipsGoClient.TrackingFailure("Het IMO-nummer is niet geldig.");
        var builder = HttpRequest.newBuilder(URI.create("https://www.voydar.com/feeds/vessel/" + imo))
                .timeout(Duration.ofSeconds(25)).header("Accept", "application/atom+xml")
                .header("User-Agent", "ENROSED-ContainerTracking/1.0 (+https://enrosed.com)");
        if (prior != null && prior.observation() != null) {
            if (prior.etag() != null) builder.header("If-None-Match", prior.etag());
            if (prior.lastModified() != null) builder.header("If-Modified-Since", prior.lastModified());
        }
        var bytes = new ByteArrayOutputStream();
        var pending = http.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofByteArrayConsumer(chunk -> chunk.ifPresent(part -> {
            if ((long) bytes.size() + part.length > MAX_BYTES) throw new ShipsGoClient.TrackingFailure("De scheepsfeed is te groot.");
            bytes.writeBytes(part);
        })));
        try {
            HttpResponse<Void> response = pending.get(25, TimeUnit.SECONDS);
            if (response.statusCode() == 304) return notModified(imo, prior);
            if (response.statusCode() != 200) throw new ShipsGoClient.TrackingFailure(switch (response.statusCode()) {
                case 404 -> "Voydar heeft nog geen feed voor dit IMO-nummer.";
                case 429 -> "Voydar vraagt om later opnieuw te controleren.";
                default -> "De scheepsfeed is tijdelijk niet beschikbaar. De vorige meldingen blijven bewaard.";
            });
            Observation fresh = parse(bytes.toByteArray(), imo);
            Observation combined = merge(prior == null ? null : prior.observation(), fresh);
            return new Result(combined, header(response, "ETag", 500), header(response, "Last-Modified", 200));
        } catch (InterruptedException interrupted) {
            pending.cancel(true); Thread.currentThread().interrupt();
            throw new ShipsGoClient.TrackingFailure("De controle van de scheepsfeed is onderbroken.");
        } catch (TimeoutException timeout) {
            pending.cancel(true);
            throw new ShipsGoClient.TrackingFailure("De scheepsfeed antwoordt niet op tijd. De vorige meldingen blijven bewaard.");
        } catch (ExecutionException failure) {
            throw new ShipsGoClient.TrackingFailure("De scheepsfeed kon niet worden opgehaald. De vorige meldingen blijven bewaard.");
        }
    }

    static Result notModified(String imo, TrackingStore.Snapshot prior) {
        return new Result(prior != null && prior.observation() != null ? prior.observation() : empty(imo),
                prior == null ? null : prior.etag(), prior == null ? null : prior.lastModified());
    }

    static Observation parse(byte[] bytes, String imo) {
        if (bytes.length > MAX_BYTES) throw new ShipsGoClient.TrackingFailure("De scheepsfeed is te groot.");
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            factory.setXIncludeAware(false); factory.setExpandEntityReferences(false);
            var builder = factory.newDocumentBuilder();
            builder.setErrorHandler(new org.xml.sax.helpers.DefaultHandler() {
                @Override public void fatalError(org.xml.sax.SAXParseException error) throws org.xml.sax.SAXException { throw error; }
                @Override public void error(org.xml.sax.SAXParseException error) throws org.xml.sax.SAXException { throw error; }
            });
            Element feed = builder.parse(new ByteArrayInputStream(bytes)).getDocumentElement();
            if (!ATOM.equals(feed.getNamespaceURI()) || !"feed".equals(feed.getLocalName())) throw new IllegalArgumentException();
            String feedId = child(feed, "id");
            if (feedId == null || !feedId.endsWith("/feeds/vessel/" + imo)) throw new IllegalArgumentException();
            String title = child(feed, "title");
            String vessel = title == null ? "IMO " + imo : title.split(":", 2)[0].strip();
            if (vessel.isBlank() || vessel.length() > 200) vessel = "IMO " + imo;
            Pattern identity = Pattern.compile(".*/feeds/vessel/" + imo + "/(arrival|departure|reportedArrival|reportedDeparture)/([^/]+)/.*");
            List<Event> events = new ArrayList<>();
            for (Element entry : children(feed, "entry")) {
                if (events.size() >= 200) break;
                String id = child(entry, "id");
                if (id == null) continue;
                var match = identity.matcher(id);
                if (!match.matches()) continue; // Names, ownership and sanctions do not locate cargo.
                Instant time = instant(match.group(2)); // Entry updated can mean an edit; the event ID holds occurrence time.
                if (time == null) time = instant(child(entry, "published"));
                if (time == null) continue;
                String eventTitle = child(entry, "title");
                boolean arrival = match.group(1).toLowerCase(Locale.ROOT).endsWith("arrival");
                String location = location(eventTitle, arrival);
                String content = child(entry, "content");
                String attribution = "Voydar";
                if (content != null) {
                    int sourceAt = content.indexOf("Source:");
                    int detectedAt = content.indexOf("Detected from");
                    if (sourceAt >= 0) attribution += " · " + content.substring(sourceAt + 7).strip();
                    else if (detectedAt >= 0) attribution += " · " + content.substring(detectedAt).strip();
                }
                if (attribution.length() > 600) attribution = attribution.substring(0, 600);
                String sourceUrl = "https://www.voydar.com/vessel/" + imo;
                for (Element link : children(entry, "link")) {
                    if ("alternate".equals(link.getAttribute("rel")) && sourceUrl(link.getAttribute("href")) != null) {
                        sourceUrl = sourceUrl(link.getAttribute("href")); break;
                    }
                }
                events.add(new Event(hash(id).substring(0, 24), arrival ? "Schip aangekomen" : "Schip vertrokken",
                        location, time.toString(), true, attribution, sourceUrl));
            }
            return observed(vessel, events);
        } catch (Exception failure) {
            throw new ShipsGoClient.TrackingFailure("Voydar gaf geen geldige scheepsfeed terug. De vorige meldingen blijven bewaard.");
        }
    }

    static Observation merge(Observation previous, Observation fresh) {
        if (previous == null) return fresh;
        Map<String, Event> events = new LinkedHashMap<>();
        previous.events().forEach(event -> events.put(event.id(), event));
        fresh.events().forEach(event -> events.put(event.id(), event));
        String vessel = fresh.vessel() != null && fresh.vessel().startsWith("IMO ") ? previous.vessel() : fresh.vessel();
        return observed(vessel, new ArrayList<>(events.values()));
    }

    static Observation since(Observation data, Instant cutoff) {
        return observed(data.vessel(), data.events().stream().filter(event -> {
            Instant at = instant(event.occurredAt());
            return at != null && !at.isBefore(cutoff);
        }).toList());
    }

    private static Observation observed(String vessel, List<Event> events) {
        List<Event> sorted = events.stream().sorted(Comparator.comparing(Event::occurredAt).reversed()).limit(200).toList();
        String location = sorted.isEmpty() ? null : sorted.getFirst().location();
        Instant latest = sorted.isEmpty() ? null : instant(sorted.getFirst().occurredAt());
        return new Observation("VESSEL_TRACKING", null, vessel, null, location, null, List.of(), sorted, null, latest);
    }
    static Observation empty(String imo) { return observed("IMO " + imo, List.of()); }
    private static String location(String title, boolean arrival) {
        if (title == null) return null;
        String expression = arrival ? "(?i).*(?:arrived at|reported arrival at) (.+)$" : "(?i).*(?:departed from|reported departure from|left) (.+)$";
        var matcher = Pattern.compile(expression).matcher(title);
        if (!matcher.matches()) return null;
        String result = matcher.group(1).strip();
        return result.length() > 200 ? result.substring(0, 200) : result;
    }
    private static String sourceUrl(String value) {
        try {
            URI uri = URI.create(value);
            return "https".equals(uri.getScheme()) && "www.voydar.com".equals(uri.getHost()) && uri.getUserInfo() == null
                    && (uri.getPort() == -1 || uri.getPort() == 443) && (uri.getPath().startsWith("/port/") || uri.getPath().startsWith("/vessel/"))
                    ? uri.toASCIIString() : null;
        } catch (IllegalArgumentException failure) { return null; }
    }
    private static String header(HttpResponse<?> response, String name, int limit) {
        return response.headers().firstValue(name).filter(value -> value.length() <= limit && !value.contains("\r") && !value.contains("\n")).orElse(null);
    }
    private static Instant instant(String value) {
        if (value == null) return null;
        try { return Instant.parse(value); } catch (java.time.format.DateTimeParseException invalid) { return null; }
    }
    private static String child(Element parent, String name) {
        var children = children(parent, name);
        if (children.isEmpty()) return null;
        String value = children.getFirst().getTextContent().strip();
        return value.isBlank() ? null : value;
    }
    private static List<Element> children(Element parent, String name) {
        List<Element> result = new ArrayList<>();
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling())
            if (node instanceof Element element && ATOM.equals(element.getNamespaceURI()) && name.equals(element.getLocalName())) result.add(element);
        return result;
    }
}
