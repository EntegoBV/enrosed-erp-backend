package be.enrosed.sourcing.application;

import be.enrosed.catalog.application.ProductService;
import be.enrosed.catalog.domain.Product;
import be.enrosed.shared.BusinessRuleException;
import be.enrosed.shared.NotFoundException;
import be.enrosed.sourcing.application.port.out.SourcingRepositories;
import be.enrosed.sourcing.domain.*;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import be.enrosed.catalog.domain.Carton;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import be.enrosed.sourcing.domain.PurchasePayment;
import be.enrosed.sourcing.domain.PurchaseDocument;
import be.enrosed.shared.Currency;
import be.enrosed.shared.Money;
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

    @jakarta.inject.Inject
    be.enrosed.push.WebPushNotifier phones;

    private static final Logger LOG = Logger.getLogger(PurchaseOrderService.class);

    private final SourcingRepositories.PurchaseOrders orders;
    private final SourcingRepositories.Suppliers suppliers;
    private final ProductService products;
    /* Payments and who records them; pure unit tests run without. */
    @jakarta.inject.Inject
    jakarta.enterprise.inject.Instance<be.enrosed.sourcing.application.port.out.SourcingRepositories.Payments> payments;
    @jakarta.inject.Inject
    jakarta.enterprise.inject.Instance<be.enrosed.shared.security.CurrentActor> actor;
    @jakarta.inject.Inject
    jakarta.enterprise.inject.Instance<be.enrosed.catalog.application.StockService> locationNames;
    @jakarta.inject.Inject
    jakarta.enterprise.inject.Instance<be.enrosed.sourcing.application.port.out.SourcingRepositories.Documents> documents;
    @jakarta.inject.Inject
    jakarta.enterprise.inject.Instance<be.enrosed.catalog.application.port.out.PhotoStorage> photoStorage;
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
        requireSupplier(supplierId);
        requirePositive(cnyToUsd, "CNY/USD-koers");
        requirePositive(usdToEur, "USD/EUR-koers");
        requirePercentage(defaultDutyRatePct, "Standaard invoerrecht");

        PurchaseOrder draft = new PurchaseOrder(
                null, nextNumber(), null, supplierId, LocalDate.now(),
                PurchaseOrderStatus.CONCEPT, ContainerType.FORTY_HQ,
                cnyToUsd, usdToEur, usdToEur,
                BigDecimal.ZERO, BigDecimal.ZERO, be.enrosed.shared.Currency.USD, BigDecimal.ZERO,
                defaultDutyRatePct, new BigDecimal("2000"),
                Allocation.CBM, Allocation.CBM, Allocation.CBM, Allocation.PIECES,
                "Ningbo", "Rotterdam", "", List.of());
        PurchaseOrder created = orders.save(draft);
        if (phones != null) phones.notifyAll("purchase", "Nieuwe inkooporder " + created.number(),
                "Calculatie aangemaakt", "/purchasing/" + created.id());
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
        return orders.save(new PurchaseOrder(
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
                        .toList()));
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
            if (line.quantity() < 0) {
                throw new BusinessRuleException("Een besteld of ontvangen aantal kan niet negatief zijn");
            }
            requireNonNegative(line.exwPrice(), "EXW-prijs");
            requireNonNegative(line.extraUnitCost(), "Extra kost per stuk");

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
                    line.exwPrice(), line.exwCurrency(), line.extraUnitCost(),
                    orderedQuantityFor(current, changes, line, requested), line.priceBasis(),
                    line.damagedQuantity()));
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
                withLateDamageNotes(changes.notes(), lateDamage), lines));

        return new UpdateResult(saved, warnings);
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
                actor != null && actor.isResolvable() ? actor.get().name() : "systeem", java.time.Instant.now(), to));

        orders.save(order.withReceipt(order.status(), order.receivedOn(), order.paidTotalEur(), order.stockBooked(),
                appendNote(order.notes(), paymentNoteLine(payment)), order.lines()));
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
        get(orderId);
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
        return documents.get().save(new PurchaseDocument(null, orderId, kind == null ? PurchaseDocument.Kind.OTHER : kind,
                label == null || label.isBlank() ? null : label.strip(), name, type, bytes.length, stored.storageKey(),
                paymentId, actor != null && actor.isResolvable() ? actor.get().name() : "systeem", java.time.Instant.now()));
    }

    /** The pencil next to an uploaded file: the title stays editable afterwards. */
    @Transactional
    public PurchaseDocument renameDocument(long orderId, long documentId, String label) {
        get(orderId);
        String cleaned = label == null || label.isBlank() ? null : label.strip();
        return documents.get().rename(orderId, documentId, cleaned)
                .orElseThrow(() -> new NotFoundException("Document", documentId));
    }

    @Transactional
    public void deleteDocument(long orderId, long documentId) {
        PurchaseDocument document = document(orderId, documentId);
        documents.get().delete(orderId, documentId);
        try { photoStorage.get().delete(document.storageKey()); } catch (RuntimeException ignored) { /* the row is gone; the blob is sweepable */ }
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

    /** One line of a receipt: what arrived, and how much of that was broken. */
    public record ReceivedLine(Long productId, Integer received, Integer damaged) {}

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
        LocalDate day = receipt.receivedOn() != null ? receipt.receivedOn() : LocalDate.now();

        Map<Long, ReceivedLine> counted = new HashMap<>();
        for (ReceivedLine line : receipt.lines() == null ? List.<ReceivedLine>of() : receipt.lines()) {
            if (line != null && line.productId() != null) counted.put(line.productId(), line);
        }
        List<PurchaseOrderLine> lines = new ArrayList<>();
        List<String> remarks = new ArrayList<>();
        Map<Long, Product> byId = productNames(order);
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
            int ordered = line.orderedQuantity() != null ? line.orderedQuantity() : line.quantity();
            if (received != ordered || damaged > 0) {
                StringBuilder remark = new StringBuilder(describe(byId, line.productId()))
                        .append(": besteld ").append(ordered).append(", ontvangen ").append(received);
                if (damaged > 0) remark.append(", ").append(damaged).append(" beschadigd");
                remarks.add(remark.toString());
            }
            lines.add(new PurchaseOrderLine(line.id(), line.productId(), received, line.exwPrice(),
                    line.exwCurrency(), line.extraUnitCost(), ordered, line.priceBasis(), damaged));
        }

        String notes = appendReceiptNote(order.notes(), day, remarks, receipt.note());
        PurchaseOrder received = orders.save(order.withReceipt(PurchaseOrderStatus.ONTVANGEN, day,
                receipt.paidTotalEur(), false, notes, lines));
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
        return orders.save(order.withReceipt(order.status(), order.receivedOn(), order.paidTotalEur(), true,
                notes, order.lines()));
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

    private static String describe(Map<Long, Product> byId, Long productId) {
        Product product = byId.get(productId);
        if (product == null) return "product " + productId;
        String sku = product.sku() == null ? "" : product.sku() + " ";
        return sku + product.name();
    }

    @Transactional
    public void delete(long id) {
        PurchaseOrder order = getForUpdate(id);
        if (order.status() == PurchaseOrderStatus.ONTVANGEN) {
            throw new BusinessRuleException(
                    "Een ontvangen inkooporder kan niet verwijderd worden omdat de voorraad al geboekt is");
        }
        orders.deleteById(id);
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
        return result;
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
