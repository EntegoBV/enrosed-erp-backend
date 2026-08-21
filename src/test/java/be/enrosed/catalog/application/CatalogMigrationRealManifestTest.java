package be.enrosed.catalog.application;

import be.enrosed.catalog.adapter.in.rest.CanonicalCatalogManifest;
import be.enrosed.catalog.adapter.in.rest.CatalogMigrationApplyRequest;
import be.enrosed.catalog.adapter.in.rest.CatalogMigrationPreflight;
import be.enrosed.catalog.adapter.in.rest.CatalogMigrationResult;
import be.enrosed.catalog.adapter.in.rest.ProductDto;
import be.enrosed.catalog.adapter.in.rest.ProductFamilyDto;
import be.enrosed.catalog.adapter.in.rest.PublicFamilyCatalogDto;
import be.enrosed.catalog.adapter.in.rest.PublicFamilyCatalogResource;
import be.enrosed.catalog.adapter.out.persistence.CatalogDaos;
import be.enrosed.catalog.adapter.out.persistence.CanonicalCatalogDaos;
import be.enrosed.catalog.adapter.out.persistence.ProductEntity;
import be.enrosed.catalog.adapter.out.persistence.CategoryEntity;
import be.enrosed.catalog.adapter.out.persistence.CategoryTextEntity;
import be.enrosed.shared.Language;
import be.enrosed.catalog.domain.CatalogChannel;
import be.enrosed.catalog.domain.PublicationState;
import be.enrosed.sales.adapter.out.persistence.SalesEntities;
import be.enrosed.shared.company.CompanyProfileEntity;
import be.enrosed.sourcing.adapter.out.persistence.PanacheSourcingRepositories;
import be.enrosed.sourcing.adapter.out.persistence.SourcingEntities;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Opt-in verification against the generated 8 MB source-audited manifest. */
@QuarkusTest
class CatalogMigrationRealManifestTest {
    @Inject CatalogMigrationService migration;
    @Inject CanonicalManifestPayload payloads;
    @Inject ObjectMapper json;
    @Inject EntityManager entityManager;
    @Inject CatalogDaos.Products productRows;
    @Inject CanonicalCatalogDaos.Provenance provenance;
    @Inject CanonicalCatalogDaos.PriceObservations priceObservations;
    @Inject CanonicalCatalogDaos.Families families;
    @Inject PanacheSourcingRepositories.SupplierDao suppliers;
    @Inject ProductService products;
    @Inject FamilyPhotoCompatibilityService familyPhotoCompatibility;
    @Inject FamilyPhotoVariantResolver familyPhotoVariants;
    @Inject PublicFamilyCatalogResource publicCatalog;
    @Inject CatalogContentBackfillService catalogContentBackfill;

    @Test
    void generatedManifestPassesExactBackendContract() throws Exception {
        JsonNode raw = realManifest();
        CanonicalManifestPayload.Parsed parsed = payloads.parse(raw);
        CatalogMigrationPreflight preflight = migration.preflight(
                parsed.manifest(), parsed.verifiedPayloadSha256());
        assertTrue(preflight.valid(), () -> String.join("\n", preflight.problems()));
        assertTrue(preflight.applicationTableRowCounts().containsKey("supplier"));
        assertEquals(CatalogMigrationService.FULL_RESET_CONFIRMATION,
                preflight.fullResetConfirmationRequired());
    }

    @Test
    @TestTransaction
    void fullResetAppliesRealManifestIdempotentlyAndServesSafePublicFamilies() throws Exception {
        seedRowsThatMustBeCleared();
        CanonicalManifestPayload.Parsed parsed = payloads.parse(realManifest());
        CatalogMigrationApplyRequest request = new CatalogMigrationApplyRequest(
                parsed.manifest(), true, false, true,
                CatalogMigrationService.FULL_RESET_CONFIRMATION);

        CatalogMigrationResult result = migration.apply(request, parsed.verifiedPayloadSha256());
        assertFalse(result.idempotent());
        assertTrue(result.fullReset());
        assertEquals(24, result.familiesApplied());
        assertEquals(58, result.variantsApplied());
        assertEquals(80, result.imagesApplied());
        assertEquals(4, result.reusedImageBlobs());
        assertEquals(1L, result.clearedRows().get("supplier"));
        assertEquals(1L, result.clearedRows().get("customer"));
        assertEquals(1L, result.clearedRows().get("product"));
        assertEquals(1L, result.clearedRows().get("company_profile"));
        assertEquals(1L, result.clearedRows().get("category_text"));
        assertTrue(result.clearedRows().get("content_translation") >= 528);
        assertTrue(result.clearedRows().get("content_translation_text") >= 4224);
        assertEquals(0, suppliers.count());
        assertEquals(58, productRows.count());
        CatalogContentBackfillService.Result localized = catalogContentBackfill.apply();
        assertEquals(3, localized.matchedCategories());
        assertEquals(24, localized.matchedFamilies());
        assertEquals(58, localized.matchedVariants());
        assertEquals(80, localized.matchedImages());
        assertEquals(528L, tableCount("content_translation"));
        assertEquals(4224L, tableCount("content_translation_text"));
        assertEquals(24L, tableCount("category_text"));
        assertEquals(1L, tableCount("catalog_localization_backfill"));
        for (ProductEntity variant : productRows.listAll()) {
            if (variant.familyId == null) continue;
            var family = families.findById(variant.familyId);
            var members = productRows.list("familyId", variant.familyId);
            for (var projected : variant.photos) {
                if (projected.familyPhotoId == null) continue;
                var source = entityManager.find(
                        be.enrosed.catalog.adapter.out.persistence.ProductFamilyPhotoEntity.class,
                        projected.familyPhotoId);
                assertNotNull(source, "derived product photo must retain its family source");
                assertTrue(familyPhotoVariants.rank(source, variant, members) < 2,
                        () -> "SKU " + variant.sku
                                + " received a sibling variant image from family "
                                + family.familyKey);
            }
        }
        assertEquals(58, provenance.count(
                "ownerType = ?1 and fieldName = ?2 and source = ?3",
                "VARIANT", "skuProvenance", "GENERATED_INTERNAL"));
        assertEquals(58, provenance.count(
                "ownerType = ?1 and fieldName = ?2 and rawValue = ?3",
                "VARIANT", "sourceSku", "null"));

        var familyWithExw = families.listAll().stream()
                .filter(family -> priceObservations.count(
                        "familyId = ?1 and context = ?2", family.id, "EXW") > 0)
                .findFirst().orElseThrow();
        var familyPrices = priceObservations.list("familyId", familyWithExw.id);
        ProductFamilyDto adminFamily = ProductFamilyDto.from(
                familyWithExw, java.util.List.of(), familyPrices,
                java.util.List.of(), java.util.List.of(),
                productRows.count("familyId", familyWithExw.id), json);
        assertTrue(adminFamily.priceObservations().stream().anyMatch(observation ->
                "EXW".equals(observation.context()) && observation.currency() == null
                        && observation.ownerType() != null && observation.ownerKey() != null),
                "authenticated family DTO must expose source EXW without inventing currency");
        assertTrue(productRows.list("familyId", familyWithExw.id).stream()
                .allMatch(product -> product.exwPrice == null && product.exwCurrency == null),
                "unknown source currency/context must not populate operational EXW fields");

        var cobalt = products.list().stream()
                .filter(product -> "cobalt-blue-roos-in-glazen-stolp".equals(product.familyKey()))
                .findFirst().orElseThrow();
        assertDecimal("58.5", cobalt.carton().dimensions().lengthCm());
        assertDecimal("40", cobalt.carton().dimensions().widthCm());
        assertDecimal("34", cobalt.carton().dimensions().heightCm());
        assertEquals(6, cobalt.carton().piecesPerCarton(),
                "audited ordered carton sides must materialize for pallet/CBM compatibility");
        assertDecimal("12", cobalt.dimensions().lengthCm());
        assertDecimal("25", cobalt.dimensions().widthCm());
        var protectedVariant = products.update(cobalt.id(), cobalt.withPublicationMetadata(
                cobalt.familyKey(), "must-remain-family-owned",
                PublicationState.PUBLISHED, PublicationState.PUBLISHED));
        assertNull(protectedVariant.publicHandle());
        assertEquals(PublicationState.DRAFT,
                protectedVariant.publicationState(CatalogChannel.WEBSITE));
        assertEquals(PublicationState.DRAFT,
                protectedVariant.publicationState(CatalogChannel.ORDER_APP));

        var acrylic = products.list().stream()
                .filter(product -> "acrylic-flowerbox".equals(product.familyKey()))
                .findFirst().orElseThrow();
        assertDecimal("52", acrylic.carton().dimensions().lengthCm());
        assertDecimal("52", acrylic.carton().dimensions().widthCm());
        assertDecimal("49", acrylic.carton().dimensions().heightCm());
        assertEquals(18, acrylic.carton().piecesPerCarton(),
                "operational family PDF carton must beat a non-operational variant observation");

        var heartReview = products.list().stream()
                .filter(product -> "odoo-heart-flowerbox-28-review".equals(product.familyKey()))
                .findFirst().orElseThrow();
        assertTrue(heartReview.dimensions().isBlank(),
                "non-operational review dimensions must remain observations only");
        assertTrue(heartReview.carton().dimensions().isBlank(),
                "non-operational review cartons must not reach pallet/CBM calculations");

        var operationalVariant = products.list().stream()
                .filter(product -> product.familyId() != null && !product.photos().isEmpty())
                .findFirst().orElseThrow();
        ProductDto adminDto = ProductDto.from(operationalVariant);
        assertFalse(adminDto.photos().isEmpty(), "legacy admin/product picker must receive a photo");
        try (InputStream bytes = products.photoData(operationalVariant.primaryPhoto().storageKey())) {
            assertTrue(bytes.readAllBytes().length > 12, "effective legacy photo must be retrievable");
        }

        UriInfo uriInfo = mock(UriInfo.class);
        when(uriInfo.getBaseUri()).thenReturn(URI.create("https://api.enrosed.test/"));
        Response response = publicCatalog.catalog(CatalogChannel.WEBSITE, "EN", uriInfo);
        PublicFamilyCatalogDto publicDto = (PublicFamilyCatalogDto) response.getEntity();
        assertEquals(19, publicDto.families().size());
        assertTrue(publicDto.families().stream().allMatch(family ->
                family.summary() != null && !family.summary().isBlank()
                        && family.description() != null && !family.description().isBlank()
                        && family.seo() != null && family.seo().title() != null
                        && family.seo().description() != null
                        && family.category() != null && family.category().eyebrow() != null
                        && family.category().description() != null
                        && !family.images().isEmpty() && !family.variants().isEmpty()),
                "all 19 EN responses must use stored field-wise fallback (including NL descriptions)");
        assertTrue(publicDto.families().stream().flatMap(family -> family.images().stream())
                .allMatch(image -> image.alt() != null && !image.alt().isBlank()
                        && image.smallWidth() > 0 && image.smallHeight() > 0
                        && image.largeWidth() > 0 && image.largeHeight() > 0));
        Set<String> expectedWebsiteDimensionHandles = Set.of(
                "acrylic-flowerbox", "glass-flowerbox", "hearth-glass-flowerbox",
                "one-rose-in-box", "rose-in-dome-elite", "rose-in-dome-m",
                "rose-in-dome-xl", "roses-in-box-16pcs", "roses-in-box-9pcs",
                "single-rose-in-acryl-glass-box", "soap-rose-box-led");
        assertEquals(expectedWebsiteDimensionHandles, publicDto.families().stream()
                .filter(family -> family.dimensions() != null)
                .map(PublicFamilyCatalogDto.FamilyDto::publicHandle).collect(Collectors.toSet()));
        assertNull(publicDto.families().stream()
                .filter(family -> "cobalt-blue-roos-in-glazen-stolp".equals(family.publicHandle()))
                .findFirst().orElseThrow().dimensions(),
                "PDF-only operational dimensions must not change the website baseline");
        var publicAcrylic = publicDto.families().stream()
                .filter(family -> "acrylic-flowerbox".equals(family.publicHandle()))
                .findFirst().orElseThrow();
        assertDecimal("12", publicAcrylic.dimensions().length());
        assertDecimal("20", publicAcrylic.dimensions().width());
        assertTrue(publicDto.families().stream().flatMap(family -> family.images().stream())
                .allMatch(image -> image.smallUrl().startsWith("/api/")
                        && image.largeUrl().startsWith("/api/")),
                "public image URLs must be reverse-proxy-safe relative paths");
        var acrylicEntity = families.find("publicHandle", "acrylic-flowerbox").firstResult();
        var acrylicImage = acrylicEntity.photos.get(0);
        Response publicImage = publicCatalog.image(
                acrylicEntity.publicHandle, acrylicImage.sourceKey, "small");
        assertEquals(200, publicImage.getStatus());
        try (InputStream bytes = (InputStream) publicImage.getEntity()) {
            assertTrue(bytes.readAllBytes().length > 12);
        }
        String publicJson = json.writeValueAsString(publicDto);
        assertFalse(publicJson.contains("provenance"));
        assertFalse(publicJson.contains("priceObservations"));
        assertFalse(publicJson.contains("\"supplier\":"));
        assertFalse(publicJson.contains("\"sourceUrl\":"));

        for (be.enrosed.shared.Language language : be.enrosed.shared.Language.values()) {
            Response strictResponse = publicCatalog.catalog(
                    CatalogChannel.WEBSITE, language.code(), true, uriInfo);
            assertEquals(200, strictResponse.getStatus(), language.code());
            PublicFamilyCatalogDto strict = (PublicFamilyCatalogDto) strictResponse.getEntity();
            assertEquals(19, strict.families().size(), language.code());
            assertEquals(47, strict.families().stream()
                    .mapToInt(family -> family.variants().size()).sum(), language.code());
            assertEquals(80, strict.families().stream()
                    .mapToInt(family -> family.images().size()).sum(), language.code());
        }

        var editableGallery = families.listAll().stream()
                .filter(family -> family.photos.size() > 1)
                .filter(family -> {
                    var members = productRows.list("familyId", family.id);
                    return members.stream().anyMatch(product ->
                            hasReorderableCompatiblePhotos(family, product, members));
                })
                .findFirst().orElseThrow();
        var membersBeforeReorder = productRows.list("familyId", editableGallery.id);
        ProductEntity reorderedConsumer = membersBeforeReorder.stream()
                .filter(product -> hasReorderableCompatiblePhotos(
                        editableGallery, product, membersBeforeReorder))
                .findFirst().orElseThrow();
        List<Long> consumerOrderBefore = inheritedFamilyPhotoIds(reorderedConsumer);
        long blobsBeforeReorder = ((Number) entityManager.createNativeQuery(
                "select count(*) from photo_blob").getSingleResult()).longValue();
        ArrayList<be.enrosed.catalog.adapter.out.persistence.ProductFamilyPhotoEntity> reversed =
                new ArrayList<>(editableGallery.photos);
        Collections.reverse(reversed);
        for (int index = 0; index < reversed.size(); index++) reversed.get(index).position = index;
        editableGallery.photos.sort(java.util.Comparator.comparingInt(photo -> photo.position));
        families.flush();
        familyPhotoCompatibility.sync(editableGallery);
        entityManager.clear();
        assertEquals(blobsBeforeReorder, ((Number) entityManager.createNativeQuery(
                "select count(*) from photo_blob").getSingleResult()).longValue());
        var reorderedGallery = families.findById(editableGallery.id);
        var membersAfterReorder = productRows.list("familyId", reorderedGallery.id);
        boolean relevantConsumerUpdated = false;
        for (ProductEntity product : membersAfterReorder) {
            List<Long> expected = reorderedGallery.photos.stream()
                    .filter(image -> familyPhotoVariants.rank(
                            image, product, membersAfterReorder) < 2)
                    .sorted(java.util.Comparator
                            .comparingInt((be.enrosed.catalog.adapter.out.persistence.ProductFamilyPhotoEntity image) ->
                                    familyPhotoVariants.rank(image, product, membersAfterReorder))
                            .thenComparingInt(image -> image.position))
                    .map(image -> image.id)
                    .toList();
            List<Long> actual = inheritedFamilyPhotoIds(product);
            assertEquals(expected, actual,
                    "legacy product photos must follow exact-then-global filtered family order");
            for (Long familyPhotoId : actual) {
                var source = reorderedGallery.photos.stream()
                        .filter(image -> image.id.equals(familyPhotoId))
                        .findFirst().orElseThrow();
                assertTrue(familyPhotoVariants.rank(source, product, membersAfterReorder) < 2,
                        "a compatibility projection must never contain a sibling variant image");
            }
            if (product.id.equals(reorderedConsumer.id)
                    && !actual.equals(consumerOrderBefore)) {
                relevantConsumerUpdated = true;
            }
        }
        assertTrue(relevantConsumerUpdated,
                "reordering must update at least one relevant exact/global legacy consumer");

        var unknownInventory = products.list().stream()
                .filter(product -> !product.inventoryKnown()).findFirst().orElseThrow();
        assertEquals(0, unknownInventory.stockQuantity());
        products.adjustStock(unknownInventory.id(), 7);
        var receivedInventory = products.get(unknownInventory.id());
        assertTrue(receivedInventory.inventoryKnown(),
                "first real stock movement must confirm previously unknown inventory");
        assertEquals(7, receivedInventory.stockQuantity());

        SourcingEntities.SupplierEntity laterSupplier = new SourcingEntities.SupplierEntity();
        laterSupplier.name = "Added after the first canonical import";
        entityManager.persist(laterSupplier);
        entityManager.flush();

        CatalogMigrationResult second = migration.apply(request, parsed.verifiedPayloadSha256());
        assertFalse(second.idempotent(),
                "an explicitly confirmed full reset must never short-circuit on an APPLIED batch");
        assertTrue(second.fullReset());
        assertEquals(1L, second.clearedRows().get("supplier"));
        assertEquals(0, suppliers.count());
        assertEquals(58, productRows.count());
        assertEquals(528L, tableCount("content_translation"));
        assertEquals(4224L, tableCount("content_translation_text"));

        CatalogMigrationApplyRequest ordinaryRepeat = new CatalogMigrationApplyRequest(
                parsed.manifest(), false, false, false, null);
        CatalogMigrationResult third = migration.apply(
                ordinaryRepeat, parsed.verifiedPayloadSha256());
        assertTrue(third.idempotent(),
                "ordinary non-reset retries retain the idempotent import contract");
        assertTrue(third.clearedRows().isEmpty());
    }

    @Test
    @TestTransaction
    void fullResetRefusesMissingConfirmationBeforeDeletingAnything() throws Exception {
        seedRowsThatMustBeCleared();
        CanonicalManifestPayload.Parsed parsed = payloads.parse(realManifest());
        CatalogMigrationApplyRequest unsafe = new CatalogMigrationApplyRequest(
                parsed.manifest(), true, false, true, "yes");
        assertThrows(RuntimeException.class,
                () -> migration.apply(unsafe, parsed.verifiedPayloadSha256()));
        assertEquals(1, suppliers.count());
        assertEquals(1, productRows.count());
    }

    @Test
    @TestTransaction
    void semanticValidationRunsBeforeProductReplacement() throws Exception {
        seedRowsThatMustBeCleared();
        JsonNode invalid = realManifest().deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) invalid.path("validationSummary"))
                .put("familyCount", 25);
        CanonicalCatalogManifest manifest = json.treeToValue(invalid, CanonicalCatalogManifest.class);
        CatalogMigrationApplyRequest request = new CatalogMigrationApplyRequest(
                manifest, true, false, false, null);

        assertThrows(RuntimeException.class, () -> migration.apply(request, null));
        assertEquals(1, suppliers.count());
        assertEquals(1, productRows.count());
    }

    private JsonNode realManifest() throws Exception {
        String configured = System.getProperty("catalog.manifest.path");
        Assumptions.assumeTrue(configured != null && !configured.isBlank(),
                "Set -Dcatalog.manifest.path to run the real-manifest contract test");
        Path path = Path.of(configured).toAbsolutePath().normalize();
        Assumptions.assumeTrue(Files.isRegularFile(path), "Manifest does not exist: " + path);
        return json.readTree(path.toFile());
    }

    private boolean hasReorderableCompatiblePhotos(
            be.enrosed.catalog.adapter.out.persistence.ProductFamilyEntity family,
            ProductEntity product, List<ProductEntity> members) {
        long exact = family.photos.stream()
                .filter(image -> familyPhotoVariants.rank(image, product, members) == 0)
                .count();
        long global = family.photos.stream()
                .filter(image -> familyPhotoVariants.rank(image, product, members) == 1)
                .count();
        return exact > 1 || global > 1;
    }

    private static List<Long> inheritedFamilyPhotoIds(ProductEntity product) {
        return product.photos.stream()
                .filter(photo -> photo.familyPhotoId != null)
                .sorted(java.util.Comparator.comparingInt(photo -> photo.position))
                .map(photo -> photo.familyPhotoId)
                .toList();
    }

    private void seedRowsThatMustBeCleared() {
        SourcingEntities.SupplierEntity supplier = new SourcingEntities.SupplierEntity();
        supplier.name = "Test supplier";
        entityManager.persist(supplier);

        SalesEntities.CustomerEntity customer = new SalesEntities.CustomerEntity();
        customer.company = "Test customer";
        entityManager.persist(customer);

        ProductEntity product = new ProductEntity();
        product.sku = "OLD-TEST-SKU";
        product.name = "Old test product";
        product.piecesPerCarton = 1;
        entityManager.persist(product);

        CompanyProfileEntity company = new CompanyProfileEntity();
        company.name = "Old company settings";
        entityManager.persist(company);

        CategoryEntity category = new CategoryEntity();
        category.code = "OLD-CATEGORY";
        category.name = "Old category";
        CategoryTextEntity categoryText = new CategoryTextEntity();
        categoryText.category = category;
        categoryText.language = Language.EN;
        categoryText.name = "Old category";
        category.texts.add(categoryText);
        entityManager.persist(category);
        entityManager.flush();
    }

    private long tableCount(String table) {
        return ((Number) entityManager.createNativeQuery("select count(*) from " + table)
                .getSingleResult()).longValue();
    }

    private static void assertDecimal(String expected, BigDecimal actual) {
        assertNotNull(actual);
        assertEquals(0, new BigDecimal(expected).compareTo(actual));
    }
}
