package be.enrosed.catalog.application;

import be.enrosed.catalog.adapter.out.persistence.ProductFamilyPhotoEntity;
import be.enrosed.catalog.domain.CatalogChannel;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FamilyPhotoPublicationPolicyTest {
    private final ObjectMapper json = new ObjectMapper();
    private final FamilyPhotoPublicationPolicy policy = new FamilyPhotoPublicationPolicy(
            new FamilyPhotoVariantResolver(), json);

    @Test
    void legacyNullStatePreservesExistingAllChannelVisibility() {
        ProductFamilyPhotoEntity image = readyImage();

        assertEquals(List.of(CatalogChannel.values()), policy.publishedChannels(image));
        assertTrue(policy.isPublic(image, List.of(), CatalogChannel.WEBSITE));
        assertTrue(policy.isPublic(image, List.of(), CatalogChannel.CATALOGUE));
    }

    @Test
    void legacyNullStateWithoutAnExplicitAltKeepsItsInternalVisibility() {
        for (String alts : new String[] {"[]", null, "[{\"language\":\"EN\",\"alt\":\" \"}]", "{not-json"}) {
            ProductFamilyPhotoEntity image = readyImage();
            image.altTextsJson = alts;

            assertEquals(List.of(), policy.publishedChannels(image), String.valueOf(alts));
            assertFalse(policy.isPublicAnywhere(image, List.of()), String.valueOf(alts));
        }

        ProductFamilyPhotoEntity chosen = readyImage();
        chosen.altTextsJson = "[]";
        chosen.publishedChannelsJson = "[\"WEBSITE\"]";
        assertTrue(policy.isPublic(chosen, List.of(), CatalogChannel.WEBSITE),
                "an explicit channel choice uses the generated alt");
    }

    @Test
    void explicitEmptyStateIsInternalEvenWhenTheAssetIsTechnicallyReady() {
        ProductFamilyPhotoEntity image = readyImage();
        image.publishedChannelsJson = "[]";

        assertTrue(policy.publishedChannels(image).isEmpty());
        assertFalse(policy.isPublicAnywhere(image, List.of()));
    }

    @Test
    void replacementNormalizesDuplicatesIntoStableEnumOrder() {
        ProductFamilyPhotoEntity image = readyImage();

        policy.replacePublishedChannels(image, List.of(
                CatalogChannel.CATALOGUE, CatalogChannel.WEBSITE,
                CatalogChannel.CATALOGUE));

        assertEquals("[\"WEBSITE\",\"CATALOGUE\"]", image.publishedChannelsJson);
        assertEquals(List.of(CatalogChannel.WEBSITE, CatalogChannel.CATALOGUE),
                policy.publishedChannels(image));
    }

    @Test
    void malformedStoredSelectionFailsClosed() {
        ProductFamilyPhotoEntity image = readyImage();
        image.publishedChannelsJson = "not-json";

        assertTrue(policy.publishedChannels(image).isEmpty());
        assertFalse(policy.isPublicAnywhere(image, List.of()));
    }

    @Test
    void explicitAltTextIsNoLongerRequiredButUnreadableAltsStillFailClosed() {
        ProductFamilyPhotoEntity image = readyImage();
        image.altTextsJson = "[]";
        assertTrue(policy.isEligible(image, List.of()), "the projection generates the alt");
        image.altTextsJson = null;
        assertTrue(policy.isEligible(image, List.of()));

        image.altTextsJson = "{not-json";
        assertFalse(policy.isEligible(image, List.of()));
        image.altTextsJson = "{\"language\":\"EN\"}";
        assertFalse(policy.isEligible(image, List.of()));

        image.altTextsJson = "[]";
        image.largeWidthPx = null;
        assertFalse(policy.isEligible(image, List.of()), "dimensions stay required");
    }

    private static ProductFamilyPhotoEntity readyImage() {
        ProductFamilyPhotoEntity image = new ProductFamilyPhotoEntity();
        image.sourceKey = "ready";
        image.smallStorageKey = "small";
        image.largeStorageKey = "large";
        image.smallWidthPx = 320;
        image.smallHeightPx = 320;
        image.largeWidthPx = 1200;
        image.largeHeightPx = 1200;
        image.altTextsJson = "[{\"language\":\"EN\",\"alt\":\"Ready rose\"}]";
        return image;
    }
}
