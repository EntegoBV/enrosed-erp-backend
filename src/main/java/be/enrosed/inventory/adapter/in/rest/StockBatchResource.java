package be.enrosed.inventory.adapter.in.rest;

import be.enrosed.catalog.application.StockBatchService;
import be.enrosed.inventory.application.StockBatchBackfillService;
import be.enrosed.inventory.application.StockBatchBackfillService.Report;
import be.enrosed.inventory.application.StockBatchViews;
import be.enrosed.inventory.application.StockBatchViews.CheckRow;
import be.enrosed.inventory.application.StockBatchViews.Moves;
import be.enrosed.inventory.application.StockBatchViews.ProductBatches;
import be.enrosed.inventory.application.StockBatchViews.PurchaseBatches;
import be.enrosed.inventory.application.StockBatchViews.SaleCost;
import be.enrosed.inventory.application.StockBatchViews.Status;
import be.enrosed.shared.BusinessRuleException;
import be.enrosed.shared.security.AdminIdentityProvider;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

import java.util.List;

/**
 * Stock batches: the status of the start, the batches of a product, a container and a shipment, the check with its
 * repair, and "Batches opbouwen" (dry run first). The reset has no button; it needs {"confirm":"RESET"}.
 */
@Path("/api/stock-batches")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed(AdminIdentityProvider.ADMIN_ROLE)
public class StockBatchResource {

    private final StockBatchViews views;
    private final StockBatchBackfillService backfill;
    private final StockBatchService batches;

    public StockBatchResource(StockBatchViews views, StockBatchBackfillService backfill, StockBatchService batches) {
        this.views = views;
        this.backfill = backfill;
        this.batches = batches;
    }

    public record RepairBody(List<Long> productIds) {}

    public record ResetBody(String confirm) {}

    @GET
    @Path("/status")
    public Status status() {
        return views.status();
    }

    /** "Pauzeren": every stock hook stops at once, stock work goes on. */
    @POST
    @Path("/pause")
    public Status pause() {
        batches.pause();
        return views.status();
    }

    /** "Hervatten": hooks write again; the drift that built up shows in the check. */
    @POST
    @Path("/resume")
    public Status resume() {
        batches.resume();
        return views.status();
    }

    /** Removes all batch data and the sales stock cost stamps; only with {"confirm":"RESET"}. */
    @POST
    @Path("/reset")
    public Status reset(ResetBody body) {
        if (body == null || !"RESET".equals(body.confirm())) throw new BusinessRuleException("Bevestig met RESET.");
        backfill.reset();
        return views.status();
    }

    @GET
    @Path("/products/{productId}")
    public ProductBatches product(@PathParam("productId") long productId) {
        return views.forProduct(productId);
    }

    @GET
    @Path("/{batchId}/moves")
    public Moves moves(@PathParam("batchId") long batchId, @QueryParam("limit") @DefaultValue("50") int limit) {
        return views.moves(batchId, limit);
    }

    @GET
    @Path("/purchase-orders/{purchaseOrderId}")
    public PurchaseBatches purchaseOrder(@PathParam("purchaseOrderId") long purchaseOrderId) {
        return views.forPurchaseOrder(purchaseOrderId);
    }

    @GET
    @Path("/sales-orders/{salesOrderId}")
    public SaleCost salesOrder(@PathParam("salesOrderId") long salesOrderId) {
        return views.forSalesOrder(salesOrderId);
    }

    @GET
    @Path("/check")
    public List<CheckRow> check() {
        return views.check();
    }

    /** "Herstellen": the named products (all differences when none) in one transaction, then the new check. */
    @POST
    @Path("/check/repair")
    public List<CheckRow> repair(RepairBody body) {
        return views.repair(body == null ? List.of() : body.productIds());
    }

    /** "Batches opbouwen": a dry run unless dryRun=false. */
    @POST
    @Path("/backfill")
    public Report backfill(@QueryParam("dryRun") @DefaultValue("true") boolean dryRun) {
        return dryRun ? backfill.dryRun() : backfill.start();
    }
}
