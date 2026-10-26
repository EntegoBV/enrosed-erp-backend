package be.enrosed.finance.odoo;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static be.enrosed.finance.odoo.OdooBankMapper.*;

/**
 * Read-only invoice search for the Facturen finder.
 * Coupled to package-private members owned by Codex (check them after every fetch, before merging):
 * OdooClient.search/fields, OdooConfiguration.problem/sourceNamespace/companyId, OdooBankMapper.text/positiveId/relationId,
 * OdooDocumentService.attachment(JsonNode) and INVOICE_TYPES.
 *
 * Only search_read and fields_get on account.move and ir.attachment; binary data is never read here (downloads stay with
 * {@link OdooDocumentService#download}). Nothing is written to Odoo and nothing is stored locally.
 */
@ApplicationScoped @Transactional(Transactional.TxType.NOT_SUPPORTED)
public class OdooInvoiceSearch {
    static final List<String> REQUIRED_FIELDS=List.of("id","name","ref","partner_id","date","invoice_date","move_type","state",
            "currency_id","amount_total");
    static final List<String> OPTIONAL_FIELDS=List.of("invoice_date_due","payment_state","amount_total_signed","peppol_move_state",
            "invoice_origin");
    /** Private copy of OdooDocumentService's attachment field list (that one is private). Never a binary field. */
    static final List<String> ATTACHMENT_FIELDS=List.of("id","res_id","name","mimetype","file_size","type");
    static final Duration FIELDS_TTL=Duration.ofMinutes(10);
    static final int ATTACHMENT_PAGE=200;
    private static final Map<String,Set<String>> DIRECTION_TYPES=Map.of(
            "IN",Set.of("in_invoice","in_refund"),"OUT",Set.of("out_invoice","out_refund"));
    private static final BigDecimal CENT=new BigDecimal("0.01");

    @Inject OdooClient client;
    @Inject OdooConfiguration config;
    /** Tests replace it to age the fields cache. */
    Clock clock=Clock.systemUTC();
    private final AtomicReference<FieldsRead> moveFields=new AtomicReference<>();
    private record FieldsRead(Set<String> fields,Instant readAt) {}

    public record Word(String text, BigDecimal amount /* null: not an amount */, Set<Long> alsoMoveIds /* may be empty */) {}
    public record Cursor(LocalDate date, Long moveId /* null: every move on that date still comes */) {}
    public record Query(String direction /* "IN" | "OUT" */, Set<String> moveTypes /* subset of the direction's two */,
                        List<Word> words, LocalDate from, LocalDate to,
                        Set<Long> excludeMoveIds, Set<Long> onlyMoveIds /* null: no restriction */,
                        Cursor after /* null: first page */, int limit /* 1..100 */) {}
    /** {@code name}, {@code ref} and {@code partner} are "" when Odoo has none. */
    public record Move(long id, String moveType, String state, String paymentState /* null if field absent */,
                       String name, String ref, String origin /* invoice_origin; null when absent or empty */,
                       Long partnerId, String partner, LocalDate date /* accounting date, sort key */,
                       LocalDate invoiceDate /* null */, LocalDate dueDate /* null */, String currency,
                       BigDecimal amountTotal /* >= 0 */, BigDecimal amountTotalEur /* >= 0, null if unknown */,
                       String peppolState /* null */, List<OdooDocumentService.Attachment> attachments) {}
    public record Page(List<Move> moves /* <= limit */, boolean more) {}

    /** null when ready, else DISABLED / NOT_CONFIGURED / INVALID_CONFIGURATION. Never throws. */
    public String problem() {
        try { return config.problem(); }
        catch(RuntimeException invalid) { return "INVALID_CONFIGURATION"; }
    }

    /** The configured source namespace; throws OdooFailure when not ready. */
    public String namespace() { return config.sourceNamespace(); }

    /** Throws OdooFailure (UNSUPPORTED_SCHEMA, INVALID_RESPONSE, AUTH_FAILED, RATE_LIMITED, UNAVAILABLE, ...). */
    public Page search(Query query) {
        validate(query);
        config.requireReady();
        // Nothing can match: answer without calling Odoo (a start date may raise "from" past "to").
        if(query.onlyMoveIds()!=null&&query.onlyMoveIds().isEmpty()
                ||query.from()!=null&&query.to()!=null&&query.from().isAfter(query.to()))return new Page(List.of(),false);
        Set<String> available=accountMoveFields();
        List<String> fields=fields(available);
        List<Object> domain=domain(query,available.contains("invoice_origin"));
        JsonNode rows=client.search("account.move",domain,fields,query.limit()+1,"date desc, id desc");
        List<JsonNode> selected=new ArrayList<>();
        LocalDate previousDate=null;long previousId=0;
        for(JsonNode row:rows) {
            long id=positiveId(row.path("id"));
            LocalDate date=requiredDate(row);
            if(previousDate!=null&&!(date.isBefore(previousDate)||date.equals(previousDate)&&id<previousId))
                throw new OdooFailure("INVALID_RESPONSE");
            if(!afterCursor(query.after(),date,id)||!query.moveTypes().contains(text(row,"move_type")))
                throw new OdooFailure("INVALID_RESPONSE");
            previousDate=date;previousId=id;
            if(selected.size()<query.limit())selected.add(row);
        }
        List<Long> ids=selected.stream().map(row->positiveId(row.path("id"))).toList();
        Map<Long,List<OdooDocumentService.Attachment>> attachments=attachments(ids);
        List<Move> moves=new ArrayList<>();
        for(JsonNode row:selected)moves.add(move(row,available,attachments));
        return new Page(List.copyOf(moves),rows.size()>query.limit());
    }

    /** One move of the configured company, any state incl. cancel; null when Odoo has no such invoice move. */
    public Move find(long moveId) {
        if(moveId<=0)throw new OdooFailure("INVALID_REQUEST");
        config.requireReady();
        Set<String> available=accountMoveFields();
        JsonNode rows=client.search("account.move",List.of(List.of("id","=",moveId),List.of("company_id","=",config.companyId),
                List.of("move_type","in",OdooDocumentService.INVOICE_TYPES)),fields(available),1,"id asc");
        if(rows.isEmpty())return null;
        JsonNode row=rows.get(0);
        if(positiveId(row.path("id"))!=moveId||!OdooDocumentService.INVOICE_TYPES.contains(text(row,"move_type")))
            throw new OdooFailure("INVALID_RESPONSE");
        return move(row,available,attachments(List.of(moveId)));
    }

    private static void validate(Query query) {
        if(query==null||query.direction()==null||!DIRECTION_TYPES.containsKey(query.direction())
                ||query.moveTypes()==null||query.moveTypes().isEmpty()
                ||!DIRECTION_TYPES.get(query.direction()).containsAll(query.moveTypes())
                ||query.limit()<1||query.limit()>100||query.words()==null
                ||query.after()!=null&&query.after().date()==null)
            throw new OdooFailure("INVALID_REQUEST");
        for(Word word:query.words())
            if(word==null||word.text()==null||word.text().isBlank())throw new OdooFailure("INVALID_REQUEST");
    }

    /** fields_get of account.move, read at most once per 10 minutes. */
    private Set<String> accountMoveFields() {
        Instant now=clock.instant();
        FieldsRead cached=moveFields.get();
        if(cached!=null&&now.isBefore(cached.readAt().plus(FIELDS_TTL)))return cached.fields();
        Set<String> fields=client.fields("account.move");
        moveFields.set(new FieldsRead(fields,now));
        return fields;
    }

    private static List<String> fields(Set<String> available) {
        if(!available.containsAll(REQUIRED_FIELDS))throw new OdooFailure("UNSUPPORTED_SCHEMA");
        List<String> fields=new ArrayList<>(REQUIRED_FIELDS);
        for(String optional:OPTIONAL_FIELDS)if(available.contains(optional))fields.add(optional);
        return List.copyOf(fields);
    }

    /** Prefix-notation domain; top-level terms are AND-ed, every OR group carries its n-1 leading "|". */
    List<Object> domain(Query query,boolean withOrigin) {
        List<Object> domain=new ArrayList<>();
        domain.add(List.of("company_id","=",config.companyId));
        domain.add(List.of("move_type","in",sorted(query.moveTypes())));
        domain.add(List.of("state","!=","cancel"));
        if(query.from()!=null)domain.add(List.of("date",">=",query.from().toString()));
        if(query.to()!=null)domain.add(List.of("date","<=",query.to().toString()));
        if(query.excludeMoveIds()!=null&&!query.excludeMoveIds().isEmpty())
            domain.add(List.of("id","not in",sorted(query.excludeMoveIds())));
        if(query.onlyMoveIds()!=null)domain.add(List.of("id","in",sorted(query.onlyMoveIds())));
        Cursor after=query.after();
        if(after!=null) {
            String day=after.date().toString();
            if(after.moveId()==null)domain.add(List.of("date","<=",day));
            else domain.addAll(List.of("|",List.of("date","<",day),"&",List.of("date","=",day),List.of("id","<",after.moveId())));
        }
        for(Word word:query.words()) {
            List<List<Object>> terms=new ArrayList<>();
            terms.add(List.of(List.of("name","ilike",word.text())));
            terms.add(List.of(List.of("ref","ilike",word.text())));
            terms.add(List.of(List.of("partner_id","ilike",word.text())));
            if(withOrigin)terms.add(List.of(List.of("invoice_origin","ilike",word.text())));
            if(word.amount()!=null)terms.add(List.of("&",List.of("amount_total",">=",word.amount().subtract(CENT)),
                    List.of("amount_total","<=",word.amount().add(CENT))));
            if(word.alsoMoveIds()!=null&&!word.alsoMoveIds().isEmpty())
                terms.add(List.of(List.of("id","in",sorted(word.alsoMoveIds()))));
            for(int i=1;i<terms.size();i++)domain.add("|");
            for(List<Object> term:terms)domain.addAll(term);
        }
        return domain;
    }

    private static <T extends Comparable<T>> List<T> sorted(Collection<T> values) {
        List<T> list=new ArrayList<>(values);Collections.sort(list);return List.copyOf(list);
    }

    private static boolean afterCursor(Cursor after,LocalDate date,long id) {
        if(after==null)return true;
        if(after.moveId()==null)return !date.isAfter(after.date());
        return date.isBefore(after.date())||date.equals(after.date())&&id<after.moveId();
    }

    private Move move(JsonNode row,Set<String> available,Map<Long,List<OdooDocumentService.Attachment>> attachments) {
        long id=positiveId(row.path("id"));
        if(!row.path("amount_total").isNumber())throw new OdooFailure("INVALID_RESPONSE");
        BigDecimal total=row.path("amount_total").decimalValue().abs().setScale(2,RoundingMode.HALF_UP);
        String currency=relationName(row,"currency_id");
        BigDecimal totalEur=null;
        if("EUR".equals(currency))totalEur=total;
        else if(available.contains("amount_total_signed")&&row.path("amount_total_signed").isNumber())
            totalEur=row.path("amount_total_signed").decimalValue().abs().setScale(2,RoundingMode.HALF_UP);
        Long partnerId=row.path("partner_id").isArray()?relationId(row.path("partner_id")):null;
        String paymentState=available.contains("payment_state")?optionalText(row,"payment_state"):null;
        String origin=available.contains("invoice_origin")?optionalText(row,"invoice_origin"):null;
        String peppol=available.contains("peppol_move_state")?optionalText(row,"peppol_move_state"):null;
        LocalDate due=available.contains("invoice_date_due")?optionalDate(row,"invoice_date_due"):null;
        return new Move(id,text(row,"move_type"),text(row,"state"),paymentState,text(row,"name"),text(row,"ref"),origin,
                partnerId,relationName(row,"partner_id"),requiredDate(row),optionalDate(row,"invoice_date"),due,currency,
                total,totalEur,peppol,List.copyOf(attachments.getOrDefault(id,List.of())));
    }

    /** Same domain and loop as OdooDocumentService.attachments (private there): metadata only, cursor pages of 200. */
    private Map<Long,List<OdooDocumentService.Attachment>> attachments(List<Long> ids) {
        Map<Long,List<OdooDocumentService.Attachment>> result=new HashMap<>();
        if(ids.isEmpty())return result;
        Set<Long> wanted=Set.copyOf(ids);
        long cursor=0;
        while(true) {
            List<Object> domain=attachmentDomain(ids);domain.add(List.of("id",">",cursor));
            JsonNode rows=client.search("ir.attachment",domain,ATTACHMENT_FIELDS,ATTACHMENT_PAGE,"id asc");
            if(rows.isEmpty())break;
            for(JsonNode row:rows) {
                long id=positiveId(row.path("id")),moveId=positiveId(row.path("res_id"));
                if(id<=cursor||!wanted.contains(moveId))throw new OdooFailure("INVALID_RESPONSE");
                cursor=id;
                if(!text(row,"type").equals("binary"))continue;
                result.computeIfAbsent(moveId,ignored->new ArrayList<>()).add(OdooDocumentService.attachment(row));
            }
            if(rows.size()<ATTACHMENT_PAGE)break;
        }
        return result;
    }

    private static List<Object> attachmentDomain(List<Long> ids) {
        // Explicitly mention res_field: Odoo normally hides binary-field attachments in generic searches.
        return new ArrayList<>(List.of(List.of("res_model","=","account.move"),List.of("res_id","in",ids),
                List.of("type","=","binary"),"|",List.of("res_field","=",false),List.of("res_field","!=",false)));
    }

    private static LocalDate requiredDate(JsonNode row) {
        LocalDate date=optionalDate(row,"date");
        if(date==null)throw new OdooFailure("INVALID_RESPONSE");
        return date;
    }

    private static LocalDate optionalDate(JsonNode row,String field) {
        String value=text(row,field).strip();
        if(value.isEmpty())return null;
        try { return LocalDate.parse(value); }
        catch(Exception invalid) { throw new OdooFailure("INVALID_RESPONSE"); }
    }

    private static String optionalText(JsonNode row,String field) {
        String value=text(row,field).strip();
        return value.isEmpty()?null:value;
    }

    /** Re-implemented here: OdooDocumentService.relationName is private. */
    private static String relationName(JsonNode row,String field) {
        return row.path(field).isArray()?row.path(field).path(1).asText(""):"";
    }
}
