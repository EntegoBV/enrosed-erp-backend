package be.enrosed.sourcing.adapter.in.rest;

import be.enrosed.sourcing.adapter.out.document.PdfPurchaseRenderer;
import be.enrosed.sourcing.application.PurchaseOrderService;
import be.enrosed.sourcing.application.SupplierService;
import be.enrosed.sourcing.domain.LandedCost;
import be.enrosed.sourcing.domain.PurchaseOrder;
import be.enrosed.sourcing.domain.PurchaseDocument;
import be.enrosed.sourcing.domain.PurchasePayment;
import be.enrosed.sourcing.domain.PurchaseCostLabels;
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
                                    List<PurchaseOrderService.CartonAdjustment> adjustments,
                                    PurchaseCostLabels costLabels,
                                    /** Who is owed what: supplier, road, and our own share. */
                                    PurchaseOrderService.Payable payable,
                                    /** What the order waits on from us, in words; empty when nothing. */
                                    List<String> attention) {}

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
        return suppliers.save(supplier.withId(id));
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
                .map(order -> view(order, purchaseOrders.calculate(order), List.of()))
                .toList();
    }

    @GET
    @Path("/purchase-orders/{id}")
    public PurchaseOrderView getPurchaseOrder(@PathParam("id") long id) {
        PurchaseOrder order = purchaseOrders.get(id);
        return view(order, purchaseOrders.calculate(order), List.of());
    }

    @POST
    @Path("/purchase-orders")
    public Response createPurchaseOrder(CreatePurchaseOrder request) {
        PurchaseOrder created = purchaseOrders.create(request.supplierId(), request.cnyToUsd(),
                request.usdToEur(), request.defaultDutyRatePct());
        return Response.status(Response.Status.CREATED)
                .entity(view(created, purchaseOrders.calculate(created), List.of()))
                .build();
    }

    @PUT
    @Path("/purchase-orders/{id}")
    public PurchaseOrderView updatePurchaseOrder(@PathParam("id") long id, PurchaseOrder order) {
        PurchaseOrderService.UpdateResult result = purchaseOrders.update(id, order);
        return view(result.order(), purchaseOrders.calculate(result.order()), result.adjustments());
    }

    /**
     * The calculation for an order as it stands on screen, without saving:
     * the editor keeps a draft and only writes on Opslaan, but the figures
     * must follow every keystroke.
     */
    @POST
    @Path("/purchase-orders/{id}/preview")
    public PurchaseOrderView previewPurchaseOrder(@PathParam("id") long id, PurchaseOrder order) {
        purchaseOrders.get(id);
        PurchaseOrder draft = order.id() == null || order.id() != id ? withId(order, id) : order;
        return view(draft, purchaseOrders.calculate(draft), List.of());
    }

    private static PurchaseOrder withId(PurchaseOrder o, long id) {
        return new PurchaseOrder(id, o.number(), o.alias(), o.supplierId(), o.orderDate(), o.status(),
                o.containerType(), o.cnyToUsd(), o.usdToEurGoods(), o.usdToEurTransport(), o.freightUsd(),
                o.originCosts(), o.originCurrency(), o.destinationCostsEur(), o.defaultDutyRatePct(),
                o.extraRevenueEur(), o.allocFreight(), o.allocOrigin(), o.allocDestination(), o.allocExtra(),
                o.departurePort(), o.destinationPort(), o.receivingLocationId(), o.groupVariants(), o.notes(),
                o.lines());
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
        Supplier supplier = order.supplierId() == null ? null : suppliers.find(order.supplierId());
        LandedCost costing = purchaseOrders.calculate(order);

        PdfPurchaseRenderer.Document document = purchasePdf.render(
                order, costing, supplier, showRevenue,
                purchaseOrders.payments(id),
                purchaseOrders.payable(order, costing,
                        supplier == null ? null : supplier.incoterm()));

        return Response.ok(document.content())
                .header("Content-Disposition",
                        "attachment; filename=\"" + document.filename() + "\"")
                .build();
    }

    /** Copies the calculation to price a variant quickly. */
    /** The container is in: counts, damage, payment, and optionally the booking. */
    @POST
    @Path("/purchase-orders/{id}/receive")
    public PurchaseOrderView receive(@PathParam("id") long id, PurchaseOrderService.Receipt receipt) {
        PurchaseOrder order = purchaseOrders.receive(id,
                receipt == null ? new PurchaseOrderService.Receipt(List.of(), true, null, null, null) : receipt);
        return view(order, purchaseOrders.calculate(order), List.of());
    }

    /** Books the usable pieces of a received container into stock - once. */
    @POST
    @Path("/purchase-orders/{id}/book-stock")
    public PurchaseOrderView bookStock(@PathParam("id") long id) {
        PurchaseOrder order = purchaseOrders.bookStock(id);
        return view(order, purchaseOrders.calculate(order), List.of());
    }

    /* ---- payments ---- */

    public record PaymentRequest(java.time.LocalDate paidOn, java.math.BigDecimal amount,
                                 be.enrosed.shared.Currency currency, String label, PurchasePayment.Payee payee) {}

    @GET
    @Path("/purchase-orders/{id}/payments")
    public List<PurchasePayment> payments(@PathParam("id") long id) {
        return purchaseOrders.payments(id);
    }

    @POST
    @Path("/purchase-orders/{id}/payments")
    public Response addPayment(@PathParam("id") long id, PaymentRequest request) {
        if (request == null) throw new be.enrosed.shared.BusinessRuleException("Geef een bedrag op");
        PurchasePayment saved = purchaseOrders.addPayment(id, request.paidOn(), request.amount(),
                request.currency(), request.label(), request.payee());
        return Response.status(Response.Status.CREATED).entity(saved).build();
    }

    @DELETE
    @Path("/purchase-orders/{id}/payments/{paymentId}")
    public Response deletePayment(@PathParam("id") long id, @PathParam("paymentId") long paymentId) {
        purchaseOrders.deletePayment(id, paymentId);
        return Response.noContent().build();
    }

    /* ---- documents ---- */

    public record DocumentDto(Long id, PurchaseDocument.Kind kind, String kindLabel, String label, String originalFilename,
                              String contentType, long sizeBytes, Long paymentId, String actor, java.time.Instant addedAt) {
        static DocumentDto from(PurchaseDocument d) {
            return new DocumentDto(d.id(), d.kind(), d.kind().dutchLabel(), d.label(), d.originalFilename(), d.contentType(),
                    d.sizeBytes(), d.paymentId(), d.actor(), d.addedAt());
        }
    }

    @GET
    @Path("/purchase-orders/{id}/documents")
    public List<DocumentDto> documents(@PathParam("id") long id) {
        return purchaseOrders.documents(id).stream().map(DocumentDto::from).toList();
    }

    @POST
    @Path("/purchase-orders/{id}/documents")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    public Response addDocument(@PathParam("id") long id,
                                @org.jboss.resteasy.reactive.RestForm("file") org.jboss.resteasy.reactive.multipart.FileUpload file,
                                @org.jboss.resteasy.reactive.RestForm("kind") String kind,
                                @org.jboss.resteasy.reactive.RestForm("label") String label,
                                @org.jboss.resteasy.reactive.RestForm("paymentId") Long paymentId) throws java.io.IOException {
        if (file == null) throw new BadRequestException("Geen bestand meegestuurd");
        byte[] bytes = java.nio.file.Files.readAllBytes(file.uploadedFile());
        PurchaseDocument.Kind documentKind;
        try { documentKind = kind == null ? PurchaseDocument.Kind.OTHER : PurchaseDocument.Kind.valueOf(kind); }
        catch (IllegalArgumentException unknown) { documentKind = PurchaseDocument.Kind.OTHER; }
        PurchaseDocument saved = purchaseOrders.addDocument(id, documentKind, label, paymentId, file.fileName(),
                file.contentType(), bytes);
        return Response.status(Response.Status.CREATED).entity(DocumentDto.from(saved)).build();
    }

    @GET
    @Path("/purchase-orders/{id}/documents/{documentId}/file")
    @Produces(MediaType.WILDCARD)
    public Response documentFile(@PathParam("id") long id, @PathParam("documentId") long documentId) {
        PurchaseDocument document = purchaseOrders.document(id, documentId);
        String name = document.originalFilename().replace("\"", "");
        return Response.ok(purchaseOrders.documentData(document))
                .type(document.contentType())
                .header("Content-Disposition", "attachment; filename=\"" + name + "\"")
                .build();
    }

    public record DocumentLabelRequest(String label) {}

    @PUT
    @Path("/purchase-orders/{id}/documents/{documentId}/label")
    public DocumentDto renameDocument(@PathParam("id") long id, @PathParam("documentId") long documentId,
                                      DocumentLabelRequest request) {
        return DocumentDto.from(purchaseOrders.renameDocument(id, documentId,
                request == null ? null : request.label()));
    }

    @DELETE
    @Path("/purchase-orders/{id}/documents/{documentId}")
    public Response deleteDocument(@PathParam("id") long id, @PathParam("documentId") long documentId) {
        purchaseOrders.deleteDocument(id, documentId);
        return Response.noContent().build();
    }

    /** Pieces on the water, per product: what the catalogue may promise soon. */
    @GET
    @Path("/purchase-orders/expected-stock")
    public List<PurchaseOrderService.ExpectedStock> expectedStock() {
        return purchaseOrders.expectedStock();
    }

    @POST
    @Path("/purchase-orders/{id}/duplicate")
    public PurchaseOrderView duplicatePurchaseOrder(@PathParam("id") long id) {
        PurchaseOrder copy = purchaseOrders.duplicate(id);
        return view(copy, purchaseOrders.calculate(copy), List.of());
    }

    /** Writes the calculated cost prices onto the products. */
    @POST
    @Path("/purchase-orders/{id}/apply")
    public LandedCost applyToProducts(@PathParam("id") long id) {
        return purchaseOrders.applyToProducts(id);
    }

    private PurchaseOrderView view(PurchaseOrder order, LandedCost costing,
                                   List<PurchaseOrderService.CartonAdjustment> adjustments) {
        Supplier supplier = order.supplierId() == null ? null : suppliers.find(order.supplierId());
        PurchaseOrderService.Payable payable = purchaseOrders.payable(order, costing,
                supplier == null ? null : supplier.incoterm());
        return new PurchaseOrderView(order, costing, adjustments,
                PurchaseCostLabels.forOrder(order, supplier), payable, purchaseOrders.attention(order, payable));
    }
}
