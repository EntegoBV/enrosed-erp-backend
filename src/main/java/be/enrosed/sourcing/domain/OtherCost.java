package be.enrosed.sourcing.domain;

import java.math.BigDecimal;

/**
 * One named cost the buyer books next to the inspection: a certificate, a
 * lab test, a sample run. Like the inspection it stays its own line on the
 * order and the internal sheets and never enters a piece price.
 */
public record OtherCost(String label, BigDecimal amountEur) {

    /* The label is kept as typed: the editor previews every keystroke and a
       trailing space stripped mid-word would swallow what the buyer types.
       The service trims it on save. */
    public OtherCost {
        label = label == null ? "" : label;
    }

    /** True when the cost adds to the bottom line. */
    public boolean charged() {
        return amountEur != null && amountEur.signum() > 0;
    }

    /** A row the buyer added and left empty: no name, no amount. */
    public boolean blank() {
        return label.isBlank() && (amountEur == null || amountEur.signum() == 0);
    }
}
