package be.enrosed.analytics;

import java.util.List;

/** Wire shapes of the website statistics: one beacon in, one report out. */
public final class WebsiteAnalyticsDtos {
    private WebsiteAnalyticsDtos() {}

    /** What the website's edge function posts for one page view. */
    public record VisitInput(String path, String locale, String referrer,
                             String utmSource, String utmMedium, String utmCampaign,
                             Integer screenWidth, String visitor, String country, String city,
                             String device) {}

    public record Totals(long visits, long visitors, long sessions, double pagesPerSession,
                         long countries) {}

    public record DayPoint(String date, long visits, long visitors) {}

    public record PageRow(String path, String kind, long visits, long visitors) {}

    public record KindRow(String kind, long visits) {}

    public record CountryRow(String country, long visits, long visitors) {}

    public record CityRow(String city, String country, long visits) {}

    /** kind: DIRECT, SEARCH, SOCIAL, CAMPAIGN or SITE. */
    public record SourceRow(String source, String kind, long visits) {}

    public record DeviceRow(String device, long visits) {}

    public record LocaleRow(String locale, long visits) {}

    public record Report(int days, String from, String to, Totals totals, List<DayPoint> perDay,
                         List<PageRow> pages, List<KindRow> kinds, List<CountryRow> countries,
                         List<CityRow> cities, List<SourceRow> sources,
                         /** Visits per weekday (Monday first) and hour, Brussels time. */
                         int[][] hours,
                         List<DeviceRow> devices, List<LocaleRow> locales,
                         /** Our own Belgian towns, left out of every number above. */
                         List<String> excludedCities) {}
}
