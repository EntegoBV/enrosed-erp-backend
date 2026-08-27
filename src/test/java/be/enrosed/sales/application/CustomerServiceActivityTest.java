package be.enrosed.sales.application;

import be.enrosed.sales.application.port.out.SalesRepositories;
import be.enrosed.sales.domain.Customer;
import be.enrosed.shared.audit.ActivityLogService;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CustomerServiceActivityTest {

    @Test
    void createUpdateAndDeleteAppendGenericActivityWithoutAClientActor() {
        InMemoryCustomers repository = new InMemoryCustomers();
        CustomerService service = new CustomerService(repository, mock(SalesRepositories.Orders.class));
        ActivityLogService activities = mock(ActivityLogService.class);
        @SuppressWarnings("unchecked")
        Instance<ActivityLogService> activityInstance = mock(Instance.class);
        when(activityInstance.isResolvable()).thenReturn(true);
        when(activityInstance.get()).thenReturn(activities);
        service.activity = activityInstance;

        Customer created = service.create(customer(null, "Buyer BV"));
        Customer updated = service.update(created.id(), customer(null, "Buyer Group BV"));
        service.delete(created.id());

        InOrder auditOrder = inOrder(activities);
        auditOrder.verify(activities).record(ActivityLogService.ACTION_CREATED,
                "CUSTOMER", "1", "Buyer BV", "Klant aangemaakt");
        auditOrder.verify(activities).record(ActivityLogService.ACTION_UPDATED,
                "CUSTOMER", "1", "Buyer Group BV", "Klant bijgewerkt");
        auditOrder.verify(activities).record(ActivityLogService.ACTION_DELETED,
                "CUSTOMER", "1", "Buyer Group BV", "Klant verwijderd");
        org.junit.jupiter.api.Assertions.assertEquals("Buyer Group BV", updated.company());
    }

    private static Customer customer(Long id, String company) {
        return new Customer(id, company, "Contact", "buyer@example.com", null, null,
                "BE", null, null, null, null, null, null, null, null);
    }

    private static Customer withId(Customer source, long id) {
        return new Customer(id, source.company(), source.contact(), source.email(), source.phone(),
                source.vatNumber(), source.countryCode(), source.language(), source.address(),
                source.postalCode(), source.city(), source.incoterm(), source.paymentTerms(),
                source.notes(), source.createdAt() == null ? LocalDate.now() : source.createdAt());
    }

    private static final class InMemoryCustomers implements SalesRepositories.Customers {
        private final List<Customer> rows = new ArrayList<>();

        @Override
        public List<Customer> findAll() {
            return List.copyOf(rows);
        }

        @Override
        public Optional<Customer> findById(long id) {
            return rows.stream().filter(customer -> customer.id() == id).findFirst();
        }

        @Override
        public Customer save(Customer customer) {
            Customer saved = customer.id() == null ? withId(customer, 1L) : customer;
            rows.removeIf(existing -> existing.id().equals(saved.id()));
            rows.add(saved);
            return saved;
        }

        @Override
        public void deleteById(long id) {
            rows.removeIf(customer -> customer.id() == id);
        }
    }
}
