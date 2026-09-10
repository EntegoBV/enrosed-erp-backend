package be.enrosed.sales.adapter.out.document;

import be.enrosed.catalog.application.ProductService;
import be.enrosed.catalog.domain.Barcodes;
import be.enrosed.catalog.domain.Carton;
import be.enrosed.catalog.domain.Dimensions;
import be.enrosed.catalog.domain.Product;
import be.enrosed.sales.application.port.out.QuoteDocumentRenderer;
import be.enrosed.sales.application.port.out.SalesPdfOptions;
import be.enrosed.sales.domain.Customer;
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
import be.enrosed.sales.domain.VatTreatment;
import be.enrosed.shared.Brand;
import be.enrosed.shared.Currency;
import be.enrosed.shared.Language;
import be.enrosed.shared.PdfFonts;
import be.enrosed.shared.company.CompanyProfile;
import be.enrosed.shared.company.CompanyProfileService;
import io.quarkus.qute.Engine;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.enterprise.inject.Instance;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.anyLong;
import static org.mockito.Mockito.when;

/**
 * End-to-end print checks for the portrait sales document family.
 *
 * The files written to output/pdf are deliberate visual QA specimens. PDFBox
 * then proves the things a screenshot cannot: every page is portrait, every
 * used font is embedded and customer documents do not leak internal pricing.
 */
@QuarkusTest
class PdfQuoteRendererRenderTest {

    @Inject Engine engine;

    private PdfQuoteRenderer renderer;

    @BeforeEach
    void rendererWithCompleteCompanyIdentity() {
        CompanyProfileService company = mock(CompanyProfileService.class);
        when(company.get()).thenReturn(company());
        renderer = new PdfQuoteRenderer(
                engine.getTemplate("quote.html"), engine.getTemplate("packing-slip.html"),
                new Brand(), company, new PdfFonts());
        renderer.portalBaseUrl = "https://enrosed.com";
    }

    @Test
    void quotationSurvivesManyLinesAndShowsEveryCommercialAdjustment() throws Exception {
        String portalUrl = "https://orders.enrosed.com/portal/"
                + "9yB8a4Qm2Lk7Wn5Pz3Rr6Tt1Vv8Xx4Cc7Dd9Ee2Ff5Gg8Hh1Jj4Kk7";
        SalesOrder stressOrder = order(DocumentType.OFFERTE, "ENR-2026-0148", 22);
        PdfQuoteRenderer.Document stressDocument = renderer.render(
                stressOrder, priced(22), customer(), portalUrl);

        /* Keep the review specimen representative as well as challenging:
           eighteen rows exercise a repeated table header without creating an
           action-only page before the frozen terms appendix. The separate
           22-row render above remains the pagination stress contract. */
        SalesOrder previewOrder = order(DocumentType.OFFERTE, "ENR-2026-0148", 18);
        PdfQuoteRenderer.Document previewDocument = renderer.render(
                previewOrder, priced(18), customer(), portalUrl);
        writePreview("enrosed-sales-quote-portrait-preview.pdf", previewDocument.content());

        try (PDDocument pdf = Loader.loadPDF(stressDocument.content())) {
            assertPortraitAndEmbedded(pdf);
            assertTrue(pdf.getNumberOfPages() >= 3,
                    "many commercial lines plus frozen terms must paginate");
            String text = textOf(pdf);
            assertTrue(text.contains("offerte"));
            assertTrue(text.contains("counter display premium kleur 1"));
            assertTrue(text.contains("counter display premium kleur 22"));
            assertTrue(text.contains("aalsmeer beurskorting"));
            assertTrue(text.contains("online bekijken, tekenen of wijzigen"));
            assertFalse(text.contains("internal-margin-sentinel"));
            assertFalse(text.contains("landed unit cost"));
        }

        try (PDDocument pdf = Loader.loadPDF(previewDocument.content())) {
            assertPortraitAndEmbedded(pdf);
            assertPrintSafeTop(pdf, 13);
            String text = textOf(pdf);
            assertTrue(text.contains("counter display premium kleur 18"));
            assertTrue(text.contains("online bekijken, tekenen of wijzigen"));
        }
    }

    @Test
    void belgianInvoiceKeepsPeppolAndPaymentHierarchy() throws Exception {
        SalesOrder invoice = order(DocumentType.FACTUUR, "F-2026-0042", 6);
        PdfQuoteRenderer.Document document = renderer.render(invoice, priced(6), customer(), null);

        writePreview("enrosed-sales-invoice-portrait-preview.pdf", document.content());

        try (PDDocument pdf = Loader.loadPDF(document.content())) {
            assertPortraitAndEmbedded(pdf);
            String text = textOf(pdf);
            assertTrue(text.contains("factuur"));
            assertTrue(text.contains("geen geldige factuur"));
            assertTrue(text.contains("peppol"));
            assertTrue(text.contains("te betalen"));
            assertTrue(text.contains("be68 5390 0754 7034"));
            assertTrue(text.contains("f-2026-0042"));
            assertTrue(text.contains("op al onze facturen"));
            assertFalse(text.contains("online bekijken, tekenen of wijzigen"));
        }
    }

    @Test
    void invoicePaymentReferencesReplaceSlashesWithoutRenumberingTheDocument() throws Exception {
        for (Language language : List.of(Language.NL, Language.EN)) {
            for (var plan : be.enrosed.sales.domain.SalesPaymentPlan.values()) {
                var invoice = order(DocumentType.FACTUUR, "CONTAINER/2026/001", 1)
                        .withPartnerDeal(13L, bd("50"))
                        .withPurpose(be.enrosed.sales.domain.SalesPurpose.PARTNER_ADVANCE, 13L, plan);
                for (boolean details : List.of(true, false)) {
                    var options = new SalesPdfOptions(false, false, true, false, false, false, details);
                    var document = renderer.render(invoice, priced(1), customer(language), null, language, options);
                    if (language == Language.NL && plan == be.enrosed.sales.domain.SalesPaymentPlan.FULL && details) {
                        writePreview("invoice-hyphenated-payment-reference.pdf", document.content());
                    }
                    try (PDDocument pdf = Loader.loadPDF(document.content())) {
                        String text = textOf(pdf);
                        // PDF extraction inserts spaces where a long reference wraps across lines.
                        String unwrapped = text.replace(" ", "");
                        assertTrue(unwrapped.contains("container/2026/001"), "document identity keeps its slashes");
                        assertEquals(details ? 2 : 1, unwrapped.split("container-2026-001", -1).length - 1,
                                "the payment box and optional instruction both use the bank reference: " + text);
                        assertPortraitAndEmbedded(pdf);
                    }
                    assertEquals("CONTAINER/2026/001", invoice.number());
                }
            }
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void partnerPaymentScheduleAndRemainingCashArePrintedWithoutClaimingPaidMoneyAgain() throws Exception {
        var invoice = order(DocumentType.FACTUUR, "PARTNER-TST-RECEIPTS", 1).withPartnerDeal(13L, bd("50"));
        var price = priced(1);
        var total = price.totals().totalInclVat();
        var first = total.divide(bd("3"), 2, RoundingMode.HALF_UP);
        var remaining = total.subtract(first);
        var service = mock(be.enrosed.sales.application.IncomingPaymentService.class);
        Instance<be.enrosed.sales.application.IncomingPaymentService> instance = mock(Instance.class);
        when(instance.isResolvable()).thenReturn(true);
        when(instance.get()).thenReturn(service);
        renderer.incomingPayments = instance;
        when(service.summary(invoice, price)).thenReturn(new be.enrosed.sales.domain.SalesPaymentSummary(
                total, first, remaining, bd("0"), bd("0"), be.enrosed.sales.domain.SalesPaymentSummary.Status.PARTIAL,
                List.of(), List.of(), false));
        var document = renderer.render(invoice, price, customer(), null, Language.NL, SalesPdfOptions.defaults());
        writePreview("partner-production-payment-plan.pdf", document.content());
        try (PDDocument pdf = Loader.loadPDF(document.content())) {
            String text = textOf(pdf);
            assertTrue(text.contains("1/3 bij start productie"), text);
            assertTrue(text.contains("2/3 na productie"), text);
            assertTrue(text.contains("ontvangen: " + be.enrosed.shared.DocumentFormat.eur(first).toLowerCase()), text);
            assertTrue(text.contains("nog te betalen: " + be.enrosed.shared.DocumentFormat.eur(remaining).toLowerCase()), text);
            assertFalse(text.contains("gelieve " + be.enrosed.shared.DocumentFormat.eur(total).toLowerCase() + " te betalen"), text);
            assertPortraitAndEmbedded(pdf);
        }
        when(service.summary(invoice, price)).thenReturn(new be.enrosed.sales.domain.SalesPaymentSummary(
                total, total, bd("0"), bd("0"), bd("0"), be.enrosed.sales.domain.SalesPaymentSummary.Status.PAID,
                List.of(), List.of(), false));
        try (PDDocument pdf = Loader.loadPDF(renderer.render(invoice, price, customer(), null).content())) {
            assertTrue(textOf(pdf).contains("geen betaling meer nodig"));
        }
        when(service.summary(invoice, price)).thenReturn(new be.enrosed.sales.domain.SalesPaymentSummary(
                bd("-100"), bd("0"), bd("0"), bd("0"), bd("100"), be.enrosed.sales.domain.SalesPaymentSummary.Status.CREDIT,
                List.of(), List.of(), false));
        try (PDDocument pdf = Loader.loadPDF(renderer.render(invoice, price, customer(), null).content())) {
            assertTrue(textOf(pdf).contains("tegoed voor de klant"));
            assertTrue(textOf(pdf).contains("geen inkomende betaling vereist"));
        }
    }

    @Test
    void staffCanDownloadACompactDocumentWithoutRemovingCommercialEssentials() throws Exception {
        PdfQuoteRenderer.Document document = renderer.render(
                order(DocumentType.OFFERTE, "ENR-2026-0149", 2), priced(2), customer(), null,
                Language.NL, new SalesPdfOptions(false, false, false, false));

        try (PDDocument pdf = Loader.loadPDF(document.content())) {
            String text = textOf(pdf);
            assertTrue(text.contains("counter display premium kleur 1"));
            assertTrue(text.contains("stukprijs"));
            assertTrue(text.contains("totaal"));
            assertFalse(text.contains("er-glass-001"), "SKU is optionele productinformatie");
            assertFalse(text.contains("week 37"), "leverinformatie valt onder logistiek");
            assertFalse(text.contains("algemene voorwaarden"), "voorwaarden zijn uitgeschakeld");
        }
    }

    @Test
    void separateAdvanceInvoiceDoesNotSplitTheMilestoneAmountAgain() throws Exception {
        var invoice = order(DocumentType.FACTUUR, "PARTNER-30-PCT", 1)
                .withPartnerDeal(13L, bd("50"))
                .withPurpose(be.enrosed.sales.domain.SalesPurpose.PARTNER_ADVANCE, 13L,
                        be.enrosed.sales.domain.SalesPaymentPlan.FULL);
        try (PDDocument pdf = Loader.loadPDF(renderer.render(invoice, priced(1), customer(), null).content())) {
            String text = textOf(pdf);
            assertTrue(text.contains("voorschotfactuur"), text);
            assertFalse(text.contains("1/3 bij start productie"), text);
            assertFalse(text.contains("2/3 na productie"), text);
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void advanceCargoHasQuantitiesAndRealLogisticsWithoutProductPricesInEveryLanguage() throws Exception {
        var invoice = order(DocumentType.FACTUUR, "CONTAINER-2-OF-3", 0)
                .withPartnerDeal(13L, bd("50"))
                .withPurpose(be.enrosed.sales.domain.SalesPurpose.PARTNER_ADVANCE, 13L,
                        be.enrosed.sales.domain.SalesPaymentPlan.FULL);
        var cargo = cargoSnapshot(14, true);
        attachCargoSnapshot(invoice, cargo);
        var catalog = mock(ProductService.class);
        Instance<ProductService> catalogInstance = mock(Instance.class);
        when(catalogInstance.isResolvable()).thenReturn(true);
        when(catalogInstance.get()).thenReturn(catalog);
        when(catalog.get(anyLong())).thenReturn(productWithPrintableMasterData());
        renderer.products = catalogInstance;
        var price = advancePrice("49289.17", "Voorschot · 2/3 na productie");
        for (Language language : Language.values()) {
            var labels = be.enrosed.shared.DocumentText.of(language);
            var document = renderer.render(invoice, price, customer(language), null, language,
                    new SalesPdfOptions(false, true, true, false, true, true, false));
            if (language == Language.NL) writePreview("partner-advance-cargo.pdf", document.content());
            try (PDDocument pdf = Loader.loadPDF(document.content())) {
                String text = textOf(pdf);
                assertTrue(text.contains(labels.get("advanceInvoice").toLowerCase()), text);
                assertTrue(text.contains("frozen glass rose 1") && text.contains("frozen glass rose 14"), text);
                assertTrue(text.contains("enr-cargo-14"), text);
                assertTrue(text.contains("3360") && text.contains("140"), text);
                assertTrue(text.contains("3,36 m³"), text);
                assertTrue(text.contains(be.enrosed.shared.DocumentText.date(LocalDate.of(2026, 11, 2), language).toLowerCase()), text);
                assertTrue(text.contains(be.enrosed.shared.DocumentText.week("2026-W46", language).toLowerCase()), text);
                assertFalse(text.contains("8712345678906") || text.contains("12 stuks per karton"),
                        "Current packaging must not contradict the frozen cargo: " + text);
                assertTrue(text.contains("8712345678920") && text.contains("8712345678937"),
                        "Enabled barcodes must come from the frozen product details: " + text);
                assertTrue(text.contains("voorschot · 2/3 na productie"), text);
                assertTrue(text.contains("49.289,17 eur"), text);
                assertTrue(text.contains("10.350,73 eur") && text.contains("59.639,90 eur"), text);
                assertFalse(text.contains(labels.get("unitPrice").toLowerCase()), text);
                assertFalse(text.contains(labels.get("pallets").toLowerCase()), "Unknown pallet counts must not appear as zero: " + text);
                assertFalse(text.contains(labels.get("goodsValue").toLowerCase()), text);
                assertFalse(text.contains("internal-margin-sentinel") || text.contains("landed unit cost"), text);
                assertPortraitAndEmbedded(pdf);
                assertTextFitsPage(pdf);
            }
        }
        var hidden = renderer.render(invoice, price, customer(), null, Language.NL,
                new SalesPdfOptions(false, false, false, false, false, false, false));
        try (PDDocument pdf = Loader.loadPDF(hidden.content())) {
            String text = textOf(pdf);
            assertTrue(text.contains("frozen glass rose 14") && text.contains("240"), text);
            assertTrue(text.contains("59.639,90 eur"), text);
            assertFalse(text.contains("bestemming") || text.contains("3,36 m³") || text.contains("140"), text);
        }
    }

    @Test
    void unavailableAdvanceLogisticsDoNotInventPalletCartonOrVolumeTotals() throws Exception {
        var invoice = order(DocumentType.FACTUUR, "CONTAINER-100-PCT", 0)
                .withPartnerDeal(13L, bd("50"))
                .withPurpose(be.enrosed.sales.domain.SalesPurpose.PARTNER_ADVANCE, 13L,
                        be.enrosed.sales.domain.SalesPaymentPlan.FULL);
        attachCargoSnapshot(invoice, cargoSnapshot(1, false));
        var document = renderer.render(invoice, advancePrice("73933.75", "Voorschot · Volledige bijdrage"),
                customer(), null, Language.NL, new SalesPdfOptions(false, false, true, false));
        try (PDDocument pdf = Loader.loadPDF(document.content())) {
            String text = textOf(pdf);
            assertTrue(text.contains("voorschotfactuur") && text.contains("frozen glass rose 1"), text);
            assertTrue(text.contains("73.933,75 eur"), text);
            assertFalse(text.contains("pallets") || text.contains("dozen: 0") || text.contains("volume: -") || text.contains("0 kg"), text);
            assertFalse(text.contains("stukprijs"), text);
            assertTextFitsPage(pdf);
        }
    }

    private static be.enrosed.sales.application.PartnerAdvanceContents.Snapshot cargoSnapshot(int count, boolean logistics) {
        var lines = java.util.stream.IntStream.rangeClosed(1, count).mapToObj(i ->
                new be.enrosed.sales.application.PartnerAdvanceContents.Item((long) i, "ENR-CARGO-" + i,
                        "Frozen glass rose " + i, 240, logistics ? 10 : null,
                        logistics ? bd("0.24") : null, logistics ? bd("62") : null,
                        logistics ? frozenCargoProductDetails() : null)).toList();
        return new be.enrosed.sales.application.PartnerAdvanceContents.Snapshot(13L, "PO-2026-013", null, lines,
                new be.enrosed.sales.application.PartnerAdvanceContents.Totals(count * 240, logistics ? count * 10 : null,
                        logistics ? bd("0.24").multiply(BigDecimal.valueOf(count)) : null,
                        logistics ? bd("62").multiply(BigDecimal.valueOf(count)) : null, null),
                new be.enrosed.sales.application.PartnerAdvanceContents.Delivery("NL", null, null, null, "40HQ",
                        LocalDate.of(2026, 11, 2), null, null, "2026-W46"), java.time.Instant.parse("2026-09-10T09:00:00Z"));
    }

    private static be.enrosed.sales.application.PartnerAdvanceContents.ProductDetails frozenCargoProductDetails() {
        return new be.enrosed.sales.application.PartnerAdvanceContents.ProductDetails(
                new Dimensions(bd("10"), bd("10"), bd("8")), be.enrosed.catalog.domain.Packaging.none(),
                new Carton(new Dimensions(bd("40"), bd("30"), bd("20")), 24, bd("6.2")),
                new Barcodes("8712345678920", "8712345678937"), "8712345678920");
    }

    @Test
    @SuppressWarnings("unchecked")
    void advanceInvoiceCartonAndBarcodeSwitchesWorkIndependentlyWithoutProductDetailsOrLogistics() throws Exception {
        var invoice = order(DocumentType.FACTUUR, "PARTNER-PRINT-OPTIONS", 0)
                .withPartnerDeal(13L, bd("50"));
        attachCargoSnapshot(invoice, cargoSnapshot(1, true));
        // Frozen specifications remain printable even if the catalog product no longer exists.
        var labels = be.enrosed.shared.DocumentText.of(Language.NL);
        for (boolean carton : List.of(false, true)) {
            for (boolean barcode : List.of(false, true)) {
                var document = renderer.render(invoice, advancePrice("100.00", "Voorschot"), customer(), null,
                        Language.NL, new SalesPdfOptions(false, false, false, false, carton, barcode, false));
                if (carton && barcode) writePreview("partner-invoice-carton-and-barcode.pdf", document.content());
                try (PDDocument pdf = Loader.loadPDF(document.content())) {
                    String text = textOf(pdf);
                    assertEquals(carton, text.contains("40 × 30 × 20 cm"), text);
                    assertEquals(carton, text.contains("24 " + labels.get("piecesPerCarton").toLowerCase()), text);
                    assertEquals(barcode, text.contains("ean 8712345678920"), text);
                    assertEquals(carton && barcode, text.contains("ean 8712345678937"), text);
                    assertFalse(text.contains("10 × 10 × 8 cm"), "Productdetails remains independently disabled");
                    assertTrue(text.contains("frozen glass rose 1") && text.contains("240"), text);
                    assertTrue(text.contains("121,00 eur"), "The switches never change the advance amount");
                    assertFalse(text.contains(labels.get("unitPrice").toLowerCase()), "Cargo remains price-free");
                    assertPortraitAndEmbedded(pdf);
                    assertTextFitsPage(pdf);
                }
            }
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void olderAdvanceSnapshotsUseProductFicheForOptionalDetailsWithoutChangingFrozenCargo() throws Exception {
        var invoice = order(DocumentType.FACTUUR, "PARTNER-LEGACY-OPTIONS", 0).withPartnerDeal(13L, bd("50"));
        var frozen = cargoSnapshot(1, true);
        var item = frozen.lines().getFirst();
        var legacy = new be.enrosed.sales.application.PartnerAdvanceContents.Snapshot(frozen.purchaseOrderId(),
                frozen.purchaseOrderNumber(), frozen.sourceQuoteId(), List.of(
                new be.enrosed.sales.application.PartnerAdvanceContents.Item(item.productId(), item.sku(), item.productName(),
                        item.quantity(), item.cartons(), item.cbm(), item.weightKg())),
                frozen.totals(), frozen.delivery(), frozen.capturedAt());
        attachCargoSnapshot(invoice, legacy);
        var catalog = mock(ProductService.class);
        Instance<ProductService> instance = mock(Instance.class);
        when(instance.isResolvable()).thenReturn(true); when(instance.get()).thenReturn(catalog);
        when(catalog.get(anyLong())).thenReturn(productWithPrintableMasterData());
        renderer.products = instance;
        for (boolean enabled : List.of(false, true)) {
            try (PDDocument pdf = Loader.loadPDF(renderer.render(invoice, advancePrice("100.00", "Voorschot"),
                    customer(), null, Language.NL, new SalesPdfOptions(false, false, true, false, enabled, enabled, false)).content())) {
                String text = textOf(pdf);
                assertEquals(enabled, text.contains("ean 8712345678906"), text);
                assertEquals(enabled, text.contains("ean 8712345678913"), text);
                assertEquals(enabled, text.contains("40 × 30 × 20 cm"), text);
                assertTrue(text.contains("frozen glass rose 1") && text.contains("enr-cargo-1"), text);
                assertTrue(text.contains("240") && text.contains("0,24 m³"), text);
                assertTrue(text.contains("121,00 eur"), text);
                assertFalse(text.contains("counter display premium"), "Frozen cargo identity stays unchanged");
                assertTextFitsPage(pdf);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void attachCargoSnapshot(SalesOrder invoice, be.enrosed.sales.application.PartnerAdvanceContents.Snapshot cargo) {
        var contents = mock(be.enrosed.sales.application.PartnerAdvanceContents.class);
        Instance<be.enrosed.sales.application.PartnerAdvanceContents> instance = mock(Instance.class);
        when(instance.isResolvable()).thenReturn(true);
        when(instance.get()).thenReturn(contents);
        when(contents.find(invoice)).thenReturn(java.util.Optional.of(cargo));
        renderer.advanceContents = instance;
    }

    private static PricedOrder advancePrice(String amount, String label) {
        var zero = BigDecimal.ZERO;
        var total = bd(amount);
        var vat = total.multiply(bd("0.21")).setScale(2, RoundingMode.HALF_UP);
        return new PricedOrder(List.of(), new PricedOrder.Totals(
                0, 0, 0, 0, 0, 0, zero, zero, zero, zero,
                zero, zero, zero, zero, zero, zero, null, zero, zero,
                zero, false, zero, zero, total, bd("21"), vat, total.add(vat), VatTreatment.BINNENLAND,
                null, null, zero, zero, zero, zero, total), priced(0).validation(),
                List.of(new PricedOrder.ExtraLine(label, BigDecimal.ONE, total, total)));
    }

    @Test
    @SuppressWarnings("unchecked")
    void compactMilestoneInvoiceHidesLegacyGeneratedInstructionsButKeepsTheBuyerNote() throws Exception {
        String generated = "Voorschot · Start productie. 30% van het afgesproken voorschot. "
                + "Deze factuur betreft uitsluitend deze termijn; de eindafrekening volgt afzonderlijk.";
        String buyerNote = "Onze referentie: PARTNER-2026.";
        var schedules = mock(be.enrosed.sales.application.PartnerAdvanceSchedules.class);
        Instance<be.enrosed.sales.application.PartnerAdvanceSchedules> instance = mock(Instance.class);
        when(instance.isResolvable()).thenReturn(true);
        when(instance.get()).thenReturn(schedules);
        renderer.advanceSchedules = instance;
        when(schedules.forInvoice(148L)).thenReturn(new be.enrosed.sales.application.PartnerAdvanceSchedules.Row(
                71L, 13L, 0, "Start productie", bd("30"), bd("900.12"), null, 148L));
        var price = priced(1);
        for (String notes : List.of(generated, generated + "\n" + buyerNote, buyerNote)) {
            var invoice = order(DocumentType.FACTUUR, "PARTNER-CONCEPT-30", 1, notes)
                    .withPartnerDeal(13L, bd("50"))
                    .withPurpose(be.enrosed.sales.domain.SalesPurpose.PARTNER_ADVANCE, 13L,
                            be.enrosed.sales.domain.SalesPaymentPlan.FULL);
            var compact = renderer.render(invoice, price, customer(), null, Language.NL,
                    new SalesPdfOptions(false, false, false, false, false, false, false));
            try (PDDocument pdf = Loader.loadPDF(compact.content())) {
                String text = textOf(pdf);
                assertTrue(text.contains("voorschotfactuur"), text);
                assertTrue(text.contains("te betalen"), text);
                assertTrue(text.contains(be.enrosed.shared.DocumentFormat.eur(price.totals().totalInclVat()).toLowerCase()), text);
                assertFalse(text.contains("30% van het afgesproken voorschot"), text);
                assertFalse(text.contains("deze factuur betreft uitsluitend deze termijn"), text);
                assertEquals(notes.contains(buyerNote), text.contains("partner-2026"), text);
                assertFalse(text.contains("1/3 bij start productie"), text);
            }
            if (notes.contains(buyerNote)) writePreview("partner-concept-compact.pdf", compact.content());
            if (notes.equals(generated)) {
                var detailed = renderer.render(invoice, price, customer(), null, Language.NL,
                        new SalesPdfOptions(false, false, false, false));
                try (PDDocument pdf = Loader.loadPDF(detailed.content())) {
                    assertTrue(textOf(pdf).contains("30% van het afgesproken voorschot"));
                }
            }
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void partialAuctionAndActualRefundAreExplicitOnTheDocument() throws Exception {
        var invoice = order(DocumentType.FACTUUR, "PARTNER-TST-PARTIAL", 1).asPartnerSettlement();
        var settlements = mock(be.enrosed.sales.application.PartnerSettlements.class);
        Instance<be.enrosed.sales.application.PartnerSettlements> settlementInstance = mock(Instance.class);
        when(settlementInstance.isResolvable()).thenReturn(true);
        when(settlementInstance.get()).thenReturn(settlements);
        renderer.partnerSettlements = settlementInstance;
        when(settlements.find(invoice.id())).thenReturn(new be.enrosed.sales.application.PartnerSettlements.Snapshot(
                bd("900"), bd("1000"), bd("1000"), false, List.of()));
        var incoming = mock(be.enrosed.sales.application.IncomingPaymentService.class);
        Instance<be.enrosed.sales.application.IncomingPaymentService> receiptInstance = mock(Instance.class);
        when(receiptInstance.isResolvable()).thenReturn(true);
        when(receiptInstance.get()).thenReturn(incoming);
        renderer.incomingPayments = receiptInstance;
        var zero = bd("0");
        var price = new PricedOrder(List.of(), new PricedOrder.Totals(
                0, 0, 0, 0, 0, 0, zero, zero, zero, zero,
                zero, zero, zero, zero, zero, zero, null, zero, zero,
                zero, false, zero, zero, bd("-100"), bd("21"), bd("-21"), bd("-121"), VatTreatment.BINNENLAND,
                null, null, zero, zero, zero, zero, bd("-100")),
                new PricedOrder.Validation(zero, true, zero, true, true, List.of(), List.of(), List.of(), null),
                List.of(new PricedOrder.ExtraLine("Credit bij deelafrekening", bd("1"), bd("-100"), bd("-100"))));
        when(incoming.summary(invoice, price)).thenReturn(new be.enrosed.sales.domain.SalesPaymentSummary(
                bd("-121"), bd("-40"), bd("0"), bd("0"), bd("81"),
                be.enrosed.sales.domain.SalesPaymentSummary.Status.CREDIT, List.of(), List.of(), false,
                bd("0"), bd("40"), bd("81")));
        var document = renderer.render(invoice, price, customer(), null, Language.NL, new SalesPdfOptions(false, false, false, false));
        writePreview("partner-partial-settlement-refund.pdf", document.content());
        try (PDDocument pdf = Loader.loadPDF(document.content())) {
            String text = textOf(pdf);
            assertTrue(text.contains("deelafrekening"), text);
            assertFalse(text.contains("slotfactuur"), text);
            assertTrue(text.contains("terugbetaald: " + be.enrosed.shared.DocumentFormat.eur(bd("40")).toLowerCase()), text);
            assertTrue(text.contains("tegoed voor de klant: " + be.enrosed.shared.DocumentFormat.eur(bd("81")).toLowerCase()), text);
            assertPortraitAndEmbedded(pdf);
        }
    }

    @Test
    void salesPdfDefaultsAndCleanTitleAreStable() {
        SalesPdfOptions defaults = SalesPdfOptions.defaults();
        assertTrue(defaults.includePhotos());
        assertTrue(defaults.includeProductDetails());
        assertTrue(defaults.includeLogistics());
        assertTrue(defaults.includeTerms());
        assertTrue(defaults.includePaymentDetails());
        assertFalse(defaults.showOuterCarton());
        assertFalse(defaults.showBarcode());
        assertEquals("Bowl Rozen XL - Red",
                PdfQuoteRenderer.cleanFallbackTitle(
                        "Bowl Rozen XL - B × D × H: 10 × 10 × 8 cm - Red"));
        assertTrue(PdfQuoteRenderer.hasLineDiscounts(priced(1)));
        assertFalse(PdfQuoteRenderer.hasLineDiscounts(priced(1, false)));
    }

    @Test
    void quotationAndInvoiceOnlyShowBarcodeAndOuterCartonWhenRequested() throws Exception {
        @SuppressWarnings("unchecked")
        Instance<ProductService> productInstance = mock(Instance.class);
        ProductService productService = mock(ProductService.class);
        when(productInstance.isResolvable()).thenReturn(true);
        when(productInstance.get()).thenReturn(productService);
        when(productService.get(anyLong())).thenReturn(productWithPrintableMasterData());
        renderer.products = productInstance;

        for (DocumentType type : List.of(DocumentType.OFFERTE, DocumentType.FACTUUR)) {
            String number = type == DocumentType.FACTUUR ? "F-2026-0091" : "ENR-2026-0191";
            PdfQuoteRenderer.Document compact = renderer.render(
                    order(type, number, 1), priced(1), customer(), null,
                    Language.NL, new SalesPdfOptions(false, false, false, false));
            try (PDDocument pdf = Loader.loadPDF(compact.content())) {
                String text = textOf(pdf);
                assertFalse(text.contains("8712345678906"));
                assertFalse(text.contains("8712345678913"));
                assertFalse(text.contains("40 × 30 × 20 cm"));
            }

            PdfQuoteRenderer.Document detailed = renderer.render(
                    order(type, number, 1), priced(1), customer(), null,
                    Language.NL, new SalesPdfOptions(false, false, false, false,
                            true, true));
            try (PDDocument pdf = Loader.loadPDF(detailed.content())) {
                String text = textOf(pdf);
                assertTrue(text.contains("8712345678906"));
                assertTrue(text.contains("8712345678913"));
                assertTrue(text.contains("40 × 30 × 20 cm"));
                assertTrue(text.contains("12 stuks"));
                assertTrue(text.contains("stukprijs"), "commercial columns stay mandatory");
            }
        }
    }

    @Test
    void partnerDocumentsSayAdvanceOrFinalAndReadTheInternalNames() throws Exception {
        @SuppressWarnings("unchecked")
        Instance<ProductService> productInstance = mock(Instance.class);
        ProductService productService = mock(ProductService.class);
        when(productInstance.isResolvable()).thenReturn(true);
        when(productInstance.get()).thenReturn(productService);
        /* The shop knows the product as "glazen sierschaal"; the container and the partner know "counter display premium". */
        when(productService.get(anyLong())).thenReturn(productWithPrintableMasterData().withTexts(List.of(
                new be.enrosed.catalog.domain.ProductText(Language.NL, "glazen sierschaal premium", null, null, null))));
        renderer.products = productInstance;

        PdfQuoteRenderer.Document plain = renderer.render(order(DocumentType.OFFERTE, "ENR-2026-0300", 1), priced(1, false), customer(), null);
        try (PDDocument pdf = Loader.loadPDF(plain.content())) {
            String text = textOf(pdf);
            assertTrue(text.contains("glazen sierschaal premium"), "a customer reads the shop's name: " + text);
            assertFalse(text.contains("voorschot"), text);
        }

        SalesOrder advanceQuote = order(DocumentType.OFFERTE, "ENR-2026-0301", 1).withPartnerDeal(13L, bd("50"));
        PdfQuoteRenderer.Document quote = renderer.render(advanceQuote, priced(1, false), customer(), null);
        writePreview("partner-advance-quote.pdf", quote.content());
        try (PDDocument pdf = Loader.loadPDF(quote.content())) {
            String text = textOf(pdf);
            assertTrue(text.contains("offerte enr-2026-0301"), text);
            assertFalse(text.contains("voorschotofferte"), text);
            assertTrue(text.contains("slotfactuur volgt met de eindafrekening."), text);
            assertFalse(text.contains("incoterm dap"), "a partner document carries no incoterm in its fact band: " + text);
            assertFalse(text.contains("30 dagen na factuurdatum"), "nor a payment term: " + text);
            assertTrue(text.contains("counter display premium"), "the partner reads the container's own name: " + text);
            assertFalse(text.contains("glazen sierschaal"), text);
            assertTrue(text.contains("er-glass-001"), "and its code, like the purchase order: " + text);
        }

        SalesOrder advanceInvoice = order(DocumentType.FACTUUR, "F-2026-0302", 1).withPartnerDeal(13L, bd("50"));
        try (PDDocument pdf = Loader.loadPDF(renderer.render(advanceInvoice, priced(1, false), customer(), null).content())) {
            String text = textOf(pdf);
            assertTrue(text.contains("voorschotfactuur f-2026-0302"), "even a partner paying the whole cost up front gets an advance invoice: " + text);
            assertFalse(text.contains("slotfactuur f-2026-0302"), text);
            assertTrue(text.contains("slotfactuur volgt met de eindafrekening."), text);
        }

        SalesOrder settlement = order(DocumentType.FACTUUR, "F-2026-0303", 1).withPartnerDeal(13L, bd("50")).asPartnerSettlement();
        PdfQuoteRenderer.Document finalInvoice = renderer.render(settlement, priced(1, false), customer(), null);
        writePreview("partner-final-invoice.pdf", finalInvoice.content());
        try (PDDocument pdf = Loader.loadPDF(finalInvoice.content())) {
            String text = textOf(pdf);
            assertTrue(text.contains("slotfactuur f-2026-0303"), text);
            assertTrue(text.contains("eindafrekening na de veiling, met het voorschot verrekend."), text);
            assertFalse(text.contains("voorschotfactuur"), text);
        }
    }

    @Test
    void advanceQuotationShowsFrozenCustomInstalmentsWithoutSuggestingAFinalSellingTotal() throws Exception {
        var quote = order(DocumentType.OFFERTE, "OFF-2026-0451", 1).withPartnerDeal(13L, bd("50"));
        var price = priced(1, false);
        var snapshot = new be.enrosed.sales.application.PartnerAdvanceQuotes.Snapshot(13L, bd("50"), bd("3000.40"), bd("50"), List.of(
                new be.enrosed.sales.application.PartnerAdvanceQuotes.Row(71L, "Start productie", bd("30"), bd("900.12"), LocalDate.of(2026, 9, 15)),
                new be.enrosed.sales.application.PartnerAdvanceQuotes.Row(72L, "Container gereed", bd("70"), bd("2100.28"), LocalDate.of(2026, 10, 20))));
        attachAdvanceSnapshot(quote.id(), snapshot);
        for (Language language : Language.values()) {
            var document = renderer.render(quote, price, customer(language), null, language,
                    new SalesPdfOptions(true, true, true, false));
            if (language == Language.NL || language == Language.EN || language == Language.TR)
                writePreview("advance-agreement-quote-" + language.code() + ".pdf", document.content());
            try (PDDocument pdf = Loader.loadPDF(document.content())) {
                String text = textOf(pdf);
                var labels = be.enrosed.shared.DocumentText.of(language);
                assertEquals(labels.get("quote") + " OFF-2026-0451", pdf.getDocumentInformation().getTitle());
                assertTrue(text.contains("off-2026-0451"), text);
                assertTrue(text.contains("start productie") && text.contains("container gereed"), text);
                assertTrue(text.contains("30%") && text.contains("70%") && text.contains("50%"), text);
                assertTrue(text.contains("900,12 eur") && text.contains("2.100,28 eur"), text);
                assertTrue(text.contains(labels.get("advanceFinalPending").toLowerCase()), text);
                assertFalse(text.contains(labels.get("subtotal").toLowerCase()), text);
                assertFalse(text.contains(labels.get("unitPrice").toLowerCase()), text);
                assertFalse(text.contains("3.000,40 eur"), "No summed contribution masquerading as final amount: " + text);
                assertFalse(text.contains(be.enrosed.shared.DocumentFormat.eur(price.totals().total()).toLowerCase()), text);
                assertFalse(text.contains("partner"), text);
                assertPortraitAndEmbedded(pdf);
                assertTextFitsPage(pdf);
            }
        }
    }

    @Test
    void fixedAdvanceAmountsKeepTheirExactValueAndInvoicesKeepTheirTaxTotals() throws Exception {
        var quote = order(DocumentType.OFFERTE, "OFF-2026-0452", 1).withPartnerDeal(13L, bd("50"));
        var snapshot = new be.enrosed.sales.application.PartnerAdvanceQuotes.Snapshot(13L, bd("100"), bd("180.75"), bd("50"), List.of(
                new be.enrosed.sales.application.PartnerAdvanceQuotes.Row(73L, "Productiemonster en verpakking", null, bd("180.75"), null)));
        attachAdvanceSnapshot(quote.id(), snapshot);
        try (PDDocument pdf = Loader.loadPDF(renderer.render(quote, priced(1), customer(), null).content())) {
            String text = textOf(pdf);
            assertTrue(text.contains("180,75 eur"), text);
            assertFalse(text.contains("100%"), "A fixed amount is not an invented percentage: " + text);
            assertTrue(text.contains("geen minimumorderbedrag"), text);
            assertTrue(text.contains("tenzij de offerte of orderbevestiging uitdrukkelijk een ander betalingsschema toestaat"), text);
        }
        var invoice = order(DocumentType.FACTUUR, "F-2026-0452", 1).withPartnerDeal(13L, bd("50"));
        try (PDDocument pdf = Loader.loadPDF(renderer.render(invoice, priced(1), customer(), null).content())) {
            String text = textOf(pdf);
            assertTrue(text.contains("voorschotfactuur f-2026-0452"), text);
            assertFalse(text.contains("stukprijs"), text);
            assertTrue(text.contains("totaal incl. btw"), text);
            assertFalse(text.contains("voorschotafspraken"), text);
        }
    }

    @Test
    void hiddenAdvanceDetailsKeepOnlyTheFrozenAmountDueAndCustomerNotesInEveryLanguage() throws Exception {
        var quote = order(DocumentType.OFFERTE, "OFF-2026-COMPACT", 1).withPartnerDeal(13L, bd("50"));
        var price = priced(1, false);
        var snapshot = new be.enrosed.sales.application.PartnerAdvanceQuotes.Snapshot(13L, bd("50"), bd("3000.40"), bd("50"), List.of(
                new be.enrosed.sales.application.PartnerAdvanceQuotes.Row(71L, "Start productie", bd("30"), bd("900.12"), LocalDate.of(2026, 9, 15)),
                new be.enrosed.sales.application.PartnerAdvanceQuotes.Row(72L, "Container gereed", bd("70"), bd("2100.28"), LocalDate.of(2026, 10, 20))));
        attachAdvanceSnapshot(quote.id(), snapshot);
        for (Language language : Language.values()) {
            var buyer = customer(language);
            buyer = new Customer(buyer.id(), buyer.company(), buyer.contact(), buyer.email(), buyer.phone(),
                    buyer.vatNumber(), buyer.countryCode(), buyer.language(), buyer.address(), buyer.postalCode(),
                    buyer.city(), buyer.incoterm(), buyer.paymentTerms(), buyer.notes(), buyer.createdAt(),
                    buyer.partner(), buyer.partnerSharePct(), buyer.partnerCostPct(), buyer.fiscalRepresentative(),
                    "Klantnotitie moet zichtbaar blijven.");
            var document = renderer.render(quote, price, buyer, null, language,
                    new SalesPdfOptions(true, true, true, false, false, false, false));
            if (language == Language.NL) {
                writePaymentPreview("advance-agreement-hidden.pdf", document.content());
                writePaymentPreview("advance-agreement-shown.pdf", renderer.render(quote, price, buyer, null,
                        language, new SalesPdfOptions(true, true, true, false)).content());
            }
            try (PDDocument pdf = Loader.loadPDF(document.content())) {
                String text = textOf(pdf);
                var labels = be.enrosed.shared.DocumentText.of(language);
                assertTrue(text.contains(labels.get("toPay").toLowerCase()), text);
                assertTrue(text.contains(labels.get("advanceAmount").toLowerCase()), "the frozen amount excludes VAT: " + text);
                assertTrue(text.contains("3.000,40 eur"), "Use the agreed contribution, never the container's selling total: " + text);
                assertTrue(text.contains("klantnotitie moet zichtbaar blijven."), text);
                assertTrue(text.contains("levering op afspraak aan het centrale magazijn."), text);
                assertFalse(text.contains("30%") || text.contains("70%") || text.contains("50%"), text);
                assertFalse(text.contains("start productie") || text.contains("container gereed"), text);
                assertFalse(text.contains("900,12 eur") || text.contains("2.100,28 eur"), text);
                assertFalse(text.contains(labels.get("advanceAgreementTitle").toLowerCase()), text);
                assertFalse(text.contains(labels.get("advanceFinalPending").toLowerCase()), text);
                if (language == Language.NL) assertFalse(text.contains("slotfactuur volgt"), text);
                assertFalse(text.contains(be.enrosed.shared.DocumentFormat.eur(price.totals().total()).toLowerCase()), text);
                assertPortraitAndEmbedded(pdf);
                assertTextFitsPage(pdf);
            }
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void hiddenInvoicePaymentDetailsRetainTheAuthoritativeClaimVatBankAndReference() throws Exception {
        var invoice = order(DocumentType.FACTUUR, "FACTUUR-COMPACT", 1).withPartnerDeal(13L, bd("50"));
        var price = priced(1);
        var total = price.totals().totalInclVat();
        var remaining = total.subtract(bd("80"));
        var service = mock(be.enrosed.sales.application.IncomingPaymentService.class);
        Instance<be.enrosed.sales.application.IncomingPaymentService> instance = mock(Instance.class);
        when(instance.isResolvable()).thenReturn(true);
        when(instance.get()).thenReturn(service);
        renderer.incomingPayments = instance;
        when(service.summary(invoice, price)).thenReturn(new be.enrosed.sales.domain.SalesPaymentSummary(
                total, bd("80"), remaining, bd("0"), bd("0"),
                be.enrosed.sales.domain.SalesPaymentSummary.Status.PARTIAL, List.of(), List.of(), false,
                bd("100"), bd("20"), bd("0")));
        var hidden = renderer.render(invoice, price, customer(), null, Language.NL,
                new SalesPdfOptions(true, true, true, false, false, false, false));
        var shown = renderer.render(invoice, price, customer(), null, Language.NL,
                new SalesPdfOptions(true, true, true, false));
        writePaymentPreview("invoice-payments-hidden.pdf", hidden.content());
        writePaymentPreview("invoice-payments-shown.pdf", shown.content());
        try (PDDocument compactPdf = Loader.loadPDF(hidden.content()); PDDocument detailedPdf = Loader.loadPDF(shown.content())) {
            String compact = textOf(compactPdf);
            String detailed = textOf(detailedPdf);
            for (var value : List.of(price.totals().total(), price.totals().vatAmount(), total, remaining)) {
                String formatted = be.enrosed.shared.DocumentFormat.eur(value).toLowerCase();
                assertTrue(compact.contains(formatted), compact);
                assertTrue(detailed.contains(formatted), detailed);
            }
            assertTrue(compact.contains("te betalen"), compact);
            assertTrue(compact.contains("be68 5390 0754 7034"), compact);
            assertTrue(compact.contains("factuur-compact"), compact);
            assertTrue(compact.contains(be.enrosed.shared.DocumentText.date(invoice.invoiceDueDate(), Language.NL).toLowerCase()), compact);
            assertTrue(compact.contains("levering op afspraak aan het centrale magazijn."), compact);
            for (String optional : List.of("ontvangen:", "terugbetaald:", "nog te betalen:",
                    "1/3 bij start productie", "2/3 na productie", "slotfactuur volgt met de eindafrekening.")) {
                assertFalse(compact.contains(optional), compact);
                assertTrue(detailed.contains(optional), detailed);
            }
            assertPortraitAndEmbedded(compactPdf);
            assertTextFitsPage(compactPdf);
        }
    }

    @SuppressWarnings("unchecked")
    private void attachAdvanceSnapshot(long quoteId, be.enrosed.sales.application.PartnerAdvanceQuotes.Snapshot snapshot) {
        var quotes = mock(be.enrosed.sales.application.PartnerAdvanceQuotes.class);
        Instance<be.enrosed.sales.application.PartnerAdvanceQuotes> instance = mock(Instance.class);
        when(instance.isResolvable()).thenReturn(true);
        when(instance.get()).thenReturn(quotes);
        when(quotes.find(quoteId)).thenReturn(snapshot);
        renderer.advanceQuotes = instance;
    }

    private static void assertTextFitsPage(PDDocument pdf) throws Exception {
        new PDFTextStripper() {
            @Override
            protected void processTextPosition(org.apache.pdfbox.text.TextPosition position) {
                float pageWidth = pdf.getPage(getCurrentPageNo() - 1).getMediaBox().getWidth();
                assertTrue(position.getXDirAdj() >= 0
                                && position.getXDirAdj() + position.getWidthDirAdj() <= pageWidth - 12,
                        "Text exceeds the printable page: " + position.getUnicode() + " at " + position.getXDirAdj());
                super.processTextPosition(position);
            }
        }.getText(pdf);
    }

    @Test
    void customersClearedThroughOurRepresentativeReadItOnGoodsDocumentsOnly() throws Exception {
        Customer cleared = new Customer(
                8L, "Bloemen Venlo BV", "Jan Peters", "jan@venlo.example", "+31 77 555 0101",
                "NL858617262B02", "NL", Language.EN, "Kade 1", "5911 AB", "Venlo", "DAP", null, "",
                LocalDate.of(2026, 1, 1), true, null, null, true, "Delivery ex warehouse Venlo.");

        SalesOrder advance = order(DocumentType.FACTUUR, "F-2026-0310", 1).withPartnerDeal(13L, bd("50"));
        PdfQuoteRenderer.Document advanceInvoice = renderer.render(advance, priced(1, false), cleared, null);
        writePreview("partner-advance-invoice-fiscal-representative.pdf", advanceInvoice.content());
        try (PDDocument pdf = Loader.loadPDF(advanceInvoice.content())) {
            String text = textOf(pdf);
            assertTrue(text.contains("customs cleared in the netherlands by our limited fiscal representative: 24/7 customs bv with vat-no: nl858617262b02"), text);
            assertTrue(text.contains("delivery ex warehouse venlo."), "the customer's own sentence: " + text);
            assertTrue(text.contains("nl858617262b02"), "the buyer's VAT number: " + text);
            assertTrue(text.contains("venlo · nl"), "the buyer's complete address, country included: " + text);
        }

        SalesOrder settlement = order(DocumentType.FACTUUR, "F-2026-0311", 1).withPartnerDeal(13L, bd("50")).asPartnerSettlement();
        try (PDDocument pdf = Loader.loadPDF(renderer.render(settlement, priced(1, false), cleared, null).content())) {
            String text = textOf(pdf);
            assertFalse(text.contains("customs cleared"), "the final invoice settles the deal; the clearance was said on the goods documents: " + text);
            assertTrue(text.contains("delivery ex warehouse venlo."), text);
        }

        try (PDDocument pdf = Loader.loadPDF(renderer.render(order(DocumentType.FACTUUR, "F-2026-0312", 1), priced(1, false), customer(), null).content())) {
            String text = textOf(pdf);
            assertFalse(text.contains("customs cleared"), "an ordinary customer never reads it: " + text);
        }
    }

    @Test
    void packingSlipMasterDataIsOptInAndRemainsPriceFree() throws Exception {
        QuoteDocumentRenderer.PackingItem item = new QuoteDocumentRenderer.PackingItem(
                "Preserved rose glass bowl", 2, 24, "40 × 30 × 20 cm", 12,
                "8712345678906", "8712345678913");
        QuoteDocumentRenderer.PackingSlip slip = new QuoteDocumentRenderer.PackingSlip(
                order(DocumentType.FACTUUR, "F-2026-0092", 1), customer(), List.of(),
                List.of(item), 2, 24, true);

        String defaultHtml = renderer.packingSlipHtml(slip);
        assertFalse(defaultHtml.contains("8712345678906"));
        assertFalse(defaultHtml.contains("40 × 30 × 20 cm"));

        SalesPdfOptions options = SalesPdfOptions.forPackingSlip(true, true);
        String detailedHtml = renderer.packingSlipHtml(slip, options);
        assertTrue(detailedHtml.contains("8712345678906"));
        assertTrue(detailedHtml.contains("8712345678913"));
        assertTrue(detailedHtml.contains("40 × 30 × 20 cm"));

        PdfQuoteRenderer.Document document = renderer.packingSlip(slip, options);
        writePreview("enrosed-sales-packing-slip-product-data-preview.pdf", document.content());
        try (PDDocument pdf = Loader.loadPDF(document.content())) {
            assertPortraitAndEmbedded(pdf);
            String text = textOf(pdf);
            assertTrue(text.contains("8712345678906"));
            assertTrue(text.contains("8712345678913"));
            assertTrue(text.contains("40 × 30 × 20 cm"));
            assertFalse(text.contains("stukprijs"));
            assertFalse(text.contains("eur"));
        }
    }

    @Test
    void packingSlipIsPortraitReadableAndPriceFree() throws Exception {
        String expectedDate = be.enrosed.shared.DocumentText
                .date(LocalDate.now(), Language.NL).toLowerCase();
        SalesOrder order = order(DocumentType.FACTUUR, "F-2026-0042", 12);
        List<QuoteDocumentRenderer.PackingItem> palletOne = packingItems(1, 8);
        List<QuoteDocumentRenderer.PackingItem> palletTwo = packingItems(9, 16);
        List<QuoteDocumentRenderer.PackingItem> loose = packingItems(17, 20);
        QuoteDocumentRenderer.PackingSlip slip = new QuoteDocumentRenderer.PackingSlip(
                order, customer(),
                List.of(
                        new QuoteDocumentRenderer.PackingPallet(
                                "Pallet 1", "EURO 120 x 80", 168, palletOne),
                        new QuoteDocumentRenderer.PackingPallet(
                                "Pallet 2", "BLOCK 120 x 100", 176, palletTwo)),
                loose, 40, 480, false);

        String html = renderer.packingSlipHtml(slip);
        assertTrue(html.contains("<html lang=\"nl\">"));
        assertTrue(html.toLowerCase().contains(expectedDate));
        PdfQuoteRenderer.Document document = renderer.packingSlip(slip);
        writePreview("enrosed-sales-packing-slip-portrait-preview.pdf", document.content());

        try (PDDocument pdf = Loader.loadPDF(document.content())) {
            assertPortraitAndEmbedded(pdf);
            assertTrue(pdf.getNumberOfPages() <= 2,
                    "a 20-line warehouse document must stay operationally compact");
            String text = textOf(pdf);
            assertTrue(text.contains("pakbon"));
            assertTrue(text.contains(expectedDate));
            assertTrue(text.contains("pallet 1"));
            assertTrue(text.contains("preserved rose glass bowl kleur 20"));
            assertTrue(text.contains("geladen door / datum"));
            assertTrue(text.contains("ontvangen door / datum"));
            assertFalse(text.contains("12,50 eur"));
            assertFalse(text.contains("6.032,80 eur"));
            assertFalse(text.contains("stukprijs"));
            assertFalse(text.contains("algemene voorwaarden"));
            assertFalse(text.contains("artikel 1"));
        }
    }

    @Test
    void englishPackingSlipUsesTheCustomerLanguage() throws Exception {
        String expectedDate = be.enrosed.shared.DocumentText
                .date(LocalDate.now(), Language.EN).toLowerCase();
        SalesOrder order = order(DocumentType.FACTUUR, "F-2026-0043", 1);
        QuoteDocumentRenderer.PackingSlip slip = new QuoteDocumentRenderer.PackingSlip(
                order, customer(Language.EN), List.of(),
                List.of(new QuoteDocumentRenderer.PackingItem(
                        "Preserved rose glass bowl - ruby red", 2, 24)),
                2, 24, true);

        String html = renderer.packingSlipHtml(slip);
        assertTrue(html.contains("<html lang=\"en\">"));
        assertTrue(html.toLowerCase().contains(expectedDate));
        PdfQuoteRenderer.Document document = renderer.packingSlip(slip);
        writePreview("enrosed-sales-packing-slip-en-portrait-preview.pdf", document.content());

        try (PDDocument pdf = Loader.loadPDF(document.content())) {
            assertPortraitAndEmbedded(pdf);
            String text = textOf(pdf);
            assertTrue(text.contains("delivery note"));
            assertTrue(text.contains(expectedDate));
            assertTrue(text.contains("delivery address"));
            assertTrue(text.contains("loose cartons"));
            assertTrue(text.contains("loaded by / date"));
            assertTrue(text.contains("received by / date"));
            assertFalse(text.contains("pakbon"));
            assertFalse(text.contains("leveradres"));
            assertFalse(text.contains("general terms and conditions"));
            assertFalse(text.contains("article 1"));
        }
    }

    @Test
    void emptyPackingSlipStillRendersAUsefulControlSheet() throws Exception {
        QuoteDocumentRenderer.PackingSlip slip = new QuoteDocumentRenderer.PackingSlip(
                order(DocumentType.FACTUUR, "F-2026-0044", 0),
                customer(), List.of(), List.of(), 0, 0, false);

        PdfQuoteRenderer.Document document = renderer.packingSlip(slip);

        try (PDDocument pdf = Loader.loadPDF(document.content())) {
            assertPortraitAndEmbedded(pdf);
            String text = textOf(pdf);
            assertTrue(text.contains("pakbon"));
            assertTrue(text.contains("0 dozen"));
            assertTrue(text.contains("geladen door / datum"));
        }
    }

    private static void assertPortraitAndEmbedded(PDDocument pdf) throws Exception {
        for (PDPage page : pdf.getPages()) {
            assertTrue(page.getMediaBox().getHeight() > page.getMediaBox().getWidth(),
                    "sales documents must remain portrait A4");
            for (var name : page.getResources().getFontNames()) {
                PDFont font = page.getResources().getFont(name);
                assertTrue(font != null && font.isEmbedded(),
                        () -> "font " + name.getName() + " must be embedded");
            }
        }
    }

    private static void assertPrintSafeTop(PDDocument document, double minimumMillimetres)
            throws Exception {
        BufferedImage page = new PDFRenderer(document).renderImageWithDPI(0, 72);
        int firstInk = page.getHeight();
        scan:
        for (int y = 0; y < page.getHeight(); y++) {
            for (int x = 0; x < page.getWidth(); x++) {
                int colour = page.getRGB(x, y);
                int red = colour >>> 16 & 0xff;
                int green = colour >>> 8 & 0xff;
                int blue = colour & 0xff;
                if (red < 248 || green < 248 || blue < 248) {
                    firstInk = y;
                    break scan;
                }
            }
        }
        int minimumPixels = (int) Math.floor(minimumMillimetres * 72 / 25.4);
        assertTrue(firstInk >= minimumPixels,
                "documentinhoud begint op " + firstInk + " px; minimaal "
                        + minimumPixels + " px vereist voor een printveilige bovenmarge");
    }

    private static String textOf(PDDocument pdf) throws Exception {
        return new PDFTextStripper().getText(pdf).toLowerCase().replaceAll("\\s+", " ");
    }

    private static void writePreview(String filename, byte[] content) throws Exception {
        Path directory = Path.of("output", "pdf");
        Files.createDirectories(directory);
        Files.write(directory.resolve(filename), content);
    }

    private static void writePaymentPreview(String filename, byte[] content) throws Exception {
        Path directory = Path.of(System.getProperty("enrosed.pdf.preview-dir",
                System.getProperty("java.io.tmpdir")), "pdfs");
        Files.createDirectories(directory);
        Files.write(directory.resolve(filename), content);
    }

    private static CompanyProfile company() {
        return new CompanyProfile(
                "Enrosed BV", "Enrosed BV", "BE 1034.273.386", "1034.273.386",
                "Vekeblok 17", "2400", "Mol", "BE",
                "hello@enrosed.com", "+32 470 02 42 07", "enrosed.com",
                "BE68 5390 0754 7034", "KREDBEBB",
                "Wholesale preserved flowers for professional buyers.",
                "Wholesale preserved flowers for professional buyers.",
                null, null, null, null);
    }

    private static Customer customer() {
        return customer(Language.NL);
    }

    private static Customer customer(Language language) {
        return new Customer(
                7L, "Royal Garden Center Group", "Anne van den Berg",
                "inkoop@royalgarden.example", "+32 3 555 01 02", "BE 0123.456.789",
                "BE", language, "Bloemenlaan 112", "2000", "Antwerpen",
                "DAP", "30 dagen na factuurdatum", null, LocalDate.of(2024, 3, 12));
    }

    private static SalesOrder order(DocumentType type, String number, int lineCount) {
        return order(type, number, lineCount, "Levering op afspraak aan het centrale magazijn.");
    }

    private static SalesOrder order(DocumentType type, String number, int lineCount, String notes) {
        LocalDate date = LocalDate.of(2026, 8, 27);
        List<be.enrosed.sales.domain.SalesOrderLine> lines = new ArrayList<>();
        for (int index = 1; index <= lineCount; index++) {
            lines.add(new be.enrosed.sales.domain.SalesOrderLine(
                    (long) index, (long) index, 24, null, null, "2026-W37"));
        }
        return new SalesOrder(
                148L, number, 7L, "BE", date, date.plusDays(30), QuoteStatus.CONCEPT,
                "DAP", "30 dagen na factuurdatum",
                notes,
                MarkupMode.PRODUCT, bd("45"), bd("5"), "Aalsmeer beurskorting",
                null, null, null, 0, null, null, null, "internal-margin-sentinel",
                DeliveryTermsState.VOLLEDIG, FreightState.BEREKEND, bd("220"),
                LoadMode.PALLETS, PalletProfile.EURO_120X80, bd("180"),
                FreightPricingStrategy.FIXED, null, null, null,
                type, type == DocumentType.FACTUUR ? date.plusDays(30) : null,
                null, type == DocumentType.FACTUUR ? 147L : null, null,
                lines, List.of());
    }

    private static PricedOrder priced(int lineCount) {
        return priced(lineCount, true);
    }

    private static PricedOrder priced(int lineCount, boolean withLineDiscount) {
        List<PricedOrder.Line> lines = new ArrayList<>();
        BigDecimal lineDiscountPct = withLineDiscount ? bd("5") : BigDecimal.ZERO;
        BigDecimal lineDiscountAmount = withLineDiscount ? bd("15.00") : BigDecimal.ZERO;
        BigDecimal lineNet = withLineDiscount ? bd("285.00") : bd("300.00");
        for (int index = 1; index <= lineCount; index++) {
            lines.add(new PricedOrder.Line(
                    (long) index, "ER-GLASS-" + String.format("%03d", index),
                    "Counter display premium kleur " + index,
                    "Counter display premium kleur " + index,
                    null, 24, 2, 16, 1, 4, 2, bd("164"),
                    bd("0.120"), bd("8.5"), bd("12.50"), bd("300.00"),
                    lineDiscountPct, BigDecimal.ZERO, lineDiscountPct, lineDiscountAmount, lineNet,
                    lineNet.divide(bd("24"), 4, RoundingMode.HALF_UP),
                    bd("6.00"), bd("144.00"), lineNet.subtract(bd("144.00")),
                    bd("49.47"), 48, bd("7.5"), 800, true, true, 0,
                    "2026-09-07", "2026-W37", "Uit voorraad leverbaar"));
        }

        BigDecimal gross = bd("300.00").multiply(BigDecimal.valueOf(lineCount));
        BigDecimal lineDiscount = lineDiscountAmount.multiply(BigDecimal.valueOf(lineCount));
        BigDecimal subtotal = gross.subtract(lineDiscount);
        BigDecimal orderDiscount = subtotal.multiply(bd("0.03"))
                .setScale(2, RoundingMode.HALF_UP);
        BigDecimal afterOrder = subtotal.subtract(orderDiscount);
        BigDecimal extraDiscount = afterOrder.multiply(bd("0.05"))
                .setScale(2, RoundingMode.HALF_UP);
        BigDecimal goods = afterOrder.subtract(extraDiscount);
        BigDecimal freight = bd("220.00");
        BigDecimal handling = bd("35.00");
        BigDecimal total = goods.add(freight).add(handling);
        BigDecimal vat = total.multiply(bd("0.21")).setScale(2, RoundingMode.HALF_UP);

        PricedOrder.Totals totals = new PricedOrder.Totals(
                lineCount * 24, lineCount * 2, Math.max(1, (lineCount + 1) / 2),
                Math.max(1, (lineCount + 2) / 3), 0, 0,
                bd("14.4"), bd("180"), bd("0.120").multiply(BigDecimal.valueOf(lineCount)),
                bd("8.5").multiply(BigDecimal.valueOf(lineCount)),
                gross, lineDiscount, subtotal, bd("3"), orderDiscount,
                bd("5"), "Aalsmeer beurskorting", extraDiscount, goods,
                freight, false, handling, freight.add(handling), total,
                bd("21"), vat, total.add(vat), VatTreatment.BINNENLAND,
                null, null, bd("144.00").multiply(BigDecimal.valueOf(lineCount)),
                goods.subtract(bd("144.00").multiply(BigDecimal.valueOf(lineCount))),
                bd("49.47"), bd("1000.00"));

        return new PricedOrder(lines, totals,
                new PricedOrder.Validation(
                        bd("500"), true, BigDecimal.ZERO, true, true,
                        List.of(), List.of(), List.of(), null));
    }

    private static List<QuoteDocumentRenderer.PackingItem> packingItems(int from, int through) {
        List<QuoteDocumentRenderer.PackingItem> items = new ArrayList<>();
        for (int index = from; index <= through; index++) {
            items.add(new QuoteDocumentRenderer.PackingItem(
                    "Preserved rose glass bowl kleur " + index, 2, 24));
        }
        return items;
    }

    private static Product productWithPrintableMasterData() {
        return new Product(
                1L, "ER-GLASS-001", "Counter display premium",
                new Dimensions(bd("10"), bd("10"), bd("8")), "Red",
                "Preserved rose display", 1L, 1L, true,
                new Barcodes("8712345678906", "8712345678913"), "0603",
                new Carton(new Dimensions(bd("40"), bd("30"), bd("20")),
                        12, bd("6.2")),
                bd("4.00"), Currency.USD, BigDecimal.ZERO,
                bd("6.00"), "PO-1", bd("45"), bd("12.50"), 480,
                List.of(), List.of());
    }

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }
}
