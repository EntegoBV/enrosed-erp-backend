package be.enrosed.sourcing.adapter.out.document;

import be.enrosed.catalog.application.ProductService;
import be.enrosed.catalog.domain.Barcodes;
import be.enrosed.catalog.domain.Carton;
import be.enrosed.catalog.domain.Dimensions;
import be.enrosed.catalog.domain.Product;
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
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
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
            assertTrue(text.contains("interne inkoopcalculatie"));
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
    void portraitUsesOrderedSnapshotEmbedsThumbnailAndNeverLeaksInternals() throws Exception {
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

            String text = new PDFTextStripper().getText(pdf)
                    .toLowerCase().replaceAll("\\s+", " ");
            assertTrue(text.contains("inkooporder - leesversie"), text);
            assertTrue(text.contains("96"),
                    "de geplaatste-order snapshot moet zichtbaar blijven na ontvangst");
            assertTrue(text.contains("1.200,00"),
                    "96 besteld x 12,50 moet het leverancierstotaal bepalen");
            assertFalse(text.contains("1.125,00"),
                    "90 ontvangen mag het afgesproken ordertotaal niet herschrijven");
            assertFalse(text.contains("interne inkoopcalculatie"), text);
            assertFalse(text.contains("enrosed kost"), text);
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
                                && pageText.contains("regeltotaal"),
                        "de productkop hoort op elke pagina terug te keren: " + pageText);
            }
            String rawText = new PDFTextStripper().getText(pdf);
            long placeholderCount = Pattern.compile("\\bEN\\b").matcher(rawText).results().count();
            assertEquals(lineCount, placeholderCount,
                    "elke regel zonder productfoto hoort een rustige printplaceholder te krijgen");
            String text = rawText.toLowerCase();
            assertTrue(text.contains("preserved rose with stem - rood 1"), text);
            assertTrue(text.contains("preserved rose with stem - rood " + lineCount), text);
        }
    }

    @Test
    void customerViewStaysFreeOfInternals() throws Exception {
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
            String text = new PDFTextStripper().getText(pdf).toLowerCase();
            assertEquals(1, pdf.getNumberOfPages());
            assertTrue(text.contains("inkoopcalculatie"));
            assertTrue(!text.contains("betaalplan"));
            assertTrue(!text.contains("dagboek"));
            assertTrue(!text.contains("factory road"));
            assertTrue(!text.contains("aangemaakt door"));
            assertTrue(!text.contains("emre"));
        }
    }

    private Product createProductWithPhoto() throws Exception {
        Product created = products.create(new Product(
                null, "PO-PDF-THUMBNAIL", "Glass bowl bestseller",
                new Dimensions(new BigDecimal("18"), new BigDecimal("18"),
                        new BigDecimal("22")), "Bordeaux", "Testproduct voor PDF QA",
                null, null, true, Barcodes.none(), "7013.99.00",
                new Carton(new Dimensions(new BigDecimal("40"), new BigDecimal("40"),
                        new BigDecimal("30")), 12, new BigDecimal("6.2")),
                new BigDecimal("12.50"), Currency.USD, BigDecimal.ZERO,
                null, null, BigDecimal.ZERO, null, 0, List.of(), List.of()));
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
                new BigDecimal("1.10"), BigDecimal.ZERO);
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
