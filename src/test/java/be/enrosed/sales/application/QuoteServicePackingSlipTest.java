package be.enrosed.sales.application;

import be.enrosed.catalog.application.ProductService;
import be.enrosed.catalog.domain.Barcodes;
import be.enrosed.catalog.domain.Carton;
import be.enrosed.catalog.domain.Dimensions;
import be.enrosed.catalog.domain.OrderStep;
import be.enrosed.catalog.domain.Packaging;
import be.enrosed.catalog.domain.PackagingKind;
import be.enrosed.catalog.domain.Product;
import be.enrosed.catalog.domain.PublicationState;
import be.enrosed.sales.application.port.out.QuoteDocumentRenderer;
import be.enrosed.sales.application.port.out.QuoteMailer;
import be.enrosed.sales.application.port.out.SalesPdfOptions;
import be.enrosed.sales.application.port.out.SalesRepositories;
import be.enrosed.sales.domain.DeliveryTermsState;
import be.enrosed.sales.domain.DocumentType;
import be.enrosed.sales.domain.FreightPricingStrategy;
import be.enrosed.sales.domain.FreightState;
import be.enrosed.sales.domain.LoadMode;
import be.enrosed.sales.domain.MarkupMode;
import be.enrosed.sales.domain.PalletProfile;
import be.enrosed.sales.domain.QuoteStatus;
import be.enrosed.sales.domain.SalesOrder;
import be.enrosed.sales.domain.SalesOrderLine;
import be.enrosed.shared.Currency;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class QuoteServicePackingSlipTest {

    @Test
    void packingSlipUsesPresentationPackagingBarcodeWhenProductBarcodeIsEmpty() {
        SalesOrderService salesOrders = mock(SalesOrderService.class);
        ProductService products = mock(ProductService.class);
        QuoteDocumentRenderer renderer = mock(QuoteDocumentRenderer.class);
        when(salesOrders.get(42L)).thenReturn(order());
        when(products.get(7L)).thenReturn(productWithPackagingBarcode());

        QuoteService service = new QuoteService(
                mock(SalesRepositories.Orders.class),
                mock(SalesRepositories.Revisions.class),
                salesOrders,
                mock(CustomerService.class),
                renderer,
                mock(QuoteMailer.class),
                products,
                mock(SalesRepositories.Events.class),
                mock(be.enrosed.shared.company.CompanyProfileService.class),
                mock(be.enrosed.push.WebPushNotifier.class));

        SalesPdfOptions options = SalesPdfOptions.forPackingSlip(false, true);
        service.packingSlip(42L, options);

        ArgumentCaptor<QuoteDocumentRenderer.PackingSlip> slip =
                ArgumentCaptor.forClass(QuoteDocumentRenderer.PackingSlip.class);
        verify(renderer).packingSlip(slip.capture(), eq(options));
        assertEquals("6153400586590", slip.getValue().loose().getFirst().barcode());
    }

    @Test
    void packingSlipCountsEachLineInItsOwnUnitAndOnlyAddsUpComparableLines() {
        SalesOrderService salesOrders = mock(SalesOrderService.class);
        ProductService products = mock(ProductService.class);
        QuoteDocumentRenderer renderer = mock(QuoteDocumentRenderer.class);
        Product bowl = productWithPackagingBarcode();
        Product bowlNamed = new Product(bowl.id(), bowl.sku(), bowl.name(), bowl.dimensions(),
                bowl.packaging().withUnitKey("bowl"), bowl.colour(), bowl.variantSize(), bowl.colourHex(),
                bowl.description(), bowl.categoryId(), bowl.supplierId(), bowl.supplierNote(), bowl.active(),
                bowl.familyId(), bowl.canonicalVariantKey(), bowl.canonicalBarcode(), bowl.variantPosition(),
                bowl.inventoryKnown(), bowl.familyKey(), bowl.publicHandle(), bowl.websiteStatus(),
                bowl.orderAppStatus(), bowl.barcodes(), bowl.hsCode(), bowl.carton(), bowl.exwPrice(),
                bowl.exwCurrency(), bowl.extraUnitCost(), bowl.landedCostEur(), bowl.landedCostSource(),
                bowl.markupPct(), bowl.fixedSalesPriceEur(), bowl.stockQuantity(), bowl.photos(), bowl.texts(),
                bowl.demo());
        when(salesOrders.get(42L)).thenReturn(order());
        when(products.get(7L)).thenReturn(bowlNamed);
        QuoteService service = service(salesOrders, products, renderer);

        SalesPdfOptions options = SalesPdfOptions.forPackingSlip(true, false);
        service.packingSlip(42L, options);

        ArgumentCaptor<QuoteDocumentRenderer.PackingSlip> slip =
                ArgumentCaptor.forClass(QuoteDocumentRenderer.PackingSlip.class);
        verify(renderer).packingSlip(slip.capture(), eq(options));
        QuoteDocumentRenderer.PackingItem item = slip.getValue().loose().getFirst();
        assertEquals("40 bowls", item.quantityText());
        assertEquals("40 bowls per doos", item.cartonContentsText());
        assertEquals("40 bowls", slip.getValue().totalQuantityText());

        /* A second line in plain pieces: 40 bowls and 40 pieces are not 80 of anything. */
        when(salesOrders.get(43L)).thenReturn(order(43L, List.of(
                new SalesOrderLine(1L, 7L, 40, BigDecimal.TEN, null, null),
                new SalesOrderLine(2L, 8L, 40, BigDecimal.TEN, null, null))));
        when(products.get(8L)).thenReturn(productWithPackagingBarcode());
        service.packingSlip(43L, options);
        verify(renderer, org.mockito.Mockito.times(2)).packingSlip(slip.capture(), eq(options));
        assertEquals("40 stuks", slip.getValue().loose().getLast().quantityText());
        assertEquals(null, slip.getValue().totalQuantityText());
        assertEquals(2, slip.getValue().totalCartons());
    }

    @Test
    void picksWhatEachLineIsChargedSoAPartCartonIsNeverPickedFull() {
        SalesOrderService salesOrders = mock(SalesOrderService.class);
        ProductService products = mock(ProductService.class);
        QuoteDocumentRenderer renderer = mock(QuoteDocumentRenderer.class);
        Product stolp = stolpen(9L, Packaging.none().withOrderStep(OrderStep.UNIT).withUnitKey("stolp"));
        Product roses = stolpen(10L, Packaging.none());
        when(products.get(9L)).thenReturn(stolp);
        when(products.get(10L)).thenReturn(roses);
        /* Three stolpen per piece, and thirteen roses per carton of twelve, charged as twenty-four. */
        when(salesOrders.get(44L)).thenReturn(order(44L, List.of(
                new SalesOrderLine(1L, 9L, 3, BigDecimal.TEN, null, null),
                new SalesOrderLine(2L, 10L, 13, BigDecimal.TEN, null, null))));
        QuoteService service = service(salesOrders, products, renderer);
        SalesPdfOptions options = SalesPdfOptions.forPackingSlip(false, false);

        service.packingSlip(44L, options);

        ArgumentCaptor<QuoteDocumentRenderer.PackingSlip> slip =
                ArgumentCaptor.forClass(QuoteDocumentRenderer.PackingSlip.class);
        verify(renderer).packingSlip(slip.capture(), eq(options));
        assertEquals(1, slip.getValue().loose().get(0).cartons(), "one opened carton");
        assertEquals(3, slip.getValue().loose().get(0).pieces(), "three stolpen picked, not twelve");
        assertEquals("3 stolpen", slip.getValue().loose().get(0).quantityText());
        assertEquals(2, slip.getValue().loose().get(1).cartons());
        assertEquals(24, slip.getValue().loose().get(1).pieces(), "what the invoice charges");
        assertEquals(3, slip.getValue().totalCartons());
        assertEquals(27, slip.getValue().totalPieces(), "the footer adds up the rows");

        /* A container-cost invoice keeps its thirteen exact roses: the last carton part-filled. */
        SalesOrder atCost = withMarkup(order(46L, List.of(new SalesOrderLine(1L, 10L, 13, BigDecimal.TEN, null, null))),
                MarkupMode.CONTAINER_COST);
        when(salesOrders.get(46L)).thenReturn(atCost);
        when(salesOrders.keepsExactQuantities(atCost)).thenReturn(true);
        service.packingSlip(46L, options);
        verify(renderer, org.mockito.Mockito.times(2)).packingSlip(slip.capture(), eq(options));
        assertEquals(2, slip.getValue().loose().getFirst().cartons());
        assertEquals(13, slip.getValue().loose().getFirst().pieces());
        assertEquals(13, slip.getValue().totalPieces());

        /* A container quote at customer price keeps the container's 1003 pieces until a save, and
           is charged for 84 full cartons; the slip picks the same 1008. */
        when(salesOrders.get(47L)).thenReturn(order(47L, List.of(
                new SalesOrderLine(1L, 10L, 1003, BigDecimal.TEN, null, null, null, null, null, 12))));
        service.packingSlip(47L, options);
        verify(renderer, org.mockito.Mockito.times(3)).packingSlip(slip.capture(), eq(options));
        assertEquals(84, slip.getValue().loose().getFirst().cartons());
        assertEquals(1008, slip.getValue().loose().getFirst().pieces());
        assertEquals(1008, slip.getValue().totalPieces());

        /* On a hand-built pallet the carton still holds only what the line ordered. */
        SalesOrder onPallet = order(45L, List.of(new SalesOrderLine(1L, 9L, 3, BigDecimal.TEN, null, null)));
        onPallet = new SalesOrder(onPallet.id(), onPallet.number(), null, "BE", onPallet.orderDate(),
                onPallet.validUntil(), QuoteStatus.CONCEPT, "DDP", null, null, MarkupMode.PRODUCT, BigDecimal.ZERO,
                null, null, null, null, null, 0, null, null, null, null,
                DeliveryTermsState.VOLLEDIG, FreightState.BEREKEND, null,
                LoadMode.PALLETS, PalletProfile.EURO_120X80, null,
                FreightPricingStrategy.COUNTRY_PALLET, null, null, null,
                DocumentType.FACTUUR, null, null, null, null, onPallet.lines(),
                List.of(new be.enrosed.sales.domain.OrderPallet(null, "Pallet 1", "Europallet", 120,
                        List.of(new be.enrosed.sales.domain.OrderPallet.Item(9L, 1)))));
        when(salesOrders.get(45L)).thenReturn(onPallet);
        service.packingSlip(45L, options);
        verify(renderer, org.mockito.Mockito.times(4)).packingSlip(slip.capture(), eq(options));
        assertEquals(3, slip.getValue().pallets().getFirst().items().getFirst().pieces());
        assertEquals(List.of(), slip.getValue().loose());
        assertEquals(3, slip.getValue().totalPieces());
    }

    private static Product stolpen(long id, Packaging packaging) {
        return new Product(
                id, "ENR-STOLP-" + id, "Stolp", Dimensions.empty(), packaging,
                "Rood", null, null, null, 1L, 1L, true,
                null, null, null, 0, true, null, null,
                PublicationState.DRAFT, PublicationState.DRAFT,
                Barcodes.none(), null,
                new Carton(new Dimensions(BigDecimal.valueOf(40), BigDecimal.valueOf(30),
                        BigDecimal.valueOf(20)), 12, BigDecimal.ONE),
                BigDecimal.ONE, Currency.USD, BigDecimal.ZERO,
                BigDecimal.ONE, null, BigDecimal.ZERO, BigDecimal.TEN, 100,
                List.of(), List.of());
    }

    private static QuoteService service(SalesOrderService salesOrders, ProductService products,
                                        QuoteDocumentRenderer renderer) {
        return new QuoteService(
                mock(SalesRepositories.Orders.class),
                mock(SalesRepositories.Revisions.class),
                salesOrders,
                mock(CustomerService.class),
                renderer,
                mock(QuoteMailer.class),
                products,
                mock(SalesRepositories.Events.class),
                mock(be.enrosed.shared.company.CompanyProfileService.class),
                mock(be.enrosed.push.WebPushNotifier.class));
    }

    private static SalesOrder order() {
        return order(42L, List.of(new SalesOrderLine(1L, 7L, 40, BigDecimal.TEN, null, null)));
    }

    private static SalesOrder order(long id, List<SalesOrderLine> lines) {
        LocalDate today = LocalDate.now();
        return new SalesOrder(id, "F-2026-00" + id, null, "BE", today, today.plusDays(30),
                QuoteStatus.CONCEPT, "DDP", null, null, MarkupMode.PRODUCT, BigDecimal.ZERO,
                null, null, null, null, null, 0, null, null, null, null,
                DeliveryTermsState.VOLLEDIG, FreightState.BEREKEND, null,
                LoadMode.LOOSE_CARTONS, PalletProfile.EURO_120X80, null,
                FreightPricingStrategy.COUNTRY_PALLET, null, null, null,
                DocumentType.FACTUUR, null, null, null, null,
                lines,
                List.of());
    }

    private static SalesOrder withMarkup(SalesOrder order, MarkupMode markup) {
        return new SalesOrder(order.id(), order.number(), null, "BE", order.orderDate(),
                order.validUntil(), QuoteStatus.CONCEPT, "DDP", null, null, markup, BigDecimal.ZERO,
                null, null, null, null, null, 0, null, null, null, null,
                DeliveryTermsState.VOLLEDIG, FreightState.BEREKEND, null,
                LoadMode.LOOSE_CARTONS, PalletProfile.EURO_120X80, null,
                FreightPricingStrategy.COUNTRY_PALLET, null, null, null,
                DocumentType.FACTUUR, null, null, null, null, order.lines(), List.of());
    }

    private static Product productWithPackagingBarcode() {
        return new Product(
                7L, "ENR-BOWL-WHITE", "Bowl rozen met Display", Dimensions.empty(),
                new Packaging(PackagingKind.DISPLAY, Dimensions.empty(), "6153400586590", 8),
                "White", null, null, null, 1L, 1L, true,
                null, null, null, 0, true, null, null,
                PublicationState.DRAFT, PublicationState.DRAFT,
                Barcodes.none(), null,
                new Carton(new Dimensions(BigDecimal.valueOf(36), BigDecimal.valueOf(39),
                        BigDecimal.valueOf(25)), 40, BigDecimal.ONE),
                BigDecimal.ONE, Currency.USD, BigDecimal.ZERO,
                BigDecimal.ONE, null, BigDecimal.ZERO, BigDecimal.TEN, 100,
                List.of(), List.of());
    }
}
