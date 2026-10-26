package be.enrosed.finance.invoices;

import be.enrosed.finance.odoo.OdooDocumentService;
import be.enrosed.finance.odoo.OdooFailure;
import be.enrosed.finance.odoo.OdooInvoiceSearch;
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import jakarta.enterprise.context.ApplicationScoped;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Seven fixed Odoo invoices for local dev and Railway test, where no Odoo exists. Only used by {@link OdooInvoices} when
 * {@code enrosed.invoice-finder.odoo-demo=true} AND Odoo is not configured, so it can never show next to a real Odoo.
 * Search, cursor, exclude/only and attachments behave as the live search; the PDFs say they are no real invoice.
 */
@ApplicationScoped
public class OdooInvoiceDemo {
    public static final String NAMESPACE="demo";
    private static final Map<String,Set<String>> DIRECTION_TYPES=Map.of(
            "IN",Set.of("in_invoice","in_refund"),"OUT",Set.of("out_invoice","out_refund"));
    private static final BigDecimal CENT=new BigDecimal("0.01");
    private static final Locale DUTCH=Locale.forLanguageTag("nl-BE");

    private record File(long id, String kind /* PDF | XML */, boolean embeddedPdf) {}
    private record Row(long id, String moveType, String state, String paymentState, String partner, long partnerId,
                       String ref, LocalDate date, LocalDate invoiceDate, String currency, BigDecimal amount,
                       String peppolState, List<File> files) {
        String name() { return moveType.startsWith("out_")?"INV/2026/00412":"BILL/2026/10/00"+(id-900000); }
        BigDecimal amountEur() { return currency.equals("EUR")?amount:null; }
        String fileName(File file) { return ref+(file.kind().equals("PDF")?".pdf":".xml"); }
    }

    private static final List<Row> ROWS=List.of(
            row(900001,"in_invoice","posted","not_paid","Verhoeven Transport BV",91001,"VT-2026-0412","2026-10-02",null,"EUR","1250.00","done",
                    "PDF","XML+PDF"),
            row(900002,"in_invoice","draft","not_paid","24/7 Customs BV",91002,"247-88123","2026-10-08",null,"EUR","186.40","done",
                    "XML+PDF"),
            row(900003,"in_invoice","posted","paid","Proximus NV",91003,"PX-551203","2026-09-30","2026-09-28","EUR","72.60",null,
                    "PDF"),
            row(900004,"in_refund","posted","not_paid","Verhoeven Transport BV",91001,"VT-CN-0031","2026-10-05",null,"EUR","125.00",null,
                    "PDF"),
            row(900005,"in_invoice","posted","not_paid","Linc BV",91004,"LINC-2026-10","2026-10-01",null,"EUR","302.50",null,
                    "XML"),
            row(900006,"in_invoice","posted","not_paid","Ningbo Freight Co",91005,"NF-7781","2026-09-26",null,"USD","840.00",null,
                    "PDF"),
            row(900007,"out_invoice","posted","not_paid","Bloemen De Smet",91006,"INV/2026/00412","2026-10-03",null,"EUR","1452.00",null,
                    "PDF"));

    private final Map<Long,byte[]> pdfs=new ConcurrentHashMap<>();
    private final Map<Long,byte[]> xmls=new ConcurrentHashMap<>();

    private static Row row(long id,String type,String state,String payment,String partner,long partnerId,String ref,String date,
                           String invoiceDate,String currency,String amount,String peppol,String... files) {
        List<File> list=new ArrayList<>();
        for(int i=0;i<files.length;i++) {
            String kind=files[i].startsWith("XML")?"XML":"PDF";
            list.add(new File(id*10+i+1,kind,files[i].equals("XML+PDF")));
        }
        LocalDate day=LocalDate.parse(date);
        return new Row(id,type,state,payment,partner,partnerId,ref,day,invoiceDate==null?day:LocalDate.parse(invoiceDate),
                currency,new BigDecimal(amount),peppol,List.copyOf(list));
    }

    /** Same semantics as {@link OdooInvoiceSearch#search}: filters, cursor, words, order "date desc, id desc", limit + 1. */
    public OdooInvoiceSearch.Page search(OdooInvoiceSearch.Query query) {
        if(query==null||query.direction()==null||!DIRECTION_TYPES.containsKey(query.direction())
                ||query.moveTypes()==null||query.moveTypes().isEmpty()
                ||!DIRECTION_TYPES.get(query.direction()).containsAll(query.moveTypes())
                ||query.limit()<1||query.limit()>100||query.words()==null
                ||query.after()!=null&&query.after().date()==null
                ||query.words().stream().anyMatch(word->word==null||word.text()==null||word.text().isBlank()))
            throw new OdooFailure("INVALID_REQUEST");
        if(query.onlyMoveIds()!=null&&query.onlyMoveIds().isEmpty())return new OdooInvoiceSearch.Page(List.of(),false);
        List<Row> hits=ROWS.stream()
                .filter(row->query.moveTypes().contains(row.moveType())&&!row.state().equals("cancel"))
                .filter(row->query.from()==null||!row.date().isBefore(query.from()))
                .filter(row->query.to()==null||!row.date().isAfter(query.to()))
                .filter(row->query.excludeMoveIds()==null||!query.excludeMoveIds().contains(row.id()))
                .filter(row->query.onlyMoveIds()==null||query.onlyMoveIds().contains(row.id()))
                .filter(row->after(query.after(),row))
                .filter(row->query.words().stream().allMatch(word->matches(word,row)))
                .sorted(Comparator.comparing(Row::date).reversed().thenComparing(Comparator.comparingLong(Row::id).reversed()))
                .limit(query.limit()+1L).toList();
        List<OdooInvoiceSearch.Move> moves=hits.stream().limit(query.limit()).map(this::move).toList();
        return new OdooInvoiceSearch.Page(moves,hits.size()>query.limit());
    }

    /** One demo move (any state); null when it is not one of the seven. */
    public OdooInvoiceSearch.Move find(long moveId) {
        if(moveId<=0)throw new OdooFailure("INVALID_REQUEST");
        return ROWS.stream().filter(row->row.id()==moveId).findFirst().map(this::move).orElse(null);
    }

    /** The demo file's bytes, as {@link OdooDocumentService#download} answers for a real one. */
    public OdooDocumentService.Download attachment(long moveId,long attachmentId) {
        if(moveId<=0||attachmentId<=0)throw new OdooFailure("INVALID_REQUEST");
        Row row=ROWS.stream().filter(candidate->candidate.id()==moveId).findFirst().orElseThrow(()->new OdooFailure("NOT_FOUND"));
        File file=row.files().stream().filter(candidate->candidate.id()==attachmentId).findFirst()
                .orElseThrow(()->new OdooFailure("NOT_FOUND"));
        return new OdooDocumentService.Download(row.fileName(file),mime(file),bytes(row,file));
    }

    private static boolean after(OdooInvoiceSearch.Cursor cursor,Row row) {
        if(cursor==null)return true;
        if(cursor.moveId()==null)return !row.date().isAfter(cursor.date());
        return row.date().isBefore(cursor.date())||row.date().equals(cursor.date())&&row.id()<cursor.moveId();
    }

    /** One OR group of the live domain: name, ref, partner, origin (ilike), amount ± 0.01, or one of the extra ids. */
    private static boolean matches(OdooInvoiceSearch.Word word,Row row) {
        String needle=word.text().toLowerCase(Locale.ROOT);
        if((contains(row.name(),needle)||contains(row.ref(),needle)||contains(row.partner(),needle)))return true;
        if(word.amount()!=null&&row.amount().subtract(word.amount()).abs().compareTo(CENT)<=0)return true;
        return word.alsoMoveIds()!=null&&word.alsoMoveIds().contains(row.id());
    }

    private static boolean contains(String haystack,String needle) {
        return haystack!=null&&haystack.toLowerCase(Locale.ROOT).contains(needle);
    }

    private OdooInvoiceSearch.Move move(Row row) {
        List<OdooDocumentService.Attachment> attachments=row.files().stream()
                .map(file->new OdooDocumentService.Attachment(file.id(),row.fileName(file),mime(file),bytes(row,file).length,file.kind()))
                .toList();
        return new OdooInvoiceSearch.Move(row.id(),row.moveType(),row.state(),row.paymentState(),row.name(),row.ref(),null,
                row.partnerId(),row.partner(),row.date(),row.invoiceDate(),row.date().plusDays(30),row.currency(),
                row.amount(),row.amountEur(),row.peppolState(),attachments);
    }

    private static String mime(File file) { return file.kind().equals("PDF")?"application/pdf":"application/xml"; }

    private byte[] bytes(Row row,File file) {
        return file.kind().equals("PDF")?pdf(row):xmls.computeIfAbsent(row.id(),ignored->xml(row,file.embeddedPdf()));
    }

    /** Rendered once per move and kept for the life of the process. */
    private byte[] pdf(Row row) { return pdfs.computeIfAbsent(row.id(),ignored->render(row)); }

    private static byte[] render(Row row) {
        String html="<html><head><style>"
                +"@page{size:A4;margin:22mm} body{font-family:sans-serif;font-size:11pt;color:#222}"
                +"h1{font-size:17pt;margin:0 0 4pt 0} p{color:#888;margin:0 0 18pt 0} td{padding:3pt 18pt 3pt 0}"
                +"</style></head><body>"
                +"<h1>Voorbeeldfactuur — geen echte factuur</h1>"
                +"<p>Demo van de Odoo-koppeling in ENROSED.</p>"
                +"<table>"
                +cell("Partij",row.partner())+cell("Referentie",row.ref())+cell("Nummer",row.name())
                +cell("Datum",row.invoiceDate().format(DateTimeFormatter.ofPattern("dd/MM/yyyy")))
                +cell("Bedrag",row.currency()+" "+String.format(DUTCH,"%,.2f",row.amount()))
                +"</table></body></html>";
        try(ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            PdfRendererBuilder builder=new PdfRendererBuilder();
            builder.useFastMode();
            builder.withHtmlContent(html,null);
            builder.toStream(out);
            builder.run();
            return out.toByteArray();
        } catch(Exception failed) {
            throw new IllegalStateException("Could not render the demo invoice PDF",failed);
        }
    }

    private static String cell(String label,String value) { return "<tr><td>"+label+"</td><td>"+escape(value)+"</td></tr>"; }

    /** A minimal UBL invoice; with {@code embedded} it carries the move's PDF in base64 (with line breaks, as Peppol files do). */
    private byte[] xml(Row row,boolean embedded) {
        String amount=row.amount().toPlainString();
        StringBuilder xml=new StringBuilder()
                .append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
                .append("<Invoice xmlns=\"urn:oasis:names:specification:ubl:schema:xsd:Invoice-2\"")
                .append(" xmlns:cac=\"urn:oasis:names:specification:ubl:schema:xsd:CommonAggregateComponents-2\"")
                .append(" xmlns:cbc=\"urn:oasis:names:specification:ubl:schema:xsd:CommonBasicComponents-2\">\n")
                .append("  <cbc:ID>").append(escape(row.ref())).append("</cbc:ID>\n")
                .append("  <cbc:IssueDate>").append(row.invoiceDate()).append("</cbc:IssueDate>\n")
                .append("  <cbc:DueDate>").append(row.date().plusDays(30)).append("</cbc:DueDate>\n")
                .append("  <cbc:Note>Voorbeeldfactuur - geen echte factuur</cbc:Note>\n")
                .append("  <cbc:DocumentCurrencyCode>").append(row.currency()).append("</cbc:DocumentCurrencyCode>\n");
        if(embedded) {
            xml.append("  <cac:AdditionalDocumentReference>\n")
                    .append("    <cbc:ID>").append(escape(row.ref())).append("</cbc:ID>\n")
                    .append("    <cac:Attachment>\n")
                    .append("      <cbc:EmbeddedDocumentBinaryObject mimeCode=\"application/pdf\" filename=\"")
                    .append(escape(row.ref())).append(".pdf\">\n")
                    .append(Base64.getMimeEncoder().encodeToString(pdf(row))).append("\n")
                    .append("      </cbc:EmbeddedDocumentBinaryObject>\n")
                    .append("    </cac:Attachment>\n")
                    .append("  </cac:AdditionalDocumentReference>\n");
        }
        xml.append("  <cac:AccountingSupplierParty><cac:Party><cac:PartyName><cbc:Name>").append(escape(row.partner()))
                .append("</cbc:Name></cac:PartyName></cac:Party></cac:AccountingSupplierParty>\n")
                .append("  <cac:LegalMonetaryTotal><cbc:PayableAmount currencyID=\"").append(row.currency()).append("\">")
                .append(amount).append("</cbc:PayableAmount></cac:LegalMonetaryTotal>\n")
                .append("</Invoice>\n");
        return xml.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static String escape(String value) {
        return value.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;");
    }
}
