package be.enrosed.inventory.application;

import be.enrosed.inventory.adapter.out.persistence.StockClosingArticleEntity;
import be.enrosed.inventory.adapter.out.persistence.StockClosingLayerEntity;
import be.enrosed.inventory.adapter.out.persistence.StockClosingSeparateEntity;
import be.enrosed.inventory.adapter.out.persistence.StockClosingWriteDownEntity;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * Values the closing stock of every product: FIFO per receipt lot.
 *
 * FIFO is a cost-flow assumption. Every piece that left, whatever its cause,
 * is taken to have come from the oldest layer, so what lies there on the
 * closing date is the newest receipts. The closing quantity is therefore
 * filled from the newest layer down: the lots of the period, then the layers
 * the previous final closing froze, then the documented opening values. No
 * sale is tracked to a lot. The one exception is in the lot itself: pieces
 * registered as damaged on the container line are not part of its capacity.
 *
 * Goods of third parties and partner pieces leave the quantity first and are
 * never in an own layer. What cannot be filled has no value and blocks the
 * closing until an opening value covers it.
 */
public final class FifoValuer {

    public static final String BLOCK_OWN = "EIGEN";
    public static final String BLOCK_INVOICED = "GEFACTUREERD";

    public static final String SOURCE_LOT = "PARTIJ";
    public static final String SOURCE_PREVIOUS = "VORIG";
    public static final String SOURCE_OPENING = "BEGINWAARDE";

    public static final String KIND_PARTNER = "PARTNER";
    public static final String KIND_INVOICED = "GEFACTUREERD";
    public static final String KIND_THIRD_PARTY = "DERDEN";
    public static final String KIND_TRANSIT = "ONDERWEG";
    public static final String KIND_OLDER = "OUDER";

    public static final String CHOICE_OUT = "UIT";
    public static final String CHOICE_STAYS = "BLIJFT";
    public static final String CHOICE_GONE = "AL_WEG";
    public static final String AUTOMATIC_REASON = "Al verwerkt bij de bewegingen van stap 2";

    public static final String STATUS_OK = "OK";
    public static final String STATUS_UNVALUED = "ZONDER_WAARDE";
    public static final String STATUS_NEGATIVE = "NEGATIEF";

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final BigDecimal ZERO = new BigDecimal("0.00");

    private FifoValuer() {}

    /* ---------------------------------------------------------------- shapes */

    /** A product as the closing rows freeze it. */
    public record Article(long productId, String sku, String name, String categoryName, String unitKey,
                          String salesUnit, Integer piecesPerUnit, boolean demo, boolean active) {}

    /** The rolled quantity of a product at one location. */
    public record LocationQuantity(long locationId, int anchorQuantity, int rollDelta, int closingQuantity) {}

    /** One product on one received container, with the unit value of its lot cost. */
    public record Lot(long purchaseOrderId, String orderNumber, String displayName, LocalDate receivedOn,
                      long productId, int capacity, BigDecimal unitValueEur, BigDecimal unitGoodsEur,
                      BigDecimal unitTransportEur, BigDecimal unitLogisticsEur, BigDecimal unitSeparateEur,
                      BigDecimal unitEstimatedEur) {}

    /** A decision as far as a row prints it. */
    public record Decided(Long decisionId, Boolean flag, String choice, String reason, String byName, Instant at) {}

    /**
     * A product on a partner container.
     *
     * @param shipped         the pieces that left on its advance or settlement documents by the cut-off
     * @param decidedQuantity the user's own figure instead of capacity minus shipped; null without one
     * @param container       the decision whether the container is included; null while undecided
     */
    public record PartnerLot(Lot lot, String partnerName, int shipped, Integer decidedQuantity, Decided container) {}

    public record ThirdParty(long decisionId, long productId, int quantity, String owner, String reason,
                             String byName, Instant at) {}

    /**
     * An invoice of the period that was not afgepunt by the cut-off.
     *
     * @param decision          the INVOICED decision; null while undecided, which counts as "blijft"
     * @param automaticProducts the products whose sale was already taken out by the movements of step 2
     */
    public record Invoice(long salesOrderId, String number, LocalDate orderDate, String customerName,
                          Map<Long, Integer> quantities, Decided decision, Set<Long> automaticProducts) {
        public Invoice {
            automaticProducts = automaticProducts == null ? Set.of() : automaticProducts;
        }
    }

    /** A waardevermindering; a null quantity takes every own piece the lower ids left untouched. */
    public record WriteDown(long decisionId, long productId, Integer quantity, BigDecimal marketUnitEur,
                            String reasonCode, String reason, String byName, Instant at) {}

    /** An active opening value. */
    public record Opening(long id, long productId, int quantity, BigDecimal unitValueEur, LocalDate asOfDate,
                          String source) {}

    /**
     * @param previousClosingDate null in a first closing
     * @param products            every product that still exists
     * @param quantities          per product its rolled quantity per location
     * @param periodLots          the own lots that no earlier closing of the chain held
     * @param carried             the EIGEN layers of the previous final closing
     * @param consumedOpeningIds  the opening values an earlier closing of the chain used
     * @param closingYears        the year of each closing a carried layer can come from
     * @param previousWriteDowns  last year's waardevermindering per product
     */
    public record Input(LocalDate closingDate, LocalDate previousClosingDate, Long previousClosingId,
                        Map<Long, Article> products, Map<Long, List<LocationQuantity>> quantities,
                        List<ThirdParty> thirdParties, List<PartnerLot> partnerLots, List<Lot> periodLots,
                        List<StockClosingLayerEntity> carried, List<Opening> openings, Set<Long> consumedOpeningIds,
                        List<Invoice> invoices, List<WriteDown> writeDowns, Map<Long, Integer> closingYears,
                        Map<Long, BigDecimal> previousWriteDowns) {
        public Input {
            products = products == null ? Map.of() : products;
            quantities = quantities == null ? Map.of() : quantities;
            thirdParties = thirdParties == null ? List.of() : thirdParties;
            partnerLots = partnerLots == null ? List.of() : partnerLots;
            periodLots = periodLots == null ? List.of() : periodLots;
            carried = carried == null ? List.of() : carried;
            openings = openings == null ? List.of() : openings;
            consumedOpeningIds = consumedOpeningIds == null ? Set.of() : consumedOpeningIds;
            invoices = invoices == null ? List.of() : invoices;
            writeDowns = writeDowns == null ? List.of() : writeDowns;
            closingYears = closingYears == null ? Map.of() : closingYears;
            previousWriteDowns = previousWriteDowns == null ? Map.of() : previousWriteDowns;
        }
    }

    /** The value of a product spread over its locations in proportion to quantity: information, not a valuation. */
    public record LocationValue(long productId, long locationId, BigDecimal costValueEur, BigDecimal goodsEur,
                                BigDecimal transportEur, BigDecimal logisticsEur, BigDecimal separateEur,
                                BigDecimal openingEur, BigDecimal estimatedEur, BigDecimal writeDownEur,
                                BigDecimal ownValueEur) {}

    /**
     * @param separates           the rows of the blocks partner, gefactureerd and derden
     * @param negativeLocations   product and location with a closing quantity below zero
     * @param thirdPartyExcess    products with more goods of third parties than were counted
     * @param writeDownExcess     products whose explicit waardeverminderingen exceed the own quantity
     * @param withoutEffect       the waardeverminderingen that lower nothing
     * @param unusedOpenings      opening values dated on or before the previous closing date that nothing consumed
     * @param lateContainers      containers received on or before the previous closing date that it did not hold
     */
    public record Result(List<StockClosingArticleEntity> articles, List<StockClosingLayerEntity> layers,
                         List<LocationValue> locations, List<StockClosingSeparateEntity> separates,
                         List<StockClosingWriteDownEntity> writeDowns, List<StockRoll.Key> negativeLocations,
                         Set<Long> thirdPartyExcess, Set<Long> writeDownExcess, List<WriteDown> withoutEffect,
                         List<Opening> unusedOpenings, Set<Long> lateContainers) {

        public StockClosingArticleEntity article(long productId) {
            return articles.stream().filter(article -> article.productId == productId).findFirst().orElse(null);
        }

        public List<StockClosingLayerEntity> layers(long productId) {
            return layers.stream().filter(layer -> layer.productId == productId).toList();
        }
    }

    /* ----------------------------------------------------------------- value */

    public static Result value(Input input) {
        List<StockClosingArticleEntity> articles = new ArrayList<>();
        List<StockClosingLayerEntity> layers = new ArrayList<>();
        List<LocationValue> locations = new ArrayList<>();
        List<StockClosingSeparateEntity> separates = new ArrayList<>();
        List<StockClosingWriteDownEntity> writeDowns = new ArrayList<>();
        List<StockRoll.Key> negative = new ArrayList<>();
        Set<Long> thirdPartyExcess = new LinkedHashSet<>();
        Set<Long> writeDownExcess = new LinkedHashSet<>();
        List<WriteDown> withoutEffect = new ArrayList<>();
        List<Opening> unusedOpenings = new ArrayList<>();
        Set<Long> late = new LinkedHashSet<>();

        Set<Long> valued = new LinkedHashSet<>();
        input.products().keySet().stream().filter(input.quantities()::containsKey).forEach(valued::add);
        input.thirdParties().stream().map(ThirdParty::productId).filter(input.products()::containsKey).forEach(valued::add);

        List<PartnerLot> partnerOrder = input.partnerLots().stream()
                .sorted(Comparator.comparing((PartnerLot partner) -> partner.lot().receivedOn(),
                                Comparator.nullsLast(Comparator.<LocalDate>naturalOrder())).reversed()
                        .thenComparing(partner -> partner.lot().purchaseOrderId(), Comparator.reverseOrder()))
                .toList();
        List<Invoice> invoiceOrder = input.invoices().stream()
                .sorted(Comparator.comparing(Invoice::orderDate, Comparator.nullsFirst(Comparator.naturalOrder()))
                        .thenComparingLong(Invoice::salesOrderId)).toList();

        for (long productId : valued) {
            Article article = input.products().get(productId);
            List<LocationQuantity> held = input.quantities().getOrDefault(productId, List.of());
            int counted = 0, rolled = 0, total = 0;
            boolean below = false;
            for (LocationQuantity quantity : held) {
                counted += quantity.anchorQuantity();
                rolled += quantity.rollDelta();
                total += Math.max(0, quantity.closingQuantity());
                if (quantity.closingQuantity() < 0) {
                    below = true;
                    negative.add(new StockRoll.Key(productId, quantity.locationId()));
                }
            }

            /* Goods of third parties leave first and are never valued. */
            int thirdQuantity = 0;
            for (ThirdParty third : input.thirdParties()) {
                if (third.productId() != productId) continue;
                thirdQuantity += third.quantity();
                separates.add(thirdPartyRow(third, article));
            }
            if (thirdQuantity > total) thirdPartyExcess.add(productId);
            int left = Math.max(0, total - thirdQuantity);

            /* Then the partner containers, newest first; they are not in the own layers. */
            int partnerQuantity = 0;
            for (PartnerLot partner : partnerOrder) {
                if (partner.lot().productId() != productId) continue;
                int proposed = Math.max(0, partner.lot().capacity() - partner.shipped());
                int quantity = Math.min(left, Math.max(0, partner.decidedQuantity() == null ? proposed : partner.decidedQuantity()));
                left -= quantity;
                partnerQuantity += quantity;
                separates.add(partnerRow(partner, article.sku(), article.name(), proposed, quantity));
            }
            int pool = left;

            List<StockClosingLayerEntity> own = fill(input, productId, pool, unusedOpenings, late);
            int filled = own.stream().mapToInt(layer -> layer.quantity).sum();
            int unvalued = pool - filled;

            /* An invoice whose pieces lay there and are taken out leaves from the oldest filled layers. */
            List<StockClosingLayerEntity> carved = new ArrayList<>();
            int invoicedOut = 0;
            for (Invoice invoice : invoiceOrder) {
                Integer invoiced = invoice.quantities().get(productId);
                if (invoiced == null) continue;
                boolean automatic = invoice.automaticProducts().contains(productId);
                String choice = automatic ? CHOICE_GONE : invoice.decision() == null ? null : invoice.decision().choice();
                int need = CHOICE_OUT.equals(choice) ? invoiced : 0;
                int taken = 0;
                BigDecimal value = ZERO, estimated = ZERO;
                for (int index = own.size() - 1; index >= 0 && need > 0; index--) {
                    StockClosingLayerEntity layer = own.get(index);
                    int pieces = Math.min(need, layer.quantity);
                    if (pieces == 0) continue;
                    layer.quantity -= pieces;
                    need -= pieces;
                    taken += pieces;
                    StockClosingLayerEntity out = copy(layer);
                    out.block = BLOCK_INVOICED;
                    out.salesOrderId = invoice.salesOrderId();
                    out.capacity = null;
                    out.quantity = pieces;
                    worth(out);
                    carved.add(out);
                    value = value.add(out.valueEur);
                    estimated = estimated.add(out.estimatedEur);
                }
                invoicedOut += taken;
                separates.add(invoicedRow(invoice, article, invoiced, choice, automatic, taken, value, estimated));
            }
            own.removeIf(layer -> layer.quantity == 0);
            own.forEach(FifoValuer::worth);
            int ownQuantity = pool - unvalued - invoicedOut;

            /* Lower market value: the dearest pieces first, never above cost, the acquisition value stays on the row. */
            List<WriteDown> decisions = input.writeDowns().stream().filter(decision -> decision.productId() == productId)
                    .sorted(Comparator.comparingLong(WriteDown::decisionId)).toList();
            int explicit = decisions.stream().filter(decision -> decision.quantity() != null)
                    .mapToInt(WriteDown::quantity).sum();
            if (explicit > ownQuantity) writeDownExcess.add(productId);
            List<StockClosingLayerEntity> dearest = own.stream()
                    .sorted(Comparator.comparing((StockClosingLayerEntity layer) -> layer.unitValueEur).reversed()
                            .thenComparing(layer -> layer.position)).toList();
            for (WriteDown decision : decisions) {
                int need = decision.quantity() == null ? Integer.MAX_VALUE : decision.quantity();
                BigDecimal market = decision.marketUnitEur() == null ? BigDecimal.ZERO : decision.marketUnitEur();
                BigDecimal lowered = ZERO;
                for (StockClosingLayerEntity layer : dearest) {
                    int pieces = Math.min(need, layer.quantity - layer.writeDownQuantity);
                    if (pieces <= 0) continue;
                    BigDecimal amount = money(layer.unitValueEur.subtract(market).max(BigDecimal.ZERO)
                            .multiply(BigDecimal.valueOf(pieces)));
                    layer.writeDownQuantity += pieces;
                    layer.writeDownEur = layer.writeDownEur.add(amount);
                    need -= pieces;
                    lowered = lowered.add(amount);
                    writeDowns.add(writeDownRow(decision, article, layer, pieces, market, amount, input.closingYears()));
                }
                if (lowered.signum() == 0) withoutEffect.add(decision);
            }

            StockClosingArticleEntity row = new StockClosingArticleEntity();
            row.productId = productId;
            row.sku = article.sku();
            row.productName = article.name();
            row.categoryName = article.categoryName();
            row.unitKey = article.unitKey();
            row.salesUnit = article.salesUnit();
            row.piecesPerUnit = article.piecesPerUnit();
            row.demo = article.demo();
            row.active = article.active();
            row.countedQuantity = counted;
            row.rollDelta = rolled;
            row.closingQuantity = total;
            row.thirdPartyQuantity = thirdQuantity;
            row.partnerQuantity = partnerQuantity;
            row.invoicedOutQuantity = invoicedOut;
            row.ownQuantity = ownQuantity;
            row.unvaluedQuantity = unvalued;
            row.costValueEur = ZERO;
            row.goodsEur = ZERO;
            row.transportEur = ZERO;
            row.logisticsEur = ZERO;
            row.separateEur = ZERO;
            row.openingEur = ZERO;
            row.estimatedEur = ZERO;
            row.writeDownEur = ZERO;
            for (StockClosingLayerEntity layer : own) {
                row.costValueEur = row.costValueEur.add(layer.valueEur);
                row.estimatedEur = row.estimatedEur.add(layer.estimatedEur);
                row.writeDownEur = row.writeDownEur.add(layer.writeDownEur);
                if (SOURCE_OPENING.equals(layer.originSource)) {
                    row.openingEur = row.openingEur.add(layer.valueEur);
                    continue;
                }
                /* The rounding difference of a layer goes to its goods, so the components add up to its value. */
                BigDecimal transport = part(layer, layer.unitTransportEur);
                BigDecimal logistics = part(layer, layer.unitLogisticsEur);
                BigDecimal separate = part(layer, layer.unitSeparateEur);
                row.transportEur = row.transportEur.add(transport);
                row.logisticsEur = row.logisticsEur.add(logistics);
                row.separateEur = row.separateEur.add(separate);
                row.goodsEur = row.goodsEur.add(layer.valueEur.subtract(transport).subtract(logistics).subtract(separate));
            }
            row.averageUnitEur = ownQuantity == 0 ? null
                    : row.costValueEur.divide(BigDecimal.valueOf(ownQuantity), 4, RoundingMode.HALF_UP);
            row.ownValueEur = row.costValueEur.subtract(row.writeDownEur);
            row.previousWriteDownEur = input.previousWriteDowns().get(productId);
            row.status = below ? STATUS_NEGATIVE : unvalued > 0 ? STATUS_UNVALUED : STATUS_OK;
            articles.add(row);
            layers.addAll(own);
            layers.addAll(carved);
            locations.addAll(spread(row, held));
        }

        /* A partner lot of a product that lies nowhere is still listed, with nothing in it. */
        for (PartnerLot partner : partnerOrder) {
            if (valued.contains(partner.lot().productId())) continue;
            Article article = input.products().get(partner.lot().productId());
            separates.add(partnerRow(partner, article == null ? null : article.sku(), article == null ? null : article.name(),
                    Math.max(0, partner.lot().capacity() - partner.shipped()), 0));
        }
        return new Result(articles, layers, locations, separates, writeDowns, negative, thirdPartyExcess,
                writeDownExcess, withoutEffect, unusedOpenings, late);
    }

    /** The own layers of one product, newest first, filled from the top until the pool is used up. */
    private static List<StockClosingLayerEntity> fill(Input input, long productId, int pool,
                                                      List<Opening> unusedOpenings, Set<Long> late) {
        LocalDate previous = input.previousClosingDate();
        Comparator<Lot> newestFirst = Comparator.comparing(Lot::receivedOn, Comparator.nullsLast(Comparator.<LocalDate>naturalOrder()))
                .reversed().thenComparing(Lot::purchaseOrderId, Comparator.reverseOrder());
        List<StockClosingLayerEntity> offered = new ArrayList<>();
        List<Lot> lateLots = new ArrayList<>();
        for (Lot lot : input.periodLots().stream().filter(lot -> lot.productId() == productId).sorted(newestFirst).toList()) {
            /* Received on or before the previous closing date and not in it: valued at its own cost, among the carried layers. */
            if (previous != null && lot.receivedOn() != null && !lot.receivedOn().isAfter(previous)) {
                lateLots.add(lot);
                late.add(lot.purchaseOrderId());
            } else {
                offered.add(lotLayer(lot, productId));
            }
        }
        List<StockClosingLayerEntity> carried = new ArrayList<>();
        input.carried().stream().filter(layer -> Objects.equals(layer.productId, productId))
                .sorted(Comparator.comparing((StockClosingLayerEntity layer) -> layer.position)
                        .thenComparing(layer -> layer.id, Comparator.nullsLast(Comparator.naturalOrder())))
                .forEach(layer -> carried.add(carriedLayer(layer, input.previousClosingId())));
        for (Lot lot : lateLots) {
            int place = carried.size();
            for (int index = 0; index < carried.size(); index++) {
                StockClosingLayerEntity layer = carried.get(index);
                if (!SOURCE_PREVIOUS.equals(layer.source)) continue;
                if (SOURCE_OPENING.equals(layer.originSource) || layer.receivedOn == null
                        || layer.receivedOn.isBefore(lot.receivedOn())) {
                    place = index;
                    break;
                }
            }
            carried.add(place, lotLayer(lot, productId));
        }
        offered.addAll(carried);

        /* Opening values are always the oldest layers; one from before the previous closing date did its work then. */
        input.openings().stream().filter(opening -> opening.productId() == productId)
                .sorted(Comparator.comparing(Opening::asOfDate).reversed().thenComparing(Opening::id, Comparator.reverseOrder()))
                .forEach(opening -> {
                    if (opening.asOfDate().isAfter(input.closingDate())) return;
                    if (previous != null && !opening.asOfDate().isAfter(previous)) {
                        if (!input.consumedOpeningIds().contains(opening.id())) unusedOpenings.add(opening);
                        return;
                    }
                    offered.add(openingLayer(opening));
                });

        List<StockClosingLayerEntity> taken = new ArrayList<>();
        int left = pool;
        for (StockClosingLayerEntity layer : offered) {
            int quantity = Math.min(left, layer.capacity);
            if (quantity <= 0) continue;
            left -= quantity;
            layer.quantity = quantity;
            layer.position = taken.size() + 1;
            taken.add(layer);
        }
        return taken;
    }

    /* -------------------------------------------------------------- locations */

    /**
     * The own quantity of a product per location: its own pieces spread in
     * proportion to the closing quantity of each location, in whole pieces by
     * largest remainder, a tie going to the lower location id. The closing
     * quantity of a location holds every piece that lies there; only this
     * part of it is own stock. A negative location counts as nothing.
     */
    public static Map<Long, Integer> ownQuantityPerLocation(int ownQuantity, Map<Long, Integer> closingQuantities) {
        Map<Long, Integer> weights = new TreeMap<>();
        closingQuantities.forEach((locationId, quantity) -> weights.put(locationId, Math.max(0, quantity == null ? 0 : quantity)));
        long total = weights.values().stream().mapToLong(Integer::longValue).sum();
        Map<Long, Integer> shares = new LinkedHashMap<>();
        if (total == 0 || ownQuantity <= 0) {
            closingQuantities.keySet().forEach(locationId -> shares.put(locationId, 0));
            return shares;
        }
        Map<Long, Long> remainders = new TreeMap<>();
        Map<Long, Integer> floors = new TreeMap<>();
        int given = 0;
        for (Map.Entry<Long, Integer> weight : weights.entrySet()) {
            long product = (long) ownQuantity * weight.getValue();
            floors.put(weight.getKey(), (int) (product / total));
            remainders.put(weight.getKey(), product % total);
            given += (int) (product / total);
        }
        List<Long> order = remainders.keySet().stream()
                .sorted(Comparator.comparing((Long locationId) -> remainders.get(locationId)).reversed()
                        .thenComparing(Comparator.naturalOrder())).toList();
        for (int index = 0; index < ownQuantity - given; index++) {
            floors.merge(order.get(index % order.size()), 1, Integer::sum);
        }
        closingQuantities.keySet().forEach(locationId -> shares.put(locationId, floors.get(locationId)));
        return shares;
    }

    private static List<LocationValue> spread(StockClosingArticleEntity article, List<LocationQuantity> held) {
        List<BigDecimal> weights = held.stream()
                .map(quantity -> BigDecimal.valueOf(Math.max(0, quantity.closingQuantity()))).toList();
        List<BigDecimal> cost = allocate(article.costValueEur, weights);
        List<BigDecimal> goods = allocate(article.goodsEur, weights);
        List<BigDecimal> transport = allocate(article.transportEur, weights);
        List<BigDecimal> logistics = allocate(article.logisticsEur, weights);
        List<BigDecimal> separate = allocate(article.separateEur, weights);
        List<BigDecimal> opening = allocate(article.openingEur, weights);
        List<BigDecimal> estimated = allocate(article.estimatedEur, weights);
        List<BigDecimal> writeDown = allocate(article.writeDownEur, weights);
        List<LocationValue> values = new ArrayList<>();
        for (int index = 0; index < held.size(); index++) {
            values.add(new LocationValue(article.productId, held.get(index).locationId(), cost.get(index), goods.get(index),
                    transport.get(index), logistics.get(index), separate.get(index), opening.get(index),
                    estimated.get(index), writeDown.get(index), cost.get(index).subtract(writeDown.get(index))));
        }
        return values;
    }

    /**
     * Shares an amount to the cent over weights by largest remainder, in the
     * given order; every cent is assigned once. All weights zero means equal
     * parts.
     */
    public static List<BigDecimal> allocate(BigDecimal amount, List<BigDecimal> weights) {
        List<BigDecimal> shares = new ArrayList<>();
        if (weights.isEmpty()) return shares;
        List<BigDecimal> used = weights.stream().map(weight -> weight == null || weight.signum() < 0 ? BigDecimal.ZERO : weight).toList();
        BigDecimal total = used.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        if (total.signum() == 0) {
            used = weights.stream().map(weight -> BigDecimal.ONE).toList();
            total = BigDecimal.valueOf(used.size());
        }
        long cents = money(amount).movePointRight(2).longValueExact();
        long given = 0;
        long[] floors = new long[used.size()];
        BigDecimal[] remainders = new BigDecimal[used.size()];
        for (int index = 0; index < used.size(); index++) {
            BigDecimal exact = BigDecimal.valueOf(cents).multiply(used.get(index)).divide(total, 12, RoundingMode.HALF_UP);
            floors[index] = exact.setScale(0, RoundingMode.FLOOR).longValueExact();
            remainders[index] = exact.subtract(BigDecimal.valueOf(floors[index]));
            given += floors[index];
        }
        List<Integer> order = new ArrayList<>();
        for (int index = 0; index < used.size(); index++) order.add(index);
        order.sort(Comparator.comparing((Integer index) -> remainders[index]).reversed().thenComparing(Comparator.naturalOrder()));
        for (int extra = 0; extra < cents - given; extra++) floors[order.get(extra % order.size())]++;
        for (long floor : floors) shares.add(BigDecimal.valueOf(floor, 2));
        return shares;
    }

    /* ------------------------------------------------------------------ rows */

    private static StockClosingLayerEntity lotLayer(Lot lot, long productId) {
        StockClosingLayerEntity layer = blank(productId);
        layer.source = SOURCE_LOT;
        layer.originSource = SOURCE_LOT;
        layer.purchaseOrderId = lot.purchaseOrderId();
        layer.orderNumber = lot.orderNumber();
        layer.displayName = lot.displayName();
        layer.receivedOn = lot.receivedOn();
        layer.capacity = lot.capacity();
        layer.unitValueEur = lot.unitValueEur();
        layer.unitGoodsEur = lot.unitGoodsEur();
        layer.unitTransportEur = lot.unitTransportEur();
        layer.unitLogisticsEur = lot.unitLogisticsEur();
        layer.unitSeparateEur = lot.unitSeparateEur();
        layer.unitEstimatedEur = lot.unitEstimatedEur() == null ? new BigDecimal("0.0000") : lot.unitEstimatedEur();
        return layer;
    }

    /** A layer of the previous final closing: capped at its frozen quantity, at its frozen units. */
    private static StockClosingLayerEntity carriedLayer(StockClosingLayerEntity frozen, Long previousClosingId) {
        StockClosingLayerEntity layer = blank(frozen.productId);
        layer.source = SOURCE_PREVIOUS;
        layer.originSource = frozen.originSource == null ? frozen.source : frozen.originSource;
        layer.originClosingId = frozen.originClosingId != null ? frozen.originClosingId
                : frozen.closingId != null ? frozen.closingId : previousClosingId;
        layer.purchaseOrderId = frozen.purchaseOrderId;
        layer.orderNumber = frozen.orderNumber;
        layer.displayName = frozen.displayName;
        layer.receivedOn = frozen.receivedOn;
        layer.openingLayerId = frozen.openingLayerId;
        layer.openingSource = frozen.openingSource;
        layer.capacity = frozen.quantity == null ? 0 : frozen.quantity;
        layer.unitValueEur = frozen.unitValueEur;
        layer.unitGoodsEur = frozen.unitGoodsEur;
        layer.unitTransportEur = frozen.unitTransportEur;
        layer.unitLogisticsEur = frozen.unitLogisticsEur;
        layer.unitSeparateEur = frozen.unitSeparateEur;
        layer.unitEstimatedEur = frozen.unitEstimatedEur == null ? new BigDecimal("0.0000") : frozen.unitEstimatedEur;
        return layer;
    }

    private static StockClosingLayerEntity openingLayer(Opening opening) {
        StockClosingLayerEntity layer = blank(opening.productId());
        layer.source = SOURCE_OPENING;
        layer.originSource = SOURCE_OPENING;
        layer.receivedOn = opening.asOfDate();
        layer.openingLayerId = opening.id();
        layer.openingSource = opening.source();
        layer.capacity = opening.quantity();
        layer.unitValueEur = opening.unitValueEur();
        layer.unitEstimatedEur = new BigDecimal("0.0000");
        return layer;
    }

    private static StockClosingLayerEntity blank(long productId) {
        StockClosingLayerEntity layer = new StockClosingLayerEntity();
        layer.productId = productId;
        layer.block = BLOCK_OWN;
        layer.quantity = 0;
        layer.writeDownQuantity = 0;
        layer.writeDownEur = ZERO;
        return layer;
    }

    private static StockClosingLayerEntity copy(StockClosingLayerEntity source) {
        StockClosingLayerEntity layer = blank(source.productId);
        layer.position = source.position;
        layer.source = source.source;
        layer.originSource = source.originSource;
        layer.originClosingId = source.originClosingId;
        layer.purchaseOrderId = source.purchaseOrderId;
        layer.orderNumber = source.orderNumber;
        layer.displayName = source.displayName;
        layer.receivedOn = source.receivedOn;
        layer.openingLayerId = source.openingLayerId;
        layer.openingSource = source.openingSource;
        layer.unitValueEur = source.unitValueEur;
        layer.unitGoodsEur = source.unitGoodsEur;
        layer.unitTransportEur = source.unitTransportEur;
        layer.unitLogisticsEur = source.unitLogisticsEur;
        layer.unitSeparateEur = source.unitSeparateEur;
        layer.unitEstimatedEur = source.unitEstimatedEur;
        return layer;
    }

    private static void worth(StockClosingLayerEntity layer) {
        layer.valueEur = money(layer.unitValueEur.multiply(BigDecimal.valueOf(layer.quantity)));
        layer.estimatedEur = money(layer.unitEstimatedEur.multiply(BigDecimal.valueOf(layer.quantity)));
    }

    private static BigDecimal part(StockClosingLayerEntity layer, BigDecimal unit) {
        return unit == null ? ZERO : money(unit.multiply(BigDecimal.valueOf(layer.quantity)));
    }

    /** How a layer is named on a waardevermindering and in the files. */
    public static String layerLabel(StockClosingLayerEntity layer, Map<Long, Integer> closingYears) {
        if (SOURCE_OPENING.equals(layer.originSource)) return cut("Beginwaarde: " + layer.openingSource, 255);
        String container = layer.displayName != null ? layer.displayName : layer.orderNumber;
        if (SOURCE_PREVIOUS.equals(layer.source)) {
            Integer year = layer.originClosingId == null ? null : closingYears.get(layer.originClosingId);
            return cut("Inventaris" + (year == null ? "" : " " + year) + ": " + container, 255);
        }
        return cut(container + (layer.receivedOn == null ? "" : " · ontvangen " + DAY.format(layer.receivedOn)), 255);
    }

    private static StockClosingWriteDownEntity writeDownRow(WriteDown decision, Article article, StockClosingLayerEntity layer,
                                                            int pieces, BigDecimal market, BigDecimal amount,
                                                            Map<Long, Integer> closingYears) {
        StockClosingWriteDownEntity row = new StockClosingWriteDownEntity();
        row.decisionId = decision.decisionId();
        row.productId = article.productId();
        row.sku = article.sku();
        row.productName = article.name();
        row.layerPosition = layer.position;
        row.layerLabel = layerLabel(layer, closingYears);
        row.quantity = pieces;
        row.layerUnitEur = layer.unitValueEur;
        row.marketUnitEur = market.setScale(4, RoundingMode.HALF_UP);
        row.amountEur = amount;
        row.reasonCode = decision.reasonCode();
        row.reason = decision.reason();
        row.decidedByName = decision.byName();
        row.decidedAt = decision.at();
        return row;
    }

    private static StockClosingSeparateEntity thirdPartyRow(ThirdParty third, Article article) {
        StockClosingSeparateEntity row = new StockClosingSeparateEntity();
        row.kind = KIND_THIRD_PARTY;
        row.counterparty = third.owner();
        row.productId = article.productId();
        row.sku = article.sku();
        row.productName = article.name();
        row.quantity = third.quantity();
        row.reason = third.reason();
        row.automatic = false;
        row.decisionId = third.decisionId();
        row.decidedByName = third.byName();
        row.decidedAt = third.at();
        return row;
    }

    private static StockClosingSeparateEntity partnerRow(PartnerLot partner, String sku, String name, int proposed, int quantity) {
        Lot lot = partner.lot();
        StockClosingSeparateEntity row = new StockClosingSeparateEntity();
        row.kind = KIND_PARTNER;
        row.purchaseOrderId = lot.purchaseOrderId();
        row.documentNumber = lot.orderNumber();
        row.documentName = lot.displayName();
        row.counterparty = partner.partnerName();
        row.productId = lot.productId();
        row.sku = sku;
        row.productName = name;
        row.proposedQuantity = proposed;
        row.quantity = quantity;
        row.unitValueEur = lot.unitValueEur();
        row.valueEur = money(lot.unitValueEur().multiply(BigDecimal.valueOf(quantity)));
        row.estimatedEur = money((lot.unitEstimatedEur() == null ? BigDecimal.ZERO : lot.unitEstimatedEur())
                .multiply(BigDecimal.valueOf(quantity)));
        row.receivedOn = lot.receivedOn();
        row.automatic = false;
        Decided decision = partner.container();
        if (decision != null) {
            row.included = decision.flag();
            row.reason = decision.reason();
            row.decisionId = decision.decisionId();
            row.decidedByName = decision.byName();
            row.decidedAt = decision.at();
        }
        return row;
    }

    private static StockClosingSeparateEntity invoicedRow(Invoice invoice, Article article, int invoiced, String choice,
                                                          boolean automatic, int taken, BigDecimal value, BigDecimal estimated) {
        StockClosingSeparateEntity row = new StockClosingSeparateEntity();
        row.kind = KIND_INVOICED;
        row.salesOrderId = invoice.salesOrderId();
        row.documentNumber = invoice.number();
        row.documentDate = invoice.orderDate();
        row.counterparty = invoice.customerName();
        row.productId = article.productId();
        row.sku = article.sku();
        row.productName = article.name();
        row.quantity = invoiced;
        row.choice = choice;
        row.automatic = automatic;
        if (automatic) {
            row.reason = AUTOMATIC_REASON;
        } else if (invoice.decision() != null) {
            row.reason = invoice.decision().reason();
            row.decisionId = invoice.decision().decisionId();
            row.decidedByName = invoice.decision().byName();
            row.decidedAt = invoice.decision().at();
        }
        /* Only pieces that were taken out of the own stock have a value here. */
        if (taken > 0) {
            row.unitValueEur = value.divide(BigDecimal.valueOf(taken), 4, RoundingMode.HALF_UP);
            row.valueEur = value;
            row.estimatedEur = estimated;
        }
        return row;
    }

    static BigDecimal money(BigDecimal value) {
        return value == null ? ZERO : value.setScale(2, RoundingMode.HALF_UP);
    }

    private static String cut(String value, int length) {
        return value == null || value.length() <= length ? value : value.substring(0, length);
    }
}
