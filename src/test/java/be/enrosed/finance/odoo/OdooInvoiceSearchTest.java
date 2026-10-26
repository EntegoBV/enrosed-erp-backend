package be.enrosed.finance.odoo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OdooInvoiceSearchTest {
    final ObjectMapper json=new ObjectMapper();
    static final Set<String> ALL_FIELDS=Set.of("id","name","ref","partner_id","date","invoice_date","move_type","state",
            "currency_id","amount_total","invoice_date_due","payment_state","amount_total_signed","peppol_move_state",
            "invoice_origin","company_id");
    static final Set<String> NO_OPTIONAL=Set.of("id","name","ref","partner_id","date","invoice_date","move_type","state",
            "currency_id","amount_total");

    OdooInvoiceSearch service(Set<String> fields) {
        var service=new OdooInvoiceSearch();
        service.config=OdooClientTest.config();
        service.client=mock(OdooClient.class);
        when(service.client.fields("account.move")).thenReturn(fields);
        when(service.client.search(eq("ir.attachment"),anyList(),anyList(),anyInt(),anyString())).thenReturn(json.createArrayNode());
        return service;
    }
    static OdooInvoiceSearch.Query query(String direction,Set<String> types,List<OdooInvoiceSearch.Word> words,LocalDate from,LocalDate to,
                                         Set<Long> exclude,Set<Long> only,OdooInvoiceSearch.Cursor after,int limit) {
        return new OdooInvoiceSearch.Query(direction,types,words,from,to,exclude,only,after,limit);
    }
    static OdooInvoiceSearch.Query incoming(int limit) {
        return query("IN",Set.of("in_invoice","in_refund"),List.of(),null,null,Set.of(),null,null,limit);
    }
    ObjectNode row(long id,String type,String date) {
        ObjectNode row=json.createObjectNode();
        row.put("id",id).put("name","BILL/2026/10/"+id).put("ref","REF-"+id).put("date",date).put("invoice_date",date)
                .put("move_type",type).put("state","posted").put("amount_total",1250.0);
        row.putArray("partner_id").add(5).add("Verhoeven Transport BV");
        row.putArray("currency_id").add(1).add("EUR");
        return row;
    }
    ArrayNode rows(JsonNode... rows) { ArrayNode array=json.createArrayNode();for(JsonNode row:rows)array.add(row);return array; }
    void answer(OdooInvoiceSearch service,ArrayNode rows) {
        when(service.client.search(eq("account.move"),anyList(),anyList(),anyInt(),anyString())).thenReturn(rows);
    }
    @SuppressWarnings({"unchecked","rawtypes"})
    List<Object> domainOf(OdooInvoiceSearch service) {
        ArgumentCaptor<List> domain=ArgumentCaptor.forClass(List.class);
        verify(service.client).search(eq("account.move"),domain.capture(),anyList(),anyInt(),anyString());
        return domain.getValue();
    }
    @SuppressWarnings({"unchecked","rawtypes"})
    List<String> fieldsOf(OdooInvoiceSearch service) {
        ArgumentCaptor<List> fields=ArgumentCaptor.forClass(List.class);
        verify(service.client).search(eq("account.move"),anyList(),fields.capture(),anyInt(),anyString());
        return fields.getValue();
    }

    @Test void theDomainHoldsTheCompanyTheMoveTypesNoCancelledMovesAndThePeriodOnTheAccountingDate() {
        var service=service(ALL_FIELDS);answer(service,rows());
        service.search(query("IN",Set.of("in_refund","in_invoice"),List.of(),LocalDate.of(2026,10,1),LocalDate.of(2026,10,31),
                Set.of(),null,null,50));
        var domain=domainOf(service);
        assertEquals(List.of(List.of("company_id","=",7L),List.of("move_type","in",List.of("in_invoice","in_refund")),
                List.of("state","!=","cancel"),List.of("date",">=","2026-10-01"),List.of("date","<=","2026-10-31")),domain);
        verify(service.client).search(eq("account.move"),anyList(),anyList(),eq(51),eq("date desc, id desc"));
    }

    @Test void aCursorWithoutAnIdKeepsTheWholeDayAndOneWithAnIdUsesTheOrForm() {
        var day=LocalDate.of(2026,10,2);
        var whole=service(ALL_FIELDS);answer(whole,rows());
        whole.search(query("OUT",Set.of("out_invoice"),List.of(),null,null,Set.of(),null,new OdooInvoiceSearch.Cursor(day,null),10));
        assertEquals(List.of("date","<=","2026-10-02"),domainOf(whole).getLast());

        var keyed=service(ALL_FIELDS);answer(keyed,rows());
        keyed.search(query("OUT",Set.of("out_invoice"),List.of(),null,null,Set.of(),null,new OdooInvoiceSearch.Cursor(day,900001L),10));
        var domain=domainOf(keyed);
        assertEquals(List.of("|",List.of("date","<","2026-10-02"),"&",List.of("date","=","2026-10-02"),List.of("id","<",900001L)),
                domain.subList(domain.size()-5,domain.size()));
    }

    @Test void excludedIdsBecomeNotInAndAnEmptyOnlySetAnswersWithoutCallingOdoo() {
        var service=service(ALL_FIELDS);answer(service,rows());
        service.search(query("IN",Set.of("in_invoice"),List.of(),null,null,Set.of(12L,3L),Set.of(40L),null,5));
        var domain=domainOf(service);
        assertTrue(domain.contains(List.of("id","not in",List.of(3L,12L))));
        assertTrue(domain.contains(List.of("id","in",List.of(40L))));

        var empty=service(ALL_FIELDS);
        var page=empty.search(query("IN",Set.of("in_invoice"),List.of(),null,null,Set.of(),Set.of(),null,5));
        assertTrue(page.moves().isEmpty());assertFalse(page.more());
        verifyNoInteractions(empty.client);

        var reversed=service(ALL_FIELDS);
        assertTrue(reversed.search(query("IN",Set.of("in_invoice"),List.of(),LocalDate.of(2026,10,2),LocalDate.of(2026,10,1),
                Set.of(),null,null,5)).moves().isEmpty());
        verifyNoInteractions(reversed.client);
    }

    @Test void everyWordIsOneOrGroupWithNMinusOneBarsAndAnAmountWindowOfOneCent() {
        var service=service(ALL_FIELDS);answer(service,rows());
        var amount=new OdooInvoiceSearch.Word("1.250,00",new BigDecimal("1250.00"),Set.of(77L,9L));
        var plain=new OdooInvoiceSearch.Word("VT",null,Set.of());
        service.search(query("IN",Set.of("in_invoice","in_refund"),List.of(amount,plain),null,null,Set.of(),null,null,50));
        var domain=domainOf(service);
        List<Object> expected=new ArrayList<>(List.of(List.of("company_id","=",7L),List.of("move_type","in",List.of("in_invoice","in_refund")),
                List.of("state","!=","cancel")));
        expected.addAll(List.of("|","|","|","|","|",
                List.of("name","ilike","1.250,00"),List.of("ref","ilike","1.250,00"),List.of("partner_id","ilike","1.250,00"),
                List.of("invoice_origin","ilike","1.250,00"),
                "&",List.of("amount_total",">=",new BigDecimal("1249.99")),List.of("amount_total","<=",new BigDecimal("1250.01")),
                List.of("id","in",List.of(9L,77L))));
        expected.addAll(List.of("|","|","|",List.of("name","ilike","VT"),List.of("ref","ilike","VT"),List.of("partner_id","ilike","VT"),
                List.of("invoice_origin","ilike","VT")));
        assertEquals(expected,domain);
    }

    @Test void invoiceOriginIsLeftOutOfDomainAndFieldsWhenOdooLacksItAndReadWhenPresent() {
        var without=service(NO_OPTIONAL);
        var bare=row(5,"in_invoice","2026-10-02");answer(without,rows(bare));
        var move=without.search(query("IN",Set.of("in_invoice"),List.of(new OdooInvoiceSearch.Word("VT",null,Set.of())),
                null,null,Set.of(),null,null,10)).moves().getFirst();
        assertFalse(domainOf(without).toString().contains("invoice_origin"));
        assertFalse(fieldsOf(without).contains("invoice_origin"));
        assertNull(move.origin());assertNull(move.paymentState(),"payment_state absent -> null");
        assertNull(move.dueDate());assertNull(move.peppolState());

        var with=service(ALL_FIELDS);
        var full=row(6,"out_refund","2026-10-03").put("invoice_origin","  F-2026-0041 ").put("payment_state","partial")
                .put("invoice_date_due","2026-11-02").put("peppol_move_state","done");
        answer(with,rows(full));
        var read=with.search(query("OUT",Set.of("out_refund"),List.of(),null,null,Set.of(),null,null,10)).moves().getFirst();
        assertTrue(fieldsOf(with).containsAll(List.of("invoice_origin","payment_state","invoice_date_due","amount_total_signed","peppol_move_state")));
        assertEquals("F-2026-0041",read.origin());assertEquals("partial",read.paymentState());
        assertEquals(LocalDate.of(2026,11,2),read.dueDate());assertEquals("done",read.peppolState());
    }

    @Test void oneRowMoreThanTheLimitMeansMoreAndOnlyTheLimitIsKept() {
        var service=service(ALL_FIELDS);
        answer(service,rows(row(30,"in_invoice","2026-10-05"),row(29,"in_invoice","2026-10-05"),row(31,"in_invoice","2026-10-04")));
        var page=service.search(incoming(2));
        assertTrue(page.more());
        assertEquals(List.of(30L,29L),page.moves().stream().map(OdooInvoiceSearch.Move::id).toList());
        verify(service.client).search(eq("account.move"),anyList(),anyList(),eq(3),eq("date desc, id desc"));

        var last=service(ALL_FIELDS);answer(last,rows(row(30,"in_invoice","2026-10-05")));
        assertFalse(last.search(incoming(2)).more());
    }

    @Test void rowsOutOfOrderOrOutsideTheRequestAreAnInvalidResponse() {
        var sameDay=service(ALL_FIELDS);answer(sameDay,rows(row(29,"in_invoice","2026-10-05"),row(30,"in_invoice","2026-10-05")));
        assertEquals("INVALID_RESPONSE",assertThrows(OdooFailure.class,()->sameDay.search(incoming(5))).code);
        var olderFirst=service(ALL_FIELDS);answer(olderFirst,rows(row(30,"in_invoice","2026-10-04"),row(29,"in_invoice","2026-10-05")));
        assertEquals("INVALID_RESPONSE",assertThrows(OdooFailure.class,()->olderFirst.search(incoming(5))).code);
        var otherType=service(ALL_FIELDS);answer(otherType,rows(row(30,"out_invoice","2026-10-04")));
        assertEquals("INVALID_RESPONSE",assertThrows(OdooFailure.class,()->otherType.search(incoming(5))).code);
        var beforeCursor=service(ALL_FIELDS);answer(beforeCursor,rows(row(30,"in_invoice","2026-10-05")));
        assertEquals("INVALID_RESPONSE",assertThrows(OdooFailure.class,()->beforeCursor.search(query("IN",Set.of("in_invoice"),List.of(),
                null,null,Set.of(),null,new OdooInvoiceSearch.Cursor(LocalDate.of(2026,10,5),30L),5))).code);
        var noDate=service(ALL_FIELDS);answer(noDate,rows(row(30,"in_invoice","2026-10-05").put("date",false)));
        assertEquals("INVALID_RESPONSE",assertThrows(OdooFailure.class,()->noDate.search(incoming(5))).code);
    }

    @Test void aMissingRequiredFieldIsAnUnsupportedSchema() {
        var fields=new HashSet<>(NO_OPTIONAL);fields.remove("date");
        var service=service(Set.copyOf(fields));
        assertEquals("UNSUPPORTED_SCHEMA",assertThrows(OdooFailure.class,()->service.search(incoming(5))).code);
        verify(service.client,never()).search(anyString(),anyList(),anyList(),anyInt(),anyString());
    }

    @Test void movesMapToMagnitudesTheirCurrencyEuroValueDatesAndPartner() {
        var service=service(ALL_FIELDS);
        var usd=row(40,"in_refund","2026-09-26").put("amount_total",-840.0).put("amount_total_signed",-772.81)
                .put("invoice_date",false).put("ref",false);
        usd.putArray("currency_id").add(2).add("USD");
        var eur=row(39,"in_invoice","2026-09-26").put("amount_total",72.6).put("invoice_date","2026-09-28").put("partner_id",false);
        var unknown=row(38,"in_invoice","2026-09-25");unknown.putArray("currency_id").add(3).add("CNY");
        answer(service,rows(usd,eur,unknown));
        var moves=service.search(incoming(10)).moves();
        var refund=moves.get(0);
        assertEquals(new BigDecimal("840.00"),refund.amountTotal());assertEquals("USD",refund.currency());
        assertEquals(new BigDecimal("772.81"),refund.amountTotalEur());assertNull(refund.invoiceDate());
        assertEquals("",refund.ref());assertEquals(5L,refund.partnerId());assertEquals("Verhoeven Transport BV",refund.partner());
        assertEquals(LocalDate.of(2026,9,26),refund.date());
        var bill=moves.get(1);
        assertEquals(new BigDecimal("72.60"),bill.amountTotal());assertEquals(bill.amountTotal(),bill.amountTotalEur());
        assertEquals(LocalDate.of(2026,9,28),bill.invoiceDate());assertNull(bill.partnerId());assertEquals("",bill.partner());
        assertNull(moves.get(2).amountTotalEur(),"no signed total and not EUR -> unknown euro value");
    }

    @Test @SuppressWarnings({"unchecked","rawtypes"}) void attachmentsAreReadOnlyForThePageIdsAndNeverWithABinaryField() throws Exception {
        var service=service(ALL_FIELDS);
        answer(service,rows(row(30,"in_invoice","2026-10-05"),row(29,"in_invoice","2026-10-05"),row(28,"in_invoice","2026-10-04")));
        when(service.client.search(eq("ir.attachment"),anyList(),anyList(),anyInt(),anyString())).thenReturn(json.readTree(
                "[{\"id\":301,\"res_id\":30,\"name\":\"VT.pdf\",\"mimetype\":\"application/pdf\",\"file_size\":1200,\"type\":\"binary\"},"
                +"{\"id\":302,\"res_id\":30,\"name\":\"VT.xml\",\"mimetype\":\"application/xml\",\"file_size\":800,\"type\":\"binary\"},"
                +"{\"id\":303,\"res_id\":29,\"name\":\"link\",\"mimetype\":\"\",\"file_size\":0,\"type\":\"url\"}]"));
        var page=service.search(incoming(2));
        ArgumentCaptor<List> domain=ArgumentCaptor.forClass(List.class);ArgumentCaptor<List> fields=ArgumentCaptor.forClass(List.class);
        verify(service.client).search(eq("ir.attachment"),domain.capture(),fields.capture(),eq(200),eq("id asc"));
        assertTrue(domain.getValue().contains(List.of("res_id","in",List.of(30L,29L))),"only the page's moves, not the extra row");
        assertTrue(domain.getValue().contains(List.of("res_model","=","account.move")));
        assertTrue(domain.getValue().toString().contains("res_field"));
        assertEquals(OdooInvoiceSearch.ATTACHMENT_FIELDS,fields.getValue());
        assertFalse(fields.getValue().contains("raw"));assertFalse(fields.getValue().contains("datas"));
        var first=page.moves().get(0).attachments();
        assertEquals(List.of("PDF","XML"),first.stream().map(OdooDocumentService.Attachment::kind).toList());
        assertTrue(page.moves().get(1).attachments().isEmpty(),"a url attachment is no file");
    }

    @Test void findHasNoStateFilterAndReturnsACancelledMove() throws Exception {
        var service=service(ALL_FIELDS);
        answer(service,rows(row(900001,"in_invoice","2026-10-02").put("state","cancel")));
        var move=service.find(900001);
        assertEquals("cancel",move.state());
        var domain=domainOf(service);
        assertEquals(List.of(List.of("id","=",900001L),List.of("company_id","=",7L),List.of("move_type","in",OdooDocumentService.INVOICE_TYPES)),domain);
        assertFalse(domain.toString().contains("state"));

        var none=service(ALL_FIELDS);answer(none,rows());
        assertNull(none.find(5));
        assertEquals("INVALID_REQUEST",assertThrows(OdooFailure.class,()->none.find(0)).code);
        var wrong=service(ALL_FIELDS);answer(wrong,rows(row(6,"in_invoice","2026-10-02")));
        assertEquals("INVALID_RESPONSE",assertThrows(OdooFailure.class,()->wrong.find(5)).code);
    }

    @Test void theFieldListIsReadOncePerTenMinutes() {
        var service=service(ALL_FIELDS);answer(service,rows());
        var start=Instant.parse("2026-10-25T10:00:00Z");
        service.clock=Clock.fixed(start,ZoneOffset.UTC);
        service.search(incoming(5));service.search(incoming(5));
        verify(service.client,times(1)).fields("account.move");
        service.clock=Clock.fixed(start.plus(Duration.ofMinutes(11)),ZoneOffset.UTC);
        service.search(incoming(5));
        verify(service.client,times(2)).fields("account.move");
    }

    @Test void aServerWithoutOdooReportsItsProblemAndNeverCallsOdoo() {
        var service=new OdooInvoiceSearch();
        service.config=new OdooConfiguration(false,Optional.empty(),Optional.empty(),Optional.empty(),Optional.empty(),Optional.empty());
        service.client=mock(OdooClient.class);
        assertEquals("DISABLED",service.problem());
        assertEquals("DISABLED",assertThrows(OdooFailure.class,()->service.search(incoming(5))).code);
        assertEquals("DISABLED",assertThrows(OdooFailure.class,service::namespace).code);
        assertNull(service(ALL_FIELDS).problem());
        verifyNoInteractions(service.client);
    }

    @Test void requestsOutsideTheContractAreRefusedBeforeOdoo() {
        var service=service(ALL_FIELDS);
        assertEquals("INVALID_REQUEST",assertThrows(OdooFailure.class,()->service.search(
                query("IN",Set.of("out_invoice"),List.of(),null,null,Set.of(),null,null,5))).code);
        assertEquals("INVALID_REQUEST",assertThrows(OdooFailure.class,()->service.search(incoming(101))).code);
        assertEquals("INVALID_REQUEST",assertThrows(OdooFailure.class,()->service.search(incoming(0))).code);
        verify(service.client,never()).search(anyString(),anyList(),anyList(),anyInt(),anyString());
    }
}
