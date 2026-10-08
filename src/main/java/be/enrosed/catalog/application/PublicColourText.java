package be.enrosed.catalog.application;

import be.enrosed.catalog.adapter.out.persistence.ProductEntity;
import be.enrosed.catalog.adapter.out.persistence.ProductTextEntity;
import be.enrosed.shared.ColourNames;
import be.enrosed.shared.Language;
import be.enrosed.shared.LanguageFallback;

/**
 * The one rule for the colour text of a public variant and whether it is exact in a language.
 *
 * A colour text of the product's own in the requested language wins. Without one, a standard
 * pick-list colour is translated by the shared colour dictionary; that dictionary is reviewed
 * and complete in every language, so the word is exact in the requested language, like a unit
 * name (in Dutch too it is the dictionary's spelling, {@link ColourNames#standardName}).
 * Anything else - "Vintage roze", a code like "Panda" - is not translated: it borrows the
 * product's text from the fallback languages or prints the stored colour, and its source is
 * never the requested language, so strict builds and the publication work queue keep
 * reporting it.
 *
 * The public family catalogue, its generated alt texts, the completeness check staff see and
 * the website revision all read the colour here, so they cannot disagree.
 */
public final class PublicColourText {
    private PublicColourText() {}

    public static LanguageFallback.Resolved<String> resolve(
            ProductEntity product, Language requested) {
        String own = own(product, requested);
        if (own != null) return new LanguageFallback.Resolved<>(own, requested);
        String standard = fromDictionary(product, requested);
        if (standard != null) return new LanguageFallback.Resolved<>(standard, requested);
        return LanguageFallback.text(product.texts, requested,
                text -> text.language, text -> text.colour, product.colour);
    }

    /** The colour when it is exact in the requested language, else null. */
    public static String exact(ProductEntity product, Language requested) {
        LanguageFallback.Resolved<String> colour = resolve(product, requested);
        return blank(colour.value()) || colour.sourceLanguage() != requested
                ? null : colour.value();
    }

    /** The dictionary word when it, and no text of the product's own, supplies the colour. */
    public static String fromDictionary(ProductEntity product, Language requested) {
        return own(product, requested) != null
                ? null : ColourNames.standardName(product.colour, requested);
    }

    private static String own(ProductEntity product, Language requested) {
        for (ProductTextEntity text : product.texts) {
            if (text.language == requested && !blank(text.colour)) return text.colour;
        }
        return null;
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }
}
