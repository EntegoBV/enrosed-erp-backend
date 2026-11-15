package be.enrosed.account;

import be.enrosed.account.CustomerLoginActivity.Kind;
import be.enrosed.account.CustomerLoginActivity.Moment;
import be.enrosed.account.CustomerLoginActivity.Visit;
import be.enrosed.account.LoginActivityDtos.Day;
import be.enrosed.account.LoginActivityDtos.LoginRow;
import be.enrosed.account.LoginActivityDtos.Previous;
import be.enrosed.account.LoginActivityDtos.Report;
import be.enrosed.account.LoginActivityDtos.Totals;
import be.enrosed.account.LoginActivityDtos.VisitRow;
import be.enrosed.sales.application.WebOrders;
import be.enrosed.sales.application.port.out.SalesRepositories;
import be.enrosed.sales.domain.Customer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * Analyses › Bezoekers › Klantlogins: the visits of the website logins in a period, per login
 * and per day, against the period of equal length before. Reads only.
 */
@ApplicationScoped
public class CustomerLoginReport {
    static final ZoneId ZONE = ZoneId.of("Europe/Brussels");
    static final List<Integer> PERIODS = List.of(1, 7, 30, 90, 365);
    static final int MAX_VISITS = 300;

    @Inject SalesRepositories.Customers customers;
    @Inject WebOrders webOrders;

    private final Clock clock = Clock.systemUTC();

    private record PlacedOrder(long accountId, Long customerId, Instant at, long salesOrderId) {}

    @Transactional
    public Report report(int requestedDays, Long accountId) {
        int days = PERIODS.contains(requestedDays) ? requestedDays : 30;
        Instant now = clock.instant();
        LocalDate today = LocalDate.ofInstant(now, ZONE);
        LocalDate fromDay = today.minusDays(days - 1L);
        Instant from = fromDay.atStartOfDay(ZONE).toInstant();
        Instant previousFrom = fromDay.minusDays(days).atStartOfDay(ZONE).toInstant();
        /* The period before ends at the same clock time (as Eigen meting compares): today until
           10:00 against yesterday until 10:00, never against the whole of yesterday. */
        Instant previousEnd = now.atZone(ZONE).minusDays(days).toInstant();
        Instant end = now.plusSeconds(1);

        List<CustomerAccountEntity> accounts = accountId == null
                ? CustomerAccountEntity.list("order by id")
                : CustomerAccountEntity.list("id = ?1", accountId);
        /* One login (from a customer sheet) is shown for its present customer only: visits it made
           for an earlier customer, before it was withdrawn and moved, belong to that customer. */
        Long presentCustomer = accountId == null || accounts.isEmpty() ? null : accounts.get(0).customerId;
        List<Visit> all = CustomerLoginActivity.visits(
                CustomerLoginActivity.moments(previousFrom, end, accountId, presentCustomer));
        List<Visit> current = all.stream().filter(visit -> !visit.start().isBefore(from)).toList();
        List<Visit> before = all.stream()
                .filter(visit -> !visit.start().isBefore(previousFrom) && visit.start().isBefore(previousEnd)).toList();

        List<PlacedOrder> orders = new ArrayList<>();
        for (WebOrders.Row row : webOrders.indexStates().values()) {
            if (row.accountId() == null || row.placedAt() == null || row.placedAt().isBefore(previousFrom)) continue;
            if (accountId != null && !accountId.equals(row.accountId())) continue;
            orders.add(new PlacedOrder(row.accountId(), row.customerId(), row.placedAt(), row.salesOrderId()));
        }
        List<PlacedOrder> ordersNow = orders.stream().filter(order -> !order.at().isBefore(from)).toList();
        int ordersBefore = (int) orders.stream().filter(order -> order.at().isBefore(previousEnd)).count();

        Map<Long, String> companies = new HashMap<>();
        Map<Long, CustomerAccountEntity> byId = new LinkedHashMap<>();
        for (CustomerAccountEntity account : accounts) byId.put(account.id, account);

        Map<String, Instant> lastMoments = CustomerLoginActivity.lastMoments(new ArrayList<>(byId.keySet()));
        Map<Long, Instant> lastSessions = lastSessionSeen(byId.keySet());
        Map<Long, Instant> linkExpiry = openLinks(byId.keySet());

        /* One row per login and customer: a login moved to another customer (only possible after
           a withdrawal) keeps its earlier visits with the earlier customer, as a withdrawn row. */
        List<LoginRow> logins = new ArrayList<>();
        for (CustomerAccountEntity account : accounts) {
            Map<Long, List<Visit>> byCustomer = new LinkedHashMap<>();
            byCustomer.put(account.customerId, new ArrayList<>());
            for (Visit visit : current) {
                if (visit.accountId() == account.id) {
                    byCustomer.computeIfAbsent(visit.customerId(), ignored -> new ArrayList<>()).add(visit);
                }
            }
            for (Map.Entry<Long, List<Visit>> share : byCustomer.entrySet()) {
                long customerId = share.getKey();
                List<Visit> own = share.getValue();
                boolean moved = customerId != account.customerId;
                boolean withdrawn = moved || CustomerAccountService.DISABLED.equals(account.status);
                /* A withdrawn login without use in the period is history: it stays off the list. */
                if (withdrawn && own.isEmpty() && (accountId == null || moved)) continue;
                Set<Kind> kinds = EnumSet.noneOf(Kind.class);
                Map<String, Integer> devices = new HashMap<>();
                long seconds = 0;
                int measured = 0;
                for (Visit visit : own) {
                    kinds.addAll(visit.kinds());
                    if (visit.device() != null) devices.merge(visit.device(), visit.actions(), Integer::sum);
                    if (visit.seconds() != null) {
                        seconds += visit.seconds();
                        measured++;
                    }
                }
                List<Long> orderIds = ordersNow.stream()
                        .filter(order -> order.accountId() == account.id && Objects.equals(order.customerId(), customerId))
                        .sorted(Comparator.comparing(PlacedOrder::at)).map(PlacedOrder::salesOrderId).toList();
                Instant lastEvent = lastMoments.get(CustomerLoginActivity.key(account.id, customerId));
                logins.add(new LoginRow(account.id, customerId, company(companies, customerId),
                        account.email, moved ? CustomerAccountService.DISABLED : account.status, account.createdAt,
                        moved ? null : account.passwordSetAt,
                        moved ? lastEvent : latest(account.lastLoginAt, lastEvent, lastSessions.get(account.id)),
                        moved ? null : account.lastLinkSentAt, moved ? null : linkExpiry.get(account.id),
                        own.size(), seconds, measured, orderIds, names(kinds), mostUsed(devices), moved));
            }
        }
        logins.sort(Comparator.comparing(LoginRow::lastActiveAt, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(LoginRow::createdAt, Comparator.nullsLast(Comparator.reverseOrder())));

        List<Visit> newestFirst = new ArrayList<>(current);
        newestFirst.sort(Comparator.comparing(Visit::start).reversed());
        List<VisitRow> visitRows = new ArrayList<>();
        for (Visit visit : newestFirst.subList(0, Math.min(MAX_VISITS, newestFirst.size()))) {
            CustomerAccountEntity account = byId.get(visit.accountId());
            List<Long> placed = ordersNow.stream()
                    .filter(order -> order.accountId() == visit.accountId()
                            && Objects.equals(order.customerId(), visit.customerId())
                            && !order.at().isBefore(visit.start())
                            && !order.at().isAfter(visit.end().plus(Duration.ofMinutes(1))))
                    .map(PlacedOrder::salesOrderId).toList();
            visitRows.add(new VisitRow(visit.accountId(), visit.customerId(), company(companies, visit.customerId()),
                    account == null ? null : account.email, visit.start(), visit.end(), visit.seconds(),
                    visit.actions(), visit.startKind(), names(visit.kinds()), visit.device(), visit.history(),
                    visit.loggedOut(), placed));
        }

        Map<LocalDate, Set<Long>> customersPerDay = new TreeMap<>();
        Map<LocalDate, Integer> visitsPerDay = new HashMap<>();
        for (Visit visit : current) {
            LocalDate day = LocalDate.ofInstant(visit.start(), ZONE);
            customersPerDay.computeIfAbsent(day, ignored -> new HashSet<>()).add(visit.customerId());
            visitsPerDay.merge(day, 1, Integer::sum);
        }
        List<Day> perDay = new ArrayList<>();
        for (LocalDate day = fromDay; !day.isAfter(today); day = day.plusDays(1)) {
            perDay.add(new Day(day, customersPerDay.getOrDefault(day, Set.of()).size(), visitsPerDay.getOrDefault(day, 0)));
        }

        Totals totals = new Totals(distinctCustomers(current), current.size(), seconds(current), measured(current),
                ordersNow.size(), activeNow(accountId, now),
                count(accounts, CustomerAccountService.ACTIVE), count(accounts, CustomerAccountService.INVITED),
                count(accounts, CustomerAccountService.DISABLED));
        /* Only a fully measured period before compares: one made of history moments, or reaching back
           before the measuring began, would read as growth. No comparison then ("geen vergelijking"). */
        Instant measuredSince = measuredSince();
        boolean comparable = measuredSince != null && !measuredSince.isAfter(previousFrom);
        Previous previous = comparable
                ? new Previous(distinctCustomers(before), before.size(), seconds(before), ordersBefore)
                : new Previous(0, 0, 0, 0);
        return new Report(days, fromDay, today, now, accountId, measuredSince, totals, previous, perDay,
                logins, visitRows, newestFirst.size() > MAX_VISITS);
    }

    // ------------------------------------------------------------------ helpers

    /** Logins with a measured row in the last 30 minutes whose last row is not a logout. */
    private static int activeNow(Long accountId, Instant now) {
        Map<Long, Moment> last = new HashMap<>();
        for (Moment moment : CustomerLoginActivity.moments(now.minus(CustomerLoginActivity.VISIT_GAP),
                now.plusSeconds(1), accountId)) {
            if (moment.history()) continue;
            Moment known = last.get(moment.accountId());
            if (known == null || moment.at().isAfter(known.at())) last.put(moment.accountId(), moment);
        }
        return (int) last.values().stream().filter(moment -> moment.kind() != Kind.LOGOUT).count();
    }

    private static Instant measuredSince() {
        return CustomerLoginEventEntity.getEntityManager()
                .createQuery("select min(e.occurredAt) from CustomerLoginEventEntity e where e.history = false",
                        Instant.class)
                .getSingleResult();
    }

    private static Map<Long, Instant> lastSessionSeen(Set<Long> accountIds) {
        Map<Long, Instant> seen = new HashMap<>();
        if (accountIds.isEmpty()) return seen;
        for (Object[] row : CustomerSessionEntity.getEntityManager().createQuery(
                        "select s.accountId, max(s.lastSeenAt) from CustomerSessionEntity s "
                                + "where s.accountId in ?1 group by s.accountId", Object[].class)
                .setParameter(1, new ArrayList<>(accountIds)).getResultList()) {
            seen.put(((Number) row[0]).longValue(), (Instant) row[1]);
        }
        return seen;
    }

    /** The expiry of the newest unused link per login. */
    private static Map<Long, Instant> openLinks(Set<Long> accountIds) {
        Map<Long, Instant> links = new HashMap<>();
        if (accountIds.isEmpty()) return links;
        for (Object[] row : CustomerAccountTokenEntity.getEntityManager().createQuery(
                        "select t.accountId, max(t.expiresAt) from CustomerAccountTokenEntity t "
                                + "where t.usedAt is null and t.accountId in ?1 group by t.accountId", Object[].class)
                .setParameter(1, new ArrayList<>(accountIds)).getResultList()) {
            links.put(((Number) row[0]).longValue(), (Instant) row[1]);
        }
        return links;
    }

    private String company(Map<Long, String> cache, Long customerId) {
        if (customerId == null) return null;
        return cache.computeIfAbsent(customerId,
                id -> customers.findById(id).map(Customer::company).orElse(null));
    }

    private static Instant latest(Instant... moments) {
        Instant latest = null;
        for (Instant moment : moments) {
            if (moment != null && (latest == null || moment.isAfter(latest))) latest = moment;
        }
        return latest;
    }

    private static List<String> names(Set<Kind> kinds) {
        return kinds.stream().map(Kind::name).toList();
    }

    private static String mostUsed(Map<String, Integer> devices) {
        return devices.entrySet().stream()
                .max(Map.Entry.<String, Integer>comparingByValue().thenComparing(Map.Entry.comparingByKey()))
                .map(Map.Entry::getKey).orElse(null);
    }

    private static int distinctCustomers(List<Visit> visits) {
        return (int) visits.stream().map(Visit::customerId).distinct().count();
    }

    private static long seconds(List<Visit> visits) {
        return visits.stream().map(Visit::seconds).filter(Objects::nonNull).mapToLong(Long::longValue).sum();
    }

    private static int measured(List<Visit> visits) {
        return (int) visits.stream().filter(visit -> visit.seconds() != null).count();
    }

    private static int count(List<CustomerAccountEntity> accounts, String status) {
        return (int) accounts.stream().filter(account -> status.equals(account.status)).count();
    }

}
