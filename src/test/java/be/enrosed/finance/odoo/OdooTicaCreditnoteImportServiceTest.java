package be.enrosed.finance.odoo;

import be.enrosed.shared.BusinessRuleException;
import be.enrosed.tica.TicaSettlementPdfService;
import be.enrosed.tica.TicaSettlementService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OdooTicaCreditnoteImportServiceTest {
    final ObjectMapper json=new ObjectMapper();
    final byte[] fixture="%PDF-fixture".getBytes(java.nio.charset.StandardCharsets.UTF_8);

    OdooTicaCreditnoteImportService service() throws Exception {
        var service=new OdooTicaCreditnoteImportService();service.config=OdooClientTest.config();service.json=json;
        service.client=mock(OdooClient.class);service.documents=mock(OdooDocumentService.class);
        service.state=mock(OdooSyncStateStore.class);service.pdf=mock(TicaSettlementPdfService.class);
        service.settlements=mock(TicaSettlementService.class);service.costImports=mock(OdooCostImportStore.class);
        service.tracked=mock(OdooTicaDocumentStore.class);service.bankService=mock(OdooService.class);
        when(service.bankService.automaticBankSyncReady()).thenReturn(true);
        when(service.tracked.observe(any())).thenReturn(new OdooTicaDocumentStore.Observed(null,null));
        when(service.settlements.createImported(any(),anyString())).thenReturn(settlement());
        when(service.state.acquireBank(anyString(),anyString(),eq(true))).thenReturn(true);
        when(service.client.search(eq("res.company"),anyList(),anyList(),eq(1),eq("id asc")))
                .thenReturn(json.readTree("[{\"id\":7,\"vat\":\"BE1034273386\"}]"));
        searches(service);
        when(service.documents.purchaseSnapshot(11)).thenReturn(snapshot(true,"hash1"));
        when(service.documents.download(11,22)).thenReturn(new OdooDocumentService.Download("267653273.pdf","application/pdf",fixture));
        when(service.pdf.preview(fixture,"267653273.pdf")).thenReturn(preview("95.58"));
        return service;
    }
    void searches(OdooTicaCreditnoteImportService service) throws Exception {
        when(service.client.search(eq("res.partner"),anyList(),anyList(),eq(100),eq("id asc")))
                .thenReturn(json.readTree("[{\"id\":3,\"vat\":\"BE 0428.038.927\"}]"),json.readTree("[]"));
        when(service.client.search(eq("account.move"),anyList(),anyList(),eq(100),eq("id asc")))
                .thenReturn(json.readTree("[{\"id\":11,\"company_id\":[7,\"ENROSED\"],\"partner_id\":[3,\"TICA\"],\"move_type\":\"in_refund\",\"state\":\"draft\"}]"),json.readTree("[]"));
    }
    OdooDocumentService.PurchaseSnapshot snapshot(boolean attached,String hash) {
        var files=attached?List.of(new OdooDocumentService.Attachment(22,"267653273.pdf","application/pdf",20,"PDF")):List.<OdooDocumentService.Attachment>of();
        var doc=new OdooDocumentService.Document(11,"BILL/2026/11","267653273","TICA","2026-10-12","in_refund","draft","EUR",new BigDecimal("95.58"),null,null,files);
        return new OdooDocumentService.PurchaseSnapshot(OdooClientTest.config().sourceNamespace(),7,doc,3,"BE0428038927","BE",
                new BigDecimal("-78.99"),new BigDecimal("-16.59"),new BigDecimal("-95.58"),"2026-10-12 10:00:00",hash);
    }
    OdooDocumentService.PurchaseSnapshot postedSnapshot(String hash) {
        var source=snapshot(true,hash);var old=source.document();
        var posted=new OdooDocumentService.Document(old.id(),old.name(),old.reference(),old.partner(),old.date(),old.moveType(),"posted",
                old.currency(),old.total(),null,null,old.attachments());
        return new OdooDocumentService.PurchaseSnapshot(source.namespace(),source.companyId(),posted,source.partnerId(),source.partnerVat(),
                source.supplierCountry(),source.netEur(),source.vatEur(),source.totalEur(),"2026-10-13 10:00:00",hash);
    }
    TicaSettlementPdfService.Preview preview(String net) {
        return new TicaSettlementPdfService.Preview("/api/tica/settlements/documents/1","267653273.pdf","267653273","TICA - Brussel",
                LocalDate.of(2026,10,5),LocalDate.of(2026,10,11),LocalDate.of(2026,10,12),LocalDate.of(2026,10,26),
                new BigDecimal("100.00"),new BigDecimal("21.00"),new BigDecimal("21.01"),new BigDecimal("4.41"),new BigDecimal(net),List.of());
    }

    TicaSettlementService.Dto settlement() {
        var preview=preview("95.58");
        return new TicaSettlementService.Dto(1L,preview.reference(),preview.location(),preview.documentDate(),preview.dueDate(),
                preview.periodFrom(),preview.periodTo(),preview.salesExcl(),preview.salesVat(),preview.commissionExcl(),
                preview.commissionVat(),preview.netReceivable(),"Source draft",preview.sourceDocumentUrl(),"DRAFT",
                preview.salesExcl(),BigDecimal.ZERO,null,null,null,java.time.Instant.now(),
                OdooTicaCreditnoteImportService.sourceKey(OdooClientTest.config().sourceNamespace(),11));
    }

    @Test void verifiedIncomingDocumentCreatesOnlyOneDraftAndRepeatedSourceSkipsDownload() throws Exception {
        var service=service();var report=service.syncIfDue().orElseThrow();
        assertTrue(report.complete());assertEquals(1,report.imported());
        var request=ArgumentCaptor.forClass(TicaSettlementService.Request.class);
        String sourceKey=OdooTicaCreditnoteImportService.sourceKey(service.config.sourceNamespace(),11);
        verify(service.settlements).createImported(request.capture(),eq(sourceKey));
        assertEquals(LocalDate.of(2026,10,26),request.getValue().dueDate());
        assertTrue(request.getValue().documentNote().contains("Bronstatus: draft"));
        assertEquals("/api/tica/settlements/documents/1",request.getValue().sourceDocumentUrl());
        verify(service.settlements,never()).confirm(anyLong());
        verify(service.client,never()).write(anyString(),anyString(),anyMap());
        when(service.settlements.findBySource(sourceKey)).thenReturn(Optional.of(settlement()));
        when(service.tracked.observe(any())).thenReturn(new OdooTicaDocumentStore.Observed("hash1",1L,"draft"));
        searches(service);
        var repeated=service.syncIfDue().orElseThrow();assertEquals(1,repeated.existing());assertEquals(0,repeated.imported());
        verify(service.documents,times(1)).download(11,22);
        verify(service.settlements,times(1)).createImported(any(),eq(sourceKey));
    }

    @Test void missingPdfIsPendingAndLaterAttachmentIsDiscoveredWithoutLosingDocument() throws Exception {
        var service=service();when(service.documents.purchaseSnapshot(11)).thenReturn(snapshot(false,"hash1"));
        var missing=service.syncIfDue().orElseThrow();assertFalse(missing.complete());assertEquals(1,missing.pending());
        verify(service.state).finishBank(anyString(),anyString(),anyString(),eq(true),eq("TICA_DOCUMENT_REVIEW"));
        verify(service.settlements,never()).createImported(any(),anyString());
        searches(service);when(service.documents.purchaseSnapshot(11)).thenReturn(snapshot(true,"hash1"));
        var later=service.syncIfDue().orElseThrow();assertTrue(later.complete());assertEquals(1,later.imported());
    }

    @Test void mismatchedOdooTotalOrChangedSourceNeverCreatesSettlement() throws Exception {
        var service=service();when(service.pdf.preview(fixture,"267653273.pdf")).thenReturn(preview("96.58"));
        var mismatch=service.syncIfDue().orElseThrow();assertFalse(mismatch.complete());assertEquals(1,mismatch.pending());
        verify(service.settlements,never()).createImported(any(),anyString());
        var changed=service();when(changed.documents.purchaseSnapshot(11)).thenReturn(snapshot(true,"hash1"),snapshot(true,"hash2"));
        assertEquals("TICA_DOCUMENT_REVIEW",changed.syncIfDue().orElseThrow().code());
        verify(changed.tracked).mark(anyString(),eq("SOURCE_CHANGED"),isNull(),eq(false));
        verify(changed.settlements,never()).createImported(any(),anyString());
    }

    @Test void recipientMismatchAndExistingGeneralCostBlockAutomaticDraft() throws Exception {
        var wrong=service();when(wrong.client.search(eq("res.company"),anyList(),anyList(),eq(1),eq("id asc")))
                .thenReturn(json.readTree("[{\"id\":7,\"vat\":\"BE0123456789\"}]"));
        assertEquals("TICA_COMPANY_MISMATCH",wrong.syncIfDue().orElseThrow().code());verifyNoInteractions(wrong.documents);
        verify(wrong.state).finishBank(anyString(),anyString(),anyString(),eq(false),eq("TICA_COMPANY_MISMATCH"));
        var duplicate=service();var cost=new OdooCostImportEntity();cost.companyCostId=99L;
        when(duplicate.costImports.find(anyString(),eq(11L))).thenReturn(cost);
        var report=duplicate.syncIfDue().orElseThrow();assertFalse(report.complete());assertEquals(1,report.pending());
        verify(duplicate.settlements,never()).createImported(any(),anyString());
    }

    @Test void equalGrossWithDifferentOdooTaxAndChangedAcceptedSourcesStayVisibleForReview() throws Exception {
        var service=service();var source=snapshot(true,"hash1");
        var differentTax=new OdooDocumentService.PurchaseSnapshot(source.namespace(),source.companyId(),source.document(),source.partnerId(),
                source.partnerVat(),source.supplierCountry(),new BigDecimal("-77.99"),new BigDecimal("-17.59"),source.totalEur(),source.sourceUpdatedAt(),source.hash());
        when(service.documents.purchaseSnapshot(11)).thenReturn(differentTax);
        assertFalse(service.syncIfDue().orElseThrow().complete());
        verify(service.tracked).mark(anyString(),eq("AMOUNT_OR_REFERENCE_MISMATCH"),isNull(),eq(false));
        verify(service.settlements,never()).createImported(any(),anyString());
        var changed=service();when(changed.settlements.findBySource(anyString())).thenReturn(Optional.of(settlement()));
        when(changed.tracked.observe(any())).thenReturn(new OdooTicaDocumentStore.Observed("original-hash",1L));
        assertEquals(1,changed.syncIfDue().orElseThrow().pending());
        verify(changed.tracked).mark(anyString(),eq("SOURCE_CHANGED"),eq(1L),eq(false));
        verify(changed.settlements,never()).createImported(any(),anyString());
        verify(changed.documents,never()).download(anyLong(),anyLong());
    }

    @Test void xmlOnlyPeppolSourceIsSupportedAndConflictingRecognizedXmlCannotBeOverriddenByPdf() throws Exception {
        var service=service();var original=snapshot(true,"hash1");var old=original.document();
        var xmlDocument=new OdooDocumentService.Document(old.id(),old.name(),old.reference(),old.partner(),old.date(),old.moveType(),old.state(),
                old.currency(),old.total(),null,null,List.of(new OdooDocumentService.Attachment(22,"creditnote.xml","application/xml",20,"XML")));
        var xmlSource=new OdooDocumentService.PurchaseSnapshot(original.namespace(),original.companyId(),xmlDocument,original.partnerId(),original.partnerVat(),
                original.supplierCountry(),original.netEur(),original.vatEur(),original.totalEur(),original.sourceUpdatedAt(),original.hash());
        when(service.documents.purchaseSnapshot(11)).thenReturn(xmlSource);
        when(service.documents.download(11,22)).thenReturn(new OdooDocumentService.Download("creditnote.xml","application/xml",fixture));
        when(service.pdf.previewXml(fixture,"creditnote.xml")).thenReturn(preview("95.58"));
        assertEquals(1,service.syncIfDue().orElseThrow().imported());verify(service.pdf,never()).preview(any(),anyString());
        var conflict=service();var both=new OdooDocumentService.Document(old.id(),old.name(),old.reference(),old.partner(),old.date(),old.moveType(),old.state(),
                old.currency(),old.total(),null,null,List.of(new OdooDocumentService.Attachment(22,"creditnote.xml","application/xml",20,"XML"),
                new OdooDocumentService.Attachment(23,"creditnote.pdf","application/pdf",20,"PDF")));
        var mixed=new OdooDocumentService.PurchaseSnapshot(original.namespace(),original.companyId(),both,original.partnerId(),original.partnerVat(),
                original.supplierCountry(),original.netEur(),original.vatEur(),original.totalEur(),original.sourceUpdatedAt(),original.hash());
        when(conflict.documents.purchaseSnapshot(11)).thenReturn(mixed);
        when(conflict.documents.download(11,22)).thenReturn(new OdooDocumentService.Download("creditnote.xml","application/xml",fixture));
        when(conflict.pdf.previewXml(fixture,"creditnote.xml")).thenReturn(preview("96.58"));
        assertEquals(1,conflict.syncIfDue().orElseThrow().pending());
        verify(conflict.documents,never()).download(11,23);verify(conflict.settlements,never()).createImported(any(),anyString());
    }

    @Test void providerCreditnoteGuardCannotBecomeNegativeCostEvenBeforeSettlementExists() {
        var store=new OdooCostImportStore();var snapshot=snapshot(false,"hash1");
        var plan=store.plan(999,snapshot,null);assertTrue(plan.unavailableReason().contains("TICA-afrekeningen"));
        assertThrows(BusinessRuleException.class,()->store.register(999,snapshot,"hash1",null,null,List.of()));
    }

    @Test void postingAnOdooDraftRevalidatesTheFullSourceWithoutChangingTheSettlement() throws Exception {
        var service=service();var posted=postedSnapshot("posted-hash");
        when(service.documents.purchaseSnapshot(11)).thenReturn(posted);
        when(service.settlements.findBySource(anyString())).thenReturn(Optional.of(settlement()));
        when(service.tracked.observe(any())).thenReturn(new OdooTicaDocumentStore.Observed("draft-hash",1L,"draft"));
        var report=service.syncIfDue().orElseThrow();assertTrue(report.complete());assertEquals(1,report.existing());
        verify(service.documents).download(11,22);
        verify(service.tracked).mark(anyString(),eq("IMPORTED"),eq(1L),eq(true));
        verify(service.settlements,never()).createImported(any(),anyString());
        verify(service.settlements,never()).confirm(anyLong());
    }

    @Test void postingCannotAcceptChangedStatementComponentsEvenWhenNetAndTaxStillMatch() throws Exception {
        var service=service();when(service.documents.purchaseSnapshot(11)).thenReturn(postedSnapshot("changed-posted-hash"));
        when(service.settlements.findBySource(anyString())).thenReturn(Optional.of(settlement()));
        when(service.tracked.observe(any())).thenReturn(new OdooTicaDocumentStore.Observed("draft-hash",1L,"draft"));
        var old=preview("95.58");
        var changed=new TicaSettlementPdfService.Preview(old.sourceDocumentUrl(),old.fileName(),old.reference(),old.location(),
                old.periodFrom(),old.periodTo(),old.documentDate(),old.dueDate(),new BigDecimal("110.00"),new BigDecimal("23.10"),
                new BigDecimal("31.01"),new BigDecimal("6.51"),old.netReceivable(),List.of());
        when(service.pdf.preview(fixture,"267653273.pdf")).thenReturn(changed);
        var report=service.syncIfDue().orElseThrow();assertFalse(report.complete());assertEquals(1,report.pending());
        verify(service.tracked).mark(anyString(),eq("SOURCE_CHANGED"),eq(1L),eq(false));
        verify(service.tracked,never()).mark(anyString(),anyString(),any(),eq(true));
        verify(service.settlements,never()).createImported(any(),anyString());
    }

    @Test void recoveryAfterDraftCreationMustVerifyFullDocumentBeforeAcceptingARevision() throws Exception {
        var service=service();when(service.settlements.findBySource(anyString())).thenReturn(Optional.of(settlement()));
        var old=preview("95.58");
        var changed=new TicaSettlementPdfService.Preview(old.sourceDocumentUrl(),old.fileName(),old.reference(),old.location(),
                old.periodFrom(),old.periodTo(),old.documentDate(),old.dueDate(),new BigDecimal("110.00"),new BigDecimal("23.10"),
                new BigDecimal("31.01"),new BigDecimal("6.51"),old.netReceivable(),List.of());
        when(service.pdf.preview(fixture,"267653273.pdf")).thenReturn(changed);
        assertEquals(1,service.syncIfDue().orElseThrow().pending());
        verify(service.tracked).mark(anyString(),eq("SOURCE_CHANGED"),eq(1L),eq(false));
        verify(service.tracked,never()).mark(anyString(),anyString(),any(),eq(true));
        searches(service);when(service.pdf.preview(fixture,"267653273.pdf")).thenReturn(old);
        assertEquals(1,service.syncIfDue().orElseThrow().existing());
        verify(service.tracked).mark(anyString(),eq("IMPORTED"),eq(1L),eq(true));
        verify(service.settlements,never()).createImported(any(),anyString());
    }
}
