package be.enrosed.finance.banking;

import be.enrosed.finance.adapter.out.persistence.CompanyCostEntity;
import be.enrosed.finance.application.CompanyCostService;
import be.enrosed.finance.domain.CompanyCost;
import be.enrosed.finance.domain.CostPayment;
import be.enrosed.finance.domain.VatRegime;
import be.enrosed.sales.adapter.out.persistence.SalesEntities.SalesOrderEntity;
import be.enrosed.sales.application.CustomerService;
import be.enrosed.sales.application.IncomingPaymentService;
import be.enrosed.sales.application.SalesOrderService;
import be.enrosed.sales.domain.*;
import be.enrosed.shared.BusinessRuleException;
import be.enrosed.shared.Currency;
import be.enrosed.shared.Language;
import be.enrosed.sourcing.application.PurchaseOrderService;
import be.enrosed.sourcing.application.SupplierService;
import be.enrosed.sourcing.domain.PurchaseOrder;
import be.enrosed.sourcing.domain.PurchasePayment;
import be.enrosed.sourcing.domain.Supplier;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** "Betaling volgt de bank" for cost payments and receipts, and "Betaling houden, verschil als ..." for purchase payments. */
@QuarkusTest
class BankFollowBankTest {
    private static final String KBC = "BE94731074082814";
    private static final Instant OCT_14 = Instant.parse("2026-10-14T08:00:00Z");
    private static final LocalDate OCT_1 = LocalDate.of(2026, 10, 1);
    private static final LocalDate OCT_13 = LocalDate.of(2026, 10, 13);

    @Inject BankStatementService bank;
    @Inject CompanyCostService costs;
    @Inject PurchaseOrderService purchases;
    @Inject SupplierService suppliers;
    @Inject IncomingPaymentService incoming;
    @Inject SalesOrderService sales;
    @Inject CustomerService customers;
    @Inject EntityManager em;

    @Test @TestTransaction
    void aCostPaymentFollowsTheTransactionAndThenLinks() {
        long costId = cost("121.00");
        long paymentId = pay(costId, "120.00", OCT_1);
        long line = line("-121.00");
        var linked = bank.linkExisting(line, new BankStatementService.OutgoingLinks(null, null, paymentId, true, null));
        assertTrue(linked.allocated, "the check read the followed amount: € 1,00 off before, linked now");
        assertEquals(new BigDecimal("0.00"), linked.allocationDifferenceEur);
        var payment = costs.view(costs.get(costId)).payments().getFirst();
        assertEquals(new BigDecimal("121.00"), payment.amountEur());
        assertEquals(LocalDate.of(2026, 10, 14), payment.paidOn());
        assertEquals(KBC, payment.account());
        assertEquals(line, payment.bankStatementLineId());
        assertEquals(LocalDate.of(2026, 10, 14), costs.get(costId).paidOn(), "the cost is paid on the bank's day");
        assertTrue(audits(line).contains("Kostbetaling volgt de bank: € 121,00 op 14/10/2026 (was € 120,00 op 01/10/2026)"));

        var retry = bank.linkExisting(line, new BankStatementService.OutgoingLinks(null, null, paymentId, true, null));
        assertEquals(line, retry.id, "the same follow again answers the line");
        assertEquals(1, costs.view(costs.get(costId)).payments().size());
    }

    @Test @TestTransaction
    void aCostNamedAloneFollowsItsOnePayment() {
        long costId = cost("80.00");
        pay(costId, "79.00", OCT_13);
        long line = line("-80.00");
        assertTrue(bank.linkExisting(line, new BankStatementService.OutgoingLinks(null, costId, null, true, null)).allocated);
        assertEquals(new BigDecimal("80.00"), costs.view(costs.get(costId)).payments().getFirst().amountEur());
    }

    @Test @TestTransaction
    void neverPaysACostMoreThanItsCashTotal() {
        long costId = cost("100.00");
        pay(costId, "60.00", OCT_1);
        long second = pay(costId, "40.00", OCT_1);
        long line = line("-50.00");
        refused(() -> bank.linkExisting(line, new BankStatementService.OutgoingLinks(null, null, second, true, null)),
                "De transactie is hoger dan wat er op deze kost openstaat; registreer het verschil apart");
        assertEquals(new BigDecimal("40.00"), em.find(be.enrosed.finance.adapter.out.persistence.CostPaymentEntity.class, second).amountEur);
        assertFalse(bank.list().stream().filter(row -> row.id == line).findFirst().orElseThrow().allocated);
    }

    @Test @TestTransaction
    void followingIsPerCostPayment() {
        var order = container();
        var purchase = purchases.addPayment(order.id(), OCT_13, new BigDecimal("100.00"), Currency.EUR, "Aanbetaling");
        long line = line("-100.00");
        String message = "Betaling volgt de bank kan per kostbetaling; inkoopbetalingen volgen de bank via hun eigen koppeling";
        refused(() -> bank.linkExisting(line, new BankStatementService.OutgoingLinks(List.of(purchase.id()), null, null, true, null)), message);
        long unpaid = cost("100.00");
        refused(() -> bank.linkExisting(line, new BankStatementService.OutgoingLinks(null, unpaid, null, true, null)), message);
        long paid = cost("100.00");
        long payment = pay(paid, "100.00", OCT_13);
        refused(() -> bank.linkExisting(line, new BankStatementService.OutgoingLinks(List.of(purchase.id()), null, payment, true, null)), message);
    }

    @Test @TestTransaction
    void aReceiptFollowsTheTransactionBeforeTheMatch() {
        var invoice = invoice("100");
        incoming.add(invoice.id(), new IncomingPaymentService.Request(new BigDecimal("120.00"), Instant.parse("2026-10-10T09:00:00Z"),
                "Europe/Brussels", invoice.number()));
        long paymentId = incoming.forOrder(invoice.id()).getFirst().id();
        long line = line("121.00");
        refused(() -> bank.allocate(line, new BankStatementService.Allocation(invoice.id(), paymentId)),
                "Bedrag of richting komen niet overeen; maximaal € 0,02 verschil is toegestaan");
        var allocated = bank.allocate(line, new BankStatementService.Allocation(invoice.id(), paymentId, true));
        assertTrue(allocated.allocated);
        assertEquals(paymentId, allocated.salesPaymentId);
        var receipt = incoming.forOrder(invoice.id()).getFirst();
        assertEquals(new BigDecimal("121.00"), receipt.amountEur());
        assertEquals(OCT_14, receipt.receivedAt());
        assertEquals(KBC, receipt.bankAccount());
        assertTrue(audits(line).contains("Ontvangst volgt de bank: € 121,00 op 14/10/2026 (was € 120,00 op 10/10/2026)"));
    }

    @Test @TestTransaction
    void keepingThePaymentBooksTheDifferenceAsAPaidCostOnTheLine() {
        var order = container();
        var purchase = purchases.addPayment(order.id(), OCT_13, new BigDecimal("12480.00"), Currency.EUR, "Aanbetaling 30%");
        long line = line("-12512.40");
        long before = costCount();
        var linked = bank.linkExisting(line, new BankStatementService.OutgoingLinks(List.of(purchase.id()), null, null, null, "BANKKOSTEN"));
        assertTrue(linked.allocated);
        assertEquals(new BigDecimal("0.00"), linked.allocationDifferenceEur);
        assertEquals("Inkoop en kosten", linked.allocationLabel);
        assertEquals(new BigDecimal("12480.00"), purchases.payments(order.id()).getFirst().amountEur(), "the payment keeps its amount");
        assertEquals(before + 1, costCount());
        var cost = costs.view(costs.get(newestCostId()));
        assertEquals("VERZEKERING", cost.cost().category());
        assertEquals("Bankkosten · Yunnan Pubei Flowers Co., Ltd", cost.cost().description());
        assertEquals("Yunnan Pubei Flowers Co., Ltd", cost.cost().party());
        assertEquals(VatRegime.EXEMPT, cost.cost().vatRegime());
        assertTrue(cost.cost().vatRegimeConfirmed());
        assertEquals(new BigDecimal("32.40"), cost.cashTotalEur());
        assertNull(cost.cost().dueDate());
        assertEquals(1, cost.payments().size(), "create added no payment of its own");
        var payment = cost.payments().getFirst();
        assertEquals(new BigDecimal("32.40"), payment.amountEur());
        assertEquals(KBC, payment.account());
        assertEquals(LocalDate.of(2026, 10, 14), payment.paidOn());
        assertEquals(line, payment.bankStatementLineId());
        assertEquals(BankStatementService.DIFFERENCE_NOTE, payment.note());
        assertEquals(new BigDecimal("0.00"), cost.openEur());
        assertTrue(audits(line).contains("Verschil € 32,40 geboekt als Bankkosten"));

        var retry = bank.linkExisting(line, new BankStatementService.OutgoingLinks(List.of(purchase.id()), null, null, null, "BANKKOSTEN"));
        assertEquals(line, retry.id);
        assertEquals(before + 1, costCount(), "a retry creates no second cost");
    }

    @Test @TestTransaction
    void anExchangeDifferenceNamesTheOrder() {
        var order = container();
        var purchase = purchases.addPayment(order.id(), OCT_13, new BigDecimal("1000.00"), Currency.EUR, "Saldo");
        long line = line("-1003.10");
        bank.linkExisting(line, new BankStatementService.OutgoingLinks(List.of(purchase.id()), null, null, null, "KOERSVERSCHIL"));
        var cost = costs.view(costs.get(newestCostId()));
        assertEquals("ANDERE", cost.cost().category());
        assertEquals("Koersverschil · " + purchases.get(order.id()).number(), cost.cost().description());
        assertEquals(VatRegime.OUT_OF_SCOPE, cost.cost().vatRegime());
        assertEquals(new BigDecimal("3.10"), cost.cashTotalEur());
        assertTrue(audits(line).contains("Verschil € 3,10 geboekt als Koersverschil"));
    }

    @Test @TestTransaction
    void keepingRefusesWithoutADifferenceOrWithACost() {
        var order = container();
        var purchase = purchases.addPayment(order.id(), OCT_13, new BigDecimal("500.00"), Currency.EUR, "Saldo");
        long costId = cost("10.00");
        long costPayment = pay(costId, "10.00", OCT_13);
        long before = costCount();
        long equal = line("-500.02");
        refused(() -> bank.linkExisting(equal, new BankStatementService.OutgoingLinks(List.of(purchase.id()), null, null, null, "BANKKOSTEN")),
                "Er is geen verschil om te boeken; koppel gewoon");
        long less = line("-450.00");
        refused(() -> bank.linkExisting(less, new BankStatementService.OutgoingLinks(List.of(purchase.id()), null, null, null, "KOERSVERSCHIL")),
                "De bank betaalde minder dan de betaling; laat de betaling de bank volgen");
        long more = line("-520.00");
        refused(() -> bank.linkExisting(more, new BankStatementService.OutgoingLinks(List.of(purchase.id()), null, costPayment, null, "BANKKOSTEN")),
                "Een verschil boeken kan alleen bij inkoopbetalingen");
        refused(() -> bank.linkExisting(more, new BankStatementService.OutgoingLinks(null, costId, null, null, "BANKKOSTEN")),
                "Een verschil boeken kan alleen bij inkoopbetalingen");
        refused(() -> bank.linkExisting(more, new BankStatementService.OutgoingLinks(List.of(purchase.id()), null, null, null, "RENTE")),
                "Kies Bankkosten of Koersverschil");
        assertEquals(before, costCount());
        assertNull(purchases.payments(order.id()).getFirst().bankStatementLineId());

        long elsewhere = line("-500.00");
        bank.linkExisting(elsewhere, new BankStatementService.OutgoingLinks(List.of(purchase.id()), null, null));
        refused(() -> bank.linkExisting(more, new BankStatementService.OutgoingLinks(List.of(purchase.id()), null, null, null, "BANKKOSTEN")),
                "Deze betaling is al aan een andere banktransactie gekoppeld");
        assertEquals(before, costCount(), "a payment on another line is refused before any cost is made");
        bank.unlinkExisting(elsewhere);

        /* Without keepDifferenceAs a link and its retry are today's. */
        long exact = line("-500.00");
        assertTrue(bank.linkExisting(exact, new BankStatementService.OutgoingLinks(List.of(purchase.id()), null, null)).allocated);
        assertEquals(exact, bank.linkExisting(exact, new BankStatementService.OutgoingLinks(List.of(purchase.id()), null, null)).id);
    }

    private static void refused(Executable call, String message) {
        assertEquals(message, assertThrows(BusinessRuleException.class, call).getMessage());
    }

    private List<String> audits(long lineId) {
        return em.createQuery("select a.summary from ActivityLogEntity a where a.entityType='BANK_STATEMENT' and a.entityId=:id order by a.id",
                String.class).setParameter("id", Long.toString(lineId)).getResultList();
    }

    private long costCount() {
        return em.createQuery("select count(c) from CompanyCostEntity c", Long.class).getSingleResult();
    }

    private long newestCostId() {
        return em.createQuery("select max(c.id) from CompanyCostEntity c", Long.class).getSingleResult();
    }

    private long line(String amount) {
        return bank.importExternal(new BankStatementService.ExternalRequest(UUID.randomUUID().toString(), KBC, new BigDecimal(amount),
                OCT_14, "Europe/Brussels", "PO-2026-043 DEPOSIT 30PCT", "Yunnan Pubei Flowers Co., Ltd", OCT_14, false)).id();
    }

    private long cost(String amount) {
        return costs.create(new CompanyCost(null, OCT_1, "SOFTWARE", "Follow fixture", "Proximus", new BigDecimal(amount), null, null,
                null, null, null, null, null, null, null, VatRegime.OUT_OF_SCOPE, true, null, null, null, null, null, null, null, null)).id();
    }

    private long pay(long costId, String amount, LocalDate day) {
        var view = costs.addPayment(costId, new CostPayment(null, null, day, new BigDecimal(amount), null, null, null, null, null));
        return view.payments().stream().map(CostPayment::id).max(Long::compare).orElseThrow();
    }

    private PurchaseOrder container() {
        var supplier = suppliers.save(new Supplier(null, "Yunnan Pubei Flowers Co., Ltd", "CN", "Kunming", null, null, null,
                Currency.USD, "FOB", "Ningbo", 35, null));
        return purchases.create(supplier.id(), new BigDecimal("0.14"), new BigDecimal("0.90"), BigDecimal.ZERO);
    }

    private SalesOrder invoice(String amount) {
        var customer = customers.create(new Customer(null, "Follow test", "Fin", null, null, "BE0000000000", "BE", Language.NL,
                "Main 1", "2000", "Antwerpen", "DAP", null, null, LocalDate.now()));
        var order = sales.create(customer.id(), "BE", "DAP", DocumentType.FACTUUR);
        var entity = em.find(SalesOrderEntity.class, order.id());
        entity.extraLinesJson = "[{\"description\":\"Service\",\"quantity\":1,\"unitPriceEur\":" + amount + "}]";
        entity.freightPricingStrategy = FreightPricingStrategy.FIXED;
        entity.manualFreightEur = BigDecimal.ZERO;
        entity.freight = FreightState.AANGEVULD;
        entity.status = QuoteStatus.UITGEREIKT;
        em.flush();
        em.clear();
        return sales.get(order.id());
    }
}
