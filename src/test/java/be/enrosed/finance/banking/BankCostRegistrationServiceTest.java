package be.enrosed.finance.banking;

import be.enrosed.finance.application.CompanyCostService;
import be.enrosed.finance.domain.CompanyCost;
import be.enrosed.finance.domain.VatRegime;
import be.enrosed.shared.BusinessRuleException;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class BankCostRegistrationServiceTest {
    @Inject BankCostRegistrationService registration;
    @Inject BankStatementService bank;
    @Inject CompanyCostService costs;
    @Inject EntityManager em;
    static final Instant BOOKED=Instant.parse("2026-09-08T22:15:00Z");
    static BigDecimal money(String value){return new BigDecimal(value).setScale(2);}
    CompanyCost cost(String description) {
        return new CompanyCost(null,LocalDate.of(2026,9,1),"SOFTWARE",description,"Fixture Supplier",money("100"),money("121"),money("21"),"INV-FIXTURE",
                LocalDate.of(2026,9,2),null,null,null,null,null,VatRegime.BE,true,"BE",null,null,null,null,null,null,null);
    }
    BankStatementLineEntity line(String amount) {
        var value=money(amount);return bank.create(new BankStatementService.ManualRequest("ATOMIC-TEST",value.abs(),value.signum()>0?BankStatementService.Direction.INCOMING:BankStatementService.Direction.OUTGOING,
                BOOKED,"Europe/Brussels","Fixture transfer","Fixture Supplier",UUID.randomUUID().toString()));
    }
    BankCostRegistrationService.Request request(String description){return new BankCostRegistrationService.Request(UUID.randomUUID().toString(),cost(description));}
    long costCount(){return em.createQuery("select count(c) from CompanyCostEntity c",Long.class).getSingleResult();}
    void cleanup(long bankId) {
        QuarkusTransaction.requiringNew().run(()->{
            var receipt=em.find(BankCostRegistrationEntity.class,bankId);
            if(receipt!=null) {
                long costId=receipt.companyCostId;
                em.remove(receipt);
                em.createQuery("delete from CostPaymentEntity p where p.companyCostId=:id").setParameter("id",costId).executeUpdate();
                em.createQuery("delete from CompanyCostEntity c where c.id=:id").setParameter("id",costId).executeUpdate();
            }
            em.createQuery("delete from BankStatementLineEntity b where b.id=:id").setParameter("id",bankId).executeUpdate();
        });
    }
    @Test @TestTransaction void completeRegistrationAndLostResponseRetryKeepOneCostOnePaymentAndOriginalAmounts() {
        var row=line("-121.02");var request=request("Atomic invoice");long before=costCount();
        var first=registration.register(row.id,request);var retry=registration.register(row.id,request);
        assertEquals(before+1,costCount());assertEquals(first.cost().cost().id(),retry.cost().cost().id());
        assertEquals(first.cost().payments().getFirst().id(),retry.cost().payments().getFirst().id());assertEquals(1,retry.cost().payments().size());
        assertEquals(money("121"),retry.cost().cashTotalEur());assertEquals(money("100"),retry.cost().cost().amountExclEur());
        assertEquals(money("-121.02"),retry.line().amountEur);assertEquals(money("-0.02"),retry.line().allocationDifferenceEur);
        assertEquals(LocalDate.of(2026,9,1),retry.cost().cost().date());assertEquals(LocalDate.of(2026,9,9),retry.cost().payments().getFirst().paidOn());
        assertEquals(money("0"),retry.cost().openEur());assertEquals(VatRegime.BE,retry.cost().cost().vatRegime());
    }
    @Test @TestTransaction void sameKeyWithChangedInvoiceDataCannotCreateOrRewriteTheCost() {
        var row=line("-121");var request=request("Original invoice");var saved=registration.register(row.id,request);
        var changed=new BankCostRegistrationService.Request(request.requestId(),cost("Changed invoice"));
        var error=assertThrows(BankCostRegistrationService.Conflict.class,()->registration.register(row.id,changed));
        assertEquals("BANK_COST_REGISTRATION_CHANGED",error.code);assertEquals("Original invoice",costs.get(saved.cost().cost().id()).description());
    }
    @Test @TestTransaction void anotherRequestForTheSameBankLineCannotCreateADuplicateCost() {
        var row=line("-121");var saved=registration.register(row.id,request("First request"));long before=costCount();
        var error=assertThrows(BankCostRegistrationService.Conflict.class,()->registration.register(row.id,request("Second request")));
        assertEquals("BANK_COST_REGISTRATION_EXISTS",error.code);assertEquals(before,costCount());assertEquals(1,costs.paymentsOf(saved.cost().cost().id()).size());
    }
    @Test @TestTransaction void aBankLineAlreadyLinkedThroughAnotherFlowCannotCreateACost() {
        var row=line("-121");var existing=costs.create(new CompanyCost(null,LocalDate.of(2026,9,1),"SOFTWARE","Existing","Fixture",money("100"),money("121"),money("21"),null,null,null,null,null,null,null,VatRegime.BE,true,"BE",null,null,null,null,null,null,null));
        bank.recordCostPayment(row.id,new BankStatementService.CostAllocation(existing.id()));long before=costCount();
        assertEquals("BANK_ALREADY_ALLOCATED",assertThrows(BankCostRegistrationService.Conflict.class,()->registration.register(row.id,request("Duplicate"))).code);
        assertEquals(before,costCount());assertNull(em.find(BankCostRegistrationEntity.class,row.id));
    }
    @Test void wrongAmountRollsBackCostPaymentAndReceiptTogether() {
        var row=line("-121.03");long before=costCount();
        try {assertThrows(BusinessRuleException.class,()->registration.register(row.id,request("Wrong gross")));
            assertEquals(before,costCount());assertNull(em.find(BankCostRegistrationEntity.class,row.id));assertFalse(bank.list().stream().filter(value->value.id.equals(row.id)).findFirst().orElseThrow().allocated);
        } finally {cleanup(row.id);}
    }
    @Test @TestTransaction void incomingBankMoneyCannotBecomeAnOrdinaryPositiveExpense() {
        var row=line("121");long before=costCount();assertThrows(BusinessRuleException.class,()->registration.register(row.id,request("Wrong direction")));
        assertEquals(before,costCount());assertNull(em.find(BankCostRegistrationEntity.class,row.id));
    }
    @Test @TestTransaction void missingVatRegimeIsValidatedByOrdinaryCostRules() {
        var row=line("-121");var invalid=new CompanyCost(null,LocalDate.of(2026,9,1),"SOFTWARE","No VAT regime","Fixture",money("100"),money("121"),money("21"),null,null,null,null,null,null,null);
        var error=assertThrows(BusinessRuleException.class,()->registration.register(row.id,new BankCostRegistrationService.Request(UUID.randomUUID().toString(),invalid)));
        assertEquals("Kies de btw-regeling",error.getMessage());assertNull(em.find(BankCostRegistrationEntity.class,row.id));
    }
    @Test @TestTransaction void deletingTheCreatedCostRetainsReceiptAndPreventsItsRecreation() {
        var row=line("-121");var request=request("Later removed");var saved=registration.register(row.id,request);
        costs.delete(saved.cost().cost().id());assertNotNull(em.find(BankCostRegistrationEntity.class,row.id));long before=costCount();
        assertEquals("BANK_COST_REGISTRATION_CHANGED",assertThrows(BankCostRegistrationService.Conflict.class,()->registration.register(row.id,request)).code);
        assertEquals(before,costCount());
    }
    /** Koppeling losmaken keeps the receipt while its cost exists: no second paid cost for one movement; once it is deleted the line registers again. */
    @Test @TestTransaction void unlinkingKeepsTheReceiptWhileItsCostExistsAndTheLineRegistersAgainOnceItIsDeleted() {
        var row=line("-121");var original=request("Kept after unlink");var saved=registration.register(row.id,original);long first=saved.cost().cost().id();
        bank.unlinkExisting(row.id);em.flush();
        assertEquals(first,em.find(BankCostRegistrationEntity.class,row.id).companyCostId);
        assertEquals(1,costs.paymentsOf(first).size());assertNull(costs.paymentsOf(first).getFirst().bankStatementLineId());
        long before=costCount();
        assertEquals("BANK_COST_REGISTRATION_CHANGED",assertThrows(BankCostRegistrationService.Conflict.class,()->registration.register(row.id,original)).code,"the pending retry");
        var refused=assertThrows(BankCostRegistrationService.Conflict.class,()->registration.register(row.id,request("Registered twice")));
        assertEquals("BANK_COST_REGISTRATION_EXISTS",refused.code);
        assertEquals("Voor deze banktransactie bestaat al kost 'Kept after unlink': koppel die opnieuw of verwijder die eerst",refused.getMessage());
        assertEquals(before,costCount(),"no second cost for the same movement");
        costs.delete(first);em.flush();
        var request=request("Registered again");
        var again=registration.register(row.id,request);var retry=registration.register(row.id,request);
        assertEquals(before,costCount());assertNotEquals(first,again.cost().cost().id());assertTrue(again.line().allocated);
        assertEquals(again.cost().cost().id(),em.find(BankCostRegistrationEntity.class,row.id).companyCostId);
        assertEquals(again.cost().cost().id(),retry.cost().cost().id(),"the receipt answers the new request's retry");
        assertEquals("BANK_COST_REGISTRATION_EXISTS",assertThrows(BankCostRegistrationService.Conflict.class,()->registration.register(row.id,original)).code);
    }
    @Test @TestTransaction void deletingTheBankMovementRemovesItsReceiptAndKeepsTheCost() {
        var row=line("-121");var saved=registration.register(row.id,request("Kept after delete"));
        bank.delete(row.id);em.flush();
        assertNull(em.find(BankCostRegistrationEntity.class,row.id));assertNotNull(costs.get(saved.cost().cost().id()));
        assertNull(em.find(BankStatementLineEntity.class,row.id));
    }
    @Test void concurrentIdenticalRetriesReturnTheSameCreatedIds() throws Exception {concurrent(true);}
    @Test void concurrentDifferentRequestsCreateOnlyOneCostAndOnePayment() throws Exception {concurrent(false);}
    void concurrent(boolean sameKey) throws Exception {
        var row=line("-121");var first=request("Concurrent invoice");var second=sameKey?first:request("Competing invoice");
        var ready=new CountDownLatch(2);var start=new CountDownLatch(1);
        java.util.function.Function<BankCostRegistrationService.Request,CompletableFuture<BankCostRegistrationService.Result>> worker=request->CompletableFuture.supplyAsync(()->{
            ready.countDown();try {if(!start.await(10,TimeUnit.SECONDS))throw new AssertionError("Workers did not start");}
            catch(InterruptedException error){Thread.currentThread().interrupt();throw new AssertionError(error);}
            try {return registration.register(row.id,request);}catch(BankCostRegistrationService.Conflict conflict){return null;}
        });
        try {
            var a=worker.apply(first);var b=worker.apply(second);assertTrue(ready.await(10,TimeUnit.SECONDS));start.countDown();
            var resultA=a.get(20,TimeUnit.SECONDS);var resultB=b.get(20,TimeUnit.SECONDS);
            if(sameKey){assertNotNull(resultA);assertNotNull(resultB);assertEquals(resultA.cost().cost().id(),resultB.cost().cost().id());assertEquals(resultA.cost().payments().getFirst().id(),resultB.cost().payments().getFirst().id());}
            else assertNotEquals(resultA==null,resultB==null);
            var receipt=em.find(BankCostRegistrationEntity.class,row.id);assertNotNull(receipt);assertEquals(1,costs.paymentsOf(receipt.companyCostId).size());
        } finally {start.countDown();cleanup(row.id);}
    }
    @Test void migrationIsAdditiveRerunnableAndDeployedAfterSourceImports() throws Exception {
        String name="bank-cost-registration-postgresql.sql";String sql=Files.readString(Path.of("docs/migrations/2026-10-13",name));
        assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS"));assertTrue(sql.contains("bank_statement_line_id bigint PRIMARY KEY"));assertFalse(sql.contains("REFERENCES"));
        assertTrue(Files.readString(Path.of("Dockerfile")).contains("COPY docs/migrations/2026-10-13/"+name));
        String runner=Files.readString(Path.of("scripts/run-postgresql-schema-migrations.sh"));assertTrue(runner.indexOf("--file=/app/migrations/"+name)>runner.indexOf("--file=/app/migrations/odoo-cost-import-postgresql.sql"));
    }
}
