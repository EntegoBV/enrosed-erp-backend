package be.enrosed.catalog.application;

import be.enrosed.catalog.application.port.out.ProductSupplierRepository;
import be.enrosed.catalog.domain.Product;
import be.enrosed.catalog.domain.ProductSupplier;
import be.enrosed.catalog.domain.PurchaseMirror;
import be.enrosed.shared.BusinessRuleException;
import be.enrosed.shared.Currency;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.Optional;

/**
 * The rules every writer of a product's supplier rows shares ({@link ProductService}, {@link ProductSupplierService}
 * and the dev backfill), kept here so no service needs the other.
 *
 * <p>PostgreSQL checks the one-preferred index per row, immediately, and Hibernate does not flush in call order. So a
 * flag only ever becomes true through {@link #promote}: clear the product's flags with one bulk update, save the new
 * preferred row, flush.</p>
 */
public final class ProductSupplierRules {

    public static final int MAX_VERSION_LABEL = 60;
    public static final int MAX_REFERENCE = 120;
    public static final int MAX_NOTE = 4000;

    private ProductSupplierRules() {
    }

    public static Instant stamp(Instant now) {
        return now == null ? null : now.truncatedTo(ChronoUnit.MICROS);
    }

    /** Both truncated to MICROS, then equals (a client echoing the stored instant always matches). */
    public static boolean sameMoment(Instant a, Instant b) {
        if (a == null || b == null) return a == b;
        return stamp(a).equals(stamp(b));
    }

    /**
     * Strings trimmed, blank becomes null. The note keeps the product's rule: null = keep, and a blank note becomes
     * {@code ""}, the explicit clear.
     */
    public static ProductSupplierWrite normalise(ProductSupplierWrite w) {
        if (w == null) {
            return new ProductSupplierWrite(null, null, null, null, null, null, null, null, null, null);
        }
        String note = w.supplierNote() == null ? null
                : w.supplierNote().isBlank() ? "" : w.supplierNote().trim();
        return new ProductSupplierWrite(w.supplierId(), trimToNull(w.versionLabel()),
                trimToNull(w.supplierReference()), w.exwPrice(), w.exwCurrency(), w.extraUnitCost(), w.moq(),
                w.leadTimeDays(), note, w.expectedUpdatedAt());
    }

    /** The refusals of the row sheet (add and edit). */
    public static void validate(ProductSupplierWrite w, boolean adding) {
        if (adding && w.supplierId() == null) throw new BusinessRuleException("Kies een leverancier");
        if (w.exwPrice() == null) throw new BusinessRuleException("Vul de EXW-prijs in");
        if (w.exwPrice().signum() < 0) throw new BusinessRuleException("EXW-prijs kan niet negatief zijn");
        if (w.exwCurrency() == null) throw new BusinessRuleException("Kies de munt");
        if (w.extraUnitCost() != null && w.extraUnitCost().signum() < 0) {
            throw new BusinessRuleException("Extra kost kan niet negatief zijn");
        }
        if (w.moq() != null && w.moq() < 1) throw new BusinessRuleException("MOQ moet minstens 1 stuk zijn");
        if (w.leadTimeDays() != null && w.leadTimeDays() < 0) {
            throw new BusinessRuleException("Levertijd kan niet negatief zijn");
        }
        if (length(w.versionLabel()) > MAX_VERSION_LABEL) {
            throw new BusinessRuleException("Versie mag maximaal 60 tekens hebben");
        }
        if (length(w.supplierReference()) > MAX_REFERENCE) {
            throw new BusinessRuleException("Referentie mag maximaal 120 tekens hebben");
        }
        validateNote(w.supplierNote());
    }

    /** The only check of a note-only save (a backfilled row without price can still get a note). */
    public static void validateNote(String note) {
        if (length(note) > MAX_NOTE) {
            throw new BusinessRuleException("Leveranciersnotitie mag maximaal 4000 tekens hebben");
        }
    }

    /** A normalised note against the stored one: null keeps, "" clears, anything else replaces. */
    public static String noteAfter(String normalisedNote, String stored) {
        if (normalisedNote == null) return stored;
        return normalisedNote.isEmpty() ? null : normalisedNote;
    }

    /**
     * The product's own supplier has its row and that row is the Voorkeur (heals products without one; the product
     * card wins). supplierId null -> empty, nothing written.
     * <ul>
     *     <li>own row preferred -> returned unchanged;</li>
     *     <li>own row present, not preferred -> promote(own.withPurchase(card values));</li>
     *     <li>no own row -> promote(ProductSupplier.preferredOf(product)).</li>
     * </ul>
     */
    public static Optional<ProductSupplier> ensureOwnRow(ProductSupplierRepository rows, Product product,
                                                         Instant now) {
        if (product == null || product.id() == null || product.supplierId() == null) return Optional.empty();
        Optional<ProductSupplier> own = rows.find(product.id(), product.supplierId());
        if (own.isPresent() && own.get().preferred()) return own;
        ProductSupplier target = own
                .map(row -> row.withPurchase(product.exwPrice(), product.exwCurrency(), product.extraUnitCost(),
                        product.supplierNote()))
                .orElseGet(() -> ProductSupplier.preferredOf(product));
        return Optional.of(promote(rows, target, now));
    }

    /**
     * The only way a flag becomes true: clearPreferred(productId) -> save(target.withPreferred(true).withActive(true))
     * -> flush(). Returns the saved row.
     */
    public static ProductSupplier promote(ProductSupplierRepository rows, ProductSupplier target, Instant now) {
        rows.clearPreferred(target.productId());
        ProductSupplier saved = rows.save(target.withPreferred(true).withActive(true));
        rows.flush();
        return saved;
    }

    /**
     * A product PUT that changes purchase values; returns the preferred row afterwards.
     * <ol>
     *     <li>ensureOwnRow on the product as it is;</li>
     *     <li>target = the row of {@code after.supplierId}, or a new row from {@code after};</li>
     *     <li>on an existing target only what differs from {@code before} is written (amounts by compareTo), so a
     *     supplier switch from an old tab keeps that supplier's own price and note, and on the same supplier a
     *     changed value lands on the Voorkeur row (last write wins). A switch that carries no note keeps the row's
     *     note (the product PUT drops an echoed note on a supplier change);</li>
     *     <li>target not preferred (or new) -> promote; changed -> save; else nothing is written.</li>
     * </ol>
     */
    public static ProductSupplier applyProductEdit(ProductSupplierRepository rows, Product current,
                                                   PurchaseMirror before, PurchaseMirror after, Instant now) {
        Objects.requireNonNull(after.supplierId(), "after.supplierId");
        ensureOwnRow(rows, current, now);
        long productId = current.id();
        Optional<ProductSupplier> existing = rows.find(productId, after.supplierId());
        if (existing.isEmpty()) {
            ProductSupplier created = new ProductSupplier(null, productId, after.supplierId(), false, true,
                    null, null, after.exwPrice(), after.exwCurrency(), after.extraUnitCost(), null, null,
                    after.supplierNote(), null, null);
            return promote(rows, created, now);
        }
        ProductSupplier target = existing.get();
        boolean switched = !Objects.equals(before.supplierId(), after.supplierId());
        BigDecimal price = target.exwPrice();
        Currency currency = target.exwCurrency();
        BigDecimal extra = target.extraUnitCost();
        String note = target.supplierNote();
        boolean changed = false;
        if (!PurchaseMirror.sameAmount(after.exwPrice(), before.exwPrice())
                && !PurchaseMirror.sameAmount(after.exwPrice(), price)) {
            price = after.exwPrice();
            changed = true;
        }
        if (after.exwCurrency() != before.exwCurrency() && after.exwCurrency() != currency) {
            currency = after.exwCurrency();
            changed = true;
        }
        if (!PurchaseMirror.sameAmount(after.extraUnitCost(), before.extraUnitCost())
                && !PurchaseMirror.sameAmount(after.extraUnitCost(), extra)) {
            extra = after.extraUnitCost();
            changed = true;
        }
        boolean noteSent = !(switched && after.supplierNote() == null);
        if (noteSent && !Objects.equals(after.supplierNote(), before.supplierNote())
                && !Objects.equals(after.supplierNote(), note)) {
            note = after.supplierNote();
            changed = true;
        }
        ProductSupplier updated = changed ? target.withPurchase(price, currency, extra, note) : target;
        if (!target.preferred()) return promote(rows, updated, now);
        if (!changed) return target;
        return rows.save(updated);
    }

    private static String trimToNull(String value) {
        if (value == null || value.isBlank()) return null;
        return value.trim();
    }

    private static int length(String value) {
        return value == null ? 0 : value.codePointCount(0, value.length());
    }
}
