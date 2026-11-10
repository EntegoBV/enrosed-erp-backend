package be.enrosed.partnerportal.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * The JSON shapes of the partner portal (contract section 5). Partner-facing shapes never carry
 * a price, cost, supplier or container field of Enrosed's own: only what the partner typed.
 */
public final class PartnerPortalViews {

    private PartnerPortalViews() {}

    /* ------------------------------------------------------------------ partner side */

    /** A product as a tile shows it. */
    public record ProductTile(long productId, String sku, String name, String variant, String colourHex,
                              Long familyId, String familyName, String unitOne, String unitOther,
                              int piecesPerUnit, boolean hasPhoto, String photoVersion) {}

    /** ProductTile & { quantity }: sales units at the partner's location. */
    public record StockTile(long productId, String sku, String name, String variant, String colourHex,
                            Long familyId, String familyName, String unitOne, String unitOther,
                            int piecesPerUnit, boolean hasPhoto, String photoVersion, int quantity) {
        static StockTile of(ProductTile tile, int quantity) {
            return new StockTile(tile.productId(), tile.sku(), tile.name(), tile.variant(), tile.colourHex(),
                    tile.familyId(), tile.familyName(), tile.unitOne(), tile.unitOther(), tile.piecesPerUnit(),
                    tile.hasPhoto(), tile.photoVersion(), quantity);
        }
    }

    /** ProductTile & what this partner sold of it in the last 60 days (Terugkoop picks from these). */
    public record RecentSaleTile(long productId, String sku, String name, String variant, String colourHex,
                                 Long familyId, String familyName, String unitOne, String unitOther,
                                 int piecesPerUnit, boolean hasPhoto, String photoVersion,
                                 int soldQuantity, LocalDate lastSoldOn, BigDecimal lastAverageEur) {
        static RecentSaleTile of(ProductTile tile, int soldQuantity, LocalDate lastSoldOn, BigDecimal lastAverageEur) {
            return new RecentSaleTile(tile.productId(), tile.sku(), tile.name(), tile.variant(), tile.colourHex(),
                    tile.familyId(), tile.familyName(), tile.unitOne(), tile.unitOther(), tile.piecesPerUnit(),
                    tile.hasPhoto(), tile.photoVersion(), soldQuantity, lastSoldOn, lastAverageEur);
        }
    }

    public record PartnerInfo(long customerId, String name, String displayName) {}

    public record Costs(BigDecimal auctionCommissionPct, BigDecimal auctionTransportPct) {}

    public record Contact(String name, String phone) {}

    public record Overview(PartnerInfo partner, Costs costs, Contact contact, List<StockTile> stock,
                           List<RecentSaleTile> recentSales, List<PartnerReport> reports, LocalDate serverDate) {}

    public record ExtraCost(String mode, BigDecimal value) {}

    public record SalePart(int quantity, BigDecimal unitPriceEur, BigDecimal grossEur, BigDecimal commissionEur,
                           BigDecimal transportEur, BigDecimal extraCostEur, BigDecimal netEur) {}

    public record BuybackPart(int quantity, BigDecimal unitPriceEur, BigDecimal grossEur) {}

    public record Entry(long productId, String name, String variant, String colourHex, String unitOne,
                        String unitOther, boolean hasPhoto, String photoVersion, SalePart sale, BuybackPart buyback) {}

    public record Totals(int saleQuantity, int buybackQuantity, BigDecimal grossEur, BigDecimal commissionEur,
                         BigDecimal transportEur, BigDecimal extraCostEur, BigDecimal saleNetEur,
                         BigDecimal buybackEur, BigDecimal netEur) {}

    /** One melding with its parts merged back per entry. status OPEN | PARTLY_INVOICED | INVOICED | VOIDED. */
    public record PartnerReport(long id, String number, String channel, LocalDate soldOn, Instant createdAt,
                                String createdByName, BigDecimal commissionPct, BigDecimal transportPct,
                                ExtraCost extraCost, List<Entry> entries, Totals totals, String status,
                                boolean canUndo) {}

    public record ReportPage(List<PartnerReport> reports, boolean more) {}

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
}
