package be.enrosed.finance.invoices;

import be.enrosed.finance.odoo.OdooInvoiceSearch;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class InvoiceFinderQueryTest {
    private static final LocalDate TODAY = LocalDate.of(2031, 3, 15);
    private static final LocalDate START = LocalDate.of(2031, 3, 1);
    private static final List<OdooInvoiceSearch.Word> WORDS = List.of(new OdooInvoiceSearch.Word("VT", null, Set.of()));

    /** registered 1 (cost 11), removed 2, TICA 3 (settled) and 4, outbox 5, marks 6 (HANDLED) and 7 (CONTAINER of order 41). */
    static ErpInvoiceRows.Dedup dedup() {
        Instant at = Instant.parse("2031-03-02T10:00:00Z");
        return new ErpInvoiceRows.Dedup("ns", Map.of(1L, 11L), Set.of(2L), Set.of(3L, 4L), Map.of(3L, 77L), Map.of(5L, 55L),
                Map.of(6L, new OdooDocumentMarks.Mark("ns", 6, "HANDLED", null, null, at, "emre"),
                        7L, new OdooDocumentMarks.Mark("ns", 7, "CONTAINER", 41L, "zit in PO", at, "emre")),
                Map.of(7L, new InvoiceRow.ContainerRef(41, "PO-2031-001", "Voorjaar")));
    }

    static InvoiceFinderService.Query request(String direction, String scope, LocalDate from, Long orderId) {
        return new InvoiceFinderService.Query(direction, scope, "VT", from, null, orderId, null, 50);
    }

    @Test
    void exclusionsPerScope() {
        OdooInvoiceSearch.Query newOnes = InvoiceFinderService.odooQuery(request("IN", "ODOO_NEW", null, null), dedup(),
                Optional.empty(), WORDS, null);
        assertEquals(Set.of(1L, 2L, 3L, 4L, 6L, 7L), newOnes.excludeMoveIds(), "registered ∪ removed ∪ tica ∪ marks");
        assertEquals(Set.of("in_invoice", "in_refund"), newOnes.moveTypes());
        assertEquals("IN", newOnes.direction());
        assertEquals(WORDS, newOnes.words());
        assertNull(newOnes.onlyMoveIds());
        assertEquals(50, newOnes.limit());

        OdooInvoiceSearch.Query all = InvoiceFinderService.odooQuery(request("IN", "ALL", null, null), dedup(),
                Optional.empty(), WORDS, null);
        assertEquals(Set.of(1L), all.excludeMoveIds(), "IN/ALL only hides registered moves");

        for (String scope : List.of("ALL", "ODOO")) {
            OdooInvoiceSearch.Query out = InvoiceFinderService.odooQuery(request("OUT", scope, null, null), dedup(),
                    Optional.empty(), WORDS, null);
            assertEquals(Set.of(5L), out.excludeMoveIds());
            assertEquals(Set.of("out_invoice", "out_refund"), out.moveTypes());
        }
        assertEquals(Set.of("out_invoice"), InvoiceFinderService.odooQuery(request("OUT", "INVOICES", null, null), dedup(),
                Optional.empty(), WORDS, null).moveTypes());
        assertEquals(Set.of("out_refund"), InvoiceFinderService.odooQuery(request("OUT", "CREDIT_NOTES", null, null), dedup(),
                Optional.empty(), WORDS, null).moveTypes());
        for (String scope : List.of("CONTAINERS", "COSTS", "NO_FILE"))
            assertNull(InvoiceFinderService.odooQuery(request("IN", scope, null, null), dedup(), Optional.empty(), WORDS, null),
                    scope + " has no Odoo part");
    }

    @Test
    void theStartDateRaisesFromForNewInOdooOnly() {
        assertEquals(START, InvoiceFinderService.odooQuery(request("IN", "ODOO_NEW", null, null), dedup(),
                Optional.of(START), WORDS, null).from());
        assertEquals(START, InvoiceFinderService.odooQuery(request("IN", "ODOO_NEW", START.minusMonths(2), null), dedup(),
                Optional.of(START), WORDS, null).from());
        assertEquals(START.plusDays(5), InvoiceFinderService.odooQuery(request("IN", "ODOO_NEW", START.plusDays(5), null),
                dedup(), Optional.of(START), WORDS, null).from(), "a later from stays");
        assertNull(InvoiceFinderService.odooQuery(request("IN", "ALL", null, null), dedup(), Optional.of(START), WORDS, null)
                .from(), "Inkomend · Alles still lists older moves (as 'Ouder')");
        assertNull(InvoiceFinderService.odooQuery(request("OUT", "ALL", null, null), dedup(), Optional.of(START), WORDS, null)
                .from());
    }

    @Test
    void aContainerFilterAsksOnlyItsMarkedMovesAndWithoutMarksMakesNoCall() {
        OdooInvoiceSearch.Query marked = InvoiceFinderService.odooQuery(request("IN", "ALL", null, 41L), dedup(),
                Optional.empty(), WORDS, null);
        assertEquals(Set.of(7L), marked.onlyMoveIds());
        assertNull(InvoiceFinderService.odooQuery(request("IN", "ALL", null, 42L), dedup(), Optional.empty(), WORDS, null));
        assertNull(InvoiceFinderService.odooQuery(request("OUT", "ALL", null, 42L), dedup(), Optional.empty(), WORDS, null));
        assertNull(InvoiceFinderService.odooQuery(request("IN", "ODOO_NEW", null, 41L), dedup(), Optional.empty(), WORDS,
                null), "Nieuw in Odoo excludes every marked move, so a container's marked moves can never come");
    }

    @Test
    void typedWordsOdooCannotTakeMakeNoCallButNoWordsAtAllAskEverything() {
        for (String q : List.of("1/2", "&", "- / -")) {
            InvoiceFinderService.Query typed = new InvoiceFinderService.Query("IN", "ALL", q, null, null, null, null, 50);
            assertNull(InvoiceFinderService.odooQuery(typed, dedup(), Optional.empty(),
                    InvoiceSearchText.odooWords(q, Map.of()), null), q);
        }
        InvoiceFinderService.Query none = new InvoiceFinderService.Query("IN", "ALL", "", null, null, null, null, 50);
        assertNotNull(InvoiceFinderService.odooQuery(none, dedup(), Optional.empty(), List.of(), null));
    }

    @Test
    void theCursorOfAListPositionBecomesTheOdooCursor() {
        OdooInvoiceSearch.Cursor fromErp = InvoiceFinderService.odooCursor(new InvoiceFinderCursor.Position(START, 3, 12));
        assertEquals(new OdooInvoiceSearch.Cursor(START, null), fromErp, "every Odoo move of that day still comes");
        assertEquals(new OdooInvoiceSearch.Cursor(START, 900001L),
                InvoiceFinderService.odooCursor(new InvoiceFinderCursor.Position(START, 4, 900001)));
        OdooInvoiceSearch.Query query = InvoiceFinderService.odooQuery(request("IN", "ALL", null, null), dedup(),
                Optional.empty(), WORDS, fromErp);
        assertEquals(fromErp, query.after());
    }

    static OdooInvoiceSearch.Move move(long id, String type, String state, String payment, LocalDate date, LocalDate due) {
        return new OdooInvoiceSearch.Move(id, type, state, payment, "BILL/" + id, "REF-" + id, null, 9L, "Partner", date, date,
                due, "EUR", new BigDecimal("10.00"), new BigDecimal("10.00"), null, List.of());
    }

    @Test
    void incomingStatusOrderCancelledTicaMarkCostRemovedBeforeStartNew() {
        ErpInvoiceRows.Dedup dedup = dedup();
        LocalDate old = START.minusDays(10);
        assertStatus("CANCELLED", null, move(3, "in_refund", "cancel", null, old, null), dedup, Optional.of(START));
        assertStatus("TICA", null, move(3, "in_refund", "posted", null, old, null), dedup, Optional.of(START));
        assertStatus("HANDLED", null, move(6, "in_invoice", "posted", null, old, null), dedup, Optional.of(START));
        assertStatus("CONTAINER", null, move(7, "in_invoice", "posted", null, old, null), dedup, Optional.of(START));
        assertStatus("HANDLED", "COST_REMOVED", move(2, "in_invoice", "posted", null, old, null), dedup, Optional.of(START));
        assertStatus("HANDLED", "BEFORE_START", move(8, "in_invoice", "posted", null, old, null), dedup, Optional.of(START));
        assertStatus("NEW", null, move(8, "in_invoice", "draft", null, START, null), dedup, Optional.of(START));
        assertStatus("NEW", null, move(8, "in_invoice", "posted", null, old, null), dedup, Optional.empty());
    }

    @Test
    void outgoingStatusFollowsOdoosPaymentState() {
        ErpInvoiceRows.Dedup dedup = dedup();
        assertStatus("CANCELLED", null, move(9, "out_invoice", "cancel", "paid", START, null), dedup, Optional.empty());
        assertStatus("PAID", null, move(9, "out_invoice", "posted", "paid", START, null), dedup, Optional.empty());
        assertStatus("PAID", null, move(9, "out_invoice", "posted", "in_payment", START, null), dedup, Optional.empty());
        assertStatus("PARTIAL", null, move(9, "out_invoice", "posted", "partial", START, null), dedup, Optional.empty());
        assertStatus("SETTLED", null, move(9, "out_refund", "posted", "reversed", START, null), dedup, Optional.empty());
        assertStatus("OVERDUE", null, move(9, "out_invoice", "posted", "not_paid", START, TODAY.minusDays(1)), dedup, Optional.empty());
        assertStatus("OPEN", null, move(9, "out_invoice", "posted", "not_paid", START, TODAY), dedup, Optional.empty());
        assertStatus("OPEN", null, move(9, "out_invoice", "posted", null, START, null), dedup, Optional.empty());
        assertStatus("OVERDUE", null, move(9, "out_invoice", "posted", null, START, TODAY.minusDays(3)), dedup, Optional.empty());
    }

    private static void assertStatus(String status, String reason, OdooInvoiceSearch.Move move, ErpInvoiceRows.Dedup dedup,
                                     Optional<LocalDate> newSince) {
        String[] got = InvoiceFinderService.odooStatus(move, dedup, newSince, TODAY);
        assertEquals(status, got[0], move.toString());
        assertEquals(reason, got[1], move.toString());
    }

    @Test
    void blockedReasonsInTheContractsOrder() {
        ErpInvoiceRows.Dedup dedup = dedup();
        assertEquals("OUTGOING", InvoiceFinderService.blockedBy(move(9, "out_invoice", "posted", null, START, null), dedup, false));
        assertEquals("CANCELLED", InvoiceFinderService.blockedBy(move(3, "in_invoice", "cancel", null, START, null), dedup, false));
        OdooInvoiceSearch.Move noDate = new OdooInvoiceSearch.Move(8, "in_invoice", "draft", null, "B", "", null, null, "",
                START, null, null, "EUR", new BigDecimal("0.00"), null, null, List.of());
        assertEquals("NO_DATE", InvoiceFinderService.blockedBy(noDate, dedup, false));
        OdooInvoiceSearch.Move zero = new OdooInvoiceSearch.Move(8, "in_invoice", "posted", null, "B", "", null, null, "",
                START, START, null, "USD", new BigDecimal("0.00"), null, null, List.of());
        assertEquals("ZERO", InvoiceFinderService.blockedBy(zero, dedup, false));
        OdooInvoiceSearch.Move usd = new OdooInvoiceSearch.Move(3, "in_invoice", "posted", null, "B", "", null, null, "",
                START, START, null, "USD", new BigDecimal("840.00"), null, null, List.of());
        assertEquals("NOT_EUR", InvoiceFinderService.blockedBy(usd, dedup, false));
        assertEquals("TICA", InvoiceFinderService.blockedBy(move(3, "in_refund", "posted", null, START, null), dedup, false));
        assertEquals("MARKED", InvoiceFinderService.blockedBy(move(6, "in_invoice", "posted", null, START, null), dedup, false));
        assertEquals("COST_REMOVED", InvoiceFinderService.blockedBy(move(2, "in_invoice", "posted", null, START, null), dedup, false));
        assertEquals("DEMO", InvoiceFinderService.blockedBy(move(8, "in_invoice", "posted", null, START, null), dedup, true));
        assertNull(InvoiceFinderService.blockedBy(move(8, "in_invoice", "draft", null, START, null), dedup, false));
    }

    @Test
    void anOdooRowCarriesItsMarkContainerTicaLinkAndFiles() {
        ErpInvoiceRows.Dedup dedup = dedup();
        OdooInvoices.Availability ready = new OdooInvoices.Availability("READY", null, "ns");
        OdooInvoiceSearch.Move marked = new OdooInvoiceSearch.Move(7, "in_invoice", "posted", "not_paid", "BILL/7", " ", null,
                9L, "Verhoeven", START, START.minusDays(2), START.plusDays(30), "EUR", new BigDecimal("1250"),
                new BigDecimal("1250"), "done", List.of(new be.enrosed.finance.odoo.OdooDocumentService.Attachment(71, "VT.pdf",
                "application/pdf", 1234, "PDF"), new be.enrosed.finance.odoo.OdooDocumentService.Attachment(72, "VT.xml",
                "application/xml", 99, "XML")));
        InvoiceRow row = InvoiceFinderService.odooRow(marked, dedup, ready, Optional.empty(), TODAY);
        assertEquals("odoo:7", row.key());
        assertEquals("BILL/7", row.number(), "ref blank: the name");
        assertEquals(START.minusDays(2), row.date());
        assertEquals(START, row.sortDate());
        assertEquals("CONTAINER", row.status());
        assertEquals(new BigDecimal("1250.00"), row.amount());
        assertEquals("ODOO_PARTNER", row.party().type());
        assertEquals(41, row.container().id());
        assertEquals(List.of("CONTAINER"), row.links().stream().map(InvoiceRow.Link::type).toList());
        assertEquals("Voorjaar", row.links().getFirst().label());
        assertEquals("MARKED", row.odoo().blockedBy());
        assertFalse(row.odoo().registrable());
        assertEquals("zit in PO", row.odoo().mark().note());
        assertEquals(List.of("PDF", "XML"), row.files().stream().map(InvoiceRow.FileRef::kind).toList());
        assertEquals(7L, row.files().getFirst().moveId());
        assertFalse(row.missingFile());
        assertEquals("done", row.peppolState());

        InvoiceRow tica = InvoiceFinderService.odooRow(move(3, "in_refund", "posted", null, START, null), dedup, ready,
                Optional.empty(), TODAY);
        assertEquals("TICA", tica.status());
        assertEquals("CREDIT_NOTE", tica.kind());
        assertEquals(List.of(new InvoiceRow.Link("TICA", 77, "TICA", null, null)), tica.links());
        assertTrue(tica.missingFile());
        InvoiceRow unsettled = InvoiceFinderService.odooRow(move(4, "in_refund", "posted", null, START, null), dedup, ready,
                Optional.empty(), TODAY);
        assertEquals("TICA", unsettled.status());
        assertEquals(List.of(), unsettled.links(), "no TICA link before the settlement exists");
        InvoiceRow fresh = InvoiceFinderService.odooRow(move(8, "in_invoice", "posted", null, START, null), dedup, ready,
                Optional.empty(), TODAY);
        assertTrue(fresh.odoo().registrable());
        assertEquals("REF-8", fresh.number());
    }

    @Test
    void validationFillsDefaultsAndRefusesWithCodes() {
        InvoiceFinderService.Query defaults = InvoiceFinderService.validate(
                new InvoiceFinderService.Query(null, null, null, null, null, null, null, 50));
        assertEquals("IN", defaults.direction());
        assertEquals("ALL", defaults.scope());
        assertEquals("", defaults.q());
        assertCode("INVALID_SCOPE", new InvoiceFinderService.Query("IN", "CREDIT_NOTES", null, null, null, null, null, 50));
        assertCode("INVALID_SCOPE", new InvoiceFinderService.Query("OUT", "NO_FILE", null, null, null, null, null, 50));
        assertCode("INVALID_SCOPE", new InvoiceFinderService.Query("SIDEWAYS", null, null, null, null, null, null, 50));
        assertCode("INVALID_QUERY", new InvoiceFinderService.Query("IN", null, "x".repeat(201), null, null, null, null, 50));
        assertCode("INVALID_PERIOD", new InvoiceFinderService.Query("IN", null, null, START, START.minusDays(1), null, null, 50));
        assertCode("INVALID_LIMIT", new InvoiceFinderService.Query("IN", null, null, null, null, null, null, 0));
        assertCode("INVALID_LIMIT", new InvoiceFinderService.Query("IN", null, null, null, null, null, null, 101));
        assertEquals(100, InvoiceFinderService.validate(
                new InvoiceFinderService.Query("out", "odoo", "x".repeat(200), null, null, null, null, 100)).limit());
    }

    private static void assertCode(String code, InvoiceFinderService.Query query) {
        assertEquals(code, assertThrows(InvoiceFinderErrors.Invalid.class, () -> InvoiceFinderService.validate(query)).code);
    }
}
