package be.enrosed.sales.application;

import be.enrosed.sales.application.port.out.SalesRepositories;
import be.enrosed.sales.domain.Customer;
import be.enrosed.shared.BusinessRuleException;
import be.enrosed.shared.NotFoundException;
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
                customer.notes(), LocalDate.now()));
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
                changes.notes(), current.createdAt()));
        recordActivity(ActivityLogService.ACTION_UPDATED, saved, "Klant bijgewerkt");
        return saved;
    }

    @Transactional
    public void delete(long id) {
        Customer customer = get(id);
        customers.deleteById(id);
        recordActivity(ActivityLogService.ACTION_DELETED, customer, "Klant verwijderd");
    }

    public long orderCount(long customerId) {
        return orders.countByCustomer(customerId);
    }

    private void requireCompany(Customer customer) {
        if (customer.company() == null || customer.company().isBlank()) {
            throw new BusinessRuleException("Bedrijfsnaam is verplicht");
        }
    }

    /** The authenticated actor is resolved inside ActivityLogService, never from the request. */
    private void recordActivity(String action, Customer customer, String summary) {
        if (activity == null || !activity.isResolvable()) return;
        activity.get().record(action, ACTIVITY_ENTITY,
                customer.id() == null ? null : customer.id().toString(), customer.company(), summary);
    }
}
