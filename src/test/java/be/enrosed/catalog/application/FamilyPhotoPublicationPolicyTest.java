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
