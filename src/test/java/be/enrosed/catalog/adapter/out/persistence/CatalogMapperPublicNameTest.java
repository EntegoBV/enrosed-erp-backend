package be.enrosed.catalog.adapter.out.persistence;

import be.enrosed.catalog.domain.Barcodes;
import be.enrosed.catalog.domain.Carton;
import be.enrosed.catalog.domain.Dimensions;
import be.enrosed.catalog.domain.Product;
import be.enrosed.catalog.domain.ProductText;
import be.enrosed.shared.Currency;
import be.enrosed.shared.Language;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CatalogMapperPublicNameTest {
    @Test
    void documentNamesFollowIntoPublicCopyOnlyUntilTheyDiverge() {
        ProductEntity entity = new ProductEntity();
        entity.id = 1L;
        entity.sku = "SKU-COPY-ON-WRITE";
        entity.name = "Invoice A";
        entity.publicName = "Invoice A";
        ProductTextEntity french = new ProductTextEntity();
        french.product = entity;
        french.language = Language.FR;
        french.name = "Facture A";
        french.publicName = "Facture A";
        entity.texts.add(french);

        Product firstEdit = product("Invoice B", "Facture B");
        CatalogMapper.apply(firstEdit, entity);
        CatalogMapper.applyTexts(firstEdit, entity);
        assertEquals("Invoice B", entity.name);
        assertEquals("Invoice B", entity.publicName);
        assertEquals("Facture B", french.name);
        assertEquals("Facture B", french.publicName);

        entity.publicName = "Website name";
        french.publicName = "Nom public";
        Product secondEdit = product("Invoice C", "Facture C");
        CatalogMapper.apply(secondEdit, entity);
        CatalogMapper.applyTexts(secondEdit, entity);

        assertEquals("Invoice C", entity.name);
        assertEquals("Website name", entity.publicName);
        assertEquals("Facture C", french.name);
        assertEquals("Nom public", french.publicName);
    }

    private static Product product(String name, String frenchName) {
        return new Product(
                1L, "SKU-COPY-ON-WRITE", name, Dimensions.empty(), null, null,
                null, null, true, Barcodes.none(), null, Carton.empty(),
                null, Currency.USD, null, null, null, null, null, 0,
                List.of(), List.of(new ProductText(
                        Language.FR, frenchName, null, null, null)));
    }
}
