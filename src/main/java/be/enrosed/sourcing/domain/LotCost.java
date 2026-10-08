package be.enrosed.sourcing.domain;

import be.enrosed.catalog.domain.Product;
import be.enrosed.catalog.domain.StockMovement;
import be.enrosed.shared.Currency;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * The acquisition value of the receipt lots of one container, for the
 * year-end stock valuation: one lot per product on the container.
 *
 * Neither the calculation nor the nacalculatie is this figure. The
 * calculation holds the Enrosed kost and is an Afspraak; the nacalculatie
 * divides by usable pieces and subtracts every credit whatever its reason.
 * Here the goods are divided by the billed pieces and the container costs
 * by the received pieces, and neither a missing nor a damaged piece is
 * valued. This record only names the shapes; it has no components itself.
 */
public record LotCost() {

    /** Received counts for a container that is in, ordered counts for one still on the water. */
    public enum QuantityBasis { ONTVANGEN, BESTELD }

    /** What the supplier charged: the ordered pieces, or only the ones that arrived. */
    public enum BilledBasis { BESTELD, GELEVERD }

    /** What a supplier credit does to the value: lowers it, stays outside it, or already sits in the lower payment. */
    public enum CreditTreatment { VERLAAGT, BUITEN, IN_BETALING }

    /** How sure the amount of a payee stream is. */
    public enum State { WERKELIJK, GESCHAT, BEVESTIGD }

    public enum LotStatus { OK, TEKORT, MEER_ONTVANGEN, GEEN_PRIJS, GEEN_ONTVANGST }

    /** The amount still owed on a stream as the user stated it, with the open amount it was confirmed against. */
    public record Accrual(BigDecimal amountEur, boolean invoiceReceived, BigDecimal basisAmountEur) {}

    /**
     * @param rateCutoff       the day the goods were bought: a foreign payment up to and including
     *                         it keeps its bank euro, a later one counts at the container rate
     * @param costCutoff       only for a container in transit: Douane & transport and the inspection
     *                         count as far as they were paid on or before this day
     * @param lossCutoff       later loss booked from this instant on is not reported
     * @param creditTreatments the user's choice per credit id
     */
    public record Options(LocalDate rateCutoff, QuantityBasis quantityBasis, LocalDate costCutoff,
                          Instant lossCutoff, BilledBasis billedBasis,
                          Map<PurchasePayment.Payee, Accrual> accruals,
                          Map<Long, CreditTreatment> creditTreatments) {
        public Options {
            quantityBasis = quantityBasis == null ? QuantityBasis.ONTVANGEN : quantityBasis;
            billedBasis = billedBasis == null ? BilledBasis.BESTELD : billedBasis;
            accruals = accruals == null ? Map.of() : Map.copyOf(accruals);
            creditTreatments = creditTreatments == null ? Map.of() : Map.copyOf(creditTreatments);
        }
    }

    /**
     * Everything the calculation reads, loaded once.
     *
     * @param brec               the calculation on received quantities: allocation key only
     * @param supplierEur        the supplier Afspraak on ordered quantities, CIF transport included
     * @param supplierFreightEur the CIF transport inside it; zero when the forwarder is paid for it
     * @param streams            the reconciliation run on the payments at the container rates
     * @param laterLoss          the stock rows reported against this container after receipt
     */
    public record Input(PurchaseOrder order, Options options, Map<Long, Product> productsById, LandedCost brec,
                        BigDecimal supplierEur, BigDecimal supplierFreightEur,
                        List<PurchasePayment> payments, List<PurchaseSupplierCredit> credits,
                        List<PurchaseReconciliation.Stream> streams, List<StockMovement> laterLoss) {
        public Input {
            productsById = productsById == null ? Map.of() : productsById;
            payments = payments == null ? List.of() : List.copyOf(payments);
            credits = credits == null ? List.of() : List.copyOf(credits);
            streams = streams == null ? List.of() : List.copyOf(streams);
            laterLoss = laterLoss == null ? List.of() : List.copyOf(laterLoss);
        }
    }

    /**
     * One payee stream on the accrual basis.
     *
     * @param status       the reconciliation's own word, shown as the Nacalculatie shows it
     * @param paidEur      the payments at the euro that enters the value
     * @param openEur      the unpaid part of the Afspraak at the container rates, before any accrual
     * @param includedEur  paid plus what is still owed: the amount in the value
     * @param estimatedEur the part of includedEur that no invoice confirms yet
     * @param overpaidEur  paid above the Afspraak; it is in the value in full
     */
    public record StreamCost(PurchasePayment.Payee payee, String status, BigDecimal plannedEur, BigDecimal paidEur,
                             BigDecimal openEur, BigDecimal includedEur, BigDecimal estimatedEur,
                             BigDecimal overpaidEur, State state, boolean accrualApplied, boolean accrualStale) {}

    /** One payment with the euro value that was counted for it and the rule that gave it. */
    public record PaymentUse(Long paymentId, LocalDate paidOn, PurchasePayment.Payee payee, String label,
                             BigDecimal amount, Currency currency, BigDecimal storedEur, BigDecimal countedEur,
                             boolean inValue, String rule) {
        public static final String RULE_STORED = "Geboekte eurowaarde (betaald tot en met de datum van aankoop)";
        public static final String RULE_GOODS_RATE = "Koers goederen van de container (betaald na de aankoop)";
        public static final String RULE_TRANSPORT_RATE = "Koers transport van de container (betaald na de aankoop)";
        public static final String RULE_EURO = "Euro";
        public static final String RULE_OTHER = "Niet in de waarde (Bijkomende kosten)";
        public static final String RULE_AFTER_CLOSING = "Na de afsluitdatum (onderweg)";
    }

    /**
     * One supplier credit with what it does to the value.
     *
     * @param treatment        null while nobody decided a credit that has no default
     * @param decided          true when the treatment is the user's choice, not the default of the reason
     * @param decisionRequired true when the default may not be accepted silently
     * @param requiredBy       the notice that asks for the decision; null when none does
     */
    public record CreditUse(Long creditId, LocalDate notedOn, PurchaseSupplierCredit.Reason reason,
                            BigDecimal amount, Currency currency, BigDecimal countedEur,
                            CreditTreatment treatment, boolean decided, boolean decisionRequired,
                            String requiredBy) {
        public static final String UNDECIDED = "NOG_TE_BESLISSEN";
        public static final String NO_KIND = "TEGOED_ZONDER_SOORT";
        public static final String SETTLED_LOWER = "TEGOED_EN_LAGER_AFGEREKEND";
        public static final String ABOVE_LOSS = "TEGOED_MEER_DAN_VERLIES";

        /** The treatment as the lists print it. */
        public String treatmentCode() {
            return treatment == null ? UNDECIDED : treatment.name();
        }
    }

    /**
     * One product on the container.
     *
     * @param billedQuantity    the pieces the supplier's amount covers
     * @param goodsDivisor      the larger of billed and received: what the goods are divided by
     * @param costDivisor       the received pieces: what every container cost is divided by
     * @param capacity          the pieces of this lot that can still lie in stock: received minus damaged
     * @param goodsKey          the weight each of the four amounts was shared out with, so every
     *                          share can be recomputed; transportKey is null unless the container is CIF
     * @param calcOriginEur     the container costs of the lot split as the calculation would, for information
     * @param missingCostEur    the goods cost of the pieces that never arrived
     * @param damagedCostEur    the full cost of the damaged pieces and the ones reported later
     */
    public record Lot(Long productId, String sku, String productName,
                      int orderedQuantity, int receivedQuantity, int damagedQuantity, int laterLostQuantity,
                      int billedQuantity, int goodsDivisor, int costDivisor, int capacity,
                      BigDecimal unitPriceEur,
                      BigDecimal goodsKey, BigDecimal transportKey, BigDecimal logisticsKey, BigDecimal separateKey,
                      BigDecimal goodsEur, BigDecimal priceCreditEur, BigDecimal transportEur,
                      BigDecimal logisticsEur, BigDecimal separateEur, BigDecimal lotCostEur, BigDecimal estimatedEur,
                      BigDecimal unitGoodsEur, BigDecimal unitTransportEur, BigDecimal unitLogisticsEur,
                      BigDecimal unitSeparateEur, BigDecimal unitValueEur, BigDecimal unitEstimatedEur,
                      BigDecimal calcOriginEur, BigDecimal calcFreightEur, BigDecimal calcDutyEur,
                      BigDecimal calcDestinationEur, BigDecimal calcDutyRatePct,
                      BigDecimal missingCostEur, BigDecimal damagedCostEur, LotStatus status) {}

    /**
     * The container as a whole.
     *
     * @param cif                      true when the supplier is owed the transport before the border
     * @param allocOrigin              the names of the order's allocation settings behind the keys
     * @param streams                  supplier, Douane & transport, inspection, in that order
     * @param otherExcludedEur         paid under "Bijkomende kosten": never in the value
     * @param priceCreditEur           the credits that lower the value
     * @param lossCreditEur            the credits that stay outside it
     * @param defaultLossCreditEur     the part of lossCreditEur nobody decided on
     * @param exchangeDifferenceEur    bank euro minus counted euro over all payments: listed, never valued
     * @param enrosedCostExcludedEur   the Enrosed kost of the calculation, for information
     * @param missingAndDamagedCostEur what the missing and damaged pieces of the container cost
     * @param supplierSettledLower     the supplier stream was explicitly settled below the Afspraak
     * @param hasShortage              some product arrived with fewer pieces than ordered
     * @param paymentWithoutEuro       a payment misses its stored euro value
     * @param notes                    one line per fallback key and per line that was left out
     */
    public record Container(Long purchaseOrderId, QuantityBasis quantityBasis, BilledBasis billedBasis,
                            LocalDate rateCutoff, boolean cif, boolean groupVariants, boolean separateInPiecePrice,
                            String allocOrigin, String allocFreight, String allocDestination, String allocSeparate,
                            List<StreamCost> streams, BigDecimal supplierGoodsEur, BigDecimal supplierTransportEur,
                            BigDecimal otherExcludedEur, BigDecimal priceCreditEur, BigDecimal lossCreditEur,
                            BigDecimal defaultLossCreditEur, BigDecimal exchangeDifferenceEur,
                            BigDecimal enrosedCostExcludedEur, BigDecimal acquisitionEur, BigDecimal estimatedEur,
                            BigDecimal missingAndDamagedCostEur, boolean supplierSettledLower, boolean hasShortage,
                            boolean paymentWithoutEuro, List<PaymentUse> payments, List<CreditUse> credits,
                            List<Lot> lots, List<String> notes) {

        public StreamCost stream(PurchasePayment.Payee payee) {
            return streams.stream().filter(stream -> stream.payee() == payee).findFirst().orElse(null);
        }

        public Lot lot(Long productId) {
            return lots.stream().filter(lot -> java.util.Objects.equals(lot.productId(), productId))
                    .findFirst().orElse(null);
        }
    }
}
