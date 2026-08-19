package be.enrosed.catalog.application;

import be.enrosed.catalog.application.port.out.ProductRepository;
import be.enrosed.catalog.domain.Product;
import be.enrosed.catalog.domain.ProductText;
import be.enrosed.shared.BusinessRuleException;
import be.enrosed.shared.Csv;
import be.enrosed.shared.Language;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Exchanging translations through CSV.
 *
 * Translating happens in a spreadsheet, often by someone else, not in this
 * screen. Hence one file out, with one row per language per product, and the
 * same file back in.
 *
 * Only name, description and colour are in it. The rest of a product is
 * universal; putting those columns in the file invites editing, and a
 * translator should not be able to change carton dimensions.
 *
 * Two things that make the file robust in Excel:
 *  - a UTF-8 BOM, otherwise Excel turns "Rosé" into something else
 *  - semicolon as separator, because that is what Excel expects in the Dutch
 *    and French locales
 */
@ApplicationScoped
public class ProductTranslationCsv {

    static final List<String> HEADERS =
            List.of("sku", "taal", "naam", "beschrijving", "kleur");

    private final ProductRepository products;

    public ProductTranslationCsv(ProductRepository products) {
        this.products = products;
    }

    /** What an import produced; reported per row instead of swallowed silently. */
    public record ImportResult(int updatedProducts, int updatedRows, List<String> problems) {}

    /* ------------------------------------------------------------- eruit */

    /**
     * All products with one row per language.
     *
     * Languages not yet translated get a row too, with the base values as a
     * starting point. A translator handed an empty file has no idea where to
     * begin; this way the file says what it is and they overwrite it.
     */
    public byte[] export() {
        StringBuilder out = new StringBuilder(Csv.BOM);
        Csv.writeRow(out, HEADERS);

        for (List<String> row : exportRows()) {
            Csv.writeRow(out, row);
        }
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** The canonical translation rows used by both CSV compatibility and Excel. */
    public List<List<String>> exportRows() {
        List<List<String>> rows = new ArrayList<>();

        List<Product> all = new ArrayList<>(products.findAll());
        all.sort(java.util.Comparator.comparing(product ->
                product.sku() == null ? "" : product.sku()));

        for (Product product : all) {
            for (Language language : Language.values()) {
                ProductText text = product.textIn(language);
                rows.add(List.of(
                        nullToBlank(product.sku()),
                        language.code(),
                        text == null || isBlank(text.name()) ? nullToBlank(product.name()) : text.name(),
                        text == null || isBlank(text.description())
                                ? nullToBlank(product.description()) : text.description(),
                        text == null || isBlank(text.colour()) ? nullToBlank(product.colour()) : text.colour()));
            }
        }
        return rows;
    }

    /* -------------------------------------------------------------- erin */

    /**
     * Reads the file back in.
     *
     * Unknown SKUs and languages are reported and skipped, not silently
     * ignored: a translation file with a typo in the SKU would otherwise
     * produce an import that says "done" while nothing changed.
     *
     * A row exactly equal to the base values counts as "not translated".
     * That keeps an export-without-changes read back in without effect,
     * instead of filling every language with the Dutch text.
     */
    @Transactional
    public ImportResult importFrom(InputStream input) {
        List<List<String>> rows = new ArrayList<>();

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(input, StandardCharsets.UTF_8))) {

            String header = reader.readLine();
            if (header == null) {
                throw new BusinessRuleException("Het bestand is leeg");
            }
            requireHeader(header);

            String line;
            while ((line = reader.readLine()) != null) {
                rows.add(line.isBlank() ? List.of() : Csv.parseRow(line));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Kan het bestand niet lezen", e);
        }

        return importRows(rows);
    }

    /** Imports canonical column rows; list index zero corresponds to spreadsheet row 2. */
    @Transactional
    public ImportResult importRows(List<List<String>> rows) {
        List<String> problems = new ArrayList<>();
        Map<String, Map<Language, ProductText>> perSku = new LinkedHashMap<>();

        for (int index = 0; index < rows.size(); index++) {
            int lineNumber = index + 2;
            List<String> cells = rows.get(index);
            if (cells == null || cells.stream().allMatch(value -> value == null || value.isBlank())) {
                continue;
            }
            if (cells.size() < 2) {
                problems.add("Regel " + lineNumber + ": te weinig kolommen");
                continue;
            }

            String sku = cells.get(0).trim();
            if (sku.isEmpty()) {
                problems.add("Regel " + lineNumber + ": geen SKU");
                continue;
            }

            String languageCode = cells.get(1).trim();
            Language language = parseLanguage(languageCode);
            if (language == null) {
                problems.add("Regel " + lineNumber + ": onbekende taal '" + languageCode + "'");
                continue;
            }

            perSku.computeIfAbsent(sku, key -> new LinkedHashMap<>())
                    .put(language, new ProductText(language,
                            cell(cells, 2), cell(cells, 3), cell(cells, 4)));
        }

        int updatedProducts = 0;
        int updatedRows = 0;

        for (Map.Entry<String, Map<Language, ProductText>> entry : perSku.entrySet()) {
            Product product = products.findBySku(entry.getKey()).orElse(null);
            if (product == null) {
                problems.add("SKU " + entry.getKey() + " bestaat niet");
                continue;
            }

            List<ProductText> texts = new ArrayList<>();
            for (ProductText text : entry.getValue().values()) {
                ProductText trimmed = withoutBaseValues(product, text);
                if (!trimmed.isEmpty()) {
                    texts.add(trimmed);
                    updatedRows++;
                }
            }

            products.save(product.withTexts(texts));
            updatedProducts++;
        }

        return new ImportResult(updatedProducts, updatedRows, problems);
    }

    /**
     * Validates the complete Excel exchange before either workbook sheet writes.
     *
     * CSV compatibility deliberately still permits a translator to upload a
     * partial file. The combined workbook is different: it is exported with
     * every language row, so an absent or duplicate row almost always means an
     * accidental deletion that must not replace the stored translation set.
     */
    List<String> validateCompleteWorkbookRows(List<List<String>> rows) {
        List<String> problems = new ArrayList<>();
        Map<String, Product> existingBySku = new LinkedHashMap<>();
        for (Product product : products.findAll()) {
            if (product.sku() != null && !product.sku().isBlank()) {
                existingBySku.put(product.sku(), product);
            }
        }

        Map<WorkbookRowKey, Integer> seen = new LinkedHashMap<>();
        for (int index = 0; index < rows.size(); index++) {
            List<String> cells = rows.get(index);
            if (cells == null || cells.stream().allMatch(value -> value == null || value.isBlank())) {
                continue;
            }

            int lineNumber = index + 2;
            String sku = cell(cells, 0);
            String languageCode = cell(cells, 1);
            if (sku == null) {
                problems.add("Vertalingen regel " + lineNumber + ": geen SKU");
                continue;
            }
            if (languageCode == null) {
                problems.add("Vertalingen regel " + lineNumber + ": geen taal");
                continue;
            }

            Language language = parseLanguage(languageCode);
            if (language == null) {
                problems.add("Vertalingen regel " + lineNumber
                        + ": onbekende taal '" + languageCode + "'");
                continue;
            }
            if (!existingBySku.containsKey(sku)) {
                problems.add("Vertalingen regel " + lineNumber + ": SKU " + sku + " bestaat niet");
                continue;
            }

            WorkbookRowKey key = new WorkbookRowKey(sku, language);
            Integer firstLine = seen.putIfAbsent(key, lineNumber);
            if (firstLine != null) {
                problems.add("Vertalingen regel " + lineNumber + ": " + sku + " / "
                        + language.code() + " staat dubbel (eerste keer op regel " + firstLine + ")");
            }
        }

        if (!problems.isEmpty()) return List.copyOf(problems);

        for (String sku : existingBySku.keySet()) {
            List<String> missing = new ArrayList<>();
            for (Language language : Language.values()) {
                if (!seen.containsKey(new WorkbookRowKey(sku, language))) {
                    missing.add(language.code());
                }
            }
            if (!missing.isEmpty()) {
                problems.add("Vertalingen: SKU " + sku + " mist "
                        + (missing.size() == 1 ? "taal " : "talen ") + String.join(", ", missing));
            }
        }
        return List.copyOf(problems);
    }

    /**
     * Fields equal to the base value do not count as a translation.
     *
     * Otherwise the first import leaves the Dutch name in every language,
     * and everything looks translated while nothing is.
     */
    private static ProductText withoutBaseValues(Product product, ProductText text) {
        return new ProductText(
                text.language(),
                sameAs(text.name(), product.name()) ? null : blankToNull(text.name()),
                sameAs(text.description(), product.description()) ? null : blankToNull(text.description()),
                sameAs(text.colour(), product.colour()) ? null : blankToNull(text.colour()));
    }


    /* ------------------------------------------------------------ csv */

    private static void requireHeader(String header) {
        List<String> cells = Csv.parseRow(Csv.stripBom(header));
        if (cells.size() < 2
                || !cells.get(0).trim().equalsIgnoreCase("sku")
                || !cells.get(1).trim().equalsIgnoreCase("taal")) {
            throw new BusinessRuleException(
                    "De eerste twee kolommen moeten 'sku' en 'taal' heten. Gebruik het bestand"
                            + " uit de export als vertrekpunt.");
        }
    }

    private static Language parseLanguage(String code) {
        for (Language language : Language.values()) {
            if (language.code().equalsIgnoreCase(code) || language.name().equalsIgnoreCase(code)) {
                return language;
            }
        }
        return null;
    }




    private static String cell(List<String> cells, int index) {
        if (index >= cells.size() || cells.get(index) == null) return null;
        String value = cells.get(index).trim();
        return value.isEmpty() ? null : value;
    }

    private static boolean sameAs(String value, String base) {
        return value != null && base != null && value.trim().equalsIgnoreCase(base.trim());
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String blankToNull(String value) {
        return isBlank(value) ? null : value.trim();
    }

    private static String nullToBlank(String value) {
        return value == null ? "" : value;
    }

    private record WorkbookRowKey(String sku, Language language) {}
}
