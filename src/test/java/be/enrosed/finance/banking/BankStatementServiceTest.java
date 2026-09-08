package be.enrosed.finance.banking;

import be.enrosed.sales.application.*;
import be.enrosed.sales.adapter.out.persistence.SalesEntities.SalesOrderEntity;
import be.enrosed.sales.domain.*;
import be.enrosed.shared.*;
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
}
