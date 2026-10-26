package be.enrosed.finance.invoices;

import be.enrosed.finance.odoo.OdooInvoiceSearch;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class InvoiceMatchTest {
    private static final LocalDate DAY = LocalDate.of(2031, 3, 2);

    static OdooInvoiceSearch.Move move(long id, String type, String name, String ref, String origin, String currency,
                                       String amount, String amountEur) {
        return new OdooInvoiceSearch.Move(id, type, "posted", "not_paid", name, ref, origin, 7L, "Partner", DAY, DAY, null,
                currency, new BigDecimal(amount), amountEur == null ? null : new BigDecimal(amountEur), null, List.of());
    }

    @Test
    void aCostMatchNeedsTheBlindNumberTheAmountAndTheSameCreditness() {
        InvoiceMatch match = InvoiceMatch.of(List.of(
                new InvoiceMatch.CostRef(12, false, "VT 2026/0412", new BigDecimal("1250.00")),
                new InvoiceMatch.CostRef(13, true, "VT-CN-0031", new BigDecimal("125.00"))), List.of(), List.of());
        assertEquals("cost:12", match.keyFor(move(1, "in_invoice", "BILL/1", "VT-2026-0412", null, "EUR", "1250.00", "1250.00")));
        assertEquals("cost:12", match.keyFor(move(1, "in_invoice", "BILL/1", "VT-2026-0412", null, "EUR", "1250.01", null)),
                "within a cent");
        assertNull(match.keyFor(move(1, "in_invoice", "BILL/1", "VT-2026-0412", null, "EUR", "1260.00", "1260.00")));
        assertNull(match.keyFor(move(1, "in_invoice", "BILL/1", "VT-2026-0413", null, "EUR", "1250.00", "1250.00")));
        assertNull(match.keyFor(move(1, "in_refund", "BILL/1", "VT-2026-0412", null, "EUR", "1250.00", "1250.00")),
                "a credit note never matches an invoice cost");
        assertEquals("cost:13", match.keyFor(move(2, "in_refund", "RBILL/1", "VT CN 0031", null, "EUR", "125.00", "125.00")));
        assertNull(match.keyFor(move(2, "in_invoice", "RBILL/1", "VT CN 0031", null, "EUR", "125.00", "125.00")));
    }

    @Test
    void aBlankRefFallsBackToTheNameAndShortNumbersNeverMatch() {
        InvoiceMatch match = InvoiceMatch.of(List.of(
                new InvoiceMatch.CostRef(5, false, "BILL/2031/03/0001", new BigDecimal("72.60")),
                new InvoiceMatch.CostRef(6, false, "A1", new BigDecimal("10.00"))), List.of(), List.of());
        assertEquals("cost:5", match.keyFor(move(1, "in_invoice", "BILL/2031/03/0001", "", null, "EUR", "72.60", "72.60")));
        assertEquals("cost:5", match.keyFor(move(1, "in_invoice", "BILL/2031/03/0001", "  ", null, "EUR", "72.60", "72.60")));
        assertNull(match.keyFor(move(1, "in_invoice", "XX", "A1", null, "EUR", "10.00", "10.00")), "fewer than 3 chars");
        assertNull(match.keyFor(move(1, "in_invoice", "A/1", "", null, "EUR", "10.00", "10.00")));
    }

    @Test
    void aForeignMoveUsesItsEuroTotalAndWithoutOneNeverMatches() {
        InvoiceMatch match = InvoiceMatch.of(List.of(new InvoiceMatch.CostRef(8, false, "NF-7781", new BigDecimal("772.80"))),
                List.of(), List.of());
        assertEquals("cost:8", match.keyFor(move(1, "in_invoice", "B", "NF-7781", null, "USD", "840.00", "772.80")));
        assertNull(match.keyFor(move(1, "in_invoice", "B", "NF-7781", null, "USD", "840.00", null)));
        assertNull(match.keyFor(move(1, "in_invoice", "B", "NF-7781", null, "USD", "772.80", "800.00")),
                "the foreign amount itself is never compared");
    }

    @Test
    void aEuroContainerInvoiceMatchesIncomingInvoicesOnlyAndTheLowestIdWins() {
        InvoiceMatch match = InvoiceMatch.of(List.of(new InvoiceMatch.CostRef(30, false, "FW-1", new BigDecimal("99.00"))),
                List.of(new InvoiceMatch.ContainerInvoiceRef(9, "FW 1", new BigDecimal("410.00")),
                        new InvoiceMatch.ContainerInvoiceRef(4, "fw-1", new BigDecimal("410.00")),
                        new InvoiceMatch.ContainerInvoiceRef(2, "FW-1", new BigDecimal("411.00"))), List.of());
        assertEquals("pi:4", match.keyFor(move(1, "in_invoice", "B", "FW-1", null, "EUR", "410.00", "410.00")));
        assertNull(match.keyFor(move(1, "in_refund", "B", "FW-1", null, "EUR", "410.00", "410.00")));
        assertEquals("cost:30", match.keyFor(move(1, "in_invoice", "B", "FW-1", null, "EUR", "99.00", "99.00")),
                "a cost is tried first");
        InvoiceMatch costs = InvoiceMatch.of(List.of(new InvoiceMatch.CostRef(31, false, "Z-100", new BigDecimal("5.00")),
                new InvoiceMatch.CostRef(17, false, "z 100", new BigDecimal("5.00"))), List.of(), List.of());
        assertEquals("cost:17", costs.keyFor(move(1, "in_invoice", "B", "Z100", null, "EUR", "5.00", "5.00")));
    }

    @Test
    void outgoingMatchesOurNumberByNameRefOrOriginOfTheSameKind() {
        InvoiceMatch match = InvoiceMatch.of(List.of(), List.of(), List.of(
                new InvoiceMatch.SalesRef(21, false, "F-2031-0012"),
                new InvoiceMatch.SalesRef(22, true, "CN-2031-0003"),
                new InvoiceMatch.SalesRef(23, false, "F-123")));
        assertEquals("so:21", match.keyFor(move(1, "out_invoice", "F-2031-0012", "", null, "EUR", "1.00", "1.00")));
        assertEquals("so:21", match.keyFor(move(1, "out_invoice", "INV/2031/00001", "F 2031 0012", null, "EUR", "1.00", "1.00")));
        assertEquals("so:21", match.keyFor(move(1, "out_invoice", "INV/2031/00001", "", "F-2031-0012", "EUR", "1.00", "1.00")));
        assertNull(match.keyFor(move(1, "out_refund", "RINV/2031/00001", "", "F-2031-0012", "EUR", "1.00", "1.00")),
                "a credit note whose origin names the invoice still shows");
        assertEquals("so:22", match.keyFor(move(1, "out_refund", "RINV/1", "CN-2031-0003", null, "EUR", "1.00", "1.00")));
        assertEquals("so:23", match.keyFor(move(1, "out_invoice", "F-123", "", null, "EUR", "1.00", "1.00")), "4 chars match");
        InvoiceMatch shortOne = InvoiceMatch.of(List.of(), List.of(), List.of(new InvoiceMatch.SalesRef(24, false, "F-12")));
        assertNull(shortOne.keyFor(move(1, "out_invoice", "F-12", "", null, "EUR", "1.00", "1.00")), "fewer than 4 chars");
        assertNull(match.keyFor(move(1, "out_invoice", "INV/2031/00001", "", null, "EUR", "1.00", "1.00")));
    }
}
