package be.enrosed.catalog.application;

import be.enrosed.catalog.adapter.in.rest.WebsiteBuilderDto;
import be.enrosed.catalog.adapter.out.persistence.CanonicalCatalogDaos;
import be.enrosed.catalog.domain.HomepageSectionKey;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@QuarkusTest
class WebsiteBuilderPersistenceTest {
    @Inject WebsiteBuilderService builder;
    @Inject CanonicalCatalogDaos.WebsiteHomepageLayouts rows;

    @Test
    @TestTransaction
    void createsTheSingletonThenPersistsDraftAndPublishedState() {
        rows.deleteAll();
        rows.flush();

        List<WebsiteBuilderDto.SectionDto> draft = List.of(
                section(HomepageSectionKey.HERO, true),
                section(HomepageSectionKey.CATALOG, true),
                section(HomepageSectionKey.RANGE, true),
                section(HomepageSectionKey.ORDER, true),
                section(HomepageSectionKey.COUNTER, true),
                section(HomepageSectionKey.FLOWERBOX, true),
                section(HomepageSectionKey.SOAP, false),
                section(HomepageSectionKey.OCCASION, false),
                section(HomepageSectionKey.RETAIL, true),
                section(HomepageSectionKey.FAQ, true),
                section(HomepageSectionKey.QUOTE, true));

        WebsiteBuilderDto.AdminDto saved = builder.update(
                new WebsiteBuilderDto.UpdateDto(0L, draft));
        WebsiteBuilderDto.AdminDto published = builder.publish(
                new WebsiteBuilderDto.PublishDto(saved.revision()));

        assertEquals(1, saved.revision());
        assertEquals(2, published.revision());
        assertEquals(2, builder.published().revision());
        assertEquals(draft, builder.published().homepage().sections());
        assertNotNull(rows.findById(1L));
    }

    private static WebsiteBuilderDto.SectionDto section(
            HomepageSectionKey key, boolean enabled) {
        return new WebsiteBuilderDto.SectionDto(key, enabled);
    }
}
