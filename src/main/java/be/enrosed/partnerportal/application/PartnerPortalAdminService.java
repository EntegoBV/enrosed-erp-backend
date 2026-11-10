package be.enrosed.partnerportal.application;

import be.enrosed.catalog.application.StockService;
import be.enrosed.catalog.domain.StockLocation;
import be.enrosed.partnerportal.adapter.out.persistence.PartnerLoginEntity;
import be.enrosed.partnerportal.adapter.out.persistence.PartnerPortalQueries;
import be.enrosed.partnerportal.adapter.out.persistence.PartnerPortalRepositories;
import be.enrosed.partnerportal.adapter.out.persistence.PartnerPortalSettingsEntity;
import be.enrosed.partnerportal.domain.PartnerNumbers;
import be.enrosed.partnerportal.domain.PartnerPortalRules;
import be.enrosed.sales.application.port.out.SalesRepositories;
import be.enrosed.sales.domain.Customer;
import be.enrosed.shared.BusinessRuleException;
import be.enrosed.shared.NotFoundException;
import be.enrosed.shared.audit.ActivityChangeSet;
import be.enrosed.shared.audit.ActivityLogService;
import be.enrosed.shared.security.AdminIdentityProvider;
import be.enrosed.shared.security.CurrentActor;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Instellingen › Partnerportaal: the partner customers with their login, cost percents and stock
 * location, and the issued settlements whose pieces were never booked out (they still lie in
 * stock although sold, so the portal would offer them again).
 */
@ApplicationScoped
public class PartnerPortalAdminService {

    static final String LOGIN_ENTITY = "PARTNER_LOGIN";
    static final String SETTINGS_ENTITY = "PARTNER_PORTAL_SETTINGS";

    private final SalesRepositories.Customers customers;
    private final PartnerPortalRepositories.Logins logins;
    private final PartnerPortalRepositories.Settings settingsRows;
    private final PartnerPortalSettings settings;
    private final PartnerPortalQueries queries;
    private final PartnerSessionService sessions;
    private final PartnerPasswords passwords;
    private final AdminIdentityProvider staff;
    private final ActivityLogService activity;
    private final CurrentActor actor;
    private final StockService stock;

    public PartnerPortalAdminService(SalesRepositories.Customers customers, PartnerPortalRepositories.Logins logins,
                                     PartnerPortalRepositories.Settings settingsRows, PartnerPortalSettings settings,
                                     PartnerPortalQueries queries, PartnerSessionService sessions,
                                     PartnerPasswords passwords, AdminIdentityProvider staff,
                                     ActivityLogService activity, CurrentActor actor, StockService stock) {
        this.customers = customers;
        this.logins = logins;
        this.settingsRows = settingsRows;
        this.settings = settings;
        this.queries = queries;
        this.sessions = sessions;
        this.passwords = passwords;
        this.staff = staff;
        this.activity = activity;
        this.actor = actor;
        this.stock = stock;
    }

    /** Partner customers, plus any customer that still has a login although it is no partner any more. */
    @Transactional
    public List<PartnerPortalViews.AdminRow> rows() {
        Map<Long, PartnerLoginEntity> loginByCustomer = new HashMap<>();
        for (PartnerLoginEntity login : logins.listAll()) {
            if (login.customerId != null) loginByCustomer.put(login.customerId, login);
        }
        List<Customer> shown = new ArrayList<>();
        for (Customer customer : customers.findAll()) {
            if (customer.id() != null && (customer.partner() || loginByCustomer.containsKey(customer.id()))) {
                shown.add(customer);
            }
        }
        shown.sort(Comparator.comparing((Customer customer) -> customer.company() == null ? ""
                        : customer.company().toLowerCase(Locale.ROOT))
                .thenComparing(Customer::id));
        return shown.stream().map(customer -> row(customer, loginByCustomer.get(customer.id()))).toList();
    }

    @Transactional
    public PartnerPortalViews.AdminRow row(long customerId) {
        Customer customer = customer(customerId);
        return row(customer, logins.findByCustomer(customerId).orElse(null));
    }

    /**
     * Creates or changes the login of a partner. The password is hashed before any transaction
     * starts; a new password, another username or switching the login off signs every device out.
     */
    public PartnerPortalViews.AdminRow saveLogin(long customerId, PartnerPortalViews.LoginRequest request) {
        if (request == null) throw new BusinessRuleException("Geen gegevens ontvangen");
        String username = PartnerPortalRules.username(request.username());
        if (staff.isStaffUsername(username)) {
            throw new BusinessRuleException("Deze gebruikersnaam is van een medewerker; kies een andere");
        }
        String displayName = PartnerPortalRules.displayName(request.displayName());
        String password = request.password() == null || request.password().isEmpty() ? null : request.password();
        if (password != null) PartnerPortalRules.requireValidPassword(password);
        String hash = password == null ? null : passwords.hash(password);
        QuarkusTransaction.requiringNew().run(() -> writeLogin(customerId, username, displayName, hash, request.active()));
        return row(customerId);
    }

    private void writeLogin(long customerId, String username, String displayName, String hash, Boolean active) {
        Customer customer = customer(customerId);
        settingsRows.lockOrCreate(customerId);
        PartnerLoginEntity login = logins.findByCustomer(customerId).orElse(null);
        boolean create = login == null;
        boolean wantActive = active == null ? create || login.isActive() : active;
        if (!customer.partner() && (create || wantActive)) {
            throw new BusinessRuleException("Deze klant is geen partner meer; zet eerst Partnercontainers aan bij de klant");
        }
        PartnerLoginEntity other = logins.findByUsername(username).orElse(null);
        if (other != null && (create || !Objects.equals(other.id, login.id))) {
            throw new BusinessRuleException("Deze gebruikersnaam is al in gebruik");
        }
        if (create && hash == null) throw new BusinessRuleException("Stel een wachtwoord in voor de eerste aanmelding");
        Instant now = Instant.now();
        String who = actor.name();
        List<String> said = new ArrayList<>();
        boolean signOut = false;
        if (create) {
            login = new PartnerLoginEntity();
            login.customerId = customerId;
            login.createdAt = now;
            login.createdBy = who;
            said.add("Login aangemaakt");
        } else {
            if (!username.equals(login.username)) {
                said.add("Gebruikersnaam gewijzigd");
                signOut = true;
            }
            if (!displayName.equals(login.displayName)) said.add("Naam gewijzigd");
            if (hash != null) signOut = true;
            if (login.isActive() && !wantActive) signOut = true;
        }
        if (hash != null) said.add(create ? "Wachtwoord ingesteld" : "Wachtwoord gewijzigd");
        if (!create && login.isActive() != wantActive) said.add(wantActive ? "Geactiveerd" : "Gedeactiveerd");
        if (create && !wantActive) said.add("Gedeactiveerd");
        if (!create && said.isEmpty()) return;
        String before = create ? null : login.username;
        login.username = username;
        login.displayName = displayName;
        login.active = wantActive;
        if (hash != null) {
            login.passwordHash = hash;
            login.passwordSetAt = now;
        }
        login.updatedAt = now;
        login.updatedBy = who;
        if (create) logins.persist(login);
        logins.flush();
        if (signOut) sessions.deleteAll(login.id);
        activity.record(create ? ActivityLogService.ACTION_CREATED : ActivityLogService.ACTION_UPDATED,
                LOGIN_ENTITY, login.id.toString(), customer.company() + " · " + username, String.join(" · ", said),
                ActivityChangeSet.create()
                        .add("username", "Gebruikersnaam", before, username)
                        .build());
    }

    /** Cost percents (0..50, null = default) and the stock location (null = MAIN) of a partner. */
    @Transactional
    public PartnerPortalViews.AdminRow saveSettings(long customerId, PartnerPortalViews.SettingsRequest request) {
        if (request == null) throw new BusinessRuleException("Geen gegevens ontvangen");
        Customer customer = customer(customerId);
        if (!customer.partner()) throw new BusinessRuleException("Deze klant is geen partner");
        BigDecimal commission = PartnerPortalRules.settingPct(request.auctionCommissionPct());
        BigDecimal transport = PartnerPortalRules.settingPct(request.auctionTransportPct());
        Long locationId = request.locationId();
        if (locationId != null) {
            StockLocation location = stock.locations().stream().filter(candidate -> locationId.equals(candidate.id()))
                    .findFirst().orElseThrow(() -> new BusinessRuleException("Deze voorraadlocatie bestaat niet meer"));
            if (!location.active()) throw new BusinessRuleException("Kies een actieve voorraadlocatie");
        }
        PartnerPortalSettingsEntity row = settingsRows.lockOrCreate(customerId);
        PartnerPortalSettings.Effective before = settings.of(customerId, row);
        boolean same = sameNumber(row.auctionCommissionPct, commission) && sameNumber(row.auctionTransportPct, transport)
                && Objects.equals(row.locationId, locationId);
        if (!same) {
            row.auctionCommissionPct = commission;
            row.auctionTransportPct = transport;
            row.locationId = locationId;
            row.updatedAt = Instant.now();
            row.updatedBy = actor.name();
            settingsRows.flush();
            PartnerPortalSettings.Effective after = settings.of(customerId, row);
            activity.record(ActivityLogService.ACTION_UPDATED, SETTINGS_ENTITY, Long.toString(customerId),
                    customer.company(), "Partnerportaal bijgewerkt", ActivityChangeSet.create()
                            .add("auctionCommissionPct", "Veilingcommissie",
                                    pctText(before.storedCommissionPct()), pctText(commission))
                            .add("auctionTransportPct", "Transport",
                                    pctText(before.storedTransportPct()), pctText(transport))
                            .add("locationId", "Voorraadlocatie", locationText(before), locationText(after))
                            .build());
        }
        return row(customer, logins.findByCustomer(customerId).orElse(null));
    }

    private PartnerPortalViews.AdminRow row(Customer customer, PartnerLoginEntity login) {
        long customerId = customer.id();
        PartnerPortalSettings.Effective applied = settings.of(customerId);
        PartnerPortalViews.AdminLogin loginView = login == null ? null : new PartnerPortalViews.AdminLogin(
                login.id, login.username, login.displayName, login.isActive(), login.passwordSetAt, login.lastLoginAt);
        PartnerPortalViews.AdminSettings settingsView = new PartnerPortalViews.AdminSettings(
                PartnerNumbers.pct(applied.storedCommissionPct()), PartnerNumbers.pct(applied.storedTransportPct()),
                applied.storedLocationId(), applied.location().name(),
                PartnerNumbers.pct(PartnerPortalRules.DEFAULT_AUCTION_COMMISSION_PCT),
                PartnerNumbers.pct(PartnerPortalRules.DEFAULT_AUCTION_TRANSPORT_PCT));
        List<PartnerPortalViews.UnshippedSettlement> unshipped = queries.unshippedSettlements(customerId).stream()
                .map(settlement -> new PartnerPortalViews.UnshippedSettlement(settlement.id(), settlement.number()))
                .toList();
        return new PartnerPortalViews.AdminRow(customerId, customer.company(), customer.partner(), loginView,
                settingsView, unshipped);
    }

    private Customer customer(long customerId) {
        return customers.findById(customerId).orElseThrow(() -> new NotFoundException("Klant", customerId));
    }

    private static boolean sameNumber(BigDecimal left, BigDecimal right) {
        if (left == null || right == null) return left == right;
        return left.compareTo(right) == 0;
    }

    /** "Magazijn", or "Magazijn (standaard)" when no location was chosen. */
    private static String locationText(PartnerPortalSettings.Effective applied) {
        return applied.storedLocationId() == null ? applied.location().name() + " (standaard)" : applied.location().name();
    }

    /** "4,5 %"; null reads as "standaard". */
    private static String pctText(BigDecimal value) {
        if (value == null) return "standaard";
        DecimalFormat format = new DecimalFormat("0.####", DecimalFormatSymbols.getInstance(Locale.forLanguageTag("nl-BE")));
        return format.format(value) + " %";
    }
}
