package be.enrosed.account;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** Analyses › Bezoekers › Klantlogins: how the website logins are used. Staff only. */
public final class LoginActivityDtos {
    private LoginActivityDtos() {}

    /**
     * The report of one period (days 1, 7, 30, 90 or 365, ending now, Belgian days), for every
     * login or for one (accountId). measuredSince is the first measured row: visits before it are
     * moments without a length. visitsCut says the visit list stops at its limit.
     */
    public record Report(
            int days,
            LocalDate from,
            LocalDate to,
            Instant generatedAt,
            Long accountId,
            Instant measuredSince,
            Totals totals,
            Previous previous,
            List<Day> perDay,
            List<LoginRow> logins,
            List<VisitRow> visits,
            boolean visitsCut
    ) {}

    /**
     * customers: distinct customers with a visit in the period. seconds: the summed length of the
     * measured visits; measuredVisits: how many visits had a length. ordersPlaced: website orders
     * placed by a login in the period. activeNow: logins seen in the last 30 minutes without a
     * logout since. The login counts are of today, whatever the period.
     */
    public record Totals(
            int customers,
            int visits,
            long seconds,
            int measuredVisits,
            int ordersPlaced,
            int activeNow,
            int loginsActive,
            int loginsInvited,
            int loginsWithdrawn
    ) {}

    /** The same figures for the period of equal length just before. */
    public record Previous(int customers, int visits, long seconds, int ordersPlaced) {}

    /** One Belgian day: the customers that had a visit starting that day, and the visits. */
    public record Day(LocalDate date, int customers, int visits) {}

    /**
     * One login with its use in the period. lastActiveAt is of any age. kinds: what the login
     * did in the period (CustomerLoginActivity.Kind names); device: the one used most.
     */
    public record LoginRow(
            long accountId,
            long customerId,
            String company,
            String email,
            String status,
            Instant createdAt,
            Instant activatedAt,
            Instant lastActiveAt,
            Instant linkSentAt,
            Instant linkExpiresAt,
            int visits,
            long seconds,
            int measuredVisits,
            List<Long> orderIds,
            List<String> kinds,
            String device
    ) {}

    /**
     * One visit, newest first. seconds is null for a history visit (only its moment is known).
     * startKind: ACTIVATION, LOGIN or RETURN (came back with a kept login). orderIds: website
     * orders the login placed during the visit.
     */
    public record VisitRow(
            long accountId,
            long customerId,
            String company,
            String email,
            Instant start,
            Instant end,
            Long seconds,
            int actions,
            String startKind,
            List<String> kinds,
            String device,
            boolean history,
            boolean loggedOut,
            List<Long> orderIds
    ) {}
}
