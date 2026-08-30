package be.enrosed.catalog.application;

import be.enrosed.catalog.adapter.in.rest.ContentTranslationDto;
import be.enrosed.catalog.adapter.in.rest.ContentTranslationResource;
import be.enrosed.catalog.adapter.in.rest.LocalizedValueDto;
import be.enrosed.catalog.adapter.out.persistence.CanonicalCatalogDaos;
import be.enrosed.catalog.adapter.out.persistence.ContentTranslationEntity;
import be.enrosed.catalog.adapter.out.persistence.ContentTranslationTextEntity;
import be.enrosed.catalog.adapter.out.persistence.WebsiteRebuildEntity;
import be.enrosed.catalog.domain.ContentScope;
import be.enrosed.catalog.domain.WebsiteRebuildStatus;
import be.enrosed.shared.BusinessRuleException;
import be.enrosed.shared.Language;
import be.enrosed.shared.adapter.in.rest.BusinessRuleMapper;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
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
    @Inject EntityManager entityManager;
    @Inject WebsiteRebuildService rebuild;
    /* The injected bean is a CDI client proxy: writing its config fields
       changes the proxy's copy, never the real instance. Unwrap first. */
    private WebsiteRebuildService rebuildTarget() {
        return io.quarkus.arc.ClientProxy.unwrap(rebuild);
    }

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
    void privacySeedMigratesOnlyExactFormerDefaultsAcrossTheContactCopy() {
        ContentTranslationEntity data = rows.find(
                "scope = ?1 and key = ?2", ContentScope.WEBSITE,
                "legal.privacy.data.p2").firstResult();
        ContentTranslationEntity purpose = rows.find(
                "scope = ?1 and key = ?2", ContentScope.WEBSITE,
                "legal.privacy.purposes.item1").firstResult();
        ContentTranslationEntity cookies = rows.find(
                "scope = ?1 and key = ?2", ContentScope.WEBSITE,
                "legal.privacy.cookies.p1").firstResult();

        ContentTranslationTextEntity dataDutch = translation(data, Language.NL);
        ContentTranslationTextEntity dataGerman = translation(data, Language.DE);
        ContentTranslationTextEntity dataFrench = translation(data, Language.FR);
        ContentTranslationTextEntity purposeSpanish = translation(purpose, Language.ES);
        ContentTranslationTextEntity purposePolish = translation(purpose, Language.PL);
        ContentTranslationTextEntity cookiesDutch = translation(cookies, Language.NL);
        ContentTranslationTextEntity cookiesTurkish = translation(cookies, Language.TR);
        ContentTranslationTextEntity cookiesPortuguese = translation(cookies, Language.PT);

        String expectedDataDutch = dataDutch.value;
        String expectedDataGerman = dataGerman.value;
        String expectedPurposeSpanish = purposeSpanish.value;
        String expectedCookiesDutch = cookiesDutch.value;
        String expectedCookiesTurkish = cookiesTurkish.value;

        dataDutch.value = "Deze website maakt gebruik van e-mail- en telefoonlinks in plaats van een offerteformulier op de website. Informatie die u in een e-mail opneemt, wordt verwerkt via de e-mailservice die wordt gebruikt door Enrosed.";
        dataGerman.value = "Diese Website stellt ein Formular für Großhandelsangebote bereit. Wenn Sie es absenden, verarbeitet Enrosed Ihre Unternehmens- und Kontaktdaten, die ausgewählten Produkte und Mengen, den Lieferort, die Umsatzsteuer-Identifikationsnummer und etwaige Anmerkungen, um Ihr Angebot zu erstellen und nachzuverfolgen.";
        dataFrench.value = "Texte de confidentialité approuvé dans le dashboard";
        purposeSpanish.value = "preparar y hacer el seguimiento de un presupuesto solicitado;";
        purposePolish.value = "Tekst celu zatwierdzony w panelu";
        cookiesDutch.value = "Deze groothandelswebsite gebruikt momenteel geen analyse- of advertentiecookies. Alleen de technische functionaliteit die nodig is om de website weer te geven en de gekozen links te openen, wordt gebruikt. De knop Cookievoorkeuren in de footer toont de huidige status.";
        cookiesTurkish.value = "Bu toptan satış sitesi analiz veya reklam çerezi kullanmaz. Yalnızca gerekli teknik işlevler kullanılır. Form koruması etkin olduğunda güvenlik sağlayıcısı teknik verileri işleyebilir ve otomatik gönderimleri saptamak için kesinlikle gerekli depolamayı kullanabilir. Alt bilgideki çerez tercihleri güncel durumu gösterir.";
        cookiesPortuguese.value = "Texto de cookies aprovado no dashboard";
        entityManager.flush();

        seeds.onStart(null);

        assertEquals(expectedDataDutch, dataDutch.value,
                "the exact former no-form seed must migrate");
        assertEquals(expectedDataGerman, dataGerman.value,
                "the exact quote-only seed must migrate");
        assertEquals(expectedPurposeSpanish, purposeSpanish.value,
                "the exact quote-only purpose must migrate");
        assertEquals(expectedCookiesDutch, cookiesDutch.value,
                "the exact pre-security cookie seed must migrate");
        assertEquals(expectedCookiesTurkish, cookiesTurkish.value,
                "the exact generic-security cookie seed must migrate");
        assertEquals("Texte de confidentialité approuvé dans le dashboard", dataFrench.value);
        assertEquals("Tekst celu zatwierdzony w panelu", purposePolish.value);
        assertEquals("Texto de cookies aprovado no dashboard", cookiesPortuguese.value,
                "dashboard-authored values must never be overwritten");
    }

    @Test
    @TestTransaction
    void legalSeedMigratesOnlyExactFormerDatesHeadingsAndLimburgClause() {
        ContentTranslationEntity shippingDate = rows.find(
                "scope = ?1 and key = ?2", ContentScope.WEBSITE,
                "legal.shipping.updated").firstResult();
        ContentTranslationEntity tradeDate = rows.find(
                "scope = ?1 and key = ?2", ContentScope.WEBSITE,
                "legal.trade.updated").firstResult();
        ContentTranslationEntity lawTitle = rows.find(
                "scope = ?1 and key = ?2", ContentScope.WEBSITE,
                "legal.trade.lawContact.title").firstResult();
        ContentTranslationEntity lawClause = rows.find(
                "scope = ?1 and key = ?2", ContentScope.WEBSITE,
                "legal.trade.lawContact.p1").firstResult();

        ContentTranslationTextEntity shippingDutch = translation(shippingDate, Language.NL);
        ContentTranslationTextEntity shippingFrench = translation(shippingDate, Language.FR);
        ContentTranslationTextEntity tradePolish = translation(tradeDate, Language.PL);
        ContentTranslationTextEntity tradePortuguese = translation(tradeDate, Language.PT);
        ContentTranslationTextEntity titleEnglish = translation(lawTitle, Language.EN);
        ContentTranslationTextEntity titleTurkish = translation(lawTitle, Language.TR);
        ContentTranslationTextEntity clauseGerman = translation(lawClause, Language.DE);
        ContentTranslationTextEntity clauseSpanish = translation(lawClause, Language.ES);

        String expectedShippingDutch = shippingDutch.value;
        String expectedTradePolish = tradePolish.value;
        String expectedTitleEnglish = titleEnglish.value;
        String expectedClauseGerman = clauseGerman.value;

        shippingDutch.value = "20 augustus 2026";
        shippingFrench.value = "Date juridique approuvée dans le dashboard";
        tradePolish.value = "20 sierpnia 2026 r";
        tradePortuguese.value = "Data jurídica aprovada no dashboard";
        titleEnglish.value = "8. Applicable law and contact";
        titleTurkish.value = "Panelde onaylanan hukuk başlığı";
        clauseGerman.value = "Für die Geschäftsbeziehung gilt belgisches Recht. Sofern nicht zwingendes Recht etwas anderes vorsieht, fallen Streitigkeiten in die Zuständigkeit der zuständigen Gerichte in Limburg, Belgien.";
        clauseSpanish.value = "Cláusula jurídica aprobada en el panel";
        entityManager.flush();

        seeds.onStart(null);

        assertEquals(expectedShippingDutch, shippingDutch.value);
        assertEquals(expectedTradePolish, tradePolish.value);
        assertEquals(expectedTitleEnglish, titleEnglish.value);
        assertEquals(expectedClauseGerman, clauseGerman.value);
        assertEquals("Date juridique approuvée dans le dashboard", shippingFrench.value);
        assertEquals("Data jurídica aprovada no dashboard", tradePortuguese.value);
        assertEquals("Panelde onaylanan hukuk başlığı", titleTurkish.value);
        assertEquals("Cláusula jurídica aprobada en el panel", clauseSpanish.value,
                "dashboard-authored legal copy must never be overwritten");
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

        Optional<String> previousHook = rebuildTarget().deployHookUrl;
        try {
            rebuildTarget().deployHookUrl = Optional.of("https://example.invalid/deploy-hook");
            seeds.ensureSeededAndQueueWebsiteChange();
            assertEquals(WebsiteRebuildStatus.QUEUED, state.status);
            assertEquals(0, state.attemptCount);

            java.time.Instant queuedAt = state.queuedAt;
            seeds.ensureSeededAndQueueWebsiteChange();
            assertEquals(queuedAt, state.queuedAt,
                    "an unchanged startup seed must not enqueue another deploy");
        } finally {
            rebuildTarget().deployHookUrl = previousHook;
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
        Optional<String> previousHook = rebuildTarget().deployHookUrl;
        try {
            rebuildTarget().deployHookUrl = Optional.of("https://example.invalid/deploy-hook");
            rebuild.onStart(null);
            assertEquals(WebsiteRebuildStatus.QUEUED, state.status);

            state.status = WebsiteRebuildStatus.LIVE;
            state.liveRevision = catalogRevision.currentRevision();
            java.time.Instant unchangedQueue = state.queuedAt;
            rebuild.onStart(null);
            assertEquals(WebsiteRebuildStatus.LIVE, state.status);
            assertEquals(unchangedQueue, state.queuedAt);
        } finally {
            rebuildTarget().deployHookUrl = previousHook;
        }
    }

    private static List<LocalizedValueDto> values(String prefix) {
        return Arrays.stream(Language.values())
                .map(language -> new LocalizedValueDto(
                        language, prefix + " " + language.code()))
                .toList();
    }

    private static ContentTranslationTextEntity translation(
            ContentTranslationEntity owner, Language language) {
        return owner.texts.stream()
                .filter(text -> text.language == language)
                .findFirst().orElseThrow();
    }
}
