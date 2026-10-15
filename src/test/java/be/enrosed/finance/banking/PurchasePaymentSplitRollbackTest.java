package be.enrosed.finance.banking;

import be.enrosed.shared.BusinessRuleException;
import be.enrosed.shared.Currency;
import be.enrosed.sourcing.adapter.out.persistence.SourcingEntities;
import be.enrosed.sourcing.application.PurchaseOrderService;
import be.enrosed.sourcing.application.SupplierService;
import be.enrosed.sourcing.domain.PaymentTerms;
import be.enrosed.sourcing.domain.PurchasePayment;
import be.enrosed.sourcing.domain.Supplier;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.mockito.InjectSpy;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;

/** A link that fails after the split undoes the split: the payment, its diary line and the first link. */
@QuarkusTest
class PurchasePaymentSplitRollbackTest {
    @Inject PurchasePaymentSplitService splits;
    @InjectSpy BankStatementService bank;
    @Inject PurchaseOrderService purchases;
    @Inject SupplierService suppliers;
    @Inject EntityManager em;

    @Test
    void aFailingSecondLinkRollsBackTheWholeSplit() {
        long[] fixture = QuarkusTransaction.requiringNew().call(() -> {
            var supplier = suppliers.save(new Supplier(null, "Rollback supplier", "CN", "Yiwu", null, null, null,
                    Currency.USD, "FOB", "Ningbo", 30, null));
            var order = purchases.create(supplier.id(), new BigDecimal("0.14"), new BigDecimal("0.90"), BigDecimal.ZERO);
            var payment = purchases.addPayment(order.id(), LocalDate.of(2026, 9, 10), new BigDecimal("21733.63"),
                    Currency.EUR, "Saldo 70%", PurchasePayment.Payee.SUPPLIER, false, PaymentTerms.Moment.SHIPPED);
            return new long[]{order.id(), supplier.id(), payment.id(),
                    line("-10000.00", Instant.parse("2026-09-10T08:00:00Z")),
                    line("-11733.63", Instant.parse("2026-09-24T08:00:00Z"))};
        });
        try {
            String notes = purchases.get(fixture[0]).notes();
            doThrow(new BusinessRuleException("Koppeling mislukt")).when(bank).linkExisting(eq(fixture[4]), any());

            var refusal = assertThrows(BusinessRuleException.class, () -> splits.linkLines(fixture[2],
                    new PurchasePaymentSplitService.LinesRequest(List.of(
                            new PurchasePaymentSplitService.LineRef(fixture[3], null),
                            new PurchasePaymentSplitService.LineRef(fixture[4], null)))));

            assertEquals("Koppeling mislukt", refusal.getMessage());
            var payments = purchases.payments(fixture[0]);
            assertEquals(1, payments.size(), "no second part");
            assertEquals(new BigDecimal("21733.63"), payments.getFirst().amountEur());
            assertNull(payments.getFirst().groupId());
            assertNull(payments.getFirst().bankStatementLineId(), "the first link went with it");
            assertEquals(notes, purchases.get(fixture[0]).notes());
            assertFalse(QuarkusTransaction.requiringNew().call(() -> bank.describe(
                    em.find(BankStatementLineEntity.class, fixture[3])).allocated));
        } finally {
            QuarkusTransaction.requiringNew().run(() -> {
                em.createQuery("delete from PurchasePaymentEntity p where p.orderId=:id").setParameter("id", fixture[0]).executeUpdate();
                em.remove(em.find(BankStatementLineEntity.class, fixture[3]));
                em.remove(em.find(BankStatementLineEntity.class, fixture[4]));
                em.remove(em.find(SourcingEntities.PurchaseOrderEntity.class, fixture[0]));
                em.remove(em.find(SourcingEntities.SupplierEntity.class, fixture[1]));
            });
        }
    }

    private long line(String amount, Instant at) {
        return bank.importExternal(new BankStatementService.ExternalRequest(UUID.randomUUID().toString(),
                "BE00000000000000", new BigDecimal(amount), at, "Europe/Brussels", "Saldo", "Supplier", at, false)).id();
    }
}
