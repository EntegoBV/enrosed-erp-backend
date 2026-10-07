package be.enrosed.account;

import be.enrosed.contact.ContactInquiryService;
import be.enrosed.sales.application.port.out.SalesRepositories;
import be.enrosed.sales.domain.Customer;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.LockModeType;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Stores a login request, whichever of the three routes it came in by: the login form, the
 * tick box of a quote request or the new-link form.
 *
 * First wins, every later version kept: an open request is never changed by a later one for
 * the same address. A later request with other details is listed beside it, up to five, so
 * an outsider can add noise around a genuine request but can never replace or remove it.
 * This class only stores; it sends nothing and never cleans up.
 */
@ApplicationScoped
public class LoginRequestWriter {
    private static final Logger LOG = Logger.getLogger(LoginRequestWriter.class);

    static final String PENDING = "PENDING";
    static final String APPROVED = "APPROVED";
    static final String REJECTED = "REJECTED";

    static final String QUOTE = "QUOTE";
    static final String ORDER_SCREEN = "ORDER_SCREEN";
    static final String NEW_LINK = "NEW_LINK";

    /** Open requests per route (login form, quote) before new ones of that route are dropped. */
    static final int INTAKE_CEILING = 300;
    static final int MAX_LATER_APPLICANTS = 5;
    /** The column holds 8 000 characters; the rest stays free for the new-link marker. */
    private static final int MAX_LATER_JSON = 7_900;
    private static final int LATER_COLUMN = 8_000;
    private static final int LATER_MESSAGE = 300;
    private static final Duration NEW_LINK_COOL_DOWN = Duration.ofHours(24);
    private static final Duration REJECTION_COOL_DOWN = Duration.ofDays(30);
    private static final TypeReference<List<LoginRequestDtos.LaterSubmission>> LATER_LIST =
            new TypeReference<>() {};

    /**
     * What one route hands in. email is the output of AccountEmails.normalize, language a
     * Language name; the customer and order link exist only for a quote.
     */
    public record Candidate(String source, String email, String language, String companyName,
                            String companyCountryCode, String vatNumber, String contactName,
                            String phone, String message, Long customerId, Long salesOrderId,
                            String salesOrderNumber) {
        /** The forgotten-password form knows nothing but the address. */
        static Candidate newLink(String email) {
            return new Candidate(NEW_LINK, email, null, null, null, null, null, null, null,
                    null, null, null);
        }
    }

    /**
     * requestId is null when nothing was stored for this candidate. notifyStaff tells the caller
     * to fire LoginRequestReceived (a record component cannot be called notify); changed tells
     * that the notice is for an entry appended to a request that was already open.
     */
    public record Stored(String reference, Long requestId, boolean notifyStaff, boolean changed) {
        public Stored(String reference, Long requestId, boolean notifyStaff) {
            this(reference, requestId, notifyStaff, false);
        }
    }

    private final SalesRepositories.Customers customers;
    private final ObjectMapper json;
    private Clock clock = Clock.systemUTC();

    @Inject
    public LoginRequestWriter(SalesRepositories.Customers customers, ObjectMapper json) {
        this.customers = customers;
        /* Absent values are left out, so the new-link marker stays far below a hundred characters. */
        this.json = json.copy().setSerializationInclusion(JsonInclude.Include.NON_NULL);
    }

    /** Joins the caller's transaction; only for the login form, inside executeAccepted. */
    @Transactional
    public Stored storeJoined(Candidate candidate) {
        return store(candidate);
    }

    /** Own transaction; only for the two background tasks, which have none. */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public Stored storeDetached(Candidate candidate) {
        return store(candidate);
    }

    /** Two inserts for one address raced on the partial unique index; the loser may run once more. */
    static boolean isUniqueViolation(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof org.hibernate.exception.ConstraintViolationException) return true;
            if (cause.getCause() == cause) break;
        }
        return false;
    }

    /** The later submissions of a request in arrival order; null when the stored text is unreadable. */
    List<LoginRequestDtos.LaterSubmission> later(CustomerLoginRequestEntity row) {
        if (row.laterSubmissions == null || row.laterSubmissions.isBlank()) return List.of();
        try {
            return json.readValue(row.laterSubmissions, LATER_LIST);
        } catch (Exception exception) {
            LOG.warnf("Login-aanvraag %d: latere indieningen zijn onleesbaar (%s)",
                    row.id, exception.getClass().getSimpleName());
            return null;
        }
    }

    private Stored store(Candidate raw) {
        Candidate candidate = fitted(raw);
        Instant now = clock.instant();
        CustomerLoginRequestEntity open = CustomerLoginRequestEntity
                .find("email = ?1 and status = ?2 order by id", candidate.email(), PENDING)
                .withLock(LockModeType.PESSIMISTIC_WRITE).firstResult();
        if (open != null) return repeat(open, candidate, now);

        CustomerAccountEntity account = null;
        if (NEW_LINK.equals(candidate.source())) {
            /* Only the holder of a login can ask for a new link, and not more than once a day. */
            account = liveAccount(candidate.email());
            if (account == null || !newLinkAllowed(account, now)) return dropped();
        } else if (CustomerLoginRequestEntity.count("status = ?1 and source = ?2",
                PENDING, candidate.source()) >= INTAKE_CEILING) {
            LOG.warnf("Login-aanvraag niet bewaard: er staan al %d aanvragen open via %s",
                    INTAKE_CEILING, candidate.source());
            return dropped();
        }

        CustomerLoginRequestEntity row = new CustomerLoginRequestEntity();
        row.reference = newReference();
        row.status = PENDING;
        row.source = candidate.source();
        row.email = candidate.email();
        row.repeatCount = 0;
        row.createdAt = now;
        row.updatedAt = now;
        boolean notify;
        if (account != null) {
            Customer customer = customers.findById(account.customerId).orElse(null);
            row.customerId = account.customerId;
            row.accountId = account.id;
            row.companyName = customer == null ? "-" : orDash(fit(customer.company(), 160));
            row.contactName = orDash(firstNonBlank(fit(account.contactName, 120),
                    customer == null ? null : fit(customer.contact(), 120)));
            row.language = account.language;
            notify = true;
        } else {
            row.language = candidate.language() == null ? "EN" : candidate.language();
            row.companyName = orDash(candidate.companyName());
            row.companyCountryCode = candidate.companyCountryCode();
            row.vatNumber = candidate.vatNumber();
            row.contactName = orDash(candidate.contactName());
            row.phone = candidate.phone();
            row.message = candidate.message();
            row.customerId = candidate.customerId();
            row.salesOrderId = candidate.salesOrderId();
            row.salesOrderNumber = candidate.salesOrderNumber();
            row.privacyAcceptedAt = now;
            row.privacyPolicyVersion = ContactInquiryService.CURRENT_PRIVACY_VERSION;
            /* A rejected address that asks again is stored and shown, but rings nobody. */
            notify = !rejectedRecently(candidate.email(), now);
        }
        row.persist();
        /* A race on the partial unique index must surface here, inside the caller's action. */
        CustomerLoginRequestEntity.flush();
        return new Stored(row.reference, row.id, notify, false);
    }

    /** The open request keeps its own fields; a differing later version is listed beside it. */
    private Stored repeat(CustomerLoginRequestEntity open, Candidate candidate, Instant now) {
        open.repeatCount++;
        open.updatedAt = now;
        Stored counted = new Stored(open.reference, open.id, false, false);
        List<LoginRequestDtos.LaterSubmission> later = later(open);
        if (later == null) return counted;

        if (NEW_LINK.equals(candidate.source())) {
            if (NEW_LINK.equals(open.source)
                    || later.stream().anyMatch(entry -> NEW_LINK.equals(entry.source()))) {
                return counted;
            }
            CustomerAccountEntity account = liveAccount(candidate.email());
            if (account == null || !newLinkAllowed(account, now)) return counted;
            /* Not silenced by an earlier rejection: this comes from the holder of a login, and
               a junk request parked on that address must not swallow it unseen. */
            String withMarker = write(later, new LoginRequestDtos.LaterSubmission(now, NEW_LINK,
                    null, null, null, null, null, null, null, null, null, null));
            if (withMarker == null || withMarker.length() > LATER_COLUMN) return counted;
            open.laterSubmissions = withMarker;
            return new Stored(open.reference, open.id, true, true);
        }

        if (!differs(candidate, open.source, open.companyName, open.vatNumber, open.contactName,
                open.customerId, open.salesOrderId)) {
            return counted;
        }
        int applicants = 0;
        for (LoginRequestDtos.LaterSubmission entry : later) {
            if (NEW_LINK.equals(entry.source())) continue;
            applicants++;
            if (!differs(candidate, entry.source(), entry.companyName(), entry.vatNumber(),
                    entry.contactName(), entry.customerId(), entry.salesOrderId())) {
                return counted;
            }
        }
        if (applicants >= MAX_LATER_APPLICANTS) return counted;
        String appended = write(later, entry(candidate, now, fit(candidate.message(), LATER_MESSAGE)));
        if (appended == null || appended.length() > MAX_LATER_JSON) {
            appended = write(later, entry(candidate, now, null));
        }
        if (appended == null || appended.length() > MAX_LATER_JSON) return counted;
        open.laterSubmissions = appended;
        return new Stored(open.reference, open.id, !rejectedRecently(candidate.email(), now), true);
    }

    private static LoginRequestDtos.LaterSubmission entry(Candidate candidate, Instant now, String message) {
        return new LoginRequestDtos.LaterSubmission(now, candidate.source(), candidate.companyName(),
                candidate.companyCountryCode(), candidate.vatNumber(), candidate.contactName(),
                candidate.phone(), message, candidate.language(), candidate.customerId(),
                candidate.salesOrderId(), candidate.salesOrderNumber());
    }

    /** The array with one entry added at the end; nothing in it is ever replaced or reordered. */
    private String write(List<LoginRequestDtos.LaterSubmission> stored, LoginRequestDtos.LaterSubmission added) {
        List<LoginRequestDtos.LaterSubmission> all = new ArrayList<>(stored);
        all.add(added);
        try {
            return json.writeValueAsString(all);
        } catch (Exception exception) {
            LOG.warnf("Latere indiening van een login-aanvraag kon niet bewaard worden (%s)",
                    exception.getClass().getSimpleName());
            return null;
        }
    }

    /**
     * Another source, company, VAT number or contact name, or a customer or order link the
     * stored set lacks.
     */
    private static boolean differs(Candidate candidate, String source, String companyName,
                                   String vatNumber, String contactName, Long customerId,
                                   Long salesOrderId) {
        return !same(candidate.source(), source)
                || !same(candidate.companyName(), companyName)
                || !vatKey(candidate.vatNumber()).equals(vatKey(vatNumber))
                || !same(candidate.contactName(), contactName)
                || candidate.customerId() != null && !candidate.customerId().equals(customerId)
                || candidate.salesOrderId() != null && !candidate.salesOrderId().equals(salesOrderId);
    }

    private static boolean same(String left, String right) {
        return (left == null ? "" : left.strip()).equalsIgnoreCase(right == null ? "" : right.strip());
    }

    /** A VAT number as it is compared: letters and digits only, upper case. */
    static String vatKey(String vatNumber) {
        return vatNumber == null ? ""
                : vatNumber.replaceAll("[^\\p{Alnum}]", "").toUpperCase(Locale.ROOT);
    }

    private static CustomerAccountEntity liveAccount(String email) {
        CustomerAccountEntity account = CustomerAccountEntity.find("email", email).firstResult();
        return account != null && (CustomerAccountService.INVITED.equals(account.status)
                || CustomerAccountService.ACTIVE.equals(account.status)) ? account : null;
    }

    /** Nobody can make staff mail a customer at will: one new-link request per login per day. */
    private static boolean newLinkAllowed(CustomerAccountEntity account, Instant now) {
        Instant since = now.minus(NEW_LINK_COOL_DOWN);
        if (account.lastLinkSentAt != null && account.lastLinkSentAt.isAfter(since)) return false;
        return CustomerLoginRequestEntity.count(
                "accountId = ?1 and source = ?2 and status <> ?3 and decidedAt > ?4",
                account.id, NEW_LINK, PENDING, since) == 0;
    }

    private static boolean rejectedRecently(String email, Instant now) {
        return CustomerLoginRequestEntity.count("email = ?1 and status = ?2 and decidedAt > ?3",
                email, REJECTED, now.minus(REJECTION_COOL_DOWN)) > 0;
    }

    /** Nothing stored: the applicant still gets a reference of the normal shape. */
    private static Stored dropped() {
        return new Stored(newReference(), null, false, false);
    }

    static String newReference() {
        return "LGN-" + UUID.randomUUID().toString().replace("-", "")
                .substring(0, 20).toUpperCase(Locale.ROOT);
    }

    /** Every value stripped and cut to its column, so PostgreSQL and the comparisons see the same text. */
    private static Candidate fitted(Candidate candidate) {
        return new Candidate(candidate.source(), candidate.email(), fit(candidate.language(), 4),
                fit(candidate.companyName(), 160), fit(candidate.companyCountryCode(), 2),
                fit(candidate.vatNumber(), 32), fit(candidate.contactName(), 120),
                fit(candidate.phone(), 50), fit(candidate.message(), 1_000), candidate.customerId(),
                candidate.salesOrderId(), fit(candidate.salesOrderNumber(), 40));
    }

    private static String fit(String value, int max) {
        if (value == null || value.isBlank()) return null;
        String stripped = value.strip();
        return stripped.length() > max ? stripped.substring(0, max).strip() : stripped;
    }

    private static String firstNonBlank(String first, String second) {
        return first != null ? first : second;
    }

    private static String orDash(String value) {
        return value == null ? "-" : value;
    }

    /** Tests pin the moment the cool-downs are judged at. */
    void useClock(Clock clock) {
        this.clock = clock;
    }
}
