package be.enrosed.sourcing.adapter.in.rest;

import be.enrosed.shared.BusinessRuleException;
import be.enrosed.sourcing.adapter.out.document.PdfPurchaseRenderer;
import be.enrosed.sourcing.application.PurchaseOrderService;
import be.enrosed.sourcing.application.SupplierService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SourcingResourceReceiptVarianceTest {

    @Test
    void getParsesEveryFilterAndReturnsTheServiceContract() {
        PurchaseOrderService purchases = mock(PurchaseOrderService.class);
        SourcingResource resource = new SourcingResource(
                mock(SupplierService.class), purchases, mock(PdfPurchaseRenderer.class));
        PurchaseOrderService.ReceiptVarianceReport report = new PurchaseOrderService.ReceiptVarianceReport(
                new PurchaseOrderService.ReceiptVarianceTotals(
                        1, 1, 10, 8, 2, 0, 1, 7,
                        new BigDecimal("8.00"), new BigDecimal("4.00"),
                        new BigDecimal("12.00"), 0, true),
                List.of());
        when(purchases.receiptVariances(
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), 7L, 42L, 10L))
                .thenReturn(report);

        PurchaseOrderService.ReceiptVarianceReport result = resource.receiptVariances(
                "2026-01-01", "2026-12-31", 7L, 42L, 10L);

        assertSame(report, result);
        verify(purchases).receiptVariances(
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), 7L, 42L, 10L);
        assertThrows(BusinessRuleException.class,
                () -> resource.receiptVariances("01/01/2026", null, null, null, null));
    }

    @Test
    void putUsesUnitValueBodyAndReturnsNoContent() {
        PurchaseOrderService purchases = mock(PurchaseOrderService.class);
        SourcingResource resource = new SourcingResource(
                mock(SupplierService.class), purchases, mock(PdfPurchaseRenderer.class));

        var response = resource.setReceiptUnitValue(10L, 100L,
                new SourcingResource.ReceiptUnitValueRequest(new BigDecimal("4.125")));

        assertEquals(204, response.getStatus());
        verify(purchases).setReceiptUnitValue(10L, 100L, new BigDecimal("4.125"));
    }
}
