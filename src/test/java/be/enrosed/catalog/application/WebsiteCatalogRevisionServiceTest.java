package be.enrosed.catalog.application;

import be.enrosed.catalog.adapter.out.persistence.CanonicalCatalogDaos;
import be.enrosed.catalog.adapter.out.persistence.CatalogDaos;
import be.enrosed.catalog.adapter.out.persistence.ContentTranslationEntity;
import be.enrosed.catalog.adapter.out.persistence.ContentTranslationTextEntity;
import be.enrosed.catalog.adapter.out.persistence.CategoryEntity;
import be.enrosed.catalog.adapter.out.persistence.ProductEntity;
import be.enrosed.catalog.adapter.out.persistence.ProductCollectionEntity;
import be.enrosed.catalog.adapter.out.persistence.ProductFamilyCollectionEntity;
import be.enrosed.catalog.adapter.out.persistence.ProductFamilyEntity;
import be.enrosed.catalog.adapter.out.persistence.ProductFamilyPhotoEntity;
import be.enrosed.catalog.adapter.out.persistence.WebsiteHomepageLayoutEntity;
import be.enrosed.catalog.domain.ContentScope;
import be.enrosed.shared.Language;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WebsiteCatalogRevisionServiceTest {

    @Test
    void publicIdentityChangesAlterTheRevisionAfterAnIdentityChangingReset() {
        String before = service(graph(10L, 20L, 30L,
                Instant.parse("2026-08-21T10:00:00Z"), "import-a")).currentRevision();
        String after = service(graph(11L, 21L, 31L,
                Instant.parse("2026-08-21T10:00:00Z"), "import-a")).currentRevision();

        assertNotEquals(before, after,
                "public family, product and image ids are part of the serialized contract");
    }

    @Test
    void siteCopyRevisionIsCoveredButInternalImportMetadataIsNot() {
        String initial = service(graph(10L, 20L, 30L,
                Instant.parse("2026-08-21T10:00:00Z"), "internal-a")).currentRevision();
        String copyRevisionChanged = service(graph(10L, 20L, 30L,
                Instant.parse("2026-08-21T10:00:01Z"), "internal-a")).currentRevision();
        String internalOnlyChanged = service(graph(10L, 20L, 30L,
                Instant.parse("2026-08-21T10:00:00Z"), "internal-b")).currentRevision();

        assertNotEquals(initial, copyRevisionChanged,
                "siteCopyRevision is returned by every public family response");
        assertEquals(initial, internalOnlyChanged,
                "migration audit metadata must not cause a public rebuild");
    }

    @Test
    void equalPositionCollectionMembershipsHaveADeterministicTieBreaker() {
        Graph graph = graph(10L, 20L, 30L,
                Instant.parse("2026-08-21T10:00:00Z"), "internal-a");
        ProductCollectionEntity second = new ProductCollectionEntity();
        second.id = 2011L;
        second.collectionKey = "secondary";
        second.name = "Secondary";
        ProductFamilyCollectionEntity membership = new ProductFamilyCollectionEntity();
        membership.id = 3011L;
        membership.family = graph.family();
        membership.collection = second;
        membership.position = graph.family().collections.getFirst().position;
        graph.family().collections.add(membership);

        String first = service(graph).currentRevision();
        java.util.Collections.reverse(graph.family().collections);
        String reversed = service(graph).currentRevision();

        assertEquals(first, reversed,
                "database return order must not affect equal-position collection memberships");
    }

    @Test
    void publicProductNameChangesAlterTheWebsiteRevision() {
        Graph graph = graph(10L, 20L, 30L,
                Instant.parse("2026-08-21T10:00:00Z"), "internal-a");
        graph.product().publicName = "Public rose";
        String before = service(graph).currentRevision();

        graph.product().publicName = "Public counter rose";
        String after = service(graph).currentRevision();

        assertNotEquals(before, after);
    }

    @Test
    void internalDocumentNameDoesNotAlterRevisionAfterPublicCopyDiverged() {
        Graph graph = graph(10L, 20L, 30L,
                Instant.parse("2026-08-21T10:00:00Z"), "internal-a");
        graph.product().publicName = "Public rose";
        String before = service(graph).currentRevision();

        graph.product().name = "Changed invoice line";
        String after = service(graph).currentRevision();

        assertEquals(before, after,
                "document-only copy must not trigger a public website rebuild");
    }

    @Test
    void homepageDraftIsExcludedButPublishedLayoutIsCoveredByTheWebsiteRevision()
            throws Exception {
        Graph graph = graph(10L, 20L, 30L,
                Instant.parse("2026-08-21T10:00:00Z"), "internal-a");
        ObjectMapper json = new ObjectMapper();
        WebsiteHomepageLayoutEntity homepage = new WebsiteHomepageLayoutEntity();
        homepage.publishedRevision = 0;
        homepage.publishedSectionsJson = json.writeValueAsString(
                WebsiteBuilderService.defaultSections());
        homepage.draftSectionsJson = "[{\"key\":\"draft-only\",\"enabled\":false}]";

        String absentDefault = service(graph).currentRevision();
        String draftOnly = service(graph, homepage).currentRevision();
        assertEquals(absentDefault, draftOnly,
                "creating or editing draft state must not alter the public digest");

        homepage.publishedRevision = 2;
        homepage.publishedSectionsJson = homepage.publishedSectionsJson
                .replace("\"key\":\"catalog\",\"enabled\":false",
                        "\"key\":\"catalog\",\"enabled\":true");
        String published = service(graph, homepage).currentRevision();

        assertNotEquals(draftOnly, published,
                "a published layout must start a new website delivery revision");
    }

    private static WebsiteCatalogRevisionService service(Graph graph) {
        return service(graph, null);
    }

    private static WebsiteCatalogRevisionService service(
            Graph graph, WebsiteHomepageLayoutEntity homepage) {
        CanonicalCatalogDaos.ContentTranslations content = mock(
                CanonicalCatalogDaos.ContentTranslations.class);
        CanonicalCatalogDaos.Families families = mock(CanonicalCatalogDaos.Families.class);
        CatalogDaos.Products products = mock(CatalogDaos.Products.class);
        CatalogDaos.Categories categories = mock(CatalogDaos.Categories.class);
        CanonicalCatalogDaos.PriceObservations prices = mock(
                CanonicalCatalogDaos.PriceObservations.class);
        CanonicalCatalogDaos.DimensionObservations dimensions = mock(
                CanonicalCatalogDaos.DimensionObservations.class);
        CanonicalCatalogDaos.WebsiteHomepageLayouts homepageLayouts = mock(
                CanonicalCatalogDaos.WebsiteHomepageLayouts.class);
        when(homepageLayouts.findById(1L)).thenReturn(homepage);
        when(content.list("scope = ?1 order by key", ContentScope.WEBSITE))
                .thenReturn(List.of(graph.copy()));
        when(categories.listAll()).thenReturn(List.of(graph.category()));
        when(families.listAll()).thenReturn(List.of(graph.family()));
        when(products.list("familyId = ?1 order by variantPosition, canonicalVariantKey, sku",
                graph.family().id)).thenReturn(List.of(graph.product()));
        when(prices.list("productId = ?1 and publicPrice = true order by publicRole, context, id",
                graph.product().id)).thenReturn(List.of());
        when(dimensions.list("familyId = ?1 order by position, id", graph.family().id))
                .thenReturn(List.of());
        return new WebsiteCatalogRevisionService(
                content, families, products, categories, prices, dimensions,
                homepageLayouts, new PublicProductNameResolver(), new ObjectMapper());
    }

    private static Graph graph(
            long familyId, long productId, long imageId,
            Instant copyUpdatedAt, String lastImportKey) {
        ContentTranslationEntity copy = new ContentTranslationEntity();
        copy.scope = ContentScope.WEBSITE;
        copy.key = "test.heading";
        copy.required = true;
        copy.system = true;
        copy.updatedAt = copyUpdatedAt;
        ContentTranslationTextEntity copyText = new ContentTranslationTextEntity();
        copyText.owner = copy;
        copyText.language = Language.EN;
        copyText.value = "Heading";
        copy.texts.add(copyText);

        ProductFamilyEntity family = new ProductFamilyEntity();
        family.id = familyId;
        family.familyKey = "family-key";
        family.publicHandle = "family-handle";
        family.name = "Family";
        family.highlightsJson = "[]";
        family.tagsJson = "[]";
        family.cardFeaturedProductId = productId;
        family.lastImportKey = lastImportKey;

        CategoryEntity category = new CategoryEntity();
        category.id = familyId + 1000;
        category.code = "display-roses";
        category.name = "Displays";
        category.featuredProductId = productId;

        ProductCollectionEntity collection = new ProductCollectionEntity();
        collection.id = familyId + 2000;
        collection.collectionKey = "display-roses";
        collection.name = "Displays";
        collection.featuredProductId = productId;
        ProductFamilyCollectionEntity membership = new ProductFamilyCollectionEntity();
        membership.id = familyId + 3000;
        membership.family = family;
        membership.collection = collection;
        membership.primaryCollection = true;
        family.collections.add(membership);

        ProductEntity product = new ProductEntity();
        product.id = productId;
        product.familyId = familyId;
        product.canonicalVariantKey = "family-red";
        product.sku = "SKU-RED";
        product.name = "Red";

        ProductFamilyPhotoEntity image = new ProductFamilyPhotoEntity();
        image.id = imageId;
        image.family = family;
        image.sourceKey = "hero";
        image.position = 0;
        image.variantProduct = product;
        image.altTextsJson = "[]";
        family.photos.add(image);
        return new Graph(copy, category, family, product);
    }

    private record Graph(
            ContentTranslationEntity copy,
            CategoryEntity category,
            ProductFamilyEntity family,
            ProductEntity product) {}
}
