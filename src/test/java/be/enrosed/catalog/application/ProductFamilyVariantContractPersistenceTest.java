package be.enrosed.catalog.application;

import be.enrosed.catalog.adapter.in.rest.ProductDto;
import be.enrosed.catalog.adapter.in.rest.CanonicalCatalogManifest;
import be.enrosed.catalog.adapter.in.rest.ProductFamilyDto;
import be.enrosed.catalog.adapter.in.rest.ProductFamilyResource;
import be.enrosed.catalog.adapter.in.rest.PublicProductTranslationsDto;
import be.enrosed.catalog.adapter.in.rest.PublicFamilyCatalogDto;
import be.enrosed.catalog.adapter.in.rest.PublicFamilyCatalogResource;
import be.enrosed.catalog.adapter.out.persistence.ProductEntity;
import be.enrosed.catalog.adapter.out.persistence.CategoryEntity;
import be.enrosed.catalog.adapter.out.persistence.CategoryTextEntity;
import be.enrosed.catalog.adapter.out.persistence.ProductCollectionEntity;
import be.enrosed.catalog.adapter.out.persistence.ProductFamilyCollectionEntity;
import be.enrosed.catalog.adapter.out.persistence.ProductFamilyEntity;
import be.enrosed.catalog.adapter.out.persistence.ProductFamilyPhotoEntity;
import be.enrosed.catalog.adapter.out.persistence.ProductFamilyTextEntity;
import be.enrosed.catalog.adapter.out.persistence.ProductPhotoEntity;
import be.enrosed.catalog.adapter.out.persistence.ProductPackageEntity;
import be.enrosed.catalog.adapter.out.persistence.ProductTextEntity;
import be.enrosed.catalog.adapter.out.persistence.WebsiteRebuildEntity;
import be.enrosed.catalog.application.port.out.ProductRepository;
import be.enrosed.catalog.domain.CatalogChannel;
import be.enrosed.catalog.domain.Barcodes;
import be.enrosed.catalog.domain.Carton;
import be.enrosed.catalog.domain.Category;
import be.enrosed.catalog.domain.Dimensions;
import be.enrosed.catalog.domain.Product;
import be.enrosed.catalog.domain.PublicationState;
import be.enrosed.catalog.domain.WebsiteRebuildStatus;
import be.enrosed.shared.BusinessRuleException;
import be.enrosed.shared.Language;
import be.enrosed.shared.LanguageFallback;
import be.enrosed.shared.LocalizationIncompleteException;
import be.enrosed.shared.UnprocessableBusinessRuleException;
import be.enrosed.shared.adapter.in.rest.BusinessRuleMapper;
import be.enrosed.shared.Currency;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
/* Resource methods are called directly here; the admin role check on
   the resource class needs an identity, exactly as over HTTP. */
@io.quarkus.test.security.TestSecurity(user = "enrosedadmin",
        roles = be.enrosed.shared.security.AdminIdentityProvider.ADMIN_ROLE)
class ProductFamilyVariantContractPersistenceTest {
    @Inject EntityManager entityManager;
    @Inject ProductRepository products;
    @Inject FamilyImageVariantService familyImageVariants;
    @Inject PublicFamilyCatalogResource publicFamilies;
    @Inject CatalogMigrationService migration;
    @Inject FeaturedProductSelectionService featuredProducts;
    @Inject CategoryService categoryService;
    @Inject ProductService productService;
    @Inject WebsiteCatalogRevisionService catalogRevisions;
    @Inject PublicProductTranslationsService publicTranslations;
    @Inject ProductFamilyWriteGuard familyWrites;
    @Inject WebsiteRebuildService websiteRebuild;
    @Inject ProductFamilyResource familyResource;
    @Inject CatalogContentBackfillService catalogBackfill;
    @Inject FamilyPhotoCompatibilityService familyPhotoCompatibility;
    @Inject PublishedFamilyGalleryGuard galleryGuard;
    @Inject PublicLocalizationCompletenessService localization;
    @Inject FamilyMemberCacheService familyMemberCache;
    @Inject FamilyCollectionAlignmentService familyCollections;
    @Inject ObjectMapper json;

    private WebsiteRebuildService websiteRebuildTarget() {
        return io.quarkus.arc.ClientProxy.unwrap(websiteRebuild);
    }

    @Test
    void archived20260820ManifestRemainsReadableWithNullDefaultsForAdditiveFields()
            throws Exception {
        java.nio.file.Path archive = java.nio.file.Path.of(
                "docs/migrations/2026-08-20/canonical-catalog.json");
        CanonicalCatalogManifest manifest = json.readValue(
                java.nio.file.Files.readString(archive), CanonicalCatalogManifest.class);

        assertEquals("2026-08-20.3", manifest.importDescriptor().transformVersion());
        assertTrue(manifest.families().stream().allMatch(family ->
                family.cardFeaturedCanonicalVariantKey() == null));
        assertTrue(manifest.families().stream().flatMap(family -> family.variants().stream())
                .allMatch(variant -> variant.size() == null && variant.colourHex() == null));
        assertTrue(manifest.families().stream().flatMap(family -> family.collections().stream())
                .allMatch(collection -> collection.mobileName() == null
                        && collection.featuredCanonicalVariantKey() == null));
    }

    @Test
    @TestTransaction
    void versionedBackfillLeavesLaterManualVariantsAndImagesUntouched() {
        ProductFamilyEntity family = family("rose-diamonds-within-display");
        entityManager.persist(family);
        entityManager.flush();
        ProductEntity manual = product(
                family, "SKU-MANUAL-AUBERGINE", "manual-aubergine", "Aubergine",
                "Bespoke", null, 99);
        manual.texts.clear();
        ProductTextEntity french = new ProductTextEntity();
        french.product = manual;
        french.language = Language.FR;
        french.name = "Variante manuelle";
        french.colour = "Aubergine personnalisée";
        french.variantSize = "Sur mesure";
        manual.texts.add(french);
        entityManager.persist(manual);
        ProductFamilyPhotoEntity image = photo(family, "manual-new-image", 99);
        image.variantProduct = manual;
        image.variantColor = "Aubergine";
        image.altTextsJson = "[{\"language\":\"FR\",\"alt\":\"Photo manuelle\"}]";
        entityManager.persist(image);
        entityManager.flush();

        CatalogContentBackfillService.Result result = catalogBackfill.apply();

        assertEquals(1, result.matchedFamilies());
        assertEquals(0, result.matchedVariants());
        assertEquals(0, result.matchedImages());
        assertNull(manual.colourHex);
        assertEquals("Aubergine personnalisée", french.colour);
        assertEquals("Sur mesure", french.variantSize);
        assertEquals("[{\"language\":\"FR\",\"alt\":\"Photo manuelle\"}]",
                image.altTextsJson);
    }

    @Test
    void oldManifestVariantsDefaultNewAttributesToNullAndPreflightRejectsInvalidHex()
            throws Exception {
        CanonicalCatalogManifest.VariantManifest legacy = json.readValue(
                "{\"canonicalVariantKey\":\"legacy\",\"color\":\"Red\"}",
                CanonicalCatalogManifest.VariantManifest.class);
        assertNull(legacy.size());
        assertNull(legacy.colourHex());

        CanonicalCatalogManifest invalid = json.readValue("""
                {
                  "schemaVersion":"1.0",
                  "importDescriptor":{"transformVersion":"2026-08-20.5"},
                  "families":[{
                    "canonicalFamilyKey":"hex-family",
                    "active":true,
                    "requestedPublication":{"websiteStatus":"READY","orderAppStatus":"DRAFT","catalogueStatus":"DRAFT"},
                    "texts":[],"collections":[],"dimensions":[],"packages":[],"images":[],
                    "externalIdentifiers":[],"priceObservations":[],"provenance":[],"conflicts":[],
                    "variants":[{
                      "canonicalVariantKey":"hex-variant","sku":"HEX-1",
                      "skuProvenance":"GENERATED_INTERNAL","color":"Red",
                      "colourHex":"#aa1122","position":0,"active":true,
                      "inventoryKnown":false,"externalIdentifiers":[],
                      "priceObservations":[],"provenance":[],"packages":[]
                    },{
                      "canonicalVariantKey":"missing-swatch","sku":"HEX-2",
                      "skuProvenance":"GENERATED_INTERNAL","color":"Blue",
                      "position":1,"active":true,"inventoryKnown":false,
                      "externalIdentifiers":[],"priceObservations":[],
                      "provenance":[],"packages":[]
                    }]
                  }]
                }
                """, CanonicalCatalogManifest.class);

        assertTrue(migration.preflight(invalid).problems().stream()
                .anyMatch(problem -> problem.contains("kleurcode moet exact #RRGGBB")));
        assertTrue(migration.preflight(invalid).problems().stream()
                .anyMatch(problem -> problem.contains("mist colourHex voor website READY/PUBLISHED")));
    }

    @Test
    void manifestCategoryFeatureMustBelongToThePrimaryFamilyCategory() throws Exception {
        CanonicalCatalogManifest secondaryOnly = json.readValue("""
                {
                  "schemaVersion":"1.0",
                  "importDescriptor":{"transformVersion":"2026-08-20.5"},
                  "categories":[
                    {"key":"primary","name":"Primary","position":0},
                    {"key":"secondary","name":"Secondary","position":1}
                  ],
                  "families":[{
                    "canonicalFamilyKey":"secondary-feature-family",
                    "active":true,
                    "category":{"key":"primary","name":"Primary","position":0},
                    "collections":[
                      {"key":"primary","name":"Primary","position":0,"primary":true},
                      {"key":"secondary","name":"Secondary","position":1,"primary":false,
                       "featuredCanonicalVariantKey":"secondary-feature"}
                    ],
                    "requestedPublication":{"websiteStatus":"PUBLISHED",
                      "orderAppStatus":"DRAFT","catalogueStatus":"DRAFT"},
                    "texts":[],"dimensions":[],"packages":[],
                    "images":[{"sourceId":"global-image","contentType":"image/webp",
                      "position":0,"altText":"Global image","altTextSource":"SHOPIFY"}],
                    "externalIdentifiers":[],"priceObservations":[],"provenance":[],
                    "conflicts":[],
                    "variants":[{
                      "canonicalVariantKey":"secondary-feature","sku":"SECONDARY-1",
                      "skuProvenance":"GENERATED_INTERNAL","position":0,"active":true,
                      "inventoryKnown":false,"externalIdentifiers":[],
                      "priceObservations":[],"provenance":[],"packages":[]
                    }]
                  }]
                }
                """, CanonicalCatalogManifest.class);

        List<String> problems = migration.preflight(secondaryOnly).problems();
        assertTrue(problems.stream().noneMatch(problem -> problem.contains(
                        "niet naar een actieve variant binnen die collectie")),
                () -> String.join("\n", problems));
        assertTrue(problems.stream().anyMatch(problem -> problem.contains(
                        "Categorie secondary verwijst niet naar een actieve variant "
                                + "binnen de primaire categorie")),
                () -> String.join("\n", problems));
    }

    @Test
    void adminPublicationIssuesWarnWhenActiveColouredMemberHasNoSwatch() {
        ProductFamilyEntity family = family("missing-swatch-family");
        ProductEntity member = product(
                family, "SKU-MISSING-HEX", "missing-hex", "Red", null, null, 0);

        ProductFamilyDto dto = ProductFamilyDto.from(
                family, List.of(), List.of(), List.of(), List.of(), List.of(member), json);

        assertTrue(dto.publicationIssues().contains(
                "Kleurstaal ontbreekt voor een actieve gekleurde variant"));
    }

    @Test
    @TestTransaction
    void persistsAttributesAndProjectsStableLinksWithTranslatedVariantLabels() {
        ProductFamilyEntity family = family("variant-contract");
        entityManager.persist(family);
        entityManager.flush();

        ProductEntity small = product(family, "SKU-RED-S", "red-small", "Rood", "Small", "#A91F32", 0);
        text(small, Language.EN, "English small rose", "Red");
        text(small, Language.NL, "Nederlandse kleine roos", "Rood vertaald");
        small.texts.removeIf(item -> item.language == Language.FR);
        ProductEntity large = product(family, "SKU-RED-L", "red-large", "Rood", "Large", "#A91F32", 1);
        ProductEntity inactive = product(
                family, "SKU-PRIVATE", "private-key", "Navy", null, "#243253", 2);
        inactive.active = false;
        ProductEntity unpictured = product(
                family, "SKU-NO-IMAGE", "no-image-key", "White", null, "#EEE8DD", 3);
        entityManager.persist(small);
        entityManager.persist(large);
        entityManager.persist(inactive);
        entityManager.persist(unpictured);
        entityManager.flush();

        ProductFamilyPhotoEntity largeSpecific = photo(family, "large-specific", 0);
        largeSpecific.variantProduct = large;
        largeSpecific.variantExternalId = large.canonicalVariantKey;
        largeSpecific.variantColor = large.colour;
        ProductFamilyPhotoEntity specific = photo(family, "small-specific", 5);
        specific.variantProduct = small;
        /* Deliberately stale legacy labels prove that the product id is authoritative. */
        specific.variantExternalId = large.canonicalVariantKey;
        specific.variantColor = "Blue";
        ProductFamilyPhotoEntity privateImage = photo(family, "private-image", 6);
        privateImage.variantProduct = inactive;
        privateImage.variantExternalId = inactive.canonicalVariantKey;
        privateImage.variantColor = inactive.colour;
        entityManager.persist(largeSpecific);
        entityManager.persist(specific);
        entityManager.persist(privateImage);
        entityManager.flush();
        entityManager.clear();

        Product persisted = products.findById(small.id).orElseThrow();
        assertEquals("Small", persisted.variantSize());
        assertEquals("#A91F32", persisted.colourHex());
        ProductDto productDto = ProductDto.from(persisted);
        assertEquals("Small", productDto.variantSize());
        assertEquals("#A91F32", productDto.colourHex());

        ProductFamilyEntity reloadedFamily = entityManager.find(ProductFamilyEntity.class, family.id);
        List<ProductEntity> memberRows = entityManager.createQuery(
                        "from ProductEntity item where item.familyId = :familyId",
                        ProductEntity.class)
                .setParameter("familyId", family.id).getResultList();
        ProductFamilyDto admin = ProductFamilyDto.from(
                reloadedFamily, List.of(), List.of(), List.of(), List.of(), memberRows, json);
        ProductFamilyDto.MemberDto member = admin.members().stream()
                .filter(item -> item.productId().equals(small.id)).findFirst().orElseThrow();
        assertEquals("red-small", member.canonicalVariantKey());
        assertEquals("Small", member.size());
        assertEquals("#A91F32", member.colourHex());
        assertEquals(small.id, admin.images().stream()
                .filter(image -> image.id().equals(specific.id)).findFirst().orElseThrow()
                .variantProductId());

        PublicFamilyCatalogDto.FamilyDto english = publicFamily("EN", family.publicHandle);
        PublicFamilyCatalogDto.VariantDto englishSmall = variant(english, small.id);
        assertEquals("Red", englishSmall.color());
        assertEquals("English small rose", englishSmall.name());
        assertEquals("Small", englishSmall.size());
        assertEquals(Language.EN, englishSmall.textSources().get("size"));
        assertEquals("#A91F32", englishSmall.colorHex());
        assertEquals(specific.id, englishSmall.primaryImageId(),
                "stable product id must beat gallery position and stale colour text");
        assertEquals(small.id, english.images().stream()
                .filter(image -> image.id().equals(specific.id)).findFirst().orElseThrow()
                .variantProductId());
        assertTrue(english.images().stream().noneMatch(image -> image.id().equals(privateImage.id)),
                "an inactive variant image must not be reinterpreted as family-wide");
        assertNull(variant(english, unpictured.id).primaryImageId(),
                "a variant without an exact or family-wide photo must not borrow another SKU's image");

        PublicFamilyCatalogDto.VariantDto dutch = variant(
                publicFamily("NL", family.publicHandle), small.id);
        assertEquals("Rood vertaald", dutch.color());
        assertEquals("Nederlandse kleine roos", dutch.name());
        assertEquals("Klein", dutch.size());
        assertEquals(Language.NL, dutch.textSources().get("size"));

        PublicFamilyCatalogDto.VariantDto frenchFallback = variant(
                publicFamily("FR", family.publicHandle), small.id);
        assertEquals("Red", frenchFallback.color());
        assertEquals("English small rose", frenchFallback.name());
    }

    @Test
    @TestTransaction
    void publicCatalogueUsesIndependentVariantNameWhileDocumentsKeepTheirName() {
        FamilyContext context = completeFamilyContext("separate-public-product-name");
        ProductEntity product = product(
                context.family, "SKU-SEPARATE-PUBLIC", "separate-public",
                "Red", null, "#A91F32", 0);
        product.name = "Internal invoice name";
        product.publicName = "Public base name";
        product.texts.forEach(text -> {
            text.name = "Document " + text.language.code();
            text.publicName = "Public " + text.language.code();
        });
        entityManager.persist(product);
        entityManager.flush();

        PublicFamilyCatalogDto.VariantDto projected = variant(
                publicFamily("EN", context.family.publicHandle), product.id);
        assertEquals("Public en", projected.name());
        assertEquals(Language.EN, projected.textSources().get("name"));

        Product operational = productService.get(product.id);
        assertEquals("Internal invoice name", operational.name());
        assertEquals("Document en", operational.nameIn(Language.EN),
                "quote/document localization must remain independent from website copy");
    }

    @Test
    @TestTransaction
    void imageVariantEndpointValidatesMembershipDerivesLegacyFieldsAndCanUnlink() {
        ProductFamilyEntity targetFamily = family("link-target");
        ProductFamilyEntity otherFamily = family("link-other");
        entityManager.persist(targetFamily);
        entityManager.persist(otherFamily);
        entityManager.flush();
        ProductEntity target = product(
                targetFamily, "SKU-TARGET", "target-key", "Cherry Pink", null, "#D9577E", 0);
        ProductEntity outsider = product(
                otherFamily, "SKU-OUTSIDE", "outside-key", "Navy", null, "#243253", 0);
        entityManager.persist(target);
        entityManager.persist(outsider);
        ProductFamilyPhotoEntity image = photo(targetFamily, "link-image", 0);
        entityManager.persist(image);
        entityManager.flush();

        assertThrows(BusinessRuleException.class, () -> familyImageVariants.link(
                targetFamily, image, outsider.id));

        familyImageVariants.link(targetFamily, image, target.id);
        assertEquals(target.id, image.variantProduct.id);
        assertEquals("target-key", image.variantExternalId);
        assertEquals("Cherry Pink", image.variantColor);

        familyImageVariants.link(targetFamily, image, null);
        assertNull(image.variantProduct);
        assertNull(image.variantExternalId);
        assertNull(image.variantColor);
    }

    @Test
    @TestTransaction
    void featuredFamilyAndCategoryChoicesUseValidatedActiveProductIds() {
        CategoryEntity category = new CategoryEntity();
        category.code = "signature";
        category.name = "Signature";
        category.description = "Signature category description";
        category.eyebrow = "Signature";
        category.mobileName = "Signature displays";
        addCategoryTexts(category);
        entityManager.persist(category);
        ProductCollectionEntity collection = new ProductCollectionEntity();
        collection.collectionKey = category.code;
        collection.name = category.name;
        collection.eyebrow = "Signature";
        collection.description = "Signature collection description";
        entityManager.persist(collection);

        ProductFamilyEntity family = family("featured-family");
        family.summary = "Featured family summary";
        family.description = "Featured family description";
        family.seoTitle = "Featured family SEO title";
        family.seoDescription = "Featured family SEO description";
        addFamilyTexts(family);
        family.categoryId = category.id;
        family.categoryKey = category.code;
        family.categoryName = category.name;
        ProductFamilyCollectionEntity membership = new ProductFamilyCollectionEntity();
        membership.family = family;
        membership.collection = collection;
        membership.primaryCollection = true;
        family.collections.add(membership);
        entityManager.persist(family);
        entityManager.flush();

        ProductEntity selected = product(
                family, "SKU-FEATURE", "feature-key", "Navy", "XL", "#243253", 0);
        selected.categoryId = category.id;
        ProductEntity retained = product(
                family, "SKU-RETAINED", "retained-key", "White", null, "#EEE8DD", 1);
        retained.categoryId = category.id;
        ProductFamilyEntity otherFamily = family("other-featured-family");
        entityManager.persist(otherFamily);
        entityManager.flush();
        ProductEntity outsider = product(
                otherFamily, "SKU-OUTSIDER", "outsider-key", "Red", null, "#A91F32", 0);
        entityManager.persist(selected);
        entityManager.persist(retained);
        entityManager.persist(outsider);
        entityManager.flush();

        assertThrows(BusinessRuleException.class,
                () -> featuredProducts.requireFamilyMember(family, selected.id),
                "featured products require an exact or family-wide public photo");
        assertThrows(BusinessRuleException.class,
                () -> featuredProducts.requireCollectionMember(collection, selected.id));
        ProductFamilyPhotoEntity familyWide = photo(family, "featured-family-wide", 0);
        entityManager.persist(familyWide);
        CategoryEntity secondaryCategory = category("SECONDARY CURATION", 1);
        ProductCollectionEntity secondaryCollection = collection("secondary-curation", 1);
        entityManager.persist(secondaryCategory);
        entityManager.persist(secondaryCollection);
        ProductFamilyCollectionEntity secondaryMembership = new ProductFamilyCollectionEntity();
        secondaryMembership.family = family;
        secondaryMembership.collection = secondaryCollection;
        secondaryMembership.primaryCollection = false;
        family.collections.add(secondaryMembership);
        entityManager.persist(secondaryMembership);
        entityManager.flush();

        featuredProducts.requireFamilyMember(family, selected.id);
        featuredProducts.requireCollectionMember(collection, selected.id);
        featuredProducts.requireCollectionMember(secondaryCollection, selected.id);
        assertThrows(BusinessRuleException.class,
                () -> featuredProducts.requireCategoryMember(
                        secondaryCategory.id, secondaryCategory.code, selected.id),
                "secondary merchandising membership must not qualify as the public category");
        secondaryCategory.featuredProductId = selected.id;
        products.save(products.findById(selected.id).orElseThrow());
        assertNull(secondaryCategory.featuredProductId,
                "ordinary SKU saves must clear stale secondary-category selections too");
        assertThrows(BusinessRuleException.class,
                () -> featuredProducts.requireFamilyMember(family, outsider.id));
        assertThrows(BusinessRuleException.class,
                () -> featuredProducts.requireCollectionMember(collection, outsider.id));

        family.cardFeaturedProductId = selected.id;
        Category currentCategory = categoryService.get(category.id);
        Category updated = categoryService.update(category.id, new Category(
                category.id, category.code, category.name, category.description, "Signature", 0,
                "Signature displays", null, null, selected.id,
                currentCategory.texts(), currentCategory.revision()));
        assertEquals("Signature displays", updated.mobileName());
        assertEquals(selected.id, updated.featuredProductId());
        assertEquals("Signature displays", collection.mobileName);
        assertEquals(selected.id, collection.featuredProductId);

        ProductFamilyDto admin = ProductFamilyDto.from(
                family, List.of(), List.of(), List.of(), List.of(),
                List.of(selected, retained), json);
        assertEquals(selected.id, admin.cardFeaturedProductId());
        assertEquals("Signature displays", admin.collections().getFirst().mobileName());
        assertEquals(selected.id, admin.collections().getFirst().featuredProductId());

        PublicFamilyCatalogDto.FamilyDto publicFamily = publicFamily("EN", family.publicHandle);
        assertEquals(selected.id, publicFamily.cardFeaturedProductId());
        assertEquals("Signature displays", publicFamily.category().mobileName());
        assertEquals(selected.id, publicFamily.category().featuredProductId());

        products.save(products.findById(selected.id).orElseThrow().withActive(false));
        entityManager.flush();
        entityManager.clear();
        ProductFamilyEntity reloadedFamily = entityManager.find(ProductFamilyEntity.class, family.id);
        ProductCollectionEntity reloadedCollection = entityManager.find(
                ProductCollectionEntity.class, collection.id);
        CategoryEntity reloadedCategory = entityManager.find(CategoryEntity.class, category.id);
        assertNull(reloadedFamily.cardFeaturedProductId);
        assertNull(reloadedCollection.featuredProductId);
        assertNull(reloadedCategory.featuredProductId);

        /* Defensive projection also protects against stale/manual database writes. */
        reloadedFamily.cardFeaturedProductId = selected.id;
        reloadedCollection.featuredProductId = selected.id;
        reloadedCategory.featuredProductId = selected.id;
        entityManager.flush();
        PublicFamilyCatalogDto.FamilyDto sanitized = publicFamily("EN", family.publicHandle);
        assertNull(sanitized.cardFeaturedProductId());
        assertNull(sanitized.category().featuredProductId());
    }

    @Test
    @TestTransaction
    void publicCatalogueOmitsPublishedFamilyWhenEveryImageIsPrivateOrStale() {
        ProductFamilyEntity family = family("private-images-only");
        entityManager.persist(family);
        entityManager.flush();
        ProductEntity publicVariant = product(
                family, "SKU-PUBLIC", "public-key", "Red", null, "#A91F32", 0);
        ProductEntity inactiveVariant = product(
                family, "SKU-INACTIVE", "inactive-key", "Navy", null, "#243253", 1);
        inactiveVariant.active = false;
        entityManager.persist(publicVariant);
        entityManager.persist(inactiveVariant);
        ProductFamilyPhotoEntity privateImage = photo(family, "inactive-only", 0);
        privateImage.variantProduct = inactiveVariant;
        privateImage.variantExternalId = inactiveVariant.canonicalVariantKey;
        entityManager.persist(privateImage);
        entityManager.flush();

        ProductFamilyDto admin = ProductFamilyDto.from(
                family, List.of(), List.of(), List.of(), List.of(),
                List.of(publicVariant, inactiveVariant), json);
        assertTrue(admin.publicationIssues().contains(
                "Minstens één publiceerbare foto met afmetingen, alt-tekst "
                        + "en actieve variantkoppeling is verplicht"));

        Response response = publicFamilies.catalog(CatalogChannel.WEBSITE, "EN", null);
        PublicFamilyCatalogDto catalog = (PublicFamilyCatalogDto) response.getEntity();
        assertTrue(catalog.families().stream().noneMatch(item ->
                family.publicHandle.equals(item.publicHandle())));
    }

    @Test
    @TestTransaction
    void incompleteAdminImageStaysPrivateUntilANonblankAltTextActivatesIt() {
        ProductFamilyEntity family = family("admin-image-readiness");
        entityManager.persist(family);
        entityManager.flush();
        ProductEntity member = product(
                family, "SKU-IMAGE-READY", "image-ready", "Red", null, "#A91F32", 0);
        entityManager.persist(member);
        ProductFamilyPhotoEntity ready = photo(family, "ready-image", 0);
        ProductFamilyPhotoEntity pending = photo(family, "pending-image", 1);
        pending.altTextsJson = "[]";
        entityManager.persist(ready);
        entityManager.persist(pending);
        entityManager.flush();

        PublicFamilyCatalogDto.FamilyDto before = publicFamily("EN", family.publicHandle);
        assertEquals(List.of(ready.id), before.images().stream().map(
                PublicFamilyCatalogDto.ImageDto::id).toList());

        pending.altTextsJson = "[{\"language\":\"EN\",\"alt\":\"Pending rose image\"}]";
        galleryGuard.validate(family);
        entityManager.flush();

        PublicFamilyCatalogDto.FamilyDto after = publicFamily("EN", family.publicHandle);
        assertEquals(Set.of(ready.id, pending.id), after.images().stream().map(
                PublicFamilyCatalogDto.ImageDto::id).collect(Collectors.toSet()));
    }

    @Test
    @TestTransaction
    void explicitPhotoPublicationChannelsPersistAndRoundTripInTheAdminDto() {
        ProductFamilyEntity family = family("photo-channel-persistence");
        family.websiteStatus = PublicationState.DRAFT;
        entityManager.persist(family);
        entityManager.flush();
        ProductEntity member = product(
                family, "SKU-CHANNELS", "photo-channels", "Red", null, "#A91F32", 0);
        entityManager.persist(member);
        ProductFamilyPhotoEntity image = photo(family, "channel-image", 0);
        image.publishedChannelsJson = "[]";
        entityManager.persist(image);
        entityManager.flush();

        ProductFamilyDto updated = familyResource.setImagePublication(
                family.id, image.id, new ProductFamilyResource.ImagePublicationRequest(
                        List.of(CatalogChannel.WEBSITE, CatalogChannel.CATALOGUE)));

        assertEquals(List.of(CatalogChannel.WEBSITE, CatalogChannel.CATALOGUE),
                updated.images().getFirst().publishedChannels());
        entityManager.flush();
        entityManager.clear();
        ProductFamilyPhotoEntity stored = entityManager.find(
                ProductFamilyPhotoEntity.class, image.id);
        assertEquals("[\"WEBSITE\",\"CATALOGUE\"]", stored.publishedChannelsJson);
    }

    @Test
    @TestTransaction
    void internalPhotoDoesNotCreateTranslationBlockersUntilItsChannelIsPublished() {
        FamilyContext context = completeFamilyContext("internal-photo-copy");
        ProductFamilyPhotoEntity internal = photo(
                context.family, "internal-study", context.family.photos.size());
        internal.altTextsJson = "[]";
        internal.publishedChannelsJson = "[]";
        entityManager.persist(internal);
        entityManager.flush();

        List<String> whileInternal = localization.missing(
                context.family, List.of(), CatalogChannel.WEBSITE);
        assertTrue(whileInternal.stream().noneMatch(path -> path.contains("internal-study")),
                whileInternal.toString());

        internal.publishedChannelsJson = "[\"WEBSITE\"]";
        List<String> afterPublication = localization.missing(
                context.family, List.of(), CatalogChannel.WEBSITE);
        assertEquals(Language.values().length, afterPublication.stream()
                .filter(path -> path.contains("internal-study") && path.endsWith(".alt"))
                .count());
    }

    @Test
    @TestTransaction
    void publicFamilyCatalogFiltersImagesAndVariantPrimaryPerRequestedChannel() {
        ProductFamilyEntity family = family("channel-specific-gallery");
        family.catalogueStatus = PublicationState.PUBLISHED;
        entityManager.persist(family);
        entityManager.flush();
        ProductEntity member = product(
                family, "SKU-CHANNEL-GALLERY", "channel-gallery", "Red", null,
                "#A91F32", 0);
        entityManager.persist(member);
        ProductFamilyPhotoEntity website = photo(family, "website-image", 0);
        website.variantProduct = member;
        website.publishedChannelsJson = "[\"WEBSITE\"]";
        ProductFamilyPhotoEntity catalogue = photo(family, "catalogue-image", 1);
        catalogue.variantProduct = member;
        catalogue.publishedChannelsJson = "[\"CATALOGUE\"]";
        entityManager.persist(website);
        entityManager.persist(catalogue);
        entityManager.flush();

        PublicFamilyCatalogDto.FamilyDto websiteFamily = publicFamily(
                CatalogChannel.WEBSITE, "EN", family.publicHandle);
        PublicFamilyCatalogDto.FamilyDto catalogueFamily = publicFamily(
                CatalogChannel.CATALOGUE, "EN", family.publicHandle);

        assertEquals(List.of(website.id), websiteFamily.images().stream()
                .map(PublicFamilyCatalogDto.ImageDto::id).toList());
        assertEquals(website.id, variant(websiteFamily, member.id).primaryImageId());
        assertEquals(List.of(catalogue.id), catalogueFamily.images().stream()
                .map(PublicFamilyCatalogDto.ImageDto::id).toList());
        assertEquals(catalogue.id, variant(catalogueFamily, member.id).primaryImageId());
    }

    @Test
    @TestTransaction
    void internalImageBytesAreNotAvailableOnTheAnonymousStableUrl() {
        ProductFamilyEntity family = family("private-image-bytes");
        entityManager.persist(family);
        entityManager.flush();
        entityManager.persist(product(
                family, "SKU-PRIVATE-BYTES", "private-bytes", "Red", null, "#A91F32", 0));
        entityManager.persist(photo(family, "public-cover", 0));
        ProductFamilyPhotoEntity internal = photo(family, "internal-original", 1);
        internal.publishedChannelsJson = "[]";
        entityManager.persist(internal);
        entityManager.flush();

        assertThrows(jakarta.ws.rs.NotFoundException.class,
                () -> publicFamilies.image(
                        family.publicHandle, internal.sourceKey, "large"));
    }

    @Test
    @TestTransaction
    void removingTheLastWebsitePublishedPhotoFromALiveFamilyIsRejected() {
        ProductFamilyEntity family = family("last-website-channel-photo");
        entityManager.persist(family);
        entityManager.flush();
        entityManager.persist(product(
                family, "SKU-LAST-WEBSITE", "last-website", "Red", null, "#A91F32", 0));
        ProductFamilyPhotoEntity image = photo(family, "last-website-image", 0);
        image.publishedChannelsJson = "[\"WEBSITE\",\"CATALOGUE\"]";
        entityManager.persist(image);
        entityManager.flush();

        BusinessRuleException error = assertThrows(BusinessRuleException.class,
                () -> familyResource.setImagePublication(
                        family.id, image.id,
                        new ProductFamilyResource.ImagePublicationRequest(
                                List.of(CatalogChannel.CATALOGUE))));

        assertTrue(error.getMessage().contains("WEBSITE"), error.getMessage());
    }

    @Test
    @TestTransaction
    void clearingTheLastPublicAltTextOfAPublishedFamilyIsRejected() {
        ProductFamilyEntity family = family("last-alt-guard");
        entityManager.persist(family);
        entityManager.flush();
        entityManager.persist(product(
                family, "SKU-LAST-ALT", "last-alt", "Red", null, "#A91F32", 0));
        ProductFamilyPhotoEntity ready = photo(family, "last-alt-image", 0);
        entityManager.persist(ready);
        entityManager.flush();

        ready.altTextsJson = "[]";
        BusinessRuleException error = assertThrows(BusinessRuleException.class,
                () -> galleryGuard.validate(family));
        assertTrue(error.getMessage().contains("publiceerbare foto"), error.getMessage());
    }

    @Test
    @TestTransaction
    void deletingTheLastPublicImageOfAPublishedFamilyIsRejected() {
        ProductFamilyEntity family = family("last-image-guard");
        entityManager.persist(family);
        entityManager.flush();
        entityManager.persist(product(
                family, "SKU-LAST-IMAGE", "last-image", "Red", null, "#A91F32", 0));
        ProductFamilyPhotoEntity ready = photo(family, "last-public-image", 0);
        entityManager.persist(ready);
        entityManager.flush();

        family.photos.remove(ready);
        BusinessRuleException error = assertThrows(BusinessRuleException.class,
                () -> galleryGuard.validate(family));
        assertTrue(error.getMessage().contains("publiceerbare foto"), error.getMessage());
    }

    @Test
    @TestTransaction
    void compatibilityPhotosNeverCrossFromOneVariantToAnother() {
        ProductFamilyEntity family = family("compatibility-images");
        entityManager.persist(family);
        entityManager.flush();
        ProductEntity red = product(
                family, "SKU-COMPAT-RED", "compat-red", "Red", null, "#A91F32", 0);
        ProductEntity blue = product(
                family, "SKU-COMPAT-BLUE", "compat-blue", "Blue", null, "#6C8FC4", 1);
        entityManager.persist(red);
        entityManager.persist(blue);
        ProductFamilyPhotoEntity redImage = photo(family, "compat-red-image", 0);
        redImage.variantProduct = red;
        redImage.variantExternalId = red.canonicalVariantKey;
        ProductFamilyPhotoEntity blueImage = photo(family, "compat-blue-image", 1);
        blueImage.variantProduct = blue;
        blueImage.variantExternalId = blue.canonicalVariantKey;
        ProductFamilyPhotoEntity global = photo(family, "compat-global-image", 2);
        entityManager.persist(redImage);
        entityManager.persist(blueImage);
        entityManager.persist(global);
        entityManager.flush();

        ProductPhotoEntity userOwned = productPhoto(red, null, "user-owned", 0);
        ProductPhotoEntity staleSibling = productPhoto(
                red, blueImage.id, blueImage.largeStorageKey, 1);
        entityManager.persist(userOwned);
        entityManager.persist(staleSibling);
        red.photos.add(userOwned);
        red.photos.add(staleSibling);
        entityManager.flush();

        familyPhotoCompatibility.sync(family);
        familyPhotoCompatibility.sync(family);
        entityManager.flush();
        entityManager.clear();

        ProductEntity persistedRed = entityManager.find(ProductEntity.class, red.id);
        ProductEntity persistedBlue = entityManager.find(ProductEntity.class, blue.id);
        assertEquals(Set.of(redImage.id, global.id), persistedRed.photos.stream()
                .filter(photo -> photo.familyPhotoId != null)
                .map(photo -> photo.familyPhotoId).collect(Collectors.toSet()));
        assertEquals(Set.of(blueImage.id, global.id), persistedBlue.photos.stream()
                .filter(photo -> photo.familyPhotoId != null)
                .map(photo -> photo.familyPhotoId).collect(Collectors.toSet()));
        assertEquals(1, persistedRed.photos.stream()
                .filter(photo -> photo.familyPhotoId == null).count());
        assertEquals("user-owned", persistedRed.photos.stream()
                .filter(photo -> photo.familyPhotoId == null).findFirst().orElseThrow().storageKey);
        ProductDto redDto = ProductDto.from(products.findById(red.id).orElseThrow());
        assertEquals(1, redDto.photos().stream()
                .filter(photo -> photo.origin() == ProductDto.PhotoOrigin.PRODUCT
                        && !photo.readOnly() && photo.familyPhotoId() == null)
                .count());
        assertEquals(2, redDto.photos().stream()
                .filter(photo -> photo.origin() == ProductDto.PhotoOrigin.FAMILY
                        && photo.readOnly() && photo.familyPhotoId() != null)
                .count());
        assertEquals(persistedRed.photos.size(), persistedRed.photos.stream()
                .map(photo -> String.valueOf(photo.familyPhotoId) + "|" + photo.storageKey)
                .distinct().count(),
                "a repeated compatibility resync must not leave duplicates");
    }

    @Test
    @TestTransaction
    void inheritedFamilyPhotoCannotBeDeletedThroughTheProductEndpoint() {
        ProductPhotoContext context = productPhotoContext("readonly-delete-photo");

        BusinessRuleException error = assertThrows(BusinessRuleException.class,
                () -> productService.removePhoto(context.productId(), context.inheritedPhotoId()));

        assertTrue(error.getMessage().contains("modelgalerij"), error.getMessage());
        assertEquals(3, entityManager.find(ProductEntity.class, context.productId()).photos.size());
    }

    @Test
    @TestTransaction
    void inheritedFamilyPhotoCannotBeReorderedThroughTheProductEndpoint() {
        ProductPhotoContext context = productPhotoContext("readonly-reorder-photo");

        BusinessRuleException error = assertThrows(BusinessRuleException.class,
                () -> productService.reorderPhotos(context.productId(), List.of(
                        context.inheritedPhotoId(), context.secondOwnedPhotoId(),
                        context.firstOwnedPhotoId())));

        assertTrue(error.getMessage().contains("alleen-lezen"), error.getMessage());
    }

    @Test
    @TestTransaction
    void productOwnedPhotosCanBeReorderedWithoutLosingTheInheritedOrigin() {
        ProductPhotoContext context = productPhotoContext("owned-reorder-photo");

        Product reordered = productService.reorderPhotos(context.productId(), List.of(
                context.secondOwnedPhotoId(), context.firstOwnedPhotoId()));

        assertEquals(List.of(
                        context.secondOwnedPhotoId(), context.firstOwnedPhotoId(),
                        context.inheritedPhotoId()),
                reordered.photos().stream().map(be.enrosed.catalog.domain.Photo::id).toList());
        assertNull(reordered.photos().get(0).familyPhotoId());
        assertNull(reordered.photos().get(1).familyPhotoId());
        assertEquals(context.familyPhotoId(), reordered.photos().get(2).familyPhotoId());
        assertEquals(List.of(0, 1, 2),
                reordered.photos().stream().map(be.enrosed.catalog.domain.Photo::position).toList());
    }

    @Test
    @TestTransaction
    void uploadedProductPhotoStaysAheadOfInheritedModelPhotos() {
        ProductPhotoContext context = productPhotoContext("owned-upload-photo");

        Product updated = productService.addPhoto(
                context.productId(), "new-product-photo.bin",
                new ByteArrayInputStream("GIF89a-own-photo".getBytes(StandardCharsets.US_ASCII)));

        assertEquals(List.of(
                        context.firstOwnedPhotoId(), context.secondOwnedPhotoId()),
                updated.photos().subList(0, 2).stream()
                        .map(be.enrosed.catalog.domain.Photo::id).toList());
        assertEquals("new-product-photo.gif", updated.photos().get(2).originalFilename());
        assertNull(updated.photos().get(2).familyPhotoId());
        assertEquals(context.inheritedPhotoId(), updated.photos().get(3).id());
        assertEquals(context.familyPhotoId(), updated.photos().get(3).familyPhotoId());
        assertEquals(List.of(0, 1, 2, 3), updated.photos().stream()
                .map(be.enrosed.catalog.domain.Photo::position).toList());
    }

    @Test
    @TestTransaction
    void productWritesAppendUniqueFamilyPositionsAndRebuildCompatibilityOnMove() {
        CategoryEntity category = category("DISPLAY ROSES", 0);
        ProductCollectionEntity collection = collection("display-roses", 0);
        entityManager.persist(category);
        entityManager.persist(collection);
        entityManager.flush();
        ProductFamilyEntity sourceFamily = completePublishedFamily(
                "position-source", category, collection, 0);
        ProductFamilyEntity targetFamily = completePublishedFamily(
                "position-target", category, collection, 1);

        ProductEntity source = product(
                sourceFamily, "SKU-POS-0", "pos-0", "Red", null, "#A91F32", 0);
        ProductEntity one = product(
                sourceFamily, "SKU-POS-1", "pos-1", "Blue", null, "#6C8FC4", 1);
        ProductEntity two = product(
                sourceFamily, "SKU-POS-2", "pos-2", "White", null, "#EEE8DD", 2);
        ProductEntity targetZero = product(
                targetFamily, "SKU-TARGET-0", "target-0", "Pink", null, "#D889A2", 0);
        ProductEntity targetOne = product(
                targetFamily, "SKU-TARGET-1", "target-1", "Cherry Pink", null, "#D9577E", 1);
        for (ProductEntity member : List.of(source, one, two, targetZero, targetOne)) {
            member.categoryId = category.id;
            entityManager.persist(member);
        }
        entityManager.flush();

        Product duplicate = productService.duplicate(
                source.id, null, null, "Large");
        assertEquals(3, duplicate.variantPosition());
        Product created = productService.create(domainProduct(
                sourceFamily, "SKU-POS-NEW", "Light Blue", null, "#9CC5DE", 0));
        assertEquals(4, created.variantPosition());

        assertTrue(created.photos().stream().anyMatch(photo ->
                java.util.Objects.equals(photo.storageKey(),
                        sourceFamily.photos.getFirst().largeStorageKey)));

        Product moved = productService.assignFamily(created.id(), targetFamily.id);
        assertEquals(2, moved.variantPosition());
        assertTrue(moved.photos().stream().noneMatch(photo ->
                java.util.Objects.equals(photo.storageKey(),
                        sourceFamily.photos.getFirst().largeStorageKey)));
        assertTrue(moved.photos().stream().anyMatch(photo ->
                java.util.Objects.equals(photo.storageKey(),
                        targetFamily.photos.getFirst().largeStorageKey)));

        Product attemptedCollision = moved.withCanonicalIdentity(
                targetFamily.id, moved.canonicalVariantKey(), moved.canonicalBarcode(),
                0, moved.inventoryKnown());
        Product preserved = productService.update(moved.id(), attemptedCollision);
        assertEquals(2, preserved.variantPosition(),
                "ordinary updates preserve the server-owned current position");

        for (String handle : List.of(sourceFamily.publicHandle, targetFamily.publicHandle)) {
            List<Integer> positions = publicFamily("EN", handle).variants().stream()
                    .map(PublicFamilyCatalogDto.VariantDto::position).toList();
            assertEquals(positions.size(), Set.copyOf(positions).size());
        }
    }

    @Test
    @TestTransaction
    void strictPublicCatalogRequiresExactCategoryNavigationAndNonblankFamilyCopy() {
        FamilyContext context = completeFamilyContext("strict-language-contract");
        context.category.navigationName = "Displays";
        context.category.footerName = "Signature displays";
        context.category.texts.forEach(text -> {
            text.navigationName = text.language == Language.EN
                    ? "Displays" : "Displays " + text.language.code();
            text.footerName = text.language == Language.EN
                    ? "Signature displays" : "Footer " + text.language.code();
        });
        ProductEntity variant = product(
                context.family, "SKU-STRICT-LANGUAGE", "strict-language",
                "Red", null, "#A91F32", 0);
        variant.categoryId = context.category.id;
        entityManager.persist(variant);
        entityManager.flush();

        Response response = publicFamilies.catalog(
                CatalogChannel.WEBSITE, "EN", true, null);
        PublicFamilyCatalogDto catalog = (PublicFamilyCatalogDto) response.getEntity();
        PublicFamilyCatalogDto.FamilyDto projected = catalog.families().stream()
                .filter(item -> context.family.publicHandle.equals(item.publicHandle()))
                .findFirst().orElseThrow();
        assertEquals("Displays", projected.category().navigationName());
        assertEquals(Language.EN,
                projected.category().textSources().get("navigationName"));
        assertEquals("Signature displays", projected.category().footerName());
        assertEquals(Language.EN, projected.category().textSources().get("footerName"));

        context.family.description = null;
        context.family.texts.stream()
                .filter(text -> text.language == Language.EN || text.language == Language.NL)
                .forEach(text -> text.description = null);
        context.category.texts.stream().filter(text -> text.language == Language.EN)
                .forEach(text -> {
                    text.navigationName = null;
                    text.footerName = null;
                });
        LocalizationIncompleteException error = assertThrows(
                LocalizationIncompleteException.class,
                () -> publicFamilies.catalog(CatalogChannel.WEBSITE, "EN", true, null));
        String prefix = "families." + context.family.publicHandle;
        assertTrue(error.missingPaths().contains(prefix + ".description"));
        assertTrue(error.missingPaths().contains(prefix + ".category.navigationName"));
        assertTrue(error.missingPaths().contains(prefix + ".category.footerName"));
        assertEquals(error.missingPaths().size(), Set.copyOf(error.missingPaths()).size(),
                "strict missing paths must be unique and deterministically ordered");
    }

    @Test
    @TestTransaction
    void strictPublicCatalogServesEverySupportedLocaleAndFallbackIsExplicit() {
        FamilyContext context = completeFamilyContext("strict-eight-locale-contract");
        ProductEntity variant = product(
                context.family, "SKU-STRICT-EIGHT", "strict-eight",
                "Red", "Small", "#A91F32", 0);
        variant.categoryId = context.category.id;
        entityManager.persist(variant);
        entityManager.flush();

        String catalogRevision = null;
        for (Language language : Language.values()) {
            Response response = publicFamilies.catalog(
                    CatalogChannel.WEBSITE, language.code(), true, null);
            assertEquals(200, response.getStatus(), language.code());
            PublicFamilyCatalogDto catalog = (PublicFamilyCatalogDto) response.getEntity();
            if (catalogRevision == null) catalogRevision = catalog.catalogRevision();
            else assertEquals(catalogRevision, catalog.catalogRevision(),
                    "all locale snapshots must publish one rebuild revision");
            assertEquals(language, catalog.language());
            assertEquals(LanguageFallback.chain(language), catalog.fallbackChain());
            PublicFamilyCatalogDto.FamilyDto projected = catalog.families().stream()
                    .filter(item -> context.family.publicHandle.equals(item.publicHandle()))
                    .findFirst().orElseThrow();
            assertEquals(language, projected.textSources().get("name"));
            assertEquals(language, projected.variants().getFirst().textSources().get("name"));
            assertEquals(language,
                    projected.images().getFirst().textSources().get("alt"));
        }

        context.family.texts.removeIf(text -> text.language == Language.FR);
        entityManager.flush();
        PublicFamilyCatalogDto fallback = (PublicFamilyCatalogDto) publicFamilies.catalog(
                CatalogChannel.WEBSITE, "fr", false, null).getEntity();
        PublicFamilyCatalogDto.FamilyDto projected = fallback.families().stream()
                .filter(item -> context.family.publicHandle.equals(item.publicHandle()))
                .findFirst().orElseThrow();
        assertEquals(Language.EN, projected.textSources().get("name"));
        assertThrows(jakarta.ws.rs.BadRequestException.class,
                () -> publicFamilies.catalog(CatalogChannel.WEBSITE, "xx", true, null));
    }

    @Test
    @TestTransaction
    void strictPublicCatalogRejectsAPublishedFamilyThatCannotBeProjected() {
        ProductFamilyEntity invalid = family("strict-projection-hole");
        entityManager.persist(invalid);
        entityManager.flush();

        LocalizationIncompleteException error = assertThrows(
                LocalizationIncompleteException.class,
                () -> publicFamilies.catalog(CatalogChannel.WEBSITE, "EN", true, null));
        assertEquals(List.of("families.strict-projection-hole.publicProjection"),
                error.missingPaths());

        PublicFamilyCatalogDto nonStrict = (PublicFamilyCatalogDto) publicFamilies.catalog(
                CatalogChannel.WEBSITE, "EN", false, null).getEntity();
        assertTrue(nonStrict.families().stream().noneMatch(item ->
                "strict-projection-hole".equals(item.publicHandle())));
    }

    @Test
    @TestTransaction
    void strictPublicCatalogRejectsAPublishedFamilyWithoutACategoryProjection() {
        FamilyContext context = completeFamilyContext("strict-category-hole");
        ProductEntity variant = product(
                context.family, "SKU-STRICT-CATEGORY", "strict-category",
                "Red", null, "#A91F32", 0);
        entityManager.persist(variant);
        context.family.categoryId = null;
        context.family.categoryKey = null;
        context.family.categoryName = null;
        entityManager.flush();

        LocalizationIncompleteException error = assertThrows(
                LocalizationIncompleteException.class,
                () -> publicFamilies.catalog(CatalogChannel.WEBSITE, "EN", true, null));

        assertTrue(error.missingPaths().contains(
                "families.strict-category-hole.category"));
    }

    @Test
    @TestTransaction
    void bulkTranslationReplacementSavesIncrementallyAndLeavesMissingCopyInTheWorkQueue() {
        FamilyContext context = completeFamilyContext("bulk-translation-guard");
        ProductEntity variant = product(
                context.family, "SKU-BULK-GUARD", "bulk-translation-guard-red",
                "Red", null, "#A91F32", 0);
        variant.categoryId = context.category.id;
        entityManager.persist(variant);
        entityManager.flush();
        assertTrue(familyWrites.websiteBuildReady());

        WebsiteRebuildEntity rebuildState = entityManager.find(WebsiteRebuildEntity.class, 1L);
        if (rebuildState == null) {
            rebuildState = new WebsiteRebuildEntity();
            entityManager.persist(rebuildState);
        }
        rebuildState.status = WebsiteRebuildStatus.LIVE;
        rebuildState.liveRevision = "0".repeat(64);
        rebuildState.attemptCount = 4;

        List<ProductDto.TextDto> incomplete = List.of(new ProductDto.TextDto(
                Language.EN, "English only", null, "Red", null));
        Optional<String> previousHook = websiteRebuildTarget().deployHookUrl;
        try {
            websiteRebuildTarget().deployHookUrl = Optional.of(
                    "https://example.invalid/deploy-hook");
            String beforeRevision = publicTranslations.get(variant.id).revision();
            assertEquals(1, publicTranslations.replaceProductTexts(
                    java.util.Map.of(variant.id, incomplete)));

            PublicProductTranslationsDto saved = publicTranslations.get(variant.id);
            assertNotEquals(beforeRevision, saved.revision());
            assertEquals(context.family.id, saved.familyId());
            assertEquals(variant.id, saved.productId());
            assertEquals(context.family.familyKey, saved.family().familyKey());
            assertEquals(context.family.publicHandle, saved.family().publicHandle());
            assertEquals(context.family.categoryKey, saved.family().categoryKey());
            assertEquals(variant.canonicalVariantKey, saved.product().canonicalVariantKey());
            assertTrue(saved.family().publicationIssues().stream().anyMatch(issue ->
                            issue.contains("bulk-translation-guard-red.nl.name")),
                    saved.family().publicationIssues().toString());
            assertFalse(familyWrites.websiteBuildReady(),
                    "incomplete published copy must remain pending");
            assertEquals(WebsiteRebuildStatus.LIVE, rebuildState.status,
                    "incomplete copy must not queue a predictably failing strict build");
            assertEquals(4, rebuildState.attemptCount);

            LocalizationIncompleteException strictBuild = assertThrows(
                    LocalizationIncompleteException.class,
                    () -> publicFamilies.catalog(CatalogChannel.WEBSITE, "NL", true, null));
            assertTrue(strictBuild.missingPaths().stream().anyMatch(path ->
                            path.equals("families." + context.family.publicHandle
                                    + ".variants." + variant.id + ".name")),
                    strictBuild.missingPaths().toString());

            List<ProductDto.TextDto> complete = java.util.Arrays.stream(Language.values())
                    .map(language -> new ProductDto.TextDto(
                            language, "Complete " + language.code(), null,
                            "Colour " + language.code(), null))
                    .toList();
            assertEquals(1, publicTranslations.replaceProductTexts(
                    java.util.Map.of(variant.id, complete)));
            assertTrue(familyWrites.websiteBuildReady());
            assertEquals(WebsiteRebuildStatus.QUEUED, rebuildState.status,
                    "fixing the final locale must resume automatic deployment");
            assertEquals(0, rebuildState.attemptCount);
        } finally {
            websiteRebuildTarget().deployHookUrl = previousHook;
        }
    }

    @Test
    @TestTransaction
    void staleGeneralFamilyPutCannotOverwriteAtomicFamilyTranslations() {
        ProductFamilyEntity family = family("legacy-family-put-copy-owner");
        /* This family has no category, photo or variant; keep it a draft so the
           general PUT is not refused on publication rules - the contract under
           test is about copy ownership, not publishability. */
        family.websiteStatus = PublicationState.DRAFT;
        /* The helper seeds every language; replace its FR copy, one row per language. */
        family.texts.removeIf(text -> text.language == Language.FR);
        ProductFamilyTextEntity approved = new ProductFamilyTextEntity();
        approved.family = family;
        approved.language = Language.FR;
        approved.name = "Copie approuvée";
        approved.description = "Description approuvée";
        approved.highlightsJson = "[]";
        family.texts.add(approved);
        entityManager.persist(family);
        entityManager.flush();

        ProductFamilyDto stale = familyResource.get(family.id).withTexts(List.of(
                new ProductFamilyDto.TextDto(Language.FR, "Ancien brouillon", null,
                        "Ancienne description", null, List.of(), null, null)));
        familyResource.update(family.id, stale);
        entityManager.flush();

        ProductFamilyTextEntity stored = entityManager.find(
                ProductFamilyEntity.class, family.id).texts.stream()
                .filter(text -> text.language == Language.FR).findFirst().orElseThrow();
        assertEquals("Copie approuvée", stored.name);
        assertEquals("Description approuvée", stored.description);
    }

    @Test
    @TestTransaction
    void generalFamilyPutKeepsTheTechnicalKeyImmutableWhileVisibleTitleRemainsEditable()
            throws Exception {
        ProductFamilyEntity family = family("stable-family-key");
        family.websiteStatus = PublicationState.DRAFT;
        entityManager.persist(family);
        entityManager.flush();

        com.fasterxml.jackson.databind.node.ObjectNode titleJson =
                json.valueToTree(familyResource.get(family.id));
        titleJson.put("name", "Visible customer family title");
        ProductFamilyDto renamed = familyResource.update(
                family.id, json.treeToValue(titleJson, ProductFamilyDto.class));
        assertEquals("Visible customer family title", renamed.name());
        assertEquals("stable-family-key", renamed.familyKey());

        com.fasterxml.jackson.databind.node.ObjectNode keyJson =
                json.valueToTree(familyResource.get(family.id));
        keyJson.put("familyKey", "renamed-technical-key");
        keyJson.put("name", "A title that must not leak through the rejected request");
        UnprocessableBusinessRuleException blocked = assertThrows(
                UnprocessableBusinessRuleException.class,
                () -> familyResource.update(
                        family.id, json.treeToValue(keyJson, ProductFamilyDto.class)));
        assertTrue(blocked.getMessage().contains("vaste technische sleutel"));
        assertEquals(422, new BusinessRuleMapper().toResponse(blocked).getStatus());

        entityManager.flush();
        entityManager.clear();
        ProductFamilyEntity stored = entityManager.find(ProductFamilyEntity.class, family.id);
        assertEquals("stable-family-key", stored.familyKey);
        assertEquals("Visible customer family title", stored.name);
    }

    @Test
    @TestTransaction
    void publicHandleIsImmutableAfterCreateWhileVisibleTitleRemainsEditable()
            throws Exception {
        ProductFamilyEntity family = family("protected-public-handle");
        family.websiteStatus = PublicationState.DRAFT;
        entityManager.persist(family);
        entityManager.flush();

        com.fasterxml.jackson.databind.node.ObjectNode titleJson =
                json.valueToTree(familyResource.get(family.id));
        titleJson.put("name", "A freely editable visible title");
        ProductFamilyDto renamed = familyResource.update(
                family.id, json.treeToValue(titleJson, ProductFamilyDto.class));
        assertEquals("A freely editable visible title", renamed.name());

        com.fasterxml.jackson.databind.node.ObjectNode changedHandleJson =
                json.valueToTree(familyResource.get(family.id));
        changedHandleJson.put("publicHandle", "unsafe-url-change");
        UnprocessableBusinessRuleException blocked = assertThrows(
                UnprocessableBusinessRuleException.class,
                () -> familyResource.update(
                        family.id, json.treeToValue(changedHandleJson, ProductFamilyDto.class)));
        assertTrue(blocked.getMessage().contains("URL-migratie"));
        assertEquals(422, new BusinessRuleMapper().toResponse(blocked).getStatus());
        assertEquals("protected-public-handle", familyResource.get(family.id).publicHandle());
    }

    @Test
    @TestTransaction
    void familyCreateRejectsTranslationValuesBeyondDatabaseBoundsBeforePersisting() {
        ProductFamilyEntity source = family("family-create-copy-bounds-source");
        entityManager.persist(source);
        entityManager.flush();
        long before = entityManager.createQuery(
                "select count(item) from ProductFamilyEntity item", Long.class).getSingleResult();
        ProductFamilyDto base = familyResource.get(source.id);

        assertThrows(BusinessRuleException.class, () -> familyResource.create(base.withTexts(List.of(
                new ProductFamilyDto.TextDto(Language.EN, "x".repeat(256), null,
                        null, null, List.of(), null, null)))));
        assertThrows(BusinessRuleException.class, () -> familyResource.create(base.withTexts(List.of(
                new ProductFamilyDto.TextDto(Language.EN, "Name", "s".repeat(2_001),
                        null, null, List.of(), null, null)))));
        assertThrows(BusinessRuleException.class, () -> familyResource.create(base.withTexts(List.of(
                new ProductFamilyDto.TextDto(Language.EN, "Name", null,
                        "d".repeat(10_001), null, List.of(), null, null)))));
        assertThrows(BusinessRuleException.class, () -> familyResource.create(base.withTexts(List.of(
                new ProductFamilyDto.TextDto(Language.EN, "Name", null,
                        null, null, List.of("h".repeat(1_001)), null, null)))));

        assertEquals(before, entityManager.createQuery(
                "select count(item) from ProductFamilyEntity item", Long.class).getSingleResult());
    }

    @Test
    @TestTransaction
    void websiteRevisionTracksPublicPackageAndCollectionFeaturedPayloads() {
        FamilyContext context = completeFamilyContext("revision-public-payload");
        ProductEntity variant = product(
                context.family, "SKU-REVISION-PUBLIC", "revision-public",
                "Red", null, "#A91F32", 0);
        variant.categoryId = context.category.id;
        entityManager.persist(variant);

        ProductPackageEntity pack = new ProductPackageEntity();
        pack.family = context.family;
        pack.sourceKey = "revision-pack";
        pack.packageType = "CARTON";
        pack.position = 0;
        pack.lengthValue = new BigDecimal("20");
        pack.widthValue = new BigDecimal("15");
        pack.heightValue = new BigDecimal("10");
        pack.dimensionUnit = "cm";
        pack.piecesPerPackage = 6;
        pack.operational = true;
        context.family.packages.add(pack);
        entityManager.persist(pack);
        entityManager.flush();

        String initial = catalogRevisions.currentRevision();
        pack.sourceLocation = "internal-only-audit-location";
        entityManager.flush();
        assertEquals(initial, catalogRevisions.currentRevision(),
                "non-public package audit metadata must not alter the public digest");

        pack.piecesPerPackage = 12;
        entityManager.flush();
        String packageChanged = catalogRevisions.currentRevision();
        assertTrue(!initial.equals(packageChanged));

        ProductFamilyCollectionEntity primary = context.family.collections.stream()
                .filter(item -> item.primaryCollection).findFirst().orElseThrow();
        primary.collection.featuredProductId = variant.id;
        entityManager.flush();
        assertTrue(!packageChanged.equals(catalogRevisions.currentRevision()));
    }

    @Test
    void optionTupleDuplicatesAreAllowedOnlyWhileEveryChannelIsDraft() {
        ProductFamilyEntity family = family("draft-option-review");
        family.websiteStatus = PublicationState.DRAFT;
        ProductEntity first = product(
                family, "SKU-DRAFT-1", "draft-1", null, null, null, 0);
        ProductEntity second = product(
                family, "SKU-DRAFT-2", "draft-2", null, null, null, 1);

        ProductFamilyDto draft = ProductFamilyDto.from(
                family, List.of(), List.of(), List.of(), List.of(),
                List.of(first, second), json);
        assertTrue(!draft.publicationIssues().contains(FamilyVariantRules.OPTION_ISSUE));

        family.orderAppStatus = PublicationState.READY;
        ProductFamilyDto ready = ProductFamilyDto.from(
                family, List.of(), List.of(), List.of(), List.of(),
                List.of(first, second), json);
        assertTrue(ready.publicationIssues().contains(FamilyVariantRules.OPTION_ISSUE));

        second.variantPosition = 0;
        ProductFamilyDto collidingPositions = ProductFamilyDto.from(
                family, List.of(), List.of(), List.of(), List.of(),
                List.of(first, second), json);
        assertTrue(collidingPositions.publicationIssues().contains(
                FamilyVariantRules.POSITION_ISSUE));
    }

    @Test
    @TestTransaction
    void readyFamilyAllowsSameColourAtAnotherSizeButRejectsNormalizedDuplicateTuple() {
        FamilyContext context = completeFamilyContext("option-write-guard");
        ProductEntity source = product(
                context.family, "SKU-OPTION-S", "option-small",
                "Red", "Small", "#A91F32", 0);
        source.categoryId = context.category.id;
        entityManager.persist(source);
        entityManager.flush();

        Product large = productService.duplicate(source.id, null, null, "Large");
        assertEquals("Red", large.colour());
        assertEquals("Large", large.variantSize());
    }

    @Test
    @TestTransaction
    void readyFamilyRejectsANormalizedDuplicateOptionTuple() {
        FamilyContext context = completeFamilyContext("duplicate-option-guard");
        ProductEntity source = product(
                context.family, "SKU-DUPLICATE-S", "duplicate-small",
                "Red", "Small", "#A91F32", 0);
        source.categoryId = context.category.id;
        entityManager.persist(source);
        entityManager.flush();

        BusinessRuleException error = assertThrows(BusinessRuleException.class,
                () -> productService.duplicate(
                        source.id, " red ", "#A91F32", " small "));
        assertTrue(error.getMessage().contains(FamilyVariantRules.OPTION_ISSUE),
                error.getMessage());
    }

    @Test
    @TestTransaction
    void productEditCannotClearRequiredSwatchOrInactivateTheLastLiveMember() {
        FamilyContext context = completeFamilyContext("live-product-guard");
        ProductEntity source = product(
                context.family, "SKU-LIVE-GUARD", "live-guard",
                "Red", "Small", "#A91F32", 0);
        source.categoryId = context.category.id;
        entityManager.persist(source);
        entityManager.flush();

        Product current = products.findById(source.id).orElseThrow();
        BusinessRuleException swatch = assertThrows(BusinessRuleException.class,
                () -> productService.update(source.id,
                        current.withVariantAttributes("Red", "Small", "")));
        assertTrue(swatch.getMessage().contains("Kleurstaal"), swatch.getMessage());
    }

    @Test
    @TestTransaction
    void productEditCannotInactivateTheLastLiveMember() {
        FamilyContext context = completeFamilyContext("last-live-member-guard");
        ProductEntity source = product(
                context.family, "SKU-LAST-LIVE", "last-live",
                "Red", null, "#A91F32", 0);
        source.categoryId = context.category.id;
        entityManager.persist(source);
        entityManager.flush();

        BusinessRuleException error = assertThrows(BusinessRuleException.class,
                () -> productService.update(
                        source.id, products.findById(source.id).orElseThrow().withActive(false)));
        assertTrue(error.getMessage().contains("actieve variant"), error.getMessage());
    }

    @Test
    @TestTransaction
    void movingVariantCannotLeaveItsOldPublishedFamilyWithoutAPublicImage() {
        FamilyContext oldContext = completeFamilyContext("move-old-photo-guard");
        FamilyContext newContext = completeFamilyContext("move-new-photo-guard");
        ProductEntity moving = product(
                oldContext.family, "SKU-MOVE-OLD", "move-old",
                "Red", null, "#A91F32", 0);
        ProductEntity retained = product(
                oldContext.family, "SKU-MOVE-RETAIN", "move-retain",
                "Blue", null, "#6C8FC4", 1);
        moving.categoryId = oldContext.category.id;
        retained.categoryId = oldContext.category.id;
        entityManager.persist(moving);
        entityManager.persist(retained);
        entityManager.flush();
        ProductFamilyPhotoEntity onlyPhoto = oldContext.family.photos.getFirst();
        onlyPhoto.variantProduct = moving;
        onlyPhoto.variantExternalId = moving.canonicalVariantKey;
        onlyPhoto.variantColor = moving.colour;
        entityManager.flush();

        BusinessRuleException error = assertThrows(BusinessRuleException.class,
                () -> productService.assignFamily(moving.id, newContext.family.id));
        assertTrue(error.getMessage().contains("publiceerbare foto"), error.getMessage());
    }

    @Test
    @TestTransaction
    void movingVariantCannotCreateADuplicateTupleInTheNewPublishedFamily() {
        FamilyContext oldContext = completeFamilyContext("move-old-option-guard");
        FamilyContext newContext = completeFamilyContext("move-new-option-guard");
        ProductEntity moving = product(
                oldContext.family, "SKU-MOVE-DUP", "move-duplicate",
                "Red", "Large", "#A91F32", 0);
        ProductEntity retained = product(
                oldContext.family, "SKU-MOVE-OLD-RETAIN", "move-old-retained",
                "Blue", null, "#6C8FC4", 1);
        ProductEntity target = product(
                newContext.family, "SKU-MOVE-TARGET", "move-target",
                " red ", " large ", "#A91F32", 0);
        moving.categoryId = oldContext.category.id;
        retained.categoryId = oldContext.category.id;
        target.categoryId = newContext.category.id;
        entityManager.persist(moving);
        entityManager.persist(retained);
        entityManager.persist(target);
        entityManager.flush();

        BusinessRuleException error = assertThrows(BusinessRuleException.class,
                () -> productService.assignFamily(moving.id, newContext.family.id));
        assertTrue(error.getMessage().contains(FamilyVariantRules.OPTION_ISSUE),
                error.getMessage());
    }

    @Test
    @TestTransaction
    void legacyProductPutPreservesFamilyAndInventoryWhileDedicatedCommandCanUnlink() throws Exception {
        FamilyContext context = completeFamilyContext("legacy-put-family");
        ProductEntity selected = product(
                context.family, "SKU-LEGACY-PUT", "legacy-put",
                "Red", null, "#A91F32", 0);
        ProductEntity retained = product(
                context.family, "SKU-LEGACY-RETAIN", "legacy-retain",
                "Blue", null, "#6C8FC4", 1);
        selected.categoryId = context.category.id;
        retained.categoryId = context.category.id;
        selected.inventoryKnown = false;
        entityManager.persist(selected);
        entityManager.persist(retained);
        entityManager.flush();

        ProductDto current = ProductDto.from(products.findById(selected.id).orElseThrow());
        com.fasterxml.jackson.databind.node.ObjectNode legacyJson = json.valueToTree(current);
        legacyJson.remove("familyId");
        legacyJson.remove("inventoryKnown");
        ProductDto legacyRequest = json.treeToValue(legacyJson, ProductDto.class);

        Product preserved = productService.update(
                selected.id, legacyRequest.toDomainForUpdate(
                        products.findById(selected.id).orElseThrow()));
        assertEquals(context.family.id, preserved.familyId());
        assertTrue(!preserved.inventoryKnown());

        Product unlinked = productService.assignFamily(selected.id, null);
        assertNull(unlinked.familyId());
        assertNull(unlinked.familyKey());
        assertTrue(unlinked.photos().isEmpty());
    }

    @Test
    @TestTransaction
    void categoryTitleCopyAndPositionPropagateWithoutChangingStableKeysOrVariantCopy() {
        Category created = categoryService.create(new Category(
                null, "DISPLAY ROSES", "Display roses", "Original category description",
                "Signature", 0, "Displays", null));
        CategoryEntity category = entityManager.find(CategoryEntity.class, created.id());
        ProductCollectionEntity collection = entityManager.createQuery(
                        "from ProductCollectionEntity item where item.collectionKey = :key",
                        ProductCollectionEntity.class)
                .setParameter("key", "display-roses").getSingleResult();
        collection.eyebrow = "Signature collection";
        ProductFamilyEntity family = completePublishedFamily(
                "category-propagation", category, collection, 0);
        ProductEntity variant = product(
                family, "SKU-CATEGORY-COPY", "category-copy",
                "Red", null, "#A91F32", 0);
        variant.categoryId = category.id;
        variant.name = "Internal quote name";
        variant.description = "Internal quote description";
        entityManager.persist(variant);
        entityManager.flush();

        familyMemberCache.sync(family);
        entityManager.flush();
        entityManager.clear();
        ProductEntity preserved = entityManager.find(ProductEntity.class, variant.id);
        assertEquals("Internal quote name", preserved.name);
        assertEquals("Internal quote description", preserved.description);

        Category renamed = categoryService.update(created.id(), new Category(
                created.id(), created.code(), "Signature displays",
                "Updated public category description", "Updated signature", 2,
                "Signature mobile", null, null, null,
                categoryTexts("Signature displays", "Updated public category description",
                        "Updated signature", "Signature mobile", null),
                created.revision()));
        assertEquals("DISPLAY ROSES", renamed.code(),
                "the administrator-owned technical code remains stable");
        entityManager.flush();
        entityManager.clear();

        ProductFamilyEntity propagated = entityManager.find(ProductFamilyEntity.class, family.id);
        ProductCollectionEntity propagatedCollection = entityManager.find(
                ProductCollectionEntity.class, collection.id);
        assertEquals("display-roses", propagated.categoryKey);
        assertEquals("Signature displays", propagated.categoryName);
        assertEquals(2, propagated.categoryPosition);
        assertEquals("display-roses", propagatedCollection.collectionKey);
        assertEquals("Signature displays", propagatedCollection.name);
        assertEquals("Updated signature", propagatedCollection.eyebrow);
        assertEquals("Updated public category description", propagatedCollection.description);
        assertEquals(2, propagatedCollection.position);
        assertEquals("Signature mobile", propagatedCollection.mobileName);

        PublicFamilyCatalogDto.FamilyDto publicFamily = publicFamily("EN", family.publicHandle);
        assertEquals("display-roses", publicFamily.category().key());
        assertEquals("Updated public category description",
                publicFamily.category().description());

        BusinessRuleException duplicatePosition = assertThrows(BusinessRuleException.class,
                () -> categoryService.create(new Category(
                        null, "OTHER CATEGORY", "Other", null, 2)));
        assertTrue(duplicatePosition.getMessage().contains("positie"),
                duplicatePosition.getMessage());
    }

    @Test
    @TestTransaction
    void categoryTitleEditKeepsStableIdentityAndAllowsAnIncompleteTranslationWorkQueue() {
        FamilyContext context = completeFamilyContext("stable-category-title");
        ProductEntity variant = product(
                context.family, "SKU-STABLE-CATEGORY", "stable-category-variant",
                "Red", null, "#A91F32", 0);
        variant.categoryId = context.category.id;
        entityManager.persist(variant);
        context.category.eyebrow = "Stable collection eyebrow";
        context.category.texts.removeIf(text -> text.language != Language.EN);
        entityManager.flush();

        Category before = categoryService.get(context.category.id);
        String stableCategoryKey = CategoryPublicKey.from(before.code());
        Category saved = categoryService.update(before.id(), new Category(
                before.id(), before.code(), "Renamed public category title",
                before.description(), before.eyebrow(), before.position(),
                before.mobileName(), before.navigationName(), before.footerName(),
                before.featuredProductId(), before.texts(), before.revision()));

        assertEquals(before.id(), saved.id());
        assertEquals(before.code(), saved.code());
        assertEquals(stableCategoryKey, CategoryPublicKey.from(saved.code()));
        assertTrue(saved.revision() > before.revision());

        PublicProductTranslationsDto workQueue = publicTranslations.get(variant.id);
        assertEquals(context.family.id, workQueue.familyId());
        assertEquals(context.family.familyKey, workQueue.family().familyKey());
        assertEquals(context.family.publicHandle, workQueue.family().publicHandle());
        assertEquals(stableCategoryKey, workQueue.family().categoryKey());
        assertEquals(variant.id, workQueue.productId());
        assertEquals(context.family.id, workQueue.product().familyId());
        assertEquals(variant.canonicalVariantKey, workQueue.product().canonicalVariantKey());
        assertTrue(workQueue.family().publicationIssues().stream().anyMatch(issue ->
                        issue.contains(".category.nl.name")),
                workQueue.family().publicationIssues().toString());
        assertFalse(familyWrites.websiteBuildReady(),
                "an incomplete category locale must suppress the automatic deploy hook");
    }

    @Test
    @TestTransaction
    void categoryCreatesCanonicalPrimaryMembershipWithoutLettingAStaleFamilyCopyEditCollection() {
        Category created = categoryService.create(new Category(
                null, "NEW SIGNATURE", "New signature", "Category copy",
                "Curated roses", 0, "Signature", null));
        ProductCollectionEntity collection = entityManager.createQuery(
                        "from ProductCollectionEntity item where item.collectionKey = :key",
                        ProductCollectionEntity.class)
                .setParameter("key", "new-signature").getSingleResult();
        assertEquals("Curated roses", created.eyebrow());
        assertEquals("Curated roses", collection.eyebrow);

        ProductFamilyEntity family = family("automatic-primary-membership");
        family.websiteStatus = PublicationState.DRAFT;
        family.categoryId = created.id();
        family.categoryKey = "new-signature";
        family.categoryName = created.name();
        family.categoryPosition = created.position();
        familyCollections.replaceMemberships(family, List.of());
        familyCollections.alignPrimary(family);
        entityManager.persist(family);
        entityManager.flush();

        assertEquals(1, family.collections.size());
        assertTrue(family.collections.getFirst().primaryCollection);
        assertEquals(collection.id, family.collections.getFirst().collection.id);
        assertEquals("new-signature", family.collectionKey);

        collection.name = "Category-owned current name";
        collection.eyebrow = "Category-owned current eyebrow";
        collection.description = "Category-owned current description";
        collection.mobileName = "Current mobile";
        familyCollections.replaceMemberships(family, List.of(
                new FamilyCollectionAlignmentService.MembershipRequest(
                        "NEW SIGNATURE", 7, true)));
        familyCollections.alignPrimary(family);
        entityManager.flush();

        assertEquals("Category-owned current name", collection.name);
        assertEquals("Category-owned current eyebrow", collection.eyebrow);
        assertEquals("Category-owned current description", collection.description);
        assertEquals("Current mobile", collection.mobileName);
        assertEquals(7, family.collections.getFirst().position);

        Category cleared = categoryService.update(created.id(), new Category(
                created.id(), created.code(), created.name(), created.description(),
                null, created.position(), created.mobileName(), null, null,
                created.featuredProductId(), created.texts(), created.revision()));
        assertNull(cleared.eyebrow());
        assertNull(collection.eyebrow,
                "full category PUT must persist an explicit nullable eyebrow clear");
    }

    @Test
    @TestTransaction
    void categoryTranslationsRejectAStaleOptimisticRevision() {
        Category created = categoryService.create(new Category(
                null, "REVISION CATEGORY", "Revision category", "Initial",
                null, 0, null, null, null, null,
                List.of(new be.enrosed.catalog.domain.CategoryText(
                        Language.FR, "Catégorie", "Initiale", null,
                        "Catégorie mobile", "Navigation initiale", "Pied de page")), null));
        assertEquals(0L, created.revision());
        Category first = categoryService.update(created.id(), new Category(
                created.id(), created.code(), created.name(), created.description(),
                created.eyebrow(), created.position(), created.mobileName(),
                created.navigationName(), created.footerName(), created.featuredProductId(),
                List.of(new be.enrosed.catalog.domain.CategoryText(
                        Language.FR, "Catégorie", "Initiale", null,
                        "Catégorie mobile", "Navigation publiée", "Pied de page")),
                created.revision()));
        assertTrue(first.revision() > created.revision());

        BusinessRuleException stale = assertThrows(BusinessRuleException.class,
                () -> categoryService.update(created.id(), new Category(
                        created.id(), created.code(), created.name(), created.description(),
                        created.eyebrow(), created.position(), created.mobileName(),
                        created.navigationName(), created.footerName(),
                        created.featuredProductId(), List.of(
                                new be.enrosed.catalog.domain.CategoryText(
                                        Language.FR, "Catégorie", "Initiale", null,
                                        "Catégorie mobile", "Écrasement obsolète",
                                        "Pied de page")), created.revision())));
        assertTrue(stale.getMessage().contains("herlaad"));
        assertEquals("Navigation publiée", categoryService.get(created.id()).texts().stream()
                .filter(text -> text.language() == Language.FR).findFirst().orElseThrow()
                .navigationName());
    }

    @Test
    void categoryRevisionColumnHasAZeroDefaultForOnlineSchemaUpdates() {
        Object[] column = (Object[]) entityManager.createNativeQuery("""
                select column_default, is_nullable
                from information_schema.columns
                where table_name = 'CATEGORY' and column_name = 'REVISION'
                """).getSingleResult();
        assertTrue(String.valueOf(column[0]).contains("0"));
        assertEquals("NO", String.valueOf(column[1]));
    }

    @Test
    @TestTransaction
    void categoryDeleteIsBlockedByAnEmptyLegacyFamilyCollectionMembership() {
        Category created = categoryService.create(new Category(
                null, "LEGACY FAMILY OWNER", "Legacy owner", null,
                "Legacy", 0, null, null));
        ProductCollectionEntity collection = entityManager.createQuery(
                        "from ProductCollectionEntity item where item.collectionKey = :key",
                        ProductCollectionEntity.class)
                .setParameter("key", "legacy-family-owner").getSingleResult();
        ProductFamilyEntity legacy = family("empty-legacy-category-family");
        legacy.websiteStatus = PublicationState.DRAFT;
        legacy.categoryId = null;
        legacy.categoryKey = null;
        ProductFamilyCollectionEntity membership = new ProductFamilyCollectionEntity();
        membership.family = legacy;
        membership.collection = collection;
        membership.primaryCollection = true;
        legacy.collections.add(membership);
        entityManager.persist(legacy);
        entityManager.flush();

        BusinessRuleException error = assertThrows(BusinessRuleException.class,
                () -> categoryService.delete(created.id()));
        assertTrue(error.getMessage().contains("productfamilie"), error.getMessage());
    }

    @Test
    @TestTransaction
    void familyLifecycleEditsClearCategoryAndCollectionFeaturedPointers() {
        FamilyContext original = completeFamilyContext("featured-family-lifecycle");
        ProductEntity selected = product(
                original.family, "SKU-FAMILY-LIFECYCLE", "featured-family-lifecycle-key",
                "Red", null, "#A91F32", 0);
        selected.categoryId = original.category.id;
        entityManager.persist(selected);
        original.collection.featuredProductId = selected.id;
        original.category.featuredProductId = selected.id;
        entityManager.flush();

        CategoryEntity replacementCategory = category("REPLACEMENT CATEGORY", 1);
        ProductCollectionEntity replacementCollection = collection("replacement-category", 1);
        entityManager.persist(replacementCategory);
        entityManager.persist(replacementCollection);
        entityManager.flush();
        original.family.categoryId = replacementCategory.id;
        original.family.categoryKey = "replacement-category";
        original.family.categoryName = replacementCategory.name;
        original.family.categoryPosition = replacementCategory.position;
        original.family.collections.clear();
        ProductFamilyCollectionEntity replacementMembership = new ProductFamilyCollectionEntity();
        replacementMembership.family = original.family;
        replacementMembership.collection = replacementCollection;
        replacementMembership.primaryCollection = true;
        original.family.collections.add(replacementMembership);
        original.family.collectionKey = replacementCollection.collectionKey;
        featuredProducts.clearInvalidReferencesForFamily(original.family);

        assertNull(original.collection.featuredProductId);
        assertNull(original.category.featuredProductId);

        replacementCollection.featuredProductId = selected.id;
        replacementCategory.featuredProductId = selected.id;
        selected.categoryId = replacementCategory.id;
        original.family.websiteStatus = PublicationState.DRAFT;
        featuredProducts.clearInvalidReferencesForFamily(original.family);

        assertNull(replacementCollection.featuredProductId);
        assertNull(replacementCategory.featuredProductId);
    }

    @Test
    @TestTransaction
    void movingProductSynchronizesTheTargetFamilyCategoryCache() {
        CategoryEntity sourceCategory = category("SOURCE CATEGORY", 0);
        CategoryEntity targetCategory = category("TARGET CATEGORY", 1);
        entityManager.persist(sourceCategory);
        entityManager.persist(targetCategory);
        ProductFamilyEntity sourceFamily = family("source-category-family");
        sourceFamily.websiteStatus = PublicationState.DRAFT;
        sourceFamily.categoryId = sourceCategory.id;
        ProductFamilyEntity targetFamily = family("target-category-family");
        targetFamily.websiteStatus = PublicationState.DRAFT;
        targetFamily.categoryId = targetCategory.id;
        entityManager.persist(sourceFamily);
        entityManager.persist(targetFamily);
        entityManager.flush();
        ProductEntity moving = product(
                sourceFamily, "SKU-CROSS-CATEGORY", "cross-category",
                "Red", null, "#A91F32", 0);
        moving.categoryId = sourceCategory.id;
        entityManager.persist(moving);
        entityManager.flush();

        Product moved = productService.assignFamily(moving.id, targetFamily.id);

        assertEquals(targetFamily.id, moved.familyId());
        assertEquals(targetCategory.id, moved.categoryId());
    }

    private PublicFamilyCatalogDto.FamilyDto publicFamily(String language, String handle) {
        return publicFamily(CatalogChannel.WEBSITE, language, handle);
    }

    private PublicFamilyCatalogDto.FamilyDto publicFamily(
            CatalogChannel channel, String language, String handle) {
        Response response = publicFamilies.catalog(channel, language, null);
        PublicFamilyCatalogDto catalog = (PublicFamilyCatalogDto) response.getEntity();
        return catalog.families().stream()
                .filter(item -> handle.equals(item.publicHandle())).findFirst().orElseThrow();
    }

    private static PublicFamilyCatalogDto.VariantDto variant(
            PublicFamilyCatalogDto.FamilyDto family, long productId) {
        return family.variants().stream().filter(item -> item.id() == productId)
                .findFirst().orElseThrow();
    }

    private static ProductFamilyEntity family(String key) {
        ProductFamilyEntity family = new ProductFamilyEntity();
        family.familyKey = key;
        family.publicHandle = key;
        family.active = true;
        family.name = "Variant family";
        family.websiteStatus = PublicationState.PUBLISHED;
        family.orderAppStatus = PublicationState.DRAFT;
        family.catalogueStatus = PublicationState.DRAFT;
        addFamilyTexts(family);
        return family;
    }

    private ProductFamilyEntity completePublishedFamily(
            String key, CategoryEntity category,
            ProductCollectionEntity collection, int productPosition) {
        ProductFamilyEntity family = family(key);
        family.summary = "Public summary";
        family.description = "Public description";
        family.categoryId = category.id;
        family.categoryKey = CategoryPublicKey.from(category.code);
        family.categoryName = category.name;
        family.categoryPosition = category.position;
        family.collectionKey = collection.collectionKey;
        family.productPosition = productPosition;
        family.seoTitle = "Public SEO title";
        family.seoDescription = "Public SEO description";
        addFamilyTexts(family);
        addCategoryTexts(category);
        ProductFamilyCollectionEntity membership = new ProductFamilyCollectionEntity();
        membership.family = family;
        membership.collection = collection;
        membership.primaryCollection = true;
        family.collections.add(membership);
        entityManager.persist(family);
        entityManager.flush();
        ProductFamilyPhotoEntity global = photo(family, key + "-global", 0);
        entityManager.persist(global);
        entityManager.flush();
        return family;
    }

    private FamilyContext completeFamilyContext(String key) {
        CategoryEntity category = category(key.toUpperCase() + " CATEGORY", 0);
        ProductCollectionEntity collection = collection(key + "-category", 0);
        entityManager.persist(category);
        entityManager.persist(collection);
        entityManager.flush();
        return new FamilyContext(category, collection,
                completePublishedFamily(key, category, collection, 0));
    }

    private record FamilyContext(
            CategoryEntity category,
            ProductCollectionEntity collection,
            ProductFamilyEntity family) {}

    private static Product domainProduct(
            ProductFamilyEntity family, String sku, String colour,
            String size, String colourHex, int requestedPosition) {
        return new Product(
                null, sku, "Internal " + sku,
                new Dimensions(BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE),
                colour, size, colourHex, "Internal description " + sku,
                family.categoryId, null, true,
                family.id, null, null, requestedPosition, false,
                family.familyKey, null, PublicationState.DRAFT, PublicationState.DRAFT,
                Barcodes.none(), "0603",
                new Carton(new Dimensions(BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE),
                        1, BigDecimal.ONE),
                BigDecimal.ONE, Currency.USD, BigDecimal.ZERO,
                null, null, new BigDecimal("25"), BigDecimal.TEN,
                0, List.of(), java.util.Arrays.stream(Language.values())
                        .map(language -> new be.enrosed.catalog.domain.ProductText(
                                language, "Internal " + sku + " " + language.code(),
                                "Internal description " + sku + " " + language.code(),
                                colour == null ? null : colour + " " + language.code()))
                        .toList());
    }

    private static CategoryEntity category(String code, int position) {
        CategoryEntity category = new CategoryEntity();
        category.code = code;
        category.name = code + " name";
        category.description = "Category description";
        category.position = position;
        addCategoryTexts(category);
        return category;
    }

    private static ProductCollectionEntity collection(String key, int position) {
        ProductCollectionEntity collection = new ProductCollectionEntity();
        collection.collectionKey = key;
        collection.name = key + " name";
        collection.eyebrow = "Collection eyebrow";
        collection.description = "Collection description";
        collection.position = position;
        return collection;
    }

    private static ProductEntity product(
            ProductFamilyEntity family, String sku, String key, String colour,
            String size, String colourHex, int position) {
        ProductEntity product = new ProductEntity();
        product.sku = sku;
        product.name = "Base variant name";
        product.familyId = family.id;
        product.familyKey = family.familyKey;
        product.canonicalVariantKey = key;
        product.colour = colour;
        product.variantSize = size;
        product.colourHex = colourHex;
        product.variantPosition = position;
        product.active = true;
        product.inventoryKnown = true;
        product.piecesPerCarton = 1;
        product.productLengthCm = BigDecimal.ONE;
        product.productWidthCm = BigDecimal.ONE;
        product.productHeightCm = BigDecimal.ONE;
        product.cartonLengthCm = BigDecimal.ONE;
        product.cartonWidthCm = BigDecimal.ONE;
        product.cartonHeightCm = BigDecimal.ONE;
        product.fixedSalesPriceEur = BigDecimal.TEN;
        for (Language language : Language.values()) {
            text(product, language, "Variant " + language.code(),
                    colour == null ? null : colour + " " + language.code());
        }
        return product;
    }

    private static ProductPhotoEntity productPhoto(
            ProductEntity product, Long familyPhotoId, String storageKey, int position) {
        ProductPhotoEntity photo = new ProductPhotoEntity();
        photo.product = product;
        photo.familyPhotoId = familyPhotoId;
        photo.storageKey = storageKey;
        photo.originalFilename = storageKey + ".webp";
        photo.contentType = "image/webp";
        photo.sizeBytes = 1;
        photo.widthPx = 1;
        photo.heightPx = 1;
        photo.position = position;
        return photo;
    }

    private ProductPhotoContext productPhotoContext(String key) {
        ProductFamilyEntity family = family(key);
        family.websiteStatus = PublicationState.DRAFT;
        entityManager.persist(family);
        entityManager.flush();
        ProductEntity product = product(
                family, "SKU-" + key.toUpperCase(), key + "-variant",
                "Red", null, "#A91F32", 0);
        entityManager.persist(product);
        ProductFamilyPhotoEntity familyPhoto = photo(family, key + "-family", 0);
        entityManager.persist(familyPhoto);
        entityManager.flush();
        ProductPhotoEntity first = productPhoto(product, null, key + "-first", 0);
        ProductPhotoEntity second = productPhoto(product, null, key + "-second", 1);
        ProductPhotoEntity inherited = productPhoto(
                product, familyPhoto.id, familyPhoto.largeStorageKey, 2);
        for (ProductPhotoEntity photo : List.of(first, second, inherited)) {
            entityManager.persist(photo);
            product.photos.add(photo);
        }
        entityManager.flush();
        return new ProductPhotoContext(
                product.id, first.id, second.id, inherited.id, familyPhoto.id);
    }

    private record ProductPhotoContext(
            long productId, long firstOwnedPhotoId, long secondOwnedPhotoId,
            long inheritedPhotoId, long familyPhotoId) {}

    private static void text(
            ProductEntity product, Language language, String name, String colour) {
        ProductTextEntity text = product.texts.stream()
                .filter(existing -> existing.language == language).findFirst().orElse(null);
        if (text == null) {
            text = new ProductTextEntity();
            text.product = product;
            text.language = language;
            product.texts.add(text);
        }
        text.name = name;
        text.colour = colour;
        text.variantSize = be.enrosed.shared.VariantSizes.translate(
                product.variantSize, language);
    }

    private static ProductFamilyPhotoEntity photo(
            ProductFamilyEntity family, String sourceKey, int position) {
        ProductFamilyPhotoEntity photo = new ProductFamilyPhotoEntity();
        photo.family = family;
        photo.sourceKey = sourceKey;
        photo.smallStorageKey = sourceKey + "-small";
        photo.largeStorageKey = sourceKey + "-large";
        photo.smallContentType = "image/jpeg";
        photo.largeContentType = "image/jpeg";
        photo.smallWidthPx = 320;
        photo.smallHeightPx = 320;
        photo.largeWidthPx = 1200;
        photo.largeHeightPx = 1200;
        photo.position = position;
        photo.altTextsJson = allAlts("Ready image");
        family.photos.add(photo);
        return photo;
    }

    private static void addFamilyTexts(ProductFamilyEntity family) {
        if (!family.texts.isEmpty()) return;
        for (Language language : Language.values()) {
            ProductFamilyTextEntity text = new ProductFamilyTextEntity();
            text.family = family;
            text.language = language;
            text.name = "Family " + language.code();
            text.summary = "Summary " + language.code();
            text.description = "Description " + language.code();
            text.seoTitle = "SEO title " + language.code();
            text.seoDescription = "SEO description " + language.code();
            text.highlightsJson = "[]";
            family.texts.add(text);
        }
    }

    private static void addCategoryTexts(CategoryEntity category) {
        if (!category.texts.isEmpty()) return;
        for (Language language : Language.values()) {
            CategoryTextEntity text = new CategoryTextEntity();
            text.category = category;
            text.language = language;
            text.name = category.name + " " + language.code();
            text.description = category.description + " " + language.code();
            text.eyebrow = (category.eyebrow == null ? "Category" : category.eyebrow)
                    + " " + language.code();
            text.mobileName = category.mobileName;
            text.navigationName = category.navigationName;
            category.texts.add(text);
        }
    }

    private static List<be.enrosed.catalog.domain.CategoryText> categoryTexts(
            String name, String description, String eyebrow,
            String mobileName, String navigationName) {
        return java.util.Arrays.stream(Language.values())
                .map(language -> new be.enrosed.catalog.domain.CategoryText(
                        language, name, description, eyebrow, mobileName, navigationName))
                .toList();
    }

    private static String allAlts(String value) {
        return java.util.Arrays.stream(Language.values())
                .map(language -> "{\"language\":\"" + language.name()
                        + "\",\"alt\":\"" + value + " " + language.code() + "\"}")
                .collect(java.util.stream.Collectors.joining(",", "[", "]"));
    }
}
