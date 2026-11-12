package be.enrosed.partnerportal.application;

import be.enrosed.partnerportal.domain.PartnerSaleTexts;
import be.enrosed.sales.application.SalesOrderService;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;

/**
 * The compact Documentnotitie of a document Factureren makes (contract R2 §6 + v2-D), written from the
 * document's own figures ({@link PartnerInvoiceFigures}), so note, preview and breakdown agree to the
 * cent. Container:
 * <pre>
 * Veilingverkopen VV-12, VV-14, VV-17–19 (01/11–10/11/2026) · 5 meldingen · 120 stuks · deelfactuur partnercontainer PO-2026-003.
 * Netto opbrengst € p · inkoopkost € c (Verhoeven 100 % · Enrosed 0 %) · winst € w · ons deel 50 % € s · onze waarde € v
 *   · voorschot verrekend € a · saldo € b. [Negatief saldo: ...] Open voorschotfacturen ...
 * </pre>
 * with s = v - c (never round2(w x pct) on its own: per-line rounding and a slotfactuur's correction of
 * earlier deelfacturen live in v; the correction is named "(waarvan correctie eerdere deelfacturen € k)").
 * Own stock: the same shape with "eigen voorraad" and "inkoopkost € c (Enrosed 100 %)", ending at onze
 * waarde (no advance). Numbers collapse only when consecutive; the quantity is in pieces with the piece
 * noun for one product, "n stuks" when every product counts per piece, else left out. The per-product
 * detail goes to the internal note. The manual Veilingafrekening keeps its own text.
 */
final class PartnerInvoiceNotes {

    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private PartnerInvoiceNotes() {}

    /** "Veilingverkopen VV-12, VV-14 (01/11–10/11/2026) · 2 meldingen[ · 120 stuks]". */
    static String head(Collection<Long> reportIds, LocalDate first, LocalDate last, String pieces) {
        String period = PartnerSaleTexts.period(first, last);
        int count = (int) reportIds.stream().distinct().count();
        return "Veilingverkopen " + PartnerSaleTexts.numbers(reportIds) + (period.isEmpty() ? "" : " (" + period + ")")
                + " · " + count + (count == 1 ? " melding" : " meldingen") + (pieces == null ? "" : " · " + pieces);
    }

    /** The pieces a document invoices: "144 rozen" for one product, "120 stuks" when all count per piece, else null. */
    static String pieces(PartnerInvoiceFigures.Group figures, PartnerProductTiles.Catalog catalog) {
        java.util.List<PartnerInvoiceFigures.Row> counted = figures.rows().stream().filter(row -> row.quantity() > 0).toList();
        if (counted.size() == 1) {
            PartnerInvoiceFigures.Row row = counted.getFirst();
            PartnerPortalViews.ProductTile tile = catalog.tile(row.productId());
            int pieces = row.quantity() * Math.max(1, tile.piecesPerSalesUnit());
            return PartnerSaleTexts.quantity(pieces, tile.pieceOne(), tile.pieceOther());
        }
        boolean perPiece = figures.rows().stream().allMatch(row -> catalog.tile(row.productId()).piecesPerSalesUnit() <= 1);
        if (!perPiece || counted.isEmpty()) return null;
        int pieces = counted.stream().mapToInt(PartnerInvoiceFigures.Row::quantity).sum();
        return PartnerSaleTexts.quantity(pieces, "stuk", "stuks");
    }

    static String container(PartnerInvoiceFigures.Group figures, String containerName, String partnerName,
                            PartnerProductTiles.Catalog catalog) {
        BigDecimal value = figures.sum(PartnerInvoiceFigures.Row::valueEur);
        BigDecimal advance = figures.sum(PartnerInvoiceFigures.Row::advanceEur);
        BigDecimal costShare = figures.costSharePct();
        String split = costShare == null ? ""
                : " (" + partnerName + " " + PartnerSaleTexts.pct(costShare) + " · Enrosed "
                + PartnerSaleTexts.pct(HUNDRED.subtract(costShare)) + ")";
        StringBuilder note = new StringBuilder(head(figures.reportIds(), figures.firstSoldOn(), figures.lastSoldOn(),
                pieces(figures, catalog)))
                .append(" · ").append(figures.finalSettlement() ? "slotfactuur" : "deelfactuur")
                .append(" partnercontainer ").append(containerName).append(".\n")
                .append(money(figures, split));
        if (advance.signum() != 0) {
            note.append(" · voorschot verrekend ").append(PartnerSaleTexts.euro(advance))
                    .append(" · saldo ").append(PartnerSaleTexts.euro(value.subtract(advance)));
        }
        note.append('.');
        if (value.compareTo(advance) < 0) note.append(' ').append(SalesOrderService.SETTLEMENT_NEGATIVE_BALANCE);
        return note.append(' ').append(SalesOrderService.SETTLEMENT_OPEN_ADVANCES).toString();
    }

    static String own(PartnerInvoiceFigures.Group figures, PartnerProductTiles.Catalog catalog) {
        return head(figures.reportIds(), figures.firstSoldOn(), figures.lastSoldOn(), pieces(figures, catalog))
                + " · eigen voorraad.\n" + money(figures, " (Enrosed 100 %)") + ".";
    }

    /** "Netto opbrengst € p · inkoopkost € c (split) · winst € w · ons deel z % € s[ (waarvan correctie …)] · onze waarde € v". */
    private static String money(PartnerInvoiceFigures.Group figures, String split) {
        BigDecimal proceeds = figures.sum(PartnerInvoiceFigures.Row::proceedsEur);
        BigDecimal cost = figures.sum(PartnerInvoiceFigures.Row::costEur);
        BigDecimal value = figures.sum(PartnerInvoiceFigures.Row::valueEur);
        BigDecimal correction = figures.sum(PartnerInvoiceFigures.Row::correctionEur);
        String share = figures.profitSharePct() == null ? "" : " " + PartnerSaleTexts.pct(figures.profitSharePct());
        return "Netto opbrengst " + PartnerSaleTexts.euro(proceeds) + " · inkoopkost " + PartnerSaleTexts.euro(cost) + split
                + " · winst " + PartnerSaleTexts.euro(proceeds.subtract(cost))
                + " · ons deel" + share + " " + PartnerSaleTexts.euro(value.subtract(cost))
                + (correction.signum() == 0 ? "" : " (waarvan correctie eerdere deelfacturen " + PartnerSaleTexts.euro(correction) + ")")
                + " · onze waarde " + PartnerSaleTexts.euro(value);
    }
}
