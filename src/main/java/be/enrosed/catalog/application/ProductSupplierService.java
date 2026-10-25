package be.enrosed.catalog.application;

import be.enrosed.catalog.application.port.out.ProductSupplierRepository;
import be.enrosed.catalog.domain.Product;
import be.enrosed.catalog.domain.ProductSupplier;
import be.enrosed.catalog.domain.PurchaseMirror;
import be.enrosed.shared.BusinessRuleException;
import be.enrosed.shared.NotFoundException;
import be.enrosed.shared.audit.ActivityChangeDto;
import be.enrosed.shared.audit.ActivityChangeSet;
import be.enrosed.shared.audit.ActivityLogService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The suppliers ("versies") of a product: one row per supplier with its own EXW price, reference, version label,
 * MOQ, lead time and agreement note. The preferred row is mirrored onto the product's own purchase columns, through
 * {@link ProductService#writePurchaseMirror}, never through a full product save. Never imports
 * {@code be.enrosed.sourcing} (catalog stays below sourcing).
 */
@ApplicationScoped
public class ProductSupplierService {

    private static final String ACTIVITY_ENTITY = "PRODUCT";
    static final String STALE = "Deze leverancier is intussen gewijzigd; laad het product opnieuw.";
    static final String PREFERRED_STAYS = "Maak eerst een andere leverancier voorkeur.";

    public record SupplierUsage(long products, long inactive) {}

    private final ProductSupplierRepository rows;
    private final ProductService products;
    private final ProductSupplierAgreementPhotoService agreementPhotos;

    @Inject
    Instance<ActivityLogService> activity;

    @Inject
    public ProductSupplierService(ProductSupplierRepository rows, ProductService products,
                                  ProductSupplierAgreementPhotoService agreementPhotos) {
        this.rows = rows;
        this.products = products;
        this.agreementPhotos = agreementPhotos;
    }

    /** Read only, never heals. Preferred first, then active, then id. */
    public List<ProductSupplier> rows(long productId) {
        return rows.findByProduct(productId);
    }

    public Optional<ProductSupplier> row(long productId, long supplierId) {
        return rows.find(productId, supplierId);
    }

    public List<ProductSupplier> rowsOfSupplier(long supplierId) {
        return rows.findBySupplier(supplierId);
    }

    /** From products.offeredBy(supplierId): rows whose product exists (active or inactive) plus legacy products. */
    public SupplierUsage usage(long supplierId) {
        List<ProductService.Offered> offered = products.offeredBy(supplierId);
        long inactive = offered.stream().filter(item -> !item.offer().active()).count();
        return new SupplierUsage(offered.size(), inactive);
    }

    /** findBySupplier + ProductSupplierOffers.of: one indexed query. */
    public Map<Long, ProductSupplier> offersFor(long supplierId, Map<Long, Product> productsById) {
        return ProductSupplierOffers.of(supplierId, productsById == null ? List.of() : productsById.values(),
                rows.findBySupplier(supplierId));
    }

    /** The product's first row becomes the Voorkeur; later rows are added beside it. */
    @Transactional
    public ProductSupplier add(long productId, ProductSupplierWrite write, String supplierName) {
        Instant now = Instant.now();
        Product product = products.lockForPurchaseWrite(productId);
        ProductSupplierRules.ensureOwnRow(rows, product, now);
        ProductSupplierWrite w = ProductSupplierRules.normalise(write);
        if (w.supplierId() == null) throw new BusinessRuleException("Kies een leverancier");
        String name = name(supplierName, w.supplierId());
        if (rows.find(productId, w.supplierId()).isPresent()) {
            throw new BusinessRuleException(name + " staat al bij dit product");
        }
        ProductSupplierRules.validate(w, true);
        boolean first = rows.findByProduct(productId).isEmpty();
        ProductSupplier candidate = new ProductSupplier(null, productId, w.supplierId(), false, true,
                w.versionLabel(), w.supplierReference(), w.exwPrice(), w.exwCurrency(), w.extraUnitCost(),
                w.moq(), w.leadTimeDays(), ProductSupplierRules.noteAfter(w.supplierNote(), null), null, null);
        ProductSupplier saved;
        if (first) {
            saved = ProductSupplierRules.promote(rows, candidate, now);
            products.writePurchaseMirror(productId, PurchaseMirror.of(saved));
        } else {
            saved = rows.save(candidate);
            rows.flush();
        }
        record(product, "Leverancier toegevoegd: " + name, details(null, saved).build());
        return saved;
    }

    @Transactional
    public ProductSupplier update(long productId, long rowId, ProductSupplierWrite write, String supplierName) {
        Product product = lockAndHeal(productId);
        ProductSupplier stored = rowOf(productId, rowId);
        ProductSupplierWrite w = ProductSupplierRules.normalise(write);
        requireFresh(w.expectedUpdatedAt(), stored);
        ProductSupplierRules.validate(w, false);
        ProductSupplier changed = stored.withDetails(w.versionLabel(), w.supplierReference(), w.exwPrice(),
                w.exwCurrency(), w.extraUnitCost(), w.moq(), w.leadTimeDays(),
                ProductSupplierRules.noteAfter(w.supplierNote(), stored.supplierNote()));
        ProductSupplier saved = rows.save(changed);
        rows.flush();
        if (saved.preferred()) products.writePurchaseMirror(productId, PurchaseMirror.of(saved));
        List<ActivityChangeDto> changes = details(stored, saved)
                .privateValue("supplierNote", "Leveranciersnotitie", stored.supplierNote(), saved.supplierNote())
                .build();
        if (!changes.isEmpty()) {
            record(product, "Leverancier " + name(supplierName, saved.supplierId()) + " bijgewerkt", changes);
        }
        return saved;
    }

    /** Only the note: null keeps it, "" clears it. A row without price can still get a note. */
    @Transactional
    public ProductSupplier updateNote(long productId, long rowId, String supplierNote, Instant expectedUpdatedAt,
                                      String supplierName) {
        Product product = lockAndHeal(productId);
        ProductSupplier stored = rowOf(productId, rowId);
        String note = supplierNote == null ? null : supplierNote.isBlank() ? "" : supplierNote.trim();
        ProductSupplierRules.validateNote(note);
        requireFresh(expectedUpdatedAt, stored);
        if (note == null) return stored;
        String after = ProductSupplierRules.noteAfter(note, stored.supplierNote());
        if (Objects.equals(after, stored.supplierNote())) return stored;
        ProductSupplier saved = rows.save(stored.withPurchase(stored.exwPrice(), stored.exwCurrency(),
                stored.extraUnitCost(), after));
        rows.flush();
        if (saved.preferred()) products.writePurchaseMirror(productId, PurchaseMirror.of(saved));
        record(product, "Leverancier " + name(supplierName, saved.supplierId()) + " bijgewerkt",
                ActivityChangeSet.create()
                        .privateValue("supplierNote", "Leveranciersnotitie", stored.supplierNote(), after)
                        .build());
        return saved;
    }

    @Transactional
    public ProductSupplier setPreferred(long productId, long rowId, String supplierName) {
        Instant now = Instant.now();
        Product product = lockAndHeal(productId);
        ProductSupplier stored = rowOf(productId, rowId);
        if (stored.preferred()) return stored;
        if (!stored.active()) throw new BusinessRuleException("Zet deze leverancier eerst weer actief.");
        Long before = product.supplierId();
        ProductSupplier promoted = ProductSupplierRules.promote(rows, stored, now);
        products.writePurchaseMirror(productId, PurchaseMirror.of(promoted));
        record(product, "Voorkeursleverancier: " + name(supplierName, promoted.supplierId()),
                ActivityChangeSet.create()
                        .add("supplierId", "Voorkeursleverancier", before, promoted.supplierId())
                        .build());
        return promoted;
    }

    @Transactional
    public ProductSupplier setActive(long productId, long rowId, boolean active, String supplierName) {
        Product product = lockAndHeal(productId);
        ProductSupplier stored = rowOf(productId, rowId);
        if (!active && stored.preferred()) throw new BusinessRuleException(PREFERRED_STAYS);
        if (stored.active() == active) return stored;
        ProductSupplier saved = rows.save(stored.withActive(active));
        rows.flush();
        String name = name(supplierName, saved.supplierId());
        record(product, active ? "Leverancier " + name + " weer actief" : "Leverancier " + name + " op inactief",
                ActivityChangeSet.create().add("active", "Actief", stored.active(), saved.active()).build());
        return saved;
    }

    /** The caller checked purchase order usage. The supplier's agreement photos of this product go too. */
    @Transactional
    public void delete(long productId, long rowId, String supplierName) {
        Product product = lockAndHeal(productId);
        ProductSupplier stored = rowOf(productId, rowId);
        if (stored.preferred()) throw new BusinessRuleException(PREFERRED_STAYS);
        agreementPhotos.deleteAllForProductSupplier(productId, stored.supplierId());
        rows.delete(stored.id());
        record(product, "Leverancier verwijderd: " + name(supplierName, stored.supplierId()), List.of());
    }

    public PurchaseMirror mirror(long productId) {
        return PurchaseMirror.of(products.get(productId));
    }

    /* ---------------------------------------------------------- helpers */

    private Product lockAndHeal(long productId) {
        Product product = products.lockForPurchaseWrite(productId);
        ProductSupplierRules.ensureOwnRow(rows, product, Instant.now());
        return product;
    }

    private ProductSupplier rowOf(long productId, long rowId) {
        return rows.findById(rowId)
                .filter(row -> row.productId() == productId)
                .orElseThrow(() -> new NotFoundException("Leverancier van product", rowId));
    }

    private static void requireFresh(Instant expected, ProductSupplier stored) {
        if (expected != null && !ProductSupplierRules.sameMoment(expected, stored.updatedAt())) {
            throw new BusinessRuleException(STALE);
        }
    }

    private static String name(String supplierName, Long supplierId) {
        if (supplierName != null && !supplierName.isBlank()) return supplierName.trim();
        return "leverancier " + supplierId;
    }

    /** The row fields the activity screen shows, before -> after (amounts compare by value). */
    private static ActivityChangeSet details(ProductSupplier before, ProductSupplier after) {
        return ActivityChangeSet.create()
                .add("versionLabel", "Versie", before == null ? null : before.versionLabel(), after.versionLabel())
                .add("supplierReference", "Referentie leverancier",
                        before == null ? null : before.supplierReference(), after.supplierReference())
                .add("exwPrice", "EXW-prijs", before == null ? null : before.exwPrice(), after.exwPrice())
                .add("exwCurrency", "EXW-valuta", before == null ? null : before.exwCurrency(), after.exwCurrency())
                .add("extraUnitCost", "Extra kost per stuk",
                        before == null ? null : before.extraUnitCost(), after.extraUnitCost())
                .add("moq", "MOQ", before == null ? null : before.moq(), after.moq())
                .add("leadTimeDays", "Levertijd (dagen)",
                        before == null ? null : before.leadTimeDays(), after.leadTimeDays());
    }

    private void record(Product product, String summary, List<ActivityChangeDto> changes) {
        if (activity == null || !activity.isResolvable()) return;
        String label = product.sku() == null || product.sku().isBlank() ? product.name() : product.sku();
        String id = product.id() == null ? null : product.id().toString();
        if (changes == null || changes.isEmpty()) {
            activity.get().record(ActivityLogService.ACTION_UPDATED, ACTIVITY_ENTITY, id, label, summary);
        } else {
            activity.get().record(ActivityLogService.ACTION_UPDATED, ACTIVITY_ENTITY, id, label, summary, changes);
        }
    }
}
