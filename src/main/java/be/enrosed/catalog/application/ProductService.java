package be.enrosed.catalog.application;

import be.enrosed.catalog.adapter.out.persistence.CanonicalCatalogDaos;
import be.enrosed.catalog.adapter.out.persistence.ProductFamilyEntity;
import be.enrosed.catalog.application.port.out.PhotoStorage;
import be.enrosed.catalog.application.port.out.ProductRepository;
import be.enrosed.catalog.domain.Barcodes;
import be.enrosed.catalog.domain.CatalogChannel;
import be.enrosed.catalog.domain.Photo;
import be.enrosed.catalog.domain.Packaging;
import be.enrosed.catalog.domain.Product;
import be.enrosed.catalog.domain.ProductText;
import be.enrosed.catalog.domain.PublicationState;
import be.enrosed.shared.BusinessRuleException;
import be.enrosed.shared.NotFoundException;
import be.enrosed.shared.VariantSizes;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import be.enrosed.catalog.adapter.out.persistence.CategoryEntity;
import be.enrosed.catalog.adapter.out.persistence.CatalogDaos;
import jakarta.enterprise.inject.Instance;
import be.enrosed.shared.security.CurrentActor;
import be.enrosed.catalog.domain.StockMovement;
import be.enrosed.catalog.application.port.out.StockLedger;
import jakarta.transaction.Transactional;

import java.io.InputStream;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Everything that happens to a product.
 *
 * The photo series hangs off it too: the file goes to storage, the order and
 * the metadata stay with the product. The first photo is the primary one and
 * appears on lists and documents.
 */
@ApplicationScoped
public class ProductService {

    private static final org.jboss.logging.Logger LOG = org.jboss.logging.Logger.getLogger(ProductService.class);

    private final ProductRepository products;
    private final PhotoStorage photoStorage;
    private final ProductValidator validator;
    private final PhotoReferenceService photoReferences;
    private final CanonicalCatalogDaos.Families families;
    private final ProductFamilyWriteGuard familyWrites;
    private final FamilyPhotoCompatibilityService familyPhotos;

    @Inject
    WebsiteRebuildService websiteRebuild;

    /* The stock book and who writes in it; pure unit tests run without either. */
    @Inject
    Instance<StockLedger> ledger;
    @Inject
    Instance<CurrentActor> actor;
    /* Stock per location; pure unit tests run on the single figure. */
    @Inject
    Instance<StockService> stock;
    /* Moving a series to another category; absent in pure unit tests. */
    @Inject
    Instance<CatalogDaos.Categories> categoryDao;
    @Inject
    Instance<FamilyCollectionAlignmentService> familyCollections;
    @Inject
    Instance<FamilyMemberCacheService> familyMembers;
    /* The company's free EAN list; a saved product's codes leave it. */
    @Inject
    Instance<BarcodePoolService> barcodePool;

    @Inject
    public ProductService(
            ProductRepository products, PhotoStorage photoStorage, ProductValidator validator,
            PhotoReferenceService photoReferences, CanonicalCatalogDaos.Families families,
            ProductFamilyWriteGuard familyWrites,
            FamilyPhotoCompatibilityService familyPhotos) {
        this.products = products;
        this.photoStorage = photoStorage;
        this.validator = validator;
        this.photoReferences = photoReferences;
        this.families = families;
        this.familyWrites = familyWrites;
        this.familyPhotos = familyPhotos;
    }

    /** Test compatibility for pure domain tests that do not share family photo blobs. */
    public ProductService(ProductRepository products, PhotoStorage photoStorage,
                          ProductValidator validator) {
        this(products, photoStorage, validator, null, null, null, null);
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
        Product prepared = withSku.withPublicationMetadata(
                normalizeOptional(withSku.familyKey()), normalizeHandle(withSku.publicHandle()),
                withSku.websiteStatus() == null ? PublicationState.DRAFT : withSku.websiteStatus(),
                withSku.orderAppStatus() == null ? PublicationState.DRAFT : withSku.orderAppStatus());
        lockFamilies(prepared.familyId());
        prepared = canonicalFamilyMetadata(prepared);
        prepared = assignFamilyPosition(prepared, null);
        validator.validate(prepared);
        ensureUniqueSku(prepared.sku(), null);
        ensureUniqueBarcodes(prepared, null);
        ensureUniqueHandle(prepared.publicHandle(), null);
        ensurePublishable(prepared);
        Product saved = products.save(prepared);
        consumePoolCodes(saved);
        validateFamilies(prepared.familyId());
        syncFamilyPhotos(saved.id(), prepared.familyId());
        queueWebsite();
        return saved.id() == null ? saved : get(saved.id());
    }

    @Transactional
    public Product update(long id, Product changes) {
        return update(id, changes, false);
    }

    /** Dedicated command: unlike the backward-compatible full PUT, null explicitly unlinks. */
    @Transactional
    public Product assignFamily(long id, Long familyId) {
        Product current = get(id);
        Product changes = current.withCanonicalIdentity(
                familyId, current.canonicalVariantKey(), current.canonicalBarcode(),
                current.variantPosition(), current.inventoryKnown());
        if (familyId == null) {
            changes = changes.withPublicationMetadata(
                    null, null, PublicationState.DRAFT, PublicationState.DRAFT);
        }
        return update(id, changes, true);
    }

    private Product update(long id, Product changes, boolean familyExplicit) {
        Product observed = get(id);
        Long requestedFamilyId = familyExplicit || changes.familyId() != null
                ? changes.familyId() : observed.familyId();
        lockFamilies(observed.familyId(), requestedFamilyId);
        if (familyWrites != null) {
            Long lockedFamilyId = familyWrites.lockProduct(id);
            if (!Objects.equals(lockedFamilyId, observed.familyId())) {
                throw new BusinessRuleException(
                        "Product is gelijktijdig naar een andere familie verplaatst; laad het opnieuw");
            }
        }
        Product current = get(id);
        Product merged = mergeUpdate(current, changes, familyExplicit);
        /* A series shares one category. Changing it on one variant moves the
           whole series - the alternative, silently snapping back to the old
           category, looked like a form that does not save. Compared against
           the family, not the product: a half-moved series (one variant
           already right, the others behind) is repaired by simply saving. */
        if (merged.familyId() != null && Objects.equals(merged.familyId(), current.familyId())
                && changes.categoryId() != null) {
            moveFamilyToCategory(merged.familyId(), changes.categoryId());
        }
        merged = canonicalFamilyMetadata(merged);
        merged = assignFamilyPosition(merged, current);
        validator.validate(merged);
        ensureUniqueSku(merged.sku(), current.id());
        ensureUniqueBarcodes(merged, current.id());
        ensureUniqueHandle(merged.publicHandle(), current.id());
        ensurePublishable(merged);
        Product saved = products.save(merged);
        consumePoolCodes(saved);
        validateFamilies(current.familyId(), merged.familyId());
        syncFamilyPhotos(saved.id(), current.familyId(), merged.familyId());
        queueWebsite();
        return saved.id() == null ? saved : get(saved.id());
    }

    /** The photo series and purchasing-owned fields are never overwritten by a full product PUT. */
    private static Product mergeUpdate(
            Product current, Product changes, boolean familyExplicit) {
        return new Product(
                current.id(),
                changes.sku() == null || changes.sku().isBlank() ? current.sku() : changes.sku(),
                changes.name(),
                changes.dimensions(),
                changes.packaging(),
                changes.colour(),
                /* Backward compatible partial PUT: null means omitted/preserve for older clients;
                   an explicit blank string is the wire-level clear operation. */
                changes.variantSize() == null
                        ? current.variantSize() : normalizeOptional(changes.variantSize()),
                changes.colourHex() == null
                        ? current.colourHex() : normalizeOptional(changes.colourHex()),
                changes.description(),
                changes.categoryId(),
                changes.supplierId(),
                changes.active(),
                /* Legacy full PUTs omitted this new field. Only the dedicated family command
                   treats null as an explicit unlink. */
                familyExplicit || changes.familyId() != null
                        ? changes.familyId() : current.familyId(),
                changes.canonicalVariantKey() == null
                        ? current.canonicalVariantKey() : normalizeOptional(changes.canonicalVariantKey()),
                changes.canonicalBarcode() == null
                        ? current.canonicalBarcode() : normalizeOptional(changes.canonicalBarcode()),
                changes.variantPosition(),
                changes.inventoryKnown(),
                familyExplicit && changes.familyId() == null
                        ? null : changes.familyKey() == null
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
                /* Public translations have their own revisioned, atomic endpoint. A stale
                   general product PUT must never overwrite that independently saved snapshot. */
                current.texts());
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
        return duplicate(id, newColour, null, null);
    }

    /** Copies one stock-bearing SKU as another colour and/or size variant. */
    @Transactional
    public Product duplicate(long id, String newColour, String newColourHex, String newVariantSize) {
        Product source = get(id);
        String requestedColour = normalizeOptional(newColour);
        /* Same colour in another case is the same colour - compare in capitals. */
        String requestedHex = normalizeOptional(newColourHex);
        if (requestedHex != null) requestedHex = requestedHex.toUpperCase();
        String requestedSize = normalizeOptional(newVariantSize);
        /* null is legacy omitted/inherit; a supplied blank string explicitly clears. */
        String colour = newColour == null ? source.colour() : requestedColour;
        String size = newVariantSize == null ? source.variantSize() : requestedSize;
        boolean colourChanged = !java.util.Objects.equals(colour, source.colour());
        boolean sizeChanged = !java.util.Objects.equals(size, source.variantSize());
        String colourHex = newColourHex != null
                ? requestedHex : colourChanged ? null : source.colourHex();
        if (java.util.Objects.equals(colour, source.colour())
                && java.util.Objects.equals(size, source.variantSize())
                && java.util.Objects.equals(colourHex, source.colourHex())) {
            throw new BusinessRuleException(
                    "De nieuwe variant moet in kleur, kleurcode of maat verschillen van het bronproduct");
        }
        /* The gift box comes along with its size and weight; its EAN is as
           unique as any other barcode and stays behind, like the piece and
           carton codes. */
        Packaging packaging = new Packaging(source.packaging().kind(),
                source.packaging().dimensions(), null);
        return create(new Product(
                null, null, source.name(), source.dimensions(), packaging,
                colour, size, colourHex,
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
                   colour comes along for size/swatch-only variants, but is cleared
                   when the actual colour label changed. */
                source.texts().stream()
                        .map(text -> new ProductText(text.language(), text.name(),
                                text.description(), colourChanged ? null : text.colour(),
                                sizeChanged ? VariantSizes.translate(size, text.language())
                                        : text.variantSize()))
                        .filter(text -> !text.isEmpty())
                        .toList()));
    }

    @Transactional
    public void delete(long id) {
        Product observed = get(id);
        lockFamilies(observed.familyId());
        if (familyWrites != null) {
            Long lockedFamilyId = familyWrites.lockProduct(id);
            if (!Objects.equals(lockedFamilyId, observed.familyId())) {
                throw new BusinessRuleException(
                        "Product is gelijktijdig naar een andere familie verplaatst; laad het opnieuw");
            }
        }
        Product product = get(id);
        ProductRepository.ReferenceCounts references = products.referenceCounts(id);
        if (references.total() > 0) {
            throw new BusinessRuleException(deleteBlockedMessage(product, references));
        }
        products.deleteById(id);
        draftEmptyFamily(product.familyId());
        validateFamilies(product.familyId());
        syncFamilyPhotos(null, product.familyId());
        product.photos().forEach(photo -> deleteBlob(photo.storageKey()));
        queueWebsite();
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
        queueWebsite();
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
        adjustStock(productId, delta, null);
    }

    /**
     * Books a purchase receipt at the receiving location.
     *
     * @param reference the purchase order number the pieces came in on
     */
    @Transactional
    public void adjustStock(long productId, int delta, String reference) {
        receiveStock(productId, delta, reference, null);
    }

    /** Broken on arrival: counted in the book without ever touching the shelf. */
    @Transactional
    public void noteDamagedOnArrival(long productId, int quantity, String reference, Long locationId) {
        if (stock == null || !stock.isResolvable()) return;
        StockService service = stock.get();
        long where = locationId != null ? locationId : service.mainLocation().id();
        service.noteDamagedOnArrival(productId, where, quantity, reference);
    }

    /** Pieces found broken after booking: they leave the shelf as damaged. */
    public void takeOutDamaged(long productId, int quantity, String reference, Long locationId) {
        if (quantity <= 0 || stock == null || !stock.isResolvable()) return;
        StockService service = stock.get();
        long where = locationId != null ? locationId : service.mainLocation().id();
        service.takeOut(productId, where, quantity, be.enrosed.catalog.domain.StockMovement.Kind.DAMAGED, reference);
    }

    /** @param locationId where the container was unloaded; null means the warehouse */
    @Transactional
    public void receiveStock(long productId, int delta, String reference, Long locationId) {
        if (stock != null && stock.isResolvable()) {
            StockService service = stock.get();
            long where = locationId != null ? locationId : service.mainLocation().id();
            service.add(productId, where, delta, StockMovement.Kind.PURCHASE_RECEIPT, reference);
            return;
        }
        if (!products.adjustStock(productId, delta)) {
            throw new NotFoundException("Product", productId);
        }
        book(productId, delta, StockMovement.Kind.PURCHASE_RECEIPT, reference);
        queueWebsite();
    }

    public List<StockMovement> stockMovements(long productId) {
        get(productId);
        return stockLedger().forProduct(productId);
    }

    /** Removes one line from the stock book; the count is not touched. */
    @Transactional
    public void deleteStockMovement(long productId, long movementId) {
        if (!stockLedger().delete(productId, movementId)) {
            throw new NotFoundException("Voorraadbeweging", movementId);
        }
        LOG.infof("Voorraadregel %d van product %d verwijderd door %s", movementId, productId, actorName());
    }

    private void book(long productId, int delta, StockMovement.Kind kind, String reference) {
        int after = products.findById(productId).map(Product::stockQuantity).orElse(0);
        stockLedger().record(new StockMovement(null, productId, Instant.now(), delta, after, kind,
                reference, actorName()));
    }

    private StockLedger stockLedger() {
        return ledger != null && ledger.isResolvable() ? ledger.get() : StockLedger.NONE;
    }

    private String actorName() {
        return actor != null && actor.isResolvable() ? actor.get().name() : "systeem";
    }

    /**
     * Manual stock correction after a recount: the new count replaces the
     * old one, and the stock book gets a line saying so.
     */
    @Transactional
    public Product setStock(long productId, int quantity) {
        if (quantity < 0) {
            throw new BusinessRuleException("Voorraad kan niet negatief zijn");
        }
        if (stock != null && stock.isResolvable()) {
            /* With locations the count belongs to one of them: the warehouse. */
            StockService service = stock.get();
            service.setLevel(productId, service.mainLocation().id(), quantity,
                    StockMovement.Kind.MANUAL_CORRECTION, null);
            return get(productId);
        }
        Product before = products.setStock(productId, quantity)
                .orElseThrow(() -> new NotFoundException("Product", productId));
        int delta = quantity - (before.inventoryKnown() ? before.stockQuantity() : 0);
        book(productId, delta, StockMovement.Kind.MANUAL_CORRECTION, null);
        LOG.infof("Voorraad van %s (%s) manueel gezet: %d -> %d",
                before.sku(), before.name(), before.stockQuantity(), quantity);
        queueWebsite();
        return get(productId);
    }

    /* ------------------------------------------------------------ fotos */

    /**
     * Adds a validated photo of at most 25 MB. The original bytes go to
     * storage without rescaling or recompression.
     */
    @Transactional
    public Product addPhoto(long productId, String filename, InputStream data) {
        PhotoUploadPolicy.ValidatedPhoto upload = PhotoUploadPolicy.validate(filename, data);
        Product product = lockProductForPhotoWrite(productId);
        rejectDuplicatePhoto(product, upload);
        PhotoStorage.Stored stored = photoStorage.store(
                upload.originalFilename(), upload.contentType(), upload.bytes());

        List<Photo> photos = new ArrayList<>(product.photos().size() + 1);
        product.photos().stream().filter(photo -> !photo.inherited()).forEach(photos::add);
        photos.add(new Photo(null, stored.storageKey(), upload.originalFilename(), upload.contentType(),
                stored.sizeBytes(), stored.widthPx(), stored.heightPx(), photos.size()));
        product.photos().stream().filter(Photo::inherited).forEach(photos::add);

        Product saved = products.save(product.withPhotos(renumber(photos)));
        queueWebsite();
        return saved;
    }

    /**
     * The same picture twice helps nobody. Compared by content, not by name:
     * a renamed copy is still the same photo. Only photos of the same byte
     * size are read back from storage, so the check stays cheap.
     */
    private void rejectDuplicatePhoto(Product product, PhotoUploadPolicy.ValidatedPhoto upload) {
        for (Photo existing : product.photos()) {
            if (existing.inherited() || existing.sizeBytes() != upload.bytes().length) continue;
            byte[] stored;
            try (InputStream in = photoStorage.read(existing.storageKey())) {
                stored = in.readAllBytes();
            } catch (Exception unreadable) {
                continue;
            }
            if (java.util.Arrays.equals(stored, upload.bytes())) {
                throw new BusinessRuleException("Deze foto staat al bij dit product ("
                        + existing.originalFilename() + ")");
            }
        }
    }

    @Transactional
    public Product removePhoto(long productId, long photoId) {
        Product product = lockProductForPhotoWrite(productId);
        Photo target = product.photos().stream()
                .filter(photo -> photo.id() != null && photo.id() == photoId)
                .findFirst()
                .orElseThrow(() -> new NotFoundException("Foto", photoId));
        if (target.inherited()) {
            throw new BusinessRuleException(
                    "Deze foto komt uit het model en is hier alleen-lezen; beheer haar in de modelgalerij");
        }

        List<Photo> photos = new ArrayList<>(product.photos());
        photos.remove(target);
        Product updated = product.withPhotos(renumber(photos));
        ensurePublishable(updated);
        Product saved = products.save(updated);
        deleteBlob(target.storageKey());
        queueWebsite();
        return saved;
    }

    /** Orders the series by the given ids; the first becomes the primary photo. */
    @Transactional
    public Product reorderPhotos(long productId, List<Long> photoIdsInOrder) {
        Product product = lockProductForPhotoWrite(productId);
        List<Photo> photos = new ArrayList<>(product.photos());

        List<Long> wanted = photoIdsInOrder == null ? List.of() : photoIdsInOrder;
        boolean containsInherited = photos.stream().filter(Photo::inherited)
                .anyMatch(photo -> photo.id() != null && wanted.contains(photo.id()));
        if (containsInherited) {
            throw new BusinessRuleException(
                    "Geërfde modelfoto's zijn alleen-lezen; wijzig hun volgorde in de modelgalerij");
        }
        List<Photo> productOwned = photos.stream().filter(photo -> !photo.inherited()).toList();
        if (wanted.size() != productOwned.size()
                || new HashSet<>(wanted).size() != wanted.size()
                || productOwned.stream().anyMatch(photo -> photo.id() == null
                        || !wanted.contains(photo.id()))) {
            throw new BusinessRuleException(
                    "De fotovolgorde moet elke product-eigen foto exact één keer bevatten");
        }

        List<Photo> ordered = new ArrayList<>(photos.size());
        for (Long photoId : wanted) {
            productOwned.stream().filter(photo -> photo.id().equals(photoId))
                    .findFirst().ifPresent(ordered::add);
        }
        /* Family projections remain read-only and keep their canonical relative order. */
        photos.stream().filter(Photo::inherited).forEach(ordered::add);

        Product saved = products.save(product.withPhotos(renumber(ordered)));
        queueWebsite();
        return saved;
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

    /** Serializes product-photo writes with family gallery projection rebuilds. */
    private Product lockProductForPhotoWrite(long productId) {
        Product observed = get(productId);
        lockFamilies(observed.familyId());
        if (familyWrites != null) {
            Long lockedFamilyId = familyWrites.lockProduct(productId);
            if (!Objects.equals(lockedFamilyId, observed.familyId())) {
                throw new BusinessRuleException(
                        "Product is gelijktijdig naar een ander model verplaatst; laad het opnieuw");
            }
        }
        return get(productId);
    }

    private void ensureUniqueSku(String sku, Long currentId) {
        products.findBySku(sku).ifPresent(existing -> {
            if (currentId == null || !currentId.equals(existing.id())) {
                throw new BusinessRuleException("SKU " + sku + " bestaat al");
            }
        });
    }

    /**
     * One barcode, one meaning. The codes on this product must differ from
     * each other (a piece and its carton never share a code) and from every
     * code elsewhere in the catalogue - the message says where it sits.
     */
    private void ensureUniqueBarcodes(Product product, Long currentId) {
        List<BarcodeOwner.Carried> own = new java.util.ArrayList<>();
        Barcodes codes = product.barcodes() == null ? Barcodes.none() : product.barcodes();
        checkOwnBarcode(own, codes.inner(), "stukbarcode");
        if (product.packaging().isPresent()) {
            checkOwnBarcode(own, product.packaging().barcode(),
                    "barcode op de " + product.packaging().kind().dutchLabel().toLowerCase());
        }
        checkOwnBarcode(own, codes.outer(), "omdoosbarcode");
        if (own.isEmpty()) return;

        List<Product> catalogue = products.findAll();
        for (BarcodeOwner.Carried carried : own) {
            BarcodeOwner owner = BarcodeOwner.find(carried.code(), catalogue, currentId);
            if (owner != null) {
                throw new BusinessRuleException(owner.describe(carried.code()));
            }
        }
    }

    private static void checkOwnBarcode(List<BarcodeOwner.Carried> own, String value, String level) {
        String code = BarcodeOwner.normalize(value);
        if (code == null) return;
        for (BarcodeOwner.Carried earlier : own) {
            if (earlier.code().equals(code)) {
                throw new BusinessRuleException("Barcode " + code + " staat op dit product zowel als "
                        + earlier.level() + " als " + level + "; elke barcode hoort bij één ding");
            }
        }
        own.add(new BarcodeOwner.Carried(code, level));
    }

    /** For the form: who carries this code, or null when it is free. */
    public BarcodeOwner barcodeOwner(String barcode, Long excludeProductId) {
        return BarcodeOwner.find(barcode, products.findAll(), excludeProductId);
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
    /** Codes now on a product leave the company's free list. */
    private void consumePoolCodes(Product saved) {
        if (barcodePool == null || !barcodePool.isResolvable()) return;
        Barcodes codes = saved.barcodes() == null ? Barcodes.none() : saved.barcodes();
        barcodePool.get().consume(codes.inner(), codes.outer(), saved.packaging().barcode());
    }

    private void moveFamilyToCategory(long familyId, long categoryId) {
        if (families == null || categoryDao == null || !categoryDao.isResolvable()) return;
        ProductFamilyEntity family = families.findById(familyId);
        if (family == null) return;
        CategoryEntity category = categoryDao.get().findById(categoryId);
        if (category == null) throw new BusinessRuleException("Onbekende categorie " + categoryId);
        boolean unchanged = java.util.Objects.equals(family.categoryId, category.id);
        family.categoryId = category.id;
        family.categoryKey = CategoryPublicKey.from(category.code);
        family.categoryName = category.name;
        family.categoryPosition = category.position;
        if (!unchanged) family.updatedAt = Instant.now();
        if (!unchanged && familyCollections != null && familyCollections.isResolvable()) {
            familyCollections.get().alignPrimary(family);
        }
        if (familyMembers != null && familyMembers.isResolvable()) familyMembers.get().sync(family);
        families.flush();
    }

    private Product canonicalFamilyMetadata(Product product) {
        if (product.familyId() == null || families == null) return product;
        ProductFamilyEntity family = families.findById(product.familyId());
        if (family == null) {
            throw new BusinessRuleException("Onbekende productfamilie " + product.familyId());
        }
        return product.withCategoryId(family.categoryId).withPublicationMetadata(
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

    private void lockFamilies(Long... familyIds) {
        if (familyWrites != null) familyWrites.lockFamilies(Arrays.asList(familyIds));
    }

    private Product assignFamilyPosition(Product candidate, Product current) {
        return familyWrites == null ? candidate : familyWrites.assignPosition(candidate, current);
    }

    private void validateFamilies(Long... familyIds) {
        if (familyWrites != null) familyWrites.validateFamilies(Arrays.asList(familyIds));
    }

    private void syncFamilyPhotos(Long productId, Long... familyIds) {
        if (familyPhotos == null || families == null) return;
        Arrays.stream(familyIds).filter(Objects::nonNull).distinct().sorted()
                .map(families::findById).filter(Objects::nonNull).forEach(familyPhotos::sync);
        if (productId != null) {
            Product product = get(productId);
            if (product.familyId() == null) familyPhotos.clearInherited(productId);
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
                    photo.contentType(), photo.sizeBytes(), photo.widthPx(), photo.heightPx(),
                    i, photo.familyPhotoId()));
        }
        return result;
    }

    private void deleteBlob(String storageKey) {
        if (photoReferences == null) photoStorage.delete(storageKey);
        else photoReferences.deleteIfUnreferenced(storageKey);
    }

    private void queueWebsite() {
        if (websiteRebuild != null) websiteRebuild.queue();
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
