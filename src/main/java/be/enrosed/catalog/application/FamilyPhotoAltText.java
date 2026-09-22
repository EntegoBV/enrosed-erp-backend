package be.enrosed.catalog.application;

import be.enrosed.catalog.adapter.in.rest.ProductFamilyDto;
import be.enrosed.catalog.adapter.out.persistence.ProductEntity;
import be.enrosed.catalog.adapter.out.persistence.ProductFamilyEntity;
import be.enrosed.catalog.adapter.out.persistence.ProductFamilyPhotoEntity;
import be.enrosed.catalog.adapter.out.persistence.ProductFamilyTextEntity;
import be.enrosed.catalog.adapter.out.persistence.ProductTextEntity;
import be.enrosed.shared.Language;
import be.enrosed.shared.LanguageFallback;

import java.util.List;

/**
 * Public alt text of a family gallery image.
 *
 * An explicit alt in the requested language wins. Otherwise the alt is generated from the
 * public family name and, for an image bound to a coloured variant, that colour - both in the
 * requested language. A published family already needs both texts in every language, so the
 * generated alt keeps strict website builds exact without nine hand-written alts per photo.
 */
public final class FamilyPhotoAltText {
    static final String COLOUR_SEPARATOR = " — ";

    private FamilyPhotoAltText() {}

    public static LanguageFallback.Resolved<String> resolve(
            ProductFamilyEntity family, ProductFamilyPhotoEntity image,
            List<ProductEntity> familyMembers, List<ProductFamilyDto.AltTextDto> explicit,
            Language requested) {
        List<ProductFamilyDto.AltTextDto> alts = explicit == null ? List.of() : explicit;
        String exact = alts.stream().filter(alt -> alt != null && alt.language() == requested)
                .map(ProductFamilyDto.AltTextDto::alt).filter(value -> !blank(value))
                .findFirst().orElse(null);
        if (exact != null) return new LanguageFallback.Resolved<>(exact, requested);

        ProductEntity variant = FamilyPhotoVariantResolver.resolvePhoto(image, familyMembers);
        String familyName = exactFamilyName(family, requested);
        if (familyName != null) {
            /* Only an exact colour may join an exact name; a borrowed colour would mislabel it. */
            String colour = exactColour(variant, requested);
            return new LanguageFallback.Resolved<>(
                    colour == null ? familyName : familyName + COLOUR_SEPARATOR + colour, requested);
        }

        LanguageFallback.Resolved<String> fallbackAlt = LanguageFallback.text(
                alts.stream().filter(java.util.Objects::nonNull).toList(), requested,
                ProductFamilyDto.AltTextDto::language, ProductFamilyDto.AltTextDto::alt, null);
        if (!blank(fallbackAlt.value())) return fallbackAlt;

        LanguageFallback.Resolved<String> name = family == null
                ? new LanguageFallback.Resolved<>(null, null)
                : LanguageFallback.text(family.texts, requested, text -> text.language,
                        text -> text.name, family.name);
        if (blank(name.value())) return new LanguageFallback.Resolved<>("", null);
        LanguageFallback.Resolved<String> colour = variant == null
                ? new LanguageFallback.Resolved<>(null, null)
                : LanguageFallback.text(variant.texts, requested, text -> text.language,
                        text -> text.colour, variant.colour);
        return new LanguageFallback.Resolved<>(blank(colour.value())
                ? name.value() : name.value() + COLOUR_SEPARATOR + colour.value(),
                name.sourceLanguage());
    }

    private static String exactFamilyName(ProductFamilyEntity family, Language requested) {
        if (family == null) return null;
        return family.texts.stream().filter(text -> text.language == requested)
                .map((ProductFamilyTextEntity text) -> text.name).filter(value -> !blank(value))
                .map(String::strip).findFirst().orElse(null);
    }

    private static String exactColour(ProductEntity variant, Language requested) {
        if (variant == null) return null;
        return variant.texts.stream().filter(text -> text.language == requested)
                .map((ProductTextEntity text) -> text.colour).filter(value -> !blank(value))
                .map(String::strip).findFirst().orElse(null);
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }
}
