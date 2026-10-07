package be.enrosed.account;

import be.enrosed.publicform.PublicFormValidationException;
import be.enrosed.sales.application.CustomerService;
import be.enrosed.sales.application.WebsiteQuoteLoginRequested;
import be.enrosed.sales.application.port.out.SalesRepositories;
import be.enrosed.sales.domain.Customer;
import be.enrosed.shared.BusinessRuleException;
import be.enrosed.shared.Language;
import be.enrosed.shared.NotFoundException;
import be.enrosed.shared.audit.ActivityLogService;
import be.enrosed.shared.security.CurrentActor;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.event.TransactionPhase;
import jakarta.inject.Inject;
import jakarta.persistence.LockModeType;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.BadRequestException;
import org.jboss.logging.Logger;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Login requests from the website and what staff do with them: the inbox, the customers a
 * request may belong to, approval and rejection.
 *
 * Binding a login to a customer is a human decision. Nothing here chooses a customer on its
 * own, a VAT match is never more than a hint, and an address that differs from the customer
 * record needs an explicit confirmation that ends up in the customer's logbook. Approving
 * never sends mail: the resource delivers the link after this transaction has committed.
 */
@ApplicationScoped
public class LoginRequestService {
    private static final Logger LOG = Logger.getLogger(LoginRequestService.class);
    private static final String ACTIVITY_ENTITY = "CUSTOMER";
    private static final String RECEIVED = "RECEIVED";
    private static final int MAX_MATCHES = 20;
    private static final int MAX_DECISION_NOTE = 500;

    public record Approved(LoginRequestDtos.LoginRequestView request, CustomerAccountService.Grant grant) {}

    private final LoginRequestWriter writer;
    private final LoginRequestNotifier notifier;
    private final CustomerAccountService accounts;
    private final CustomerService customers;
    private final SalesRepositories.Customers customerRows;
    private final ActivityLogService activity;
    private final CurrentActor actor;
    private final Event<LoginRequestReceived> received;
    private Clock clock = Clock.systemUTC();

    @Inject
    public LoginRequestService(LoginRequestWriter writer, LoginRequestNotifier notifier,
                               CustomerAccountService accounts, CustomerService customers,
                               SalesRepositories.Customers customerRows,
                               ActivityLogService activity, CurrentActor actor,
                               Event<LoginRequestReceived> received) {
        this.writer = writer;
        this.notifier = notifier;
        this.accounts = accounts;
        this.customers = customers;
        this.customerRows = customerRows;
        this.activity = activity;
        this.actor = actor;
        this.received = received;
    }

    // ------------------------------------------------------------------ intake: the login form

    /** Read-only; the fields of the standalone request form. */
    public void validate(LoginRequestDtos.Submit request) {
        if (request == null) {
            throw new PublicFormValidationException(Map.of("request", "REQUIRED"));
        }
        Map<String, String> errors = new LinkedHashMap<>();
        requiredSingleLine(request.companyName(), 160, "companyName", errors);
        if (blank(request.companyCountryCode())) errors.put("companyCountryCode", "REQUIRED");
        else if (countryCode(request.companyCountryCode()) == null) errors.put("companyCountryCode", "INVALID");
        requiredSingleLine(request.vatNumber(), 32, "vatNumber", errors);
        requiredSingleLine(request.contactName(), 120, "contactName", errors);
        if (blank(request.email())) errors.put("email", "REQUIRED");
        else if (AccountEmails.normalize(request.email()) == null) errors.put("email", "INVALID");
        optionalSingleLine(request.phone(), 50, "phone", errors);
        if (request.message() != null) {
            if (request.message().strip().length() > 1_000) errors.put("message", "TOO_LONG");
            if (request.message().indexOf('\0') >= 0) errors.put("message", "INVALID");
        }
        if (!Boolean.TRUE.equals(request.privacyAccepted())) {
            errors.put("privacyAccepted", "REQUIRED");
        }
        if (!errors.isEmpty()) throw new PublicFormValidationException(errors);
    }

    /**
     * Joins the transaction of executeAccepted: one row is written and nothing else happens on
     * this thread. The notice is announced here and delivered only after that transaction
     * has committed.
     */
    @Transactional
    public LoginRequestDtos.Accepted submit(LoginRequestDtos.Submit request) {
        validate(request);
        LoginRequestWriter.Stored stored = writer.storeJoined(new LoginRequestWriter.Candidate(
                LoginRequestWriter.ORDER_SCREEN, AccountEmails.normalize(request.email()),
                language(request.language()).name(), clean(request.companyName()),
                countryCode(request.companyCountryCode()), clean(request.vatNumber()),
                clean(request.contactName()), clean(request.phone()),
                cleanMultiline(request.message()), null, null, null));
        announce(stored);
        return new LoginRequestDtos.Accepted(stored.reference(), RECEIVED);
    }

    // ------------------------------------------------------------------ intake: background routes

    /**
     * The forgotten-password form. Runs on the notifier's executor, so the request thread did
     * the same work whether or not the address has a login. A request is stored only for an
     * existing login; the language of the form is not kept, the login has its own.
     */
    public void requestNewLink(String email, String language) {
        try {
            String normalized = AccountEmails.normalize(email);
            if (normalized == null) return;
            announce(storeDetached(LoginRequestWriter.Candidate.newLink(normalized)));
        } catch (RuntimeException exception) {
            LOG.errorf("Aanvraag voor een nieuwe loginlink kon niet bewaard worden (%s)",
                    exception.getClass().getSimpleName());
        }
    }

    /** No database work on the committing thread; a rolled-back quote never gets here. */
    void onQuoteLoginRequested(
            @Observes(during = TransactionPhase.AFTER_SUCCESS) WebsiteQuoteLoginRequested event) {
        notifier.runLater(() -> storeFromQuote(event));
    }

    /**
     * The tick box of a quote request. A failure never reaches the quote: the note line on the
     * order still tells staff, who then give the login on the customer.
     */
    void storeFromQuote(WebsiteQuoteLoginRequested event) {
        try {
            String email = AccountEmails.normalize(event.email());
            if (email == null) {
                LOG.warnf("Login-aanvraag bij offerte %s niet bewaard: het e-mailadres is niet bruikbaar",
                        event.orderNumber());
                return;
            }
            announce(storeDetached(new LoginRequestWriter.Candidate(
                    LoginRequestWriter.QUOTE, email, language(event.language()).name(),
                    clean(event.companyName()), countryCode(event.companyCountryCode()),
                    clean(event.vatNumber()), clean(event.contactName()), clean(event.phone()),
                    null, event.customerId(), event.orderId(), clean(event.orderNumber()))));
        } catch (RuntimeException exception) {
            LOG.errorf("Login-aanvraag bij offerte %s kon niet bewaard worden (%s)",
                    event.orderNumber(), exception.getClass().getSimpleName());
        }
    }

    /** Two inserts for one address can race in PostgreSQL; the second run finds the open request. */
    private LoginRequestWriter.Stored storeDetached(LoginRequestWriter.Candidate candidate) {
        try {
            return writer.storeDetached(candidate);
        } catch (RuntimeException exception) {
            if (!LoginRequestWriter.isUniqueViolation(exception)) throw exception;
            return writer.storeDetached(candidate);
        }
    }

    /** Inside a transaction the notifier hears this after the commit, outside one at once. */
    private void announce(LoginRequestWriter.Stored stored) {
        if (stored.notifyStaff() && stored.requestId() != null) {
            received.fire(new LoginRequestReceived(stored.requestId(), stored.changed()));
        }
    }

    // ------------------------------------------------------------------ staff: the inbox

    /** Waiting requests oldest first, so junk cannot push one off the first page. */
    public List<LoginRequestDtos.LoginRequestView> list(String requestedStatus, int page, int size) {
        if (page < 0 || size < 1 || size > 100) {
            throw new BadRequestException("page of size ligt buiten het bereik");
        }
        String status = blank(requestedStatus) ? LoginRequestWriter.PENDING
                : requestedStatus.strip().toUpperCase(Locale.ROOT);
        String order;
        if (LoginRequestWriter.PENDING.equals(status)) {
            order = "createdAt, id";
        } else if (LoginRequestWriter.APPROVED.equals(status) || LoginRequestWriter.REJECTED.equals(status)) {
            order = "decidedAt desc, id desc";
        } else {
            throw new BadRequestException("Onbekende status");
        }
        List<CustomerLoginRequestEntity> rows = CustomerLoginRequestEntity
                .find("status = ?1 order by " + order, status).page(page, size).list();
        return rows.stream().map(this::view).toList();
    }

    public LoginRequestDtos.Summary summary() {
        long pending = CustomerLoginRequestEntity.count("status", LoginRequestWriter.PENDING);
        List<String> full = new ArrayList<>();
        for (String source : List.of(LoginRequestWriter.ORDER_SCREEN, LoginRequestWriter.QUOTE)) {
            if (CustomerLoginRequestEntity.count("status = ?1 and source = ?2",
                    LoginRequestWriter.PENDING, source) >= LoginRequestWriter.INTAKE_CEILING) {
                full.add(source);
            }
        }
        return new LoginRequestDtos.Summary(pending, !full.isEmpty(), List.copyOf(full));
    }

    public LoginRequestDtos.Detail detail(long id) {
        CustomerLoginRequestEntity row = CustomerLoginRequestEntity.findById(id);
        if (row == null) throw new NotFoundException("Login-aanvraag", id);
        AccountDtos.AccountView existing = existingAccount(row);
        return new LoginRequestDtos.Detail(view(row), matches(row, existing), existing);
    }

    /** The login of the request's address in any status; for a decided request the login it led to. */
    private AccountDtos.AccountView existingAccount(CustomerLoginRequestEntity row) {
        if (!LoginRequestWriter.PENDING.equals(row.status) && row.accountId != null
                && CustomerAccountEntity.findById(row.accountId) != null) {
            return accounts.view(row.accountId);
        }
        return accounts.findByEmail(row.email).orElse(null);
    }

    /**
     * The customers this request may belong to. The customer that already holds the login comes
     * first and is never cut off; a VAT match is only ever a hint, VAT numbers are public.
     */
    private List<LoginRequestDtos.Match> matches(CustomerLoginRequestEntity row,
                                                 AccountDtos.AccountView existing) {
        Long loginCustomer = existing != null && live(existing.status()) ? existing.customerId() : null;
        Set<Long> quoteCustomers = new LinkedHashSet<>();
        if (LoginRequestWriter.QUOTE.equals(row.source) && row.customerId != null) {
            quoteCustomers.add(row.customerId);
        }
        List<LoginRequestDtos.LaterSubmission> later = writer.later(row);
        if (later != null) {
            later.stream().map(LoginRequestDtos.LaterSubmission::customerId)
                    .filter(Objects::nonNull).forEach(quoteCustomers::add);
        }
        String vat = LoginRequestWriter.vatKey(row.vatNumber);

        record Found(Customer customer, List<String> matchedOn) {}
        List<Found> withLogin = new ArrayList<>();
        List<Found> withEmail = new ArrayList<>();
        List<Found> others = new ArrayList<>();
        List<Customer> all = customers.list().stream()
                .filter(customer -> customer.id() != null)
                .sorted(Comparator.comparing(Customer::id)).toList();
        for (Customer customer : all) {
            List<String> matchedOn = new ArrayList<>();
            if (customer.id().equals(loginCustomer)) matchedOn.add("LOGIN");
            if (quoteCustomers.contains(customer.id())) matchedOn.add("QUOTE");
            if (row.email.equals(AccountEmails.normalize(customer.email()))) matchedOn.add("EMAIL");
            if (!vat.isEmpty() && vat.equals(LoginRequestWriter.vatKey(customer.vatNumber()))) {
                matchedOn.add("VAT");
            }
            if (matchedOn.isEmpty()) continue;
            Found found = new Found(customer, List.copyOf(matchedOn));
            if (matchedOn.contains("LOGIN")) withLogin.add(found);
            else if (matchedOn.contains("EMAIL")) withEmail.add(found);
            else others.add(found);
        }
        List<Found> ordered = new ArrayList<>(withLogin);
        ordered.addAll(withEmail);
        ordered.addAll(others);
        return ordered.stream().limit(MAX_MATCHES).map(found -> {
            Customer customer = found.customer();
            List<LoginRequestDtos.MatchLogin> logins = accounts.forCustomer(customer.id()).stream()
                    .map(login -> new LoginRequestDtos.MatchLogin(login.id(), login.email(), login.status()))
                    .toList();
            return new LoginRequestDtos.Match(customer.id(), customer.company(), customer.contact(),
                    customer.email(), customer.vatNumber(), customer.countryCode(), customer.city(),
                    found.matchedOn(), logins);
        }).toList();
    }

    // ------------------------------------------------------------------ staff: the decision

    /**
     * Steps 1 to 7 of an approval in one transaction. The link is made here and mailed by the
     * resource after the commit, so a rollback never leaves a live link in a mailbox.
     */
    @Transactional
    public Approved approve(long id, LoginRequestDtos.ApproveRequest body) {
        CustomerLoginRequestEntity row = lockPending(id);
        CustomerAccountService.Grant grant;
        long customerId;
        String company;
        boolean mismatchConfirmed = false;
        if (LoginRequestWriter.NEW_LINK.equals(row.source)) {
            /* The body is ignored: the login already exists and only gets a fresh link. */
            CustomerAccountEntity account = row.accountId == null ? null
                    : CustomerAccountEntity.findById(row.accountId);
            if (account == null || CustomerAccountService.DISABLED.equals(account.status)) {
                throw new BusinessRuleException("Deze login is ingetrokken. Geef de login opnieuw"
                        + " bij de klant (Klanten, blok Websitelogin).");
            }
            customerId = account.customerId;
            company = companyOf(customerId);
            grant = accounts.reissue(account.id);
        } else {
            boolean existingCustomer = body != null && body.customerId() != null;
            boolean newCustomer = body != null && body.newCustomer() != null;
            if (existingCustomer == newCustomer) {
                throw new BadRequestException("Kies een bestaande klant of maak een nieuwe klant aan");
            }
            Long chosen = existingCustomer ? body.customerId() : null;
            Customer customer = existingCustomer ? customers.get(chosen) : null;
            CustomerAccountEntity login = CustomerAccountEntity.find("email", row.email).firstResult();
            boolean liveLogin = login != null && live(login.status);
            if (liveLogin && !Objects.equals(login.customerId, chosen)) {
                throw new BusinessRuleException("Dit e-mailadres heeft al een login bij klant "
                        + companyOf(login.customerId) + ". Kies die klant of trek die login eerst in.");
            }
            if (existingCustomer) {
                /* A login that already sits on this customer was bound when it was given;
                   approving only sends a new link to that same login. */
                if (!liveLogin && !row.email.equals(AccountEmails.normalize(customer.email()))) {
                    if (!Boolean.TRUE.equals(body.confirmEmailMismatch())) {
                        throw new BusinessRuleException("Het e-mailadres van de aanvraag wijkt af van"
                                + " het e-mailadres van de klant. Bevestig uitdrukkelijk dat deze"
                                + " persoon bij de klant hoort.");
                    }
                    mismatchConfirmed = true;
                }
            } else {
                customer = customers.create(newCustomer(row, body.newCustomer()));
            }
            customerId = customer.id();
            company = customer.company();
            grant = accounts.grant(customerId, row.email, row.contactName, Language.of(row.language));
        }
        Instant now = clock.instant();
        row.status = LoginRequestWriter.APPROVED;
        row.customerId = customerId;
        row.accountId = grant.accountId();
        row.decidedAt = now;
        row.decidedBy = actor.name();
        row.updatedAt = now;
        activity.record(ActivityLogService.ACTION_STATUS_CHANGED, ACTIVITY_ENTITY,
                String.valueOf(customerId), company, "Websitelogin goedgekeurd voor " + row.email
                        + (mismatchConfirmed ? " (wijkt af van het e-mailadres van de klant;"
                        + " uitdrukkelijk bevestigd)" : ""));
        LOG.infof("Login-aanvraag %d: goedgekeurd, websitelogin %d", row.id, grant.accountId());
        return new Approved(view(row), grant);
    }

    /** No mail, no push, no login: a rejection is silent. */
    @Transactional
    public LoginRequestDtos.LoginRequestView reject(long id, String note) {
        CustomerLoginRequestEntity row = lockPending(id);
        Instant now = clock.instant();
        row.status = LoginRequestWriter.REJECTED;
        row.decidedAt = now;
        row.decidedBy = actor.name();
        row.decisionNote = decisionNote(note);
        row.updatedAt = now;
        LOG.infof("Login-aanvraag %d: afgewezen", row.id);
        return view(row);
    }

    /** Every decision locks the request and needs it to be waiting still. */
    private static CustomerLoginRequestEntity lockPending(long id) {
        CustomerLoginRequestEntity row =
                CustomerLoginRequestEntity.findById(id, LockModeType.PESSIMISTIC_WRITE);
        if (row == null) throw new NotFoundException("Login-aanvraag", id);
        if (!LoginRequestWriter.PENDING.equals(row.status)) {
            throw new BusinessRuleException("Deze aanvraag is al behandeld");
        }
        return row;
    }

    /** The new customer gets the address of the request, so there is no mismatch to confirm. */
    private static Customer newCustomer(CustomerLoginRequestEntity row, LoginRequestDtos.NewCustomer body) {
        Language language;
        try {
            language = Language.requireSupported(body.language(), Language.of(row.language));
        } catch (IllegalArgumentException exception) {
            throw new BadRequestException("Onbekende taal");
        }
        return new Customer(null, strip(body.company()), strip(body.contact()), row.email,
                strip(body.phone()), strip(body.vatNumber()),
                blank(body.countryCode()) ? null : body.countryCode().strip().toUpperCase(Locale.ROOT),
                language, null, null, null, null, null,
                "Aangemaakt bij goedkeuring van login-aanvraag " + row.reference, null);
    }

    private static String decisionNote(String note) {
        if (blank(note)) return null;
        String stripped = note.strip();
        return stripped.length() > MAX_DECISION_NOTE ? stripped.substring(0, MAX_DECISION_NOTE) : stripped;
    }

    // ------------------------------------------------------------------ views

    private LoginRequestDtos.LoginRequestView view(CustomerLoginRequestEntity row) {
        List<LoginRequestDtos.LaterSubmission> later = writer.later(row);
        if (later == null) later = List.of();
        long applicants = later.stream()
                .filter(entry -> !LoginRequestWriter.NEW_LINK.equals(entry.source())).count();
        boolean hasExistingLogin = CustomerAccountEntity.count("email = ?1 and status in (?2, ?3)",
                row.email, CustomerAccountService.INVITED, CustomerAccountService.ACTIVE) > 0;
        boolean previouslyRejected = CustomerLoginRequestEntity.count(
                "email = ?1 and status = ?2 and id <> ?3",
                row.email, LoginRequestWriter.REJECTED, row.id) > 0;
        return new LoginRequestDtos.LoginRequestView(row.id, row.reference, row.status, row.source,
                row.language, row.companyName, row.companyCountryCode, row.vatNumber, row.contactName,
                row.email, row.phone, row.message, row.customerId,
                row.customerId == null ? null : companyOrNull(row.customerId),
                row.salesOrderId, row.salesOrderNumber, row.accountId, row.repeatCount,
                hasExistingLogin, previouslyRejected, later,
                applicants >= LoginRequestWriter.MAX_LATER_APPLICANTS,
                row.createdAt, row.decidedAt, row.decidedBy, row.decisionNote);
    }

    private String companyOrNull(long customerId) {
        return customerRows.findById(customerId).map(Customer::company).orElse(null);
    }

    private String companyOf(Long customerId) {
        String company = customerId == null ? null : companyOrNull(customerId);
        return company == null ? "-" : company;
    }

    // ------------------------------------------------------------------ helpers

    private static boolean live(String accountStatus) {
        return CustomerAccountService.INVITED.equals(accountStatus)
                || CustomerAccountService.ACTIVE.equals(accountStatus);
    }

    /** Unknown or missing is English: the form is not refused over its language. */
    private static Language language(String value) {
        try {
            return Language.requireSupported(value, Language.EN);
        } catch (IllegalArgumentException exception) {
            return Language.EN;
        }
    }

    /** Two letters after upper-casing, else null; not checked against the delivery countries. */
    private static String countryCode(String value) {
        if (blank(value)) return null;
        String code = value.strip().toUpperCase(Locale.ROOT);
        return code.matches("^[A-Z]{2}$") ? code : null;
    }

    private static void requiredSingleLine(String value, int max, String field,
                                           Map<String, String> errors) {
        if (blank(value)) errors.put(field, "REQUIRED");
        else optionalSingleLine(value, max, field, errors);
    }

    private static void optionalSingleLine(String value, int max, String field,
                                           Map<String, String> errors) {
        if (value == null) return;
        String stripped = value.strip();
        if (stripped.length() > max) errors.put(field, "TOO_LONG");
        if (stripped.matches("(?s).*[\u0000-\u001f\u007f].*")) errors.put(field, "INVALID");
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static String strip(String value) {
        return blank(value) ? null : value.strip();
    }

    private static String clean(String value) {
        if (blank(value)) return null;
        return value.strip().replaceAll("[\\p{Cc}]", "");
    }

    private static String cleanMultiline(String value) {
        if (blank(value)) return null;
        return value.strip().replace("\r\n", "\n").replace('\r', '\n')
                .replaceAll("[\\p{Cc}&&[^\\n\\t]]", "");
    }

    /** Tests pin the moment of a decision. */
    void useClock(Clock clock) {
        this.clock = clock;
    }
}
