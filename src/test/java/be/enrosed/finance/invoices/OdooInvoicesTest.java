package be.enrosed.finance.invoices;

import be.enrosed.finance.odoo.OdooDocumentService;
import be.enrosed.finance.odoo.OdooFailure;
import be.enrosed.finance.odoo.OdooInvoiceSearch;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OdooInvoicesTest {
    static final class MutableClock extends Clock {
        Instant now=Instant.parse("2026-10-25T10:00:00Z");
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
        void advance(Duration duration) { now=now.plus(duration); }
    }

    final OdooInvoiceSearch live=mock(OdooInvoiceSearch.class);
    final OdooDocumentService documents=mock(OdooDocumentService.class);
    final OdooInvoiceDemo demo=new OdooInvoiceDemo();
    final MutableClock clock=new MutableClock();

    OdooInvoices ready(boolean demoFlag) {
        when(live.problem()).thenReturn(null);
        when(live.namespace()).thenReturn("ns-live");
        return new OdooInvoices(live,documents,demo,demoFlag,clock);
    }
    OdooInvoices notReady(String problem,boolean demoFlag) {
        doReturn(problem).when(live).problem();
        doThrow(new OdooFailure(problem)).when(live).namespace();
        return new OdooInvoices(live,documents,demo,demoFlag,clock);
    }
    static OdooInvoiceSearch.Query query(String direction,Set<String> types,List<OdooInvoiceSearch.Word> words,Set<Long> exclude,
                                         Set<Long> only,OdooInvoiceSearch.Cursor after,int limit) {
        return new OdooInvoiceSearch.Query(direction,types,words,null,null,exclude,only,after,limit);
    }
    static OdooInvoiceSearch.Query incoming(int limit) {
        return query("IN",Set.of("in_invoice","in_refund"),List.of(),Set.of(),null,null,limit);
    }
    static OdooInvoiceSearch.Word word(String text) { return new OdooInvoiceSearch.Word(text,null,Set.of()); }
    static List<Long> ids(OdooInvoiceSearch.Page page) { return page.moves().stream().map(OdooInvoiceSearch.Move::id).toList(); }
    static OdooInvoiceSearch.Page page(long... ids) {
        List<OdooInvoiceSearch.Move> moves=new ArrayList<>();
        for(long id:ids)moves.add(new OdooInvoiceSearch.Move(id,"in_invoice","posted","not_paid","BILL/"+id,"R"+id,null,1L,"P",
                LocalDate.of(2026,10,1),null,null,"EUR",BigDecimal.TEN,BigDecimal.TEN,null,List.of()));
        return new OdooInvoiceSearch.Page(moves,false);
    }

    @Test void aConfiguredOdooNeverUsesTheDemoEvenWithTheFlagOn() {
        var invoices=ready(true);
        var download=new OdooDocumentService.Download("x.pdf","application/pdf",new byte[]{1});
        when(live.search(any())).thenReturn(page(41));
        when(live.find(41)).thenReturn(page(41).moves().getFirst());
        when(documents.download(41,7)).thenReturn(download);
        assertEquals(new OdooInvoices.Availability("READY",null,"ns-live"),invoices.availability());
        assertEquals(List.of(41L),ids(invoices.search(incoming(10))));
        assertEquals(41L,invoices.find(41).id());
        assertSame(download,invoices.attachment(41,7));
        assertNull(invoices.find(900001),"a demo id is not looked up in the demo");
        verify(live).find(900001);
    }

    @Test void withoutOdooAndWithoutTheFlagTheFinderIsNotConnected() {
        var invoices=notReady("NOT_CONFIGURED",false);
        assertEquals(new OdooInvoices.Availability("NOT_CONNECTED","NOT_CONFIGURED",null),invoices.availability());
        var page=invoices.search(incoming(10));
        assertTrue(page.moves().isEmpty());assertFalse(page.more());
        assertEquals("NOT_CONFIGURED",assertThrows(OdooFailure.class,()->invoices.find(900001)).code);
        assertEquals("NOT_CONFIGURED",assertThrows(OdooFailure.class,()->invoices.attachment(900001,9000011)).code);
        verify(live,never()).search(any());verify(live,never()).find(anyLong());verifyNoInteractions(documents);

        var disabled=notReady("DISABLED",false);
        assertEquals("DISABLED",disabled.availability().code());
    }

    @Test void theDemoAnswersWhereOdooIsMissingAndTheFlagIsOn() {
        var invoices=notReady("DISABLED",true);
        assertEquals(new OdooInvoices.Availability("DEMO",null,"demo"),invoices.availability());
        assertEquals(List.of(900002L,900004L,900001L,900005L,900003L,900006L),ids(invoices.search(incoming(10))));
        assertEquals(List.of(900007L),ids(invoices.search(query("OUT",Set.of("out_invoice","out_refund"),List.of(),Set.of(),null,null,10))));
        verify(live,never()).search(any());
    }

    @Test void theDemoSearchesByWordAmountExcludeOnlyAndCursorLikeTheLiveSearch() {
        var invoices=notReady("NOT_CONFIGURED",true);
        assertEquals(List.of(900004L,900001L),ids(invoices.search(query("IN",Set.of("in_invoice","in_refund"),List.of(word("verhoeven")),Set.of(),null,null,10))));
        assertEquals(List.of(900004L),ids(invoices.search(query("IN",Set.of("in_refund"),List.of(word("Verhoeven")),Set.of(),null,null,10))));
        assertEquals(List.of(900001L),ids(invoices.search(query("IN",Set.of("in_invoice"),List.of(word("VT"),word("0412")),Set.of(),null,null,10))));
        var amount=new OdooInvoiceSearch.Word("1.250,00",new BigDecimal("1250.00"),Set.of());
        assertEquals(List.of(900001L),ids(invoices.search(query("IN",Set.of("in_invoice","in_refund"),List.of(amount),Set.of(),null,null,10))));
        var nearly=new OdooInvoiceSearch.Word("1250,01",new BigDecimal("1250.01"),Set.of());
        assertEquals(List.of(900001L),ids(invoices.search(query("IN",Set.of("in_invoice"),List.of(nearly),Set.of(),null,null,10))));
        var marked=new OdooInvoiceSearch.Word("PO-2026-012",null,Set.of(900006L));
        assertEquals(List.of(900006L),ids(invoices.search(query("IN",Set.of("in_invoice"),List.of(marked),Set.of(),null,null,10))));
        assertFalse(ids(invoices.search(query("IN",Set.of("in_invoice","in_refund"),List.of(),Set.of(900001L,900002L),null,null,10))).contains(900001L));
        assertEquals(List.of(900003L),ids(invoices.search(query("IN",Set.of("in_invoice","in_refund"),List.of(),Set.of(),Set.of(900003L,900007L),null,10))));
        assertTrue(invoices.search(query("IN",Set.of("in_invoice"),List.of(),Set.of(),Set.of(),null,10)).moves().isEmpty());

        var first=invoices.search(incoming(2));
        assertEquals(List.of(900002L,900004L),ids(first));assertTrue(first.more());
        var last=first.moves().getLast();
        var second=invoices.search(query("IN",Set.of("in_invoice","in_refund"),List.of(),Set.of(),null,
                new OdooInvoiceSearch.Cursor(last.date(),last.id()),2));
        assertEquals(List.of(900001L,900005L),ids(second));assertTrue(second.more());
        var third=invoices.search(query("IN",Set.of("in_invoice","in_refund"),List.of(),Set.of(),null,
                new OdooInvoiceSearch.Cursor(LocalDate.of(2026,10,1),900005L),2));
        assertEquals(List.of(900003L,900006L),ids(third));assertFalse(third.more());
        var wholeDay=invoices.search(query("IN",Set.of("in_invoice","in_refund"),List.of(),Set.of(),null,
                new OdooInvoiceSearch.Cursor(LocalDate.of(2026,10,1),null),10));
        assertEquals(List.of(900005L,900003L,900006L),ids(wholeDay));
    }

    @Test void theDemoMovesCarryTheContractFacts() {
        var invoices=notReady("DISABLED",true);
        var bill=invoices.find(900003);
        assertEquals("BILL/2026/10/003",bill.name());assertEquals("PX-551203",bill.ref());assertEquals("paid",bill.paymentState());
        assertEquals(LocalDate.of(2026,9,30),bill.date());assertEquals(LocalDate.of(2026,9,28),bill.invoiceDate());
        assertEquals(LocalDate.of(2026,10,30),bill.dueDate());assertEquals(new BigDecimal("72.60"),bill.amountTotalEur());
        var usd=invoices.find(900006);
        assertEquals("USD",usd.currency());assertEquals(new BigDecimal("840.00"),usd.amountTotal());assertNull(usd.amountTotalEur());
        var draft=invoices.find(900002);
        assertEquals("draft",draft.state());assertEquals("done",draft.peppolState());
        assertEquals(List.of("XML"),draft.attachments().stream().map(OdooDocumentService.Attachment::kind).toList());
        var sale=invoices.find(900007);
        assertEquals("out_invoice",sale.moveType());assertEquals("INV/2026/00412",sale.name());
        assertNull(invoices.find(123));
        assertEquals("INVALID_REQUEST",assertThrows(OdooFailure.class,()->invoices.find(0)).code);
    }

    @Test void theDemoPdfIsAPdfAndTheDemoXmlCarriesThatSamePdf() {
        var invoices=notReady("DISABLED",true);
        var move=invoices.find(900001);
        assertEquals(List.of(9000011L,9000012L),move.attachments().stream().map(OdooDocumentService.Attachment::id).toList());
        var pdf=invoices.attachment(900001,9000011);
        assertEquals("application/pdf",pdf.mimeType());assertEquals("VT-2026-0412.pdf",pdf.name());
        assertTrue(new String(pdf.bytes(),0,5,StandardCharsets.US_ASCII).equals("%PDF-"));
        assertEquals(move.attachments().get(0).size(),pdf.bytes().length);
        var xml=invoices.attachment(900001,9000012);
        assertEquals("application/xml",xml.mimeType());assertEquals(move.attachments().get(1).size(),xml.bytes().length);
        assertTrue(EmbeddedPdf.looksLikeXml(xml.name(),xml.mimeType(),xml.bytes()));
        var embedded=EmbeddedPdf.fromXml(xml.bytes()).orElseThrow();
        assertArrayEquals(pdf.bytes(),embedded.pdf());assertEquals("VT-2026-0412.pdf",embedded.filename());
        var xmlOnly=invoices.attachment(900002,9000021);
        assertTrue(EmbeddedPdf.fromXml(xmlOnly.bytes()).isPresent());
        var noPdf=invoices.attachment(900005,9000051);
        assertTrue(EmbeddedPdf.fromXml(noPdf.bytes()).isEmpty(),"900005's XML has no embedded PDF");
        assertEquals("NOT_FOUND",assertThrows(OdooFailure.class,()->invoices.attachment(900001,9000021)).code);
        assertEquals("NOT_FOUND",assertThrows(OdooFailure.class,()->invoices.attachment(123,1)).code);
    }

    @Test void identicalReadyQueriesAreAnsweredFromTheCacheFor30Seconds() {
        var invoices=ready(false);
        when(live.search(any())).thenReturn(page(1));
        invoices.search(incoming(10));
        clock.advance(Duration.ofSeconds(29));
        invoices.search(incoming(10));
        verify(live,times(1)).search(any());
        clock.advance(Duration.ofSeconds(2));
        invoices.search(incoming(10));
        verify(live,times(2)).search(any());
        invoices.search(incoming(11));
        verify(live,times(3)).search(any());
        invoices.search(query("IN",Set.of("in_invoice","in_refund"),List.of(word("VT")),Set.of(),null,null,11));
        verify(live,times(4)).search(any());
    }

    @Test void aFailureIsNeverCached() {
        var invoices=ready(false);
        when(live.search(any())).thenThrow(new OdooFailure("RATE_LIMITED")).thenReturn(page(2));
        assertEquals("RATE_LIMITED",assertThrows(OdooFailure.class,()->invoices.search(incoming(10))).code);
        assertEquals(List.of(2L),ids(invoices.search(incoming(10))));
        assertEquals(List.of(2L),ids(invoices.search(incoming(10))));
        verify(live,times(2)).search(any());
    }

    @Test void theCacheKeepsAtMost64QueriesAndEvictsTheOldestFirst() {
        var invoices=ready(false);
        when(live.search(any())).thenReturn(page(3));
        for(int limit=1;limit<=65;limit++) {
            int size=limit;
            invoices.search(query("IN",Set.of("in_invoice"),List.of(word("w"+size)),Set.of(),null,null,10));
        }
        verify(live,times(65)).search(any());
        invoices.search(query("IN",Set.of("in_invoice"),List.of(word("w65")),Set.of(),null,null,10));
        verify(live,times(65)).search(any());
        invoices.search(query("IN",Set.of("in_invoice"),List.of(word("w1")),Set.of(),null,null,10));
        verify(live,times(66)).search(any());
    }

    @Test void demoAndNotConnectedAnswersAreNotCached() {
        var invoices=notReady("DISABLED",true);
        var once=invoices.search(incoming(10));
        var twice=invoices.search(incoming(10));
        assertEquals(ids(once),ids(twice));
        verify(live,never()).search(any());
    }
}
