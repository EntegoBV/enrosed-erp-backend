package be.enrosed.catalog.application;

import be.enrosed.catalog.application.port.out.ProductRepository;
import be.enrosed.catalog.domain.Barcodes;
import be.enrosed.catalog.domain.Carton;
import be.enrosed.catalog.domain.CatalogChannel;
import be.enrosed.catalog.domain.Dimensions;
import be.enrosed.catalog.domain.Product;
import be.enrosed.catalog.domain.PublicationState;
import be.enrosed.shared.BusinessRuleException;
import be.enrosed.shared.Csv;
import be.enrosed.shared.Currency;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Bulk editing the catalogue through a spreadsheet.
 *
 * One row per product with the universal fields: HS code, dimensions, carton,
 * barcodes, prices. Fixing forty HS codes one edit screen at a time is the
 * kind of chore that never gets finished; in a spreadsheet it is ten minutes.
 *
 * Two rules keep the import safe:
 *
 *  - rows match on SKU, and an unknown SKU is reported, never created — a
 *    typo must not silently grow the catalogue;
 *  - an empty cell means "leave as is", not "clear". A bulk file usually
 *    edits one or two columns, and wiping every field someone did not fill
 *    in would destroy the rest of the catalogue on first import.
 *
 * Stock is deliberately absent: it belongs to purchasing, where receiving a
 * container books it. Translations have their own file per language.
 */
@ApplicationScoped
public class ProductCsv {

    static final List<String> HEADERS = List.of(
            "sku", "naam", "beschrijving", "kleur", "hs_code",
            "lengte_cm", "breedte_cm", "hoogte_cm",
            "doos_lengte_cm", "doos_breedte_cm", "doos_hoogte_cm",
            "stuks_per_doos", "doos_gewicht_kg",
            "barcode_inner", "barcode_outer",
            "exw_prijs", "exw_munt", "opslag_pct", "vaste_verkoopprijs_eur", "actief",
            "family_key", "public_handle", "website_status", "order_app_status");

    private final ProductRepository products;
    private final ProductValidator validator;

    public ProductCsv(ProductRepository products, ProductValidator validator) {
        this.products = products;
        this.validator = validator;
    }

    public record ImportResult(int updatedProducts, List<String> problems) {}

    /* ------------------------------------------------------------- export */

    public byte[] export() {
        StringBuilder out = new StringBuilder(Csv.BOM);
        Csv.writeRow(out, HEADERS);

        for (List<String> row : exportRows()) {
            Csv.writeRow(out, row);
        }
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** The same canonical rows are also used by the native Excel exchange. */
    public List<List<String>> exportRows() {
        List<List<String>> rows = new ArrayList<>();

        List<Product> all = new ArrayList<>(products.findAll());
        all.sort(java.util.Comparator.comparing(p -> p.sku() == null ? "" : p.sku()));

        for (Product product : all) {
            Dimensions size = product.dimensions() == null ? Dimensions.empty() : product.dimensions();
            Carton carton = product.carton() == null ? Carton.empty() : product.carton();
            Dimensions box = carton.dimensions() == null ? Dimensions.empty() : carton.dimensions();
            Barcodes codes = product.barcodes() == null ? Barcodes.none() : product.barcodes();

            rows.add(List.of(
                    blank(product.sku()), blank(product.name()), blank(product.description()),
                    blank(product.colour()), blank(product.hsCode()),
                    number(size.lengthCm()), number(size.widthCm()), number(size.heightCm()),
                    number(box.lengthCm()), number(box.widthCm()), number(box.heightCm()),
                    String.valueOf(carton.piecesPerCarton()), number(carton.weightKg()),
                    blank(codes.inner()), blank(codes.outer()),
                    number(product.exwPrice()),
                    product.exwCurrency() == null ? "USD" : product.exwCurrency().name(),
                    number(product.markupPct()), number(product.fixedSalesPriceEur()),
                    product.active() ? "ja" : "nee",
                    blank(product.familyKey()), blank(product.publicHandle()),
                    product.publicationState(CatalogChannel.WEBSITE).name(),
                    product.publicationState(CatalogChannel.ORDER_APP).name()));
        }
        return rows;
    }

    /* ------------------------------------------------------------- import */

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
        int updated = 0;

        for (int index = 0; index < rows.size(); index++) {
            int lineNumber = index + 2;
            List<String> cells = rows.get(index);
            if (cells == null || cells.stream().allMatch(value -> value == null || value.isBlank())) {
                continue;
            }
            String sku = cell(cells, 0);
            if (sku == null) {
                problems.add("Regel " + lineNumber + ": geen SKU");
                continue;
            }

            Product current = products.findBySku(sku).orElse(null);
            if (current == null) {
                problems.add("SKU " + sku + " bestaat niet");
                continue;
            }

            try {
                Product merged = merge(current, cells);
                validator.validate(merged);
                validatePublicationFields(merged, current.id());
                products.save(merged);
                updated++;
            } catch (IllegalArgumentException | BusinessRuleException e) {
                problems.add("Regel " + lineNumber + " (" + sku + "): " + e.getMessage());
            }
        }

        return new ImportResult(updated, problems);
    }

    /** A filled cell overwrites; an empty one leaves the product untouched. */
    private static Product merge(Product current, List<String> cells) {
        Dimensions size = current.dimensions() == null ? Dimensions.empty() : current.dimensions();
        Carton carton = current.carton() == null ? Carton.empty() : current.carton();
        Dimensions box = carton.dimensions() == null ? Dimensions.empty() : carton.dimensions();
        Barcodes codes = current.barcodes() == null ? Barcodes.none() : current.barcodes();

        return new Product(
                current.id(), current.sku(),
                text(cells, 1, current.name()),
                new Dimensions(
                        decimal(cells, 5, size.lengthCm()),
                        decimal(cells, 6, size.widthCm()),
                        decimal(cells, 7, size.heightCm())),
                text(cells, 3, current.colour()),
                text(cells, 2, current.description()),
                current.categoryId(), current.supplierId(),
                bool(cells, 19, current.active()),
                text(cells, 20, current.familyKey()),
                handle(cells, 21, current.publicHandle()),
                publicationState(cells, 22, current.publicationState(CatalogChannel.WEBSITE)),
                publicationState(cells, 23, current.publicationState(CatalogChannel.ORDER_APP)),
                new Barcodes(text(cells, 13, codes.inner()), text(cells, 14, codes.outer())),
                text(cells, 4, current.hsCode()),
                new Carton(
                        new Dimensions(
                                decimal(cells, 8, box.lengthCm()),
                                decimal(cells, 9, box.widthCm()),
                                decimal(cells, 10, box.heightCm())),
                        intValue(cells, 11, carton.piecesPerCarton()),
                        decimal(cells, 12, carton.weightKg())),
                decimal(cells, 15, current.exwPrice()),
                currency(cells, 16, current.exwCurrency()),
                current.extraUnitCost(),
                current.landedCostEur(), current.landedCostSource(),
                decimal(cells, 17, current.markupPct()),
                decimal(cells, 18, current.fixedSalesPriceEur()),
                current.stockQuantity(),
                current.photos(), current.texts());
    }

    private void validatePublicationFields(Product product, Long currentId) {
        if (product.publicHandle() != null
                && !product.publicHandle().matches("[a-z0-9]+(?:-[a-z0-9]+)*")) {
            throw new IllegalArgumentException(
                    "publieke handle mag alleen kleine letters, cijfers en koppeltekens bevatten");
        }
        if (product.publicHandle() != null) {
            products.findByPublicHandle(product.publicHandle()).ifPresent(existing -> {
                if (!java.util.Objects.equals(currentId, existing.id())) {
                    throw new IllegalArgumentException(
                            "publieke handle '" + product.publicHandle() + "' bestaat al");
                }
            });
        }
        boolean published = product.publicationState(CatalogChannel.WEBSITE) == PublicationState.PUBLISHED
                || product.publicationState(CatalogChannel.ORDER_APP) == PublicationState.PUBLISHED;
        if (published && !product.publicationIssues().isEmpty()) {
            throw new IllegalArgumentException(
                    "kan niet gepubliceerd worden: " + String.join("; ", product.publicationIssues()));
        }
    }

    /* ------------------------------------------------------------ parsing */

    private static void requireHeader(String header) {
        List<String> cells = Csv.parseRow(Csv.stripBom(header));
        if (cells.isEmpty() || !cells.get(0).trim().equalsIgnoreCase("sku")) {
            throw new BusinessRuleException(
                    "De eerste kolom moet 'sku' heten. Gebruik het bestand uit de export"
                            + " als vertrekpunt.");
        }
    }

    private static String cell(List<String> cells, int index) {
        if (index >= cells.size()) return null;
        String value = cells.get(index).trim();
        return value.isEmpty() ? null : value;
    }

    private static String text(List<String> cells, int index, String keep) {
        String value = cell(cells, index);
        return value == null ? keep : value;
    }

    /** Accepts both 1.5 and 1,5 — the file has been through Excel. */
    private static BigDecimal decimal(List<String> cells, int index, BigDecimal keep) {
        String value = cell(cells, index);
        if (value == null) return keep;
        try {
            return new BigDecimal(value.replace(',', '.'));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("'" + value + "' is geen getal");
        }
    }

    private static int intValue(List<String> cells, int index, int keep) {
        String value = cell(cells, index);
        if (value == null) return keep;
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("'" + value + "' is geen geheel getal");
        }
    }

    private static Currency currency(List<String> cells, int index, Currency keep) {
        String value = cell(cells, index);
        if (value == null) return keep;
        try {
            return Currency.valueOf(value.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("'" + value + "' is geen munt (USD, CNY of EUR)");
        }
    }

    private static PublicationState publicationState(
            List<String> cells, int index, PublicationState keep) {
        String value = cell(cells, index);
        if (value == null) return keep;
        try {
            return PublicationState.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "'" + value + "' is geen publicatiestatus (DRAFT, READY of PUBLISHED)");
        }
    }

    private static String handle(List<String> cells, int index, String keep) {
        String value = cell(cells, index);
        return value == null ? keep : value.toLowerCase(Locale.ROOT);
    }

    private static boolean bool(List<String> cells, int index, boolean keep) {
        String value = cell(cells, index);
        if (value == null) return keep;
        return switch (value.toLowerCase()) {
            case "ja", "yes", "true", "1" -> true;
            case "nee", "no", "false", "0" -> false;
            default -> throw new IllegalArgumentException("'" + value + "' is geen ja/nee");
        };
    }

    private static String blank(String value) {
        return value == null ? "" : value;
    }

    private static String number(BigDecimal value) {
        return value == null ? "" : value.stripTrailingZeros().toPlainString();
    }
}
