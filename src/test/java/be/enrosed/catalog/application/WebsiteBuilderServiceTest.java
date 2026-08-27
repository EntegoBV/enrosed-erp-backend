package be.enrosed.catalog.application;

import be.enrosed.catalog.adapter.in.rest.WebsiteBuilderDto;
import be.enrosed.catalog.adapter.out.persistence.CanonicalCatalogDaos;
import be.enrosed.catalog.adapter.out.persistence.WebsiteHomepageLayoutEntity;
import be.enrosed.catalog.domain.HomepageSectionKey;
import be.enrosed.shared.BusinessRuleException;
import be.enrosed.shared.adapter.in.rest.BusinessRuleMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.LockModeType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WebsiteBuilderServiceTest {
    private CanonicalCatalogDaos.WebsiteHomepageLayouts rows;
    private WebsiteRebuildService rebuilds;
    private WebsiteHomepageLayoutEntity entity;
    private WebsiteBuilderService builder;

    @BeforeEach
    void setUp() throws Exception {
        rows = mock(CanonicalCatalogDaos.WebsiteHomepageLayouts.class);
        rebuilds = mock(WebsiteRebuildService.class);
        ObjectMapper json = new ObjectMapper();
        String defaults = json.writeValueAsString(WebsiteBuilderService.defaultSections());
        entity = new WebsiteHomepageLayoutEntity();
        entity.draftSectionsJson = defaults;
        entity.publishedSectionsJson = defaults;
        when(rows.findById(1L)).thenReturn(entity);
        when(rows.findById(1L, LockModeType.PESSIMISTIC_WRITE)).thenReturn(entity);
        builder = new WebsiteBuilderService(rows, rebuilds, json);
    }

    @Test
    void absentAggregateUsesTheB2bDefaultWithoutWritingOnRead() {
        when(rows.findById(1L)).thenReturn(null);

        WebsiteBuilderDto.AdminDto admin = builder.get();
        WebsiteBuilderDto.PublicDto published = builder.published();

        assertEquals(0, admin.revision());
        assertNull(admin.updatedAt());
        assertNull(admin.publishedAt());
        assertEquals(expectedDefault(), admin.draft().sections());
        assertEquals(expectedDefault(), admin.published().sections());
        assertEquals(0, published.revision());
        assertEquals(expectedDefault(), published.homepage().sections());
        assertEquals(false, section(admin.draft().sections(), HomepageSectionKey.SOAP).enabled());
        assertEquals(false, section(admin.draft().sections(), HomepageSectionKey.OCCASION).enabled());
        assertEquals(false, section(admin.draft().sections(), HomepageSectionKey.CATALOG).enabled());
    }

    @Test
    void draftUpdateIsRevisionedButDoesNotChangePublicLayoutOrQueueARebuild() {
        List<WebsiteBuilderDto.SectionDto> requested = reorderedDraft();

        WebsiteBuilderDto.AdminDto saved = builder.update(
                new WebsiteBuilderDto.UpdateDto(0L, requested));

        assertEquals(1, saved.revision());
        assertEquals(requested, saved.draft().sections());
        assertEquals(expectedDefault(), saved.published().sections());
        assertEquals(0, builder.published().revision());
        assertEquals(expectedDefault(), builder.published().homepage().sections());
        verify(rebuilds, never()).queue();

        Instant updatedAt = saved.updatedAt();
        WebsiteBuilderDto.AdminDto noOp = builder.update(
                new WebsiteBuilderDto.UpdateDto(saved.revision(), requested));
        assertEquals(saved.revision(), noOp.revision());
        assertEquals(updatedAt, noOp.updatedAt());
        verify(rebuilds, never()).queue();
    }

    @Test
    void publishCopiesDraftChangesPublicRevisionAndQueuesOnce() {
        WebsiteBuilderDto.AdminDto saved = builder.update(
                new WebsiteBuilderDto.UpdateDto(0L, reorderedDraft()));

        WebsiteBuilderDto.AdminDto published = builder.publish(
                new WebsiteBuilderDto.PublishDto(saved.revision()));

        assertEquals(2, published.revision());
        assertEquals(2, builder.published().revision());
        assertEquals(reorderedDraft(), published.published().sections());
        assertEquals(reorderedDraft(), builder.published().homepage().sections());
        assertNotNull(published.publishedAt());
        verify(rebuilds).queue();

        Instant publishedAt = published.publishedAt();
        WebsiteBuilderDto.AdminDto noOp = builder.publish(
                new WebsiteBuilderDto.PublishDto(published.revision()));
        assertEquals(published.revision(), noOp.revision());
        assertEquals(publishedAt, noOp.publishedAt());
        verify(rebuilds).queue();
    }

    @Test
    void staleRevisionAndInvalidFixedSectionsAreActionableConflicts() {
        WebsiteBuilderDto.AdminDto saved = builder.update(
                new WebsiteBuilderDto.UpdateDto(0L, reorderedDraft()));

        BusinessRuleException stale = assertThrows(BusinessRuleException.class,
                () -> builder.publish(new WebsiteBuilderDto.PublishDto(saved.revision() - 1)));
        assertEquals(409, new BusinessRuleMapper().toResponse(stale).getStatus());
        assertTrue(stale.getMessage().contains("herlaad"));

        List<WebsiteBuilderDto.SectionDto> heroDisabled = new ArrayList<>(expectedDefault());
        heroDisabled.set(0, section(HomepageSectionKey.HERO, false));
        BusinessRuleException fixedHero = assertThrows(BusinessRuleException.class,
                () -> builder.update(new WebsiteBuilderDto.UpdateDto(
                        saved.revision(), heroDisabled)));
        assertTrue(fixedHero.getMessage().contains("hero"));

        List<WebsiteBuilderDto.SectionDto> duplicate = new ArrayList<>(expectedDefault());
        duplicate.set(2, section(HomepageSectionKey.RANGE, true));
        BusinessRuleException duplicateKey = assertThrows(BusinessRuleException.class,
                () -> builder.update(new WebsiteBuilderDto.UpdateDto(
                        saved.revision(), duplicate)));
        assertTrue(duplicateKey.getMessage().contains("meer dan één keer"));
    }

    @Test
    void sectionKeysAreAnExactClosedWireAllowlist() {
        assertEquals(HomepageSectionKey.FLOWERBOX,
                HomepageSectionKey.fromKey("flowerbox"));
        assertEquals(HomepageSectionKey.SOAP,
                HomepageSectionKey.fromKey("soap"));
        IllegalArgumentException unknown = assertThrows(IllegalArgumentException.class,
                () -> HomepageSectionKey.fromKey("custom-html"));
        assertTrue(unknown.getMessage().contains("toegestaan"));
        assertThrows(IllegalArgumentException.class,
                () -> HomepageSectionKey.fromKey("Hero"));
    }

    private static List<WebsiteBuilderDto.SectionDto> expectedDefault() {
        return HomepageSectionKey.defaultOrder().stream()
                .map(key -> section(key, key.enabledByDefault()))
                .toList();
    }

    private static List<WebsiteBuilderDto.SectionDto> reorderedDraft() {
        return List.of(
                section(HomepageSectionKey.HERO, true),
                section(HomepageSectionKey.CATALOG, true),
                section(HomepageSectionKey.RANGE, true),
                section(HomepageSectionKey.COUNTER, false),
                section(HomepageSectionKey.FLOWERBOX, true),
                section(HomepageSectionKey.SOAP, false),
                section(HomepageSectionKey.OCCASION, true),
                section(HomepageSectionKey.RETAIL, true),
                section(HomepageSectionKey.FAQ, true),
                section(HomepageSectionKey.ORDER, true),
                section(HomepageSectionKey.QUOTE, true));
    }

    private static WebsiteBuilderDto.SectionDto section(
            HomepageSectionKey key, boolean enabled) {
        return new WebsiteBuilderDto.SectionDto(key, enabled);
    }

    private static WebsiteBuilderDto.SectionDto section(
            List<WebsiteBuilderDto.SectionDto> sections, HomepageSectionKey key) {
        return sections.stream().filter(section -> section.key() == key).findFirst().orElseThrow();
    }
}
