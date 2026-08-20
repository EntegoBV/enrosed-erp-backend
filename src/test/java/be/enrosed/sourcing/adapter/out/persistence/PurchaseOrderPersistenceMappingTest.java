package be.enrosed.sourcing.adapter.out.persistence;

import be.enrosed.shared.Currency;
import be.enrosed.sourcing.adapter.out.persistence.SourcingEntities.PurchaseOrderEntity;
import be.enrosed.sourcing.domain.Allocation;
import be.enrosed.sourcing.domain.PurchaseOrder;
import be.enrosed.sourcing.domain.PurchaseOrderStatus;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PurchaseOrderPersistenceMappingTest {

    @Test
    void historicalDistinctExchangeRatesSurviveDatabaseRead() {
        PurchaseOrderEntity entity = new PurchaseOrderEntity();
        entity.id = 1L;
        entity.number = "PO-HISTORY";
        entity.supplierId = 7L;
        entity.orderDate = LocalDate.of(2025, 1, 2);
        entity.status = PurchaseOrderStatus.BESTELD;
        entity.containerType = "40HQ";
        entity.cnyToUsd = new BigDecimal("0.14");
        entity.usdToEurGoods = new BigDecimal("0.81");
        entity.usdToEurTransport = new BigDecimal("0.93");
        entity.originCurrency = Currency.USD;
        entity.allocFreight = Allocation.CBM;
        entity.allocOrigin = Allocation.CBM;
        entity.allocDestination = Allocation.CBM;
        entity.allocExtra = Allocation.PIECES;

        PurchaseOrder restored = PanacheSourcingRepositories.PurchaseOrderAdapter.toDomain(entity);

        assertEquals(new BigDecimal("0.81"), restored.usdToEurGoods());
        assertEquals(new BigDecimal("0.93"), restored.usdToEurTransport());
    }
}
