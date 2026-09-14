package be.enrosed.analytics;

import be.enrosed.analytics.GoogleReportingDtos.*;
import be.enrosed.analytics.GoogleReportingClient.Failure;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

/** Independent read-only provider reports with bounded in-memory caches; no website/ERP data writes. */
@ApplicationScoped
public class GoogleReportingService {
    private static final ZoneId GA_ZONE=ZoneId.of("Europe/Brussels"), SEARCH_ZONE=ZoneId.of("America/Los_Angeles");
    private static final String GA_BASE="https://analyticsdata.googleapis.com/v1beta/properties/";
    private final GoogleReportingClient client;
    private final String property, site;
    private final Clock clock;
    private final ReportCache<GaData> gaCache=new ReportCache<>(Duration.ofMinutes(15),Duration.ofHours(24));
    private final ReportCache<SearchData> searchCache=new ReportCache<>(Duration.ofMinutes(15),Duration.ofHours(24));
    private final ReportCache<SearchData> searchDetailCache=new ReportCache<>(Duration.ofMinutes(15),Duration.ofHours(24));
    private final ReportCache<SearchAvailability> searchAvailabilityCache=new ReportCache<>(Duration.ofMinutes(15),Duration.ofHours(24));
    private final ReportCache<Realtime> realtimeCache=new ReportCache<>(Duration.ofMinutes(1),Duration.ofMinutes(5));

    @Inject
    public GoogleReportingService(GoogleReportingClient client,
            @ConfigProperty(name="enrosed.google-reporting.analytics-property-id") Optional<String> property,
            @ConfigProperty(name="enrosed.google-reporting.search-console-site-url") Optional<String> site) {
        this(client,property.orElse(""),site.orElse(""),Clock.systemUTC());
    }
    GoogleReportingService(GoogleReportingClient client,String property,String site,Clock clock) {
        this.client=client; this.property=property.trim(); this.site=site.trim(); this.clock=clock;
    }

    public Report report(int requestedDays) {
        int days=Math.max(1,Math.min(365,requestedDays));
        Period gaPeriod=period(days,GA_ZONE), searchPeriod=period(days,SEARCH_ZONE);
        // One slow/denied provider cannot hide the other provider or realtime. No background polling.
        try (var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            var ga=CompletableFuture.supplyAsync(()->source(property,validProperty(),gaPeriod,gaCache,
                    ()->fetchGa(gaPeriod), data->data.totals().users()==0 && data.totals().views()==0 && data.totals().sessions()==0),executor);
            var search=CompletableFuture.supplyAsync(()->source(site,validSite(),searchPeriod,searchCache,
                    ()->fetchSearch(searchPeriod),data->data.totals().impressions()==0 && data.totals().clicks()==0),executor);
            var realtime=CompletableFuture.supplyAsync(()->source(property,validProperty(),null,realtimeCache,
                    this::fetchRealtime,data->data.activeUsers()==0),executor);
            Source<GaData> gaResult=ga.join(); Source<SearchData> searchResult=search.join(); Source<Realtime> realtimeResult=realtime.join();
            return new Report(days,gaPeriod.from,gaPeriod.to,clock.instant().toString(),gaResult,searchResult,realtimeResult);
        }
    }
    private <T> Source<T> source(String identity,boolean valid,Period period,ReportCache<T> cache,
            Supplier<T> fetch,java.util.function.Predicate<T> empty) {
        String from=period==null?null:period.from, to=period==null?null:period.to;
        if (!client.configured() || identity.isBlank()) return new Source<>(Status.NOT_CONFIGURED,identity,from,to,
                null,"NOT_CONFIGURED","Google-rapportage is nog niet gekoppeld.",null);
        if (!valid) return new Source<>(Status.ERROR,identity,from,to,null,"INVALID_CONFIGURATION",
                message("INVALID_CONFIGURATION"),null);
        return cache.get(identity+":"+from+":"+to,clock,identity,from,to,fetch,empty);
    }
    private boolean validProperty() { return property.matches("[0-9]{1,20}"); }
    private boolean validSite() {
        return site.matches("sc-domain:[a-zA-Z0-9.-]+") || site.matches("https://[a-zA-Z0-9.-]+(?::[0-9]+)?/[^?#\\s]*");
    }
    private Period period(int days,ZoneId zone) {
        LocalDate to=LocalDate.now(clock.withZone(zone)); return new Period(to.minusDays(days-1L).toString(),to.toString());
    }
    private record Period(String from,String to) {}

    /** Search-only entry: no GA batch or realtime work, even on a cold cache. */
    public SearchReport searchConsole(int requestedDays) {
        int days=Math.max(1,Math.min(365,requestedDays));
        LocalDate today=LocalDate.now(clock.withZone(SEARCH_ZONE));
        Period probe=new Period(today.minusDays(13).toString(),today.toString());
        Source<SearchAvailability> availability=source(site,validSite(),probe,searchAvailabilityCache,
                ()->fetchSearchAvailability(probe,today),value->value.through==null);
        SearchAvailability known=availability.data();
        LocalDate through=known!=null && known.through!=null?known.through:today.minusDays(1);
        Period effective=new Period(through.minusDays(days-1L).toString(),through.toString());
        Source<SearchData> result=source(site,validSite(),effective,searchDetailCache,
                ()->fetchSearchDetail(effective,days,today,availability),
                data->data.totals().impressions()==0 && data.totals().clicks()==0);
        return new SearchReport(days,clock.instant().toString(),result);
    }

    private record SearchAvailability(LocalDate through,String basis) {}

    private String searchEndpoint() {
        return "https://www.googleapis.com/webmasters/v3/sites/"
                +URLEncoder.encode(site,StandardCharsets.UTF_8)+"/searchAnalytics/query";
    }

    private SearchAvailability fetchSearchAvailability(Period probe,LocalDate today) {
        // Fresh metrics are never displayed. Google's metadata identifies where final
        // calendar days stop, including final days with no impressions/omitted rows.
        var request=new LinkedHashMap<>(searchRequest(probe,List.of("date"),14));
        request.put("dataState","all");
        JsonNode response=client.post(searchEndpoint(),request);
        searchRows(response);
        JsonNode metadata=response.path("metadata");
        JsonNode incomplete=metadata.has("firstIncompleteDate")?metadata.get("firstIncompleteDate"):
                metadata.path("first_incomplete_date");
        if (metadata.has("firstIncompleteDate") && metadata.has("first_incomplete_date")
                && !metadata.get("firstIncompleteDate").equals(metadata.get("first_incomplete_date"))) throw new Failure("INVALID_RESPONSE");
        if (!incomplete.isMissingNode()) {
            LocalDate first=searchDate(incomplete.asText());
            if (first.isBefore(LocalDate.parse(probe.from)) || first.isAfter(today)) throw new Failure("INVALID_RESPONSE");
            return new SearchAvailability(first.minusDays(1),"GOOGLE_FINAL_BOUNDARY");
        }
        // An absent metadata field does not prove that recent zero-traffic days are
        // complete. Fall back explicitly to the latest date actually reported as final.
        List<SearchDay> finalDays=searchDays(client.post(searchEndpoint(),searchRequest(probe,List.of("date"),14)),probe);
        LocalDate latest=finalDays.stream().map(day->LocalDate.parse(day.date()))
                .filter(day->day.isBefore(today)).max(Comparator.naturalOrder()).orElse(null);
        return new SearchAvailability(latest,latest==null?"UNCONFIRMED":"LATEST_REPORTED_FINAL_DAY");
    }

    private record SearchPart<T>(T data,String error) {}
    private static <T> SearchPart<T> searchPart(Supplier<T> fetch) {
        try { return new SearchPart<>(fetch.get(),null); }
        catch (Failure failed) { return new SearchPart<>(null,failed.code); }
        catch (RuntimeException failed) { return new SearchPart<>(null,"UNAVAILABLE"); }
    }

    private SearchData fetchSearchDetail(Period period,int days,LocalDate today,Source<SearchAvailability> availability) {
        var warnings=new ArrayList<String>();
        warnings.add("Alle cijfers zijn definitieve webzoekresultaten, per kalenderdag in America/Los_Angeles. Begin- en einddatum tellen mee.");
        warnings.add("Zoekopdrachten en pagina’s tonen maximaal 100 resultaten, gerangschikt op klikken. Geanonimiseerde zoekopdrachten ontbreken; tel deze rijen niet op voor het totaal.");
        SearchAvailability known=availability.data();
        String basis=known==null?"UNCONFIRMED":known.basis;
        if ("LATEST_REPORTED_FINAL_DAY".equals(basis)) warnings.add("Google gaf geen grens voor de verwerking. De periode eindigt op de laatste dag waarvoor Google definitieve gegevens teruggaf.");
        if (availability.status()==Status.STALE) warnings.add("De laatst bekende verwerkingsgrens wordt gebruikt; de nieuwe controle bij Google is tijdelijk mislukt.");
        LocalDate previousTo=LocalDate.parse(period.from).minusDays(1);
        Period previous=new Period(previousTo.minusDays(days-1L).toString(),previousTo.toString());
        String comparisonError="UNCONFIRMED".equals(basis)?"FINAL_DATES_UNCONFIRMED":
                LocalDate.parse(previous.from).isBefore(today.minusMonths(16))?"COMPARISON_OUTSIDE_RETENTION":null;
        if (comparisonError!=null) warnings.add(searchMessage(comparisonError));
        try (var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            var totals=CompletableFuture.supplyAsync(()->searchPart(()->searchTotals(client.post(searchEndpoint(),searchRequest(period,List.of(),1)))),executor);
            var perDay=CompletableFuture.supplyAsync(()->searchPart(()->searchDays(client.post(searchEndpoint(),searchRequest(period,List.of("date"),366)),period)),executor);
            var queries=CompletableFuture.supplyAsync(()->searchPart(()->searchQueries(client.post(searchEndpoint(),searchRequest(period,List.of("query"),100)))),executor);
            var pages=CompletableFuture.supplyAsync(()->searchPart(()->searchPages(client.post(searchEndpoint(),searchRequest(period,List.of("page"),100)))),executor);
            var devices=CompletableFuture.supplyAsync(()->searchPart(()->searchDevices(client.post(searchEndpoint(),searchRequest(period,List.of("device"),3)))),executor);
            var comparison=comparisonError==null
                    ? CompletableFuture.supplyAsync(()->searchPart(()->searchTotals(client.post(searchEndpoint(),searchRequest(previous,List.of(),1)))),executor)
                    : CompletableFuture.completedFuture(new SearchPart<SearchTotals>(null,comparisonError));
            SearchPart<SearchTotals> core=totals.join();
            if (core.error!=null) throw new Failure(core.error);
            var dayResult=perDay.join(); var queryResult=queries.join(); var pageResult=pages.join();
            var deviceResult=devices.join(); var previousResult=comparison.join();
            var issues=new ArrayList<SearchIssue>();
            if (known==null || known.through==null) issues.add(new SearchIssue("AVAILABILITY",
                    availability.errorCode()==null?"FINAL_DATES_UNCONFIRMED":availability.errorCode(),searchMessage("FINAL_DATES_UNCONFIRMED")));
            addSearchIssue(issues,"PER_DAY",dayResult); addSearchIssue(issues,"QUERIES",queryResult); addSearchIssue(issues,"PAGES",pageResult);
            SearchComparison compared=new SearchComparison(previousResult.error==null
                    ? searchStatus(previousResult.data):Status.ERROR,previous.from,previous.to,previousResult.data,
                    previousResult.error,previousResult.error==null?null:searchMessage(previousResult.error));
            SearchDevices breakdown=new SearchDevices(deviceResult.error==null
                    ? deviceResult.data.isEmpty()?Status.NO_DATA:Status.CONNECTED:Status.ERROR,
                    deviceResult.data,deviceResult.error,deviceResult.error==null?null:searchMessage(deviceResult.error));
            List<SearchDay> dates=dayResult.data==null?List.of():dayResult.data;
            return new SearchData(core.data,dates,queryResult.data==null?List.of():queryResult.data,
                    pageResult.data==null?List.of():pageResult.data,"final",SEARCH_ZONE.getId(),
                    known!=null&&known.through!=null?known.through.toString():dates.isEmpty()?null:dates.getLast().date(),
                    List.copyOf(warnings),compared,breakdown,100,basis,List.copyOf(issues));
        }
    }

    private static void addSearchIssue(List<SearchIssue> issues,String section,SearchPart<?> part) {
        if (part.error!=null) issues.add(new SearchIssue(section,part.error,searchMessage(part.error)));
    }
    private static String searchMessage(String code) {
        return switch (code) {
            case "FINAL_DATES_UNCONFIRMED" -> "De definitieve einddatum kon niet worden vastgesteld. De cijfers blijven beschikbaar, maar een betrouwbare periodevergelijking ontbreekt.";
            case "COMPARISON_OUTSIDE_RETENTION" -> "De vorige periode valt deels buiten de circa 16 maanden die Search Console bewaart. Kies een kortere periode om eerlijk te vergelijken.";
            default -> message(code);
        };
    }
    private static Status searchStatus(SearchTotals totals) {
        return totals.clicks()==0 && totals.impressions()==0?Status.NO_DATA:Status.CONNECTED;
    }
    private static SearchTotals searchTotals(JsonNode response) {
        JsonNode row=firstSearchRow(response);
        return new SearchTotals(number(row,"clicks"),number(row,"impressions"),number(row,"ctr"),number(row,"position"));
    }
    private static LocalDate searchDate(String text) {
        try { return LocalDate.parse(text); } catch (RuntimeException invalid) { throw new Failure("INVALID_RESPONSE"); }
    }
    private static List<SearchDay> searchDays(JsonNode response,Period period) {
        var days=new TreeMap<String,SearchDay>();
        for (JsonNode row:searchRows(response)) {
            String date=searchDate(searchKey(row)).toString();
            if (date.compareTo(period.from)<0 || date.compareTo(period.to)>0 || days.containsKey(date)) throw new Failure("INVALID_RESPONSE");
            days.put(date,new SearchDay(date,number(row,"clicks"),number(row,"impressions"),number(row,"ctr"),number(row,"position")));
        }
        return List.copyOf(days.values());
    }
    private static List<SearchQuery> searchQueries(JsonNode response) {
        var rows=new ArrayList<SearchQuery>();
        for (JsonNode row:searchRows(response)) rows.add(new SearchQuery(searchKey(row),number(row,"clicks"),number(row,"impressions"),number(row,"ctr"),number(row,"position")));
        if (rows.size()>100) throw new Failure("INVALID_RESPONSE");
        return List.copyOf(rows);
    }
    private static List<SearchPage> searchPages(JsonNode response) {
        var rows=new ArrayList<SearchPage>();
        for (JsonNode row:searchRows(response)) rows.add(new SearchPage(searchKey(row),number(row,"clicks"),number(row,"impressions"),number(row,"ctr"),number(row,"position")));
        if (rows.size()>100) throw new Failure("INVALID_RESPONSE");
        return List.copyOf(rows);
    }
    private static List<SearchDevice> searchDevices(JsonNode response) {
        var rows=new ArrayList<SearchDevice>();
        for (JsonNode row:searchRows(response)) {
            String device=searchKey(row);
            if (!Set.of("DESKTOP","MOBILE","TABLET").contains(device)) throw new Failure("INVALID_RESPONSE");
            rows.add(new SearchDevice(device,number(row,"clicks"),number(row,"impressions"),number(row,"ctr"),number(row,"position")));
        }
        if (rows.size()>3) throw new Failure("INVALID_RESPONSE");
        return List.copyOf(rows);
    }

    private GaData fetchGa(Period period) {
        List<Map<String,Object>> requests=List.of(
                gaRequest(period,List.of(),List.of("activeUsers","sessions","screenPageViews","engagedSessions","engagementRate","keyEvents","averageSessionDuration"),1),
                gaRequest(period,List.of("date"),List.of("activeUsers","sessions","screenPageViews"),366),
                gaRequest(period,List.of("pagePath"),List.of("screenPageViews","activeUsers"),20),
                gaRequest(period,List.of("sessionDefaultChannelGroup"),List.of("sessions","activeUsers"),20),
                leadRequest(period));
        JsonNode response=client.post(GA_BASE+property+":batchRunReports",Map.of("requests",requests));
        JsonNode reports=response.path("reports");
        if (!reports.isArray() || reports.size()!=5) throw new Failure("INVALID_RESPONSE");
        var warnings=new LinkedHashSet<String>();
        warnings.add("Google Analytics verwerkt standaardrapporten met vertraging; vandaag kan onvolledig zijn.");
        for (JsonNode report:reports) {
            JsonNode meta=report.path("metadata");
            if (meta.path("subjectToThresholding").asBoolean()) warnings.add("Google past privacydrempels toe; sommige rijen kunnen ontbreken.");
            if (meta.path("dataLossFromOtherRow").asBoolean()) warnings.add("Google groepeert een deel van de gegevens onder overige waarden.");
            if (meta.path("samplingMetadatas").isArray() && !meta.path("samplingMetadatas").isEmpty()) warnings.add("Dit Google-rapport bevat steekproefgegevens.");
        }
        JsonNode totals=firstGaRow(reports.get(0));
        var summary=new GaTotals(integerMetric(totals,0),integerMetric(totals,1),integerMetric(totals,2),
                integerMetric(totals,3),metric(totals,4),metric(totals,5),metric(totals,6));
        List<GaDay> days=new ArrayList<>();
        for (JsonNode row:gaRows(reports.get(1))) days.add(new GaDay(gaDate(dimension(row)),integerMetric(row,0),integerMetric(row,1),integerMetric(row,2)));
        days.sort(Comparator.comparing(GaDay::date));
        List<GaPage> pages=new ArrayList<>();
        for (JsonNode row:gaRows(reports.get(2))) pages.add(new GaPage(dimension(row),integerMetric(row,0),integerMetric(row,1)));
        List<GaChannel> channels=new ArrayList<>();
        for (JsonNode row:gaRows(reports.get(3))) channels.add(new GaChannel(dimension(row),integerMetric(row,0),integerMetric(row,1)));
        List<GaEvent> events=new ArrayList<>();
        for (JsonNode row:gaRows(reports.get(4))) events.add(new GaEvent(dimension(row),integerMetric(row,0)));
        String timezone=reports.get(0).path("metadata").path("timeZone").asText(GA_ZONE.getId());
        if (!GA_ZONE.getId().equals(timezone)) warnings.add("De rapportperiode is gekozen vanuit Europe/Brussels; Google groepeert dagen volgens "+timezone+".");
        warnings.add("Pagina’s en kanalen tonen maximaal 20 resultaten. Gebruikers zijn niet optelbaar over dagen of pagina’s.");
        return new GaData(summary,List.copyOf(days),List.copyOf(pages),List.copyOf(channels),List.copyOf(events),
                timezone,days.isEmpty()?null:days.getLast().date(),List.copyOf(warnings));
    }
    private static Map<String,Object> gaRequest(Period period,List<String> dimensions,List<String> metrics,int limit) {
        Map<String,Object> request=new LinkedHashMap<>();
        request.put("dateRanges",List.of(Map.of("startDate",period.from,"endDate",period.to)));
        request.put("dimensions",dimensions.stream().map(name->Map.of("name",name)).toList());
        request.put("metrics",metrics.stream().map(name->Map.of("name",name)).toList());
        request.put("limit",String.valueOf(limit));
        if (!dimensions.isEmpty()) request.put("orderBys",List.of("date".equals(dimensions.getFirst())
                ? Map.of("dimension",Map.of("dimensionName","date"))
                : Map.of("metric",Map.of("metricName",metrics.getFirst()),"desc",true)));
        return request;
    }
    private static Map<String,Object> leadRequest(Period period) {
        Map<String,Object> request=gaRequest(period,List.of("eventName"),List.of("eventCount"),1);
        request.put("dimensionFilter",Map.of("filter",Map.of("fieldName","eventName","stringFilter",
                Map.of("matchType","EXACT","value","generate_lead","caseSensitive",true)))); return request;
    }
    private Realtime fetchRealtime() {
        JsonNode response=client.post(GA_BASE+property+":runRealtimeReport",
                Map.of("metrics",List.of(Map.of("name","activeUsers")),"limit","1",
                        "minuteRanges",List.of(Map.of("startMinutesAgo",29,"endMinutesAgo",0))));
        // Google returns only this typed envelope when no users are active. Unlike an
        // arbitrary empty/malformed response, this is a successful, explicit zero result.
        if (response.size()==1 && "analyticsData#runRealtimeReport".equals(response.path("kind").asText())) {
            return new Realtime(0,30);
        }
        if (!response.has("metricHeaders")) throw new Failure("INVALID_RESPONSE");
        return new Realtime(integerMetric(firstGaRow(response),0),30);
    }
    private SearchData fetchSearch(Period period) {
        String endpoint="https://www.googleapis.com/webmasters/v3/sites/"
                + URLEncoder.encode(site,StandardCharsets.UTF_8)+"/searchAnalytics/query";
        List<JsonNode> reports;
        try (var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            var tasks=List.of(List.<String>of(),List.of("date"),List.of("query"),List.of("page")).stream()
                    .map(dimensions->CompletableFuture.supplyAsync(()->client.post(endpoint,
                            searchRequest(period,dimensions)),executor)).toList();
            try { reports=tasks.stream().map(CompletableFuture::join).toList(); }
            catch (java.util.concurrent.CompletionException failure) {
                if (failure.getCause() instanceof Failure known) throw known;
                throw new Failure("UNAVAILABLE");
            }
        }
        JsonNode summary=firstSearchRow(reports.getFirst());
        var totals=new SearchTotals(number(summary,"clicks"),number(summary,"impressions"),number(summary,"ctr"),number(summary,"position"));
        List<SearchDay> days=new ArrayList<>();
        for (JsonNode row:searchRows(reports.get(1))) days.add(new SearchDay(searchKey(row),number(row,"clicks"),number(row,"impressions"),number(row,"ctr"),number(row,"position")));
        days.sort(Comparator.comparing(SearchDay::date));
        List<SearchQuery> queries=new ArrayList<>();
        for (JsonNode row:searchRows(reports.get(2))) queries.add(new SearchQuery(searchKey(row),number(row,"clicks"),number(row,"impressions"),number(row,"ctr"),number(row,"position")));
        List<SearchPage> pages=new ArrayList<>();
        for (JsonNode row:searchRows(reports.get(3))) pages.add(new SearchPage(searchKey(row),number(row,"clicks"),number(row,"impressions"),number(row,"ctr"),number(row,"position")));
        return new SearchData(totals,List.copyOf(days),List.copyOf(queries),List.copyOf(pages),"final",SEARCH_ZONE.getId(),
                days.isEmpty()?null:days.getLast().date(),List.of(
                    "Search Console levert alleen definitieve gegevens, doorgaans enkele dagen later.",
                    "Zoekopdrachten en pagina’s tonen maximaal 20 resultaten. Geanonimiseerde zoekopdrachten ontbreken; tel deze rijen niet op voor het totaal."));
    }
    private static Map<String,Object> searchRequest(Period period,List<String> dimensions) {
        return Map.of("startDate",period.from,"endDate",period.to,"dimensions",dimensions,
                "type","web","dataState","final","rowLimit",dimensions.isEmpty()?1:dimensions.getFirst().equals("date")?366:20);
    }
    private static Map<String,Object> searchRequest(Period period,List<String> dimensions,int limit) {
        return Map.of("startDate",period.from,"endDate",period.to,"dimensions",dimensions,
                "type","web","dataState","final","rowLimit",limit,
                "aggregationType",dimensions.contains("page")?"auto":"byProperty");
    }
    private static JsonNode gaRows(JsonNode report) {
        // Empty standard totals can omit both rows and headers. Accept only the
        // typed Google envelope, optionally carrying its metadata object.
        if (report.isObject() && "analyticsData#runReport".equals(report.path("kind").asText())
                && (report.size()==1 || (report.size()==2 && report.path("metadata").isObject()))) {
            return com.fasterxml.jackson.databind.node.MissingNode.getInstance();
        }
        if (!report.isObject() || !report.path("metricHeaders").isArray() || report.path("metricHeaders").isEmpty()) throw new Failure("INVALID_RESPONSE");
        JsonNode rows=report.path("rows");
        if (!rows.isMissingNode() && !rows.isArray()) throw new Failure("INVALID_RESPONSE"); return rows;
    }
    private static JsonNode firstGaRow(JsonNode report) {
        JsonNode rows=gaRows(report); return rows.isEmpty()?null:rows.get(0);
    }
    private static JsonNode searchRows(JsonNode report) {
        if (report==null || !report.isObject()) throw new Failure("INVALID_RESPONSE");
        JsonNode rows=report.path("rows"); if (!rows.isMissingNode() && !rows.isArray()) throw new Failure("INVALID_RESPONSE"); return rows;
    }
    private static JsonNode firstSearchRow(JsonNode report) {
        JsonNode rows=searchRows(report); return rows.isEmpty()?null:rows.get(0);
    }
    private static String dimension(JsonNode row) { return row.path("dimensionValues").path(0).path("value").asText(); }
    private static String searchKey(JsonNode row) {
        JsonNode keys=row.path("keys");
        if (!keys.isArray() || keys.size()!=1 || !keys.get(0).isTextual() || keys.get(0).asText().isBlank()) throw new Failure("INVALID_RESPONSE");
        return keys.get(0).asText();
    }
    private static String gaDate(String value) {
        try { return LocalDate.parse(value,DateTimeFormatter.BASIC_ISO_DATE).toString(); }
        catch (Exception invalid) { throw new Failure("INVALID_RESPONSE"); }
    }
    private static double metric(JsonNode row,int index) {
        if (row==null) return 0;
        JsonNode value=row.path("metricValues").path(index).path("value");
        if (!value.isTextual()) throw new Failure("INVALID_RESPONSE");
        return finite(value.asText());
    }
    private static long integerMetric(JsonNode row,int index) {
        double value=metric(row,index); if (value!=Math.rint(value) || value>Long.MAX_VALUE) throw new Failure("INVALID_RESPONSE"); return (long)value;
    }
    private static double number(JsonNode row,String key) {
        if (row==null) return 0;
        JsonNode value=row.path(key); if (!value.isNumber()) throw new Failure("INVALID_RESPONSE"); return finite(value.asText());
    }
    private static double finite(String text) {
        try { double value=Double.parseDouble(text); if (!Double.isFinite(value) || value<0) throw new NumberFormatException(); return value; }
        catch (NumberFormatException invalid) { throw new Failure("INVALID_RESPONSE"); }
    }
    static String message(String code) {
        return switch(code) {
            case "INVALID_CONFIGURATION" -> "De Google-koppeling is niet correct geconfigureerd.";
            case "AUTH_FAILED" -> "De server kan zich niet aanmelden bij Google. Controleer de koppeling.";
            case "ACCESS_DENIED" -> "Google geeft geen toegang tot deze property, of de rapportage-API is niet ingeschakeld.";
            case "NOT_FOUND" -> "Google kan deze property of site niet vinden.";
            case "QUOTA_EXCEEDED" -> "Google heeft tijdelijk de rapportagelimiet bereikt. Probeer later opnieuw.";
            case "INVALID_REQUEST", "INVALID_RESPONSE" -> "Google kon dit rapport niet correct leveren. Probeer later opnieuw.";
            default -> "Google-rapportage is tijdelijk niet bereikbaar. Probeer later opnieuw.";
        };
    }
    private static final class ReportCache<T> {
        private record Entry<T>(Source<T> source,Instant expiresAt,Instant successfulAt) {}
        private final Map<String,Entry<T>> entries=new LinkedHashMap<>(16,0.75f,true);
        private final Duration ttl,maxStale;
        ReportCache(Duration ttl,Duration maxStale) { this.ttl=ttl; this.maxStale=maxStale; }
        synchronized Source<T> get(String key,Clock clock,String identity,String from,String to,
                Supplier<T> fetch,java.util.function.Predicate<T> empty) {
            Instant now=clock.instant();
            Entry<T> previous=entries.get(key);
            if (previous!=null && now.isBefore(previous.expiresAt)) return previous.source;
            Source<T> result; Instant successfulAt=null; Duration next=ttl;
            try {
                T data=fetch.get(); now=clock.instant(); successfulAt=now;
                result=new Source<>(empty.test(data)?Status.NO_DATA:Status.CONNECTED,identity,from,to,now.toString(),null,
                        empty.test(data)?"Google heeft voor deze periode nog geen gegevens gerapporteerd.":null,data);
            } catch (Exception failure) {
                now=clock.instant();
                String code=failure instanceof Failure known?known.code:"UNAVAILABLE";
                boolean stale=previous!=null && previous.source.data()!=null && previous.successfulAt!=null
                        && now.isBefore(previous.successfulAt.plus(maxStale));
                successfulAt=stale?previous.successfulAt:null;
                result=new Source<>(stale?Status.STALE:Status.ERROR,identity,from,to,
                        stale?previous.source.fetchedAt():null,code,message(code),stale?previous.source.data():null);
                next=Duration.ofSeconds(30);
            }
            entries.put(key,new Entry<>(result,now.plus(next),successfulAt));
            while (entries.size()>16) entries.remove(entries.keySet().iterator().next());
            return result;
        }
    }
}
