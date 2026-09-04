package be.enrosed.sourcing.domain;

import be.enrosed.shared.Currency;
import be.enrosed.shared.security.ActorRef;
import com.fasterxml.jackson.annotation.JsonIgnore;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Purchase order on a container basis.
 *
 * The costs sit in two bins that must not blur together:
 *  - {@code originCosts} are the costs up to the ship in China. They fall
 *    before the EU border and therefore count towards the customs value.
 *  - {@code destinationCosts} are the costs from the port of discharge.
 *    They come after import and carry no import duty.
 *
 * Exchange rates are pinned onto the order: an old calculation must not
 * change because today's rate moves.
 */
public record PurchaseOrder(
        Long id,
        String number,
        /**
         * Free-text nickname next to the number, e.g. "voor Frans".
         *
         * Duplicated calculations exist to compare variants, and "PO-2026-008"
         * against "PO-2026-009" says nothing about which is which.
         */
        String alias,
        Long supplierId,
        LocalDate orderDate,
        PurchaseOrderStatus status,
        ContainerType containerType,

        BigDecimal cnyToUsd,
        BigDecimal usdToEurGoods,
        BigDecimal usdToEurTransport,

        BigDecimal freightUsd,
        BigDecimal originCosts,
        Currency originCurrency,
        BigDecimal destinationCostsEur,

        BigDecimal defaultDutyRatePct,
        BigDecimal extraRevenueEur,

        Allocation allocFreight,
        Allocation allocOrigin,
        Allocation allocDestination,
        Allocation allocExtra,

        /**
         * Port of departure (Ningbo, Shanghai, Shenzhen, ...).
         *
         * <p>This belongs to the order rather than the supplier: the same
         * factory can ship through another port for a specific container.
         * Legacy rows predate this field and can therefore be {@code null};
         * callers always receive the safe operational default.</p>
         */
        String departurePort,

        /**
         * Port of arrival (Rotterdam, Amsterdam, Antwerp, ...).
         *
         * Drives the label of the destination costs on screen and on the PDF.
         * The costs themselves do not change with the port; only where they
         * start counting does.
         */
        String destinationPort,

        /**
         * Stock location the container is unloaded at; null on orders from
         * before locations existed, which is read as the warehouse.
         */
        Long receivingLocationId,

        /**
         * Treat the colours and sizes of one series as a single product when
         * sharing out the container costs, so every variant lands at the
         * same unit cost. Null reads as on - that is what a buyer expects.
         */
        Boolean groupVariants,

        /** When the container is expected; drives "te verwachten" on the products. */
        LocalDate expectedArrival,
        /** The day it was received; null until then. */
        LocalDate receivedOn,
        /** What was actually paid for the whole order - it sometimes differs from the sum. */
        BigDecimal paidTotalEur,
        /** Whether the received pieces were booked into stock; null on old orders reads as "yes if received". */
        Boolean stockBooked,
        /** How the supplier is paid; null reads as thirds. */
        PaymentTerms paymentTerms,
        /** The day the container sailed; null until then. */
        LocalDate shippedOn,
        /** Container or bill-of-lading number, carrier tracking link - whatever finds the box. */
        String trackingReference,

        /** Server-owned creator snapshot. Kept outside the mutable REST order payload. */
        @JsonIgnore ActorRef createdBy,
        /** Exact creation moment; legacy orders legitimately have no value. */
        @JsonIgnore Instant createdAt,

        String notes,
        List<PurchaseOrderLine> lines,

        /**
         * Inspection at the factory, in euro. Its own line on the order and
         * on the internal sheets, never shared out into a piece price: the
         * buyer decides per container whether it is worth it.
         */
        BigDecimal inspectionCostEur,

        /**
         * Other costs booked next to the inspection, each under its own name:
         * a certificate, a lab test, a sample run. Same rule: own lines on
         * the order and the internal sheets, never a piece price. Never null.
         */
        List<OtherCost> otherCosts
) {
    public PurchaseOrder {
        otherCosts = otherCosts == null ? List.of()
                : otherCosts.stream().filter(java.util.Objects::nonNull).toList();
    }

    /** Compatibility for callers written before the other costs existed. */
    public PurchaseOrder(
            Long id, String number, String alias, Long supplierId, LocalDate orderDate,
            PurchaseOrderStatus status, ContainerType containerType,
            BigDecimal cnyToUsd, BigDecimal usdToEurGoods, BigDecimal usdToEurTransport,
            BigDecimal freightUsd, BigDecimal originCosts, Currency originCurrency,
            BigDecimal destinationCostsEur, BigDecimal defaultDutyRatePct, BigDecimal extraRevenueEur,
            Allocation allocFreight, Allocation allocOrigin, Allocation allocDestination, Allocation allocExtra,
            String departurePort, String destinationPort, Long receivingLocationId, Boolean groupVariants,
            LocalDate expectedArrival, LocalDate receivedOn, BigDecimal paidTotalEur, Boolean stockBooked,
            PaymentTerms paymentTerms, LocalDate shippedOn, String trackingReference,
            ActorRef createdBy, Instant createdAt, String notes, List<PurchaseOrderLine> lines,
            BigDecimal inspectionCostEur) {
        this(id, number, alias, supplierId, orderDate, status, containerType, cnyToUsd, usdToEurGoods,
                usdToEurTransport, freightUsd, originCosts, originCurrency, destinationCostsEur,
                defaultDutyRatePct, extraRevenueEur, allocFreight, allocOrigin, allocDestination, allocExtra,
                departurePort, destinationPort, receivingLocationId, groupVariants, expectedArrival, receivedOn,
                paidTotalEur, stockBooked, paymentTerms, shippedOn, trackingReference,
                createdBy, createdAt, notes, lines, inspectionCostEur, List.of());
    }

    /** Compatibility for callers written before the inspection cost existed. */
    public PurchaseOrder(
            Long id, String number, String alias, Long supplierId, LocalDate orderDate,
            PurchaseOrderStatus status, ContainerType containerType,
            BigDecimal cnyToUsd, BigDecimal usdToEurGoods, BigDecimal usdToEurTransport,
            BigDecimal freightUsd, BigDecimal originCosts, Currency originCurrency,
            BigDecimal destinationCostsEur, BigDecimal defaultDutyRatePct, BigDecimal extraRevenueEur,
            Allocation allocFreight, Allocation allocOrigin, Allocation allocDestination, Allocation allocExtra,
            String departurePort, String destinationPort, Long receivingLocationId, Boolean groupVariants,
            LocalDate expectedArrival, LocalDate receivedOn, BigDecimal paidTotalEur, Boolean stockBooked,
            PaymentTerms paymentTerms, LocalDate shippedOn, String trackingReference,
            ActorRef createdBy, Instant createdAt, String notes, List<PurchaseOrderLine> lines) {
        this(id, number, alias, supplierId, orderDate, status, containerType, cnyToUsd, usdToEurGoods,
                usdToEurTransport, freightUsd, originCosts, originCurrency, destinationCostsEur,
                defaultDutyRatePct, extraRevenueEur, allocFreight, allocOrigin, allocDestination, allocExtra,
                departurePort, destinationPort, receivingLocationId, groupVariants, expectedArrival, receivedOn,
                paidTotalEur, stockBooked, paymentTerms, shippedOn, trackingReference,
                createdBy, createdAt, notes, lines, null, List.of());
    }

    /** The same order with the inspection cost set; null clears it. */
    public PurchaseOrder withInspectionCost(BigDecimal value) {
        return new PurchaseOrder(id, number, alias, supplierId, orderDate, status, containerType, cnyToUsd,
                usdToEurGoods, usdToEurTransport, freightUsd, originCosts, originCurrency, destinationCostsEur,
                defaultDutyRatePct, extraRevenueEur, allocFreight, allocOrigin, allocDestination, allocExtra,
                departurePort, destinationPort, receivingLocationId, groupVariants, expectedArrival, receivedOn,
                paidTotalEur, stockBooked, paymentTerms, shippedOn, trackingReference,
                createdBy, createdAt, notes, lines, value, otherCosts);
    }

    /** The same order with the other costs replaced; null or empty clears them. */
    public PurchaseOrder withOtherCosts(List<OtherCost> value) {
        return new PurchaseOrder(id, number, alias, supplierId, orderDate, status, containerType, cnyToUsd,
                usdToEurGoods, usdToEurTransport, freightUsd, originCosts, originCurrency, destinationCostsEur,
                defaultDutyRatePct, extraRevenueEur, allocFreight, allocOrigin, allocDestination, allocExtra,
                departurePort, destinationPort, receivingLocationId, groupVariants, expectedArrival, receivedOn,
                paidTotalEur, stockBooked, paymentTerms, shippedOn, trackingReference,
                createdBy, createdAt, notes, lines, inspectionCostEur, value);
    }

    /** True when an inspection or another named cost is booked apart from the piece price. */
    public boolean hasSeparateCosts() {
        if (inspectionCostEur != null && inspectionCostEur.signum() > 0) return true;
        return otherCosts.stream().anyMatch(OtherCost::charged);
    }

    /** Compatibility for callers written before creator metadata existed. */
    public PurchaseOrder(
            Long id, String number, String alias, Long supplierId, LocalDate orderDate,
            PurchaseOrderStatus status, ContainerType containerType,
            BigDecimal cnyToUsd, BigDecimal usdToEurGoods, BigDecimal usdToEurTransport,
            BigDecimal freightUsd, BigDecimal originCosts, Currency originCurrency,
            BigDecimal destinationCostsEur, BigDecimal defaultDutyRatePct, BigDecimal extraRevenueEur,
            Allocation allocFreight, Allocation allocOrigin, Allocation allocDestination, Allocation allocExtra,
            String departurePort, String destinationPort, Long receivingLocationId, Boolean groupVariants,
            LocalDate expectedArrival, LocalDate receivedOn, BigDecimal paidTotalEur, Boolean stockBooked,
            PaymentTerms paymentTerms, LocalDate shippedOn, String trackingReference,
            String notes, List<PurchaseOrderLine> lines) {
        this(id, number, alias, supplierId, orderDate, status, containerType, cnyToUsd, usdToEurGoods,
                usdToEurTransport, freightUsd, originCosts, originCurrency, destinationCostsEur,
                defaultDutyRatePct, extraRevenueEur, allocFreight, allocOrigin, allocDestination, allocExtra,
                departurePort, destinationPort, receivingLocationId, groupVariants, expectedArrival, receivedOn,
                paidTotalEur, stockBooked, paymentTerms, shippedOn, trackingReference,
                null, null, notes, lines);
    }

    /** Compatibility for callers written before payment terms and shipping facts existed. */
    public PurchaseOrder(
            Long id, String number, String alias, Long supplierId, LocalDate orderDate,
            PurchaseOrderStatus status, ContainerType containerType,
            BigDecimal cnyToUsd, BigDecimal usdToEurGoods, BigDecimal usdToEurTransport,
            BigDecimal freightUsd, BigDecimal originCosts, Currency originCurrency,
            BigDecimal destinationCostsEur, BigDecimal defaultDutyRatePct, BigDecimal extraRevenueEur,
            Allocation allocFreight, Allocation allocOrigin, Allocation allocDestination, Allocation allocExtra,
            String departurePort, String destinationPort, Long receivingLocationId, Boolean groupVariants,
            LocalDate expectedArrival, LocalDate receivedOn, BigDecimal paidTotalEur, Boolean stockBooked,
            String notes, List<PurchaseOrderLine> lines) {
        this(id, number, alias, supplierId, orderDate, status, containerType, cnyToUsd, usdToEurGoods,
                usdToEurTransport, freightUsd, originCosts, originCurrency, destinationCostsEur,
                defaultDutyRatePct, extraRevenueEur, allocFreight, allocOrigin, allocDestination, allocExtra,
                departurePort, destinationPort, receivingLocationId, groupVariants, expectedArrival, receivedOn,
                paidTotalEur, stockBooked, null, null, null, notes, lines);
    }

    public PaymentTerms paymentTerms() {
        return paymentTerms == null ? PaymentTerms.THIRDS : paymentTerms;
    }

    /** Adds immutable metadata while a brand-new order is still being assembled server-side. */
    public PurchaseOrder withCreationMetadata(ActorRef actor, Instant at) {
        return new PurchaseOrder(id, number, alias, supplierId, orderDate, status, containerType, cnyToUsd,
                usdToEurGoods, usdToEurTransport, freightUsd, originCosts, originCurrency, destinationCostsEur,
                defaultDutyRatePct, extraRevenueEur, allocFreight, allocOrigin, allocDestination, allocExtra,
                departurePort, destinationPort, receivingLocationId, groupVariants, expectedArrival, receivedOn,
                paidTotalEur, stockBooked, paymentTerms, shippedOn, trackingReference,
                actor, at, notes, lines, inspectionCostEur, otherCosts);
    }

    /** Compatibility for callers written before receipts had their own fields. */
    public PurchaseOrder(
            Long id, String number, String alias, Long supplierId, LocalDate orderDate,
            PurchaseOrderStatus status, ContainerType containerType,
            BigDecimal cnyToUsd, BigDecimal usdToEurGoods, BigDecimal usdToEurTransport,
            BigDecimal freightUsd, BigDecimal originCosts, Currency originCurrency,
            BigDecimal destinationCostsEur, BigDecimal defaultDutyRatePct, BigDecimal extraRevenueEur,
            Allocation allocFreight, Allocation allocOrigin, Allocation allocDestination, Allocation allocExtra,
            String departurePort, String destinationPort, Long receivingLocationId, Boolean groupVariants,
            String notes, List<PurchaseOrderLine> lines) {
        this(id, number, alias, supplierId, orderDate, status, containerType, cnyToUsd, usdToEurGoods,
                usdToEurTransport, freightUsd, originCosts, originCurrency, destinationCostsEur,
                defaultDutyRatePct, extraRevenueEur, allocFreight, allocOrigin, allocDestination, allocExtra,
                departurePort, destinationPort, receivingLocationId, groupVariants,
                null, null, null, null, notes, lines);
    }

    /** Old received orders booked their stock at the transition; newer ones say so explicitly. */
    public boolean isStockBooked() {
        return stockBooked != null ? stockBooked : status == PurchaseOrderStatus.ONTVANGEN;
    }

    /** Same order, other receipt facts - the one thing the receive flow changes on the header. */
    public PurchaseOrder withReceipt(PurchaseOrderStatus status, LocalDate receivedOn, BigDecimal paidTotalEur,
                                     Boolean stockBooked, String notes, List<PurchaseOrderLine> lines) {
        return new PurchaseOrder(id, number, alias, supplierId, orderDate, status, containerType, cnyToUsd,
                usdToEurGoods, usdToEurTransport, freightUsd, originCosts, originCurrency, destinationCostsEur,
                defaultDutyRatePct, extraRevenueEur, allocFreight, allocOrigin, allocDestination, allocExtra,
                departurePort, destinationPort, receivingLocationId, groupVariants, expectedArrival,
                receivedOn, paidTotalEur, stockBooked, paymentTerms, shippedOn, trackingReference,
                createdBy, createdAt, notes, lines, inspectionCostEur, otherCosts);
    }

    /** Compatibility for callers written before variant grouping existed. */
    public PurchaseOrder(
            Long id, String number, String alias, Long supplierId, LocalDate orderDate,
            PurchaseOrderStatus status, ContainerType containerType,
            BigDecimal cnyToUsd, BigDecimal usdToEurGoods, BigDecimal usdToEurTransport,
            BigDecimal freightUsd, BigDecimal originCosts, Currency originCurrency,
            BigDecimal destinationCostsEur, BigDecimal defaultDutyRatePct, BigDecimal extraRevenueEur,
            Allocation allocFreight, Allocation allocOrigin, Allocation allocDestination, Allocation allocExtra,
            String departurePort, String destinationPort, Long receivingLocationId,
            String notes, List<PurchaseOrderLine> lines) {
        this(id, number, alias, supplierId, orderDate, status, containerType, cnyToUsd, usdToEurGoods,
                usdToEurTransport, freightUsd, originCosts, originCurrency, destinationCostsEur,
                defaultDutyRatePct, extraRevenueEur, allocFreight, allocOrigin, allocDestination, allocExtra,
                departurePort, destinationPort, receivingLocationId, null, notes, lines);
    }

    /** Null reads as on. */
    public boolean groupsVariants() {
        return groupVariants == null || groupVariants;
    }

    /** Compatibility for callers written before receiving locations existed. */
    public PurchaseOrder(
            Long id, String number, String alias, Long supplierId, LocalDate orderDate,
            PurchaseOrderStatus status, ContainerType containerType,
            BigDecimal cnyToUsd, BigDecimal usdToEurGoods, BigDecimal usdToEurTransport,
            BigDecimal freightUsd, BigDecimal originCosts, Currency originCurrency,
            BigDecimal destinationCostsEur, BigDecimal defaultDutyRatePct, BigDecimal extraRevenueEur,
            Allocation allocFreight, Allocation allocOrigin, Allocation allocDestination, Allocation allocExtra,
            String departurePort, String destinationPort, String notes, List<PurchaseOrderLine> lines) {
        this(id, number, alias, supplierId, orderDate, status, containerType, cnyToUsd, usdToEurGoods,
                usdToEurTransport, freightUsd, originCosts, originCurrency, destinationCostsEur,
                defaultDutyRatePct, extraRevenueEur, allocFreight, allocOrigin, allocDestination, allocExtra,
                departurePort, destinationPort, null, notes, lines);
    }

    public List<PurchaseOrderLine> lines() {
        return lines == null ? List.of() : lines;
    }

    public String departurePort() {
        return departurePort == null || departurePort.isBlank() ? "Ningbo" : departurePort.strip();
    }

    public String destinationPort() {
        return destinationPort == null || destinationPort.isBlank() ? "Rotterdam" : destinationPort.strip();
    }
}
