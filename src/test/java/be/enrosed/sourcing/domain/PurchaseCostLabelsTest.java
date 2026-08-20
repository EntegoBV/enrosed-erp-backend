package be.enrosed.sourcing.domain;

import be.enrosed.shared.Currency;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PurchaseCostLabelsTest {

    @Test
    void namesEveryCostLegFromSupplierAndOrder() {
        Supplier supplier = new Supplier(1L, "Factory", "CN", "Guangzhou",
                null, null, null, Currency.CNY, "FOB", "Shenzhen", 30, null);

        PurchaseCostLabels labels = PurchaseCostLabels.forOrder(order("Antwerpen"), supplier);

        assertEquals("China", labels.originCountry());
        assertEquals("Lokale kosten China", labels.originCostsLabel());
        assertEquals("Fabriek → Shenzhen", labels.originRoute());
        assertEquals("Shenzhen → Antwerpen", labels.seaFreightRoute());
        assertEquals("Antwerpen → magazijn", labels.destinationCostsLabel());
    }

    @Test
    void supplierCityAndSafeWordsCoverMissingRouteData() {
        Supplier cityOnly = new Supplier(1L, "Factory", "VN", "Da Nang",
                null, null, null, Currency.USD, null, null, 20, null);
        PurchaseCostLabels withCity = PurchaseCostLabels.forOrder(order("Rotterdam"), cityOnly);
        PurchaseCostLabels missing = PurchaseCostLabels.forOrder(null, null);

        assertEquals("Da Nang", withCity.loadingPort());
        assertEquals("Da Nang → Rotterdam", withCity.seaFreightRoute());
        assertEquals("Lokale kosten land van oorsprong", missing.originCostsLabel());
        assertEquals("Fabriek → laadhaven", missing.originRoute());
        assertEquals("laadhaven → Rotterdam", missing.seaFreightRoute());
    }

    private static PurchaseOrder order(String destinationPort) {
        return new PurchaseOrder(1L, "PO-LABEL", null, 1L, LocalDate.now(),
                PurchaseOrderStatus.CONCEPT, ContainerType.FORTY_HQ,
                new BigDecimal("0.14"), new BigDecimal("0.90"), new BigDecimal("0.90"),
                BigDecimal.ZERO, BigDecimal.ZERO, Currency.USD, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO,
                Allocation.CBM, Allocation.CBM, Allocation.CBM, Allocation.PIECES,
                destinationPort, null, List.of());
    }
}
