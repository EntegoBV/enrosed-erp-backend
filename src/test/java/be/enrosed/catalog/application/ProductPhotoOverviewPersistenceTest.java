package be.enrosed.catalog.application;

import be.enrosed.catalog.adapter.in.rest.ProductFamilyDto;
import be.enrosed.catalog.adapter.in.rest.ProductFamilyResource;
import be.enrosed.catalog.adapter.in.rest.ProductPhotoOverviewDto;
import be.enrosed.catalog.adapter.in.rest.ProductPhotoOverviewDto.Kind;
import be.enrosed.catalog.adapter.in.rest.ProductPhotoOverviewDto.PhotoDto;
import be.enrosed.catalog.adapter.in.rest.ProductPhotoOverviewDto.Role;
import be.enrosed.catalog.adapter.in.rest.ProductPhotoOverviewDto.RoleChoiceDto;
import be.enrosed.catalog.adapter.in.rest.ProductPhotoOverviewDto.Scope;
import be.enrosed.catalog.adapter.in.rest.ProductPhotoOverviewDto.WebsiteReason;
import be.enrosed.catalog.adapter.in.rest.PublicFamilyCatalogDto;
import be.enrosed.catalog.adapter.in.rest.PublicFamilyCatalogResource;
import be.enrosed.catalog.adapter.out.persistence.ProductEntity;
import be.enrosed.catalog.adapter.out.persistence.ProductFamilyEntity;
import be.enrosed.catalog.adapter.out.persistence.ProductFamilyPhotoEntity;
import be.enrosed.catalog.adapter.out.persistence.ProductFamilyTextEntity;
import be.enrosed.catalog.adapter.out.persistence.ProductPhotoEntity;
import be.enrosed.catalog.adapter.out.persistence.ProductTextEntity;
import be.enrosed.catalog.domain.CatalogChannel;
import be.enrosed.catalog.domain.PhotoRole;
import be.enrosed.catalog.domain.PublicationState;
import be.enrosed.shared.BusinessRuleException;
import be.enrosed.shared.Language;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
/* Services are called directly; the family resource read needs the admin identity. */
@io.quarkus.test.security.TestSecurity(user = "emre",
        roles = be.enrosed.shared.security.AdminIdentityProvider.ADMIN_ROLE)
class ProductPhotoOverviewPersistenceTest {
    @Inject EntityManager entityManager;
    @Inject ProductPhotoOverviewService overviews;
    @Inject ProductPhotoCommandService commands;
    @Inject ProductService productService;
    @Inject FamilyPhotoCompatibilityService familyPhotoCompatibility;
    @Inject PublicFamilyCatalogResource publicFamilies;
    @Inject ProductFamilyResource familyResource;
    @Inject WebsiteCatalogRevisionService revisions;
    @Inject com.fasterxml.jackson.databind.ObjectMapper json;

    @Test
    @TestTransaction
    void overviewListsOwnPhotosThenThisColourAllColoursAndOtherColours() {
        Fixture f = fixture("overview-order");
        ProductPhotoEntity own = ownPhoto(f.red(), "overview-own", 0);
        own.sizeBytes = 4096;
        own.widthPx = 1200;
        own.heightPx = 1200;
        own.contentType = "image/jpeg";
        f.global().largeSizeBytes = 4096;
        entityManager.flush();

        ProductPhotoOverviewDto overview = overviews.overview(f.red().id);

        assertEquals(List.of("P" + own.id, "F" + f.redImage().id, "F" + f.global().id,
                "F" + f.blueImage().id), overview.photos().stream().map(PhotoDto::key).toList());
        assertEquals(List.of(Scope.THIS_VARIANT, Scope.THIS_VARIANT, Scope.ALL_VARIANTS,
                Scope.OTHER_VARIANT), overview.photos().stream().map(PhotoDto::scope).toList());
        assertEquals(f.family().id, overview.familyId());
        assertEquals(PublicationState.PUBLISHED, overview.familyWebsiteStatus());
        assertEquals("Red", overview.variantLabel());
        assertEquals("STANDARD", overview.catalogueDetailSize());

        PhotoDto ownDto = overview.photos().get(0);
        assertEquals(Kind.OWN, ownDto.kind());
        assertEquals(own.id, ownDto.productPhotoId());
        assertEquals("F" + f.global().id, ownDto.duplicateOfKey(),
                "same size, dimensions and type as a series original");
        assertFalse(ownDto.publishable());
        assertFalse(ownDto.visibility().website(), "a usable series photo keeps it off the website");
        assertNull(ownDto.websiteReason());
        assertEquals(0, ownDto.ownPosition());
        assertEquals("/api/products/" + f.red().id + "/photos/" + own.id + "/renditions/small",
                ownDto.smallUrl());
        assertEquals("/api/products/" + f.red().id + "/photos/" + own.id + "/download",
                ownDto.downloadUrl());

        PhotoDto redDto = overview.photos().get(1);
        assertEquals(Kind.SERIES, redDto.kind());
        assertNotNull(redDto.productPhotoId(), "this colour's projection row");
        assertTrue(redDto.visibility().website());
        assertEquals(WebsiteReason.PUBLISHED, redDto.websiteReason());
        assertTrue(redDto.publishable());
        assertEquals(List.of(Role.MAIN, Role.QUOTE), redDto.roles());
        assertEquals("Red", redDto.variantLabel());
        assertEquals(f.red().id, redDto.variantProductId());
        assertEquals("/api/product-families/" + f.family().id + "/images/" + f.redImage().id + "/small",
                redDto.smallUrl());
        assertEquals(0, redDto.familyPosition());

        PhotoDto otherColour = overview.photos().get(3);
        assertNull(otherColour.productPhotoId());
        assertEquals("Blue", otherColour.variantLabel());
        assertFalse(otherColour.visibility().website());
        assertNull(otherColour.websiteReason());
        assertTrue(otherColour.publishable());
        assertNull(overview.photos().get(2).variantLabel(), "a photo for all colours has no colour");

        assertEquals(new RoleChoiceDto("F" + f.redImage().id, false), overview.main());
        assertEquals(new RoleChoiceDto("F" + f.redImage().id, false), overview.quote());
        assertNull(overview.catalogueVariant());
        assertNull(overview.catalogueOverview());
        assertNull(overview.catalogueDetail());
    }

    @Test
    @TestTransaction
    void seriesPhotosCarryTheirStoredChannelsSoASwitchNeverDropsOne() {
        Fixture f = fixture("overview-channels");
        ProductPhotoEntity own = ownPhoto(f.red(), "overview-channels-own", 0);
        /* Selected for the catalogue, but the projection hides it while its colour is inactive. */
        f.blueImage().publishedChannelsJson = "[\"WEBSITE\",\"CATALOGUE\"]";
        f.blue().active = false;
        ProductFamilyPhotoEntity legacy = photo(f.family(), "overview-channels-legacy", 3);
        legacy.altTextsJson = "[{\"language\":\"EN\",\"alt\":\"Legacy rose\"}]";
        ProductFamilyPhotoEntity legacyWithoutAlt = photo(f.family(), "overview-channels-no-alt", 4);
        entityManager.persist(legacy);
        entityManager.persist(legacyWithoutAlt);
        entityManager.flush();

        ProductPhotoOverviewDto overview = overviews.overview(f.red().id);

        assertNull(photo(overview, "P" + own.id).publishedChannels(), "own photos have no channel choice");
        assertEquals(List.of(CatalogChannel.WEBSITE), photo(overview, "F" + f.redImage().id).publishedChannels());
        PhotoDto hidden = photo(overview, "F" + f.blueImage().id);
        assertEquals(List.of(CatalogChannel.WEBSITE, CatalogChannel.CATALOGUE), hidden.publishedChannels());
        assertFalse(hidden.visibility().website() || hidden.visibility().catalogue());
        assertEquals(List.of(CatalogChannel.WEBSITE, CatalogChannel.ORDER_APP, CatalogChannel.CATALOGUE),
                photo(overview, "F" + legacy.id).publishedChannels(), "legacy null is every channel");
        assertEquals(List.of(), photo(overview, "F" + legacyWithoutAlt.id).publishedChannels(),
                "a legacy row without alt was never public and stays internal");

        com.fasterxml.jackson.databind.JsonNode tree = json.valueToTree(overview);
        assertEquals("WEBSITE", tree.path("photos").get(1).path("publishedChannels").get(0).asText());
        assertTrue(tree.path("photos").get(0).has("publishedChannels"));
    }

    @Test
    @TestTransaction
    void automaticMainIsThePhotoQuotesAndInvoicesPrint() {
        Fixture f = fixture("main-documents");
        ProductFamilyPhotoEntity catalogueFirst = photo(f.family(), "main-documents-catalogue", 0);
        catalogueFirst.variantProduct = f.red();
        catalogueFirst.publishedChannelsJson = "[\"CATALOGUE\"]";
        f.redImage().position = 1;
        f.global().position = 2;
        f.blueImage().position = 3;
        entityManager.persist(catalogueFirst);
        entityManager.flush();
        familyPhotoCompatibility.sync(f.family());
        entityManager.flush();
        entityManager.clear();

        ProductPhotoOverviewDto overview = overviews.overview(f.red().id);

        assertEquals(new RoleChoiceDto("F" + catalogueFirst.id, false), overview.main());
        assertEquals(catalogueFirst.id, productService.get(f.red().id).photoForSalesDocument().familyPhotoId(),
                "the Hoofdfoto row shows what the quote and invoice print");
        assertEquals(List.of(Role.MAIN), photo(overview, "F" + catalogueFirst.id).roles());
        assertEquals(f.redImage().id, variant(publicFamily(f.family().publicHandle), f.red().id).primaryImageId(),
                "the website still skips a photo it does not publish");
    }

    @Test
    @TestTransaction
    void productWithoutAFamilyOnlyShowsItsOwnPhotos() {
        ProductEntity loose = product(null, "SKU-LOOSE-PHOTOS", "Groen", 0);
        entityManager.persist(loose);
        ProductPhotoEntity own = ownPhoto(loose, "loose-own", 0);
        entityManager.flush();

        ProductPhotoOverviewDto overview = overviews.overview(loose.id);

        assertNull(overview.familyId());
        assertNull(overview.familyName());
        assertNull(overview.familyWebsiteStatus());
        assertNull(overview.quote());
        assertNull(overview.catalogueDetailSize());
        assertEquals(List.of("P" + own.id), overview.photos().stream().map(PhotoDto::key).toList());
        assertNull(overview.photos().getFirst().duplicateOfKey());
        assertEquals(new RoleChoiceDto("P" + own.id, false), overview.main());
    }

    @Test
    @TestTransaction
    void mainOnAnUnpublishedSeriesPhotoPublishesItForTheWebsiteAndLeadsThisColourOnly() {
        Fixture f = fixture("main-role");
        ProductFamilyPhotoEntity catalogueOnly = photo(f.family(), "main-catalogue-only", 3);
        catalogueOnly.publishedChannelsJson = "[\"CATALOGUE\"]";
        entityManager.persist(catalogueOnly);
        entityManager.flush();
        familyPhotoCompatibility.sync(f.family());

        ProductPhotoOverviewDto after = commands.setRole(
                f.red().id, Role.MAIN, "F" + catalogueOnly.id);

        assertEquals("[\"WEBSITE\",\"CATALOGUE\"]", catalogueOnly.publishedChannelsJson,
                "published for the website, other channels kept");
        assertEquals(new RoleChoiceDto("F" + catalogueOnly.id, true), after.main());
        ProductPhotoEntity projection = f.red().photos.stream()
                .filter(photo -> catalogueOnly.id.equals(photo.familyPhotoId)).findFirst().orElseThrow();
        assertEquals("WEBSITE", projection.leadRoles);
        PublicFamilyCatalogDto.FamilyDto website = publicFamily(f.family().publicHandle);
        assertEquals(catalogueOnly.id, variant(website, f.red().id).primaryImageId());
        assertEquals(f.global().id, variant(website, f.blue().id).primaryImageId(),
                "another colour keeps its own first photo");

        ProductPhotoOverviewDto automatic = commands.setRole(f.red().id, Role.MAIN, null);
        assertEquals(new RoleChoiceDto("F" + f.redImage().id, false), automatic.main());
        assertNull(projection.leadRoles);

        BusinessRuleException otherColour = assertThrows(BusinessRuleException.class,
                () -> commands.setRole(f.red().id, Role.MAIN, "F" + f.blueImage().id));
        assertTrue(otherColour.getMessage().contains("andere kleur"), otherColour.getMessage());
    }

    @Test
    @TestTransaction
    void quoteChoiceIsExplicitThenFallsBackToAutomaticWhenItLeavesTheWebsite() {
        Fixture f = fixture("quote-role");
        PublicFamilyCatalogDto.FamilyDto automatic = publicFamily(f.family().publicHandle);
        assertEquals(f.redImage().id, automatic.quoteImageId(), "first colour's website primary");
        assertQuoteIsAGalleryImage(automatic);

        ProductPhotoOverviewDto chosen = commands.setRole(
                f.blue().id, Role.QUOTE, "F" + f.global().id);
        assertEquals(new RoleChoiceDto("F" + f.global().id, true), chosen.quote());
        assertEquals(f.global().id, f.family().websiteQuotePhotoId);
        PublicFamilyCatalogDto.FamilyDto explicit = publicFamily(f.family().publicHandle);
        assertEquals(f.global().id, explicit.quoteImageId());
        assertQuoteIsAGalleryImage(explicit);
        ProductFamilyDto admin = familyResource.get(f.family().id);
        assertEquals(f.global().id, admin.websiteQuotePhotoId());
        assertEquals(f.global().id, admin.effectiveWebsiteQuotePhotoId());

        commands.setRole(f.red().id, Role.QUOTE, "F" + f.blueImage().id);
        assertEquals("[\"WEBSITE\"]", f.blueImage().publishedChannelsJson,
                "an internal photo of another colour is published for the website first");
        assertEquals(f.blueImage().id, publicFamily(f.family().publicHandle).quoteImageId());

        f.blueImage().publishedChannelsJson = "[]";
        entityManager.flush();
        PublicFamilyCatalogDto.FamilyDto stale = publicFamily(f.family().publicHandle);
        assertEquals(f.redImage().id, stale.quoteImageId(), "a hidden choice falls back to automatic");
        assertQuoteIsAGalleryImage(stale);
        ProductFamilyDto staleAdmin = familyResource.get(f.family().id);
        assertEquals(f.blueImage().id, staleAdmin.websiteQuotePhotoId());
        assertEquals(f.redImage().id, staleAdmin.effectiveWebsiteQuotePhotoId());
        assertEquals(new RoleChoiceDto("F" + f.redImage().id, false),
                overviews.overview(f.red().id).quote());

        commands.setRole(f.red().id, Role.QUOTE, null);
        assertNull(f.family().websiteQuotePhotoId);
    }

    @Test
    @TestTransaction
    void theGeneralFamilySaveNeverClearsTheQuoteChoice() throws Exception {
        Fixture f = fixture("quote-general-save");
        commands.setRole(f.red().id, Role.QUOTE, "F" + f.global().id);
        com.fasterxml.jackson.databind.node.ObjectNode body = json.valueToTree(familyResource.get(f.family().id));
        body.putNull("websiteQuotePhotoId");
        body.putNull("effectiveWebsiteQuotePhotoId");

        ProductFamilyDto saved = familyResource.update(
                f.family().id, json.treeToValue(body, ProductFamilyDto.class));

        assertEquals(f.global().id, saved.websiteQuotePhotoId());
        assertEquals(f.global().id, f.family().websiteQuotePhotoId);
    }

    @Test
    @TestTransaction
    void overviewJsonUsesTheAgreedFieldNames() throws Exception {
        Fixture f = fixture("overview-json");
        ProductPhotoEntity own = ownPhoto(f.red(), "overview-json-own", 0);
        entityManager.flush();

        com.fasterxml.jackson.databind.JsonNode tree = json.valueToTree(overviews.overview(f.red().id));

        assertEquals(f.red().id.longValue(), tree.path("productId").asLong());
        assertEquals("PUBLISHED", tree.path("familyWebsiteStatus").asText());
        com.fasterxml.jackson.databind.JsonNode first = tree.path("photos").get(0);
        assertEquals("P" + own.id, first.path("key").asText());
        assertEquals("OWN", first.path("kind").asText());
        assertEquals("THIS_VARIANT", first.path("scope").asText());
        for (String field : List.of("familyPhotoId", "productPhotoId", "variantProductId", "variantLabel",
                "originalFilename", "contentType", "widthPx", "heightPx", "sizeBytes", "smallUrl",
                "mediumUrl", "largeUrl", "downloadUrl", "visibility", "websiteReason", "publishable",
                "roles", "duplicateOfKey", "familyPosition", "ownPosition")) {
            assertTrue(first.has(field), field);
        }
        assertTrue(first.path("visibility").has("website"));
        assertTrue(first.path("visibility").has("catalogue"));
        assertTrue(first.path("visibility").has("orderApp"));
        assertEquals("F" + f.redImage().id, tree.path("main").path("key").asText());
        assertFalse(tree.path("main").path("explicit").asBoolean());
        for (String field : List.of("quote", "catalogueVariant", "catalogueOverview",
                "catalogueDetail", "catalogueDetailSize", "familyId", "familyName", "variantLabel")) {
            assertTrue(tree.has(field), field);
        }
    }

    @Test
    @TestTransaction
    void deletingTheChosenQuotePhotoLeavesNoDanglingChoice() {
        Fixture f = fixture("quote-delete");
        ProductPhotoEntity own = ownPhoto(f.red(), "quote-delete-own", 0);
        entityManager.flush();
        productService.setPhotoLead(f.red().id, own.id, PhotoRole.WEBSITE, true);
        commands.setRole(f.red().id, Role.QUOTE, "P" + own.id);
        assertEquals(-own.id, f.family().websiteQuotePhotoId);

        productService.removePhoto(f.red().id, own.id);
        assertNull(f.family().websiteQuotePhotoId);

        commands.setRole(f.red().id, Role.QUOTE, "F" + f.global().id);
        familyResource.deleteImage(f.family().id, f.global().id);
        assertNull(f.family().websiteQuotePhotoId);
        assertEquals(f.redImage().id, publicFamily(f.family().publicHandle).quoteImageId());
    }

    @Test
    @TestTransaction
    void changingTheQuoteChoiceChangesTheWebsiteRevision() {
        Fixture f = fixture("quote-revision");
        String before = revisions.currentRevision();

        commands.setRole(f.red().id, Role.QUOTE, "F" + f.global().id);

        assertFalse(before.equals(revisions.currentRevision()));
    }

    @Test
    @TestTransaction
    void quoteRefusesAnOwnPhotoThatIsNotOnTheWebsite() {
        Fixture f = fixture("quote-own-private");
        ProductPhotoEntity own = ownPhoto(f.red(), "quote-own-private", 0);
        entityManager.flush();

        BusinessRuleException error = assertThrows(BusinessRuleException.class,
                () -> commands.setRole(f.red().id, Role.QUOTE, "P" + own.id));

        assertEquals("Deze losse foto staat niet op de website. Zet hem eerst in de reeks.",
                error.getMessage());
    }

    @Test
    @TestTransaction
    void catalogueRolesPublishForTheCatalogueAndStoreTheSignedChoice() {
        Fixture f = fixture("catalogue-roles");
        ProductPhotoEntity own = ownPhoto(f.red(), "catalogue-own", 0);
        entityManager.flush();

        ProductPhotoOverviewDto overview = commands.setRole(
                f.red().id, Role.CATALOGUE_OVERVIEW, "F" + f.blueImage().id);
        assertEquals("[\"CATALOGUE\"]", f.blueImage().publishedChannelsJson);
        assertEquals(f.blueImage().id, f.family().catalogueOverviewPhotoId);
        assertEquals(new RoleChoiceDto("F" + f.blueImage().id, true), overview.catalogueOverview());
        assertTrue(photo(overview, "F" + f.blueImage().id).roles().contains(Role.CATALOGUE_OVERVIEW));

        ProductPhotoOverviewDto detail = commands.setRole(
                f.red().id, Role.CATALOGUE_DETAIL, "P" + own.id);
        assertEquals(-own.id, f.family().catalogueDetailPhotoId);
        assertEquals(new RoleChoiceDto("P" + own.id, true), detail.catalogueDetail());

        ProductPhotoOverviewDto variantLead = commands.setRole(
                f.red().id, Role.CATALOGUE_VARIANT, "P" + own.id);
        assertEquals(new RoleChoiceDto("P" + own.id, true), variantLead.catalogueVariant());
        assertEquals("CATALOGUE", entityManager.find(ProductPhotoEntity.class, own.id).leadRoles);

        assertNull(commands.setRole(f.red().id, Role.CATALOGUE_VARIANT, null).catalogueVariant());
        assertNull(commands.setRole(f.red().id, Role.CATALOGUE_DETAIL, null).catalogueDetail());
        assertNull(f.family().catalogueDetailPhotoId);
    }

    @Test
    @TestTransaction
    void promoteMovesLeadsAndChoicesToTheNewSeriesPhotoAndRemovesTheOwnCopy() throws Exception {
        Fixture f = fixture("promote-own");
        byte[] png = png(40, 30, Color.RED);
        long ownId = ownUpload(f.red(), "rose.png", png);
        productService.setPhotoLead(f.red().id, ownId, PhotoRole.WEBSITE, true);
        productService.setPhotoLead(f.red().id, ownId, PhotoRole.CATALOGUE, true);
        f.family().catalogueOverviewPhotoId = -ownId;
        f.family().websiteQuotePhotoId = -ownId;
        entityManager.flush();
        assertEquals(-ownId, publicFamily(f.family().publicHandle).quoteImageId(),
                "the own photo reaches the website through its lead");

        ProductPhotoOverviewDto after = commands.promote(f.red().id, "P" + ownId, Scope.THIS_VARIANT);

        ProductFamilyPhotoEntity series = f.family().photos.stream()
                .filter(photo -> ("admin-" + FamilyPhotoUploadService.sha256(png)).equals(photo.sourceKey))
                .findFirst().orElseThrow();
        assertEquals(f.red().id, series.variantProduct.id);
        assertEquals("[\"WEBSITE\",\"ORDER_APP\",\"CATALOGUE\"]", series.publishedChannelsJson,
                "every channel that showed the own photo keeps showing it");
        assertEquals("[]", series.altTextsJson);
        assertNull(entityManager.find(ProductPhotoEntity.class, ownId));
        ProductPhotoEntity projection = f.red().photos.stream()
                .filter(photo -> series.id.equals(photo.familyPhotoId)).findFirst().orElseThrow();
        assertEquals("CATALOGUE,WEBSITE", projection.leadRoles);
        assertEquals(series.id, f.family().catalogueOverviewPhotoId);
        assertEquals(series.id, f.family().websiteQuotePhotoId);
        assertEquals(new RoleChoiceDto("F" + series.id, true), after.main());
        assertEquals(new RoleChoiceDto("F" + series.id, true), after.quote());
        assertTrue(after.photos().stream().noneMatch(photo -> photo.kind() == Kind.OWN));
        PublicFamilyCatalogDto.FamilyDto website = publicFamily(f.family().publicHandle);
        assertEquals(series.id, website.quoteImageId());
        assertEquals(series.id, variant(website, f.red().id).primaryImageId());
        assertTrue(website.images().stream().noneMatch(image -> image.id() < 0),
                "no own-photo duplicate left in the public gallery");

        long blueOwnId = ownUpload(f.blue(), "rose-copy.png", png);
        int seriesCount = f.family().photos.size();
        commands.promote(f.blue().id, "P" + blueOwnId, Scope.THIS_VARIANT);
        assertEquals(seriesCount, f.family().photos.size(), "the same bytes stay one series photo");
        assertNull(series.variantProduct, "widened to all colours so red keeps it too");
        assertTrue(f.blue().photos.stream().anyMatch(photo -> series.id.equals(photo.familyPhotoId)));

        BusinessRuleException duplicate = assertThrows(BusinessRuleException.class,
                () -> productService.addPhoto(f.red().id, "again.png", new ByteArrayInputStream(png)));
        assertEquals("Deze foto staat al bij de reeksfoto's van dit product.", duplicate.getMessage());
    }

    @Test
    @TestTransaction
    void cleaningADuplicateReusesTheImportedSeriesPhotoWithTheSameOriginal() throws Exception {
        Fixture f = fixture("promote-duplicate");
        byte[] png = png(40, 30, Color.BLUE);
        String sha = FamilyPhotoUploadService.sha256(png);
        ProductFamilyPhotoEntity imported = photo(f.family(), "legacy-import-dome", 3);
        imported.largeSha256 = sha;
        imported.largeStorageKey = "sha256-" + sha + ".png";
        imported.largeContentType = "image/png";
        imported.largeSizeBytes = png.length;
        imported.largeWidthPx = 40;
        imported.largeHeightPx = 30;
        imported.variantProduct = f.blue();
        entityManager.persist(imported);
        entityManager.flush();
        familyPhotoCompatibility.sync(f.family());
        long ownId = ownUpload(f.red(), "re-upload.png", png);
        assertEquals("F" + imported.id, photo(overviews.overview(f.red().id), "P" + ownId).duplicateOfKey());

        ProductPhotoOverviewDto after = commands.promote(f.red().id, "P" + ownId, Scope.THIS_VARIANT);

        assertEquals(4, f.family().photos.size(), "no second series photo for the same bytes");
        assertNull(imported.variantProduct, "blue keeps it; red now has it as well");
        assertTrue(after.photos().stream().noneMatch(photo -> photo.kind() == Kind.OWN));
        assertEquals(Scope.ALL_VARIANTS, photo(after, "F" + imported.id).scope());
    }

    @Test
    @TestTransaction
    void promoteNeedsAFamily() {
        ProductEntity loose = product(null, "SKU-LOOSE-PROMOTE", "Groen", 0);
        entityManager.persist(loose);
        ProductPhotoEntity own = ownPhoto(loose, "loose-promote", 0);
        entityManager.flush();

        BusinessRuleException error = assertThrows(BusinessRuleException.class,
                () -> commands.promote(loose.id, "P" + own.id, Scope.ALL_VARIANTS));

        assertTrue(error.getMessage().contains("reeks"), error.getMessage());
    }

    private long ownUpload(ProductEntity product, String filename, byte[] bytes) {
        return productService.addPhoto(product.id, filename, new ByteArrayInputStream(bytes))
                .photos().stream().filter(photo -> !photo.inherited())
                .filter(photo -> photo.originalFilename().equals(filename))
                .findFirst().orElseThrow().id();
    }

    private static void assertQuoteIsAGalleryImage(PublicFamilyCatalogDto.FamilyDto family) {
        assertTrue(family.images().stream().anyMatch(image -> image.id().equals(family.quoteImageId())),
                "quoteImageId " + family.quoteImageId() + " must be one of the family images");
    }

    private static PhotoDto photo(ProductPhotoOverviewDto overview, String key) {
        return overview.photos().stream().filter(photo -> photo.key().equals(key)).findFirst().orElseThrow();
    }

    private PublicFamilyCatalogDto.FamilyDto publicFamily(String handle) {
        Response response = publicFamilies.catalog(CatalogChannel.WEBSITE, "EN", null);
        PublicFamilyCatalogDto catalog = (PublicFamilyCatalogDto) response.getEntity();
        return catalog.families().stream()
                .filter(item -> handle.equals(item.publicHandle())).findFirst().orElseThrow();
    }

    private static PublicFamilyCatalogDto.VariantDto variant(
            PublicFamilyCatalogDto.FamilyDto family, long productId) {
        return family.variants().stream().filter(item -> item.id() == productId)
                .findFirst().orElseThrow();
    }

    private record Fixture(ProductFamilyEntity family, ProductEntity red, ProductEntity blue,
                           ProductFamilyPhotoEntity redImage, ProductFamilyPhotoEntity global,
                           ProductFamilyPhotoEntity blueImage) {}

    /** Red and blue colours; a website photo of red, a website photo for all, an internal blue one. */
    private Fixture fixture(String key) {
        ProductFamilyEntity family = new ProductFamilyEntity();
        family.familyKey = key;
        family.publicHandle = key;
        family.active = true;
        family.name = "Photo family";
        family.websiteStatus = PublicationState.PUBLISHED;
        for (Language language : Language.values()) {
            ProductFamilyTextEntity text = new ProductFamilyTextEntity();
            text.family = family;
            text.language = language;
            text.name = "Family " + language.code();
            text.summary = "Summary " + language.code();
            text.description = "Description " + language.code();
            text.highlightsJson = "[]";
            family.texts.add(text);
        }
        entityManager.persist(family);
        entityManager.flush();
        ProductEntity red = product(family, "SKU-" + key.toUpperCase() + "-RED", "Red", 0);
        ProductEntity blue = product(family, "SKU-" + key.toUpperCase() + "-BLUE", "Blue", 1);
        entityManager.persist(red);
        entityManager.persist(blue);
        ProductFamilyPhotoEntity redImage = photo(family, key + "-red", 0);
        redImage.variantProduct = red;
        redImage.publishedChannelsJson = "[\"WEBSITE\"]";
        ProductFamilyPhotoEntity global = photo(family, key + "-global", 1);
        global.publishedChannelsJson = "[\"WEBSITE\"]";
        ProductFamilyPhotoEntity blueImage = photo(family, key + "-blue", 2);
        blueImage.variantProduct = blue;
        blueImage.publishedChannelsJson = "[]";
        entityManager.persist(redImage);
        entityManager.persist(global);
        entityManager.persist(blueImage);
        entityManager.flush();
        familyPhotoCompatibility.sync(family);
        return new Fixture(family, red, blue, redImage, global, blueImage);
    }

    private static ProductEntity product(ProductFamilyEntity family, String sku, String colour, int position) {
        ProductEntity product = new ProductEntity();
        product.sku = sku;
        product.name = "Variant " + colour;
        product.familyId = family == null ? null : family.id;
        product.familyKey = family == null ? null : family.familyKey;
        product.canonicalVariantKey = sku.toLowerCase();
        product.colour = colour;
        product.colourHex = "#A91F32";
        product.variantPosition = position;
        product.active = true;
        product.piecesPerCarton = 1;
        product.fixedSalesPriceEur = BigDecimal.TEN;
        for (Language language : Language.values()) {
            ProductTextEntity text = new ProductTextEntity();
            text.product = product;
            text.language = language;
            text.name = "Variant " + language.code();
            text.colour = colour + " " + language.code();
            product.texts.add(text);
        }
        return product;
    }

    private ProductPhotoEntity ownPhoto(ProductEntity product, String storageKey, int position) {
        ProductPhotoEntity photo = new ProductPhotoEntity();
        photo.product = product;
        photo.storageKey = storageKey;
        photo.originalFilename = storageKey + ".jpg";
        photo.contentType = "image/jpeg";
        photo.sizeBytes = 1;
        photo.widthPx = 1;
        photo.heightPx = 1;
        photo.position = position;
        entityManager.persist(photo);
        product.photos.add(photo);
        return photo;
    }

    private static ProductFamilyPhotoEntity photo(
            ProductFamilyEntity family, String sourceKey, int position) {
        ProductFamilyPhotoEntity photo = new ProductFamilyPhotoEntity();
        photo.family = family;
        photo.sourceKey = sourceKey;
        photo.originalFilename = sourceKey + ".jpg";
        photo.smallStorageKey = sourceKey + "-small";
        photo.largeStorageKey = sourceKey + "-large";
        photo.smallContentType = "image/jpeg";
        photo.largeContentType = "image/jpeg";
        photo.smallWidthPx = 320;
        photo.smallHeightPx = 320;
        photo.largeWidthPx = 1200;
        photo.largeHeightPx = 1200;
        photo.position = position;
        photo.altTextsJson = "[]";
        family.photos.add(photo);
        return photo;
    }

    private static byte[] png(int width, int height, Color colour) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D graphics = image.createGraphics();
        graphics.setColor(colour);
        graphics.fillRect(0, 0, width, height);
        graphics.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }
}
