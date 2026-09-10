package be.enrosed.catalog.application;

import be.enrosed.catalog.adapter.in.rest.CanonicalCatalogManifest;
import be.enrosed.catalog.adapter.in.rest.CanonicalCatalogManifest.ImportDescriptor;
import be.enrosed.catalog.adapter.in.rest.CanonicalCatalogManifest.ValidationSummary;
import be.enrosed.catalog.adapter.in.rest.CatalogMigrationApplyRequest;
import be.enrosed.sales.adapter.out.persistence.SalesEntities.QuoteEventEntity;
import be.enrosed.sales.adapter.out.persistence.SalesEntities.SalesOrderEntity;
import be.enrosed.sales.domain.QuoteEvent;
import be.enrosed.shared.BusinessRuleException;
import be.enrosed.sourcing.adapter.out.persistence.SourcingEntities.PurchaseOrderEntity;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class CatalogMigrationTrashProtectionTest {
    @Inject EntityManager entities;
    @Inject CatalogMigrationService migration;

    @Test @TestTransaction
    void fullResetRefusesToEraseRetainedSalesHistoryEvenWithResetConfirmation() {
        var order = new SalesOrderEntity(); entities.persist(order);
        var event = new QuoteEventEntity(); event.salesOrderId = order.id;
        event.type = QuoteEvent.Type.VERSTUURD; event.at = Instant.now();
        event.summary = "Retained history"; entities.persist(event); entities.flush();
        tombstone("sales_order", order.id);
        var manifest = manifest();
        var preflight = migration.preflight(manifest);
        assertFalse(preflight.valid());
        assertTrue(preflight.problems().stream().anyMatch(problem -> problem.contains("prullenbak")),
                preflight.problems().toString());
        assertEquals(1, preflight.problems().size(), "fixture is valid apart from retained history");
        var exception = assertThrows(BusinessRuleException.class, () -> migration.apply(
                new CatalogMigrationApplyRequest(manifest, true, true, true,
                        CatalogMigrationService.FULL_RESET_CONFIRMATION), manifest.importDescriptor().payloadSha256()));
        assertTrue(exception.getMessage().contains("prullenbak"));
        assertEquals(1, rowCount("sales_order", order.id));
        assertNotNull(entities.find(QuoteEventEntity.class, event.id));
    }

    @Test @TestTransaction
    void productReplacementCannotCleanUpARetainedPurchaseGraph() {
        var purchase = new PurchaseOrderEntity(); entities.persist(purchase); entities.flush();
        tombstone("purchase_order", purchase.id);
        var exception = assertThrows(BusinessRuleException.class, () -> migration.apply(
                new CatalogMigrationApplyRequest(manifest(), true, true, false, null)));
        assertTrue(exception.getMessage().contains("prullenbak"));
        assertEquals(1, rowCount("purchase_order", purchase.id));
    }

    private CanonicalCatalogManifest manifest() {
        var summary = new ValidationSummary(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0, Map.of());
        var payload = new CanonicalCatalogManifest("1.0", null, List.of(), List.of(), summary);
        String hash = migration.computePayloadSha256(payload);
        var descriptor = new ImportDescriptor("enrosed-catalog-" + hash.substring(0, 16),
                Instant.parse("2026-09-10T00:00:00Z"), "2026-09-10.1",
                "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", hash, List.of(), List.of());
        return new CanonicalCatalogManifest("1.0", descriptor, List.of(), List.of(), summary);
    }

    private void tombstone(String table, long id) {
        entities.createNativeQuery("update " + table + " set deleted_at=current_timestamp where id=:id")
                .setParameter("id", id).executeUpdate();
    }

    private long rowCount(String table, long id) {
        return ((Number) entities.createNativeQuery("select count(*) from " + table + " where id=:id")
                .setParameter("id", id).getSingleResult()).longValue();
    }
}
