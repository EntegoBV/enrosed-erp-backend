package be.enrosed.sales.adapter.in.rest;

import be.enrosed.sales.application.QuoteService;
import be.enrosed.sales.application.SalesOrderService;
import be.enrosed.sales.application.port.out.QuoteDocumentRenderer;
import be.enrosed.sales.application.port.out.SalesPdfOptions;
import be.enrosed.shared.Language;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SalesOrderResourcePdfOptionsTest {

    @Test
    void quoteAndInvoicePdfForwardIndependentPrintableProductOptions() {
        QuoteService quotes = mock(QuoteService.class);
        SalesOrderResource resource = new SalesOrderResource(mock(SalesOrderService.class), quotes);
        SalesPdfOptions expected = new SalesPdfOptions(true, false, true, false, true, true);
        when(quotes.document(42L, Language.EN, expected)).thenReturn(
                new QuoteDocumentRenderer.Document("Q-42.pdf", new byte[]{1}, "application/pdf"));

        try (Response response = resource.pdf(
                42L, "en", true, false, true, false, true, true)) {
            assertEquals(200, response.getStatus());
            assertEquals("attachment; filename=\"Q-42.pdf\"",
                    response.getHeaderString("Content-Disposition"));
        }
        verify(quotes).document(42L, Language.EN, expected);
    }

    @Test
    void packingSlipForwardsPriceFreeProductOptions() {
        QuoteService quotes = mock(QuoteService.class);
        SalesOrderResource resource = new SalesOrderResource(mock(SalesOrderService.class), quotes);
        SalesPdfOptions expected = SalesPdfOptions.forPackingSlip(true, false);
        when(quotes.packingSlip(42L, expected)).thenReturn(
                new QuoteDocumentRenderer.Document(
                        "Q-42-pakbon.pdf", new byte[]{1}, "application/pdf"));

        try (Response response = resource.packingSlip(42L, true, false)) {
            assertEquals(200, response.getStatus());
            assertEquals("inline; filename=Q-42-pakbon.pdf",
                    response.getHeaderString("Content-Disposition"));
        }
        verify(quotes).packingSlip(42L, expected);
    }

    @Test
    void compatibilityCallsKeepNewOptionsDisabledByDefault() {
        QuoteService quotes = mock(QuoteService.class);
        SalesOrderResource resource = new SalesOrderResource(mock(SalesOrderService.class), quotes);
        SalesPdfOptions salesDefaults = new SalesPdfOptions(true, true, true, true,
                false, false);
        when(quotes.document(7L, null, salesDefaults)).thenReturn(
                new QuoteDocumentRenderer.Document("Q-7.pdf", new byte[]{1}, "application/pdf"));
        when(quotes.packingSlip(7L, SalesPdfOptions.forPackingSlip(false, false))).thenReturn(
                new QuoteDocumentRenderer.Document(
                        "Q-7-pakbon.pdf", new byte[]{1}, "application/pdf"));

        try (Response ignored = resource.pdf(7L, null, true, true, true, true)) {
            verify(quotes).document(7L, null, salesDefaults);
        }
        try (Response ignored = resource.packingSlip(7L)) {
            verify(quotes).packingSlip(7L, SalesPdfOptions.forPackingSlip(false, false));
        }
    }
}
