package be.enrosed.analytics;

import java.util.List;

/** Wire shapes of the website statistics: one beacon in, one report out. */
public final class WebsiteAnalyticsDtos {
    private WebsiteAnalyticsDtos() {}

    /**
     * What the website's edge function posts for one page view. {@code internal}
     * is the browser saying "this is one of us": a device that opted out of
     * the statistics is accepted and then forgotten.
     */
    public record VisitInput(String path, String locale, String referrer,
                             String utmSource, String utmMedium, String utmCampaign,
                             Integer screenWidth, String visitor, String country, String city,
                             String device, Boolean internal) {
        public VisitInput(String path, String locale, String referrer,
                          String utmSource, String utmMedium, String utmCampaign,
                          Integer screenWidth, String visitor, String country, String city,
                          String device) {
            this(path, locale, referrer, utmSource, utmMedium, utmCampaign, screenWidth, visitor,
                    country, city, device, null);
        }
    }

    /**
     * The headline numbers. A bounce is a session of one page; the average
     * session length only counts sessions that had a second page, because a
     * single view has no measurable length. {@code activeNow} is how many
     * visitors were seen in the last half hour, whatever the period.
     */
    public record Totals(long visits, long visitors, long sessions, double pagesPerSession,
                         long countries, double bounceRatePct, long avgSessionSeconds,
                         long activeNow) {}

    /** The same window one period earlier, so the headline numbers can say up or down. */
    public record Compare(long visits, long visitors, long sessions, long quoteSessions) {}

    public record DayPoint(String date, long visits, long visitors) {}

    /** Visits in one hour of the day (Brussels time) over the whole period. */
    public record HourPoint(int hour, long visits, long visitors) {}

    public record PageRow(String path, String kind, long visits, long visitors) {}

    public record KindRow(String kind, long visits) {}

    public record CountryRow(String country, long visits, long visitors) {}

    public record CityRow(String city, String country, long visits) {}

    /** kind: DIRECT, SEARCH, SOCIAL, CAMPAIGN or SITE. */
    public record SourceRow(String source, String kind, long visits) {}

    public record DeviceRow(String device, long visits) {}

    public record LocaleRow(String locale, long visits) {}

    /** How far sessions get: a product page, the quote page, the contact page. */
    public record Funnel(long sessions, long productSessions, long quoteSessions, long contactSessions) {}

    public record Report(int days, String from, String to, Totals totals, Compare previous,
                         List<DayPoint> perDay, List<HourPoint> perHour,
                         List<PageRow> pages, List<KindRow> kinds, List<CountryRow> countries,
                         List<CityRow> cities, List<SourceRow> sources,
                         /** Visits per weekday (Monday first) and hour, Brussels time. */
                         int[][] hours,
                         List<DeviceRow> devices, List<LocaleRow> locales,
                         /** Where sessions began and where they ended; visits count sessions here. */
                         List<PageRow> entryPages, List<PageRow> exitPages,
                         Funnel funnel,
                         /** Our own Belgian towns, left out of every number above. */
                         List<String> excludedCities,
                         String generatedAt) {}
}
