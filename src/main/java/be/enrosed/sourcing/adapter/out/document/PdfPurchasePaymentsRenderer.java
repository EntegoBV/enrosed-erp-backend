package be.enrosed.sourcing.adapter.out.document;

import be.enrosed.shared.Brand;
import be.enrosed.shared.Currency;
import be.enrosed.shared.PdfFonts;
import be.enrosed.sourcing.domain.PurchaseOrder;
import be.enrosed.sourcing.domain.PurchasePayment;
import be.enrosed.sourcing.domain.PurchaseReconciliation;
import be.enrosed.sourcing.domain.Supplier;
import jakarta.enterprise.context.ApplicationScoped;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** A separate, internal settlement report based on exactly the same reconciliation as the screen. */
@ApplicationScoped
public class PdfPurchasePaymentsRenderer {
    private final PdfFonts fonts;
    private final Brand brand;
    private static final Locale LOCALE = Locale.forLanguageTag("nl-BE");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    public PdfPurchasePaymentsRenderer(PdfFonts fonts, Brand brand) {
        this.fonts = fonts;
        this.brand = brand;
    }

    public record Document(String filename, byte[] content) {}

    public Document render(PurchaseOrder order, Supplier supplier, PurchaseReconciliation report,
                           List<PurchasePayment> payments) {
        String reference = order.number() == null ? "container-" + order.id() : order.number();
        String filename = reference.replaceAll("[^a-zA-Z0-9._-]", "-") + "-betalingen-kostprijs.pdf";
        return new Document(filename, fonts.render(html(order, supplier, report, payments)));
    }

    String html(PurchaseOrder order, Supplier supplier, PurchaseReconciliation report, List<PurchasePayment> payments) {
        var total = report.totals();
        boolean finalCost = total.finalized();
        String basis = finalCost ? "Afgerekende kost" : "Verwachte eindkost";
        String quantityBasis = total.receiptRecorded() ? "bruikbare ontvangen stuks" : "bestelde stuks";
        String generated = ZonedDateTime.now(ZoneId.of("Europe/Brussels"))
                .format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm z", LOCALE));
        StringBuilder html = new StringBuilder("<!DOCTYPE html><html><head><meta charset=\"UTF-8\"/><style>");
        html.append("""
                @page { size: A4; margin: 15mm 15mm 18mm;
                  @bottom-left { content: "ENROSED  /  Interne containerafrekening"; font-size: 7pt; color: #63736c; }
                  @bottom-right { content: counter(page) " / " counter(pages); font-size: 8pt; color: #63736c; }
                }
                body { font-family: 'DejaVu Sans', sans-serif; font-size: 9pt; color: #17352a; line-height: 1.5; }
                h1 { font-size: 26pt; font-weight: 700; margin: 6pt 0; line-height: 1.15; }
                h2 { font-size: 18pt; margin: 4pt 0 8pt; }
                h3 { font-size: 10pt; margin: 15pt 0 6pt; }
                p { margin: 5pt 0 9pt; }
                .brand { font-size: 17pt; font-weight: 700; letter-spacing: 4pt; }
                .logo { width: 115pt; height: auto; }
                .kicker { color: #7b6b45; text-transform: uppercase; letter-spacing: 1.5pt; font-size: 8pt; margin-top: 15pt; }
                .muted { color: #63736c; font-size: 8pt; }
                .meta { border-top: 1pt solid #cad4cf; padding-top: 8pt; margin-top: 12pt; }
                .badge { color: #fff; background: #214939; padding: 5pt 9pt; font-size: 8pt; }
                .pending { background: #846c32; }
                .kpis { width: 100%; margin: 16pt 0; border-spacing: 5pt; table-layout: fixed; }
                .kpis td { background: #eef3ef; padding: 11pt; vertical-align: top; }
                .kpis .value { display: block; font-size: 19pt; font-weight: 700; letter-spacing: -0.5pt; }
                .kpis .label { display: block; font-size: 8pt; color: #63736c; }
                table.data { width: 100%; border-collapse: collapse; table-layout: fixed; -fs-table-paginate: paginate; font-size: 8pt; }
                .data th { background: #214939; color: white; padding: 7pt 5pt; text-align: left; font-weight: 700; }
                .data td { padding: 8pt 5pt; border-bottom: 0.5pt solid #dce4de; vertical-align: top; word-wrap: break-word; }
                .data tr { page-break-inside: avoid; }
                .data tr.sum td { background: #eef3ef; font-weight: 700; }
                .data .num { text-align: right; }
                .note { padding: 10pt 12pt; background: #f6f3ea; margin: 12pt 0; font-size: 8pt; }
                .note p { margin: 3pt 0; }
                .positive { color: #9a4937; } .negative { color: #24734b; }
                .page { page-break-before: always; }
                .totals { width: 70%; margin: 15pt 0 15pt auto; border-collapse: collapse; }
                .totals td { padding: 6pt; border-bottom: 0.5pt solid #dce4de; }
                .totals td:last-child { text-align: right; font-weight: 700; }
                .nowrap { white-space: nowrap; }
                .small { font-size: 7pt; }
                """);
        html.append("</style></head><body>");
        if (brand.logoDataUri() != null) html.append("<img class=\"logo\" src=\"").append(brand.logoDataUri()).append("\"/>");
        else html.append("<div class=\"brand\">ENROSED</div>");
        html.append("<div class=\"kicker\">Containerafrekening</div><h1>Betalingen &amp; kostprijs</h1>")
                .append("<p>").append(escape(order.number())).append(" · ").append(escape(order.alias())).append("</p>")
                .append("<p class=\"muted\">").append(escape(supplier == null ? "" : supplier.name())).append("</p>")
                .append("<p><span class=\"badge ").append(finalCost ? "" : "pending").append("\">")
                .append(finalCost ? "Afgerekend" : "Voorlopig - open posten of afrekening te bevestigen").append("</span></p>")
                .append("<div class=\"meta muted\">Orderdatum ").append(day(order.orderDate()))
                .append(" · ").append(escape(order.status().name())).append(" · Opgemaakt ").append(escape(generated))
                .append("<br/>").append(escape(order.departurePort())).append(" → ").append(escape(order.destinationPort()))
                .append(" · Referentie ").append(escape(order.trackingReference())).append("</div>");
        html.append("<table class=\"kpis\"><tr>");
        kpi(html, "Begrote externe kost", total.plannedExternalEur());
        kpi(html, basis, total.forecastExternalEur());
        html.append("</tr><tr>");
        kpi(html, "Werkelijk betaald", total.paidEur());
        kpi(html, "Nog te betalen", total.remainingEur());
        html.append("</tr></table><h3>Waar zit het verschil?</h3><table class=\"data\"><thead><tr>")
                .append("<th style=\"width:26%\">Kostengroep</th><th class=\"num\">Begroot</th><th class=\"num\">Betaald</th>")
                .append("<th class=\"num\">Open</th><th class=\"num\">Eindkost</th><th class=\"num\">Verschil</th></tr></thead><tbody>");
        for (var stream : report.streams()) {
            html.append("<tr><td><b>").append(escape(stream.label())).append("</b><br/><span class=\"muted small\">")
                    .append(escape(status(stream))).append("</span></td>");
            moneyCell(html, stream.plannedEur()); moneyCell(html, stream.paidEur()); moneyCell(html, stream.remainingEur());
            moneyCell(html, stream.forecastEur()); differenceCell(html, stream.varianceEur()); html.append("</tr>");
        }
        html.append("<tr class=\"sum\"><td>Externe kost</td>");
        moneyCell(html, total.plannedExternalEur()); moneyCell(html, total.paidEur()); moneyCell(html, total.remainingEur());
        moneyCell(html, total.forecastExternalEur()); differenceCell(html, total.varianceEur());
        html.append("</tr></tbody></table>");
        if (payments != null && payments.stream().anyMatch(payment -> payment.instalmentDue() != null)
                && report.supplierInstalments() != null && !report.supplierInstalments().isEmpty()) {
            html.append("<h3>Leverancier per termijn</h3><table class=\"data\"><thead><tr>")
                    .append("<th>Termijn</th><th class=\"num\">Afgesproken</th><th class=\"num\">Betaald</th>")
                    .append("<th class=\"num\">Open</th><th class=\"num\">Besparing</th></tr></thead><tbody>");
            for (var instalment : report.supplierInstalments()) {
                html.append("<tr><td><b>").append(escape(instalment.label()))
                        .append("</b><br/><span class=\"muted small\">")
                        .append(instalment.finalized() ? "Termijn afgerekend" : "Termijn nog open").append("</span></td>");
                moneyCell(html, instalment.plannedEur()); moneyCell(html, instalment.paidEur());
                moneyCell(html, instalment.remainingEur()); moneyCell(html, instalment.settledSavingEur());
                html.append("</tr>");
            }
            html.append("</tbody></table>");
        }
        html.append("<div class=\"note\"><p><b>Positief verschil = duurder; negatief verschil = goedkoper.</b></p>")
                .append("<p>Een gedeeltelijke betaling verlaagt de eindkost niet: het open bedrag blijft meegerekend. Een slotbetaling voor een termijn sluit alleen die termijn af; latere termijnen blijven open. Alleen een slotbetaling voor de hele groep sluit de volledige groep af. Een overbetaling zonder afrekening blijft voorlopig.</p>")
                .append("<p>Interne Enrosed-opslag is geen uitgaande betaling en staat apart van de externe kost.</p></div>");

        html.append("<div class=\"page\"><div class=\"kicker\">").append(escape(order.number()))
                .append(" / Kostprijs</div><h2>De kost per product</h2><p class=\"muted\">")
                .append(basis).append(" verdeeld over ").append(total.unitCostQuantity()).append(" ").append(quantityBasis)
                .append(". Alle externe kosten zijn inbegrepen, ook inspectie en extra betalingen.</p>");
        if (total.receiptRecorded()) html.append("<p>Besteld: ").append(total.orderedQuantity()).append(" · Ontvangen: ")
                .append(total.receivedQuantity()).append(" · Beschadigd: ").append(total.damagedQuantity())
                .append(" · Bruikbaar: ").append(total.usableQuantity()).append("</p>");
        html.append("<table class=\"data\"><thead><tr><th style=\"width:24%\">Product</th><th style=\"width:9%\" class=\"num\">Stuks</th>")
                .append("<th class=\"num\">Begroot extern</th><th class=\"num\">Eindkost extern</th><th class=\"num\">Verschil</th>")
                .append("<th class=\"num\">Extern / stuk</th><th class=\"num\">Met opslag / stuk</th></tr></thead><tbody>");
        for (var line : report.lines()) {
            html.append("<tr><td>").append(escape(line.productName())).append("</td><td class=\"num nowrap\">")
                    .append(line.unitCostQuantity()).append("</td>");
            moneyCell(html, line.plannedExternalEur()); moneyCell(html, line.forecastExternalEur());
            differenceCell(html, line.varianceEur()); unitCell(html, line.forecastExternalUnitEur());
            unitCell(html, line.forecastPricingUnitEur()); html.append("</tr>");
        }
        html.append("<tr class=\"sum\"><td>Totaal / gemiddeld</td><td class=\"num nowrap\">").append(total.unitCostQuantity()).append("</td>");
        moneyCell(html, total.plannedExternalEur()); moneyCell(html, total.forecastExternalEur());
        differenceCell(html, total.varianceEur()); unitCell(html, total.forecastExternalUnitEur());
        unitCell(html, total.forecastPricingUnitEur());
        html.append("</tr></tbody></table><table class=\"totals\"><tr><td>").append(basis).append(" extern</td><td>")
                .append(money(total.forecastExternalEur())).append("</td></tr><tr><td>Interne Enrosed-opslag</td><td>")
                .append(money(total.internalMarkupEur())).append("</td></tr><tr><td><b>Totaal met interne opslag</b></td><td>")
                .append(money(total.forecastPricingEur())).append("</td></tr></table>");
        html.append("<div class=\"note\"><p><b>Berekening en verdeling</b></p>");
        if (!report.lines().isEmpty()) html.append("<p>").append(escape(report.lines().getFirst().allocationBasis())).append("</p>");
        for (String note : report.notes()) html.append("<p>").append(escape(note)).append("</p>");
        html.append("<p>Een bedrag per stuk ontbreekt als er geen stuks zijn. Deze afrekening wijzigt opgeslagen productprijzen en historische voorraadwaardes niet.</p></div>");
        if (total.legacyPaidTotalEur() != null) html.append("<p class=\"muted\">Historisch totaal op ontvangst: ")
                .append(money(total.legacyPaidTotalEur())).append(". Dit bedrag is informatief en wordt niet opnieuw bij de betaalregels opgeteld.</p>");
        html.append("</div><div class=\"page\"><div class=\"kicker\">").append(escape(order.number()))
                .append(" / Betaalregister</div><h2>Alle geregistreerde betalingen</h2>")
                .append("<p class=\"muted\">Dezelfde betaalregels als in Kosten &amp; bank, gekoppeld aan deze container. Bedragen in EUR gebruiken de koers die bij registratie is vastgelegd.</p>")
                .append("<table class=\"data\"><thead><tr><th style=\"width:14%\">Datum</th><th style=\"width:20%\">Bestemming</th>")
                .append("<th style=\"width:30%\">Omschrijving</th><th class=\"num\">Betaald</th><th class=\"num\">EUR</th></tr></thead><tbody>");
        List<PurchasePayment> sorted = payments == null ? List.of() : payments.stream().sorted(
                Comparator.comparing(PurchasePayment::paidOn, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(PurchasePayment::id, Comparator.nullsLast(Comparator.naturalOrder()))).toList();
        for (var payment : sorted) {
            html.append("<tr><td>").append(day(payment.paidOn())).append("</td><td>")
                    .append(escape(payment.payee().dutchLabel())).append("</td><td>").append(escape(payment.label()))
                    .append(PdfPurchaseRenderer.paymentScopeLabel(payment) == null ? ""
                            : "<br/><b class=\"small\">" + escape(PdfPurchaseRenderer.paymentScopeLabel(payment)) + "</b>")
                    .append("<br/><span class=\"muted small\">Betaling #").append(payment.id())
                    .append(" · ").append(escape(payment.actor())).append("</span></td><td class=\"num\">")
                    .append(currency(payment.amount(), payment.currency())).append("</td>");
            moneyCell(html, payment.amountEur()); html.append("</tr>");
        }
        if (sorted.isEmpty()) html.append("<tr><td colspan=\"5\">Nog geen betalingen geregistreerd.</td></tr>");
        html.append("<tr class=\"sum\"><td colspan=\"4\">Totaal werkelijk betaald</td>"); moneyCell(html, total.paidEur());
        html.append("</tr></tbody></table><div class=\"note\"><p>Correcties en verwijderingen voer je uit bij de containerbetaling. Kosten &amp; bank volgt automatisch dezelfde betaalregel; er wordt geen tweede kost geboekt.</p></div></div></body></html>");
        return html.toString();
    }

    private static void kpi(StringBuilder html, String label, BigDecimal value) {
        html.append("<td><span class=\"label\">").append(label).append("</span><span class=\"value\">")
                .append(money(value)).append("</span></td>");
    }
    private static void moneyCell(StringBuilder html, BigDecimal value) {
        html.append("<td class=\"num\">").append(money(value)).append("</td>");
    }
    private static void unitCell(StringBuilder html, BigDecimal value) {
        html.append("<td class=\"num\">").append(value == null ? "n.v.t." : "€ " + String.format(LOCALE, "%,.4f", value)).append("</td>");
    }
    private static void differenceCell(StringBuilder html, BigDecimal value) {
        html.append("<td class=\"num ").append(value.signum() > 0 ? "positive" : "negative").append("\">")
                .append(value.signum() > 0 ? "+" : "").append(money(value)).append("</td>");
    }
    private static String money(BigDecimal value) { return value == null ? "n.v.t." : "€ " + String.format(LOCALE, "%,.2f", value); }
    private static String currency(BigDecimal value, Currency currency) {
        return (currency == Currency.USD ? "US$ " : currency == Currency.CNY ? "CN¥ " : "€ ") + String.format(LOCALE, "%,.2f", value);
    }
    private static String day(LocalDate day) { return day == null ? "-" : DAY.format(day); }
    private static String escape(String value) {
        return value == null || value.isBlank() ? "-" : value.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;");
    }
    private static String status(PurchaseReconciliation.Stream stream) {
        return switch (stream.status()) {
            case PLANNED -> "Begroot";
            case UNPAID -> "Nog niet betaald";
            case PARTIAL -> "Gedeeltelijk betaald";
            case PAID -> "Volledig betaald";
            case OVERPAID -> stream.explicitlySettled() ? "Hoger afgerekend" : "Meer betaald - voorlopig";
            case SETTLED_LOWER -> "Lager afgerekend";
            case NOT_APPLICABLE -> "Niet van toepassing";
            case ADDITIONAL -> stream.explicitlySettled() ? "Extra betaling - afgerekend" : "Extra betaling";
        };
    }
}
