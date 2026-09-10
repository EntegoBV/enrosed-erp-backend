package be.enrosed.sales.application;

import be.enrosed.sales.application.port.out.SalesRepositories;
import be.enrosed.sales.domain.Customer;
import be.enrosed.shared.BusinessRuleException;
import be.enrosed.shared.NotFoundException;
import be.enrosed.shared.audit.ActivityChangeDto;
import be.enrosed.shared.audit.ActivityChangeSet;
import be.enrosed.shared.audit.ActivityLogService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import java.time.LocalDate;
import java.util.List;

@ApplicationScoped
public class CustomerService {

    private static final String ACTIVITY_ENTITY = "CUSTOMER";

    private final SalesRepositories.Customers customers;
    private final SalesRepositories.Orders orders;

    @Inject
    Instance<ActivityLogService> activity;
    @Inject
    Instance<be.enrosed.sourcing.application.port.out.SourcingRepositories.PurchaseOrders> partnerPurchases;

    public CustomerService(SalesRepositories.Customers customers, SalesRepositories.Orders orders) {
        this.customers = customers;
        this.orders = orders;
    }

    public List<Customer> list() {
        return customers.findAll();
    }

    public Customer get(long id) {
        return customers.findById(id).orElseThrow(() -> new NotFoundException("Klant", id));
    }

    @Transactional
    public Customer create(Customer customer) {
        requireCompany(customer);
        Customer saved = customers.save(new Customer(null, customer.company(), customer.contact(), customer.email(),
                customer.phone(), customer.vatNumber(), customer.countryCode(),
                customer.language(), customer.address(),
                customer.postalCode(), customer.city(), customer.incoterm(), customer.paymentTerms(),
                customer.notes(), LocalDate.now(), customer.partner(),
                partnerPct(customer.partnerSharePct()), partnerPct(customer.partnerCostPct()),
                customer.fiscalRepresentative(), invoiceNote(customer.invoiceNote())));
        recordActivity(ActivityLogService.ACTION_CREATED, saved, "Klant aangemaakt");
        return saved;
    }

    @Transactional
    public Customer update(long id, Customer changes) {
        Customer current = get(id);
        requireCompany(changes);
        Customer saved = customers.save(new Customer(current.id(), changes.company(), changes.contact(), changes.email(),
                changes.phone(), changes.vatNumber(), changes.countryCode(),
                changes.language(), changes.address(),
                changes.postalCode(), changes.city(), changes.incoterm(), changes.paymentTerms(),
                changes.notes(), current.createdAt(), changes.partner(),
                partnerPct(changes.partnerSharePct()), partnerPct(changes.partnerCostPct()),
                changes.fiscalRepresentative(), invoiceNote(changes.invoiceNote())));
        List<ActivityChangeDto> changesMade = customerChanges(current, saved);
        if (!changesMade.isEmpty()) {
            recordActivity(ActivityLogService.ACTION_UPDATED, saved, "Klant bijgewerkt", changesMade);
        }
        return saved;
    }

    /** A percentage between 0 and 100, or null when it was not filled in. */
    /** The document sentence as typed, trimmed; blank means none, and it stays short enough for a footer. */
    private static String invoiceNote(String note) {
        if (note == null || note.isBlank()) return null;
        String trimmed = note.strip();
        if (trimmed.length() > 500) throw new BusinessRuleException("De vermelding op documenten mag hoogstens 500 tekens lang zijn");
        return trimmed;
    }

    private static java.math.BigDecimal partnerPct(java.math.BigDecimal value) {
        if (value == null) return null;
        if (value.signum() < 0 || value.compareTo(new java.math.BigDecimal("100")) > 0) {
            throw new BusinessRuleException("Een partnerpercentage ligt tussen 0 en 100");
        }
        return value;
    }

    @Transactional
    public void delete(long id) {
        Customer customer = get(id);
        if (orders.countByCustomer(id) > 0)
            throw new BusinessRuleException("Deze klant heeft offertes of facturen en kan niet worden verwijderd");
        if (partnerPurchases != null && partnerPurchases.isResolvable()
                && partnerPurchases.get().referencesPartnerIncludingDeleted(id))
            throw new BusinessRuleException("Deze klant is partner op een inkooporder, mogelijk in de prullenbak. Behoud deze klant zodat documenten en financiële historie intact blijven");
        customers.deleteById(id);
        recordActivity(ActivityLogService.ACTION_DELETED, customer, "Klant verwijderd");
    }

    public long orderCount(long customerId) {
        return orders.findAll().stream().filter(order -> Long.valueOf(customerId).equals(order.customerId())).count();
    }

    private void requireCompany(Customer customer) {
        if (customer.company() == null || customer.company().isBlank()) {
            throw new BusinessRuleException("Bedrijfsnaam is verplicht");
        }
        /* The number itself is required; whether it fits the country is not
           judged here - a mismatch is checked by hand afterwards. */
        if (customer.vatNumber() == null || customer.vatNumber().isBlank()) {
            throw new BusinessRuleException("BTW-nummer is verplicht");
        }
    }

    /** The authenticated actor is resolved inside ActivityLogService, never from the request. */
    private void recordActivity(String action, Customer customer, String summary) {
        if (activity == null || !activity.isResolvable()) return;
        activity.get().record(action, ACTIVITY_ENTITY,
                customer.id() == null ? null : customer.id().toString(), customer.company(), summary);
    }

    private void recordActivity(String action, Customer customer, String summary,
                                List<ActivityChangeDto> changes) {
        if (activity == null || !activity.isResolvable()) return;
        activity.get().record(action, ACTIVITY_ENTITY,
                customer.id() == null ? null : customer.id().toString(), customer.company(), summary, changes);
    }

    private static List<ActivityChangeDto> customerChanges(Customer before, Customer after) {
        return ActivityChangeSet.create()
                .add("company", "Bedrijf", before.company(), after.company())
                .privateValue("contact", "Contactpersoon", before.contact(), after.contact())
                .privateValue("email", "E-mail", before.email(), after.email())
                .privateValue("phone", "Telefoon", before.phone(), after.phone())
                .privateValue("vatNumber", "Btw-nummer", before.vatNumber(), after.vatNumber())
                .add("countryCode", "Land", before.countryCode(), after.countryCode())
                .add("language", "Taal", before.language(), after.language())
                .privateValue("postalCode", "Postcode", before.postalCode(), after.postalCode())
                .add("city", "Plaats", before.city(), after.city())
                .add("incoterm", "Incoterm", before.incoterm(), after.incoterm())
                .add("paymentTerms", "Betaalvoorwaarden", before.paymentTerms(), after.paymentTerms())
                .add("partner", "Partnercontainers", before.partner() ? "ja" : "nee", after.partner() ? "ja" : "nee")
                .add("partnerSharePct", "Winstdeling partner", before.partnerSharePct(), after.partnerSharePct())
                .add("partnerCostPct", "Kost vooraf door partner", before.partnerCostPct(), after.partnerCostPct())
                .add("fiscalRepresentative", "Inklaring via fiscaal vertegenwoordiger",
                        before.fiscalRepresentative() ? "ja" : "nee", after.fiscalRepresentative() ? "ja" : "nee")
                .add("invoiceNote", "Vermelding op documenten", before.invoiceNote(), after.invoiceNote())
                .privateValue("address", "Adres", before.address(), after.address())
                .privateValue("notes", "Notities", before.notes(), after.notes())
                .build();
    }
}
