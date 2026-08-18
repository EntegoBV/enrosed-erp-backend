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
 * Alles wat er met een product gebeurt.
 *
 * De fotoreeks hangt hier ook aan: het bestand gaat naar de opslag, de
 * volgorde en de metadata blijven bij het product. De eerste foto is de
 * hoofdfoto en verschijnt op lijsten en documenten.
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
        /* De fotoreeks wordt via de fotomethodes beheerd, niet via een update. */
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
                /* Voorraad hangt aan de inkoop, niet aan een productupdate. */
                current.stockQuantity(),
                current.photos(),
                /* Vertalingen komen van het formulier mee; is het veld niet
                   meegestuurd, dan blijft staan wat er stond. */
                changes.texts().isEmpty() ? current.texts() : changes.texts());
        return products.save(merged);
    }

    /**
     * Maakt een kopie van een product.
     *
     * Bedoeld voor hetzelfde artikel in een andere kleur: alle maten, prijzen en
     * verpakkingsgegevens komen mee, de foto's en barcodes niet. Die zijn per
     * kleur verschillend, en een gekopieerde barcode is erger dan geen barcode -
     * twee artikelen met dezelfde EAN geeft in het magazijn van de klant een
     * probleem dat niemand meteen ziet.
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
                /* Voorraad start op nul: dit is een nieuw artikel. */
                0, List.of(),
                /* De vertaalde namen en beschrijvingen gaan mee; de kleur per taal
                   niet, want die is hier net gewijzigd en zou anders in elke taal
                   de oude kleur blijven noemen. */
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
     * Legt de kostprijs vast die uit een inkoopcalculatie komt. Wordt door de
     * sourcing-kant aangeroepen zodra een calculatie toegepast wordt.
     */
    @Transactional
    public void applyLandedCost(long productId, BigDecimal landedCostEur, String source) {
        products.save(get(productId).withLandedCost(landedCostEur, source));
    }

    /**
     * Boekt voorraad bij of af.
     *
     * Positief bij het ontvangen van een inkooporder, negatief wanneer een
     * verkooporder de deur uit gaat. De voorraad mag onder nul zakken - dat is een
     * signaal dat er iets niet klopt in de administratie, en dat verberg je beter
     * niet door stilletjes op nul af te kappen.
     */
    @Transactional
    public void adjustStock(long productId, int delta) {
        Product current = get(productId);
        products.save(current.withStockQuantity(current.stockQuantity() + delta));
    }

    /* ------------------------------------------------------------ fotos */

    /**
     * Voegt een foto toe. Er is bewust geen maximum en er wordt niets
     * herschaald - de originele bytes gaan naar de opslag.
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

    /** Zet de reeks in de volgorde van de meegegeven id's; de eerste is de hoofdfoto. */
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
        /* Wat niet genoemd is behoudt zijn relatieve volgorde achteraan. */
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
