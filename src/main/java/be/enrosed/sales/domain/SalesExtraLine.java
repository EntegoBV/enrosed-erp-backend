package be.enrosed.sales.domain;

import be.enrosed.shared.Money;

import java.math.BigDecimal;

/**
 * A line the seller writes freely on a quote or invoice next to the
 * products: assembly, an extra transport leg, a sample, a lump-sum
 * discount. It reads as its own line on the document, counts in the
 * total, and stays outside the tier discounts and the product margin.
 */
public record SalesExtraLine(String description, BigDecimal quantity, BigDecimal unitPriceEur) {

    /* The description is kept as typed: the editor previews every
       keystroke and a trailing space stripped mid-word would swallow what
       the seller types. The service trims it on save. */
    public SalesExtraLine {
        description = description == null ? "" : description;
        quantity = quantity == null ? BigDecimal.ONE : quantity;
    }

    /** Quantity times unit price, in money. */
    public BigDecimal total() {
        return Money.money(quantity.multiply(Money.nz(unitPriceEur)));
    }

    /** A row the seller added and left empty: no text, no price. */
    public boolean blank() {
        return description.isBlank() && (unitPriceEur == null || unitPriceEur.signum() == 0);
    }
}
