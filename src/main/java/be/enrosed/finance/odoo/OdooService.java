package be.enrosed.finance.odoo;

import be.enrosed.finance.banking.BankStatementService;
import com.fasterxml.jackson.databind.*;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.time.Instant;
import java.util.*;
import static be.enrosed.finance.odoo.OdooBankMapper.*;

@ApplicationScoped
@Transactional(Transactional.TxType.NOT_SUPPORTED)
public class OdooService {
    private static final int PAGE_SIZE=200;
    static final List<String> BANK_FIELDS=List.of("id","company_id","journal_id","currency_id","amount","date",
            "payment_ref","partner_name","partner_id","write_date","is_reconciled");
    @Inject OdooClient client;
    @Inject OdooConfiguration config;
    @Inject OdooOutboundConfiguration outbound;
    @Inject OdooSyncStateStore state;
    @Inject BankStatementService bank;
    @Inject ObjectMapper json;

    public record SyncReport(int scanned,int created,int updated,int unchanged,int conflicts,int skippedNonEur,
                             boolean complete,String message,String code) {}
    public record Status(boolean enabled,boolean configured,String code,String message,List<String> missingConfiguration,
                         boolean outboundEnabled,Instant lastCheckAt,Instant lastSyncAt,SyncReport lastSync,
                         String companyName,Integer journalCount) {}
    record Journal(long id,String account,long currencyId) {}
    record Scope(String companyName,long eurCurrencyId,Map<Long,Journal> journals) {}

    public Status status() { return status(null,null); }
    private Status status(Scope scope,String forcedCode) {
        String code=forcedCode==null?config.problem():forcedCode;
        OdooSyncStateStore.Snapshot saved=null;
        if(config.problem()==null)saved=state.snapshot(config.sourceNamespace());
        if(code==null)code=saved==null||saved.lastCheckCode()==null?"NOT_CHECKED":saved.lastCheckCode();
        SyncReport lastSync=null;
        if(saved!=null&&saved.lastSyncJson()!=null)try { lastSync=json.readValue(saved.lastSyncJson(),SyncReport.class); }
        catch(Exception ignored) { /* A schema change must not hide configuration status. */ }
        String message=switch(code) {
            case "READY" -> "De Odoo-administratie en bankdagboeken zijn bereikbaar. Factuurverzending heeft aparte instellingen en controles.";
            case "NOT_CHECKED" -> "De configuratie is ingevuld. Controleer de verbinding voordat je synchroniseert.";
            default -> new OdooFailure(code).userMessage();
        };
        return new Status(config.enabled,config.problem()==null,code,message,config.missing(),outbound!=null&&outbound.enabled&&outbound.sendEnabled,
                saved==null?null:saved.lastCheckAt(),saved==null?null:saved.lastSyncAt(),lastSync,
                scope==null?null:scope.companyName,scope==null?null:scope.journals.size());
    }
    public Status check() {
        if(config.problem()!=null)return status();
        try {
            Scope scope=scope();requireBankSchema();
            // Verify access to the actual statement model even when the company has no bank movements.
            client.search("account.bank.statement.line",bankDomain(scope),BANK_FIELDS,1,"id asc");
            state.checked(config.sourceNamespace(),"READY");return status(scope,"READY");
        } catch(OdooFailure failure) { state.checked(config.sourceNamespace(),failure.code);return status(null,failure.code); }
    }
    public SyncReport syncBank() {
        config.requireReady();String namespace=config.sourceNamespace(),owner=UUID.randomUUID().toString();
        try { state.acquire(namespace,owner); }
        catch(OdooFailure failure) { throw failure; }
        catch(RuntimeException raced) { throw new OdooFailure("BUSY"); }
        int scanned=0,created=0,updated=0,unchanged=0,conflicts=0,skipped=0;
        boolean complete=false;String code="READY";
        try {
            Scope scope=scope();requireBankSchema();List<Object> base=bankDomain(scope);
            JsonNode latest=client.search("account.bank.statement.line",base,List.of("id"),1,"id desc");
            long upper=latest.isEmpty()?0:positiveId(latest.get(0).path("id")),cursor=0;
            while(cursor<upper) {
                state.renew(namespace,owner);
                List<Object> domain=new ArrayList<>(base);
                domain.add(List.of("id",">",cursor));domain.add(List.of("id","<=",upper));
                JsonNode rows=client.search("account.bank.statement.line",domain,BANK_FIELDS,PAGE_SIZE,"id asc");
                if(rows.isEmpty())break;
                for(JsonNode row:rows) {
                    long id=positiveId(row.path("id"));
                    if(id<=cursor||id>upper)throw new OdooFailure("INVALID_RESPONSE");
                    cursor=id;scanned++;
                    Journal journal=scope.journals.get(relationId(row.path("journal_id")));
                    if(journal==null||relationId(row.path("company_id"))!=config.companyId)throw new OdooFailure("INVALID_RESPONSE");
                    if(journal.currencyId!=scope.eurCurrencyId||relationId(row.path("currency_id"))!=scope.eurCurrencyId) { skipped++;continue; }
                    var result=bank.importExternal(map(row,namespace,journal.account,config.companyId,journal.id,scope.eurCurrencyId));
                    switch(result.outcome()) {
                        case CREATED -> created++;case UPDATED -> updated++;case UNCHANGED -> unchanged++;case CONFLICT -> conflicts++;
                    }
                }
            }
            complete=true;
        } catch(OdooFailure failure) { code=failure.code; }
        catch(RuntimeException failure) { code="UNAVAILABLE"; }
        String message=complete?"Alle toegankelijke geboekte bankregels binnen de ingestelde administratie en dagboeken zijn opgehaald. "
                +"Afpunten in Odoo wijzigt geen koppelingen in ENROSED.":new OdooFailure(code).userMessage()+" Reeds geïmporteerde regels blijven behouden; opnieuw proberen maakt geen dubbele regels.";
        if(skipped>0)message+=" "+skipped+" regels overgeslagen omdat de bankvaluta geen EUR is.";
        if(conflicts>0)message+=" "+conflicts+" bronwijzigingen vragen controle; de gekoppelde lokale bankregels zijn behouden.";
        SyncReport report=new SyncReport(scanned,created,updated,unchanged,conflicts,skipped,complete,message,code);
        try { state.finish(namespace,owner,json.writeValueAsString(report)); }
        catch(OdooFailure failure) { throw failure; }
        catch(Exception failure) { throw new OdooFailure("UNAVAILABLE"); }
        return report;
    }
    private void requireBankSchema() {
        if(!client.fields("account.bank.statement.line").containsAll(BANK_FIELDS))throw new OdooFailure("UNSUPPORTED_SCHEMA");
    }
    private List<Object> bankDomain(Scope scope) {
        return new ArrayList<>(List.of(List.of("company_id","=",config.companyId),List.of("state","=","posted"),List.of("amount","!=",0),
                List.of("journal_id","in",new ArrayList<>(scope.journals.keySet()))));
    }
    Scope scope() {
        JsonNode companies=client.search("res.company",List.of(List.of("id","=",config.companyId)),List.of("id","name","currency_id"),1,"id asc");
        if(companies.size()!=1||positiveId(companies.get(0).path("id"))!=config.companyId)throw new OdooFailure("COMPANY_UNAVAILABLE");
        JsonNode currencies=client.search("res.currency",List.of(List.of("name","=","EUR")),List.of("id","name"),1,"id asc");
        if(currencies.size()!=1)throw new OdooFailure("UNSUPPORTED_SCHEMA");
        long eur=positiveId(currencies.get(0).path("id")),companyCurrency=relationId(companies.get(0).path("currency_id"));
        Map<Long,Journal> journals=new LinkedHashMap<>();long cursor=0;
        Set<String> journalFields=client.fields("account.journal");
        List<String> fields=new ArrayList<>(List.of("id","company_id","currency_id","name"));
        if(journalFields.contains("bank_acc_number"))fields.add("bank_acc_number");
        while(true) {
            List<Object> domain=new ArrayList<>(List.of(List.of("company_id","=",config.companyId),List.of("type","in",List.of("bank","credit")),List.of("id",">",cursor)));
            if(!config.journalIds.isEmpty())domain.add(List.of("id","in",new ArrayList<>(config.journalIds)));
            JsonNode rows=client.search("account.journal",domain,fields,PAGE_SIZE,"id asc");
            if(rows.isEmpty())break;
            for(JsonNode row:rows) {
                long id=positiveId(row.path("id"));
                if(id<=cursor||relationId(row.path("company_id"))!=config.companyId)throw new OdooFailure("INVALID_RESPONSE");
                cursor=id;
                long currency=row.path("currency_id").isArray()?relationId(row.path("currency_id")):companyCurrency;
                String account=text(row,"bank_acc_number").replaceAll("\\s","").toUpperCase(Locale.ROOT);
                if(account.isBlank())account="ODOO-"+config.sourceNamespace().substring(0,12)+"-J"+id;
                if(account.length()>120)throw new OdooFailure("INVALID_RESPONSE");
                journals.put(id,new Journal(id,account,currency));
            }
        }
        if(journals.isEmpty()||!journals.keySet().containsAll(config.journalIds))throw new OdooFailure("NO_JOURNALS");
        return new Scope(text(companies.get(0),"name"),eur,Map.copyOf(journals));
    }
}
