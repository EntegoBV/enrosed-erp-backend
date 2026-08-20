package be.enrosed.catalog.application;

import be.enrosed.catalog.adapter.out.persistence.CanonicalCatalogDaos;
import be.enrosed.catalog.adapter.out.persistence.ProductFamilyEntity;
import be.enrosed.catalog.application.port.out.PhotoStorage;
import be.enrosed.catalog.application.port.out.ProductRepository;
import be.enrosed.catalog.domain.Barcodes;
import be.enrosed.catalog.domain.CatalogChannel;
import be.enrosed.catalog.domain.Photo;
import be.enrosed.catalog.domain.Product;
import be.enrosed.catalog.domain.ProductText;
import be.enrosed.catalog.domain.PublicationState;
import be.enrosed.shared.BusinessRuleException;
import be.enrosed.shared.NotFoundException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import java.io.InputStream;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Everything that happens to a product.
 *
 * The photo series hangs off it too: the file goes to storage, the order and
 * the metadata stay with the product. The first photo is the primary one and
 * appears on lists and documents.
 */
@ApplicationScoped
public class ProductService {

    private final ProductRepository products;
    private final PhotoStorage photoStorage;
    private final ProductValidator validator;
    private final PhotoReferenceService photoReferences;
    private final CanonicalCatalogDaos.Families families;

    @Inject
    public ProductService(
            ProductRepository products, PhotoStorage photoStorage, ProductValidator validator,
            PhotoReferenceService photoReferences, CanonicalCatalogDaos.Families families) {
        this.products = products;
        this.photoStorage = photoStorage;
        this.validator = validator;
        this.photoReferences = photoReferences;
        this.families = families;
    }

    /** Test compatibility for pure domain tests that do not share family photo blobs. */
    public ProductService(ProductRepository products, PhotoStorage photoStorage,
                          ProductValidator validator) {
        this(products, photoStorage, validator, null, null);
    }

    public List<Product> list() {
        return products.findAll();
    }

    public List<Product> listBySupplier(long supplierId) {
        return products.findBySupplier(supplierId);
    }

    public Product get(long id) {
        return products.findById(id).orElseThrow(() -> new NotFoundException("Product", id));
    }

    @Transactional
    public Product create(Product product) {
        Product withSku = product.sku() == null || product.sku().isBlank()
                ? product.withSku(nextSku())
                : product;
        Product prepared = canonicalFamilyMetadata(withSku.withPublicationMetadata(
                normalizeOptional(withSku.familyKey()), normalizeHandle(withSku.publicHandle()),
                withSku.websiteStatus() == null ? PublicationState.DRAFT : withSku.websiteStatus(),
                withSku.orderAppStatus() == null ? PublicationState.DRAFT : withSku.orderAppStatus()));
        validator.validate(prepared);
        ensureUniqueSku(prepared.sku(), null);
        ensureUniqueHandle(prepared.publicHandle(), null);
        ensurePublishable(prepared);
        return products.save(prepared);
    }

    @Transactional
    public Product update(long id, Product changes) {
        Product current = get(id);
        /* The photo series is managed through the photo methods, not an update. */
        Product merged = new Product(
                current.id(),
                changes.sku() == null || changes.sku().isBlank() ? current.sku() : changes.sku(),
                changes.name(),
                changes.dimensions(),
                changes.colour(),
                changes.description(),
                changes.categoryId(),
                changes.supplierId(),
                changes.active(),
                /* familyId is an editable nullable field: null explicitly unlinks the variant. */
                changes.familyId(),
                changes.canonicalVariantKey() == null
                        ? current.canonicalVariantKey() : normalizeOptional(changes.canonicalVariantKey()),
                changes.canonicalBarcode() == null
                        ? current.canonicalBarcode() : normalizeOptional(changes.canonicalBarcode()),
                changes.variantPosition(),
                changes.inventoryKnown(),
                changes.familyKey() == null
                        ? current.familyKey() : normalizeOptional(changes.familyKey()),
                changes.publicHandle() == null
                        ? current.publicHandle() : normalizeHandle(changes.publicHandle()),
                changes.websiteStatus() == null
                        ? current.publicationState(CatalogChannel.WEBSITE) : changes.websiteStatus(),
                changes.orderAppStatus() == null
                        ? current.publicationState(CatalogChannel.ORDER_APP) : changes.orderAppStatus(),
                changes.barcodes(),
                changes.hsCode(),
                changes.carton(),
                changes.exwPrice(),
                changes.exwCurrency(),
                changes.extraUnitCost(),
                current.landedCostEur(),
                current.landedCostSource(),
                changes.markupPct(),
                changes.fixedSalesPriceEur(),
                /* Stock belongs to purchasing, not to a product update. */
                current.stockQuantity(),
                current.photos(),
                /* Translations travel with the form; when the field is not
                   sent along, what was there stays. */
                changes.texts().isEmpty() ? current.texts() : changes.texts());
        merged = canonicalFamilyMetadata(merged);
        validator.validate(merged);
        ensureUniqueSku(merged.sku(), current.id());
        ensureUniqueHandle(merged.publicHandle(), current.id());
        ensurePublishable(merged);
        return products.save(merged);
    }

    /**
     * Makes a copy of a product.
     *
     * Meant for the same article in another colour: all sizes, prices and
     * packaging data come along, the photos and barcodes do not. Those differ
     * per colour, and a copied barcode is worse than none - two articles with
     * the same EAN cause a problem in the customer's warehouse that nobody
     * spots right away.
     */
    @Transactional
    public Product duplicate(long id, String newColour) {
        Product source = get(id);
        return create(new Product(
                null, null, source.name(), source.dimensions(),
                newColour == null || newColour.isBlank() ? source.colour() : newColour,
                source.description(),
                source.categoryId(), source.supplierId(), source.active(),
                source.familyId(), null, null, source.variantPosition() + 1, false,
                source.familyKey(), null, PublicationState.DRAFT, PublicationState.DRAFT,
                Barcodes.none(), source.hsCode(), source.carton(),
                source.exwPrice(), source.exwCurrency(), source.extraUnitCost(),
                source.landedCostEur(), source.landedCostSource(),
                source.markupPct(), source.fixedSalesPriceEur(),
                /* Stock starts at zero: this is a new article. */
                0, List.of(),
                /* Translated names and descriptions come along; the per-language
                   colour does not, because it was just changed here and would
                   otherwise keep naming the old colour in every language. */
                source.texts().stream()
                        .map(text -> new ProductText(text.language(), text.name(),
                                text.description(), null))
                        .filter(text -> !text.isEmpty())
                        .toList()));
    }

    @Transactional
    public void delete(long id) {
        Product product = get(id);
        ProductRepository.ReferenceCounts references = products.referenceCounts(id);
        if (references.total() > 0) {
            throw new BusinessRuleException(deleteBlockedMessage(product, references));
        }
        products.deleteById(id);
        draftEmptyFamily(product.familyId());
        product.photos().forEach(photo -> deleteBlob(photo.storageKey()));
    }

    /**
     * Records the cost price coming out of a purchase calculation. Called by
     * the sourcing side whenever a calculation is applied.
     */
    @Transactional
    public void applyLandedCost(long productId, BigDecimal landedCostEur, String source) {
        Product updated = get(productId).withLandedCost(landedCostEur, source);
        ensurePublishable(updated);
        products.save(updated);
    }

    /**
     * Books stock in or out.
     *
     * Positive when a purchase order is received, negative when a sales order
     * goes out the door. Stock may sink below zero - that is a signal the
     * bookkeeping is off, and hiding it by silently clamping to zero helps
     * nobody.
     */
    @Transactional
    public void adjustStock(long productId, int delta) {
        if (!products.adjustStock(productId, delta)) {
            throw new NotFoundException("Product", productId);
        }
    }

    /* ------------------------------------------------------------ fotos */

    /**
     * Adds a validated photo of at most 25 MB. The original bytes go to
     * storage without rescaling or recompression.
     */
    @Transactional
    public Product addPhoto(long productId, String filename, InputStream data) {
        Product product = get(productId);
        PhotoUploadPolicy.ValidatedPhoto upload = PhotoUploadPolicy.validate(filename, data);
        PhotoStorage.Stored stored = photoStorage.store(
                upload.originalFilename(), upload.contentType(), upload.bytes());

        List<Photo> photos = new ArrayList<>(product.photos());
        photos.add(new Photo(null, stored.storageKey(), upload.originalFilename(), upload.contentType(),
                stored.sizeBytes(), stored.widthPx(), stored.heightPx(), photos.size()));

        return products.save(product.withPhotos(photos));
    }

    @Transactional
    public Product removePhoto(long productId, long photoId) {
        Product product = get(productId);
        Photo target = product.photos().stream()
                .filter(photo -> photo.id() != null && photo.id() == photoId)
                .findFirst()
                .orElseThrow(() -> new NotFoundException("Foto", photoId));

        List<Photo> photos = new ArrayList<>(product.photos());
        photos.remove(target);
        Product updated = product.withPhotos(renumber(photos));
        ensurePublishable(updated);
        Product saved = products.save(updated);
        deleteBlob(target.storageKey());
        return saved;
    }

    /** Orders the series by the given ids; the first becomes the primary photo. */
    @Transactional
    public Product reorderPhotos(long productId, List<Long> photoIdsInOrder) {
        Product product = get(productId);
        List<Photo> photos = new ArrayList<>(product.photos());

        List<Photo> ordered = new ArrayList<>();
        for (Long photoId : photoIdsInOrder) {
            photos.stream()
                    .filter(photo -> photo.id() != null && photo.id().equals(photoId))
                    .findFirst()
                    .ifPresent(photo -> { ordered.add(photo); });
        }
        /* Whatever is not named keeps its relative order at the back. */
        photos.stream().filter(photo -> !ordered.contains(photo)).forEach(ordered::add);

        return products.save(product.withPhotos(renumber(ordered)));
    }

    public Photo photo(long productId, long photoId) {
        return get(productId).photos().stream()
                .filter(photo -> photo.id() != null && photo.id() == photoId)
                .findFirst()
                .orElseThrow(() -> new NotFoundException("Foto", photoId));
    }

    public InputStream photoData(String storageKey) {
        return photoStorage.read(storageKey);
    }

    /* ---------------------------------------------------------- helpers */

    private void ensureUniqueSku(String sku, Long currentId) {
        products.findBySku(sku).ifPresent(existing -> {
            if (currentId == null || !currentId.equals(existing.id())) {
                throw new BusinessRuleException("SKU " + sku + " bestaat al");
            }
        });
    }

    private void ensureUniqueHandle(String publicHandle, Long currentId) {
        if (publicHandle == null) return;
        products.findByPublicHandle(publicHandle).ifPresent(existing -> {
            if (currentId == null || !currentId.equals(existing.id())) {
                throw new BusinessRuleException("Publieke handle " + publicHandle + " bestaat al");
            }
        });
    }

    private static void ensurePublishable(Product product) {
        boolean published = product.publicationState(CatalogChannel.WEBSITE) == PublicationState.PUBLISHED
                || product.publicationState(CatalogChannel.ORDER_APP) == PublicationState.PUBLISHED;
        if (published && !product.publicationIssues().isEmpty()) {
            throw new BusinessRuleException(
                    "Product kan nog niet gepubliceerd worden: "
                            + String.join("; ", product.publicationIssues()));
        }
    }

    /** Family publication and URL identity are family-owned, never copied onto unique flat SKUs. */
    private Product canonicalFamilyMetadata(Product product) {
        if (product.familyId() == null || families == null) return product;
        ProductFamilyEntity family = families.findById(product.familyId());
        if (family == null) {
            throw new BusinessRuleException("Onbekende productfamilie " + product.familyId());
        }
        return product.withPublicationMetadata(
                family.familyKey, null, PublicationState.DRAFT, PublicationState.DRAFT);
    }

    private static String normalizeOptional(String value) {
        if (value == null || value.isBlank()) return null;
        return value.trim();
    }

    private static String normalizeHandle(String value) {
        String normalized = normalizeOptional(value);
        return normalized == null ? null : normalized.toLowerCase(Locale.ROOT);
    }

    private String nextSku() {
        int highest = products.findAll().stream()
                .map(Product::sku)
                .filter(sku -> sku != null && sku.startsWith("ENR-P"))
                .map(sku -> sku.replaceAll("\\D", ""))
                .filter(digits -> !digits.isBlank())
                .mapToInt(Integer::parseInt)
                .max()
                .orElse(0);
        return String.format("ENR-P%02d", highest + 1);
    }

    private static List<Photo> renumber(List<Photo> photos) {
        List<Photo> result = new ArrayList<>(photos.size());
        for (int i = 0; i < photos.size(); i++) {
            Photo photo = photos.get(i);
            result.add(new Photo(photo.id(), photo.storageKey(), photo.originalFilename(),
                    photo.contentType(), photo.sizeBytes(), photo.widthPx(), photo.heightPx(), i));
        }
        return result;
    }

    private void deleteBlob(String storageKey) {
        if (photoReferences == null) photoStorage.delete(storageKey);
        else photoReferences.deleteIfUnreferenced(storageKey);
    }

    /** Preserve family content and media, but never leave an empty family publicly visible. */
    private void draftEmptyFamily(Long familyId) {
        if (familyId == null || families == null || products.countActiveByFamily(familyId) > 0) return;
        ProductFamilyEntity family = families.findById(familyId);
        if (family == null) return;
        family.websiteStatus = PublicationState.DRAFT;
        family.orderAppStatus = PublicationState.DRAFT;
        family.catalogueStatus = PublicationState.DRAFT;
        family.updatedAt = Instant.now();
    }

    private static String deleteBlockedMessage(
            Product product, ProductRepository.ReferenceCounts references) {
        List<String> reasons = new ArrayList<>();
        addCount(reasons, references.purchaseOrderLines(), "inkooporderregel", "inkooporderregels");
        addCount(reasons, references.salesOrderLines(), "verkooporderregel", "verkooporderregels");
        addCount(reasons, references.salesPalletItems(), "palletregel", "palletregels");
        addCount(reasons, references.quoteRevisionLines(),
                "offertevoorstelregel", "offertevoorstelregels");
        String identity = product.sku() == null || product.sku().isBlank()
                ? product.name() : product.sku();
        return "Product " + identity + " kan niet worden verwijderd omdat het nog voorkomt in "
                + joinReasons(reasons) + ". Zet het product in plaats daarvan op inactief.";
    }

    private static void addCount(List<String> reasons, long count, String singular, String plural) {
        if (count > 0) reasons.add(count + " " + (count == 1 ? singular : plural));
    }

    private static String joinReasons(List<String> reasons) {
        if (reasons.size() == 1) return reasons.getFirst();
        return String.join(", ", reasons.subList(0, reasons.size() - 1))
                + " en " + reasons.getLast();
    }


}
