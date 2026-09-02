package be.enrosed.sourcing.adapter.out.document;

import be.enrosed.catalog.domain.Barcodes;
import be.enrosed.catalog.domain.Carton;
import be.enrosed.catalog.domain.Dimensions;
import be.enrosed.catalog.domain.Product;
import be.enrosed.shared.Currency;
import be.enrosed.sourcing.domain.PurchaseOrderLine;
import jakarta.ws.rs.BadRequestException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PdfPurchaseRendererTest {

    @Test
    void missingProductMasterNeverInventsCartonDetails() {
        assertTrue(PdfPurchaseRenderer.productSpecs(null).isEmpty());
        assertNull(PdfPurchaseRenderer.piecesPerCarton(null));
    }

    @Test
    void pdfCanDistinguishUnifiedAndHistoricalRates() {
        assertTrue(PdfPurchaseRenderer.sameRate(order("0.90", "0.9000")));
        assertFalse(PdfPurchaseRenderer.sameRate(order("0.81", "0.93")));
    }

    @Test
    void layoutKeepsLandscapeAsBackwardCompatibleDefault() {
        assertEquals(PdfPurchaseRenderer.Layout.LANDSCAPE,
                PdfPurchaseRenderer.Layout.parse(null));
        assertEquals(PdfPurchaseRenderer.Layout.LANDSCAPE,
                PdfPurchaseRenderer.Layout.parse("  "));
        assertEquals(PdfPurchaseRenderer.Layout.PORTRAIT,
                PdfPurchaseRenderer.Layout.parse("portrait"));
        assertThrows(BadRequestException.class,
                () -> PdfPurchaseRenderer.Layout.parse("square"));
    }

    @Test
    void audienceKeepsStandardAsBackwardCompatibleDefaultAndValidatesSupplierPaper() {
        assertEquals(PdfPurchaseRenderer.Audience.STANDARD,
                PdfPurchaseRenderer.Audience.parse(null));
        assertEquals(PdfPurchaseRenderer.Audience.STANDARD,
                PdfPurchaseRenderer.Audience.parse("  "));
        assertEquals(PdfPurchaseRenderer.Audience.INTERNAL,
                PdfPurchaseRenderer.Audience.parse("internal"));
        assertEquals(PdfPurchaseRenderer.Audience.SUPPLIER,
                PdfPurchaseRenderer.Audience.parse(" supplier "));
        assertThrows(BadRequestException.class,
                () -> PdfPurchaseRenderer.Audience.parse("customer"));
        assertThrows(BadRequestException.class,
                () -> PdfPurchaseRenderer.Audience.SUPPLIER.validate(
                        PdfPurchaseRenderer.Layout.LANDSCAPE));
        PdfPurchaseRenderer.Audience.SUPPLIER.validate(PdfPurchaseRenderer.Layout.PORTRAIT);
    }

    @Test
    void portraitOptionsOnlyApplyToTheStandardPortraitContract() {
        var requested = new PdfPurchaseRenderer.PdfOptions(false, false, true, true, true);

        assertEquals(PdfPurchaseRenderer.PdfOptions.defaults(), requested.normalized(
                PdfPurchaseRenderer.Layout.LANDSCAPE, PdfPurchaseRenderer.Audience.STANDARD));
        assertEquals(PdfPurchaseRenderer.PdfOptions.defaults(), requested.normalized(
                PdfPurchaseRenderer.Layout.PORTRAIT, PdfPurchaseRenderer.Audience.SUPPLIER));
        assertEquals(new PdfPurchaseRenderer.PdfOptions(false, false, false, false, false),
                requested.normalized(PdfPurchaseRenderer.Layout.PORTRAIT,
                        PdfPurchaseRenderer.Audience.STANDARD));
    }

    @Test
    void combinedCostCanStandAloneAndSuppressesLegacyFreightFlags() {
        var requested = new PdfPurchaseRenderer.PdfOptions(
                false, false, true, true, true, true);

        assertEquals(new PdfPurchaseRenderer.PdfOptions(
                        false, false, false, false, false, true),
                requested.normalized(PdfPurchaseRenderer.Layout.PORTRAIT,
                        PdfPurchaseRenderer.Audience.STANDARD));
        assertEquals(PdfPurchaseRenderer.PdfOptions.defaults(),
                requested.normalized(PdfPurchaseRenderer.Layout.LANDSCAPE,
                        PdfPurchaseRenderer.Audience.STANDARD));
        assertEquals(PdfPurchaseRenderer.PdfOptions.defaults(),
                requested.normalized(PdfPurchaseRenderer.Layout.PORTRAIT,
                        PdfPurchaseRenderer.Audience.SUPPLIER));
    }

    @Test
    void eurOnlyWinsFromTheSubtleEquivalentAndStaysInStandardPortrait() {
        var requested = new PdfPurchaseRenderer.PdfOptions(
                true, true, true, true, true, true, false);

        assertEquals(new PdfPurchaseRenderer.PdfOptions(
                        true, true, false, true, false, false, false),
                requested.normalized(PdfPurchaseRenderer.Layout.PORTRAIT,
                        PdfPurchaseRenderer.Audience.STANDARD));
        assertEquals(PdfPurchaseRenderer.PdfOptions.defaults(),
                requested.normalized(PdfPurchaseRenderer.Layout.LANDSCAPE,
                        PdfPurchaseRenderer.Audience.STANDARD));
        assertEquals(PdfPurchaseRenderer.PdfOptions.defaults(),
                requested.normalized(PdfPurchaseRenderer.Layout.PORTRAIT,
                        PdfPurchaseRenderer.Audience.SUPPLIER));

        var hiddenPrices = new PdfPurchaseRenderer.PdfOptions(
                true, false, false, true, false, false, false);
        assertFalse(hiddenPrices.normalized(PdfPurchaseRenderer.Layout.PORTRAIT,
                PdfPurchaseRenderer.Audience.STANDARD).eurOnly());
    }

    @Test
    void supplierEanPrefersCanonicalButFallsBackToEditablePieceBarcode() {
        Product legacy = new Product(
                1L, "SKU-EAN", "EAN product", Dimensions.empty(), null, null,
                null, null, true, new Barcodes(" 5410000000019 ", "15410000000016"),
                null, Carton.empty(), BigDecimal.ONE, Currency.USD, BigDecimal.ZERO,
                null, null, BigDecimal.ZERO, null, 0, List.of(), List.of());

        assertEquals("5410000000019", PdfPurchaseRenderer.ean(legacy));
        assertEquals("8710000000010", PdfPurchaseRenderer.ean(
                legacy.withCanonicalIdentity(null, null, " 8710000000010 ", 0, true)));
    }

    @Test
    void agreedUnitPriceTreatsAmountAndCurrencyAsOnePair() {
        Product product = new Product(
                1L, "SKU-PRICE", "Price product", Dimensions.empty(), null, null,
                null, null, true, Barcodes.none(), null, Carton.empty(),
                new BigDecimal("10"), Currency.USD, BigDecimal.ZERO,
                null, null, BigDecimal.ZERO, null, 0, List.of(), List.of());

        var inherited = PdfPurchaseRenderer.agreedUnitPrice(
                new PurchaseOrderLine(1L, 1L, 6, null, null, BigDecimal.ZERO, null),
                product);
        assertEquals(new BigDecimal("10"), inherited.amount());
        assertEquals(Currency.USD, inherited.currency());

        var explicit = PdfPurchaseRenderer.agreedUnitPrice(
                new PurchaseOrderLine(1L, 1L, 6, new BigDecimal("12"), Currency.EUR,
                        BigDecimal.ZERO, null), product);
        assertEquals(new BigDecimal("12"), explicit.amount());
        assertEquals(Currency.EUR, explicit.currency());

        var currencyOnly = PdfPurchaseRenderer.agreedUnitPrice(
                new PurchaseOrderLine(1L, 1L, 6, null, Currency.EUR,
                        BigDecimal.ZERO, null), product);
        assertFalse(currencyOnly.available(),
                "a line currency must never be combined with the product amount");
        assertNull(currencyOnly.amount());
        assertNull(currencyOnly.currency());

        var amountOnly = PdfPurchaseRenderer.agreedUnitPrice(
                new PurchaseOrderLine(1L, 1L, 6, new BigDecimal("12"), null,
                        BigDecimal.ZERO, null), product);
        assertFalse(amountOnly.available(),
                "a line amount must never be combined with the product currency");
        assertNull(amountOnly.amount());
        assertNull(amountOnly.currency());
    }

    private static be.enrosed.sourcing.domain.PurchaseOrder order(String goods, String transport) {
        return new be.enrosed.sourcing.domain.PurchaseOrder(
                1L, "PO-PDF", null, 1L, LocalDate.now(),
                be.enrosed.sourcing.domain.PurchaseOrderStatus.CONCEPT,
                be.enrosed.sourcing.domain.ContainerType.FORTY_HQ,
                new BigDecimal("0.14"), new BigDecimal(goods), new BigDecimal(transport),
                BigDecimal.ZERO, BigDecimal.ZERO, Currency.USD, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO,
                be.enrosed.sourcing.domain.Allocation.CBM,
                be.enrosed.sourcing.domain.Allocation.CBM,
                be.enrosed.sourcing.domain.Allocation.CBM,
                be.enrosed.sourcing.domain.Allocation.PIECES,
                "Ningbo", "Rotterdam", null, List.of());
    }
}
