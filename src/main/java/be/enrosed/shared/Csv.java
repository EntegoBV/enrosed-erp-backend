package be.enrosed.shared;

import java.util.ArrayList;
import java.util.List;

/**
 * The CSV dialect Excel expects in this part of Europe.
 *
 * Semicolon separated, CRLF line endings, and a UTF-8 BOM so Excel does not
 * read "Rosé" as mojibake. Cells are quoted only when they contain the
 * separator, a quote or a line break; a doubled quote inside a quoted cell is
 * a literal quote. One implementation, because two CSV parsers that agree on
 * ninety percent of inputs are worse than none.
 */
public final class Csv {

    public static final char SEPARATOR = ';';
    /** Byte-order mark; makes Excel open the file as UTF-8. */
    public static final String BOM = "﻿";

    private Csv() {}

    public static void writeRow(StringBuilder out, List<String> cells) {
        for (int i = 0; i < cells.size(); i++) {
            if (i > 0) out.append(SEPARATOR);
            out.append(quote(cells.get(i)));
        }
        out.append("\r\n");
    }

    public static String quote(String value) {
        String text = value == null ? "" : value;
        boolean needed = text.indexOf(SEPARATOR) >= 0 || text.indexOf('"') >= 0
                || text.indexOf('\n') >= 0 || text.indexOf('\r') >= 0;
        if (!needed) return text;
        return '"' + text.replace("\"", "\"\"") + '"';
    }

    /** Parses one line the way Excel writes it. */
    public static List<String> parseRow(String line) {
        List<String> cells = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;

        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        current.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    current.append(c);
                }
            } else if (c == '"') {
                inQuotes = true;
            } else if (c == SEPARATOR) {
                cells.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        cells.add(current.toString());
        return cells;
    }

    /** Strips the BOM a round trip through Excel may have kept. */
    public static String stripBom(String line) {
        return line != null && line.startsWith(BOM) ? line.substring(1) : line;
    }
}
