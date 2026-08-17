package be.enrosed.sales.adapter.in.rest;

import be.enrosed.catalog.application.ProductService;
import be.enrosed.catalog.domain.Photo;
import be.enrosed.catalog.domain.Product;
import be.enrosed.sales.application.CustomerService;
import be.enrosed.sales.application.QuoteService;
import be.enrosed.sales.application.SalesOrderService;
import be.enrosed.sales.application.port.out.QuoteDocumentRenderer;
import be.enrosed.sales.domain.*;
import be.enrosed.shared.DocumentText;
import be.enrosed.shared.Language;
import jakarta.annotation.security.PermitAll;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * De klantkant van de offerte.
 *
 * Bereikbaar met alleen een portaltoken, zonder account: wie de link uit de
 * mail heeft mag de offerte zien en erop reageren.
 *
 * Let op wat hier <b>niet</b> uitgaat: kostprijs, marge en het verschil tussen
 * strikte en gemengde pallets blijven binnen. De klant krijgt zijn eigen
 * weergave, niet de onze met een filter erop.
 */
@Path("/api/portal")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@PermitAll
public class PortalResource {

    private final QuoteService quotes;
    private final SalesOrderService salesOrders;
    private final CustomerService customers;
    private final ProductService products;

    public PortalResource(QuoteService quotes, SalesOrderService salesOrders,
                          CustomerService customers, ProductService products) {
        this.quotes = quotes;
        this.salesOrders = salesOrders;
        this.customers = customers;
        this.products = products;
    }

    /* ------------------------------------------------------------ DTOs */

    public record CustomerLine(
            Long productId, String sku, String description, String photoUrl,
            int quantity, int cartons, int pallets,
            /** Doosinhoud, zodat het portaal aantallen op volle dozen kan afronden. */
            int piecesPerCarton,
            BigDecimal unitPrice, BigDecimal discountPct, BigDecimal net,
            /* Levering. Het exacte voorraadaantal blijft binnen; de klant hoeft
               alleen te weten of het leverbaar is en wanneer. */
            boolean inStock, String deliveryDate, String deliveryWeek) {}

    public record CustomerTotals(
            int pieces, int cartons, int pallets,
            BigDecimal subtotal, BigDecimal orderDiscountPercent, BigDecimal orderDiscountAmount,
            BigDecimal extraDiscountPercent, String extraDiscountLabel, BigDecimal extraDiscountAmount,
            BigDecimal goodsTotal, BigDecimal freight, BigDecimal handling,
            BigDecimal total, BigDecimal vatRatePct, BigDecimal vatAmount, BigDecimal totalInclVat,
            String vatTreatment, String vatLegalMention) {}

    /**
     * Wat de klant ziet. Bewust een eigen record: interne notities, kostprijs en
     * marge zitten er niet in, ook niet als iemand ze later aan SalesOrder
     * toevoegt.
     */
    public record QuoteView(
            String number, String status, String orderDate, String validUntil, String incoterm,
            String notes, String companyName, String contactName, String countryCode,
            List<CustomerLine> lines, CustomerTotals totals,
            boolean canRespond, String signedByName, List<PendingProposal> proposals,
            /**
             * Stand van de levertermijnen: VOLLEDIG, TE_BEPALEN of AANGEVULD. Bij
             * AANGEVULD krijgt de klant bovenaan te zien dat wij de termijn die hij
             * miste hebben ingevuld.
             */
            String deliveryTerms,
            /** Stand van de vracht: BEREKEND, TE_BEPALEN of AANGEVULD. */
            String freight,
            /** Taalcode van de klant, zodat het portaal in zijn taal opent. */
            String language,
            /**
             * De vertaalde teksten voor dit portaal.
             *
             * Ze komen van de server in plaats van uit de Angular-bundel: dan
             * staan de offerte, de PDF, de mail en dit scherm gegarandeerd in
             * dezelfde woorden, en hoeft een nieuwe taal maar op één plaats
             * toegevoegd te worden.
             */
            Map<String, String> text) {}

    public record PendingProposal(String status, String proposedAt, String message, String responseMessage) {}

    /** Product dat de klant kan bijbestellen; zonder kostprijs, uiteraard. */
    public record CatalogItem(Long productId, String sku, String description, String photoUrl,
                              int piecesPerCarton, BigDecimal unitPrice,
                              /* Leverbaar uit voorraad, of moeten we het eerst bestellen? */
                              boolean inStock) {}

    public record AcceptRequest(String signedByName, String message) {}
    public record RejectRequest(String message) {}
    public record ProposalLine(Long productId, int quantity, String note) {}
    public record ProposeRequest(String proposedBy, String message, List<ProposalLine> lines) {}

    /**
     * Wat de klant er nog bij kan zetten.
     *
     * Het token is hier de sleutel: alleen wie de offertelink heeft ziet deze
     * lijst, en dan nog zonder kostprijs of marge.
     */
    @GET
    @Path("/{token}/products")
    public List<CatalogItem> catalog(@PathParam("token") String token) {
        SalesOrder order = quotes.byToken(token);
        return products.list().stream()
                .filter(Product::active)
                .map(product -> new CatalogItem(
                        product.id(),
                        product.sku(),
                        product.describe(),
                        product.primaryPhoto() == null ? null
                                : "/api/portal/" + token + "/products/" + product.id() + "/photo",
                        product.carton() == null ? 1 : product.carton().piecesPerCarton(),
                        salesOrders.unitPriceFor(product, order),
                        product.stockQuantity() > 0))
                .toList();
    }

    /** Productfoto voor het portaal; bereikbaar met het token in plaats van een aanmelding. */
    @GET
    @Path("/{token}/products/{productId}/photo")
    @Produces(MediaType.WILDCARD)
    public Response photo(@PathParam("token") String token, @PathParam("productId") long productId) {
        quotes.byToken(token);
        Photo photo = products.get(productId).primaryPhoto();
        if (photo == null) return Response.status(Response.Status.NOT_FOUND).build();
        return Response.ok(products.photoData(photo.storageKey()))
                .type(photo.contentType() == null ? MediaType.APPLICATION_OCTET_STREAM : photo.contentType())
                .header("Cache-Control", "public, max-age=86400")
                .build();
    }

    /* ---------------------------------------------------------- lezen */

    @GET
    @Path("/{token}")
    public QuoteView open(@PathParam("token") String token,
                          @QueryParam("language") String language) {
        SalesOrder order = quotes.openByToken(token);
        return view(order, language);
    }

    @GET
    @Path("/{token}/pdf")
    @Produces("application/pdf")
    public Response pdf(@PathParam("token") String token) {
        SalesOrder order = quotes.byToken(token);
        QuoteDocumentRenderer.Document document = quotes.document(order.id());
        return Response.ok(document.content())
                .header("Content-Disposition", "attachment; filename=\"" + document.filename() + "\"")
                .build();
    }

    /* -------------------------------------------------------- reageren */

    /** De klant tekent voor akkoord; de ingetikte naam is de handtekening. */
    @POST
    @Path("/{token}/accept")
    public QuoteView accept(@PathParam("token") String token, AcceptRequest request) {
        return view(quotes.acceptByCustomer(token,
                request == null ? null : request.signedByName(),
                request == null ? null : request.message()));
    }

    @POST
    @Path("/{token}/reject")
    public QuoteView reject(@PathParam("token") String token, RejectRequest request) {
        return view(quotes.rejectByCustomer(token, request == null ? null : request.message()));
    }

    /**
     * De klant trekt zijn voorstel weer in.
     *
     * Het voorstel blijft in de geschiedenis staan; alleen ligt de offerte weer
     * bij hem in plaats van bij ons.
     */
    @POST
    @Path("/{token}/withdraw")
    public QuoteView withdraw(@PathParam("token") String token) {
        return view(quotes.withdrawRevision(token));
    }

    /**
     * De klant stelt wijzigingen voor. Dit verandert de offerte niet - het is
     * een voorstel dat bij ons ter goedkeuring komt.
     */
    @POST
    @Path("/{token}/propose")
    public QuoteView propose(@PathParam("token") String token, ProposeRequest request) {
        List<QuoteRevision.Line> lines = request == null || request.lines() == null
                ? List.of()
                : request.lines().stream()
                        .map(line -> new QuoteRevision.Line(null, line.productId(), line.quantity(), line.note()))
                        .toList();

        quotes.proposeRevision(token, lines,
                request == null ? null : request.proposedBy(),
                request == null ? null : request.message());

        return view(quotes.byToken(token));
    }

    /* --------------------------------------------------------- mapping */

    /**
     * Doosinhoud van een product.
     *
     * Bewust opgezocht en niet afgeleid uit aantal gedeeld door dozen: dat klopt
     * alleen zolang het aantal een volle doos is, en juist bij de regels die dat
     * niet zijn heb je het getal nodig.
     */
    private int piecesPerCarton(Long productId) {
        if (productId == null) return 1;
        try {
            int per = products.get(productId).carton().piecesPerCarton();
            return Math.max(1, per);
        } catch (RuntimeException e) {
            return 1;
        }
    }

    private QuoteView view(SalesOrder order) {
        return view(order, null);
    }

    /**
     * @param preferred taal die de klant zelf koos in het portaal, of null voor de
     *                  taal die bij hem hoort. Zijn keuze verandert niets aan de
     *                  klantfiche: de volgende offerte vertrekt gewoon weer in de
     *                  taal die wij afgesproken hebben. Wie in Frankrijk zit maar
     *                  liever Engels leest hoeft daarvoor niet te bellen.
     */
    private QuoteView view(SalesOrder order, String preferred) {
        PricedOrder priced = salesOrders.price(order);
        Customer customer = order.customerId() == null ? null : customers.get(order.customerId());
        Language language = preferred != null && !preferred.isBlank()
                ? Language.of(preferred)
                : customer == null ? Language.NL : customer.language();

        List<CustomerLine> lines = priced.lines().stream()
                .map(line -> new CustomerLine(
                        line.productId(), line.sku(), line.customerDescription(), line.photoUrl(),
                        line.quantity(), line.cartons(), line.pallets(),
                        piecesPerCarton(line.productId()),
                        line.unitPrice(), line.discountPct(), line.net(),
                        line.inStock(), line.deliveryDate(), line.deliveryWeek()))
                .toList();

        PricedOrder.Totals totals = priced.totals();
        CustomerTotals customerTotals = new CustomerTotals(
                totals.pieces(), totals.cartons(), totals.palletsStrict(),
                totals.subtotal(), totals.orderDiscountPercent(), totals.orderDiscountAmount(),
                totals.extraDiscountPercent(), totals.extraDiscountLabel(), totals.extraDiscountAmount(),
                totals.goodsTotal(), totals.freight(), totals.handling(),
                totals.total(), totals.vatRatePct(), totals.vatAmount(), totals.totalInclVat(),
                totals.vatTreatment().labelIn(language),
                totals.vatTreatment().legalMentionIn(language));

        List<PendingProposal> proposals = quotes.revisionsFor(order.id()).stream()
                .map(revision -> new PendingProposal(
                        revision.status().name(),
                        revision.proposedAt() == null ? null : revision.proposedAt().toString(),
                        revision.message(), revision.responseMessage()))
                .toList();

        return new QuoteView(
                order.number(), order.status().name(),
                String.valueOf(order.orderDate()), String.valueOf(order.validUntil()),
                order.incoterm(), order.notes(),
                customer == null ? null : customer.company(),
                customer == null ? null : customer.contact(),
                order.countryCode(),
                lines, customerTotals,
                order.status().isOpenForCustomer(), order.signedByName(), proposals,
                order.deliveryTerms().name(),
                order.freight().name(),
                language.code(),
                DocumentText.of(language));
    }
}
