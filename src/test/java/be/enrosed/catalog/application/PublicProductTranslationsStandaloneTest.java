package be.enrosed.catalog.application;

import be.enrosed.catalog.adapter.in.rest.ProductDto;
import be.enrosed.catalog.adapter.in.rest.ProductFamilyDto;
import be.enrosed.catalog.adapter.in.rest.PublicProductTranslationsDto;
import be.enrosed.catalog.adapter.in.rest.WebsiteRebuildDto;
import be.enrosed.catalog.adapter.out.persistence.ProductEntity;
import be.enrosed.catalog.adapter.out.persistence.ProductTextEntity;
import be.enrosed.catalog.adapter.out.persistence.CanonicalCatalogDaos;
import be.enrosed.catalog.adapter.out.persistence.WebsiteRebuildEntity;
import be.enrosed.catalog.domain.WebsiteRebuildStatus;
import be.enrosed.shared.BusinessRuleException;
import be.enrosed.shared.Language;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class PublicProductTranslationsStandaloneTest {
    @Inject PublicProductTranslationsService translations;
    @Inject EntityManager entityManager;
    @Inject WebsiteRebuildService rebuild;
    @Inject CanonicalCatalogDaos.WebsiteRebuilds rebuildRows;

    @Test
    @TestTransaction
    void standaloneProductHasAtomicTranslationSnapshotAndStableNoOpRevision() {
        ProductEntity product = standalone("STANDALONE-TRANSLATION");
        entityManager.persist(product);
        entityManager.flush();

        PublicProductTranslationsDto initial = translations.get(product.id);
        assertNull(initial.familyId());
        assertNull(initial.family());
        assertTrue(initial.familyTexts().isEmpty());
        assertTrue(initial.images().isEmpty());
        assertNotNull(initial.product());

        List<ProductDto.TextDto> localized = List.of(
                new ProductDto.TextDto(Language.FR, "Rose autonome",
                        "Description française", "Rouge", "Petit"),
                new ProductDto.TextDto(Language.TR, "Bağımsız gül",
                        "Türkçe açıklama", "Kırmızı", "Küçük"));
        PublicProductTranslationsDto updated = translations.update(product.id,
                new PublicProductTranslationsDto.UpdateDto(
                        initial.revision(), null, List.of(), localized, List.of()));
        assertNotEquals(initial.revision(), updated.revision());
        assertEquals(localized, updated.productTexts());
        assertNull(updated.familyId());

        PublicProductTranslationsDto noOp = translations.update(product.id,
                new PublicProductTranslationsDto.UpdateDto(
                        updated.revision(), null, List.of(), localized, List.of()));
        assertEquals(updated.revision(), noOp.revision(),
                "identical normalized payload must not create a new public revision");

        assertThrows(BusinessRuleException.class, () -> translations.update(product.id,
                new PublicProductTranslationsDto.UpdateDto(
                        initial.revision(), null, List.of(), localized, List.of())));
    }

    @Test
    @TestTransaction
    void standaloneProductRejectsFamilyOnlyPayload() {
        ProductEntity product = standalone("STANDALONE-FAMILY-PAYLOAD");
        entityManager.persist(product);
        entityManager.flush();
        PublicProductTranslationsDto initial = translations.get(product.id);

        ProductFamilyDto.TextDto illegal = new ProductFamilyDto.TextDto(
                Language.EN, "Not linked", null, null, null, List.of(), null, null);
        assertThrows(BusinessRuleException.class, () -> translations.update(product.id,
                new PublicProductTranslationsDto.UpdateDto(
                        initial.revision(), null, List.of(illegal), List.of(), List.of())));
    }

    @Test
    @TestTransaction
    void atomicProductTextHonoursDatabaseShortColumnBoundaries() {
        ProductEntity product = standalone("STANDALONE-LENGTHS");
        entityManager.persist(product);
        entityManager.flush();
        PublicProductTranslationsDto initial = translations.get(product.id);
        ProductDto.TextDto accepted = new ProductDto.TextDto(
                Language.EN, "x".repeat(255), null, "c".repeat(255), "s".repeat(255));
        PublicProductTranslationsDto updated = translations.update(product.id,
                new PublicProductTranslationsDto.UpdateDto(initial.revision(), null,
                        List.of(), List.of(accepted), List.of()));
        assertEquals(accepted, updated.productTexts().getFirst());

        ProductDto.TextDto tooLong = new ProductDto.TextDto(
                Language.EN, "x".repeat(256), null, null, null);
        assertThrows(BusinessRuleException.class, () -> translations.update(product.id,
                new PublicProductTranslationsDto.UpdateDto(updated.revision(), null,
                        List.of(), List.of(tooLong), List.of())));
    }

    @Test
    @TestTransaction
    void bulkTranslationImportQueuesOneRebuildAndANoOpDoesNotRequeue() {
        ProductEntity product = standalone("STANDALONE-BULK-TRANSLATION");
        ProductTextEntity french = new ProductTextEntity();
        french.product = product;
        french.language = Language.FR;
        french.name = "Version française la plus récente";
        product.texts.add(french);
        entityManager.persist(product);
        entityManager.flush();
        WebsiteRebuildEntity state = rebuildRows.findById(1L);
        if (state == null) {
            state = new WebsiteRebuildEntity();
            rebuildRows.persist(state);
        }
        state.status = WebsiteRebuildStatus.LIVE;
        state.liveRevision = "0".repeat(64);
        state.attemptCount = 3;
        Map<Language, ProductDto.TextDto> patch = Map.of(Language.EN,
                new ProductDto.TextDto(
                        Language.EN, "Standalone", "Description", null, "Small"));
        Optional<String> previousHook = rebuild.deployHookUrl;
        try {
            rebuild.deployHookUrl = Optional.of("https://example.invalid/deploy-hook");
            assertEquals(1, translations.patchProductTexts(Map.of(product.id, patch)));
            assertEquals(WebsiteRebuildStatus.QUEUED, state.status);
            assertEquals(0, state.attemptCount);
            java.time.Instant queuedAt = state.queuedAt;
            PublicProductTranslationsDto after = translations.get(product.id);
            assertEquals("Version française la plus récente", after.productTexts().stream()
                    .filter(text -> text.language() == Language.FR)
                    .findFirst().orElseThrow().name(),
                    "a partial spreadsheet patch must preserve a concurrently newer absent language");

            assertEquals(0, translations.patchProductTexts(Map.of(product.id, patch)));
            assertEquals(queuedAt, state.queuedAt,
                    "an identical full translation snapshot must not requeue the outbox");
        } finally {
            rebuild.deployHookUrl = previousHook;
        }
    }

    @Test
    @TestTransaction
    void configuredStatusCreatesAndLocksTheSingletonOutboxRowWithNativeColumnNames() {
        rebuildRows.deleteAll();
        rebuildRows.flush();
        Optional<String> previousHook = rebuild.deployHookUrl;
        try {
            rebuild.deployHookUrl = Optional.of("https://example.invalid/deploy-hook");
            WebsiteRebuildDto status = rebuild.status();
            assertEquals(WebsiteRebuildStatus.QUEUED, status.status());
            WebsiteRebuildEntity row = rebuildRows.findById(1L);
            assertNotNull(row);
            assertEquals(0L, row.rowRevision);
            assertEquals(0, row.attemptCount);
        } finally {
            rebuild.deployHookUrl = previousHook;
        }
    }

    private static ProductEntity standalone(String sku) {
        ProductEntity product = new ProductEntity();
        product.sku = sku;
        product.name = "Standalone product";
        product.active = true;
        product.familyId = null;
        product.familyKey = null;
        product.canonicalVariantKey = "standalone-" + sku.toLowerCase();
        product.piecesPerCarton = 1;
        return product;
    }
}
