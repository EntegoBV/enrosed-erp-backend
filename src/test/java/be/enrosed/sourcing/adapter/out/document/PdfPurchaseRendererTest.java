package be.enrosed.sourcing.adapter.out.document;

import be.enrosed.shared.Currency;
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
        assertNull(PdfPurchaseRenderer.productSpecs(null));
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
