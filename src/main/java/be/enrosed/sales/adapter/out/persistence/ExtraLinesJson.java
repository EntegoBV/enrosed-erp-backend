package be.enrosed.sales.adapter.out.persistence;

import be.enrosed.sales.domain.SalesExtraLine;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;

/**
 * The free lines of a document as one JSON column. A handful of them per
 * document does not deserve its own table, and one column keeps the
 * document row self-contained for exports and backups.
 */
final class ExtraLinesJson {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<List<SalesExtraLine>> LIST = new TypeReference<>() {};

    private ExtraLinesJson() {}

    /** Null for an empty list, so untouched documents keep a null column. */
    static String write(List<SalesExtraLine> lines) {
        if (lines == null || lines.isEmpty()) return null;
        try {
            return JSON.writeValueAsString(lines);
        } catch (Exception exception) {
            throw new IllegalStateException("Extra regels konden niet worden opgeslagen", exception);
        }
    }

    /** An unreadable column reads as no lines rather than blocking the document. */
    static List<SalesExtraLine> read(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            List<SalesExtraLine> lines = JSON.readValue(json, LIST);
            return lines == null ? List.of() : lines;
        } catch (Exception exception) {
            return List.of();
        }
    }
}
