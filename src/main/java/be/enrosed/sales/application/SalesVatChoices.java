package be.enrosed.sales.application;

import be.enrosed.sales.adapter.out.persistence.SalesEntities.SalesOrderEntity;
import be.enrosed.sales.adapter.out.persistence.SalesOrderVatChoiceEntity;
import be.enrosed.sales.domain.Country;
import be.enrosed.sales.domain.Customer;
import be.enrosed.sales.domain.QuoteStatus;
import be.enrosed.sales.domain.SalesOrder;
import be.enrosed.sales.domain.SalesPurpose;
import be.enrosed.sales.domain.SalesVatChoice;
import be.enrosed.shared.BusinessRuleException;
import be.enrosed.shared.audit.ActivityChangeDto;
import be.enrosed.shared.audit.ActivityLogService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The VAT route of one sales document (2026-11-26, Emre: "het kan ook zijn dat ik niet fiscaal doe,
 * enkel verleggen volgens België naar Nederland ... kunnen switchen gemakkelijk" and "een custom
 * factuur kan ook een dienst zijn die niks met goederen te maken heeft").
 *
 * The customer record stays the standard (Customer.fiscalRepresentative). A document stores a route
 * only where it differs: the other goods route, or SERVICE on a document without product lines.
 * Pricing asks {@link #effective}; a stored route the document no longer fits (product lines on a
 * service, a customer without VAT number on the fiscal route) simply reads as the standard again.
 * A derived document (invoice, advance, slotfactuur, split part, copy, credit note) takes its
 * source's route once, at creation ({@link #copy}).
 */
@ApplicationScoped
public class SalesVatChoices {

    /**
     * What the staff screens show. {@code route} is what the document is priced with, {@code customerRoute}
     * what it would be without a choice, {@code options} what may be picked (one entry: nothing to
     * choose), {@code lockedReason} why it cannot be changed right now (null while it can).
     */
    public record View(SalesVatChoice route, SalesVatChoice customerRoute, boolean chosen,
                       List<SalesVatChoice> options, boolean editable, String lockedReason) {}

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

    /** The route this document is priced with; never null. */
    public SalesVatChoice effective(SalesOrder order, Customer customer, Country country) {
        return effective(order, customer, country, order == null || order.id() == null ? null : find(order.id()));
    }

    private SalesVatChoice effective(SalesOrder order, Customer customer, Country country, SalesVatChoice stored) {
        if (stored == SalesVatChoice.SERVICE && serviceFits(order)) return SalesVatChoice.SERVICE;
        boolean fiscalPossible = fiscalPossible(customer, country);
        if (stored == SalesVatChoice.FISCAL_REPRESENTATIVE) return fiscalPossible ? stored : SalesVatChoice.BELGIUM;
        if (stored == SalesVatChoice.BELGIUM) return stored;
        return customerRoute(customer, fiscalPossible);
    }

    /** Whether this document is a service by choice: no delivery, no freight, no country minimum. */
    public boolean isService(SalesOrder order) {
        return order != null && order.id() != null && serviceFits(order) && find(order.id()) == SalesVatChoice.SERVICE;
    }

    /**
     * Whether the goods of this document were cleared by our fiscal representative, which the quote and
     * the advance invoice say in a line of their own: the customer's standard, unless the document
     * was put on another route (or back on this one).
     */
    public boolean clearedByRepresentative(SalesOrder order, Customer customer) {
        SalesVatChoice stored = order == null || order.id() == null ? null : find(order.id());
        if (stored == SalesVatChoice.SERVICE && !serviceFits(order)) stored = null;
        return stored == null ? customer != null && customer.fiscalRepresentative()
                : stored == SalesVatChoice.FISCAL_REPRESENTATIVE;
    }

    public View get(long id) {
        SalesOrder order = sales.get(id);
        return view(order, customerOf(order), countries.find(order.countryCode()));
    }

    @Transactional
    public View save(long id, SalesVatChoice requested) {
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
        if (wanted == before.route()) return before;

        var stored = entities.find(SalesOrderVatChoiceEntity.class, id);
        if (wanted == before.customerRoute()) {
            if (stored != null) entities.remove(stored);
        } else {
            boolean fresh = stored == null;
            if (fresh) {
                stored = new SalesOrderVatChoiceEntity();
                stored.salesOrderId = id;
                stored.order = entities.getReference(SalesOrderEntity.class, id);
            }
            stored.choice = wanted.name();
            stored.updatedAt = Instant.now();
            if (fresh) entities.persist(stored);
        }
        entities.flush();
        forget();
        /* A partner invoice wording that names the fiscal representative cannot stay on another route:
           its own check says so, and the refusal undoes this change. */
        if (invoiceDeclarations != null && invoiceDeclarations.isResolvable())
            invoiceDeclarations.get().validateForIssue(order, customer);
        activity.record(ActivityLogService.ACTION_UPDATED, "SALES_ORDER", Long.toString(id), order.number(),
                "Btw-regeling gewijzigd", List.of(
                        new ActivityChangeDto("vatChoice", "Btw-regeling", label(before.route()), label(wanted))));
        return view(order, customer, country);
    }

    /** A derived document takes its source's route once, at creation, inside the creating transaction. */
    @Transactional
    public void copy(Long sourceId, Long targetId) {
        if (sourceId == null || targetId == null) return;
        var stored = entities.find(SalesOrderVatChoiceEntity.class, sourceId);
        if (stored == null || SalesVatChoice.stored(stored.choice) == null
                || entities.find(SalesOrderVatChoiceEntity.class, targetId) != null) return;
        var copy = new SalesOrderVatChoiceEntity();
        copy.salesOrderId = targetId;
        copy.order = entities.getReference(SalesOrderEntity.class, targetId);
        copy.choice = stored.choice;
        copy.updatedAt = Instant.now();
        entities.persist(copy);
        entities.flush();
        forget();
    }

    private View view(SalesOrder order, Customer customer, Country country) {
        SalesVatChoice stored = order.id() == null ? null : find(order.id());
        boolean fiscalPossible = fiscalPossible(customer, country);
        SalesVatChoice customerRoute = customerRoute(customer, fiscalPossible);
        SalesVatChoice route = effective(order, customer, country, stored);
        List<SalesVatChoice> options = new ArrayList<>();
        /* The goods routes are a choice only for a customer on the fiscal representative (or a document put there). */
        if (fiscalPossible && (customer != null && customer.fiscalRepresentative() || route == SalesVatChoice.FISCAL_REPRESENTATIVE)) {
            options.add(SalesVatChoice.FISCAL_REPRESENTATIVE);
            options.add(SalesVatChoice.BELGIUM);
        } else options.add(SalesVatChoice.BELGIUM);
        if (serviceFits(order) && country != null && !derived(order)) options.add(SalesVatChoice.SERVICE);
        if (!options.contains(route)) options.add(route);
        String locked = lockedReason(order);
        return new View(route, customerRoute, route != customerRoute, List.copyOf(options), locked == null, locked);
    }

    private String lockedReason(SalesOrder order) {
        if (order.archivedAt() != null) return "Dit document staat in het archief";
        if (order.status() != QuoteStatus.CONCEPT) return "De btw-regeling kan alleen op een concept worden gewijzigd";
        if (order.isCreditNote()) return "Een creditnota volgt de btw-regeling van haar factuur";
        if (derived(order)) return "Een voorschot- of slotfactuur volgt de btw-regeling van haar offerte";
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

    private Customer customerOf(SalesOrder order) {
        return order.customerId() == null ? null : customers.get(order.customerId());
    }

    private SalesVatChoice find(long id) {
        Map<Long, SalesVatChoice> all = held();
        if (all != null) return all.get(id);
        var row = entities.find(SalesOrderVatChoiceEntity.class, id);
        return row == null ? null : SalesVatChoice.stored(row.choice);
    }

    /** Inside a request the whole (small) table is read once; outside one each document reads its own row. */
    private Map<Long, SalesVatChoice> held() {
        try {
            if (cache == null || !cache.isResolvable()) return null;
            SalesVatChoiceCache held = cache.get();
            if (held.all() == null) {
                Map<Long, SalesVatChoice> all = new HashMap<>();
                List<?> rows = entities.createNativeQuery("select sales_order_id, choice from sales_order_vat_choice").getResultList();
                for (Object row : rows) {
                    Object[] cells = (Object[]) row;
                    SalesVatChoice choice = SalesVatChoice.stored((String) cells[1]);
                    if (choice != null) all.put(((Number) cells[0]).longValue(), choice);
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

    private static String label(SalesVatChoice route) {
        if (route == null) return null;
        return switch (route) {
            case FISCAL_REPRESENTATIVE -> "Via fiscaal vertegenwoordiger (NL)";
            case BELGIUM -> "Belgische regels";
            case SERVICE -> "Dienst";
        };
    }
}
