package be.enrosed.sourcing.adapter.out.persistence;

import be.enrosed.shared.Currency;
import be.enrosed.sourcing.adapter.out.persistence.SourcingEntities.SupplierEntity;
import be.enrosed.sourcing.domain.Supplier;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class SupplierPersistenceMappingTest {

    @Test
    void addressRoundTripsThroughNullableEntityColumns() {
        Supplier source = new Supplier(9L, "Shenzhen Flowers Ltd", "CN", "Shenzhen",
                "Mei Lin", "mei@example.cn", "+86 755 0000 0000", Currency.CNY,
                "EXW", "Shenzhen", 21, "Call before pickup",
                "No. 88 Longhua Road", "Block 3, Floor 6", "518000", "Guangdong");
        SupplierEntity entity = new SupplierEntity();
        entity.id = source.id();

        PanacheSourcingRepositories.SupplierAdapter.apply(source, entity);
        Supplier restored = PanacheSourcingRepositories.SupplierAdapter.toDomain(entity);

        assertEquals(source, restored);
    }

    @Test
    void legacyRowWithMissingAddressColumnsMapsWithoutDefaultsOrFailure() {
        SupplierEntity legacy = new SupplierEntity();
        legacy.id = 10L;
        legacy.name = "Legacy supplier";
        legacy.country = "CN";
        legacy.city = "Yiwu";

        Supplier restored = PanacheSourcingRepositories.SupplierAdapter.toDomain(legacy);

        assertNull(restored.addressLine1());
        assertNull(restored.addressLine2());
        assertNull(restored.postalCode());
        assertNull(restored.region());
    }
}
