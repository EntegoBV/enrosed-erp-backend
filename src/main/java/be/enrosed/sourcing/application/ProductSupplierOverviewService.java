package be.enrosed.sourcing.application;

import be.enrosed.catalog.application.ProductCostHistoryService;
import be.enrosed.catalog.application.ProductOverviewOrder;
import be.enrosed.catalog.application.ProductService;
import be.enrosed.catalog.application.ProductSupplierAgreementPhotoService;
import be.enrosed.catalog.application.ProductSupplierService;
import be.enrosed.catalog.application.ProductSupplierWrite;
import be.enrosed.catalog.domain.Photo;
import be.enrosed.catalog.domain.Product;
import be.enrosed.catalog.domain.ProductCostEntry;
import be.enrosed.catalog.domain.ProductSupplier;
import be.enrosed.catalog.domain.PurchaseMirror;
import be.enrosed.shared.BusinessRuleException;
import be.enrosed.shared.Currency;
import be.enrosed.shared.NotFoundException;
import be.enrosed.sourcing.application.port.out.SourcingRepositories;
import be.enrosed.sourcing.domain.PurchaseOrder;
import be.enrosed.sourcing.domain.PurchaseOrderLine;
import be.enrosed.sourcing.domain.PurchaseOrderName;
import be.enrosed.sourcing.domain.PurchaseOrderStatus;
import be.enrosed.sourcing.domain.Supplier;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import java.math.BigDecimal;
import java.text.Collator;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The suppliers of a product as the screens read them (one product, several suppliers, 2026-10-25): each catalog row
 * with its supplier's name and terms, the last container it was bought on and the last cost it brought, and the other
 * way round, the products one supplier makes. Sourcing may read catalog; catalog never reads sourcing, which is why
 * these views live here and every write goes through {@link ProductSupplierService}.
 */
@ApplicationScoped
public class ProductSupplierOverviewService {

    private static final ZoneId BRUSSELS = ZoneId.of("Europe/Brussels");
    static final String UNKNOWN_SUPPLIER = "Onbekende leverancier";
    static final String ON_PURCHASE_ORDERS =
            "Deze leverancier staat op inkooporders, ook in de prullenbak. Zet hem op inactief.";

    public record LastOrder(long orderId, String number, String name, LocalDate orderDate, PurchaseOrderStatus status,
                            BigDecimal exwPrice, Currency exwCurrency) {}

    public record LastCost(BigDecimal unitEur, LocalDate appliedAt, String orderNumber) {}

    public record ProductSupplierView(long id, long productId, long supplierId, String supplierName, String supplierCity,
            Currency supplierCurrency, String supplierIncoterm, Integer supplierLeadTimeDays,
            boolean preferred, boolean active, String versionLabel, String supplierReference,
            BigDecimal exwPrice, Currency exwCurrency, BigDecimal extraUnitCost, Integer moq, Integer leadTimeDays,
            String supplierNote, int photoCount, LastOrder lastOrder, LastCost lastCost,
            long purchaseLineCount, Instant updatedAt) {}

    public record ProductSupplierChange(List<ProductSupplierView> rows, PurchaseMirror product) {}

    public record ProductSupplierRequest(Long supplierId, String versionLabel, String supplierReference,
            BigDecimal exwPrice, Currency exwCurrency, BigDecimal extraUnitCost, Integer moq, Integer leadTimeDays,
            String supplierNote, Instant updatedAt) {}

    public record ProductSupplierNoteRequest(String supplierNote, Instant updatedAt) {}

    public record SupplierProductLastOrder(long orderId, String number, LocalDate orderDate) {}

    public record SupplierProductView(long rowId, long productId, String sku, String name, String colour,
            String variantSize, String photoUrl, boolean productActive, boolean preferred, boolean active,
            String versionLabel, String supplierReference, BigDecimal exwPrice, Currency exwCurrency,
            SupplierProductLastOrder lastOrder) {}

    private final ProductSupplierService rows;
    private final ProductService products;
    private final SourcingRepositories.Suppliers suppliers;
    private final SourcingRepositories.PurchaseOrders orders;
    private final ProductSupplierAgreementPhotoService agreementPhotos;

    @Inject
    Instance<ProductCostHistoryService> costHistory;
    @Inject
    Instance<ProductOverviewOrder> overviewOrder;

    @Inject
    public ProductSupplierOverviewService(ProductSupplierService rows, ProductService products,
                                          SourcingRepositories.Suppliers suppliers,
                                          SourcingRepositories.PurchaseOrders orders,
                                          ProductSupplierAgreementPhotoService agreementPhotos) {
        this.rows = rows;
        this.products = products;
        this.suppliers = suppliers;
        this.orders = orders;
        this.agreementPhotos = agreementPhotos;
    }

    /** The product's rows: preferred first, then active, then supplier name. Read only. */
    @Transactional(Transactional.TxType.SUPPORTS)
    public List<ProductSupplierView> views(long productId) {
        products.get(productId);
        List<ProductSupplier> productRows = rows.rows(productId);
        if (productRows.isEmpty()) return List.of();
        Map<Long, Supplier> supplierById = suppliersById();
        List<PurchaseOrder> placed = placedOrders();
        Map<Long, PurchaseOrder> orderById = placed.stream()
                .filter(order -> order.id() != null)
                .collect(Collectors.toMap(PurchaseOrder::id, Function.identity(), (left, right) -> left));
        List<ProductCostEntry> history = costHistory == null || !costHistory.isResolvable()
                ? List.of() : costHistory.get().history(productId);
        List<ProductSupplierView> views = new ArrayList<>();
        for (ProductSupplier row : productRows) {
            Supplier supplier = supplierById.get(row.supplierId());
            views.add(new ProductSupplierView(row.id(), row.productId(), row.supplierId(),
                    supplier == null ? "Leverancier #" + row.supplierId() : supplier.name(),
                    supplier == null ? null : supplier.city(),
                    supplier == null ? null : supplier.currency(),
                    supplier == null ? null : supplier.incoterm(),
                    supplier == null ? null : supplier.leadTimeDays(),
                    row.preferred(), row.active(), row.versionLabel(), row.supplierReference(),
                    row.exwPrice(), row.exwCurrency(), row.extraUnitCost(), row.moq(), row.leadTimeDays(),
                    row.supplierNote(), photoCount(productId, row.supplierId()),
                    lastOrder(placed, productId, row.supplierId()),
                    lastCost(history, orderById, row.supplierId()),
                    orders.linesUsingProductSupplierIncludingDeleted(row.id(), productId, row.supplierId()),
                    row.updatedAt()));
        }
        Collator words = Collator.getInstance(Locale.forLanguageTag("nl-BE"));
        views.sort(Comparator.comparing(ProductSupplierView::preferred).reversed()
                .thenComparing(Comparator.comparing(ProductSupplierView::active).reversed())
                .thenComparing(ProductSupplierView::supplierName, Comparator.nullsLast(words))
                .thenComparingLong(ProductSupplierView::id));
        return List.copyOf(views);
    }

    @Transactional
    public ProductSupplierChange add(long productId, ProductSupplierRequest request) {
        products.get(productId);
        ProductSupplierRequest body = request == null ? emptyRequest() : request;
        String name = null;
        if (body.supplierId() != null) {
            name = suppliers.findById(body.supplierId())
                    .map(Supplier::name)
                    .orElseThrow(() -> new BusinessRuleException(UNKNOWN_SUPPLIER));
        }
        rows.add(productId, write(body.supplierId(), body), name);
        return change(productId);
    }

    @Transactional
    public ProductSupplierChange update(long productId, long rowId, ProductSupplierRequest request) {
        ProductSupplier row = row(productId, rowId);
        ProductSupplierRequest body = request == null ? emptyRequest() : request;
        rows.update(productId, rowId, write(row.supplierId(), body), supplierName(row.supplierId()));
        return change(productId);
    }

    @Transactional
    public ProductSupplierChange updateNote(long productId, long rowId, ProductSupplierNoteRequest request) {
        ProductSupplier row = row(productId, rowId);
        rows.updateNote(productId, rowId, request == null ? null : request.supplierNote(),
                request == null ? null : request.updatedAt(), supplierName(row.supplierId()));
        return change(productId);
    }

    @Transactional
    public ProductSupplierChange setPreferred(long productId, long rowId) {
        ProductSupplier row = row(productId, rowId);
        rows.setPreferred(productId, rowId, supplierName(row.supplierId()));
        return change(productId);
    }

    @Transactional
    public ProductSupplierChange setActive(long productId, long rowId, boolean active) {
        ProductSupplier row = row(productId, rowId);
        rows.setActive(productId, rowId, active, supplierName(row.supplierId()));
        return change(productId);
    }

    /** Only a row no purchase line names, trashed containers included; otherwise it goes inactive instead. */
    @Transactional
    public ProductSupplierChange delete(long productId, long rowId) {
        ProductSupplier row = row(productId, rowId);
        if (!row.preferred()
                && orders.linesUsingProductSupplierIncludingDeleted(rowId, productId, row.supplierId()) > 0) {
            throw new BusinessRuleException(ON_PURCHASE_ORDERS);
        }
        rows.delete(productId, rowId, supplierName(row.supplierId()));
        return change(productId);
    }

    /**
     * Every row of the supplier (active and inactive) whose product exists, with its product: active rows first,
     * then the product overview order.
     */
    @Transactional(Transactional.TxType.SUPPORTS)
    public List<SupplierProductView> productsOfSupplier(long supplierId) {
        if (suppliers.findById(supplierId).isEmpty()) throw new NotFoundException("Leverancier", supplierId);
        List<ProductService.Offered> offered = products.offeredBy(supplierId).stream()
                .filter(item -> item.offer() != null && !item.offer().legacy())
                .toList();
        if (offered.isEmpty()) return List.of();
        Map<Long, ProductSupplier> offerByProduct = new HashMap<>();
        offered.forEach(item -> offerByProduct.put(item.product().id(), item.offer()));
        List<Product> ordered = sortProducts(offered.stream().map(ProductService.Offered::product).toList());
        List<PurchaseOrder> placed = placedOrders();
        List<SupplierProductView> active = new ArrayList<>();
        List<SupplierProductView> inactive = new ArrayList<>();
        for (Product product : ordered) {
            ProductSupplier offer = offerByProduct.get(product.id());
            LastOrder last = lastOrder(placed, product.id(), supplierId);
            SupplierProductView view = new SupplierProductView(offer.id(), product.id(), product.sku(),
                    product.name(), product.colour(), product.variantSize(), photoUrl(product), product.active(),
                    offer.preferred(), offer.active(), offer.versionLabel(), offer.supplierReference(),
                    offer.exwPrice(), offer.exwCurrency(),
                    last == null ? null : new SupplierProductLastOrder(last.orderId(), last.number(), last.orderDate()));
            (offer.active() ? active : inactive).add(view);
        }
        active.addAll(inactive);
        return List.copyOf(active);
    }

    /* ---------------------------------------------------------- helpers */

    private ProductSupplierChange change(long productId) {
        return new ProductSupplierChange(views(productId), rows.mirror(productId));
    }

    private ProductSupplier row(long productId, long rowId) {
        products.get(productId);
        return rows.rows(productId).stream()
                .filter(row -> Objects.equals(row.id(), rowId))
                .findFirst()
                .orElseThrow(() -> new NotFoundException("Leverancier van product", rowId));
    }

    private static ProductSupplierWrite write(Long supplierId, ProductSupplierRequest body) {
        return new ProductSupplierWrite(supplierId, body.versionLabel(), body.supplierReference(), body.exwPrice(),
                body.exwCurrency(), body.extraUnitCost(), body.moq(), body.leadTimeDays(), body.supplierNote(),
                body.updatedAt());
    }

    private static ProductSupplierRequest emptyRequest() {
        return new ProductSupplierRequest(null, null, null, null, null, null, null, null, null, null);
    }

    private String supplierName(long supplierId) {
        return suppliers.findById(supplierId).map(Supplier::name).orElse("Leverancier #" + supplierId);
    }

    private Map<Long, Supplier> suppliersById() {
        return suppliers.findAll().stream()
                .filter(supplier -> supplier.id() != null)
                .collect(Collectors.toMap(Supplier::id, Function.identity(), (left, right) -> left));
    }

    /** Placed, not trashed: what a supplier really sold us. */
    private List<PurchaseOrder> placedOrders() {
        return orders.findAll().stream()
                .filter(order -> order.status() != null && order.status() != PurchaseOrderStatus.CONCEPT)
                .toList();
    }

    /** The newest placed container of this supplier with a line for the product (order date, then id). */
    static LastOrder lastOrder(List<PurchaseOrder> placed, long productId, long supplierId) {
        PurchaseOrder newest = null;
        PurchaseOrderLine newestLine = null;
        for (PurchaseOrder order : placed) {
            if (!Objects.equals(order.supplierId(), supplierId) || order.id() == null) continue;
            PurchaseOrderLine line = order.lines().stream()
                    .filter(candidate -> candidate != null && Objects.equals(candidate.productId(), productId))
                    .findFirst().orElse(null);
            if (line == null) continue;
            if (newest == null || newer(order, newest)) {
                newest = order;
                newestLine = line;
            }
        }
        if (newest == null) return null;
        return new LastOrder(newest.id(), newest.number(),
                PurchaseOrderName.display(newest.id(), newest.number(), newest.alias()), newest.orderDate(),
                newest.status(), newestLine.exwPrice(), newestLine.exwCurrency());
    }

    private static boolean newer(PurchaseOrder candidate, PurchaseOrder current) {
        LocalDate left = candidate.orderDate();
        LocalDate right = current.orderDate();
        if (left != null && right != null && !left.equals(right)) return left.isAfter(right);
        if (left != null && right == null) return true;
        if (left == null && right != null) return false;
        return candidate.id() > current.id();
    }

    /** The newest applied cost that came from a container of this supplier. */
    static LastCost lastCost(List<ProductCostEntry> newestFirst, Map<Long, PurchaseOrder> orderById, long supplierId) {
        for (ProductCostEntry entry : newestFirst) {
            if (entry.purchaseOrderId() == null || entry.landedUnitEur() == null) continue;
            PurchaseOrder order = orderById.get(entry.purchaseOrderId());
            if (order == null || !Objects.equals(order.supplierId(), supplierId)) continue;
            return new LastCost(entry.landedUnitEur(),
                    entry.appliedAt() == null ? null : entry.appliedAt().atZone(BRUSSELS).toLocalDate(),
                    order.number() == null ? entry.source() : order.number());
        }
        return null;
    }

    private int photoCount(long productId, long supplierId) {
        try {
            return agreementPhotos.list(productId, supplierId).size();
        } catch (NotFoundException outOfScope) {
            return 0;
        }
    }

    private List<Product> sortProducts(List<Product> list) {
        if (overviewOrder != null && overviewOrder.isResolvable()) return overviewOrder.get().sort(list);
        return list.stream().sorted(ProductOverviewOrder.fallbackComparator()).toList();
    }

    private static String photoUrl(Product product) {
        Photo photo = product.primaryPhoto();
        if (photo == null || photo.id() == null) return null;
        return "/api/products/" + product.id() + "/photos/" + photo.id() + "/renditions/small";
    }
}
