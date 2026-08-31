package be.enrosed.catalog.adapter.out.persistence;

import be.enrosed.catalog.domain.Product;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class CatalogMapperSupplierNoteTest {

    @Test
    void supplierNoteRoundTripsAndBlankTextIsStoredAsNull() {
        ProductEntity entity = new ProductEntity();
        entity.id = 7L;
        entity.sku = "SKU-7";
        entity.name = "Product";
        entity.supplierNote = "Gebruik kartonnen hoekbeschermers";

        Product restored = CatalogMapper.toDomain(entity);
        assertEquals("Gebruik kartonnen hoekbeschermers", restored.supplierNote());

        CatalogMapper.apply(restored.withSupplierNote("  Geen plastic tape  "), entity);
        assertEquals("Geen plastic tape", entity.supplierNote);

        CatalogMapper.apply(restored.withSupplierNote(" \n "), entity);
        assertNull(entity.supplierNote);
    }
}
