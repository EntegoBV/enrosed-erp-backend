package be.enrosed.shared;

import java.util.Map;

/**
 * Default swatch per standard colour name.
 *
 * A product sold as "Rood" should never trip "swatch missing" on the
 * website: the name already says what the dot looks like. The seller can
 * still pick an exact sample on the product; this only fills the blank.
 * Keys are the Dutch names of the colour pick-list.
 */
public final class ColourSwatches {

    private ColourSwatches() {}

    private static final Map<String, String> DEFAULTS = Map.ofEntries(
            Map.entry("Rood", "#A91F32"),
            Map.entry("Roze", "#E59BB4"),
            Map.entry("Fuchsia", "#C2187A"),
            Map.entry("Bordeaux", "#6B1A2B"),
            Map.entry("Wit", "#F4F1EC"),
            Map.entry("Ivoor", "#F1E9D6"),
            Map.entry("Champagne", "#E8D6B3"),
            Map.entry("Geel", "#F2C94C"),
            Map.entry("Oranje", "#EF8A2F"),
            Map.entry("Groen", "#3E7D4F"),
            Map.entry("Blauw", "#2F5D9E"),
            Map.entry("Paars", "#6E3C9A"),
            Map.entry("Lila", "#B69AD6"),
            Map.entry("Zwart", "#1A1614"),
            Map.entry("Grijs", "#9A9A9A"),
            Map.entry("Zilver", "#C0C4C9"),
            Map.entry("Goud", "#C9A227"),
            Map.entry("Gemengd", "#D8C3C3"));

    /** The default swatch for a standard colour name, or null for anything else. */
    public static String defaultFor(String colour) {
        if (colour == null) return null;
        return DEFAULTS.get(colour.trim());
    }

    /** The given swatch when present, otherwise the colour's default. */
    public static String orDefault(String colourHex, String colour) {
        return colourHex == null || colourHex.isBlank() ? defaultFor(colour) : colourHex;
    }
}
