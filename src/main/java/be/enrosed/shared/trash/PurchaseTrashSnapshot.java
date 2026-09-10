package be.enrosed.shared.trash;

import be.enrosed.catalog.application.ProductService;
import be.enrosed.catalog.application.StockService;
import be.enrosed.catalog.domain.Product;
import be.enrosed.sales.application.CustomerService;
import be.enrosed.shared.NotFoundException;
import be.enrosed.shared.trash.DeletedItemDtos.*;
import be.enrosed.sourcing.application.LandedCostCalculator;
import be.enrosed.sourcing.application.port.out.SourcingRepositories;
import be.enrosed.sourcing.domain.LandedCost;
import be.enrosed.sourcing.domain.PurchaseOrder;
import jakarta.enterprise.context.ApplicationScoped;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** A reviewable snapshot at deletion time; restoring never recalculates or posts this data. */
@ApplicationScoped
public class PurchaseTrashSnapshot {
    private final SourcingRepositories.Suppliers suppliers;
    private final SourcingRepositories.Documents documents;
    private final ProductService products;
    private final CustomerService customers;
    private final StockService stock;
    private final LandedCostCalculator calculator;

    public PurchaseTrashSnapshot(SourcingRepositories.Suppliers suppliers, SourcingRepositories.Documents documents,
                                 ProductService products, CustomerService customers, StockService stock,
                                 LandedCostCalculator calculator) {
        this.suppliers = suppliers;
        this.documents = documents;
        this.products = products;
        this.customers = customers;
        this.stock = stock;
        this.calculator = calculator;
    }

    public String partyName(PurchaseOrder order) {
        return order.supplierId() == null ? "Onbekende leverancier" : suppliers.findById(order.supplierId())
                .map(supplier -> supplier.name()).orElse("Leverancier #" + order.supplierId());
    }

    public Snapshot snapshot(PurchaseOrder order) {
        Map<Long, Product> catalog = products.list().stream().collect(Collectors.toMap(Product::id, Function.identity()));
        boolean complete = positive(order.cnyToUsd()) && positive(order.usdToEurGoods())
                && order.lines().stream().allMatch(line -> {
                    Product product = catalog.get(line.productId());
                    return product != null && (line.exwPrice() != null || product.exwPrice() != null);
                });
        LandedCost costing = complete ? calculator.calculate(order, catalog) : null;
        Map<Long, LandedCost.Line> costs = costing == null ? Map.of() : costing.lines().stream()
                .collect(Collectors.toMap(LandedCost.Line::productId, Function.identity(), (first, last) -> first));
        List<Line> lines = order.lines().stream().map(line -> {
            Product product = catalog.get(line.productId());
            LandedCost.Line cost = costs.get(line.productId());
            return new Line(product == null ? "Product #" + line.productId() : product.name(),
                    product == null ? null : product.sku(), BigDecimal.valueOf(line.quantity()), "stuks",
                    cost == null ? null : cost.landedUnitEur(), cost == null ? null : cost.totalEur());
        }).toList();
        List<Field> fields = new ArrayList<>();
        add(fields, "Nummer", order.number());
        add(fields, "Naam", order.alias());
        add(fields, "Leverancier", partyName(order));
        add(fields, "Orderdatum", order.orderDate());
        add(fields, "Status", order.status());
        add(fields, "Container", order.containerType() == null ? null : order.containerType().code());
        add(fields, "Verwachte aankomst", order.expectedArrival());
        add(fields, "Vertrokken op", order.shippedOn());
        add(fields, "Trackingreferentie", order.trackingReference());
        add(fields, "USD/EUR goederen", order.usdToEurGoods());
        add(fields, "USD/EUR transport", order.usdToEurTransport());
        add(fields, "CNY/USD", order.cnyToUsd());
        add(fields, "Partnerfinanciering (%)", order.partnerCostPct());
        add(fields, "Winstdeling (%)", order.partnerSharePct());
        if (costing != null) {
            add(fields, "Goederen (EUR)", costing.totals().goodsEur());
            add(fields, "Vertrekkosten (EUR)", costing.totals().originEur());
            add(fields, "Zeevracht (EUR)", costing.totals().freightEur());
            add(fields, "Invoerrechten (EUR)", costing.totals().dutyEur());
            add(fields, "Bestemmingskosten (EUR)", costing.totals().destinationEur());
            add(fields, "Enrosed kost (EUR)", costing.totals().extraRevenueEur());
            add(fields, "Inspectie en andere kosten (EUR)", costing.totals().separateCostsEur());
            add(fields, "Inspectie/andere kosten in stukprijs", costing.totals().separateCostsInPiecePrice() ? "Ja" : "Nee");
        }
        var attachments = documents.forOrder(order.id()).stream()
                .map(document -> new Attachment(document.id(), document.originalFilename(), null)).toList();
        return new Snapshot(List.copyOf(fields), lines, order.notes(), attachments,
                costing == null ? null : costing.totals().totalWithSeparateCostsEur());
    }

    /** Only dependency checks: incomplete drafts remain recoverable without replaying business actions. */
    public String validateRestore(PurchaseOrder order) {
        if (order == null) return "De bewaarde inkooporder ontbreekt";
        if (order.supplierId() == null || suppliers.findById(order.supplierId()).isEmpty())
            return "De leverancier van deze inkooporder bestaat niet meer";
        for (var line : order.lines()) {
            if (line.productId() == null) continue; // Preserve an unfinished historical draft as it was.
            try { products.get(line.productId()); }
            catch (NotFoundException missing) { return "Een product van deze inkooporder bestaat niet meer (" + line.productId() + ")"; }
        }
        if (order.partnerCustomerId() != null) {
            try { customers.get(order.partnerCustomerId()); }
            catch (NotFoundException missing) { return "De partner van deze inkooporder bestaat niet meer"; }
        }
        if (order.receivingLocationId() != null) {
            try { stock.location(order.receivingLocationId()); }
            catch (NotFoundException missing) { return "De ontvangstlocatie van deze inkooporder bestaat niet meer"; }
        }
        return null;
    }

    private static boolean positive(BigDecimal value) { return value != null && value.signum() > 0; }
    private static void add(List<Field> fields, String label, Object value) {
        if (value != null && !value.toString().isBlank()) fields.add(new Field(label, value.toString()));
    }
}
