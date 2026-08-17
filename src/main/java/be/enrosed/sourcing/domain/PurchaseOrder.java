package be.enrosed.sourcing.domain;

import be.enrosed.shared.Currency;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Inkooporder op containerbasis.
 *
 * De kosten staan in twee bakken die niet door elkaar mogen lopen:
 *  - {@code originCosts} zijn de kosten tot aan het schip in China. Die vallen
 *    voor de EU-grens en tellen dus mee in de douanewaarde.
 *  - {@code destinationCosts} zijn de kosten vanaf de loshaven. Die komen na
 *    de invoer en worden niet belast met invoerrechten.
 *
 * De koersen worden op de order vastgeklikt: een oude calculatie mag niet
 * veranderen omdat de dagkoers beweegt.
 */
public record PurchaseOrder(
        Long id,
        String number,
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

        String notes,
        List<PurchaseOrderLine> lines
) {
    public List<PurchaseOrderLine> lines() {
        return lines == null ? List.of() : lines;
    }
}
