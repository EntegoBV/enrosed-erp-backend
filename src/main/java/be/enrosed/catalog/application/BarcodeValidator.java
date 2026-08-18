package be.enrosed.catalog.application;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * Validates the check digit of EAN-13 and ITF-14.
 *
 * A barcode may be empty - that is a choice, not an error. When something
 * is there it has to be right, because a barcode with a wrong check digit
 * simply does not scan in the customer's warehouse.
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

    /** Modulo-10 with weights 3 and 1, right to left. */
    private int checkDigit(String digits) {
        int sum = 0;
        for (int i = digits.length() - 1, weightIndex = 0; i >= 0; i--, weightIndex++) {
            int digit = digits.charAt(i) - '0';
            sum += digit * (weightIndex % 2 == 0 ? 3 : 1);
        }
        return (10 - (sum % 10)) % 10;
    }
}
