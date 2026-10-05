package be.enrosed.account;

import be.enrosed.publicform.PublicFormAction;
import be.enrosed.publicform.PublicFormRateLimitException;
import be.enrosed.publicform.PublicFormRateLimiter;
import be.enrosed.push.WebPushNotifier;
import be.enrosed.sales.application.port.out.SalesRepositories;
import be.enrosed.sales.domain.Customer;
import be.enrosed.shared.mail.InternalMessageSender;
import be.enrosed.shared.mail.InternalMessageSender.TeamFact;
import be.enrosed.shared.mail.InternalMessageSender.TeamNotice;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.event.TransactionPhase;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;

/**
 * Tells the team about a login request: one push and one team mail, off the request thread.
 *
 * Everything a login request causes besides its own row runs on this executor, so the public
 * answers take the same time whether or not an address has a login. The threads have neither
 * a request context nor a transaction: every database access here sits inside a transaction
 * this code opens itself. No outbox and no job: a notice is tried once, and the request stays
 * in the list and in the count whatever happens to it.
 */
@ApplicationScoped
public class LoginRequestNotifier {
    private static final Logger LOG = Logger.getLogger(LoginRequestNotifier.class);
    private static final String PUSH_KIND = "login-request";
    private static final String ERP_PATH = "/klantlogins";
    private static final int NOTICES_PER_HOUR = 6;
    private static final int NOTICES_PER_DAY = 20;

    /** Tests set Runnable::run or a capturing one, through useExecutor. */
    Executor executor = ForkJoinPool.commonPool();

    private final LoginRequestWriter writer;
    private final SalesRepositories.Customers customers;
    private final PublicFormRateLimiter rateLimiter;
    private final WebPushNotifier push;
    private final InternalMessageSender sender;
    private final AccountHousekeeping housekeeping;
    private final String portalBaseUrl;

    /** What leaves for one request: the push stays free of personal data, the mail carries the facts. */
    private record Notice(String pushTitle, String pushBody, TeamNotice mail) {}

    /** The request as it was read; notice is null when none is due for it. */
    private record Loaded(Notice notice) {}

    @Inject
    public LoginRequestNotifier(LoginRequestWriter writer, SalesRepositories.Customers customers,
                                PublicFormRateLimiter rateLimiter, WebPushNotifier push,
                                InternalMessageSender sender, AccountHousekeeping housekeeping,
                                @ConfigProperty(name = "enrosed.portal.base-url",
                                        defaultValue = "http://localhost:4321") String portalBaseUrl) {
        this.writer = writer;
        this.customers = customers;
        this.rateLimiter = rateLimiter;
        this.push = push;
        this.sender = sender;
        this.housekeeping = housekeeping;
        this.portalBaseUrl = portalBaseUrl;
    }

    /** Hands work to the background; a task that fails is logged and never reaches the caller. */
    public void runLater(Runnable task) {
        try {
            executor.execute(() -> {
                try {
                    task.run();
                } catch (RuntimeException exception) {
                    LOG.errorf("Achtergrondtaak voor login-aanvragen mislukt (%s)",
                            exception.getClass().getSimpleName());
                }
            });
        } catch (RuntimeException exception) {
            LOG.errorf("Achtergrondtaak voor login-aanvragen kon niet starten (%s)",
                    exception.getClass().getSimpleName());
        }
    }

    /** Reads nothing and sends nothing on the committing thread. */
    void onReceived(@Observes(during = TransactionPhase.AFTER_SUCCESS) LoginRequestReceived event) {
        runLater(() -> process(event.id(), event.changed()));
    }

    /** Runs on the executor: read the request, respect the cap, push, mail, clean up. */
    void process(long requestId, boolean changed) {
        Loaded loaded;
        try {
            loaded = QuarkusTransaction.requiringNew().call(() -> load(requestId, changed));
        } catch (RuntimeException exception) {
            LOG.errorf("Login-aanvraag %d: kon niet gelezen worden voor de melding (%s)",
                    requestId, exception.getClass().getSimpleName());
            return;
        }
        if (loaded == null) return;
        Notice notice = loaded.notice();
        if (notice != null && withinCap(requestId)) {
            try {
                /* notifyAll reads the devices without a transaction of its own; on this thread
                   a bare call would throw and no push would ever leave. */
                QuarkusTransaction.requiringNew().run(() ->
                        push.notifyAll(PUSH_KIND, notice.pushTitle(), notice.pushBody(), ERP_PATH));
            } catch (RuntimeException exception) {
                LOG.errorf("Login-aanvraag %d: pushmelding kon niet vertrekken (%s)",
                        requestId, exception.getClass().getSimpleName());
            }
            try {
                sender.sendTeamNotice(notice.mail());
            } catch (RuntimeException exception) {
                LOG.errorf("Login-aanvraag %d: teammail kon niet vertrekken (%s)",
                        requestId, exception.getClass().getSimpleName());
            }
        }
        housekeeping.purge();
    }

    /**
     * At most six notices an hour and twenty a day, however many requests come in. A request
     * beyond that arrives silently; it is in the list and in the count like any other.
     */
    private boolean withinCap(long requestId) {
        try {
            rateLimiter.checkKey(PublicFormAction.ACCOUNT_NOTICE_HOUR, "GLOBAL", "staff", NOTICES_PER_HOUR);
            rateLimiter.checkKey(PublicFormAction.ACCOUNT_NOTICE_DAY, "GLOBAL", "staff", NOTICES_PER_DAY);
            return true;
        } catch (PublicFormRateLimitException capped) {
            LOG.infof("Login-aanvraag %d: geen melding, het maximum aantal meldingen is bereikt", requestId);
            return false;
        } catch (RuntimeException exception) {
            LOG.errorf("Login-aanvraag %d: geen melding, het maximum kon niet gecontroleerd worden (%s)",
                    requestId, exception.getClass().getSimpleName());
            return false;
        }
    }

    /** Null when the request no longer exists. */
    private Loaded load(long requestId, boolean changed) {
        CustomerLoginRequestEntity row = CustomerLoginRequestEntity.findById(requestId);
        if (row == null) return null;
        if (changed) {
            List<LoginRequestDtos.LaterSubmission> later = writer.later(row);
            if (later != null && !later.isEmpty()) {
                LoginRequestDtos.LaterSubmission last = later.getLast();
                return new Loaded(LoginRequestWriter.NEW_LINK.equals(last.source())
                        ? linkAskedOnOpenRequest(row) : submittedAgain(row, last));
            }
        }
        /* A request that came with a quote rings nobody: the quote's own team mail says so. */
        if (LoginRequestWriter.QUOTE.equals(row.source)) return new Loaded(null);
        return new Loaded(LoginRequestWriter.NEW_LINK.equals(row.source) ? newLink(row) : newRequest(row));
    }

    private Notice newRequest(CustomerLoginRequestEntity row) {
        String company = value(row.companyName);
        List<TeamFact> facts = List.of(
                new TeamFact("Bedrijf", company),
                new TeamFact("Contact", value(row.contactName)),
                new TeamFact("E-mail", value(row.email)),
                new TeamFact("Telefoon", value(row.phone)),
                new TeamFact("BTW-nummer", value(row.vatNumber)),
                new TeamFact("Land", value(row.companyCountryCode)),
                new TeamFact("Taal", value(row.language)),
                new TeamFact("Bron", "Loginformulier"));
        return new Notice("Nieuwe login-aanvraag", "Wacht op goedkeuring bij Login-aanvragen",
                mail("Nieuwe login-aanvraag " + row.reference + " · " + company,
                        "Website · login-aanvraag",
                        company + " vraagt een login",
                        "Keur de aanvraag goed of wijs ze af in het ERP. Zonder jullie goedkeuring"
                                + " krijgt niemand toegang.",
                        facts, "Bericht van de aanvrager", row.message, row.id));
    }

    private Notice newLink(CustomerLoginRequestEntity row) {
        String company = value(row.companyName);
        List<TeamFact> facts = List.of(
                new TeamFact("Bedrijf", company),
                new TeamFact("E-mail", value(row.email)),
                new TeamFact("Taal", value(row.language)));
        return new Notice("Nieuwe loginlink gevraagd", "Stuur de link vanuit Login-aanvragen",
                mail("Nieuwe loginlink gevraagd · " + company,
                        "Website · wachtwoord vergeten",
                        company + " vraagt een nieuwe loginlink",
                        "Deze klant heeft al een login. Stuur met één tik een nieuwe link vanuit"
                                + " Login-aanvragen.",
                        facts, null, null, row.id));
    }

    /** The facts are those of the version that was just listed beside the first request. */
    private Notice submittedAgain(CustomerLoginRequestEntity row, LoginRequestDtos.LaterSubmission last) {
        List<TeamFact> facts = List.of(
                new TeamFact("Bedrijf", value(last.companyName())),
                new TeamFact("Contact", value(last.contactName())),
                new TeamFact("BTW-nummer", value(last.vatNumber())),
                new TeamFact("Land", value(last.companyCountryCode())),
                new TeamFact("Bron", LoginRequestWriter.QUOTE.equals(last.source())
                        ? "Offerteaanvraag " + value(last.salesOrderNumber()) : "Loginformulier"));
        return new Notice("Login-aanvraag opnieuw ingediend",
                "Met andere gegevens. Bekijk ze bij Login-aanvragen",
                mail("Login-aanvraag " + row.reference + " opnieuw ingediend met andere gegevens",
                        "Website · login-aanvraag",
                        row.email + " vroeg opnieuw een login, met andere gegevens",
                        "De eerste aanvraag staat nog open en is niet gewijzigd. Vergelijk ze in het"
                                + " ERP voor je goedkeurt of afwijst.",
                        facts, null, null, row.id));
    }

    private Notice linkAskedOnOpenRequest(CustomerLoginRequestEntity row) {
        CustomerAccountEntity account = CustomerAccountEntity.find("email", row.email).firstResult();
        String holder = account == null ? null
                : customers.findById(account.customerId).map(Customer::company).orElse(null);
        List<TeamFact> facts = List.of(
                new TeamFact("E-mail", value(row.email)),
                new TeamFact("Klant met deze login", value(holder)));
        return new Notice("Nieuwe loginlink gevraagd",
                "Bij een open login-aanvraag. Bekijk ze bij Login-aanvragen",
                mail("Nieuwe loginlink gevraagd · " + row.email,
                        "Website · wachtwoord vergeten",
                        row.email + " vraagt een nieuwe loginlink",
                        "Dit e-mailadres heeft al een login, en er staat ook nog een login-aanvraag"
                                + " voor open. Goedkeuren van die aanvraag stuurt de nieuwe link; wijs"
                                + " je ze af, stuur de link dan zelf via Klanten, blok Websitelogin.",
                        facts, null, null, row.id));
    }

    private TeamNotice mail(String subject, String kicker, String title, String intro,
                            List<TeamFact> facts, String messageTitle, String message, long requestId) {
        String erpUrl = portalBaseUrl.replaceAll("/+$", "") + ERP_PATH + "?open=" + requestId;
        boolean hasMessage = message != null && !message.isBlank();
        List<String> text = new ArrayList<>();
        text.add(subject);
        for (TeamFact fact : facts) text.add(fact.label() + ": " + fact.value());
        if (hasMessage) {
            text.add("");
            text.add(messageTitle + ":");
            text.add(message);
        }
        text.add("");
        text.add(erpUrl);
        return new TeamNotice(subject, kicker, title, intro, facts, List.of(),
                hasMessage ? messageTitle : null, hasMessage ? message : null,
                "Open in het ERP", erpUrl, null, null, String.join("\n", text));
    }

    private static String value(String value) {
        return value == null || value.isBlank() ? "—" : value.strip();
    }

    /** Tests run the tasks on the test thread or capture them. */
    void useExecutor(Executor executor) {
        this.executor = executor;
    }
}
