package be.enrosed.finance.odoo;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.*;
import static be.enrosed.finance.odoo.OdooBankMapper.*;

/** Read-through invoice view. Odoo invoice and Peppol states are distinct; no local invoice is created or paid. */
@ApplicationScoped
@Transactional(Transactional.TxType.NOT_SUPPORTED)
public class OdooDocumentService {
    static final int MAX_ATTACHMENT_BYTES=15*1024*1024;
    static final List<String> INVOICE_TYPES=List.of("in_invoice","in_refund","out_invoice","out_refund");
    private static final List<String> DOCUMENT_FIELDS=List.of("id","name","ref","partner_id","invoice_date","move_type","state","currency_id","amount_total");
    private static final List<String> ATTACHMENT_FIELDS=List.of("id","res_id","name","mimetype","file_size","type");
    @Inject OdooClient client;
    @Inject OdooConfiguration config;
    public record Attachment(long id,String name,String mimeType,long size,String kind) {}
    public record Document(long id,String name,String reference,String partner,String date,String moveType,String state,
                           String currency,BigDecimal total,String peppolState,String peppolMessageId,List<Attachment> attachments) {}
    public record Page(List<Document> documents,long nextAfterId,boolean hasMore,boolean peppolSupported) {}
    public record Download(String name,String mimeType,byte[] bytes) {}
    public record PurchaseSnapshot(String namespace,long companyId,Document document,long partnerId,String partnerVat,
                                   String supplierCountry,BigDecimal netEur,BigDecimal vatEur,BigDecimal totalEur,
                                   String sourceUpdatedAt,String hash) {}

    /** Refresh financial facts from the configured company; list totals and filenames are never import authority. */
    public PurchaseSnapshot purchaseSnapshot(long id) {
        config.requireReady();if(id<=0)throw new OdooFailure("INVALID_REQUEST");
        Set<String> available=client.fields("account.move");
        List<String> fields=new ArrayList<>(DOCUMENT_FIELDS);
        fields.addAll(List.of("company_id","amount_untaxed","amount_tax","write_date"));
        if(!available.containsAll(fields))throw new OdooFailure("UNSUPPORTED_SCHEMA");
        if(available.contains("peppol_move_state"))fields.add("peppol_move_state");
        if(available.contains("peppol_message_uuid"))fields.add("peppol_message_uuid");
        JsonNode result=client.search("account.move",List.of(List.of("id","=",id),List.of("company_id","=",config.companyId),
                List.of("move_type","in",List.of("in_invoice","in_refund"))),fields,1,"id asc");
        if(result.size()!=1)throw new OdooFailure("NOT_FOUND");
        JsonNode row=result.get(0);
        if(positiveId(row.path("id"))!=id||relationId(row.path("company_id"))!=config.companyId)throw new OdooFailure("INVALID_RESPONSE");
        String type=text(row,"move_type"),state=text(row,"state");
        if(!Set.of("in_invoice","in_refund").contains(type)||!Set.of("draft","posted").contains(state))throw new OdooFailure("PURCHASE_DOCUMENT_UNAVAILABLE");
        if(!"EUR".equals(relationName(row,"currency_id")))throw new OdooFailure("PURCHASE_CURRENCY_UNSUPPORTED");
        String date=text(row,"invoice_date"),updated=text(row,"write_date");
        try { LocalDate.parse(date); } catch(Exception invalid) { throw new OdooFailure("INVALID_RESPONSE"); }
        if(updated.isBlank())throw new OdooFailure("INVALID_RESPONSE");
        BigDecimal net=cents(row,"amount_untaxed"),vat=cents(row,"amount_tax"),total=cents(row,"amount_total");
        if(net.signum()<0||vat.signum()<0||total.signum()<=0||net.add(vat).compareTo(total)!=0)throw new OdooFailure("INVALID_RESPONSE");
        long partnerId=relationId(row.path("partner_id"));
        JsonNode partners=client.search("res.partner",List.of(List.of("id","=",partnerId)),List.of("id","name","vat","country_id"),1,"id asc");
        if(partners.size()!=1||positiveId(partners.get(0).path("id"))!=partnerId)throw new OdooFailure("INVALID_RESPONSE");
        JsonNode partner=partners.get(0);String country=null;
        if(partner.path("country_id").isArray()) {
            long countryId=relationId(partner.path("country_id"));
            JsonNode countries=client.search("res.country",List.of(List.of("id","=",countryId)),List.of("id","code"),1,"id asc");
            if(countries.size()!=1||positiveId(countries.get(0).path("id"))!=countryId)throw new OdooFailure("INVALID_RESPONSE");
            country=text(countries.get(0),"code").toUpperCase(Locale.ROOT);
            if(!country.matches("[A-Z]{2}"))throw new OdooFailure("INVALID_RESPONSE");
        }
        Document document=new Document(id,text(row,"name"),text(row,"ref"),text(partner,"name"),date,type,state,"EUR",total,
                optionalText(row,"peppol_move_state"),optionalText(row,"peppol_message_uuid"),List.copyOf(attachments(List.of(id)).getOrDefault(id,List.of())));
        if(document.partner().isBlank())throw new OdooFailure("INVALID_RESPONSE");
        if(type.equals("in_refund")) {net=net.negate();vat=vat.negate();total=total.negate();}
        String namespace=config.sourceNamespace(),partnerVat=text(partner,"vat");
        String hash=hash(String.join("\n",namespace,Long.toString(config.companyId),Long.toString(id),type,state,date,updated,
                Long.toString(partnerId),document.partner(),partnerVat,Objects.toString(country,""),document.name(),document.reference(),net.toPlainString(),vat.toPlainString(),total.toPlainString()));
        return new PurchaseSnapshot(namespace,config.companyId,document,partnerId,partnerVat,country,net,vat,total,updated,hash);
    }
    private static BigDecimal cents(JsonNode row,String field) {
        try { if(!row.path(field).isNumber())throw new IllegalArgumentException();return row.path(field).decimalValue().setScale(2,RoundingMode.UNNECESSARY); }
        catch(Exception invalid) {throw new OdooFailure("INVALID_RESPONSE");}
    }
    private static String hash(String value) {
        try {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}
        catch(Exception impossible) {throw new IllegalStateException(impossible);}
    }
    public Page documents(String direction,long afterId,int limit) {
        config.requireReady();
        if(!Set.of("incoming","outgoing").contains(direction)||afterId<0||limit<1||limit>100)throw new OdooFailure("INVALID_REQUEST");
        Set<String> available=client.fields("account.move");
        if(!available.containsAll(DOCUMENT_FIELDS))throw new OdooFailure("UNSUPPORTED_SCHEMA");
        List<String> fields=new ArrayList<>(DOCUMENT_FIELDS);
        if(available.contains("peppol_move_state"))fields.add("peppol_move_state");
        if(available.contains("peppol_message_uuid"))fields.add("peppol_message_uuid");
        List<Object> domain=new ArrayList<>(List.of(List.of("company_id","=",config.companyId),
                List.of("move_type","in",direction.equals("incoming")?List.of("in_invoice","in_refund"):List.of("out_invoice","out_refund"))));
        // Descending keyset pagination displays the newest Odoo records first, with no offset races.
        if(afterId>0)domain.add(List.of("id","<",afterId));
        JsonNode rows=client.search("account.move",domain,fields,limit+1,"id desc");
        List<JsonNode> selected=new ArrayList<>();long previous=afterId==0?Long.MAX_VALUE:afterId;
        for(JsonNode row:rows) {
            long id=positiveId(row.path("id"));
            if(id>=previous)throw new OdooFailure("INVALID_RESPONSE");previous=id;
            if(selected.size()<limit)selected.add(row);
        }
        Map<Long,List<Attachment>> attachments=attachments(selected.stream().map(row->positiveId(row.path("id"))).toList());
        List<Document> documents=new ArrayList<>();
        for(JsonNode row:selected) {
            long id=positiveId(row.path("id"));
            if(!row.path("amount_total").isNumber())throw new OdooFailure("INVALID_RESPONSE");
            documents.add(new Document(id,text(row,"name"),text(row,"ref"),relationName(row,"partner_id"),
                    text(row,"invoice_date"),text(row,"move_type"),text(row,"state"),relationName(row,"currency_id"),
                    row.path("amount_total").decimalValue(),optionalText(row,"peppol_move_state"),
                    optionalText(row,"peppol_message_uuid"),List.copyOf(attachments.getOrDefault(id,List.of()))));
        }
        return new Page(List.copyOf(documents),documents.isEmpty()?0:documents.getLast().id(),rows.size()>limit,available.contains("peppol_move_state"));
    }
    private Map<Long,List<Attachment>> attachments(List<Long> ids) {
        Map<Long,List<Attachment>> result=new HashMap<>();if(ids.isEmpty())return result;
        long cursor=0;
        while(true) {
            List<Object> domain=attachmentDomain(ids);domain.add(List.of("id",">",cursor));
            JsonNode rows=client.search("ir.attachment",domain,ATTACHMENT_FIELDS,200,"id asc");
            if(rows.isEmpty())break;
            for(JsonNode row:rows) {
                long id=positiveId(row.path("id")),documentId=positiveId(row.path("res_id"));
                if(id<=cursor||!ids.contains(documentId))throw new OdooFailure("INVALID_RESPONSE");cursor=id;
                if(!text(row,"type").equals("binary"))continue;
                result.computeIfAbsent(documentId,ignored->new ArrayList<>()).add(attachment(row));
            }
        }
        return result;
    }
    private List<Object> attachmentDomain(List<Long> ids) {
        // Explicitly mention res_field: Odoo normally hides binary-field attachments in generic searches.
        return new ArrayList<>(List.of(List.of("res_model","=","account.move"),List.of("res_id","in",ids),
                List.of("type","=","binary"),"|",List.of("res_field","=",false),List.of("res_field","!=",false)));
    }
    public Download download(long documentId,long attachmentId) {
        config.requireReady();if(documentId<=0||attachmentId<=0)throw new OdooFailure("INVALID_REQUEST");
        JsonNode document=client.search("account.move",List.of(List.of("id","=",documentId),
                List.of("company_id","=",config.companyId),List.of("move_type","in",INVOICE_TYPES)),List.of("id"),1,"id asc");
        if(document.size()!=1||positiveId(document.get(0).path("id"))!=documentId)throw new OdooFailure("NOT_FOUND");
        List<Object> domain=attachmentDomain(List.of(documentId));domain.add(List.of("id","=",attachmentId));
        JsonNode metadata=client.search("ir.attachment",domain,ATTACHMENT_FIELDS,1,"id asc");
        if(metadata.size()!=1)throw new OdooFailure("NOT_FOUND");
        Attachment attachment=attachment(metadata.get(0));
        if(attachment.id()!=attachmentId||positiveId(metadata.get(0).path("res_id"))!=documentId)throw new OdooFailure("INVALID_RESPONSE");
        if(attachment.size()>MAX_ATTACHMENT_BYTES)throw new OdooFailure("ATTACHMENT_TOO_LARGE");
        Set<String> available=client.fields("ir.attachment");
        // JSON-2 serializes both current raw and legacy datas binary fields as base64 strings.
        String binaryField=available.contains("raw")?"raw":available.contains("datas")?"datas":null;
        if(binaryField==null)throw new OdooFailure("UNSUPPORTED_SCHEMA");
        JsonNode data=client.search("ir.attachment",domain,List.of("id","res_id",binaryField),1,"id asc");
        if(data.size()!=1||positiveId(data.get(0).path("id"))!=attachmentId
                ||positiveId(data.get(0).path("res_id"))!=documentId)throw new OdooFailure("NOT_FOUND");
        String encoded=text(data.get(0),binaryField);
        if(encoded.length()>(MAX_ATTACHMENT_BYTES+2L)/3*4)throw new OdooFailure("ATTACHMENT_TOO_LARGE");
        try {
            byte[] bytes=Base64.getDecoder().decode(encoded);
            if(bytes.length>MAX_ATTACHMENT_BYTES)throw new OdooFailure("ATTACHMENT_TOO_LARGE");
            if(bytes.length==0)throw new OdooFailure("NOT_FOUND");
            String mime=switch(attachment.kind()) {case "PDF"->"application/pdf";case "XML"->"application/xml";default->"application/octet-stream";};
            return new Download(attachment.name(),mime,bytes);
        } catch(IllegalArgumentException invalid) { throw new OdooFailure("INVALID_RESPONSE"); }
    }
    static Attachment attachment(JsonNode row) {
        String name=limit(text(row,"name"),200),mime=limit(text(row,"mimetype"),100),lower=name.toLowerCase(Locale.ROOT);
        if(!row.path("file_size").isIntegralNumber()||row.path("file_size").longValue()<0)throw new OdooFailure("INVALID_RESPONSE");
        String kind=mime.equals("application/pdf")||lower.endsWith(".pdf")?"PDF":mime.contains("xml")||lower.endsWith(".xml")?"XML":"FILE";
        return new Attachment(positiveId(row.path("id")),name,mime,row.path("file_size").longValue(),kind);
    }
    private static String optionalText(JsonNode row,String name) { String value=text(row,name);return value.isBlank()?null:value; }
    private static String relationName(JsonNode row,String name) { return row.path(name).isArray()?row.path(name).path(1).asText(""):""; }
}
