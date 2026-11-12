package be.enrosed.sales.application;

import be.enrosed.sales.application.port.out.SalesRepositories;
import be.enrosed.sales.domain.Customer;
import be.enrosed.sales.domain.CustomerCrmStatus;
import be.enrosed.sales.domain.CustomerTouchpoint;
import be.enrosed.shared.BusinessRuleException;
import be.enrosed.shared.NotFoundException;
import be.enrosed.shared.audit.ActivityChangeDto;
import be.enrosed.shared.audit.ActivityChangeSet;
import be.enrosed.shared.audit.ActivityLogService;
import be.enrosed.shared.security.ActorRef;
import be.enrosed.shared.security.AdminIdentityProvider;
import be.enrosed.shared.security.CurrentActor;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@ApplicationScoped
public class CustomerService {

    private static final String ACTIVITY_ENTITY = "CUSTOMER";

    private final SalesRepositories.Customers customers;
    private final SalesRepositories.Orders orders;

    @Inject
    Instance<ActivityLogService> activity;
    /** Who ticks a contact moment; the system when there is no request (the demo loader). */
    @Inject
    Instance<CurrentActor> actor;
    /** Turns the stored login behind a contact moment into the person's name, as the klantprijzen history does. */
    @Inject
    AdminIdentityProvider names;
    @Inject
    Instance<be.enrosed.sourcing.application.port.out.SourcingRepositories.PurchaseOrders> partnerPurchases;
    /** Features that keep rows of their own for a customer (website logins) clear them before the customer goes. */
    @Inject
    Instance<CustomerDeletionListener> deletionListeners;
    /** Features that hang off the partner flag (the partner portal's sessions) hear when it goes off. */
    @Inject
    Instance<CustomerPartnerListener> partnerListeners;
    /** Customers a partner added in the partner portal (be.enrosed.partnerportal); absent in pure unit tests. */
    @Inject
    Instance<CustomerPartnerOrigins> partnerOrigins;

    public CustomerService(SalesRepositories.Customers customers, SalesRepositories.Orders orders) {
        this.customers = customers;
        this.orders = orders;
    }

    public List<Customer> list() {
        return customers.findAll().stream().map(this::named).toList();
    }

    public Customer get(long id) {
        return named(customers.findById(id).orElseThrow(() -> new NotFoundException("Klant", id)));
    }

    /** The list as /api/customers answers it: each customer with its partner origin when a partner added it. */
    public List<CustomerView> listViews() {
        List<Customer> all = list();
        Map<Long, CustomerPartnerOrigins.Origin> origins = origins(null);
        return all.stream().map(customer -> new CustomerView(customer,
                customer.id() == null ? null : origins.get(customer.id()))).toList();
    }

    /** One customer as /api/customers answers it (also the answer of a create and an update). */
    public CustomerView view(Customer customer) {
        if (customer == null || customer.id() == null) return new CustomerView(customer, null);
        return new CustomerView(customer, origins(List.of(customer.id())).get(customer.id()));
    }

    private Map<Long, CustomerPartnerOrigins.Origin> origins(java.util.Collection<Long> ids) {
        if (partnerOrigins == null || !partnerOrigins.isResolvable()) return Map.of();
        try {
            return partnerOrigins.get().origins(ids);
        } catch (RuntimeException unavailable) {
            /* The partner portal's marker is never a reason to lose the customer list. */
            return Map.of();
        }
    }

    /**
     * A customer a partner adds in the partner portal (the buyer of a direct sale): only what the partner
     * typed, without the VAT rule of the staff form (staff complete the record later), status NIEUW, language
     * NL. The caller validated the fields; summary is the activity line ("Klant aangemaakt via Partnerportaal
     * (Frans)"), logged under whoever is signed in.
     */
    @Transactional
    public Customer createMinimal(String company, String countryCode, String city, String email, String phone,
                                  String notes, String summary) {
        if (company == null || company.isBlank()) throw new BusinessRuleException("Bedrijfsnaam is verplicht");
        Customer saved = customers.save(new Customer(null, company.strip(), null, email, phone, null, countryCode,
                be.enrosed.shared.Language.NL, null, null, city, null, null, notes, LocalDate.now(), false,
                null, null, false, null, CustomerCrmStatus.NIEUW, List.of()));
        recordActivity(ActivityLogService.ACTION_CREATED, saved, summary == null ? "Klant aangemaakt" : summary);
        return named(saved);
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
                customer.fiscalRepresentative(), invoiceNote(customer.invoiceNote()),
                crmStatus(customer.crmStatus(), CustomerCrmStatus.NIEUW),
                touchpoints(customer.touchpoints(), List.of())));
        recordActivity(ActivityLogService.ACTION_CREATED, saved, "Klant aangemaakt");
        return named(saved);
    }

    @Transactional
    public Customer update(long id, Customer changes) {
        Customer current = get(id);
        /* A customer a partner added and staff have not reviewed yet may be saved without a VAT number while
           it has none: staff fix its city or status first and add the number when they know it. */
        boolean vatOptional = blank(current.vatNumber()) && awaitsPartnerReview(id);
        requireCompany(changes, !vatOptional);
        Customer saved = customers.save(new Customer(current.id(), changes.company(), changes.contact(), changes.email(),
                changes.phone(), changes.vatNumber(), changes.countryCode(),
                changes.language(), changes.address(),
                changes.postalCode(), changes.city(), changes.incoterm(), changes.paymentTerms(),
                changes.notes(), current.createdAt(), changes.partner(),
                partnerPct(changes.partnerSharePct()), partnerPct(changes.partnerCostPct()),
                changes.fiscalRepresentative(), invoiceNote(changes.invoiceNote()),
                crmStatus(changes.crmStatus(), current.crmStatus()),
                changes.touchpoints() == null ? null : touchpoints(changes.touchpoints(), current.touchpoints())));
        List<ActivityChangeDto> changesMade = customerChanges(current, saved);
        if (!changesMade.isEmpty()) {
            recordActivity(ActivityLogService.ACTION_UPDATED, saved, "Klant bijgewerkt", changesMade);
        }
        if (current.partner() && !saved.partner() && partnerListeners != null) {
            for (CustomerPartnerListener listener : partnerListeners) listener.partnerEnded(id);
        }
        return named(saved);
    }

    /**
     * Completes the address of a record: each of street, postal code and
     * city is written only where the record has none. A field that holds a
     * value keeps it, and country, name, VAT number and contact data are not
     * looked at, so a record that would not pass today's form still saves.
     * Logged like any other edit of the record, under the given summary;
     * nothing to fill means nothing saved and nothing logged.
     */
    @Transactional
    public Customer fillMissingAddress(long id, String address, String postalCode, String city, String summary) {
        Customer current = get(id);
        Customer filled = new Customer(current.id(), current.company(), current.contact(), current.email(),
                current.phone(), current.vatNumber(), current.countryCode(),
                current.language(), whenEmpty(current.address(), address),
                whenEmpty(current.postalCode(), postalCode), whenEmpty(current.city(), city), current.incoterm(),
                current.paymentTerms(), current.notes(), current.createdAt(), current.partner(),
                current.partnerSharePct(), current.partnerCostPct(), current.fiscalRepresentative(),
                current.invoiceNote(), current.crmStatus(), current.touchpoints());
        List<ActivityChangeDto> changesMade = customerChanges(current, filled);
        if (changesMade.isEmpty()) return current;
        Customer saved = customers.save(filled);
        recordActivity(ActivityLogService.ACTION_UPDATED, saved, summary, changesMade);
        return saved;
    }

    private static String whenEmpty(String current, String value) {
        if (current != null && !current.isBlank()) return current;
        return value == null || value.isBlank() ? current : value.strip();
    }

    /** A percentage between 0 and 100, or null when it was not filled in. */
    /** The document sentence as typed, trimmed; blank means none, and it stays short enough for a footer. */
    private static String invoiceNote(String note) {
        if (note == null || note.isBlank()) return null;
        String trimmed = note.strip();
        if (trimmed.length() > 500) throw new BusinessRuleException("De vermelding op documenten mag hoogstens 500 tekens lang zijn");
        return trimmed;
    }

    /** The status as given when it is one we know; null means "not said", which falls back to the given default. */
    private static String crmStatus(String given, String fallback) {
        if (given == null || given.isBlank()) return fallback;
        String status = given.strip();
        if (!CustomerCrmStatus.isKnown(status)) throw new BusinessRuleException("Onbekende klantstatus: " + given);
        return status;
    }

    /**
     * The contact moments a client ticked, one per kind: a kind the customer already has keeps
     * the moment and actor it was first ticked with, a new kind is ticked now by whoever is
     * logged in. The client only ever sends kinds; at and by are never taken from it.
     */
    private List<CustomerTouchpoint> touchpoints(List<CustomerTouchpoint> given, List<CustomerTouchpoint> current) {
        Map<String, CustomerTouchpoint> known = new LinkedHashMap<>();
        if (current != null) for (CustomerTouchpoint touchpoint : current) known.put(touchpoint.kind(), touchpoint);
        Map<String, CustomerTouchpoint> result = new LinkedHashMap<>();
        /* Not said (an older client, or a create without the field): what the customer has stays. */
        if (given == null) return current == null ? List.of() : current;
        Instant now = Instant.now();
        String who = actorName();
        for (CustomerTouchpoint touchpoint : given) {
            String kind = touchpoint == null || touchpoint.kind() == null ? null : touchpoint.kind().strip();
            if (!CustomerTouchpoint.isValidKind(kind)) {
                throw new BusinessRuleException("Onbekend contactmoment: " + (kind == null ? "" : kind));
            }
            if (result.containsKey(kind)) continue;
            CustomerTouchpoint existing = known.get(kind);
            result.put(kind, existing != null ? existing : new CustomerTouchpoint(kind, now, who));
        }
        return new ArrayList<>(result.values());
    }

    /** The canonical username, as every audit column stores it; {@link #named} turns it into a name on the way out. */
    private String actorName() {
        if (actor == null || !actor.isResolvable()) return ActorRef.SYSTEM.username();
        return actor.get().name();
    }

    /**
     * The customer as a client shows it: a contact moment names the person ("Emre", "Systeem"),
     * never the stored login, which is the only name the Opvolging block would otherwise print.
     */
    private Customer named(Customer customer) {
        if (customer == null || customer.touchpoints() == null || customer.touchpoints().isEmpty()) return customer;
        return customer.withTouchpoints(customer.touchpoints().stream()
                .map(touchpoint -> new CustomerTouchpoint(touchpoint.kind(), touchpoint.at(),
                        names == null ? touchpoint.by() : names.displayNameFor(touchpoint.by())))
                .toList());
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
        if (deletionListeners != null) {
            for (CustomerDeletionListener listener : deletionListeners) listener.beforeDelete(id);
        }
        customers.deleteById(id);
        recordActivity(ActivityLogService.ACTION_DELETED, customer, "Klant verwijderd");
    }

    public long orderCount(long customerId) {
        return orders.findAll().stream().filter(order -> Long.valueOf(customerId).equals(order.customerId())).count();
    }

    private void requireCompany(Customer customer) {
        requireCompany(customer, true);
    }

    private void requireCompany(Customer customer, boolean vatRequired) {
        if (customer.company() == null || customer.company().isBlank()) {
            throw new BusinessRuleException("Bedrijfsnaam is verplicht");
        }
        /* The number itself is required; whether it fits the country is not
           judged here - a mismatch is checked by hand afterwards. */
        if (vatRequired && (customer.vatNumber() == null || customer.vatNumber().isBlank())) {
            throw new BusinessRuleException("BTW-nummer is verplicht");
        }
    }

    private boolean awaitsPartnerReview(long id) {
        if (partnerOrigins == null || !partnerOrigins.isResolvable()) return false;
        try {
            return partnerOrigins.get().awaitsReview(id);
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
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
                .add("crmStatus", "Status", CustomerCrmStatus.label(before.crmStatus()),
                        CustomerCrmStatus.label(after.crmStatus()))
                .add("touchpoints", "Contactmomenten", touchpointLabels(before.touchpoints()),
                        touchpointLabels(after.touchpoints()))
                .privateValue("address", "Adres", before.address(), after.address())
                .privateValue("notes", "Notities", before.notes(), after.notes())
                .build();
    }

    /**
     * The ticked kinds as the Logboek shows them, in the order the screen lists them (unknown
     * kinds last, by name) so a save that changed nothing but the row order logs nothing. Null
     * for none, and also for "not loaded", so a before without rows never reads as a change.
     */
    private static String touchpointLabels(List<CustomerTouchpoint> touchpoints) {
        if (touchpoints == null || touchpoints.isEmpty()) return null;
        return touchpoints.stream()
                .map(CustomerTouchpoint::kind)
                .distinct()
                .sorted(Comparator.comparingInt((String kind) -> {
                    int index = CustomerTouchpoint.KNOWN_KINDS.indexOf(kind);
                    return index < 0 ? Integer.MAX_VALUE : index;
                }).thenComparing(Comparator.<String>naturalOrder()))
                .map(CustomerTouchpoint::label)
                .collect(java.util.stream.Collectors.joining(", "));
    }
}
