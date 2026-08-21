package be.enrosed.catalog.application;

import be.enrosed.catalog.adapter.out.persistence.ProductEntity;
import be.enrosed.catalog.adapter.out.persistence.ProductFamilyEntity;
import be.enrosed.catalog.adapter.out.persistence.ProductFamilyPhotoEntity;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FamilyPhotoVariantResolverTest {
    private final FamilyPhotoVariantResolver resolver = new FamilyPhotoVariantResolver();

    @Test
    void stableProductIdWinsWhenLegacyColourTextIsStale() {
        ProductEntity redSmall = variant(1L, "red-small", "Red", "Small");
        ProductEntity blue = variant(2L, "blue", "Blue", null);
        ProductFamilyPhotoEntity image = new ProductFamilyPhotoEntity();
        image.variantProduct = blue;
        image.variantExternalId = redSmall.canonicalVariantKey;
        image.variantColor = "Red";

        assertEquals(blue, resolver.resolve(image, List.of(redSmall, blue)));
    }

    @Test
    void backfillsCanonicalKeyButNeverGuessesAnAmbiguousColour() {
        ProductEntity redSmall = variant(1L, "red-small", "Red", "Small");
        ProductEntity redLarge = variant(2L, "red-large", "Red", "Large");
        ProductFamilyPhotoEntity keyed = new ProductFamilyPhotoEntity();
        keyed.variantExternalId = "red-large";
        keyed.variantColor = "outdated label";
        ProductFamilyPhotoEntity ambiguous = new ProductFamilyPhotoEntity();
        ambiguous.variantColor = " red ";
        ProductFamilyEntity family = new ProductFamilyEntity();
        family.photos.add(keyed);
        family.photos.add(ambiguous);

        assertTrue(resolver.backfill(family, List.of(redSmall, redLarge)));

        assertEquals(redLarge, keyed.variantProduct);
        assertNull(ambiguous.variantProduct);
        assertFalse(resolver.isFamilyWide(ambiguous));
    }

    @Test
    void aUniqueLegacyColourBackfillsAndAnUnscopedImageStaysFamilyWide() {
        ProductEntity blue = variant(1L, "blue", "Light Blue", null);
        ProductFamilyPhotoEntity legacyColour = new ProductFamilyPhotoEntity();
        legacyColour.variantColor = "  LIGHT   BLUE ";
        ProductFamilyPhotoEntity familyWide = new ProductFamilyPhotoEntity();
        ProductFamilyEntity family = new ProductFamilyEntity();
        family.photos.add(legacyColour);
        family.photos.add(familyWide);

        assertTrue(resolver.backfill(family, List.of(blue)));

        assertEquals(blue, legacyColour.variantProduct);
        assertTrue(resolver.isFamilyWide(familyWide));
        assertEquals(0, resolver.rank(legacyColour, blue, List.of(blue)));
        assertEquals(1, resolver.rank(familyWide, blue, List.of(blue)));
    }

    private static ProductEntity variant(Long id, String key, String colour, String size) {
        ProductEntity product = new ProductEntity();
        product.id = id;
        product.canonicalVariantKey = key;
        product.colour = colour;
        product.variantSize = size;
        return product;
    }
}
