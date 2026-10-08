package be.enrosed.sourcing.application;

import be.enrosed.catalog.application.StockService;
import be.enrosed.catalog.domain.Product;
import be.enrosed.catalog.domain.StockMovement;
import be.enrosed.sourcing.domain.LandedCost;
import be.enrosed.sourcing.domain.LotCost;
import be.enrosed.sourcing.domain.PurchaseOrder;
import be.enrosed.sourcing.domain.PurchasePayment;
import be.enrosed.sourcing.domain.PurchaseReconciliation;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Map;

/**
 * Loads what one container's lot cost reads and hands it to the pure
 * calculation. Everything is existing data: nothing is stored at receipt
 * and no purchase table gains a column. Read-only.
 */
@ApplicationScoped
public class LotCostService {

    @Inject
    PurchaseOrderService orders;
    @Inject
    Instance<StockService> stock;

    private final LotCostCalculator calculator = new LotCostCalculator();

    /**
     * @param productsById every product, loaded once per closing run by the caller
     */
    public LotCost.Container forContainer(PurchaseOrder order, LotCost.Options options,
                                          Map<Long, Product> productsById) {
        /* On received quantities: allocation key and informational split only. */
        LandedCost received = orders.calculate(order);
        /* On ordered quantities: only what the supplier is owed, and the CIF transport inside it. */
        PurchaseOrderService.Payable payable = orders.payable(order, orders.calculateForOrderedQuantities(order), null);
        List<PurchasePayment> payments = order.id() == null ? List.of() : orders.payments(order.id());
        /* One call, on the payments at the container rates: its figures only measure what is still owed. */
        PurchaseReconciliation reconciliation = orders.reconciliation(order, received,
                LotCostCalculator.normalised(order, payments));
        List<StockMovement> laterLoss = order.id() == null || stock == null || !stock.isResolvable()
                ? List.of() : stock.get().movementsForPurchaseOrder(order.id());
        return calculator.calculate(new LotCost.Input(order, options, productsById, received,
                payable.supplierEur(), payable.supplierFreightEur(), payments,
                orders.supplierCredits(order.id()), reconciliation.streams(), laterLoss));
    }
}
