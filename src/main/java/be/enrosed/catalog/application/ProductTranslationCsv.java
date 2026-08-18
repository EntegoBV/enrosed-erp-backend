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

    private static final List<String> HEADERS =
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

        List<Product> all = new ArrayList<>(products.findAll());
        all.sort(java.util.Comparator.comparing(product ->
                product.sku() == null ? "" : product.sku()));

        for (Product product : all) {
            for (Language language : Language.values()) {
                ProductText text = product.textIn(language);
                Csv.writeRow(out, List.of(
                        nullToBlank(product.sku()),
                        language.code(),
                        text == null || isBlank(text.name()) ? nullToBlank(product.name()) : text.name(),
                        text == null || isBlank(text.description())
                                ? nullToBlank(product.description()) : text.description(),
                        text == null || isBlank(text.colour()) ? nullToBlank(product.colour()) : text.colour()));
            }
        }
        return out.toString().getBytes(StandardCharsets.UTF_8);
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
        List<String> problems = new ArrayList<>();
        Map<String, Map<Language, ProductText>> perSku = new LinkedHashMap<>();

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(input, StandardCharsets.UTF_8))) {

            String header = reader.readLine();
            if (header == null) {
                throw new BusinessRuleException("Het bestand is leeg");
            }
            requireHeader(header);

            String line;
            int lineNumber = 1;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (line.isBlank()) continue;

                List<String> cells = Csv.parseRow(line);
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
        } catch (IOException e) {
            throw new UncheckedIOException("Kan het bestand niet lezen", e);
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
        return index < cells.size() ? cells.get(index).trim() : null;
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
}
