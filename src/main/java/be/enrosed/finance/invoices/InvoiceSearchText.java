package be.enrosed.finance.invoices;

import be.enrosed.finance.odoo.OdooInvoiceSearch;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.Normalizer;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The finder's one search field: number, party, amount, text or container, typed the way people type them ("CI 2026/118",
 * "1.250,00", "€ 18 420", "decor"). Pure: normalisation, words, amount parsing, row matching and the words sent to Odoo.
 */
public final class InvoiceSearchText {
    public static final int MAX_QUERY = 200;
    public static final int MAX_WORDS = 8;
    public static final int MAX_WORD = 64;
    public static final int MAX_ODOO_WORDS = 12;
    static final BigDecimal CENT = new BigDecimal("0.01");

    /** A space between a digit and exactly three digits that end the number: "18 420" is one amount. */
    private static final Pattern GROUPED = Pattern.compile("(?<=\\d)[ \\u00A0\\u202F](?=\\d{3}(?!\\d))");
    private static final Pattern MARKS = Pattern.compile("\\p{M}+");
    private static final Pattern SPACES = Pattern.compile("[\\s\\u00A0\\u202F]+");
    private static final Pattern NOT_BLIND = Pattern.compile("[^a-z0-9]");
    private static final Pattern AMOUNT_SHAPE = Pattern.compile("^[0-9][0-9.,]*[0-9]$|^[0-9]$");
    private static final Pattern LETTERS_DIGITS = Pattern.compile("^[\\p{L}\\p{N}]+$");
    private static final Pattern RUN = Pattern.compile("[\\p{L}\\p{N}]{2,}");
    private static final List<String> CURRENCY_WORDS = List.of("eur", "usd", "cny", "€", "$", "¥");

    private InvoiceSearchText() {}

    /** The words of a search: grouped thousands joined, standalone currency signs dropped, at most 8 words of 64 chars. */
    public static List<String> words(String q) {
        if (q == null) return List.of();
        String text = GROUPED.matcher(q.strip()).replaceAll("");
        List<String> words = new ArrayList<>();
        for (String word : SPACES.split(text)) {
            if (word.isEmpty() || word.equals("€") || word.equals("$") || word.equals("¥")) continue;
            words.add(word.length() > MAX_WORD ? word.substring(0, MAX_WORD) : word);
            if (words.size() == MAX_WORDS) break;
        }
        return List.copyOf(words);
    }

    /** Lower case, accents removed, whitespace collapsed. */
    public static String normalize(String value) {
        if (value == null) return "";
        String lower = value.toLowerCase(Locale.ROOT);
        String plain = MARKS.matcher(Normalizer.normalize(lower, Normalizer.Form.NFD)).replaceAll("");
        return SPACES.matcher(plain).replaceAll(" ").strip();
    }

    /** Only letters a-z and digits: "CI 2026/118" and "CI-2026-118" read the same. */
    public static String blind(String value) {
        return NOT_BLIND.matcher(normalize(value)).replaceAll("");
    }

    /** The amount a word stands for, scale 2, or null when it is no amount (see the contract's examples). */
    public static BigDecimal amount(String word) {
        if (word == null) return null;
        String text = word.strip();
        boolean changed = true;
        while (changed && !text.isEmpty()) {
            changed = false;
            String lower = text.toLowerCase(Locale.ROOT);
            for (String currency : CURRENCY_WORDS) {
                if (lower.startsWith(currency)) {
                    text = text.substring(currency.length()).strip();
                    changed = true;
                    break;
                }
                if (lower.endsWith(currency) && lower.length() > currency.length()) {
                    text = text.substring(0, text.length() - currency.length()).strip();
                    changed = true;
                    break;
                }
            }
        }
        if (text.startsWith("+") || text.startsWith("-")) text = text.substring(1);
        text = text.replace(" ", "").replace(" ", "").replace(" ", "").replace("'", "");
        if (!AMOUNT_SHAPE.matcher(text).matches()) return null;
        int lastDot = text.lastIndexOf('.');
        int lastComma = text.lastIndexOf(',');
        String plain;
        if (lastDot >= 0 && lastComma >= 0) {
            int decimal = Math.max(lastDot, lastComma);
            String fraction = text.substring(decimal + 1);
            if (fraction.length() < 1 || fraction.length() > 2 || !fraction.chars().allMatch(Character::isDigit)) return null;
            String whole = text.substring(0, decimal).replace(".", "").replace(",", "");
            plain = whole + "." + fraction;
        } else if (lastDot >= 0 || lastComma >= 0) {
            char separator = lastDot >= 0 ? '.' : ',';
            String[] parts = text.split(Pattern.quote(String.valueOf(separator)), -1);
            if (parts.length == 2 && parts[1].length() >= 1 && parts[1].length() <= 2) {
                plain = parts[0] + "." + parts[1];
            } else {
                for (int i = 1; i < parts.length; i++) if (parts[i].length() != 3) return null;
                plain = String.join("", parts);
            }
        } else {
            plain = text;
        }
        try {
            return new BigDecimal(plain).setScale(2, RoundingMode.HALF_UP);
        } catch (NumberFormatException invalid) {
            return null;
        }
    }

    /** A row's search text, prepared once per row. */
    public record Hay(String normalized, String blind, BigDecimal amount, BigDecimal amountEur) {
        public static Hay of(String text, BigDecimal amount, BigDecimal amountEur) {
            return new Hay(InvoiceSearchText.normalize(text), InvoiceSearchText.blind(text), amount, amountEur);
        }
    }

    /** A word prepared once per request. */
    public record Needle(String normalized, String blind, boolean hasDigit, BigDecimal amount) {
        public static Needle of(String word) {
            return new Needle(InvoiceSearchText.normalize(word), InvoiceSearchText.blind(word),
                    word.chars().anyMatch(Character::isDigit), InvoiceSearchText.amount(word));
        }
    }

    public static List<Needle> needles(String q) {
        return words(q).stream().map(Needle::of).toList();
    }

    /** Every word must match: as text, separator-blind (words with a digit, 3+ chars), or as an amount (± 0.01). */
    public static boolean matches(Hay hay, List<Needle> needles) {
        for (Needle needle : needles) if (!matches(hay, needle)) return false;
        return true;
    }

    static boolean matches(Hay hay, Needle needle) {
        if (!needle.normalized().isEmpty() && hay.normalized().contains(needle.normalized())) return true;
        if (needle.hasDigit() && needle.blind().length() >= 3 && hay.blind().contains(needle.blind())) return true;
        BigDecimal x = needle.amount();
        if (x == null) return false;
        return close(hay.amount(), x) || close(hay.amountEur(), x);
    }

    static boolean close(BigDecimal value, BigDecimal x) {
        return value != null && value.subtract(x).abs().compareTo(CENT) <= 0;
    }

    /** True for text-only matching (tests and the one-row checks). */
    public static boolean matches(String hay, BigDecimal amount, BigDecimal amountEur, String q) {
        return matches(Hay.of(hay, amount, amountEur), needles(q));
    }

    /**
     * The words sent to Odoo for a search. A word with an amount, or of letters/digits only, stays one word; any other word
     * is replaced by its runs of 2+ letters/digits (AND-ed), or dropped when it has none. Every word carries the moves
     * marked to a container whose number or alias matches it. At most 12 words.
     */
    public static List<OdooInvoiceSearch.Word> odooWords(String q, Map<Long, InvoiceRow.ContainerRef> markContainers) {
        List<OdooInvoiceSearch.Word> words = new ArrayList<>();
        for (String word : words(q)) {
            Set<Long> ids = markedMoves(word, markContainers);
            BigDecimal amount = amount(word);
            if (amount != null || LETTERS_DIGITS.matcher(word).matches()) {
                words.add(new OdooInvoiceSearch.Word(word, amount, ids));
            } else {
                Matcher runs = RUN.matcher(word);
                while (runs.find()) words.add(new OdooInvoiceSearch.Word(runs.group(), null, ids));
            }
        }
        return List.copyOf(words.size() > MAX_ODOO_WORDS ? words.subList(0, MAX_ODOO_WORDS) : words);
    }

    private static Set<Long> markedMoves(String word, Map<Long, InvoiceRow.ContainerRef> markContainers) {
        if (markContainers == null || markContainers.isEmpty()) return Set.of();
        String normal = normalize(word);
        String blindWord = blind(word);
        Set<Long> ids = new TreeSet<>();
        for (Map.Entry<Long, InvoiceRow.ContainerRef> entry : markContainers.entrySet()) {
            InvoiceRow.ContainerRef container = entry.getValue();
            if (container == null) continue;
            boolean hit = !normal.isEmpty() && (normalize(container.number()).contains(normal)
                    || normalize(container.alias()).contains(normal))
                    || blindWord.length() >= 3 && blind(container.number()).contains(blindWord);
            if (hit) ids.add(entry.getKey());
        }
        return Collections.unmodifiableSet(ids);
    }
}
