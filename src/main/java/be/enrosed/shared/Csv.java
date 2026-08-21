package be.enrosed.shared;

import java.io.IOException;
import java.io.Reader;
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

    /** Parses a complete CSV stream, including quoted cells that contain physical line breaks. */
    public static List<List<String>> parseRows(Reader reader) throws IOException {
        StringBuilder document = new StringBuilder();
        char[] buffer = new char[4096];
        int read;
        while ((read = reader.read(buffer)) >= 0) document.append(buffer, 0, read);

        List<List<String>> rows = new ArrayList<>();
        List<String> cells = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        for (int index = 0; index < document.length(); index++) {
            char character = document.charAt(index);
            if (inQuotes) {
                if (character == '"') {
                    if (index + 1 < document.length() && document.charAt(index + 1) == '"') {
                        current.append('"');
                        index++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    current.append(character);
                }
                continue;
            }
            if (character == '"') {
                inQuotes = true;
            } else if (character == SEPARATOR) {
                cells.add(current.toString());
                current.setLength(0);
            } else if (character == '\n' || character == '\r') {
                if (character == '\r' && index + 1 < document.length()
                        && document.charAt(index + 1) == '\n') index++;
                cells.add(current.toString());
                current.setLength(0);
                if (!(cells.size() == 1 && cells.getFirst().isEmpty())) rows.add(List.copyOf(cells));
                cells.clear();
            } else {
                current.append(character);
            }
        }
        if (inQuotes) throw new IllegalArgumentException("CSV bevat een niet-afgesloten quote");
        if (!cells.isEmpty() || !current.isEmpty()) {
            cells.add(current.toString());
            rows.add(List.copyOf(cells));
        }
        if (!rows.isEmpty() && !rows.getFirst().isEmpty()) {
            List<String> header = new ArrayList<>(rows.getFirst());
            header.set(0, stripBom(header.getFirst()));
            rows.set(0, List.copyOf(header));
        }
        return List.copyOf(rows);
    }

    /** Strips the BOM a round trip through Excel may have kept. */
    public static String stripBom(String line) {
        return line != null && line.startsWith(BOM) ? line.substring(1) : line;
    }
}
