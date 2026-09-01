package be.enrosed.catalog.application;

import be.enrosed.catalog.adapter.out.persistence.CatalogDaos;
import be.enrosed.catalog.adapter.out.persistence.ProductEntity;
import be.enrosed.catalog.adapter.out.persistence.ProductSupplierAgreementPhotoEntity;
import be.enrosed.catalog.application.port.out.PhotoStorage;
import be.enrosed.shared.BusinessRuleException;
import be.enrosed.shared.NotFoundException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import jakarta.persistence.LockModeType;
import jakarta.transaction.Transactional;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Manages the ordered, English-captioned evidence photos agreed with a product's supplier.
 *
 * <p>Every operation derives its supplier scope from the product at request time. Rows from a
 * former supplier are retained as history, but their metadata and bytes both become inaccessible
 * until that supplier is assigned to the product again.</p>
 */
@ApplicationScoped
public class ProductSupplierAgreementPhotoService {

    public static final int MAX_CAPTION_CODE_POINTS = 500;

    private final CatalogDaos.Products products;
    private final CatalogDaos.SupplierAgreementPhotos photos;
    private final PhotoStorage storage;
    private final Event<ProductPhotoCleanup.UploadReady> uploadCleanup;
    private final Event<ProductPhotoCleanup.DeleteReady> deleteCleanup;

    @Inject
    public ProductSupplierAgreementPhotoService(
            CatalogDaos.Products products,
            CatalogDaos.SupplierAgreementPhotos photos,
            PhotoStorage storage,
            Event<ProductPhotoCleanup.UploadReady> uploadCleanup,
            Event<ProductPhotoCleanup.DeleteReady> deleteCleanup) {
        this.products = products;
        this.photos = photos;
        this.storage = storage;
        this.uploadCleanup = uploadCleanup;
        this.deleteCleanup = deleteCleanup;
    }

    @Transactional(Transactional.TxType.SUPPORTS)
    public List<AgreementPhoto> list(long productId) {
        ProductEntity product = product(productId, false);
        if (product.supplierId == null) return List.of();
        return currentEntities(productId, product.supplierId).stream()
                .map(ProductSupplierAgreementPhotoService::toView)
                .toList();
    }

    @Transactional
    public AgreementPhoto upload(
            long productId, String filename, InputStream data, String englishCaption) {
        String caption = normalizeCaption(englishCaption);
        PhotoUploadPolicy.ValidatedPhoto upload = PhotoUploadPolicy.validate(filename, data);
        ProductEntity product = product(productId, true);
        long supplierId = requiredSupplier(product);

        PhotoStorage.Stored stored = storage.store(
                upload.originalFilename(), upload.contentType(), upload.bytes());
        uploadCleanup.fire(new ProductPhotoCleanup.UploadReady(productId, stored.storageKey()));

        ProductSupplierAgreementPhotoEntity entity = new ProductSupplierAgreementPhotoEntity();
        entity.productId = productId;
        entity.supplierId = supplierId;
        entity.storageKey = stored.storageKey();
        entity.originalFilename = upload.originalFilename();
        entity.contentType = upload.contentType();
        entity.sizeBytes = stored.sizeBytes();
        entity.widthPx = stored.widthPx();
        entity.heightPx = stored.heightPx();
        entity.position = nextPosition(productId, supplierId);
        entity.captionEn = caption;
        photos.persist(entity);
        photos.flush();
        return toView(entity);
    }

    @Transactional(Transactional.TxType.SUPPORTS)
    public AgreementPhoto get(long productId, long photoId) {
        ProductEntity product = product(productId, false);
        return toView(currentEntity(product, photoId));
    }

    @Transactional(Transactional.TxType.SUPPORTS)
    public AgreementPhotoFile open(long productId, long photoId) {
        ProductEntity product = product(productId, false);
        ProductSupplierAgreementPhotoEntity entity = currentEntity(product, photoId);
        return new AgreementPhotoFile(toView(entity), storage.read(entity.storageKey));
    }

    @Transactional
    public AgreementPhoto updateCaption(
            long productId, long photoId, String englishCaption) {
        String caption = normalizeCaption(englishCaption);
        ProductEntity product = product(productId, true);
        ProductSupplierAgreementPhotoEntity entity = currentEntity(product, photoId);
        entity.captionEn = caption;
        photos.flush();
        return toView(entity);
    }

    /** The request must name every photo in the current supplier scope exactly once. */
    @Transactional
    public List<AgreementPhoto> reorder(long productId, List<Long> photoIdsInOrder) {
        ProductEntity product = product(productId, true);
        long supplierId = requiredSupplier(product);
        List<ProductSupplierAgreementPhotoEntity> current = currentEntities(productId, supplierId);
        List<Long> wanted = photoIdsInOrder == null
                ? List.of() : new ArrayList<>(photoIdsInOrder);

        boolean invalid = wanted.stream().anyMatch(Objects::isNull)
                || wanted.size() != current.size()
                || new HashSet<>(wanted).size() != wanted.size()
                || current.stream().anyMatch(photo -> !wanted.contains(photo.id));
        if (invalid) {
            throw new BusinessRuleException(
                    "De volgorde moet elke leveranciersafspraakfoto exact één keer bevatten");
        }

        return applyOrder(current, wanted).stream().map(ProductSupplierAgreementPhotoService::toView)
                .toList();
    }

    @Transactional
    public void delete(long productId, long photoId) {
        ProductEntity product = product(productId, true);
        long supplierId = requiredSupplier(product);
        ProductSupplierAgreementPhotoEntity target = currentEntity(product, photoId);
        String storageKey = target.storageKey;
        photos.delete(target);
        photos.flush();

        List<ProductSupplierAgreementPhotoEntity> remaining = currentEntities(productId, supplierId);
        applyOrder(remaining, remaining.stream().map(photo -> photo.id).toList());
        deleteCleanup.fire(new ProductPhotoCleanup.DeleteReady(List.of(storageKey)));
    }

    /** Called by product deletion; unlike normal reads, this deliberately includes old suppliers. */
    @Transactional
    public void deleteAllForProduct(long productId) {
        List<ProductSupplierAgreementPhotoEntity> all = photos.list("productId", productId);
        if (all.isEmpty()) return;
        List<String> storageKeys = all.stream().map(photo -> photo.storageKey).distinct().toList();
        photos.delete("productId", productId);
        photos.flush();
        deleteCleanup.fire(new ProductPhotoCleanup.DeleteReady(storageKeys));
    }

    private ProductEntity product(long productId, boolean lock) {
        ProductEntity product = lock
                ? products.findById(productId, LockModeType.PESSIMISTIC_WRITE)
                : products.findById(productId);
        if (product == null) throw new NotFoundException("Product", productId);
        return product;
    }

    private ProductSupplierAgreementPhotoEntity currentEntity(
            ProductEntity product, long photoId) {
        if (product.supplierId == null) {
            throw new NotFoundException("Leveranciersafspraakfoto", photoId);
        }
        long supplierId = product.supplierId;
        return photos.find(
                        "id = ?1 and productId = ?2 and supplierId = ?3",
                        photoId, product.id, supplierId)
                .firstResultOptional()
                .orElseThrow(() -> new NotFoundException("Leveranciersafspraakfoto", photoId));
    }

    private List<ProductSupplierAgreementPhotoEntity> currentEntities(
            long productId, long supplierId) {
        return photos.list(
                "productId = ?1 and supplierId = ?2 order by position, id",
                productId, supplierId);
    }

    private int nextPosition(long productId, long supplierId) {
        return currentEntities(productId, supplierId).stream()
                .mapToInt(photo -> photo.position)
                .max()
                .orElse(-1) + 1;
    }

    /**
     * Uses a temporary disjoint range so a database uniqueness constraint also permits swaps.
     * The enclosing transaction means no caller can observe the temporary positions.
     */
    private List<ProductSupplierAgreementPhotoEntity> applyOrder(
            List<ProductSupplierAgreementPhotoEntity> current, List<Long> wanted) {
        if (current.isEmpty()) return List.of();
        for (int index = 0; index < current.size(); index++) {
            current.get(index).position = Integer.MIN_VALUE + index;
        }
        photos.flush();

        Map<Long, ProductSupplierAgreementPhotoEntity> byId = new HashMap<>();
        current.forEach(photo -> byId.put(photo.id, photo));
        List<ProductSupplierAgreementPhotoEntity> ordered = new ArrayList<>(wanted.size());
        for (int index = 0; index < wanted.size(); index++) {
            ProductSupplierAgreementPhotoEntity photo = byId.get(wanted.get(index));
            photo.position = index;
            ordered.add(photo);
        }
        photos.flush();
        return List.copyOf(ordered);
    }

    private static long requiredSupplier(ProductEntity product) {
        if (product.supplierId == null) {
            throw new BusinessRuleException(
                    "Koppel eerst een leverancier aan het product om afsprakenfoto's te beheren");
        }
        return product.supplierId;
    }

    static String normalizeCaption(String caption) {
        if (caption == null) return null;
        String normalized = caption.strip();
        if (normalized.isEmpty()) return null;
        if (normalized.codePointCount(0, normalized.length()) > MAX_CAPTION_CODE_POINTS) {
            throw new BusinessRuleException(
                    "Het Engelse bijschrift mag maximaal 500 tekens bevatten");
        }
        return normalized;
    }

    private static AgreementPhoto toView(ProductSupplierAgreementPhotoEntity entity) {
        return new AgreementPhoto(
                entity.id,
                entity.productId,
                entity.supplierId,
                entity.position,
                entity.captionEn,
                entity.originalFilename,
                entity.contentType,
                entity.sizeBytes,
                entity.widthPx,
                entity.heightPx);
    }

    public record AgreementPhoto(
            long id,
            long productId,
            long supplierId,
            int position,
            String caption,
            String originalFilename,
            String contentType,
            long sizeBytes,
            Integer widthPx,
            Integer heightPx) {}

    public record AgreementPhotoFile(AgreementPhoto photo, InputStream data) {}
}
