package be.enrosed.finance.odoo;

import be.enrosed.sales.application.*;
import be.enrosed.sales.domain.*;
import be.enrosed.shared.company.CompanyProfileService;
import com.fasterxml.jackson.databind.*;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.math.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import static be.enrosed.finance.odoo.OdooBankMapper.*;

/** Durable, explicitly gated test outbox. An ambiguous mutation can only be read/reconciled, never blindly retried. */
@ApplicationScoped @Transactional(Transactional.TxType.NOT_SUPPORTED)
public class OdooOutboxService {
    @Inject OdooClient client;
    @Inject OdooConfiguration config;
    @Inject OdooOutboundConfiguration outbound;
    @Inject OdooOutboxStore store;
    @Inject OdooSyncStateStore leases;
    @Inject SalesOrderService sales;
    @Inject SalesAdvanceBillingService advances;
    @Inject CustomerService customers;
    @Inject CompanyProfileService company;
    @Inject ObjectMapper json;
    public record Line(String description,BigDecimal quantity,BigDecimal unitPrice,BigDecimal discount,BigDecimal total) {}
    public record LocalPreview(String number,String moveType,String date,String dueDate,String customerName,String customerVat,
                               String vatTreatment,BigDecimal vatRate,BigDecimal net,BigDecimal vat,BigDecimal total,
                               List<Line> lines,String legalMention,String creditReference) {}
    public record Identity(String name,String vat,String country,String address,String zip,String city) {}
    public record LocalData(LocalPreview preview,Identity customer,Identity seller) {}
    public record Snapshot(LocalData local,String sourceHash,long partnerId,long companyId,long journalId,long accountId,long taxId,
                           long currencyId,Long reversedEntryId) {}
    public record Entry(String state,String snapshotHash,Long odooMoveId,String number,String peppolState,Instant updatedAt,String message) {}
    public record View(boolean enabled,boolean sendEnabled,boolean configured,String code,String message,List<String> missingConfiguration,
                       LocalPreview localPreview,Entry entry) {}
    public record Partner(long id,String name,String vat,String country) {}
    public record Partners(List<Partner> partners,String message) {}
    public record PreviewRequest(Long odooPartnerId) {}
    public record MutationRequest(String snapshotHash) {}
    private static final List<String> PARTNER_FIELDS=List.of("id","name","vat","country_code","street","street2","zip","city","company_id");
    private static final List<String> MOVE_FIELDS=List.of("id","name","ref","invoice_origin","company_id","partner_id","journal_id","currency_id",
            "invoice_date","invoice_date_due","move_type","state","amount_untaxed","amount_tax","amount_total","peppol_move_state","reversed_entry_id");

    public View view(long id) {
        var saved=store.get(id);LocalData local=null;String problem=null;
        try {local=localData(id);}catch(OdooFailure unsupported){problem=unsupported.code;}
        Snapshot snapshot=saved==null?null:snapshot(saved);
        if(saved!=null&&config.problem()==null&&!saved.sourceNamespace().equals(config.sourceNamespace()))problem="OUTBOX_SOURCE_CHANGED";
        if(saved!=null&&local!=null&&!snapshot.sourceHash().equals(hash(encode(local))))problem="OUTBOX_CHANGED";
        String treatment=local==null?null:local.preview.vatTreatment;
        List<String> missing=new ArrayList<>(config.missing());missing.addAll(outbound.missing(treatment));
        boolean configured=config.problem()==null&&outbound.enabled&&missing.isEmpty();
        if(problem==null)problem=!outbound.enabled?"OUTBOUND_DISABLED":!configured?"OUTBOUND_NOT_CONFIGURED":saved==null?"OUTBOX_PREVIEW_REQUIRED":saved.messageCode();
        if(problem==null)problem="READY";
        Entry entry=saved==null?null:new Entry(saved.state(),saved.snapshotHash(),saved.odooMoveId(),snapshot.local.preview.number,
                saved.peppolState(),saved.updatedAt(),message(saved.messageCode()==null?"READY":saved.messageCode()));
        return new View(outbound.enabled,outbound.sendEnabled,configured,problem,message(problem),List.copyOf(missing),
                snapshot==null?(local==null?null:local.preview):snapshot.local.preview,entry);
    }
    public Partners partners(long id) {
        config.requireReady();LocalData local=localData(id);
        String vat=normalizeVat(local.customer.vat);
        List<Object> domain=new ArrayList<>(List.of("|",List.of("company_id","=",false),List.of("company_id","=",config.companyId)));
        domain.add(vat.isEmpty()?List.of("name","=",local.customer.name):List.of("vat","=",vat));
        JsonNode rows=client.search("res.partner",domain,PARTNER_FIELDS,100,"id asc");
        List<Partner> candidates=new ArrayList<>();
        for(JsonNode row:rows) {
            try {validatePartner(row,local.customer);candidates.add(new Partner(positiveId(row.path("id")),text(row,"name"),text(row,"vat"),text(row,"country_code")));}
            catch(OdooFailure mismatch) { /* Offer only partners with the same verified identity and postal address. */ }
        }
        return new Partners(List.copyOf(candidates),candidates.isEmpty()?"Geen Odoo-klant met overeenkomende naam, btw-nummer en adres. Werk de klantgegevens in Odoo bij.":"Kies de overeenkomende klant in Odoo.");
    }
    public View preview(long id,PreviewRequest request) {
        if(request==null||request.odooPartnerId==null||request.odooPartnerId<=0)throw new OdooFailure("INVALID_REQUEST");
        return locked(id,()->{
            LocalData local=localData(id);outbound.requireMapping(local.preview.vatTreatment);
            JsonNode partner=one("res.partner",List.of(List.of("id","=",request.odooPartnerId)),PARTNER_FIELDS);
            validatePartner(partner,local.customer);long currency=validateEnvironment(local);
            Long reversed=null;
            if(local.preview.creditReference!=null) {
                JsonNode original=client.search("account.move",List.of(List.of("company_id","=",config.companyId),List.of("partner_id","=",request.odooPartnerId),
                        List.of("name","=",local.preview.creditReference),List.of("move_type","=","out_invoice"),List.of("state","=","posted")),List.of("id"),2,"id asc");
                if(original.size()!=1)throw new OdooFailure("CREDIT_REFERENCE_MISSING");reversed=positiveId(original.get(0).path("id"));
            }
            var snapshot=new Snapshot(local,hash(encode(local)),request.odooPartnerId,config.companyId,outbound.journalId,outbound.incomeAccountId,
                    outbound.taxIds.get(local.preview.vatTreatment),currency,reversed);
            String encoded=encode(snapshot),namespace=config.sourceNamespace();
            store.preview(id,namespace,"ENROSED:"+namespace+":"+id,hash(encoded),encoded);
            return view(id);
        });
    }
    public View draft(long id,MutationRequest request) {
        requireRequest(request);
        return locked(id,()->{
            var saved=required(id,request);var snapshot=snapshot(saved);outbound.requireMapping(snapshot.local.preview.vatTreatment);
            requireSameMapping(snapshot);
            validateEnvironment(snapshot.local);validateCurrentPartner(snapshot);
            if("DRAFT".equals(saved.state()))return view(id);
            if(!"PREVIEW".equals(saved.state()))throw new OdooFailure("OUTBOX_RETRY_BLOCKED");
            if(!findRemote(saved).isEmpty())throw new OdooFailure("OUTBOX_REMOTE_EXISTS");
            // Persist uncertainty BEFORE any external mutation. Crashes/timeouts cannot turn into a second create.
            store.transition(id,saved.snapshotHash(),"UNKNOWN_CREATE",null,null,"OUTBOX_UNKNOWN_CREATE");
            try {
                JsonNode created=client.write("account.move","create",Map.of("vals_list",List.of(draftValues(saved,snapshot))));
                long remoteId=created.isArray()&&created.size()==1?positiveId(created.get(0)):positiveId(created);
                store.transition(id,saved.snapshotHash(),"UNKNOWN_CREATE",remoteId,null,"OUTBOX_UNKNOWN_CREATE");
                JsonNode remote=remote(remoteId);validateRemote(remote,saved,snapshot,true);
                store.transition(id,saved.snapshotHash(),"DRAFT",remoteId,optionalText(remote,"peppol_move_state"),null);
            } catch(OdooFailure failure) {
                // Keep UNKNOWN for transport uncertainty; exact mismatches remain blocked for manual correction.
                if("OUTBOX_REMOTE_MISMATCH".equals(failure.code))store.transition(id,saved.snapshotHash(),"BLOCKED",null,null,failure.code);
                throw failure;
            }
            return view(id);
        });
    }
    public View send(long id,MutationRequest request) {
        requireRequest(request);
        return locked(id,()->{
            outbound.requireWrite(true);var saved=required(id,request);var snapshot=snapshot(saved);
            requireSameMapping(snapshot);
            if(Set.of("QUEUED","DONE").contains(saved.state()))return view(id);
            if(!"DRAFT".equals(saved.state())||saved.odooMoveId()==null)throw new OdooFailure("OUTBOX_RETRY_BLOCKED");
            validateEnvironment(snapshot.local);validateCurrentPartner(snapshot);
            JsonNode remote=remote(saved.odooMoveId());validateRemote(remote,saved,snapshot,true);
            store.transition(id,saved.snapshotHash(),"UNKNOWN_SEND",null,optionalText(remote,"peppol_move_state"),"OUTBOX_UNKNOWN_SEND");
            // Posting is deliberately part of the explicit send action, after every financial comparison.
            client.write("account.move","action_post",Map.of("ids",List.of(saved.odooMoveId())));
            remote=remote(saved.odooMoveId());validateRemote(remote,saved,snapshot,false);
            if(!"posted".equals(text(remote,"state")))throw new OdooFailure("OUTBOX_REMOTE_MISMATCH");
            JsonNode wizard=client.write("account.move.send.wizard","create",Map.of("vals_list",List.of(Map.of(
                    "move_id",saved.odooMoveId(),"sending_methods",List.of("peppol"),"extra_edis",List.of()))));
            long wizardId=wizard.isArray()&&wizard.size()==1?positiveId(wizard.get(0)):positiveId(wizard);
            client.write("account.move.send.wizard","action_send_and_print",Map.of("ids",List.of(wizardId),"allow_fallback_pdf",false));
            refreshSaved(store.get(id),snapshot);return view(id);
        });
    }
    public View refresh(long id) {return locked(id,()->{var saved=required(id,null);refreshSaved(saved,snapshot(saved));return view(id);});}
    private void refreshSaved(OdooOutboxStore.Saved saved,Snapshot snapshot) {
        JsonNode matches=findRemote(saved);
        if(matches.isEmpty())return;
        if(matches.size()!=1)throw new OdooFailure("OUTBOX_REMOTE_MISMATCH");
        JsonNode remote=matches.get(0);long remoteId=positiveId(remote.path("id"));
        if(saved.odooMoveId()!=null&&saved.odooMoveId()!=remoteId)throw new OdooFailure("OUTBOX_REMOTE_MISMATCH");
        validateRemote(remote,saved,snapshot,false);
        String peppol=optionalText(remote,"peppol_move_state"),state=saved.state(),code=saved.messageCode();
        if("done".equals(peppol)){state="DONE";code=null;}
        else if(Set.of("to_send","processing").contains(peppol==null?"":peppol)){state="QUEUED";code=null;}
        else if("error".equals(peppol)||"cancel".equals(text(remote,"state"))){state="BLOCKED";code="OUTBOX_REMOTE_MISMATCH";}
        else if(Set.of("PREVIEW","UNKNOWN_CREATE").contains(state)&&"draft".equals(text(remote,"state"))){state="DRAFT";code=null;}
        // UNKNOWN_SEND + ready/draft does not prove that no transmission occurred: stay blocked from retry.
        store.transition(saved.salesOrderId(),saved.snapshotHash(),state,remoteId,peppol,code);
    }
    private OdooOutboxStore.Saved required(long id,MutationRequest request) {
        config.requireReady();var saved=store.get(id);if(saved==null)throw new OdooFailure("OUTBOX_PREVIEW_REQUIRED");
        if(!saved.sourceNamespace().equals(config.sourceNamespace()))throw new OdooFailure("OUTBOX_SOURCE_CHANGED");
        if(request!=null&&(!Objects.equals(request.snapshotHash,saved.snapshotHash())))throw new OdooFailure("OUTBOX_CHANGED");
        if(request!=null&&!snapshot(saved).sourceHash.equals(hash(encode(localData(id)))))throw new OdooFailure("OUTBOX_CHANGED");
        return saved;
    }
    private View locked(long id,java.util.function.Supplier<View> action) {
        config.requireReady();String key=hash("outbox|"+config.sourceNamespace()+"|"+id),owner=UUID.randomUUID().toString();
        leases.acquireFor(key,owner,900);
        try {return action.get();}finally {leases.finish(key,owner,"{}");}
    }
    private void requireRequest(MutationRequest request) {if(request==null||request.snapshotHash==null||request.snapshotHash.isBlank())throw new OdooFailure("INVALID_REQUEST");}
    private void requireSameMapping(Snapshot snapshot) {
        if(!Objects.equals(outbound.journalId,snapshot.journalId)||!Objects.equals(outbound.incomeAccountId,snapshot.accountId)
                ||!Objects.equals(outbound.taxIds.get(snapshot.local.preview.vatTreatment),snapshot.taxId))throw new OdooFailure("OUTBOX_CHANGED");
    }
    LocalData localData(long id) {
        SalesOrder order=sales.get(id);
        if(!order.isClaimDocument()||!Set.of(QuoteStatus.UITGEREIKT,QuoteStatus.VERZONDEN,QuoteStatus.BEKEKEN,QuoteStatus.BETAALD).contains(order.status()))
            throw new OdooFailure("OUTBOX_NOT_ISSUED");
        Customer customer=customers.get(order.customerId());
        if(customer.fiscalRepresentative()||order.isPartnerDeal()||order.partnerSettlement()||order.paymentPlan()!=SalesPaymentPlan.FULL||sales.hasSplitOrder(order)
                ||advances.presentation(order)!=null)
            throw new OdooFailure("OUTBOX_UNSUPPORTED_DOCUMENT");
        PricedOrder priced=sales.price(order);var totals=priced.totals();
        if(totals.vatTreatment()==null||totals.vatTreatment()==VatTreatment.VERLEGD_FISCAAL_VERTEGENWOORDIGER
                ||totals.totalInclVat().signum()<=0||order.number()==null||order.orderDate()==null)
            throw new OdooFailure("OUTBOX_UNSUPPORTED_DOCUMENT");
        List<Line> lines=new ArrayList<>();
        for(var line:priced.lines())lines.add(new Line(line.customerDescription()==null?line.description():line.customerDescription(),
                BigDecimal.valueOf(line.quantity()),line.unitPrice(),line.discountPct(),line.net()));
        addAmount(lines,"Orderkorting",totals.orderDiscountAmount().negate());
        addAmount(lines,totals.extraDiscountLabel()==null?"Extra korting":totals.extraDiscountLabel(),totals.extraDiscountAmount().negate());
        for(var line:priced.extraLines())lines.add(new Line(line.description(),line.quantity(),line.unitPrice(),BigDecimal.ZERO,line.total()));
        addAmount(lines,"Transport",totals.freight());addAmount(lines,"Behandeling",totals.handling());
        if(lines.isEmpty()||lines.stream().anyMatch(line->line.description==null||line.description.isBlank()||line.quantity.signum()<=0)
                ||lines.stream().map(Line::total).reduce(BigDecimal.ZERO,BigDecimal::add).compareTo(totals.total())!=0)
            throw new OdooFailure("OUTBOX_UNSUPPORTED_DOCUMENT");
        String creditReference=null;
        if(order.isCreditNote()) {
            if(order.creditedInvoiceId()==null)throw new OdooFailure("OUTBOX_UNSUPPORTED_DOCUMENT");
            creditReference=sales.get(order.creditedInvoiceId()).number();
        }
        var profile=company.get();
        var preview=new LocalPreview(order.number(),order.isCreditNote()?"out_refund":"out_invoice",order.orderDate().toString(),
                order.invoiceDueDate()==null?null:order.invoiceDueDate().toString(),customer.company(),customer.vatNumber(),totals.vatTreatment().name(),
                totals.vatRatePct(),totals.total(),totals.vatAmount(),totals.totalInclVat(),List.copyOf(lines),totals.vatLegalMention(),creditReference);
        return new LocalData(preview,new Identity(customer.company(),customer.vatNumber(),customer.countryCode(),customer.address(),customer.postalCode(),customer.city()),
                new Identity(profile.legalName(),profile.vatNumber(),profile.countryCode(),profile.addressLine(),profile.postalCode(),profile.city()));
    }
    private static void addAmount(List<Line> lines,String description,BigDecimal amount) {
        if(amount!=null&&amount.signum()!=0)lines.add(new Line(description,BigDecimal.ONE,amount,BigDecimal.ZERO,amount));
    }
    private void validateCurrentPartner(Snapshot snapshot) {validatePartner(one("res.partner",List.of(List.of("id","=",snapshot.partnerId)),PARTNER_FIELDS),snapshot.local.customer);}
    private void validatePartner(JsonNode partner,Identity identity) {
        if(partner.path("company_id").isArray()&&relationId(partner.path("company_id"))!=config.companyId)throw new OdooFailure("OUTBOX_PARTNER_MISMATCH");
        String address=(text(partner,"street")+" "+text(partner,"street2")).strip();
        if(!normalizeVat(text(partner,"vat")).equals(normalizeVat(identity.vat))||!normalized(text(partner,"name")).equals(normalized(identity.name))
                ||!normalized(address).equals(normalized(identity.address))||!normalized(text(partner,"zip")).equals(normalized(identity.zip))
                ||!normalized(text(partner,"city")).equals(normalized(identity.city))||!normalized(text(partner,"country_code")).equals(normalized(identity.country)))
            throw new OdooFailure("OUTBOX_PARTNER_MISMATCH");
    }
    private long validateEnvironment(LocalData local) {
        outbound.requireMapping(local.preview.vatTreatment);
        JsonNode seller=one("res.company",List.of(List.of("id","=",config.companyId)),List.of("id","vat","currency_id"));
        if(normalizeVat(local.seller.vat).isEmpty()||!normalizeVat(text(seller,"vat")).equals(normalizeVat(local.seller.vat)))throw new OdooFailure("OUTBOX_COMPANY_MISMATCH");
        JsonNode eur=one("res.currency",List.of(List.of("name","=","EUR")),List.of("id"));long currency=positiveId(eur.path("id"));
        if(relationId(seller.path("currency_id"))!=currency)throw new OdooFailure("OUTBOX_REMOTE_MISMATCH");
        JsonNode journal=one("account.journal",List.of(List.of("id","=",outbound.journalId),List.of("company_id","=",config.companyId),List.of("type","=","sale")),List.of("id","currency_id"));
        if(journal.path("currency_id").isArray()&&relationId(journal.path("currency_id"))!=currency)throw new OdooFailure("OUTBOX_REMOTE_MISMATCH");
        one("account.account",List.of(List.of("id","=",outbound.incomeAccountId),List.of("company_ids","in",List.of(config.companyId)),
                List.of("account_type","in",List.of("income","income_other"))),List.of("id"));
        JsonNode tax=one("account.tax",List.of(List.of("id","=",outbound.taxIds.get(local.preview.vatTreatment)),List.of("company_id","=",config.companyId),
                List.of("type_tax_use","=","sale")),List.of("id","amount","amount_type","price_include"));
        if(!"percent".equals(text(tax,"amount_type"))||tax.path("price_include").asBoolean(true)||!tax.path("amount").isNumber()
                ||tax.path("amount").decimalValue().compareTo(local.preview.vatRate)!=0)throw new OdooFailure("OUTBOX_TAX_MISMATCH");
        // TST cannot send live Peppol invoices, even if someone points its credentials at a production database.
        JsonNode proxy=client.search("account_edi_proxy_client.user",List.of(List.of("company_id","=",config.companyId),List.of("proxy_type","=","peppol"),
                List.of("active","=",true)),List.of("id","edi_mode"),2,"id asc");
        if(proxy.size()!=1||!"test".equals(text(proxy.get(0),"edi_mode")))throw new OdooFailure("OUTBOX_TEST_MODE_REQUIRED");
        if(!client.fields("account.move").containsAll(MOVE_FIELDS))throw new OdooFailure("UNSUPPORTED_SCHEMA");
        return currency;
    }
    Map<String,Object> draftValues(OdooOutboxStore.Saved saved,Snapshot snapshot) {
        var preview=snapshot.local.preview;Map<String,Object> values=new LinkedHashMap<>();
        values.put("company_id",snapshot.companyId);values.put("journal_id",snapshot.journalId);values.put("partner_id",snapshot.partnerId);
        values.put("currency_id",snapshot.currencyId);values.put("move_type",preview.moveType);values.put("name",preview.number);
        values.put("ref",saved.marker());values.put("invoice_origin",preview.number);values.put("invoice_date",preview.date);values.put("date",preview.date);
        values.put("invoice_payment_term_id",false);if(preview.dueDate!=null)values.put("invoice_date_due",preview.dueDate);
        if(snapshot.reversedEntryId!=null)values.put("reversed_entry_id",snapshot.reversedEntryId);
        String legal=preview.legalMention==null?"":preview.legalMention;
        if(preview.creditReference!=null)legal+=" · Creditnota bij factuur "+preview.creditReference;
        values.put("narration",escapeHtml(legal));
        List<Object> lines=new ArrayList<>();
        for(Line line:preview.lines)lines.add(List.of(0,0,Map.of("name",line.description,"quantity",line.quantity,"price_unit",line.unitPrice,
                "discount",line.discount,"account_id",snapshot.accountId,"tax_ids",List.of(List.of(6,0,List.of(snapshot.taxId))))));
        values.put("invoice_line_ids",lines);return values;
    }
    void validateRemote(JsonNode remote,OdooOutboxStore.Saved saved,Snapshot snapshot,boolean draftRequired) {
        var preview=snapshot.local.preview;
        if(!text(remote,"name").equals(preview.number)||!text(remote,"ref").equals(saved.marker())||!text(remote,"invoice_origin").equals(preview.number)
                ||relationId(remote.path("company_id"))!=snapshot.companyId||relationId(remote.path("partner_id"))!=snapshot.partnerId
                ||relationId(remote.path("journal_id"))!=snapshot.journalId||relationId(remote.path("currency_id"))!=snapshot.currencyId
                ||!text(remote,"invoice_date").equals(preview.date)||preview.dueDate!=null&&!text(remote,"invoice_date_due").equals(preview.dueDate)
                ||!text(remote,"move_type").equals(preview.moveType)||draftRequired&&!"draft".equals(text(remote,"state"))
                ||!moneyEquals(remote,"amount_untaxed",preview.net)||!moneyEquals(remote,"amount_tax",preview.vat)||!moneyEquals(remote,"amount_total",preview.total))
            throw new OdooFailure("OUTBOX_REMOTE_MISMATCH");
        if(snapshot.reversedEntryId!=null&&relationId(remote.path("reversed_entry_id"))!=snapshot.reversedEntryId)throw new OdooFailure("OUTBOX_REMOTE_MISMATCH");
        List<String> fields=List.of("id","name","quantity","price_unit","discount","price_subtotal","account_id","tax_ids");
        JsonNode lines=client.search("account.move.line",List.of(List.of("move_id","=",positiveId(remote.path("id"))),List.of("display_type","=","product")),fields,preview.lines.size()+1,"sequence asc,id asc");
        if(lines.size()!=preview.lines.size())throw new OdooFailure("OUTBOX_REMOTE_MISMATCH");
        for(int i=0;i<lines.size();i++) {JsonNode line=lines.get(i);Line expected=preview.lines.get(i);
            if(!text(line,"name").equals(expected.description)||!moneyEquals(line,"quantity",expected.quantity)
                    ||!moneyEquals(line,"price_unit",expected.unitPrice)||!moneyEquals(line,"discount",expected.discount)
                    ||!moneyEquals(line,"price_subtotal",expected.total)||relationId(line.path("account_id"))!=snapshot.accountId
                    ||!line.path("tax_ids").isArray()||line.path("tax_ids").size()!=1||positiveId(line.path("tax_ids").get(0))!=snapshot.taxId)
                throw new OdooFailure("OUTBOX_REMOTE_MISMATCH");
        }
    }
    private JsonNode findRemote(OdooOutboxStore.Saved saved) {return client.search("account.move",List.of(List.of("company_id","=",config.companyId),List.of("ref","=",saved.marker())),MOVE_FIELDS,2,"id asc");}
    private JsonNode remote(long id) {return one("account.move",List.of(List.of("id","=",id),List.of("company_id","=",config.companyId)),MOVE_FIELDS);}
    private JsonNode one(String model,List<?> domain,List<String> fields) {JsonNode rows=client.search(model,domain,fields,2,"id asc");if(rows.size()!=1)throw new OdooFailure("OUTBOX_REMOTE_MISMATCH");return rows.get(0);}
    private Snapshot snapshot(OdooOutboxStore.Saved saved) {try{return json.readValue(saved.snapshotJson(),Snapshot.class);}catch(Exception invalid){throw new OdooFailure("OUTBOX_CHANGED");}}
    private String encode(Object value) {try{return json.writeValueAsString(value);}catch(Exception invalid){throw new OdooFailure("OUTBOX_CHANGED");}}
    static String hash(String value) {try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception invalid){throw new OdooFailure("OUTBOX_CHANGED");}}
    static boolean moneyEquals(JsonNode row,String field,BigDecimal value) {return value!=null&&row.path(field).isNumber()&&row.path(field).decimalValue().compareTo(value)==0;}
    static String normalizeVat(String value) {return value==null?"":value.replaceAll("[^A-Za-z0-9]","").toUpperCase(Locale.ROOT);}
    static String normalized(String value) {return value==null?"":value.strip().replaceAll("\\s+"," ").toLowerCase(Locale.ROOT);}
    static String escapeHtml(String value) {return value.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;");}
    static String optionalText(JsonNode row,String field) {String value=text(row,field);return value.isBlank()?null:value;}
    private static String message(String code) {return "READY".equals(code)?"Controleer de factuur en voer elke stap expliciet uit. Er wordt alleen naar de Peppol-testomgeving verstuurd.":new OdooFailure(code).userMessage();}
}
