package be.enrosed.sales.domain;

import be.enrosed.shared.Money;

import java.math.BigDecimal;

/**
 * A credit note is priced positive like every other document; its sign is
 * carried by the document type. Every ledger that adds documents together
 * reads the amounts through here so a credit note counts against, never for.
 */
public final class DocumentSign {
    private DocumentSign() {}

    /** What the customer owes (positive) or is owed (negative), including VAT. */
    public static BigDecimal claim(SalesOrder order, PricedOrder priced) {
        return signed(order, Money.money(priced.totals().totalInclVat()));
    }

    /** The document total excluding VAT, negative for a credit note. */
    public static BigDecimal signedTotal(SalesOrder order, PricedOrder priced) {
        return signed(order, Money.money(priced.totals().total()));
    }

    /** The recognised cost, negative for a credit note. */
    public static BigDecimal signedCost(SalesOrder order, PricedOrder priced) {
        return signed(order, Money.money(priced.totals().costTotal()));
    }

    /** The pieces sold, negative for a credit note. */
    public static int signedPieces(SalesOrder order, PricedOrder priced) {
        return order.isCreditNote() ? -priced.totals().pieces() : priced.totals().pieces();
    }

    private static BigDecimal signed(SalesOrder order, BigDecimal amount) {
        return order.isCreditNote() ? amount.negate() : amount;
    }
}
