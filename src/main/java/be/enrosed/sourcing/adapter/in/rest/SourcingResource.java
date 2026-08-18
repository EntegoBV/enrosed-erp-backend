package be.enrosed.sourcing.adapter.in.rest;

import be.enrosed.sourcing.adapter.out.document.PdfPurchaseRenderer;
import be.enrosed.sourcing.application.PurchaseOrderService;
import be.enrosed.sourcing.application.SupplierService;
import be.enrosed.sourcing.domain.LandedCost;
import be.enrosed.sourcing.domain.PurchaseOrder;
import be.enrosed.sourcing.domain.Supplier;
import be.enrosed.shared.security.AdminIdentityProvider;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@Path("/api")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed(AdminIdentityProvider.ADMIN_ROLE)
public class SourcingResource {

    private final SupplierService suppliers;
    private final PurchaseOrderService purchaseOrders;
    private final PdfPurchaseRenderer purchasePdf;

    public SourcingResource(SupplierService suppliers, PurchaseOrderService purchaseOrders,
                            PdfPurchaseRenderer purchasePdf) {
        this.suppliers = suppliers;
        this.purchaseOrders = purchaseOrders;
        this.purchasePdf = purchasePdf;
    }

    public record CreatePurchaseOrder(long supplierId, BigDecimal cnyToUsd, BigDecimal usdToEur,
                                      BigDecimal defaultDutyRatePct) {}

    public record PurchaseOrderView(PurchaseOrder order, LandedCost costing,
                                    List<PurchaseOrderService.CartonAdjustment> adjustments) {}

    /* ------------------------------------------------------ leveranciers */

    @GET
    @Path("/suppliers")
    public List<Supplier> listSuppliers() {
        return suppliers.list();
    }

    @POST
    @Path("/suppliers")
    public Response createSupplier(Supplier supplier) {
        return Response.status(Response.Status.CREATED).entity(suppliers.save(supplier)).build();
    }

    @PUT
    @Path("/suppliers/{id}")
    public Supplier updateSupplier(@PathParam("id") long id, Supplier supplier) {
        suppliers.get(id);
        return suppliers.save(new Supplier(id, supplier.name(), supplier.country(), supplier.city(),
                supplier.contact(), supplier.email(), supplier.phone(), supplier.currency(),
                supplier.incoterm(), supplier.portOfLoading(), supplier.leadTimeDays(), supplier.notes()));
    }

    @DELETE
    @Path("/suppliers/{id}")
    public Response deleteSupplier(@PathParam("id") long id) {
        suppliers.delete(id);
        return Response.noContent().build();
    }

    @GET
    @Path("/suppliers/{id}/usage")
    public Map<String, Long> supplierUsage(@PathParam("id") long id) {
        return Map.of("products", suppliers.productCount(id));
    }

    /* ------------------------------------------------------ inkooporders */

    @GET
    @Path("/purchase-orders")
    public List<PurchaseOrderView> listPurchaseOrders() {
        return purchaseOrders.list().stream()
                .map(order -> new PurchaseOrderView(order, purchaseOrders.calculate(order), List.of()))
                .toList();
    }

    @GET
    @Path("/purchase-orders/{id}")
    public PurchaseOrderView getPurchaseOrder(@PathParam("id") long id) {
        PurchaseOrder order = purchaseOrders.get(id);
        return new PurchaseOrderView(order, purchaseOrders.calculate(order), List.of());
    }

    @POST
    @Path("/purchase-orders")
    public Response createPurchaseOrder(CreatePurchaseOrder request) {
        PurchaseOrder created = purchaseOrders.create(request.supplierId(), request.cnyToUsd(),
                request.usdToEur(), request.defaultDutyRatePct());
        return Response.status(Response.Status.CREATED)
                .entity(new PurchaseOrderView(created, purchaseOrders.calculate(created), List.of()))
                .build();
    }

    @PUT
    @Path("/purchase-orders/{id}")
    public PurchaseOrderView updatePurchaseOrder(@PathParam("id") long id, PurchaseOrder order) {
        PurchaseOrderService.UpdateResult result = purchaseOrders.update(id, order);
        return new PurchaseOrderView(result.order(), purchaseOrders.calculate(result.order()),
                result.adjustments());
    }

    @DELETE
    @Path("/purchase-orders/{id}")
    public Response deletePurchaseOrder(@PathParam("id") long id) {
        purchaseOrders.delete(id);
        return Response.noContent().build();
    }

    /**
     * The calculation as a PDF.
     *
     * @param showRevenue whether the desired extra revenue appears as its own
     *                    line on the sheet. Off by default: the sheet is then
     *                    safe to show, and the total still holds. The screen
     *                    sends the state of the double-tap switch here.
     */
    @GET
    @Path("/purchase-orders/{id}/pdf")
    @Produces("application/pdf")
    public Response purchasePdf(@PathParam("id") long id,
                                @QueryParam("showRevenue") @DefaultValue("false") boolean showRevenue) {
        PurchaseOrder order = purchaseOrders.get(id);
        String supplierName = order.supplierId() == null ? null : suppliers.get(order.supplierId()).name();

        PdfPurchaseRenderer.Document document = purchasePdf.render(
                order, purchaseOrders.calculate(order), supplierName, showRevenue);

        return Response.ok(document.content())
                .header("Content-Disposition",
                        "attachment; filename=\"" + document.filename() + "\"")
                .build();
    }

    /** Copies the calculation to price a variant quickly. */
    @POST
    @Path("/purchase-orders/{id}/duplicate")
    public PurchaseOrderView duplicatePurchaseOrder(@PathParam("id") long id) {
        PurchaseOrder copy = purchaseOrders.duplicate(id);
        return new PurchaseOrderView(copy, purchaseOrders.calculate(copy), List.of());
    }

    /** Writes the calculated cost prices onto the products. */
    @POST
    @Path("/purchase-orders/{id}/apply")
    public LandedCost applyToProducts(@PathParam("id") long id) {
        return purchaseOrders.applyToProducts(id);
    }
}
