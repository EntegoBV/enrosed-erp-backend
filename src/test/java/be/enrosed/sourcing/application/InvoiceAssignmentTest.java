package be.enrosed.sourcing.application;

import be.enrosed.shared.Currency;
import be.enrosed.sourcing.domain.PaymentTerms;
import be.enrosed.sourcing.domain.PurchaseInvoice;
import be.enrosed.sourcing.domain.PurchasePayment;
import be.enrosed.sourcing.domain.PurchasePayment.Payee;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InvoiceAssignmentTest {

    private static final PurchaseInvoice CI_118 = invoice(7L, Payee.SUPPLIER, "CI-2026-118", LocalDate.of(2026, 8, 20));

    @Test
    void anExplicitInvoiceOfTheOrderWins() {
        var second = invoice(8L, Payee.SUPPLIER, "CI-2026-118-B", LocalDate.of(2026, 8, 25));
        var payment = payment(101L, Payee.SUPPLIER, null, 8L);
        assertEquals(8L, InvoiceAssignment.resolve(payment, List.of(CI_118, second), List.of(payment), false));
        /* A name that is not an invoice of this order falls back to the automatic rule. */
        var stale = payment(102L, Payee.SUPPLIER, null, 99L);
        assertEquals(7L, InvoiceAssignment.resolve(stale, List.of(CI_118), List.of(stale), false));
    }

    @Test
    void thePayeesOnlyInvoiceIsAutomaticAndTwoNeedAChoice() {
        var payment = payment(101L, Payee.SUPPLIER, 501L, null);
        assertEquals(7L, InvoiceAssignment.resolve(payment, List.of(CI_118), List.of(payment), false));
        var second = invoice(8L, Payee.SUPPLIER, "CI-2026-140", LocalDate.of(2026, 9, 1));
        assertNull(InvoiceAssignment.resolve(payment, List.of(CI_118, second), List.of(payment), false));
        var road = payment(103L, Payee.LOGISTICS, null, null);
        assertNull(InvoiceAssignment.resolve(road, List.of(CI_118), List.of(road), false),
                "another payee's invoice is never automatic");
    }

    @Test
    void anInspectionFollowsTheSupplierPrimaryOnlyWhenPaidThroughTheSupplier() {
        var goods = payment(201L, Payee.SUPPLIER, 610L, null);
        var onGoodsLine = payment(202L, Payee.SEPARATE, 610L, null);
        var unlinked = payment(203L, Payee.SEPARATE, null, null);
        var ownLine = payment(204L, Payee.SEPARATE, 611L, null);
        List<PurchasePayment> ledger = List.of(goods, onGoodsLine, unlinked, ownLine);
        List<PurchaseInvoice> invoices = List.of(CI_118);

        assertEquals(7L, InvoiceAssignment.resolve(onGoodsLine, invoices, ledger, true));
        assertEquals(7L, InvoiceAssignment.resolve(unlinked, invoices, ledger, true));
        assertNull(InvoiceAssignment.resolve(ownLine, invoices, ledger, true),
                "a lab fee paid on its own transfer is not via the supplier");
        assertNull(InvoiceAssignment.resolve(onGoodsLine, invoices, ledger, false), "without the flag: none");
        assertTrue(InvoiceAssignment.viaSupplier(onGoodsLine, ledger, true));
        assertFalse(InvoiceAssignment.viaSupplier(ownLine, ledger, true));
        assertFalse(InvoiceAssignment.viaSupplier(goods, ledger, true), "only the inspection stream");

        /* An inspection invoice of its own takes over. */
        var inspection = invoice(9L, Payee.SEPARATE, "INS-77", LocalDate.of(2026, 9, 2));
        assertEquals(9L, InvoiceAssignment.resolve(onGoodsLine, List.of(CI_118, inspection), ledger, true));
    }

    @Test
    void thePrimaryIsTheFirstRegisteredInvoiceEvenWhenALaterOneCarriesAnEarlierDate() {
        var earlierDated = invoice(12L, Payee.SUPPLIER, "CI-2026-100", LocalDate.of(2026, 7, 1));
        var invoices = List.of(earlierDated, CI_118);
        assertEquals(7L, InvoiceAssignment.primary(invoices, Payee.SUPPLIER).id());
        assertTrue(InvoiceAssignment.isPrimary(CI_118, invoices));
        assertFalse(InvoiceAssignment.isPrimary(earlierDated, invoices));
        var inspection = payment(202L, Payee.SEPARATE, null, null);
        assertEquals(7L, InvoiceAssignment.resolve(inspection, invoices, List.of(inspection), true));
    }

    @Test
    void theSupplierPrimaryCoversTheInspectionWhileNoInspectionInvoiceExists() {
        var extra = invoice(8L, Payee.SUPPLIER, "CI-2026-118-X", LocalDate.of(2026, 9, 1));
        assertTrue(InvoiceAssignment.coversInspection(CI_118, List.of(CI_118, extra), true));
        assertFalse(InvoiceAssignment.coversInspection(extra, List.of(CI_118, extra), true), "only the primary");
        assertFalse(InvoiceAssignment.coversInspection(CI_118, List.of(CI_118), false), "only with the flag");
        var inspection = invoice(9L, Payee.SEPARATE, "INS-77", LocalDate.of(2026, 9, 2));
        assertFalse(InvoiceAssignment.coversInspection(CI_118, List.of(CI_118, inspection), true));
    }

    @Test
    void aSupplierInvoiceMayCarryAnInspectionOnlyWithTheFlag() {
        assertTrue(InvoiceAssignment.mayCarry(CI_118, Payee.SUPPLIER, false));
        assertTrue(InvoiceAssignment.mayCarry(CI_118, Payee.SEPARATE, true));
        assertFalse(InvoiceAssignment.mayCarry(CI_118, Payee.SEPARATE, false));
        assertFalse(InvoiceAssignment.mayCarry(CI_118, Payee.LOGISTICS, true));
    }

    private static PurchaseInvoice invoice(Long id, Payee payee, String number, LocalDate date) {
        return new PurchaseInvoice(id, 14L, payee, number, date, new BigDecimal("31048.04"), Currency.EUR,
                new BigDecimal("31048.04"), null, null, "Emre", Instant.parse("2026-08-21T08:10:00Z"));
    }

    private static PurchasePayment payment(Long id, Payee payee, Long line, Long invoiceId) {
        return new PurchasePayment(id, 14L, LocalDate.of(2026, 9, 3), new BigDecimal("100.00"), Currency.EUR,
                new BigDecimal("100.00"), null, "Emre", Instant.parse("2026-09-03T08:00:00Z"), payee, false,
                payee == Payee.SUPPLIER ? PaymentTerms.Moment.ORDERED : null, line, null, invoiceId);
    }
}
