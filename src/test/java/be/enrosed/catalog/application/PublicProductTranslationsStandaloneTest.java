package be.enrosed.catalog.application;

import be.enrosed.catalog.adapter.in.rest.ProductDto;
import be.enrosed.catalog.adapter.in.rest.ProductFamilyDto;
import be.enrosed.catalog.adapter.in.rest.PublicProductTranslationsDto;
import be.enrosed.catalog.adapter.in.rest.WebsiteRebuildDto;
import be.enrosed.catalog.adapter.out.persistence.ProductEntity;
import be.enrosed.catalog.adapter.out.persistence.ProductTextEntity;
import be.enrosed.catalog.adapter.out.persistence.CanonicalCatalogDaos;
import be.enrosed.catalog.adapter.out.persistence.WebsiteRebuildEntity;
import be.enrosed.catalog.application.port.out.ProductRepository;
import be.enrosed.catalog.domain.Product;
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
    @Inject ProductRepository products;
    @Inject EntityManager entityManager;
    @Inject WebsiteRebuildService rebuild;
    /* The injected bean is a CDI client proxy: writing its config fields
       changes the proxy's copy, never the real instance. Unwrap first. */
    private WebsiteRebuildService rebuildTarget() {
        return io.quarkus.arc.ClientProxy.unwrap(rebuild);
    }

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
    void explicitPublicNameDivergesFromDocumentNameAndSurvivesLaterTranslationEdits() {
        ProductEntity product = standalone("STANDALONE-PUBLIC-NAME");
        product.publicName = product.name;
        ProductTextEntity french = new ProductTextEntity();
        french.product = product;
        french.language = Language.FR;
        french.name = "Nom de facture";
        french.publicName = french.name;
        product.texts.add(french);
        entityManager.persist(product);
        entityManager.flush();

        PublicProductTranslationsDto initial = translations.get(product.id);
        var publicCopy = new PublicProductTranslationsDto.ProductPublicCopyDto(
                "Public standalone rose",
                List.of(new PublicProductTranslationsDto.ProductPublicTextDto(
                        Language.FR, "Rose publique")));
        PublicProductTranslationsDto separated = translations.update(product.id,
                new PublicProductTranslationsDto.UpdateDto(
                        initial.revision(), null, List.of(), null, List.of(), publicCopy));

        assertEquals("Public standalone rose", separated.productPublicCopy().publicName());
        assertEquals("Rose publique",
                separated.productPublicCopy().texts().getFirst().publicName());
        assertEquals("Nom de facture", separated.productTexts().getFirst().name());

        translations.patchProductTexts(Map.of(product.id, Map.of(Language.FR,
                new ProductDto.TextDto(
                        Language.FR, "Nouveau nom de facture", null, null, null))));
        entityManager.flush();
        entityManager.clear();

        ProductEntity stored = entityManager.find(ProductEntity.class, product.id);
        ProductTextEntity storedFrench = stored.texts.stream()
                .filter(text -> text.language == Language.FR).findFirst().orElseThrow();
        assertEquals("Nouveau nom de facture", storedFrench.name);
        assertEquals("Rose publique", storedFrench.publicName,
                "an explicit public name must not follow later document-name changes");
        assertEquals("Public standalone rose", stored.publicName);
    }

    @Test
    @TestTransaction
    void publicOnlyLanguageRowSurvivesAnUnawareLegacyProductSave() {
        ProductEntity product = standalone("STANDALONE-PUBLIC-ONLY");
        ProductTextEntity turkish = new ProductTextEntity();
        turkish.product = product;
        turkish.language = Language.TR;
        turkish.publicName = "Halka açık gül";
        product.texts.add(turkish);
        entityManager.persist(product);
        entityManager.flush();

        Product operational = products.findById(product.id).orElseThrow();
        assertTrue(operational.texts().isEmpty(),
                "public-only copy must stay outside the document Product aggregate");
        products.save(operational);
        entityManager.flush();
        entityManager.clear();

        ProductEntity stored = entityManager.find(ProductEntity.class, product.id);
        ProductTextEntity storedTurkish = stored.texts.stream()
                .filter(text -> text.language == Language.TR).findFirst().orElseThrow();
        assertNull(storedTurkish.name);
        assertEquals("Halka açık gül", storedTurkish.publicName);
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
        Optional<String> previousHook = rebuildTarget().deployHookUrl;
        try {
            rebuildTarget().deployHookUrl = Optional.of("https://example.invalid/deploy-hook");
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
            rebuildTarget().deployHookUrl = previousHook;
        }
    }

    @Test
    @TestTransaction
    void configuredStatusCreatesAndLocksTheSingletonOutboxRowWithNativeColumnNames() {
        /* The singleton row survives other test classes (the scheduler
           commits in its own transactions); an in-test delete would roll
           back with this test, so clear it in a committed transaction and
           drop the stale instance from the first-level cache. */
        io.quarkus.narayana.jta.QuarkusTransaction.requiringNew()
                .run(() -> rebuildRows.deleteAll());
        entityManager.clear();
        Optional<String> previousHook = rebuildTarget().deployHookUrl;
        try {
            rebuildTarget().deployHookUrl = Optional.of("https://example.invalid/deploy-hook");
            WebsiteRebuildDto status = rebuild.status();
            assertEquals(WebsiteRebuildStatus.QUEUED, status.status());
            WebsiteRebuildEntity row = rebuildRows.findById(1L);
            assertNotNull(row);
            /* The native insert writes rowRevision 0; status() then fills in
               queuedAt/nextAttemptAt/currentRevision and flushes, so the
               @Version column legitimately reads 1 here. */
            assertEquals(1L, row.rowRevision);
            assertEquals(0, row.attemptCount);
        } finally {
            rebuildTarget().deployHookUrl = previousHook;
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
