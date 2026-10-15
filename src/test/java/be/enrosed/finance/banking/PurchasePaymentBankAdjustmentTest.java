package be.enrosed.finance.banking;

import be.enrosed.shared.Currency;
import be.enrosed.sourcing.adapter.out.persistence.PurchasePaymentBankAdjustmentEntity;
import be.enrosed.sourcing.application.PurchaseOrderService;
import be.enrosed.sourcing.application.SupplierService;
import be.enrosed.sourcing.domain.PaymentTerms;
import be.enrosed.sourcing.domain.PurchaseOrder;
import be.enrosed.sourcing.domain.PurchasePayment;
import be.enrosed.sourcing.domain.Supplier;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** What a purchase payment was before it first followed its bank transaction ("aangepast aan de bank (was ...)"). */
@QuarkusTest
class PurchasePaymentBankAdjustmentTest {
    private static final LocalDate SEP_3 = LocalDate.of(2026, 9, 3);
    private static final Instant SEP_5 = Instant.parse("2026-09-05T08:00:00Z");
    private static final Instant SEP_8 = Instant.parse("2026-09-08T08:00:00Z");

    @Inject PurchasePaymentSplitService splits;
    @Inject BankStatementService bank;
    @Inject PurchaseOrderService purchases;
    @Inject SupplierService suppliers;
    @Inject EntityManager em;
    @Inject ObjectMapper json;

    @Test @TestTransaction
    void theFirstFollowIsRememberedAndASecondKeepsIt() throws Exception {
        PurchaseOrder order = container();
        PurchasePayment deposit = deposit(order, "12480.00", SEP_3);
        long first = line("-12512.40", SEP_5);
        splits.linkPart(deposit.id(), new PurchasePaymentSplitService.PartRequest(first, null, PurchasePaymentSplitService.Higher.ADJUST, null, null));
        var row = adjustment(deposit.id());
        assertEquals(new BigDecimal("12480.00"), row.fromAmountEur);
        assertEquals(SEP_3, row.fromPaidOn);
        assertEquals(first, row.bankStatementLineId);
        assertNotNull(row.adjustedAt);

        var listed = listed(deposit.id());
        assertEquals(new BigDecimal("12480.00"), listed.bankAdjustedFromEur());
        assertEquals(SEP_3, listed.bankAdjustedFromOn());
        assertEquals(new BigDecimal("12512.40"), listed.amountEur());
        String body = json.writeValueAsString(listed);
        assertTrue(body.contains("\"bankAdjustedFromEur\":12480.00") && body.contains("\"bankAdjustedFromOn\":\"2026-09-03\""), body);

        bank.unlinkExisting(first);
        assertNotNull(adjustment(deposit.id()), "unlinking keeps the memory");
        long second = line("-12520.00", SEP_8);
        splits.linkPart(deposit.id(), new PurchasePaymentSplitService.PartRequest(second, null, PurchasePaymentSplitService.Higher.ADJUST, null, null));
        assertEquals(new BigDecimal("12520.00"), listed(deposit.id()).amountEur());
        row = adjustment(deposit.id());
        assertEquals(new BigDecimal("12480.00"), row.fromAmountEur, "the first adjustment wins");
        assertEquals(SEP_3, row.fromPaidOn);
        assertEquals(first, row.bankStatementLineId);
    }

    @Test @TestTransaction
    void aDateOnlyFollowIsRememberedAndANoChangeLinkIsNot() {
        PurchaseOrder order = container();
        PurchasePayment moved = deposit(order, "500.00", SEP_3);
        splits.linkPart(moved.id(), new PurchasePaymentSplitService.PartRequest(line("-500.00", SEP_5), null));
        assertEquals(new BigDecimal("500.00"), adjustment(moved.id()).fromAmountEur);
        assertEquals(SEP_3, adjustment(moved.id()).fromPaidOn);

        PurchasePayment same = deposit(order, "700.00", LocalDate.of(2026, 9, 5));
        splits.linkPart(same.id(), new PurchasePaymentSplitService.PartRequest(line("-700.00", SEP_5), null));
        assertNull(adjustment(same.id()), "nothing moved, nothing to remember");
        assertNull(listed(same.id()).bankAdjustedFromEur());

        PurchasePayment single = deposit(order, "300.00", SEP_3);
        long only = line("-300.01", SEP_8);
        splits.linkLines(single.id(), new PurchasePaymentSplitService.LinesRequest(java.util.List.of(
                new PurchasePaymentSplitService.LineRef(only, null))));
        assertEquals(new BigDecimal("300.00"), adjustment(single.id()).fromAmountEur, "bank-lines with one line follows too");
    }

    @Test @TestTransaction
    void aCarvedPartIsANewTransferNotAFollowedPayment() {
        PurchaseOrder order = container();
        PurchasePayment deposit = deposit(order, "10000.00", SEP_3);
        splits.linkPart(deposit.id(), new PurchasePaymentSplitService.PartRequest(line("-4000.00", SEP_5), null));
        assertNull(adjustment(deposit.id()));
    }

    @Test @TestTransaction
    void onlyAHandEditOfAmountOrDateOrADeletionForgetsIt() {
        PurchaseOrder order = container();
        PurchasePayment deposit = deposit(order, "12480.00", SEP_3);
        long line = line("-12512.40", SEP_5);
        splits.linkPart(deposit.id(), new PurchasePaymentSplitService.PartRequest(line, null, PurchasePaymentSplitService.Higher.ADJUST, null, null));
        bank.unlinkExisting(line);
        LocalDate day = LocalDate.of(2026, 9, 5);

        purchases.updatePayment(order.id(), deposit.id(), day, new BigDecimal("12500.00"), Currency.EUR, "Aanbetaling 30%",
                PurchasePayment.Payee.SUPPLIER, false, PaymentTerms.Moment.ORDERED, true, new BigDecimal("12500.00"));
        assertNotNull(adjustment(deposit.id()), "a correction through updatePayment itself (as a follow path may reuse it) keeps the row");

        hand(order, deposit, day, "12500.00", "Aanbetaling 30% (bank)");
        assertNotNull(adjustment(deposit.id()), "a hand edit of the label only keeps it");
        hand(order, deposit, day, "12490.00", "Aanbetaling 30% (bank)");
        assertNull(adjustment(deposit.id()), "a hand edit of the amount forgets it");

        PurchasePayment other = deposit(order, "800.00", SEP_3);
        long otherLine = line("-800.00", SEP_8);
        splits.linkPart(other.id(), new PurchasePaymentSplitService.PartRequest(otherLine, null));
        bank.unlinkExisting(otherLine);
        hand(order, other, LocalDate.of(2026, 9, 9), "800.00", "Aanbetaling 30%");
        assertNull(adjustment(other.id()), "a hand edit of the date forgets it");

        PurchasePayment gone = deposit(order, "900.00", SEP_3);
        long goneLine = line("-900.00", SEP_8);
        splits.linkPart(gone.id(), new PurchasePaymentSplitService.PartRequest(goneLine, null));
        bank.unlinkExisting(goneLine);
        assertNotNull(adjustment(gone.id()));
        purchases.deletePayment(order.id(), gone.id());
        assertNull(adjustment(gone.id()), "deleting the payment deletes its memory");
    }

    private void hand(PurchaseOrder order, PurchasePayment payment, LocalDate day, String amount, String label) {
        purchases.updatePaymentByHand(order.id(), payment.id(), day, new BigDecimal(amount), Currency.EUR, label,
                PurchasePayment.Payee.SUPPLIER, false, PaymentTerms.Moment.ORDERED, true, null, null, false, false);
    }

    private PurchasePaymentBankAdjustmentEntity adjustment(long paymentId) {
        em.flush();
        return em.find(PurchasePaymentBankAdjustmentEntity.class, paymentId);
    }

    private PurchaseOrderService.PaymentRow listed(long paymentId) {
        return purchases.paymentsSince(null).stream().filter(row -> row.id() == paymentId).findFirst().orElseThrow();
    }

    private PurchaseOrder container() {
        var supplier = suppliers.save(new Supplier(null, "Adjusted supplier", "CN", "Yiwu", null, null, null,
                Currency.USD, "FOB", "Ningbo", 30, null));
        return purchases.create(supplier.id(), new BigDecimal("0.14"), new BigDecimal("0.90"), BigDecimal.ZERO);
    }

    private PurchasePayment deposit(PurchaseOrder order, String amount, LocalDate day) {
        return purchases.addPayment(order.id(), day, new BigDecimal(amount), Currency.EUR, "Aanbetaling 30%",
                PurchasePayment.Payee.SUPPLIER, false, PaymentTerms.Moment.ORDERED);
    }

    private long line(String amount, Instant at) {
        return bank.importExternal(new BankStatementService.ExternalRequest(UUID.randomUUID().toString(),
                "BE00000000000000", new BigDecimal(amount), at, "Europe/Brussels", "Goederen", "Supplier", at, false)).id();
    }
}
