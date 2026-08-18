package be.enrosed.catalog.application;

import be.enrosed.catalog.application.port.out.PhotoStorage;
import be.enrosed.catalog.application.port.out.ProductRepository;
import be.enrosed.catalog.domain.Barcodes;
import be.enrosed.catalog.domain.Photo;
import be.enrosed.catalog.domain.Product;
import be.enrosed.catalog.domain.ProductText;
import be.enrosed.shared.BusinessRuleException;
import be.enrosed.shared.NotFoundException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import java.io.InputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

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
    private final BarcodeValidator barcodes;

    public ProductService(ProductRepository products, PhotoStorage photoStorage, BarcodeValidator barcodes) {
        this.products = products;
        this.photoStorage = photoStorage;
        this.barcodes = barcodes;
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
        validate(product);
        Product withSku = product.sku() == null || product.sku().isBlank()
                ? product.withSku(nextSku())
                : product;
        products.findBySku(withSku.sku()).ifPresent(existing -> {
            throw new BusinessRuleException("SKU " + withSku.sku() + " bestaat al");
        });
        return products.save(withSku);
    }

    @Transactional
    public Product update(long id, Product changes) {
        Product current = get(id);
        validate(changes);
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
        product.photos().forEach(photo -> photoStorage.delete(photo.storageKey()));
        products.deleteById(id);
    }

    /**
     * Records the cost price coming out of a purchase calculation. Called by
     * the sourcing side whenever a calculation is applied.
     */
    @Transactional
    public void applyLandedCost(long productId, BigDecimal landedCostEur, String source) {
        products.save(get(productId).withLandedCost(landedCostEur, source));
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
        Product current = get(productId);
        products.save(current.withStockQuantity(current.stockQuantity() + delta));
    }

    /* ------------------------------------------------------------ fotos */

    /**
     * Adds a photo. Deliberately no maximum and no rescaling - the original
     * bytes go to storage.
     */
    @Transactional
    public Product addPhoto(long productId, String filename, String contentType, InputStream data) {
        Product product = get(productId);
        PhotoStorage.Stored stored = photoStorage.store(filename, contentType, data);

        List<Photo> photos = new ArrayList<>(product.photos());
        photos.add(new Photo(null, stored.storageKey(), filename, contentType,
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
        photoStorage.delete(target.storageKey());
        return products.save(product.withPhotos(renumber(photos)));
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

    private void validate(Product product) {
        if (product.name() == null || product.name().isBlank()) {
            throw new BusinessRuleException("Naam is verplicht");
        }
        if (product.carton() == null || product.carton().piecesPerCarton() < 1) {
            throw new BusinessRuleException("Stuks per karton moet minstens 1 zijn");
        }
        checkBarcode(product.barcodes() == null ? null : product.barcodes().inner(), "Binnenbarcode");
        checkBarcode(product.barcodes() == null ? null : product.barcodes().outer(), "Omdoosbarcode");
    }

    private void checkBarcode(String value, String label) {
        BarcodeValidator.Result result = barcodes.validate(value);
        if (!result.valid()) {
            throw new BusinessRuleException(label + ": " + result.message());
        }
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


}
