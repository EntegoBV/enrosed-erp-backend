package be.enrosed.sales.application;

import be.enrosed.catalog.adapter.out.persistence.ProductEntity;
import be.enrosed.catalog.application.CatalogMutationLock;
import be.enrosed.catalog.domain.OrderStep;
import be.enrosed.catalog.domain.PackagingKind;
import be.enrosed.catalog.domain.SalesUnit;
import be.enrosed.sales.adapter.out.persistence.SalesEntities.SalesOrderEntity;
import be.enrosed.sales.application.port.out.SalesRepositories;
import be.enrosed.sales.domain.*;
import be.enrosed.shared.BusinessRuleException;
import be.enrosed.shared.Currency;
import be.enrosed.shared.Language;
import be.enrosed.sourcing.adapter.out.persistence.SourcingEntities.PurchaseOrderEntity;
import be.enrosed.sourcing.adapter.out.persistence.SourcingEntities.PurchaseOrderLineEntity;
import be.enrosed.sourcing.application.PurchaseOrderService;
import be.enrosed.sourcing.application.SupplierService;
import be.enrosed.sourcing.domain.PurchaseOrder;
import be.enrosed.sourcing.domain.Supplier;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * "Verkocht per" on the paths that write sales quantities: the save, the preview, the portal,
 * a container quote and a delivery split. Each rounds to the product's step and remembers it on
 * the line; exempt documents keep their pieces.
 */
@QuarkusTest
@TestSecurity(user = "emre", roles = "admin")
class SalesOrderStepTest {
    @Inject SalesOrderService sales;
    @Inject QuoteService quotes;
    @Inject SalesSplits splits;
    @Inject be.enrosed.sales.adapter.in.rest.CustomerQuoteMapper customerQuotes;
    @Inject SalesRepositories.Revisions revisions;
    @Inject CustomerService customers;
    @Inject PurchaseOrderService purchases;
    @Inject SupplierService suppliers;
    @Inject CatalogMutationLock catalogLock;
    @Inject EntityManager em;
    @Inject ObjectMapper mapper;

    @Test @TestTransaction
    void aConceptRoundsToTheStepAndASentDocumentNeverMovesWithTheProduct() throws Exception {
        long stolp = product("UNIT", 12);
        SalesOrder quote = quote(stolp, 3);
        assertEquals(3, quote.lines().getFirst().quantity(), "per stolp: three stay three");
        assertEquals(1, quote.lines().getFirst().unitsPerOrderStep(), "the step the line was rounded to");

        /* Emre switches the product back to full cartons of twelve after the quote was accepted and paid. */
        step(stolp, "CARTON");
        for (QuoteStatus sent : List.of(QuoteStatus.GEACCEPTEERD, QuoteStatus.BETAALD)) {
            status(quote.id(), sent);
            PricedOrder priced = sales.price(sales.get(quote.id()));
            assertEquals(3, priced.lines().getFirst().quantity(), sent + " is never re-priced by a step change");
            assertEquals(new BigDecimal("15.00"), priced.totals().goodsTotal());
        }

        /* A concept saved again after the switch takes today's step, as every save does. */
        status(quote.id(), QuoteStatus.CONCEPT);
        SalesOrder resaved = sales.update(quote.id(), echoed(sales.get(quote.id())));
        assertEquals(12, resaved.lines().getFirst().quantity());
        assertEquals(12, resaved.lines().getFirst().unitsPerOrderStep());
    }

    @Test @TestTransaction
    void theStepIsTheServersNeverTheClients() throws Exception {
        long roses = product(null, 12);
        SalesOrder quote = quote(roses, 24);
        ObjectNode sent = mapper.valueToTree(quote);
        ObjectNode line = (ObjectNode) sent.path("lines").get(0);
        line.put("quantity", 3);
        line.put("unitsPerOrderStep", 1);

        SalesOrder previewed = sales.preview(quote.id(), mapper.treeToValue(sent, SalesOrder.class));
        assertEquals(12, previewed.lines().getFirst().unitsPerOrderStep(), "the preview prices with today's step");
        assertEquals(12, sales.price(previewed).lines().getFirst().quantity());

        SalesOrder saved = sales.update(quote.id(), mapper.treeToValue(sent, SalesOrder.class));
        assertEquals(12, saved.lines().getFirst().quantity(), "a client-sent step of one is ignored");
        assertEquals(12, saved.lines().getFirst().unitsPerOrderStep());
    }

    @Test @TestTransaction
    void containerQuotesKeepTheirPiecesAndTheCustomerPriceOneRemembersItsStep() throws Exception {
        Container container = container();

        SalesOrder atCost = sales.createFromPurchaseOrder(request(container, "COST"));
        assertEquals(MarkupMode.CONTAINER_COST, atCost.markupMode());
        assertNull(atCost.lines().getFirst().unitsPerOrderStep(), "an exempt document remembers no step");
        ObjectNode edit = mapper.valueToTree(atCost);
        ((ObjectNode) edit.path("lines").get(0)).put("unitsPerOrderStep", 7);
        SalesOrder kept = sales.update(atCost.id(), mapper.treeToValue(edit, SalesOrder.class));
        assertEquals(99, kept.lines().getFirst().quantity());
        assertNull(kept.lines().getFirst().unitsPerOrderStep(), "and never takes one from the client");
        assertEquals(1, sales.price(kept).lines().getFirst().unitsPerOrderStep());

        SalesOrder atCustomerPrice = sales.createFromPurchaseOrder(request(container, "CUSTOMER"));
        assertEquals(MarkupMode.PRODUCT, atCustomerPrice.markupMode());
        assertEquals(99, atCustomerPrice.lines().getFirst().quantity(), "the container's exact pieces until a save");
        assertEquals(10, atCustomerPrice.lines().getFirst().unitsPerOrderStep(), "the step they will round to");
        assertEquals(100, sales.price(atCustomerPrice).lines().getFirst().quantity());
        assertEquals(100, sales.update(atCustomerPrice.id(), echoed(atCustomerPrice)).lines().getFirst().quantity());
    }

    @Test @TestTransaction
    void thePortalRoundsToTheStepWithTheCalculatorsExemptions() {
        long stolp = product("UNIT", 12);
        long roses = product(null, 12);
        SalesOrder quote = sent(quote(stolp, 3));
        quotes.proposeRevision(quote.portalToken(), List.of(new QuoteRevision.Line(null, stolp, 5, null),
                new QuoteRevision.Line(null, roses, 13, null)), "Klant", null);
        QuoteRevision proposal = revisions.findByOrder(quote.id()).getFirst();
        assertEquals(5, proposal.lines().get(0).quantity(), "per stolp, five stay five");
        assertEquals(24, proposal.lines().get(1).quantity(), "per carton, thirteen become two cartons");

        /* The product went per carton before staff adopted the proposal: adopted as a save would write it. */
        step(stolp, "CARTON");
        SalesOrder adopted = quotes.approveRevision(proposal.id(), "emre", null);
        SalesOrderLine stolpLine = adopted.lines().stream().filter(line -> line.productId() == stolp).findFirst().orElseThrow();
        assertEquals(12, stolpLine.quantity());
        assertEquals(12, stolpLine.unitsPerOrderStep());

        Container container = container();
        SalesOrder atCost = sent(sales.createFromPurchaseOrder(request(container, "COST")));
        quotes.proposeRevision(atCost.portalToken(), List.of(new QuoteRevision.Line(null, container.productId(), 95, null)),
                "Klant", null);
        assertEquals(95, revisions.findByOrder(atCost.id()).getFirst().lines().getFirst().quantity(),
                "a container-cost quote keeps exact pieces in the portal too");
    }

    @Test @TestTransaction
    void anOpenPortalLineCarriesTheStepAProposalOnItRoundsTo() {
        long stolp = product("UNIT", 12);
        SalesOrder quote = sent(quote(stolp, 3));
        /* Sent per stolp; Emre then switches the product to full cartons of twelve. */
        step(stolp, "CARTON");
        var line = customerQuotes.portal(sales.get(quote.id()), null).lines().getFirst();
        assertEquals(3, line.quantity(), "the sent line keeps what it was priced with");
        assertEquals(12, line.unitsPerOrderStep(), "but a proposal on it rounds to today's step");

        quotes.proposeRevision(quote.portalToken(), List.of(new QuoteRevision.Line(null, stolp, 13, null)), "Klant", null);
        assertEquals(be.enrosed.catalog.domain.OrderSteps.roundUp(13, line.unitsPerOrderStep()),
                revisions.findByOrder(quote.id()).getFirst().lines().getFirst().quantity(),
                "the portal announces exactly what the server stores");

        status(quote.id(), QuoteStatus.GEACCEPTEERD);
        assertEquals(1, customerQuotes.portal(sales.get(quote.id()), null).lines().getFirst().unitsPerOrderStep(),
                "a closed quote shows the step its line was priced with");
    }

    @Test @TestTransaction
    void aSplitNamesTheMultipleTheLineWasRoundedToNotTodaysSetting() {
        long bowls = product(null, 40);
        em.find(ProductEntity.class, bowls).packagingUnitKey = "bowl";
        em.flush();
        SalesOrder perCarton = quote(bowls, 40);
        assertEquals(40, perCarton.lines().getFirst().unitsPerOrderStep());

        /* The product goes per display of eight after the line was rounded to cartons of forty. */
        ProductEntity display = em.find(ProductEntity.class, bowls);
        display.packagingKind = PackagingKind.DISPLAY;
        display.packagingPiecesPerUnit = 8;
        display.packagingSalesUnit = SalesUnit.PIECE;
        em.flush();
        step(bowls, "PACKAGE");
        BusinessRuleException cartons = assertThrows(BusinessRuleException.class, () -> splits.preview(perCarton.id(),
                new SalesSplits.Request(List.of(new SalesSplits.Choice(perCarton.lines().getFirst().id(), 8)),
                        "2026-W45", BigDecimal.ZERO, BigDecimal.ZERO, null, null, null, null)));
        assertTrue(cartons.getMessage().contains("in volle dozen van 40 bowls"), cartons.getMessage());

        /* And the other way round: rounded per display, the product back on full cartons. */
        SalesOrder perDisplay = quote(bowls, 16);
        assertEquals(8, perDisplay.lines().getFirst().unitsPerOrderStep());
        step(bowls, "CARTON");
        BusinessRuleException displays = assertThrows(BusinessRuleException.class, () -> splits.preview(perDisplay.id(),
                new SalesSplits.Request(List.of(new SalesSplits.Choice(perDisplay.lines().getFirst().id(), 4)),
                        "2026-W45", BigDecimal.ZERO, BigDecimal.ZERO, null, null, null, null)));
        assertTrue(displays.getMessage().contains("in displays van 8 bowls"), displays.getMessage());
    }

    @Test @TestTransaction
    void aSplitPortionKeepsTheStepOfItsLine() {
        long stolp = product("UNIT", 12);
        SalesOrder perPiece = quote(stolp, 5);
        SalesSplits.LineChoice choice = splits.eligibility(perPiece.id()).lines().getFirst();
        assertEquals(1, choice.unitsPerOrderStep());
        assertEquals("UNIT", choice.orderStep());
        assertEquals(12, choice.piecesPerCarton(), "the carton content is still sent along");
        SalesSplits.Request two = new SalesSplits.Request(
                List.of(new SalesSplits.Choice(perPiece.lines().getFirst().id(), 2)), "2026-W45",
                BigDecimal.ZERO, BigDecimal.ZERO, null, null, null, null);
        SalesSplits.Preview preview = splits.preview(perPiece.id(), two);
        assertEquals(3, preview.current().quantity());
        assertEquals(2, preview.later().quantity(), "two stolpen later, not a full carton");

        long bowls = product("PACKAGE", 40);
        ProductEntity display = em.find(ProductEntity.class, bowls);
        display.packagingKind = PackagingKind.DISPLAY;
        display.packagingPiecesPerUnit = 8;
        display.packagingSalesUnit = SalesUnit.PIECE;
        display.packagingUnitKey = "bowl";
        em.flush();
        SalesOrder perDisplay = quote(bowls, 16);
        assertEquals(8, splits.eligibility(perDisplay.id()).lines().getFirst().unitsPerOrderStep());
        BusinessRuleException half = assertThrows(BusinessRuleException.class, () -> splits.preview(perDisplay.id(),
                new SalesSplits.Request(List.of(new SalesSplits.Choice(perDisplay.lines().getFirst().id(), 4)),
                        "2026-W45", BigDecimal.ZERO, BigDecimal.ZERO, null, null, null, null)));
        assertTrue(half.getMessage().contains("in displays van 8 bowls"), half.getMessage());
    }

    /* ------------------------------------------------------------------ fixtures */

    private long product(String step, int perCarton) {
        catalogLock.acquire();
        em.createNativeQuery("delete from discount_tier").executeUpdate();
        ProductEntity product = new ProductEntity();
        product.sku = "STEP-" + UUID.randomUUID();
        product.name = "Stolp";
        product.active = true;
        product.cartonLengthCm = BigDecimal.TEN;
        product.cartonWidthCm = BigDecimal.TEN;
        product.cartonHeightCm = BigDecimal.TEN;
        product.cartonWeightKg = BigDecimal.ONE;
        product.piecesPerCarton = perCarton;
        product.stockQuantity = 100;
        product.inventoryKnown = true;
        product.landedCostEur = new BigDecimal("2");
        product.fixedSalesPriceEur = new BigDecimal("5");
        product.packagingOrderStep = step;
        product.packagingUnitKey = "stolp";
        em.persist(product);
        em.flush();
        return product.id;
    }

    private void step(long productId, String step) {
        em.find(ProductEntity.class, productId).packagingOrderStep = step;
        em.flush();
        em.clear();
    }

    private void status(long orderId, QuoteStatus status) {
        em.find(SalesOrderEntity.class, orderId).status = status;
        em.flush();
        em.clear();
    }

    private SalesOrder quote(long productId, int quantity) {
        Customer buyer = customers.create(new Customer(null, "Step QA", "Buyer", null, null, "BE0123456789", "BE",
                Language.NL, "Voorbeeld 1", "2400", "Mol", "DAP", "30 dagen", null, LocalDate.now()));
        SalesOrder created = sales.create(buyer.id(), "BE", "DAP", DocumentType.OFFERTE);
        return sales.update(created.id(), created.withLinesAndPallets(
                List.of(new SalesOrderLine(null, productId, quantity, null, null, null)), List.of()));
    }

    /** As the editor sends a document back: the JSON of what it read. */
    private SalesOrder echoed(SalesOrder order) throws Exception {
        return mapper.treeToValue(mapper.valueToTree(order), SalesOrder.class);
    }

    private SalesOrder sent(SalesOrder order) {
        SalesOrderEntity row = em.find(SalesOrderEntity.class, order.id());
        row.status = QuoteStatus.VERZONDEN;
        row.sentAt = Instant.now();
        row.portalToken = "step-" + UUID.randomUUID();
        em.flush();
        em.clear();
        return sales.get(order.id());
    }

    private record Container(long purchaseId, long customerId, long productId) {}

    private Container container() {
        Customer buyer = customers.create(new Customer(null, "Step container buyer", "Finance", null, null,
                "BE0000000000", "BE", Language.NL, "Test 1", "2000", "Antwerpen", "DAP", null, null, LocalDate.now()));
        Supplier supplier = suppliers.save(new Supplier(null, "Step supplier", "CN", "Yiwu", null, null, null,
                Currency.USD, "FOB", "Ningbo", 30, null));
        PurchaseOrder purchase = purchases.create(supplier.id(), new BigDecimal("0.14"), BigDecimal.ONE, BigDecimal.ZERO);
        ProductEntity product = new ProductEntity();
        product.sku = "STEP-COST-" + UUID.randomUUID();
        product.name = "Container roses";
        product.supplierId = supplier.id();
        product.cartonLengthCm = BigDecimal.TEN;
        product.cartonWidthCm = BigDecimal.TEN;
        product.cartonHeightCm = BigDecimal.TEN;
        product.cartonWeightKg = BigDecimal.ONE;
        product.piecesPerCarton = 10;
        product.active = true;
        product.fixedSalesPriceEur = new BigDecimal("5");
        em.persist(product);
        em.flush();
        PurchaseOrderEntity entity = em.find(PurchaseOrderEntity.class, purchase.id());
        entity.freightUsd = BigDecimal.ZERO;
        entity.originCosts = BigDecimal.ZERO;
        entity.destinationCostsEur = BigDecimal.ZERO;
        entity.defaultDutyRatePct = BigDecimal.ZERO;
        entity.extraRevenueEur = BigDecimal.ZERO;
        PurchaseOrderLineEntity line = new PurchaseOrderLineEntity();
        line.order = entity;
        line.productId = product.id;
        line.quantity = 99;
        line.exwPrice = new BigDecimal("1000");
        line.exwCurrency = Currency.EUR;
        entity.lines.add(line);
        em.persist(line);
        em.flush();
        em.clear();
        return new Container(purchase.id(), buyer.id(), product.id);
    }

    private static SalesOrderService.FromPurchaseOrderRequest request(Container container, String pricing) {
        return new SalesOrderService.FromPurchaseOrderRequest(container.purchaseId(), container.customerId(), pricing,
                BigDecimal.ZERO, false, null, null, false, List.of(), "CUSTOMER", "2026-W40", SalesPurpose.STANDARD,
                SalesPaymentPlan.FULL, null, null);
    }
}
