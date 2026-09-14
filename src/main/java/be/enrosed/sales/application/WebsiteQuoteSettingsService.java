package be.enrosed.sales.application;

import be.enrosed.sales.adapter.out.persistence.WebsiteQuoteSettingsEntity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

@ApplicationScoped
public class WebsiteQuoteSettingsService {
    private final EntityManager entities;

    public WebsiteQuoteSettingsService(EntityManager entities) {
        this.entities = entities;
    }

    public boolean pricesVisible() {
        WebsiteQuoteSettingsEntity settings = entities.find(WebsiteQuoteSettingsEntity.class, 1L);
        return settings == null || settings.pricesVisible;
    }

    @Transactional
    public boolean update(boolean pricesVisible) {
        WebsiteQuoteSettingsEntity settings = entities.find(WebsiteQuoteSettingsEntity.class, 1L);
        if (settings == null) {
            settings = new WebsiteQuoteSettingsEntity();
            entities.persist(settings);
        }
        settings.pricesVisible = pricesVisible;
        entities.flush();
        return settings.pricesVisible;
    }
}
