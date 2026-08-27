package be.enrosed.catalog.adapter.in.rest;

import be.enrosed.catalog.adapter.out.persistence.ProductEntity;
import be.enrosed.catalog.adapter.out.persistence.ProductFamilyEntity;
import be.enrosed.catalog.adapter.out.persistence.CatalogImportBatchEntity;
import be.enrosed.catalog.adapter.out.persistence.CatalogImportConflictEntity;
import be.enrosed.catalog.domain.PublicationState;
import be.enrosed.shared.BusinessRuleException;
import be.enrosed.shared.audit.ActivityLogEntity;
import be.enrosed.shared.audit.ActivityLogService;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@io.quarkus.test.security.TestSecurity(user = "emre",
        roles = be.enrosed.shared.security.AdminIdentityProvider.ADMIN_ROLE)
class DraftFamilyIdentityFinalizationTest {
    @Inject ProductFamilyResource resource;
    @Inject EntityManager entityManager;

    @Test
    @TestTransaction
    void finalizesTheExactDraftMembershipAndRetryIsIdempotent() {
        ActivityLogEntity.deleteAll();
        Fixture fixture = fixture("success", "TEST-ID-SUCCESS-RED", "TEST-ID-SUCCESS-PINK");
        ProductFamilyResource.FinalizeDraftIdentityRequest request = request(
                fixture, "semantic-display-success", "semantic-display-success",
                "semantic-display-success-red", "semantic-display-success-pink");

        ProductFamilyDto first = resource.finalizeDraftIdentity(fixture.family.id, request);
        ProductFamilyDto retry = resource.finalizeDraftIdentity(fixture.family.id, request);

        assertEquals("semantic-display-success", first.familyKey());
        assertEquals("semantic-display-success", first.publicHandle());
        assertEquals(first.familyKey(), retry.familyKey());
        assertEquals(first.publicHandle(), retry.publicHandle());
        assertEquals("semantic-display-success-red", fixture.members.get(0).canonicalVariantKey);
        assertEquals("semantic-display-success-pink", fixture.members.get(1).canonicalVariantKey);
        assertEquals("semantic-display-success", fixture.members.get(0).familyKey);
        assertEquals("semantic-display-success", fixture.members.get(1).familyKey);
        List<ActivityLogEntity> activities = ActivityLogEntity.list(
                "entityType = ?1 and entityId = ?2",
                ActivityLogService.ENTITY_PRODUCT_FAMILY, String.valueOf(fixture.family.id));
        assertEquals(1, activities.size());
        assertEquals(ActivityLogService.ACTION_IDENTITY_FINALIZED,
                activities.getFirst().action);
        assertEquals("Conceptidentiteit definitief gemaakt voor 2 variant(en)",
                activities.getFirst().summary);
    }

    @Test
    @TestTransaction
    void rejectsAStaleExpectedFamilyKeyWithoutChangingAnything() {
        Fixture fixture = fixture("stale", "TEST-ID-STALE-RED");
        ProductFamilyResource.FinalizeDraftIdentityRequest request = new ProductFamilyResource
                .FinalizeDraftIdentityRequest(
                        "model-no-longer-current", "semantic-display-stale",
                        "semantic-display-stale",
                        List.of(new ProductFamilyResource.VariantIdentityRequest(
                                fixture.members.getFirst().sku,
                                null,
                                "semantic-display-stale-red")));

        BusinessRuleException conflict = assertThrows(BusinessRuleException.class,
                () -> resource.finalizeDraftIdentity(fixture.family.id, request));

        assertTrue(conflict.getMessage().startsWith("Familiecode is gewijzigd"));
        assertEquals("model-stale", fixture.family.familyKey);
        assertNull(fixture.family.publicHandle);
        assertNull(fixture.members.getFirst().canonicalVariantKey);
    }

    @Test
    @TestTransaction
    void rejectsMembershipDriftWithoutChangingAnything() {
        Fixture fixture = fixture("membership", "TEST-ID-MEMBER-RED", "TEST-ID-MEMBER-PINK");
        ProductFamilyResource.FinalizeDraftIdentityRequest request = new ProductFamilyResource
                .FinalizeDraftIdentityRequest(
                        fixture.family.familyKey, "semantic-display-membership",
                        "semantic-display-membership",
                        List.of(new ProductFamilyResource.VariantIdentityRequest(
                                fixture.members.getFirst().sku,
                                null,
                                "semantic-display-membership-red")));

        BusinessRuleException conflict = assertThrows(BusinessRuleException.class,
                () -> resource.finalizeDraftIdentity(fixture.family.id, request));

        assertTrue(conflict.getMessage().startsWith("SKU-lidmaatschap is gewijzigd"));
        assertEquals("model-membership", fixture.family.familyKey);
        assertNull(fixture.members.get(0).canonicalVariantKey);
        assertNull(fixture.members.get(1).canonicalVariantKey);
    }

    @Test
    @TestTransaction
    void rejectsAnyNonDraftFamilyOrMemberChannel() {
        Fixture fixture = fixture("published", "TEST-ID-PUBLISHED-RED");
        fixture.members.getFirst().orderAppStatus = PublicationState.READY;
        entityManager.flush();

        BusinessRuleException conflict = assertThrows(BusinessRuleException.class,
                () -> resource.finalizeDraftIdentity(fixture.family.id, request(
                        fixture, "semantic-display-published", "semantic-display-published",
                        "semantic-display-published-red")));

        assertTrue(conflict.getMessage().contains("op elk kanaal concept"));
        assertEquals("model-published", fixture.family.familyKey);
        assertNull(fixture.members.getFirst().canonicalVariantKey);
    }

    @Test
    @TestTransaction
    void rejectsFamilyKeyAndHandleCollisions() {
        Fixture fixture = fixture("family-collision", "TEST-ID-FAMILY-COLLISION-RED");
        ProductFamilyEntity keyOwner = family("semantic-display-family-collision");
        keyOwner.publicHandle = "unrelated-owner-handle";
        ProductFamilyEntity handleOwner = family("unrelated-owner-key");
        handleOwner.publicHandle = "semantic-display-handle-collision";
        entityManager.persist(keyOwner);
        entityManager.persist(handleOwner);
        entityManager.flush();

        BusinessRuleException keyConflict = assertThrows(BusinessRuleException.class,
                () -> resource.finalizeDraftIdentity(fixture.family.id, request(
                        fixture, "semantic-display-family-collision", "new-unique-handle",
                        "semantic-display-family-collision-red")));
        assertTrue(keyConflict.getMessage().contains("Familiecode"));

        BusinessRuleException handleConflict = assertThrows(BusinessRuleException.class,
                () -> resource.finalizeDraftIdentity(fixture.family.id, request(
                        fixture, "new-unique-family-key", "semantic-display-handle-collision",
                        "new-unique-family-key-red")));
        assertTrue(handleConflict.getMessage().contains("Publieke handle"));
        assertEquals("model-family-collision", fixture.family.familyKey);
    }

    @Test
    @TestTransaction
    void rejectsVariantKeyCollisionAndAStaleExpectedMemberKey() {
        Fixture fixture = fixture("variant-collision", "TEST-ID-VARIANT-COLLISION-RED");
        ProductEntity other = product("TEST-ID-VARIANT-OWNER");
        other.canonicalVariantKey = "semantic-display-variant-collision-red";
        entityManager.persist(other);
        entityManager.flush();

        BusinessRuleException collision = assertThrows(BusinessRuleException.class,
                () -> resource.finalizeDraftIdentity(fixture.family.id, request(
                        fixture, "semantic-display-variant-collision",
                        "semantic-display-variant-collision",
                        "semantic-display-variant-collision-red")));
        assertTrue(collision.getMessage().contains("Canonieke variantcode"));
        assertNull(fixture.members.getFirst().canonicalVariantKey);

        fixture.members.getFirst().canonicalVariantKey = "existing-stable-variant-key";
        entityManager.flush();
        ProductFamilyResource.FinalizeDraftIdentityRequest staleVariantRequest =
                new ProductFamilyResource.FinalizeDraftIdentityRequest(
                        fixture.family.familyKey, "different-semantic-family",
                        "different-semantic-family",
                        List.of(new ProductFamilyResource.VariantIdentityRequest(
                                fixture.members.getFirst().sku, null,
                                "different-semantic-family-red")));
        BusinessRuleException stableConflict = assertThrows(BusinessRuleException.class,
                () -> resource.finalizeDraftIdentity(
                        fixture.family.id, staleVariantRequest));
        assertTrue(stableConflict.getMessage().contains("Variantcode van SKU"));
        assertEquals("model-variant-collision", fixture.family.familyKey);
        assertEquals("existing-stable-variant-key",
                fixture.members.getFirst().canonicalVariantKey);
    }

    @Test
    @TestTransaction
    void migratesOnlyImportConflictsProvenByTheFamilyLastImportBatch() {
        Fixture fixture = fixture("owned-conflict", "TEST-ID-OWNED-CONFLICT-RED");
        fixture.family.lastImportKey = "test-owned-family-import";
        fixture.members.getFirst().canonicalVariantKey = "model-owned-conflict-red";
        CatalogImportBatchEntity batch = new CatalogImportBatchEntity();
        batch.importKey = fixture.family.lastImportKey;
        batch.sourceDigest = "1".repeat(64);
        entityManager.persist(batch);
        entityManager.flush();
        CatalogImportConflictEntity conflict = new CatalogImportConflictEntity();
        conflict.importBatchId = batch.id;
        conflict.familyKey = fixture.family.familyKey;
        conflict.canonicalVariantKey = fixture.members.getFirst().canonicalVariantKey;
        conflict.code = "TEST_CONFLICT";
        conflict.fieldName = "colour";
        entityManager.persist(conflict);
        entityManager.flush();

        ProductFamilyDto finalized = resource.finalizeDraftIdentity(
                fixture.family.id, request(fixture, "semantic-owned-conflict",
                        "semantic-owned-conflict", "semantic-owned-conflict-red"));

        assertEquals("semantic-owned-conflict", finalized.familyKey());
        assertEquals("semantic-owned-conflict", conflict.familyKey);
        assertEquals("semantic-owned-conflict-red", conflict.canonicalVariantKey);
        assertTrue(finalized.conflicts().stream()
                .anyMatch(item -> "colour".equals(item.fieldName())));
    }

    @Test
    @TestTransaction
    void idempotentRetryDoesNotClaimAReusedOldKeyFromTheSameImportBatch() {
        Fixture fixture = fixture("retry-owned", "TEST-ID-RETRY-OWNED-RED");
        fixture.family.lastImportKey = "test-shared-retry-import";
        CatalogImportBatchEntity batch = new CatalogImportBatchEntity();
        batch.importKey = fixture.family.lastImportKey;
        batch.sourceDigest = "2".repeat(64);
        entityManager.persist(batch);
        entityManager.flush();
        ProductFamilyResource.FinalizeDraftIdentityRequest request = request(
                fixture, "semantic-retry-owned", "semantic-retry-owned",
                "semantic-retry-owned-red");
        resource.finalizeDraftIdentity(fixture.family.id, request);

        ProductFamilyEntity laterOwner = family("model-retry-owned");
        laterOwner.lastImportKey = batch.importKey;
        entityManager.persist(laterOwner);
        entityManager.flush();
        CatalogImportConflictEntity laterConflict = new CatalogImportConflictEntity();
        laterConflict.importBatchId = batch.id;
        laterConflict.familyKey = laterOwner.familyKey;
        laterConflict.code = "LATER_CONFLICT";
        entityManager.persist(laterConflict);
        entityManager.flush();

        ProductFamilyDto retry = resource.finalizeDraftIdentity(fixture.family.id, request);

        assertEquals("semantic-retry-owned", retry.familyKey());
        assertEquals("model-retry-owned", laterConflict.familyKey);
        assertNull(laterConflict.canonicalVariantKey);
    }

    private Fixture fixture(String suffix, String... skus) {
        ProductFamilyEntity family = family("model-" + suffix);
        entityManager.persist(family);
        entityManager.flush();
        List<ProductEntity> members = java.util.Arrays.stream(skus).map(sku -> {
            ProductEntity product = product(sku);
            product.familyId = family.id;
            product.familyKey = family.familyKey;
            entityManager.persist(product);
            return product;
        }).toList();
        entityManager.flush();
        return new Fixture(family, members);
    }

    private ProductFamilyResource.FinalizeDraftIdentityRequest request(
            Fixture fixture, String familyKey, String handle, String... variantKeys) {
        List<ProductFamilyResource.VariantIdentityRequest> variants =
                java.util.stream.IntStream.range(0, fixture.members.size())
                        .mapToObj(index -> new ProductFamilyResource.VariantIdentityRequest(
                                fixture.members.get(index).sku,
                                fixture.members.get(index).canonicalVariantKey,
                                variantKeys[index]))
                        .toList();
        return new ProductFamilyResource.FinalizeDraftIdentityRequest(
                fixture.family.familyKey, familyKey, handle, variants);
    }

    private static ProductFamilyEntity family(String key) {
        ProductFamilyEntity family = new ProductFamilyEntity();
        family.familyKey = key;
        family.name = key;
        return family;
    }

    private static ProductEntity product(String sku) {
        ProductEntity product = new ProductEntity();
        product.sku = sku;
        product.name = sku;
        return product;
    }

    private record Fixture(ProductFamilyEntity family, List<ProductEntity> members) {}
}
