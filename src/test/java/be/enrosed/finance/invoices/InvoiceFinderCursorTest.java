package be.enrosed.finance.invoices;

import be.enrosed.finance.invoices.InvoiceFinderCursor.Position;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class InvoiceFinderCursorTest {
    private static final LocalDate D1 = LocalDate.of(2031, 3, 10);
    private static final LocalDate D2 = LocalDate.of(2031, 3, 9);
    private static final LocalDate D3 = LocalDate.of(2031, 3, 8);

    static InvoiceRow row(String key, String source, LocalDate sortDate) {
        String direction = InvoiceRow.SALES.equals(source) ? "OUT" : "IN";
        return new InvoiceRow(key, source, direction, "INVOICE", null, null, sortDate, sortDate, "INVOICE", null, null, null,
                null, null, null, null, null, null, null, null, "OPEN", null, "MANUAL", null, null, null, null, List.of(),
                List.of(), false);
    }

    @Test
    void aCursorRoundTripsAndRefusesTamperingOtherVersionsAndOtherFilters() {
        String hash = InvoiceFinderCursor.filterHash("IN", "ALL", "CI 2026", D3, D1, 41L);
        assertEquals(8, hash.length());
        assertEquals(hash, InvoiceFinderCursor.filterHash("IN", "ALL", "  ci   2026 ", D3, D1, 41L), "q is normalised");
        String cursor = InvoiceFinderCursor.encode(new Position(D2, 4, 900001), true, hash);
        assertFalse(cursor.contains("="), "no padding");
        InvoiceFinderCursor.Decoded decoded = InvoiceFinderCursor.decode(cursor, hash);
        assertEquals(new Position(D2, 4, 900001), decoded.position());
        assertTrue(decoded.odoo());
        assertFalse(InvoiceFinderCursor.decode(InvoiceFinderCursor.encode(new Position(D2, 1, 3), false, hash), hash).odoo());
        assertNull(InvoiceFinderCursor.decode(null, hash));
        assertNull(InvoiceFinderCursor.decode(" ", hash));

        String other = InvoiceFinderCursor.filterHash("IN", "COSTS", "CI 2026", D3, D1, 41L);
        assertInvalid(cursor, other);
        assertInvalid(cursor.substring(0, cursor.length() - 3) + "abc", hash);
        assertInvalid("%%%", hash);
        assertInvalid(Base64.getUrlEncoder().withoutPadding().encodeToString(
                ("v2|" + D2 + "|4|900001|1|" + hash).getBytes(StandardCharsets.UTF_8)), hash);
        assertInvalid(Base64.getUrlEncoder().withoutPadding().encodeToString(
                ("v1|" + D2 + "|9|900001|1|" + hash).getBytes(StandardCharsets.UTF_8)), hash);
        assertInvalid(Base64.getUrlEncoder().withoutPadding().encodeToString(
                ("v1|" + D2 + "|4|x|1|" + hash).getBytes(StandardCharsets.UTF_8)), hash);
    }

    private static void assertInvalid(String cursor, String hash) {
        InvoiceFinderErrors.Invalid invalid = assertThrows(InvoiceFinderErrors.Invalid.class,
                () -> InvoiceFinderCursor.decode(cursor, hash));
        assertEquals("INVALID_CURSOR", invalid.code);
        assertEquals(400, invalid.getResponse().getStatus());
    }

    @Test
    void equalDatesOrderBySourceRankThenIdDescending() {
        InvoiceRow cost = row("cost:5", InvoiceRow.COST, D1);
        InvoiceRow invoice = row("pi:2", InvoiceRow.CONTAINER_INVOICE, D1);
        InvoiceRow invoiceHigh = row("pi:9", InvoiceRow.CONTAINER_INVOICE, D1);
        InvoiceRow file = row("pd:7", InvoiceRow.CONTAINER_FILE, D1);
        InvoiceRow odoo = row("odoo:900001", InvoiceRow.ODOO, D1);
        InvoiceRow older = row("pi:99", InvoiceRow.CONTAINER_INVOICE, D2);
        InvoiceFinderCursor.Merged merged = InvoiceFinderCursor.merge(List.of(older, cost, invoice, file, invoiceHigh),
                List.of(odoo), null, false, 10);
        assertEquals(List.of("pi:9", "pi:2", "pd:7", "cost:5", "odoo:900001", "pi:99"), keys(merged.rows()));
        assertNull(merged.next());
        assertFalse(merged.odoo());
        assertTrue(InvoiceFinderCursor.after(Position.of(odoo), Position.of(cost)));
        assertFalse(InvoiceFinderCursor.after(Position.of(cost), Position.of(cost)));
        assertTrue(InvoiceFinderCursor.after(Position.of(older), Position.of(odoo)));
    }

    @Test
    void aFullPageWithMoreCandidatesPointsAtItsLastRow() {
        List<InvoiceRow> erp = List.of(row("pi:3", InvoiceRow.CONTAINER_INVOICE, D1), row("cost:4", InvoiceRow.COST, D2),
                row("cost:2", InvoiceRow.COST, D3));
        InvoiceFinderCursor.Merged merged = InvoiceFinderCursor.merge(erp, List.of(), null, false, 2);
        assertEquals(List.of("pi:3", "cost:4"), keys(merged.rows()));
        assertEquals(new Position(D2, 3, 4), merged.next());
        assertFalse(merged.odoo(), "Odoo was exhausted (or not asked): later pages never call it");

        InvoiceFinderCursor.Merged odooLeft = InvoiceFinderCursor.merge(erp.subList(0, 2), List.of(), null, true, 2);
        assertEquals(new Position(D2, 3, 4), odooLeft.next(), "Odoo still has moves: a next page exists");
        assertTrue(odooLeft.odoo());

        InvoiceFinderCursor.Merged untaken = InvoiceFinderCursor.merge(erp.subList(0, 1),
                List.of(row("odoo:5", InvoiceRow.ODOO, D2), row("odoo:4", InvoiceRow.ODOO, D3)), null, false, 2);
        assertEquals(List.of("pi:3", "odoo:5"), keys(untaken.rows()));
        assertTrue(untaken.odoo(), "a kept Odoo row was not taken");
    }

    @Test
    void aScanStopDropsLaterCandidatesAndYieldsItsOwnPositionOnAShortPage() {
        Position stop = new Position(D2, 4, 900010);
        List<InvoiceRow> erp = List.of(row("pi:3", InvoiceRow.CONTAINER_INVOICE, D1), row("cost:8", InvoiceRow.COST, D3));
        List<InvoiceRow> kept = List.of(row("odoo:900020", InvoiceRow.ODOO, D1));
        InvoiceFinderCursor.Merged merged = InvoiceFinderCursor.merge(erp, kept, stop, true, 5);
        assertEquals(List.of("pi:3", "odoo:900020"), keys(merged.rows()), "cost:8 sorts after the stop: not safe yet");
        assertEquals(stop, merged.next());
        assertTrue(merged.odoo());

        InvoiceFinderCursor.Merged empty = InvoiceFinderCursor.merge(List.of(row("cost:8", InvoiceRow.COST, D3)), List.of(),
                stop, true, 5);
        assertEquals(List.of(), empty.rows());
        assertEquals(stop, empty.next(), "an empty page may still continue after the last scanned move");
    }

    @Test
    void odooExhaustedAndEveryKeptRowTakenGivesNoOdooFlag() {
        InvoiceFinderCursor.Merged merged = InvoiceFinderCursor.merge(
                List.of(row("pi:3", InvoiceRow.CONTAINER_INVOICE, D1), row("cost:8", InvoiceRow.COST, D3),
                        row("cost:7", InvoiceRow.COST, D3)),
                List.of(row("odoo:900020", InvoiceRow.ODOO, D2)), null, false, 2);
        assertEquals(List.of("pi:3", "odoo:900020"), keys(merged.rows()));
        assertEquals(new Position(D2, 4, 900020), merged.next());
        assertFalse(merged.odoo());
    }

    @Test
    void pagingAFixedListWithLimitTwoYieldsTheSameSequenceAsLimitHundred() {
        List<InvoiceRow> erp = new ArrayList<>();
        List<InvoiceRow> odoo = new ArrayList<>();
        LocalDate day = LocalDate.of(2031, 3, 28);
        long id = 1;
        for (int i = 0; i < 9; i++) {
            erp.add(row("pi:" + id++, InvoiceRow.CONTAINER_INVOICE, day.minusDays(i / 2)));
            erp.add(row("cost:" + id++, InvoiceRow.COST, day.minusDays(i)));
            odoo.add(row("odoo:" + (900000 + id++), InvoiceRow.ODOO, day.minusDays(i / 3)));
        }
        List<String> all = keys(InvoiceFinderCursor.merge(erp, odoo, null, false, 100).rows());
        assertEquals(27, all.size());

        List<String> paged = new ArrayList<>();
        Position cursor = null;
        boolean odooOpen = true;
        for (int guard = 0; guard < 50; guard++) {
            Position at = cursor;
            List<InvoiceRow> erpAfter = erp.stream().filter(row -> InvoiceFinderCursor.after(Position.of(row), at)).toList();
            List<InvoiceRow> odooAfter = odooOpen
                    ? odoo.stream().filter(row -> InvoiceFinderCursor.after(Position.of(row), at)).toList() : List.of();
            // Odoo answers at most 2 moves per page, as the live search does with limit 2.
            List<InvoiceRow> kept = odooAfter.stream().sorted(InvoiceFinderCursor.ROW_ORDER).limit(2).toList();
            boolean more = odooAfter.size() > 2;
            Position scanStop = more && kept.size() < 2 ? Position.of(kept.getLast()) : null;
            InvoiceFinderCursor.Merged page = InvoiceFinderCursor.merge(erpAfter, kept, scanStop, more, 2);
            paged.addAll(keys(page.rows()));
            if (page.next() == null) break;
            cursor = page.next();
            odooOpen = page.odoo();
        }
        assertEquals(all, paged);
    }

    private static List<String> keys(List<InvoiceRow> rows) {
        return rows.stream().map(InvoiceRow::key).toList();
    }
}
