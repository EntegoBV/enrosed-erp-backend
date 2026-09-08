package be.enrosed.sales.application;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Document numbers written from a pattern: {@code partner/{jaar}/{nr:3}} gives
 * {@code partner/2026/003}, {@code ENR-{jaar}-{nr}} gives {@code ENR-2026-0001}.
 * {@code {jaar}} is the year, {@code {nr}} the sequence padded to four digits,
 * {@code {nr:3}} to three; everything else is written as typed.
 */
public final class NumberSeries {

    private static final Pattern TOKEN = Pattern.compile("\\{(jaar|nr(?::(\\d))?)\\}");

    private NumberSeries() {}

    /** The number for this sequence in this year. */
    public static String format(String pattern, int year, int sequence) {
        StringBuilder out = new StringBuilder();
        Matcher token = TOKEN.matcher(pattern);
        int at = 0;
        while (token.find()) {
            out.append(pattern, at, token.start());
            if (token.group(1).startsWith("nr")) {
                int digits = token.group(2) == null ? 4 : Integer.parseInt(token.group(2));
                out.append(String.format("%0" + digits + "d", sequence));
            } else {
                out.append(year);
            }
            at = token.end();
        }
        out.append(pattern.substring(at));
        return out.toString();
    }

    /** Matches the numbers this pattern wrote in this year; group 1 is the sequence. */
    public static Pattern series(String pattern, int year) {
        StringBuilder regex = new StringBuilder("^");
        Matcher token = TOKEN.matcher(pattern);
        int at = 0;
        while (token.find()) {
            regex.append(Pattern.quote(pattern.substring(at, token.start())));
            regex.append(token.group(1).startsWith("nr") ? "(\\d+)" : String.valueOf(year));
            at = token.end();
        }
        regex.append(Pattern.quote(pattern.substring(at))).append("$");
        return Pattern.compile(regex.toString());
    }

    /** True when the pattern will produce a sequence at all. */
    public static boolean valid(String pattern) {
        return pattern != null && pattern.contains("{nr");
    }
}
