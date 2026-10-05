package be.enrosed.account;

import be.enrosed.publicform.PublicFormServiceUnavailableException;
import be.enrosed.publicform.PublicFormValidationException;
import be.enrosed.sales.application.port.out.SalesRepositories;
import be.enrosed.sales.domain.Customer;
import be.enrosed.shared.BusinessRuleException;
import be.enrosed.shared.Language;
import be.enrosed.shared.NotFoundException;
import be.enrosed.shared.audit.ActivityLogService;
import be.enrosed.shared.mail.CustomerAccountMailer;
import be.enrosed.shared.security.CurrentActor;
import io.quarkus.elytron.security.common.BcryptUtil;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.panache.common.Parameters;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.persistence.LockModeType;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Website logins of customers: giving and withdrawing them, the one-time link, the
 * password the customer chooses and the server-side sessions.
 *
 * Staff never see, type or reset a password. Raw tokens exist only in the answer to the
 * customer and in the Grant on its way to the mailer; the database holds their SHA-256.
 * Granting never sends mail: a resource calls deliver after the granting transaction has
 * committed, so a rollback can never leave a live link in a mailbox.
 */
@ApplicationScoped
public class CustomerAccountService {
    private static final Logger LOG = Logger.getLogger(CustomerAccountService.class);

    static final String INVITED = "INVITED";
    static final String ACTIVE = "ACTIVE";
    static final String DISABLED = "DISABLED";

    static final int MAX_SESSIONS = 5;
    static final int DEFAULT_INVITE_TTL_DAYS = 7;
    private static final int BCRYPT_COST = 12;
    private static final int MIN_PASSWORD_LENGTH = 10;
    private static final int MAX_PASSWORD_BYTES = 72;
    private static final Duration LAST_SEEN_STEP = Duration.ofMinutes(15);
    private static final String ACTIVITY_ENTITY = "CUSTOMER";

    /**
     * Bcrypt runs on the worker pool the staff ERP shares: login traffic never occupies
     * more than four worker threads with hashing.
     */
    static final Semaphore BCRYPT = new Semaphore(4);

    /**
     * Raw token leaves this record only towards the mailer. Never serialise it. kind is decided
     * inside the granting transaction from the account as it was before the change.
     */
    public record Grant(long accountId, String rawToken, Instant expiresAt,
                        CustomerAccountMailer.Kind kind) {
        @Override
        public String toString() {
            return "Grant[accountId=" + accountId + ", expiresAt=" + expiresAt + ", kind=" + kind + "]";
        }
    }

    private record Credentials(long accountId, String passwordHash) {}

    private record LiveToken(long accountId, String email, String company, Instant expiresAt) {}

    private final SalesRepositories.Customers customers;
    private final ActivityLogService activity;
    private final CurrentActor actor;
    private final CustomerAccountMailer mailer;
    private final int sessionTtlDays;
    private final int sessionIdleDays;
    private final int inviteTtlDays;
    /* Compared against when no usable login exists, so every attempt costs one bcrypt. */
    private final String dummyHash;
    private Clock clock = Clock.systemUTC();

    @Inject
    public CustomerAccountService(
            SalesRepositories.Customers customers, ActivityLogService activity, CurrentActor actor,
            CustomerAccountMailer mailer,
            @ConfigProperty(name = "enrosed.customer-account.session-ttl-days", defaultValue = "30")
            int sessionTtlDays,
            @ConfigProperty(name = "enrosed.customer-account.session-idle-days", defaultValue = "14")
            int sessionIdleDays,
            @ConfigProperty(name = "enrosed.customer-account.invite-ttl-days", defaultValue = "7")
            int inviteTtlDays) {
        this.customers = customers;
        this.activity = activity;
        this.actor = actor;
        this.mailer = mailer;
        this.sessionTtlDays = sessionTtlDays;
        this.sessionIdleDays = sessionIdleDays;
        this.inviteTtlDays = inviteTtlDays(inviteTtlDays);
        if (this.inviteTtlDays != inviteTtlDays) {
            LOG.warnf("enrosed.customer-account.invite-ttl-days=%d ligt buiten 2..30; de loginlink blijft %d dagen geldig",
                    inviteTtlDays, this.inviteTtlDays);
        }
        this.dummyHash = BcryptUtil.bcryptHash(AccountTokens.newSessionToken(), BCRYPT_COST);
    }

    /** Builds the bean at startup: the dummy hash and the configuration warning do not wait for a first login. */
    void onStart(@Observes StartupEvent event) {
    }

    /** The mail sentence is a plural and a link must survive a weekend: 2 to 30 days, else 7. */
    static int inviteTtlDays(int configured) {
        return configured < 2 || configured > 30 ? DEFAULT_INVITE_TTL_DAYS : configured;
    }

    // ------------------------------------------------------------------ giving and withdrawing

    /**
     * Find-or-create by normalised e-mail and issue one fresh token. A live login on another
     * customer blocks; a withdrawn one on another customer is re-pointed to this customer.
     * Joins the caller's transaction.
     */
    @Transactional
    public Grant grant(long customerId, String email, String contactName, Language language) {
        String normalized = AccountEmails.normalize(email);
        if (normalized == null) throw new BusinessRuleException("Vul een geldig e-mailadres in");
        Customer customer = customer(customerId);
        Instant now = clock.instant();
        /* Locked: two grants for one existing login make one live link, not two. */
        CustomerAccountEntity account = CustomerAccountEntity.<CustomerAccountEntity>find("email", normalized)
                .withLock(LockModeType.PESSIMISTIC_WRITE).list().stream().findFirst().orElse(null);
        CustomerAccountMailer.Kind kind;
        if (account == null) {
            account = new CustomerAccountEntity();
            account.customerId = customerId;
            account.email = normalized;
            account.contactName = contactName(contactName);
            account.language = (language == null ? customer.language() : language).name();
            account.status = INVITED;
            account.createdAt = now;
            account.createdBy = actor.name();
            account.updatedAt = now;
            account.persist();
            kind = CustomerAccountMailer.Kind.FIRST;
        } else if (DISABLED.equals(account.status)) {
            if (!Objects.equals(account.customerId, customerId)) {
                move(account, customer, contactName, language);
            }
            enableAgain(account);
            kind = CustomerAccountMailer.Kind.FIRST;
        } else {
            if (!Objects.equals(account.customerId, customerId)) {
                throw new BusinessRuleException("Dit e-mailadres heeft al een login bij klant "
                        + company(account.customerId) + ". Trek die login eerst in.");
            }
            kind = kindOfNewLink(account);
        }
        account.updatedAt = now;
        return issue(account, kind, now);
    }

    /** A fresh link for an existing login; a withdrawn one becomes an invitation again. */
    @Transactional
    public Grant reissue(long accountId) {
        CustomerAccountEntity account = lockedAccount(accountId);
        Instant now = clock.instant();
        CustomerAccountMailer.Kind kind;
        if (DISABLED.equals(account.status)) {
            enableAgain(account);
            kind = CustomerAccountMailer.Kind.FIRST;
        } else {
            kind = kindOfNewLink(account);
        }
        account.updatedAt = now;
        return issue(account, kind, now);
    }

    /**
     * "Login geven" on the customer sheet: a login without a request. A blank e-mail means the
     * customer's own address.
     */
    @Transactional
    public Grant grantByStaff(long customerId, String email) {
        Customer customer = customer(customerId);
        String address = email == null || email.isBlank() ? customer.email() : email;
        if (address == null || address.isBlank()) {
            throw new BusinessRuleException("Deze klant heeft geen e-mailadres");
        }
        String normalized = AccountEmails.normalize(address);
        if (normalized == null) throw new BusinessRuleException("Vul een geldig e-mailadres in");
        Grant grant = grant(customerId, normalized, customer.contact(), customer.language());
        activity.record(ActivityLogService.ACTION_STATUS_CHANGED, ACTIVITY_ENTITY,
                String.valueOf(customerId), customer.company(), "Websitelogin gegeven aan " + normalized);
        return grant;
    }

    /** "Nieuwe link sturen" by staff, with its line in the customer's logbook. */
    @Transactional
    public Grant reissueByStaff(long accountId) {
        Grant grant = reissue(accountId);
        CustomerAccountEntity account = account(accountId);
        activity.record(ActivityLogService.ACTION_UPDATED, ACTIVITY_ENTITY,
                String.valueOf(account.customerId), company(account.customerId),
                "Nieuwe loginlink verstuurd naar " + account.email);
        return grant;
    }

    /**
     * Called by a resource AFTER the granting transaction has committed, never from inside one.
     * Sends the invitation and stores last_link_sent_at / last_link_error in its own transaction.
     * Never throws.
     */
    public AccountDtos.Invitation deliver(Grant grant) {
        String error = send(grant);
        try {
            inTransaction(() -> {
                CustomerAccountEntity account = CustomerAccountEntity.findById(grant.accountId());
                if (account == null) return null;
                Instant now = clock.instant();
                if (error == null) {
                    account.lastLinkSentAt = now;
                    account.lastLinkError = null;
                } else {
                    account.lastLinkError = error;
                }
                account.updatedAt = now;
                return null;
            });
        } catch (RuntimeException exception) {
            LOG.errorf("Websitelogin %d: uitkomst van de uitnodigingsmail niet bewaard (%s)",
                    grant.accountId(), exception.getClass().getSimpleName());
        }
        return new AccountDtos.Invitation(error == null, grant.expiresAt(), error);
    }

    /** Null when the mail left, else the reason in at most 300 characters. */
    private String send(Grant grant) {
        try {
            CustomerAccountMailer.Invitation invitation = inTransaction(() -> invitation(grant));
            if (invitation == null) return "De login of de klant bestaat niet meer";
            mailer.sendInvitation(invitation);
            LOG.infof("Websitelogin %d: uitnodigingsmail verstuurd (%s)", grant.accountId(), grant.kind());
            return null;
        } catch (RuntimeException exception) {
            /* The exception itself is not logged: a mail provider may echo the body, link included. */
            LOG.warnf("Websitelogin %d: uitnodigingsmail niet verstuurd (%s)",
                    grant.accountId(), exception.getClass().getSimpleName());
            String reason = exception instanceof BusinessRuleException && exception.getMessage() != null
                    && !exception.getMessage().isBlank()
                    ? exception.getMessage().strip() : "De mail kon niet verzonden worden";
            /* This text is stored and shown to staff: whatever the mailer put in it, no link. */
            reason = AccountTokens.withoutLinks(reason);
            return reason.length() > 300 ? reason.substring(0, 300) : reason;
        }
    }

    private CustomerAccountMailer.Invitation invitation(Grant grant) {
        CustomerAccountEntity account = CustomerAccountEntity.findById(grant.accountId());
        if (account == null) return null;
        Customer customer = customers.findById(account.customerId).orElse(null);
        if (customer == null) return null;
        return new CustomerAccountMailer.Invitation(account.email, Language.of(account.language),
                firstNonBlank(account.contactName, customer.contact()), customer.company(),
                grant.rawToken(), inviteTtlDays, grant.kind());
    }

    @Transactional
    public AccountDtos.AccountView withdraw(long accountId) {
        CustomerAccountEntity account = lockedAccount(accountId);
        if (DISABLED.equals(account.status)) {
            throw new BusinessRuleException("Deze login is al ingetrokken");
        }
        Instant now = clock.instant();
        account.status = DISABLED;
        account.passwordHash = null;
        account.lastLinkSentAt = null;
        account.disabledAt = now;
        account.disabledBy = actor.name();
        account.updatedAt = now;
        CustomerSessionEntity.delete("accountId", account.id);
        CustomerAccountTokenEntity.delete("accountId", account.id);
        activity.record(ActivityLogService.ACTION_STATUS_CHANGED, ACTIVITY_ENTITY,
                String.valueOf(account.customerId), company(account.customerId),
                "Websitelogin ingetrokken (" + account.email + ")");
        LOG.infof("Websitelogin %d: ingetrokken", account.id);
        return toView(account);
    }

    // ------------------------------------------------------------------ staff views

    /** The login of this e-mail address in any status, withdrawn included. */
    @Transactional
    public Optional<AccountDtos.AccountView> findByEmail(String email) {
        String normalized = AccountEmails.normalize(email);
        if (normalized == null) return Optional.empty();
        CustomerAccountEntity account = CustomerAccountEntity.find("email", normalized).firstResult();
        return Optional.ofNullable(account).map(this::toView);
    }

    @Transactional
    public List<AccountDtos.AccountView> forCustomer(long customerId) {
        List<CustomerAccountEntity> accounts =
                CustomerAccountEntity.list("customerId = ?1 order by id", customerId);
        return accounts.stream().map(this::toView).toList();
    }

    @Transactional
    public AccountDtos.AccountView view(long accountId) {
        return toView(account(accountId));
    }

    private AccountDtos.AccountView toView(CustomerAccountEntity account) {
        Instant now = clock.instant();
        CustomerAccountTokenEntity link = CustomerAccountTokenEntity
                .find("accountId = ?1 and usedAt is null order by id desc", account.id).firstResult();
        long activeSessions = CustomerSessionEntity.count(
                "accountId = ?1 and expiresAt > ?2 and lastSeenAt > ?3",
                account.id, now, now.minus(Duration.ofDays(sessionIdleDays)));
        return new AccountDtos.AccountView(account.id, account.customerId,
                customers.findById(account.customerId).map(Customer::company).orElse(null),
                account.email, account.contactName, account.language, account.status,
                account.passwordSetAt, account.lastLoginAt, account.lastLinkSentAt,
                account.lastLinkError, link == null ? null : link.expiresAt, account.createdAt,
                account.createdBy, account.disabledAt, account.disabledBy, activeSessions);
    }

    // ------------------------------------------------------------------ customer: log in

    /**
     * One bcrypt per attempt, whatever the cause of a failure: unknown address, no password
     * yet, withdrawn, customer gone and a wrong password all end in the same 401.
     */
    public AccountDtos.SessionResponse login(String normalizedEmail, String password) {
        Credentials known = normalizedEmail == null ? null
                : inTransaction(() -> credentials(normalizedEmail));
        String submitted = password == null ? "" : password;
        boolean matches = hashing(() -> BcryptUtil.matches(submitted,
                known == null ? dummyHash : known.passwordHash()));
        AccountDtos.SessionResponse session = known == null || !matches ? null
                : inTransaction(() -> openSession(known));
        if (session == null) {
            throw new CustomerUnauthorizedException(CustomerUnauthorizedException.INVALID_CREDENTIALS);
        }
        return session;
    }

    private Credentials credentials(String normalizedEmail) {
        CustomerAccountEntity account = CustomerAccountEntity.find("email", normalizedEmail).firstResult();
        if (account == null || !ACTIVE.equals(account.status) || account.passwordHash == null
                || customers.findById(account.customerId).isEmpty()) {
            return null;
        }
        return new Credentials(account.id, account.passwordHash);
    }

    /** New session, cap of five, last login; null when the login changed while bcrypt ran. */
    private AccountDtos.SessionResponse openSession(Credentials verified) {
        CustomerAccountEntity account = CustomerAccountEntity.findById(verified.accountId());
        if (account == null || !ACTIVE.equals(account.status)
                || !verified.passwordHash().equals(account.passwordHash)) {
            return null;
        }
        Customer customer = customers.findById(account.customerId).orElse(null);
        if (customer == null) return null;
        Instant now = clock.instant();
        String token = newSession(account, now);
        List<CustomerSessionEntity> sessions = CustomerSessionEntity
                .list("accountId = ?1 order by createdAt desc, id desc", account.id);
        for (CustomerSessionEntity old : sessions.subList(Math.min(MAX_SESSIONS, sessions.size()),
                sessions.size())) {
            old.delete();
        }
        account.lastLoginAt = now;
        account.updatedAt = now;
        LOG.infof("Websitelogin %d: ingelogd", account.id);
        return new AccountDtos.SessionResponse(token,
                now.plus(Duration.ofDays(sessionTtlDays)), profile(account, customer));
    }

    private String newSession(CustomerAccountEntity account, Instant now) {
        String token = AccountTokens.newSessionToken();
        CustomerSessionEntity session = new CustomerSessionEntity();
        session.accountId = account.id;
        session.tokenHash = AccountTokens.hash(token);
        session.createdAt = now;
        session.lastSeenAt = now;
        session.expiresAt = now.plus(Duration.ofDays(sessionTtlDays));
        session.persist();
        return token;
    }

    // ------------------------------------------------------------------ customer: sessions

    /**
     * The valid session behind this token: not expired, not idle, on an active login whose
     * customer still exists. last_seen_at is written at most once per 15 minutes.
     */
    Optional<CustomerSessionGuard.CustomerSession> resolveSession(String token) {
        if (!AccountTokens.isSessionToken(token)) return Optional.empty();
        String hash = AccountTokens.hash(token);
        return Optional.ofNullable(inTransaction(() -> {
            CustomerSessionEntity session = CustomerSessionEntity.find("tokenHash", hash).firstResult();
            if (session == null) return null;
            Instant now = clock.instant();
            if (!session.expiresAt.isAfter(now)
                    || !session.lastSeenAt.isAfter(now.minus(Duration.ofDays(sessionIdleDays)))) {
                return null;
            }
            CustomerAccountEntity account = CustomerAccountEntity.findById(session.accountId);
            if (account == null || !ACTIVE.equals(account.status)
                    || customers.findById(account.customerId).isEmpty()) {
                return null;
            }
            if (session.lastSeenAt.isBefore(now.minus(LAST_SEEN_STEP))) session.lastSeenAt = now;
            return new CustomerSessionGuard.CustomerSession(session.id, account.id,
                    account.customerId, account.email);
        }));
    }

    /** What the website restores on a page load: the end of the session and the profile. */
    public AccountDtos.SessionInfo sessionInfo(CustomerSessionGuard.CustomerSession current) {
        AccountDtos.SessionInfo info = inTransaction(() -> {
            CustomerSessionEntity session = CustomerSessionEntity.findById(current.sessionId());
            CustomerAccountEntity account = CustomerAccountEntity.findById(current.accountId());
            if (session == null || account == null) return null;
            Customer customer = customers.findById(account.customerId).orElse(null);
            if (customer == null) return null;
            return new AccountDtos.SessionInfo(session.expiresAt, profile(account, customer));
        });
        if (info == null) {
            throw new CustomerUnauthorizedException(CustomerUnauthorizedException.SESSION_INVALID);
        }
        return info;
    }

    /** Logging out removes the one session of this token; an unknown token is not an error. */
    public void endSession(String token) {
        if (!AccountTokens.isSessionToken(token)) return;
        String hash = AccountTokens.hash(token);
        inTransaction(() -> CustomerSessionEntity.delete("tokenHash", hash));
    }

    // ------------------------------------------------------------------ customer: the one-time link

    /** Read-only look at a link: unknown, used, expired and withdrawn all answer token INVALID. */
    public AccountDtos.TokenInfo inspect(String token) {
        if (!AccountTokens.isInvitationToken(token)) throw invalidToken();
        String hash = AccountTokens.hash(token);
        LiveToken live = inTransaction(() -> liveToken(hash));
        if (live == null) throw invalidToken();
        return new AccountDtos.TokenInfo(live.email(), live.company(), live.expiresAt());
    }

    /**
     * The customer chooses a password with a live link and is logged in at once. The password
     * policy is judged before the link is consumed, so a refused password leaves it usable.
     */
    public AccountDtos.SessionResponse activate(String token, String password) {
        if (!AccountTokens.isInvitationToken(token)) throw invalidToken();
        String hash = AccountTokens.hash(token);
        LiveToken live = inTransaction(() -> liveToken(hash));
        if (live == null) throw invalidToken();
        String refused = passwordError(password, live.email());
        if (refused != null) throw new PublicFormValidationException(Map.of("password", refused));
        String passwordHash = hashing(() -> BcryptUtil.bcryptHash(password, BCRYPT_COST));
        AccountDtos.SessionResponse session = inTransaction(() -> consume(hash, passwordHash));
        if (session == null) throw invalidToken();
        return session;
    }

    /** REQUIRED, TOO_SHORT, TOO_LONG or INVALID; null when the password is acceptable. */
    static String passwordError(String password, String email) {
        if (password == null || password.isBlank()) return "REQUIRED";
        if (password.length() < MIN_PASSWORD_LENGTH) return "TOO_SHORT";
        /* Bcrypt reads 72 bytes; a longer password is refused, never silently cut. */
        if (password.getBytes(StandardCharsets.UTF_8).length > MAX_PASSWORD_BYTES) return "TOO_LONG";
        if (password.equalsIgnoreCase(email)) return "INVALID";
        return null;
    }

    private LiveToken liveToken(String hash) {
        CustomerAccountTokenEntity token = CustomerAccountTokenEntity.find("tokenHash", hash).firstResult();
        if (token == null || token.usedAt != null || !token.expiresAt.isAfter(clock.instant())) return null;
        CustomerAccountEntity account = CustomerAccountEntity.findById(token.accountId);
        if (account == null || !(INVITED.equals(account.status) || ACTIVE.equals(account.status))) {
            return null;
        }
        Customer customer = customers.findById(account.customerId).orElse(null);
        if (customer == null) return null;
        return new LiveToken(account.id, account.email, customer.company(), token.expiresAt);
    }

    /** Null when the link is no longer live; nothing is written in that case. */
    private AccountDtos.SessionResponse consume(String hash, String passwordHash) {
        /* The login row is locked before its link and sessions are touched, exactly as a
           withdrawal and a new link do: whoever comes second waits and then sees the outcome,
           instead of each holding the rows the other needs. */
        CustomerAccountTokenEntity link = CustomerAccountTokenEntity.find("tokenHash", hash).firstResult();
        if (link == null
                || CustomerAccountEntity.findById(link.accountId, LockModeType.PESSIMISTIC_WRITE) == null) {
            return null;
        }
        LiveToken live = liveToken(hash);
        if (live == null) return null;
        Instant now = clock.instant();
        /* One conditional update decides who used the link: two tabs cannot both win. */
        int consumed = CustomerAccountTokenEntity.update(
                "usedAt = :now where tokenHash = :hash and usedAt is null and expiresAt > :now",
                Parameters.with("now", now).and("hash", hash));
        if (consumed != 1) return null;
        CustomerAccountEntity account = CustomerAccountEntity.findById(live.accountId());
        Customer customer = customers.findById(account.customerId).orElseThrow();
        account.passwordHash = passwordHash;
        account.passwordSetAt = now;
        account.status = ACTIVE;
        account.updatedAt = now;
        CustomerSessionEntity.delete("accountId", account.id);
        CustomerAccountTokenEntity.delete("accountId", account.id);
        String token = newSession(account, now);
        activity.record(ActivityLogService.ACTION_UPDATED, ACTIVITY_ENTITY,
                String.valueOf(account.customerId), customer.company(),
                "Websitelogin geactiveerd: wachtwoord gekozen (" + account.email + ")");
        LOG.infof("Websitelogin %d: wachtwoord gekozen", account.id);
        return new AccountDtos.SessionResponse(token,
                now.plus(Duration.ofDays(sessionTtlDays)), profile(account, customer));
    }

    // ------------------------------------------------------------------ helpers

    /** FIRST while no invitation mail has ever left for this login since it was (re)given. */
    private static CustomerAccountMailer.Kind kindOfNewLink(CustomerAccountEntity account) {
        if (ACTIVE.equals(account.status) && account.passwordHash != null) {
            return CustomerAccountMailer.Kind.NEW_LINK_KEEPS_PASSWORD;
        }
        return account.lastLinkSentAt == null
                ? CustomerAccountMailer.Kind.FIRST : CustomerAccountMailer.Kind.NEW_LINK;
    }

    private static void enableAgain(CustomerAccountEntity account) {
        account.status = INVITED;
        account.disabledAt = null;
        account.disabledBy = null;
        account.lastLinkSentAt = null;
    }

    /**
     * The only way a login changes customer: a withdrawn login, which has no password, session
     * or link left, is re-pointed. Both customers get a line in their logbook.
     */
    private void move(CustomerAccountEntity account, Customer target, String contactName, Language language) {
        Customer previous = customers.findById(account.customerId).orElse(null);
        String previousCompany = previous == null ? "-" : previous.company();
        activity.record(ActivityLogService.ACTION_UPDATED, ACTIVITY_ENTITY, String.valueOf(target.id()),
                target.company(), "Websitelogin " + account.email + " verplaatst van klant " + previousCompany);
        if (previous != null) {
            activity.record(ActivityLogService.ACTION_UPDATED, ACTIVITY_ENTITY, String.valueOf(previous.id()),
                    previous.company(), "Websitelogin " + account.email + " verplaatst naar klant " + target.company());
        }
        account.customerId = target.id();
        account.contactName = contactName(contactName);
        account.language = (language == null ? target.language() : language).name();
    }

    /** One live link per login: earlier ones stop working the moment a new one is made. */
    private Grant issue(CustomerAccountEntity account, CustomerAccountMailer.Kind kind, Instant now) {
        CustomerAccountTokenEntity.delete("accountId", account.id);
        String raw = AccountTokens.newInvitationToken();
        CustomerAccountTokenEntity token = new CustomerAccountTokenEntity();
        token.accountId = account.id;
        token.tokenHash = AccountTokens.hash(raw);
        token.expiresAt = now.plus(Duration.ofDays(inviteTtlDays));
        token.createdAt = now;
        token.createdBy = actor.name();
        token.persist();
        LOG.infof("Websitelogin %d: nieuwe link gemaakt (%s)", account.id, kind);
        return new Grant(account.id, raw, token.expiresAt, kind);
    }

    private static AccountDtos.Profile profile(CustomerAccountEntity account, Customer customer) {
        return new AccountDtos.Profile(account.email,
                firstNonBlank(account.contactName, customer.contact()), customer.company(),
                customer.countryCode(), customer.vatNumber(), customer.phone(), account.language);
    }

    private CustomerAccountEntity account(long accountId) {
        CustomerAccountEntity account = CustomerAccountEntity.findById(accountId);
        if (account == null) throw new NotFoundException("Websitelogin", accountId);
        return account;
    }

    static final String DUPLICATE_LOGIN = "Dit e-mailadres heeft net een login gekregen. Herlaad de pagina.";

    /**
     * Two grants for an address without a login can both find none; the second insert then
     * hits the unique e-mail constraint. Staff resources answer that as a conflict.
     */
    static boolean isDuplicateLogin(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof org.hibernate.exception.ConstraintViolationException violation) {
                String where = violation.getConstraintName() + " " + violation.getMessage()
                        + " " + (violation.getSQLException() == null ? "" : violation.getSQLException().getMessage());
                if (where.toLowerCase(java.util.Locale.ROOT).contains("uq_customer_account_email")) return true;
            }
            if (cause.getCause() == cause) break;
        }
        return false;
    }

    /** The login row under a write lock: the first lock of every path that changes its link or sessions. */
    private CustomerAccountEntity lockedAccount(long accountId) {
        CustomerAccountEntity account = CustomerAccountEntity.findById(accountId, LockModeType.PESSIMISTIC_WRITE);
        if (account == null) throw new NotFoundException("Websitelogin", accountId);
        return account;
    }

    private Customer customer(long customerId) {
        return customers.findById(customerId).orElseThrow(() -> new NotFoundException("Klant", customerId));
    }

    private String company(Long customerId) {
        return customerId == null ? null : customers.findById(customerId).map(Customer::company).orElse(null);
    }

    private static String contactName(String value) {
        if (value == null || value.isBlank()) return null;
        String name = value.strip();
        return name.length() > 120 ? name.substring(0, 120) : name;
    }

    private static String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first : second;
    }

    private static PublicFormValidationException invalidToken() {
        return new PublicFormValidationException(Map.of("token", "INVALID"));
    }

    /** At most four hashes at a time; no permit within two seconds answers 503 with Retry-After. */
    private static <T> T hashing(Supplier<T> work) {
        boolean permitted;
        try {
            permitted = BCRYPT.tryAcquire(2, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            permitted = false;
        }
        if (!permitted) throw new PublicFormServiceUnavailableException();
        try {
            return work.get();
        } finally {
            BCRYPT.release();
        }
    }

    /**
     * A short transaction for one step, so that bcrypt and the mail provider never hold a
     * database connection. Inside a caller's transaction the step joins it.
     */
    private static <T> T inTransaction(Callable<T> step) {
        return QuarkusTransaction.joiningExisting().call(step);
    }

    /** Tests pin the moment that links and sessions are judged at. */
    void useClock(Clock clock) {
        this.clock = clock;
    }
}
