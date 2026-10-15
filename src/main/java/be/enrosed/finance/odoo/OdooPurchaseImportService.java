package be.enrosed.finance.odoo;

import be.enrosed.finance.banking.BankStatementLineEntity;
import be.enrosed.shared.BusinessRuleException;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.math.BigDecimal;
import java.util.*;

/** Explicit preview, bounded provider reads, then one local financial transaction. Never posts an Odoo invoice. */
@ApplicationScoped @Transactional(Transactional.TxType.NOT_SUPPORTED)
public class OdooPurchaseImportService {
    @Inject OdooConfiguration config;
    @Inject OdooDocumentService documents;
    @Inject OdooCostImportStore store;
    @Inject OdooCostImportSlots slots;
    @Inject be.enrosed.finance.banking.BankStatementService bank;
    public record Item(@JsonUnwrapped OdooDocumentService.Document document,Long companyCostId,boolean registered,boolean eligible,String unavailableReason) {}
    public record Page(List<Item> documents,long nextAfterId,boolean hasMore,boolean peppolSupported) {}
    public record Preview(OdooDocumentService.Document document,String snapshotHash,BigDecimal invoiceTotalEur,BigDecimal netEur,BigDecimal vatEur,
                          BigDecimal paymentAmountEur,BigDecimal differenceEur,String supplierCountry,Long companyCostId,
                          List<OdooCostImportStore.Candidate> existingCandidates,boolean eligible,String unavailableReason,String actionMessage) {}
    public record Request(long odooInvoiceId,String snapshotHash,Long existingCompanyCostId,String category) {}

    public Page list(long lineId,long afterId,int limit) {
        config.requireReady();var line=store.line(lineId);OdooCostImportStore.requireBankSource(line,config.sourceNamespace());
        var page=documents.documents("incoming",afterId,limit);List<Item> result=new ArrayList<>();
        for(var document:page.documents()) {
            var registered=store.find(config.sourceNamespace(),document.id());Long costId=registered==null?null:registered.companyCostId;
            String reason=basicReason(line,document);
            result.add(new Item(document,costId,costId!=null,reason==null,reason));
        }
        return new Page(List.copyOf(result),page.nextAfterId(),page.hasMore(),page.peppolSupported());
    }
    public Preview preview(long lineId,long documentId,Long existingCostId) {
        var snapshot=documents.purchaseSnapshot(documentId);var line=store.line(lineId);
        var plan=store.plan(lineId,snapshot,existingCostId);
        return new Preview(snapshot.document(),snapshot.hash(),snapshot.totalEur(),snapshot.netEur(),snapshot.vatEur(),plan.paymentAmountEur(),
                plan.paymentAmountEur()==null?null:line.amountEur.add(plan.paymentAmountEur()),snapshot.supplierCountry(),plan.companyCostId(),
                plan.existingCandidates(),plan.unavailableReason()==null,plan.unavailableReason(),plan.actionMessage());
    }
    public OdooCostImportStore.Result register(long lineId,Request request) {
        config.requireReady();
        if(request==null||request.odooInvoiceId()<=0||request.snapshotHash()==null||!request.snapshotHash().matches("[0-9a-f]{64}"))
            throw new BusinessRuleException("Open en controleer eerst het factuurvoorbeeld");
        bank.requireUnclassified(store.line(lineId));
        var retry=store.completedRetry(config.sourceNamespace(),lineId,request.odooInvoiceId(),request.snapshotHash());
        if(retry!=null) {
            if(request.existingCompanyCostId()!=null&&request.existingCompanyCostId()!=retry.companyCostId())throw new BusinessRuleException("Deze bronfactuur hoort al bij een andere kost");
            return retry;
        }
        var snapshot=documents.purchaseSnapshot(request.odooInvoiceId());
        if(!snapshot.hash().equals(request.snapshotHash()))throw new OdooFailure("PURCHASE_PREVIEW_CHANGED");
        var plan=store.plan(lineId,snapshot,request.existingCompanyCostId());
        if(plan.unavailableReason()!=null)throw new BusinessRuleException(plan.unavailableReason());
        if(plan.companyCostId()==null&&!plan.existingCandidates().isEmpty())throw new BusinessRuleException("Kies de bestaande kost om een dubbele registratie te voorkomen");
        var existing=store.find(snapshot.namespace(),request.odooInvoiceId());List<OdooCostImportStore.Downloaded> files=new ArrayList<>();
        if(existing==null||existing.companyCostId==null) {
            long bytes=0;
            var attachments=snapshot.document().attachments().stream().filter(file->Set.of("PDF","XML").contains(file.kind())).toList();
            if(attachments.size()>12)throw new BusinessRuleException("Deze factuur bevat te veel brondocumenten om automatisch over te nemen");
            for(var attachment:attachments) {
                var download=documents.download(request.odooInvoiceId(),attachment.id());bytes+=download.bytes().length;
                if(bytes>30L*1024*1024)throw new OdooFailure("ATTACHMENT_TOO_LARGE");
                files.add(new OdooCostImportStore.Downloaded(attachment.id(),attachment.kind(),download));
            }
        }
        // Downloads can take time: never register financial facts which changed while the user/file transfer waited.
        var refreshed=documents.purchaseSnapshot(request.odooInvoiceId());
        if(!refreshed.hash().equals(snapshot.hash()))throw new OdooFailure("PURCHASE_PREVIEW_CHANGED");
        try {slots.reserve(snapshot.namespace(),snapshot.companyId(),request.odooInvoiceId());}
        catch(RuntimeException raced) {if(!slots.exists(snapshot.namespace()+":"+request.odooInvoiceId()))throw raced;}
        return store.register(lineId,snapshot,request.snapshotHash(),request.existingCompanyCostId(),request.category(),List.copyOf(files));
    }
    private static String basicReason(BankStatementLineEntity line,OdooDocumentService.Document document) {
        if(!Set.of("draft","posted").contains(document.state()))return "Dit document is geannuleerd of niet beschikbaar";
        if(!"EUR".equals(document.currency()))return "Deze factuur is niet in euro";
        if(document.total()==null||document.total().signum()<=0)return "Deze factuur heeft geen positief documentbedrag";
        boolean credit="in_refund".equals(document.moveType());
        if(!Set.of("in_invoice","in_refund").contains(document.moveType())||line.amountEur.signum()!=(credit?1:-1))return "De richting past niet bij deze banktransactie";
        return null;
    }
}
