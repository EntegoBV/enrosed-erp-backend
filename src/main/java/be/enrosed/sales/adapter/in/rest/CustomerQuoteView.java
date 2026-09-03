package be.enrosed.sales.adapter.in.rest;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Customer-safe quotation projection shared by the public portal and the
 * authenticated staff preview.
 *
 * Keeping this as an allow-list DTO is intentional: additions to the internal
 * sales aggregate cannot silently expose cost prices, margin, internal notes
 * or the portal token.
 */
public record CustomerQuoteView(
        boolean preview,
        String number, String status, String orderDate, String validUntil, String incoterm,
        String notes, String companyName, String contactName, String countryCode,
        List<CustomerLine> lines, CustomerTotals totals,
        boolean canRespond, String signedByName, List<PendingProposal> proposals,
        String deliveryTerms,
        String freight,
        String loadMode,
        String freightPricingStrategy,
        String language,
        Map<String, String> text,
        /** What we told the customer when we withdrew the quote; null otherwise. */
        String cancellationMessage) {

    public record CustomerLine(
            Long productId, String sku, String description, String photoUrl,
            int quantity, int cartons, int pallets, BigDecimal cbm,
            int piecesPerCarton,
            BigDecimal unitPrice, BigDecimal discountPct, BigDecimal net,
            boolean inventoryKnown, boolean inStock,
            String deliveryDate, String deliveryWeek) {}

    public record CustomerTotals(
            int pieces, int cartons, int pallets, BigDecimal cbm,
            BigDecimal subtotal, BigDecimal orderDiscountPercent, BigDecimal orderDiscountAmount,
            BigDecimal extraDiscountPercent, String extraDiscountLabel, BigDecimal extraDiscountAmount,
            BigDecimal goodsTotal, BigDecimal freight, BigDecimal handling,
            BigDecimal total, BigDecimal vatRatePct, BigDecimal vatAmount, BigDecimal totalInclVat,
            String vatTreatment, String vatLegalMention) {}

    public record PendingProposal(
            String status, String proposedAt, String message, String responseMessage) {}
}
