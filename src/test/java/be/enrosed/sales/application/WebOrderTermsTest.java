package be.enrosed.sales.application;

import be.enrosed.sales.domain.DeliveryTermsState;
import be.enrosed.sales.domain.DocumentType;
import be.enrosed.sales.domain.FreightPricingStrategy;
import be.enrosed.sales.domain.FreightState;
import be.enrosed.sales.domain.LoadMode;
import be.enrosed.sales.domain.MarkupMode;
import be.enrosed.sales.domain.PalletProfile;
import be.enrosed.sales.domain.PricedOrder;
import be.enrosed.sales.domain.QuoteStatus;
import be.enrosed.sales.domain.SalesOrder;
import be.enrosed.sales.domain.SalesPurpose;
import be.enrosed.sales.domain.VatTreatment;
import be.enrosed.shared.Money;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The fingerprint a website order is compared by, the Dutch list of what
 * differs, and the snapshot of what the customer ordered.
 */
class WebOrderTermsTest {

    @Test
    void anEqualDocumentHasTheSameTermsWhateverTheLineOrder() {
        PricedOrder first = priced(List.of(line(12, "ER-RED", 96, "1.85", "5", "168.72"), line(7, "ER-PINK", 48, "2.00", "0", "96.00")),
                "42.00", "21", List.of());
        PricedOrder second = priced(List.of(line(7, "ER-PINK", 48, "2.0000", "0.00", "96"), line(12, "ER-RED", 96, "1.850", "5.0", "168.720")),
                "42", "21.00", List.of());
        assertEquals(WebOrderTerms.of(first), WebOrderTerms.of(second));
        assertEquals(64, WebOrderTerms.of(first).length());
        assertEquals(WebOrderTerms.of(first), WebOrderTerms.of(first, BigDecimal.ZERO));
    }

    @Test
    void quantityPriceDiscountFreightAnExtraLineAndTheVatRateEachChangeTheTerms() {
        String ordered = WebOrderTerms.of(ordered());
        assertNotEquals(ordered, WebOrderTerms.of(priced(List.of(line(12, "ER-RED", 120, "1.85", "5", "210.90")), "42.00", "21", List.of())));
        assertNotEquals(ordered, WebOrderTerms.of(priced(List.of(line(12, "ER-RED", 96, "1.95", "5", "177.84")), "42.00", "21", List.of())));
        assertNotEquals(ordered, WebOrderTerms.of(priced(List.of(line(12, "ER-RED", 96, "1.85", "0", "177.60")), "42.00", "21", List.of())));
        assertNotEquals(ordered, WebOrderTerms.of(priced(List.of(line(12, "ER-RED", 96, "1.85", "5", "168.72")), "55.00", "21", List.of())));
        assertNotEquals(ordered, WebOrderTerms.of(priced(List.of(line(12, "ER-RED", 96, "1.85", "5", "168.72")), "42.00", "21",
                List.of(extra("Montage", "25.00")))));
        assertNotEquals(ordered, WebOrderTerms.of(priced(List.of(line(12, "ER-RED", 96, "1.85", "5", "168.72")), "42.00", "0", List.of())));
        assertNotEquals(ordered, WebOrderTerms.of(priced(List.of(line(12, "ER-RED", 96, "1.85", "5", "168.72"),
                line(13, "ER-BLUE", 24, "2.00", "0", "48.00")), "42.00", "21", List.of())));
    }

    @Test
    void aSlotfactuurEqualsTheOrderOnceItsAdvanceDeductionsAreAddedBack() {
        PricedOrder slotfactuur = priced(List.of(line(12, "ER-RED", 96, "1.85", "5", "168.72")), "42.00", "21",
                List.of(extra("Voorschot F-2026-0001", "-60.00"), extra("Voorschot F-2026-0002", "-40.50")));
        assertNotEquals(WebOrderTerms.of(ordered()), WebOrderTerms.of(slotfactuur));
        assertEquals(WebOrderTerms.of(ordered()), WebOrderTerms.of(slotfactuur, new BigDecimal("100.50")));
        assertEquals(List.of(), WebOrderTerms.differences(snapshot(ordered()), slotfactuur, new BigDecimal("100.50")));
    }

    @Test
    void theDifferencesNameEveryChangeInDutch() {
        WebOrderSnapshot snapshot = snapshot(priced(List.of(line(12, "ER-RED", 960, "1.85", "5", "1687.20"),
                line(7, "ER-PINK", 48, "2.00", "0", "96.00")), "42.00", "21", List.of()));
        PricedOrder now = priced(List.of(line(12, "ER-RED", 720, "1.95", "7.5", "1298.70"),
                line(13, "ER-BLUE", 24, "2.00", "0", "48.00")), "55.00", "6", List.of(extra("Montage", "25.00")));
        assertEquals(List.of(
                "Aantal ER-RED: besteld 960, nu 720",
                "Prijs ER-RED: besteld € 1,85, nu € 1,95",
                "Korting ER-RED: besteld 5 %, nu 7,5 %",
                "Product verwijderd: ER-PINK",
                "Product toegevoegd: ER-BLUE",
                "Vracht: besteld € 42,00, nu € 55,00",
                "Extra regels: niet besteld, nu € 25,00",
                "Totaal excl. btw: besteld € 1.825,20, nu € 1.426,70"), WebOrderTerms.differences(snapshot, now));
        assertEquals(List.of(), WebOrderTerms.differences(snapshot, priced(List.of(line(12, "ER-RED", 960, "1.85", "5", "1687.20"),
                line(7, "ER-PINK", 48, "2.00", "0", "96.00")), "42.00", "21", List.of())));
        assertEquals(List.of("Btw-tarief: besteld 21 %, nu 6 %"), WebOrderTerms.differences(snapshot(ordered()),
                priced(List.of(line(12, "ER-RED", 96, "1.85", "5", "168.72")), "42.00", "6", List.of())));
    }

    @Test
    void neverMoreThanEightDifferencesAndTheTotalIsNeverTheOneThatFallsOff() {
        List<PricedOrder.Line> orderedLines = new ArrayList<>();
        List<PricedOrder.Line> changedLines = new ArrayList<>();
        for (int product = 1; product <= 12; product++) {
            orderedLines.add(line(product, "SKU-" + product, 24, "2.00", "0", "48.00"));
            changedLines.add(line(product, "SKU-" + product, 48, "2.00", "0", "96.00"));
        }
        List<String> differences = WebOrderTerms.differences(snapshot(priced(orderedLines, "42.00", "21", List.of())),
                priced(changedLines, "42.00", "21", List.of()));
        assertEquals(8, differences.size());
        assertEquals("Aantal SKU-1: besteld 24, nu 48", differences.getFirst());
        assertEquals("Totaal excl. btw: besteld € 618,00, nu € 1.194,00", differences.getLast());
    }

    @Test
    void freightThatWasOpenAtOrderingReadsAsStillToConfirm() {
        WebOrderSnapshot open = WebOrderSnapshot.of(order().freight(FreightState.TE_BEPALEN).build(),
                priced(List.of(line(12, "ER-RED", 96, "1.85", "5", "168.72")), "0", "21", List.of()),
                1, Instant.parse("2026-10-08T09:12:44Z"), "NL", "DELIVERY", Map.of(12L, 24), Map.of());
        assertEquals(List.of("Vracht: besteld nog te bevestigen, nu € 42,00"), WebOrderTerms.differences(open, ordered()));
    }

    @Test
    void theSnapshotSurvivesItsJsonAndCarriesNoInternalFigure() throws Exception {
        WebOrderSnapshot snapshot = WebOrderSnapshot.of(order().notes("Graag voor vrijdag").build(), ordered(), 2,
                Instant.parse("2026-10-08T09:12:44Z"), "NL", "DELIVERY", Map.of(12L, 24), Map.of());
        String json = snapshot.toJson();
        assertEquals(snapshot, WebOrderSnapshot.fromJson(json));
        JsonNode node = new ObjectMapper().readTree(json);
        assertEquals(1, node.get("v").asInt());
        assertEquals(2, node.get("revision").asInt());
        assertEquals("2026-10-08T09:12:44Z", node.get("at").asText());
        assertEquals(Instant.parse("2026-10-08T09:12:44Z"), snapshot.takenAt());
        assertEquals("NL", node.get("language").asText());
        assertEquals("BE", node.get("countryCode").asText());
        assertEquals("DELIVERY", node.get("fulfillment").asText());
        assertEquals("Graag voor vrijdag", node.get("notes").asText());
        JsonNode line = node.get("lines").get(0);
        assertEquals(List.of("productId", "sku", "description", "cartons", "piecesPerCarton", "quantity", "unitPrice",
                "discountPct", "net"), names(line));
        assertEquals(4, line.get("cartons").asInt());
        assertEquals(24, line.get("piecesPerCarton").asInt());
        assertEquals(96, line.get("quantity").asInt());
        assertEquals("Rode roos", line.get("description").asText(), "the customer's language, as on the quote");
        assertEquals(List.of("goods", "shipping", "shippingStatus", "totalExclVat", "vatRatePct", "vatAmount",
                "totalInclVat", "vatTreatment"), names(node.get("totals")));
        assertEquals("CALCULATED", node.get("totals").get("shippingStatus").asText());
        assertEquals(0, new BigDecimal("168.72").compareTo(snapshot.totals().goods()));
        assertEquals(0, new BigDecimal("210.72").compareTo(snapshot.totals().totalExclVat()));
        assertEquals(0, new BigDecimal("254.97").compareTo(snapshot.totals().totalInclVat()));
        assertEquals("BINNENLAND", snapshot.totals().vatTreatment());
        assertTrue(snapshot.complete());
        assertTrue(node.get("complete").asBoolean());
        assertEquals(List.of("v", "revision", "at", "language", "countryCode", "fulfillment", "notes", "lines", "totals",
                "complete"), names(node));
        String lower = json.toLowerCase(java.util.Locale.ROOT);
        for (String forbidden : new String[] {"cost", "margin", "internal", "actor", "landed", "stock"})
            assertFalse(lower.contains(forbidden), forbidden);
        assertNull(WebOrderSnapshot.fromJson(null));
        assertNull(WebOrderSnapshot.fromJson("{not json"));
        assertEquals(snapshot, WebOrderSnapshot.fromJson(json.replaceFirst("\\{", "{\"later\":true,")),
                "a field a later version adds does not make an old reader lose the order");
    }

    @Test
    void theSnapshotHoldsAHundredLinesAndCutsLongDescriptions() {
        List<PricedOrder.Line> lines = new ArrayList<>();
        Map<Long, Integer> pieces = new HashMap<>();
        for (int product = 1; product <= 100; product++) {
            lines.add(line(product, "SKU-" + product, 24, "2.00", "0", "48.00", "x".repeat(200)));
            pieces.put((long) product, 24);
        }
        WebOrderSnapshot hundred = WebOrderSnapshot.of(order().build(), priced(lines, "42.00", "21", List.of()), 1,
                Instant.now(), "EN", "DELIVERY", pieces, Map.of());
        assertEquals(100, hundred.lines().size());
        assertTrue(hundred.complete());
        assertEquals(160, hundred.lines().getFirst().description().length());
        assertEquals(hundred, WebOrderSnapshot.fromJson(hundred.toJson()));

        lines.add(line(101, "SKU-101", 24, "2.00", "0", "48.00"));
        pieces.put(101L, 24);
        WebOrderSnapshot more = WebOrderSnapshot.of(order().build(), priced(lines, "42.00", "21", List.of()), 1,
                Instant.now(), "EN", "DELIVERY", pieces, Map.of());
        assertEquals(100, more.lines().size());
        assertFalse(more.complete(), "a snapshot that had to drop a line never proves an unchanged order");
    }

    @Test
    void anOrderIsCompleteOnlyWhenThePageShowedTheCustomerATotal() {
        Instant at = Instant.now();
        PricedOrder.Line known = line(12, "ER-RED", 96, "1.85", "5", "168.72");

        WebOrderSnapshot zeroQuantity = WebOrderSnapshot.of(order().build(),
                priced(List.of(known, line(13, "ER-BLUE", 0, "2.00", "0", "0")), "42.00", "21", List.of()),
                1, at, "NL", "DELIVERY", Map.of(12L, 24, 13L, 12), Map.of());
        assertFalse(zeroQuantity.complete());

        WebOrderSnapshot unknownCarton = WebOrderSnapshot.of(order().build(),
                priced(List.of(known, line(13, "ER-BLUE", 0, "2.00", "0", "0")), "42.00", "21", List.of()),
                1, at, "NL", "DELIVERY", Map.of(12L, 24), Map.of(13L, 5));
        assertFalse(unknownCarton.complete());
        assertNull(unknownCarton.lines().get(1).piecesPerCarton());
        assertEquals(5, unknownCarton.lines().get(1).cartons(), "the cartons the customer asked for");

        WebOrderSnapshot noPrice = WebOrderSnapshot.of(order().build(),
                priced(List.of(known, line(13, "ER-BLUE", 24, "0", "0", "0")), "42.00", "21", List.of()),
                1, at, "NL", "DELIVERY", Map.of(12L, 24, 13L, 24), Map.of());
        assertFalse(noPrice.complete());
        assertNull(noPrice.lines().get(1).unitPrice());
        assertNull(noPrice.lines().get(1).discountPct());
        assertNull(noPrice.lines().get(1).net());

        WebOrderSnapshot freightOpen = WebOrderSnapshot.of(order().freight(FreightState.TE_BEPALEN).build(), ordered(),
                1, at, "NL", "DELIVERY", Map.of(12L, 24), Map.of());
        assertFalse(freightOpen.complete());
        assertEquals("TO_CONFIRM", freightOpen.totals().shippingStatus());
        assertNull(freightOpen.totals().shipping());
        assertNull(freightOpen.totals().totalExclVat());
        assertNull(freightOpen.totals().vatAmount());
        assertNull(freightOpen.totals().totalInclVat());
        assertEquals(0, new BigDecimal("168.72").compareTo(freightOpen.totals().goods()));
        assertEquals(0, new BigDecimal("21").compareTo(freightOpen.totals().vatRatePct()));

        PricedOrder base = ordered();
        for (PricedOrder.Validation validation : List.of(
                new PricedOrder.Validation(BigDecimal.ZERO, true, BigDecimal.ZERO, true, true, List.of(), List.of(), List.of(), "Geen tarief"),
                new PricedOrder.Validation(BigDecimal.ZERO, true, BigDecimal.ZERO, true, true, List.of(), List.of("ER-RED"), List.of(), null),
                new PricedOrder.Validation(BigDecimal.ZERO, true, BigDecimal.ZERO, true, true, List.of(), List.of(), List.of("ER-RED"), null))) {
            assertFalse(WebOrderSnapshot.of(order().build(), new PricedOrder(base.lines(), base.totals(), validation, List.of()),
                    1, at, "NL", "DELIVERY", Map.of(12L, 24), Map.of()).complete());
        }

        WebOrderSnapshot noFreightAmount = WebOrderSnapshot.of(order().build(),
                priced(List.of(known), "0", "21", List.of()), 1, at, "NL", "DELIVERY", Map.of(12L, 24), Map.of());
        assertEquals("TO_CONFIRM", noFreightAmount.totals().shippingStatus());
        assertFalse(noFreightAmount.complete());

        WebOrderSnapshot pickup = WebOrderSnapshot.of(order().incoterm("EXW").build(),
                priced(List.of(known), "0", "21", List.of()), 1, at, "NL", "PICKUP", Map.of(12L, 24), Map.of());
        assertEquals("PICKUP", pickup.totals().shippingStatus());
        assertTrue(pickup.complete());
        assertEquals(0, new BigDecimal("168.72").compareTo(pickup.totals().totalExclVat()));
        assertEquals(List.of(), WebOrderTerms.differences(pickup, priced(List.of(known), "0", "21", List.of())));
    }

    // ------------------------------------------------------------ fixtures, shared with the other web-order tests

    /** 4 cartons of 24 at € 1,85 with 5 % tier discount, € 42 freight, 21 % VAT: € 210,72 excl., € 254,97 incl. */
    static PricedOrder ordered() {
        return priced(List.of(line(12, "ER-RED", 96, "1.85", "5", "168.72")), "42.00", "21", List.of());
    }

    static WebOrderSnapshot snapshot(PricedOrder priced) {
        Map<Long, Integer> pieces = new HashMap<>();
        priced.lines().forEach(line -> pieces.put(line.productId(), 24));
        return WebOrderSnapshot.of(order().build(), priced, 1, Instant.parse("2026-10-08T09:12:44Z"), "NL", "DELIVERY",
                pieces, Map.of());
    }

    static PricedOrder.Line line(long productId, String sku, int quantity, String unitPrice, String discountPct, String net) {
        return line(productId, sku, quantity, unitPrice, discountPct, net, "Rode roos");
    }

    static PricedOrder.Line line(long productId, String sku, int quantity, String unitPrice, String discountPct,
                                 String net, String customerDescription) {
        BigDecimal zero = BigDecimal.ZERO;
        BigDecimal price = new BigDecimal(unitPrice);
        return new PricedOrder.Line(productId, sku, "Red rose", customerDescription, null, quantity, quantity / 24, 16, 1,
                4, 2, zero, zero, zero, price, price.multiply(BigDecimal.valueOf(quantity)), new BigDecimal(discountPct), zero,
                new BigDecimal(discountPct), zero, new BigDecimal(net), price,
                new BigDecimal("0.71"), new BigDecimal("68.16"), new BigDecimal("100.56"), new BigDecimal("59.60"),
                null, null, 500, true, true, 0, null, null, null);
    }

    static PricedOrder.ExtraLine extra(String description, String total) {
        return new PricedOrder.ExtraLine(description, BigDecimal.ONE, new BigDecimal(total), new BigDecimal(total));
    }

    static PricedOrder priced(List<PricedOrder.Line> lines, String shipping, String vatRatePct, List<PricedOrder.ExtraLine> extras) {
        BigDecimal zero = BigDecimal.ZERO;
        BigDecimal goods = lines.stream().map(PricedOrder.Line::net).reduce(zero, BigDecimal::add);
        BigDecimal extraTotal = extras.stream().map(PricedOrder.ExtraLine::total).reduce(zero, BigDecimal::add);
        BigDecimal freight = new BigDecimal(shipping);
        BigDecimal total = goods.add(freight).add(extraTotal);
        BigDecimal vat = Money.money(Money.percentOf(total, new BigDecimal(vatRatePct)));
        PricedOrder.Totals totals = new PricedOrder.Totals(
                lines.stream().mapToInt(PricedOrder.Line::quantity).sum(), lines.size(), 1, 1, 0, 0, zero, zero, zero, zero,
                goods, zero, goods, zero, zero, zero, null, zero, goods,
                freight, false, zero, freight, total, new BigDecimal(vatRatePct), vat, total.add(vat),
                VatTreatment.BINNENLAND, null, null, new BigDecimal("68.16"), new BigDecimal("100.56"),
                new BigDecimal("59.60"), new BigDecimal("58.56"), extraTotal);
        return new PricedOrder(lines, totals, new PricedOrder.Validation(new BigDecimal("600"), true, zero,
                !lines.isEmpty(), true, List.of(), List.of(), List.of(), null), extras);
    }

    static OrderFixture order() {
        return new OrderFixture();
    }

    /** A website concept as the intake stores it; every test names only what it changes. */
    static final class OrderFixture {
        private Long id = 4711L;
        private Long customerId = 2L;
        private QuoteStatus status = QuoteStatus.CONCEPT;
        private String incoterm = "DAP";
        private String notes;
        private String portalToken;
        private Instant sentAt;
        private FreightState freight = FreightState.BEREKEND;
        private DocumentType docType = DocumentType.OFFERTE;
        private Long sourceQuoteId;
        private Instant archivedAt;
        private Long partnerPurchaseOrderId;
        private String salesChannel = "WEBSITE";
        private SalesPurpose purpose;
        private Long creditedInvoiceId;

        OrderFixture id(Long value) { id = value; return this; }
        OrderFixture customerId(Long value) { customerId = value; return this; }
        OrderFixture status(QuoteStatus value) { status = value; return this; }
        OrderFixture incoterm(String value) { incoterm = value; return this; }
        OrderFixture notes(String value) { notes = value; return this; }
        OrderFixture portalToken(String value) { portalToken = value; return this; }
        OrderFixture sentAt(Instant value) { sentAt = value; return this; }
        OrderFixture freight(FreightState value) { freight = value; return this; }
        OrderFixture docType(DocumentType value) { docType = value; return this; }
        OrderFixture sourceQuoteId(Long value) { sourceQuoteId = value; return this; }
        OrderFixture archivedAt(Instant value) { archivedAt = value; return this; }
        OrderFixture partnerPurchaseOrderId(Long value) { partnerPurchaseOrderId = value; return this; }
        OrderFixture salesChannel(String value) { salesChannel = value; return this; }
        OrderFixture purpose(SalesPurpose value) { purpose = value; return this; }
        OrderFixture creditedInvoiceId(Long value) { creditedInvoiceId = value; return this; }

        SalesOrder build() {
            LocalDate today = LocalDate.of(2026, 10, 8);
            return new SalesOrder(id, "OF-2026-0123", customerId, "BE", today, today.plusDays(30), status, incoterm,
                    null, notes, MarkupMode.PRODUCT, BigDecimal.ZERO, null, null, portalToken, sentAt, null, 0, null,
                    null, null, "[WEBSITE_AANVRAAG] OF-2026-0123", DeliveryTermsState.VOLLEDIG, freight, null,
                    LoadMode.PALLETS, PalletProfile.EURO_120X80, null, FreightPricingStrategy.COUNTRY_PALLET, null,
                    null, null, docType, null, null, sourceQuoteId, null, List.of(), List.of(), null, archivedAt,
                    List.of(), partnerPurchaseOrderId, null, false, salesChannel, purpose, null, null,
                    creditedInvoiceId, null, null);
        }
    }

    private static List<String> names(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }
}
