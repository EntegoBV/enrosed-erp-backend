package be.enrosed.catalog.application;

import be.enrosed.catalog.application.port.out.ProductRepository;
import be.enrosed.catalog.domain.Product;
import be.enrosed.catalog.domain.ProductText;
import be.enrosed.shared.BusinessRuleException;
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
 * Vertalingen uitwisselen via CSV.
 *
 * Vertalen gebeurt niet in dit scherm maar in een spreadsheet, vaak door iemand
 * anders. Daarom één bestand eruit, met per product een rij per taal, en
 * hetzelfde bestand er weer in.
 *
 * Alleen naam, beschrijving en kleur staan erin. De rest van een product is
 * universeel; die kolommen in het bestand zetten nodigt uit tot wijzigen, en
 * een vertaler hoort geen doosafmetingen te kunnen aanpassen.
 *
 * Twee dingen die het bestand robuust maken in Excel:
 *  - een UTF-8 BOM, anders maakt Excel van "Rosé" iets anders
 *  - puntkomma als scheidingsteken, want dat verwacht Excel in het Nederlandse
 *    en Franse taalgebied
 */
@ApplicationScoped
public class ProductTranslationCsv {

    /** Puntkomma: wat Excel hier verwacht. */
    private static final char SEPARATOR = ';';

    /** Zodat Excel het bestand als UTF-8 opent in plaats van als Latin-1. */
    private static final String BOM = "﻿";

    private static final List<String> HEADERS =
            List.of("sku", "taal", "naam", "beschrijving", "kleur");

    private final ProductRepository products;

    public ProductTranslationCsv(ProductRepository products) {
        this.products = products;
    }

    /** Wat een import opgeleverd heeft; per regel gemeld in plaats van stil geslikt. */
    public record ImportResult(int updatedProducts, int updatedRows, List<String> problems) {}

    /* ------------------------------------------------------------- eruit */

    /**
     * Alle producten met per taal een rij.
     *
     * Ook talen die nog niet vertaald zijn krijgen een rij, met de basiswaarden
     * als vertrekpunt. Een vertaler die een leeg bestand krijgt weet niet waar
     * hij moet beginnen; nu staat er wat het is en overschrijft hij het.
     */
    public byte[] export() {
        StringBuilder out = new StringBuilder(BOM);
        writeRow(out, HEADERS);

        List<Product> all = new ArrayList<>(products.findAll());
        all.sort(java.util.Comparator.comparing(product ->
                product.sku() == null ? "" : product.sku()));

        for (Product product : all) {
            for (Language language : Language.values()) {
                ProductText text = product.textIn(language);
                writeRow(out, List.of(
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
     * Leest het bestand terug in.
     *
     * Onbekende SKU's en talen worden gemeld en overgeslagen, niet stil
     * genegeerd: een vertaalbestand met een typfout in de SKU levert anders een
     * import op die "gelukt" zegt terwijl er niets veranderd is.
     *
     * Een rij die exact gelijk is aan de basiswaarden telt als "niet vertaald".
     * Zo blijft een export-zonder-wijzigingen die je weer inleest zonder effect,
     * in plaats van elke taal te vullen met de Nederlandse tekst.
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

                List<String> cells = parseRow(line);
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

            products.save(replaceTexts(product, texts));
            updatedProducts++;
        }

        return new ImportResult(updatedProducts, updatedRows, problems);
    }

    /**
     * Velden die gelijk zijn aan de basiswaarde tellen niet als vertaling.
     *
     * Anders staat na de eerste import in elke taal de Nederlandse naam, en dan
     * lijkt alles vertaald terwijl er niets vertaald is.
     */
    private static ProductText withoutBaseValues(Product product, ProductText text) {
        return new ProductText(
                text.language(),
                sameAs(text.name(), product.name()) ? null : blankToNull(text.name()),
                sameAs(text.description(), product.description()) ? null : blankToNull(text.description()),
                sameAs(text.colour(), product.colour()) ? null : blankToNull(text.colour()));
    }

    private static Product replaceTexts(Product product, List<ProductText> texts) {
        return new Product(product.id(), product.sku(), product.name(), product.dimensions(),
                product.colour(), product.description(),
                product.categoryId(), product.supplierId(), product.active(),
                product.barcodes(), product.hsCode(), product.carton(),
                product.exwPrice(), product.exwCurrency(), product.extraUnitCost(),
                product.landedCostEur(), product.landedCostSource(),
                product.markupPct(), product.fixedSalesPriceEur(), product.stockQuantity(),
                product.photos(), texts);
    }

    /* ------------------------------------------------------------ csv */

    private static void requireHeader(String header) {
        List<String> cells = parseRow(header.startsWith(BOM) ? header.substring(1) : header);
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

    private static void writeRow(StringBuilder out, List<String> cells) {
        for (int i = 0; i < cells.size(); i++) {
            if (i > 0) out.append(SEPARATOR);
            out.append(quote(cells.get(i)));
        }
        /* CRLF: wat Excel op Windows verwacht. */
        out.append("\r\n");
    }

    /**
     * Zet een cel tussen aanhalingstekens zodra ze een scheidingsteken, een
     * aanhalingsteken of een regeleinde bevat. Een beschrijving met een
     * puntkomma erin zou het bestand anders uit elkaar trekken.
     */
    private static String quote(String value) {
        String text = nullToBlank(value);
        boolean needed = text.indexOf(SEPARATOR) >= 0 || text.indexOf('"') >= 0
                || text.indexOf('\n') >= 0 || text.indexOf('\r') >= 0;
        if (!needed) return text;
        return '"' + text.replace("\"", "\"\"") + '"';
    }

    /** Leest één regel met aanhalingstekens zoals Excel ze schrijft. */
    private static List<String> parseRow(String line) {
        List<String> cells = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;

        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    /* Twee aanhalingstekens op rij is er één in de tekst. */
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
