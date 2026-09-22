package be.enrosed.sales.adapter.in.rest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static be.enrosed.sales.adapter.in.rest.PublicQuoteDtos.*;
import static org.junit.jupiter.api.Assertions.*;

class PublicQuotePriceVisibilityTest {
    @Test
    void hiddenEstimateCannotExposeAmountsDiscountsOrMinimumComparisons() {
        EstimateResponse original = pricedEstimate();
        EstimateResponse hidden = PublicQuotePriceVisibility.apply(original, false);
        assertFalse(hidden.pricesVisible());
        assertTrue(hidden.validation().canSubmit());
        assertTrue(hidden.validation().requiresReview());
        assertNull(hidden.validation().meetsMinimum());
        assertEquals(List.of("PRICES_ON_REQUEST", "PALLET_FIT_TO_CONFIRM"), hidden.validation().messageCodes());
        assertEquals(24, hidden.lines().getFirst().quantityPieces());
        assertEquals(2, hidden.shipping().cartons());
        assertEquals(1, hidden.shipping().pallets());
        assertFalse(hidden.lines().getFirst().priceAvailable());
        assertEquals(original.pickupLocation(), hidden.pickupLocation());
        assertNullMoneyFields(hidden);
        assertNotNull(original.lines().getFirst().unitPriceNet(), "redaction must not mutate the ERP estimate");
        assertEquals(hidden, PublicQuotePriceVisibility.apply(hidden, false), "repeated redaction is safe");
    }

    @Test
    void hiddenConfigurationKeepsCartonAndDestinationChoices() {
        ConfigurationResponse original = new ConfigurationResponse("EUR", "NET_EXCL_VAT", "FULL_CARTONS",
                List.of("DELIVERY", "PICKUP"), "ESTIMATE_NOT_BINDING",
                List.of(new CountryOption("BE", "Belgium", new BigDecimal("100"), 2)),
                List.of(new ProductPrice(1L, new BigDecimal("10"), true, 12)),
                List.of(new PickupLocation(2L, "Warehouse", "Street 1", null, 0)));
        ConfigurationResponse hidden = PublicQuotePriceVisibility.apply(original, false);
        assertFalse(hidden.pricesVisible());
        assertNull(hidden.products().getFirst().unitPriceNet());
        assertFalse(hidden.products().getFirst().priceAvailable());
        assertEquals(12, hidden.products().getFirst().piecesPerCarton());
        assertNull(hidden.countries().getFirst().minimumOrderNet());
        assertEquals(2, hidden.countries().getFirst().transitDays());
        assertEquals(original.pickupLocations(), hidden.pickupLocations());
        assertSame(original, PublicQuotePriceVisibility.apply(original, true));
    }

    @Test
    void hiddenPricesStillSayPerWhichUnitTheProductIsSold() throws Exception {
        var bowl = be.enrosed.catalog.adapter.in.rest.UnitDto.of("bowl", be.enrosed.shared.Language.FR);
        ConfigurationResponse original = new ConfigurationResponse("EUR", "NET_EXCL_VAT", "FULL_CARTONS",
                List.of("DELIVERY"), "ESTIMATE_NOT_BINDING", List.of(),
                List.of(new ProductPrice(1L, new BigDecimal("3.95"), true, 40, "PIECE", 8, bowl)),
                List.of());
        ConfigurationResponse hidden = PublicQuotePriceVisibility.apply(original, false);

        assertNull(hidden.products().getFirst().unitPriceNet());
        assertEquals(bowl, hidden.products().getFirst().unit(), "a unit name is not a price");
        assertEquals(8, hidden.products().getFirst().piecesPerDisplay());
        JsonNode unit = new ObjectMapper().valueToTree(hidden).path("products").path(0).path("unit");
        assertEquals("bowl", unit.path("key").asText());
        assertEquals("par bol", unit.path("per").asText());
        assertEquals("bols", unit.path("short").asText());
        assertEquals("bols", unit.path("few").asText());
        assertFalse(unit.has("shortForm"), "the wire name is 'short'");
    }

    @Test
    void visibleModeRetainsExistingCalculationsAndReplayRedactsOnlyPublicResponse() {
        EstimateResponse original = pricedEstimate();
        assertTrue(original.pricesVisible());
        assertSame(original, PublicQuotePriceVisibility.apply(original, true));
        SubmissionResponse accepted = new SubmissionResponse("WEB-123", "RECEIVED", "REQUEST_RECEIVED_NOT_BINDING",
                "FINAL_QUOTE_FOLLOWS", original);
        SubmissionResponse hidden = PublicQuotePriceVisibility.apply(accepted, false);
        assertEquals(accepted.reference(), hidden.reference());
        assertNullMoneyFields(hidden.estimate());
        assertEquals(original, accepted.estimate());
        assertEquals(accepted, PublicQuotePriceVisibility.apply(accepted, true));
        SubmissionResponse honeypot = new SubmissionResponse("WEB-456", "RECEIVED", "REQUEST_RECEIVED_NOT_BINDING",
                "FINAL_QUOTE_FOLLOWS", null);
        assertEquals(honeypot, PublicQuotePriceVisibility.apply(honeypot, false));
    }

    @Test
    void responsesStoredBeforeTheSettingExistedFollowTheCurrentPolicy() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode persisted = mapper.valueToTree(pricedEstimate());
        persisted.remove("pricesVisible");
        EstimateResponse old = mapper.treeToValue(persisted, EstimateResponse.class);
        assertFalse(PublicQuotePriceVisibility.apply(old, false).pricesVisible());
        assertNullMoneyFields(PublicQuotePriceVisibility.apply(old, false));
        assertTrue(PublicQuotePriceVisibility.apply(old, true).pricesVisible());
        assertEquals(pricedEstimate().totals(), PublicQuotePriceVisibility.apply(old, true).totals());
    }

    private static void assertNullMoneyFields(EstimateResponse value) {
        JsonNode json = new ObjectMapper().valueToTree(value);
        for (JsonNode line : json.path("lines")) {
            for (String key : List.of("unitPriceNet", "discountPct", "lineTotalNet")) assertTrue(line.path(key).isNull(), key);
        }
        for (String key : List.of("freightNet", "handlingNet", "totalNet")) assertTrue(json.path("shipping").path(key).isNull(), key);
        for (String key : List.of("goodsGrossNet", "lineDiscountNet", "goodsAfterLineDiscountNet", "orderDiscountPct",
                "orderDiscountNet", "goodsNet", "shippingNet", "totalNet", "vatRatePct", "vatAmount", "totalInclVat")) {
            assertTrue(json.path("totals").path(key).isNull(), key);
        }
        for (String key : List.of("minimumOrderNet", "minimumShortfallNet")) assertTrue(json.path("validation").path(key).isNull(), key);
    }

    static EstimateResponse pricedEstimate() {
        BigDecimal amount = new BigDecimal("123.45");
        return new EstimateResponse("EUR", "NET_EXCL_VAT", "DELIVERY",
                new PickupLocation(2L, "Warehouse", "Street 1", null, 0), "ESTIMATE_NOT_BINDING", "FINAL_QUOTE_FOLLOWS",
                List.of(new LineEstimate(1L, "SKU-1", 2, 24, 12, amount, amount, amount, true)),
                new ShippingEstimate("CALCULATED", "COUNTRY_TARIFF", amount, amount, amount, 1, 2),
                new TotalsEstimate(amount, amount, amount, amount, amount, amount, amount, amount, amount, amount, amount, "DOMESTIC", true),
                new ValidationSummary(true, true, false, amount, amount, List.of("MINIMUM_NOT_MET", "PRICE_TO_CONFIRM", "PALLET_FIT_TO_CONFIRM")));
    }
}
