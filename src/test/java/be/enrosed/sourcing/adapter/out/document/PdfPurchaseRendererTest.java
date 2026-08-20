package be.enrosed.sourcing.adapter.out.document;

import be.enrosed.shared.Currency;
import be.enrosed.sourcing.domain.Supplier;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.math.BigDecimal;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PdfPurchaseRendererTest {

    @Test
    void addressIsAvailableOnlyToInternalPurchaseCalculation() {
        Supplier supplier = new Supplier(1L, "Factory Ltd", "CN", "Guangzhou",
                null, null, null, Currency.CNY, "EXW", "Guangzhou", 25, null,
                "Factory Road 1", "Baiyun District", "510000", "Guangdong");

        assertEquals(List.of(), PdfPurchaseRenderer.visibleSupplierAddress(supplier, false));
        assertEquals(List.of("Factory Road 1", "Baiyun District",
                        "510000 Guangzhou, Guangdong", "CHINA (CN)"),
                PdfPurchaseRenderer.visibleSupplierAddress(supplier, true));
    }

    @Test
    void pdfCanDistinguishUnifiedAndHistoricalRates() {
        assertTrue(PdfPurchaseRenderer.sameRate(order("0.90", "0.9000")));
        assertFalse(PdfPurchaseRenderer.sameRate(order("0.81", "0.93")));
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
