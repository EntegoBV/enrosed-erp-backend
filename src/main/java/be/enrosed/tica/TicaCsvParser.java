package be.enrosed.tica;

import jakarta.enterprise.context.ApplicationScoped;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.*;

/** Validates an entire export before anything is persisted. Customer fields are deliberately discarded. */
@ApplicationScoped
public class TicaCsvParser {
    private static final List<String> REQUIRED = List.of("Locatie", "Exposantennummer", "Datum", "Tijd",
            "Transactienummer", "Kassabonregel", "Artikelomschrijving", "Beeyond artikelnummer",
            "Uw artikelnummer", "Barcode", "BTW artikel", "Aantal", "Kortingspercentage",
            "Regeltotaal bruto", "Regeltotaal netto", "Transactietype");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d-M-uuuu").withResolverStyle(ResolverStyle.STRICT);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss").withResolverStyle(ResolverStyle.STRICT);

    public record Row(String location, String exhibitor, LocalDateTime soldAt, String transactionNumber,
                      String lineNumber, String description, String beeyondArticleNumber, String sku,
                      String barcode, BigDecimal quantity, BigDecimal discountPercent, BigDecimal grossTotal,
                      BigDecimal netTotal, BigDecimal vatRate, String transactionType) {
        public String key() { return exhibitor + "|" + location + "|" + transactionNumber + "|" + lineNumber; }
        public String transactionKey() { return exhibitor + "|" + location + "|" + transactionNumber; }
    }

    public List<Row> parse(String csv, String expectedExhibitor, LocalDate from, LocalDate to) {
        if (csv == null || csv.isBlank() || csv.length() > 20_000_000)
            throw invalid("Lege of te grote export");
        List<List<String>> records = records(csv.charAt(0) == '\ufeff' ? csv.substring(1) : csv);
        if (records.isEmpty()) throw invalid("Kolomkoppen ontbreken");
        Map<String, Integer> header = new LinkedHashMap<>();
        for (int i = 0; i < records.getFirst().size(); i++) {
            String name = records.getFirst().get(i).strip();
            if (header.put(name, i) != null) throw invalid("Dubbele kolomkop");
        }
        if (!header.keySet().containsAll(REQUIRED)) throw invalid("Onverwachte kolomkoppen");
        Map<String, Row> unique = new LinkedHashMap<>();
        for (int i = 1; i < records.size(); i++) {
            List<String> cells = records.get(i);
            if (cells.size() == 1 && cells.getFirst().isBlank()) continue;
            if (cells.size() != header.size()) throw invalid("Onvolledige regel " + (i + 1));
            try {
                String exhibitor = text(cells, header, "Exposantennummer", 30, true);
                if (!expectedExhibitor.equals(exhibitor)) throw invalid("Export behoort niet tot het ingestelde exposantennummer");
                LocalDate date = LocalDate.parse(text(cells, header, "Datum", 10, true), DATE);
                if (date.isBefore(from) || date.isAfter(to)) throw invalid("Verkoopdatum buiten het gevraagde bereik");
                Row row = new Row(text(cells, header, "Locatie", 160, true), exhibitor,
                        LocalDateTime.of(date, LocalTime.parse(text(cells, header, "Tijd", 8, true), TIME)),
                        identifier(cells, header, "Transactienummer"), identifier(cells, header, "Kassabonregel"),
                        text(cells, header, "Artikelomschrijving", 1000, true),
                        text(cells, header, "Beeyond artikelnummer", 100, false),
                        text(cells, header, "Uw artikelnummer", 150, false), text(cells, header, "Barcode", 100, false),
                        number(cells, header, "Aantal", 3, true), number(cells, header, "Kortingspercentage", 2, true),
                        number(cells, header, "Regeltotaal bruto", 2, true), number(cells, header, "Regeltotaal netto", 2, true),
                        number(cells, header, "BTW artikel", 2, false), text(cells, header, "Transactietype", 100, true));
                if (row.location().contains("|") || row.exhibitor().contains("|")) throw invalid("Ongeldige bronidentiteit");
                if (row.quantity().signum() == 0 || row.discountPercent().abs().compareTo(new BigDecimal("100")) > 0
                        || (row.vatRate() != null && (row.vatRate().signum() < 0 || row.vatRate().compareTo(new BigDecimal("100")) > 0)))
                    throw invalid("Ongeldige aantallen of percentages");
                Row previous = unique.putIfAbsent(row.key(), row);
                if (previous != null && !previous.equals(row)) throw invalid("Tegenstrijdige dubbele transactieregel");
            } catch (IllegalArgumentException | java.time.DateTimeException exception) {
                throw invalid("Ongeldige verkoopregel " + (i + 1));
            }
        }
        return List.copyOf(unique.values());
    }

    private static String identifier(List<String> cells, Map<String, Integer> header, String name) {
        String result = text(cells, header, name, 80, true);
        if (!result.matches("[A-Za-z0-9_-]+")) throw invalid("Ongeldig transactienummer");
        return result;
    }

    private static String text(List<String> cells, Map<String, Integer> header, String name, int max, boolean required) {
        String value = cells.get(header.get(name)).strip();
        if ((required && value.isEmpty()) || value.length() > max || value.indexOf('\0') >= 0)
            throw invalid("Ongeldig veld");
        return value;
    }

    private static BigDecimal number(List<String> cells, Map<String, Integer> header, String name, int scale, boolean required) {
        String value = text(cells, header, name, 24, required);
        if (value.isEmpty()) return null;
        if (!value.matches("-?(?:[0-9]+|[0-9]{1,3}(?:\\.[0-9]{3})+)(?:,[0-9]{1," + scale + "})?"))
            throw invalid("Ongeldig getal");
        BigDecimal result = new BigDecimal(value.replace(".", "").replace(',', '.')).setScale(scale);
        if (result.abs().compareTo(new BigDecimal("1000000000")) >= 0) throw invalid("Getal buiten bereik");
        return result;
    }

    /** Semicolon CSV with escaped quotes and quoted newlines; malformed quoting fails closed. */
    static List<List<String>> records(String csv) {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false, closed = false;
        for (int i = 0; i < csv.length(); i++) {
            char ch = csv.charAt(i);
            if (quoted) {
                if (ch == '"') {
                    if (i + 1 < csv.length() && csv.charAt(i + 1) == '"') { cell.append('"'); i++; }
                    else { quoted = false; closed = true; }
                } else cell.append(ch);
            } else if (ch == ';' || ch == '\r' || ch == '\n') {
                row.add(cell.toString()); cell.setLength(0); closed = false;
                if (ch != ';') {
                    rows.add(row); row = new ArrayList<>();
                    if (ch == '\r' && i + 1 < csv.length() && csv.charAt(i + 1) == '\n') i++;
                    if (rows.size() > 100_001) throw invalid("Te veel regels");
                }
            } else if (ch == '"' && cell.isEmpty() && !closed) quoted = true;
            else {
                if (closed || ch == '"') throw invalid("Ongeldige CSV-aanhalingstekens");
                cell.append(ch);
            }
            if (cell.length() > 10_000 || row.size() > 100) throw invalid("Ongeldige CSV-cel");
        }
        if (quoted) throw invalid("Onvolledig CSV-bestand");
        if (!row.isEmpty() || !cell.isEmpty() || closed) { row.add(cell.toString()); rows.add(row); }
        return rows;
    }

    private static IllegalArgumentException invalid(String reason) { return new IllegalArgumentException(reason); }
}
