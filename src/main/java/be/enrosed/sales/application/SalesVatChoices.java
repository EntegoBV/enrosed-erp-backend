package be.enrosed.sales.application;

import be.enrosed.sales.adapter.out.persistence.SalesEntities.SalesOrderEntity;
import be.enrosed.sales.adapter.out.persistence.SalesOrderVatChoiceEntity;
import be.enrosed.sales.domain.Country;
import be.enrosed.sales.domain.Customer;
import be.enrosed.sales.domain.PricedOrder;
import be.enrosed.sales.domain.QuoteStatus;
import be.enrosed.sales.domain.SalesOrder;
import be.enrosed.sales.domain.SalesPurpose;
import be.enrosed.sales.domain.SalesVatChoice;
import be.enrosed.sales.domain.VatTreatment;
import be.enrosed.shared.BusinessRuleException;
import be.enrosed.shared.audit.ActivityChangeDto;
import be.enrosed.shared.audit.ActivityLogService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The VAT route of one sales document (2026-11-26, Emre: "het kan ook zijn dat ik niet fiscaal doe,
 * enkel verleggen volgens België naar Nederland ... kunnen switchen gemakkelijk", "een custom
 * factuur kan ook een dienst zijn die niks met goederen te maken heeft" and "eenmaal factuur
 * verstuurd, zelfs als ik instelling verander, moet source of truth blijven bestaan, enkel kunnen
 * wijzigen met waarschuwing").
 *
 * <b>Choice.</b> The customer record stays the standard (Customer.fiscalRepresentative). A document
 * stores a route only where it differs: the other goods route, or SERVICE on a document without
 * product lines. A stored route the document no longer fits (product lines on a service, a
 * customer without VAT number on the fiscal route) reads as the standard again. A derived document
 * (invoice, advance, slotfactuur, split part, copy, credit note) takes its source's route once, at
 * creation ({@link #copy}).
 *
 * <b>Pin.</b> An invoice or credit note that leaves concept keeps the regime and rate it left with
 * ({@link #pin}): a later change of the customer's tick, VAT number or the country's rate no longer
 * moves it. {@link SalesVatPinJob} gives the documents issued before this existed their pin. A
 * concept credit note prices in the regime of the invoice it corrects. The route changes on a
 * concept only, as every other field does: an issued invoice is reopened first (Heropenen). A
 * reopened invoice stays on the route it was issued on, whatever the customer says by then, and a
 * change of it needs an explicit confirmation ({@link #save}).
 */
@ApplicationScoped
public class SalesVatChoices {

    /**
     * What the staff screens show. {@code route} is what the document is priced with, {@code customerRoute}
     * what the customer's standard gives today, {@code options} what may be picked (one entry: nothing to
     * choose), {@code lockedReason} why it cannot be changed (null while it can), {@code pinned} that the
     * document holds the regime of an issue (now, or before it was reopened), {@code needsConfirm} that
     * a change must be confirmed first because the document was issued on its present route.
     */
    public record View(SalesVatChoice route, SalesVatChoice customerRoute, boolean chosen,
                       List<SalesVatChoice> options, boolean editable, String lockedReason,
                       boolean pinned, boolean needsConfirm) {}

    /** The regime an issued document holds. */
    public record Pin(VatTreatment treatment, BigDecimal ratePct) {}

    /** One stored row: the document's own route, and the regime it was issued with; either may be absent. */
    public record Row(SalesVatChoice choice, VatTreatment treatment, BigDecimal ratePct) {
        /** The rate in the scale live pricing gives it (a country's rate has two decimals, an exempt regime plain zero). */
        Pin pin() {
            if (treatment == null) return null;
            return new Pin(treatment, ratePct == null || ratePct.signum() == 0 ? BigDecimal.ZERO
                    : ratePct.setScale(2, java.math.RoundingMode.HALF_UP));
        }
    }

    @Inject EntityManager entities;
    @Inject SalesOrderService sales;
    @Inject CustomerService customers;
    @Inject CountryService countries;
    @Inject VatCalculator vat;
    @Inject ActivityLogService activity;
    @Inject Instance<SalesVatChoiceCache> cache;
    @Inject Instance<SalesAdvanceBillingService> advanceBilling;
    @Inject Instance<PartnerInvoiceDeclarations> invoiceDeclarations;

    @ConfigProperty(name = "enrosed.company.country", defaultValue = "BE")
    String homeCountry;

    /**
     * The regime this document holds, or null while it is priced live: an invoice or credit note
     * that left concept, and a credit note still in concept on an invoice that holds one.
     */
    public Pin pinned(SalesOrder order) {
        if (order == null || !order.isClaimDocument()) return null;
        if (order.id() != null && order.status() != QuoteStatus.CONCEPT) {
            Row own = find(order.id());
            return own == null ? null : own.pin();
        }
        if (order.isCreditNote() && order.creditedInvoiceId() != null) {
            Row credited = find(order.creditedInvoiceId());
            return credited == null ? null : credited.pin();
        }
        return null;
    }

    /** The route this document is priced with while nothing is pinned; never null. */
    public SalesVatChoice effective(SalesOrder order, Customer customer, Country country) {
        return effective(order, customer, country, choiceOf(order));
    }

    private SalesVatChoice effective(SalesOrder order, Customer customer, Country country, SalesVatChoice stored) {
        if (stored == SalesVatChoice.SERVICE && serviceFits(order)) return SalesVatChoice.SERVICE;
        boolean fiscalPossible = fiscalPossible(customer, country);
        if (stored == SalesVatChoice.FISCAL_REPRESENTATIVE) return fiscalPossible ? stored : SalesVatChoice.BELGIUM;
        if (stored == SalesVatChoice.BELGIUM) return stored;
        return customerRoute(customer, fiscalPossible);
    }

    /** Whether this document is a service: no delivery, no freight, no country minimum. */
    public boolean isService(SalesOrder order) {
        Pin pin = pinned(order);
        if (pin != null) return pin.treatment().isService();
        return order != null && serviceFits(order) && choiceOf(order) == SalesVatChoice.SERVICE;
    }

    public View get(long id) {
        SalesOrder order = sales.get(id);
        return view(order, customerOf(order), countries.find(order.countryCode()));
    }

    /**
     * @param confirmed staff answered the warning: required to change an invoice that already left concept
     */
    @Transactional
    public View save(long id, SalesVatChoice requested, boolean confirmed) {
        // Same document lock as issue and send; refreshes an earlier cached CONCEPT.
        sales.lockDocumentForMutation(id);
        SalesOrder order = sales.get(id);
        Customer customer = customerOf(order);
        Country country = countries.find(order.countryCode());
        View before = view(order, customer, country);
        if (!before.editable()) throw new BusinessRuleException(before.lockedReason());
        SalesVatChoice wanted = requested == null ? before.customerRoute() : requested;
        if (!before.options().contains(wanted))
            throw new BusinessRuleException(wanted == SalesVatChoice.SERVICE
                    ? "Een dienst kan alleen op een document zonder productregels"
                    : "Deze btw-regeling past niet bij deze klant en dit land");
        if (wanted == before.route()) {
            forgetUnfitChoice(id, wanted, before);
            return before;
        }
        if (before.needsConfirm() && !confirmed)
            throw new BusinessRuleException("Deze factuur is eerder uitgereikt en houdt haar btw-regeling vast."
                    + " Bevestig dat je ze toch wilt wijzigen");

        var stored = entities.find(SalesOrderVatChoiceEntity.class, id);
        boolean fresh = stored == null;
        if (fresh) stored = row(id);
        stored.choice = wanted == before.customerRoute() ? null : wanted.name();
        stored.updatedAt = Instant.now();
        /* The route of an earlier issue ends with this decision; the next issue holds the new one. */
        stored.treatment = null; stored.ratePct = null; stored.pinnedAt = null;
        if (stored.choice == null && stored.treatment == null) { if (!fresh) entities.remove(stored); }
        else if (fresh) entities.persist(stored);
        entities.flush();
        forget();
        /* The document must still price on its new route: a refusal here undoes the change instead of
           leaving a document no list can show. */
        sales.price(order);
        /* A partner invoice wording that names the fiscal representative cannot stay on another route:
           its own check says so, and the refusal undoes this change. */
        if (invoiceDeclarations != null && invoiceDeclarations.isResolvable())
            invoiceDeclarations.get().validateForIssue(order, customer);
        activity.record(ActivityLogService.ACTION_UPDATED, "SALES_ORDER", Long.toString(id), order.number(),
                before.needsConfirm() ? "Btw-regeling gewijzigd na een eerdere uitgifte" : "Btw-regeling gewijzigd", List.of(
                        new ActivityChangeDto("vatChoice", "Btw-regeling", label(before.route()), label(wanted))));
        return view(order, customer, country);
    }

    /**
     * A stored choice the document no longer fits (Dienst on a document that got product lines, the
     * fiscal route for a customer whose VAT number went) reads as the standard; picking that standard
     * removes it, so it cannot come back to life later or travel to a derived document.
     */
    private void forgetUnfitChoice(long id, SalesVatChoice wanted, View before) {
        var stored = entities.find(SalesOrderVatChoiceEntity.class, id);
        SalesVatChoice kept = stored == null ? null : SalesVatChoice.stored(stored.choice);
        if (kept == null || kept == wanted || wanted != before.customerRoute()) return;
        stored.choice = null;
        stored.updatedAt = Instant.now();
        if (stored.treatment == null) entities.remove(stored);
        entities.flush();
        forget();
    }

    /**
     * A derived document takes its source's route once, at creation, inside the creating transaction:
     * the route the source is priced with, never a stored one it no longer fits (a Dienst left on a
     * quote that got product lines would turn its line-less advance invoice into a service).
     */
    @Transactional
    public void copy(Long sourceId, Long targetId) {
        if (sourceId == null || targetId == null) return;
        var source = entities.find(SalesOrderVatChoiceEntity.class, sourceId);
        if (source == null || SalesVatChoice.stored(source.choice) == null
                || entities.find(SalesOrderVatChoiceEntity.class, targetId) != null) return;
        if (SalesVatChoice.stored(source.choice) == SalesVatChoice.SERVICE && !serviceFits(sales.get(sourceId))) return;
        var copy = row(targetId);
        copy.choice = source.choice;
        copy.updatedAt = Instant.now();
        entities.persist(copy);
        entities.flush();
        forget();
    }

    /**
     * An invoice or credit note left concept: from here it holds the regime and rate it was priced with.
     * Called right after the status change, and by SalesVatPinJob for what was issued before.
     */
    @Transactional
    public void pin(SalesOrder issued, PricedOrder priced) {
        if (issued == null || issued.id() == null || !issued.isClaimDocument() || issued.status() == QuoteStatus.CONCEPT
                || priced == null || priced.totals() == null || priced.totals().vatTreatment() == null) return;
        var stored = entities.find(SalesOrderVatChoiceEntity.class, issued.id());
        boolean fresh = stored == null;
        if (fresh) stored = row(issued.id());
        stored.treatment = priced.totals().vatTreatment().name();
        stored.ratePct = priced.totals().vatRatePct() == null ? BigDecimal.ZERO : priced.totals().vatRatePct();
        stored.pinnedAt = Instant.now();
        stored.updatedAt = stored.pinnedAt;
        if (fresh) entities.persist(stored);
        entities.flush();
        forget();
    }

    /** Issued invoices and credit notes that hold no regime yet, oldest first. */
    @SuppressWarnings("unchecked")
    List<Long> issuedWithoutPin() {
        List<Object> ids = entities.createNativeQuery("select o.id from sales_order o"
                + " where o.doctype in ('FACTUUR', 'CREDITNOTA') and o.status <> 'CONCEPT' and o.deleted_at is null"
                + " and not exists (select 1 from sales_order_vat_choice c where c.sales_order_id = o.id and c.treatment is not null)"
                + " order by o.id").getResultList();
        return ids.stream().map(id -> ((Number) id).longValue()).toList();
    }

    private SalesOrderVatChoiceEntity row(long id) {
        var row = new SalesOrderVatChoiceEntity();
        row.salesOrderId = id;
        row.order = entities.getReference(SalesOrderEntity.class, id);
        return row;
    }

    private View view(SalesOrder order, Customer customer, Country country) {
        Pin pin = pinned(order);
        Row own = order.id() == null ? null : find(order.id());
        boolean held = order.isClaimDocument() && own != null && own.treatment() != null;
        boolean fiscalPossible = fiscalPossible(customer, country);
        SalesVatChoice customerRoute = customerRoute(customer, fiscalPossible);
        SalesVatChoice route = pin != null ? routeOf(pin.treatment()) : effective(order, customer, country, choiceOf(order));
        List<SalesVatChoice> options = new ArrayList<>();
        /* The goods routes are a choice only for a customer on the fiscal representative (or a document put there). */
        if (fiscalPossible && (customer != null && customer.fiscalRepresentative() || route == SalesVatChoice.FISCAL_REPRESENTATIVE)) {
            options.add(SalesVatChoice.FISCAL_REPRESENTATIVE);
            options.add(SalesVatChoice.BELGIUM);
        } else options.add(SalesVatChoice.BELGIUM);
        if (serviceFits(order) && country != null && !order.isCreditNote() && !derived(order)) options.add(SalesVatChoice.SERVICE);
        if (!options.contains(route)) options.add(route);
        String locked = lockedReason(order);
        return new View(route, customerRoute, route != customerRoute, List.copyOf(options), locked == null, locked,
                held, locked == null && held);
    }

    private String lockedReason(SalesOrder order) {
        if (order.archivedAt() != null) return "Dit document staat in het archief";
        if (order.isCreditNote()) return "Een creditnota volgt de btw-regeling van haar factuur";
        if (derived(order)) return "Een voorschot- of slotfactuur volgt de btw-regeling van haar offerte";
        if (sales.hasSplitOrder(order)) return "Een gesplitste levering houdt de btw-regeling van haar verdeling";
        /* The advances were made in this regime and the slotfactuur is held to it, also for an advance credited
           in full (SalesAdvanceBillingService.prepareFinal compares before it skips one). */
        if (hasLiveAdvances(order))
            return "De btw-regeling ligt vast zolang er een voorschotfactuur op deze offerte bestaat; een conceptvoorschot kun je eerst verwijderen";
        if (order.status() != QuoteStatus.CONCEPT)
            return order.isClaimDocument() ? "Vastgelegd bij uitgifte. Wijzigen kan alleen nadat de factuur heropend is."
                    : "De btw-regeling kan alleen op een concept worden gewijzigd";
        return null;
    }

    /** An advance invoice or slotfactuur of a regular quote: its VAT is the quote's. */
    private boolean derived(SalesOrder order) {
        if (order.id() == null || !order.isClaimDocument() || order.isCreditNote()) return false;
        try {
            return advanceBilling != null && advanceBilling.isResolvable() && advanceBilling.get().presentation(order) != null;
        } catch (RuntimeException unknown) {
            return false;
        }
    }

    private boolean hasLiveAdvances(SalesOrder order) {
        try {
            return advanceBilling != null && advanceBilling.isResolvable() && advanceBilling.get().hasLiveAdvances(order);
        } catch (RuntimeException unknown) {
            return false;
        }
    }

    /** A service has no product lines and is no container or partner document. */
    private static boolean serviceFits(SalesOrder order) {
        return order != null && (order.lines() == null || order.lines().isEmpty())
                && order.purpose() == SalesPurpose.STANDARD && !order.isPartnerDeal() && !order.partnerSettlement();
    }

    private boolean fiscalPossible(Customer customer, Country country) {
        return country != null && country.euMember() && country.code() != null
                && !country.code().equalsIgnoreCase(homeCountry) && vat.hasVatNumber(customer);
    }

    private static SalesVatChoice customerRoute(Customer customer, boolean fiscalPossible) {
        return customer != null && customer.fiscalRepresentative() && fiscalPossible
                ? SalesVatChoice.FISCAL_REPRESENTATIVE : SalesVatChoice.BELGIUM;
    }

    private static SalesVatChoice routeOf(VatTreatment treatment) {
        if (treatment == VatTreatment.VERLEGD_FISCAAL_VERTEGENWOORDIGER) return SalesVatChoice.FISCAL_REPRESENTATIVE;
        return treatment.isService() ? SalesVatChoice.SERVICE : SalesVatChoice.BELGIUM;
    }

    /** The Belgian rate, for a service taxed in Belgium for a customer elsewhere in the EU. */
    BigDecimal homeRate() {
        Country home = countries.find(homeCountry);
        return home == null ? null : home.vatRatePct();
    }

    private Customer customerOf(SalesOrder order) {
        return order.customerId() == null ? null : customers.get(order.customerId());
    }

    /**
     * The document's own route: the stored choice, else the route an invoice or credit note was issued
     * on. So a reopened invoice stays where it was issued, also after the customer's standard changed.
     */
    private SalesVatChoice choiceOf(SalesOrder order) {
        if (order == null || order.id() == null) return null;
        Row row = find(order.id());
        if (row == null) return null;
        if (row.choice() != null) return row.choice();
        if (row.treatment() == null || !order.isClaimDocument()) return null;
        /* Domestic, export and "EU without VAT number" follow from country and customer, not from a route:
           read as BELGIUM they would turn a fiscal customer's reopened invoice into an intra-community one. */
        VatTreatment held = row.treatment();
        return held == VatTreatment.VERLEGD_FISCAAL_VERTEGENWOORDIGER || held == VatTreatment.INTRACOMMUNAUTAIR
                || held.isService() ? routeOf(held) : null;
    }

    private Row find(long id) {
        Map<Long, Row> all = held();
        if (all != null) return all.get(id);
        var row = entities.find(SalesOrderVatChoiceEntity.class, id);
        return row == null ? null : new Row(SalesVatChoice.stored(row.choice), treatment(row.treatment), row.ratePct);
    }

    /** Inside a request the whole table is read once; outside one each document reads its own row. */
    private Map<Long, Row> held() {
        try {
            if (cache == null || !cache.isResolvable()) return null;
            SalesVatChoiceCache held = cache.get();
            if (held.all() == null) {
                Map<Long, Row> all = new HashMap<>();
                List<?> rows = entities.createNativeQuery(
                        "select sales_order_id, choice, treatment, rate_pct from sales_order_vat_choice").getResultList();
                for (Object row : rows) {
                    Object[] cells = (Object[]) row;
                    all.put(((Number) cells[0]).longValue(), new Row(SalesVatChoice.stored((String) cells[1]),
                            treatment((String) cells[2]), cells[3] == null ? null : new BigDecimal(cells[3].toString())));
                }
                held.fill(all);
            }
            return held.all();
        } catch (jakarta.enterprise.context.ContextNotActiveException outsideRequest) {
            return null;
        }
    }

    private void forget() {
        try {
            if (cache != null && cache.isResolvable()) cache.get().fill(null);
        } catch (jakarta.enterprise.context.ContextNotActiveException outsideRequest) {
            // Nothing was kept.
        }
    }

    /** An unknown stored name reads as "not recorded", never as a failure. */
    private static VatTreatment treatment(String value) {
        if (value == null || value.isBlank()) return null;
        try { return VatTreatment.valueOf(value.strip()); } catch (IllegalArgumentException unknown) { return null; }
    }

    private static String label(SalesVatChoice route) {
        if (route == null) return null;
        return switch (route) {
            case FISCAL_REPRESENTATIVE -> "Via fiscaal vertegenwoordiger (NL)";
            case BELGIUM -> "Belgische regels";
            case SERVICE -> "Dienst";
        };
    }
}
