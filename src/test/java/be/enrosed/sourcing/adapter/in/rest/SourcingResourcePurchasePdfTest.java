package be.enrosed.sourcing.adapter.in.rest;

import be.enrosed.sourcing.adapter.out.document.PdfPurchaseRenderer;
import be.enrosed.sourcing.application.PurchaseOrderService;
import be.enrosed.sourcing.application.SupplierService;
import be.enrosed.sourcing.domain.LandedCost;
import be.enrosed.sourcing.domain.Allocation;
import be.enrosed.sourcing.domain.ContainerType;
import be.enrosed.sourcing.domain.PurchaseOrder;
import be.enrosed.sourcing.domain.PurchaseOrderStatus;
import be.enrosed.sourcing.domain.PurchasePayment;
import be.enrosed.shared.Currency;
import jakarta.ws.rs.BadRequestException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SourcingResourcePurchasePdfTest {

    @Test
    void explicitSupplierAudienceReachesRendererAndReturnsSupplierFilename() {
        SupplierService suppliers = mock(SupplierService.class);
        PurchaseOrderService purchases = mock(PurchaseOrderService.class);
        PdfPurchaseRenderer renderer = mock(PdfPurchaseRenderer.class);
        SourcingResource resource = new SourcingResource(suppliers, purchases, renderer);
        PurchaseOrder order = order(41L);
        LandedCost costing = null;
        List<PurchasePayment> payments = List.of();
        PurchaseOrderService.Payable payable = new PurchaseOrderService.Payable(
                new BigDecimal("100.00"), BigDecimal.ZERO, BigDecimal.ZERO, false, false);
        byte[] pdf = {1, 2, 3};
        PdfPurchaseRenderer.Document document = new PdfPurchaseRenderer.Document(
                "PO-2026-041-supplier.pdf", pdf, "application/pdf");
        when(purchases.get(41L)).thenReturn(order);
        when(purchases.calculate(order)).thenReturn(costing);
        when(purchases.payments(41L)).thenReturn(payments);
        when(purchases.payable(order, costing, null)).thenReturn(payable);
        when(renderer.render(order, costing, null, true, payments, payable,
                PdfPurchaseRenderer.Layout.PORTRAIT,
                PdfPurchaseRenderer.Audience.SUPPLIER)).thenReturn(document);

        var response = resource.purchasePdf(41L, true, "portrait", "supplier");

        assertEquals(200, response.getStatus());
        assertSame(pdf, response.getEntity());
        assertEquals("attachment; filename=\"PO-2026-041-supplier.pdf\"",
                response.getHeaderString("Content-Disposition"));
        verify(renderer).render(order, costing, null, true, payments, payable,
                PdfPurchaseRenderer.Layout.PORTRAIT,
                PdfPurchaseRenderer.Audience.SUPPLIER);
    }

    @Test
    void omittedAudienceKeepsHistoricalStandardRendererContract() {
        PurchaseOrderService purchases = mock(PurchaseOrderService.class);
        PdfPurchaseRenderer renderer = mock(PdfPurchaseRenderer.class);
        SourcingResource resource = new SourcingResource(
                mock(SupplierService.class), purchases, renderer);
        PurchaseOrder order = order(42L);
        LandedCost costing = null;
        List<PurchasePayment> payments = List.of();
        PurchaseOrderService.Payable payable = new PurchaseOrderService.Payable(
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, false, false);
        PdfPurchaseRenderer.Document document = new PdfPurchaseRenderer.Document(
                "legacy.pdf", new byte[] {9}, "application/pdf");
        when(purchases.get(42L)).thenReturn(order);
        when(purchases.calculate(order)).thenReturn(costing);
        when(purchases.payments(42L)).thenReturn(payments);
        when(purchases.payable(order, costing, null)).thenReturn(payable);
        when(renderer.render(order, costing, null, true, payments, payable,
                PdfPurchaseRenderer.Layout.PORTRAIT,
                PdfPurchaseRenderer.Audience.STANDARD)).thenReturn(document);

        resource.purchasePdf(42L, true, "PORTRAIT", null);

        verify(renderer).render(order, costing, null, true, payments, payable,
                PdfPurchaseRenderer.Layout.PORTRAIT,
                PdfPurchaseRenderer.Audience.STANDARD);
    }

    @Test
    void supplierAudienceRejectsLandscapeBeforeLoadingAnOrder() {
        PurchaseOrderService purchases = mock(PurchaseOrderService.class);
        PdfPurchaseRenderer renderer = mock(PdfPurchaseRenderer.class);
        SourcingResource resource = new SourcingResource(
                mock(SupplierService.class), purchases, renderer);

        assertThrows(BadRequestException.class,
                () -> resource.purchasePdf(43L, false, "LANDSCAPE", "SUPPLIER"));

        verifyNoInteractions(purchases, renderer);
    }

    private static PurchaseOrder order(long id) {
        return new PurchaseOrder(
                id, "PO-2026-" + id, null, null, LocalDate.of(2026, 8, 31),
                PurchaseOrderStatus.CONCEPT, ContainerType.FORTY_HQ,
                new BigDecimal("0.14"), new BigDecimal("0.89"), new BigDecimal("0.89"),
                BigDecimal.ZERO, BigDecimal.ZERO, Currency.USD, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO,
                Allocation.CBM, Allocation.CBM, Allocation.CBM, Allocation.PIECES,
                "Ningbo", "Rotterdam", null, List.of());
    }
}
