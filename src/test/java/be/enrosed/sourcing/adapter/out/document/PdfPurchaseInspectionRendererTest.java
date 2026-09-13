package be.enrosed.sourcing.adapter.out.document;

import be.enrosed.catalog.adapter.out.document.PdfImageEncoder;
import be.enrosed.catalog.application.ProductService;
import be.enrosed.catalog.application.ProductSupplierAgreementPhotoService;
import be.enrosed.catalog.application.ProductSupplierAgreementService;
import be.enrosed.catalog.domain.*;
import be.enrosed.shared.Brand;
import be.enrosed.shared.Currency;
import be.enrosed.shared.Language;
import be.enrosed.shared.NotFoundException;
import be.enrosed.shared.PdfFonts;
import be.enrosed.shared.company.CompanyProfileService;
import be.enrosed.sourcing.application.CurrencyConverter;
import be.enrosed.sourcing.application.LandedCostCalculator;
import be.enrosed.sourcing.application.port.out.SourcingRepositories;
import be.enrosed.sourcing.domain.PurchaseOrder;
import be.enrosed.sourcing.domain.PurchaseOrderLine;
import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

@QuarkusTest
class PdfPurchaseInspectionRendererTest {
    @Inject @Location("purchase-inspection.html") Template template;
    @Inject @Location("purchase-portrait.html") Template portrait;
    @Inject @Location("purchase.html") Template landscape;
    @Inject Brand brand;
    @Inject PdfFonts fonts;
    @Inject PdfImageEncoder encoder;
    @Inject CompanyProfileService company;

    ProductService products;
    ProductSupplierAgreementService agreements;
    ProductSupplierAgreementPhotoService photos;
    PdfPurchaseInspectionRenderer renderer;
    Product red;
    Product white;
    byte[] image;
    ProductSupplierAgreementService.ResolvedAgreement shared;

    @BeforeEach
    void fixture() throws Exception {
        products = mock(ProductService.class);
        agreements = mock(ProductSupplierAgreementService.class);
        photos = mock(ProductSupplierAgreementPhotoService.class);
        renderer = new PdfPurchaseInspectionRenderer(template, brand, fonts, products, agreements, photos, encoder);
        try (var resource = getClass().getResourceAsStream("/seed-images/P05.jpg")) {
            assertNotNull(resource);
            image = resource.readAllBytes();
        }
        red = product(1L, "Red"); white = product(2L, "White");
        when(products.get(1L)).thenReturn(red); when(products.get(2L)).thenReturn(white);
        when(products.list()).thenReturn(List.of(red, white));
        when(products.photoData("authentic-reference.jpg")).thenAnswer(call -> new ByteArrayInputStream(image));
        var photo = new ProductSupplierAgreementPhotoService.AgreementPhoto(90L, 1L, 7L, 0,
                "Check cushioning and protective inner packaging.", "P05.jpg", "image/jpeg", image.length, 800, 800);
        when(photos.open(1L, 90L)).thenAnswer(call ->
                new ProductSupplierAgreementPhotoService.AgreementPhotoFile(photo, new ByteArrayInputStream(image)));
        when(photos.list(1L)).thenReturn(List.of(photo));
        shared = new ProductSupplierAgreementService.ResolvedAgreement("product:1:supplier:7", 1L, 7L, 50L,
                "SHARED-INSTRUCTION: Use white inner boxes.\n- Check glass for cracks & scratches.\n- Preserve the approved rose colour.",
                List.of(photo), List.of(new ProductSupplierAgreementService.Variant(1L, red.sku(), red.name(), "Red"),
                new ProductSupplierAgreementService.Variant(2L, white.sku(), white.name(), "White")), true);
        when(agreements.resolve(anyLong())).thenReturn(shared);
    }

    @Test
    void inspectionEndpointRequiresAuthentication() {
        io.restassured.RestAssured.given().get("/api/purchase-orders/999999999/inspection.pdf")
                .then().statusCode(401);
    }

    @Test
    @io.quarkus.test.security.TestSecurity(user = "inspection-reader", roles = {"user"})
    void inspectionEndpointRequiresAdminRole() {
        io.restassured.RestAssured.given().get("/api/purchase-orders/999999999/inspection.pdf")
                .then().statusCode(403);
    }

    @Test
    @io.quarkus.test.security.TestSecurity(user = "inspection-admin", roles = {"admin"})
    void inspectionEndpointValidatesLanguageAndMissingOrder() {
        io.restassured.RestAssured.given().queryParam("language", "DE")
                .get("/api/purchase-orders/999999999/inspection.pdf").then().statusCode(400);
        io.restassured.RestAssured.given().get("/api/purchase-orders/999999999/inspection.pdf")
                .then().statusCode(404);
    }

    @Test
    void actualPdfKeepsEachOrderedLineAndOneSharedInstructionWithoutFinancialData() throws Exception {
        var order = order(List.of(line(101, 1, 90, 96), line(102, 2, 48, 48), line(103, 1, 12, 24)));
        var prepared = renderer.prepare(order, PdfPurchaseRendererRenderTest.supplier(), options(Language.EN, true, true));
        assertEquals(List.of(96, 48, 24), prepared.lines().stream().map(PdfPurchaseInspectionRenderer.InspectionLine::ordered).toList());
        assertEquals(168, prepared.orderedPieces());
        assertEquals(1, prepared.agreements().size());
        assertEquals(3, prepared.agreements().getFirst().appliesTo().size());
        assertEquals(List.of("A1", "A1", "A1"), prepared.lines().stream().map(PdfPurchaseInspectionRenderer.InspectionLine::agreementReference).toList());
        assertTrue(prepared.lines().getFirst().facts().stream().anyMatch(fact -> fact.label().equals("Product barcode")
                && fact.value().equals("8712345678906")), "Editable inner barcode must remain authoritative");
        var document = renderer.render(order, PdfPurchaseRendererRenderTest.supplier(), options(Language.EN, true, true));
        writePreview("inspection-en.pdf", document.content());
        try (var pdf = Loader.loadPDF(document.content())) {
            assertTrue(pdf.getNumberOfPages() >= 4);
            assertTrue(pdf.getNumberOfPages() <= 6, "Compact fixture must not create sparse surplus pages");
            String text = new PDFTextStripper().getText(pdf);
            assertTrue(text.contains("Pre-shipment inspection"), text);
            assertTrue(text.contains("Size option: Large"), text);
            assertTrue(text.contains("40HQ · 40' High Cube"), text);
            assertFalse(text.contains("74: Groot"), text);
            assertEquals(1, occurrences(text, "SHARED-INSTRUCTION"));
            for (String visible : List.of("168", "PO/2026/INSPECT", "SKU-1", "SKU-2", "Actual sampled pcs",
                    "Defect and evidence log", "No results or approval have been recorded", "Ningbo", "Rotterdam")) {
                assertTrue(text.replaceAll("\\s+", " ").contains(visible), visible);
            }
            for (String secret : List.of("9876.54", "9,876.54", "9.876,54", "INTERNAL-PAYMENT-NOTE",
                    "INTERNAL-PROFIT-ALIAS", "USD", "EUR", "€", "Aanbetaling", "Enrosed-kost")) {
                assertFalse(text.contains(secret), "Inspector PDF leaked " + secret);
            }
            assertTrue(imageCount(pdf) >= 4, "Logo, genuine product photos and agreement evidence must be embedded");
        }
        verify(photos, times(2)).open(1L, 90L); // One per export/prepare, not once per colour.
    }

    @Test
    void photoTogglesRemainIndependentAndDutchLabelsRender() throws Exception {
        var order = order(List.of(line(1, 1, 48, 48)));
        var withoutProducts = renderer.prepare(order, null, options(Language.NL, false, true));
        assertNull(withoutProducts.lines().getFirst().photo());
        assertEquals(1, withoutProducts.agreements().getFirst().photos().size());
        var withoutAgreements = renderer.prepare(order, null, options(Language.NL, true, false));
        assertNotNull(withoutAgreements.lines().getFirst().photo());
        assertTrue(withoutAgreements.agreements().isEmpty());
        var document = renderer.render(order, PdfPurchaseRendererRenderTest.supplier(), options(Language.NL, true, true));
        writePreview("inspection-nl.pdf", document.content());
        try (var pdf = Loader.loadPDF(document.content())) {
            String text = new PDFTextStripper().getText(pdf);
            assertTrue(text.contains("Inspectie vóór verzending"), text);
            assertTrue(text.contains("Werkelijk onderzochte stuks"), text);
            assertTrue(text.contains("Blanco inspectieformulier"), text);
        }
    }

    @Test
    void missingMasterDataAndSupplierMismatchNeverInventSpecificationsOrAgreements() throws Exception {
        when(products.get(3L)).thenThrow(new NotFoundException("Product", 3));
        var minimal = copy(red, Map.of("carton", new Carton(Dimensions.empty(), 0, null),
                "dimensions", Dimensions.empty(), "packaging", Packaging.none()));
        when(products.get(1L)).thenReturn(minimal);
        when(agreements.resolve(1L)).thenReturn(new ProductSupplierAgreementService.ResolvedAgreement(
                "different-supplier", 1L, 99L, 50L, "PRIVATE-OTHER-SUPPLIER", List.of(), List.of(), true));
        var order = copy(order(List.of(line(1, 1, 13, 13), line(2, 3, 7, 7))),
                new java.util.HashMap<>() {{ put("departurePort", null); put("destinationPort", null); }});
        var prepared = renderer.prepare(order, null, options(Language.EN, false, true));
        assertEquals("-", prepared.lines().getFirst().piecesPerCarton());
        assertEquals("-", prepared.lines().getFirst().cartons());
        assertEquals("Product record unavailable #3", prepared.lines().get(1).name());
        assertEquals("-", prepared.shipment().stream().filter(f -> f.label().equals("Port of loading")).findFirst().orElseThrow().value());
        assertTrue(prepared.agreements().isEmpty());
        when(agreements.resolve(1L)).thenReturn(new ProductSupplierAgreementService.ResolvedAgreement(
                "empty", 1L, 7L, 50L, "  ", List.of(), List.of(), true));
        assertTrue(renderer.prepare(order, null, options(Language.EN, false, true)).agreements().isEmpty());
        verifyNoInteractions(photos);
    }

    @Test
    void longSharedGroupPaginatesAndEscapesInstructionsWithoutLosingText() throws Exception {
        String longNote = "<script>not executable</script> & approved <reference>\n"
                + "Inspect each compartment and confirm the cushioning. ".repeat(65) + "END-OF-INSTRUCTION";
        assertEquals(longNote.replaceAll("\\s+", " ").strip(),
                String.join(" ", PdfPurchaseInspectionRenderer.paragraphs(longNote)).replace("\u200b", "")
                        .replaceAll("\\s+", " ").strip(), "Pagination must not split words or alter the instruction");
        when(agreements.resolve(anyLong())).thenReturn(new ProductSupplierAgreementService.ResolvedAgreement(
                shared.groupKey(), 1L, 7L, 50L, longNote, List.of(), shared.variants(), true));
        List<PurchaseOrderLine> lines = new ArrayList<>();
        for (int i = 1; i <= 30; i++) lines.add(line(i, i % 2 + 1, 24, 24));
        var brief = renderer.prepare(order(lines), null, options(Language.EN, false, true));
        String html = template.data("brief", brief).data("t", PdfPurchaseInspectionRenderer.labels(Language.EN))
                .data("logo", brand.logoDataUri()).data("language", "en").render();
        assertTrue(html.contains("&lt;script&gt;not executable&lt;/script&gt;"), html);
        assertFalse(html.contains("<script>"));
        var document = renderer.render(order(lines), null, options(Language.EN, false, true));
        writePreview("inspection-long-shared-group.pdf", document.content());
        try (var pdf = Loader.loadPDF(document.content())) {
            String text = new PDFTextStripper().getText(pdf).replaceAll("\\s+", " ");
            assertEquals(1, occurrences(text, "END-OF-INSTRUCTION"));
            assertTrue(text.contains("Line 30 · SKU-1"), text);
            assertTrue(text.contains("Inspector name / signature / date"));
            var pageText = new PDFTextStripper();
            for (int page = 1; page <= pdf.getNumberOfPages(); page++) {
                pageText.setStartPage(page); pageText.setEndPage(page);
                String current = pageText.getText(pdf);
                if (current.contains("Inspect each compartment")) {
                    assertTrue(current.contains("SUPPLIER INSTRUCTION A1"),
                            "Shared instruction identity must repeat on continuation page " + page);
                }
            }
            for (var page : pdf.getPages()) assertEquals(595, Math.round(page.getMediaBox().getWidth()));
        }
    }

    @Test
    void supplierPdfAlsoSharesInstructionsButPreservesDistinctSameProductOrderLines() throws Exception {
        var repository = mock(SourcingRepositories.PurchaseOrders.class);
        when(repository.findAll()).thenReturn(List.of());
        var supplierRenderer = new PdfPurchaseRenderer(landscape, portrait, brand, company, fonts, products,
                encoder, mock(CurrencyConverter.class), mock(LandedCostCalculator.class), photos, repository);
        supplierRenderer.effectiveSupplierAgreements = agreements;
        var order = order(List.of(line(1, 1, 48, 48), line(2, 2, 24, 24), line(3, 1, 12, 12)));
        var document = supplierRenderer.render(order, PdfPurchaseRendererRenderTest.costing(2),
                PdfPurchaseRendererRenderTest.supplier(), false, List.of(), null,
                PdfPurchaseRenderer.Layout.PORTRAIT, PdfPurchaseRenderer.Audience.SUPPLIER);
        try (var pdf = Loader.loadPDF(document.content())) {
            String text = new PDFTextStripper().getText(pdf).replaceAll("\\s+", " ");
            assertEquals(1, occurrences(text, "SHARED-INSTRUCTION"));
            assertTrue(text.contains("Line 1") && text.contains("48 pcs"), text);
            assertTrue(text.contains("Line 3") && text.contains("12 pcs"), text);
        }
        verify(photos, times(1)).open(1L, 90L);
    }

    private static Product product(long id, String colour) {
        return new Product(id, "SKU-" + id, "Preserved rose in glass bowl", new Dimensions(bd("18"), bd("18"), bd("22")),
                new Packaging(PackagingKind.GIFT_BOX, new Dimensions(bd("20"), bd("20"), bd("25")), "8712345678913", 1),
                colour, "Large", null, "UNUSED MARKETING COPY", null, 7L, true, 50L, null, "8712345678999", 0, true,
                "inspection-family", "inspection-family", PublicationState.DRAFT, PublicationState.DRAFT,
                new Barcodes("8712345678906", "8712345678920"), "7013.99.00",
                new Carton(new Dimensions(bd("40"), bd("40"), bd("30")), 12, bd("6.2")),
                bd("9876.54"), Currency.USD, bd("9876.54"), bd("9876.54"), "PRIVATE COST SOURCE", bd("25"), bd("9876.54"), 0,
                List.of(new Photo(1L, "authentic-reference.jpg", "P05.jpg", "image/jpeg", 1000, 800, 800, 0)),
                List.of(new ProductText(Language.EN, "Preserved rose in glass bowl", null, colour, "Large"),
                        new ProductText(Language.NL, "Gepreserveerde roos in glazen bowl", null,
                        colour.equals("Red") ? "Rood" : "Wit", "Groot")), false);
    }

    private static PurchaseOrder order(List<PurchaseOrderLine> lines) throws Exception {
        return copy(PdfPurchaseRendererRenderTest.order(1), Map.of("number", "PO/2026/INSPECT",
                "lines", lines, "notes", "INTERNAL-PAYMENT-NOTE", "alias", "INTERNAL-PROFIT-ALIAS"));
    }
    private static PurchaseOrderLine line(long id, long product, int current, int ordered) {
        return new PurchaseOrderLine(id, product, current, bd("9876.54"), Currency.USD, bd("9876.54"), ordered);
    }
    private static PdfPurchaseInspectionRenderer.Options options(Language language, boolean photos, boolean agreements) {
        return new PdfPurchaseInspectionRenderer.Options(language, photos, agreements);
    }
    private static BigDecimal bd(String value) { return new BigDecimal(value); }
    @SuppressWarnings("unchecked")
    private static <T> T copy(T source, Map<String, Object> updates) throws Exception {
        var components = source.getClass().getRecordComponents();
        Object[] values = new Object[components.length];
        for (int i = 0; i < components.length; i++) values[i] = updates.containsKey(components[i].getName())
                ? updates.get(components[i].getName()) : components[i].getAccessor().invoke(source);
        return (T) source.getClass().getDeclaredConstructor(Arrays.stream(components)
                .map(java.lang.reflect.RecordComponent::getType).toArray(Class[]::new)).newInstance(values);
    }
    private static int occurrences(String source, String part) { return source.split(java.util.regex.Pattern.quote(part), -1).length - 1; }
    private static int imageCount(org.apache.pdfbox.pdmodel.PDDocument pdf) throws Exception {
        int count = 0;
        for (var page : pdf.getPages()) for (var name : page.getResources().getXObjectNames()) {
            if (page.getResources().getXObject(name) instanceof org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject) count++;
        }
        return count;
    }
    private static void writePreview(String filename, byte[] content) throws Exception {
        Path directory = Path.of("/private/tmp/enrosed-inspection-pdf-qa");
        Files.createDirectories(directory); Files.write(directory.resolve(filename), content);
    }
}
