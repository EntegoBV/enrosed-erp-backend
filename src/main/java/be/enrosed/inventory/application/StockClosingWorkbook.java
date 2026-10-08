package be.enrosed.inventory.application;

import be.enrosed.inventory.application.ClosingReportData.Column;
import be.enrosed.inventory.application.ClosingReportData.Kind;
import be.enrosed.inventory.application.ClosingReportData.SummaryLine;
import be.enrosed.inventory.application.ClosingReportData.Table;
import jakarta.enterprise.context.ApplicationScoped;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * The Excel workbook of a closing for the accountant: the valued stock per
 * location and per product, the FIFO build-up, every container with its
 * payee streams and allocation keys, and the lists behind each decision.
 *
 * Amounts, unit values, quantities and dates are typed cells, so the
 * accountant can add up and filter without converting text. Every sheet is
 * built from the stored rows of the closing; nothing is read live.
 */
@ApplicationScoped
public class StockClosingWorkbook {

    public static final String SHEET_SUMMARY = "Samenvatting";
    public static final String SHEET_LOCATIONS = "Voorraad per locatie";
    public static final String SHEET_PRODUCTS = "Producten";
    public static final String SHEET_LAYERS = "Partijen (FIFO)";
    public static final String SHEET_CONTAINERS = "Containers";
    public static final String SHEET_LOTS = "Containerlijnen";
    public static final String SHEET_PAYMENTS = "Betalingen";
    public static final String SHEET_CREDITS = "Creditnota's";
    public static final String SHEET_ESTIMATED = "Geschatte kosten";
    public static final String SHEET_WRITE_DOWNS = "Waardeverminderingen";
    public static final String SHEET_PARTNER = "Partner en derden";
    public static final String SHEET_TRANSIT = "Onderweg";
    public static final String SHEET_INVOICED = "Gefactureerd niet afgepunt";
    public static final String SHEET_COUNT = "Telverschillen";
    public static final String SHEET_MOVEMENTS = "Bewegingen";
    public static final String SHEET_CHANGES = "Wijzigingen";
    public static final String SHEET_DECISIONS = "Beslissingen";
    public static final String SHEET_NOTICES = "Aandachtspunten";
    public static final String SHEET_RULE = "Waarderingsregel";

    /** The cell styles of one workbook; those of the catalogue workbook are its own. */
    private record Styles(CellStyle header, CellStyle text, CellStyle wrapped, CellStyle count, CellStyle money, CellStyle unit,
                          CellStyle rate, CellStyle day, CellStyle moment, CellStyle note, CellStyle totalText,
                          CellStyle totalCount, CellStyle totalMoney) {}

    public byte[] render(ClosingReportData data) {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            Styles styles = styles(workbook);
            summary(workbook, data, styles);
            sheet(workbook, SHEET_LOCATIONS, ClosingReportData.LOCATION_NOTE, styles, data.perLocation());
            sheet(workbook, SHEET_PRODUCTS, null, styles, data.products());
            sheet(workbook, SHEET_LAYERS, null, styles, data.fifoLayers());
            sheet(workbook, SHEET_CONTAINERS, null, styles, data.containerTable());
            sheet(workbook, SHEET_LOTS, ClosingReportData.LOT_NOTE, styles, data.lotTable());
            sheet(workbook, SHEET_PAYMENTS, null, styles, data.paymentTable());
            sheet(workbook, SHEET_CREDITS, null, styles, data.creditTable());
            sheet(workbook, SHEET_ESTIMATED, null, styles, data.estimatedTable());
            sheet(workbook, SHEET_WRITE_DOWNS, null, styles, data.writeDownTable());
            sheet(workbook, SHEET_PARTNER, null, styles, data.partnerTable(), data.thirdPartyTable());
            sheet(workbook, SHEET_TRANSIT, null, styles, data.transitTable());
            if (!data.kind(FifoValuer.KIND_TRANSIT).isEmpty()) {
                /* Under the table, so the header row stays the first row and keeps its filter. */
                Sheet transit = workbook.getSheet(SHEET_TRANSIT);
                label(transit.createRow(transit.getLastRowNum() + 2), ClosingReportData.TRANSIT_NOTE, styles.note());
            }
            sheet(workbook, SHEET_INVOICED, null, styles, data.invoicedTable(), data.olderInvoiceTable());
            sheet(workbook, SHEET_COUNT, null, styles, data.countDifferenceTable());
            sheet(workbook, SHEET_MOVEMENTS, null, styles, data.movementTable());
            if (data.correction()) sheet(workbook, SHEET_CHANGES, data.changesTitle(), styles, data.changeTable());
            sheet(workbook, SHEET_DECISIONS, null, styles, data.decisionTable());
            sheet(workbook, SHEET_NOTICES, null, styles, data.noticeTable());
            rule(workbook, data, styles);
            workbook.setActiveSheet(0);
            workbook.write(output);
            return output.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("Kan het Excel-bestand niet maken", exception);
        }
    }

    /* ---------------------------------------------------------------- sheets */

    private static void summary(XSSFWorkbook workbook, ClosingReportData data, Styles styles) {
        Sheet sheet = workbook.createSheet(SHEET_SUMMARY);
        sheet.setDisplayGridlines(false);
        sheet.setColumnWidth(0, 62 * 256);
        sheet.setColumnWidth(1, 44 * 256);
        int at = header(sheet, 0, List.of(new Column("Gegeven", Kind.TEXT), new Column("Waarde", Kind.ANY)), styles, false);
        sheet.createFreezePane(0, at);
        for (SummaryLine line : data.summary()) {
            Row row = sheet.createRow(at++);
            label(row, line.label(), line.total() ? styles.totalText() : styles.text());
            Cell value = row.createCell(1);
            if (line.quantity() != null) {
                value.setCellValue(line.quantity());
                value.setCellStyle(styles.count());
            } else {
                value.setCellValue(line.amountEur().doubleValue());
                value.setCellStyle(line.total() ? styles.totalMoney() : styles.money());
            }
        }
        if (data.marketNote() != null) label(sheet.createRow(at++), data.marketNote(), styles.note());
        var closing = data.closing();
        /* Opened on its own, the workbook says whose stock it is. */
        at = fact(sheet, at, "Onderneming", data.company().name(), styles);
        at = fact(sheet, at, "Btw-nummer", data.company().vat(), styles);
        at = fact(sheet, at, "Adres", data.company().address(), styles);
        at = fact(sheet, at, "Boekjaar", closing.closingYear, styles);
        at = fact(sheet, at, "Afsluitdatum", closing.closingDate, styles);
        at = fact(sheet, at, "Versie", closing.versionNo, styles);
        at = fact(sheet, at, "Status", data.statusLabel(), styles);
        at = fact(sheet, at, "Methode", closing.ruleMethodLabel, styles);
        at = fact(sheet, at, "Ondertekenaar", closing.signerName, styles);
        at = fact(sheet, at, "Definitief op", closing.finalizedAt, styles);
        fact(sheet, at, "Gegevenscontrole", closing.dataSha256, styles);
    }

    private static int fact(Sheet sheet, int at, String name, Object value, Styles styles) {
        Row row = sheet.createRow(at);
        label(row, name, styles.text());
        write(row.createCell(1), value, Kind.ANY, styles, false);
        return at + 1;
    }

    /**
     * One sheet: an optional note row, then each table with its header row, its rows and its totals.
     * The first header row stays in view and carries the filter; a later table starts under its title.
     */
    private static void sheet(XSSFWorkbook workbook, String name, String note, Styles styles, Table... tables) {
        Sheet sheet = workbook.createSheet(name);
        sheet.setDisplayGridlines(false);
        int at = 0;
        if (note != null) label(sheet.createRow(at++), note, styles.note());
        boolean first = true;
        for (Table table : tables) {
            if (!first) {
                at++;
                label(sheet.createRow(at++), table.title(), styles.totalText());
            }
            int headerRow = at;
            at = header(sheet, at, table.columns(), styles, first);
            for (List<Object> values : table.rows()) at = row(sheet, at, table.columns(), values, styles, false);
            if (first) {
                sheet.createFreezePane(0, headerRow + 1);
                sheet.setAutoFilter(new CellRangeAddress(headerRow, Math.max(headerRow + 1, at - 1), 0, table.columns().size() - 1));
            }
            if (table.totals() != null) at = row(sheet, at, table.columns(), table.totals(), styles, true);
            first = false;
        }
    }

    private static void rule(XSSFWorkbook workbook, ClosingReportData data, Styles styles) {
        Sheet sheet = workbook.createSheet(SHEET_RULE);
        sheet.setDisplayGridlines(false);
        sheet.setColumnWidth(0, 160 * 256);
        sheet.setColumnWidth(1, 40 * 256);
        int at = header(sheet, 0, List.of(new Column(SHEET_RULE, Kind.TEXT)), styles, false);
        sheet.createFreezePane(0, at);
        String text = data.closing().ruleText == null ? "" : data.closing().ruleText;
        for (String paragraph : text.split("\\R")) {
            if (!paragraph.isBlank()) label(sheet.createRow(at++), paragraph, styles.wrapped());
        }
        at = fact(sheet, at, "Methode", data.closing().ruleMethodLabel, styles);
        fact(sheet, at, "Van toepassing sinds", data.closing().ruleEffectiveFromYear, styles);
    }

    /* ----------------------------------------------------------------- cells */

    private static int header(Sheet sheet, int at, List<Column> columns, Styles styles, boolean widths) {
        Row header = sheet.createRow(at);
        header.setHeightInPoints(32);
        for (int index = 0; index < columns.size(); index++) {
            Cell cell = header.createCell(index);
            cell.setCellValue(columns.get(index).title());
            cell.setCellStyle(styles.header());
            if (widths) sheet.setColumnWidth(index, width(columns.get(index)) * 256);
        }
        return at + 1;
    }

    private static int width(Column column) {
        return switch (column.kind()) {
            case TEXT, ANY -> Math.max(18, Math.min(40, column.title().length() + 4));
            case MOMENT -> 18;
            default -> Math.max(14, Math.min(26, column.title().length() + 2));
        };
    }

    private static int row(Sheet sheet, int at, List<Column> columns, List<Object> values, Styles styles, boolean total) {
        Row row = sheet.createRow(at);
        for (int index = 0; index < columns.size(); index++) {
            Object value = index < values.size() ? values.get(index) : null;
            if (value == null && !total) continue;
            write(row.createCell(index), value, columns.get(index).kind(), styles, total);
        }
        return at + 1;
    }

    private static void label(Row row, String text, CellStyle style) {
        Cell cell = row.createCell(0);
        cell.setCellValue(text);
        cell.setCellStyle(style);
    }

    /** Writes a value as the type it has: a number stays a number, a day a day, yes or no a word. */
    private static void write(Cell cell, Object value, Kind kind, Styles styles, boolean total) {
        switch (value) {
            case null -> cell.setCellStyle(total ? styles.totalText() : styles.text());
            case Integer number -> {
                cell.setCellValue(number);
                cell.setCellStyle(total ? styles.totalCount() : styles.count());
            }
            case Long number -> {
                cell.setCellValue(number);
                cell.setCellStyle(total ? styles.totalCount() : styles.count());
            }
            case BigDecimal number -> {
                cell.setCellValue(number.doubleValue());
                boolean fine = kind == Kind.UNIT || kind == Kind.ANY && number.scale() > 2;
                cell.setCellStyle(total ? styles.totalMoney() : kind == Kind.RATE ? styles.rate() : fine ? styles.unit() : styles.money());
            }
            case LocalDate day -> {
                cell.setCellValue(day);
                cell.setCellStyle(styles.day());
            }
            case Instant moment -> {
                cell.setCellValue(LocalDateTime.ofInstant(moment, InventoryClock.BRUSSELS));
                cell.setCellStyle(styles.moment());
            }
            case Boolean yes -> {
                cell.setCellValue(yes ? "ja" : "nee");
                cell.setCellStyle(styles.text());
            }
            default -> {
                cell.setCellValue(value.toString());
                cell.setCellStyle(total ? styles.totalText() : styles.text());
            }
        }
    }

    private static Styles styles(XSSFWorkbook workbook) {
        Font white = workbook.createFont();
        white.setBold(true);
        white.setColor(IndexedColors.WHITE.getIndex());
        CellStyle header = workbook.createCellStyle();
        header.setFont(white);
        header.setFillForegroundColor(IndexedColors.DARK_TEAL.getIndex());
        header.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        header.setVerticalAlignment(VerticalAlignment.CENTER);
        header.setWrapText(true);

        Font bold = workbook.createFont();
        bold.setBold(true);
        Font italic = workbook.createFont();
        italic.setItalic(true);

        CellStyle text = workbook.createCellStyle();
        text.setVerticalAlignment(VerticalAlignment.TOP);
        CellStyle wrapped = workbook.createCellStyle();
        wrapped.setVerticalAlignment(VerticalAlignment.TOP);
        wrapped.setWrapText(true);
        CellStyle note = workbook.createCellStyle();
        note.setFont(italic);
        CellStyle totalText = workbook.createCellStyle();
        totalText.setFont(bold);
        return new Styles(header, text, wrapped, number(workbook, "0", null), number(workbook, "#,##0.00", null),
                number(workbook, "#,##0.0000", null), number(workbook, "0.0000####", null), number(workbook, "dd/mm/yyyy", null),
                number(workbook, "dd/mm/yyyy hh:mm", null), note, totalText, number(workbook, "0", bold),
                number(workbook, "#,##0.00", bold));
    }

    private static CellStyle number(XSSFWorkbook workbook, String format, Font font) {
        CellStyle style = workbook.createCellStyle();
        style.setAlignment(HorizontalAlignment.RIGHT);
        style.setVerticalAlignment(VerticalAlignment.TOP);
        style.setDataFormat(workbook.createDataFormat().getFormat(format));
        if (font != null) style.setFont(font);
        return style;
    }
}
