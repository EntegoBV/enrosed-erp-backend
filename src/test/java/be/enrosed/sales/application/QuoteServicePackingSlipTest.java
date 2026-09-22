package be.enrosed.sales.application;

import be.enrosed.catalog.application.ProductService;
import be.enrosed.catalog.domain.Barcodes;
import be.enrosed.catalog.domain.Carton;
import be.enrosed.catalog.domain.Dimensions;
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
