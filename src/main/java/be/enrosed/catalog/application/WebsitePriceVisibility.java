package be.enrosed.catalog.application;

import be.enrosed.catalog.adapter.out.persistence.WebsiteQuoteSettingsEntity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityManager;

/** Read side of the website price switch; a missing row means prices are shown. */
@ApplicationScoped
public class WebsitePriceVisibility {
    private final EntityManager entities;

    public WebsitePriceVisibility(EntityManager entities) {
        this.entities = entities;
    }

    public boolean pricesVisible() {
        WebsiteQuoteSettingsEntity settings = entities.find(WebsiteQuoteSettingsEntity.class, 1L);
        return settings == null || settings.pricesVisible;
    }
}
