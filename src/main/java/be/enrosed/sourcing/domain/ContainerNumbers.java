package be.enrosed.sourcing.domain;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Which of two container numbers is the newer one, by the rule the screens use ({@code isNewerSource} in
 * purchase-phone-model.ts): "PO-2026-048" is newer than "PO-2026-047"; numbers of another shape never compare.
 */
public final class ContainerNumbers {

    private static final Pattern DIGITS = Pattern.compile("[0-9]+");

    private ContainerNumbers() {}

    /**
     * Both trimmed; null/blank → not newer; the shape (every digit run replaced by '#') must be exactly equal, case
     * included; then the digit runs are compared in order as numbers (leading zeros ignored), the first difference
     * decides; all equal → not newer.
     */
    public static boolean isNewer(String candidate, String than) {
        OptionalInt compared = compare(candidate, than);
        return compared.isPresent() && compared.getAsInt() > 0;
    }

    /** Negative, zero or positive as {@code a} is older, equal or newer than {@code b}; empty when they do not compare. */
    static OptionalInt compare(String a, String b) {
        if (a == null || b == null) return OptionalInt.empty();
        String left = a.trim();
        String right = b.trim();
        if (left.isEmpty() || right.isEmpty()) return OptionalInt.empty();
        if (!shape(left).equals(shape(right))) return OptionalInt.empty();
        List<BigInteger> leftRuns = runs(left);
        List<BigInteger> rightRuns = runs(right);
        for (int i = 0; i < leftRuns.size() && i < rightRuns.size(); i++) {
            int order = leftRuns.get(i).compareTo(rightRuns.get(i));
            if (order != 0) return OptionalInt.of(Integer.signum(order));
        }
        return OptionalInt.of(0);
    }

    private static String shape(String value) {
        return DIGITS.matcher(value).replaceAll("#");
    }

    private static List<BigInteger> runs(String value) {
        List<BigInteger> result = new ArrayList<>();
        Matcher matcher = DIGITS.matcher(value);
        while (matcher.find()) result.add(new BigInteger(matcher.group()));
        return result;
    }
}
