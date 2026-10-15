package be.enrosed.sourcing.adapter.out.document;

import be.enrosed.shared.Brand;
import be.enrosed.shared.Currency;
import be.enrosed.shared.PdfFonts;
import be.enrosed.sourcing.application.ReconciliationUnits;
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
    /**
     * The unit of a display figure, as a small line under it like on the
     * internal calculation: "€ 31,2000 /display" on one line does not fit a
     * 12.5 % column.
     */
    private static final String PER_DISPLAY = "<br/><span class=\"muted small\">per display</span>";

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
                table.products { font-size: 7.5pt; }
                .products th, .products td { padding-left: 4pt; padding-right: 4pt; }
                .products td.num { white-space: nowrap; }
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
                    .append("<th style=\"width:30%\">Termijn</th><th class=\"num\">Afgesproken</th><th class=\"num\">Betaald</th>")
                    .append("<th class=\"num\">Open</th><th class=\"num\">Verschil</th><th class=\"num\">Besparing</th></tr></thead><tbody>");
            for (var instalment : report.supplierInstalments()) {
                html.append("<tr><td><b>").append(escape(instalment.label()))
                        .append("</b><br/><span class=\"muted small\">")
                        .append(escape(termStatus(instalment, report.supplierInstalments()))).append("</span></td>");
                moneyCell(html, instalment.plannedEur()); moneyCell(html, instalment.paidEur());
                moneyCell(html, instalment.remainingEur()); differenceCell(html, instalment.differenceEur());
                moneyCell(html, instalment.settledSavingEur());
                html.append("</tr>");
            }
            html.append("</tbody></table>");
        }
        html.append("<div class=\"note\"><p><b>Positief verschil = duurder; negatief verschil = goedkoper.</b></p>")
                .append("<p>Een gedeeltelijke betaling verlaagt de eindkost niet: het open bedrag blijft meegerekend. Wat op de ene termijn te veel is betaald, wordt verrekend met wat op een andere termijn te weinig is betaald; alleen het saldo blijft open. Een slotbetaling voor een termijn sluit alleen die termijn af en houdt hem buiten die verrekening; latere termijnen blijven open. Alleen een slotbetaling voor de hele groep sluit de volledige groep af. Een overbetaling zonder afrekening blijft voorlopig.</p>")
                .append("<p>Enrosed kost is een interne kost, geen uitgaande betaling, en staat apart van de externe kost.</p></div>");

        boolean sets = report.lines().stream().anyMatch(PdfPurchasePaymentsRenderer::isSet);
        String setBasis = total.setBasis() == null ? "PIECE" : total.setBasis();
        /* An average over different products says nothing: then every figure per stuk or per display is per product.
           The lines decide too, so a report that skipped the units never prints one. */
        List<Long> productIds = report.lines().stream().map(PurchaseReconciliation.Line::productId).toList();
        boolean average = total.containerAverage() && ReconciliationUnits.containerAverage(productIds);
        int productCount = total.productCount() > 0 ? total.productCount() : ReconciliationUnits.productCount(productIds);
        html.append("<div class=\"page\"><div class=\"kicker\">").append(escape(order.number()))
                .append(" / Kostprijs</div><h2>De kost per product</h2><p class=\"muted\">")
                .append(basis).append(" verdeeld over ").append(average
                        ? quantityText(total, quantityBasis, sets, setBasis)
                        : productsText(total, productCount, quantityBasis, sets, setBasis))
                .append(". Alle externe kosten zijn inbegrepen, ook inspectie en extra betalingen.")
                .append(average ? "" : " De kost per stuk of per display staat per product.")
                .append(" De kolom Enrosed kost toont welk deel van de Enrosed kost op elk product is verdeeld.</p>");
        if (total.receiptRecorded()) html.append("<p>Besteld: ").append(total.orderedQuantity()).append(" · Ontvangen: ")
                .append(total.receivedQuantity()).append(" · Beschadigd: ").append(total.damagedQuantity())
                .append(" · Bruikbaar: ").append(total.usableQuantity()).append("</p>");
        String per = sets ? "eenheid" : "stuk";
        /* Eight columns on A4: every amount keeps to one line, the product name wraps. */
        html.append("<table class=\"data products\"><thead><tr><th style=\"width:17%\">Product</th><th style=\"width:8%\" class=\"num\">")
                .append(sets ? "Aantal" : "Stuks").append("</th>")
                .append("<th style=\"width:12.5%\" class=\"num\">Begroot extern</th><th style=\"width:12.5%\" class=\"num\">Eindkost extern</th>")
                .append("<th style=\"width:12.5%\" class=\"num\">Verschil</th>")
                .append("<th style=\"width:12.5%\" class=\"num\">Extern / ").append(per).append("</th>")
                .append("<th style=\"width:12.5%\" class=\"num\">Enrosed kost</th>")
                .append("<th style=\"width:12.5%\" class=\"num\">Incl. Enrosed kost / ").append(per).append("</th></tr></thead><tbody>");
        for (var line : report.lines()) {
            html.append("<tr><td>").append(escape(line.productName())).append("</td><td class=\"num nowrap\">")
                    .append(isSet(line) ? setCount(line.unitCostQuantity()) : String.valueOf(line.unitCostQuantity()))
                    .append("</td>");
            moneyCell(html, line.plannedExternalEur()); moneyCell(html, line.forecastExternalEur());
            differenceCell(html, line.varianceEur());
            if (isSet(line)) {
                setCell(html, line.forecastExternalUnitEur(), line.forecastExternalPieceEur());
                moneyCell(html, line.internalMarkupEur());
                setCell(html, line.forecastPricingUnitEur(), line.forecastPricingPieceEur());
            } else if (sets) {
                unitCell(html, line.forecastExternalUnitEur(), " /stuk");
                moneyCell(html, line.internalMarkupEur());
                unitCell(html, line.forecastPricingUnitEur(), " /stuk");
            } else {
                unitCell(html, line.forecastExternalUnitEur());
                moneyCell(html, line.internalMarkupEur());
                unitCell(html, line.forecastPricingUnitEur());
            }
            html.append("</tr>");
        }
        html.append("<tr class=\"sum\"><td>").append(average ? "Totaal / gemiddeld" : "Totaal")
                .append("</td><td class=\"num nowrap\">")
                .append(!sets || "PIECE".equals(setBasis) ? String.valueOf(total.unitCostQuantity())
                        : "DISPLAY".equals(setBasis) ? setCount(total.unitCostQuantity())
                        : count(total.unitCostPieceQuantity()) + " stuks")
                .append("</td>");
        moneyCell(html, total.plannedExternalEur()); moneyCell(html, total.forecastExternalEur());
        differenceCell(html, total.varianceEur());
        if (!average) {
            perProductCell(html);
            moneyCell(html, total.internalMarkupEur());
            perProductCell(html);
        } else if (sets && "DISPLAY".equals(setBasis)) {
            unitCell(html, total.forecastExternalUnitEur(), PER_DISPLAY);
            moneyCell(html, total.internalMarkupEur());
            unitCell(html, total.forecastPricingUnitEur(), PER_DISPLAY);
        } else {
            unitCell(html, total.forecastExternalUnitEur());
            moneyCell(html, total.internalMarkupEur());
            unitCell(html, total.forecastPricingUnitEur());
        }
        html.append("</tr></tbody></table><table class=\"totals\"><tr><td>").append(basis).append(" extern</td><td>")
                .append(money(total.forecastExternalEur())).append("</td></tr><tr><td>Enrosed kost <span class=\"muted small\">intern, verdeeld over de producten</span></td><td>")
                .append(money(total.internalMarkupEur())).append("</td></tr><tr><td><b>Totaal incl. Enrosed kost</b></td><td>")
                .append(money(total.forecastPricingEur())).append("</td></tr></table>");
        html.append("<div class=\"note\"><p><b>Berekening en verdeling</b></p>");
        if (!report.lines().isEmpty()) html.append("<p>").append(escape(report.lines().getFirst().allocationBasis())).append("</p>");
        for (String note : report.notes()) html.append("<p>").append(escape(note)).append("</p>");
        html.append("<p>Een bedrag per stuk ontbreekt als er geen stuks zijn. Deze afrekening wijzigt opgeslagen productprijzen en historische voorraadwaardes niet.")
                .append(sets ? " Bij displays staat de prijs per display en per stuk." : "").append("</p></div>");
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
    private static void unitCell(StringBuilder html, BigDecimal value, String unit) {
        html.append("<td class=\"num\">").append(value == null ? "n.v.t." : unit(value) + unit).append("</td>");
    }
    /** A display: the price per display, and under it the price per piece. */
    private static void setCell(StringBuilder html, BigDecimal perSet, BigDecimal perPiece) {
        html.append("<td class=\"num\">").append(perSet == null ? "n.v.t." : unit(perSet) + PER_DISPLAY)
                .append(perPiece == null ? "" : "<br/>" + unit(perPiece) + " /stuk").append("</td>");
    }
    /** The total row of a container of different products: no average, the figure is in each product's row. */
    private static void perProductCell(StringBuilder html) {
        html.append("<td class=\"num muted small\">per product</td>");
    }
    private static String unit(BigDecimal value) { return "€ " + String.format(LOCALE, "%,.4f", value); }
    private static String count(int value) { return String.format(LOCALE, "%,d", value); }
    /** "100" with "displays" under it: the 8 % Aantal column holds the count, not the word next to it. */
    private static String setCount(int value) {
        return count(value) + "<br/><span class=\"muted small\">" + (value == 1 ? "display" : "displays") + "</span>";
    }
    private static boolean isSet(PurchaseReconciliation.Line line) { return "DISPLAY".equals(line.salesUnit()); }

    /**
     * What the cost is spread over: "100 bestelde displays (1.200 stuks)" for
     * a container of one display size, "1.400 bestelde stuks (displays als 12
     * rozen geteld)" for displays mixed with pieces or with each other; plain
     * pieces otherwise.
     */
    private static String quantityText(PurchaseReconciliation.Totals total, String quantityBasis, boolean sets,
                                       String setBasis) {
        if (!sets || "PIECE".equals(setBasis)) return total.unitCostQuantity() + " " + quantityBasis;
        String state = total.receiptRecorded() ? "bruikbare ontvangen" : "bestelde";
        if ("DISPLAY".equals(setBasis)) {
            return count(total.unitCostQuantity()) + " " + state + " " + (total.unitCostQuantity() == 1 ? "display" : "displays")
                    + " (" + count(total.unitCostPieceQuantity()) + " stuks)";
        }
        String counted = total.setPieces() != null && total.setUnitOther() != null
                ? "displays als " + total.setPieces() + " " + total.setUnitOther() + " geteld"
                : "displays als hun aantal stuks geteld";
        return count(total.unitCostPieceQuantity()) + " " + state + " stuks (" + counted + ")";
    }
    /**
     * What the cost is spread over when the products differ: the products
     * first, then the same quantities as for one product ("3 producten
     * (6.920 bestelde stuks)", "2 producten (1.400 bestelde stuks, displays
     * als 12 rozen geteld)").
     */
    private static String productsText(PurchaseReconciliation.Totals total, int productCount, String quantityBasis,
                                       boolean sets, String setBasis) {
        String products = productCount == 1 ? "1 product" : productCount + " producten";
        String state = total.receiptRecorded() ? "bruikbare ontvangen" : "bestelde";
        String quantities;
        if (!sets || "PIECE".equals(setBasis)) {
            quantities = count(total.unitCostQuantity()) + " " + quantityBasis;
        } else if ("DISPLAY".equals(setBasis)) {
            quantities = count(total.unitCostQuantity()) + " " + state + " " + (total.unitCostQuantity() == 1 ? "display" : "displays")
                    + ", " + count(total.unitCostPieceQuantity()) + " stuks";
        } else {
            quantities = count(total.unitCostPieceQuantity()) + " " + state + " stuks, "
                    + (total.setPieces() != null && total.setUnitOther() != null
                            ? "displays als " + total.setPieces() + " " + total.setUnitOther() + " geteld"
                            : "displays als hun aantal stuks geteld");
        }
        return products + " (" + quantities + ")";
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
    /**
     * The term's word, as on the Betalingen tab. A term keeps its own
     * difference in the Verschil column; the word says what netting with the
     * payee's other terms did with it.
     */
    static String termStatus(PurchaseReconciliation.SupplierInstalment term,
                             List<PurchaseReconciliation.SupplierInstalment> terms) {
        BigDecimal offset = term.offsetEur() == null ? BigDecimal.ZERO : term.offsetEur();
        String with = "verrekend met " + dutchList(terms.stream()
                .filter(other -> other != term && other.offsetEur() != null
                        && other.offsetEur().signum() == -offset.signum() && offset.signum() != 0)
                .map(PurchaseReconciliation.SupplierInstalment::label).toList());
        String moved = offset.signum() == 0 ? null : money(offset.abs()) + " " + with;
        boolean settled = term.explicitlySettled() && term.finalized();
        String word;
        if (settled && term.settledSavingEur().signum() > 0) word = "Afgerekend · minder betaald";
        else if (settled && term.overpaidEur().signum() > 0) word = "Afgerekend · meer betaald";
        else if (offset.signum() > 0 && term.remainingEur().signum() == 0) return "Betaald · " + with;
        else if (offset.signum() < 0 && term.overpaidEur().signum() > 0) word = "Betaald · " + money(term.overpaidEur()) + " te veel";
        else if (offset.signum() < 0) return "Betaald · " + with;
        else if (offset.signum() > 0) word = "Termijn nog open";
        else if (term.overpaidEur().signum() > 0) word = "Te veel betaald · nakijken";
        else word = term.finalized() ? "Termijn afgerekend" : "Termijn nog open";
        return moved == null ? word : word + " · " + moved;
    }

    private static String dutchList(List<String> labels) {
        if (labels.size() < 2) return labels.isEmpty() ? "" : labels.getFirst();
        return String.join(", ", labels.subList(0, labels.size() - 1)) + " en " + labels.getLast();
    }

    private static String status(PurchaseReconciliation.Stream stream) {
        return switch (stream.status()) {
            case PLANNED -> "Begroot";
            case UNPAID -> "Nog niet betaald";
            case PARTIAL -> "Gedeeltelijk betaald";
            case PAID -> "Volledig betaald";
            case OVERPAID -> stream.explicitlySettled() || stream.finalized() ? settled(stream)
                    : stream.remainingEur().signum() == 0 ? "Betaald · " + money(stream.overpaidEur()) + " te veel"
                    : "Meer betaald - voorlopig";
            case SETTLED_LOWER -> "Lager afgerekend";
            case NOT_APPLICABLE -> "Niet van toepassing";
            case ADDITIONAL -> stream.explicitlySettled() ? "Extra betaling - afgerekend" : "Extra betaling";
        };
    }

    /**
     * A settled payee can hold an overrun and a saving at once, from terms
     * closed on their own; the word follows the net result, as the Verschil
     * column does, not the overrun alone.
     */
    private static String settled(PurchaseReconciliation.Stream stream) {
        int net = stream.overpaidEur().compareTo(stream.settledSavingEur());
        return net > 0 ? "Hoger afgerekend" : net < 0 ? "Lager afgerekend" : "Afgerekend";
    }
}
