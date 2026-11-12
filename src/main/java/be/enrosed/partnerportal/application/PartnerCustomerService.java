package be.enrosed.partnerportal.application;

import be.enrosed.partnerportal.adapter.out.persistence.PartnerCustomerLinkEntity;
import be.enrosed.partnerportal.adapter.out.persistence.PartnerCustomerQueries;
import be.enrosed.partnerportal.adapter.out.persistence.PartnerPortalRepositories;
import be.enrosed.partnerportal.domain.PartnerPortalRules;
import be.enrosed.publicform.PublicFormAction;
import be.enrosed.publicform.PublicFormRateLimitException;
import be.enrosed.publicform.PublicFormRateLimiter;
import be.enrosed.sales.application.CustomerPartnerOrigins;
import be.enrosed.sales.application.CustomerService;
import be.enrosed.sales.application.port.out.SalesRepositories;
import be.enrosed.sales.domain.Customer;
import be.enrosed.shared.BusinessRuleException;
import be.enrosed.shared.NotFoundException;
import be.enrosed.shared.audit.ActivityLogService;
import be.enrosed.shared.security.ActorRef;
import be.enrosed.shared.security.CurrentActor;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import java.text.Normalizer;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * The buyer of a direct sale (contract R2 section 4 and v2-E): the partner searches every ERP customer by
 * company or city and sees only {customerId, company, city, countryCode, addedByYou}, or adds one itself with
 * the fewest fields (staff complete it later and mark it "Nagekeken"). Partner customers are never shown.
 *
 * <ul>
 * <li>search: at least 2 letters or digits (%, _ and \ are taken literally), at most 20 answers, 300 searches
 *     per login per day (then 429); an empty query answers this partner's last 8 buyers. Left out: a customer
 *     whose only sales documents are website requests still waiting for review (unless this partner added it);</li>
 * <li>add: one transaction under the partner lock (a double click waits and finds the first): the same name
 *     (case, accents, punctuation and spaces aside) among the customers this partner added answers that one
 *     (200), at most 20 new customers per partner per day, then the customer and its link.</li>
 * </ul>
 */
@ApplicationScoped
public class PartnerCustomerService {

    /** Nagekeken on a customer without a VAT number (the staff sheet asks for it first). */
    static final String REVIEW_NEEDS_VAT = "Nagekeken kan pas met een BTW-nummer";

    static final int MIN_LETTERS = 2;
    static final int MAX_QUERY = 100;
    static final int MAX_RESULTS = 20;
    static final int RECENT_BUYERS = 8;
    static final int SEARCHES_PER_DAY = 300;
    static final int NEW_PER_DAY = 20;
    static final int MAX_COMPANY = 160;
    static final int MAX_CITY = 100;
    static final int MAX_EMAIL = 160;
    static final int MAX_PHONE = 40;
    /** The country when neither the request nor the partner has one: the partner sells at the Dutch auction. */
    static final String FALLBACK_COUNTRY = "NL";

    private static final Set<String> ISO_COUNTRIES = Set.of(Locale.getISOCountries());
    private static final Pattern EMAIL = Pattern.compile("[^@\\s]+@[^@\\s]+\\.[^@\\s]+");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    /** What an add did: the tile, and false when the partner had already added a customer of that name. */
    public record Added(PartnerPortalViews.PartnerCustomerTile tile, boolean created) {}

    private final PartnerCustomerQueries queries;
    private final PartnerPortalRepositories.CustomerLinks links;
    private final PartnerPortalRepositories.Reports reports;
    private final PartnerPortalRepositories.Settings settingsRows;
    private final SalesRepositories.Customers customers;
    private final CustomerService customerService;
    private final PublicFormRateLimiter limiter;
    private final CurrentActor actor;
    private final ActivityLogService activity;

    public PartnerCustomerService(PartnerCustomerQueries queries, PartnerPortalRepositories.CustomerLinks links,
                                  PartnerPortalRepositories.Reports reports,
                                  PartnerPortalRepositories.Settings settingsRows, SalesRepositories.Customers customers,
                                  CustomerService customerService, PublicFormRateLimiter limiter, CurrentActor actor,
                                  ActivityLogService activity) {
        this.queries = queries;
        this.links = links;
        this.reports = reports;
        this.settingsRows = settingsRows;
        this.customers = customers;
        this.customerService = customerService;
        this.limiter = limiter;
        this.actor = actor;
        this.activity = activity;
    }

    /* ---------------------------------------------------------------- search */

    @Transactional
    public List<PartnerPortalViews.PartnerCustomerTile> search(long partnerId, long loginId, String q) {
        String typed = q == null ? "" : q.replaceAll("\\p{Cntrl}", " ").strip();
        Set<Long> mine = new LinkedHashSet<>();
        for (PartnerCustomerLinkEntity link : links.addedBy(partnerId)) if (link.customerId != null) mine.add(link.customerId);
        if (typed.isEmpty()) return recent(partnerId, mine);
        if (typed.length() > MAX_QUERY) throw PartnerRefusals.badRequest("Zoek met hoogstens " + MAX_QUERY + " tekens");
        if (typed.codePoints().filter(Character::isLetterOrDigit).count() < MIN_LETTERS) return List.of();
        try {
            limiter.checkKey(PublicFormAction.PARTNER_CUSTOMER_SEARCH, "LOGIN", Long.toString(loginId), SEARCHES_PER_DAY);
        } catch (PublicFormRateLimitException limited) {
            throw PartnerRefusals.tooMany("Je zocht vandaag al heel vaak naar klanten; probeer het morgen opnieuw.",
                    limited.retryAfterSeconds());
        }
        String escaped = escape(typed.toLowerCase(Locale.ROOT));
        return tiles(queries.search(partnerId, "%" + escaped + "%", escaped + "%", mine, MAX_RESULTS), mine);
    }

    /** The last buyers of this partner's meldingen, then the customers it added most recently: at most 8. */
    private List<PartnerPortalViews.PartnerCustomerTile> recent(long partnerId, Set<Long> mine) {
        Set<Long> ids = new LinkedHashSet<>(reports.recentBuyers(partnerId, RECENT_BUYERS));
        for (Long id : mine) {
            if (ids.size() >= RECENT_BUYERS) break;
            ids.add(id);
        }
        Map<Long, PartnerCustomerQueries.Row> rows = new HashMap<>();
        for (PartnerCustomerQueries.Row row : queries.byIds(partnerId, ids)) rows.put(row.id(), row);
        List<PartnerCustomerQueries.Row> ordered = new ArrayList<>();
        for (Long id : ids) if (rows.containsKey(id)) ordered.add(rows.get(id));
        return tiles(ordered, mine);
    }

    /** LIKE-safe: \, % and _ escaped with \ (the queries say escape '\'). */
    static String escape(String typed) {
        return typed.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static List<PartnerPortalViews.PartnerCustomerTile> tiles(List<PartnerCustomerQueries.Row> rows, Set<Long> mine) {
        return rows.stream().map(row -> tile(row, mine.contains(row.id()))).toList();
    }

    private static PartnerPortalViews.PartnerCustomerTile tile(PartnerCustomerQueries.Row row, boolean mine) {
        return new PartnerPortalViews.PartnerCustomerTile(row.id(), row.company(), blankToNull(row.city()),
                blankToNull(row.countryCode()), mine);
    }

    /* ------------------------------------------------------------------- add */

    @Transactional
    public Added add(long partnerId, long loginId, PartnerSaleRequests.NewCustomer request) {
        if (request == null) throw new BusinessRuleException("Geen gegevens ontvangen");
        String company = collapse(clean(request.company()));
        if (company == null) throw new BusinessRuleException("Vul de bedrijfsnaam in");
        if (company.length() > MAX_COMPANY) throw new BusinessRuleException("Een bedrijfsnaam heeft hoogstens " + MAX_COMPANY + " tekens");
        String city = clean(request.city());
        if (city != null && city.length() > MAX_CITY) throw new BusinessRuleException("Een plaats heeft hoogstens " + MAX_CITY + " tekens");
        String email = clean(request.email());
        if (email != null && (email.length() > MAX_EMAIL || !EMAIL.matcher(email).matches())) {
            throw new BusinessRuleException("Dit e-mailadres klopt niet");
        }
        String phone = clean(request.phone());
        if (phone != null && phone.length() > MAX_PHONE) throw new BusinessRuleException("Een telefoonnummer heeft hoogstens " + MAX_PHONE + " tekens");
        Customer partner = customers.findById(partnerId).orElseThrow(() -> new NotFoundException("Klant", partnerId));
        if (!partner.partner()) throw new BusinessRuleException("Deze klant is geen partner");
        String country = clean(request.countryCode());
        if (country == null) country = blankToNull(partner.countryCode());
        country = country == null ? FALLBACK_COUNTRY : country.toUpperCase(Locale.ROOT);
        if (!ISO_COUNTRIES.contains(country)) throw new BusinessRuleException("Kies een land uit de lijst");

        /* Lock 1: two clicks of the same add run one after the other; the second finds the first. */
        settingsRows.lockOrCreate(partnerId);
        Set<Long> mine = new LinkedHashSet<>();
        for (PartnerCustomerLinkEntity link : links.addedBy(partnerId)) if (link.customerId != null) mine.add(link.customerId);
        String key = normalized(company);
        for (PartnerCustomerQueries.Row row : queries.byIds(partnerId, mine)) {
            if (key.equals(normalized(row.company()))) return new Added(tile(row, true), false);
        }
        Instant midnight = PartnerPortalRules.today().atStartOfDay(PartnerPortalRules.ZONE).toInstant();
        if (links.addedSince(partnerId, midnight) >= NEW_PER_DAY) {
            throw new BusinessRuleException("Vandaag kwamen er al " + NEW_PER_DAY
                    + " nieuwe klanten bij; bel Enrosed voor meer of probeer het morgen opnieuw.");
        }
        ActorRef who = actor.current();
        String name = who.displayName();
        Customer created = customerService.createMinimal(company, country, city, email, phone,
                "Aangemaakt via Partnerportaal door " + name + " op " + DAY.format(PartnerPortalRules.today()),
                "Klant aangemaakt via Partnerportaal (" + name + ")");
        PartnerCustomerLinkEntity link = new PartnerCustomerLinkEntity();
        link.partnerCustomerId = partnerId;
        link.customerId = created.id();
        link.addedByPartner = true;
        link.loginId = loginId;
        link.createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        link.createdBy = who.username();
        link.createdByName = cut(name, 100);
        links.persist(link);
        links.flush();
        return new Added(new PartnerPortalViews.PartnerCustomerTile(created.id(), created.company(),
                blankToNull(created.city()), blankToNull(created.countryCode()), true), true);
    }

    /* -------------------------------------------------------------- reviewed */

    /**
     * Staff marked a customer a partner added as reviewed ("Nagekeken"): its bell item goes, and a staff save
     * needs the VAT number again. 404 when no partner added it. Takes the partner lock of each partner involved.
     * Refused (409 {@link #REVIEW_NEEDS_VAT}) while the customer has no VAT number and is not reviewed yet: the
     * VAT rule every later staff save applies holds here too, so a reviewed customer always has one. Marking an
     * already reviewed customer again changes nothing and answers its origin.
     */
    @Transactional
    public CustomerPartnerOrigins.Origin reviewed(long customerId) {
        Customer customer = customers.findById(customerId).orElseThrow(() -> new NotFoundException("Klant", customerId));
        List<PartnerCustomerLinkEntity> added = links.addedFor(List.of(customerId));
        if (added.isEmpty()) throw new NotFoundException("Deze klant komt niet uit het partnerportaal");
        for (Long partnerId : new TreeSet<>(added.stream().map(link -> link.partnerCustomerId)
                .filter(java.util.Objects::nonNull).toList())) {
            settingsRows.lockOrCreate(partnerId);
        }
        for (PartnerCustomerLinkEntity link : added) links.getEntityManager().refresh(link);
        boolean waiting = added.stream().anyMatch(link -> link.reviewedAt == null);
        if (waiting && (customer.vatNumber() == null || customer.vatNumber().isBlank())) {
            throw new BusinessRuleException(REVIEW_NEEDS_VAT);
        }
        ActorRef who = actor.current();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        boolean changed = false;
        for (PartnerCustomerLinkEntity link : added) {
            if (link.reviewedAt != null) continue;
            link.reviewedAt = now;
            link.reviewedBy = who.username();
            changed = true;
        }
        links.flush();
        if (changed) {
            activity.record(ActivityLogService.ACTION_UPDATED, "CUSTOMER", Long.toString(customerId), customer.company(),
                    "Nagekeken (aangemaakt via Partnerportaal)");
        }
        PartnerCustomerLinkEntity first = added.getFirst();
        String partner = first.partnerCustomerId == null ? null
                : customers.findById(first.partnerCustomerId).map(Customer::company).orElse(null);
        return PartnerCustomerOriginReader.of(first, partner);
    }

    /* --------------------------------------------------------------- helpers */

    /** Control characters out, trimmed; blank = null. */
    static String clean(String typed) {
        if (typed == null) return null;
        String clean = typed.replaceAll("\\p{Cntrl}", " ").strip();
        return clean.isEmpty() ? null : clean;
    }

    private static String collapse(String value) {
        return value == null ? null : value.replaceAll("\\s+", " ");
    }

    /** "Bloemen Jansen B.V." and "bloemen  jansen bv" are the same name: accents, case, punctuation and spaces aside. */
    static String normalized(String company) {
        if (company == null) return "";
        String plain = Normalizer.normalize(company, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        return plain.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", "");
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static String cut(String value, int length) {
        return value == null || value.length() <= length ? value : value.substring(0, length);
    }
}
