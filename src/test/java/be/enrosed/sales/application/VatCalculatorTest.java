package be.enrosed.sales.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import be.enrosed.sales.domain.Country;
import be.enrosed.sales.domain.Customer;
import be.enrosed.sales.domain.VatTreatment;
import be.enrosed.shared.Language;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/**
 * Where the VAT on a delivery goes, seen from Belgium. The fiscal
 * representative case is the one that is easy to get wrong: goods cleared in
 * the Netherlands are supplied there, so the Dutch buyer with a VAT number
 * owes the VAT himself, and that must never depend on the buyer's country
 * alone.
 */
class VatCalculatorTest {

    private final VatCalculator calculator = new VatCalculator();

    VatCalculatorTest() {
        calculator.homeCountry = "BE";
    }

    @Test
    void goodsClearedThroughOurFiscalRepresentativeShiftTheVatToTheDutchBuyer() {
        VatCalculator.Result result = calculator.determine(netherlands(), customer("NL858617262B02", true));
        assertEquals(VatTreatment.VERLEGD_FISCAAL_VERTEGENWOORDIGER, result.treatment());
        assertEquals(BigDecimal.ZERO, result.ratePct());
        assertTrue(result.treatment().isExempt(), "nothing is charged; the buyer accounts for it");
        assertTrue(result.treatment().legalMentionIn(Language.EN).startsWith("REVERSE CHARGE - VAT shifted to the Dutch customer"));
    }

    @Test
    void withoutTheRepresentativeADutchBuyerIsAnOrdinaryIntraCommunitySupply() {
        assertEquals(VatTreatment.INTRACOMMUNAUTAIR,
                calculator.determine(netherlands(), customer("NL858617262B02", false)).treatment());
    }

    @Test
    void theRepresentativeNeedsAVatRegisteredBuyerAndNeverTouchesAHomeDelivery() {
        assertEquals(VatTreatment.EU_ZONDER_BTW_NUMMER,
                calculator.determine(netherlands(), customer("", true)).treatment(),
                "no VAT number: no reverse charge, the ordinary rule applies");
        Country belgium = new Country("BE", "België", BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, new BigDecimal("21"), 1, true);
        assertEquals(VatTreatment.BINNENLAND,
                calculator.determine(belgium, customer("BE0123456789", true)).treatment());
    }

    private static Country netherlands() {
        return new Country("NL", "Nederland", BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, new BigDecimal("21"), 2, true);
    }

    private static Customer customer(String vatNumber, boolean representative) {
        return new Customer(7L, "Bloemen Venlo BV", "Jan", "jan@venlo.example", "", vatNumber, "NL",
                Language.NL, "Kade 1", "5911", "Venlo", "DAP", null, "", LocalDate.of(2026, 1, 1),
                true, null, null, representative, null);
    }
}
