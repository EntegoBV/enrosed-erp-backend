package be.enrosed.finance.banking;

import be.enrosed.finance.application.CompanyCostService;
import be.enrosed.finance.application.CostPartyService;
import be.enrosed.finance.adapter.out.persistence.CostPaymentEntity;
import be.enrosed.finance.domain.CompanyCost;
import be.enrosed.finance.domain.CostPayment;
import be.enrosed.finance.domain.VatRegime;
import be.enrosed.sales.application.*;
import be.enrosed.sales.adapter.out.persistence.SalesEntities.SalesOrderEntity;
import be.enrosed.sales.domain.*;
import be.enrosed.shared.*;
import be.enrosed.sourcing.application.PurchaseOrderService;
import be.enrosed.sourcing.application.SupplierService;
import be.enrosed.sourcing.domain.PurchasePayment;
import be.enrosed.sourcing.domain.Supplier;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.*;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class BankStatementServiceTest {
    @Inject BankStatementService bank;
    @Inject IncomingPaymentService incoming;
    @Inject SalesOrderService sales;
    @Inject CustomerService customers;
    @Inject PurchaseOrderService purchases;
    @Inject SupplierService suppliers;
    @Inject CompanyCostService costs;
    @Inject CostPartyService parties;
    @Inject EntityManager em;
    private static final Instant AT=Instant.parse("2026-09-08T10:00:00Z");

    @Test @TestTransaction void manualEntryPreservesExactBankMomentAndRetriesNeverDuplicateIt() {
        var request=entry("KBC", "12.50");
        var saved=bank.create(request);
        assertEquals(AT,saved.bookedAt);assertEquals("Europe/Brussels",saved.timeZone);
        assertEquals(new BigDecimal("12.50"),saved.amountEur);assertNotNull(saved.recordedAt);assertNotNull(saved.actor);
        assertEquals(saved.id,bank.create(request).id);assertEquals(1,bank.list().size());
        var otherAccount=new BankStatementService.ManualRequest("ING",request.amountEur(),request.direction(),request.bookedAt(),request.timeZone(),request.reference(),request.counterparty(),request.requestId());
        bank.create(otherAccount);assertEquals(2,bank.list().size());
        var conflict=new BankStatementService.ManualRequest("KBC",BigDecimal.TEN,request.direction(),request.bookedAt(),request.timeZone(),request.reference(),request.counterparty(),request.requestId());
        assertThrows(BusinessRuleException.class,()->bank.create(conflict));
    }

    @Test void manualEntryRequiresPositiveExactCentsAndAValidBankMoment() {
        // Each rejected request rolls back independently, as it does through REST.
        assertThrows(BusinessRuleException.class,()->bank.create(new BankStatementService.ManualRequest("KBC",BigDecimal.ZERO,BankStatementService.Direction.INCOMING,AT,"Europe/Brussels",null,null,"zero")));
        assertThrows(BusinessRuleException.class,()->bank.create(new BankStatementService.ManualRequest("KBC",new BigDecimal("1.005"),BankStatementService.Direction.INCOMING,AT,"Europe/Brussels",null,null,"fraction")));
        assertThrows(BusinessRuleException.class,()->bank.create(new BankStatementService.ManualRequest("KBC",BigDecimal.ONE,BankStatementService.Direction.INCOMING,null,"Europe/Brussels",null,null,"missing-time")));
        assertThrows(BusinessRuleException.class,()->bank.create(new BankStatementService.ManualRequest("KBC",BigDecimal.ONE,BankStatementService.Direction.INCOMING,Instant.now().plusSeconds(3600),"Europe/Brussels",null,null,"future")));
        assertThrows(BusinessRuleException.class,()->bank.create(new BankStatementService.ManualRequest("KBC",BigDecimal.ONE,BankStatementService.Direction.INCOMING,AT,"invalid/zone",null,null,"zone")));
        assertThrows(BusinessRuleException.class,()->bank.create(new BankStatementService.ManualRequest("KBC",BigDecimal.ONE,null,AT,"Europe/Brussels",null,null,"direction")));
        assertTrue(bank.list().isEmpty());
    }

    @Test @TestTransaction void allocationIsExplicitAndExistingReceiptIsNeverBookedTwice() {
        var invoice=invoice("100");
        incoming.add(invoice.id(),new IncomingPaymentService.Request(new BigDecimal("121"),AT,"Europe/Brussels",invoice.number()));
        long paymentId=incoming.forOrder(invoice.id()).getFirst().id();
        bank.create(entry("KBC","121"));var row=bank.list().getFirst();
        assertEquals(1,incoming.forOrder(invoice.id()).size());
        assertTrue(bank.suggestions(row.id).stream().anyMatch(match->Long.valueOf(paymentId).equals(match.existingPaymentId())));
        bank.allocate(row.id,new BankStatementService.Allocation(invoice.id(),paymentId));
        assertEquals(1,incoming.forOrder(invoice.id()).size());
        assertThrows(BusinessRuleException.class,()->incoming.delete(invoice.id(),paymentId));
        bank.unallocate(row.id);
        assertEquals(1,incoming.forOrder(invoice.id()).size());
        bank.delete(row.id);assertTrue(bank.list().isEmpty());
    }

    @Test @TestTransaction void hyphenPaymentReferenceFindsAPartialPaymentAheadOfAnUnrelatedExactAmount() {
        var intended=invoiceWithSlashNumber("100");
        var unrelated=invoice("25");
        assertTrue(intended.number().contains("/"), "fixture retains the actual slash-separated invoice number");
        String communication="Deelbetaling "+intended.number().replace('/', '-').toUpperCase(java.util.Locale.ROOT);
        var request=new BankStatementService.ManualRequest("KBC",new BigDecimal("30.25"),
                BankStatementService.Direction.INCOMING,AT,"Europe/Brussels",communication,"Partner","hyphen-partial");
        var row=bank.create(request);
        var matches=bank.suggestions(row.id);
        assertEquals(intended.id().longValue(),matches.getFirst().salesOrderId());
        assertEquals(100,matches.getFirst().score(), "reference matches despite no exact amount match");
        assertEquals(new BigDecimal("121.00"),matches.getFirst().openEur());
        assertTrue(matches.stream().anyMatch(match->match.salesOrderId()==unrelated.id()&&match.score()==20),
                "an unrelated exact amount remains a lower-ranked suggestion");
        assertEquals(intended.number(),matches.getFirst().number());
        assertEquals(intended.number(),sales.get(intended.id()).number());
        assertEquals(communication,bank.list().stream().filter(value->value.id.equals(row.id)).findFirst().orElseThrow().reference);
        assertTrue(incoming.forOrder(intended.id()).isEmpty(), "matching suggestions never create a payment");
        assertEquals(row.id,bank.create(request).id, "normalization must not alter stored bank-entry idempotency");
    }

    @Test @TestTransaction void legacySlashReferenceStillMatchesPartialPaymentsAndKeepsTheRecordedCommunication() {
        var intended=invoiceWithSlashNumber("100");
        String communication="Oude betalingsmededeling "+intended.number();
        var row=bank.create(new BankStatementService.ManualRequest("KBC",new BigDecimal("17.50"),
                BankStatementService.Direction.INCOMING,AT,"Europe/Brussels",communication,"Partner","slash-partial"));
        var match=bank.suggestions(row.id).stream().filter(value->value.salesOrderId()==intended.id()).findFirst().orElseThrow();
        assertEquals(100,match.score(), "both sides are normalized, including legacy bank references");
        assertEquals(intended.number(),match.number());
        assertEquals(communication,row.reference);
        assertTrue(incoming.forOrder(intended.id()).isEmpty());
        bank.allocate(row.id,new BankStatementService.Allocation(intended.id(),null));
        assertEquals(communication,incoming.forOrder(intended.id()).getFirst().reference(),
                "an explicit allocation preserves the bank's original communication");
        assertEquals(new BigDecimal("17.50"),incoming.forOrder(intended.id()).getFirst().amountEur());
    }

    @Test @TestTransaction void manualOutgoingRefundUsesSignedCashAndUndoRestoresCreditWithoutLosingBankLine() {
        var credit=invoice("-100");bank.create(entry("KBC","-121"));var line=bank.list().getFirst();
        assertEquals(new BigDecimal("-121.00"),line.amountEur);
        bank.allocate(line.id,new BankStatementService.Allocation(credit.id(),null));
        assertEquals(new BigDecimal("-121.00"),summary(credit.id()).receivedEur());
        assertEquals(BigDecimal.ZERO.setScale(2),summary(credit.id()).creditEur());
        assertEquals(QuoteStatus.BETAALD,sales.get(credit.id()).status());
        bank.unallocate(line.id);
        assertEquals(new BigDecimal("121.00"),summary(credit.id()).creditEur());
        assertEquals(1,bank.list().size());assertTrue(incoming.forOrder(credit.id()).isEmpty());
    }

    @Test @TestTransaction void overpaymentRefundGuardsCorrectionsAndVoidsAndPreservesAuditTimes() {
        var invoice=invoice("100");incoming.add(invoice.id(),new IncomingPaymentService.Request(new BigDecimal("150"),AT,"Europe/Brussels","Receipt"));
        var receipt=incoming.forOrder(invoice.id()).getFirst();
        incoming.add(invoice.id(),refund("29",AT.plusSeconds(60)));
        var refund=incoming.forOrder(invoice.id()).getLast();
        assertEquals(new BigDecimal("121.00"),summary(invoice.id()).receivedEur());
        assertEquals(new BigDecimal("150.00"),summary(invoice.id()).grossReceivedEur());
        assertEquals(new BigDecimal("29.00"),summary(invoice.id()).refundedEur());
        assertEquals(AT,sales.get(invoice.id()).paidAt());
        assertThrows(BusinessRuleException.class,()->incoming.add(invoice.id(),refund("0.01",AT.plusSeconds(60))));
        assertThrows(BusinessRuleException.class,()->incoming.delete(invoice.id(),receipt.id()));
        assertThrows(BusinessRuleException.class,()->incoming.update(invoice.id(),receipt.id(),new IncomingPaymentService.Request(new BigDecimal("120"),AT,"Europe/Brussels",null)));
        incoming.update(invoice.id(),refund.id(),refund("20",AT.plusSeconds(90)));
        assertEquals(refund.recordedAt(),incoming.forOrder(invoice.id()).getLast().recordedAt());
        assertEquals(refund.actor(),incoming.forOrder(invoice.id()).getLast().actor());
        incoming.delete(invoice.id(),refund.id());assertEquals(new BigDecimal("29.00"),summary(invoice.id()).refundableEur());
    }

    @Test @TestTransaction void creditRefundCannotExceedCreditAndVoidingHistoryDoesNotReviveCancelledInvoice() {
        var credit=invoice("-100");incoming.add(credit.id(),refund("40",AT));
        assertEquals(new BigDecimal("81.00"),summary(credit.id()).refundableEur());
        assertThrows(BusinessRuleException.class,()->incoming.add(credit.id(),refund("82",AT)));
        var invoice=invoice("100");incoming.add(invoice.id(),new IncomingPaymentService.Request(new BigDecimal("150"),AT,"Europe/Brussels",null));
        incoming.add(invoice.id(),new IncomingPaymentService.Request(BigDecimal.ONE,AT.plusSeconds(1),"Europe/Brussels",null));
        var last=incoming.forOrder(invoice.id()).getLast();
        em.find(SalesOrderEntity.class,invoice.id()).status=QuoteStatus.GEANNULEERD;em.flush();em.clear();
        incoming.delete(invoice.id(),last.id());assertEquals(QuoteStatus.GEANNULEERD,sales.get(invoice.id()).status());
    }

    @Test @TestTransaction void anOutgoingLineCarriesThePaymentsAndTheCostItSettledOnce() {
        var order=container();
        var goods=purchases.addPayment(order.id(),LocalDate.of(2026,9,8),new BigDecimal("900"),Currency.EUR,"Saldo");
        var inspection=purchases.addPayment(order.id(),LocalDate.of(2026,9,8),new BigDecimal("100"),Currency.EUR,"Inspectie",PurchasePayment.Payee.SEPARATE);
        var cost=costs.create(cost("Kosten overschrijving","12.50",LocalDate.of(2026,9,8)));
        var request=new BankStatementService.ManualRequest("KBC",new BigDecimal("1012.50"),BankStatementService.Direction.OUTGOING,AT,"Europe/Brussels",
                "Saldo · "+order.number()+" · containerbetaling #"+goods.id(),"Pay Co","transfer",java.util.List.of(goods.id(),inspection.id()),cost.id());
        var line=bank.create(request);
        assertEquals(java.util.List.of(line.id,line.id),purchases.payments(order.id()).stream().map(PurchasePayment::bankStatementLineId).toList(),
                "one transfer, one line, every payment of it on the line");
        assertEquals(line.id,purchases.paymentsSince(null).stream().filter(row->goods.id().equals(row.id())).findFirst().orElseThrow().bankStatementLineId());
        assertEquals(line.id,costs.get(cost.id()).bankStatementLineId());
        assertEquals(line.id,costs.paymentsOf(cost.id()).getFirst().bankStatementLineId(),"the one payment the switch wrote is on the line too");
        assertEquals(line.id,bank.create(request).id,"a retried request returns the line without linking again");
        assertEquals(line.id,costs.get(cost.id()).bankStatementLineId());

        assertThrows(BusinessRuleException.class,()->bank.create(linked("again",java.util.List.of(inspection.id()),null)),"a payment is on the bank once");
        assertThrows(BusinessRuleException.class,()->bank.create(linked("cost-again",null,cost.id())),"so is a cost");
        assertThrows(BusinessRuleException.class,()->bank.create(linked("no-payment",java.util.List.of(Long.MAX_VALUE),null)));
        assertThrows(BusinessRuleException.class,()->bank.create(linked("no-cost",null,Long.MAX_VALUE)));
        assertEquals(line.id,purchases.payments(order.id()).getLast().bankStatementLineId(),"a refused line links nothing");

        bank.delete(line.id);
        assertTrue(purchases.payments(order.id()).stream().allMatch(payment->payment.bankStatementLineId()==null),"the line goes, the payments are open again");
        assertNull(costs.get(cost.id()).bankStatementLineId());
        assertNull(costs.paymentsOf(cost.id()).getFirst().bankStatementLineId());
        assertEquals(2,purchases.payments(order.id()).size(),"the payments themselves stay");
    }

    @Test @TestTransaction void aLinePerPaymentOnOneCostIsAccepted() {
        var cost=costs.create(cost("KBC-factuur september","100.00",null));
        var first=costs.addPayment(cost.id(),payment("40",LocalDate.of(2026,9,10))).payments().getFirst();
        var second=costs.addPayment(cost.id(),payment("60",LocalDate.of(2026,9,20))).payments().getLast();
        assertEquals(LocalDate.of(2026,9,20),costs.get(cost.id()).paidOn(),"paid once the two cover the total");

        var lineOne=bank.create(paymentLine("kbc-1","40",first.id()));
        var lineTwo=bank.create(paymentLine("kbc-2","60",second.id()));
        var payments=costs.paymentsOf(cost.id());
        assertEquals(lineOne.id,payments.get(0).bankStatementLineId());
        assertEquals(lineTwo.id,payments.get(1).bankStatementLineId());
        assertNull(costs.get(cost.id()).bankStatementLineId(),"the cost-level link is the one-shot payment's only");

        var single=costs.create(cost("Boekhouder","50.00",null));
        var only=costs.addPayment(single.id(),payment("50",LocalDate.of(2026,9,21))).payments().getFirst();
        var lineThree=bank.create(paymentLine("kbc-3","50",only.id()));
        assertEquals(lineThree.id,costs.paymentsOf(single.id()).getFirst().bankStatementLineId());
        assertEquals(lineThree.id,costs.get(single.id()).bankStatementLineId(),"the only payment also links the cost");
    }

    @Test @TestTransaction void deletingTheLineClearsThePaymentLink() {
        var cost=costs.create(cost("KBC-factuur oktober","100.00",null));
        var only=costs.addPayment(cost.id(),payment("100",LocalDate.of(2026,10,2))).payments().getFirst();
        var line=bank.create(paymentLine("kbc-4","100",only.id()));
        assertEquals(line.id,costs.paymentsOf(cost.id()).getFirst().bankStatementLineId());
        assertEquals(line.id,costs.get(cost.id()).bankStatementLineId());

        bank.delete(line.id);

        assertNull(costs.paymentsOf(cost.id()).getFirst().bankStatementLineId(),"the line goes, the payment is open for the bank again");
        assertNull(costs.get(cost.id()).bankStatementLineId());
        assertEquals(LocalDate.of(2026,10,2),costs.get(cost.id()).paidOn(),"the payment itself stays");

        costs.deletePayment(cost.id(),only.id());
        assertTrue(costs.paymentsOf(cost.id()).isEmpty());
        assertNull(em.find(CostPaymentEntity.class,only.id()));
    }

    @Test @TestTransaction void legacyCostIdResolvesToItsSinglePayment() {
        var cost=costs.create(cost("Verzekering","300.00",null));
        var only=costs.addPayment(cost.id(),payment("300",LocalDate.of(2026,9,15))).payments().getFirst();
        var line=bank.create(linked("legacy-one",null,cost.id()));
        assertEquals(line.id,costs.paymentsOf(cost.id()).getFirst().bankStatementLineId(),"the cost alone names its single payment");
        assertEquals(line.id,costs.get(cost.id()).bankStatementLineId());
        assertThrows(BusinessRuleException.class,()->bank.create(linked("legacy-again",null,cost.id())),"its one payment is on the bank already");
        assertEquals(only.id(),costs.paymentsOf(cost.id()).getFirst().id());

        var split=costs.create(cost("KBC-factuur september","100.00",null));
        costs.addPayment(split.id(),payment("40",LocalDate.of(2026,9,3)));
        costs.addPayment(split.id(),payment("60",LocalDate.of(2026,9,9)));
        var refused=assertThrows(BusinessRuleException.class,()->bank.create(linked("legacy-split",null,split.id())));
        assertEquals("Kies de betaling die deze bankbeweging dekt",refused.getMessage());
        assertTrue(costs.paymentsOf(split.id()).stream().allMatch(payment->payment.bankStatementLineId()==null),"a refused line links nothing");
    }

    @Test @TestTransaction void secondLineOnOnePaymentIsRefused() {
        var cost=costs.create(cost("Google Workspace","12.00",null));
        var only=costs.addPayment(cost.id(),payment("12",LocalDate.of(2026,10,5))).payments().getFirst();
        var line=bank.create(paymentLine("google-1","12",only.id()));
        var refused=assertThrows(BusinessRuleException.class,()->bank.create(paymentLine("google-2","12",only.id())));
        assertEquals("Deze betaling is al aan een bankbeweging gekoppeld",refused.getMessage());
        assertThrows(BusinessRuleException.class,()->bank.create(paymentLine("no-such-payment","12",Long.MAX_VALUE)));
        assertEquals(line.id,costs.paymentsOf(cost.id()).getFirst().bankStatementLineId(),"a refused line links nothing");
    }

    private static CompanyCost cost(String description,String excl,LocalDate paidOn) {
        return new CompanyCost(null,LocalDate.of(2026,9,8),"BANK",description,"KBC",new BigDecimal(excl),null,null,null,paidOn,null,null,null,null,null,
                VatRegime.OUT_OF_SCOPE,true,null,null,null,null,null,null,null);
    }

    @Test @TestTransaction void externalImportIsIdempotentAndProviderReconciliationNeverAllocatesLocally() {
        var request=external("bank-17","150",false);
        var result=bank.importExternal(request);
        assertEquals(BankStatementService.ImportOutcome.CREATED,result.outcome());
        assertEquals(BankStatementService.ImportOutcome.UNCHANGED,bank.importExternal(request).outcome());
        assertEquals(BankStatementService.ImportOutcome.UPDATED,bank.importExternal(external("bank-17","150",true)).outcome());
        var line=bank.list().getFirst();
        assertEquals("ODOO",line.source);assertTrue(line.sourceReconciled);assertFalse(line.allocated);
        assertEquals(1,bank.list().size());
        assertThrows(BusinessRuleException.class,()->bank.delete(line.id));
    }

    @Test @TestTransaction void allocatedImportChangesAreReportedWithoutChangingMoneyOrReceipt() {
        var invoice=invoice("100");
        var result=bank.importExternal(external("bank-receipt","121",false));
        var allocated=bank.allocate(result.id(),new BankStatementService.Allocation(invoice.id(),null));
        assertTrue(allocated.allocated);
        assertEquals(allocated.id,bank.allocate(result.id(),new BankStatementService.Allocation(invoice.id(),null)).id);
        assertEquals(1,incoming.forOrder(invoice.id()).size(),"retry after a lost response must not double-book");
        assertEquals(BankStatementService.ImportOutcome.CONFLICT,bank.importExternal(external("bank-receipt","122",true)).outcome());
        assertEquals(new BigDecimal("121.00"),bank.list().getFirst().amountEur);
        assertEquals(new BigDecimal("121.00"),incoming.forOrder(invoice.id()).getFirst().amountEur());
        bank.unallocate(result.id());
        assertEquals(BankStatementService.ImportOutcome.UPDATED,bank.importExternal(external("bank-receipt","122",true)).outcome());
    }

    @Test @TestTransaction void importedTransferLinksPurchaseAndCostOnceAndUnlinkKeepsPayments() {
        var order=container();
        var purchase=purchases.addPayment(order.id(),LocalDate.of(2026,9,8),new BigDecimal("90"),Currency.EUR,"Leverancier");
        var cost=costs.create(cost("Bankkosten","10",null));
        var payment=costs.addPayment(cost.id(),payment("10",LocalDate.of(2026,9,8))).payments().getFirst();
        var line=bank.importExternal(external("combined","-100",false));
        var request=new BankStatementService.OutgoingLinks(java.util.List.of(purchase.id()),cost.id(),payment.id());
        assertTrue(bank.linkExisting(line.id(),request).allocated);
        assertEquals(line.id(),bank.linkExisting(line.id(),request).id,"same selection may be retried");
        assertEquals("Inkoop en kosten",bank.list().getFirst().allocationLabel);
        assertEquals(line.id(),purchases.payments(order.id()).getFirst().bankStatementLineId());
        assertEquals(BankStatementService.ImportOutcome.CONFLICT,bank.importExternal(external("combined","-101",true)).outcome());
        bank.unlinkExisting(line.id());
        assertFalse(bank.list().getFirst().allocated);
        assertEquals(1,purchases.payments(order.id()).size());assertEquals(1,costs.paymentsOf(cost.id()).size());
        assertNull(purchases.payments(order.id()).getFirst().bankStatementLineId());
        assertNull(costs.paymentsOf(cost.id()).getFirst().bankStatementLineId());
        assertNull(costs.get(cost.id()).bankStatementLineId());
    }

    @Test @TestTransaction void linkingRejectsWrongAmountWrongDirectionAndReuse() {
        var order=container();
        var first=purchases.addPayment(order.id(),LocalDate.of(2026,9,8),new BigDecimal("90"),Currency.EUR,"Eerste");
        var second=purchases.addPayment(order.id(),LocalDate.of(2026,9,8),new BigDecimal("90"),Currency.EUR,"Tweede");
        var outgoing=bank.importExternal(external("outgoing","-90",false));
        var wrong=bank.importExternal(external("wrong","-80",false));
        var receipt=bank.importExternal(external("incoming","90",false));
        var request=new BankStatementService.OutgoingLinks(java.util.List.of(first.id()),null,null);
        assertThrows(BusinessRuleException.class,()->bank.linkExisting(wrong.id(),request));
        assertThrows(BusinessRuleException.class,()->bank.linkExisting(receipt.id(),request));
        bank.linkExisting(outgoing.id(),request);
        assertThrows(BusinessRuleException.class,()->bank.linkExisting(outgoing.id(),new BankStatementService.OutgoingLinks(java.util.List.of(second.id()),null,null)));
        assertThrows(BusinessRuleException.class,()->bank.allocate(outgoing.id(),new BankStatementService.Allocation(invoice("-100").id(),null)));
        assertNull(purchases.payments(order.id()).getLast().bankStatementLineId());
    }

    @Test @TestTransaction void deletingOnePartOfAPurchaseTransferRequiresUnlinkingFirst() {
        var order=container();
        var goods=purchases.addPayment(order.id(),LocalDate.of(2026,9,8),new BigDecimal("70"),Currency.EUR,"Goederen");
        var freight=purchases.addPayment(order.id(),LocalDate.of(2026,9,8),new BigDecimal("30"),Currency.EUR,"Vracht");
        var line=bank.importExternal(external("delete-transfer-part","-100",false));
        bank.linkExisting(line.id(),new BankStatementService.OutgoingLinks(java.util.List.of(goods.id(),freight.id()),null,null));
        assertThrows(BusinessRuleException.class,()->purchases.deletePayment(order.id(),freight.id()));
        assertEquals(2,purchases.payments(order.id()).size());
        assertTrue(purchases.payments(order.id()).stream().allMatch(payment->line.id().equals(payment.bankStatementLineId())));
        bank.unlinkExisting(line.id());
        purchases.deletePayment(order.id(),freight.id());
        assertEquals(1,purchases.payments(order.id()).size());
        assertFalse(bank.list().getFirst().allocated);
    }

    @Test @TestTransaction void deletingACostFromASharedTransferRequiresUnlinkingFirst() {
        var order=container();
        var goods=purchases.addPayment(order.id(),LocalDate.of(2026,9,8),new BigDecimal("90"),Currency.EUR,"Goederen");
        var cost=costs.create(cost("Bankkosten gedeelde overschrijving","10",null));
        var paid=costs.addPayment(cost.id(),payment("10",LocalDate.of(2026,9,8))).payments().getFirst();
        var line=bank.importExternal(external("delete-shared-cost","-100",false));
        bank.linkExisting(line.id(),new BankStatementService.OutgoingLinks(java.util.List.of(goods.id()),cost.id(),paid.id()));
        assertThrows(BusinessRuleException.class,()->costs.deletePayment(cost.id(),paid.id()));
        assertThrows(BusinessRuleException.class,()->costs.delete(cost.id()));
        assertEquals(line.id(),costs.paymentsOf(cost.id()).getFirst().bankStatementLineId());
        assertEquals(line.id(),purchases.payments(order.id()).getFirst().bankStatementLineId());
        bank.unlinkExisting(line.id());
        costs.deletePayment(cost.id(),paid.id());
        assertTrue(costs.paymentsOf(cost.id()).isEmpty());
        assertFalse(bank.list().getFirst().allocated);
    }

    private static BankStatementService.ExternalRequest external(String id,String amount,boolean reconciled) {
        return new BankStatementService.ExternalRequest(id,"KBC",new BigDecimal(amount),AT,"Europe/Brussels","Betaling","Leverancier",AT,reconciled);
    }

    @Test @TestTransaction void deletingLastLinkedCostPaymentFreesImportedMovementAndReopensCost() {
        var cost=costs.create(cost("Abonnement","42",null));
        var payment=costs.addPayment(cost.id(),payment("42",LocalDate.of(2026,9,8))).payments().getFirst();
        var line=bank.importExternal(external("delete-cost-payment","-42",false));
        assertTrue(bank.linkExisting(line.id(),new BankStatementService.OutgoingLinks(null,cost.id(),payment.id())).allocated);
        assertEquals(line.id(),costs.get(cost.id()).bankStatementLineId());
        var reopened=costs.deletePayment(cost.id(),payment.id());
        assertEquals(new BigDecimal("42.00"),reopened.openEur());
        assertNull(costs.get(cost.id()).paidOn());assertNull(costs.get(cost.id()).bankStatementLineId());
        var retained=bank.list().stream().filter(row->row.id.equals(line.id())).findFirst().orElseThrow();
        assertEquals("ODOO",retained.source);assertFalse(retained.allocated);
    }

    @Test @TestTransaction void existingSalesReceiptAccountLabelCanBeReplacedByActualIbanWithoutDoubleBooking() {
        var invoice=invoice("100");
        incoming.add(invoice.id(),new IncomingPaymentService.Request(new BigDecimal("121"),AT,"Europe/Brussels","Already noted",IncomingPaymentService.Direction.RECEIPT,"KBC"));
        var receipt=incoming.forOrder(invoice.id()).getFirst();
        var source=external("iban","121",false);
        var imported=bank.importExternal(new BankStatementService.ExternalRequest(source.sourceId(),"BE12345678901234",source.amountEur(),source.bookedAt(),source.timeZone(),source.reference(),source.counterparty(),source.sourceUpdatedAt(),false));
        assertTrue(bank.suggestions(imported.id()).stream().anyMatch(match->receipt.id().equals(match.existingPaymentId())));
        bank.allocate(imported.id(),new BankStatementService.Allocation(invoice.id(),receipt.id()));
        assertEquals(1,incoming.forOrder(invoice.id()).size());
        var after=incoming.forOrder(invoice.id()).getFirst();assertEquals("BE12345678901234",after.bankAccount());
        assertEquals(receipt.receivedAt(),after.receivedAt());assertEquals(receipt.reference(),after.reference());
    }

    @Test @TestTransaction void atomicCostPaymentRetryCannotCreateASecondPayment() {
        var cost=costs.create(cost("Verzekering","100",null));
        costs.addPayment(cost.id(),payment("10",LocalDate.of(2026,9,1)));
        var imported=bank.importExternal(external("atomic-cost","-40",false));
        var request=new BankStatementService.CostAllocation(cost.id());
        assertTrue(bank.recordCostPayment(imported.id(),request).allocated);
        assertEquals(imported.id(),bank.recordCostPayment(imported.id(),request).id);
        var payments=costs.paymentsOf(cost.id());
        assertEquals(2,payments.size());
        var linked=payments.stream().filter(payment->imported.id().equals(payment.bankStatementLineId())).findFirst().orElseThrow();
        assertEquals(new BigDecimal("40.00"),linked.amountEur());
        assertEquals(new BigDecimal("50.00"),costs.view(costs.get(cost.id())).openEur());
        var other=costs.create(cost("Andere kost","40",null));
        assertThrows(BusinessRuleException.class,()->bank.recordCostPayment(imported.id(),new BankStatementService.CostAllocation(other.id())));
        assertTrue(costs.paymentsOf(other.id()).isEmpty());
    }

    private static CostPayment payment(String amount,LocalDate day) {
        return new CostPayment(null,null,day,new BigDecimal(amount),"KBC",null,null,null,null);
    }

    private BankStatementService.ManualRequest paymentLine(String requestId,String amount,Long costPaymentId) {
        return new BankStatementService.ManualRequest("KBC",new BigDecimal(amount),BankStatementService.Direction.OUTGOING,AT,"Europe/Brussels",
                "kostbetaling #"+costPaymentId,"KBC",requestId,null,null,costPaymentId);
    }

    @Test @TestTransaction void incomingMoneyTeachesTheListAanWieNoName() {
        bank.create(new BankStatementService.ManualRequest("KBC",new BigDecimal("250"),BankStatementService.Direction.INCOMING,AT,"Europe/Brussels","Factuur 2026-012","Bloemen BV","incoming-bloemen"));
        assertTrue(parties.list().stream().noneMatch(party->"Bloemen BV".equals(party.name())),"a customer who pays is no payee");
    }

    private BankStatementService.ManualRequest linked(String requestId,java.util.List<Long> paymentIds,Long costId) {
        return new BankStatementService.ManualRequest("KBC",BigDecimal.ONE,BankStatementService.Direction.OUTGOING,AT,"Europe/Brussels","Nogmaals","Pay Co",requestId,paymentIds,costId);
    }

    private be.enrosed.sourcing.domain.PurchaseOrder container() {
        var supplier=suppliers.save(new Supplier(null,"Pay Co","CN","Yiwu",null,null,null,Currency.USD,"FOB","Ningbo",30,null));
        return purchases.create(supplier.id(),new BigDecimal("0.14"),new BigDecimal("0.90"),BigDecimal.ZERO);
    }

    private IncomingPaymentService.Request refund(String amount,Instant at){return new IncomingPaymentService.Request(new BigDecimal(amount),at,"Europe/Brussels","Refund",IncomingPaymentService.Direction.REFUND,"KBC");}
    private SalesPaymentSummary summary(long id){var order=sales.get(id);return incoming.summary(order,sales.price(order));}
    private BankStatementService.ManualRequest entry(String account,String amount) {
        BigDecimal value=new BigDecimal(amount);
        return new BankStatementService.ManualRequest(account,value.abs(),value.signum()<0?BankStatementService.Direction.OUTGOING:BankStatementService.Direction.INCOMING,
                AT,"Europe/Brussels","Invoice payment","Partner",java.util.UUID.randomUUID().toString());
    }

    private SalesOrder invoice(String amount) {
        var customer=customers.create(new Customer(null,"Bank test","Fin",null,null,"BE0000000000","BE",Language.NL,"Main 1","2000","Antwerpen","DAP",null,null,LocalDate.now()));
        var order=sales.create(customer.id(),"BE","DAP",DocumentType.FACTUUR);
        var entity=em.find(SalesOrderEntity.class,order.id());
        entity.extraLinesJson="[{\"description\":\"Service\",\"quantity\":1,\"unitPriceEur\":"+amount+"}]";
        entity.freightPricingStrategy=FreightPricingStrategy.FIXED;entity.manualFreightEur=BigDecimal.ZERO;entity.freight=FreightState.AANGEVULD;entity.status=QuoteStatus.UITGEREIKT;
        em.flush();em.clear();return sales.get(order.id());
    }

    private SalesOrder invoiceWithSlashNumber(String amount) {
        var invoice=invoice(amount);
        em.find(SalesOrderEntity.class,invoice.id()).number="factuur/2026/"+invoice.id();
        em.flush();em.clear();return sales.get(invoice.id());
    }
}
