package be.enrosed.catalog.adapter.in.rest;

import be.enrosed.catalog.domain.Barcodes;
import be.enrosed.catalog.domain.Carton;
import be.enrosed.catalog.domain.Dimensions;
import be.enrosed.catalog.domain.Product;
import be.enrosed.shared.Currency;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ProductDtoSupplierNoteTest {

    @Test
    void supplierNoteIsPresentInResponsesAndAcceptedInRequests() throws Exception {
        Product product = new Product(
                7L, "SKU-7", "Product", Dimensions.empty(), null, null,
                2L, 3L, true, Barcodes.none(), null, Carton.empty(),
                null, Currency.USD, null, null, null, null, null, 0,
                List.of(), List.of())
                .withSupplierNote("Gebruik kartonnen hoekbeschermers");
        ObjectMapper json = new ObjectMapper();

        ProductDto response = ProductDto.from(product);
        JsonNode payload = json.readTree(json.writeValueAsBytes(response));
        assertEquals("Gebruik kartonnen hoekbeschermers", payload.path("supplierNote").asText());

        ProductDto request = json.treeToValue(payload, ProductDto.class);
        assertEquals("Gebruik kartonnen hoekbeschermers", request.toDomain(7L).supplierNote());
    }
}
