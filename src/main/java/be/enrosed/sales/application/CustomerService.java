package be.enrosed.sales.application;

import be.enrosed.sales.application.port.out.SalesRepositories;
import be.enrosed.sales.domain.Customer;
import be.enrosed.shared.BusinessRuleException;
import be.enrosed.shared.NotFoundException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import java.time.LocalDate;
import java.util.List;

@ApplicationScoped
public class CustomerService {

    private final SalesRepositories.Customers customers;
    private final SalesRepositories.Orders orders;

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
        return customers.save(new Customer(null, customer.company(), customer.contact(), customer.email(),
                customer.phone(), customer.vatNumber(), customer.countryCode(),
                customer.language(), customer.address(),
                customer.postalCode(), customer.city(), customer.incoterm(), customer.paymentTerms(),
                customer.notes(), LocalDate.now()));
    }

    @Transactional
    public Customer update(long id, Customer changes) {
        Customer current = get(id);
        requireCompany(changes);
        return customers.save(new Customer(current.id(), changes.company(), changes.contact(), changes.email(),
                changes.phone(), changes.vatNumber(), changes.countryCode(),
                changes.language(), changes.address(),
                changes.postalCode(), changes.city(), changes.incoterm(), changes.paymentTerms(),
                changes.notes(), current.createdAt()));
    }

    @Transactional
    public void delete(long id) {
        get(id);
        customers.deleteById(id);
    }

    public long orderCount(long customerId) {
        return orders.countByCustomer(customerId);
    }

    private void requireCompany(Customer customer) {
        if (customer.company() == null || customer.company().isBlank()) {
            throw new BusinessRuleException("Bedrijfsnaam is verplicht");
        }
    }
}
