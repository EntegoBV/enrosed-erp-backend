package be.enrosed.catalog.application;

import be.enrosed.catalog.adapter.out.persistence.CatalogDaos;
import be.enrosed.catalog.adapter.out.persistence.ProductEntity;
import be.enrosed.shared.ColourSwatches;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

/**
 * Gives existing products with a standard colour but no swatch their
 * default at startup. Idempotent and cheap: rows with a swatch are left
 * alone, so a seller's hand-picked sample never changes.
 */
@ApplicationScoped
public class ColourSwatchBackfill {

    private static final Logger LOG = Logger.getLogger(ColourSwatchBackfill.class);

    private final CatalogDaos.Products products;

    public ColourSwatchBackfill(CatalogDaos.Products products) {
        this.products = products;
    }

    @Transactional
    void onStart(@Observes StartupEvent event) {
        int filled = 0;
        int normalized = 0;
        for (ProductEntity product : products.listAll()) {
            if (product.colourHex == null || product.colourHex.isBlank()) {
                String swatch = ColourSwatches.defaultFor(product.colour);
                if (swatch == null) continue;
                product.colourHex = swatch;
                filled++;
            } else if (!product.colourHex.equals(product.colourHex.toUpperCase())) {
                /* The validator demands #RRGGBB in capitals; older rows (and
                   a first version of this backfill) stored lowercase. */
                product.colourHex = product.colourHex.toUpperCase();
                normalized++;
            }
        }
        if (filled > 0 || normalized > 0) {
            LOG.infof("Colour swatches: %d default(s) set, %d normalised to capitals", filled, normalized);
        }
    }
}
