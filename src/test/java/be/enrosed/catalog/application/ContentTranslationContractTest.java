package be.enrosed.catalog.application;

import be.enrosed.catalog.adapter.in.rest.ContentTranslationDto;
import be.enrosed.catalog.adapter.in.rest.ContentTranslationResource;
import be.enrosed.catalog.adapter.in.rest.LocalizedValueDto;
import be.enrosed.catalog.adapter.out.persistence.CanonicalCatalogDaos;
import be.enrosed.catalog.adapter.out.persistence.ContentTranslationEntity;
import be.enrosed.catalog.adapter.out.persistence.WebsiteRebuildEntity;
import be.enrosed.catalog.domain.ContentScope;
import be.enrosed.catalog.domain.WebsiteRebuildStatus;
import be.enrosed.shared.BusinessRuleException;
import be.enrosed.shared.Language;
import be.enrosed.shared.adapter.in.rest.BusinessRuleMapper;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class ContentTranslationContractTest {
    @Inject ContentTranslationService content;
    @Inject PublicContentSeedLoader seeds;
    @Inject CanonicalCatalogDaos.ContentTranslations rows;
    @Inject CanonicalCatalogDaos.WebsiteRebuilds rebuildRows;
    @Inject WebsiteRebuildService rebuild;
    @Inject WebsiteCatalogRevisionService catalogRevision;

    @Test
    @TestTransaction
    void customCopyUpdatesRowsInPlaceAndNoOpKeepsRevisionStable() {
        ContentTranslationDto created = content.create(new ContentTranslationDto.CreateDto(
                ContentScope.CATALOG, "test.custom-copy", "Custom copy", true,
                values("Initial")));
        ContentTranslationDto updated = content.update(ContentScope.CATALOG, created.key(),
                new ContentTranslationDto.UpdateDto(created.revision(), "Custom copy", true,
                        values("Updated")));
        assertTrue(updated.revision() > created.revision());
        assertEquals(8, updated.texts().size());
        assertTrue(updated.texts().stream().allMatch(text -> text.value().startsWith("Updated")));

        ContentTranslationDto noOp = content.update(ContentScope.CATALOG, created.key(),
                new ContentTranslationDto.UpdateDto(updated.revision(), "Custom copy", true,
                        values("Updated")));
        assertEquals(updated.revision(), noOp.revision());
    }

    @Test
    @TestTransaction
    void staleRevisionMapsToConflict() {
        ContentTranslationDto created = content.create(new ContentTranslationDto.CreateDto(
                ContentScope.CATALOG, "test.stale-copy", "Stale copy", false,
                List.of(new LocalizedValueDto(Language.EN, "Value"))));
        BusinessRuleException stale = assertThrows(BusinessRuleException.class,
                () -> content.update(ContentScope.CATALOG, created.key(),
                        new ContentTranslationDto.UpdateDto(
                                created.revision() + 1, "Stale copy", false, created.texts())));
        Response staleResponse = new BusinessRuleMapper().toResponse(stale);
        assertEquals(409, staleResponse.getStatus());
        assertTrue(staleResponse.getEntity().toString().contains("herlaad"));

    }

    @Test
    @TestTransaction
    void systemDeletionMapsToConflict() {
        ContentTranslationDto system = content.get(ContentScope.WEBSITE, "a11y.productCount");
        BusinessRuleException protectedDelete = assertThrows(BusinessRuleException.class,
                () -> content.delete(system.scope(), system.key(), system.revision()));
        assertEquals(409, new BusinessRuleMapper().toResponse(protectedDelete).getStatus());
    }

    @Test
    @TestTransaction
    void deleteRequiresAnExplicitRevisionEvenForRevisionZeroRows() {
        ContentTranslationDto created = content.create(new ContentTranslationDto.CreateDto(
                ContentScope.CATALOG, "test.delete-revision", "Delete revision", false,
                List.of(new LocalizedValueDto(Language.EN, "Value"))));
        assertEquals(0, created.revision());
        BadRequestException missing = assertThrows(BadRequestException.class,
                () -> new ContentTranslationResource(content)
                        .delete(created.scope(), created.key(), null));
        assertTrue(missing.getMessage().contains("revision"));
        assertEquals(created.key(), content.get(created.scope(), created.key()).key());
    }

    @Test
    @TestTransaction
    void updateRequiresAnExplicitRevisionEvenForRevisionZeroRows() {
        ContentTranslationDto created = content.create(new ContentTranslationDto.CreateDto(
                ContentScope.CATALOG, "test.update-revision", "Update revision", false,
                List.of(new LocalizedValueDto(Language.EN, "Value"))));
        assertEquals(0, created.revision());

        BusinessRuleException missing = assertThrows(BusinessRuleException.class,
                () -> content.update(created.scope(), created.key(),
                        new ContentTranslationDto.UpdateDto(
                                null, created.label(), created.required(), created.texts())));

        assertTrue(missing.getMessage().contains("revision"));
        assertEquals("Value", content.get(created.scope(), created.key()).texts()
                .getFirst().value());
    }

    @Test
    @TestTransaction
    void systemMetadataIsImmutableAndMapsToConflict() {
        ContentTranslationDto system = content.get(ContentScope.WEBSITE, "a11y.productCount");
        BusinessRuleException protectedMetadata = assertThrows(BusinessRuleException.class,
                () -> content.update(system.scope(), system.key(),
                        new ContentTranslationDto.UpdateDto(system.revision(),
                                system.label() + " changed", system.required(), system.texts())));
        assertEquals(409, new BusinessRuleMapper().toResponse(protectedMetadata).getStatus());
        assertTrue(protectedMetadata.getMessage().contains("niet wijzigbaar"));
    }

    @Test
    @TestTransaction
    void duplicateLanguagesAreRejectedActionably() {
        List<LocalizedValueDto> duplicate = List.of(
                new LocalizedValueDto(Language.EN, "One"),
                new LocalizedValueDto(Language.EN, "Two"));
        BusinessRuleException duplicateError = assertThrows(BusinessRuleException.class,
                () -> content.create(new ContentTranslationDto.CreateDto(
                        ContentScope.CATALOG, "test.duplicate-language", "Duplicate", false,
                        duplicate)));
        assertTrue(duplicateError.getMessage().contains("exact één keer"));
    }

    @Test
    @TestTransaction
    void systemPlaceholderDriftIsRejectedActionably() {
        ContentTranslationDto system = content.get(ContentScope.WEBSITE, "a11y.productCount");
        List<LocalizedValueDto> drifted = new ArrayList<>(system.texts());
        for (int index = 0; index < drifted.size(); index++) {
            LocalizedValueDto value = drifted.get(index);
            if (value.language() == Language.NL) {
                drifted.set(index, new LocalizedValueDto(Language.NL,
                        value.value().replace("{count}", "{aantal}")));
            }
        }
        BadRequestException placeholderError = assertThrows(BadRequestException.class,
                () -> content.update(system.scope(), system.key(),
                        new ContentTranslationDto.UpdateDto(system.revision(), system.label(),
                                system.required(), drifted)));
        assertTrue(placeholderError.getMessage().contains("placeholders"));
        assertTrue(placeholderError.getMessage().contains("count"));
    }

    @Test
    @TestTransaction
    void seedPromotionEnforcesMetadataAndCorrectsOnlyKnownStaleValues() {
        ContentTranslationEntity entity = rows.find(
                "scope = ?1 and key = ?2", ContentScope.WEBSITE,
                "home.counter.item3.title").firstResult();
        entity.system = false;
        entity.required = false;
        entity.label = "Stale label";
        entity.texts.forEach(text -> text.value = text.language == Language.FR
                ? "Texte personnalisé approuvé" : "12 Steel Roses");

        seeds.onStart(null);

        assertTrue(entity.system);
        assertTrue(entity.required);
        assertEquals("Homepage · Toonbankpresentatie · item 3 · titel", entity.label);
        assertEquals("12 Stem Roses", entity.texts.stream()
                .filter(text -> text.language == Language.EN).findFirst().orElseThrow().value);
        assertEquals("12 steelrozen", entity.texts.stream()
                .filter(text -> text.language == Language.NL).findFirst().orElseThrow().value);
        assertEquals("Texte personnalisé approuvé", entity.texts.stream()
                .filter(text -> text.language == Language.FR).findFirst().orElseThrow().value,
                "a dashboard customization that is not the exact stale seed must survive");
    }

    @Test
    @TestTransaction
    void startupSeedDeltaQueuesAnExistingLiveWebsiteWithoutNoOpLoops() {
        ContentTranslationEntity entity = rows.find(
                "scope = ?1 and key = ?2", ContentScope.WEBSITE,
                "home.counter.item3.title").firstResult();
        entity.texts.stream().filter(text -> text.language == Language.EN)
                .findFirst().orElseThrow().value = "12 Steel Roses";
        WebsiteRebuildEntity state = rebuildRows.findById(1L);
        if (state == null) {
            state = new WebsiteRebuildEntity();
            rebuildRows.persist(state);
        }
        state.status = WebsiteRebuildStatus.LIVE;
        state.liveRevision = "0".repeat(64);
        state.attemptCount = 3;

        Optional<String> previousHook = rebuild.deployHookUrl;
        try {
            rebuild.deployHookUrl = Optional.of("https://example.invalid/deploy-hook");
            seeds.ensureSeededAndQueueWebsiteChange();
            assertEquals(WebsiteRebuildStatus.QUEUED, state.status);
            assertEquals(0, state.attemptCount);

            java.time.Instant queuedAt = state.queuedAt;
            seeds.ensureSeededAndQueueWebsiteChange();
            assertEquals(queuedAt, state.queuedAt,
                    "an unchanged startup seed must not enqueue another deploy");
        } finally {
            rebuild.deployHookUrl = previousHook;
        }
    }

    @Test
    @TestTransaction
    void configuredStartupQueuesAnyPersistedRevisionMismatchButNotAnAlreadyLiveDigest() {
        WebsiteRebuildEntity state = rebuildRows.findById(1L);
        if (state == null) {
            state = new WebsiteRebuildEntity();
            rebuildRows.persist(state);
        }
        state.status = WebsiteRebuildStatus.LIVE;
        state.liveRevision = "0".repeat(64);
        Optional<String> previousHook = rebuild.deployHookUrl;
        try {
            rebuild.deployHookUrl = Optional.of("https://example.invalid/deploy-hook");
            rebuild.onStart(null);
            assertEquals(WebsiteRebuildStatus.QUEUED, state.status);

            state.status = WebsiteRebuildStatus.LIVE;
            state.liveRevision = catalogRevision.currentRevision();
            java.time.Instant unchangedQueue = state.queuedAt;
            rebuild.onStart(null);
            assertEquals(WebsiteRebuildStatus.LIVE, state.status);
            assertEquals(unchangedQueue, state.queuedAt);
        } finally {
            rebuild.deployHookUrl = previousHook;
        }
    }

    private static List<LocalizedValueDto> values(String prefix) {
        return Arrays.stream(Language.values())
                .map(language -> new LocalizedValueDto(
                        language, prefix + " " + language.code()))
                .toList();
    }
}
