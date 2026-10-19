package be.enrosed.tica;

import be.enrosed.shared.BusinessRuleException;
import jakarta.enterprise.context.ApplicationScoped;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.IsoFields;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Recognizes the verified Brussels weekly settlement, never a generic supplier credit note. */
@ApplicationScoped
public class TicaSettlementPdfParser {
    public static final int MAX_BYTES = 3 * 1024 * 1024;
    private static final String MONEY = "([0-9]+(?:\\.[0-9]{3})*,[0-9]{2})";
    private static final DateTimeFormatter DUTCH_DATE = DateTimeFormatter.ofPattern("d MMMM uuuu", Locale.forLanguageTag("nl-BE"));
    public record Parsed(String reference, String location, LocalDate periodFrom, LocalDate periodTo,
                         LocalDate documentDate, LocalDate dueDate, BigDecimal salesExcl, BigDecimal salesVat,
                         BigDecimal commissionExcl, BigDecimal commissionVat, BigDecimal netReceivable,
                         List<String> warnings) {}

    public Parsed parse(byte[] bytes) {
        if (bytes == null || bytes.length < 5 || bytes.length > MAX_BYTES || bytes[0] != '%' || bytes[1] != 'P'
                || bytes[2] != 'D' || bytes[3] != 'F' || bytes[4] != '-') throw invalid();
        try (var document = Loader.loadPDF(bytes)) {
            if (document.isEncrypted() || document.getNumberOfPages() < 1 || document.getNumberOfPages() > 3) throw invalid();
            var stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            String text = stripper.getText(document);
            if (text.length() > 80_000) throw invalid();
            return parseText(text);
        } catch (BusinessRuleException invalid) { throw invalid; }
        catch (Exception unreadable) { throw invalid(); }
    }

    Parsed parseText(String text) {
        String flat = text.replace('\u00a0', ' ').replace('\u2212', '-').replaceAll("\\s+", " ").strip();
        String vatText = flat.replaceAll("[ .]", "");
        if (!flat.contains("Creditnota") || !flat.contains("TICA Brussel")
                || !vatText.contains("BE0428038927") || !vatText.contains("BE1034273386")) throw invalid();
        String reference = match("Creditnotanr\\.?:\\s*([0-9]{5,30})", flat).group(1);
        LocalDate date, dueDate;
        try {
            date = LocalDate.parse(match("Documentdatum:\\s*([0-9]{1,2} [a-z]+ [0-9]{4})", flat).group(1), DUTCH_DATE);
            dueDate = LocalDate.parse(match("Vervaldatum:\\s*([0-9]{1,2} [a-z]+ [0-9]{4})", flat).group(1), DUTCH_DATE);
        }
        catch (Exception invalid) { throw invalid(); }
        Matcher commission = match("Commissie week ([0-9]{1,2})\\s*-\\s*([0-9]+[.,][0-9]+)%\\s+-1\\s+" + MONEY + "\\s+(21)\\s+-" + MONEY, flat);
        Matcher sales = match("Omzet week ([0-9]{1,2})\\s+1\\s+" + MONEY + "\\s+(21)\\s+" + MONEY, flat);
        int week = Integer.parseInt(sales.group(1));
        if (week != Integer.parseInt(commission.group(1)) || week < 1 || week > 53) throw invalid();
        BigDecimal salesExcl = amount(sales.group(4)), feeExcl = amount(commission.group(5));
        if (salesExcl.signum() <= 0 || feeExcl.signum() < 0 || !amount(sales.group(2)).equals(salesExcl)
                || !amount(commission.group(3)).equals(feeExcl)) throw invalid();
        BigDecimal percent = new BigDecimal(commission.group(2).replace(',', '.'));
        if (percent.signum() < 0 || percent.compareTo(new BigDecimal("100")) > 0
                || !salesExcl.multiply(percent).movePointLeft(2).setScale(2, RoundingMode.HALF_UP).equals(feeExcl)) throw invalid();
        // Read the printed VAT allocation rather than deriving a second accounting amount.
        int vatSectionAt = flat.indexOf("Specificatie btw-bedrag");
        if (vatSectionAt < 0) throw invalid();
        String vatSection = flat.substring(vatSectionAt);
        BigDecimal salesVat = taxAllocation(vatSection, salesExcl, false);
        BigDecimal feeVat = taxAllocation(vatSection, feeExcl, true);
        BigDecimal subtotal = amount(match("Totaal EUR excl\\. btw\\s+" + MONEY, flat).group(1));
        BigDecimal vat = amount(match("Btw-bedrag\\s+" + MONEY, flat).group(1));
        BigDecimal total = amount(match("Totaal EUR\\s+" + MONEY, flat).group(1));
        if (!salesExcl.subtract(feeExcl).equals(subtotal) || !salesVat.subtract(feeVat).equals(vat)
                || !subtotal.add(vat).equals(total) || total.signum() <= 0) throw invalid();
        LocalDate monday = null;
        // The document date determines which occurrence of its ISO week is meant, including New Year.
        for (int days = 0; days <= 90; days++) {
            LocalDate candidate = date.minusDays(days);
            if (candidate.getDayOfWeek().getValue() == 1 && candidate.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR) == week
                    && !candidate.plusDays(6).isAfter(date)) { monday = candidate; break; }
        }
        if (monday == null) throw invalid();
        return new Parsed(reference, "TICA - Brussel", monday, monday.plusDays(6), date, dueDate,
                salesExcl, salesVat, feeExcl, feeVat, total, List.of("Controleer de periode en bedragen voordat je de afrekening bevestigt."));
    }

    private static BigDecimal taxAllocation(String section, BigDecimal base, boolean negative) {
        // Retain cents even when the PDF prints whole euro amounts.
        var decimal = new java.text.DecimalFormat("#,##0.00", java.text.DecimalFormatSymbols.getInstance(Locale.forLanguageTag("nl-NL")));
        String formatted = decimal.format(base);
        String sign = negative ? "-" : "(?<![-0-9,])";
        Matcher found = match(sign + Pattern.quote(formatted) + "\\s+21\\s+" + (negative ? "-" : "") + MONEY, section);
        BigDecimal value = amount(found.group(1));
        if (!base.multiply(new BigDecimal("0.21")).setScale(2, RoundingMode.HALF_UP).equals(value)) throw invalid();
        return value;
    }
    private static Matcher match(String regex, String text) {
        Matcher matcher = Pattern.compile(regex).matcher(text);
        if (!matcher.find()) throw invalid();
        return matcher;
    }
    private static BigDecimal amount(String amount) { return new BigDecimal(amount.replace(".", "").replace(',', '.')).setScale(2); }
    private static BusinessRuleException invalid() {
        return new BusinessRuleException("Deze PDF is niet eenduidig herkend als TICA Brussel-weekafrekening. Vul de bedragen handmatig in en controleer het brondocument.");
    }
}
