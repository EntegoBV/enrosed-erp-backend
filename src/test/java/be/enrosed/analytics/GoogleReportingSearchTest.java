package be.enrosed.analytics;

import be.enrosed.analytics.GoogleReportingClient.Failure;
import be.enrosed.analytics.GoogleReportingDtos.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

class GoogleReportingSearchTest {
    private final ObjectMapper json=new ObjectMapper();
    private static final class MutableClock extends Clock {
        private Instant now=Instant.parse("2026-09-14T10:00:00Z");
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return Clock.fixed(instant(),zone); }
        public synchronized Instant instant() { return now; }
        synchronized void advance(Duration amount) { now=now.plus(amount); }
    }
    private final class Client extends GoogleReportingClient {
        boolean configured=true,metadata=true,emptyAvailability=false,emptyCurrent=false;
        String metadataKey="firstIncompleteDate",boundary="2026-09-13";
        String failedPart,malformedPart;
        final List<Map<String,Object>> requests=new CopyOnWriteArrayList<>();
        Client() { super(json,Optional.empty()); }
        @Override public boolean configured() { return configured; }
        @Override public JsonNode post(String endpoint,Map<String,Object> body) {
            assertTrue(endpoint.endsWith("/searchAnalytics/query"),"Search-only must never fetch GA or realtime");
            assertTrue(endpoint.contains("sites/sc-domain%3Aenrosed.com/"));
            requests.add(body);
            @SuppressWarnings("unchecked") List<String> dimensions=(List<String>)body.get("dimensions");
            String part="all".equals(body.get("dataState"))?"probe":
                    dimensions.isEmpty()?(String.valueOf(body.get("startDate")).compareTo("2026-08-01")<0?"previous":"totals"):dimensions.getFirst();
            if (part.equals(failedPart)) throw new Failure("ACCESS_DENIED");
            if (part.equals(malformedPart)) return json.valueToTree(Map.of("rows",Map.of()));
            if (part.equals("probe")) return json.valueToTree(metadata
                    ? Map.of("metadata",Map.of(metadataKey,boundary),"responseAggregationType","byProperty")
                    : Map.of("responseAggregationType","byProperty"));
            if (dimensions.equals(List.of("date")) && Objects.equals(body.get("rowLimit"),14)) {
                return response(emptyAvailability?List.of():List.of(row("2026-09-11",1,10,.1,4)));
            }
            if (part.equals("previous")) return response(List.of(row(null,99,1617,99d/1617,8.4391)));
            if (emptyCurrent) return response(List.of());
            return response(switch (part) {
                case "totals" -> List.of(row(null,162,2170,162d/2170,9.5604));
                // Last final day has no returned traffic row. Metadata must still set Sep12 as the cutoff.
                case "date" -> List.of(row("2026-09-11",20,100,.2,3));
                case "query" -> List.of(row("wholesale roses",10,100,.1,5));
                case "page" -> List.of(row("https://enrosed.com/nl/products/",15,120,.125,5));
                case "device" -> List.of(row("MOBILE",132,1275,132d/1275,5.5169),row("DESKTOP",27,866,27d/866,15.649),row("TABLET",3,29,3d/29,5.517));
                default -> throw new AssertionError(part);
            });
        }
        JsonNode response(List<?> rows) { return json.valueToTree(Map.of("rows",rows,"responseAggregationType","byProperty")); }
    }
    private Map<String,Object> row(String key,double clicks,double impressions,double ctr,double position) {
        return Map.of("keys",key==null?List.of():List.of(key),"clicks",clicks,"impressions",impressions,"ctr",ctr,"position",position);
    }
    private GoogleReportingService service(Client client,Clock clock) {
        return new GoogleReportingService(client,"554014865","sc-domain:enrosed.com",clock);
    }

    @Test void liveCamelCaseBoundaryProducesEqualFinalPeriodsAndIndependentAuthoritativeTotals() {
        var client=new Client(); var report=service(client,new MutableClock()).searchConsole(30);
        assertEquals(30,report.days()); var source=report.searchConsole(); var data=source.data();
        assertEquals(Status.CONNECTED,source.status());
        assertEquals("2026-08-14",source.from()); assertEquals("2026-09-12",source.to());
        assertEquals("2026-07-15",data.comparison().from()); assertEquals("2026-08-13",data.comparison().to());
        assertEquals("GOOGLE_FINAL_BOUNDARY",data.periodBasis()); assertEquals("2026-09-12",data.availableThrough());
        assertEquals("America/Los_Angeles",data.timeZone()); assertEquals("final",data.dataState());
        assertEquals(162,data.totals().clicks()); assertEquals(2170,data.totals().impressions());
        assertEquals(162d/2170,data.totals().ctr()); assertEquals(9.5604,data.totals().position());
        assertEquals(99,data.comparison().totals().clicks()); assertEquals(Status.CONNECTED,data.comparison().status());
        assertEquals(10,data.queries().getFirst().clicks()); assertEquals(20,data.perDay().getFirst().clicks());
        assertEquals(100,data.rowLimit()); assertTrue(data.issues().isEmpty());
        assertEquals(List.of("MOBILE","DESKTOP","TABLET"),data.devices().rows().stream().map(SearchDevice::device).toList());
        assertEquals(7,client.requests.size(),"one metadata probe and six bounded reports");
        assertEquals(1,client.requests.stream().filter(r->r.get("dataState").equals("all")).count());
        for (var request:client.requests) {
            assertEquals("web",request.get("type"));
            assertTrue((Integer)request.get("rowLimit")<=366);
            if (List.of("page").equals(request.get("dimensions"))) assertEquals("auto",request.get("aggregationType"));
            else assertEquals("byProperty",request.get("aggregationType"));
            if (List.of("page").equals(request.get("dimensions")) || List.of("query").equals(request.get("dimensions"))) assertEquals(100,request.get("rowLimit"));
        }
    }

    @Test void documentedSnakeCaseAndMetadataAbsentFallbackNeverTreatProvisionalMetricsAsFinal() {
        var snake=new Client(); snake.metadataKey="first_incomplete_date";
        assertEquals("2026-09-12",service(snake,new MutableClock()).searchConsole(30).searchConsole().to());
        var absent=new Client(); absent.metadata=false;
        var source=service(absent,new MutableClock()).searchConsole(30).searchConsole();
        assertEquals("2026-09-11",source.to()); assertEquals("LATEST_REPORTED_FINAL_DAY",source.data().periodBasis());
        assertEquals(8,absent.requests.size()); assertEquals(162,source.data().totals().clicks());
        assertTrue(source.data().warnings().stream().anyMatch(w->w.contains("laatste dag")));
    }

    @Test void unknownFinalBoundaryKeepsActualCoreButDoesNotInventComparison() {
        var client=new Client(); client.metadata=false; client.emptyAvailability=true;
        var source=service(client,new MutableClock()).searchConsole(30).searchConsole();
        assertEquals(Status.CONNECTED,source.status()); assertEquals(162,source.data().totals().clicks());
        assertEquals("UNCONFIRMED",source.data().periodBasis());
        assertEquals(Status.ERROR,source.data().comparison().status()); assertNull(source.data().comparison().totals());
        assertEquals("FINAL_DATES_UNCONFIRMED",source.data().comparison().errorCode());
        assertEquals("AVAILABILITY",source.data().issues().getFirst().section());
        assertEquals(7,client.requests.size(),"no previous-period request without a reliable cutoff");
    }

    @Test void partialFailuresKeepCoreAndDistinguishUnavailableRowsFromTrueZero() {
        for (String part:List.of("query","page","date","device","previous")) {
            var client=new Client(); client.failedPart=part;
            var source=service(client,new MutableClock()).searchConsole(30).searchConsole();
            assertEquals(Status.CONNECTED,source.status(),part); assertEquals(162,source.data().totals().clicks());
            if (part.equals("device")) { assertEquals(Status.ERROR,source.data().devices().status()); assertNull(source.data().devices().rows()); }
            else if (part.equals("previous")) { assertEquals(Status.ERROR,source.data().comparison().status()); assertNull(source.data().comparison().totals()); }
            else assertEquals("ACCESS_DENIED",source.data().issues().getFirst().errorCode(),part);
        }
        var client=new Client(); client.emptyCurrent=true;
        var source=service(client,new MutableClock()).searchConsole(30).searchConsole();
        assertEquals(Status.NO_DATA,source.status()); assertEquals(0,source.data().totals().clicks());
        assertEquals(Status.CONNECTED,source.data().comparison().status(),"a real decline to zero remains comparable");
        assertEquals(Status.NO_DATA,source.data().devices().status()); assertEquals(List.of(),source.data().devices().rows());
    }

    @Test void malformedCoreFailsAndMalformedSupplementalRowsRemainExplicit() {
        var client=new Client(); client.malformedPart="totals";
        var source=service(client,new MutableClock()).searchConsole(30).searchConsole();
        assertEquals(Status.ERROR,source.status()); assertEquals("INVALID_RESPONSE",source.errorCode()); assertNull(source.data());
        client=new Client(); client.malformedPart="query";
        source=service(client,new MutableClock()).searchConsole(30).searchConsole();
        assertEquals(Status.CONNECTED,source.status()); assertEquals("INVALID_RESPONSE",source.data().issues().getFirst().errorCode());
        assertEquals(List.of(),source.data().queries());
    }

    @Test void comparisonOutsideRetentionIsUnavailableInsteadOfAnArtificialYearOverYearDecline() {
        var client=new Client(); var source=service(client,new MutableClock()).searchConsole(999).searchConsole();
        assertEquals("2025-09-13",source.from()); assertEquals("2026-09-12",source.to());
        assertEquals("COMPARISON_OUTSIDE_RETENTION",source.data().comparison().errorCode());
        assertNull(source.data().comparison().totals());
        assertEquals(6,client.requests.size());
        assertTrue(client.requests.stream().allMatch(r->r.get("startDate").toString().compareTo("2025-09-13")>=0));
    }

    @Test void missingConfigurationAvoidsAllCallsAndPacificMidnightKeepsTheCorrectProbeDates() {
        var client=new Client(); client.configured=false;
        var report=service(client,new MutableClock()).searchConsole(-2);
        assertEquals(1,report.days()); assertEquals(Status.NOT_CONFIGURED,report.searchConsole().status()); assertTrue(client.requests.isEmpty());
        client=new Client(); client.boundary="2026-09-12";
        var clock=Clock.fixed(Instant.parse("2026-09-14T01:00:00Z"),ZoneOffset.UTC);
        var source=service(client,clock).searchConsole(7).searchConsole();
        assertEquals("2026-09-13",client.requests.getFirst().get("endDate"),"still Sep13 in Pacific");
        assertEquals("2026-09-05",source.from()); assertEquals("2026-09-11",source.to());
    }

    @Test void concurrentLoadsDeduplicateAndCoreFailureRetainsBoundedStaleDataWithCooldown() throws Exception {
        var client=new Client(); var clock=new MutableClock(); var service=service(client,clock);
        try (var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            var tasks=new ArrayList<Future<SearchReport>>();
            for (int i=0;i<12;i++) tasks.add(executor.submit(()->service.searchConsole(30)));
            for (var task:tasks) assertEquals(Status.CONNECTED,task.get().searchConsole().status());
        }
        assertEquals(7,client.requests.size());
        var first=service.searchConsole(30).searchConsole();
        clock.advance(Duration.ofMinutes(16)); client.failedPart="totals";
        var stale=service.searchConsole(30).searchConsole();
        assertEquals(Status.STALE,stale.status()); assertEquals(first.data(),stale.data()); assertEquals(first.fetchedAt(),stale.fetchedAt());
        int calls=client.requests.size(); service.searchConsole(30); assertEquals(calls,client.requests.size());
        clock.advance(Duration.ofHours(25));
        var expired=service.searchConsole(30).searchConsole(); assertEquals(Status.ERROR,expired.status()); assertNull(expired.data());
    }
}
