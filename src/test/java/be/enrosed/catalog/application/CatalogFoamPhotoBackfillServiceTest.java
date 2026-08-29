package be.enrosed.catalog.application;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class CatalogFoamPhotoBackfillServiceTest {

    @Test
    void canonicalizesTheSupportedFoamColoursAcrossLanguages() {
        assertEquals("foam-bear-25-red",
                CatalogFoamPhotoBackfillService.canonicalVariantKey("foam-bear-25", "Rood"));
        assertEquals("foam-bear-25-pink",
                CatalogFoamPhotoBackfillService.canonicalVariantKey("foam-bear-25", "Pink"));
        assertEquals("foam-bear-25-mixed",
                CatalogFoamPhotoBackfillService.canonicalVariantKey("foam-bear-25", "Gemengd"));
        assertNull(CatalogFoamPhotoBackfillService.canonicalVariantKey(
                "foam-bear-25", "Onbekend"));
    }

    @Test
    void exposesExactlyTheFiveStableV7CataloguePhotoKeys() {
        assertEquals(List.of(
                "foam-half-heart-25:catalog-primary-red",
                "foam-half-heart-40:catalog-primary-red",
                "foam-bear-25:catalog-primary-red",
                "foam-heart-15:catalog-primary-red",
                "foam-bear-with-heart-25:catalog-primary-red"),
                CatalogFoamPhotoBackfillService.targetImageKeys());
    }
}
