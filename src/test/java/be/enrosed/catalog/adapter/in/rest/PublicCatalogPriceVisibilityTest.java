package be.enrosed.catalog.adapter.in.rest;

import be.enrosed.catalog.domain.CatalogChannel;
import be.enrosed.shared.Language;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PublicCatalogPriceVisibilityTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void hiddenFamilyCatalogueCarriesNoAmountOnAnyChannelAndSaysSo() throws Exception {
        for (CatalogChannel channel : CatalogChannel.values()) {
            PublicFamilyCatalogDto original = pricedFamilies(channel);
            PublicFamilyCatalogDto hidden = PublicCatalogPriceVisibility.apply(original, false);
            String body = JSON.writeValueAsString(hidden);
            JsonNode wire = JSON.readTree(body);

            assertFalse(wire.path("pricesVisible").asBoolean(true), channel.name());
            assertTrue(wire.path("families").get(0).path("variants").get(0).path("publicPrice").isNull());
            assertTrue(wire.path("families").get(0).path("variants").get(1).path("publicPrice").isNull());
            for (String amount : List.of("12.34", "56.78", "99.99", "EUR")) {
                assertFalse(body.contains(amount), channel.name() + " leaked " + amount);
            }
            assertEquals(JSON.readTree(JSON.writeValueAsString(withoutPriceFields(original))),
                    withoutPriceFields(hidden), "everything except the prices stays as projected");
            assertNotNull(original.families().getFirst().variants().getFirst().publicPrice(),
                    "redaction must not mutate the ERP projection");
            assertEquals(hidden, PublicCatalogPriceVisibility.apply(hidden, false), "repeated redaction is safe");
        }
    }

    @Test
    void visibleFamilyCatalogueIsUntouchedAndOlderCallersStayVisible() throws Exception {
        PublicFamilyCatalogDto original = pricedFamilies(CatalogChannel.WEBSITE);

        assertSame(original, PublicCatalogPriceVisibility.apply(original, true));
        JsonNode wire = JSON.readTree(JSON.writeValueAsString(original));
        assertTrue(wire.path("pricesVisible").asBoolean(false));
        assertEquals(0, new BigDecimal("12.34").compareTo(wire.path("families").get(0)
                .path("variants").get(0).path("publicPrice").path("amount").decimalValue()));
    }

    @Test
    void hiddenLegacyCatalogueCarriesNoSalesPrice() throws Exception {
        PublicCatalogDto original = new PublicCatalogDto(CatalogChannel.ORDER_APP, Language.NL, List.of(
                new PublicCatalogDto.PublicProductDto(1L, "ENR-P01", "rose-family", "rose-1", "Roos",
                        "Beschrijving", "Rood", null, null, null, new BigDecimal("35.50"),
                        PublicCatalogDto.Availability.IN_STOCK, List.of())));

        assertSame(original, PublicCatalogPriceVisibility.apply(original, true));
        PublicCatalogDto hidden = PublicCatalogPriceVisibility.apply(original, false);
        String body = JSON.writeValueAsString(hidden);

        assertTrue(JSON.readTree(body).path("products").get(0).path("salesPriceEur").isNull());
        assertFalse(body.contains("35.5"), body);
        assertEquals("ENR-P01", hidden.products().getFirst().sku());
        assertEquals(PublicCatalogDto.Availability.IN_STOCK, hidden.products().getFirst().availability());
    }

    private static JsonNode withoutPriceFields(PublicFamilyCatalogDto catalog) throws Exception {
        JsonNode wire = JSON.readTree(JSON.writeValueAsString(catalog));
        ((com.fasterxml.jackson.databind.node.ObjectNode) wire).remove("pricesVisible");
        wire.path("families").forEach(family -> family.path("variants").forEach(variant ->
                ((com.fasterxml.jackson.databind.node.ObjectNode) variant).remove("publicPrice")));
        return wire;
    }

    private static PublicFamilyCatalogDto pricedFamilies(CatalogChannel channel) {
        PublicFamilyCatalogDto.VariantDto red = new PublicFamilyCatalogDto.VariantDto(
                20L, "ENR-RED", "5400000000017", "Red", "9*9cm", "#A91F32", "Red rose", 0, "IN_STOCK", 30L,
                new PublicFamilyCatalogDto.PublicPriceDto(new BigDecimal("12.34"), "EUR", new BigDecimal("99.99")),
                Map.of("name", Language.EN), "DISPLAY", 6, UnitDto.of(null, Language.EN));
        PublicFamilyCatalogDto.VariantDto pink = new PublicFamilyCatalogDto.VariantDto(
                21L, "ENR-PINK", null, "Pink", null, "#F4B6C2", "Pink rose", 1, "UNKNOWN", null,
                new PublicFamilyCatalogDto.PublicPriceDto(new BigDecimal("56.78"), "EUR", null),
                Map.of("name", Language.EN));
        PublicFamilyCatalogDto.FamilyDto family = new PublicFamilyCatalogDto.FamilyDto(
                10L, "rose-family", "rose-dome", "Rose dome", "Summary", "Description", "Dome",
                List.of("Lasts for years"), null, 0, 20L, List.of("rose"), "PUBLISHED",
                new PublicFamilyCatalogDto.SeoDto("Rose dome", "Summary"),
                new PublicFamilyCatalogDto.DimensionsDto(BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, "cm", null),
                List.of(new PublicFamilyCatalogDto.PackageDto("CARTON", 0, null, 6, new BigDecimal("2.5"), "kg", 20L)),
                List.of(), List.of(red, pink), Map.of("name", Language.EN), 30L);
        return new PublicFamilyCatalogDto(channel, Language.EN, List.of(Language.EN), 17L, "a".repeat(64),
                Map.of("common.priceOnRequest", new LocalizedValueDto(Language.EN, "Price on request")),
                List.of(), List.of(family));
    }
}
