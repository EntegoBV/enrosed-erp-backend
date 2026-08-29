package be.enrosed.catalog.adapter.in.rest;

import be.enrosed.catalog.adapter.out.persistence.ProductEntity;
import be.enrosed.catalog.adapter.out.persistence.ProductFamilyEntity;
import be.enrosed.catalog.adapter.out.persistence.ProductFamilyPhotoEntity;
import be.enrosed.catalog.domain.CatalogChannel;
import be.enrosed.catalog.domain.PublicationState;
import be.enrosed.shared.Language;
import be.enrosed.shared.audit.ActivityDto;
import be.enrosed.shared.audit.ActivityLogEntity;
import be.enrosed.shared.audit.ActivityLogService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.ws.rs.core.Response;
import org.jboss.resteasy.reactive.multipart.FileUpload;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@QuarkusTest
@io.quarkus.test.security.TestSecurity(user = "emre",
        roles = be.enrosed.shared.security.AdminIdentityProvider.ADMIN_ROLE)
class ProductFamilyActivityTest {

    @Inject ProductFamilyResource resource;
    @Inject EntityManager entityManager;
    @Inject ObjectMapper json;
    @Inject ActivityLogService activityLog;

    @Test
    @TestTransaction
    void createAndFullEditLogMeaningfulChangesWithoutPersistingFreeCopy() throws Exception {
        ProductFamilyEntity template = family("audit-family-template");
        template.name = "Template name";
        entityManager.persist(template);
        entityManager.flush();

        ActivityLogEntity.deleteAll();
        ObjectNode createBody = json.valueToTree(resource.get(template.id));
        createBody.remove("id");
        createBody.put("familyKey", "audit-family-created");
        createBody.put("publicHandle", "audit-family-created");
        createBody.put("name", "Sensitive created family name");
        createBody.put("summary", "Sensitive created family summary");
        ProductFamilyDto createRequest = json.treeToValue(createBody, ProductFamilyDto.class);

        ProductFamilyDto created;
        try (Response response = resource.create(createRequest)) {
            assertEquals(Response.Status.CREATED.getStatusCode(), response.getStatus());
            created = (ProductFamilyDto) response.getEntity();
        }

        List<ActivityDto> afterCreate = activities(created.id());
        assertEquals(1, afterCreate.size());
        ActivityDto createdEvent = afterCreate.getFirst();
        assertEquals(ActivityLogService.ACTION_CREATED, createdEvent.action());
        assertEquals("Productreeks aangemaakt", createdEvent.summary());
        assertTrue(createdEvent.changes().stream().anyMatch(change ->
                change.field().equals("familyKey")));
        assertTrue(createdEvent.changes().stream().anyMatch(change ->
                change.field().equals("name")
                        && change.beforeValue() == null && change.afterValue() == null));
        assertNoCopy(createdEvent, "Sensitive created family name",
                "Sensitive created family summary");

        ObjectNode updateBody = json.valueToTree(created);
        updateBody.put("active", false);
        updateBody.put("name", "Sensitive renamed family name");
        updateBody.put("summary", "Sensitive edited family summary");
        updateBody.put("description", "Sensitive edited family description");
        updateBody.put("seoDescription", "Sensitive edited SEO description");
        updateBody.put("productPosition", 7);
        ProductFamilyDto updated = resource.update(
                created.id(), json.treeToValue(updateBody, ProductFamilyDto.class));

        List<ActivityDto> afterUpdate = activities(created.id());
        assertEquals(2, afterUpdate.size());
        ActivityDto updatedEvent = afterUpdate.getFirst();
        assertEquals(ActivityLogService.ACTION_UPDATED, updatedEvent.action());
        assertEquals("Productreeks bijgewerkt", updatedEvent.summary());
        assertEquals(Set.of("active", "name", "summary", "description", "productPosition",
                        "seoDescription"),
                updatedEvent.changes().stream().map(change -> change.field())
                        .collect(Collectors.toSet()));
        assertNoCopy(updatedEvent,
                "Sensitive renamed family name",
                "Sensitive edited family summary",
                "Sensitive edited family description",
                "Sensitive edited SEO description");

        resource.update(created.id(), updated);
        assertEquals(2, activities(created.id()).size(),
                "an identical full PUT must not add a duplicate log event");
    }

    @Test
    @TestTransaction
    void photoActionsAreDetailedAndIdempotentWhileAltCopyStaysPrivate() throws Exception {
        ProductFamilyEntity family = family("audit-family-photo-actions");
        entityManager.persist(family);
        entityManager.flush();
        ProductEntity variant = variant(family, "AUDIT-FAMILY-PHOTO-SKU");
        entityManager.persist(variant);
        ProductFamilyPhotoEntity first = photo(family, "audit-photo-first", 0);
        ProductFamilyPhotoEntity second = photo(family, "audit-photo-second", 1);
        family.photos.add(first);
        family.photos.add(second);
        entityManager.persist(first);
        entityManager.persist(second);
        entityManager.flush();
        ActivityLogEntity.deleteAll();

        List<Long> reversed = List.of(second.id, first.id);
        resource.reorderImages(family.id, reversed);
        resource.reorderImages(family.id, reversed);
        resource.linkImageVariant(
                family.id, first.id, new ProductFamilyResource.VariantLinkRequest(variant.id));
        resource.linkImageVariant(
                family.id, first.id, new ProductFamilyResource.VariantLinkRequest(variant.id));
        ProductFamilyResource.ImagePublicationRequest website =
                new ProductFamilyResource.ImagePublicationRequest(List.of(CatalogChannel.WEBSITE));
        resource.setImagePublication(family.id, first.id, website);
        resource.setImagePublication(family.id, first.id, website);
        ProductFamilyResource.AltRequest newAlt = new ProductFamilyResource.AltRequest(
                Language.EN, "Secret replacement alt text that must not enter the log");
        resource.setImageAlt(family.id, first.id, newAlt);
        resource.setImageAlt(family.id, first.id, newAlt);
        resource.deleteImage(family.id, second.id);

        List<ActivityDto> events = activities(family.id);
        assertEquals(5, events.size(), "retries that do not change state stay out of the logbook");
        assertEquals(Set.of(
                        "Fotovolgorde aangepast",
                        "Foto aan variant gekoppeld",
                        "Fotopublicatie aangepast",
                        "Alt-tekst aangepast",
                        "Foto verwijderd"),
                events.stream().map(ActivityDto::summary).collect(Collectors.toSet()));
        assertTrue(events.stream().anyMatch(event ->
                event.action().equals(ActivityLogService.ACTION_PHOTO_REORDERED)));
        assertTrue(events.stream().anyMatch(event ->
                event.action().equals(ActivityLogService.ACTION_PHOTO_DELETED)));
        ActivityDto altEvent = events.stream()
                .filter(event -> event.summary().equals("Alt-tekst aangepast"))
                .findFirst().orElseThrow();
        assertTrue(altEvent.changes().stream().anyMatch(change ->
                change.field().endsWith(".alt.EN")
                        && change.beforeValue() == null && change.afterValue() == null));
        assertNoCopy(altEvent,
                "Initial private alt copy",
                "Secret replacement alt text that must not enter the log");
    }

    @Test
    @TestTransaction
    void uploadLogsSafeImageMetadataOnceWithoutFilenameOrBytes() throws Exception {
        ProductFamilyEntity family = family("audit-family-upload");
        entityManager.persist(family);
        entityManager.flush();
        ActivityLogEntity.deleteAll();

        Path upload = Files.createTempFile("audit-family-upload", ".png");
        BufferedImage image = new BufferedImage(64, 48, BufferedImage.TYPE_INT_RGB);
        ImageIO.write(image, "png", upload.toFile());
        FileUpload file = mock(FileUpload.class);
        when(file.uploadedFile()).thenReturn(upload);
        when(file.fileName()).thenReturn("private-original-filename.png");

        try {
            resource.uploadImage(family.id, file, null, null, null);
            resource.uploadImage(family.id, file, null, null, null);

            List<ActivityDto> events = activities(family.id);
            assertEquals(1, events.size());
            ActivityDto uploadEvent = events.getFirst();
            assertEquals(ActivityLogService.ACTION_PHOTO_ADDED, uploadEvent.action());
            assertEquals("Foto toegevoegd", uploadEvent.summary());
            assertTrue(uploadEvent.changes().stream().anyMatch(change ->
                    change.field().endsWith(".widthPx")
                            && change.afterValue().equals("64")));
            assertTrue(uploadEvent.changes().stream().anyMatch(change ->
                    change.field().endsWith(".heightPx")
                            && change.afterValue().equals("48")));
            assertNoCopy(uploadEvent, "private-original-filename.png");
        } finally {
            Files.deleteIfExists(upload);
        }
    }

    private List<ActivityDto> activities(long familyId) {
        return activityLog.list(
                null,
                ActivityLogService.ENTITY_PRODUCT_FAMILY,
                String.valueOf(familyId),
                null,
                100).items();
    }

    private void assertNoCopy(ActivityDto event, String... values) throws Exception {
        String serialized = json.writeValueAsString(event.changes());
        for (String value : values) {
            assertFalse(serialized.contains(value), "activity details copied private text: " + value);
        }
    }

    private static ProductFamilyEntity family(String key) {
        ProductFamilyEntity family = new ProductFamilyEntity();
        family.familyKey = key;
        family.publicHandle = key;
        family.name = "Private family name " + key;
        family.active = true;
        family.websiteStatus = PublicationState.DRAFT;
        family.orderAppStatus = PublicationState.DRAFT;
        family.catalogueStatus = PublicationState.DRAFT;
        return family;
    }

    private static ProductEntity variant(ProductFamilyEntity family, String sku) {
        ProductEntity product = new ProductEntity();
        product.sku = sku;
        product.name = "Audit variant";
        product.familyId = family.id;
        product.familyKey = family.familyKey;
        product.canonicalVariantKey = family.familyKey + "-variant";
        product.active = true;
        product.inventoryKnown = true;
        product.piecesPerCarton = 1;
        return product;
    }

    private static ProductFamilyPhotoEntity photo(
            ProductFamilyEntity family, String sourceKey, int position) {
        ProductFamilyPhotoEntity photo = new ProductFamilyPhotoEntity();
        photo.family = family;
        photo.sourceKey = sourceKey;
        photo.originalFilename = "private-source-name-" + position + ".jpg";
        photo.originalWidthPx = 800;
        photo.originalHeightPx = 600;
        photo.smallStorageKey = sourceKey + "-small";
        photo.smallContentType = "image/jpeg";
        photo.smallWidthPx = 400;
        photo.smallHeightPx = 300;
        photo.largeStorageKey = sourceKey + "-large";
        photo.largeContentType = "image/jpeg";
        photo.largeWidthPx = 800;
        photo.largeHeightPx = 600;
        photo.position = position;
        photo.altTextSource = "TEST";
        photo.altTextsJson = "[{\"language\":\"EN\",\"alt\":\"Initial private alt copy\"}]";
        photo.publishedChannelsJson = "[]";
        return photo;
    }
}
