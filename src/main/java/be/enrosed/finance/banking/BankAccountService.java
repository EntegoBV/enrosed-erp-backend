package be.enrosed.finance.banking;

import be.enrosed.finance.odoo.OdooFailure;
import be.enrosed.finance.odoo.OdooService;
import be.enrosed.sales.application.IncomingPaymentService;
import be.enrosed.shared.BusinessRuleException;
import be.enrosed.shared.NotFoundException;
import be.enrosed.shared.audit.ActivityLogService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.*;
import jakarta.transaction.Transactional;
import java.time.Instant;
import java.util.*;

@ApplicationScoped
public class BankAccountService {
    @Inject EntityManager em;
    @Inject OdooService odoo;
    @Inject ActivityLogService activity;

    public record Account(String key,String label,String source,boolean archived,List<String> aliases,
                          Long odooJournalId,boolean manualMovementsAllowed) {}
    public record Update(String label,String source,Long odooJournalId,List<String> aliases) {}

    /** Historical names remain intact; explicit aliases determine how they are grouped in the overview. */
    public List<Account> list() {
        Map<String,BankAccountEntity> registry=registry();
        Map<String,Boolean> sources=new TreeMap<>();
        for(String query:List.of("select distinct b.account from BankBalanceEntity b",
                "select distinct p.bankAccount from SalesPaymentEntity p where p.voidedAt is null and p.bankAccount is not null",
                "select distinct p.account from CostPaymentEntity p where p.account is not null"))
            for(String value:em.createQuery(query,String.class).getResultList())include(sources,registry,value,false);
        for(Object[] row:em.createQuery("select distinct b.account,b.source from BankStatementLineEntity b",Object[].class).getResultList())
            include(sources,registry,(String)row[0],"ODOO".equals(row[1]));
        for(var row:registry.values())if(row.canonicalKey==null)include(sources,registry,row.key,"ODOO".equals(row.source));
        return sources.entrySet().stream().map(entry->view(entry.getKey(),entry.getValue(),registry)).toList();
    }

    @Transactional
    public Account update(String rawKey,Update request) {
        String key=requiredKey(rawKey);
        if(request==null)throw new BusinessRuleException("Geen rekening meegestuurd");
        String label=request.label()==null?"":request.label().strip();
        if(label.isBlank()||label.length()>120)throw new BusinessRuleException("Geef een rekeningnaam van maximaal 120 tekens");
        if(!Set.of("MANUAL","ODOO").contains(Objects.toString(request.source(),"")))throw new BusinessRuleException("Ongeldige rekeningbron");
        var row=em.find(BankAccountEntity.class,key,LockModeType.PESSIMISTIC_WRITE);
        requireCanonical(row);
        var current=list().stream().filter(account->account.key().equals(key)).findFirst().orElse(null);
        boolean authoritative=current!=null&&current.source().equals("ODOO");
        if(authoritative&&!request.source().equals("ODOO"))throw new BusinessRuleException("Deze rekening wordt door Odoo beheerd; de bankbron kan niet handmatig worden gewijzigd");
        if(request.source().equals("MANUAL")&&request.odooJournalId()!=null)throw new BusinessRuleException("Een handmatige rekening heeft geen Odoo-bankdagboek");
        Set<String> aliases=new TreeSet<>();
        if(request.aliases()!=null)for(String value:request.aliases())aliases.add(requiredKey(value));
        aliases.remove(key);
        if(aliases.size()>30)throw new BusinessRuleException("Gebruik maximaal 30 alternatieve rekeningnamen");
        Map<String,Account> existing=new HashMap<>();
        for(var account:list())existing.put(account.key(),account);
        for(String alias:aliases) {
            var owner=em.find(BankAccountEntity.class,alias,LockModeType.PESSIMISTIC_WRITE);
            if(owner!=null&&!key.equals(owner.canonicalKey))throw new BusinessRuleException("De rekeningnaam "+alias+" wordt al afzonderlijk beheerd of hoort bij een andere rekening");
            if(existing.containsKey(alias)&&existing.get(alias).source().equals("ODOO"))throw new BusinessRuleException("Een andere Odoo-rekening kan niet als alternatieve naam worden toegevoegd");
        }
        OdooService.VerifiedBankAccount verified=null;
        if(request.source().equals("ODOO")&&request.odooJournalId()!=null) {
            if(request.odooJournalId()<=0)throw new BusinessRuleException("Kies een geldig Odoo-bankdagboek");
            if(row!=null&&row.odooJournalId!=null&&!row.odooJournalId.equals(request.odooJournalId()))
                throw new BusinessRuleException("Deze rekening is al met een ander Odoo-bankdagboek verbonden");
        }
        if(request.source().equals("ODOO")&&request.odooJournalId()!=null&&(row==null||row.odooJournalId==null)) {
            // Verify the actual provider IBAN, not a user-supplied authority claim or alias.
            verified=odoo.verifyBankAccount(request.odooJournalId(),key);
            requireProvenance(verified.sourceNamespace(),key);
            var other=em.createQuery("from BankAccountEntity b where b.odooSourceNamespace=:namespace and b.odooJournalId=:journal and b.key<>:key",BankAccountEntity.class)
                    .setParameter("namespace",verified.sourceNamespace()).setParameter("journal",verified.journalId()).setParameter("key",key).getResultList();
            if(!other.isEmpty())throw new BusinessRuleException("Dit Odoo-bankdagboek is al aan een andere rekening gekoppeld");
        } else if(request.source().equals("ODOO")&&!authoritative) {
            throw new BusinessRuleException("Controleer eerst het Odoo-bankdagboek voor deze rekening");
        }
        if(row==null) { row=newRow(key);em.persist(row); }
        row.label=label;row.source=request.source();row.updatedAt=Instant.now();
        if(verified!=null) { row.odooSourceNamespace=verified.sourceNamespace();row.odooCompanyId=verified.companyId();row.odooJournalId=verified.journalId(); }
        for(var alias:em.createQuery("from BankAccountEntity b where b.canonicalKey=:key",BankAccountEntity.class).setParameter("key",key).getResultList())
            if(!aliases.contains(alias.key))em.remove(alias);
        for(String alias:aliases)if(em.find(BankAccountEntity.class,alias)==null) {
            var added=newRow(alias);added.canonicalKey=key;em.persist(added);
        }
        em.flush();
        audit(row,"Rekeningnaam en bankbron bijgewerkt; financiële historiek behouden");
        return get(key);
    }

    @Transactional
    public void archive(String rawKey) {
        var row=managed(rawKey);
        if(!row.archived) { row.archived=true;row.updatedAt=Instant.now();audit(row,"Rekening uit het overzicht verwijderd; financiële historiek behouden"); }
    }

    @Transactional
    public Account restore(String rawKey) {
        var row=managed(rawKey);
        if(row.archived) { row.archived=false;row.updatedAt=Instant.now();audit(row,"Rekening teruggezet in het overzicht"); }
        return get(row.key);
    }

    /** Also protects already imported accounts before anyone has configured display metadata. */
    public void requireManualMovements(String rawKey) {
        String key=requiredKey(rawKey);
        var registry=registry();
        var alias=registry.get(key);
        if(alias!=null&&alias.canonicalKey!=null)key=alias.canonicalKey;
        String canonical=key;
        var account=list().stream().filter(value->value.key().equals(canonical)).findFirst().orElse(null);
        if(account!=null&&account.archived())throw new BusinessRuleException("Deze rekening is gearchiveerd; zet de rekening eerst terug in het overzicht");
        if(account!=null&&!account.manualMovementsAllowed())throw new BusinessRuleException("De bankbewegingen van deze rekening komen uit Odoo; synchroniseer en kies de echte banktransactie");
    }

    /** Explicit bindings fail closed when provider configuration or the journal's IBAN changes. */
    public void validateImportAccount(String namespace,long companyId,long journalId,String rawAccount) {
        String key=requiredKey(rawAccount);
        var registered=em.find(BankAccountEntity.class,key);
        if(registered!=null&&registered.canonicalKey!=null)throw new OdooFailure("JOURNAL_ACCOUNT_MISMATCH");
        var mappings=em.createQuery("from BankAccountEntity b where b.odooJournalId is not null",BankAccountEntity.class).getResultList();
        for(var mapping:mappings) {
            boolean sameJournal=namespace.equals(mapping.odooSourceNamespace)&&mapping.odooJournalId==journalId;
            if((sameJournal&&!mapping.key.equals(key))||(mapping.key.equals(key)&&(!sameJournal||!Objects.equals(mapping.odooCompanyId,companyId))))
                throw new OdooFailure("JOURNAL_ACCOUNT_MISMATCH");
        }
    }

    /** Called only by an explicit sync after reading the real EUR journal; connection checks stay read-only. */
    @Transactional
    public void registerImportedAccount(String namespace,long companyId,long journalId,String rawAccount) {
        String key=requiredKey(rawAccount);
        if(key.startsWith("ODOO-"))return; // A synthetic fallback does not prove an actual bank account.
        var row=em.find(BankAccountEntity.class,key,LockModeType.PESSIMISTIC_WRITE);
        validateImportAccount(namespace,companyId,journalId,key);
        requireProvenance(namespace,key);
        if(row==null) { row=newRow(key);em.persist(row); }
        if(row.odooJournalId!=null)return;
        row.source="ODOO";row.odooSourceNamespace=namespace;row.odooCompanyId=companyId;row.odooJournalId=journalId;row.updatedAt=Instant.now();
        em.flush();audit(row,"Rekening verbonden met het gecontroleerde Odoo-bankdagboek; financiële historiek behouden");
    }

    private void requireProvenance(String namespace,String key) {
        for(String sourceId:em.createQuery("select b.sourceId from BankStatementLineEntity b where b.source='ODOO' and b.account=:key",String.class).setParameter("key",key).getResultList())
            if(sourceId==null||!sourceId.startsWith(namespace+":"))throw new OdooFailure("JOURNAL_ACCOUNT_MISMATCH");
    }

    private BankAccountEntity managed(String rawKey) {
        String key=requiredKey(rawKey);
        var row=em.find(BankAccountEntity.class,key,LockModeType.PESSIMISTIC_WRITE);
        requireCanonical(row);
        if(row==null) {
            var account=get(key);
            row=newRow(key);row.label=account.label();row.source=account.source();em.persist(row);
        }
        return row;
    }
    private Account get(String key) {return list().stream().filter(value->value.key().equals(key)).findFirst().orElseThrow(()->new NotFoundException("Rekening",key));}
    private static void requireCanonical(BankAccountEntity row) {
        if(row!=null&&row.canonicalKey!=null)throw new BusinessRuleException("Beheer deze alternatieve naam via de rekening "+row.canonicalKey);
    }
    private Map<String,BankAccountEntity> registry() {
        Map<String,BankAccountEntity> result=new HashMap<>();
        for(var row:em.createQuery("from BankAccountEntity",BankAccountEntity.class).getResultList())result.put(row.key,row);
        return result;
    }
    private static void include(Map<String,Boolean> sources,Map<String,BankAccountEntity> registry,String raw,boolean odoo) {
        String key=IncomingPaymentService.normalizeAccount(raw);
        if(key==null)return;
        var row=registry.get(key);
        if(row!=null&&row.canonicalKey!=null)key=row.canonicalKey;
        sources.merge(key,odoo,Boolean::logicalOr);
    }
    private static Account view(String key,boolean odoo,Map<String,BankAccountEntity> registry) {
        var row=registry.get(key);
        String source=odoo||row!=null&&"ODOO".equals(row.source)?"ODOO":"MANUAL";
        boolean archived=row!=null&&row.archived;
        var aliases=registry.values().stream().filter(value->key.equals(value.canonicalKey)).map(value->value.key).sorted().toList();
        return new Account(key,row==null?key:row.label,source,archived,aliases,row==null?null:row.odooJournalId,!archived&&source.equals("MANUAL"));
    }
    private static String requiredKey(String value) {
        String key=IncomingPaymentService.normalizeAccount(value);
        if(key==null)throw new BusinessRuleException("Geef de rekening een naam");
        return key;
    }
    private static BankAccountEntity newRow(String key) {
        var row=new BankAccountEntity();row.key=key;row.label=key;row.source="MANUAL";row.createdAt=Instant.now();row.updatedAt=row.createdAt;return row;
    }
    private void audit(BankAccountEntity row,String message) {
        activity.record(ActivityLogService.ACTION_UPDATED,"BANK_ACCOUNT",null,row.label,message);
    }
}
