package be.enrosed.finance.odoo;

import be.enrosed.finance.adapter.out.persistence.*;
import be.enrosed.finance.application.CompanyCostService;
import be.enrosed.finance.banking.*;
import be.enrosed.media.*;
import be.enrosed.shared.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.*;
import jakarta.transaction.Transactional;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** All financial writes, source ownership and document links commit together. Provider reads happen before this service. */
@ApplicationScoped
public class OdooCostImportStore {
    @Inject EntityManager em;
    @Inject CompanyCostService costs;
    @Inject BankStatementService bank;
    @Inject MediaService media;
    @Inject ObjectMapper json;

    public record Candidate(long id,String description,String party,LocalDate date,String reference,BigDecimal totalEur,
                            BigDecimal openEur,Long bankStatementLineId) {}
    public record LocalPlan(Long companyCostId,List<Candidate> existingCandidates,BigDecimal paymentAmountEur,String unavailableReason,String actionMessage) {}
    public record SavedDocument(long odooAttachmentId,long mediaAssetId,String name,String kind) {}
    public record Downloaded(long attachmentId,String kind,OdooDocumentService.Download file) {}
    public record Source(OdooDocumentService.PurchaseSnapshot snapshot,List<SavedDocument> documents,Instant registeredAt) {}
    public record Result(BankStatementLineEntity line,long companyCostId,boolean alreadyRegistered,BigDecimal invoiceTotalEur,
                         BigDecimal paymentAmountEur,BigDecimal differenceEur,int documentsSaved) {}

    public boolean isImported(long costId) {
        return em.createQuery("select count(i) from OdooCostImportEntity i where i.companyCostId=:id",Long.class).setParameter("id",costId).getSingleResult()>0;
    }
    public OdooCostImportEntity find(String namespace,long moveId) {return em.find(OdooCostImportEntity.class,namespace+":"+moveId);}
    public BankStatementLineEntity line(long id) {
        var line=em.find(BankStatementLineEntity.class,id);if(line==null)throw new NotFoundException("Banktransactie",id);return line;
    }
    public Source source(long costId) {
        var row=em.createQuery("from OdooCostImportEntity i where i.companyCostId=:id",OdooCostImportEntity.class).setParameter("id",costId).getResultStream().findFirst().orElse(null);
        if(row==null)return null;
        return new Source(snapshot(row),savedDocuments(row),row.registeredAt);
    }
    public static void requireBankSource(BankStatementLineEntity line,String namespace) {
        if("ODOO".equals(line.source)&&(line.sourceId==null||!line.sourceId.startsWith(namespace+":")))
            throw new BusinessRuleException("Deze banktransactie hoort bij een andere Odoo-administratie");
    }
    public LocalPlan plan(long lineId,OdooDocumentService.PurchaseSnapshot snapshot,Long selectedCostId) {
        var line=line(lineId);
        if(line.classification!=null)return new LocalPlan(null,List.of(),null,"Deze banktransactie is geboekt als "
                +BankStatementService.classificationLabel(line.classification)+"; maak dat eerst ongedaan",null);
        requireBankSource(line,snapshot.namespace());
        if(line.amountEur.signum()!=-snapshot.totalEur().signum())return new LocalPlan(null,List.of(),null,"De richting van deze banktransactie past niet bij de factuur of creditnota",null);
        var registration=find(snapshot.namespace(),snapshot.document().id());
        Long costId=registration==null?null:registration.companyCostId;
        var candidates=costId==null?candidates(snapshot,lineId):List.<Candidate>of();
        if(costId==null&&selectedCostId!=null) {
            if(candidates.stream().noneMatch(candidate->candidate.id()==selectedCostId))throw new BusinessRuleException("Kies een bestaande kost die bij deze bronfactuur hoort");
            costId=selectedCostId;
        }
        if(costId==null) {
            if(line.salesPaymentId!=null)return new LocalPlan(null,candidates,null,"Deze banktransactie is al aan een verkoopfactuur gekoppeld",null);
            if(hasExpenseLink(lineId))return new LocalPlan(null,candidates,null,candidates.isEmpty()?"Deze banktransactie is al gekoppeld aan een andere betaling":null,"Kies de bestaande gekoppelde kost; alleen brongegevens en documenten worden toegevoegd");
            if(line.amountEur.abs().subtract(snapshot.totalEur().abs()).compareTo(BankAmountMatch.MAX_DIFFERENCE)>0)
                return new LocalPlan(null,candidates,null,"Het bankbedrag is groter dan het factuurbedrag",null);
            var amount=BankAmountMatch.matches(line.amountEur,snapshot.totalEur().negate())?snapshot.totalEur():line.amountEur.negate();
            return new LocalPlan(null,candidates,amount,null,candidates.isEmpty()?"Ontvangen document als nieuwe kost registreren":"Kies de bestaande kost; er wordt geen tweede kost aangemaakt");
        }
        var cost=em.find(CompanyCostEntity.class,costId);
        if(cost==null)return new LocalPlan(costId,candidates,null,"Deze bronfactuur is eerder geregistreerd, maar de lokale kost is verwijderd",null);
        if(!BankAmountMatch.matches(cost.toDomain().totalInclEur(),snapshot.totalEur()))
            return new LocalPlan(costId,candidates,null,"Het totaal van de bestaande kost wijkt meer dan € 0,02 af van de bronfactuur; corrigeer eerst de bestaande registratie",null);
        BigDecimal localNet=cost.toDomain().amountExclEur(),localVat=cost.toDomain().totalInclEur().subtract(localNet);
        if(localNet.subtract(snapshot.netEur()).abs().compareTo(BankAmountMatch.MAX_DIFFERENCE)>0
                ||localVat.subtract(snapshot.vatEur()).abs().compareTo(BankAmountMatch.MAX_DIFFERENCE)>0)
            return new LocalPlan(costId,candidates,null,"De netto- of btw-bedragen van de bestaande kost wijken af van de bronfactuur; controleer eerst de bestaande registratie",null);
        if(line.salesPaymentId!=null)return new LocalPlan(costId,candidates,null,"Deze banktransactie is aan een verkoopfactuur gekoppeld",null);
        var rows=payments(costId);
        var linked=rows.stream().filter(payment->Objects.equals(payment.bankStatementLineId,lineId)).toList();
        if(!linked.isEmpty()) {
            if(linked.size()!=1||hasOtherExpenseLink(lineId,costId,linked.getFirst().id))return new LocalPlan(costId,candidates,null,"Deze banktransactie bevat meerdere betalingen; controleer de bestaande koppeling",null);
            if(!BankAmountMatch.matches(line.amountEur,linked.getFirst().amountEur.negate()))
                return new LocalPlan(costId,candidates,null,"De bestaande betaling wijkt meer dan € 0,02 af van deze banktransactie; controleer eerst de koppeling",null);
            return new LocalPlan(costId,candidates,linked.getFirst().amountEur,null,"Bronfactuur en documenten aan bestaande kost toevoegen; betaling behouden");
        }
        if(hasExpenseLink(lineId))return new LocalPlan(costId,candidates,null,"Deze banktransactie is aan een andere kost of inkoopbetaling gekoppeld",null);
        var matching=rows.stream().filter(payment->BankAmountMatch.matches(line.amountEur,payment.amountEur.negate())).toList();
        if(matching.size()>1)return new LocalPlan(costId,candidates,null,"Meerdere bestaande betalingen passen; koppel eerst de juiste betaling via de kost",null);
        if(matching.size()==1) {
            var payment=matching.getFirst();
            if(payment.bankStatementLineId!=null) {
                var old=line(payment.bankStatementLineId);
                if(old.source!=null)return new LocalPlan(costId,candidates,null,"De bestaande betaling is al aan een andere opgehaalde banktransactie gekoppeld",null);
                if(hasOtherExpenseLink(old.id,costId,payment.id))return new LocalPlan(costId,candidates,null,"De historische bankbeweging bevat meerdere betalingen; maak die koppeling eerst los",null);
                return new LocalPlan(costId,candidates,payment.amountEur,null,"Bestaande handmatige bankkoppeling vervangen; betaling en historische beweging behouden");
            }
            return new LocalPlan(costId,candidates,payment.amountEur,null,"Bestaande betaling koppelen; geen tweede betaling aanmaken");
        }
        var view=costs.view(cost.toDomain());
        if(view.openEur().signum()==0)return new LocalPlan(costId,candidates,null,"Deze kost is al volledig betaald; controleer de bestaande betalingen",null);
        if(line.amountEur.abs().subtract(view.openEur().abs()).compareTo(BankAmountMatch.MAX_DIFFERENCE)>0)
            return new LocalPlan(costId,candidates,null,"Het bankbedrag is groter dan het openstaande bedrag van de bestaande kost",null);
        var amount=BankAmountMatch.matches(line.amountEur,view.openEur().negate())?view.openEur():line.amountEur.negate();
        return new LocalPlan(costId,candidates,amount,null,"Betaling toevoegen aan de bestaande kost");
    }

    public List<Candidate> candidates(OdooDocumentService.PurchaseSnapshot snapshot,long lineId) {
        String reference=normalized(snapshot.document().reference()),name=normalized(snapshot.document().name()),party=partyKey(snapshot.document().partner());
        LocalDate date=LocalDate.parse(snapshot.document().date());
        var bankLine=line(lineId);LocalDate bankDate=bankLine.bookedAt.atZone(ZoneId.of(bankLine.timeZone)).toLocalDate();
        List<Candidate> result=new ArrayList<>();
        for(var cost:em.createQuery("from CompanyCostEntity order by date desc,id desc",CompanyCostEntity.class).getResultList()) {
            if(cost.toDomain().totalInclEur().signum()!=snapshot.totalEur().signum())continue;
            String localRef=normalized(cost.reference),localParty=partyKey(cost.party);
            boolean sameReference=localRef.length()>=4&&(localRef.equals(reference)||localRef.equals(name));
            boolean sameParty=!party.isBlank()&&!localParty.isBlank()&&(localParty.equals(party)
                    ||Math.min(localParty.length(),party.length())>=5&&(localParty.startsWith(party)||party.startsWith(localParty)));
            boolean sameAmount=cost.toDomain().totalInclEur().subtract(snapshot.totalEur()).abs().compareTo(BankAmountMatch.MAX_DIFFERENCE)<=0;
            boolean closeDate=cost.date!=null&&Math.abs(ChronoUnit.DAYS.between(cost.date,date))<=7;
            boolean linkedThisBank=payments(cost.id).stream().anyMatch(payment->Objects.equals(payment.bankStatementLineId,lineId));
            boolean closePayment=payments(cost.id).stream().anyMatch(payment->Objects.equals(payment.bankStatementLineId,lineId)
                    ||payment.paidOn!=null&&Math.abs(ChronoUnit.DAYS.between(payment.paidOn,bankDate))<=7&&BankAmountMatch.matches(bankLine.amountEur,payment.amountEur.negate()));
            if(sameParty&&(sameReference||sameAmount&&(closeDate||closePayment))||sameReference&&sameAmount&&linkedThisBank) {
                var totals=costs.view(cost.toDomain());result.add(new Candidate(cost.id,cost.description,cost.party,cost.date,cost.reference,totals.cashTotalEur(),totals.openEur(),cost.bankStatementLineId));
            }
        }
        return List.copyOf(result);
    }

    /** Returns a completed same-bank retry without another provider download or payment. */
    public Result completedRetry(String namespace,long lineId,long moveId,String requestedHash) {
        var row=find(namespace,moveId);
        if(row==null||row.companyCostId==null||!Objects.equals(row.snapshotHash,requestedHash))return null;
        var source=snapshot(row);var plan=plan(lineId,source,row.companyCostId);
        if(plan.unavailableReason()!=null)return null;
        boolean linked=payments(row.companyCostId).stream().anyMatch(payment->Objects.equals(payment.bankStatementLineId,lineId));
        if(!linked)return null;
        return result(lineId,row,true,plan.paymentAmountEur());
    }

    @Transactional
    public Result register(long lineId,OdooDocumentService.PurchaseSnapshot snapshot,String requestedHash,Long selectedCostId,String category,List<Downloaded> files) {
        if(!Objects.equals(snapshot.hash(),requestedHash))throw new OdooFailure("PURCHASE_PREVIEW_CHANGED");
        var binding=em.find(OdooCostImportEntity.class,snapshot.namespace()+":"+snapshot.document().id(),LockModeType.PESSIMISTIC_WRITE);
        if(binding==null||binding.companyId!=snapshot.companyId())throw new OdooFailure("INVALID_RESPONSE");
        boolean registered=binding.companyCostId!=null;
        if(registered&&selectedCostId!=null&&!binding.companyCostId.equals(selectedCostId))throw new BusinessRuleException("Deze bronfactuur hoort al bij een andere kost");
        if(registered&&!binding.snapshotHash.equals(snapshot.hash()))throw new OdooFailure("PURCHASE_PREVIEW_CHANGED");
        Long target=binding.companyCostId==null?selectedCostId:binding.companyCostId;
        // Match the bank -> cost lock order used by ordinary linking, including both sides of a historical relink.
        SortedSet<Long> bankIds=new TreeSet<>();bankIds.add(lineId);
        if(target!=null)payments(target).stream().map(payment->payment.bankStatementLineId).filter(Objects::nonNull).forEach(bankIds::add);
        for(long id:bankIds)em.find(BankStatementLineEntity.class,id,LockModeType.PESSIMISTIC_WRITE);
        var line=line(lineId);bank.requireUnclassified(line);requireBankSource(line,snapshot.namespace());
        if(target!=null) {
            em.find(CompanyCostEntity.class,target,LockModeType.PESSIMISTIC_WRITE);
            if(payments(target).stream().anyMatch(payment->payment.bankStatementLineId!=null&&!bankIds.contains(payment.bankStatementLineId)))
                throw new BusinessRuleException("Een betaling is intussen gekoppeld; vernieuw het factuurvoorbeeld");
        }
        LocalPlan plan=plan(lineId,snapshot,target);
        if(plan.unavailableReason()!=null)throw new BusinessRuleException(plan.unavailableReason());
        if(plan.companyCostId()==null&&!plan.existingCandidates().isEmpty())throw new BusinessRuleException("Deze factuur lijkt al als kost geregistreerd; kies de bestaande kost");
        if(target!=null) {
            var other=em.createQuery("from OdooCostImportEntity i where i.companyCostId=:cost and i.id<>:id",OdooCostImportEntity.class).setParameter("cost",target).setParameter("id",binding.id).getResultList();
            if(!other.isEmpty())throw new BusinessRuleException("Deze kost hoort al bij een andere ontvangen Odoo-factuur");
        }
        if(plan.companyCostId()==null)target=costs.createImported(snapshot,category).id();else target=plan.companyCostId();
        long costId=target;
        List<CostPaymentEntity> payments=payments(costId);
        var already=payments.stream().filter(payment->Objects.equals(payment.bankStatementLineId,lineId)).findFirst().orElse(null);
        if(already==null) {
            var matches=payments.stream().filter(payment->BankAmountMatch.matches(line.amountEur,payment.amountEur.negate())).toList();
            if(matches.size()==1) {
                var payment=matches.getFirst();
                if(payment.bankStatementLineId!=null) {
                    bank.requireExclusiveCostLink(payment.bankStatementLineId,costId,payment.id);
                    bank.unlinkExisting(payment.bankStatementLineId);
                }
                bank.linkExisting(lineId,new BankStatementService.OutgoingLinks(null,costId,payment.id));
            } else bank.recordCostPayment(lineId,new BankStatementService.CostAllocation(costId));
        }
        if(!registered) {
            binding.companyCostId=costId;binding.snapshotHash=snapshot.hash();binding.snapshotJson=encode(snapshot);binding.registeredAt=Instant.now();
            List<SavedDocument> saved=new ArrayList<>();
            for(var file:files) {
                // XML stays a downloadable binary document; never make untrusted XML browser-executable.
                var validated=file.kind().equals("PDF")?MediaUploadPolicy.validate(file.file().name(),"application/pdf",new ByteArrayInputStream(file.file().bytes()))
                        :new MediaUploadPolicy.ValidatedFile(safeFilename(file.file().name()),"application/octet-stream",MediaKind.DOCUMENT,file.file().bytes());
                if(file.kind().equals("PDF")&&!validated.contentType().equals("application/pdf"))throw new BusinessRuleException("De ontvangen PDF heeft een ongeldig bestandsformaat");
                var asset=media.upload(file.file().name(),validated).asset();
                media.link(asset.id(),new MediaDtos.LinkRequest(MediaTargetType.COMPANY_COST,costId,MediaRole.INVOICE));
                saved.add(new SavedDocument(file.attachmentId(),asset.id(),file.file().name(),file.kind()));
            }
            binding.documentsJson=encode(saved);em.flush();
        }
        var payment=payments(costId).stream().filter(value->Objects.equals(value.bankStatementLineId,lineId)).findFirst().orElseThrow();
        return result(lineId,binding,registered,payment.amountEur);
    }
    private Result result(long lineId,OdooCostImportEntity binding,boolean registered,BigDecimal amount) {
        var line=bank.list().stream().filter(value->value.id==lineId).findFirst().orElseThrow();
        return new Result(line,binding.companyCostId,registered,snapshot(binding).totalEur(),amount,line.amountEur.add(amount),savedDocuments(binding).size());
    }
    private List<CostPaymentEntity> payments(long costId) {return em.createQuery("from CostPaymentEntity p where p.companyCostId=:id order by p.id",CostPaymentEntity.class).setParameter("id",costId).getResultList();}
    private boolean hasExpenseLink(long id) {return em.createQuery("select count(p) from CostPaymentEntity p where p.bankStatementLineId=:id",Long.class).setParameter("id",id).getSingleResult()>0
            ||em.createQuery("select count(p) from PurchasePaymentEntity p where p.bankStatementLineId=:id",Long.class).setParameter("id",id).getSingleResult()>0
            ||em.createQuery("select count(c) from CompanyCostEntity c where c.bankStatementLineId=:id",Long.class).setParameter("id",id).getSingleResult()>0;}
    private boolean hasOtherExpenseLink(long id,long costId,long paymentId) {
        return em.createQuery("select count(p) from PurchasePaymentEntity p where p.bankStatementLineId=:id",Long.class).setParameter("id",id).getSingleResult()>0
                ||em.createQuery("select count(p) from CostPaymentEntity p where p.bankStatementLineId=:id and p.id<>:payment",Long.class).setParameter("id",id).setParameter("payment",paymentId).getSingleResult()>0
                ||em.createQuery("select count(c) from CompanyCostEntity c where c.bankStatementLineId=:id and c.id<>:cost",Long.class).setParameter("id",id).setParameter("cost",costId).getSingleResult()>0;
    }
    private OdooDocumentService.PurchaseSnapshot snapshot(OdooCostImportEntity row) {
        try {return json.readValue(row.snapshotJson,OdooDocumentService.PurchaseSnapshot.class);}catch(Exception invalid) {throw new BusinessRuleException("De bewaarde bronfactuur kan niet worden gelezen");}
    }
    private List<SavedDocument> savedDocuments(OdooCostImportEntity row) {
        if(row.documentsJson==null)return List.of();
        try {return List.of(json.readValue(row.documentsJson,SavedDocument[].class));}catch(Exception invalid) {throw new BusinessRuleException("De bewaarde brondocumenten kunnen niet worden gelezen");}
    }
    private String encode(Object value) {try {return json.writeValueAsString(value);}catch(Exception failure) {throw new IllegalStateException(failure);}}
    // Strip only common terminal legal forms, never guess unrelated trading-name aliases.
    static String partyKey(String value) {
        if(value==null)return "";
        String words=value.toUpperCase(Locale.ROOT).replace('.', ' ').trim();
        words=words.replaceAll("(?:(?:\\s+(?:B\\s*V|N\\s*V|S\\s*A|B\\s*V\\s*B\\s*A|GMBH|LTD|LIMITED|SRL|SARL)))+$", "");
        return normalized(words);
    }
    private static String safeFilename(String value) {String safe=value.replaceAll("[^A-Za-z0-9._ -]","_");return safe.isBlank()?"odoo-document.xml":safe;}
    static String normalized(String value) {return value==null?"":value.toUpperCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]","");}
}
