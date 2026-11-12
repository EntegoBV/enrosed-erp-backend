package be.enrosed.partnerportal.application;

import be.enrosed.partnerportal.domain.PartnerCountUnits;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * The JSON shapes of the partner portal (contract section 5 of round 1, sections 1-4 and v2-A of
 * round 2). Partner-facing shapes never carry a price, cost, amount, supplier or free text of
 * Enrosed's own: only what the partner typed (his own melding money). Container identity and
 * logistics are allowed, and only in {@link PartnerContainerViews} (round 2): the PO number (never
 * the alias), own or not, status, type, dates, ports, the location name of an own container or
 * MAIN, quantities, pieces, cartons, m³, fill, pallets, weight and the product card's sizes.
 */
public final class PartnerPortalViews {

    private PartnerPortalViews() {}

    /** Overview, StaffPage and the summary answer it; a round-2 frontend refuses to save without it. */
    public static final int API_VERSION = 2;

    /* ------------------------------------------------------------------ partner side */

    /**
     * A product as a tile shows it. unitOne / unitOther / piecesPerUnit name the sales unit (round 1);
     * round 2 adds how it counts: salesUnit PIECE | DISPLAY, piecesPerSalesUnit, the piece noun, the
     * offered count units smallest first ({unit, one, other, pieces}) and the partner's default unit.
     * categoryId / categoryName / categoryOrder (review round 2): the product's category and its place in
     * Enrosed's own category order (0 first; null without a category), for grouping like the ERP's
     * Voorraad. Never a price.
     */
    public record ProductTile(long productId, String sku, String name, String variant, String colourHex,
                              Long familyId, String familyName, String unitOne, String unitOther,
                              int piecesPerUnit, boolean hasPhoto, String photoVersion, String salesUnit,
                              int piecesPerSalesUnit, String pieceOne, String pieceOther,
                              List<PartnerCountUnits.CountUnit> countUnits, String defaultUnit,
                              Long categoryId, String categoryName, Integer categoryOrder) {
        ProductTile withDefaultUnit(String unit) {
            return unit == null || unit.equals(defaultUnit) ? this : new ProductTile(productId, sku, name, variant,
                    colourHex, familyId, familyName, unitOne, unitOther, piecesPerUnit, hasPhoto, photoVersion,
                    salesUnit, piecesPerSalesUnit, pieceOne, pieceOther, countUnits, unit, categoryId, categoryName,
                    categoryOrder);
        }
    }

    /** ProductTile & { quantity }: sales units at the partner's location. */
    public record StockTile(long productId, String sku, String name, String variant, String colourHex,
                            Long familyId, String familyName, String unitOne, String unitOther,
                            int piecesPerUnit, boolean hasPhoto, String photoVersion, String salesUnit,
                            int piecesPerSalesUnit, String pieceOne, String pieceOther,
                            List<PartnerCountUnits.CountUnit> countUnits, String defaultUnit, int quantity,
                            Long categoryId, String categoryName, Integer categoryOrder) {
        static StockTile of(ProductTile tile, int quantity) {
            return new StockTile(tile.productId(), tile.sku(), tile.name(), tile.variant(), tile.colourHex(),
                    tile.familyId(), tile.familyName(), tile.unitOne(), tile.unitOther(), tile.piecesPerUnit(),
                    tile.hasPhoto(), tile.photoVersion(), tile.salesUnit(), tile.piecesPerSalesUnit(), tile.pieceOne(),
                    tile.pieceOther(), tile.countUnits(), tile.defaultUnit(), quantity, tile.categoryId(),
                    tile.categoryName(), tile.categoryOrder());
        }
    }

    /** ProductTile & what this partner sold of it in the last 60 days (Terugkoop picks from these). */
    public record RecentSaleTile(long productId, String sku, String name, String variant, String colourHex,
                                 Long familyId, String familyName, String unitOne, String unitOther,
                                 int piecesPerUnit, boolean hasPhoto, String photoVersion, String salesUnit,
                                 int piecesPerSalesUnit, String pieceOne, String pieceOther,
                                 List<PartnerCountUnits.CountUnit> countUnits, String defaultUnit,
                                 int soldQuantity, LocalDate lastSoldOn, BigDecimal lastAverageEur,
                                 Long categoryId, String categoryName, Integer categoryOrder) {
        static RecentSaleTile of(ProductTile tile, int soldQuantity, LocalDate lastSoldOn, BigDecimal lastAverageEur) {
            return new RecentSaleTile(tile.productId(), tile.sku(), tile.name(), tile.variant(), tile.colourHex(),
                    tile.familyId(), tile.familyName(), tile.unitOne(), tile.unitOther(), tile.piecesPerUnit(),
                    tile.hasPhoto(), tile.photoVersion(), tile.salesUnit(), tile.piecesPerSalesUnit(), tile.pieceOne(),
                    tile.pieceOther(), tile.countUnits(), tile.defaultUnit(), soldQuantity, lastSoldOn, lastAverageEur,
                    tile.categoryId(), tile.categoryName(), tile.categoryOrder());
        }
    }

    public record PartnerInfo(long customerId, String name, String displayName) {}

    public record Costs(BigDecimal auctionCommissionPct, BigDecimal auctionTransportPct) {}

    public record Contact(String name, String phone) {}

    public record Overview(PartnerInfo partner, Costs costs, Contact contact, List<StockTile> stock,
                           List<RecentSaleTile> recentSales, List<PartnerReport> reports, LocalDate serverDate,
                           int apiVersion) {}

    /** Round 1's single extra cost; on newer meldingen filled from the one cost line, or AMOUNT of the sum of several. */
    public record ExtraCost(String mode, BigDecimal value) {}

    /** One extra cost line: what was typed and the euros it comes to over the melding's sales. */
    public record CostLine(String description, String mode, BigDecimal value, BigDecimal amountEur) {}

    /** The buyer of a direct sale: only name and city ("Klant verwijderd" when it is gone). */
    public record Buyer(long customerId, String company, String city) {}

    /**
     * The sale of an entry (its parts merged). enteredUnit / enteredCount / piecePriceEur /
     * enteredPiecesPerUnit are what was typed; for a round-1 row (or a count that no longer matches
     * the booked pieces) they read in sales units: STUK or VERPAKKING, the quantity, the unit price per
     * piece. pieces = the booked pieces.
     */
    public record SalePart(int quantity, BigDecimal unitPriceEur, BigDecimal grossEur, BigDecimal commissionEur,
                           BigDecimal transportEur, BigDecimal extraCostEur, BigDecimal netEur, String enteredUnit,
                           int enteredCount, BigDecimal piecePriceEur, int enteredPiecesPerUnit, int pieces) {}

    /** The buyback of an entry: the clock price total (gross) with its commission and transport; costEur = -net. */
    public record BuybackPart(int quantity, BigDecimal unitPriceEur, BigDecimal grossEur, BigDecimal commissionEur,
                              BigDecimal transportEur, BigDecimal costEur, String enteredUnit, int enteredCount,
                              BigDecimal piecePriceEur, int enteredPiecesPerUnit, int pieces) {}

    /** One product of a melding with the full tile (round 2, the category since review round 2) and its sale and buyback. */
    public record Entry(long productId, String name, String variant, String colourHex, String unitOne,
                        String unitOther, boolean hasPhoto, String photoVersion, SalePart sale, BuybackPart buyback,
                        String sku, Long familyId, String familyName, int piecesPerUnit, String salesUnit,
                        int piecesPerSalesUnit, String pieceOne, String pieceOther,
                        List<PartnerCountUnits.CountUnit> countUnits, String defaultUnit,
                        Long categoryId, String categoryName, Integer categoryOrder) {}

    /**
     * commission / transport / extra are the sale side's; buybackEur is the terugkoopkost (buyback gross +
     * its commission + transport, i.e. -net), buybackGrossEur the clock price total; netEur = saleNetEur -
     * buybackEur. Quantities in sales units, pieces in pieces.
     */
    public record Totals(int saleQuantity, int buybackQuantity, BigDecimal grossEur, BigDecimal commissionEur,
                         BigDecimal transportEur, BigDecimal extraCostEur, BigDecimal saleNetEur,
                         BigDecimal buybackEur, BigDecimal netEur, int salePieces, int buybackPieces,
                         BigDecimal buybackGrossEur) {}

    /**
     * One melding with its parts merged back per entry. status OPEN | PARTLY_INVOICED | INVOICED | VOIDED.
     * note (additive) is what was typed with it. Round 2 (additive): editToken (send it back as
     * expectedToken on an edit), updatedAt/By, the extra cost lines, the buyer, confirmed (staff
     * confirmed it, or a document holds a part) and, for the partner, canEdit (open, not confirmed) and
     * canUndo (also no buyback of another melding mirrors it). An edit that changes a quantity or product
     * of a mirrored melding is refused by the server; a price, cost or note fix is not.
     */
    public record PartnerReport(long id, String number, String channel, LocalDate soldOn, Instant createdAt,
                                String createdByName, BigDecimal commissionPct, BigDecimal transportPct,
                                ExtraCost extraCost, List<Entry> entries, Totals totals, String status,
                                boolean canUndo, String note, String editToken, Instant updatedAt,
                                String updatedByName, List<CostLine> extraCosts, Buyer buyer, boolean confirmed,
                                Instant confirmedAt, String confirmedByName, boolean canEdit) {}

    public record ReportPage(List<PartnerReport> reports, boolean more) {}

    /**
     * A buyer the partner may pick for a direct sale (GET/POST /api/partner-portal/customers): only the name,
     * city and country of an ERP customer; addedByYou = this partner added it itself.
     */
    public record PartnerCustomerTile(long customerId, String company, String city, String countryCode,
                                      boolean addedByYou) {}

    /* -------------------------------------------------------------------- staff side */

    public record AdminLogin(long id, String username, String displayName, boolean active,
                             Instant passwordSetAt, Instant lastLoginAt) {}

    /**
     * The stored values (null = the default applies) beside the defaults, so the screen can show
     * "standaard 4,5" as a placeholder; locationName is the location that applies.
     */
    public record AdminSettings(BigDecimal auctionCommissionPct, BigDecimal auctionTransportPct, Long locationId,
                                String locationName, BigDecimal defaultAuctionCommissionPct,
                                BigDecimal defaultAuctionTransportPct) {}

    public record UnshippedSettlement(long id, String number) {}

    public record AdminRow(long customerId, String customerName, boolean partner, AdminLogin login,
                           AdminSettings settings, List<UnshippedSettlement> unshippedSettlements) {}

    /** PUT /api/partner-portal-admin/{customerId}/login; password null (or empty) keeps the current one. */
    public record LoginRequest(String username, String displayName, String password, Boolean active) {
        @Override
        public String toString() {
            return "LoginRequest[username=" + username + ", active=" + active + "]";
        }
    }

    public record SettingsRequest(BigDecimal auctionCommissionPct, BigDecimal auctionTransportPct, Long locationId) {}

    /** A stored default count unit of a partner's product (GET /{customerId}/units lists only these rows). */
    public record UnitDefault(long productId, String unit) {}

    /** One change: unit STUK | VERPAKKING | OMDOOS, or null (and STUK) = back to the default per stuk. */
    public record UnitChange(Long productId, String unit) {}

    /** PUT /{customerId}/units: a partial update, never a full replace. */
    public record UnitsRequest(List<UnitChange> changes) {}

    /* ------------------------------------------------------------ Veilingverkopen */

    /**
     * One source part of a melding for staff: containerName null = "Eigen voorraad"; invoiceId and
     * invoiceNumber the document that holds it (null while open); unitCostEur own parts only.
     */
    public record StaffPart(long id, int entryNo, String lineType, long productId, String name, String variant,
                            Long purchaseOrderId, String containerName, int quantity, BigDecimal grossEur,
                            BigDecimal netEur, BigDecimal unitCostEur, Long invoiceId, String invoiceNumber,
                            boolean open, BigDecimal commissionEur, BigDecimal transportEur, BigDecimal extraCostEur,
                            int pieces) {}

    /**
     * PartnerReport plus who and for whom, the withdrawal and the parts per source. canEdit and canUndo are
     * the staff rule (also when confirmed): canEdit = open, canUndo = open and no buyback of another melding
     * mirrors it; revisionOf is set on an archive row (an old version, read by id only).
     */
    public record StaffReport(long id, String number, String channel, LocalDate soldOn, Instant createdAt,
                              String createdByName, BigDecimal commissionPct, BigDecimal transportPct,
                              ExtraCost extraCost, List<Entry> entries, Totals totals, String status,
                              boolean canUndo, String note, long customerId, String customerName, String createdBy,
                              Instant voidedAt, String voidedBy, String voidReason, List<StaffPart> parts,
                              String editToken, Instant updatedAt, String updatedByName, List<CostLine> extraCosts,
                              Buyer buyer, boolean confirmed, Instant confirmedAt, String confirmedByName,
                              boolean canEdit, Long revisionOf) {
        static StaffReport of(PartnerReport base, boolean staffCanEdit, boolean staffCanUndo, long customerId,
                              String customerName, String createdBy, Instant voidedAt, String voidedBy,
                              String voidReason, List<StaffPart> parts, Long revisionOf) {
            return new StaffReport(base.id(), base.number(), base.channel(), base.soldOn(), base.createdAt(),
                    base.createdByName(), base.commissionPct(), base.transportPct(), base.extraCost(), base.entries(),
                    base.totals(), base.status(), staffCanUndo, base.note(), customerId, customerName, createdBy,
                    voidedAt, voidedBy, voidReason, parts, base.editToken(), base.updatedAt(), base.updatedByName(),
                    base.extraCosts(), base.buyer(), base.confirmed(), base.confirmedAt(), base.confirmedByName(),
                    staffCanEdit, revisionOf);
        }
    }

    /** POST /api/partner-sales/reports/confirm | unconfirm: the meldingen as they are now. */
    public record ConfirmResult(List<StaffReport> reports) {}

    /** What is still to invoice: meldingen with an open part, their open sale quantity and open net. */
    public record OpenTotals(int openReports, int openSaleQuantity, BigDecimal openNetEur) {}

    /**
     * The sums of a filtered list (round 2, the list's "Totaal · n meldingen" row): every melding the filter
     * matches, also past the 1000-row cap, withdrawn ones left out. salePieces in pieces; costsEur = the sale
     * side's commission + transport + extra; buybackEur = the terugkoopkost (incl. its commission and
     * transport); netEur = grossEur - costsEur - buybackEur.
     */
    public record FilteredTotals(int reports, int salePieces, BigDecimal grossEur, BigDecimal costsEur,
                                 BigDecimal buybackEur, BigDecimal netEur) {}

    /**
     * GET /api/partner-sales. totals = what is still to invoice (round 1, always). filteredTotals = the sums
     * of the filtered set (round 2 filters only; null on the round-1 id-paged list).
     */
    public record StaffPage(List<StaffReport> reports, boolean more, OpenTotals totals, int apiVersion,
                            FilteredTotals filteredTotals) {}

    /**
     * One partner of the summary. Round 2 (Bevestigen): unconfirmedReports = meldingen with an open part that
     * staff have not confirmed (Factureren cannot take them); confirmedOpen* = what Factureren can take now
     * (confirmed, with open parts): their count, open sale quantity (sales units, like openSaleQuantity)
     * and open net. open = unconfirmed + confirmedOpen (per melding and per euro). openSalePieces /
     * confirmedOpenSalePieces (review round 2): the same open sale in pieces (a display of 12 rozen counts 12,
     * like salePieces), for "· 54 stuks" on the home card and the Te doen task.
     */
    public record PartnerSummary(long customerId, String customerName, int openReports, int openSaleQuantity,
                                 BigDecimal openNetEur, Instant lastReportAt, int unconfirmedReports,
                                 int confirmedOpenReports, int confirmedOpenSaleQuantity, BigDecimal confirmedOpenNetEur,
                                 int openSalePieces, int confirmedOpenSalePieces) {}

    /** GET /api/partner-sales/summary: the nav count, the home card and the Te doen task read this. */
    public record Summary(int openReports, int openSaleQuantity, BigDecimal openNetEur, Instant lastReportAt,
                          int recentReports14d, List<PartnerSummary> partners, int apiVersion, int unconfirmedReports,
                          int confirmedOpenReports, int confirmedOpenSaleQuantity, BigDecimal confirmedOpenNetEur,
                          int openSalePieces, int confirmedOpenSalePieces) {}

    /**
     * One live melding. open = not fully invoiced; openSaleQuantity / openNetEur (additive) = only its parts that
     * are still open by the open-part rule, so "nog te factureren" here equals the summary's for the same melding.
     * costsEur = the sale side's commission + transport + extra; buybackEur = the terugkoopkost (-net, round 2:
     * incl. its commission and transport), buybackGrossEur the clock price total; netEur = gross - costs -
     * buybackEur. salePieces in pieces, buyer on a direct sale. Review round 2 (additive): confirmed as the
     * staff list says it (staff confirmed it, or a document holds a part), buybackPieces and openSalePieces in
     * pieces beside buybackQuantity and openSaleQuantity.
     */
    public record AnalyticsReport(long id, String number, long customerId, String channel, LocalDate soldOn,
                                  boolean open, int saleQuantity, int buybackQuantity, BigDecimal grossEur,
                                  BigDecimal costsEur, BigDecimal buybackEur, BigDecimal netEur,
                                  int openSaleQuantity, BigDecimal openNetEur, BigDecimal buybackGrossEur,
                                  int salePieces, Buyer buyer, boolean confirmed, int buybackPieces,
                                  int openSalePieces) {}

    /**
     * One melding x product (reportId additive); netEur = sale net - terugkoopkost. Averages are derived, never
     * summed. openSaleQuantity / openNetEur (additive): the still-open parts of this product in this melding.
     * salePieces, buybackPieces, openSalePieces: the same in pieces.
     */
    public record AnalyticsProduct(long reportId, long productId, String name, String variant, LocalDate soldOn,
                                   String channel, boolean open, int saleQuantity, int buybackQuantity,
                                   BigDecimal grossEur, BigDecimal netEur, int openSaleQuantity, BigDecimal openNetEur,
                                   int salePieces, int buybackPieces, int openSalePieces) {}

    public record Analytics(List<AnalyticsReport> reports, List<AnalyticsProduct> products) {}

    /* ------------------------------------------------------------------ Factureren */

    /**
     * POST /api/partner-sales/invoice-preview; reportIds null = every open melding of the partner, until by sold_on.
     * includeUnconfirmed (round 2, preview only, with explicit reportIds): those meldingen count as confirmed for
     * this read, so Veilingverkopen can show the Inkoop en winst of a melding before Bevestigen, as Factureren will
     * reckon it once confirmed; /invoice has no such field and stays confirmed-only.
     */
    public record InvoicePreviewRequest(Long customerId, List<Long> reportIds, LocalDate until, Boolean includeUnconfirmed) {}

    /** POST /api/partner-sales/invoice: the preview's reportIds; parts of other meldingen are never taken. */
    public record InvoiceRequest(Long customerId, List<Long> reportIds) {}

    /**
     * One product of a group: what its open parts sold and bought back, the invoice quantity
     * (sold - bought back) and the net proceeds the invoice uses (proceedsEur: saleNet - buyback, plus
     * what it absorbs of other products of the source, see the group warnings), with the cost and our
     * value as the invoice computes them. buybackEur is the terugkoopkost (-net: round 2 adds the buyback's
     * commission and transport), buybackGrossEur the clock price totals alone; salePieces = sold in pieces.
     */
    public record InvoicePreviewLine(long productId, String name, String variant, int soldQuantity, int buybackQuantity,
                                     int quantity, BigDecimal saleNetEur, BigDecimal buybackEur, BigDecimal proceedsEur,
                                     BigDecimal costEur, BigDecimal valueEur, BigDecimal buybackGrossEur, int salePieces,
                                     BigDecimal grossEur, BigDecimal commissionEur, BigDecimal transportEur,
                                     BigDecimal extraEur, BigDecimal buybackCostEur, BigDecimal absorbedEur,
                                     List<String> absorbedFrom, BigDecimal profitEur, BigDecimal enrosedProfitEur,
                                     BigDecimal partnerProfitEur, BigDecimal advanceEur, BigDecimal correctionEur,
                                     BigDecimal costSharePct, BigDecimal profitSharePct) {}

    /**
     * One invoice to be: source CONTAINER (a partner settlement on purchaseOrderId, createAuctionSettlement's
     * own arithmetic) or OWN (one invoice for Enrosed's own stock). balanceEur = valueEur - advanceEur;
     * blocked = the refusal the creation would give (nothing is made for it).
     *
     * <p>Round 2 (ERP only, never in a partner response; the document's own figures, the same the note and
     * the breakdown show): profitSharePct (ours), costSharePct (the partner's financed part: the advance
     * agreement's percent, else the container's; own stock 0), proceedsEur (netto opbrengst) - costEur
     * (inkoopkost, external, without Enrosed kost) = profitEur; enrosedProfitEur = valueEur - costEur;
     * partnerProfitEur = proceedsEur - valueEur; correctionEur = a slotfactuur's true-up of earlier
     * deelfacturen (inside valueEur). The money figures are null on a blocked group. gross / commission /
     * transport / extra / buybackCost / salePieces sum every product the group holds (absorbed q = 0 ones
     * too), so grossEur - commissionEur - transportEur - extraEur - buybackCostEur = proceedsEur.
     */
    public record InvoicePreviewGroup(String source, Long purchaseOrderId, String containerName,
                                      List<InvoicePreviewLine> lines, BigDecimal valueEur, BigDecimal advanceEur,
                                      BigDecimal balanceEur, boolean finalSettlement, String blocked,
                                      List<String> warnings, BigDecimal profitSharePct, BigDecimal costSharePct,
                                      BigDecimal proceedsEur, BigDecimal costEur, BigDecimal profitEur,
                                      BigDecimal enrosedProfitEur, BigDecimal partnerProfitEur, BigDecimal correctionEur,
                                      BigDecimal grossEur, BigDecimal commissionEur, BigDecimal transportEur,
                                      BigDecimal extraEur, BigDecimal buybackCostEur, int salePieces) {}

    /** A product whose open parts stay open in this run, and why. */
    public record InvoiceSkipped(long productId, String name, String source, Long purchaseOrderId, String reason) {}

    /** warnings (round 2): e.g. an unconfirmed buyback that mirrors a chosen sale and stays out of this run. */
    public record InvoicePreview(List<InvoicePreviewGroup> groups, List<InvoiceSkipped> skipped, List<Long> reportIds,
                                 BigDecimal profitSharePct, List<String> warnings) {}

    public record CreatedInvoice(long id, String number, String source, Long purchaseOrderId) {}

    public record BlockedGroup(Long purchaseOrderId, String message) {}

    public record InvoiceResult(List<CreatedInvoice> invoices, List<BlockedGroup> blocked, List<InvoiceSkipped> skipped) {}

    /* ------------------------------------------------- the margin popup (round 2) */

    /** A melding behind a document. */
    public record BreakdownMelding(long id, String number, LocalDate soldOn, String channel) {}

    /**
     * One product of a report-backed document (GET /api/partner-sales/documents/{id}/breakdown), from the
     * figure snapshot written with it: grossEur - commissionEur - transportEur - extraEur - buybackCostEur
     * (+ absorbedEur, what the q = 0 netting moved onto it, negative when moved off; absorbedFrom names the
     * other products) = proceedsEur; - costEur = profitEur; valueEur - costEur = enrosedProfitEur;
     * proceedsEur - valueEur = partnerProfitEur; valueEur - advanceEur = what the document invoices for it;
     * correctionEur = the slotfactuur's true-up inside valueEur. quantity = soldQuantity -
     * boughtBackQuantity (sales units), pieces = quantity in pieces. onLine = it has an invoice line.
     */
    public record BreakdownLine(long productId, String name, String variant, int soldQuantity, int boughtBackQuantity,
                                int quantity, int pieces, int salePieces, BigDecimal grossEur, BigDecimal commissionEur,
                                BigDecimal transportEur, BigDecimal extraEur, BigDecimal buybackCostEur,
                                BigDecimal proceedsEur, BigDecimal absorbedEur, List<String> absorbedFrom,
                                BigDecimal costEur, BigDecimal costSharePct, BigDecimal profitEur,
                                BigDecimal profitSharePct, BigDecimal enrosedProfitEur, BigDecimal partnerProfitEur,
                                BigDecimal valueEur, BigDecimal advanceEur, BigDecimal correctionEur, boolean onLine) {}

    /**
     * The breakdown of one report-backed document: source CONTAINER | OWN, the meldingen behind it (and their
     * channel: AUCTION, DIRECT or MIXED), the lines and the document totals. fallback = a document from
     * before round 2 (no figure snapshot): its lines come from the invoice lines / settlement snapshot and
     * the linked parts, labelled with fallbackNote. ERP only.
     */
    public record DocumentBreakdown(long salesOrderId, String source, Long purchaseOrderId, boolean finalSettlement,
                                    String channel, List<BreakdownMelding> meldingen, List<BreakdownLine> lines,
                                    BigDecimal grossEur, BigDecimal commissionEur, BigDecimal transportEur,
                                    BigDecimal extraEur, BigDecimal buybackCostEur, BigDecimal proceedsEur,
                                    BigDecimal costEur, BigDecimal profitEur, BigDecimal enrosedProfitEur,
                                    BigDecimal partnerProfitEur, BigDecimal valueEur, BigDecimal advanceEur,
                                    BigDecimal balanceEur, BigDecimal correctionEur, BigDecimal costSharePct,
                                    BigDecimal profitSharePct, boolean fallback, String fallbackNote) {}
}
