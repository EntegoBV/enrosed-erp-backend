package be.enrosed.account;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.vertx.core.http.HttpServerRequest;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * What a logged-in customer did on the website and when, as rows of customer_login_event, and
 * the visits worked out from them. Staff read it on Analyses › Bezoekers › Klantlogins and on
 * the Websitelogin block of the customer sheet.
 *
 * Every call of a logged-in page passes the session guard, which writes one row: the moment,
 * the kind of call (from the address the website called, never the page or product) and the
 * kind of device. Logging in and choosing a password with the link write their own row inside
 * the login's transaction. Writing a row never fails a customer's call.
 *
 * A visit is the run of one login's rows with less than 30 minutes between two of them; a
 * logout ends it. Its length runs from its first to its last row, so the time spent on the
 * last page is not in it.
 */
public final class CustomerLoginActivity {
    private static final Logger LOG = Logger.getLogger(CustomerLoginActivity.class);
    private static final String PREFIX = "/api/v1/public/account/";

    /** A visit ends after this much silence. */
    public static final Duration VISIT_GAP = Duration.ofMinutes(30);

    private CustomerLoginActivity() {}

    /**
     * ACTIVATION: password chosen with the link (logs in at once). LOGIN: e-mail and password.
     * LOGOUT. PAGE: a page restored the login. PRICES: a product page asked the customer's own
     * prices. ORDER_PAGE: the order/quote page (configuration, estimates, delivery defaults).
     * ORDER, ORDER_CHANGE, ORDER_CANCEL: sending, changing or cancelling an order. ORDERS:
     * Mijn bestellingen (list and detail). PDF: a document downloaded. SEEN: a moment the
     * session was in use, known only from before 2026-11-15 (history rows). OTHER: anything else.
     */
    public enum Kind {
        ACTIVATION, LOGIN, LOGOUT, PAGE, PRICES, ORDER_PAGE, ORDER, ORDER_CHANGE, ORDER_CANCEL,
        ORDERS, PDF, SEEN, OTHER;

        static Kind of(String value) {
            if (value == null) return OTHER;
            try {
                return valueOf(value);
            } catch (IllegalArgumentException unknown) {
                return OTHER;
            }
        }
    }

    /** What one call of a logged-in page was, and from which kind of device. */
    public record Touch(Kind kind, String device) {
        public static final Touch NONE = new Touch(Kind.OTHER, null);
    }

    // ------------------------------------------------------------------ reading a call

    /** The kind of call from method and path; the device from the User-Agent. */
    public static Touch of(HttpServerRequest request) {
        if (request == null) return Touch.NONE;
        return new Touch(kindOf(request.method() == null ? null : request.method().name(), request.path()),
                device(request.getHeader("User-Agent")));
    }

    static Kind kindOf(String method, String path) {
        if (path == null || !path.startsWith(PREFIX)) return Kind.OTHER;
        String rest = path.substring(PREFIX.length());
        boolean post = "POST".equalsIgnoreCase(method);
        if (rest.equals("session")) return post ? Kind.LOGIN : Kind.PAGE;
        if (rest.startsWith("session/logout")) return Kind.LOGOUT;
        if (rest.startsWith("prices")) return Kind.PRICES;
        if (rest.startsWith("quotes")) return Kind.ORDER_PAGE;
        if (rest.startsWith("orders")) {
            String order = rest.substring("orders".length());
            if (order.isEmpty() || order.equals("/")) return post ? Kind.ORDER : Kind.ORDERS;
            if (order.startsWith("/preview")) return Kind.ORDER_PAGE;
            if (order.endsWith("/changes")) return Kind.ORDER_CHANGE;
            if (order.endsWith("/cancellation")) return Kind.ORDER_CANCEL;
            return Kind.ORDERS;
        }
        if (rest.startsWith("documents")) {
            if (rest.startsWith("documents/delivery-defaults")) return Kind.ORDER_PAGE;
            if (rest.endsWith("/pdf")) return Kind.PDF;
            return Kind.ORDERS;
        }
        return Kind.OTHER;
    }

    /** MOBILE, TABLET or DESKTOP; null without a User-Agent. An iPad that says "Macintosh" counts as a computer. */
    static String device(String userAgent) {
        if (userAgent == null || userAgent.isBlank()) return null;
        String agent = userAgent.toLowerCase(Locale.ROOT);
        if (agent.contains("ipad") || agent.contains("tablet")
                || (agent.contains("android") && !agent.contains("mobile"))) {
            return "TABLET";
        }
        if (agent.contains("mobi") || agent.contains("iphone") || agent.contains("ipod")
                || agent.contains("android")) {
            return "MOBILE";
        }
        return "DESKTOP";
    }

    // ------------------------------------------------------------------ writing a row

    /** Inside the caller's transaction: logging in and choosing a password. */
    static void recordJoined(long accountId, long customerId, Long sessionId, Instant at, Kind kind, String device) {
        CustomerLoginEventEntity row = new CustomerLoginEventEntity();
        row.accountId = accountId;
        row.customerId = customerId;
        row.sessionId = sessionId;
        row.occurredAt = at;
        row.kind = kind.name();
        row.device = device;
        row.history = false;
        row.persist();
    }

    /**
     * In a transaction of its own, after the session was judged valid: a failure is logged and
     * swallowed, so the customer's call goes on whatever happens to the statistics.
     */
    static void recordSeparately(long accountId, long customerId, Long sessionId, Instant at, Touch touch) {
        try {
            QuarkusTransaction.requiringNew().run(() ->
                    recordJoined(accountId, customerId, sessionId, at, touch.kind(), touch.device()));
        } catch (RuntimeException failure) {
            LOG.warnf(failure, "Websitelogin %d: activiteit niet bewaard", accountId);
        }
    }

    // ------------------------------------------------------------------ visits

    /** One row as the visit split reads it. */
    public record Moment(long accountId, long customerId, Instant at, Kind kind, String device, boolean history) {}

    /**
     * One visit of one login. seconds is null for a visit made only of history rows (its length is
     * unknown). startKind is ACTIVATION or LOGIN when the visit began with one, else RETURN: the
     * customer came back with a login the browser had kept.
     */
    public record Visit(long accountId, long customerId, Instant start, Instant end, Long seconds,
                        int actions, String startKind, Set<Kind> kinds, String device, boolean history,
                        boolean loggedOut) {}

    /** The visits of any number of logins, oldest first; the moments may come in any order. */
    public static List<Visit> visits(List<Moment> moments) {
        List<Moment> sorted = new ArrayList<>(moments);
        sorted.sort(Comparator.comparingLong(Moment::accountId).thenComparing(Moment::at));
        List<Visit> visits = new ArrayList<>();
        List<Moment> current = new ArrayList<>();
        for (Moment moment : sorted) {
            if (!current.isEmpty()) {
                Moment last = current.get(current.size() - 1);
                boolean otherLogin = last.accountId() != moment.accountId();
                boolean silent = Duration.between(last.at(), moment.at()).compareTo(VISIT_GAP) > 0;
                boolean afterLogout = last.kind() == Kind.LOGOUT;
                /* A history moment never joins a measured visit: its length would be made up. */
                boolean otherOrigin = last.history() != moment.history();
                if (otherLogin || silent || afterLogout || otherOrigin) {
                    visits.add(visit(current));
                    current = new ArrayList<>();
                }
            }
            current.add(moment);
        }
        if (!current.isEmpty()) visits.add(visit(current));
        visits.sort(Comparator.comparing(Visit::start));
        return visits;
    }

    private static Visit visit(List<Moment> moments) {
        Moment first = moments.get(0);
        Moment last = moments.get(moments.size() - 1);
        Set<Kind> kinds = EnumSet.noneOf(Kind.class);
        Map<String, Integer> devices = new HashMap<>();
        for (Moment moment : moments) {
            kinds.add(moment.kind());
            if (moment.device() != null) devices.merge(moment.device(), 1, Integer::sum);
        }
        String device = devices.entrySet().stream()
                .max(Map.Entry.<String, Integer>comparingByValue().thenComparing(Map.Entry.comparingByKey()))
                .map(Map.Entry::getKey).orElse(null);
        String startKind = first.kind() == Kind.ACTIVATION || first.kind() == Kind.LOGIN
                ? first.kind().name() : "RETURN";
        Long seconds = first.history() ? null : Duration.between(first.at(), last.at()).toSeconds();
        return new Visit(first.accountId(), last.customerId(), first.at(), last.at(), seconds, moments.size(),
                startKind, kinds, device, first.history(), last.kind() == Kind.LOGOUT);
    }

    /** The moments of logins between two instants (from inclusive, to exclusive); null for every login. */
    static List<Moment> moments(Instant from, Instant to, Long accountId) {
        String query = "select e.accountId, e.customerId, e.occurredAt, e.kind, e.device, e.history "
                + "from CustomerLoginEventEntity e where e.occurredAt >= ?1 and e.occurredAt < ?2"
                + (accountId == null ? "" : " and e.accountId = ?3");
        var typed = CustomerLoginEventEntity.getEntityManager().createQuery(query, Object[].class)
                .setParameter(1, from).setParameter(2, to);
        if (accountId != null) typed.setParameter(3, accountId);
        List<Moment> moments = new ArrayList<>();
        for (Object[] row : typed.getResultList()) {
            moments.add(new Moment(((Number) row[0]).longValue(), ((Number) row[1]).longValue(),
                    (Instant) row[2], Kind.of((String) row[3]), (String) row[4], Boolean.TRUE.equals(row[5])));
        }
        return moments;
    }

    /** The last row of each of these logins, any age. */
    static Map<Long, Instant> lastMoments(List<Long> accountIds) {
        Map<Long, Instant> last = new HashMap<>();
        if (accountIds == null || accountIds.isEmpty()) return last;
        for (Object[] row : CustomerLoginEventEntity.getEntityManager().createQuery(
                        "select e.accountId, max(e.occurredAt) from CustomerLoginEventEntity e "
                                + "where e.accountId in ?1 group by e.accountId", Object[].class)
                .setParameter(1, accountIds).getResultList()) {
            last.put(((Number) row[0]).longValue(), (Instant) row[1]);
        }
        return last;
    }
}
