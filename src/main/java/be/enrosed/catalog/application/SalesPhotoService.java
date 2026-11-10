package be.enrosed.catalog.application;

import be.enrosed.catalog.adapter.out.persistence.ProductFamilyPhotoEntity;
import be.enrosed.catalog.application.port.out.PhotoStorage;
import be.enrosed.catalog.domain.Photo;
import be.enrosed.catalog.domain.PhotoRole;
import be.enrosed.catalog.domain.Product;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityManager;
import jakarta.ws.rs.BadRequestException;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/**
 * The photo that stands for a product while selling: the ERP's salesPhoto rule (frontend
 * shared/sales-photo.ts) on the server, for screens that only get an image URL (the partner
 * portal). The explicit website lead first, else the inherited family photo, else the first.
 *
 * An inherited photo is served from the family's stored 480 px rendition for "small" (no decode
 * at all) and rendered from its large file for "medium"; an own photo goes through
 * {@link PhotoDeliveryService#render}, exactly as the product photo renditions do.
 */
@ApplicationScoped
public class SalesPhotoService {

    /** One chosen photo and the version that changes whenever the chosen file changes. */
    public record Choice(Photo photo, String version) {}

    /** Image bytes with what the response needs. */
    public record Image(InputStream data, String contentType, String filename) {}

    private final PhotoDeliveryService delivery;
    private final PhotoStorage storage;
    private final EntityManager entityManager;

    public SalesPhotoService(PhotoDeliveryService delivery, PhotoStorage storage, EntityManager entityManager) {
        this.delivery = delivery;
        this.storage = storage;
        this.entityManager = entityManager;
    }

    /** The salesPhoto rule; empty when the product has no photo at all. */
    public static Optional<Photo> salesPhoto(Product product) {
        List<Photo> photos = product == null || product.photos() == null ? List.of() : product.photos();
        return photos.stream().filter(photo -> photo.leads(PhotoRole.WEBSITE)).findFirst()
                .or(() -> photos.stream().filter(Photo::inherited).findFirst())
                .or(() -> photos.stream().findFirst());
    }

    /**
     * The chosen photo with a short version: a hash over the photo row and its stored file, so a
     * new lead, another family photo or a replaced file gives another URL. Never decodes.
     */
    public static Optional<Choice> choose(Product product) {
        return salesPhoto(product).map(photo -> new Choice(photo, version(photo)));
    }

    /** size is "small" (480 px) or "medium" (1280 px). */
    public Image render(Photo photo, String size) {
        if (!"small".equals(size) && !"medium".equals(size)) throw new BadRequestException("Onbekende fotoversie");
        if ("small".equals(size) && photo.inherited()) {
            /* A short read of its own: the caller's transaction may already be over. */
            String[] small = QuarkusTransaction.joiningExisting().call(() -> {
                ProductFamilyPhotoEntity family = entityManager.find(ProductFamilyPhotoEntity.class, photo.familyPhotoId());
                return family == null || family.smallStorageKey == null || family.smallStorageKey.isBlank() ? null
                        : new String[] {family.smallStorageKey, family.smallContentType};
            });
            if (small != null) {
                return new Image(storage.read(small[0]), small[1] == null ? photo.contentType() : small[1],
                        photo.originalFilename());
            }
        }
        PhotoDeliveryService.Source source = new PhotoDeliveryService.Source(
                photo.storageKey(), photo.originalFilename(), photo.contentType(), photo.sizeBytes(),
                photo.widthPx(), photo.heightPx(), () -> storage.read(photo.storageKey()));
        PhotoRenditionService.Rendition rendition = delivery.render(source, size, null, null);
        return new Image(new ByteArrayInputStream(rendition.bytes()), rendition.contentType(), rendition.filename());
    }

    private static String version(Photo photo) {
        String identity = (photo.inherited() ? "f" + photo.familyPhotoId() : "p" + photo.id())
                + ':' + photo.storageKey() + ':' + photo.sizeBytes();
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(identity.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 8);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
