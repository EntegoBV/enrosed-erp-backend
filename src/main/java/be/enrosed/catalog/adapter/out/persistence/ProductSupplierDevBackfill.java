package be.enrosed.catalog.adapter.out.persistence;

import be.enrosed.catalog.application.ProductSupplierRules;
import be.enrosed.catalog.application.port.out.ProductRepository;
import be.enrosed.catalog.application.port.out.ProductSupplierRepository;
import be.enrosed.catalog.domain.Product;
import io.quarkus.arc.profile.IfBuildProfile;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.List;

/**
 * The developer's H2 file gets the product_supplier table from schema update, but not the rows the PostgreSQL
 * migration ({@code 2026-10-25/product-supplier-postgresql.sql}) fills. In dev only, after {@code DemoDataLoader}:
 * every product with a supplier gets its own row as Voorkeur (the migration's steps 1a-1c), and purchase lines
 * without a row link get the row of their product at their container's supplier (step 3; trashed containers are
 * skipped). Steps 1d, 2 and 4 are not mirrored here. Production and test never run this class.
 */
@ApplicationScoped
@IfBuildProfile("dev")
public class ProductSupplierDevBackfill {

    private static final Logger LOG = Logger.getLogger(ProductSupplierDevBackfill.class);

    @Inject
    EntityManager entities;

    @Inject
    ProductRepository products;

    @Inject
    ProductSupplierRepository rows;

    @Transactional
    void onStart(@Observes @Priority(5000) StartupEvent event) {
        int rowsMade = 0;
        try {
            Instant now = Instant.now();
            for (Product product : products.findAll()) {
                if (product.supplierId() == null) continue;
                try {
                    boolean had = rows.find(product.id(), product.supplierId())
                            .map(row -> row.preferred()).orElse(false);
                    ProductSupplierRules.ensureOwnRow(rows, product, now);
                    if (!had) rowsMade++;
                } catch (RuntimeException failure) {
                    LOG.debugf("supplier row of product %d left as is: %s", product.id(), failure.getMessage());
                }
            }
        } catch (RuntimeException failure) {
            LOG.debugf("product supplier rows not filled: %s", failure.getMessage());
        }
        int linesLinked = 0;
        try {
            List<Object[]> lines = entities.createQuery("select l.id, l.productId, o.supplierId "
                            + "from PurchaseOrderLineEntity l join l.order o where l.productSupplierId is null",
                            Object[].class)
                    .getResultList();
            for (Object[] line : lines) {
                if (line[1] == null || line[2] == null) continue;
                long lineId = ((Number) line[0]).longValue();
                long productId = ((Number) line[1]).longValue();
                long supplierId = ((Number) line[2]).longValue();
                var row = rows.find(productId, supplierId);
                if (row.isEmpty()) continue;
                linesLinked += entities.createQuery(
                                "update PurchaseOrderLineEntity set productSupplierId = :row where id = :id")
                        .setParameter("row", row.get().id())
                        .setParameter("id", lineId)
                        .executeUpdate();
            }
        } catch (RuntimeException failure) {
            LOG.debugf("purchase line supplier links not filled: %s", failure.getMessage());
        }
        if (rowsMade > 0 || linesLinked > 0) {
            LOG.infof("Leveranciersrijen aangevuld: %d product(en), %d inkoopregel(s) gekoppeld", rowsMade, linesLinked);
        }
    }
}
