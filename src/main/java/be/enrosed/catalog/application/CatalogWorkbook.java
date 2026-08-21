package be.enrosed.catalog.application;

import be.enrosed.shared.BusinessRuleException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataValidation;
import org.apache.poi.ss.usermodel.DataValidationConstraint;
import org.apache.poi.ss.usermodel.DataValidationHelper;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.ss.util.CellRangeAddressList;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Native Excel exchange for the catalogue fields that are safe to bulk-edit.
 *
 * The workbook deliberately keeps operational product data and customer-facing
 * translations on separate sheets. It uses readable Dutch headings, typed
 * number cells, filters, frozen identifiers and constrained choice columns.
 * Import maps by heading instead of position, so moving a column does not put a
 * price into a dimension by accident.
 */
@ApplicationScoped
public class CatalogWorkbook {

    public static final String MEDIA_TYPE =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    private static final String PRODUCTS_SHEET = "Producten";
    private static final String TRANSLATIONS_SHEET = "Vertalingen";
    private static final String GUIDE_SHEET = "Uitleg";
    private static final int MAX_DATA_ROWS = 100_000;
    private static final int MAX_SOURCE_COLUMNS = 128;
    private static final long MAX_LOGICAL_CELLS = 2_000_000L;

    private static final List<Column> PRODUCT_COLUMNS = List.of(
            text("sku", "SKU", 18),
            text("naam", "Productnaam", 28),
            wrapped("beschrijving", "Beschrijving (basis)", 42),
            text("kleur", "Kleur (basis)", 20),
            text("hs_code", "HS-code", 18),
            decimal("lengte_cm", "Product breedte B (cm)", 20, "Product lengte (cm)"),
            decimal("breedte_cm", "Product diepte D (cm)", 20, "Product breedte (cm)"),
            decimal("hoogte_cm", "Product hoogte H (cm)", 20, "Product hoogte (cm)"),
            decimal("doos_lengte_cm", "Doos breedte B (cm)", 19, "Doos lengte (cm)"),
            decimal("doos_breedte_cm", "Doos diepte D (cm)", 19, "Doos breedte (cm)"),
            decimal("doos_hoogte_cm", "Doos hoogte H (cm)", 19, "Doos hoogte (cm)"),
            integer("stuks_per_doos", "Stuks per doos", 16),
            decimal("doos_gewicht_kg", "Doosgewicht (kg)", 18),
            text("barcode_inner", "Barcode binnenverpakking", 26),
            text("barcode_outer", "Barcode omdoos", 22),
            decimal("exw_prijs", "EXW-prijs", 14),
            text("exw_munt", "EXW-munt", 13),
            decimal("opslag_pct", "Opslag (%)", 14),
            decimal("vaste_verkoopprijs_eur", "Vaste verkoopprijs (EUR)", 24),
            text("actief", "Actief (ja/nee)", 16),
            text("family_key", "Familiecode", 22),
            text("public_handle", "Publieke URL-naam", 24),
            text("website_status", "Website status", 18),
            text("order_app_status", "Orderapp status", 18),
            text("variant_size", "Variantmaat", 18),
            text("colour_hex", "Kleurstaal (#RRGGBB)", 22));

    private static final List<Column> TRANSLATION_COLUMNS = List.of(
            text("sku", "SKU", 18),
            text("taal", "Taal", 12),
            text("naam", "Productnaam", 30),
            wrapped("beschrijving", "Beschrijving", 48),
            text("kleur", "Kleur", 20));

    static {
        requireCanonicalColumns(ProductCsv.HEADERS, PRODUCT_COLUMNS);
        requireCanonicalColumns(ProductTranslationCsv.HEADERS, TRANSLATION_COLUMNS);
    }

    private final ProductCsv products;
    private final ProductTranslationCsv translations;

    public CatalogWorkbook(ProductCsv products, ProductTranslationCsv translations) {
        this.products = products;
        this.translations = translations;
    }

    public record ImportResult(
            int updatedProducts,
            int updatedRows,
            List<String> problems) {}

    /** Produces one immediately editable .xlsx workbook for the bulk-safe fields. */
    public byte[] export() {
        try (XSSFWorkbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            Styles styles = createStyles(workbook);
            createDataSheet(workbook, PRODUCTS_SHEET, PRODUCT_COLUMNS,
                    products.exportRows(), styles, Map.of(
                            16, new String[]{"USD", "CNY", "EUR"},
                            19, new String[]{"ja", "nee"},
                            22, new String[]{"DRAFT", "READY", "PUBLISHED"},
                            23, new String[]{"DRAFT", "READY", "PUBLISHED"}));
            createDataSheet(workbook, TRANSLATIONS_SHEET, TRANSLATION_COLUMNS,
                    translations.exportRows(), styles, Map.of(
                            1, new String[]{"nl", "fr", "en", "de", "es", "pl", "pt", "tr"}));
            createGuideSheet(workbook, styles);
            workbook.setActiveSheet(0);
            workbook.write(output);
            return output.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("Kan het Excel-bestand niet maken", exception);
        }
    }

    /** Reads both sheets before changing anything, then applies their safe row importers. */
    @Transactional
    public ImportResult importFrom(InputStream input) {
        try (XSSFWorkbook workbook = new XSSFWorkbook(input)) {
            ReadRows productRows = readRows(workbook, PRODUCTS_SHEET, PRODUCT_COLUMNS, "sku");
            ReadRows translationRows = readRows(
                    workbook, TRANSLATIONS_SHEET, TRANSLATION_COLUMNS,
                    "sku", "taal", "naam", "beschrijving", "kleur");

            List<String> workbookProblems = new ArrayList<>();
            workbookProblems.addAll(productRows.problems());
            workbookProblems.addAll(translationRows.problems());
            if (workbookProblems.isEmpty()) {
                workbookProblems.addAll(
                        translations.validateCompleteWorkbookRows(translationRows.rows()));
            }
            if (!workbookProblems.isEmpty()) {
                return new ImportResult(0, 0, List.copyOf(workbookProblems));
            }

            // Translate against the exported base text first. If the same workbook also
            // changes that base text, unchanged fallback cells must not become accidental
            // translations of the old product name.
            ProductTranslationCsv.ImportResult translationResult =
                    translations.importRows(translationRows.rows());
            ProductCsv.ImportResult productResult = products.importRows(productRows.rows());

            List<String> problems = new ArrayList<>();
            problems.addAll(productResult.problems());
            problems.addAll(translationResult.problems());
            return new ImportResult(productResult.updatedProducts(),
                    translationResult.updatedRows(), List.copyOf(problems));
        } catch (BusinessRuleException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw new BusinessRuleException(
                    "Dit is geen leesbaar Excel-bestand. Exporteer eerst een nieuw .xlsx-bestand "
                            + "en gebruik dat als vertrekpunt.");
        }
    }

    private static void createDataSheet(
            XSSFWorkbook workbook,
            String name,
            List<Column> columns,
            List<List<String>> rows,
            Styles styles,
            Map<Integer, String[]> choices) {
        Sheet sheet = workbook.createSheet(name);
        sheet.setDisplayGridlines(false);
        sheet.setZoom(90);
        sheet.createFreezePane(Math.min(2, columns.size()), 1);

        Row header = sheet.createRow(0);
        header.setHeightInPoints(32);
        for (int columnIndex = 0; columnIndex < columns.size(); columnIndex++) {
            Column column = columns.get(columnIndex);
            Cell cell = header.createCell(columnIndex, CellType.STRING);
            cell.setCellValue(column.label());
            cell.setCellStyle(styles.header());
            sheet.setColumnWidth(columnIndex, Math.min(255, column.width()) * 256);
        }

        for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
            Row row = sheet.createRow(rowIndex + 1);
            row.setHeightInPoints(24);
            List<String> values = rows.get(rowIndex);
            for (int columnIndex = 0; columnIndex < columns.size(); columnIndex++) {
                Column column = columns.get(columnIndex);
                String value = columnIndex < values.size() ? values.get(columnIndex) : "";
                writeValue(row.createCell(columnIndex), value, column, columnIndex == 0, styles);
            }
        }

        int lastRow = Math.max(1, rows.size());
        sheet.setAutoFilter(new CellRangeAddress(0, lastRow, 0, columns.size() - 1));
        choices.forEach((column, values) -> addChoiceValidation(sheet, column, values, lastRow));
    }

    private static void writeValue(
            Cell cell, String value, Column column, boolean identifier, Styles styles) {
        String safe = value == null ? "" : value;
        if (safe.isBlank()) {
            cell.setBlank();
            cell.setCellStyle(dataStyle(column, identifier, styles));
            return;
        }
        if (!safe.isBlank() && (column.kind() == Kind.DECIMAL || column.kind() == Kind.INTEGER)) {
            try {
                cell.setCellValue(new BigDecimal(safe.replace(',', '.')).doubleValue());
                cell.setCellStyle(column.kind() == Kind.INTEGER
                        ? styles.integer() : styles.decimal());
                return;
            } catch (NumberFormatException ignored) {
                // A malformed legacy value remains visible as text and will be reported on import.
            }
        }
        cell.setCellValue(safe);
        cell.setCellStyle(dataStyle(column, identifier, styles));
    }

    private static CellStyle dataStyle(Column column, boolean identifier, Styles styles) {
        if (identifier) return styles.identifier();
        return switch (column.kind()) {
            case DECIMAL -> styles.decimal();
            case INTEGER -> styles.integer();
            case WRAPPED -> styles.wrapped();
            case TEXT -> styles.text();
        };
    }

    private static void addChoiceValidation(
            Sheet sheet, int column, String[] values, int lastDataRow) {
        DataValidationHelper helper = sheet.getDataValidationHelper();
        DataValidationConstraint constraint = helper.createExplicitListConstraint(values);
        int finalRow = Math.max(1000, lastDataRow + 200);
        DataValidation validation = helper.createValidation(
                constraint, new CellRangeAddressList(1, finalRow, column, column));
        validation.setErrorStyle(DataValidation.ErrorStyle.STOP);
        validation.setShowErrorBox(true);
        validation.createErrorBox("Kies een geldige waarde",
                "Gebruik een waarde uit de keuzelijst in deze kolom.");
        validation.setShowPromptBox(true);
        validation.createPromptBox("Keuzelijst", "Kies een waarde uit de lijst.");
        sheet.addValidationData(validation);
    }

    private static void createGuideSheet(XSSFWorkbook workbook, Styles styles) {
        Sheet sheet = workbook.createSheet(GUIDE_SHEET);
        sheet.setDisplayGridlines(false);
        sheet.setColumnWidth(0, 28 * 256);
        sheet.setColumnWidth(1, 92 * 256);

        Row titleRow = sheet.createRow(0);
        titleRow.setHeightInPoints(34);
        Cell title = titleRow.createCell(0);
        title.setCellValue("Enrosed catalogus — zo werkt dit bestand");
        title.setCellStyle(styles.title());
        sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, 1));

        List<String[]> guidance = List.of(
                new String[]{"1. Producten", "Bewerk stamgegevens op het tabblad Producten. Eén rij is één SKU."},
                new String[]{"2. Vertalingen", "Bewerk klantteksten op Vertalingen. Eén rij is één SKU en taal."},
                new String[]{"3. Opslaan", "Bewaar als .xlsx en importeer hetzelfde bestand terug in Instellingen."},
                new String[]{"SKU", "Niet wijzigen: de SKU koppelt elke rij veilig aan het bestaande product."},
                new String[]{"Lege productcel", "Laat het bestaande productveld ongemoeid."},
                new String[]{"Lege vertaling", "Verwijdert de vertaling voor dat veld; de basistekst wordt dan gebruikt."},
                new String[]{"Keuzelijsten", "Gebruik de dropdowns voor munt, actief, taal en publicatiestatus."},
                new String[]{"Maatvolgorde", "Alle product- en doosmaten staan als Breedte × Diepte × Hoogte (B × D × H)."},
                new String[]{"Variantmaat", "Een verkoopoptie zoals S, XL of 25 cm; dit is iets anders dan de fysieke B × D × H."},
                new String[]{"Kleurstaal", "Gebruik exact #RRGGBB in hoofdletters, bijvoorbeeld #A91F32. Leeg laat de bestaande waarde staan."},
                new String[]{"Niet opgenomen", "Categorie, leverancier, voorraad, extra eenheidskosten "
                        + "en actuele landed cost blijven in het ERP en veranderen niet door deze import."});

        for (int index = 0; index < guidance.size(); index++) {
            Row row = sheet.createRow(index + 2);
            row.setHeightInPoints(index < 3 ? 28 : 34);
            Cell label = row.createCell(0);
            label.setCellValue(guidance.get(index)[0]);
            label.setCellStyle(styles.guideLabel());
            Cell description = row.createCell(1);
            description.setCellValue(guidance.get(index)[1]);
            description.setCellStyle(styles.guideText());
        }
        sheet.createFreezePane(0, 2);
    }

    private static ReadRows readRows(
            Workbook workbook, String sheetName, List<Column> columns, String... requiredKeys) {
        Sheet sheet = workbook.getSheet(sheetName);
        if (sheet == null) {
            throw new BusinessRuleException("Tabblad '" + sheetName
                    + "' ontbreekt. Gebruik het bestand uit de Excel-export als vertrekpunt.");
        }
        Row header = sheet.getRow(0);
        if (header == null) {
            throw new BusinessRuleException("Tabblad '" + sheetName + "' heeft geen kolomkoppen.");
        }
        validateSheetBounds(sheetName, sheet.getLastRowNum(),
                Math.max(columns.size(), Math.max(0, header.getLastCellNum())));

        Map<String, Integer> sourceColumns = new HashMap<>();
        for (Cell cell : header) {
            String normalized = normalize(cell.toString());
            if (!normalized.isEmpty()) {
                sourceColumns.putIfAbsent(normalized, cell.getColumnIndex());
            }
        }

        Map<String, Integer> canonicalIndexes = new LinkedHashMap<>();
        for (int index = 0; index < columns.size(); index++) {
            Column column = columns.get(index);
            canonicalIndexes.put(column.key(), index);
        }
        for (String requiredKey : requiredKeys) {
            Column required = columns.get(canonicalIndexes.get(requiredKey));
            if (findSourceColumn(sourceColumns, required) == null) {
                throw new BusinessRuleException("Kolom '" + required.label() + "' ontbreekt op tabblad '"
                        + sheetName + "'. Gebruik een nieuwe export als vertrekpunt.");
            }
        }

        List<List<String>> rows = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        for (int rowIndex = 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
            Row sourceRow = sheet.getRow(rowIndex);
            if (sourceRow != null && sourceRow.getLastCellNum() > MAX_SOURCE_COLUMNS) {
                throw tooLarge(sheetName);
            }
            List<String> canonical = new ArrayList<>();
            for (int index = 0; index < columns.size(); index++) canonical.add("");

            if (sourceRow != null) {
                for (int targetIndex = 0; targetIndex < columns.size(); targetIndex++) {
                    Column column = columns.get(targetIndex);
                    Integer sourceIndex = findSourceColumn(sourceColumns, column);
                    if (sourceIndex == null) continue;
                    Cell cell = sourceRow.getCell(sourceIndex, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
                    canonical.set(targetIndex,
                            importValue(cell, sheetName, rowIndex + 1, column, problems));
                }
            }
            rows.add(canonical);
        }
        return new ReadRows(rows, problems);
    }

    private static void validateSheetBounds(
            String sheetName, int lastRowIndex, int logicalColumns) {
        long dataRows = Math.max(0L, lastRowIndex);
        long logicalCells = (dataRows + 1L) * Math.max(1, logicalColumns);
        if (dataRows > MAX_DATA_ROWS || logicalColumns > MAX_SOURCE_COLUMNS
                || logicalCells > MAX_LOGICAL_CELLS) {
            throw tooLarge(sheetName);
        }
    }

    private static BusinessRuleException tooLarge(String sheetName) {
        return new BusinessRuleException("Tabblad '" + sheetName
                + "' bevat te veel gebruikte rijen of kolommen. Verwijder lege opmaak buiten "
                + "de catalogustabel of begin met een nieuwe export.");
    }

    private static Integer findSourceColumn(Map<String, Integer> sourceColumns, Column column) {
        Integer byLabel = sourceColumns.get(normalize(column.label()));
        if (byLabel != null) return byLabel;
        Integer byKey = sourceColumns.get(normalize(column.key()));
        if (byKey != null) return byKey;
        return column.legacyLabels().stream()
                .map(CatalogWorkbook::normalize)
                .map(sourceColumns::get)
                .filter(java.util.Objects::nonNull)
                .findFirst().orElse(null);
    }

    private static String importValue(
            Cell cell, String sheetName, int rowNumber, Column column, List<String> problems) {
        if (cell == null || cell.getCellType() == CellType.BLANK) return "";
        return switch (cell.getCellType()) {
            case STRING -> cell.getStringCellValue().trim();
            case NUMERIC -> BigDecimal.valueOf(cell.getNumericCellValue())
                    .stripTrailingZeros().toPlainString();
            case BOOLEAN -> Boolean.toString(cell.getBooleanCellValue());
            case FORMULA -> {
                problems.add(sheetName + " regel " + rowNumber + " ('" + column.label()
                        + "'): formules zijn niet toegestaan; er is niets geïmporteerd");
                yield "";
            }
            case ERROR -> {
                problems.add(sheetName + " regel " + rowNumber + " ('" + column.label()
                        + "'): Excel-fout in cel; er is niets geïmporteerd");
                yield "";
            }
            default -> "";
        };
    }

    private static Styles createStyles(XSSFWorkbook workbook) {
        Font headerFont = workbook.createFont();
        headerFont.setBold(true);
        headerFont.setColor(IndexedColors.WHITE.getIndex());
        headerFont.setFontHeightInPoints((short) 11);

        CellStyle header = workbook.createCellStyle();
        header.setFont(headerFont);
        header.setFillForegroundColor(IndexedColors.DARK_TEAL.getIndex());
        header.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        header.setVerticalAlignment(VerticalAlignment.CENTER);
        header.setWrapText(true);

        CellStyle text = baseDataStyle(workbook);
        text.setDataFormat(workbook.createDataFormat().getFormat("@"));
        CellStyle identifier = baseDataStyle(workbook);
        identifier.setDataFormat(workbook.createDataFormat().getFormat("@"));
        identifier.setFillForegroundColor(IndexedColors.LIGHT_CORNFLOWER_BLUE.getIndex());
        identifier.setFillPattern(FillPatternType.SOLID_FOREGROUND);

        CellStyle wrapped = baseDataStyle(workbook);
        wrapped.setDataFormat(workbook.createDataFormat().getFormat("@"));
        wrapped.setWrapText(true);

        CellStyle decimal = baseDataStyle(workbook);
        decimal.setAlignment(HorizontalAlignment.RIGHT);
        decimal.setDataFormat(workbook.createDataFormat().getFormat("0.00########"));

        CellStyle integer = baseDataStyle(workbook);
        integer.setAlignment(HorizontalAlignment.RIGHT);
        integer.setDataFormat(workbook.createDataFormat().getFormat("0"));

        Font titleFont = workbook.createFont();
        titleFont.setBold(true);
        titleFont.setFontHeightInPoints((short) 16);
        titleFont.setColor(IndexedColors.DARK_TEAL.getIndex());
        CellStyle title = workbook.createCellStyle();
        title.setFont(titleFont);
        title.setVerticalAlignment(VerticalAlignment.CENTER);

        Font labelFont = workbook.createFont();
        labelFont.setBold(true);
        labelFont.setColor(IndexedColors.DARK_TEAL.getIndex());
        CellStyle guideLabel = baseDataStyle(workbook);
        guideLabel.setFont(labelFont);
        guideLabel.setWrapText(true);
        CellStyle guideText = baseDataStyle(workbook);
        guideText.setWrapText(true);

        return new Styles(header, text, identifier, wrapped, decimal, integer,
                title, guideLabel, guideText);
    }

    private static CellStyle baseDataStyle(Workbook workbook) {
        CellStyle style = workbook.createCellStyle();
        style.setVerticalAlignment(VerticalAlignment.TOP);
        style.setBorderBottom(BorderStyle.THIN);
        style.setBottomBorderColor(IndexedColors.GREY_25_PERCENT.getIndex());
        return style;
    }

    private static void requireCanonicalColumns(List<String> keys, List<Column> columns) {
        List<String> workbookKeys = columns.stream().map(Column::key).toList();
        if (!keys.equals(workbookKeys)) {
            throw new IllegalStateException("Excel- en cataloguskolommen lopen niet gelijk");
        }
    }

    private static String normalize(String value) {
        if (value == null) return "";
        String withoutAccents = Normalizer.normalize(value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
        return withoutAccents.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private static Column text(String key, String label, int width) {
        return new Column(key, label, Kind.TEXT, width, List.of());
    }

    private static Column wrapped(String key, String label, int width) {
        return new Column(key, label, Kind.WRAPPED, width, List.of());
    }

    private static Column decimal(String key, String label, int width) {
        return decimal(key, label, width, new String[0]);
    }

    private static Column decimal(String key, String label, int width, String... legacyLabels) {
        return new Column(key, label, Kind.DECIMAL, width, List.of(legacyLabels));
    }

    private static Column integer(String key, String label, int width) {
        return new Column(key, label, Kind.INTEGER, width, List.of());
    }

    private enum Kind { TEXT, WRAPPED, DECIMAL, INTEGER }

    private record Column(String key, String label, Kind kind, int width,
                          List<String> legacyLabels) {}

    private record ReadRows(List<List<String>> rows, List<String> problems) {}

    private record Styles(
            CellStyle header,
            CellStyle text,
            CellStyle identifier,
            CellStyle wrapped,
            CellStyle decimal,
            CellStyle integer,
            CellStyle title,
            CellStyle guideLabel,
            CellStyle guideText) {}
}
