package be.enrosed.sourcing.domain;

import be.enrosed.shared.Currency;

import java.math.BigDecimal;
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
         * Port of arrival (Rotterdam, Amsterdam, Antwerp, ...).
         *
         * Drives the label of the destination costs on screen and on the PDF.
         * The costs themselves do not change with the port; only where they
         * start counting does.
         */
        String destinationPort,

        String notes,
        List<PurchaseOrderLine> lines
) {
    public List<PurchaseOrderLine> lines() {
        return lines == null ? List.of() : lines;
    }

    public String destinationPort() {
        return destinationPort == null || destinationPort.isBlank() ? "Rotterdam" : destinationPort;
    }
}
