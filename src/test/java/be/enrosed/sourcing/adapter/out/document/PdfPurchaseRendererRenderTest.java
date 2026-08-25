package be.enrosed.sourcing.adapter.out.document;

import be.enrosed.shared.Currency;
import be.enrosed.sourcing.application.PurchaseOrderService;
import be.enrosed.sourcing.domain.Allocation;
import be.enrosed.sourcing.domain.ContainerType;
import be.enrosed.sourcing.domain.LandedCost;
import be.enrosed.sourcing.domain.PaymentTerms;
import be.enrosed.sourcing.domain.PurchaseOrder;
import be.enrosed.sourcing.domain.PurchaseOrderStatus;
import be.enrosed.sourcing.domain.PurchasePayment;
import be.enrosed.sourcing.domain.Supplier;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    @Test
    void internalDossierSurvivesManyLinesAcrossPages() throws Exception {
        int lineCount = 28;
        PurchaseOrder order = order(lineCount);
        LandedCost costing = costing(lineCount);
        PdfPurchaseRenderer.Document document = renderer.render(
                order, costing, supplier(), true, payments(),
                new PurchaseOrderService.Payable(
                        new BigDecimal("34469.88"), new BigDecimal("10240.93"),
                        new BigDecimal("2500.00"), false, false));

        Path preview = Path.of("target", "pdf-preview");
        Files.createDirectories(preview);
        Files.write(preview.resolve("purchase-internal-many-lines.pdf"), document.content());

        try (PDDocument pdf = Loader.loadPDF(document.content())) {
            assertTrue(pdf.getNumberOfPages() >= 2,
                    "28 regels horen niet op één pagina te passen");
            /* Kickers and card titles print uppercase (CSS text-transform),
               so the extracted text is compared in lowercase. */
            String text = new PDFTextStripper().getText(pdf)
                    .toLowerCase().replaceAll("\\s+", " ");
            assertTrue(text.contains("interne inkoopcalculatie"));
            assertTrue(text.contains("preserved rose with stem - rood 1"));
            assertTrue(text.contains("preserved rose with stem - rood " + lineCount));
            assertTrue(text.contains("betaalplan"));
            assertTrue(text.contains("dagboek"));
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
        }
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
                List.of());
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
