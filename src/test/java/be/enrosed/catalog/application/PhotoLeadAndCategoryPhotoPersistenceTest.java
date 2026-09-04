package be.enrosed.catalog.application;

import be.enrosed.catalog.adapter.out.persistence.CategoryEntity;
import be.enrosed.catalog.adapter.out.persistence.ProductEntity;
import be.enrosed.catalog.domain.Category;
import be.enrosed.catalog.domain.Photo;
import be.enrosed.catalog.domain.PhotoRole;
import be.enrosed.catalog.domain.Product;
import be.enrosed.shared.BusinessRuleException;
import be.enrosed.shared.NotFoundException;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class PhotoLeadAndCategoryPhotoPersistenceTest {

    private static final byte[] FIRST_PNG = pngBytes((byte) 1);
    private static final byte[] SECOND_PNG = pngBytes((byte) 2);

    @Inject EntityManager entityManager;
    @Inject ProductService products;
    @Inject CategoryPhotoService categoryPhotos;

    @Test
    @TestTransaction
    void aPhotoCanOpenTheWebsiteOrTheCatalogueWhileTheFirstStaysTheInternalLead() {
        ProductEntity row = product("LEAD-ROLES");
        Product withFirst = products.addPhoto(row.id, "first.png", new ByteArrayInputStream(FIRST_PNG));
        Product withBoth = products.addPhoto(row.id, "second.png", new ByteArrayInputStream(SECOND_PNG));
        Photo first = withBoth.photos().get(0);
        Photo second = withBoth.photos().get(1);
        assertEquals(withFirst.photos().get(0).id(), first.id());

        Product websiteLed = products.setPhotoLead(row.id, second.id(), PhotoRole.WEBSITE, true);
        assertEquals(second.id(), websiteLed.photoFor(PhotoRole.WEBSITE).id());
        assertEquals(first.id(), websiteLed.primaryPhoto().id(), "the first photo stays the internal lead");
        assertEquals(first.id(), websiteLed.photoFor(PhotoRole.CATALOGUE).id(), "no catalogue choice reads as the first");
        assertEquals(List.of(second.id(), first.id()),
                websiteLed.photosFor(PhotoRole.WEBSITE).stream().map(Photo::id).toList());

        /* One lead per channel: the choice moves, it never doubles. */
        Product moved = products.setPhotoLead(row.id, first.id(), PhotoRole.WEBSITE, true);
        assertEquals(Set.of(PhotoRole.WEBSITE), moved.photos().get(0).leadFor());
        assertEquals(Set.of(), moved.photos().get(1).leadFor());

        Product cleared = products.setPhotoLead(row.id, first.id(), PhotoRole.WEBSITE, false);
        assertTrue(cleared.photos().stream().allMatch(photo -> photo.leadFor().isEmpty()));

        assertThrows(NotFoundException.class,
                () -> products.setPhotoLead(row.id, 999_999L, PhotoRole.CATALOGUE, true));
    }

    @Test
    @TestTransaction
    void aCategoryKeepsItsOwnPhotosApartFromItsCopy() throws Exception {
        CategoryEntity row = new CategoryEntity();
        row.code = "PHOTO-CAT";
        row.name = "Met foto";
        row.position = 9;
        entityManager.persist(row);
        entityManager.flush();

        Category one = categoryPhotos.add(row.id, "cover.png", new ByteArrayInputStream(FIRST_PNG));
        Category two = categoryPhotos.add(row.id, "detail.png", new ByteArrayInputStream(SECOND_PNG));
        assertEquals(1, one.photos().size());
        assertEquals(2, two.photos().size());
        assertEquals("cover.png", two.leadPhoto().originalFilename());
        assertEquals(List.of(0, 1), two.photos().stream().map(Photo::position).toList());

        Photo detail = two.photos().get(1);
        Category reordered = categoryPhotos.reorder(row.id, List.of(detail.id(), two.photos().get(0).id()));
        assertEquals("detail.png", reordered.leadPhoto().originalFilename());
        assertEquals(FIRST_PNG.length, categoryPhotos.photo(row.id, two.photos().get(0).id()).sizeBytes());

        assertThrows(BusinessRuleException.class,
                () -> categoryPhotos.reorder(row.id, List.of(detail.id())), "every photo exactly once");
        assertThrows(BusinessRuleException.class,
                () -> categoryPhotos.importFromUrl(row.id, "https://example.com/photo.jpg"),
                "only our own website is a source");

        Category removed = categoryPhotos.remove(row.id, detail.id());
        assertEquals(1, removed.photos().size());
        assertEquals("cover.png", removed.leadPhoto().originalFilename());
        assertThrows(NotFoundException.class, () -> categoryPhotos.photo(row.id, detail.id()));

        Category emptied = categoryPhotos.remove(row.id, removed.photos().get(0).id());
        assertNull(emptied.leadPhoto());
    }

    private ProductEntity product(String sku) {
        ProductEntity product = new ProductEntity();
        product.sku = sku;
        product.name = sku;
        product.active = true;
        product.piecesPerCarton = 1;
        entityManager.persist(product);
        entityManager.flush();
        return product;
    }

    private static byte[] pngBytes(byte marker) {
        return new byte[] {
                (byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a,
                marker, 0, 0, 0
        };
    }
}
