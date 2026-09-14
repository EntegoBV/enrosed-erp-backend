package be.enrosed.analytics;

import be.enrosed.analytics.GoogleReportingDtos.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class GoogleReportingServiceTest {
    private final ObjectMapper json=new ObjectMapper();
    private static final class MutableClock extends Clock {
        private Instant now; MutableClock(String now) { this.now=Instant.parse(now); }
        synchronized void advance(Duration amount) { now=now.plus(amount); }
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return Clock.fixed(instant(),zone); }
        public synchronized Instant instant() { return now; }
    }
    private final class FakeClient extends GoogleReportingClient {
        boolean configured=true,empty=false,malformed=false;
        volatile boolean failGa=false,failSearch=false,failRealtime=false;
        Runnable gaHook=()->{};
        JsonNode realtimeOverride,gaOverride;
        final AtomicInteger gaCalls=new AtomicInteger(),searchCalls=new AtomicInteger(),realtimeCalls=new AtomicInteger();
        final List<Map<String,Object>> calls=new CopyOnWriteArrayList<>();
        FakeClient() { super(json,Optional.empty()); }
        @Override public boolean configured() { return configured; }
        @Override public JsonNode post(String endpoint,Map<String,Object> body) {
            calls.add(Map.of("endpoint",endpoint,"body",body));
            if (endpoint.endsWith("batchRunReports")) {
                gaCalls.incrementAndGet(); gaHook.run();
                if(failGa)throw new Failure("ACCESS_DENIED");
                if(gaOverride!=null)return gaOverride;
                if(malformed)return json.valueToTree(Map.of("reports",List.of(Map.of(),Map.of(),Map.of(),Map.of(),Map.of())));
                return json.valueToTree(Map.of("reports",List.of(
                    ga(List.of(),empty?List.of():List.of(row(null,"5","9","20","6","0.6666667","3","42.5"))),
                    ga(List.of("date"),empty?List.of():List.of(row("20260912","4","5","10"),row("20260913","4","4","10"))),
                    ga(List.of("pagePath"),empty?List.of():List.of(row("/nl/","12","4"))),
                    ga(List.of("sessionDefaultChannelGroup"),empty?List.of():List.of(row("Organic Search","6","4"))),
                    ga(List.of("eventName"),empty?List.of():List.of(row("generate_lead","2"))))));
            }
            if (endpoint.endsWith("runRealtimeReport")) {
                realtimeCalls.incrementAndGet(); if(failRealtime)throw new Failure("QUOTA_EXCEEDED");
                if(realtimeOverride!=null)return realtimeOverride;
                return json.valueToTree(ga(List.of(),List.of(row(null,"2"))));
            }
            searchCalls.incrementAndGet(); if(failSearch)throw new Failure("ACCESS_DENIED");
            @SuppressWarnings("unchecked") List<String> dimensions=(List<String>)body.get("dimensions");
            List<?> rows=empty?List.of():dimensions.isEmpty()?List.of(searchRow(null,40,200,.2,4.5)):
                    List.of(searchRow(switch(dimensions.getFirst()){case "date"->"2026-09-11";case "query"->"preserved roses";default->"https://enrosed.com/nl/";},10,50,.2,3.2));
            return json.valueToTree(Map.of("rows",rows,"responseAggregationType","byProperty"));
        }
    }
    private Map<String,Object> ga(List<String> dimensions,List<?> rows) {
        return Map.of("metricHeaders",List.of(Map.of("name","testMetric","type","TYPE_INTEGER")),
                "dimensionHeaders",dimensions.stream().map(name->Map.of("name",name)).toList(),
                "metadata",Map.of("timeZone","Europe/Brussels"),"rows",rows,"rowCount",rows.size());
    }
    private Map<String,Object> row(String dimension,String... values) {
        return Map.of("dimensionValues",dimension==null?List.of():List.of(Map.of("value",dimension)),
                "metricValues",Arrays.stream(values).map(value->Map.of("value",value)).toList());
    }
    private Map<String,Object> searchRow(String key,double clicks,double impressions,double ctr,double position) {
        return Map.of("keys",key==null?List.of():List.of(key),"clicks",clicks,"impressions",impressions,"ctr",ctr,"position",position);
    }
    private GoogleReportingService service(FakeClient client,Clock clock) {
        return new GoogleReportingService(client,"554014865","sc-domain:enrosed.com",clock);
    }
    @Test void missingCredentialsOrProviderConfigurationMakesNoNetworkCalls() {
        var client=new FakeClient(); client.configured=false;
        var report=service(client,Clock.systemUTC()).report(30);
        assertEquals(Status.NOT_CONFIGURED,report.googleAnalytics().status()); assertNull(report.googleAnalytics().data());
        assertEquals(Status.NOT_CONFIGURED,report.searchConsole().status()); assertNull(report.realtime().data());
        assertTrue(client.calls.isEmpty());
        client.configured=true;
        report=new GoogleReportingService(client,"","",Clock.systemUTC()).report(30);
        assertEquals(Status.NOT_CONFIGURED,report.googleAnalytics().status()); assertTrue(client.calls.isEmpty());
    }
    @Test void authoritativeTotalsAreNotSummedFromOverlappingUsersOrIncompleteTopRows() {
        var client=new FakeClient(); var clock=new MutableClock("2026-09-14T10:00:00Z");
        var report=service(client,clock).report(30);
        assertEquals(Status.CONNECTED,report.googleAnalytics().status());
        assertEquals(5,report.googleAnalytics().data().totals().users());
        assertEquals(8,report.googleAnalytics().data().perDay().stream().mapToLong(GaDay::users).sum());
        assertEquals(40,report.searchConsole().data().totals().clicks());
        assertEquals(10,report.searchConsole().data().queries().getFirst().clicks());
        assertEquals(.2,report.searchConsole().data().totals().ctr()); assertEquals(4.5,report.searchConsole().data().totals().position());
        assertEquals(3,report.googleAnalytics().data().totals().keyEvents()); assertEquals(2,report.googleAnalytics().data().events().getFirst().count());
        assertEquals("2026-09-13",report.googleAnalytics().data().availableThrough());
        assertEquals("2026-09-11",report.searchConsole().data().availableThrough());
        assertEquals(30,report.realtime().data().windowMinutes()); assertNull(report.realtime().from());
        String requests=json.valueToTree(client.calls).toString();
        assertTrue(requests.contains("sites/sc-domain%3Aenrosed.com/searchAnalytics/query"));
        assertTrue(requests.contains("generate_lead")); assertFalse(requests.contains("customEvent:"));
        assertTrue(requests.contains("\"dataState\":\"final\""));
        assertEquals(1,client.gaCalls.get()); assertEquals(4,client.searchCalls.get()); assertEquals(1,client.realtimeCalls.get());
    }
    @Test void eachProviderUsesItsOwnCalendarAndDaysAreBounded() {
        var clock=new MutableClock("2026-09-13T22:30:00Z"); var client=new FakeClient();
        var report=service(client,clock).report(1);
        assertEquals("2026-09-14",report.googleAnalytics().to()); assertEquals("2026-09-14",report.googleAnalytics().from());
        assertEquals("2026-09-13",report.searchConsole().to()); assertEquals("2026-09-13",report.searchConsole().from());
        assertEquals(365,service(client,clock).report(999).days()); assertEquals(1,service(client,clock).report(-1).days());
    }
    @Test void newPropertyCanHaveNoProcessedDataWhileRealtimeAlreadyWorksAndDeniedIsNotZero() {
        var clock=new MutableClock("2026-09-14T10:00:00Z"); var client=new FakeClient(); client.empty=true;
        var report=service(client,clock).report(30);
        assertEquals(Status.NO_DATA,report.googleAnalytics().status()); assertNotNull(report.googleAnalytics().data());
        assertEquals(Status.NO_DATA,report.searchConsole().status()); assertEquals(Status.CONNECTED,report.realtime().status());
        client.failGa=true; client.failSearch=true;
        report=service(client,clock).report(30);
        assertEquals(Status.ERROR,report.googleAnalytics().status()); assertEquals("ACCESS_DENIED",report.googleAnalytics().errorCode());
        assertNull(report.googleAnalytics().data()); assertNull(report.searchConsole().data());
        assertEquals(Status.CONNECTED,report.realtime().status());
        client.failRealtime=true; client.failGa=false; client.empty=false;
        report=service(client,clock).report(30);
        assertEquals(Status.CONNECTED,report.googleAnalytics().status()); assertEquals(Status.ERROR,report.realtime().status());
    }
    @Test void cacheHasSeparateRealtimeTtlAndGracefulBoundedStaleData() {
        var clock=new MutableClock("2026-09-14T00:00:00Z"); var client=new FakeClient(); var service=service(client,clock);
        var first=service.report(30); service.report(30); assertEquals(1,client.gaCalls.get()); assertEquals(1,client.realtimeCalls.get());
        clock.advance(Duration.ofMinutes(2)); service.report(30); assertEquals(1,client.gaCalls.get()); assertEquals(2,client.realtimeCalls.get());
        clock.advance(Duration.ofMinutes(14)); client.failGa=true;
        var stale=service.report(30).googleAnalytics(); assertEquals(Status.STALE,stale.status());
        assertEquals(first.googleAnalytics().data(),stale.data()); assertEquals(first.googleAnalytics().fetchedAt(),stale.fetchedAt());
        service.report(30); assertEquals(2,client.gaCalls.get(),"a failure must cool down rather than hammer Google");
        clock.advance(Duration.ofHours(25));
        assertEquals(Status.ERROR,service.report(30).googleAnalytics().status());
        assertNull(service.report(30).googleAnalytics().data());
    }
    @Test void slowRequestsStartCacheFreshnessAndFailureCooldownAtCompletion() {
        var clock=new MutableClock("2026-09-14T10:00:00Z"); var client=new FakeClient();
        client.gaHook=()->clock.advance(Duration.ofSeconds(40));
        var service=service(client,clock); var first=service.report(30);
        assertEquals("2026-09-14T10:00:40Z",first.googleAnalytics().fetchedAt());
        client.failGa=true; clock.advance(Duration.ofMinutes(16));
        service.report(30); service.report(30); assertEquals(2,client.gaCalls.get());
    }
    @Test void exactLiveGoogleEmptyRealtimeEnvelopeMeansZeroActiveUsers() throws Exception {
        var client=new FakeClient();
        client.realtimeOverride=json.readTree("{\"kind\":\"analyticsData#runRealtimeReport\"}");
        var report=service(client,Clock.systemUTC()).report(30);
        assertEquals(Status.NO_DATA,report.realtime().status());
        assertEquals(0,report.realtime().data().activeUsers());
        assertEquals(30,report.realtime().data().windowMinutes());
        assertNull(report.realtime().errorCode());
        assertEquals(Status.CONNECTED,report.googleAnalytics().status());
    }
    @Test void untypedOrMalformedRealtimeBodiesStillFailInsteadOfInventingZero() throws Exception {
        for(String body:List.of("{}", "{\"kind\":\"wrong-kind\"}",
                "{\"kind\":\"analyticsData#runReport\"}",
                "{\"kind\":\"analyticsData#runRealtimeReport\",\"rows\":[{}]}",
                "{\"kind\":\"analyticsData#runRealtimeReport\",\"rows\":null}")) {
            var client=new FakeClient(); client.realtimeOverride=json.readTree(body);
            var report=service(client,Clock.systemUTC()).report(30);
            assertEquals(Status.ERROR,report.realtime().status(),body);
            assertEquals("INVALID_RESPONSE",report.realtime().errorCode(),body);
            assertNull(report.realtime().data(),body);
            assertEquals(Status.CONNECTED,report.googleAnalytics().status());
        }
    }
    @Test void malformedSuccessfulGaResponseIsAnErrorInsteadOfInventedZero() {
        var client=new FakeClient(); client.malformed=true;
        var report=service(client,Clock.systemUTC()).report(30);
        assertEquals(Status.ERROR,report.googleAnalytics().status()); assertEquals("INVALID_RESPONSE",report.googleAnalytics().errorCode());
        assertNull(report.googleAnalytics().data()); assertEquals(Status.CONNECTED,report.realtime().status());
    }
    @Test void exactLiveGoogleEmptyStandardTotalsEnvelopeMeansNoProcessedData() throws Exception {
        var emptyTotals=json.readTree("""
                {"metadata":{"currencyCode":"EUR","timeZone":"Europe/Brussels"},"kind":"analyticsData#runReport"}
                """);
        var emptyEvents=json.readTree("""
                {"dimensionHeaders":[{"name":"eventName"}],"metricHeaders":[{"name":"eventCount","type":"TYPE_INTEGER"}],
                 "metadata":{"currencyCode":"EUR","timeZone":"Europe/Brussels"},"kind":"analyticsData#runReport"}
                """);
        for(JsonNode totals:List.of(emptyTotals,json.readTree("{\"kind\":\"analyticsData#runReport\"}"))) {
            var client=new FakeClient();
            client.gaOverride=json.valueToTree(Map.of("reports",List.of(totals,
                    ga(List.of("date"),List.of()),ga(List.of("pagePath"),List.of()),
                    ga(List.of("sessionDefaultChannelGroup"),List.of()),emptyEvents),
                    "kind","analyticsData#batchRunReports"));
            var source=service(client,Clock.systemUTC()).report(30).googleAnalytics();
            assertEquals(Status.NO_DATA,source.status()); assertNull(source.errorCode());
            assertEquals(0,source.data().totals().users()); assertEquals(0,source.data().totals().sessions());
            assertEquals(0,source.data().totals().views()); assertTrue(source.data().events().isEmpty());
            assertTrue(source.data().perDay().isEmpty()); assertNull(source.data().availableThrough());
            assertEquals("Europe/Brussels",source.data().timeZone());
        }
    }
    @Test void malformedHeaderlessStandardReportsRemainErrors() throws Exception {
        for(String body:List.of("{}", "{\"kind\":\"wrong-kind\"}",
                "{\"kind\":\"analyticsData#runReport\",\"metadata\":null}",
                "{\"kind\":\"analyticsData#runReport\",\"rows\":[{}]}",
                "{\"kind\":\"analyticsData#runReport\",\"rows\":null}",
                "{\"kind\":\"analyticsData#runReport\",\"rowCount\":1}")) {
            var client=new FakeClient();
            client.gaOverride=json.valueToTree(Map.of("reports",List.of(json.readTree(body),
                    ga(List.of("date"),List.of()),ga(List.of("pagePath"),List.of()),
                    ga(List.of("sessionDefaultChannelGroup"),List.of()),ga(List.of("eventName"),List.of()))));
            var report=service(client,Clock.systemUTC()).report(30);
            assertEquals(Status.ERROR,report.googleAnalytics().status(),body);
            assertEquals("INVALID_RESPONSE",report.googleAnalytics().errorCode(),body);
            assertNull(report.googleAnalytics().data(),body);
            assertEquals(Status.CONNECTED,report.realtime().status());
        }
    }
}
