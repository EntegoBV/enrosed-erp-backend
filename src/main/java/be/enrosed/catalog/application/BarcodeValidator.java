package be.enrosed.catalog.application;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * Controleert het controlecijfer van EAN-13 en ITF-14.
 *
 * Een barcode mag leeg zijn - dat is geen fout maar een keuze. Staat er wel
 * iets, dan moet het kloppen, want een barcode met een fout controlecijfer
 * wordt in het magazijn van de klant gewoon niet gelezen.
 */
@ApplicationScoped
public class BarcodeValidator {

    public record Result(boolean valid, String message) {
        public static Result ok(String message) { return new Result(true, message); }
        public static Result fail(String message) { return new Result(false, message); }
    }

    public Result validate(String barcode) {
        if (barcode == null || barcode.isBlank()) {
            return Result.ok("leeg");
        }
        String value = barcode.trim();
        if (!value.matches("\\d+")) {
            return Result.fail("barcode mag alleen cijfers bevatten");
        }
        if (value.length() != 13 && value.length() != 14) {
            return Result.fail(value.length() + " cijfers - verwacht 13 (EAN-13) of 14 (ITF-14)");
        }
        int expected = checkDigit(value.substring(0, value.length() - 1));
        int actual = value.charAt(value.length() - 1) - '0';
        if (expected != actual) {
            return Result.fail("controlecijfer klopt niet - verwacht " + expected);
        }
        return Result.ok(value.length() == 13 ? "geldige EAN-13" : "geldige ITF-14");
    }

    /** Modulo-10 met gewichten 3 en 1, van rechts naar links. */
    private int checkDigit(String digits) {
        int sum = 0;
        for (int i = digits.length() - 1, weightIndex = 0; i >= 0; i--, weightIndex++) {
            int digit = digits.charAt(i) - '0';
            sum += digit * (weightIndex % 2 == 0 ? 3 : 1);
        }
        return (10 - (sum % 10)) % 10;
    }
}
