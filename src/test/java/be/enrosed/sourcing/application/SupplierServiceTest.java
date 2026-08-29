package be.enrosed.sourcing.application;

import be.enrosed.catalog.application.port.out.ProductRepository;
import be.enrosed.shared.BusinessRuleException;
import be.enrosed.shared.Currency;
import be.enrosed.shared.audit.ActivityLogService;
import be.enrosed.sourcing.application.port.out.SourcingRepositories;
import be.enrosed.sourcing.domain.Supplier;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class SupplierServiceTest {

    @Test
    void saveTrimsAddressValuesAndStoresBlankAsNull() {
        InMemorySuppliers repository = new InMemorySuppliers();
        SupplierService service = new SupplierService(repository, mock(ProductRepository.class));

        Supplier saved = service.save(new Supplier(null, "  Test supplier  ", " cn ", " Shenzhen ",
                null, null, null, Currency.CNY, "EXW", "Shenzhen", 20, null,
                "  Bao'an Industrial Park  ", " \t ", " 518000 ", "  Guangdong "));

        assertEquals("Bao'an Industrial Park", saved.addressLine1());
        assertNull(saved.addressLine2());
        assertEquals("518000", saved.postalCode());
        assertEquals("Guangdong", saved.region());
        assertEquals("Test supplier", saved.name());
        assertEquals("CN", saved.country());
        assertEquals("Shenzhen", saved.city());
    }

    @Test
    void legacySupplierWithoutAddressRemainsValid() {
        InMemorySuppliers repository = new InMemorySuppliers();
        SupplierService service = new SupplierService(repository, mock(ProductRepository.class));

        Supplier saved = service.save(new Supplier(null, "Legacy supplier", "CN", "Yiwu",
                null, null, null, Currency.USD, "FOB", "Ningbo", 30, null));

        assertNull(saved.addressLine1());
        assertNull(saved.addressLine2());
        assertNull(saved.postalCode());
        assertNull(saved.region());
    }

    @Test
    void suppliedCountryMustBeAnActualIsoCode() {
        SupplierService service = new SupplierService(
                new InMemorySuppliers(), mock(ProductRepository.class));
        Supplier invalid = new Supplier(null, "Supplier", "XX", "City",
                null, null, null, Currency.USD, null, null, 0, null);

        assertThrows(BusinessRuleException.class, () -> service.save(invalid));
    }

    @Test
    void createUpdateAndDeleteAppendServerAttributedActivity() {
        InMemorySuppliers repository = new InMemorySuppliers();
        ProductRepository products = mock(ProductRepository.class);
        SupplierService service = new SupplierService(repository, products);
        ActivityLogService activities = mock(ActivityLogService.class);
        @SuppressWarnings("unchecked")
        Instance<ActivityLogService> activityInstance = mock(Instance.class);
        when(activityInstance.isResolvable()).thenReturn(true);
        when(activityInstance.get()).thenReturn(activities);
        service.activity = activityInstance;

        Supplier created = service.save(new Supplier(null, "Supplier One", "CN", "Yiwu",
                null, null, null, Currency.CNY, "EXW", "Ningbo", 30, null));
        Supplier updated = service.save(created.withId(created.id()));
        service.delete(created.id());

        InOrder auditOrder = inOrder(activities);
        auditOrder.verify(activities).record(ActivityLogService.ACTION_CREATED,
                "SUPPLIER", "1", "Supplier One", "Leverancier aangemaakt");
        auditOrder.verify(activities).record(ActivityLogService.ACTION_DELETED,
                "SUPPLIER", "1", "Supplier One", "Leverancier verwijderd");
        verifyNoMoreInteractions(activities);
        assertEquals(1L, updated.id());
    }

    private static final class InMemorySuppliers implements SourcingRepositories.Suppliers {
        private Supplier stored;

        @Override
        public List<Supplier> findAll() {
            return stored == null ? List.of() : List.of(stored);
        }

        @Override
        public Optional<Supplier> findById(long id) {
            return Optional.ofNullable(stored).filter(value -> value.id() == id);
        }

        @Override
        public Supplier save(Supplier supplier) {
            stored = supplier.id() == null ? supplier.withId(1L) : supplier;
            return stored;
        }

        @Override
        public void deleteById(long id) {
            stored = null;
        }
    }
}
