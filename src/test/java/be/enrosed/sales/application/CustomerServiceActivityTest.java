package be.enrosed.sales.application;

import be.enrosed.sales.application.port.out.SalesRepositories;
import be.enrosed.sales.domain.Customer;
import be.enrosed.shared.audit.ActivityChangeDto;
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
                "CUSTOMER", "1", "Buyer Group BV", "Klant bijgewerkt",
                List.of(new ActivityChangeDto("company", "Bedrijf", "Buyer BV", "Buyer Group BV")));
        auditOrder.verify(activities).record(ActivityLogService.ACTION_DELETED,
                "CUSTOMER", "1", "Buyer Group BV", "Klant verwijderd");
        org.junit.jupiter.api.Assertions.assertEquals("Buyer Group BV", updated.company());
    }

    @Test
    void theVatNumberIsRequiredButItsCountryIsNotJudged() {
        InMemoryCustomers repository = new InMemoryCustomers();
        CustomerService service = new CustomerService(repository, mock(SalesRepositories.Orders.class));

        Customer blank = new Customer(null, "Buyer BV", "Contact", "buyer@example.com", null, "  ",
                "BE", null, null, null, null, null, null, null, null);
        org.junit.jupiter.api.Assertions.assertThrows(be.enrosed.shared.BusinessRuleException.class,
                () -> service.create(blank));

        Customer dutchNumberBelgianCountry = new Customer(null, "Buyer BV", "Contact", "buyer@example.com",
                null, "NL123456789B01", "BE", null, null, null, null, null, null, null, null);
        Customer saved = service.create(dutchNumberBelgianCountry);
        org.junit.jupiter.api.Assertions.assertEquals("NL123456789B01", saved.vatNumber(),
                "a number that does not fit the country still saves; it is checked by hand");
    }

    @Test
    void fillingTheAddressWritesOnlyEmptyFieldsAndIsLoggedOnceUnderItsOwnSummary() {
        InMemoryCustomers repository = new InMemoryCustomers();
        CustomerService service = new CustomerService(repository, mock(SalesRepositories.Orders.class));
        ActivityLogService activities = mock(ActivityLogService.class);
        @SuppressWarnings("unchecked")
        Instance<ActivityLogService> activityInstance = mock(Instance.class);
        when(activityInstance.isResolvable()).thenReturn(true);
        when(activityInstance.get()).thenReturn(activities);
        /* A record as login approval leaves it, with a city somebody typed already and no country. */
        Customer before = repository.save(new Customer(1L, "Buyer BV", "Contact", "buyer@example.com", "+32 1", "BE0123456789",
                null, be.enrosed.shared.Language.NL, null, " ", "Gent", "DAP", "30 dagen", "let op", LocalDate.of(2026, 10, 1)));
        service.activity = activityInstance;

        Customer filled = service.fillMissingAddress(1L, " Stationsstraat 9 ", "9000", "Brussel", "BE",
                "Adres overgenomen van het leveradres van ENR-1");

        org.junit.jupiter.api.Assertions.assertEquals("Stationsstraat 9", filled.address());
        org.junit.jupiter.api.Assertions.assertEquals("9000", filled.postalCode());
        org.junit.jupiter.api.Assertions.assertEquals("Gent", filled.city(), "a field that holds a value keeps it");
        org.junit.jupiter.api.Assertions.assertEquals("BE", filled.countryCode());
        org.junit.jupiter.api.Assertions.assertEquals(new Customer(1L, "Buyer BV", "Contact", "buyer@example.com", "+32 1",
                "BE0123456789", "BE", be.enrosed.shared.Language.NL, "Stationsstraat 9", "9000", "Gent", "DAP", "30 dagen", "let op",
                LocalDate.of(2026, 10, 1)), filled, "name, VAT number, contact data and terms are the record's own");
        org.junit.jupiter.api.Assertions.assertEquals(filled, repository.findById(1L).orElseThrow());
        org.mockito.Mockito.verify(activities).record(ActivityLogService.ACTION_UPDATED, "CUSTOMER", "1", "Buyer BV",
                "Adres overgenomen van het leveradres van ENR-1",
                List.of(new ActivityChangeDto("countryCode", "Land", null, "BE"),
                        new ActivityChangeDto("postalCode", "Postcode", null, null),
                        new ActivityChangeDto("address", "Adres", null, null)));

        Customer again = service.fillMissingAddress(1L, "Andere straat 1", "1000", "Brussel", "NL", "Adres overgenomen");
        org.junit.jupiter.api.Assertions.assertEquals(filled, again, "a second call changes nothing");
        org.mockito.Mockito.verifyNoMoreInteractions(activities);
        org.junit.jupiter.api.Assertions.assertNotEquals(before, again);
    }

    @Test
    void fillingTheAddressDoesNotJudgeARecordThatLacksAVatNumber() {
        InMemoryCustomers repository = new InMemoryCustomers();
        CustomerService service = new CustomerService(repository, mock(SalesRepositories.Orders.class));
        repository.save(new Customer(1L, "Oude klant", null, null, null, null, "BE", null, null, null, null, null, null,
                null, LocalDate.of(2020, 1, 1)));

        Customer filled = service.fillMissingAddress(1L, "Kaai 1", "9000", "Gent", "BE", "Adres overgenomen");

        org.junit.jupiter.api.Assertions.assertEquals("Kaai 1", filled.address());
        org.junit.jupiter.api.Assertions.assertNull(filled.vatNumber());
    }

    private static Customer customer(Long id, String company) {
        return new Customer(id, company, "Contact", "buyer@example.com", null, "BE0123456789",
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
