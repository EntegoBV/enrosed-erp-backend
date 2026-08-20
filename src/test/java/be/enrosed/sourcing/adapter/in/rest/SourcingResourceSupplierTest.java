package be.enrosed.sourcing.adapter.in.rest;

import be.enrosed.shared.Currency;
import be.enrosed.sourcing.application.SupplierService;
import be.enrosed.sourcing.domain.Supplier;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SourcingResourceSupplierTest {

    @Test
    void putUsesPathIdAndRetainsEveryAddressField() {
        SupplierService suppliers = mock(SupplierService.class);
        when(suppliers.save(any(Supplier.class))).thenAnswer(call -> call.getArgument(0));
        SourcingResource resource = new SourcingResource(suppliers, null, null);
        Supplier request = new Supplier(999L, "Factory Ltd", "CN", "Ningbo",
                null, null, null, Currency.CNY, "EXW", "Ningbo", 20, null,
                "Road 1", "Building 2", "315000", "Zhejiang");

        Supplier response = resource.updateSupplier(42L, request);

        assertEquals(42L, response.id());
        assertEquals("Road 1", response.addressLine1());
        assertEquals("Building 2", response.addressLine2());
        assertEquals("315000", response.postalCode());
        assertEquals("Zhejiang", response.region());
    }

    @Test
    void legacyJsonWithoutAddressStillDeserializes() throws Exception {
        Supplier legacy = new ObjectMapper().readValue("""
                {"id":7,"name":"Legacy supplier","country":"CN","city":"Yiwu",
                 "currency":"USD","incoterm":"FOB","portOfLoading":"Ningbo",
                 "leadTimeDays":30}
                """, Supplier.class);

        assertNull(legacy.addressLine1());
        assertNull(legacy.addressLine2());
        assertNull(legacy.postalCode());
        assertNull(legacy.region());
    }
}
