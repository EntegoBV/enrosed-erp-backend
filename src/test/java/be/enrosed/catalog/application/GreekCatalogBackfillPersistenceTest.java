package be.enrosed.catalog.application;

import be.enrosed.catalog.adapter.out.persistence.*;
import be.enrosed.shared.Language;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class GreekCatalogBackfillPersistenceTest {
    @Inject CatalogContentBackfillService backfill;
    @Inject EntityManager entities;
    @Inject ObjectMapper json;

    @Test
    @TestTransaction
    void greekCoversLaterVariantsAndPhotosWithoutRewritingExistingContentOrGoods() throws Exception {
        ProductFamilyEntity family = family("rose-diamonds-within-display");
        family.seoTitle = "Editor’s SEO title";
        ProductFamilyTextEntity authored = text(family, Language.EN,
                "Editor’s diamond rose", "Editor’s SEO title");
        ProductEntity product = new ProductEntity();
        product.sku = "EL-NEW-VARIANT";
        product.name = "Internal operational name";
        product.publicName = "Public custom base";
        product.familyId = family.id;
        product.canonicalVariantKey = null; // Ordinary newly linked products do not need an import key.
        product.colour = "Red";
        product.variantSize = null;
        product.colourHex = "#ab0000";
        product.landedCostEur = new BigDecimal("3.2500");
        ProductTextEntity source = new ProductTextEntity();
        source.product = product;
        source.language = Language.EN;
        source.publicName = "Blue";
        source.colour = "Blue"; // The public colour was deliberately corrected after the import.
        source.variantSize = "4.5*4.5cm"; // Existing public measurement, no operational size value.
        product.texts.add(source);
        entities.persist(product);
        ProductFamilyPhotoEntity image = new ProductFamilyPhotoEntity();
        image.family = family;
        image.sourceKey = "admin-upload-after-original-import";
        image.smallStorageKey = "keep-small-photo";
        image.largeStorageKey = "keep-large-photo";
        image.variantProduct = product;
        image.altTextsJson = "[{\"language\":\"EN\",\"alt\":\"Editor’s actual photo description\"}]";
        family.photos.add(image);
        entities.flush();
        long productId = product.id;
        long familyId = family.id;
        backfill.apply();
        entities.flush();
        entities.clear();
        ProductFamilyEntity stored = entities.find(ProductFamilyEntity.class, familyId);
        assertEquals("Editor’s SEO title", stored.texts.stream().filter(t -> t.language == Language.EN).findFirst().orElseThrow().seoTitle);
        ProductFamilyTextEntity greek = stored.texts.stream().filter(t -> t.language == Language.EL).findFirst().orElseThrow();
        assertTrue(greek.name.contains("Τριαντάφυλλο"));
        assertTrue(greek.seoTitle.contains("Χονδρική"));
        assertTrue(greek.description.contains("τριαντάφυλλο"));
        var alts = json.readTree(stored.photos.getFirst().altTextsJson);
        assertEquals("Editor’s actual photo description", alts.findValues("alt").stream().filter(v -> v.asText().startsWith("Editor")).findFirst().orElseThrow().asText());
        assertTrue(alts.toString().contains("Μπλε"));
        assertEquals("keep-large-photo", stored.photos.getFirst().largeStorageKey);
        ProductEntity after = entities.find(ProductEntity.class, productId);
        assertEquals("Internal operational name", after.name);
        assertEquals("Public custom base", after.publicName);
        assertEquals(0, new BigDecimal("3.25").compareTo(after.landedCostEur));
        assertEquals("#ab0000", after.colourHex);
        assertEquals("Μπλε", after.texts.stream().filter(t -> t.language == Language.EL).findFirst().orElseThrow().colour);
        assertEquals("4.5*4.5cm", after.texts.stream().filter(t -> t.language == Language.EL).findFirst().orElseThrow().variantSize);
        assertEquals(2, after.texts.size(), "existing variants get only the newly introduced locale");
        backfill.apply();
        entities.flush();
        assertEquals(2, after.texts.size(), "reapplying never duplicates a locale");
    }

    @Test
    @TestTransaction
    void knownDutchImportedSeoIsCorrectedUsingCurrentLocalName() {
        ProductFamilyEntity family = family("preserved-single-rose-in-display");
        family.seoTitle = "12 Steelrozen met display | Enrosed Wholesale";
        text(family, Language.EN, "12 Preserved Stem Roses with Display", family.seoTitle);
        text(family, Language.DE, "Rosen nach Kundenwunsch", "Eigener Titel für den Fachhandel");
        entities.flush();
        backfill.apply();
        entities.flush();
        assertEquals("12 Preserved Stem Roses with Display | Enrosed Wholesale",
                family.texts.stream().filter(t -> t.language == Language.EN).findFirst().orElseThrow().seoTitle);
        assertEquals("Eigener Titel für den Fachhandel",
                family.texts.stream().filter(t -> t.language == Language.DE).findFirst().orElseThrow().seoTitle);
    }

    private ProductFamilyEntity family(String key) {
        ProductFamilyEntity value = new ProductFamilyEntity();
        value.familyKey = key;
        value.name = "Oorspronkelijke Nederlandse familienaam";
        value.highlightsJson = "[]";
        value.tagsJson = "[]";
        value.createdAt = value.updatedAt = Instant.now();
        entities.persist(value);
        entities.flush();
        return value;
    }

    private ProductFamilyTextEntity text(ProductFamilyEntity family, Language language, String name, String seo) {
        ProductFamilyTextEntity value = new ProductFamilyTextEntity();
        value.family = family;
        value.language = language;
        value.name = name;
        value.seoTitle = seo;
        value.summary = "Editor’s existing summary";
        value.description = "Editor’s existing description";
        value.highlightsJson = "[\"Existing highlight\"]";
        family.texts.add(value);
        return value;
    }
}
