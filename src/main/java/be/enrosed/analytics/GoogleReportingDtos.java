package be.enrosed.analytics;

import java.util.List;

/** Reporting-only projections: Google metrics are never added to first-party visit counters. */
public final class GoogleReportingDtos {
    private GoogleReportingDtos() {}
    public enum Status { NOT_CONFIGURED, CONNECTED, NO_DATA, ERROR, STALE }
    public record Source<T>(Status status, String property, String from, String to,
            String fetchedAt, String errorCode, String message, T data) {}
    public record Report(int days, String from, String to, String generatedAt,
            Source<GaData> googleAnalytics, Source<SearchData> searchConsole, Source<Realtime> realtime) {}
    public record GaTotals(long users, long sessions, long views, long engagedSessions,
            double engagementRate, double keyEvents, double avgSessionDurationSeconds) {}
    public record GaDay(String date, long users, long sessions, long views) {}
    public record GaPage(String path, long views, long users) {}
    public record GaChannel(String channel, long sessions, long users) {}
    public record GaEvent(String name, long count) {}
    public record GaData(GaTotals totals, List<GaDay> perDay, List<GaPage> pages,
            List<GaChannel> channels, List<GaEvent> events, String timeZone,
            String availableThrough, List<String> warnings) {}
    public record SearchTotals(double clicks, double impressions, double ctr, double position) {}
    public record SearchDay(String date, double clicks, double impressions, double ctr, double position) {}
    public record SearchQuery(String query, double clicks, double impressions, double ctr, double position) {}
    public record SearchPage(String page, double clicks, double impressions, double ctr, double position) {}
    public record SearchData(SearchTotals totals, List<SearchDay> perDay, List<SearchQuery> queries,
            List<SearchPage> pages, String dataState, String timeZone,
            String availableThrough, List<String> warnings) {}
    public record Realtime(long activeUsers, int windowMinutes) {}
}
