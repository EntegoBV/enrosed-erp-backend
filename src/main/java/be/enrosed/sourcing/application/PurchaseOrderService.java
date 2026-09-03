package be.enrosed.sourcing.application;

import be.enrosed.catalog.application.ProductService;
import be.enrosed.catalog.domain.Product;
import be.enrosed.shared.BusinessRuleException;
import be.enrosed.shared.NotFoundException;
import be.enrosed.shared.audit.ActivityChangeDto;
import be.enrosed.shared.audit.ActivityChangeSet;
import be.enrosed.shared.audit.ActivityLogService;
import be.enrosed.shared.security.ActorRef;
import be.enrosed.shared.security.CurrentActor;
import be.enrosed.sourcing.application.port.out.SourcingRepositories;
import be.enrosed.sourcing.domain.*;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import be.enrosed.catalog.domain.Carton;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import be.enrosed.sourcing.domain.PurchasePayment;
import be.enrosed.sourcing.domain.PurchaseDocument;
import be.enrosed.shared.Currency;
import be.enrosed.shared.Money;
import be.enrosed.media.MediaLegacySourceType;
import be.enrosed.media.MediaService;
import be.enrosed.media.MediaTargetType;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Managing and pricing purchase orders.
 *
 * The outcome is not written onto the products automatically: that only
 * happens when someone applies the calculation deliberately. That way the
 * sales price never shifts just because a draft is computing somewhere.
 */
@ApplicationScoped
public class PurchaseOrderService {

    private static final Logger LOG = Logger.getLogger(PurchaseOrderService.class);

    private final SourcingRepositories.PurchaseOrders orders;
    private final SourcingRepositories.Suppliers suppliers;
    private final ProductService products;
    /* Payments and who records them; pure unit tests run without. */
    @Inject
    Instance<be.enrosed.sourcing.application.port.out.SourcingRepositories.Payments> payments;
    @Inject
    Instance<CurrentActor> actor;
    @Inject
    Instance<ActivityLogService> activity;
    @Inject
    Event<PurchasePushNotifier.Ready> purchasePush;
    @Inject
    Instance<be.enrosed.catalog.application.StockService> locationNames;
    @Inject
    Instance<be.enrosed.sourcing.application.port.out.SourcingRepositories.Documents> documents;
    @Inject
    Instance<be.enrosed.catalog.application.port.out.PhotoStorage> photoStorage;
    @Inject
    Event<PurchaseDocumentStorageCleanup.DeleteReady> documentDeleteCleanup;
    @Inject
    Event<PurchaseDocumentStorageCleanup.UploadReady> documentUploadCleanup;
    @Inject
    Instance<MediaService> mediaRegistry;
    private final LandedCostCalculator calculator;

    public PurchaseOrderService(SourcingRepositories.PurchaseOrders orders,
                                SourcingRepositories.Suppliers suppliers,
                                ProductService products,
                                LandedCostCalculator calculator) {
        this.orders = orders;
        this.suppliers = suppliers;
        this.products = products;
        this.calculator = calculator;
    }

    public List<PurchaseOrder> list() {
        return orders.findAll();
    }

    public PurchaseOrder get(long id) {
        return orders.findById(id).orElseThrow(() -> new NotFoundException("Inkooporder", id));
    }

    /** Serialises lifecycle changes so receipt can book stock only once. */
    private PurchaseOrder getForUpdate(long id) {
        return orders.findByIdForUpdate(id)
                .orElseThrow(() -> new NotFoundException("Inkooporder", id));
    }

    public LandedCost calculate(PurchaseOrder order) {
        Map<Long, Product> byId = products.list().stream()
                .collect(Collectors.toMap(Product::id, Function.identity()));
        return calculator.calculate(order, byId);
    }

    @Transactional
    public PurchaseOrder create(long supplierId, BigDecimal cnyToUsd, BigDecimal usdToEur,
                                BigDecimal defaultDutyRatePct) {
        return create(supplierId, cnyToUsd, usdToEur, defaultDutyRatePct, ContainerType.FORTY_HQ);
    }

    /**
     * Creates a calculation for one full container. The nullable fallback is
     * deliberate: older app versions did not send a container type and must
     * keep their historical 40 ft HQ default during a rolling deployment.
     */
    @Transactional
    public PurchaseOrder create(long supplierId, BigDecimal cnyToUsd, BigDecimal usdToEur,
                                BigDecimal defaultDutyRatePct, ContainerType containerType) {
        requireSupplier(supplierId);
        requirePositive(cnyToUsd, "CNY/USD-koers");
        requirePositive(usdToEur, "USD/EUR-koers");
        requirePercentage(defaultDutyRatePct, "Standaard invoerrecht");
        ContainerType selectedContainer = containerType == null ? ContainerType.FORTY_HQ : containerType;
        if (!selectedContainer.hasCapacity()) {
            throw new BusinessRuleException("Kies 20 ft, 40 ft of 40 ft HQ voor een nieuwe inkooporder");
        }

        ActorRef creator = currentActor();
        PurchaseOrder draft = new PurchaseOrder(
                null, nextNumber(), null, supplierId, LocalDate.now(),
                PurchaseOrderStatus.CONCEPT, selectedContainer,
                cnyToUsd, usdToEur, usdToEur,
                BigDecimal.ZERO, BigDecimal.ZERO, be.enrosed.shared.Currency.USD, BigDecimal.ZERO,
                defaultDutyRatePct, new BigDecimal("2000"),
                Allocation.CBM, Allocation.CBM, Allocation.CBM, Allocation.PIECES,
                "Ningbo", "Rotterdam", "", List.of())
                .withCreationMetadata(creator, Instant.now());
        PurchaseOrder created = orders.save(draft);
        recordActivity(ActivityLogService.ACTION_CREATED, created, "Inkooporder aangemaakt");
        firePush(new PurchasePushNotifier.Ready(PurchasePushNotifier.Kind.CREATED,
                created.id(), created.number(), created.destinationPort(), false, creator));
        return created;
    }

    /**
     * Makes a copy of a purchase calculation.
     *
     * Meant to price a variant quickly: another container size, another
     * exchange rate, a supplier adjusting their price. Everything comes along
     * except the status and the number - a copy is a new draft, not a second
     * instance of an order already placed.
     */
    @Transactional
    public PurchaseOrder duplicate(long id) {
        PurchaseOrder source = get(id);
        String alias = source.alias() == null || source.alias().isBlank()
                ? null : source.alias() + " (kopie)";
        ActorRef creator = currentActor();
        PurchaseOrder copy = orders.save(new PurchaseOrder(
                null, nextNumber(), alias, source.supplierId(), LocalDate.now(),
                /* Always a draft: otherwise a copy of a received order would
                   book the stock a second time. */
                PurchaseOrderStatus.CONCEPT, source.containerType(),
                source.cnyToUsd(), unifiedUsdToEur(source), unifiedUsdToEur(source),
                source.freightUsd(), source.originCosts(), source.originCurrency(),
                source.destinationCostsEur(), source.defaultDutyRatePct(), source.extraRevenueEur(),
                source.allocFreight(), source.allocOrigin(), source.allocDestination(),
                source.allocExtra(), source.departurePort(), source.destinationPort(), source.notes(),
                source.lines().stream()
                        .map(line -> new PurchaseOrderLine(null, line.productId(), line.quantity(),
                                line.exwPrice(), line.exwCurrency(), line.extraUnitCost(), null,
                                line.priceBasis()))
                        .toList()).withCreationMetadata(creator, Instant.now()));
        recordActivity(ActivityLogService.ACTION_DUPLICATED, copy,
                "Inkooporder gedupliceerd vanuit " + source.number());
        return copy;
    }

    /**
     * A quantity that is not a whole number of cartons.
     *
     * Reported, never corrected: a supplier can perfectly well ship a
     * three-piece sample, and silently inflating an order to a supplier costs
     * real money. {@code adjusted} carries the nearest full carton as a
     * suggestion for the screen.
     */
    public record CartonAdjustment(long productId, String productName, int requested,
                                   int adjusted, int piecesPerCarton) {}

    public record UpdateResult(PurchaseOrder order, List<CartonAdjustment> adjustments) {}

    /**
     * Updates the purchase order.
     *
     * Quantities are entered in pieces, because that is how you talk to a
     * supplier. Quantities that do not fill whole cartons are flagged in the
     * response but saved exactly as entered — unlike sales, purchasing never
     * rounds. Only the user knows whether "3 pieces" is a typo or a sample.
     */
    @Transactional
    public UpdateResult update(long id, PurchaseOrder changes) {
        PurchaseOrder current = getForUpdate(id);
        if (changes == null) {
            throw new BusinessRuleException("Geen inkoopordergegevens meegestuurd");
        }
        validateHeader(changes);
        requireForwardTransition(current.status(), changes.status());
        List<String> lateDamage = new ArrayList<>();
        if (current.status() == PurchaseOrderStatus.ONTVANGEN) {
            requireReceivedLinesUnchanged(current, changes);
            lateDamage.addAll(bookLateDamage(current, changes));
        }

        Map<Long, Product> byId = products.list().stream()
                .collect(Collectors.toMap(Product::id, Function.identity()));

        List<CartonAdjustment> warnings = new ArrayList<>();
        List<PurchaseOrderLine> lines = new ArrayList<>();
        Set<Long> seenProducts = new HashSet<>();
        boolean placingNow = current.status() == PurchaseOrderStatus.CONCEPT
                && changes.status() != PurchaseOrderStatus.CONCEPT;

        for (PurchaseOrderLine line : changes.lines()) {
            if (line == null || line.productId() == null) {
                throw new BusinessRuleException("Elke inkoopregel moet bij een product horen");
            }
            if (!seenProducts.add(line.productId())) {
                throw new BusinessRuleException(
                        "Product " + line.productId() + " staat dubbel op de inkooporder");
            }
            Product product = byId.get(line.productId());
            if (product == null) {
                throw new BusinessRuleException("Product " + line.productId() + " bestaat niet meer");
            }
            if (!Objects.equals(product.supplierId(), changes.supplierId())
                    && !keepsHistoricalSupplierLine(current, changes, line, placingNow)) {
                throw new BusinessRuleException("Product " + product.sku()
                        + " hoort niet bij de gekozen leverancier");
            }
            if (line.quantity() < 0) {
                throw new BusinessRuleException("Een besteld of ontvangen aantal kan niet negatief zijn");
            }
            requireNonNegative(line.exwPrice(), "EXW-prijs");
            requireNonNegative(line.extraUnitCost(), "Extra kost per stuk");
            LinePurchasePrice purchasePrice = purchasePriceForSave(
                    current, line, product, placingNow);

            Carton carton = product.carton() == null ? Carton.empty() : product.carton();
            int perCarton = Math.max(1, carton.piecesPerCarton());
            int requested = line.quantity();
            int fullCartons = carton.cartonsFor(requested) * perCarton;

            /* Only a freshly typed count earns the warning: editing a rate or
               a note must not repeat it for numbers that stood for weeks. */
            boolean quantityTouched = current.lines().stream()
                    .noneMatch(stored -> java.util.Objects.equals(stored.productId(), line.productId())
                            && stored.quantity() == requested);
            if (fullCartons != requested && quantityTouched) {
                warnings.add(new CartonAdjustment(
                        product.id(), product.describe(), requested, fullCartons, perCarton));
            }
            /* Saved as entered; the warning is the whole intervention. */
            lines.add(new PurchaseOrderLine(line.id(), line.productId(), requested,
                    purchasePrice.amount(), purchasePrice.currency(), line.extraUnitCost(),
                    orderedQuantityFor(current, changes, line, requested), line.priceBasis(),
                    line.damagedQuantity(), storedReceiptUnitValue(current, line), cleanIssueNote(line.issueNote())));
        }

        if (changes.status() != PurchaseOrderStatus.CONCEPT
                && lines.stream().noneMatch(line -> line.quantity() > 0)) {
            throw new BusinessRuleException("Een geplaatste inkooporder moet minstens één product bevatten");
        }

        BigDecimal usdToEur = unifiedUsdToEur(changes);
        PurchaseOrder saved = orders.save(new PurchaseOrder(
                current.id(), numberFor(current, changes), changes.alias(),
                changes.supplierId(), changes.orderDate(),
                changes.status(), changes.containerType(),
                changes.cnyToUsd(), usdToEur, usdToEur,
                changes.freightUsd(), changes.originCosts(), changes.originCurrency(),
                changes.destinationCostsEur(), changes.defaultDutyRatePct(), changes.extraRevenueEur(),
                changes.allocFreight(), changes.allocOrigin(), changes.allocDestination(),
                changes.allocExtra(), changes.departurePort(), changes.destinationPort(),
                changes.receivingLocationId(), changes.groupVariants(),
                changes.expectedArrival(), current.receivedOn(), current.paidTotalEur(), current.stockBooked(),
                changes.paymentTerms(),
                /* The sailing date is set the moment the status says so, and kept. */
                current.shippedOn() != null ? current.shippedOn()
                        : changes.status() == PurchaseOrderStatus.ONDERWEG ? LocalDate.now() : null,
                changes.trackingReference(),
                current.createdBy(), current.createdAt(),
                withLateDamageNotes(changes.notes(), lateDamage), lines));

        if (!saved.equals(current)) {
            List<ActivityChangeDto> auditChanges = purchaseChanges(current, saved, byId);
            if (current.status() != saved.status()) {
                recordActivity(ActivityLogService.ACTION_STATUS_CHANGED, saved,
                        "Status gewijzigd van " + statusLabel(current.status()) + " naar " + statusLabel(saved.status()),
                        auditChanges);
            } else {
                recordActivity(ActivityLogService.ACTION_UPDATED, saved, "Inkooporder bijgewerkt", auditChanges);
            }
        }
        if (current.status() != saved.status()) {
            switch (saved.status()) {
                case BESTELD -> firePush(new PurchasePushNotifier.Ready(PurchasePushNotifier.Kind.ORDERED,
                        saved.id(), saved.number(), saved.destinationPort(), false, currentActor()));
                case ONDERWEG -> firePush(new PurchasePushNotifier.Ready(PurchasePushNotifier.Kind.DEPARTED,
                        saved.id(), saved.number(), saved.destinationPort(), false, currentActor()));
                default -> { }
            }
        }
        return new UpdateResult(saved, warnings);
    }

    private record LinePurchasePrice(BigDecimal amount, Currency currency) {}

    /**
     * A product can be reassigned after an order was placed. That must not
     * make an unrelated tracking/header correction impossible, but it also
     * must not authorize a new product line or a supplier switch.
     */
    private static boolean keepsHistoricalSupplierLine(
            PurchaseOrder current, PurchaseOrder changes,
            PurchaseOrderLine incoming, boolean placingNow) {
        if (placingNow || current.status() == PurchaseOrderStatus.CONCEPT
                || !Objects.equals(current.supplierId(), changes.supplierId())
                || incoming.id() == null) {
            return false;
        }
        return current.lines().stream().anyMatch(stored ->
                Objects.equals(stored.id(), incoming.id())
                        && Objects.equals(stored.productId(), incoming.productId()));
    }

    /**
     * Price and currency are one agreement: never combine a line currency with
     * a product amount (or vice versa). When a draft is placed, a line without
     * an override snapshots the then-current product purchase-price pair.
     */
    private LinePurchasePrice purchasePriceForSave(PurchaseOrder current,
                                                   PurchaseOrderLine incoming,
                                                   Product product,
                                                   boolean placingNow) {
        boolean hasAmount = incoming.exwPrice() != null;
        boolean hasCurrency = incoming.exwCurrency() != null;
        if (hasAmount != hasCurrency) {
            throw new BusinessRuleException("Vul voor product " + product.sku()
                    + " zowel de inkoopprijs als de valuta in, of laat beide leeg");
        }
        if (hasAmount) {
            return new LinePurchasePrice(incoming.exwPrice(), incoming.exwCurrency());
        }

        if (placingNow) {
            return completePrice(product.exwPrice(), product.exwCurrency());
        }

        /* Once placed, a full PUT from an older client may omit the snapshot.
           Preserve storage instead of silently returning to a live master price. */
        if (current.status() != PurchaseOrderStatus.CONCEPT && incoming.id() != null) {
            PurchaseOrderLine stored = current.lines().stream()
                    .filter(line -> incoming.id().equals(line.id()))
                    .findFirst()
                    .orElse(null);
            if (stored != null) {
                return completePrice(stored.exwPrice(), stored.exwCurrency());
            }
        }
        return new LinePurchasePrice(null, null);
    }

    private static LinePurchasePrice completePrice(BigDecimal amount, Currency currency) {
        return amount == null || currency == null
                ? new LinePurchasePrice(null, null)
                : new LinePurchasePrice(amount, currency);
    }

    /**
     * What the line's ordered-quantity snapshot should be after this update.
     *
     * The moment the order leaves concept it has been placed with the
     * supplier; from then on the quantity as ordered is a fact worth keeping.
     * Containers regularly arrive short, and "ordered 96, received 90" is the
     * difference between an explainable order and a mystery. Lines added
     * after ordering never get a snapshot: nothing was agreed for them.
     */
    private Integer orderedQuantityFor(PurchaseOrder current, PurchaseOrder changes,
                                       PurchaseOrderLine line, int requested) {
        boolean placingNow = current.status() == PurchaseOrderStatus.CONCEPT
                && changes.status() != PurchaseOrderStatus.CONCEPT;
        if (placingNow) {
            /* This save confirms the order: these are the agreed quantities. */
            return requested;
        }
        if (current.status() == PurchaseOrderStatus.CONCEPT) {
            /* Not ordered yet: nothing has been agreed, so nothing to keep. */
            return null;
        }
        /* Past ordering: preserve the value from storage, never the value the
           client echoed back. Lines added after ordering stay without one -
           nothing was agreed for them. */
        if (line.id() == null) return null;
        return current.lines().stream()
                .filter(stored -> line.id().equals(stored.id()))
                .map(PurchaseOrderLine::orderedQuantity)
                .findFirst()
                .orElse(null);
    }

    /** Receipt valuations are server-owned snapshots, never mutable through the full order payload. */
    private static BigDecimal storedReceiptUnitValue(PurchaseOrder current, PurchaseOrderLine incoming) {
        if (current.status() != PurchaseOrderStatus.ONTVANGEN || incoming.id() == null) return null;
        PurchaseOrderLine stored = current.lines().stream()
                .filter(candidate -> incoming.id().equals(candidate.id()))
                .findFirst()
                .orElse(null);
        return stored == null ? null : stored.receiptUnitValueEur();
    }

    /**
     * The order number as it should be after an update.
     *
     * Setting a number by hand matters when migrating from another system or
     * matching the supplier's numbering. Two orders with the same number are
     * refused: every reference to it would be ambiguous. Empty means: keep
     * what was there.
     */
    private String numberFor(PurchaseOrder current, PurchaseOrder changes) {
        String wanted = changes.number() == null ? null : changes.number().trim();
        if (wanted == null || wanted.isBlank() || wanted.equals(current.number())) {
            return current.number();
        }
        boolean taken = orders.findAll().stream()
                .anyMatch(other -> !other.id().equals(current.id())
                        && wanted.equalsIgnoreCase(other.number()));
        if (taken) {
            throw new BusinessRuleException("Er bestaat al een inkooporder met nummer " + wanted);
        }
        return wanted;
    }

    private static final java.time.format.DateTimeFormatter DAY = java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy");

    /* ---- payments ---------------------------------------------------------- */

    public List<PurchasePayment> payments(long orderId) {
        get(orderId);
        return payments == null || !payments.isResolvable() ? List.of() : payments.get().forOrder(orderId);
    }

    /**
     * Records money that left for this order, in the currency it left in,
     * with the euro value at the order's rates. Nothing is derived later:
     * what the bank line said is what stays.
     */
    @Transactional
    public PurchasePayment addPayment(long orderId, LocalDate paidOn, BigDecimal amount, Currency currency,
                                      String label) {
        return addPayment(orderId, paidOn, amount, currency, label, PurchasePayment.Payee.SUPPLIER);
    }

    /**
     * Records money that left, and writes a line about it into the order's
     * notes - the notes are the container's diary, and a payment belongs in it.
     */
    @Transactional
    public PurchasePayment addPayment(long orderId, LocalDate paidOn, BigDecimal amount, Currency currency,
                                      String label, PurchasePayment.Payee payee) {
        PurchaseOrder order = get(orderId);
        if (amount == null || amount.signum() <= 0) throw new BusinessRuleException("Geef een bedrag groter dan nul op");
        Currency money = currency == null ? Currency.EUR : currency;
        BigDecimal eur = switch (money) {
            case EUR -> amount;
            case USD -> amount.multiply(Money.nz(order.usdToEurGoods()));
            case CNY -> amount.multiply(Money.nz(order.cnyToUsd())).multiply(Money.nz(order.usdToEurGoods()));
        };
        LocalDate day = paidOn != null ? paidOn : LocalDate.now();
        PurchasePayment.Payee to = payee == null ? PurchasePayment.Payee.SUPPLIER : payee;
        BigDecimal eurRounded = eur.setScale(2, java.math.RoundingMode.HALF_UP);
        PurchasePayment payment = payments.get().save(new PurchasePayment(null, orderId, day,
                amount.setScale(2, java.math.RoundingMode.HALF_UP), money, eurRounded,
                label == null || label.isBlank() ? null : label.strip(),
                currentActor().displayName(), java.time.Instant.now(), to));

        orders.save(order.withReceipt(order.status(), order.receivedOn(), order.paidTotalEur(), order.stockBooked(),
                appendNote(order.notes(), paymentNoteLine(payment)), order.lines()));
        recordActivity(ActivityLogService.ACTION_PAYMENT_ADDED, order,
                "Betaling aan " + to.dutchLabel().toLowerCase(java.util.Locale.ROOT) + " toegevoegd",
                ActivityChangeSet.create()
                        .add("payment.amount", "Bedrag", null, payment.amount())
                        .add("payment.currency", "Valuta", null, payment.currency())
                        .add("payment.date", "Betaald op", null, payment.paidOn())
                        .add("payment.payee", "Begunstigde", null, payment.payee().dutchLabel())
                        .privateValue("payment.label", "Omschrijving", null, payment.label())
                        .build());
        return payment;
    }

    /** The diary line a payment writes; built one way so deleting can find it again. */
    private static String paymentNoteLine(PurchasePayment payment) {
        return "Betaald " + payment.paidOn().format(DAY) + ": " + describeMoney(payment.amount(), payment.currency())
                + (payment.currency() != Currency.EUR ? " (≈ " + describeMoney(payment.amountEur(), Currency.EUR) + ")" : "")
                + " aan " + (payment.payee() == PurchasePayment.Payee.SUPPLIER ? "de leverancier" : "douane & transport")
                + (payment.label() != null ? " · " + payment.label() : "") + ".";
    }

    private static String describeMoney(BigDecimal amount, Currency currency) {
        String symbol = switch (currency) { case EUR -> "€ "; case USD -> "US$ "; case CNY -> "CN¥ "; };
        /* Belgian figures: a point every three digits, a comma before the cents. */
        return symbol + String.format(java.util.Locale.forLanguageTag("nl-BE"), "%,.2f",
                amount.setScale(2, java.math.RoundingMode.HALF_UP));
    }

    private static String appendNote(String notes, String line) {
        return notes == null || notes.isBlank() ? line : notes.stripTrailing() + "\n" + line;
    }

    /* ---- documents ----------------------------------------------------------- */

    public List<PurchaseDocument> documents(long orderId) {
        get(orderId);
        return documents == null || !documents.isResolvable() ? List.of() : documents.get().forOrder(orderId);
    }

    public PurchaseDocument document(long orderId, long documentId) {
        get(orderId);
        return documents.get().find(orderId, documentId).orElseThrow(() -> new NotFoundException("Document", documentId));
    }

    public java.io.InputStream documentData(PurchaseDocument document) {
        return photoStorage.get().read(document.storageKey());
    }

    /** Keeps a file with the container, in the same store as the photos. */
    @Transactional
    public PurchaseDocument addDocument(long orderId, PurchaseDocument.Kind kind, String label, Long paymentId,
                                        String filename, String contentType, byte[] bytes) {
        PurchaseOrder order = get(orderId);
        if (bytes == null || bytes.length == 0) throw new BusinessRuleException("Het bestand is leeg");
        if (bytes.length > 25 * 1024 * 1024) throw new BusinessRuleException("Een bestand mag hoogstens 25 MB zijn");
        if (paymentId != null) {
            long proofs = documents.get().forOrder(orderId).stream().filter(d -> paymentId.equals(d.paymentId())).count();
            if (proofs >= 5) throw new BusinessRuleException("Bij één betaling horen hoogstens vijf bewijsstukken");
        } else {
            /* Every category keeps room for a handful, not an archive. */
            PurchaseDocument.Kind wanted = kind == null ? PurchaseDocument.Kind.OTHER : kind;
            long inKind = documents.get().forOrder(orderId).stream()
                    .filter(d -> d.paymentId() == null && d.kind() == wanted).count();
            if (inKind >= 5) throw new BusinessRuleException("Per categorie horen hoogstens vijf bestanden bij een order");
        }
        String name = filename == null || filename.isBlank() ? "document" : filename.strip();
        String type = contentType == null || contentType.isBlank() ? "application/octet-stream" : contentType;
        var stored = photoStorage.get().store(name, type, bytes);
        fireUploadCleanup(new PurchaseDocumentStorageCleanup.UploadReady(orderId, stored.storageKey()));
        PurchaseDocument saved = documents.get().save(new PurchaseDocument(null, orderId,
                kind == null ? PurchaseDocument.Kind.OTHER : kind,
                label == null || label.isBlank() ? null : label.strip(), name, type, bytes.length, stored.storageKey(),
                paymentId, currentActor().displayName(), java.time.Instant.now()));
        recordActivity(ActivityLogService.ACTION_DOCUMENT_ADDED, order, "Document toegevoegd",
                ActivityChangeSet.create()
                        .privateValue("document.filename", "Bestand", null, saved.originalFilename())
                        .add("document.kind", "Documenttype", null, saved.kind())
                        .privateValue("document.label", "Naam", null, saved.label())
                        .build());
        return saved;
    }

    /** The pencil next to an uploaded file: the title stays editable afterwards. */
    @Transactional
    public PurchaseDocument renameDocument(long orderId, long documentId, String label) {
        PurchaseOrder order = get(orderId);
        PurchaseDocument current = document(orderId, documentId);
        String cleaned = label == null || label.isBlank() ? null : label.strip();
        PurchaseDocument renamed = documents.get().rename(orderId, documentId, cleaned)
                .orElseThrow(() -> new NotFoundException("Document", documentId));
        recordActivity(ActivityLogService.ACTION_DOCUMENT_RENAMED, order, "Documentnaam gewijzigd",
                ActivityChangeSet.create()
                        .privateValue("document.label", "Documentnaam", current.label(), renamed.label())
                        .build());
        return renamed;
    }

    @Transactional
    public void deleteDocument(long orderId, long documentId) {
        PurchaseOrder order = get(orderId);
        PurchaseDocument document = document(orderId, documentId);
        documents.get().delete(orderId, documentId);
        unlinkLegacyMedia(MediaLegacySourceType.PURCHASE_DOCUMENT, documentId);
        recordActivity(ActivityLogService.ACTION_DOCUMENT_DELETED, order, "Document verwijderd",
                ActivityChangeSet.create()
                        .privateValue("document.filename", "Bestand", document.originalFilename(), null)
                        .privateValue("document.label", "Naam", document.label(), null)
                        .build());
        fireDocumentDeleteCleanup(new PurchaseDocumentStorageCleanup.DeleteReady(
                orderId, List.of(document.storageKey())));
    }

    /**
     * Who is owed what, in euro: the supplier gets the goods (and the sea
     * freight when the price is CIF/CFR); the forwarder and customs get the
     * road; the Enrosed kost is ours and nobody's invoice.
     */
    public Payable payable(PurchaseOrder order, LandedCost costing, String supplierIncoterm) {
        boolean ddp = order.lines().stream().allMatch(PurchaseOrderLine::deliveredDutyPaid) && !order.lines().isEmpty();
        /* The factory is owed its goods price, nothing more: the freight on
           the order is our own quote, whatever the incoterm on paper says.
           Only DDP folds everything into the piece price. */
        BigDecimal supplier = costing.totals().goodsEur();
        BigDecimal logistics = ddp ? BigDecimal.ZERO
                : costing.totals().originEur().add(costing.totals().dutyEur()).add(costing.totals().destinationEur())
                        .add(costing.totals().freightEur());
        return new Payable(supplier.setScale(2, java.math.RoundingMode.HALF_UP),
                logistics.setScale(2, java.math.RoundingMode.HALF_UP),
                costing.totals().extraRevenueEur(), ddp, ddp);
    }

    public record Payable(BigDecimal supplierEur, BigDecimal logisticsEur, BigDecimal enrosedEur,
                          boolean freightInSupplierPrice, boolean ddp) {}

    /**
     * What the order is waiting on from us: a box on the water without a
     * tracking reference, an instalment whose moment has come and is not
     * noted yet. Once ordered, something must always have been paid -
     * without a plan, the first payment itself is the open point.
     */
    public List<String> attention(PurchaseOrder order, Payable payable) {
        List<String> items = new ArrayList<>();
        if (order.status() == PurchaseOrderStatus.ONDERWEG
                && (order.trackingReference() == null || order.trackingReference().isBlank())) {
            items.add("Track & trace ontbreekt");
        }
        if (order.status() == PurchaseOrderStatus.CONCEPT || payable == null || payable.supplierEur().signum() <= 0
                || payments == null || !payments.isResolvable()) {
            return items;
        }
        BigDecimal owed = payable.supplierEur();
        BigDecimal paid = payments.get().forOrder(order.id()).stream()
                .filter(payment -> payment.payee() == PurchasePayment.Payee.SUPPLIER)
                .map(PurchasePayment::amountEur).reduce(BigDecimal.ZERO, BigDecimal::add);
        PaymentTerms terms = order.paymentTerms() == null ? PaymentTerms.THIRDS : order.paymentTerms();
        if (terms.instalments().isEmpty()) {
            if (paid.signum() == 0) items.add("Nog geen betaling genoteerd");
            return items;
        }
        /* Ticked off against the running total with a few cents of slack, the
           same way the screen does it. */
        BigDecimal slack = new BigDecimal("0.05");
        BigDecimal cumulative = BigDecimal.ZERO;
        /* What is genuinely still open on the stream: a due instalment never
           asks for more than that, or the note screen would refuse its own
           suggestion after earlier payments that did not line up exactly. */
        BigDecimal stillOpen = owed.subtract(paid).max(BigDecimal.ZERO);
        boolean earlierOpen = false;
        for (PaymentTerms.Instalment step : terms.instalments()) {
            BigDecimal amount = owed.multiply(step.share()).setScale(2, java.math.RoundingMode.HALF_UP);
            cumulative = cumulative.add(amount);
            boolean covered = !earlierOpen && paid.compareTo(cumulative.min(owed).subtract(slack)) >= 0;
            if (covered) continue;
            earlierOpen = true;
            boolean due = switch (step.due()) {
                case ORDERED -> true;
                case SHIPPED -> order.status() == PurchaseOrderStatus.ONDERWEG
                        || order.status() == PurchaseOrderStatus.ONTVANGEN;
                case ARRIVED -> order.status() == PurchaseOrderStatus.ONTVANGEN;
            };
            BigDecimal ask = amount.min(stillOpen);
            stillOpen = stillOpen.subtract(ask).max(BigDecimal.ZERO);
            if (due && ask.signum() > 0) {
                items.add("Betaling open: " + step.label() + " (" + describeMoney(ask, Currency.EUR) + ")");
            }
        }
        return items;
    }

    @Transactional
    public void deletePayment(long orderId, long paymentId) {
        PurchaseOrder order = getForUpdate(orderId);
        PurchasePayment payment = payments.get().forOrder(orderId).stream()
                .filter(candidate -> candidate.id() != null && candidate.id() == paymentId)
                .findFirst().orElseThrow(() -> new NotFoundException("Betaling", paymentId));
        if (!payments.get().delete(orderId, paymentId)) throw new NotFoundException("Betaling", paymentId);
        /* The diary line the payment wrote goes with it. */
        String cleaned = removeNoteLine(order.notes(), paymentNoteLine(payment));
        if (!java.util.Objects.equals(cleaned, order.notes())) {
            orders.save(order.withReceipt(order.status(), order.receivedOn(), order.paidTotalEur(),
                    order.stockBooked(), cleaned, order.lines()));
        }
        recordActivity(ActivityLogService.ACTION_PAYMENT_DELETED, order, "Betaling verwijderd",
                ActivityChangeSet.create()
                        .add("payment.amount", "Bedrag", payment.amount(), null)
                        .add("payment.currency", "Valuta", payment.currency(), null)
                        .add("payment.date", "Betaald op", payment.paidOn(), null)
                        .add("payment.payee", "Begunstigde", payment.payee().dutchLabel(), null)
                        .build());
    }

    /** Removes the first line that matches, and nothing else someone wrote. */
    private static String removeNoteLine(String notes, String line) {
        if (notes == null || notes.isBlank()) return notes;
        List<String> kept = new ArrayList<>();
        boolean removed = false;
        for (String candidate : notes.split("\n", -1)) {
            if (!removed && candidate.strip().equals(line)) { removed = true; continue; }
            kept.add(candidate);
        }
        if (!removed) return notes;
        String joined = String.join("\n", kept).replaceAll("\n{3,}", "\n\n").strip();
        return joined.isBlank() ? null : joined;
    }

    /** One line of a receipt: what arrived, what broke, and an optional explicit euro value per piece. */
    public record ReceivedLine(Long productId, Integer received, Integer damaged,
                               BigDecimal unitValueEur, String issueNote) {
        /** Compatibility for clients and tests from before receipt valuation. */
        public ReceivedLine(Long productId, Integer received, Integer damaged) {
            this(productId, received, damaged, null, null);
        }

        public ReceivedLine(Long productId, Integer received, Integer damaged, BigDecimal unitValueEur) {
            this(productId, received, damaged, unitValueEur, null);
        }
    }

    public record Receipt(List<ReceivedLine> lines, boolean bookStock, BigDecimal paidTotalEur,
                          LocalDate receivedOn, String note) {}

    /**
     * The container is in. Counts replace the ordered quantities (the order
     * remembers what was ordered), broken pieces are noted, the receipt is
     * written into the notes with its date, and - when asked - the usable
     * pieces go into stock at the receiving location.
     */
    @Transactional
    public PurchaseOrder receive(long id, Receipt receipt) {
        PurchaseOrder order = getForUpdate(id);
        if (order.status() == PurchaseOrderStatus.ONTVANGEN) {
            throw new BusinessRuleException("Deze container is al ontvangen");
        }
        requireForwardTransition(order.status(), PurchaseOrderStatus.ONTVANGEN);
        if (receipt == null) receipt = new Receipt(List.of(), true, null, null, null);
        LocalDate day = receipt.receivedOn() != null ? receipt.receivedOn() : LocalDate.now();

        Map<Long, ReceivedLine> counted = new HashMap<>();
        Set<Long> orderedProducts = order.lines().stream()
                .map(PurchaseOrderLine::productId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        for (ReceivedLine line : receipt.lines() == null ? List.<ReceivedLine>of() : receipt.lines()) {
            if (line == null || line.productId() == null) {
                throw new BusinessRuleException("Elke ontvangstregel moet bij een product horen");
            }
            if (!orderedProducts.contains(line.productId())) {
                throw new BusinessRuleException("Product " + line.productId()
                        + " staat niet op deze inkooporder");
            }
            if (counted.putIfAbsent(line.productId(), line) != null) {
                throw new BusinessRuleException("Product " + line.productId()
                        + " staat dubbel in de ontvangst");
            }
        }
        List<PurchaseOrderLine> lines = new ArrayList<>();
        List<String> remarks = new ArrayList<>();
        Map<Long, Product> byId = productNames(order);
        /* Calculate against the still-ordered quantities. Recalculating after
           replacement by received counts would silently change the historical
           per-piece basis for a short delivery. */
        Map<Long, BigDecimal> automaticUnitValues = receiptUnitValues(order, byId);
        for (PurchaseOrderLine line : order.lines()) {
            ReceivedLine count = counted.get(line.productId());
            int received = count == null || count.received() == null ? line.quantity() : count.received();
            int damaged = count == null || count.damaged() == null ? 0 : count.damaged();
            if (received < 0 || damaged < 0) {
                throw new BusinessRuleException("Ontvangen en beschadigde aantallen kunnen niet negatief zijn");
            }
            if (damaged > received) {
                throw new BusinessRuleException("Meer beschadigd dan ontvangen bij "
                        + describe(byId, line.productId()));
            }
            BigDecimal explicitUnitValue = count == null ? null : count.unitValueEur();
            if (explicitUnitValue != null && explicitUnitValue.signum() < 0) {
                throw new BusinessRuleException("Waarde per stuk kan niet negatief zijn bij "
                        + describe(byId, line.productId()));
            }
            BigDecimal receiptUnitValue = explicitUnitValue == null
                    ? automaticUnitValues.get(line.productId()) : Money.unit(explicitUnitValue);
            int ordered = line.orderedQuantity() != null ? line.orderedQuantity() : line.quantity();
            String issueNote = cleanIssueNote(count == null ? null : count.issueNote());
            if (received != ordered || damaged > 0) {
                StringBuilder remark = new StringBuilder(describe(byId, line.productId()))
                        .append(": besteld ").append(ordered).append(", ontvangen ").append(received);
                if (damaged > 0) remark.append(", ").append(damaged).append(" beschadigd");
                if (issueNote != null) remark.append(" (").append(issueNote).append(')');
                remarks.add(remark.toString());
            }
            lines.add(new PurchaseOrderLine(line.id(), line.productId(), received, line.exwPrice(),
                    line.exwCurrency(), line.extraUnitCost(), ordered, line.priceBasis(), damaged,
                    receiptUnitValue, received != ordered || damaged > 0 ? issueNote : null));
        }

        String notes = appendReceiptNote(order.notes(), day, remarks, receipt.note());
        PurchaseOrder received = orders.save(order.withReceipt(PurchaseOrderStatus.ONTVANGEN, day,
                receipt.paidTotalEur(), false, notes, lines));
        recordActivity(ActivityLogService.ACTION_RECEIVED, received, "Container ontvangen",
                purchaseChanges(order, received, byId));
        firePush(new PurchasePushNotifier.Ready(PurchasePushNotifier.Kind.RECEIVED,
                received.id(), received.number(), received.destinationPort(), receipt.bookStock(), currentActor()));
        return receipt.bookStock() ? bookStock(received.id()) : received;
    }

    /** Puts the usable pieces of a received container into stock - once. */
    @Transactional
    public PurchaseOrder bookStock(long id) {
        PurchaseOrder order = getForUpdate(id);
        if (order.status() != PurchaseOrderStatus.ONTVANGEN) {
            throw new BusinessRuleException("Alleen een ontvangen container kan bijgeboekt worden");
        }
        if (order.isStockBooked()) {
            throw new BusinessRuleException("De voorraad van deze container is al bijgeboekt");
        }
        for (PurchaseOrderLine line : order.lines()) {
            if (line.usable() > 0) {
                products.receiveStock(line.productId(), line.usable(), order.number(), order.receivingLocationId());
            }
            if (line.damaged() > 0) {
                products.noteDamagedOnArrival(line.productId(), line.damaged(), order.number(),
                        order.receivingLocationId());
            }
        }
        LOG.infof("Voorraad bijgeboekt uit %s: %d regel(s)", order.number(), order.lines().size());
        String where = locationNames != null && locationNames.isResolvable() && order.receivingLocationId() != null
                ? locationNames.get().location(order.receivingLocationId()).name() : "Magazijn";
        String note = "Voorraad bijgeboekt op " + LocalDate.now().format(DAY) + " (" + where + ").";
        String notes = order.notes() == null || order.notes().isBlank() ? note : order.notes().stripTrailing() + "\n" + note;
        PurchaseOrder booked = orders.save(order.withReceipt(order.status(), order.receivedOn(),
                order.paidTotalEur(), true, notes, order.lines()));
        recordActivity(ActivityLogService.ACTION_STOCK_BOOKED, booked, "Voorraad bijgeboekt");
        return booked;
    }

    /** Pieces still on the water: per product the sum over ordered and shipped containers. */
    public List<ExpectedStock> expectedStock() {
        Map<Long, ExpectedStock> byProduct = new java.util.LinkedHashMap<>();
        for (PurchaseOrder order : orders.findAll()) {
            if (order.status() != PurchaseOrderStatus.BESTELD && order.status() != PurchaseOrderStatus.ONDERWEG) continue;
            for (PurchaseOrderLine line : order.lines()) {
                if (line.quantity() <= 0) continue;
                ExpectedStock current = byProduct.get(line.productId());
                LocalDate arrival = order.expectedArrival();
                if (current == null) {
                    byProduct.put(line.productId(), new ExpectedStock(line.productId(), line.quantity(), arrival,
                            new ArrayList<>(List.of(order.number())), new ArrayList<>(List.of(order.id()))));
                } else {
                    LocalDate earliest = current.expectedArrival() == null ? arrival
                            : arrival == null ? current.expectedArrival()
                            : arrival.isBefore(current.expectedArrival()) ? arrival : current.expectedArrival();
                    List<String> numbers = new ArrayList<>(current.orderNumbers());
                    numbers.add(order.number());
                    List<Long> ids = new ArrayList<>(current.orderIds());
                    ids.add(order.id());
                    byProduct.put(line.productId(), new ExpectedStock(line.productId(),
                            current.quantity() + line.quantity(), earliest, numbers, ids));
                }
            }
        }
        return List.copyOf(byProduct.values());
    }

    public record ExpectedStock(long productId, int quantity, LocalDate expectedArrival, List<String> orderNumbers,
                                List<Long> orderIds) {}

    /** Aggregate counters for the affected receipt lines in the selected scope. */
    public record ReceiptVarianceTotals(
            long affectedOrders,
            long affectedLines,
            long orderedPieces,
            long receivedPieces,
            long missingPieces,
            long overReceivedPieces,
            long damagedPieces,
            long usablePieces,
            BigDecimal missingValueEur,
            BigDecimal damagedValueEur,
            BigDecimal totalLossValueEur,
            long unvaluedLossPieces,
            boolean valuationComplete
    ) {}

    /** One damaged or short purchase line, enriched for the historical metric screen. */
    public record ReceiptVarianceRow(
            long orderId,
            String orderNumber,
            String orderAlias,
            LocalDate receivedOn,
            LocalDate expectedArrival,
            Long supplierId,
            String supplierName,
            Long lineId,
            Long productId,
            String productSku,
            String productName,
            int orderedPieces,
            int receivedPieces,
            int missingPieces,
            int overReceivedPieces,
            int damagedPieces,
            int usablePieces,
            BigDecimal receiptUnitValueEur,
            BigDecimal missingValueEur,
            BigDecimal damagedValueEur,
            BigDecimal totalLossValueEur,
            boolean valuationComplete
    ) {}

    public record ReceiptVarianceReport(ReceiptVarianceTotals totals,
                                        List<ReceiptVarianceRow> rows) {}

    /** All historical receipt exceptions, optionally narrowed for dashboard metrics. */
    public ReceiptVarianceReport receiptVariances(LocalDate from, LocalDate to, Long supplierId,
                                                  Long productId, Long orderId) {
        if (from != null && to != null && from.isAfter(to)) {
            throw new BusinessRuleException("Begindatum kan niet na einddatum liggen");
        }
        Map<Long, Product> productsById = products.list().stream()
                .filter(product -> product.id() != null)
                .collect(Collectors.toMap(Product::id, Function.identity(), (left, right) -> left));
        Map<Long, Supplier> suppliersById = suppliers.findAll().stream()
                .filter(supplier -> supplier.id() != null)
                .collect(Collectors.toMap(Supplier::id, Function.identity(), (left, right) -> left));

        List<ReceiptVarianceRow> rows = new ArrayList<>();
        for (PurchaseOrder order : orders.findAll()) {
            if (order.status() != PurchaseOrderStatus.ONTVANGEN) continue;
            if (orderId != null && !orderId.equals(order.id())) continue;
            if (supplierId != null && !supplierId.equals(order.supplierId())) continue;
            if (from != null && (order.receivedOn() == null || order.receivedOn().isBefore(from))) continue;
            if (to != null && (order.receivedOn() == null || order.receivedOn().isAfter(to))) continue;

            Supplier supplier = order.supplierId() == null ? null : suppliersById.get(order.supplierId());
            for (PurchaseOrderLine line : order.lines()) {
                if (productId != null && !productId.equals(line.productId())) continue;
                if (line.missing() == 0 && line.damaged() == 0) continue;
                Product product = line.productId() == null ? null : productsById.get(line.productId());
                rows.add(receiptVarianceRow(order, supplier, line, product));
            }
        }
        rows.sort(java.util.Comparator
                .comparing(ReceiptVarianceRow::receivedOn,
                        java.util.Comparator.nullsLast(java.util.Comparator.reverseOrder()))
                .thenComparing(ReceiptVarianceRow::orderId, java.util.Comparator.reverseOrder())
                .thenComparing(ReceiptVarianceRow::productSku,
                        java.util.Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)));
        return new ReceiptVarianceReport(receiptVarianceTotals(rows), List.copyOf(rows));
    }

    /** Compact per-order summary included with the ordinary purchase-order view. */
    public ReceiptVarianceTotals receiptVarianceSummary(PurchaseOrder order) {
        if (order == null || order.status() != PurchaseOrderStatus.ONTVANGEN) {
            return receiptVarianceTotals(List.of());
        }
        List<ReceiptVarianceRow> rows = order.lines().stream()
                .map(line -> receiptVarianceRow(order, null, line, null))
                .toList();
        List<ReceiptVarianceRow> affected = rows.stream()
                .filter(row -> row.missingPieces() > 0 || row.damagedPieces() > 0)
                .toList();
        ReceiptVarianceTotals losses = receiptVarianceTotals(affected);
        return new ReceiptVarianceTotals(
                losses.affectedOrders(), losses.affectedLines(),
                rows.stream().mapToLong(ReceiptVarianceRow::orderedPieces).sum(),
                rows.stream().mapToLong(ReceiptVarianceRow::receivedPieces).sum(),
                losses.missingPieces(),
                rows.stream().mapToLong(ReceiptVarianceRow::overReceivedPieces).sum(),
                losses.damagedPieces(),
                rows.stream().mapToLong(ReceiptVarianceRow::usablePieces).sum(),
                losses.missingValueEur(), losses.damagedValueEur(), losses.totalLossValueEur(),
                losses.unvaluedLossPieces(), losses.valuationComplete());
    }

    private static ReceiptVarianceRow receiptVarianceRow(PurchaseOrder order, Supplier supplier,
                                                         PurchaseOrderLine line, Product product) {
        String productName = product == null
                ? "Product " + line.productId() : product.describe();
        String supplierName = supplier == null || supplier.name() == null || supplier.name().isBlank()
                ? "Onbekende leverancier" : supplier.name();
        return new ReceiptVarianceRow(
                order.id(), order.number(), order.alias(), order.receivedOn(), order.expectedArrival(),
                order.supplierId(), supplierName,
                line.id(), line.productId(),
                product == null ? null : product.sku(), productName,
                line.ordered(), line.received(), line.missing(), line.overReceived(), line.damaged(), line.usable(),
                line.receiptUnitValueEur(), line.missingValueEur(), line.damagedValueEur(),
                line.totalLossValueEur(), line.valuationComplete());
    }

    private static ReceiptVarianceTotals receiptVarianceTotals(List<ReceiptVarianceRow> rows) {
        Set<Long> orders = rows.stream().map(ReceiptVarianceRow::orderId).collect(Collectors.toSet());
        long ordered = 0;
        long received = 0;
        long missing = 0;
        long overReceived = 0;
        long damaged = 0;
        long usable = 0;
        long unvalued = 0;
        BigDecimal missingValue = BigDecimal.ZERO;
        BigDecimal damagedValue = BigDecimal.ZERO;
        for (ReceiptVarianceRow row : rows) {
            ordered += row.orderedPieces();
            received += row.receivedPieces();
            missing += row.missingPieces();
            overReceived += row.overReceivedPieces();
            damaged += row.damagedPieces();
            usable += row.usablePieces();
            if (row.missingValueEur() != null) missingValue = missingValue.add(row.missingValueEur());
            if (row.damagedValueEur() != null) damagedValue = damagedValue.add(row.damagedValueEur());
            if (!row.valuationComplete()) {
                unvalued += (long) row.missingPieces() + row.damagedPieces();
            }
        }
        BigDecimal valuedMissing = Money.money(missingValue);
        BigDecimal valuedDamaged = Money.money(damagedValue);
        return new ReceiptVarianceTotals(orders.size(), rows.size(), ordered, received, missing,
                overReceived, damaged, usable, valuedMissing, valuedDamaged,
                Money.money(valuedMissing.add(valuedDamaged)), unvalued, unvalued == 0);
    }

    /** Changes only the receipt valuation, without touching counts, stock, prices, or rates. */
    @Transactional
    public void setReceiptUnitValue(long orderId, long lineId, BigDecimal unitValueEur) {
        PurchaseOrder order = getForUpdate(orderId);
        if (order.status() != PurchaseOrderStatus.ONTVANGEN) {
            throw new BusinessRuleException("Alleen een ontvangen inkooporder heeft ontvangstwaardes");
        }
        if (unitValueEur != null && unitValueEur.signum() < 0) {
            throw new BusinessRuleException("Waarde per stuk kan niet negatief zijn");
        }
        PurchaseOrderLine selected = order.lines().stream()
                .filter(line -> line.id() != null && line.id() == lineId)
                .findFirst()
                .orElseThrow(() -> new NotFoundException("Inkooporderregel", lineId));
        BigDecimal normalized = unitValueEur == null ? null : Money.unit(unitValueEur);
        if (sameAmount(selected.receiptUnitValueEur(), normalized)) return;

        List<PurchaseOrderLine> lines = order.lines().stream()
                .map(line -> line.id() != null && line.id() == lineId
                        ? new PurchaseOrderLine(line.id(), line.productId(), line.quantity(), line.exwPrice(),
                                line.exwCurrency(), line.extraUnitCost(), line.orderedQuantity(), line.priceBasis(),
                                line.damagedQuantity(), normalized, line.issueNote())
                        : line)
                .toList();
        orders.save(order.withReceipt(order.status(), order.receivedOn(), order.paidTotalEur(),
                order.stockBooked(), order.notes(), lines));
        Map<Long, Product> byId = productNames(order);
        recordActivity(ActivityLogService.ACTION_UPDATED, order, "Ontvangstwaarde bijgewerkt",
                ActivityChangeSet.create()
                        .add("line." + selected.productId() + ".receiptUnitValueEur",
                                "Waarde per stuk · " + describe(byId, selected.productId()),
                                selected.receiptUnitValueEur(), normalized)
                        .build());
    }

    private static boolean sameAmount(BigDecimal left, BigDecimal right) {
        if (left == null || right == null) return left == null && right == null;
        return left.compareTo(right) == 0;
    }

    private static String appendReceiptNote(String notes, LocalDate day, List<String> remarks, String extra) {
        StringBuilder note = new StringBuilder("Ontvangst ").append(day.format(DAY));
        if (remarks.isEmpty()) note.append(": alles volgens bestelling.");
        else note.append(":\n- ").append(String.join("\n- ", remarks));
        if (extra != null && !extra.isBlank()) note.append("\n").append(extra.strip());
        return notes == null || notes.isBlank() ? note.toString() : notes.stripTrailing() + "\n\n" + note;
    }

    private Map<Long, Product> productNames(PurchaseOrder order) {
        Map<Long, Product> byId = new HashMap<>();
        for (Product product : products.list()) byId.put(product.id(), product);
        return byId;
    }

    /**
     * Stable purchase-value basis for a receipt, calculated before received
     * quantities replace ordered quantities.
     */
    private Map<Long, BigDecimal> receiptUnitValues(PurchaseOrder order, Map<Long, Product> byId) {
        Map<Long, LandedCost.Line> calculated = new HashMap<>();
        if (calculator != null) {
            for (LandedCost.Line line : calculator.calculate(order, byId).lines()) {
                calculated.put(line.productId(), line);
            }
        }

        Map<Long, BigDecimal> values = new HashMap<>();
        for (PurchaseOrderLine line : order.lines()) {
            Product product = byId.get(line.productId());
            BigDecimal price = line.exwPrice() != null
                    ? line.exwPrice() : product == null ? null : product.exwPrice();
            if (price == null) {
                values.put(line.productId(), null);
                continue;
            }

            BigDecimal direct = directPurchaseUnitEur(order, line, product, price);
            if (direct == null) {
                /* Live costing uses zero for absent rates so previews remain
                   calculable. A historical receipt must instead stay unknown;
                   zero would be a false loss metric. */
                values.put(line.productId(), null);
                continue;
            }

            LandedCost.Line cost = calculated.get(line.productId());
            if (cost != null && line.quantity() > 0) {
                values.put(line.productId(), Money.unit(Money.divide(
                        cost.goodsEur(), BigDecimal.valueOf(line.quantity()))));
                continue;
            }
            values.put(line.productId(), direct);
        }
        return values;
    }

    /** Pure-test and zero-quantity fallback matching the calculator's goods conversion. */
    private static BigDecimal directPurchaseUnitEur(PurchaseOrder order, PurchaseOrderLine line,
                                                    Product product, BigDecimal price) {
        Currency currency = line.exwCurrency() != null
                ? line.exwCurrency() : product == null ? null : product.exwCurrency();
        if (currency == null) currency = Currency.USD;
        BigDecimal extra = line.extraUnitCost() != null
                ? line.extraUnitCost() : product == null ? null : product.extraUnitCost();
        BigDecimal amount = price.add(Money.nz(extra));
        BigDecimal eur = switch (currency) {
            case EUR -> amount;
            case USD -> positiveRate(order.usdToEurGoods())
                    ? amount.multiply(order.usdToEurGoods()) : null;
            case CNY -> positiveRate(order.cnyToUsd()) && positiveRate(order.usdToEurGoods())
                    ? amount.multiply(order.cnyToUsd()).multiply(order.usdToEurGoods()) : null;
        };
        return eur == null ? null : Money.unit(eur);
    }

    private static boolean positiveRate(BigDecimal rate) {
        return rate != null && rate.signum() > 0;
    }

    private static String describe(Map<Long, Product> byId, Long productId) {
        Product product = byId.get(productId);
        if (product == null) return "product " + productId;
        String sku = product.sku() == null ? "" : product.sku() + " ";
        return sku + product.name();
    }

    @Transactional
    public void delete(long id) {
        PurchaseOrder order = getForUpdate(id);
        if (order.status() == PurchaseOrderStatus.ONTVANGEN || order.isStockBooked()) {
            throw new BusinessRuleException(
                    "Een ontvangen inkooporder kan niet verwijderd worden omdat de voorraad al geboekt is");
        }
        List<String> storageKeys = List.of();
        List<Long> documentIds = List.of();
        if (documents != null && documents.isResolvable()) {
            List<PurchaseDocument> ownedDocuments = documents.get().forOrder(id);
            storageKeys = ownedDocuments.stream()
                    .map(PurchaseDocument::storageKey)
                    .filter(Objects::nonNull)
                    .filter(key -> !key.isBlank())
                    .map(String::strip)
                    .distinct()
                    .toList();
            documentIds = ownedDocuments.stream().map(PurchaseDocument::id)
                    .filter(Objects::nonNull).toList();
            documents.get().deleteForOrder(id);
            documentIds.forEach(documentId -> unlinkLegacyMedia(
                    MediaLegacySourceType.PURCHASE_DOCUMENT, documentId));
        }
        if (payments != null && payments.isResolvable()) payments.get().deleteForOrder(id);
        unlinkMediaTarget(MediaTargetType.PURCHASE_ORDER, id);
        orders.deleteById(id);
        recordActivity(ActivityLogService.ACTION_DELETED, order, "Inkooporder verwijderd");
        if (!storageKeys.isEmpty()) {
            fireDocumentDeleteCleanup(new PurchaseDocumentStorageCleanup.DeleteReady(id, storageKeys));
        }
    }

    /** Forward-only lifecycle; same-state saves remain possible for details. */
    static void requireForwardTransition(PurchaseOrderStatus current,
                                         PurchaseOrderStatus requested) {
        if (current == null || requested == null) {
            throw new BusinessRuleException("Kies een geldige status voor de inkooporder");
        }
        boolean allowed = current == requested
                || current == PurchaseOrderStatus.CONCEPT
                    && (requested == PurchaseOrderStatus.BESTELD
                        || requested == PurchaseOrderStatus.ONDERWEG)
                || current == PurchaseOrderStatus.BESTELD
                    && (requested == PurchaseOrderStatus.ONDERWEG
                        || requested == PurchaseOrderStatus.ONTVANGEN)
                || current == PurchaseOrderStatus.ONDERWEG
                    && requested == PurchaseOrderStatus.ONTVANGEN;
        if (!allowed) {
            throw new BusinessRuleException(
                    "Inkooporder kan niet van " + current.name().toLowerCase()
                            + " naar " + requested.name().toLowerCase() + " gaan");
        }
    }

    private void validateHeader(PurchaseOrder order) {
        if (order.supplierId() == null || order.supplierId() <= 0) {
            throw new BusinessRuleException("Koppel een geldige leverancier aan de inkooporder");
        }
        requireSupplier(order.supplierId());
        if (order.orderDate() == null) {
            throw new BusinessRuleException("Orderdatum is verplicht");
        }
        if (order.containerType() == null) {
            throw new BusinessRuleException("Kies een containertype");
        }
        requirePositive(order.cnyToUsd(), "CNY/USD-koers");
        requirePositive(unifiedUsdToEur(order), "USD/EUR-koers");
        requireNonNegative(order.freightUsd(), "Zeevracht");
        requireNonNegative(order.originCosts(), "Kosten aan de vertrekzijde");
        requireNonNegative(order.destinationCostsEur(), "Kosten aan de aankomstzijde");
        requireNonNegative(order.extraRevenueEur(), "Enrosed kost");
        requirePercentage(order.defaultDutyRatePct(), "Standaard invoerrecht");
        if (order.originCurrency() == null) {
            throw new BusinessRuleException("Kies de munt van de kosten aan de vertrekzijde");
        }
        if (order.allocFreight() == null || order.allocOrigin() == null
                || order.allocDestination() == null || order.allocExtra() == null) {
            throw new BusinessRuleException("Kies voor elke gedeelde kost een verdeelsleutel");
        }
    }

    private void requireSupplier(long supplierId) {
        if (supplierId <= 0 || suppliers.findById(supplierId).isEmpty()) {
            throw new BusinessRuleException("De gekozen leverancier bestaat niet meer");
        }
    }

    /**
     * One rate for every new or changed order. Goods is authoritative for the
     * legacy full-order payload; transport is the fallback for older clients
     * that only populated that column. Persistence and the calculator still
     * retain both fields so untouched historical orders keep their old maths.
     */
    static BigDecimal unifiedUsdToEur(PurchaseOrder order) {
        if (order == null) return null;
        return order.usdToEurGoods() != null
                ? order.usdToEurGoods() : order.usdToEurTransport();
    }

    /**
     * A box opened weeks after the container: broken pieces can still be
     * noted on a received order, and a count that turns out short (or long)
     * can be corrected. Damage only grows - pieces do not unbreak - and once
     * the stock is booked, every difference follows onto the shelf: extra
     * broken ones leave as damaged, a corrected count as a receipt
     * correction.
     */
    private List<String> bookLateDamage(PurchaseOrder current, PurchaseOrder changes) {
        List<String> notes = new ArrayList<>();
        Map<Long, Product> byId = productNames(current);
        for (PurchaseOrderLine incoming : changes.lines()) {
            PurchaseOrderLine stored = current.lines().stream()
                    .filter(line -> incoming.id() != null && incoming.id().equals(line.id()))
                    .findFirst().orElse(null);
            if (stored == null) continue;
            int damagedBefore = stored.damaged();
            int damagedAfter = incoming.damagedQuantity() == null ? damagedBefore : incoming.damagedQuantity();
            int countBefore = stored.quantity();
            int countAfter = incoming.quantity();
            if (damagedAfter == damagedBefore && countAfter == countBefore) continue;
            if (damagedAfter < damagedBefore) {
                throw new BusinessRuleException("Beschadigde stuks kunnen niet dalen bij "
                        + describe(byId, stored.productId()) + "; corrigeer dat via de voorraad van het product");
            }
            if (countAfter < 0) {
                throw new BusinessRuleException("Een ontvangen aantal kan niet negatief zijn");
            }
            if (damagedAfter > countAfter) {
                throw new BusinessRuleException("Meer beschadigd dan ontvangen bij "
                        + describe(byId, stored.productId()));
            }
            List<String> parts = new ArrayList<>();
            if (countAfter != countBefore) {
                parts.add("ontvangen " + countBefore + " → " + countAfter);
            }
            if (damagedAfter != damagedBefore) {
                parts.add((damagedAfter - damagedBefore) + " beschadigd bijgemeld");
            }
            if (current.isStockBooked()) {
                int extraDamaged = damagedAfter - damagedBefore;
                if (extraDamaged > 0) {
                    products.takeOutDamaged(stored.productId(), extraDamaged, current.number(),
                            current.receivingLocationId());
                }
                int countDelta = countAfter - countBefore;
                if (countDelta != 0) {
                    products.receiveStock(stored.productId(), countDelta, current.number() + " correctie",
                            current.receivingLocationId());
                }
                int usableDelta = (countAfter - damagedAfter) - (countBefore - damagedBefore);
                parts.add("voorraad " + (usableDelta > 0 ? "+" : "") + usableDelta);
            }
            notes.add(describe(byId, stored.productId()) + ": " + String.join(", ", parts));
        }
        return notes;
    }

    /** Trimmed, bounded, never blank. */
    static String cleanIssueNote(String note) {
        if (note == null) return null;
        String cleaned = note.strip().replaceAll("[\\p{Cntrl}&&[^\\n]]", "");
        if (cleaned.isEmpty()) return null;
        return cleaned.length() > 500 ? cleaned.substring(0, 500) : cleaned;
    }

    /**
     * What went wrong with this product on earlier containers: every received
     * order where it arrived short or damaged, newest first. The product page
     * shows it and the supplier order prints it as a warning.
     */
    public List<ReceiptIssues.ReceiptIssue> receiptIssuesFor(long productId, Long excludeOrderId) {
        return ReceiptIssues.forProduct(orders.findAll(), productId, excludeOrderId);
    }

    private static String withLateDamageNotes(String notes, List<String> lateDamage) {
        if (lateDamage.isEmpty()) return notes;
        String line = "Ontvangst gecorrigeerd " + LocalDate.now().format(DAY) + ": " + String.join("; ", lateDamage) + ".";
        return appendNote(notes, line);
    }

    private static void requireReceivedLinesUnchanged(PurchaseOrder current,
                                                      PurchaseOrder changes) {
        if (current.lines().size() != changes.lines().size()) {
            throw new BusinessRuleException(
                    "Producten en aantallen van een ontvangen inkooporder kunnen niet meer wijzigen");
        }
        Set<Long> seen = new HashSet<>();
        for (PurchaseOrderLine incoming : changes.lines()) {
            if (incoming == null || incoming.id() == null || !seen.add(incoming.id())) {
                throw new BusinessRuleException(
                        "Producten van een ontvangen inkooporder kunnen niet meer wijzigen");
            }
            PurchaseOrderLine stored = current.lines().stream()
                    .filter(line -> incoming.id().equals(line.id()))
                    .findFirst()
                    .orElse(null);
            /* The received counts themselves may still be corrected (a box
               short, glass broken) - that runs through the reconciliation
               and books the stock difference; the product set is fixed. */
            if (stored == null || !Objects.equals(stored.productId(), incoming.productId())) {
                throw new BusinessRuleException(
                        "Producten van een ontvangen inkooporder kunnen niet meer wijzigen");
            }
        }
    }

    private static void requirePositive(BigDecimal value, String label) {
        if (value == null || value.signum() <= 0) {
            throw new BusinessRuleException(label + " moet groter zijn dan nul");
        }
    }

    private static void requireNonNegative(BigDecimal value, String label) {
        if (value != null && value.signum() < 0) {
            throw new BusinessRuleException(label + " kan niet negatief zijn");
        }
    }

    private static void requirePercentage(BigDecimal value, String label) {
        if (value == null || value.signum() < 0 || value.compareTo(BigDecimal.valueOf(100)) > 0) {
            throw new BusinessRuleException(label + " moet tussen 0 en 100% liggen");
        }
    }

    /**
     * Writes the calculated cost prices onto the products. From that moment
     * the sales side computes with them.
     */
    @Transactional
    public LandedCost applyToProducts(long id) {
        PurchaseOrder order = get(id);
        LandedCost result = calculate(order);
        for (LandedCost.Line line : result.lines()) {
            products.applyLandedCost(line.productId(), line.landedUnitEur(), order.number());
        }
        LOG.infof("Kostprijzen uit %s toegepast op %d product(en)", order.number(), result.lines().size());
        /* Into the diary: applying rewrites what the whole catalogue counts with. */
        String line = "Kostprijzen toegepast " + LocalDate.now().format(DAY) + ": "
                + result.lines().size() + " product(en) bijgewerkt in de catalogus.";
        orders.save(order.withReceipt(order.status(), order.receivedOn(), order.paidTotalEur(),
                order.stockBooked(), appendNote(order.notes(), line), order.lines()));
        recordActivity(ActivityLogService.ACTION_COSTS_APPLIED, order,
                "Kostprijzen op " + result.lines().size() + " product(en) toegepast");
        return result;
    }

    private ActorRef currentActor() {
        return actor != null && actor.isResolvable() ? actor.get().current() : ActorRef.SYSTEM;
    }

    /** Audit joins the business transaction: no order change may outlive a failed audit write. */
    private void recordActivity(String action, PurchaseOrder order, String summary) {
        if (activity == null || !activity.isResolvable()) return;
        activity.get().record(action, ActivityLogService.ENTITY_PURCHASE_ORDER,
                order.id() == null ? null : order.id().toString(), order.number(), summary);
    }

    private void recordActivity(String action, PurchaseOrder order, String summary,
                                List<ActivityChangeDto> changes) {
        if (activity == null || !activity.isResolvable()) return; // pure unit tests instantiate this service directly
        activity.get().record(action, ActivityLogService.ENTITY_PURCHASE_ORDER,
                order.id() == null ? null : order.id().toString(), order.number(), summary, changes);
    }

    private static List<ActivityChangeDto> purchaseChanges(
            PurchaseOrder before, PurchaseOrder after, Map<Long, Product> products) {
        ActivityChangeSet changes = ActivityChangeSet.create()
                .add("alias", "Naam", before.alias(), after.alias())
                .add("supplierId", "Leverancier", before.supplierId(), after.supplierId())
                .add("orderDate", "Orderdatum", before.orderDate(), after.orderDate())
                .add("status", "Status", statusLabel(before.status()), statusLabel(after.status()))
                .add("containerType", "Container", before.containerType(), after.containerType())
                .add("cnyToUsd", "Koers CNY/USD", before.cnyToUsd(), after.cnyToUsd())
                .add("usdToEur", "Koers USD/EUR", before.usdToEurGoods(), after.usdToEurGoods())
                .add("freightUsd", "Zeevracht", before.freightUsd(), after.freightUsd())
                .add("originCosts", "Kosten oorsprong", before.originCosts(), after.originCosts())
                .add("destinationCostsEur", "Kosten bestemming",
                        before.destinationCostsEur(), after.destinationCostsEur())
                .add("defaultDutyRatePct", "Invoerrecht",
                        before.defaultDutyRatePct(), after.defaultDutyRatePct())
                .add("extraRevenueEur", "Extra opbrengst", before.extraRevenueEur(), after.extraRevenueEur())
                .add("departurePort", "Vertrekhaven", before.departurePort(), after.departurePort())
                .add("destinationPort", "Bestemmingshaven", before.destinationPort(), after.destinationPort())
                .add("receivingLocationId", "Ontvangstlocatie",
                        before.receivingLocationId(), after.receivingLocationId())
                .add("groupVariants", "Varianten groeperen", before.groupsVariants(), after.groupsVariants())
                .add("expectedArrival", "Verwachte aankomst", before.expectedArrival(), after.expectedArrival())
                .add("receivedOn", "Ontvangen op", before.receivedOn(), after.receivedOn())
                .add("paymentTerms", "Betaalvoorwaarden", before.paymentTerms(), after.paymentTerms())
                .add("trackingReference", "Tracking", before.trackingReference(), after.trackingReference())
                .add("lineCount", "Aantal productregels", before.lines().size(), after.lines().size())
                .add("pieceCount", "Totaal aantal stuks", totalPieces(before), totalPieces(after))
                .privateValue("notes", "Notities", before.notes(), after.notes());

        Map<Long, PurchaseOrderLine> beforeLines = before.lines().stream()
                .filter(line -> line.productId() != null)
                .collect(Collectors.toMap(PurchaseOrderLine::productId, Function.identity(), (left, right) -> right));
        Map<Long, PurchaseOrderLine> afterLines = after.lines().stream()
                .filter(line -> line.productId() != null)
                .collect(Collectors.toMap(PurchaseOrderLine::productId, Function.identity(), (left, right) -> right));
        Set<Long> productIds = new java.util.TreeSet<>();
        productIds.addAll(beforeLines.keySet());
        productIds.addAll(afterLines.keySet());
        for (Long productId : productIds) {
            PurchaseOrderLine oldLine = beforeLines.get(productId);
            PurchaseOrderLine newLine = afterLines.get(productId);
            Product product = products.get(productId);
            String label = product == null ? "Product " + productId : product.describe();
            changes.add("line." + productId + ".quantity", "Aantal · " + label,
                    oldLine == null ? null : oldLine.quantity(), newLine == null ? null : newLine.quantity());
            changes.add("line." + productId + ".damagedQuantity", "Beschadigd · " + label,
                    oldLine == null ? null : oldLine.damaged(), newLine == null ? null : newLine.damaged());
            changes.add("line." + productId + ".receiptUnitValueEur", "Ontvangstwaarde/stuk · " + label,
                    oldLine == null ? null : oldLine.receiptUnitValueEur(),
                    newLine == null ? null : newLine.receiptUnitValueEur());
            changes.add("line." + productId + ".exwPrice", "EXW-prijs · " + label,
                    oldLine == null ? null : oldLine.exwPrice(), newLine == null ? null : newLine.exwPrice());
        }
        return changes.build();
    }

    private static int totalPieces(PurchaseOrder order) {
        return order.lines().stream().mapToInt(PurchaseOrderLine::quantity).sum();
    }

    /** The observer sends only after this transaction commits successfully. */
    private void firePush(PurchasePushNotifier.Ready ready) {
        if (purchasePush != null) purchasePush.fire(ready);
    }

    /** Deletes the bytes only after the document row and audit entry committed. */
    private void fireDocumentDeleteCleanup(PurchaseDocumentStorageCleanup.DeleteReady ready) {
        if (documentDeleteCleanup != null) documentDeleteCleanup.fire(ready);
    }

    /** Compensates an uploaded external blob if the document transaction rolls back. */
    private void fireUploadCleanup(PurchaseDocumentStorageCleanup.UploadReady ready) {
        if (documentUploadCleanup != null) documentUploadCleanup.fire(ready);
    }

    private void unlinkLegacyMedia(MediaLegacySourceType sourceType, long sourceId) {
        if (mediaRegistry != null && mediaRegistry.isResolvable()) {
            mediaRegistry.get().unlinkLegacy(sourceType, sourceId);
        }
    }

    private void unlinkMediaTarget(MediaTargetType targetType, long targetId) {
        if (mediaRegistry != null && mediaRegistry.isResolvable()) {
            mediaRegistry.get().unlinkTarget(targetType, targetId);
        }
    }

    private static String statusLabel(PurchaseOrderStatus status) {
        if (status == null) return "onbekend";
        return switch (status) {
            case CONCEPT -> "concept";
            case BESTELD -> "besteld";
            case ONDERWEG -> "onderweg";
            case ONTVANGEN -> "ontvangen";
        };
    }

    private String nextNumber() {
        int year = LocalDate.now().getYear();
        String prefix = "PO-" + year + "-";
        int highest = orders.findAll().stream()
                .map(PurchaseOrder::number)
                .filter(number -> number != null && number.startsWith(prefix))
                .map(number -> number.substring(prefix.length()))
                .filter(suffix -> suffix.matches("\\d+"))
                .mapToInt(Integer::parseInt)
                .max()
                .orElse(0);
        return prefix + String.format("%03d", highest + 1);
    }
}
