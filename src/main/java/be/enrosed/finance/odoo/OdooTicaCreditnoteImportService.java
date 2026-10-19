package be.enrosed.finance.odoo;

import be.enrosed.tica.TicaSettlementPdfService;
import be.enrosed.tica.TicaSettlementService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.Instant;
import java.util.Map;
import java.util.HashSet;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static be.enrosed.finance.odoo.OdooBankMapper.*;

/** Reads received TICA statements into ERP drafts. It never posts, sends, reconciles or pays an Odoo document. */
@ApplicationScoped
@Transactional(Transactional.TxType.NOT_SUPPORTED)
public class OdooTicaCreditnoteImportService {
    static final String PROVIDER_VAT="BE0428038927";
    static final String RECIPIENT_VAT="BE1034273386";
    private static final BigDecimal TOLERANCE=new BigDecimal("0.02");
    private static final int PAGE_SIZE=100,MAX_DOCUMENTS=5000;
    @Inject OdooConfiguration config;
    @Inject OdooClient client;
    @Inject OdooDocumentService documents;
    @Inject OdooSyncStateStore state;
    @Inject OdooCostImportStore costImports;
    @Inject TicaSettlementPdfService pdf;
    @Inject TicaSettlementService settlements;
    @Inject ObjectMapper json;
    @Inject OdooTicaDocumentStore tracked;
    @Inject OdooService bankService;

    public record Report(int scanned,int imported,int existing,int pending,boolean complete,String code) {}
    public record Status(boolean enabled,int intervalHours,int retryMinutes,Instant lastAttemptAt,Instant lastSuccessAt,
                         Instant nextRunAt,String lastCode,Report lastReport,long pendingCount,Map<String,Long> pendingReasons,
                         List<OdooTicaDocumentStore.DocumentStatus> pendingDocuments) {}

    public Status status() {
        boolean ready=bankService.automaticBankSyncReady();
        if(config.problem()!=null)return new Status(false,12,30,null,null,null,config.problem(),null,0,Map.of(),List.of());
        String source=config.sourceNamespace(),key=scheduleKey(source);
        var schedule=state.bankSchedule(key);var saved=state.snapshot(key);Report report=null;
        if(saved!=null&&saved.lastSyncJson()!=null)try {report=json.readValue(saved.lastSyncJson(),Report.class);}catch(Exception ignored) { }
        var reasons=tracked.pendingReasons(source);
        return new Status(ready,12,30,schedule.lastAttemptAt(),schedule.lastSuccessAt(),
                !ready?null:schedule.nextRunAt()==null?Instant.now():schedule.nextRunAt(),schedule.lastCode(),report,
                reasons.values().stream().mapToLong(Long::longValue).sum(),reasons,tracked.pending(source));
    }

    public Optional<Report> syncIfDue() {
        if(config.problem()!=null)return Optional.empty();
        String source=config.sourceNamespace(),key=scheduleKey(source),owner=UUID.randomUUID().toString();
        try { if(!state.acquireBank(key,owner,true))return Optional.empty(); }
        catch(OdooFailure failure) { throw failure; }
        catch(RuntimeException raced) { throw new OdooFailure("BUSY"); }
        int scanned=0,imported=0,existing=0,pending=0;boolean complete=false,scanCompleted=false;String code="READY";
        try {
            requireRecipientCompany();
            List<Long> partners=providerPartners();
            var seen=new HashSet<Long>();
            if(!partners.isEmpty()) {
                long cursor=0;
                while(true) {
                    state.renew(key,owner);
                    List<Object> domain=new ArrayList<>(List.of(List.of("company_id","=",config.companyId),
                            List.of("partner_id","in",partners),List.of("move_type","=","in_refund"),
                            List.of("state","in",List.of("draft","posted")),List.of("id",">",cursor)));
                    JsonNode rows=client.search("account.move",domain,List.of("id","company_id","partner_id","move_type","state"),PAGE_SIZE,"id asc");
                    if(rows.isEmpty())break;
                    for(JsonNode row:rows) {
                        state.renew(key,owner);
                        long id=positiveId(row.path("id"));
                        if(id<=cursor||relationId(row.path("company_id"))!=config.companyId
                                ||!partners.contains(relationId(row.path("partner_id")))||!"in_refund".equals(text(row,"move_type"))
                                ||!Set.of("draft","posted").contains(text(row,"state")))throw new OdooFailure("INVALID_RESPONSE");
                        cursor=id;
                        if(++scanned>MAX_DOCUMENTS)throw new OdooFailure("TICA_DOCUMENT_LIMIT");
                        seen.add(id);
                        String sourceKey=sourceKey(source,id);
                        try {
                            var snapshot=documents.purchaseSnapshot(id);
                            if(!isTicaCreditnote(snapshot))throw new OdooFailure("INVALID_RESPONSE");
                            var observed=tracked.observe(snapshot);
                            var existingSettlement=settlements.findBySource(sourceKey);
                            if(existingSettlement.isPresent()) {
                                var stored=existingSettlement.get();
                                if(snapshot.hash().equals(observed.acceptedHash())
                                        ||revalidateSourceRevision(snapshot,stored,observed)) {
                                    tracked.mark(sourceKey,"IMPORTED",stored.id(),true);existing++;
                                } else {tracked.mark(sourceKey,"SOURCE_CHANGED",stored.id(),false);pending++;}
                                continue;
                            }
                            var priorCost=costImports.find(source,id);
                            if(priorCost!=null&&priorCost.companyCostId!=null) {
                                tracked.mark(sourceKey,"ALREADY_COST",null,false);pending++;continue;
                            }
                            String result=importDocument(snapshot,sourceKey);
                            if("IMPORTED".equals(result))imported++;
                            else {tracked.mark(sourceKey,result,null,false);pending++;}
                        } catch(be.enrosed.shared.BusinessRuleException rejected) {
                            tracked.mark(sourceKey,"SOURCE_CHANGED",null,false);pending++;
                        }
                    }
                }
            }
            // A previously imported source can disappear from the eligible query after cancellation,
            // company/partner changes, or deletion. Keep that change visible without touching finances.
            for(long known:tracked.knownMoves(source))if(!seen.contains(known)) {
                tracked.mark(sourceKey(source,known),"SOURCE_UNAVAILABLE",null,false);pending++;
            }
            scanCompleted=true;complete=pending==0;
            if(!complete)code="TICA_DOCUMENT_REVIEW";
        } catch(OdooFailure failure) {code=failure.code;}
        catch(RuntimeException unavailable) {code="UNAVAILABLE";}
        Report report=new Report(scanned,imported,existing,pending,complete,code);
        // Review items do not justify re-downloading the same history every 30 minutes.
        // A completed scan remains on the 12-hour cycle; only technical failures retry sooner.
        try {state.finishBank(key,owner,json.writeValueAsString(report),scanCompleted,code);}
        catch(OdooFailure failure) {throw failure;}
        catch(Exception unavailable) {throw new OdooFailure("UNAVAILABLE");}
        return Optional.of(report);
    }

    private String importDocument(OdooDocumentService.PurchaseSnapshot snapshot,String sourceKey) {
        long id=snapshot.document().id();
        var parsed=readDocument(snapshot);
        if(parsed.failure()!=null)return parsed.failure();
        var selected=parsed.document();
        var refreshed=documents.purchaseSnapshot(id);
        if(!snapshot.hash().equals(refreshed.hash())) {
            tracked.observe(refreshed);return "SOURCE_CHANGED";
        }
        var request=new TicaSettlementService.Request(selected.reference(),selected.location(),selected.documentDate(),selected.dueDate(),
                selected.periodFrom(),selected.periodTo(),selected.salesExcl(),selected.salesVat(),selected.commissionExcl(),
                selected.commissionVat(),selected.netReceivable(),"Ontvangen via Odoo. Bronstatus: "+snapshot.document().state()
                +". Odoo-document: "+id+". Controleer de afrekening tegenover de geïmporteerde TICA-verkopen.",selected.sourceDocumentUrl());
        var settlement=settlements.createImported(request,sourceKey);
        tracked.mark(sourceKey,"IMPORTED",settlement.id(),true);
        return "IMPORTED";
    }

    private record ParsedDocument(TicaSettlementPdfService.Preview document,String failure) {}

    private ParsedDocument readDocument(OdooDocumentService.PurchaseSnapshot snapshot) {
        long id=snapshot.document().id();
        if(!Set.of("draft","posted").contains(snapshot.document().state()))throw new OdooFailure("INVALID_RESPONSE");
        var attachments=snapshot.document().attachments().stream().filter(file->Set.of("PDF","XML").contains(file.kind())).toList();
        if(attachments.isEmpty())return new ParsedDocument(null,"MISSING_DOCUMENT");
        if(attachments.size()>10)return new ParsedDocument(null,"AMBIGUOUS_DOCUMENT");
        TicaSettlementPdfService.Preview selected=null;
        boolean recognized=false;
        // A verified received UBL is authoritative. Retain PDF as the fallback when UBL is unsupported.
        for(String kind:List.of("XML","PDF")) {
            boolean recognizedKind=false;
            for(var attachment:attachments) {
                if(!kind.equals(attachment.kind()))continue;
                var file=documents.download(id,attachment.id());
                TicaSettlementPdfService.Preview parsed;
                try {parsed="XML".equals(kind)?pdf.previewXml(file.bytes(),file.name()):pdf.preview(file.bytes(),file.name());}
                catch(be.enrosed.shared.BusinessRuleException unsupported) {continue;}
                recognized=true;recognizedKind=true;
                if(!matches(snapshot,parsed))continue;
                if(selected!=null)return new ParsedDocument(null,"AMBIGUOUS_DOCUMENT");
                selected=parsed;
            }
            if(selected!=null)break;
            if("XML".equals(kind)&&recognizedKind)return new ParsedDocument(null,"AMOUNT_OR_REFERENCE_MISMATCH");
        }
        if(selected==null)return new ParsedDocument(null,recognized?"AMOUNT_OR_REFERENCE_MISMATCH":"UNSUPPORTED_DOCUMENT");
        return new ParsedDocument(selected,null);
    }

    private boolean revalidateSourceRevision(OdooDocumentService.PurchaseSnapshot snapshot,TicaSettlementService.Dto stored,
                                                OdooTicaDocumentStore.Observed observed) {
        // Posting a received draft changes Odoo's state/write timestamp. Accept that one lifecycle transition
        // only after re-reading the actual document and comparing every immutable settlement field.
        boolean unaccepted=observed.acceptedHash()==null;
        boolean posted="draft".equals(observed.acceptedSourceState())&&"posted".equals(snapshot.document().state());
        // The same full check recovers a crash after draft creation but before its source was accepted.
        if((!unaccepted&&!posted)||!"DRAFT".equals(stored.status())||!matchesSettlement(snapshot,stored))return false;
        var parsed=readDocument(snapshot);
        if(parsed.failure()!=null||!sameStatement(parsed.document(),stored))return false;
        var refreshed=documents.purchaseSnapshot(snapshot.document().id());
        if(!snapshot.hash().equals(refreshed.hash())) {
            tracked.observe(refreshed);return false;
        }
        return true;
    }

    private static boolean sameStatement(TicaSettlementPdfService.Preview parsed,TicaSettlementService.Dto stored) {
        return normalized(parsed.reference()).equals(normalized(stored.reference()))
                &&java.util.Objects.equals(parsed.location(),stored.location())
                &&java.util.Objects.equals(parsed.documentDate(),stored.documentDate())
                &&java.util.Objects.equals(parsed.dueDate(),stored.dueDate())
                &&java.util.Objects.equals(parsed.periodFrom(),stored.periodFrom())
                &&java.util.Objects.equals(parsed.periodTo(),stored.periodTo())
                &&parsed.salesExcl().compareTo(stored.salesExcl())==0&&parsed.salesVat().compareTo(stored.salesVat())==0
                &&parsed.commissionExcl().compareTo(stored.commissionExcl())==0&&parsed.commissionVat().compareTo(stored.commissionVat())==0
                &&parsed.netReceivable().compareTo(stored.netReceivable())==0;
    }

    static boolean matches(OdooDocumentService.PurchaseSnapshot snapshot,TicaSettlementPdfService.Preview parsed) {
        String reference=normalized(parsed.reference());
        return !reference.isBlank()&&(reference.equals(normalized(snapshot.document().reference()))
                ||reference.equals(normalized(snapshot.document().name())))
                && LocalDate.parse(snapshot.document().date()).equals(parsed.documentDate())
                && snapshot.totalEur().abs().subtract(parsed.netReceivable()).abs().compareTo(TOLERANCE)<=0
                && snapshot.netEur().abs().subtract(parsed.salesExcl().subtract(parsed.commissionExcl())).abs().compareTo(TOLERANCE)<=0
                && snapshot.vatEur().abs().subtract(parsed.salesVat().subtract(parsed.commissionVat())).abs().compareTo(TOLERANCE)<=0;
    }

    private static boolean matchesSettlement(OdooDocumentService.PurchaseSnapshot snapshot,TicaSettlementService.Dto existing) {
        return normalized(snapshot.document().reference()).equals(normalized(existing.reference()))
                && LocalDate.parse(snapshot.document().date()).equals(existing.documentDate())
                && snapshot.totalEur().abs().subtract(existing.netReceivable()).abs().compareTo(TOLERANCE)<=0
                && snapshot.netEur().abs().subtract(existing.salesExcl().subtract(existing.commissionExcl())).abs().compareTo(TOLERANCE)<=0
                && snapshot.vatEur().abs().subtract(existing.salesVat().subtract(existing.commissionVat())).abs().compareTo(TOLERANCE)<=0;
    }

    private void requireRecipientCompany() {
        JsonNode rows=client.search("res.company",List.of(List.of("id","=",config.companyId)),List.of("id","vat"),1,"id asc");
        if(rows.size()!=1||positiveId(rows.get(0).path("id"))!=config.companyId
                ||!RECIPIENT_VAT.equals(normalized(text(rows.get(0),"vat"))))throw new OdooFailure("TICA_COMPANY_MISMATCH");
    }

    private List<Long> providerPartners() {
        List<Long> result=new ArrayList<>();long cursor=0;int scanned=0;
        while(true) {
            // Odoo normalizes VAT values; allow common printed punctuation but verify the full number ourselves.
            JsonNode rows=client.search("res.partner",List.of(List.of("vat","ilike","0428"),List.of("id",">",cursor)),
                    List.of("id","vat"),PAGE_SIZE,"id asc");
            if(rows.isEmpty())return List.copyOf(result);
            for(JsonNode row:rows) {
                long id=positiveId(row.path("id"));if(id<=cursor||++scanned>1000)throw new OdooFailure("INVALID_RESPONSE");cursor=id;
                if(PROVIDER_VAT.equals(normalized(text(row,"vat"))))result.add(id);
            }
        }
    }

    static boolean isTicaCreditnote(OdooDocumentService.PurchaseSnapshot snapshot) {
        return snapshot!=null&&snapshot.document()!=null&&"in_refund".equals(snapshot.document().moveType())
                &&PROVIDER_VAT.equals(normalized(snapshot.partnerVat()));
    }
    static String sourceKey(String source,long id) {return "odoo:"+source+":"+id;}
    static String normalized(String value) {return value==null?"":value.toUpperCase(java.util.Locale.ROOT).replaceAll("[^A-Z0-9]","");}
    static String scheduleKey(String source) {
        try {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest((source+":tica-creditnotes").getBytes(StandardCharsets.UTF_8)));}
        catch(Exception impossible) {throw new IllegalStateException(impossible);}
    }
}
