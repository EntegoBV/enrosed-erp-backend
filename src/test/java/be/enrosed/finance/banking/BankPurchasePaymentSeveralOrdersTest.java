package be.enrosed.finance.banking;

import be.enrosed.shared.BusinessRuleException;
import be.enrosed.shared.Currency;
import be.enrosed.shared.NotFoundException;
import be.enrosed.sourcing.adapter.out.persistence.SourcingEntities;
import be.enrosed.sourcing.application.PurchaseOrderService;
import be.enrosed.sourcing.application.SupplierService;
import be.enrosed.sourcing.domain.PaymentTerms;
import be.enrosed.sourcing.domain.PurchaseOrder;
import be.enrosed.sourcing.domain.PurchasePayment;
import be.enrosed.sourcing.domain.Supplier;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.*;

/** Emre, 2026-10-15: one transfer pays 30 % of three containers ordered together. */
@QuarkusTest
class BankPurchasePaymentSeveralOrdersTest {
    private static final Instant AT = Instant.parse("2026-10-14T08:00:00Z");
    private static final LocalDate DAY = LocalDate.of(2026, 10, 14);

    @Inject BankPurchasePaymentService service;
    @Inject BankStatementService bank;
    @Inject PurchaseOrderService purchases;
    @Inject SupplierService suppliers;
    @Inject EntityManager em;

    @Test @TestTransaction
    void oneTransferPaysThreeContainersInOneCall() {
        var supplier = supplier("Yunnan Pubei Flowers Co., Ltd");
        var first = container(supplier);
        var second = container(supplier);
        var third = container(supplier);
        long line = line("-27000.00");
        var result = service.record(line, new BankPurchasePaymentService.Request(null, List.of(
                deposit(first, "9000.00"), deposit(second, "9000.00"), deposit(third, "9000.00"))));

        assertTrue(result.line().allocated);
        assertEquals("Inkoopbetaling", result.line().allocationLabel);
        assertEquals(new BigDecimal("0.00"), result.line().allocationDifferenceEur);
        assertEquals(List.of(first.id(), second.id(), third.id()), result.payments().stream().map(PurchasePayment::orderId).toList());
        for (var order : List.of(first, second, third)) {
            var payments = purchases.payments(order.id());
            assertEquals(1, payments.size(), "one payment on each container");
            var payment = payments.getFirst();
            assertEquals(new BigDecimal("9000.00"), payment.amountEur());
            assertEquals(DAY, payment.paidOn(), "the bank's day");
            assertEquals(line, payment.bankStatementLineId(), "the shared transfer is every part's proof");
            assertEquals(PaymentTerms.Moment.ORDERED, payment.instalmentDue());
            assertEquals("Aanbetaling 30%", payment.label());
        }
        var listed = purchases.paymentsSince(null).stream().filter(row -> line == (row.bankStatementLineId() == null ? -1 : row.bankStatementLineId())).toList();
        assertEquals(3, listed.size(), "the list shows the three containers on one transaction");
    }

    @Test @TestTransaction
    void theRequestOrderIsTheDefaultForPartsWithoutOne() {
        var supplier = supplier("Mixed supplier");
        var main = container(supplier);
        var other = container(supplier("Another supplier"));
        long line = line("-1300.00");
        var result = service.record(line, new BankPurchasePaymentService.Request(main.id(), List.of(
                new BankPurchasePaymentService.Payment(new BigDecimal("1000.00"), Currency.EUR, "Goederen", PurchasePayment.Payee.SUPPLIER,
                        false, null, null),
                new BankPurchasePaymentService.Payment(new BigDecimal("300.00"), Currency.EUR, "Andere leverancier",
                        PurchasePayment.Payee.SUPPLIER, false, null, null, null, null, other.id()))));
        assertEquals(List.of(main.id(), other.id()), result.payments().stream().map(PurchasePayment::orderId).toList(),
                "a part on another supplier's container is allowed (the screen warns)");
    }

    @Test @TestTransaction
    void theTotalMustMakeTheLineAndNothingIsWrittenOtherwise() {
        var supplier = supplier("Total supplier");
        var first = container(supplier);
        var second = container(supplier);
        long line = line("-18000.00");
        assertEquals("Het totaal van de betalingen mag maximaal € 0,02 van het bankbedrag verschillen",
                assertThrows(BusinessRuleException.class, () -> service.record(line, new BankPurchasePaymentService.Request(null,
                        List.of(deposit(first, "9000.00"), deposit(second, "8999.97"))))).getMessage());
        assertTrue(purchases.payments(first.id()).isEmpty());
        assertTrue(purchases.payments(second.id()).isEmpty());
        assertEquals("Kies een inkooporder en één tot tien betalingsdelen",
                assertThrows(BusinessRuleException.class, () -> service.record(line, new BankPurchasePaymentService.Request(null,
                        List.of(deposit(first, "9000.00"), new BankPurchasePaymentService.Payment(new BigDecimal("9000.00"),
                                Currency.EUR, "Zonder container", PurchasePayment.Payee.SUPPLIER, false, null, null))))).getMessage());
        assertThrows(NotFoundException.class, () -> service.record(line, new BankPurchasePaymentService.Request(null,
                List.of(deposit(first, "9000.00"), new BankPurchasePaymentService.Payment(new BigDecimal("9000.00"), Currency.EUR,
                        "Onbekend", PurchasePayment.Payee.SUPPLIER, false, null, null, null, null, 987654321L)))));
        assertTrue(purchases.payments(first.id()).isEmpty());
    }

    @Test @TestTransaction
    void aRetryAnswersTheSameIdsAndAMovedPartIsRefused() {
        var supplier = supplier("Retry supplier");
        var first = container(supplier);
        var second = container(supplier);
        long line = line("-15000.00");
        var request = new BankPurchasePaymentService.Request(null, List.of(deposit(first, "10000.00"), deposit(second, "5000.00")));
        var saved = service.record(line, request);
        var notes = purchases.get(second.id()).notes();
        var retry = service.record(line, request);
        assertEquals(saved.payments(), retry.payments(), "same line, same parts: the same ids");
        assertEquals(1, purchases.payments(first.id()).size());
        assertEquals(1, purchases.payments(second.id()).size());
        assertEquals(notes, purchases.get(second.id()).notes());
        var swapped = new BankPurchasePaymentService.Request(null, List.of(deposit(second, "10000.00"), deposit(first, "5000.00")));
        assertEquals("Deze banktransactie is al aan een andere betaling gekoppeld",
                assertThrows(BusinessRuleException.class, () -> service.record(line, swapped)).getMessage());
    }

    @Test
    void aLaterInvalidPartRollsBackThePaymentsOnTheOtherContainers() {
        var fixture = QuarkusTransaction.requiringNew().call(() -> {
            var supplier = supplier("Rollback supplier");
            var first = container(supplier);
            var second = container(supplier);
            return new long[]{first.id(), second.id(), supplier.id(), line("-200.00")};
        });
        try {
            String notes = purchases.get(fixture[0]).notes();
            var invalid = new BankPurchasePaymentService.Payment(new BigDecimal("100.00"), Currency.EUR, "Vracht",
                    PurchasePayment.Payee.LOGISTICS, false, PaymentTerms.Moment.ORDERED, null, null, null, fixture[1]);
            assertThrows(BusinessRuleException.class, () -> service.record(fixture[3], new BankPurchasePaymentService.Request(fixture[0],
                    List.of(new BankPurchasePaymentService.Payment(new BigDecimal("100.00"), Currency.EUR, "Goederen",
                            PurchasePayment.Payee.SUPPLIER, false, null, null), invalid))));
            assertTrue(purchases.payments(fixture[0]).isEmpty(), "the first container's payment went with the refusal");
            assertTrue(purchases.payments(fixture[1]).isEmpty());
            assertEquals(notes, purchases.get(fixture[0]).notes());
        } finally {
            QuarkusTransaction.requiringNew().run(() -> {
                em.createQuery("delete from PurchasePaymentEntity p where p.orderId in :ids").setParameter("ids", List.of(fixture[0], fixture[1])).executeUpdate();
                em.remove(em.find(BankStatementLineEntity.class, fixture[3]));
                em.remove(em.find(SourcingEntities.PurchaseOrderEntity.class, fixture[0]));
                em.remove(em.find(SourcingEntities.PurchaseOrderEntity.class, fixture[1]));
                em.remove(em.find(SourcingEntities.SupplierEntity.class, fixture[2]));
            });
        }
    }

    @Test @TestSecurity(user = "emre", roles = "admin")
    void theEndpointTakesAnOrderPerPart() {
        var fixture = QuarkusTransaction.requiringNew().call(() -> {
            var supplier = supplier("Endpoint supplier");
            return new long[]{container(supplier).id(), container(supplier).id(), supplier.id(), line("-600.00")};
        });
        try {
            given().contentType("application/json").body(Map.of("payments", List.of(
                            Map.of("amount", 400, "currency", "EUR", "label", "Aanbetaling 30%", "payee", "SUPPLIER",
                                    "instalmentDue", "ORDERED", "purchaseOrderId", fixture[0]),
                            Map.of("amount", 200, "currency", "EUR", "label", "Aanbetaling 30%", "payee", "SUPPLIER",
                                    "instalmentDue", "ORDERED", "purchaseOrderId", fixture[1]))))
                    .post("/api/bank-statements/" + fixture[3] + "/purchase-payment").then().statusCode(200)
                    .body("line.allocated", equalTo(true)).body("payments", hasSize(2))
                    .body("payments[0].orderId", equalTo((int) fixture[0])).body("payments[1].orderId", equalTo((int) fixture[1]));
        } finally {
            QuarkusTransaction.requiringNew().run(() -> {
                em.createQuery("delete from PurchasePaymentEntity p where p.orderId in :ids").setParameter("ids", List.of(fixture[0], fixture[1])).executeUpdate();
                em.remove(em.find(BankStatementLineEntity.class, fixture[3]));
                em.remove(em.find(SourcingEntities.PurchaseOrderEntity.class, fixture[0]));
                em.remove(em.find(SourcingEntities.PurchaseOrderEntity.class, fixture[1]));
                em.remove(em.find(SourcingEntities.SupplierEntity.class, fixture[2]));
            });
        }
    }

    private static BankPurchasePaymentService.Payment deposit(PurchaseOrder order, String amount) {
        return new BankPurchasePaymentService.Payment(new BigDecimal(amount), Currency.EUR, "Aanbetaling 30%",
                PurchasePayment.Payee.SUPPLIER, false, PaymentTerms.Moment.ORDERED, null, null, null, order.id());
    }

    private Supplier supplier(String name) {
        return suppliers.save(new Supplier(null, name, "CN", "Kunming", null, null, null, Currency.USD, "FOB", "Ningbo", 35, null));
    }

    private PurchaseOrder container(Supplier supplier) {
        return purchases.create(supplier.id(), new BigDecimal("0.14"), new BigDecimal("0.90"), BigDecimal.ZERO);
    }

    private long line(String amount) {
        return bank.importExternal(new BankStatementService.ExternalRequest(UUID.randomUUID().toString(), "BE94731074082814",
                new BigDecimal(amount), AT, "Europe/Brussels", "DEPOSIT 30PCT 3 CONTAINERS", "Yunnan Pubei Flowers Co., Ltd", AT, false)).id();
    }
}
