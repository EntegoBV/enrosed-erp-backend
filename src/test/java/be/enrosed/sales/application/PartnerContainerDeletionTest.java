package be.enrosed.sales.application;

import be.enrosed.catalog.adapter.out.persistence.ProductEntity;
import be.enrosed.sales.adapter.out.persistence.SalesEntities.SalesOrderEntity;
import be.enrosed.sales.domain.*;
import be.enrosed.shared.BusinessRuleException;
import be.enrosed.shared.Currency;
import be.enrosed.shared.Language;
import be.enrosed.shared.NotFoundException;
import be.enrosed.shared.trash.DeletedItemDtos;
import be.enrosed.shared.trash.DeletedItemsService;
import be.enrosed.sourcing.adapter.out.persistence.SourcingEntities.PurchaseOrderEntity;
import be.enrosed.sourcing.adapter.out.persistence.SourcingEntities.PurchaseOrderLineEntity;
import be.enrosed.sourcing.application.PurchaseOrderService;
import be.enrosed.sourcing.application.SupplierService;
import be.enrosed.sourcing.domain.PurchaseOrderStatus;
import be.enrosed.sourcing.domain.Supplier;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class PartnerContainerDeletionTest {
    @Inject PartnerContainerDeletionService deletion;
    @Inject DeletedItemsService trash;
    @Inject PartnerAdvanceScheduleService schedules;
    @Inject SalesOrderService sales;
    @Inject PurchaseOrderService purchases;
    @Inject CustomerService customers;
    @Inject SupplierService suppliers;
    @Inject IncomingPaymentService incoming;
    @Inject EntityManager entities;

    @Test @TestTransaction
    void twoUnusedTermInvoicesAndTheirContainerLeaveTogetherAndRestoreToTheirOriginalSlots() {
        var f = fixture(true);
        var ids = invoiceIds(f);
        entities.flush(); entities.clear();
        var originalPurchase = purchases.get(f.purchaseId());
        Map<Long, SalesOrder> originalInvoices = ids.stream().collect(Collectors.toMap(id -> id, sales::get));
        var originalRows = schedules.get(f.purchaseId()).rows();
        var preview = deletion.preview(f.purchaseId());
        assertTrue(preview.allowed(), preview.blockReason());
        assertEquals(originalPurchase.number(), preview.number());
        assertEquals(ids, preview.invoices().stream().map(PartnerContainerDeletionService.Invoice::id).toList());
        for (var invoice : preview.invoices()) {
            assertEquals(QuoteStatus.CONCEPT, invoice.status());
            assertEquals(sales.price(originalInvoices.get(invoice.id())).totals().totalInclVat(), invoice.totalEur());
        }

        var removed = deletion.delete(f.purchaseId(), new PartnerContainerDeletionService.Request(ids));
        assertEquals(f.purchaseId(), removed.purchaseOrderId());
        assertEquals(ids, removed.deletedInvoiceIds());
        assertThrows(NotFoundException.class, () -> purchases.get(f.purchaseId()));
        for (long id : ids) assertThrows(NotFoundException.class, () -> sales.get(id));
        assertFalse(purchases.list().stream().anyMatch(order -> order.id() == f.purchaseId()));
        assertFalse(sales.list().stream().anyMatch(order -> ids.contains(order.id())));
        assertEquals(1, marked("purchase_order", f.purchaseId()));
        for (long id : ids) assertEquals(1, marked("sales_order", id));

        long purchaseEntry = trashEntry(f.purchaseId(), DeletedItemDtos.Type.PURCHASE_ORDER);
        Map<Long, Long> invoiceEntries = ids.stream().collect(Collectors.toMap(id -> id,
                id -> trashEntry(id, DeletedItemDtos.Type.INVOICE)));
        for (long entry : invoiceEntries.values()) {
            assertFalse(trash.detail(entry).restoreAllowed());
            assertTrue(trash.detail(entry).blockReason().contains("inkooporder"));
        }
        trash.restore(purchaseEntry);
        assertEquals(originalPurchase, purchases.get(f.purchaseId()));
        assertTrue(schedules.get(f.purchaseId()).rows().stream().allMatch(row -> row.invoiceId() == null));
        // Reversing restore order must still reclaim each invoice's exact original term.
        for (long id : ids.reversed()) {
            assertTrue(trash.detail(invoiceEntries.get(id)).restoreAllowed());
            trash.restore(invoiceEntries.get(id));
            assertEquals(originalInvoices.get(id), sales.get(id));
            assertTrue(incoming.forOrder(id).isEmpty());
        }
        assertEquals(originalRows, schedules.get(f.purchaseId()).rows());
        assertEquals(0, marked("purchase_order", f.purchaseId()));
        for (long id : ids) assertEquals(0, marked("sales_order", id));
    }

    @Test @TestTransaction
    void issuingTheSecondInvoiceAfterPreviewBlocksDeletionOfEveryDocument() {
        var f = fixture(true);
        assertTrue(deletion.preview(f.purchaseId()).allowed());
        sales.issueInvoice(invoiceIds(f).getLast());
        assertBlockedAndIntact(f);
        assertEquals(QuoteStatus.CONCEPT, sales.get(invoiceIds(f).getFirst()).status());
        assertEquals(QuoteStatus.UITGEREIKT, sales.get(invoiceIds(f).getLast()).status());
    }

    @Test @TestTransaction
    void evenVoidedReceiptHistoryProtectsAConceptInvoiceAndItsContainer() {
        var f = fixture(true);
        long invoiceId = invoiceIds(f).getLast();
        sales.issueInvoice(invoiceId);
        incoming.add(invoiceId, new IncomingPaymentService.Request(new BigDecimal("10.00"),
                Instant.now().minusSeconds(60), "Europe/Brussels", "Receipt history"));
        long paymentId = incoming.forOrder(invoiceId).getFirst().id();
        incoming.delete(invoiceId, paymentId);
        // Historical/imported lifecycle corrections must not bypass the retained receipt guard.
        entities.createNativeQuery("update sales_order set status='CONCEPT' where id=:id")
                .setParameter("id", invoiceId).executeUpdate();
        entities.clear();
        assertTrue(incoming.forOrder(invoiceId).isEmpty());
        assertTrue(incoming.hasHistory(invoiceId));
        assertBlockedAndIntact(f);
        assertTrue(deletion.preview(f.purchaseId()).blockReason().contains("betaalhistoriek"));
    }

    @Test @TestTransaction
    void purchasePaymentAddedAfterPreviewProtectsTheWholeDossier() {
        var f = fixture(true);
        assertTrue(deletion.preview(f.purchaseId()).allowed());
        purchases.addPayment(f.purchaseId(), LocalDate.now(), new BigDecimal("50.00"), Currency.EUR, "Supplier paid");
        assertBlockedAndIntact(f);
        assertTrue(deletion.preview(f.purchaseId()).blockReason().contains("betalingen"));
    }

    @Test @TestTransaction
    void receivedStockKeepsThePurchaseDeletionGuard() {
        var f = fixture(true);
        var purchase = entities.find(PurchaseOrderEntity.class, f.purchaseId());
        purchase.status = PurchaseOrderStatus.ONTVANGEN; purchase.stockBooked = true;
        entities.flush(); entities.clear();
        assertFalse(deletion.preview(f.purchaseId()).allowed());
        assertTrue(deletion.preview(f.purchaseId()).blockReason().contains("voorraad"));
        assertBlockedAndIntact(f);
    }

    @Test @TestTransaction
    void legacyPurchasePaidTotalAlsoBlocksTheCascadeWithoutPaymentRows() {
        var f = fixture(true);
        entities.find(PurchaseOrderEntity.class, f.purchaseId()).paidTotalEur = new BigDecimal("12.34");
        entities.flush(); entities.clear();
        assertBlockedAndIntact(f);
        assertEquals(new BigDecimal("12.34"), purchases.get(f.purchaseId()).paidTotalEur());
    }

    @ParameterizedTest @EnumSource(value = SalesPurpose.class, names = {"STANDARD", "PARTNER_SETTLEMENT"})
    @TestTransaction
    void anOrdinaryOrSettlementInvoiceCannotBeSweptUpWithUnusedAdvances(SalesPurpose purpose) {
        var f = fixture(true);
        var other = sales.create(f.partnerId(), "BE", "DAP", DocumentType.FACTUUR);
        var entity = entities.find(SalesOrderEntity.class, other.id());
        entity.sourcePurchaseOrderId = f.purchaseId(); entity.partnerPurchaseOrderId = f.purchaseId();
        entity.purpose = purpose; entity.partnerSettlement = purpose == SalesPurpose.PARTNER_SETTLEMENT;
        entities.flush(); entities.clear();
        assertBlockedAndIntact(f);
        assertNotNull(sales.get(other.id()));
        assertEquals(0, marked("sales_order", other.id()));
    }

    @Test @TestTransaction
    void aHistoricalLinkedQuoteIsNotSilentlyRemovedByTheInvoiceCascade() {
        var f = fixture(true);
        var quote = sales.create(f.partnerId(), "BE", "DAP", DocumentType.OFFERTE);
        entities.find(SalesOrderEntity.class, quote.id()).sourcePurchaseOrderId = f.purchaseId();
        entities.flush(); entities.clear();
        assertBlockedAndIntact(f);
        assertNotNull(sales.get(quote.id()));
    }

    @Test @TestTransaction
    void aNewSecondInvoiceRequiresAFreshConfirmationWithTheCompleteSet() {
        var f = fixture(false);
        var seen = deletion.preview(f.purchaseId());
        assertTrue(seen.allowed());
        var expected = seen.invoices().stream().map(PartnerContainerDeletionService.Invoice::id).toList();
        assertEquals(1, expected.size());
        schedules.createInvoice(f.purchaseId(), f.rowIds().getLast());
        assertEquals(2, invoiceIds(f).size());
        var failure = assertThrows(BusinessRuleException.class,
                () -> deletion.delete(f.purchaseId(), new PartnerContainerDeletionService.Request(expected)));
        assertTrue(failure.getMessage().contains("gewijzigd"));
        assertIntact(f);
    }

    @Test @TestTransaction
    void ordinaryPurchaseOrdersCannotUsePartnerCascadeEvenWhenTheyHaveNoDocuments() {
        var f = fixture(true);
        var plain = purchases.create(purchases.get(f.purchaseId()).supplierId(), new BigDecimal("0.14"), BigDecimal.ONE, BigDecimal.ZERO);
        assertFalse(deletion.preview(plain.id()).allowed());
        assertThrows(BusinessRuleException.class,
                () -> deletion.delete(plain.id(), new PartnerContainerDeletionService.Request(List.of())));
        assertNotNull(purchases.get(plain.id()));
        assertIntact(f);
    }

    @Test void anonymousCannotPreviewOrDeleteAPartnerContainer() {
        given().get(endpoint(1)).then().statusCode(401);
        given().contentType("application/json").body("{\"expectedInvoiceIds\":[]}").post(endpoint(1)).then().statusCode(401);
    }

    @Test @TestSecurity(user = "viewer", roles = "viewer")
    void nonAdminCannotPreviewOrDeleteAPartnerContainer() {
        given().get(endpoint(1)).then().statusCode(403);
        given().contentType("application/json").body("{\"expectedInvoiceIds\":[]}").post(endpoint(1)).then().statusCode(403);
    }

    @Test @TestSecurity(user = "emre", roles = "admin")
    void adminCanReachTheRealPreviewAndGuardedDeleteEndpoints() {
        long id = QuarkusTransaction.requiringNew().call(() -> {
            var purchase = new PurchaseOrderEntity(); purchase.number = "CASCADE-AUTH-" + UUID.randomUUID();
            entities.persist(purchase); entities.flush(); return purchase.id;
        });
        try {
            given().get(endpoint(id)).then().statusCode(200).body("allowed", is(false)).body("invoices.size()", is(0));
            given().contentType("application/json").body("{\"expectedInvoiceIds\":[]}").post(endpoint(id)).then().statusCode(409);
            QuarkusTransaction.requiringNew().run(() -> assertEquals(0, marked("purchase_order", id)));
        } finally {
            QuarkusTransaction.requiringNew().run(() -> entities.createNativeQuery("delete from purchase_order where id=:id")
                    .setParameter("id", id).executeUpdate());
        }
    }

    private void assertBlockedAndIntact(Fixture f) {
        var preview = deletion.preview(f.purchaseId());
        assertFalse(preview.allowed()); assertNotNull(preview.blockReason());
        assertThrows(BusinessRuleException.class,
                () -> deletion.delete(f.purchaseId(), new PartnerContainerDeletionService.Request(invoiceIds(f))));
        assertIntact(f);
    }

    private void assertIntact(Fixture f) {
        assertNotNull(purchases.get(f.purchaseId()));
        assertEquals(0, marked("purchase_order", f.purchaseId()));
        assertEquals(2, invoiceIds(f).size(), "a rejected cascade may not clear either original term slot");
        for (long id : invoiceIds(f)) {
            assertNotNull(sales.get(id)); assertEquals(0, marked("sales_order", id));
        }
    }

    private List<Long> invoiceIds(Fixture f) {
        return schedules.get(f.purchaseId()).rows().stream().map(PartnerAdvanceScheduleService.Row::invoiceId)
                .filter(java.util.Objects::nonNull).sorted().toList();
    }

    private long trashEntry(long id, DeletedItemDtos.Type type) {
        return trash.list().items().stream().filter(item -> item.sourceId() == id && item.type() == type)
                .findFirst().orElseThrow().id();
    }

    private long marked(String table, long id) {
        return ((Number) entities.createNativeQuery("select count(*) from " + table + " where id=:id and deleted_at is not null")
                .setParameter("id", id).getSingleResult()).longValue();
    }

    private String endpoint(long id) { return "/api/purchase-orders/" + id + "/partner-container-deletion"; }

    private record Fixture(long purchaseId, long partnerId, List<Long> rowIds) {}

    private Fixture fixture(boolean bothInvoices) {
        var partner = customers.create(new Customer(null, "Cascade partner", "Buyer", null, null, "BE0000000000", "BE", Language.NL,
                "Test 1", "2000", "Antwerpen", "DAP", null, null, LocalDate.now()));
        var supplier = suppliers.save(new Supplier(null, "Cascade supplier", "CN", "Yiwu", null, null, null,
                Currency.USD, "FOB", "Ningbo", 30, null));
        var purchase = purchases.create(supplier.id(), new BigDecimal("0.14"), BigDecimal.ONE, BigDecimal.ZERO);
        purchases.setPartner(purchase.id(), new PurchaseOrderService.PartnerRequest(partner.id(), new BigDecimal("100"), new BigDecimal("50")));
        var product = new ProductEntity(); product.sku = "CASCADE-" + UUID.randomUUID(); product.name = "Partner roses";
        product.supplierId = supplier.id(); product.cartonLengthCm = BigDecimal.TEN; product.cartonWidthCm = BigDecimal.TEN;
        product.cartonHeightCm = BigDecimal.TEN; product.cartonWeightKg = BigDecimal.ONE; product.piecesPerCarton = 1;
        entities.persist(product);
        var entity = entities.find(PurchaseOrderEntity.class, purchase.id());
        entity.freightUsd = BigDecimal.ZERO; entity.originCosts = BigDecimal.ZERO; entity.destinationCostsEur = BigDecimal.ZERO;
        entity.defaultDutyRatePct = BigDecimal.ZERO; entity.extraRevenueEur = BigDecimal.ZERO;
        var line = new PurchaseOrderLineEntity(); line.order = entity; line.productId = product.id;
        line.quantity = 12; line.exwPrice = new BigDecimal("1000"); line.exwCurrency = Currency.EUR;
        entity.lines.add(line); entities.persist(line); entities.flush(); entities.clear();
        var plan = schedules.save(purchase.id(), new PartnerAdvanceScheduleService.Request(List.of(
                new PartnerAdvanceScheduleService.RowRequest(null, "Bij start", new BigDecimal("30"), null, null),
                new PartnerAdvanceScheduleService.RowRequest(null, "Na productie", new BigDecimal("70"), null, null)), false));
        var rowIds = plan.rows().stream().map(PartnerAdvanceScheduleService.Row::id).toList();
        schedules.createInvoice(purchase.id(), rowIds.getFirst());
        if (bothInvoices) schedules.createInvoice(purchase.id(), rowIds.getLast());
        entities.flush(); entities.clear();
        return new Fixture(purchase.id(), partner.id(), rowIds);
    }
}
