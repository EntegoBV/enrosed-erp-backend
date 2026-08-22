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
            Map.entry("Rood", "#a91f32"),
            Map.entry("Roze", "#e59bb4"),
            Map.entry("Fuchsia", "#c2187a"),
            Map.entry("Bordeaux", "#6b1a2b"),
            Map.entry("Wit", "#f4f1ec"),
            Map.entry("Ivoor", "#f1e9d6"),
            Map.entry("Champagne", "#e8d6b3"),
            Map.entry("Geel", "#f2c94c"),
            Map.entry("Oranje", "#ef8a2f"),
            Map.entry("Groen", "#3e7d4f"),
            Map.entry("Blauw", "#2f5d9e"),
            Map.entry("Paars", "#6e3c9a"),
            Map.entry("Lila", "#b69ad6"),
            Map.entry("Zwart", "#1a1614"),
            Map.entry("Grijs", "#9a9a9a"),
            Map.entry("Zilver", "#c0c4c9"),
            Map.entry("Goud", "#c9a227"),
            Map.entry("Gemengd", "#d8c3c3"));

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
