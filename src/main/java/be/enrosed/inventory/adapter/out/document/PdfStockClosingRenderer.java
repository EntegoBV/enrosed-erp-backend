package be.enrosed.inventory.adapter.out.document;

import be.enrosed.inventory.adapter.out.persistence.StockClosingArticleEntity;
import be.enrosed.inventory.adapter.out.persistence.StockClosingEntity;
import be.enrosed.inventory.adapter.out.persistence.StockClosingSeparateEntity;
import be.enrosed.inventory.application.ClosingNotices;
import be.enrosed.inventory.application.ClosingReportData;
import be.enrosed.inventory.application.FifoValuer;
import be.enrosed.inventory.application.StockClosingFinalizer;
import be.enrosed.inventory.domain.WriteDownReason;
import be.enrosed.shared.Brand;
import be.enrosed.shared.PdfFonts;
import jakarta.enterprise.context.ApplicationScoped;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;

/** Compact accounting copy of the stored closing. Detailed supporting records remain in Excel. */
@ApplicationScoped
public class PdfStockClosingRenderer implements StockClosingFinalizer.PdfRenderer {
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final String DOTS = "……………………";
    private final PdfFonts fonts;
    private final Brand brand;

    public PdfStockClosingRenderer(PdfFonts fonts, Brand brand) {
        this.fonts = fonts;
        this.brand = brand;
    }

    @Override
    public byte[] render(ClosingReportData data) {
        return fonts.render(html(data));
    }

    String html(ClosingReportData data) {
        StockClosingEntity closing = data.closing();
        StringBuilder html = new StringBuilder("<!DOCTYPE html><html><head><meta charset=\"UTF-8\"/><style>");
        html.append("@page { size: A4 portrait; margin: 14mm 12mm 15mm; ")
                .append("@bottom-left { content: \"ENROSED / Jaarinventaris ").append(closing.closingYear)
                .append(" versie ").append(closing.versionNo).append("\"; font-size: 7pt; color: #666; } ")
                .append("@bottom-right { content: counter(page) \" / \" counter(pages); font-size: 7pt; color: #666; } }")
                .append("""
                        body { font-family: 'DejaVu Sans', sans-serif; font-size: 9pt; color: #222; line-height: 1.35; }
                        h1 { font-size: 18pt; margin: 10pt 0 5pt; line-height: 1.2; }
                        h2 { font-size: 11pt; margin: 14pt 0 5pt; page-break-after: avoid; }
                        p { margin: 3pt 0 5pt; }
                        p.table-intro { page-break-after: avoid; }
                        .brand { font-size: 17pt; font-weight: 700; letter-spacing: 4pt; }
                        .logo { width: 90pt; height: auto; }
                        .muted { color: #666; font-size: 7.5pt; }
                        .concept { background: #eee; padding: 2pt 5pt; font-weight: 700; }
                        table.data { width: 100%; border-collapse: collapse; table-layout: fixed; -fs-table-paginate: paginate; font-size: 8pt; }
                        .data th { background: #eee; padding: 5pt 3pt; text-align: left; font-weight: 700; vertical-align: bottom; }
                        .data td { padding: 4pt 3pt; border-bottom: 0.5pt solid #ddd; vertical-align: top; word-wrap: break-word; }
                        .data tr, .data tbody.product { page-break-inside: avoid; }
                        .data thead, .data thead tr, .data tr.group { page-break-after: avoid; }
                        .data tr.group td { background: #f7f7f7; font-weight: 700; }
                        .data tr.sum td { border-top: 1pt solid #888; font-weight: 700; }
                        .data .num { text-align: right; white-space: nowrap; }
                        .data th.num { white-space: normal; }
                        table.summary { width: 100%; border-collapse: collapse; }
                        .summary td { padding: 4pt 5pt; border-bottom: 0.5pt solid #ddd; }
                        .summary td.num { text-align: right; white-space: nowrap; }
                        .summary tr.sum td { background: #eee; font-weight: 700; font-size: 11pt; }
                        .note { font-size: 8pt; }
                        .sign { margin-top: 12pt; page-break-inside: avoid; font-size: 8pt; }
                        .sign p { padding: 5pt 0; }
                        .hashes { margin-top: 9pt; page-break-inside: avoid; }
                        .hash { font-size: 6.5pt; color: #666; }
                        """);
        html.append("</style></head><body>");
        head(html, data);
        summary(html, data);
        valuedList(html, data.valuedArticles());
        included(html, "Opgenomen partnergoederen", data.kind(FifoValuer.KIND_PARTNER));
        included(html, "Opgenomen goederen onderweg", data.kind(FifoValuer.KIND_TRANSIT));
        writeDowns(html, data);
        excluded(html, data);
        if (data.correction()) changes(html, data);
        notices(html, data);
        html.append("<h2>Waardering en bevestiging</h2><p class=\"note\">Aanschafwaarde, verminderd met de ingevoerde waardeverminderingen. ")
                .append("De volledige waarderingsregel en onderbouwing staan in de bijbehorende Excel-export.</p>");
        for (String statement : data.statements()) html.append("<p class=\"note\">").append(escape(statement)).append("</p>");
        html.append("<div class=\"sign\"><p>Opgemaakt door: ")
                .append(escape(closing.signerName == null ? DOTS : closing.signerName))
                .append("<br/>Datum: ").append(DOTS).append(" · Handtekening: ").append(DOTS).append("</p>")
                .append("<p>Nagezien door (boekhouder): ").append(DOTS)
                .append("<br/>Datum: ").append(DOTS).append(" · Handtekening: ").append(DOTS).append("</p></div>")
                .append("<div class=\"hashes\"><p class=\"hash\">Gegevens: ").append(escape(closing.dataSha256)).append("</p>")
                .append("<p class=\"hash\">Excel: ").append(escape(closing.xlsxSha256 != null ? closing.xlsxSha256
                        : "wordt vastgelegd bij het definitief maken")).append("</p></div>");
        return html.append("</body></html>").toString();
    }

    private void head(StringBuilder html, ClosingReportData data) {
        StockClosingEntity closing = data.closing();
        if (brand.logoDataUri() != null) html.append("<img class=\"logo\" src=\"").append(brand.logoDataUri()).append("\"/>");
        else html.append("<div class=\"brand\">ENROSED</div>");
        html.append("<p><b>").append(escape(data.company().name())).append("</b><br/>").append(escape(data.company().vat()))
                .append("<br/>").append(escape(data.company().address())).append("</p>")
                .append("<h1>Voorraadinventaris per ").append(day(closing.closingDate)).append("</h1>")
                .append("<p>Boekjaar ").append(closing.closingYear).append(" · versie ").append(closing.versionNo).append(" · ");
        if (data.concept()) html.append("<span class=\"concept\">").append(escape(data.statusLabel())).append("</span>");
        else html.append(escape(data.statusLabel()));
        html.append("</p><p class=\"muted\">Waarderingsmethode: ").append(escape(closing.ruleMethodLabel))
                .append(", van toepassing sinds boekjaar ").append(closing.ruleEffectiveFromYear).append("</p>");
        if (data.correction()) html.append("<p class=\"note\">Vervangt versie ")
                .append(data.changes() == null ? closing.versionNo - 1 : data.changes().againstVersionNo())
                .append(": ").append(escape(closing.correctionReason)).append("</p>");
        if (!data.concept()) html.append("<p class=\"muted\">Definitief gemaakt door ").append(escape(closing.finalizedByName))
                .append(" op ").append(escape(ClosingReportData.moment(closing.finalizedAt))).append("</p>");
    }

    private static void summary(StringBuilder html, ClosingReportData data) {
        StockClosingEntity c = data.closing();
        html.append("<h2>Voorraadwaarde</h2><table class=\"summary\">");
        if (nonzero(c.writeDownEur)) {
            amountLine(html, "Aanschafwaarde eigen voorraad", c.costValueEur, false);
            amountLine(html, "Waardeverminderingen", c.writeDownEur.negate(), false);
        }
        amountLine(html, "Eigen voorraad", c.ownValueEur, false);
        if (nonzero(c.partnerIncludedEur)) amountLine(html, "Partnergoederen opgenomen", c.partnerIncludedEur, false);
        if (nonzero(c.transitIncludedEur)) amountLine(html, "Goederen onderweg opgenomen", c.transitIncludedEur, false);
        amountLine(html, "Totaal voorraadwaarde", c.totalValueEur, true);
        html.append("</table>");
        if (nonzero(c.estimatedEur)) html.append("<p class=\"note\">Waarvan geschat: ").append(money(c.estimatedEur)).append(".</p>");
        if (data.marketNote() != null) html.append("<p class=\"note\">").append(escape(data.marketNote())).append("</p>");
    }

    private static void amountLine(StringBuilder html, String label, BigDecimal value, boolean total) {
        html.append(total ? "<tr class=\"sum\">" : "<tr>").append("<td>").append(escape(label))
                .append("</td><td class=\"num\">").append(money(nz(value))).append("</td></tr>");
    }

    private static void valuedList(StringBuilder html, List<StockClosingArticleEntity> articles) {
        html.append("<h2>Eigen voorraad per artikel</h2>");
        if (articles.isEmpty()) {
            html.append("<p class=\"muted\">Geen eigen voorraad.</p>");
            return;
        }
        boolean reductions = articles.stream().anyMatch(a -> nonzero(a.writeDownEur));
        html.append("<p class=\"muted table-intro\">Aantallen in stuks. Bedragen in EUR. Kost/stuk is de gemiddelde aanschafwaarde.</p>");
        tableStart(html, reductions);
        int quantity = 0;
        BigDecimal cost = BigDecimal.ZERO, down = BigDecimal.ZERO, value = BigDecimal.ZERO;
        String category = null;
        for (int index = 0; index < articles.size(); index++) {
            StockClosingArticleEntity a = articles.get(index);
            String nextCategory = a.categoryName == null || a.categoryName.isBlank() ? "Zonder categorie" : a.categoryName;
            html.append("<tbody class=\"product\">");
            if (!nextCategory.equals(category)) {
                html.append("<tr class=\"group\"><td colspan=\"").append(reductions ? 6 : 4).append("\">")
                        .append(escape(nextCategory)).append("</td></tr>");
                category = nextCategory;
            }
            html.append("<tr><td>").append(escape(a.productName)).append("<br/><span class=\"muted\">").append(escape(a.sku))
                    .append(Boolean.TRUE.equals(a.demo) ? " · Demo" : "").append("</span>");
            if (count(a.unvaluedQuantity) > 0) html.append("<br/><b>").append(count(a.unvaluedQuantity)).append(" stuks zonder waarde</b>");
            html.append("</td><td class=\"num\">").append(count(a.ownQuantity)).append("</td>");
            number(html, a.averageUnitEur, 4);
            if (reductions) { number(html, a.costValueEur, 2); number(html, a.writeDownEur, 2); }
            number(html, a.ownValueEur, 2);
            html.append("</tr>");
            // Keep the total in the last product's unbroken group, including its repeated table header.
            if (index < articles.size() - 1) html.append("</tbody>");
            quantity += count(a.ownQuantity);
            cost = cost.add(nz(a.costValueEur));
            down = down.add(nz(a.writeDownEur));
            value = value.add(nz(a.ownValueEur));
        }
        html.append("<tr class=\"sum\"><td>Totaal eigen voorraad</td><td class=\"num\">").append(quantity).append("</td><td></td>");
        if (reductions) { number(html, cost, 2); number(html, down, 2); }
        number(html, value, 2);
        html.append("</tr></tbody></table>");
    }

    private static void tableStart(StringBuilder html, boolean reductions) {
        html.append("<table class=\"data\"><colgroup>");
        for (int width : reductions ? new int[]{31, 9, 15, 15, 14, 16} : new int[]{48, 10, 20, 22}) {
            html.append("<col style=\"width:").append(width).append("%\"/>");
        }
        html.append("</colgroup><thead><tr><th>Artikel</th><th class=\"num\">Aantal</th><th class=\"num\">Kost/stuk</th>");
        if (reductions) html.append("<th class=\"num\">Aanschaf-<br/>waarde</th><th class=\"num\">Waarde-<br/>vermindering</th>");
        html.append("<th class=\"num\">Voorraad-<br/>waarde</th></tr></thead>");
    }

    private static void included(StringBuilder html, String title, List<StockClosingSeparateEntity> all) {
        List<StockClosingSeparateEntity> rows = all.stream().filter(row -> Boolean.TRUE.equals(row.included)).toList();
        if (rows.isEmpty()) return;
        html.append("<h2>").append(escape(title)).append("</h2><p class=\"muted table-intro\">Aantallen in stuks. Bedragen in EUR.</p>");
        tableStart(html, false);
        BigDecimal total = BigDecimal.ZERO;
        int quantity = 0;
        for (int index = 0; index < rows.size(); index++) {
            StockClosingSeparateEntity row = rows.get(index);
            html.append("<tbody class=\"product\"><tr><td>").append(escape(row.productName)).append("<br/><span class=\"muted\">").append(escape(row.sku));
            if (row.documentNumber != null) html.append(" · ").append(escape(row.documentNumber));
            if (row.ownershipDate != null) html.append(" · Eigendom of risico vanaf ").append(day(row.ownershipDate));
            if (row.reason != null && !row.reason.isBlank()) html.append("<br/>").append(escape(row.reason));
            html.append("</span></td><td class=\"num\">").append(count(row.quantity)).append("</td>");
            number(html, row.unitValueEur, 4);
            number(html, row.valueEur, 2);
            html.append("</tr>");
            if (index < rows.size() - 1) html.append("</tbody>");
            quantity += count(row.quantity);
            total = total.add(nz(row.valueEur));
        }
        html.append("<tr class=\"sum\"><td>Subtotaal</td><td class=\"num\">").append(quantity).append("</td><td></td>");
        number(html, total, 2);
        html.append("</tr></tbody></table>");
    }

    private static void writeDowns(StringBuilder html, ClosingReportData data) {
        if (data.writeDowns().isEmpty()) return;
        html.append("<h2>Toelichting waardeverminderingen</h2>");
        for (var down : data.writeDowns()) {
            html.append("<p class=\"note\"><b>").append(escape(down.sku)).append(" · ").append(escape(down.productName))
                    .append(": ").append(money(down.amountEur)).append("</b> (").append(count(down.quantity)).append(" stuks). ")
                    .append(escape(WriteDownReason.labelOf(down.reasonCode)));
            if (down.reason != null && !down.reason.isBlank()) html.append(" · ").append(escape(down.reason));
            html.append("</p>");
        }
    }

    private static void excluded(StringBuilder html, ClosingReportData data) {
        StockClosingEntity c = data.closing();
        int thirdParty = data.kind(FifoValuer.KIND_THIRD_PARTY).stream().mapToInt(row -> count(row.quantity)).sum();
        if (!nonzero(c.partnerExcludedEur) && !nonzero(c.transitExcludedEur) && !nonzero(c.invoicedOutEur)
                && thirdParty == 0 && data.olderInvoiceLine() == null) return;
        html.append("<h2>Afzonderlijk vermeld</h2>");
        if (nonzero(c.partnerExcludedEur)) html.append("<p class=\"note\">Partnergoederen niet opgenomen: ").append(money(c.partnerExcludedEur)).append(".</p>");
        if (nonzero(c.transitExcludedEur)) html.append("<p class=\"note\">Goederen onderweg niet opgenomen: ").append(money(c.transitExcludedEur)).append(".</p>");
        if (nonzero(c.invoicedOutEur)) html.append("<p class=\"note\">Gefactureerde, nog niet geleverde goederen buiten de voorraadwaarde: ").append(money(c.invoicedOutEur)).append(".</p>");
        if (thirdParty > 0) html.append("<p class=\"note\">Goederen van derden: ").append(thirdParty).append(" stuks, zonder voorraadwaarde.</p>");
        if (data.olderInvoiceLine() != null) html.append("<p class=\"note\">").append(escape(data.olderInvoiceLine())).append("</p>");
    }

    private static void changes(StringBuilder html, ClosingReportData data) {
        html.append("<h2>").append(escape(data.changesTitle() == null ? "Wijzigingen tegenover de vorige versie" : data.changesTitle())).append("</h2>");
        if (data.changes() == null || data.changes().isEmpty()) {
            html.append("<p class=\"note\">Geen verschillen.</p>");
            return;
        }
        html.append("<p class=\"note\">Voorraadwaarde vorige versie: ").append(money(data.changes().totalBeforeEur()))
                .append(". Nieuwe voorraadwaarde: ").append(money(data.changes().totalAfterEur()))
                .append(". Verschil: ").append(money(nz(data.changes().totalAfterEur()).subtract(nz(data.changes().totalBeforeEur()))))
                .append(". De afzonderlijke wijzigingen staan in Excel.</p>");
    }

    private static void notices(StringBuilder html, ClosingReportData data) {
        if ((!data.concept() || data.blockers().isEmpty()) && data.warnings().isEmpty()) return;
        html.append("<h2>Aandachtspunten</h2>");
        if (data.concept()) for (ClosingNotices.Notice blocker : data.blockers()) {
            html.append("<p class=\"note\"><b>Blokkeert:</b> ").append(escape(ClosingNotices.reportText(blocker.message()))).append("</p>");
        }
        for (ClosingNotices.Notice warning : data.warnings()) html.append("<p class=\"note\">")
                .append(escape(ClosingNotices.reportText(warning.message()))).append("</p>");
    }

    private static void number(StringBuilder html, BigDecimal value, int scale) {
        html.append("<td class=\"num\">").append(value == null ? "" : decimals(value, scale)).append("</td>");
    }

    private static String money(BigDecimal value) { return value == null ? "" : "€ " + decimals(value, 2); }

    private static String decimals(BigDecimal value, int scale) {
        DecimalFormatSymbols symbols = new DecimalFormatSymbols();
        symbols.setGroupingSeparator('.');
        symbols.setDecimalSeparator(',');
        symbols.setMinusSign('-');
        DecimalFormat format = new DecimalFormat("#,##0." + "0".repeat(scale), symbols);
        format.setRoundingMode(RoundingMode.HALF_UP);
        return format.format(value);
    }

    private static String day(LocalDate day) { return day == null ? "-" : DAY.format(day); }
    private static BigDecimal nz(BigDecimal value) { return value == null ? BigDecimal.ZERO : value; }
    private static boolean nonzero(BigDecimal value) { return nz(value).signum() != 0; }
    private static int count(Integer value) { return value == null ? 0 : value; }
    private static String escape(String value) {
        return Objects.requireNonNullElse(value, "").replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }
}
