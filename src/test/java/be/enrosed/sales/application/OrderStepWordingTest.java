package be.enrosed.sales.application;

import be.enrosed.catalog.application.ProductService;
import be.enrosed.catalog.domain.Barcodes;
import be.enrosed.catalog.domain.Carton;
import be.enrosed.catalog.domain.Dimensions;
import be.enrosed.catalog.domain.OrderStep;
import be.enrosed.catalog.domain.Packaging;
import be.enrosed.catalog.domain.PackagingKind;
import be.enrosed.catalog.domain.Product;
import be.enrosed.catalog.domain.SalesUnit;
import be.enrosed.sales.adapter.in.rest.AccountOrderDtos.DetailLine;
import be.enrosed.sales.domain.*;
import be.enrosed.shared.Currency;
import be.enrosed.shared.Language;
import be.enrosed.shared.mail.InternalMessageSender;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static be.enrosed.sales.application.WebOrderTermsTest.line;
import static be.enrosed.sales.application.WebOrderTermsTest.order;
import static be.enrosed.sales.application.WebOrderTermsTest.priced;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** How a line sold per verpakking or per stuk reads once frozen, summarised and shown to its customer. */
class OrderStepWordingTest {

    private static final Packaging STOLPEN = Packaging.none().withOrderStep(OrderStep.UNIT).withUnitKey("stolp");
    private static final Packaging BOWLS = new Packaging(PackagingKind.DISPLAY, Dimensions.empty(), null, 8,
            SalesUnit.PIECE, "bowl", OrderStep.PACKAGE);

    @Test
    void aFrozenLineKeepsItsStepAndUnitAndNeverFloorsItsCartons() {
        PricedOrder.Line stolpen = stepped(line(12, "ER-DOME", 3, "8.95", "0", "26.85"), OrderStep.UNIT, 1);
        PricedOrder.Line bowls = stepped(line(13, "ER-BOWL", 16, "3.95", "0", "63.20"), OrderStep.PACKAGE, 8);
        PricedOrder.Line exactPieces = stepped(line(14, "ER-RED", 45, "1.00", "0", "45.00"), OrderStep.CARTON, 1);
        PricedOrder.Line noCarton = stepped(line(15, "ER-HEART", 2, "5.00", "0", "10.00"), OrderStep.UNIT, 1);

        WebOrderSnapshot snapshot = WebOrderSnapshot.of(order().incoterm("EXW").build(),
                priced(List.of(stolpen, bowls, exactPieces, noCarton), "0", "21", List.of()), 1, Instant.now(), "NL",
                "PICKUP", Map.of(12L, 12, 13L, 40, 14L, 40), Map.of(),
                Map.of(12L, STOLPEN, 13L, BOWLS, 15L, Packaging.none().withOrderStep(OrderStep.UNIT).withUnitKey("hart")));

        WebOrderSnapshot.Line perPiece = snapshot.lines().get(0);
        assertNull(perPiece.cartons(), "three out of a carton of twelve is no number of cartons");
        assertEquals(12, perPiece.piecesPerCarton());
        assertEquals("UNIT", perPiece.orderStep());
        assertEquals(1, perPiece.unitsPerOrderStep());
        assertEquals("stolp", perPiece.unitKey());
        assertEquals("PIECE", perPiece.salesUnit());
        assertEquals("3 stolpen", perPiece.quantityText(Language.NL));
        assertFalse(perPiece.perCarton());
        assertEquals("PACKAGE", snapshot.lines().get(1).orderStep());
        assertEquals(8, snapshot.lines().get(1).unitsPerOrderStep());
        assertNull(snapshot.lines().get(1).cartons());
        assertEquals(2, snapshot.lines().get(2).cartons(), "45 out of cartons of 40 ships in two, never one");
        assertNull(snapshot.lines().get(3).piecesPerCarton());
        assertTrue(snapshot.complete(), "a line per stuk has a known quantity without its carton content");
        assertEquals(snapshot, WebOrderSnapshot.fromJson(snapshot.toJson()));
        assertFalse(snapshot.toJson().contains("perCarton"), "a reading aid, not a frozen figure");

        WebOrderSnapshot old = WebOrderSnapshot.fromJson(snapshot.toJson()
                .replace("\"orderStep\":\"UNIT\",\"unitsPerOrderStep\":1,\"unitKey\":\"stolp\",\"salesUnit\":\"PIECE\"",
                        "\"later\":\"ANYTHING\""));
        assertTrue(old.lines().getFirst().perCarton(), "an order frozen before steps reads per carton");
        assertEquals("3 stuks", old.lines().getFirst().quantityText(Language.NL));
        WebOrderSnapshot unknown = WebOrderSnapshot.fromJson(snapshot.toJson().replace("\"UNIT\"", "\"LATER\""));
        assertTrue(unknown.lines().getFirst().perCarton(), "a step this code does not know never blanks the order");
    }

    @Test
    void aChangeOfALineSoldPerPieceIsSummarisedInItsOwnUnit() {
        Map<Long, Integer> pieces = Map.of(12L, 12, 13L, 12, 14L, 24);
        Map<Long, Packaging> packagings = Map.of(12L, STOLPEN, 13L, STOLPEN, 14L, Packaging.none());
        WebOrderSnapshot before = WebOrderSnapshot.of(order().build(), priced(List.of(
                        stepped(line(12, "ER-DOME", 3, "8.95", "0", "26.85"), OrderStep.UNIT, 1),
                        line(14, "ER-RED", 144, "1.85", "0", "266.40")), "42.00", "21", List.of()),
                1, Instant.now(), "NL", "DELIVERY", pieces, Map.of(), packagings);
        WebOrderSnapshot after = WebOrderSnapshot.of(order().build(), priced(List.of(
                        stepped(line(12, "ER-DOME", 5, "8.95", "0", "44.75"), OrderStep.UNIT, 1),
                        stepped(line(13, "ER-DOME-2", 3, "8.95", "0", "26.85"), OrderStep.UNIT, 1),
                        line(14, "ER-RED", 192, "1.85", "0", "355.20")), "42.00", "21", List.of()),
                2, Instant.now(), "NL", "DELIVERY", pieces, Map.of(), packagings);
        WebOrderDeliveries.Delivery delivery = new WebOrderDeliveries.Delivery(1L, "DELIVERY", "Street 1", "3500",
                "Hasselt", null, null, null, "Mia", null, null);
        PublicQuoteService.OrderFacts facts = new PublicQuoteService.OrderFacts("NL", "DELIVERY", "Street 1", "3500",
                "Hasselt", null, null, null, "Mia", null, pieces, Map.of(), Map.of(), Map.of(), packagings);

        assertEquals("ER-DOME: 3 → 5 stolpen, ER-DOME-2 toegevoegd (3 stolpen), ER-RED: 6 → 8 dozen",
                WebOrderService.summary(before, after, delivery, delivery, facts, Map.of()));

        /* The product went from per carton to per stolp between the two versions: units, never "1 → ? dozen". */
        WebOrderSnapshot cartonBefore = WebOrderSnapshot.of(order().build(), priced(List.of(
                        line(12, "ER-DOME", 12, "8.95", "0", "107.40")), "42.00", "21", List.of()),
                1, Instant.now(), "NL", "DELIVERY", pieces, Map.of(), Map.of());
        WebOrderSnapshot unitAfter = WebOrderSnapshot.of(order().build(), priced(List.of(
                        stepped(line(12, "ER-DOME", 4, "8.95", "0", "35.80"), OrderStep.UNIT, 1)), "42.00", "21", List.of()),
                2, Instant.now(), "NL", "DELIVERY", pieces, Map.of(), packagings);
        assertEquals("ER-DOME: 12 → 4 stolpen",
                WebOrderService.summary(cartonBefore, unitAfter, delivery, delivery, facts, Map.of()));
    }

    @Test
    void anAccountLineTakesItsCartonContentFromTheProductNeverFromQuantityOverCartons() {
        Product stolp = product(STOLPEN, 12);
        DetailLine perPiece = AccountDocuments.currentLine(
                stepped(line(12, "ER-DOME", 3, "8.95", "0", "26.85"), OrderStep.UNIT, 1), stolp, Language.FR);
        assertNull(perPiece.cartons(), "not \"1 doos × 3\"");
        assertEquals(12, perPiece.piecesPerCarton());
        assertEquals(3, perPiece.quantity());
        assertEquals("UNIT", perPiece.orderStep());
        assertEquals("PIECE", perPiece.salesUnit());
        assertEquals("stolp", perPiece.unit().key());
        assertEquals("par cloche", perPiece.unit().per(), "in the customer's language");

        DetailLine fullCartons = AccountDocuments.currentLine(line(14, "ER-RED", 240, "1.85", "0", "444.00"),
                product(Packaging.none(), 12), Language.NL);
        assertEquals(20, fullCartons.cartons());
        assertEquals(12, fullCartons.piecesPerCarton());
        assertEquals("CARTON", fullCartons.orderStep());

        DetailLine exactPieces = AccountDocuments.currentLine(line(14, "ER-RED", 500, "1.85", "0", "925.00"),
                product(Packaging.none(), 6), Language.NL);
        assertEquals(84, exactPieces.cartons(), "per carton, a part carton ships as one");

        DetailLine bowls = AccountDocuments.currentLine(stepped(line(13, "ER-BOWL", 80, "3.95", "0", "316.00"),
                OrderStep.PACKAGE, 8), product(BOWLS, 40), Language.NL);
        assertEquals(2, bowls.cartons(), "whole cartons whatever the step");

        DetailLine gone = AccountDocuments.currentLine(line(16, "GONE", 3, "1.00", "0", "3.00"), null, Language.NL);
        assertNull(gone.cartons());
        assertNull(gone.piecesPerCarton());
        assertEquals("stuk", gone.unit().key());
    }

    @Test
    void theTeamMailCountsALineSoldPerPieceInItsUnitAndNamesTheCartonItComesOutOf() {
        ProductService products = mock(ProductService.class);
        when(products.get(1L)).thenReturn(product(STOLPEN, 12));
        when(products.get(2L)).thenReturn(product(Packaging.none(), 40));
        WebsiteQuoteMailNotifier notifier = new WebsiteQuoteMailNotifier(mock(SalesOrderService.class),
                mock(CustomerService.class), products, mock(InternalMessageSender.class), "https://erp.example.test");
        LocalDate today = LocalDate.now();
        SalesOrder order = new SalesOrder(61L, "ENR-2026-0061", 9L, "BE", today, today.plusDays(30),
                QuoteStatus.CONCEPT, "DAP", null, null, MarkupMode.PRODUCT, new BigDecimal("45"),
                null, null, null, null, null, 0, null, null, null, null,
                DeliveryTermsState.VOLLEDIG, FreightState.BEREKEND, null,
                LoadMode.PALLETS, PalletProfile.EURO_120X80, null,
                FreightPricingStrategy.COUNTRY_PALLET, null, null, null,
                DocumentType.OFFERTE, null, null, null, null,
                List.of(new SalesOrderLine(1L, 1L, 3, null, null, null), new SalesOrderLine(2L, 2L, 40, null, null, null)),
                List.of());

        List<InternalMessageSender.TeamLine> lines = notifier.notice(order, null).lines();

        assertEquals("3 stolpen", lines.get(0).quantity());
        assertEquals("SKU · uit omdoos van 12", lines.get(0).note(), "never \"1 doos van 12\" for three pieces");
        assertEquals("40 st", lines.get(1).quantity(), "a line per carton reads as it always did");
        assertEquals("SKU · 1 doos van 40", lines.get(1).note());
    }

    private static Product product(Packaging packaging, int perCarton) {
        return new Product(1L, "SKU", "Name", Dimensions.empty(), packaging, null, null, null, null,
                1L, 1L, true, null, null, null, 0, true, null, null, null, null,
                Barcodes.none(), null, new Carton(new Dimensions(new BigDecimal("40"), new BigDecimal("30"),
                new BigDecimal("20")), perCarton, new BigDecimal("5")), BigDecimal.ZERO, Currency.USD,
                BigDecimal.ZERO, BigDecimal.ONE, "test", new BigDecimal("45"), null, 0, List.of(), List.of());
    }

    static PricedOrder.Line stepped(PricedOrder.Line l, OrderStep step, int units) {
        return new PricedOrder.Line(l.productId(), l.sku(), l.description(), l.customerDescription(), l.photoUrl(),
                l.quantity(), l.cartons(), l.cartonsPerPallet(), l.pallets(), l.cartonsPerLayer(), l.palletLayers(),
                l.calculatedPalletHeightCm(), l.cbm(), l.weightKg(), l.unitPrice(), l.gross(), l.tierPercent(),
                l.manualPercent(), l.discountPct(), l.discountAmount(), l.net(), l.netUnitPrice(), l.landedUnitCost(),
                l.costTotal(), l.marginEur(), l.marginPct(), l.nextTierAtQuantity(), l.nextTierPercent(),
                l.stockQuantity(), l.inventoryKnown(), l.inStock(), l.shortfall(), l.deliveryDate(), l.deliveryWeek(),
                l.deliveryExplanation(), l.unavailable(), l.requestedQuantity(), step, units);
    }
}
