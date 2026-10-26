package be.enrosed.finance.invoices;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.*;

/**
 * Pure keyset paging over the merged list. Order: sortDate desc, source rank asc, id desc. The cursor names the last
 * position shown, whether Odoo may still have rows, and a hash of the filters it was made for.
 */
public final class InvoiceFinderCursor {
    static final String VERSION = "v1";
    public static final int ODOO_RANK = 4;

    private InvoiceFinderCursor() {}

    /** A row's place in the order. */
    public record Position(LocalDate sortDate, int rank, long id) {
        public static Position of(InvoiceRow row) {
            return new Position(row.sortDate(), row.rank(), row.id());
        }
    }

    public record Decoded(Position position, boolean odoo) {}

    /** The page to show and where the next one starts (null: last page). */
    public record Merged(List<InvoiceRow> rows, Position next, boolean odoo) {}

    /** sortDate desc (unknown dates last), rank asc, id desc. */
    public static final Comparator<Position> ORDER = (left, right) -> {
        int dates = compareDatesDesc(left.sortDate(), right.sortDate());
        if (dates != 0) return dates;
        int ranks = Integer.compare(left.rank(), right.rank());
        if (ranks != 0) return ranks;
        return Long.compare(right.id(), left.id());
    };

    public static final Comparator<InvoiceRow> ROW_ORDER = Comparator.comparing(Position::of, ORDER);

    private static int compareDatesDesc(LocalDate left, LocalDate right) {
        if (Objects.equals(left, right)) return 0;
        if (left == null) return 1;
        if (right == null) return -1;
        return right.compareTo(left);
    }

    /** True when {@code row} comes strictly after {@code cursor} in the order. */
    public static boolean after(Position row, Position cursor) {
        return cursor == null || ORDER.compare(row, cursor) > 0;
    }

    /** First 8 hex of SHA-256 over the filters a cursor belongs to. */
    public static String filterHash(String direction, String scope, String q, LocalDate from, LocalDate to, Long orderId) {
        String text = String.join("|", text(direction), text(scope), InvoiceSearchText.normalize(q), text(from), text(to),
                text(orderId));
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, 8);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static String text(Object value) {
        return value == null ? "" : value.toString();
    }

    public static String encode(Position position, boolean odoo, String filterHash) {
        String plain = String.join("|", VERSION, text(position.sortDate()), String.valueOf(position.rank()),
                String.valueOf(position.id()), odoo ? "1" : "0", filterHash);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(plain.getBytes(StandardCharsets.UTF_8));
    }

    /** The cursor of a request; INVALID_CURSOR when it cannot be read, has another version or other filters. */
    public static Decoded decode(String cursor, String filterHash) {
        if (cursor == null || cursor.isBlank()) return null;
        try {
            if (cursor.length() > 200) throw new IllegalArgumentException("too long");
            String plain = new String(Base64.getUrlDecoder().decode(cursor.strip()), StandardCharsets.UTF_8);
            String[] parts = plain.split("\\|", -1);
            if (parts.length != 6 || !VERSION.equals(parts[0]) || !parts[5].equals(filterHash))
                throw new IllegalArgumentException("other cursor");
            LocalDate date = parts[1].isEmpty() ? null : LocalDate.parse(parts[1]);
            int rank = Integer.parseInt(parts[2]);
            long id = Long.parseLong(parts[3]);
            if (rank < 1 || rank > ODOO_RANK || id < 0 || !parts[4].equals("0") && !parts[4].equals("1"))
                throw new IllegalArgumentException("out of range");
            return new Decoded(new Position(date, rank, id), parts[4].equals("1"));
        } catch (RuntimeException unreadable) {
            throw InvoiceFinderErrors.invalid("INVALID_CURSOR");
        }
    }

    /**
     * Merges the ENROSED rows after the cursor with the Odoo rows kept from this request's calls.
     * When {@code scanStop} is set (Odoo still had moves the calls did not reach), only rows at or before it are safe:
     * an unscanned move could sort between the later ones. A short page with a cursor is possible only then.
     */
    public static Merged merge(List<InvoiceRow> erpAfter, List<InvoiceRow> keptOdoo, Position scanStop, boolean odooMore,
                               int limit) {
        List<InvoiceRow> candidates = new ArrayList<>(erpAfter == null ? List.of() : erpAfter);
        List<InvoiceRow> odoo = keptOdoo == null ? List.of() : keptOdoo;
        candidates.addAll(odoo);
        candidates.sort(ROW_ORDER);
        List<InvoiceRow> safe = scanStop == null ? candidates
                : candidates.stream().filter(row -> ORDER.compare(Position.of(row), scanStop) <= 0).toList();
        List<InvoiceRow> taken = List.copyOf(safe.subList(0, Math.min(limit, safe.size())));
        boolean odooLeft = odooMore || scanStop != null || odoo.stream().anyMatch(row -> !taken.contains(row));
        Position next;
        if (taken.size() == limit && (candidates.size() > taken.size() || odooMore)) next = Position.of(taken.getLast());
        else if (scanStop != null) next = scanStop;
        else next = null;
        return new Merged(taken, next, next != null && odooLeft);
    }
}
