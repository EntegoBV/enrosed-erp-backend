package be.enrosed.finance.banking;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BankAmountMatchTest {
    @Test void acceptsInclusiveTwoCentBoundaryOnBothSidesAndBothDirections() {
        for(String bank: new String[]{"100", "-100"}) {
            BigDecimal amount=new BigDecimal(bank);
            for(String difference: new String[]{"-0.02", "-0.01", "0", "0.01", "0.02"})
                assertTrue(BankAmountMatch.matches(amount,amount.add(new BigDecimal(difference))),bank+" / "+difference);
            for(String difference: new String[]{"-0.03", "0.03", "0.0201"})
                assertFalse(BankAmountMatch.matches(amount,amount.add(new BigDecimal(difference))),bank+" / "+difference);
        }
    }

    @Test void smallAmountsStillRequireTheSameNonzeroDirection() {
        assertFalse(BankAmountMatch.matches(new BigDecimal("0.01"),new BigDecimal("-0.01")));
        assertFalse(BankAmountMatch.matches(new BigDecimal("-0.01"),new BigDecimal("0.01")));
        assertFalse(BankAmountMatch.matches(BigDecimal.ZERO,BigDecimal.ZERO));
        assertFalse(BankAmountMatch.matches(BigDecimal.ONE,null));
        assertFalse(BankAmountMatch.matches(null,BigDecimal.ONE));
        assertFalse(BankAmountMatch.matches(new BigDecimal("0.01"),BigDecimal.ZERO));
    }

    @Test void allowanceIsNotMultipliedByTheNumberOfPaymentParts() {
        BigDecimal bank=new BigDecimal("-100");
        assertTrue(BankAmountMatch.matches(bank,new BigDecimal("60").add(new BigDecimal("39.98")).negate()));
        assertFalse(BankAmountMatch.matches(bank,new BigDecimal("59.98").add(new BigDecimal("39.98")).negate()));
    }
}
