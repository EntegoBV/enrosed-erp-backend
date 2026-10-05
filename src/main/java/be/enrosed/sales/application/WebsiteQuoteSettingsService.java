package be.enrosed.sales.application;

import be.enrosed.catalog.adapter.out.persistence.WebsiteQuoteSettingsEntity;
import be.enrosed.catalog.application.WebsitePriceVisibility;
import be.enrosed.catalog.application.WebsiteRebuildService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

@ApplicationScoped
public class WebsiteQuoteSettingsService {
    private final EntityManager entities;
    private final WebsitePriceVisibility visibility;
    private final WebsiteRebuildService websiteRebuild;

    public WebsiteQuoteSettingsService(EntityManager entities, WebsitePriceVisibility visibility,
                                       WebsiteRebuildService websiteRebuild) {
        this.entities = entities;
        this.visibility = visibility;
        this.websiteRebuild = websiteRebuild;
    }

    public boolean pricesVisible() {
        return visibility.pricesVisible();
    }

    @Transactional
    public boolean update(boolean pricesVisible) {
        WebsiteQuoteSettingsEntity settings = entities.find(WebsiteQuoteSettingsEntity.class, 1L);
        boolean changed = (settings == null || settings.pricesVisible) != pricesVisible;
        if (settings == null) {
            settings = new WebsiteQuoteSettingsEntity();
            entities.persist(settings);
        }
        settings.pricesVisible = pricesVisible;
        entities.flush();
        /* The static product pages carry the prices too, so a real change rebuilds the website. */
        if (changed) websiteRebuild.queue();
        return settings.pricesVisible;
    }
}
