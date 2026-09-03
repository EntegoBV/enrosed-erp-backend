package be.enrosed.sourcing.adapter.out.document;

import be.enrosed.catalog.application.ProductService;
import be.enrosed.catalog.application.ProductSupplierAgreementPhotoService;
import be.enrosed.catalog.application.StockService;
import be.enrosed.catalog.domain.Barcodes;
import be.enrosed.catalog.domain.Carton;
import be.enrosed.catalog.domain.Dimensions;
import be.enrosed.catalog.domain.Packaging;
import be.enrosed.catalog.domain.PackagingKind;
import be.enrosed.catalog.domain.Product;
import be.enrosed.catalog.domain.PublicationState;
import be.enrosed.catalog.domain.StockLocation;
import be.enrosed.shared.Currency;
import be.enrosed.shared.security.ActorRef;
import be.enrosed.sourcing.application.PurchaseOrderService;
import be.enrosed.sourcing.domain.Allocation;
import be.enrosed.sourcing.domain.ContainerType;
import be.enrosed.sourcing.domain.LandedCost;
import be.enrosed.sourcing.domain.PaymentTerms;
import be.enrosed.sourcing.domain.PurchaseOrder;
import be.enrosed.sourcing.domain.PurchaseOrderLine;
import be.enrosed.sourcing.domain.PurchaseOrderStatus;
import be.enrosed.sourcing.domain.PurchasePayment;
import be.enrosed.sourcing.domain.Supplier;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.awt.image.BufferedImage;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Renders the internal purchase dossier end to end.
 *
 * The many-line order is the important one: rows must move to the next page
 * as a whole (no line split over a page edge) and the column header must
 * return on every page. PDFBox reads the result back to prove the pages and
 * the content are there.
 */
@QuarkusTest
class PdfPurchaseRendererRenderTest {

    @Inject PdfPurchaseRenderer renderer;
    @Inject ProductService products;
    @Inject ProductSupplierAgreementPhotoService supplierAgreementPhotos;
    @Inject StockService stock;

    @Test
    void internalDossierSurvivesManyLinesAcrossPages() throws Exception {
        int lineCount = 28;
        PurchaseOrder order = order(lineCount);
        LandedCost costing = costing(lineCount);
        PdfPurchaseRenderer.Document document = renderer.render(
                order, costing, supplier(), true, payments(),
                new PurchaseOrderService.Payable(
                        new BigDecimal("34469.88"), new BigDecimal("10240.93"),
                        new BigDecimal("2500.00"), false, false),
                PdfPurchaseRenderer.Layout.LANDSCAPE);

        Path preview = Path.of("target", "pdf-preview");
        Files.createDirectories(preview);
        Files.write(preview.resolve("purchase-landscape-internal-many-lines.pdf"), document.content());

        try (PDDocument pdf = Loader.loadPDF(document.content())) {
            assertTrue(pdf.getNumberOfPages() >= 2,
                    "28 regels horen niet op één pagina te passen");
            assertTrue(pdf.getPage(0).getMediaBox().getWidth()
                            > pdf.getPage(0).getMediaBox().getHeight(),
                    "de interne calculatie hoort landscape te blijven");
            /* Kickers and card titles print uppercase (CSS text-transform),
               so the extracted text is compared in lowercase. */
            String text = new PDFTextStripper().getText(pdf)
                    .toLowerCase().replaceAll("\\s+", " ");
            assertTrue(text.contains("interne calculatie"));
            assertTrue(text.contains("preserved rose with stem - rood 1"));
            assertTrue(text.contains("preserved rose with stem - rood " + lineCount));
            assertTrue(text.contains("betaalplan"));
            assertTrue(text.contains("dagboek"));
            /* PDFBox extracts the label and value columns separately; assert both facts rather
               than a visual adjacency that text extraction cannot preserve. */
            assertTrue(text.contains("aangemaakt door"), text);
            assertTrue(text.contains("emre"), text);
        }
    }

    @Test
    @TestTransaction
    void portraitDefaultKeepsCoreOrderFactsAndHidesEveryOptionalField() throws Exception {
        Product product = createProductWithPhoto();
        PurchaseOrder order = portraitOrder(product.id());
        LandedCost costing = portraitCosting(product.id());
        PdfPurchaseRenderer.Document document = renderer.render(
                order, costing, supplier(), true, payments(),
                new PurchaseOrderService.Payable(
                        new BigDecimal("1125.00"), new BigDecimal("480.00"),
                        new BigDecimal("375.00"), false, false),
                PdfPurchaseRenderer.Layout.PORTRAIT);

        Path preview = Path.of("target", "pdf-preview");
        Files.createDirectories(preview);
        Files.write(preview.resolve("purchase-portrait-supplier-read.pdf"), document.content());

        try (PDDocument pdf = Loader.loadPDF(document.content())) {
            assertEquals(1, pdf.getNumberOfPages());
            assertTrue(pdf.getPage(0).getMediaBox().getWidth()
                            < pdf.getPage(0).getMediaBox().getHeight(),
                    "de leveranciersleesversie hoort portrait te zijn");
            assertTrue(imageCount(pdf) >= 2,
                    "logo en server-embedded productfoto horen beide in de PDF te staan");
            assertPrintSafeTop(pdf, 11);

            String text = new PDFTextStripper().getText(pdf)
                    .toLowerCase().replaceAll("\\s+", " ");
            assertTrue(text.contains("inkooporder"), text);
            assertTrue(text.contains("route & levering"), text);
            assertFalse(text.contains("inkooporder voor controle"), text);
            assertFalse(text.contains("controleer voor verzending"), text);
            assertFalse(text.contains("uitgiftesnapshot"), text);
            assertTrue(text.contains("96"),
                    "de geplaatste-order snapshot moet zichtbaar blijven na ontvangst");
            assertTrue(text.replace(" ", "").contains("stuksperkarton"), text);
            assertTrue(text.contains("b × d × h in cm"), "the axis order is said once above the table: " + text);
            assertTrue(text.contains("product 18 × 18 × 22 cm"), text);
            assertTrue(text.contains("verpakking") && text.contains("20 × 20 × 25 cm"), text);
            assertFalse(text.contains("omdoos b × d × h"), text);
            assertFalse(text.contains("barcode"), text);
            assertFalse(text.contains("glass bowls"),
                    "de interne containernaam hoort niet op de inkooporder: " + text);
            assertFalse(text.contains("culinan preserved flowers"),
                    "de leverancier is een expliciete exportkeuze: " + text);
            assertFalse(text.contains("betalingsafspraak"),
                    "de betalingsafspraak is een expliciete exportkeuze: " + text);
            assertFalse(text.contains("per stuk"), text);
            assertFalse(text.contains("regeltotaal"), text);
            assertFalse(text.contains("12,50"), text);
            assertFalse(text.contains("1.200,00"), text);
            assertFalse(text.contains("1.125,00"),
                    "90 ontvangen mag het afgesproken ordertotaal niet herschrijven");
            assertFalse(text.contains("interne inkoopcalculatie"), text);
            assertFalse(text.contains("enrosed-kost"), text);
            assertFalse(text.contains("douanewaarde"), text);
            assertFalse(text.contains("invoerrechten"), text);
            assertFalse(text.contains("betaalplan"), text);
            assertFalse(text.contains("geregistreerde betalingen"), text);
            assertFalse(text.contains("dagboek"), text);
            assertFalse(text.contains("aangemaakt door"), text);
            assertFalse(text.contains("emre"), text);
            assertFalse(text.contains("10.344,47"),
                    "geregistreerde leveranciersbetaling mag niet uitlekken");
            assertFalse(text.contains("24.136,80"),
                    "interne betaalstand mag niet uitlekken");
        }
    }

    @Test
    @TestTransaction
    void portraitCanIncludeOuterCartonAndBarcodesIndependently() throws Exception {
        Product product = createProductWithPhoto();
        PurchaseOrder order = portraitOrder(product.id());
        LandedCost costing = portraitCosting(product.id());
        PdfPurchaseRenderer.Document document = renderer.render(
                order, costing, supplier(), false, payments(),
                new PurchaseOrderService.Payable(
                        new BigDecimal("1125.00"), new BigDecimal("480.00"),
                        new BigDecimal("375.00"), false, false),
                PdfPurchaseRenderer.Layout.PORTRAIT,
                PdfPurchaseRenderer.Audience.STANDARD,
                new PdfPurchaseRenderer.PdfOptions(
                        false, false, false, false, false, false,
                        false, false, false, false, true, true));

        try (PDDocument pdf = Loader.loadPDF(document.content())) {
            String text = new PDFTextStripper().getText(pdf)
                    .toLowerCase().replaceAll("\\s+", " ");
            assertTrue(text.contains("b × d × h in cm"), "the axis order is said once above the table: " + text);
            assertTrue(text.contains("product 18 × 18 × 22 cm"), text);
            assertTrue(text.contains("verpakking") && text.contains("20 × 20 × 25 cm"), text);
            assertTrue(text.contains("omdoos 40 × 40 × 30 cm"), text);
            assertTrue(text.contains("12 stuks per karton"), text);
            assertTrue(text.contains("8712345678906"), text);
            assertTrue(text.contains("8712345678913"), text);
            assertTrue(text.contains("8712345678920"), text);
        }
    }

    @Test
    @TestTransaction
    void explicitSupplierAudienceRendersOnlyAgreedUnitAndOperationalProductFacts() throws Exception {
        Product product = createProductWithPhoto();
        try (InputStream image = getClass().getResourceAsStream("/seed-images/P05.jpg")) {
            if (image == null) throw new IllegalStateException("Testfoto P05.jpg ontbreekt");
            supplierAgreementPhotos.upload(product.id(), "approved-packing.jpg", image,
                    "Use this exact inner-box layout and protective spacing.");
        }
        PurchaseOrder order = portraitOrder(product.id());
        order = withLines(order, order.number(), "SECRET CUSTOMER PROJECT", order.lines());
        LandedCost costing = portraitCosting(product.id());
        PdfPurchaseRenderer.Document document = renderer.render(
                order, costing, supplier(), true, payments(),
                new PurchaseOrderService.Payable(
                        new BigDecimal("1125.00"), new BigDecimal("480.00"),
                        new BigDecimal("375.00"), false, false),
                PdfPurchaseRenderer.Layout.PORTRAIT,
                PdfPurchaseRenderer.Audience.SUPPLIER,
                new PdfPurchaseRenderer.PdfOptions(false, false, true, true, true));

        assertEquals("PO-2026-PORTRAIT-supplier.pdf", document.filename());
        Path preview = Path.of("target", "pdf-preview");
        Files.createDirectories(preview);
        Files.write(preview.resolve("purchase-portrait-explicit-supplier.pdf"), document.content());

        try (PDDocument pdf = Loader.loadPDF(document.content())) {
            assertEquals(2, pdf.getNumberOfPages(),
                    "de leveranciersorder hoort productregels op pagina 1 en afspraken op pagina 2 te hebben");
            assertTrue(pdf.getPage(0).getMediaBox().getWidth()
                            < pdf.getPage(0).getMediaBox().getHeight(),
                    "de leveranciersorder hoort altijd portrait te zijn");
            String text = new PDFTextStripper().getText(pdf)
                    .toLowerCase().replaceAll("\\s+", " ");

            assertTrue(text.contains("purchase order agreement"), text);
            assertTrue(text.contains("culinan preserved flowers"),
                    "portrait options must not alter the fixed supplier contract: " + text);
            assertTrue(text.contains("glass bowl bestseller"), text);
            assertTrue(text.contains("po-pdf-thumbnail"), text);
            assertTrue(text.contains("8712345678906"), text);
            assertTrue(text.contains("use white inner boxes"), text);
            assertTrue(text.contains("use this exact inner-box layout"), text);
            assertTrue(text.contains("sizes w × d × h in cm"), text);
            assertTrue(text.contains("product 18 × 18 × 22 cm"), text);
            assertTrue(text.replace(" ", "").contains("packaging20×20×25cm"), text);
            assertTrue(text.contains("0,05 m³") && text.contains("0,38 m³"),
                    "the carton volume and the line volume both print: " + text);
            assertTrue(text.replace(" ", "").contains("carton40×40×30cm"), text);
            assertTrue(text.contains("8712345678913"), text);
            assertTrue(text.contains("8712345678920"), text);
            assertFalse(text.contains("omdoos"), text);
            assertTrue(text.contains("96"), text);
            assertTrue(text.contains("8"), text);
            assertTrue(text.contains("0,05 m³"), text);
            assertTrue(text.contains("12,50"), text);
            assertTrue(text.contains("usd") && text.contains("exw"), text);
            assertFalse(text.contains("fob"),
                    "alleen de prijsbasis van de productregel is gezaghebbend: " + text);
            assertTrue(text.contains("quality and 1% maximum tolerance"), text);
            assertTrue(text.contains("no more than 1% damaged"), text);
            assertTrue(text.contains("shipping schedule and deviations"), text);
            assertTrue(text.contains("authorised signatures"), text);
            assertTrue(text.contains("buyer initials"), text);
            assertTrue(text.contains("supplier initials"), text);
            assertTrue(imageCount(pdf) >= 2,
                    "product photo and agreement reference must be embedded in the supplier agreement");
            PDFTextStripper secondPageStripper = new PDFTextStripper();
            secondPageStripper.setStartPage(2);
            secondPageStripper.setEndPage(2);
            String secondPage = secondPageStripper.getText(pdf).toLowerCase();
            assertTrue(secondPage.contains("authorised signatures"), secondPage);
            assertFalse(text.contains("99,99"),
                    "de actuele productprijs mag de afgesproken orderregelsnapshot niet vervangen: " + text);

            assertFalse(text.contains("regeltotaal"), text);
            assertFalse(text.contains("ordertotaal"), text);
            assertFalse(text.contains("1.200,00"), text);
            assertFalse(text.contains("1.125,00"), text);
            assertFalse(text.contains("douanewaarde"), text);
            assertFalse(text.contains("invoerrechten"), text);
            assertFalse(text.contains("opbrengst"), text);
            assertFalse(text.contains("betaal"), text);
            assertFalse(text.contains("aangemaakt door"), text);
            assertFalse(text.contains("10.344,47"), text);
            assertFalse(text.contains("24.136,80"), text);
            assertFalse(text.contains("intern"),
                    "de leveranciers-PDF mag geen interne labels of aanduidingen tonen: " + text);
            assertFalse(text.contains("supplier-safe"), text);
            assertFalse(text.contains("onderweg"), text);
            assertFalse(text.contains("a4 staand"), text);
            assertFalse(text.contains("inkooporder verticaal"), text);
            assertFalse(text.contains("order voor de leverancier"), text);
            assertFalse(text.contains("controleer dit voor delen"), text);
            assertFalse(text.contains("actuele enrosed-dossier"), text);
            assertFalse(text.contains("uitgiftesnapshot"), text);
            assertFalse(text.contains("secret customer project"), text);
            assertFalse(text.contains("vracht en bijkomende logistiek"), text);
        }
    }

    @Test
    @TestTransaction
    void portraitOptionsShowSubtleEurWhileHidingSupplierAndLegacyFreight() throws Exception {
        Product product = createProductWithPhoto();
        StockLocation zaltbommel = stock.saveLocation(new StockLocation(
                null, "ZALTBOMMEL", "Zaltbommel", StockLocation.Kind.WAREHOUSE,
                "Zaltbommel", true, false, false, 10));
        PurchaseOrder order = withReceivingLocation(
                portraitOrder(product.id()), zaltbommel.id());
        PdfPurchaseRenderer.Document document = renderer.render(
                order, portraitCosting(product.id()), supplier(), false, payments(),
                new PurchaseOrderService.Payable(
                        new BigDecimal("1125.00"), new BigDecimal("480.00"),
                        new BigDecimal("375.00"), false, false),
                PdfPurchaseRenderer.Layout.PORTRAIT,
                PdfPurchaseRenderer.Audience.STANDARD,
                new PdfPurchaseRenderer.PdfOptions(false, true, true, true, true));

        Path preview = Path.of("target", "pdf-preview");
        Files.createDirectories(preview);
        Files.write(preview.resolve("purchase-portrait-options.pdf"), document.content());

        try (PDDocument pdf = Loader.loadPDF(document.content())) {
            assertEquals(1, pdf.getNumberOfPages());
            String text = new PDFTextStripper().getText(pdf)
                    .toLowerCase().replaceAll("\\s+", " ");
            assertFalse(text.contains("culinan preserved flowers"), text);
            assertFalse(text.contains("lily@culinan.cn"), text);
            assertTrue(text.contains("12,50") && text.contains("usd"), text);
            assertTrue(text.contains("ca. 11,125 eur"), text);
            assertTrue(text.contains("ca. 1.068,00 eur"), text);
            assertFalse(text.contains("vracht en bijkomende logistiek"), text);
            assertFalse(text.contains("lokale kosten china"), text);
            assertFalse(text.contains("zeevracht"), text);
            assertFalse(text.contains("invoerrechten"), text);
            assertFalse(text.contains("7.435,24 eur"), text);
            assertFalse(text.contains("8.503,24 eur"), text);
        }
    }

    @Test
    @TestTransaction
    void portraitCanShowOnlyEurPricesAndTotalDeliveryCostTogether() throws Exception {
        Product product = createProductWithPhotoForAllInCost();
        PurchaseOrder order = portraitOrder(product.id());
        PdfPurchaseRenderer.Document document = renderer.render(
                order, portraitCosting(product.id()), supplier(), false, payments(),
                new PurchaseOrderService.Payable(
                        new BigDecimal("1125.00"), new BigDecimal("480.00"),
                        new BigDecimal("375.00"), false, false),
                PdfPurchaseRenderer.Layout.PORTRAIT,
                PdfPurchaseRenderer.Audience.STANDARD,
                new PdfPurchaseRenderer.PdfOptions(
                        true, true, true, true, false, false, true));

        Path preview = Path.of("target", "pdf-preview");
        Files.createDirectories(preview);
        Files.write(preview.resolve("purchase-portrait-eur-only-total-cost.pdf"), document.content());

        try (PDDocument pdf = Loader.loadPDF(document.content())) {
            assertEquals(1, pdf.getNumberOfPages());
            String text = new PDFTextStripper().getText(pdf)
                    .toLowerCase().replaceAll("\\s+", " ");
            assertTrue(text.contains("11,125"), text);
            assertTrue(text.contains("1.068,00 eur"), text);
            assertTrue(text.contains("ordertotaal in eur"), text);
            assertTrue(text.contains("totale kost"), text);
            assertTrue(text.contains("t/m levering"), text);
            assertTrue(text.contains("8.843,03 eur"), text);
            assertFalse(text.contains("12,50"), text);
            assertFalse(text.contains("1.200,00"), text);
            assertFalse(text.contains(" usd"), text);
            assertFalse(text.contains("ca. 11,125"), text);
            assertFalse(text.contains("enrosed-kost"), text);
        }
    }

    @Test
    @TestTransaction
    void portraitCanShowTotalCostThroughDeliveryWithoutSeparateCostLegs() throws Exception {
        Product product = createProductWithPhotoForAllInCost();
        PurchaseOrder order = portraitOrder(product.id());
        PdfPurchaseRenderer.Document document = renderer.render(
                order, portraitCosting(product.id()), supplier(), false, payments(),
                new PurchaseOrderService.Payable(
                        new BigDecimal("1125.00"), new BigDecimal("480.00"),
                        new BigDecimal("375.00"), false, false),
                PdfPurchaseRenderer.Layout.PORTRAIT,
                PdfPurchaseRenderer.Audience.STANDARD,
                new PdfPurchaseRenderer.PdfOptions(true, true, false, true, true, true));

        Path preview = Path.of("target", "pdf-preview");
        Files.createDirectories(preview);
        Files.write(preview.resolve("purchase-portrait-enrosed-cost.pdf"), document.content());

        try (PDDocument pdf = Loader.loadPDF(document.content())) {
            assertEquals(1, pdf.getNumberOfPages());
            String text = new PDFTextStripper().getText(pdf)
                    .toLowerCase().replaceAll("\\s+", " ");
            assertTrue(text.contains("regeltotaal"), text);
            assertTrue(text.contains("totale kost"), text);
            assertTrue(text.contains("t/m levering"), text);
            assertTrue(text.contains("inkoop en producttoeslagen"), text);
            assertTrue(text.contains("invoerrechten"), text);
            assertFalse(text.contains("enrosed-kost"), text);
            assertTrue(text.contains("8.843,03"),
                    "de totale regelkost moet op dezelfde 96 bestelde stuks zijn berekend");
            assertFalse(text.contains("1.498,00"),
                    "de ontvangen 90 stuks mogen niet de kostenbasis van de bestel-PDF zijn");
            assertFalse(text.contains("1.597,87"),
                    "vaste vrachtkosten mogen niet via 96/90 worden opgeschaald");
            assertFalse(text.contains("inkooporder voor controle"), text);
            assertFalse(text.contains("controleer voor verzending"), text);
            assertFalse(text.contains("goederenwaarde"), text);
            assertFalse(text.contains("vracht en bijkomende logistiek"), text);
            assertFalse(text.contains("lokale kosten china"), text);
            assertFalse(text.contains("zeevracht"), text);
            assertFalse(text.contains("douanewaarde"), text);
            assertFalse(text.contains("bestemmingskosten"), text);
            assertFalse(text.contains("betaalplan"), text);
        }
    }

    @Test
    @TestTransaction
    void portraitCanShowOrderedUnitCostAndPaymentTermsIndependently() throws Exception {
        Product product = createProductWithPhotoForAllInCost();
        PurchaseOrder order = portraitOrder(product.id());
        PdfPurchaseRenderer.Document document = renderer.render(
                order, portraitCosting(product.id()), supplier(), false, payments(),
                new PurchaseOrderService.Payable(
                        new BigDecimal("1125.00"), new BigDecimal("480.00"),
                        new BigDecimal("375.00"), false, false),
                PdfPurchaseRenderer.Layout.PORTRAIT,
                PdfPurchaseRenderer.Audience.STANDARD,
                new PdfPurchaseRenderer.PdfOptions(
                        false, false, false, false, false, false,
                        false, false, true, true));

        Path preview = Path.of("target", "pdf-preview");
        Files.createDirectories(preview);
        Files.write(preview.resolve("purchase-portrait-unit-cost-payment-terms.pdf"),
                document.content());

        try (PDDocument pdf = Loader.loadPDF(document.content())) {
            assertEquals(1, pdf.getNumberOfPages());
            String text = new PDFTextStripper().getText(pdf)
                    .toLowerCase().replaceAll("\\s+", " ");
            assertTrue(text.contains("betalingsafspraak"), text);
            assertTrue(text.contains("totale kost per stuk"), text);
            assertTrue(text.contains("92,115"),
                    "de all-in kost per stuk moet op 96 bestelde stuks zijn berekend: " + text);
            assertTrue(text.contains("eur / stuk"), text);
            assertFalse(text.contains("eur / regel"), text);
            assertFalse(text.contains("8.843,03"),
                    "de regeltotaalkost is een onafhankelijke exportkeuze: " + text);
            assertFalse(text.contains("regeltotaal"), text);
            assertFalse(text.contains("12,50"), text);
            assertFalse(text.contains("culinan preserved flowers"), text);
        }
    }

    @Test
    @TestTransaction
    void portraitKeepsOrderedTotalDeliveryCostWhenSupplierPricesAreHidden() throws Exception {
        Product product = createProductWithPhotoForAllInCost();
        PurchaseOrder order = portraitOrder(product.id());
        PdfPurchaseRenderer.Document document = renderer.render(
                order, portraitCosting(product.id()), supplier(), false, payments(),
                new PurchaseOrderService.Payable(
                        new BigDecimal("1125.00"), new BigDecimal("480.00"),
                        new BigDecimal("375.00"), false, false),
                PdfPurchaseRenderer.Layout.PORTRAIT,
                PdfPurchaseRenderer.Audience.STANDARD,
                new PdfPurchaseRenderer.PdfOptions(true, false, true, true, true, true));

        try (PDDocument pdf = Loader.loadPDF(document.content())) {
            String text = new PDFTextStripper().getText(pdf)
                    .toLowerCase().replaceAll("\\s+", " ");
            assertTrue(text.contains("96"), text);
            assertTrue(text.contains("totale kost"), text);
            assertTrue(text.contains("t/m levering"), text);
            assertFalse(text.contains("enrosed-kost"), text);
            assertTrue(text.contains("8.843,03"), text);
            assertFalse(text.contains("per stuk"), text);
            assertFalse(text.contains("regeltotaal"), text);
            assertFalse(text.contains("ordertotaal per valuta"), text);
            assertFalse(text.contains("12,50"), text);
            assertFalse(text.contains("1.200,00"), text);
            assertFalse(text.contains("vracht en bijkomende logistiek"), text);
        }
    }

    @Test
    @TestTransaction
    void portraitCanHidePricesAndNormalizesDependentOptionsOff() throws Exception {
        Product product = createProductWithPhoto();
        PurchaseOrder order = portraitOrder(product.id());
        PdfPurchaseRenderer.Document document = renderer.render(
                order, portraitCosting(product.id()), supplier(), false, payments(),
                new PurchaseOrderService.Payable(
                        new BigDecimal("1125.00"), new BigDecimal("480.00"),
                        new BigDecimal("375.00"), false, false),
                PdfPurchaseRenderer.Layout.PORTRAIT,
                PdfPurchaseRenderer.Audience.STANDARD,
                new PdfPurchaseRenderer.PdfOptions(true, false, true, true, true));

        try (PDDocument pdf = Loader.loadPDF(document.content())) {
            String text = new PDFTextStripper().getText(pdf)
                    .toLowerCase().replaceAll("\\s+", " ");
            assertTrue(text.contains("culinan preserved flowers"), text);
            assertFalse(text.contains("per stuk"), text);
            assertFalse(text.contains("regeltotaal"), text);
            assertFalse(text.contains("ordertotaal per valuta"), text);
            assertFalse(text.contains("12,50"), text);
            assertFalse(text.contains("ca. 11,125 eur"), text);
            assertFalse(text.contains("vracht en bijkomende logistiek"), text);
            assertFalse(text.contains("niet bij het ordertotaal opgeteld"), text);
            assertFalse(text.contains("ordertotaal incl."), text);
            assertFalse(text.contains("1.327,06 eur"), text);
        }
    }

    @Test
    @TestTransaction
    void longSupplierNoteRemainsCompleteAndKeepsPortraitPages() throws Exception {
        String longNote = "Check every carton for colour, packing and the correct EAN. ".repeat(60)
                + "END OF NOTE VISIBLE";
        assertTrue(longNote.length() <= 4_000, "fixture moet binnen het productcontract blijven");
        Product product = createProductWithPhoto(longNote);
        PurchaseOrder order = portraitOrder(product.id());
        order = withLines(order, order.number(), "SECRET CUSTOMER PROJECT", order.lines());
        PdfPurchaseRenderer.Document document = renderer.render(
                order, portraitCosting(product.id()), supplier(), false, List.of(),
                new PurchaseOrderService.Payable(
                        BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, false, false),
                PdfPurchaseRenderer.Layout.PORTRAIT,
                PdfPurchaseRenderer.Audience.SUPPLIER);

        Path preview = Path.of("target", "pdf-preview");
        Files.createDirectories(preview);
        Files.write(preview.resolve("purchase-portrait-supplier-long-note.pdf"), document.content());

        try (PDDocument pdf = Loader.loadPDF(document.content())) {
            assertTrue(pdf.getNumberOfPages() >= 2,
                    "een bijna maximale leveranciersnotitie hoort gecontroleerd door te lopen");
            for (var page : pdf.getPages()) {
                assertTrue(page.getMediaBox().getWidth() < page.getMediaBox().getHeight(),
                        "elke vervolgpagina moet A4 portrait blijven");
            }
            String text = new PDFTextStripper().getText(pdf)
                    .toLowerCase().replaceAll("\\s+", " ");
            assertTrue(text.contains("check every carton"), text);
            assertTrue(text.contains("end of note visible"),
                    "het einde van de lange notitie mag niet worden afgesneden: " + text);
            assertFalse(text.contains("intern"), text);
            assertFalse(text.contains("a4 staand"), text);
            assertFalse(text.contains("actuele enrosed-dossier"), text);
            assertFalse(text.contains("secret customer project"), text);
            PDFTextStripper pageStripper = new PDFTextStripper();
            int notePages = 0;
            for (int pageNumber = 1; pageNumber <= pdf.getNumberOfPages(); pageNumber++) {
                pageStripper.setStartPage(pageNumber);
                pageStripper.setEndPage(pageNumber);
                String pageText = pageStripper.getText(pdf).toLowerCase();
                if (pageText.contains("agreed product instruction")) {
                    notePages++;
                    assertTrue(pageText.contains("po-pdf-thumbnail"),
                            "elk notitiefragment moet het product identificeren op pagina " + pageNumber);
                }
            }
            assertTrue(notePages >= 1,
                    "een lange notitie moet zichtbaar blijven met een identificeerbare productkop");
        }
    }

    @Test
    @TestTransaction
    void supplierNoteNeverLeaksFromAProductThatNowBelongsToAnotherSupplier() throws Exception {
        Product product = createProductWithPhoto(
                "SECRET NOTE FOR A DIFFERENT SUPPLIER", 8L);
        try (InputStream image = getClass().getResourceAsStream("/seed-images/P05.jpg")) {
            if (image == null) throw new IllegalStateException("Testfoto P05.jpg ontbreekt");
            supplierAgreementPhotos.upload(product.id(), "other-supplier-only.jpg", image,
                    "SECRET PHOTO CAPTION FOR A DIFFERENT SUPPLIER");
        }
        PurchaseOrder order = portraitOrder(product.id());

        PdfPurchaseRenderer.Document document = renderer.render(
                order, portraitCosting(product.id()), supplier(), false, List.of(),
                new PurchaseOrderService.Payable(
                        BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, false, false),
                PdfPurchaseRenderer.Layout.PORTRAIT,
                PdfPurchaseRenderer.Audience.SUPPLIER);

        try (PDDocument pdf = Loader.loadPDF(document.content())) {
            String text = new PDFTextStripper().getText(pdf)
                    .toLowerCase().replaceAll("\\s+", " ");
            assertTrue(text.contains("po-pdf-thumbnail"), text);
            assertFalse(text.contains("secret note for a different supplier"),
                    "a current note for another supplier must never enter a historical PDF");
            assertFalse(text.contains("secret photo caption for a different supplier"),
                    "agreement photos for another supplier must never enter the PDF");
            assertFalse(text.contains("agreed product instruction"), text);
        }
    }

    @Test
    @TestTransaction
    void horizontalPurchaseOrderMatchesPortraitContentAndEmbedsThumbnail() throws Exception {
        Product product = createProductWithPhoto();
        PurchaseOrder order = portraitOrder(product.id());
        LandedCost costing = portraitCosting(product.id());
        PdfPurchaseRenderer.Document document = renderer.render(
                order, costing, supplier(), false, payments(),
                new PurchaseOrderService.Payable(
                        new BigDecimal("1125.00"), new BigDecimal("480.00"),
                        new BigDecimal("375.00"), false, false),
                PdfPurchaseRenderer.Layout.LANDSCAPE);

        Path preview = Path.of("target", "pdf-preview");
        Files.createDirectories(preview);
        Files.write(preview.resolve("purchase-landscape-supplier.pdf"), document.content());

        try (PDDocument pdf = Loader.loadPDF(document.content())) {
            assertEquals(1, pdf.getNumberOfPages());
            assertTrue(pdf.getPage(0).getMediaBox().getWidth()
                            > pdf.getPage(0).getMediaBox().getHeight(),
                    "de horizontale inkooporder hoort landscape te zijn");
            assertTrue(imageCount(pdf) >= 2,
                    "logo en server-embedded productfoto horen beide zichtbaar te zijn");
            assertPrintSafeTop(pdf, 10);

            String text = new PDFTextStripper().getText(pdf)
                    .toLowerCase().replaceAll("\\s+", " ");
            assertTrue(text.contains("inkooporder - horizontaal"), text);
            assertTrue(text.contains("96"), text);
            assertTrue(text.replace(" ", "").contains("stuksperkarton"), text);
            assertTrue(text.contains("b × d × h in cm"), "the axis order is said once above the table: " + text);
            assertTrue(text.contains("product 18 × 18 × 22 cm"), text);
            assertTrue(text.contains("verpakking") && text.contains("20 × 20 × 25 cm"), text);
            assertTrue(text.contains("omdoos 40 × 40 × 30 cm"), text);
            assertTrue(text.contains("8712345678906"), text);
            assertTrue(text.contains("8712345678913"), text);
            assertTrue(text.contains("8712345678920"), text);
            assertFalse(text.contains("glass bowls"),
                    "de interne containernaam hoort niet op de inkooporder: " + text);
            assertTrue(text.contains("1.200,00"), text);
            assertFalse(text.contains("1.125,00"), text);
            assertFalse(text.contains("douanewaarde"), text);
            assertFalse(text.contains("invoerrechten"), text);
            assertFalse(text.contains("betaalplan"), text);
            assertFalse(text.contains("geregistreerde betalingen"), text);
            assertFalse(text.contains("dagboek"), text);
        }
    }

    @Test
    void portraitRowsPaginateWholeAndRepeatTheirHeader() throws Exception {
        int lineCount = 24;
        List<PurchaseOrderLine> purchaseLines = new ArrayList<>();
        for (int index = 1; index <= lineCount; index++) {
            purchaseLines.add(new PurchaseOrderLine(
                    (long) index, (long) index, 240 + index * 12,
                    new BigDecimal("12.50"), Currency.USD, BigDecimal.ZERO,
                    240 + index * 12));
        }
        PurchaseOrder order = withLines(order(lineCount), "PO-2026-PORTRAIT-MANY",
                "24 productregels", purchaseLines);
        PdfPurchaseRenderer.Document document = renderer.render(
                order, costing(lineCount), supplier(), true, payments(),
                new PurchaseOrderService.Payable(
                        new BigDecimal("34469.88"), new BigDecimal("10240.93"),
                        new BigDecimal("2500.00"), false, false),
                PdfPurchaseRenderer.Layout.PORTRAIT);

        Path preview = Path.of("target", "pdf-preview");
        Files.createDirectories(preview);
        Files.write(preview.resolve("purchase-portrait-many-lines.pdf"), document.content());

        try (PDDocument pdf = Loader.loadPDF(document.content())) {
            assertTrue(pdf.getNumberOfPages() >= 2,
                    "24 portraitregels met miniaturen horen over meerdere pagina's te lopen");
            PDFTextStripper pageStripper = new PDFTextStripper();
            for (int pageNumber = 1; pageNumber <= pdf.getNumberOfPages(); pageNumber++) {
                assertTrue(pdf.getPage(pageNumber - 1).getMediaBox().getWidth()
                                < pdf.getPage(pageNumber - 1).getMediaBox().getHeight(),
                        "elke vervolgpagina hoort portrait te blijven");
                pageStripper.setStartPage(pageNumber);
                pageStripper.setEndPage(pageNumber);
                String pageText = pageStripper.getText(pdf)
                        .toLowerCase().replaceAll("\\s+", " ");
                assertTrue(pageText.contains("product") && pageText.contains("stuks")
                                && pageText.contains("kartons"),
                        "de productkop hoort op elke pagina terug te keren: " + pageText);
            }
            String rawText = new PDFTextStripper().getText(pdf);
            long placeholderCount = Pattern.compile("\\bEN\\b").matcher(rawText).results().count();
            assertEquals(lineCount, placeholderCount,
                    "elke regel zonder productfoto hoort een rustige printplaceholder te krijgen");
            String text = rawText.toLowerCase().replaceAll("\\s+", " ");
            assertTrue(text.contains("preserved rose with stem - rood 1"), text);
            assertTrue(text.contains("preserved rose with stem - rood " + lineCount), text);
        }
    }

    @Test
    void horizontalPurchaseOrderStaysFreeOfInternals() throws Exception {
        PurchaseOrder order = order(4);
        PdfPurchaseRenderer.Document document = renderer.render(
                order, costing(4), supplier(), false, payments(),
                new PurchaseOrderService.Payable(
                        new BigDecimal("34469.88"), new BigDecimal("10240.93"),
                        new BigDecimal("2500.00"), false, false));

        Path preview = Path.of("target", "pdf-preview");
        Files.createDirectories(preview);
        Files.write(preview.resolve("purchase-customer.pdf"), document.content());

        try (PDDocument pdf = Loader.loadPDF(document.content())) {
            String text = new PDFTextStripper().getText(pdf)
                    .toLowerCase().replaceAll("\\s+", " ");
            assertEquals(1, pdf.getNumberOfPages());
            assertTrue(text.contains("inkooporder") && text.contains("horizontaal"), text);
            assertTrue(!text.contains("betaalplan"));
            assertTrue(!text.contains("dagboek"));
            assertTrue(!text.contains("aangemaakt door"));
            assertTrue(!text.contains("emre"));
        }
    }

    private Product createProductWithPhoto() throws Exception {
        return createProductWithPhoto("Use white inner boxes.");
    }

    private Product createProductWithPhotoForAllInCost() throws Exception {
        return createProductWithPhoto("Use white inner boxes.", 7L, "PDF-UNKNOWN");
    }

    private Product createProductWithPhoto(String supplierNote) throws Exception {
        return createProductWithPhoto(supplierNote, 7L);
    }

    private Product createProductWithPhoto(String supplierNote, Long supplierId) throws Exception {
        return createProductWithPhoto(supplierNote, supplierId, "7013.99.00");
    }

    private Product createProductWithPhoto(String supplierNote, Long supplierId,
                                           String hsCode) throws Exception {
        Product created = products.create(new Product(
                null, "PO-PDF-THUMBNAIL", "Glass bowl bestseller",
                new Dimensions(new BigDecimal("18"), new BigDecimal("18"),
                        new BigDecimal("22")),
                new Packaging(PackagingKind.GIFT_BOX,
                        new Dimensions(new BigDecimal("20"), new BigDecimal("20"),
                                new BigDecimal("25")), "8712345678913", 1),
                "Bordeaux", null, null, "Testproduct voor PDF QA",
                null, supplierId, true,
                null, null, "8712345678906", 0, true,
                null, null, PublicationState.DRAFT, PublicationState.DRAFT,
                new Barcodes("8712345678906", "8712345678920"), hsCode,
                new Carton(new Dimensions(new BigDecimal("40"), new BigDecimal("40"),
                        new BigDecimal("30")), 12, new BigDecimal("6.2")),
                new BigDecimal("99.99"), Currency.USD, BigDecimal.ZERO,
                null, null, BigDecimal.ZERO, null, 0, List.of(), List.of(), false));
        created = products.update(created.id(),
                created.withSupplierNote(supplierNote));
        try (InputStream image = getClass().getResourceAsStream("/seed-images/P05.jpg")) {
            if (image == null) throw new IllegalStateException("Testfoto P05.jpg ontbreekt");
            return products.addPhoto(created.id(), "P05.jpg", image);
        }
    }

    private static PurchaseOrder portraitOrder(long productId) {
        PurchaseOrder base = order(1);
        PurchaseOrderLine receivedShort = new PurchaseOrderLine(
                1L, productId, 90, new BigDecimal("12.50"), Currency.USD,
                BigDecimal.ZERO, 96);
        return withLines(base, "PO-2026-PORTRAIT", "Glass bowls", List.of(receivedShort));
    }

    private static PurchaseOrder withLines(PurchaseOrder base, String number, String alias,
                                           List<PurchaseOrderLine> lines) {
        return new PurchaseOrder(
                base.id(), number, alias, base.supplierId(),
                base.orderDate(), base.status(), base.containerType(), base.cnyToUsd(),
                base.usdToEurGoods(), base.usdToEurTransport(), base.freightUsd(),
                base.originCosts(), base.originCurrency(), base.destinationCostsEur(),
                base.defaultDutyRatePct(), base.extraRevenueEur(), base.allocFreight(),
                base.allocOrigin(), base.allocDestination(), base.allocExtra(),
                base.departurePort(), base.destinationPort(), base.receivingLocationId(),
                base.groupVariants(), base.expectedArrival(), base.receivedOn(),
                base.paidTotalEur(), base.stockBooked(), base.paymentTerms(), base.shippedOn(),
                base.trackingReference(), base.createdBy(), base.createdAt(), base.notes(), lines);
    }

    private static PurchaseOrder withReceivingLocation(PurchaseOrder base, Long locationId) {
        return new PurchaseOrder(
                base.id(), base.number(), base.alias(), base.supplierId(), base.orderDate(),
                base.status(), base.containerType(), base.cnyToUsd(), base.usdToEurGoods(),
                base.usdToEurTransport(), base.freightUsd(), base.originCosts(), base.originCurrency(),
                base.destinationCostsEur(), base.defaultDutyRatePct(), base.extraRevenueEur(),
                base.allocFreight(), base.allocOrigin(), base.allocDestination(), base.allocExtra(),
                base.departurePort(), base.destinationPort(), locationId, base.groupVariants(),
                base.expectedArrival(), base.receivedOn(), base.paidTotalEur(), base.stockBooked(),
                base.paymentTerms(), base.shippedOn(), base.trackingReference(), base.createdBy(),
                base.createdAt(), base.notes(), base.lines());
    }

    private static LandedCost portraitCosting(long productId) {
        LandedCost source = costing(1);
        LandedCost.Line base = source.lines().getFirst();
        LandedCost.Line received = new LandedCost.Line(
                productId, "Glass bowl bestseller", 90, 8, base.cbm(),
                new BigDecimal("1125.00"), new BigDecimal("1001.25"),
                base.originEur(), base.freightEur(), base.customsValueEur(),
                base.dutyRatePct(), base.dutySource(), base.dutyEur(), base.destinationEur(),
                new BigDecimal("375.00"), new BigDecimal("1498.00"),
                new BigDecimal("16.6444"), base.cbmShare(),
                base.valueShare(), base.pieceShare());
        return new LandedCost(List.of(received), source.totals(), source.containerFill());
    }

    private static int imageCount(PDDocument document) throws Exception {
        int count = 0;
        for (var page : document.getPages()) {
            for (var name : page.getResources().getXObjectNames()) {
                PDXObject object = page.getResources().getXObject(name);
                if (object instanceof PDImageXObject) count++;
            }
        }
        return count;
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

    static PurchaseOrder order(int lineCount) {
        return new PurchaseOrder(1L, "PO-2026-001", "beursvoorraad Aalsmeer", 7L,
                LocalDate.of(2026, 5, 12), PurchaseOrderStatus.ONDERWEG, ContainerType.FORTY_HQ,
                new BigDecimal("0.1400"), new BigDecimal("0.8900"), new BigDecimal("0.8900"),
                new BigDecimal("3800.00"), new BigDecimal("450.00"), Currency.USD,
                new BigDecimal("1250.00"), new BigDecimal("5.0"), new BigDecimal("2500.00"),
                Allocation.CBM, Allocation.VALUE, Allocation.CBM, Allocation.VALUE,
                "Ningbo", "Rotterdam", null, true,
                LocalDate.of(2026, 9, 4), null, null, null,
                PaymentTerms.DEPOSIT_30_40_30, LocalDate.of(2026, 7, 18), "MSCU1234567",
                "12/08/2026 · Aanbetaling 30% geboekt.\n18/07/2026 · Container vertrokken uit Ningbo.\n12/05/2026 · Order geplaatst bij Culinan.",
                List.of()).withCreationMetadata(
                        new ActorRef("emre", "Emre"), Instant.parse("2026-05-12T08:15:30Z"));
    }

    static Supplier supplier() {
        return new Supplier(7L, "Culinan Preserved Flowers Co., Ltd", "CN", "Guangzhou",
                "Lily Chen", "lily@culinan.cn", "+86 20 1234 5678", Currency.USD, "FOB",
                "Ningbo", 30, null, "Factory Road 1", "Baiyun District", "510000", "Guangdong");
    }

    /** Consistent-looking numbers; the exact arithmetic is the calculator's job. */
    static LandedCost costing(int lineCount) {
        List<LandedCost.Line> lines = new ArrayList<>();
        BigDecimal totalEur = BigDecimal.ZERO;
        int pieces = 0;
        for (int index = 1; index <= lineCount; index++) {
            int quantity = 240 + index * 12;
            BigDecimal goodsUsd = new BigDecimal("1200.50").add(BigDecimal.valueOf(index * 37L));
            BigDecimal goodsEur = goodsUsd.multiply(new BigDecimal("0.89")).setScale(2, RoundingMode.HALF_UP);
            BigDecimal origin = new BigDecimal("16.10");
            BigDecimal freight = new BigDecimal("135.70");
            BigDecimal customs = goodsEur.add(origin).add(freight);
            BigDecimal duty = customs.multiply(new BigDecimal("0.05")).setScale(2, RoundingMode.HALF_UP);
            BigDecimal destination = new BigDecimal("44.60");
            BigDecimal extra = new BigDecimal("89.30");
            BigDecimal total = customs.add(duty).add(destination).add(extra);
            lines.add(new LandedCost.Line((long) index,
                    "Preserved rose with stem - Rood " + index,
                    quantity, 10 + index, new BigDecimal("2.40"),
                    goodsUsd, goodsEur, origin, freight, customs,
                    new BigDecimal("5.0"), "TARIC", duty, destination, extra,
                    total, total.divide(BigDecimal.valueOf(quantity), 4, RoundingMode.HALF_UP),
                    new BigDecimal("3.5"), new BigDecimal("3.6"), new BigDecimal("3.6")));
            totalEur = totalEur.add(total);
            pieces += quantity;
        }
        LandedCost.Totals totals = new LandedCost.Totals(pieces, lineCount * 15,
                new BigDecimal("66.90"), new BigDecimal("38729.00"), new BigDecimal("34469.88"),
                new BigDecimal("450.82"), new BigDecimal("3799.60"), new BigDecimal("38720.30"),
                new BigDecimal("1936.02"), new BigDecimal("1248.80"), new BigDecimal("2500.42"),
                totalEur.setScale(2, RoundingMode.HALF_UP),
                totalEur.divide(BigDecimal.valueOf(pieces), 4, RoundingMode.HALF_UP),
                new BigDecimal("5.0"));
        LandedCost.ContainerFill fill = new LandedCost.ContainerFill("40HQ",
                new BigDecimal("68.00"), new BigDecimal("66.90"), new BigDecimal("98.4"),
                new BigDecimal("1.10"), BigDecimal.ZERO, 1);
        return new LandedCost(lines, totals, fill);
    }

    static List<PurchasePayment> payments() {
        return List.of(
                new PurchasePayment(1L, 1L, LocalDate.of(2026, 5, 14),
                        new BigDecimal("11623.00"), Currency.USD, new BigDecimal("10344.47"),
                        "Aanbetaling 30%", "emre", Instant.parse("2026-05-14T09:00:00Z"),
                        PurchasePayment.Payee.SUPPLIER),
                new PurchasePayment(2L, 1L, LocalDate.of(2026, 7, 19),
                        new BigDecimal("15497.00"), Currency.USD, new BigDecimal("13792.33"),
                        "40% bij vertrek", "emre", Instant.parse("2026-07-19T09:00:00Z"),
                        PurchasePayment.Payee.SUPPLIER),
                new PurchasePayment(3L, 1L, LocalDate.of(2026, 7, 22),
                        new BigDecimal("1250.00"), Currency.EUR, new BigDecimal("1250.00"),
                        "Voorschot expediteur", "emre", Instant.parse("2026-07-22T09:00:00Z"),
                        PurchasePayment.Payee.LOGISTICS));
    }
}
