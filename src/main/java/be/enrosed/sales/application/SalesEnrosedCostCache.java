package be.enrosed.sales.application;

import be.enrosed.catalog.application.ProductCostHistoryService;
import be.enrosed.catalog.domain.StockBatchSaleCost;
import jakarta.enterprise.context.RequestScoped;
import java.util.Map;

/** The cost-history parts and every shipment's batch takes, read once for a request that values many documents. */
@RequestScoped
public class SalesEnrosedCostCache {
    private ProductCostHistoryService.EnrosedParts parts;
    private Map<Long, Map<Long, StockBatchSaleCost>> takes;

    /** Null while nothing was preloaded. */
    public ProductCostHistoryService.EnrosedParts parts() { return parts; }
    public Map<Long, Map<Long, StockBatchSaleCost>> takes() { return takes; }
    public void fill(ProductCostHistoryService.EnrosedParts parts, Map<Long, Map<Long, StockBatchSaleCost>> takes) {
        this.parts = parts;
        this.takes = takes;
    }
}
