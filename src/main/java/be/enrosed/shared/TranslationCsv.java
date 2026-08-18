package be.enrosed.shared;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Loads a translation table from a semicolon-separated CSV on the classpath.
 *
 * The first column holds the key, the header names the language of every
 * other column. Same dialect as the product exports ({@link Csv}), so the
 * files open cleanly in Excel - translations are maintained by people, not
 * by code, and Excel is where that happens.
 *
 * Loading is strict on purpose: a missing file, an unknown language column
 * or a row with the wrong width throws at class initialisation, which makes
 * any test that touches translations fail immediately instead of shipping a
 * quote with silent gaps.
 */
final class TranslationCsv {

    private TranslationCsv() {}

    /** One insertion-ordered map of key to text per language column. */
    static Map<Language, Map<String, String>> load(String resource) {
        Map<Language, Map<String, String>> table = new EnumMap<>(Language.class);
        try (InputStream in = TranslationCsv.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("Translation file missing: " + resource);
            }
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(in, StandardCharsets.UTF_8));

            String headerLine = reader.readLine();
            if (headerLine == null) {
                throw new IllegalStateException(resource + " is empty");
            }
            List<String> header = Csv.parseRow(Csv.stripBom(headerLine));
            Language[] columns = new Language[header.size()];
            for (int i = 1; i < header.size(); i++) {
                columns[i] = Language.valueOf(header.get(i).trim().toUpperCase());
                table.put(columns[i], new LinkedHashMap<>());
            }

            String line;
            int lineNumber = 1;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (line.isBlank()) continue;
                List<String> cells = Csv.parseRow(line);
                if (cells.size() != header.size()) {
                    throw new IllegalStateException(resource + " line " + lineNumber
                            + ": expected " + header.size() + " columns, got " + cells.size());
                }
                String key = cells.get(0);
                for (int i = 1; i < cells.size(); i++) {
                    table.get(columns[i]).put(key, cells.get(i));
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Could not read " + resource, e);
        }
        table.replaceAll((language, texts) -> Collections.unmodifiableMap(texts));
        return Collections.unmodifiableMap(table);
    }
}
