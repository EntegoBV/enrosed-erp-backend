package be.enrosed.finance.banking;

import java.math.BigDecimal;

/** A bank allocation may explain a two-cent difference, never change either stored amount. */
public final class BankAmountMatch {
    public static final BigDecimal MAX_DIFFERENCE = new BigDecimal("0.02");

    private BankAmountMatch() {}

    /** Call once for the complete signed allocation, not once per payment part. */
    public static boolean matches(BigDecimal signedBank, BigDecimal signedAllocated) {
        return signedBank != null && signedAllocated != null && signedBank.signum() != 0
                && signedBank.signum() == signedAllocated.signum()
                && signedBank.subtract(signedAllocated).abs().compareTo(MAX_DIFFERENCE) <= 0;
    }
}
