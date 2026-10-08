package be.enrosed.sourcing.application;

import be.enrosed.catalog.domain.Product;
import be.enrosed.catalog.domain.StockMovement;
import be.enrosed.shared.Currency;
import be.enrosed.shared.Money;
import be.enrosed.sourcing.domain.LandedCost;
import be.enrosed.sourcing.domain.LotCost;
import be.enrosed.sourcing.domain.LotCost.Accrual;
import be.enrosed.sourcing.domain.LotCost.BilledBasis;
import be.enrosed.sourcing.domain.LotCost.CreditTreatment;
import be.enrosed.sourcing.domain.LotCost.CreditUse;
import be.enrosed.sourcing.domain.LotCost.LotStatus;
import be.enrosed.sourcing.domain.LotCost.PaymentUse;
import be.enrosed.sourcing.domain.LotCost.QuantityBasis;
import be.enrosed.sourcing.domain.LotCost.State;
import be.enrosed.sourcing.domain.LotCost.StreamCost;
import be.enrosed.sourcing.domain.PurchaseOrder;
import be.enrosed.sourcing.domain.PurchaseOrderLine;
import be.enrosed.sourcing.domain.PurchasePayment;
import be.enrosed.sourcing.domain.PurchasePayment.Payee;
import be.enrosed.sourcing.domain.PurchaseReconciliation;
import be.enrosed.sourcing.domain.PurchaseSupplierCredit;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * The acquisition unit value of the receipt lots of one container.
 *
 * Pure calculation: it reads the input and writes nothing. The road:
 *
 *   1. PAYMENTS     each at its historical euro: a prepayment keeps the
 *                   bank euro of its day, a payment after the purchase
 *                   counts at the container rate and the difference is an
 *                   exchange result, listed and never valued
 *   2. STREAMS      per payee what was paid plus what is still owed; the
 *                   open part is an estimate until an invoice confirms it
 *   3. CREDITS      a price credit lowers the goods, a credit for missing
 *                   or broken pieces stays outside the value
 *   4. ALLOCATION   every amount over the products with a key that is
 *                   stored next to the share
 *   5. UNIT VALUE   goods over the billed pieces, container costs over
 *                   the received pieces, each component rounded on its own
 *
 * The Enrosed kost is no payee stream and "Bijkomende kosten" are never
 * taken, so neither can reach a unit value.
 */
public class LotCostCalculator {
    private static final BigDecimal ZERO = Money.money(BigDecimal.ZERO);
    private static final BigDecimal ZERO_UNIT = Money.unit(BigDecimal.ZERO);
    private static final List<Payee> VALUED = List.of(Payee.SUPPLIER, Payee.LOGISTICS, Payee.SEPARATE);

    private static final String BY_RECEIVED_VALUE = "ontvangen waarde";
    private static final String BY_RECEIVED_PIECES = "ontvangen stuks";
    private static final String BY_BILLED_PIECES = "aangerekende stuks";
    private static final String BY_EQUAL_PARTS = "gelijke delen";

    /**
     * The euro value of a payment at the rate its Afspraak was built with,
     * unrounded: the transport rate for Douane & transport when the
     * container has one, the goods rate for everything else. A supplier
     * payment of a CIF container takes the goods rate as a whole, because
     * one payment cannot be split into goods and freight.
     */
    public static BigDecimal atContainerRate(PurchaseOrder order, Payee payee, BigDecimal amount, Currency currency) {
        Currency money = currency == null ? Currency.EUR : currency;
        if (usesTransportRate(order, payee, money)) {
            return money == Currency.USD
                    ? amount.multiply(order.usdToEurTransport())
                    : amount.multiply(Money.nz(order.cnyToUsd())).multiply(order.usdToEurTransport());
        }
        return PurchaseOrderService.euroAtOrderRate(order, amount, money);
    }

    /**
     * Copies of the payments that carry their value at the container
     * rates, only to measure what is still owed: a stream paid in full in
     * its own currency then has nothing open, whatever the bank charged.
     */
    public static List<PurchasePayment> normalised(PurchaseOrder order, List<PurchasePayment> payments) {
        if (payments == null) return List.of();
        return payments.stream().filter(java.util.Objects::nonNull).map(payment -> {
            boolean foreign = payment.currency() != null && payment.currency() != Currency.EUR;
            BigDecimal value = foreign && payment.amount() != null
                    ? Money.money(atContainerRate(order, payment.payee(), payment.amount(), payment.currency()))
                    : payment.amountEur();
            return new PurchasePayment(payment.id(), payment.orderId(), payment.paidOn(), payment.amount(),
                    payment.currency(), value, payment.label(), payment.actor(), payment.recordedAt(),
                    payment.payee(), payment.settles(), payment.instalmentDue());
        }).toList();
    }

    public LotCost.Container calculate(LotCost.Input input) {
        PurchaseOrder order = input.order();
        LotCost.Options options = input.options();
        List<String> notes = new ArrayList<>();
        List<Row> rows = rows(input, notes);

        /* ---- 1. Payments at their historical euro value --------------- */
        Map<Payee, BigDecimal> paid = new EnumMap<>(Payee.class);
        for (Payee payee : Payee.values()) paid.put(payee, ZERO);
        BigDecimal exchangeDifference = ZERO;
        boolean paymentWithoutEuro = false;
        List<PaymentUse> paymentUses = new ArrayList<>();
        for (PurchasePayment payment : input.payments()) {
            Payee payee = payment.payee();
            Currency money = payment.currency() == null ? Currency.EUR : payment.currency();
            boolean foreign = money != Currency.EUR;
            /* Bought already: the bank euro of a later payment holds an exchange result. */
            boolean afterPurchase = foreign && options.rateCutoff() != null && payment.paidOn() != null
                    && payment.paidOn().isAfter(options.rateCutoff());
            boolean afterClosing = options.costCutoff() != null && payment.paidOn() != null
                    && (payee == Payee.LOGISTICS || payee == Payee.SEPARATE)
                    && payment.paidOn().isAfter(options.costCutoff());
            BigDecimal stored = payment.amountEur() == null ? null : Money.money(payment.amountEur());
            if (stored == null) paymentWithoutEuro = true;
            BigDecimal counted = afterPurchase
                    ? Money.money(atContainerRate(order, payee, Money.nz(payment.amount()), money))
                    : stored == null ? ZERO : stored;
            if (stored != null) exchangeDifference = exchangeDifference.add(stored.subtract(counted));
            boolean inValue = payee != Payee.OTHER && !afterClosing;
            if (inValue || payee == Payee.OTHER) paid.merge(payee, counted, BigDecimal::add);
            String rule = payee == Payee.OTHER ? PaymentUse.RULE_OTHER
                    : afterClosing ? PaymentUse.RULE_AFTER_CLOSING
                    : !foreign ? PaymentUse.RULE_EURO
                    : !afterPurchase ? PaymentUse.RULE_STORED
                    : usesTransportRate(order, payee, money) ? PaymentUse.RULE_TRANSPORT_RATE
                    : PaymentUse.RULE_GOODS_RATE;
            paymentUses.add(new PaymentUse(payment.id(), payment.paidOn(), payee, payment.label(),
                    payment.amount(), money, stored, counted, inValue, rule));
        }

        /* ---- 2. What is owed per payee --------------------------------- */
        Map<Payee, StreamCost> streams = new EnumMap<>(Payee.class);
        for (Payee payee : VALUED) streams.put(payee, stream(payee, input, paid.get(payee)));
        StreamCost supplier = streams.get(Payee.SUPPLIER);
        StreamCost logistics = streams.get(Payee.LOGISTICS);
        StreamCost separate = streams.get(Payee.SEPARATE);
        boolean settledLower = PurchaseReconciliation.Status.SETTLED_LOWER.name().equals(supplier.status());

        /* ---- 3. Supplier credits --------------------------------------- */
        List<Credit> credits = new ArrayList<>();
        BigDecimal priceCredit = ZERO;
        BigDecimal lossCredit = ZERO;
        BigDecimal defaultLossCredit = ZERO;
        for (PurchaseSupplierCredit credit : input.credits()) {
            Credit use = new Credit(credit);
            /* At the order's rate, not the bank's euro: no exchange difference leaks in. */
            use.eur = credit.currency() == null || credit.currency() == Currency.EUR
                    ? Money.money(credit.amount())
                    : Money.money(PurchaseOrderService.euroAtOrderRate(order, Money.nz(credit.amount()), credit.currency()));
            CreditTreatment chosen = credit.id() == null ? null : options.creditTreatments().get(credit.id());
            use.decided = chosen != null;
            use.treatment = chosen != null ? chosen : defaultTreatment(credit.reason());
            if (use.treatment == CreditTreatment.VERLAAGT) priceCredit = priceCredit.add(use.eur);
            if (use.treatment == CreditTreatment.BUITEN) {
                lossCredit = lossCredit.add(use.eur);
                if (!use.decided) defaultLossCredit = defaultLossCredit.add(use.eur);
            }
            /* No default for "Andere": the ERP cannot know what the credit is. */
            if (use.treatment == null) use.requiredBy = CreditUse.NO_KIND;
            /* After a settlement nothing is open, so a deducted credit already sits in the lower payment. */
            else if (!use.decided && use.treatment == CreditTreatment.VERLAAGT && settledLower) {
                use.requiredBy = CreditUse.SETTLED_LOWER;
            }
            credits.add(use);
        }

        /* ---- 4. Split and allocation ----------------------------------- */
        BigDecimal supplierFreight = Money.money(input.supplierFreightEur());
        boolean cif = supplierFreight.signum() > 0;
        BigDecimal goodsPart = supplier.includedEur();
        BigDecimal transportPart = ZERO;
        BigDecimal goodsEstimated = supplier.estimatedEur();
        BigDecimal transportEstimated = ZERO;
        if (cif) {
            List<BigDecimal> split = List.of(Money.money(input.supplierEur()).subtract(supplierFreight), supplierFreight);
            List<BigDecimal> parts = allocate(supplier.includedEur(), split);
            List<BigDecimal> estimated = allocate(supplier.estimatedEur(), split);
            goodsPart = parts.get(0);
            transportPart = parts.get(1);
            goodsEstimated = estimated.get(0);
            transportEstimated = estimated.get(1);
            /* The weights live on the container only: written down, so a frozen closing can redo its split. */
            notes.add("Transport via leverancier: het bedrag van de leverancier is gesplitst volgens de Afspraak van € "
                    + Money.money(input.supplierEur()).toPlainString() + ", waarvan € " + supplierFreight.toPlainString()
                    + " transport.");
        }

        Key goodsKey = key(rows, List.of(
                new Candidate(null, row -> row.goodsValue),
                new Candidate(BY_BILLED_PIECES, row -> BigDecimal.valueOf(row.billed))));
        List<Candidate> afterCalculation = List.of(
                new Candidate(BY_RECEIVED_VALUE, row -> row.nonDdp ? row.receivedValue : BigDecimal.ZERO),
                new Candidate(BY_RECEIVED_PIECES, row -> row.nonDdp ? BigDecimal.valueOf(row.received) : BigDecimal.ZERO),
                new Candidate(BY_RECEIVED_PIECES, row -> BigDecimal.valueOf(row.received)));
        Key transportKey = key(rows, with(new Candidate(null, row -> row.origin.add(row.freight)), afterCalculation));
        Key logisticsKey = key(rows, with(new Candidate(null, row -> cif ? row.duty.add(row.destination)
                : row.duty.add(row.destination).add(row.origin).add(row.freight)), afterCalculation));
        /* Kept apart on the container, the inspection still belongs to the goods it was done for. */
        Key separateKey = order.separateInPiecePrice()
                ? key(rows, List.of(
                        new Candidate(null, row -> row.separate),
                        new Candidate(BY_RECEIVED_VALUE, row -> row.receivedValue),
                        new Candidate(BY_RECEIVED_PIECES, row -> BigDecimal.valueOf(row.received))))
                : key(rows, List.of(
                        new Candidate(null, row -> row.receivedValue),
                        new Candidate(BY_RECEIVED_PIECES, row -> BigDecimal.valueOf(row.received))));
        /* A product without carton volume gets no sea freight in the calculation, and the duty of the Afspraak
           is then calculated on its goods alone: said out loud, since it lowers an amount that may be estimated. */
        boolean carried = rows.stream().anyMatch(row -> row.origin.add(row.freight).signum() > 0);
        for (Row row : rows) {
            if (carried && row.nonDdp && row.received > 0 && row.origin.add(row.freight).signum() == 0) {
                notes.add(row.product.nameWithColour() + " krijgt in de berekening geen aandeel in vertrekkosten en zeevracht"
                        + " (sleutel 0, bijvoorbeeld zonder doosafmetingen): de invoerrechten in de Afspraak zijn voor dit"
                        + " product op de goederen alleen berekend. Kijk de doosafmetingen na.");
            }
        }
        noteFallback(notes, "Goederen", goodsKey, goodsPart.add(priceCredit));
        if (cif) noteFallback(notes, "Transport via leverancier", transportKey, transportPart);
        noteFallback(notes, "Douane & transport", logisticsKey, logistics.includedEur());
        noteFallback(notes, "Inspectie & andere kosten", separateKey, separate.includedEur());
        if (rows.isEmpty() && supplier.includedEur().add(logistics.includedEur()).add(separate.includedEur()).signum() != 0) {
            notes.add("Geen productregels: de kosten van de container zijn aan geen partij toegewezen.");
        }

        List<BigDecimal> goods = allocate(goodsPart, goodsKey.weights);
        List<BigDecimal> goodsEst = allocate(goodsEstimated, goodsKey.weights);
        List<BigDecimal> priceCredits = allocate(priceCredit, goodsKey.weights);
        List<BigDecimal> transport = allocate(transportPart, transportKey.weights);
        List<BigDecimal> transportEst = allocate(transportEstimated, transportKey.weights);
        List<BigDecimal> logisticsShares = allocate(logistics.includedEur(), logisticsKey.weights);
        List<BigDecimal> logisticsEst = allocate(logistics.estimatedEur(), logisticsKey.weights);
        List<BigDecimal> separateShares = allocate(separate.includedEur(), separateKey.weights);
        List<BigDecimal> separateEst = allocate(separate.estimatedEur(), separateKey.weights);

        /* ---- 5. Divisors, unit value, capacity ------------------------- */
        Map<Long, Integer> laterLost = laterLost(input);
        List<LotCost.Lot> lots = new ArrayList<>();
        BigDecimal acquisition = ZERO;
        BigDecimal missingAndDamaged = ZERO;
        for (int i = 0; i < rows.size(); i++) {
            Row row = rows.get(i);
            /* The supplier's amount covers the billed pieces; a piece that never came keeps its own goods cost. */
            int goodsDivisor = Math.max(row.billed, row.received);
            int costDivisor = row.received;
            BigDecimal containerEstimated = transportEst.get(i).add(logisticsEst.get(i)).add(separateEst.get(i));
            /* A price credit lowers the goods of its lot to nothing at most: the rest is no price reduction of this lot. */
            BigDecimal netGoods = goods.get(i).subtract(priceCredits.get(i)).max(ZERO);
            if (priceCredits.get(i).compareTo(goods.get(i)) > 0) {
                notes.add("Prijstegoed hoger dan de goederen van " + row.product.nameWithColour() + ": € "
                        + priceCredits.get(i).subtract(goods.get(i)).toPlainString()
                        + " is niet van de aanschafwaarde afgetrokken.");
            }
            BigDecimal unitGoods = unit(netGoods, goodsDivisor);
            BigDecimal unitTransport = unit(transport.get(i), costDivisor);
            BigDecimal unitLogistics = unit(logisticsShares.get(i), costDivisor);
            BigDecimal unitSeparate = unit(separateShares.get(i), costDivisor);
            /* The sum of the rounded components, so the breakdown always adds up. */
            BigDecimal unitValue = unitGoods.add(unitTransport).add(unitLogistics).add(unitSeparate);
            BigDecimal unitEstimated = unit(goodsEst.get(i), goodsDivisor).add(unit(containerEstimated, costDivisor));
            BigDecimal lotCost = netGoods.add(transport.get(i))
                    .add(logisticsShares.get(i)).add(separateShares.get(i));
            int lost = laterLost.getOrDefault(row.productId, 0);
            BigDecimal missingCost = Money.money(unitGoods.multiply(BigDecimal.valueOf(goodsDivisor - row.received)));
            BigDecimal damagedCost = Money.money(unitValue.multiply(BigDecimal.valueOf(row.damaged + lost)));
            /* For information: the container costs of the lot as the calculation splits them. */
            List<BigDecimal> calc = allocate(logisticsShares.get(i).add(transport.get(i)),
                    rounded(List.of(row.origin, row.freight, row.duty, row.destination)));
            LotStatus status = row.received == 0 ? LotStatus.GEEN_ONTVANGST
                    : !row.priced ? LotStatus.GEEN_PRIJS
                    : row.received > row.ordered ? LotStatus.MEER_ONTVANGEN
                    : row.received < row.ordered ? LotStatus.TEKORT : LotStatus.OK;
            lots.add(new LotCost.Lot(row.productId, row.product.sku(), row.product.nameWithColour(),
                    row.ordered, row.received, row.damaged, lost, row.billed, goodsDivisor, costDivisor,
                    Math.max(0, row.received - row.damaged), row.unitPrice(),
                    goodsKey.weights.get(i), cif ? transportKey.weights.get(i) : null,
                    logisticsKey.weights.get(i), separateKey.weights.get(i),
                    goods.get(i), priceCredits.get(i), transport.get(i), logisticsShares.get(i),
                    separateShares.get(i), lotCost, goodsEst.get(i).add(containerEstimated),
                    unitGoods, unitTransport, unitLogistics, unitSeparate, unitValue, unitEstimated,
                    calc.get(0), calc.get(1), calc.get(2), calc.get(3),
                    row.dutyRatePct == null ? null : Money.unit(row.dutyRatePct),
                    missingCost, damagedCost, status));
            acquisition = acquisition.add(lotCost);
            missingAndDamaged = missingAndDamaged.add(missingCost).add(damagedCost);
        }

        /* More credit kept outside the value than the lost pieces cost: some of it is a discount on pieces that
           lie there. Decided and default amounts together, so one decision cannot clear it: every shortage and
           damage credit needs the user's own answer. */
        boolean aboveLoss = lossCredit.compareTo(missingAndDamaged) > 0;
        List<CreditUse> creditUses = new ArrayList<>();
        for (Credit use : credits) {
            if (aboveLoss && !use.decided && use.treatment == CreditTreatment.BUITEN) use.requiredBy = CreditUse.ABOVE_LOSS;
            creditUses.add(new CreditUse(use.credit.id(), use.credit.notedOn(), use.credit.reason(),
                    use.credit.amount(), use.credit.currency() == null ? Currency.EUR : use.credit.currency(),
                    use.eur, use.treatment, use.decided, use.requiredBy != null, use.requiredBy));
        }

        return new LotCost.Container(order.id(), options.quantityBasis(), options.billedBasis(), options.rateCutoff(),
                cif, order.groupsVariants(), order.separateInPiecePrice(),
                name(order.allocOrigin()), name(order.allocFreight()), name(order.allocDestination()),
                name(order.separateAllocation()),
                List.of(supplier, logistics, separate), goodsPart, transportPart, paid.get(Payee.OTHER),
                priceCredit, lossCredit, defaultLossCredit, exchangeDifference,
                Money.money(input.brec() == null || input.brec().totals() == null ? null
                        : input.brec().totals().extraRevenueEur()),
                acquisition, supplier.estimatedEur().add(logistics.estimatedEur()).add(separate.estimatedEur()),
                missingAndDamaged, settledLower, rows.stream().anyMatch(row -> row.received < row.ordered),
                paymentWithoutEuro, List.copyOf(paymentUses), List.copyOf(creditUses), List.copyOf(lots),
                List.copyOf(notes));
    }

    /** Paid plus what is still owed; the open Afspraak counts until an accrual states the amount. */
    private StreamCost stream(Payee payee, LotCost.Input input, BigDecimal paid) {
        PurchaseReconciliation.Stream source = input.streams().stream()
                .filter(stream -> stream != null && stream.payee() == payee).findFirst().orElse(null);
        BigDecimal planned = source == null ? ZERO : Money.money(source.plannedEur());
        BigDecimal normalisedPaid = source == null ? ZERO : Money.money(source.paidEur());
        BigDecimal remaining = source == null ? ZERO : Money.money(source.remainingEur());
        String status = source == null || source.status() == null
                ? PurchaseReconciliation.Status.NOT_APPLICABLE.name() : source.status().name();
        /* The lower of the two: zero after a settlement, and zero when the total paid reaches the
           Afspraak even though a deposit above its term left a gap on a later term. */
        BigDecimal open = remaining.min(planned.subtract(normalisedPaid).max(ZERO)).max(ZERO);
        BigDecimal overpaid = normalisedPaid.subtract(planned).max(ZERO);
        Accrual accrual = input.options().accruals().get(payee);
        boolean applied;
        boolean stale = false;
        if (input.options().costCutoff() != null && payee != Payee.SUPPLIER) {
            /* On the water: only what was paid by the closing date, and whatever the user adds to it. */
            open = ZERO;
            overpaid = ZERO;
            applied = accrual != null;
        } else {
            applied = accrual != null && Money.money(accrual.basisAmountEur()).compareTo(open) == 0;
            stale = accrual != null && !applied;
        }
        BigDecimal unpaid = applied ? Money.money(accrual.amountEur()) : open;
        boolean confirmed = applied && accrual.invoiceReceived();
        State state = unpaid.signum() == 0 ? State.WERKELIJK : confirmed ? State.BEVESTIGD : State.GESCHAT;
        return new StreamCost(payee, status, planned, paid, open, paid.add(unpaid),
                confirmed ? ZERO : unpaid, overpaid, state, applied, stale);
    }

    /** One row per product, in the order of first appearance on the container. */
    private List<Row> rows(LotCost.Input input, List<String> notes) {
        PurchaseOrder order = input.order();
        boolean orderedBasis = input.options().quantityBasis() == QuantityBasis.BESTELD;
        boolean billedAsDelivered = input.options().billedBasis() == BilledBasis.GELEVERD;
        Map<Long, Row> byProduct = new LinkedHashMap<>();
        Set<Long> gone = new LinkedHashSet<>();
        for (PurchaseOrderLine line : order.lines()) {
            Product product = line.productId() == null ? null : input.productsById().get(line.productId());
            if (product == null) {
                if (gone.add(line.productId())) {
                    notes.add("Product " + line.productId() + " bestaat niet meer: de regel is niet opgenomen.");
                }
                continue;
            }
            Row row = byProduct.computeIfAbsent(line.productId(), id -> new Row(id, product));
            BigDecimal exwPrice = line.exwPrice() != null ? line.exwPrice() : product.exwPrice();
            BigDecimal extraUnit = line.extraUnitCost() != null ? line.extraUnitCost() : product.extraUnitCost();
            Currency currency = line.exwCurrency() != null ? line.exwCurrency() : product.exwCurrency();
            if (currency == null) currency = Currency.USD;
            /* The product's own price, never the family-levelled goods value: each item is valued on its own. */
            BigDecimal priceEur = PurchaseOrderService.euroAtOrderRate(order,
                    Money.nz(exwPrice).add(Money.nz(extraUnit)), currency);
            int received = orderedBasis ? line.ordered() : line.received();
            int billed = billedAsDelivered ? Math.min(line.ordered(), received) : line.ordered();
            row.ordered += line.ordered();
            row.received += received;
            row.damaged += orderedBasis ? 0 : line.damaged();
            row.billed += billed;
            row.goodsValue = row.goodsValue.add(priceEur.multiply(BigDecimal.valueOf(billed)));
            row.receivedValue = row.receivedValue.add(priceEur.multiply(BigDecimal.valueOf(received)));
            row.priced |= priceEur.signum() != 0;
            row.nonDdp |= !line.deliveredDutyPaid();
            if (row.firstPrice == null) row.firstPrice = priceEur;
        }
        if (input.brec() != null && input.brec().lines() != null) {
            for (LandedCost.Line cost : input.brec().lines()) {
                Row row = byProduct.get(cost.productId());
                if (row == null) continue;
                row.origin = row.origin.add(positive(cost.originEur()));
                row.freight = row.freight.add(positive(cost.freightEur()));
                row.duty = row.duty.add(positive(cost.dutyEur()));
                row.destination = row.destination.add(positive(cost.destinationEur()));
                row.separate = row.separate.add(positive(cost.separateEur()));
                if (row.dutyRatePct == null) row.dutyRatePct = cost.dutyRatePct();
            }
        }
        return new ArrayList<>(byProduct.values());
    }

    /** Pieces reported damaged or short against this container after receipt, per product: information only. */
    private Map<Long, Integer> laterLost(LotCost.Input input) {
        Map<Long, Integer> lost = new LinkedHashMap<>();
        for (StockMovement row : input.laterLoss()) {
            if (row == null || (row.kind() != StockMovement.Kind.DAMAGED && row.kind() != StockMovement.Kind.SHORTAGE)) continue;
            if (input.options().lossCutoff() != null && (row.at() == null || !row.at().isBefore(input.options().lossCutoff()))) continue;
            lost.merge(row.productId(), Math.abs(row.delta()), Integer::sum);
        }
        return lost;
    }

    private static CreditTreatment defaultTreatment(PurchaseSupplierCredit.Reason reason) {
        if (reason == null) return null;
        return switch (reason) {
            case PRICE -> CreditTreatment.VERLAAGT;
            case SHORTAGE, DAMAGE -> CreditTreatment.BUITEN;
            case OTHER -> null;
        };
    }

    private static boolean usesTransportRate(PurchaseOrder order, Payee payee, Currency money) {
        return payee == Payee.LOGISTICS && money != Currency.EUR
                && order.usdToEurTransport() != null && order.usdToEurTransport().signum() > 0;
    }

    /**
     * The first candidate that gives any product a weight, rounded to the
     * cent before it is used: that rounded weight is what the lot stores.
     * When none does, every product weighs the same.
     */
    private Key key(List<Row> rows, List<Candidate> candidates) {
        for (Candidate candidate : candidates) {
            List<BigDecimal> weights = rounded(rows.stream().map(candidate.weight).toList());
            if (weights.stream().anyMatch(weight -> weight.signum() > 0)) return new Key(weights, candidate.fallback);
        }
        return new Key(rows.stream().map(row -> Money.money(BigDecimal.ONE)).toList(), BY_EQUAL_PARTS);
    }

    private static List<Candidate> with(Candidate first, List<Candidate> rest) {
        List<Candidate> all = new ArrayList<>();
        all.add(first);
        all.addAll(rest);
        return all;
    }

    /** The weights are then pieces or received value, not shares of the calculation; the container says so. */
    private void noteFallback(List<String> notes, String amount, Key key, BigDecimal total) {
        if (key.fallback == null || total.signum() == 0 || key.weights.isEmpty()) return;
        notes.add(amount + " verdeeld volgens " + key.fallback + ": de berekening gaf geen sleutel.");
    }

    private static List<BigDecimal> rounded(List<BigDecimal> weights) {
        return weights.stream().map(weight -> Money.money(weight).max(ZERO)).toList();
    }

    /**
     * Largest remainders, stable product order: every source cent is assigned exactly once.
     * The same algorithm as the nacalculatie uses; all weights zero means equal weights.
     */
    private List<BigDecimal> allocate(BigDecimal amount, List<BigDecimal> weights) {
        if (weights.isEmpty()) return List.of();
        BigDecimal weightTotal = weights.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        if (weightTotal.signum() == 0) return allocate(amount, weights.stream().map(w -> BigDecimal.ONE).toList());
        BigDecimal cents = Money.money(amount).abs().movePointRight(2);
        List<BigDecimal> assigned = new ArrayList<>();
        List<BigDecimal> remainders = new ArrayList<>();
        BigDecimal assignedCents = BigDecimal.ZERO;
        for (BigDecimal weight : weights) {
            /* Compare exact remainder numerators; never round ratios before deciding who receives a cent. */
            BigDecimal weighted = cents.multiply(weight);
            BigDecimal whole = weighted.divide(weightTotal, 0, RoundingMode.DOWN);
            assigned.add(whole);
            remainders.add(weighted.subtract(whole.multiply(weightTotal)));
            assignedCents = assignedCents.add(whole);
        }
        int extras = cents.subtract(assignedCents).intValueExact();
        List<Integer> priority = new ArrayList<>();
        for (int i = 0; i < weights.size(); i++) priority.add(i);
        priority.sort(Comparator.comparing((Integer i) -> remainders.get(i)).reversed());
        for (int i = 0; i < extras; i++) {
            int target = priority.get(i);
            assigned.set(target, assigned.get(target).add(BigDecimal.ONE));
        }
        BigDecimal sign = BigDecimal.valueOf(amount.signum());
        return assigned.stream().map(value -> value.multiply(sign).movePointLeft(2).setScale(2)).toList();
    }

    /** A component whose divisor is zero is zero; the lot keeps its amounts. */
    private static BigDecimal unit(BigDecimal amount, int divisor) {
        return divisor == 0 ? ZERO_UNIT
                : amount.divide(BigDecimal.valueOf(divisor), Money.UNIT_SCALE, RoundingMode.HALF_UP);
    }

    private static BigDecimal positive(BigDecimal value) { return Money.nz(value).max(BigDecimal.ZERO); }

    private static String name(Enum<?> value) { return value == null ? null : value.name(); }

    private record Candidate(String fallback, Function<Row, BigDecimal> weight) {}

    private record Key(List<BigDecimal> weights, String fallback) {}

    private static final class Credit {
        private final PurchaseSupplierCredit credit;
        private BigDecimal eur = ZERO;
        private CreditTreatment treatment;
        private boolean decided;
        private String requiredBy;

        private Credit(PurchaseSupplierCredit credit) { this.credit = credit; }
    }

    private static final class Row {
        private final Long productId;
        private final Product product;
        private int ordered;
        private int received;
        private int damaged;
        private int billed;
        private boolean priced;
        private boolean nonDdp;
        private BigDecimal firstPrice;
        /** Billed pieces at the product's own price: the key of the goods and of a price credit. */
        private BigDecimal goodsValue = BigDecimal.ZERO;
        private BigDecimal receivedValue = BigDecimal.ZERO;
        private BigDecimal origin = BigDecimal.ZERO;
        private BigDecimal freight = BigDecimal.ZERO;
        private BigDecimal duty = BigDecimal.ZERO;
        private BigDecimal destination = BigDecimal.ZERO;
        private BigDecimal separate = BigDecimal.ZERO;
        private BigDecimal dutyRatePct;

        private Row(Long productId, Product product) {
            this.productId = productId;
            this.product = product;
        }

        /** The purchase price of one piece in euro; an average when the lines of the product differ. */
        private BigDecimal unitPrice() {
            if (billed > 0) return unit(goodsValue, billed);
            if (received > 0) return unit(receivedValue, received);
            return Money.unit(firstPrice);
        }
    }
}
