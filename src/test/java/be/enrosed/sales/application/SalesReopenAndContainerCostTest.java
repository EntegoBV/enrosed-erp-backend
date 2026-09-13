package be.enrosed.sales.application;

import be.enrosed.catalog.adapter.out.persistence.ProductEntity;
import be.enrosed.sales.adapter.out.persistence.SalesEntities.DiscountTierEntity;
import be.enrosed.sales.adapter.out.persistence.SalesEntities.SalesOrderEntity;
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
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
@io.quarkus.test.security.TestSecurity(user = "emre", roles = "admin")
class SalesReopenAndContainerCostTest {
    @Inject SalesOrderService sales;
    @Inject QuoteService quotes;
    @Inject IncomingPaymentService receipts;
    @Inject PurchaseOrderService purchases;
    @Inject SupplierService suppliers;
    @Inject CustomerService customers;
    @Inject EntityManager entities;
    @Inject ObjectMapper mapper;

    @Test @TestTransaction
    void containerCostPreservesExactPiecesAndSkipsAutomaticTiersAndHandlingEvenAfterEditing() throws Exception {
        Fixture f = fixture();
        tier(TierScope.LINE, f.productId()); tier(TierScope.ORDER, null);
        SalesOrder quote = sales.createFromPurchaseOrder(request(f, "0", null));
        assertEquals(MarkupMode.CONTAINER_COST, quote.markupMode());
        assertEquals(99, quote.lines().getFirst().quantity());
        PricedOrder priced = sales.price(quote);
        assertEquals(99, priced.lines().getFirst().quantity());
        assertEquals(0, priced.lines().getFirst().tierPercent().signum());
        assertEquals(0, priced.totals().marginEur().signum());
        assertEquals(0, priced.totals().handling().signum());
        assertEquals(0, priced.totals().freight().signum());
        ObjectNode edit = mapper.valueToTree(quote); edit.put("notes", "Een echte documentnotitie");
        SalesOrder saved = sales.update(quote.id(), mapper.treeToValue(edit, SalesOrder.class));
        assertEquals(99, saved.lines().getFirst().quantity());
        assertEquals(priced.totals().total(), sales.price(saved).totals().total());
        SalesOrder invoice = sales.createInvoiceFrom(quote.id());
        assertEquals(MarkupMode.CONTAINER_COST, invoice.markupMode());
        assertEquals(priced.totals().total(), sales.price(invoice).totals().total());
    }

    @Test @TestTransaction
    void euroMarkupAndPercentMarkupChargeOnlyTheChosenMarkupAndExplicitDiscountStillWorks() throws Exception {
        Fixture f = fixture(); tier(TierScope.ORDER, null);
        SalesOrder euro = sales.createFromPurchaseOrder(request(f, "0", "0.50"));
        assertEquals(money("49.50"), sales.price(euro).totals().marginEur());
        SalesOrder percent = sales.createFromPurchaseOrder(request(f, "10", null));
        assertEquals(money("9900"), sales.price(percent).totals().marginEur());
        ObjectNode edit = mapper.valueToTree(euro); edit.put("extraDiscountPct", new BigDecimal("10"));
        SalesOrder discounted = sales.update(euro.id(), mapper.treeToValue(edit, SalesOrder.class));
        PricedOrder priced = sales.price(discounted);
        assertEquals(money("-9855.45"), priced.totals().marginEur());
        assertEquals(priced.totals().marginEur(), priced.lines().getFirst().marginEur());
        assertThrows(BusinessRuleException.class, () -> sales.createFromPurchaseOrder(request(f, "10", "0.50")));
    }

    @Test @TestTransaction
    void internallyIssuedInvoiceCanReopenWithTheSameNumberAndAmountButCannotLoseItsIssueHistory() {
        Fixture f = fixture();
        SalesOrder quote = sales.createFromPurchaseOrder(request(f, "0", null));
        SalesOrder invoice = sales.createInvoiceFrom(quote.id());
        BigDecimal total = sales.price(invoice).totals().totalInclVat();
        sales.issueInvoice(invoice.id());
        SalesOrder reopened = quotes.reopen(invoice.id());
        assertEquals(QuoteStatus.CONCEPT, reopened.status());
        assertEquals(invoice.number(), reopened.number());
        assertEquals(invoice.sourceQuoteId(), reopened.sourceQuoteId());
        assertEquals(invoice.invoiceDueDate(), reopened.invoiceDueDate());
        assertEquals(total, sales.price(reopened).totals().totalInclVat());
        assertNull(reopened.sentAt());
        assertThrows(BusinessRuleException.class, () -> sales.delete(invoice.id()), "reopening cannot make an issued invoice disposable");
    }

    @Test @TestTransaction
    void sentQuoteReturnsToConceptWhileKeepingItsOriginalSendingHistory() {
        Fixture f = fixture();
        SalesOrder quote = sales.createFromPurchaseOrder(request(f, "0", null));
        Instant sent = Instant.parse("2026-09-01T10:00:00Z");
        SalesOrderEntity row = entities.find(SalesOrderEntity.class, quote.id());
        row.status = QuoteStatus.VERZONDEN; row.sentAt = sent; row.portalToken = "reopen-" + UUID.randomUUID();
        entities.flush(); entities.clear();
        SalesOrder reopened = quotes.reopen(quote.id());
        assertEquals(QuoteStatus.CONCEPT, reopened.status()); assertEquals(sent, reopened.sentAt());
        assertNotNull(reopened.portalToken()); assertEquals(quote.number(), reopened.number());
    }

    @Test @TestTransaction
    void aVoidedReceiptStillBlocksReopeningItsFinancialDocument() {
        Fixture f = fixture();
        SalesOrder invoice = sales.createInvoiceFrom(sales.createFromPurchaseOrder(request(f, "0", null)).id());
        sales.issueInvoice(invoice.id());
        receipts.add(invoice.id(), new IncomingPaymentService.Request(BigDecimal.ONE, Instant.now().minusSeconds(5), "Europe/Brussels", "Regressie"));
        long paymentId = receipts.forOrder(invoice.id()).getFirst().id();
        receipts.delete(invoice.id(), paymentId);
        assertTrue(receipts.forOrder(invoice.id()).isEmpty());
        assertTrue(receipts.hasHistory(invoice.id()));
        assertThrows(BusinessRuleException.class, () -> quotes.reopen(invoice.id()));
    }

    @Test @TestTransaction
    void actualShipmentBlocksReopeningAndSignedQuotesAreNeverOpenedForEditing() {
        Fixture f = fixture();
        SalesOrder quote = sales.createFromPurchaseOrder(request(f, "0", null));
        SalesOrder invoice = sales.createInvoiceFrom(quote.id());
        SalesOrderEntity shipped = entities.find(SalesOrderEntity.class, invoice.id());
        shipped.status = QuoteStatus.VERZONDEN; shipped.goodsShippedAt = Instant.now();
        SalesOrderEntity signed = entities.find(SalesOrderEntity.class, quote.id());
        signed.status = QuoteStatus.GEACCEPTEERD; signed.signedByName = "Inkoper";
        entities.flush(); entities.clear();
        assertThrows(BusinessRuleException.class, () -> quotes.reopen(invoice.id()));
        assertThrows(BusinessRuleException.class, () -> quotes.reopen(quote.id()));
    }

    private static SalesOrderService.FromPurchaseOrderRequest request(Fixture f, String pct, String perUnit) {
        return new SalesOrderService.FromPurchaseOrderRequest(f.purchaseId(), f.customerId(), "COST", new BigDecimal(pct),
                false, null, null, false, List.of(), "CUSTOMER", "2026-W40", SalesPurpose.STANDARD,
                SalesPaymentPlan.FULL, null, perUnit == null ? null : new BigDecimal(perUnit));
    }
    private void tier(TierScope scope, Long productId) {
        DiscountTierEntity tier = new DiscountTierEntity(); tier.scope = scope; tier.productId = productId;
        tier.minQuantity = 1; tier.percent = BigDecimal.TEN; entities.persist(tier); entities.flush();
    }
    private record Fixture(long purchaseId, long customerId, long productId) {}
    private Fixture fixture() {
        Customer buyer = customers.create(new Customer(null, "Cost-mode buyer", "Finance", null, null,
                "BE0000000000", "BE", Language.NL, "Test 1", "2000", "Antwerpen", "DAP", null, null, LocalDate.now()));
        Supplier supplier = suppliers.save(new Supplier(null, "Cost-mode supplier", "CN", "Yiwu", null, null, null,
                Currency.USD, "FOB", "Ningbo", 30, null));
        PurchaseOrder purchase = purchases.create(supplier.id(), new BigDecimal("0.14"), BigDecimal.ONE, BigDecimal.ZERO);
        ProductEntity product = new ProductEntity(); product.sku = "COST-" + UUID.randomUUID(); product.name = "Cost-price roses";
        product.supplierId = supplier.id(); product.cartonLengthCm = BigDecimal.TEN; product.cartonWidthCm = BigDecimal.TEN;
        product.cartonHeightCm = BigDecimal.TEN; product.cartonWeightKg = BigDecimal.ONE; product.piecesPerCarton = 10;
        entities.persist(product); entities.flush();
        PurchaseOrderEntity entity = entities.find(PurchaseOrderEntity.class, purchase.id());
        entity.freightUsd = BigDecimal.ZERO; entity.originCosts = BigDecimal.ZERO; entity.destinationCostsEur = BigDecimal.ZERO;
        entity.defaultDutyRatePct = BigDecimal.ZERO; entity.extraRevenueEur = BigDecimal.ZERO;
        PurchaseOrderLineEntity line = new PurchaseOrderLineEntity(); line.order = entity; line.productId = product.id;
        line.quantity = 99; line.exwPrice = new BigDecimal("1000"); line.exwCurrency = Currency.EUR;
        entity.lines.add(line); entities.persist(line); entities.flush(); entities.clear();
        return new Fixture(purchase.id(), buyer.id(), product.id);
    }
    private static BigDecimal money(String value) { return new BigDecimal(value).setScale(2); }
}
