package be.enrosed.finance.odoo;

import be.enrosed.finance.adapter.out.persistence.*;
import be.enrosed.finance.application.CompanyCostService;
import be.enrosed.finance.banking.*;
import be.enrosed.finance.domain.CostPayment;
import be.enrosed.shared.BusinessRuleException;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class OdooCostImportStoreTest {
    @Inject OdooCostImportStore store;
    @Inject CompanyCostService costs;
    @Inject BankStatementService bank;
    @Inject EntityManager em;
    @Inject OdooCostImportSlots slots;
    static final String NS="incoming-test", HASH="a".repeat(64);
    static BigDecimal money(String value){return new BigDecimal(value).setScale(2);}
    OdooDocumentService.PurchaseSnapshot snapshot(long id,String type,String party,String reference,String date,String net,String vat,String total) {
        var document=new OdooDocumentService.Document(id,"BILL/"+id,reference,party,date,type,"draft","EUR",money(total).abs(),"done","message-"+id,List.of());
        return new OdooDocumentService.PurchaseSnapshot(NS,1,document,9,"BE0123456789","BE",money(net),money(vat),money(total),"2026-09-01 12:00:00",HASH);
    }
    OdooDocumentService.PurchaseSnapshot invoice(long id){return snapshot(id,"in_invoice","Example Supplies","INV-"+id,"2026-09-01","100","21","121");}
    BankStatementLineEntity line(String amount,String date){
        var value=money(amount);return bank.create(new BankStatementService.ManualRequest("TEST",value.abs(),value.signum()>0?BankStatementService.Direction.INCOMING:BankStatementService.Direction.OUTGOING,
                LocalDate.parse(date).atTime(12,0).toInstant(ZoneOffset.UTC),"Europe/Brussels","invoice","Example Supplies",UUID.randomUUID().toString()));
    }
    void slot(OdooDocumentService.PurchaseSnapshot source){var row=new OdooCostImportEntity();row.id=NS+":"+source.document().id();row.namespace=NS;row.companyId=1;row.moveId=source.document().id();row.createdAt=Instant.now();em.persist(row);em.flush();}
    OdooCostImportStore.Result register(BankStatementLineEntity line,OdooDocumentService.PurchaseSnapshot source,Long existing){slot(source);return store.register(line.id,source,HASH,existing,null,List.of());}

    @Test @TestTransaction void newDraftBillPreservesAmountsAndCompletedRetryNeverCreatesAnotherPayment(){
        var source=invoice(2001);var row=line("-121.02","2026-09-08");var result=register(row,source,null);
        var cost=costs.view(costs.get(result.companyCostId()));
        assertEquals(money("121"),cost.cashTotalEur());assertEquals(money("121"),cost.paidEur());assertEquals(money("0"),cost.openEur());
        assertEquals(money("-0.02"),result.differenceEur());assertEquals(money("-121.02"),result.line().amountEur);
        var retry=store.completedRetry(NS,row.id,2001,HASH);assertNotNull(retry);assertEquals(result.companyCostId(),retry.companyCostId());
        var repeated=store.register(row.id,source,HASH,null,null,List.of());assertEquals(result.companyCostId(),repeated.companyCostId());assertTrue(repeated.alreadyRegistered());
        assertEquals(1,cost.payments().size());assertEquals("draft",store.source(result.companyCostId()).snapshot().document().state());
        assertEquals(Boolean.FALSE,cost.cost().vatRegimeConfirmed());assertNull(cost.cost().vatPct());
    }
    @Test @TestTransaction void supplierCreditCreatesNegativeCostAndRefundPaymentWithoutChangingBank(){
        var source=snapshot(2002,"in_refund","Credit Supplier","CN-2002","2026-09-01","-78.99","-16.59","-95.58");
        var result=register(line("95.60","2026-09-08"),source,null);var cost=costs.view(costs.get(result.companyCostId()));
        assertEquals(money("-95.58"),cost.cashTotalEur());assertEquals(money("-95.58"),cost.paidEur());assertEquals(money("0"),cost.openEur());
        assertEquals(money("0.02"),result.differenceEur());assertEquals(1,cost.payments().size());assertNotNull(cost.cost().paidOn());
        var updated=costs.update(result.companyCostId(),costs.get(result.companyCostId()));assertEquals(money("-95.58"),updated.totalInclEur());
    }
    @Test @TestTransaction void existingPaymentIsReusedAndHistoricalManualBankLineIsRetained(){
        var source=invoice(2003);long id=costs.createImported(source,null).id();var old=line("-121","2026-09-08");bank.recordCostPayment(old.id,new BankStatementService.CostAllocation(id));
        long paymentId=costs.view(costs.get(id)).payments().getFirst().id();var official=line("-121.01","2026-09-08");
        var result=register(official,source,id);assertEquals(id,result.companyCostId());assertEquals(paymentId,costs.view(costs.get(id)).payments().getFirst().id());
        assertEquals(1,costs.view(costs.get(id)).payments().size());assertNotNull(store.line(old.id));assertFalse(bank.list().stream().filter(row->row.id.equals(old.id)).findFirst().orElseThrow().allocated);
    }
    @Test @TestTransaction void existingBankLinkedCostGetsOnlySourceMetadata(){
        var source=invoice(2004);long id=costs.createImported(source,null).id();var row=line("-121","2026-09-08");bank.recordCostPayment(row.id,new BankStatementService.CostAllocation(id));
        long paymentId=costs.view(costs.get(id)).payments().getFirst().id();var result=register(row,source,id);
        assertEquals(paymentId,costs.view(costs.get(id)).payments().getFirst().id());assertEquals(id,result.companyCostId());assertNotNull(store.source(id));
    }
    @Test @TestTransaction void sameSupplierAndReferenceWithDifferentGrossCannotBeAdopted(){
        var source=invoice(2005);var existing=costs.createImported(snapshot(999,"in_invoice","Example Supplies","INV-2005","2026-09-01","200","42","242"),null);
        var row=line("-121","2026-09-08");var plan=store.plan(row.id,source,existing.id());assertNotNull(plan.unavailableReason());assertTrue(plan.unavailableReason().contains("totaal"));
    }
    @Test @TestTransaction void equalGrossWithDifferentNetAndTaxCannotBeAdopted(){
        var source=invoice(2006);var existing=costs.createImported(snapshot(999,"in_invoice","Example Supplies","INV-2006","2026-09-01","121","0","121"),null);
        var plan=store.plan(line("-121","2026-09-08").id,source,existing.id());assertTrue(plan.unavailableReason().contains("netto- of btw"));
    }
    @Test @TestTransaction void recurringSamePriceInOtherMonthsDoesNotForceUnrelatedExistingCost(){
        costs.createImported(snapshot(997,"in_invoice","Monthly Supplier","JUN-001","2026-06-15","300","63","363"),null);
        costs.createImported(snapshot(998,"in_invoice","Monthly Supplier","AUG-001","2026-08-17","300","63","363"),null);
        var july=snapshot(2007,"in_invoice","Monthly Supplier","JUL-001","2026-07-01","300","63","363");
        assertTrue(store.plan(line("-363","2026-07-15").id,july,null).existingCandidates().isEmpty());
    }
    @Test @TestTransaction void legalSuffixDifferenceIsCandidateButUnrelatedSupplierWithGenericReferenceIsNot(){
        var source=snapshot(2008,"in_invoice","Example S.A.","GENERIC-2026","2026-09-01","100","21","121");
        long intended=costs.createImported(snapshot(997,"in_invoice","Example","LOCAL-REFERENCE","2026-09-01","100","21","121"),null).id();
        costs.createImported(snapshot(998,"in_invoice","Unrelated Vendor","GENERIC-2026","2026-09-01","100","21","121"),null);
        var candidates=store.candidates(source,line("-121","2026-09-08").id);assertEquals(List.of(intended),candidates.stream().map(OdooCostImportStore.Candidate::id).toList());
        assertEquals("ODOO",OdooCostImportStore.partyKey("Odoo S.A."));
    }
    @Test @TestTransaction void mixedVatOriginalTotalsSurviveImportWithoutInventedVatRate(){
        var source=snapshot(2009,"in_invoice","Mixed Supplier","MIX-2009","2026-09-01","1214.40","185.60","1400");
        var result=register(line("-1400","2026-09-08"),source,null);var cost=costs.get(result.companyCostId());
        assertEquals(money("1214.40"),cost.amountExclEur());assertEquals(money("1400"),cost.totalInclEur());assertNull(cost.vatPct());
    }
    @Test @TestTransaction void differentOdooNamespaceAndWrongCreditDirectionAreRejected(){
        var row=line("-121","2026-09-08");row.source="ODOO";row.sourceId="other:1";em.merge(row);em.flush();
        assertThrows(BusinessRuleException.class,()->store.plan(row.id,invoice(2010),null));
    }
    @Test @TestTransaction void threeCentOverpaymentAndSourceChangeAreRejected(){
        var source=invoice(2011);var row=line("-121.03","2026-09-08");assertNotNull(store.plan(row.id,source,null).unavailableReason());slot(source);
        assertEquals("PURCHASE_PREVIEW_CHANGED",assertThrows(OdooFailure.class,()->store.register(row.id,source,"b".repeat(64),null,null,List.of())).code);
    }
    @Test @TestTransaction void xmlIsRetainedAsDownloadableMediaAndLinkedToCost(){
        var source=invoice(2012);var row=line("-121","2026-09-08");slot(source);
        var file=new OdooDocumentService.Download("invoice.xml","application/xml","<Invoice><ID>synthetic</ID></Invoice>".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var result=store.register(row.id,source,HASH,null,null,List.of(new OdooCostImportStore.Downloaded(12,"XML",file)));
        assertEquals(1,result.documentsSaved());assertEquals("XML",store.source(result.companyCostId()).documents().getFirst().kind());
        assertTrue(store.source(result.companyCostId()).documents().getFirst().mediaAssetId()>0);
    }

    @Test @TestTransaction void historicalInvalidBankAmountCannotBeUsedForSourceAssociation(){
        var source=invoice(2013);long id=costs.createImported(source,null).id();var row=line("-121","2026-09-08");bank.recordCostPayment(row.id,new BankStatementService.CostAllocation(id));
        var payment=em.find(CostPaymentEntity.class,costs.view(costs.get(id)).payments().getFirst().id());payment.amountEur=money("100");em.flush();
        assertTrue(store.plan(row.id,source,id).unavailableReason().contains("bestaande betaling"));
    }
    @Test void invalidPdfRollsBackCostPaymentLinkAndBindingTogether(){
        var source=invoice(2014);var row=line("-121","2026-09-08");slots.reserve(NS,1,2014);
        long before=em.createQuery("select count(c) from CompanyCostEntity c",Long.class).getSingleResult();
        var file=new OdooDocumentService.Download("invalid.pdf","application/pdf","not a PDF".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        try {
            assertThrows(BusinessRuleException.class,()->store.register(row.id,source,HASH,null,null,List.of(new OdooCostImportStore.Downloaded(14,"PDF",file))));
            assertEquals(before,em.createQuery("select count(c) from CompanyCostEntity c",Long.class).getSingleResult());
            assertNull(store.find(NS,2014).companyCostId);assertFalse(bank.list().stream().filter(value->value.id.equals(row.id)).findFirst().orElseThrow().allocated);
        } finally {
            bank.delete(row.id);
            io.quarkus.narayana.jta.QuarkusTransaction.requiringNew().run(()->em.remove(em.find(OdooCostImportEntity.class,NS+":2014")));
        }
    }
}
