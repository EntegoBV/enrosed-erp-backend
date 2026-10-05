package be.enrosed.sales.application;

import be.enrosed.catalog.adapter.out.persistence.WebsiteQuoteSettingsEntity;
import be.enrosed.catalog.application.WebsitePriceVisibility;
import be.enrosed.catalog.application.WebsiteRebuildService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WebsiteQuoteSettingsServiceTest {
    private final EntityManager entities = mock(EntityManager.class);
    private final WebsiteRebuildService websiteRebuild = mock(WebsiteRebuildService.class);
    private final WebsiteQuoteSettingsService service = new WebsiteQuoteSettingsService(
            entities, new WebsitePriceVisibility(entities), websiteRebuild);

    @Test
    void aRealChangeQueuesOneWebsiteRebuildAfterTheValueIsFlushed() {
        WebsiteQuoteSettingsEntity stored = new WebsiteQuoteSettingsEntity();
        when(entities.find(WebsiteQuoteSettingsEntity.class, 1L)).thenReturn(stored);

        assertFalse(service.update(false));
        assertFalse(service.pricesVisible());
        InOrder order = inOrder(entities, websiteRebuild);
        order.verify(entities).flush();
        order.verify(websiteRebuild).queue();

        assertTrue(service.update(true));
        verify(websiteRebuild, times(2)).queue();
    }

    @Test
    void savingTheStoredValueAgainQueuesNothing() {
        WebsiteQuoteSettingsEntity stored = new WebsiteQuoteSettingsEntity();
        when(entities.find(WebsiteQuoteSettingsEntity.class, 1L)).thenReturn(stored);

        assertTrue(service.update(true));
        stored.pricesVisible = false;
        assertFalse(service.update(false));

        verify(websiteRebuild, never()).queue();
    }

    @Test
    void aMissingRowCountsAsVisible() {
        when(entities.find(WebsiteQuoteSettingsEntity.class, 1L)).thenReturn(null);

        assertTrue(service.update(true));
        verify(websiteRebuild, never()).queue();

        assertFalse(service.update(false));
        verify(entities, times(2)).persist(any(WebsiteQuoteSettingsEntity.class));
        verify(websiteRebuild).queue();
    }
}
