package be.enrosed.inventory.adapter.out.document;

import be.enrosed.inventory.adapter.out.persistence.StockClosingArticleEntity;
import be.enrosed.inventory.adapter.out.persistence.StockClosingEntity;
import be.enrosed.inventory.adapter.out.persistence.StockClosingLayerEntity;
import be.enrosed.inventory.application.ClosingNotices;
import be.enrosed.inventory.application.ClosingReportData;
import be.enrosed.inventory.application.ClosingReportData.Column;
import be.enrosed.inventory.application.ClosingReportData.Kind;
import be.enrosed.inventory.application.ClosingReportData.SummaryLine;
import be.enrosed.inventory.application.ClosingReportData.Table;
import be.enrosed.inventory.application.StockClosingFinalizer;
import be.enrosed.shared.Brand;
import be.enrosed.shared.PdfFonts;
import jakarta.enterprise.context.ApplicationScoped;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;

/**
 * The valued stock list of a closing for the accountant, with the sign-off
 * block: every section is printed from the stored rows of the closing.
 *
 * The paper names what the user decided and why, and what the ERP left out
 * of the value. It prints the fingerprint of the data and the hash of the
 * workbook, so a signed copy anchors the electronic record.
 */
@ApplicationScoped
public class PdfStockClosingRenderer implements StockClosingFinalizer.PdfRenderer {

    static final String CLOSING_SENTENCE = "Dit document geeft de getelde aantallen en de gebruikte waarderingsbasis weer."
            + " Beslissingen over marktwaarde, waardevermindering, eigendom van goederen onderweg, tegoeden van leveranciers"
            + " en geschatte bedragen zijn door de gebruiker ingevoerd en staan er met hun reden in. De boekhoudkundige"
            + " verwerking is aan de boekhouder.";

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
        String footer = "ENROSED / Jaarinventaris " + closing.closingYear + " versie " + closing.versionNo;
        StringBuilder html = new StringBuilder("<!DOCTYPE html><html><head><meta charset=\"UTF-8\"/><style>");
        html.append("@page { size: A4 landscape; margin: 11mm 8mm 13mm;\n")
                .append("  @bottom-left { content: \"").append(footer).append("\"; font-size: 7pt; color: #63736c; }\n")
                .append("  @bottom-right { content: counter(page) \" / \" counter(pages); font-size: 8pt; color: #63736c; }\n}\n")
                .append("""
                        body { font-family: 'DejaVu Sans', sans-serif; font-size: 8pt; color: #17352a; line-height: 1.4; }
                        h1 { font-size: 20pt; font-weight: 700; margin: 6pt 0 4pt; line-height: 1.15; }
                        h2 { font-size: 11pt; margin: 16pt 0 5pt; page-break-after: avoid; }
                        h3 { font-size: 8pt; margin: 9pt 0 3pt; page-break-after: avoid; }
                        p { margin: 3pt 0 5pt; }
                        .brand { font-size: 17pt; font-weight: 700; letter-spacing: 4pt; }
                        .logo { width: 100pt; height: auto; }
                        .muted { color: #63736c; font-size: 7pt; }
                        .concept { color: #fff; background: #846c32; padding: 3pt 7pt; font-weight: 700; }
                        table.data { width: 100%; border-collapse: collapse; -fs-table-paginate: paginate; font-size: 7pt; }
                        .data th { background: #214939; color: white; padding: 4pt 3pt; text-align: left; font-weight: 700; }
                        .data td { padding: 3pt 3pt; border-bottom: 0.5pt solid #dce4de; vertical-align: top; word-wrap: break-word; }
                        .data tr { page-break-inside: avoid; }
                        .data tr.sum td { background: #eef3ef; font-weight: 700; }
                        .data tr.group td { background: #f6f3ea; font-weight: 700; }
                        .data tr.layer td { font-size: 6pt; color: #4c5f57; border-bottom: none; padding: 1pt 3pt; }
                        .data .num { text-align: right; }
                        .data td.num { white-space: nowrap; }
                        table.summary { width: 62%; border-collapse: collapse; }
                        .summary td { padding: 3pt 4pt; border-bottom: 0.5pt solid #dce4de; }
                        .summary td.num { text-align: right; white-space: nowrap; }
                        .summary tr.sum td { background: #eef3ef; font-weight: 700; }
                        .note { padding: 6pt 9pt; background: #f6f3ea; margin: 6pt 0; }
                        .rule { text-align: justify; }
                        .hash { font-size: 7pt; word-wrap: break-word; }
                        .sign { margin-top: 14pt; page-break-inside: avoid; }
                        .sign p { border-bottom: 0.5pt solid #17352a; padding: 14pt 0 4pt; margin: 0; }
                        """);
        html.append("</style></head><body>");
        head(html, data);
        summary(html, data);
        valuedList(html, data, "Gewaardeerde voorraadlijst", data.valuedArticles(), true);
        valuedList(html, data, "Demostukken (begrepen in de lijst hierboven)",
                data.valuedArticles().stream().filter(article -> Boolean.TRUE.equals(article.demo)).toList(), false);
        section(html, data.writeDownTable());
        section(html, data.estimatedTable());
        section(html, data.partnerTable());
        section(html, data.transitTable());
        section(html, data.invoicedTable());
        if (data.olderInvoiceLine() != null) html.append("<p>").append(escape(data.olderInvoiceLine())).append("</p>");
        section(html, data.thirdPartyTable());
        section(html, data.notInValueTable());
        section(html, data.creditTable());
        section(html, data.countDifferenceTable());
        section(html, data.movementTable());
        if (data.correction()) changes(html, data);
        notices(html, data);
        html.append("<h2>Verklaringen van de gebruiker</h2>");
        for (String statement : data.statements()) html.append("<p>").append(escape(statement)).append("</p>");
        html.append("<h2>Waarderingsregel</h2><p class=\"rule\">").append(escape(closing.ruleText)).append("</p>");
        html.append("<h2>Controle</h2><p class=\"hash\">Gegevens: ").append(escape(closing.dataSha256)).append("</p>")
                .append("<p class=\"hash\">Excel: ").append(escape(closing.xlsxSha256 != null ? closing.xlsxSha256
                        : "wordt vastgelegd bij het definitief maken")).append("</p>");
        html.append("<div class=\"sign\"><p>Opgemaakt door: ").append(escape(closing.signerName == null ? DOTS : closing.signerName))
                .append(" · Datum: ").append(DOTS).append(" · Handtekening: ").append(DOTS).append("</p>")
                .append("<p>Nagezien door (boekhouder): ").append(DOTS).append(" · Datum: ").append(DOTS)
                .append(" · Handtekening: ").append(DOTS).append("</p></div>");
        html.append("<p class=\"muted\" style=\"margin-top:12pt\">").append(escape(CLOSING_SENTENCE)).append("</p>");
        return html.append("</body></html>").toString();
    }

    /* -------------------------------------------------------------- sections */

    private void head(StringBuilder html, ClosingReportData data) {
        StockClosingEntity closing = data.closing();
        if (brand.logoDataUri() != null) html.append("<img class=\"logo\" src=\"").append(brand.logoDataUri()).append("\"/>");
        else html.append("<div class=\"brand\">ENROSED</div>");
        html.append("<p><b>").append(escape(data.company().name())).append("</b><br/>").append(escape(data.company().vat()))
                .append("<br/>").append(escape(data.company().address())).append("</p>");
        html.append("<h1>Voorraadinventaris per ").append(day(closing.closingDate)).append("</h1>");
        html.append("<p>Boekjaar ").append(closing.closingYear).append(" · versie ").append(closing.versionNo).append(" · ");
        if (data.concept()) html.append("<span class=\"concept\">").append(escape(data.statusLabel())).append("</span>");
        else html.append(escape(data.statusLabel()));
        html.append("</p><p>Waarderingsmethode: ").append(escape(closing.ruleMethodLabel))
                .append(", van toepassing sinds boekjaar ").append(closing.ruleEffectiveFromYear).append("</p>");
        if (data.correction()) {
            html.append("<p>Vervangt versie ").append(data.changes() == null ? closing.versionNo - 1 : data.changes().againstVersionNo())
                    .append(": ").append(escape(closing.correctionReason)).append("</p>");
        }
        if (!data.concept()) {
            html.append("<p>Definitief gemaakt door ").append(escape(closing.finalizedByName)).append(" op ")
                    .append(escape(ClosingReportData.moment(closing.finalizedAt))).append("</p>");
        }
    }

    private static void summary(StringBuilder html, ClosingReportData data) {
        html.append("<h2>Samenvatting</h2><table class=\"summary\">");
        for (SummaryLine line : data.summary()) {
            html.append(line.total() ? "<tr class=\"sum\">" : "<tr>").append("<td>").append(escape(line.label()))
                    .append("</td><td class=\"num\">").append(line.quantity() != null ? String.valueOf(line.quantity())
                            : money(line.amountEur())).append("</td></tr>");
        }
        html.append("</table>");
        if (data.marketNote() != null) html.append("<p class=\"note\">").append(escape(data.marketNote())).append("</p>");
    }

    /** The own stock per category with a subtotal, each product followed by the lots it is built from. */
    private static void valuedList(StringBuilder html, ClosingReportData data, String title,
                                   List<StockClosingArticleEntity> articles, boolean byCategory) {
        html.append("<h2>").append(escape(title)).append("</h2>");
        if (articles.isEmpty()) {
            html.append("<p class=\"muted\">Geen.</p>");
            return;
        }
        html.append("<table class=\"data\"><thead><tr><th style=\"width:13%\">SKU</th><th style=\"width:31%\">Product</th>")
                .append("<th>Eenheid</th><th class=\"num\">Aantal</th><th class=\"num\">Waarde per stuk (gem.)</th>")
                .append("<th class=\"num\">Aanschafwaarde</th><th class=\"num\">Waardevermindering</th><th class=\"num\">Waarde</th>")
                .append("</tr></thead><tbody>");
        Sum all = new Sum(), group = new Sum();
        String category = null;
        for (StockClosingArticleEntity article : articles) {
            String own = article.categoryName == null || article.categoryName.isBlank() ? "Zonder categorie" : article.categoryName;
            if (byCategory && !own.equals(category)) {
                if (category != null) group.row(html, "Subtotaal " + category);
                group = new Sum();
                category = own;
                html.append("<tr class=\"group\"><td colspan=\"8\">").append(escape(own)).append("</td></tr>");
            }
            html.append("<tr><td>").append(escape(article.sku)).append("</td><td>").append(escape(article.productName))
                    .append("</td><td>").append(escape(article.unitKey)).append("</td><td class=\"num\">")
                    .append(count(article.ownQuantity)).append("</td><td class=\"num\">").append(unit(article.averageUnitEur))
                    .append("</td><td class=\"num\">").append(money(article.costValueEur)).append("</td><td class=\"num\">")
                    .append(money(article.writeDownEur)).append("</td><td class=\"num\">").append(money(article.ownValueEur))
                    .append("</td></tr>");
            for (StockClosingLayerEntity layer : data.ownLayers(article.productId)) {
                boolean estimated = layer.estimatedEur != null && layer.estimatedEur.signum() > 0;
                html.append("<tr class=\"layer\"><td></td><td>").append(escape(data.layerLabel(layer)))
                        .append(estimated ? " · geschat" : "").append("</td><td></td><td class=\"num\">").append(count(layer.quantity))
                        .append("</td><td class=\"num\">").append(unit(layer.unitValueEur)).append("</td><td class=\"num\">")
                        .append(money(layer.valueEur)).append("</td><td></td><td></td></tr>");
            }
            if (count(article.unvaluedQuantity) > 0) {
                html.append("<tr class=\"layer\"><td></td><td>Zonder gewaardeerde partij</td><td></td><td class=\"num\">")
                        .append(count(article.unvaluedQuantity)).append("</td><td></td><td></td><td></td><td></td></tr>");
            }
            all.add(article);
            group.add(article);
        }
        if (byCategory && category != null) group.row(html, "Subtotaal " + category);
        all.row(html, byCategory ? "Totaal eigen voorraad" : "Subtotaal demostukken");
        html.append("</tbody></table>");
    }

    /** The running totals of the valued list. */
    private static final class Sum {
        private int quantity;
        private BigDecimal cost = BigDecimal.ZERO, writeDown = BigDecimal.ZERO, value = BigDecimal.ZERO;

        void add(StockClosingArticleEntity article) {
            quantity += count(article.ownQuantity);
            cost = cost.add(nz(article.costValueEur));
            writeDown = writeDown.add(nz(article.writeDownEur));
            value = value.add(nz(article.ownValueEur));
        }

        void row(StringBuilder html, String label) {
            html.append("<tr class=\"sum\"><td colspan=\"3\">").append(escape(label)).append("</td><td class=\"num\">").append(quantity)
                    .append("</td><td></td><td class=\"num\">").append(money(cost)).append("</td><td class=\"num\">")
                    .append(money(writeDown)).append("</td><td class=\"num\">").append(money(value)).append("</td></tr>");
        }
    }

    /** One table of the report under its title; an empty one says so in a line. */
    private static void section(StringBuilder html, Table table) {
        html.append("<h2>").append(escape(table.title())).append("</h2>");
        table(html, table);
    }

    private static void table(StringBuilder html, Table table) {
        if (table.rows().isEmpty()) {
            html.append("<p class=\"muted\">Geen.</p>");
            return;
        }
        html.append("<table class=\"data\"><thead><tr>");
        for (Column column : table.columns()) {
            html.append(numeric(column.kind()) ? "<th class=\"num\">" : "<th>").append(escape(column.title())).append("</th>");
        }
        html.append("</tr></thead><tbody>");
        for (List<Object> row : table.rows()) row(html, table.columns(), row, false);
        if (table.totals() != null) row(html, table.columns(), table.totals(), true);
        html.append("</tbody></table>");
    }

    private static void row(StringBuilder html, List<Column> columns, List<Object> values, boolean total) {
        html.append(total ? "<tr class=\"sum\">" : "<tr>");
        for (int index = 0; index < columns.size(); index++) {
            Object value = index < values.size() ? values.get(index) : null;
            boolean right = value instanceof Number;
            html.append(right ? "<td class=\"num\">" : "<td>").append(escape(print(value, columns.get(index).kind()))).append("</td>");
        }
        html.append("</tr>");
    }

    private static void changes(StringBuilder html, ClosingReportData data) {
        html.append("<h2>").append(escape(data.changesTitle() == null ? "Wijzigingen tegenover de vorige versie" : data.changesTitle()))
                .append("</h2>");
        if (data.changes() == null || data.changes().isEmpty()) {
            html.append("<p>Geen verschillen.</p>");
            return;
        }
        table(html, data.changeTable());
    }

    private static void notices(StringBuilder html, ClosingReportData data) {
        html.append("<h2>Aandachtspunten</h2>");
        if (data.concept()) {
            for (ClosingNotices.Notice blocker : data.blockers()) {
                html.append("<p><b>Blokkeert:</b> ").append(escape(blocker.message())).append("</p>");
            }
        }
        if (data.warnings().isEmpty()) html.append("<p class=\"muted\">Geen.</p>");
        for (ClosingNotices.Notice warning : data.warnings()) html.append("<p>").append(escape(warning.message())).append("</p>");
    }

    /* --------------------------------------------------------------- printing */

    private static boolean numeric(Kind kind) {
        return kind == Kind.COUNT || kind == Kind.MONEY || kind == Kind.UNIT || kind == Kind.RATE;
    }

    /** A cell as the paper prints it: money with two decimals, a unit value with four, a day as dd/MM/yyyy. */
    private static String print(Object value, Kind kind) {
        return switch (value) {
            case null -> "";
            case BigDecimal number -> kind == Kind.RATE ? decimals(number, 8)
                    : kind == Kind.UNIT || kind == Kind.ANY && number.scale() > 2 ? decimals(number, 4) : decimals(number, 2);
            case LocalDate day -> DAY.format(day);
            case Instant moment -> ClosingReportData.moment(moment);
            case Boolean yes -> yes ? "ja" : "nee";
            default -> value.toString();
        };
    }

    private static String money(BigDecimal value) {
        return value == null ? "" : "€ " + decimals(value, 2);
    }

    private static String unit(BigDecimal value) {
        return value == null ? "" : "€ " + decimals(value, 4);
    }

    /** Belgian notation: a point per thousand, a comma before the decimals. */
    private static String decimals(BigDecimal value, int scale) {
        DecimalFormatSymbols symbols = new DecimalFormatSymbols();
        symbols.setGroupingSeparator('.');
        symbols.setDecimalSeparator(',');
        symbols.setMinusSign('-');
        DecimalFormat format = new DecimalFormat("#,##0." + "0".repeat(scale), symbols);
        format.setRoundingMode(RoundingMode.HALF_UP);
        return format.format(value);
    }

    private static String day(LocalDate day) {
        return day == null ? "-" : DAY.format(day);
    }

    private static BigDecimal nz(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private static int count(Integer value) {
        return value == null ? 0 : value;
    }

    private static String escape(String value) {
        return Objects.requireNonNullElse(value, "").replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }
}
