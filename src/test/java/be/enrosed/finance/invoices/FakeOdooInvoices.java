package be.enrosed.finance.invoices;

import be.enrosed.finance.odoo.OdooDocumentService;
import be.enrosed.finance.odoo.OdooFailure;
import be.enrosed.finance.odoo.OdooInvoiceSearch;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A READY Odoo for the finder's {@code @QuarkusTest}s, installed with QuarkusMock: fixed moves searched with the live
 * search's semantics (move types, no cancelled moves, period, exclude/only, cursor, words as OR groups over name, ref,
 * partner and origin, amount ± 0.01, container-marked ids), every query recorded.
 */
class FakeOdooInvoices extends OdooInvoices {
    final String namespace;
    final List<OdooInvoiceSearch.Move> moves = new CopyOnWriteArrayList<>();
    final List<OdooInvoiceSearch.Query> queries = new CopyOnWriteArrayList<>();
    final Map<String, OdooDocumentService.Download> files = new ConcurrentHashMap<>();
    volatile String failWith;

    FakeOdooInvoices(String namespace) {
        super(null, null, null, false, Clock.systemUTC());
        this.namespace = namespace;
    }

    @Override
    public Availability availability() {
        return new Availability("READY", null, namespace);
    }

    @Override
    public OdooInvoiceSearch.Page search(OdooInvoiceSearch.Query query) {
        queries.add(query);
        if (failWith != null) throw new OdooFailure(failWith);
        if (query.onlyMoveIds() != null && query.onlyMoveIds().isEmpty()) return new OdooInvoiceSearch.Page(List.of(), false);
        List<OdooInvoiceSearch.Move> hits = moves.stream()
                .filter(move -> query.moveTypes().contains(move.moveType()) && !"cancel".equals(move.state()))
                .filter(move -> query.from() == null || !move.date().isBefore(query.from()))
                .filter(move -> query.to() == null || !move.date().isAfter(query.to()))
                .filter(move -> query.excludeMoveIds() == null || !query.excludeMoveIds().contains(move.id()))
                .filter(move -> query.onlyMoveIds() == null || query.onlyMoveIds().contains(move.id()))
                .filter(move -> after(query.after(), move))
                .filter(move -> query.words().stream().allMatch(word -> matches(word, move)))
                .sorted(Comparator.comparing(OdooInvoiceSearch.Move::date).reversed()
                        .thenComparing(Comparator.comparingLong(OdooInvoiceSearch.Move::id).reversed()))
                .limit(query.limit() + 1L).toList();
        return new OdooInvoiceSearch.Page(hits.stream().limit(query.limit()).toList(), hits.size() > query.limit());
    }

    private static boolean after(OdooInvoiceSearch.Cursor cursor, OdooInvoiceSearch.Move move) {
        if (cursor == null) return true;
        if (cursor.moveId() == null) return !move.date().isAfter(cursor.date());
        return move.date().isBefore(cursor.date()) || move.date().equals(cursor.date()) && move.id() < cursor.moveId();
    }

    private static boolean matches(OdooInvoiceSearch.Word word, OdooInvoiceSearch.Move move) {
        String text = word.text().toLowerCase(Locale.ROOT);
        for (String field : new String[] {move.name(), move.ref(), move.partner(), move.origin()})
            if (field != null && field.toLowerCase(Locale.ROOT).contains(text)) return true;
        if (word.amount() != null && move.amountTotal() != null
                && move.amountTotal().subtract(word.amount()).abs().compareTo(new BigDecimal("0.01")) <= 0) return true;
        return word.alsoMoveIds() != null && word.alsoMoveIds().contains(move.id());
    }

    @Override
    public OdooInvoiceSearch.Move find(long moveId) {
        if (failWith != null) throw new OdooFailure(failWith);
        return moves.stream().filter(move -> move.id() == moveId).findFirst().orElse(null);
    }

    @Override
    public OdooDocumentService.Download attachment(long moveId, long attachmentId) {
        OdooDocumentService.Download file = files.get(moveId + ":" + attachmentId);
        if (file == null) throw new OdooFailure("NOT_FOUND");
        return file;
    }
}
