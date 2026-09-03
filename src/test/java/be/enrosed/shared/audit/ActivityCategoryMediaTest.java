package be.enrosed.shared.audit;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ActivityCategoryMediaTest {
    @Test
    void mediaAssetsBelongToCatalogueLogbookCategory() {
        assertEquals(ActivityCategory.CATALOGUE,
                ActivityCategory.forEntityType("MEDIA_ASSET"));
        assertTrue(ActivityCategory.knownEntityTypes().contains("MEDIA_ASSET"));
    }
}
