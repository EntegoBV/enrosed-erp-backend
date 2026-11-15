package be.enrosed.sourcing.tracking.bill;

import be.enrosed.sourcing.tracking.bill.BillText.Cell;
import be.enrosed.sourcing.tracking.bill.BillText.Line;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads a bill of lading (or its telex release, which is the same form) from the cells {@link BillText} found: the
 * B/L number and carrier, the containers, the ship and voyage, the ports, and the day the goods were laden on board.
 * A value is taken from under its label ("Ocean Vessel/Voy. No.", "VESSEL", "Port of Loading", "B/L NO."), so the
 * same rules read an Evergreen and a CMA CGM bill and most others built as a grid of boxes.
 *
 * <p>Nothing is guessed: a field that is not found stays null, a container number must pass its ISO 6346 check
 * digit, and a bill marked DRAFT, PROOFREAD or NOT FINAL says so ({@link Reading#draft()}), because the ship on a
 * draft is the planned one and may still change (the Foam Bear container of October 2026 was rolled from the EVER
 * GLOBE of its draft to the EVER ALLY of its final bill).</p>
 */
public final class BillParser {

    /**
     * What one document says. bill: it is a bill of lading at all. draft: marked as not final. billNumber as the
     * carrier writes it, with the Evergreen prefix (EGLV + 12 digits). containers: valid ISO 6346 numbers, in reading
     * order. onBoard: laden/shipped on board; issuedOn: the bill's date of issue.
     */
    public record Reading(boolean bill, boolean draft, String carrier, String billNumber, List<String> containers,
                          String vessel, String voyage, String portOfLoading, String portOfDischarge,
                          LocalDate onBoard, LocalDate issuedOn) {
        public static final Reading NONE = new Reading(false, false, null, null, List.of(), null, null, null, null, null, null);

        /** A final bill names the ship that sailed: it has its on-board day, or is not marked as a draft. */
        public boolean finalCopy() {
            return bill && !draft;
        }
    }

    private static final Pattern BOX = Pattern.compile("(?<![A-Z0-9])([A-Z]{3}[UJZ])[ -]?([0-9]{6})[ -]?([0-9])(?![0-9])");
    private static final Pattern EVERGREEN_BILL = Pattern.compile("(?<![A-Z0-9])EGLV\\s*([0-9]{12})(?![0-9])");
    private static final Pattern NUMBER_TOKEN = Pattern.compile("(?<![A-Z0-9])[A-Z0-9][A-Z0-9/-]{5,24}(?![A-Z0-9])");
    private static final Pattern VOYAGE_TOKEN = Pattern.compile("[0-9A-Z]{2,10}(?:-[0-9A-Z]{1,8})?");
    private static final Pattern NUMBERED_LABEL = Pattern.compile("^\\(?[0-9]{1,2}\\)\\s*[A-Za-z].*");
    private static final Pattern DRAFT = Pattern.compile("(?<![A-Z])(DRAFT|NOT FINAL|PROOFREAD|PROOF READ|FOR CHECKING|CHECK COPY)(?![A-Z])");

    private static final Pattern VESSEL_VOYAGE_LABEL = label("(OCEAN )?VESSEL ?(/|AND|&)? ?VOY(AGE|\\.)?( ?NO\\.?| ?NUMBER)?.*");
    private static final Pattern VESSEL_LABEL = label("(OCEAN |EXPORT |MOTHER )?VESSEL( NAME)?");
    private static final Pattern VOYAGE_LABEL = label("VOY(AGE|\\.)?( ?NO\\.?| ?NUMBER)?");
    private static final Pattern LOADING_LABEL = label("PORT OF LOADING");
    private static final Pattern DISCHARGE_LABEL = label("PORT OF DISCHARGE");
    private static final Pattern BILL_LABEL = label("(B/L|BILL OF LADING|SEA ?WAYBILL|WAYBILL) ?(NO\\.?|NUMBER|#).*");
    private static final Pattern DOCUMENT_LABEL = label("DOCUMENT NO\\.?");
    private static final Pattern ON_BOARD_LABEL = label("(LADEN|SHIPPED|LOADED) ON BOARD.*|ON BOARD DATE.*|DATE OF SHIPMENT.*");
    private static final Pattern ISSUE_LABEL = Pattern.compile(".*(DATE OF ISSUE|ISSUE ?/ ?DATE|ISSUE DATE|PLACE AND DATE).*");

    private static final Pattern COUNTRY_TAIL = Pattern.compile("(?i)\\s+(P\\.? ?R\\.? ?(OF )?CHINA|PRC|CHINA|CN|THE NETHERLANDS|NETHERLANDS|HOLLAND|NL|BELGIUM|BE|"
            + "GERMANY|DE|FRANCE|FR|UNITED KINGDOM|UK|GB)$");

    private static final String MONTHS = "JAN|FEB|MAR|APR|MAY|JUN|JUL|AUG|SEP|OCT|NOV|DEC";
    private static final Pattern MONTH_FIRST = Pattern.compile("(?<![A-Z])(" + MONTHS + ")[A-Z]*\\.?\\s*([0-9]{1,2})\\s*,\\s*([0-9]{4})(?![0-9])");
    private static final Pattern DAY_FIRST = Pattern.compile("(?<![0-9])([0-9]{1,2})[ .-]?(" + MONTHS + ")[A-Z]*\\.?[ .,-]{0,2}([0-9]{4})(?![0-9])");
    private static final Pattern YEAR_FIRST = Pattern.compile("(?<![0-9])(20[0-9]{2})[-/.]([0-9]{1,2})[-/.]([0-9]{1,2})(?![0-9])");

    /** How the carrier is recognised in the text, most telling first. */
    private static final List<Map.Entry<Pattern, String>> CARRIERS = List.of(
            carrier("EVERGREEN|(?<![A-Z0-9])EGLV", "Evergreen"),
            carrier("CMA ?CGM", "CMA CGM"),
            carrier("MEDITERRANEAN SHIPPING|(?<![A-Z])MSC(?![A-Z])|(?<![A-Z0-9])MEDU", "MSC"),
            carrier("MAERSK|(?<![A-Z0-9])MAEU", "Maersk"),
            carrier("COSCO|(?<![A-Z0-9])COSU", "COSCO"),
            carrier("OCEAN NETWORK EXPRESS|(?<![A-Z0-9])ONEY", "ONE"),
            carrier("HAPAG|(?<![A-Z0-9])HLCU", "Hapag-Lloyd"),
            carrier("(?<![A-Z])OOCL|ORIENT OVERSEAS|(?<![A-Z0-9])OOLU", "OOCL"),
            carrier("YANG ?MING|(?<![A-Z0-9])YMLU", "Yang Ming"),
            carrier("HYUNDAI MERCHANT|(?<![A-Z])HMM(?![A-Z])|(?<![A-Z0-9])HDMU", "HMM"),
            carrier("(?<![A-Z])ZIM(?![A-Z])|(?<![A-Z0-9])ZIMU", "ZIM"));

    private BillParser() {}

    public static Reading parse(List<Line> lines) {
        if (lines == null || lines.isEmpty()) return Reading.NONE;
        String flat = upper(BillText.flat(lines));
        boolean titled = flat.contains("BILL OF LADING") || flat.contains("WAYBILL");

        List<String> containers = containers(flat);
        String carrier = carrier(flat);
        String billNumber = billNumber(lines, flat, containers);
        String vessel = null;
        String voyage = null;

        List<String> together = under(lines, VESSEL_VOYAGE_LABEL, 1);
        if (!together.isEmpty()) {
            String[] split = vesselAndVoyage(together.get(0));
            vessel = split[0];
            voyage = split[1];
        }
        if (vessel == null) {
            List<String> named = under(lines, VESSEL_LABEL, 1);
            if (!named.isEmpty()) {
                String[] split = vesselAndVoyage(named.get(0));
                vessel = split[0];
                voyage = split[1];
            }
        }
        if (voyage == null) {
            for (String candidate : under(lines, VOYAGE_LABEL, 1)) {
                String token = candidate.strip();
                if (VOYAGE_TOKEN.matcher(token).matches() && token.chars().anyMatch(Character::isDigit)) {
                    voyage = token;
                    break;
                }
            }
        }

        String loading = port(under(lines, LOADING_LABEL, 1));
        String discharge = port(under(lines, DISCHARGE_LABEL, 1));

        LocalDate onBoard = firstDate(under(lines, ON_BOARD_LABEL, 3));
        LocalDate issued = issued(lines);
        boolean draft = DRAFT.matcher(flat).find();
        boolean bill = titled && (billNumber != null || !containers.isEmpty()) && (vessel != null || !containers.isEmpty());
        if (!bill) return Reading.NONE;
        return new Reading(true, draft, carrier, billNumber, List.copyOf(containers), vessel, voyage, loading, discharge,
                onBoard, issued);
    }

    /* ------------------------------------------------------------------ values under a label */

    /**
     * The cells under a label: on the lines right below it (same page, at most {@code depth} lines that hold such a
     * cell, within 32 points), the cells that stand in this label's box rather than in a neighbour's. The first place
     * the label occurs with something under it wins (forms repeat their head on every sheet).
     */
    static List<String> under(List<Line> lines, Pattern label, int depth) {
        for (int i = 0; i < lines.size(); i++) {
            Line head = lines.get(i);
            for (Cell labelCell : head.cells()) {
                if (labelCell.text().length() > 70 || !label.matcher(labelText(labelCell.text())).matches()) continue;
                List<String> found = new ArrayList<>();
                for (int j = i + 1; j < lines.size() && found.size() < depth; j++) {
                    Line below = lines.get(j);
                    if (below.page() != head.page() || below.y() - head.y() > 32f) break;
                    if (below.y() - head.y() < 2f) continue; // the right half of a label row that broke in two
                    String mine = null;
                    for (Cell value : below.cells()) {
                        if (owner(head.cells(), value) == labelCell && !isLabel(value.text())) {
                            mine = mine == null ? value.text() : mine + " " + value.text();
                        }
                    }
                    if (mine != null) found.add(mine);
                    else if (!found.isEmpty() || labelRow(below)) break;
                }
                if (!found.isEmpty()) return found;
            }
        }
        return List.of();
    }

    /** The label whose box a value stands in: the one it overlaps most, else the nearest within 100 points. */
    private static Cell owner(List<Cell> labels, Cell value) {
        Cell best = null;
        float bestOverlap = 0f;
        for (Cell label : labels) {
            float overlap = Math.min(label.xEnd(), value.xEnd()) - Math.max(label.x(), value.x());
            if (overlap > bestOverlap) {
                bestOverlap = overlap;
                best = label;
            }
        }
        if (best != null) return best;
        float nearest = 100f;
        float centre = (value.x() + value.xEnd()) / 2f;
        for (Cell label : labels) {
            float distance = Math.abs((label.x() + label.xEnd()) / 2f - centre);
            if (distance < nearest) {
                nearest = distance;
                best = label;
            }
        }
        return best;
    }

    private static boolean labelRow(Line line) {
        for (Cell cell : line.cells()) if (isLabel(cell.text())) return true;
        return false;
    }

    private static boolean isLabel(String text) {
        String value = labelText(text);
        return NUMBERED_LABEL.matcher(text.strip()).matches() || VESSEL_VOYAGE_LABEL.matcher(value).matches()
                || VESSEL_LABEL.matcher(value).matches() || LOADING_LABEL.matcher(value).matches()
                || DISCHARGE_LABEL.matcher(value).matches() || BILL_LABEL.matcher(value).matches()
                || ON_BOARD_LABEL.matcher(value).matches();
    }

    /** "(14) Ocean Vessel/Voy. No." -> "OCEAN VESSEL/VOY. NO."; "PORT OF LOADING*" -> "PORT OF LOADING". */
    private static String labelText(String text) {
        return upper(text).replaceFirst("^\\(?[0-9]{1,2}\\)\\s*", "").replaceAll("[*:]+$", "").replaceAll("\\s+", " ").strip();
    }

    private static Pattern label(String expression) {
        return Pattern.compile("^(?:" + expression + ")$");
    }

    /* ------------------------------------------------------------------ the fields */

    static List<String> containers(String flat) {
        Set<String> found = new LinkedHashSet<>();
        Matcher box = BOX.matcher(flat);
        while (box.find()) {
            String number = box.group(1) + box.group(2) + box.group(3);
            if (validContainer(number)) found.add(number);
        }
        return new ArrayList<>(found);
    }

    /** ISO 6346: owner code, U/J/Z, six digits and a check digit. */
    public static boolean validContainer(String number) {
        if (number == null || !number.matches("[A-Z]{3}[UJZ][0-9]{7}")) return false;
        int sum = 0;
        for (int i = 0; i < 10; i++) {
            char c = number.charAt(i);
            int value;
            if (Character.isDigit(c)) {
                value = c - '0';
            } else {
                value = c - 'A' + 10;
                value += (value - 1) / 10; // 11, 22 and 33 are skipped
            }
            sum += value << i;
        }
        return sum % 11 % 10 == number.charAt(10) - '0';
    }

    private static String carrier(String flat) {
        for (Map.Entry<Pattern, String> entry : CARRIERS) if (entry.getKey().matcher(flat).find()) return entry.getValue();
        return null;
    }

    private static Map.Entry<Pattern, String> carrier(String expression, String name) {
        return Map.entry(Pattern.compile(expression), name);
    }

    /**
     * The bill's number: an Evergreen number anywhere (EGLV + 12 digits, also printed as "EGLV" over the digits),
     * else what stands under "B/L NO." / "BILL OF LADING NUMBER" / "Document No.". Never a container number.
     */
    private static String billNumber(List<Line> lines, String flat, List<String> containers) {
        Matcher evergreen = EVERGREEN_BILL.matcher(flat);
        if (evergreen.find()) return "EGLV" + evergreen.group(1);
        for (Pattern label : List.of(BILL_LABEL, DOCUMENT_LABEL)) {
            List<String> values = under(lines, label, 2);
            if (values.isEmpty()) continue;
            String joined = upper(String.join(" ", values));
            Matcher split = Pattern.compile("(?<![A-Z0-9])EGLV\\s+([0-9]{12})(?![0-9])").matcher(joined);
            if (split.find()) return "EGLV" + split.group(1);
            if (flat.contains("EVERGREEN") || flat.contains("EGLV")) {
                Matcher digits = Pattern.compile("(?<![0-9])([0-9]{12})(?![0-9])").matcher(joined);
                if (digits.find()) return "EGLV" + digits.group(1);
            }
            Matcher token = NUMBER_TOKEN.matcher(joined);
            while (token.find()) {
                String number = token.group();
                if (number.chars().anyMatch(Character::isDigit) && number.chars().anyMatch(Character::isLetter)
                        && !containers.contains(number) && !number.contains("/")) return number;
            }
            token = NUMBER_TOKEN.matcher(joined);
            while (token.find()) {
                String number = token.group();
                if (number.matches("[0-9]{8,20}")) return number;
            }
        }
        return null;
    }

    /** "EVER ACE 1423-020W" -> ship "EVER ACE", voyage "1423-020W"; "CMA CGM RHONE" -> the ship, no voyage. */
    static String[] vesselAndVoyage(String value) {
        String text = upper(value).replaceFirst("^(M\\.?V\\.?|M/V|S\\.?S\\.?)\\s+", "").replaceAll("\\s+", " ").strip();
        text = text.replaceFirst("\\s*/\\s*", " ");
        if (text.isEmpty()) return new String[] {null, null};
        String[] words = text.split(" ");
        String voyage = null;
        int end = words.length;
        if (words.length > 1) {
            String last = words[words.length - 1].replaceFirst("^V\\.?", "");
            if (VOYAGE_TOKEN.matcher(last).matches() && last.chars().anyMatch(Character::isDigit) && last.length() >= 3) {
                voyage = last;
                end = words.length - 1;
            }
        }
        String vessel = String.join(" ", List.of(words).subList(0, end)).strip();
        if (vessel.length() < 3 || vessel.length() > 60 || !vessel.chars().anyMatch(Character::isLetter)
                || vessel.matches(".*\\b(TBA|TBN|TO BE (NAMED|ADVISED|NOMINATED))\\b.*")) return new String[] {null, voyage};
        return new String[] {vessel, voyage};
    }

    /** "NINGBO,CHINA" -> "Ningbo"; "ROTTERDAM, NETHERLANDS" -> "Rotterdam". */
    private static String port(List<String> values) {
        if (values.isEmpty()) return null;
        String text = values.get(0).split(",", 2)[0].replaceAll("\\(.*?\\)", " ").replaceAll("[^\\p{L}\\p{N} .'-]", " ")
                .replaceAll("\\s+", " ").strip();
        /* "NINGBO CHINA", "ROTTERDAM THE NETHERLANDS": the country behind the port is not part of its name. */
        text = COUNTRY_TAIL.matcher(text).replaceFirst("").strip();
        if (text.length() < 3 || text.length() > 40 || !text.chars().anyMatch(Character::isLetter)) return null;
        StringBuilder out = new StringBuilder();
        for (String word : text.toLowerCase(Locale.ROOT).split(" ")) {
            if (word.isEmpty()) continue;
            if (out.length() > 0) out.append(' ');
            out.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return out.toString();
    }

    private static LocalDate issued(List<Line> lines) {
        for (int i = 0; i < lines.size(); i++) {
            Line line = lines.get(i);
            boolean labelled = false;
            for (Cell cell : line.cells()) labelled |= cell.text().length() <= 70 && ISSUE_LABEL.matcher(upper(cell.text())).matches();
            if (!labelled) continue;
            LocalDate same = date(line.text());
            if (same != null) return same;
            for (int j = i + 1; j < lines.size() && j <= i + 2; j++) {
                if (lines.get(j).page() != line.page() || lines.get(j).y() - line.y() > 24f) break;
                LocalDate below = date(lines.get(j).text());
                if (below != null) return below;
            }
        }
        return null;
    }

    private static LocalDate firstDate(List<String> values) {
        for (String value : values) {
            LocalDate found = date(value);
            if (found != null) return found;
        }
        return null;
    }

    /** "OCT.24,2026", "24 OCT 2026", "2026-10-24"; a day written only in figures and slashes is not guessed. */
    static LocalDate date(String value) {
        if (value == null) return null;
        String text = upper(value);
        try {
            Matcher month = MONTH_FIRST.matcher(text);
            if (month.find()) return LocalDate.of(Integer.parseInt(month.group(3)), monthOf(month.group(1)), Integer.parseInt(month.group(2)));
            Matcher day = DAY_FIRST.matcher(text);
            if (day.find()) return LocalDate.of(Integer.parseInt(day.group(3)), monthOf(day.group(2)), Integer.parseInt(day.group(1)));
            Matcher year = YEAR_FIRST.matcher(text);
            if (year.find()) return LocalDate.of(Integer.parseInt(year.group(1)), Integer.parseInt(year.group(2)), Integer.parseInt(year.group(3)));
        } catch (DateTimeException impossible) {
            return null;
        }
        return null;
    }

    private static int monthOf(String name) {
        return MONTHS.indexOf(name.substring(0, 3)) / 4 + 1;
    }

    /** Upper case, with full-width letters and figures as plain ones (NFKC) and every odd space a plain space. */
    private static String upper(String value) {
        if (value == null) return "";
        String plain = java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFKC).toUpperCase(Locale.ROOT);
        StringBuilder out = new StringBuilder(plain.length());
        for (int k = 0; k < plain.length(); k++) {
            char c = plain.charAt(k);
            if (c == 0x200B || c == 0xFEFF) continue;
            out.append(c == 0x00A0 || (c >= 0x2000 && c <= 0x200A) || c == 0x202F || c == 0x3000 ? ' ' : c);
        }
        return out.toString();
    }
}
