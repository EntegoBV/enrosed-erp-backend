package be.enrosed.sales.application;

import be.enrosed.catalog.adapter.out.persistence.ProductEntity;
import be.enrosed.sales.adapter.in.rest.SalesOrderResource;
import be.enrosed.sales.adapter.in.rest.PartnerFinanceResource;
import be.enrosed.sales.adapter.out.persistence.PartnerAdvanceContentsEntity;
import be.enrosed.sales.adapter.out.persistence.SalesEntities.SalesOrderEntity;
import be.enrosed.sales.domain.*;
import be.enrosed.shared.BusinessRuleException;
import be.enrosed.shared.Currency;
import be.enrosed.shared.Language;
import be.enrosed.sourcing.adapter.out.persistence.SourcingEntities.PurchaseOrderEntity;
import be.enrosed.sourcing.adapter.out.persistence.SourcingEntities.PurchaseOrderLineEntity;
import be.enrosed.sourcing.application.PurchaseOrderService;
import be.enrosed.sourcing.application.SupplierService;
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
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
@TestSecurity(user = "emre", roles = "admin")
class PartnerAdvanceContentsTest {
    @Inject PartnerAdvanceContents contents;
    @Inject PartnerAdvanceScheduleService schedules;
    @Inject PartnerFinancingService financing;
    @Inject SalesOrderService sales;
    @Inject SalesOrderResource resource;
    @Inject PartnerFinanceResource partnerResource;
    @Inject PurchaseOrderService purchases;
    @Inject SupplierService suppliers;
    @Inject CustomerService customers;
    @Inject EntityManager entities;
    @Inject ObjectMapper json;

    @Test @TestTransaction
    void productSheetDetailsAreCapturedAndNeverFollowLaterCatalogueChanges() throws Exception {
        var f = fixture();
        var product = entities.find(ProductEntity.class, f.productId());
        product.productLengthCm = new BigDecimal("6.5");
        product.productWidthCm = new BigDecimal("7.5");
        product.productHeightCm = new BigDecimal("12");
        product.productWeightKg = new BigDecimal("0.25");
        product.packagingKind = be.enrosed.catalog.domain.PackagingKind.DISPLAY;
        product.packagingLengthCm = new BigDecimal("30");
        product.packagingWidthCm = new BigDecimal("20");
        product.packagingHeightCm = new BigDecimal("45");
        product.packagingWeightKg = new BigDecimal("3");
        product.packagingPiecesPerUnit = 12;
        product.packagingBarcode = "2000000000422";
        product.barcodeInner = "2000000000439";
        product.barcodeOuter = "2000000000446";
        product.canonicalBarcode = "2000000000453";
        product.piecesPerHc = 9600;
        entities.flush(); entities.clear();

        var invoice = sales.createFromPurchaseOrder(request(f, plan(), SalesPurpose.PARTNER_ADVANCE));
        var captured = contents.find(invoice).orElseThrow();
        var detail = captured.lines().getFirst().productDetails();
        assertNotNull(detail);
        assertEquals(0, new BigDecimal("6.5").compareTo(detail.dimensions().lengthCm()));
        assertEquals(0, new BigDecimal("0.25").compareTo(detail.dimensions().weightKg()));
        assertEquals(be.enrosed.catalog.domain.PackagingKind.DISPLAY, detail.packaging().kind());
        assertEquals(12, detail.packaging().piecesPerUnit());
        assertEquals("2000000000422", detail.packaging().barcode());
        assertEquals(0, new BigDecimal("45").compareTo(detail.packaging().dimensions().heightCm()));
        assertEquals(24, detail.carton().piecesPerCarton());
        assertEquals(9600, detail.carton().piecesPerHc());
        assertEquals(0, new BigDecimal("50").compareTo(detail.carton().dimensions().lengthCm()));
        assertEquals(0, new BigDecimal("12").compareTo(detail.carton().weightKg()));
        assertEquals("2000000000439", detail.barcodes().inner());
        assertEquals("2000000000446", detail.barcodes().outer());
        assertEquals("2000000000453", detail.canonicalBarcode());
        var amount = sales.price(invoice).totals().total();
        var encoded = json.readTree(json.writeValueAsString(captured));
        assertEquals("2000000000446", encoded.path("lines").get(0).path("productDetails").path("barcodes").path("outer").asText());

        product = entities.find(ProductEntity.class, f.productId());
        product.productLengthCm = new BigDecimal("99");
        product.productWeightKg = new BigDecimal("8");
        product.packagingKind = be.enrosed.catalog.domain.PackagingKind.GIFT_BOX;
        product.packagingHeightCm = new BigDecimal("80");
        product.packagingPiecesPerUnit = 1;
        product.packagingBarcode = "changed-packaging";
        product.cartonLengthCm = new BigDecimal("90");
        product.cartonWeightKg = new BigDecimal("50");
        product.piecesPerCarton = 48;
        product.piecesPerHc = 4800;
        product.barcodeInner = "changed-inner";
        product.barcodeOuter = "changed-outer";
        product.canonicalBarcode = "changed-canonical";
        entities.flush(); entities.clear();
        assertEquals(captured, contents.find(invoice.id()).orElseThrow());
        assertEquals(captured, contents.capture(sales.get(invoice.id())).orElseThrow());
        assertEquals(amount, sales.price(sales.get(invoice.id())).totals().total());
        assertTrue(sales.get(invoice.id()).lines().isEmpty());
    }

    @Test @TestTransaction
    void olderJsonWithoutProductDetailsRemainsReadableAndIsNotSilentlyUpgraded() throws Exception {
        var f = fixture();
        var invoice = sales.createFromPurchaseOrder(request(f, plan(), SalesPurpose.PARTNER_ADVANCE));
        var original = contents.find(invoice).orElseThrow();
        ObjectNode legacy = json.valueToTree(original);
        for (var line : legacy.withArray("lines")) ((ObjectNode) line).remove("productDetails");
        String stored = json.writeValueAsString(legacy);
        entities.find(PartnerAdvanceContentsEntity.class, invoice.id()).snapshotJson = stored;
        entities.flush(); entities.clear();

        var loaded = contents.find(invoice.id()).orElseThrow();
        var item = loaded.lines().getFirst();
        assertNull(item.productDetails());
        assertEquals(original.totals().pieces(), loaded.totals().pieces());
        assertEquals(original.totals().cartons(), loaded.totals().cartons());
        assertEquals(0, original.totals().cbm().compareTo(loaded.totals().cbm()));
        assertEquals(0, original.totals().weightKg().compareTo(loaded.totals().weightKg()));
        assertEquals(original.totals().pallets(), loaded.totals().pallets());
        assertEquals(original.capturedAt(), loaded.capturedAt());
        assertEquals(original.lines().getFirst().productName(), item.productName());
        assertEquals(original.lines().getFirst().quantity(), item.quantity());
        assertEquals(loaded, contents.capture(sales.get(invoice.id())).orElseThrow());
        assertEquals(stored, entities.find(PartnerAdvanceContentsEntity.class, invoice.id()).snapshotJson);
        assertNull(new PartnerAdvanceContents.Item(item.productId(), item.sku(), item.productName(), item.quantity(),
                item.cartons(), item.cbm(), item.weightKg()).productDetails());
    }

    @Test @TestTransaction
    void everyTermHasTheFullCargoButNoAdditionalFinancialOrStockClaimAndItNeverDrifts() throws Exception {
        var f = fixture();
        var first = sales.createFromPurchaseOrder(request(f, plan(), SalesPurpose.PARTNER_ADVANCE));
        var ids = schedules.get(f.purchaseId()).rows().stream().map(PartnerAdvanceScheduleService.Row::invoiceId).toList();
        var snapshot = contents.find(first).orElseThrow();
        assertEquals(240, snapshot.totals().pieces());
        assertEquals(10, snapshot.totals().cartons());
        assertEquals(0, new BigDecimal("0.6").compareTo(snapshot.totals().cbm()));
        assertEquals(0, new BigDecimal("120").compareTo(snapshot.totals().weightKg()));
        assertNull(snapshot.totals().pallets());
        assertEquals("40HQ", snapshot.delivery().containerType());
        assertEquals(LocalDate.of(2026, 12, 8), snapshot.delivery().expectedArrival());
        assertEquals("Antwerp", snapshot.delivery().destinationPort());
        assertNull(snapshot.delivery().loadMode(), "the default pallet mode is not a real transport instruction");
        for (Long id : ids) {
            var order = sales.get(id);
            assertTrue(order.lines().isEmpty(), "cargo must never become a second set of priced sales lines");
            assertEquals(snapshot.lines(), contents.find(order).orElseThrow().lines());
            assertNull(order.sentAt()); assertNull(order.goodsShippedAt());
        }
        assertEquals(new BigDecimal("12000.00"), financing.get(f.purchaseId()).committedAdvanceEur());
        assertEquals(37, entities.find(ProductEntity.class, f.productId()).stockQuantity);
        var wire = json.readTree(json.writeValueAsString(snapshot));
        assertFalse(wire.path("lines").get(0).has("unitPriceEur"));
        assertFalse(wire.path("lines").get(0).has("unitCostEur"));
        assertFalse(wire.path("totals").has("total"));
        assertEquals(snapshot, resource.get(first.id()).advanceContents());
        assertEquals(snapshot, partnerResource.scheduleInvoice(f.purchaseId(),
                schedules.get(f.purchaseId()).rows().getFirst().id()).advanceContents(),
                "the single-term creation response includes cargo immediately, before reloading");

        var product = entities.find(ProductEntity.class, f.productId());
        product.name = "Changed catalogue name"; product.piecesPerCarton = 12; product.cartonLengthCm = new BigDecimal("90");
        var purchase = entities.find(PurchaseOrderEntity.class, f.purchaseId());
        purchase.lines.getFirst().quantity = 48; purchase.expectedArrival = LocalDate.of(2027, 1, 20);
        entities.flush(); entities.clear();
        assertEquals(snapshot, contents.capture(sales.get(first.id())).orElseThrow());
        assertEquals(snapshot, resource.get(first.id()).advanceContents(), "API and document service share the same frozen cargo");
        assertEquals(new BigDecimal("4000.00"), sales.price(sales.get(first.id())).totals().total());
        assertThrows(BusinessRuleException.class, () -> sales.setPartnerDeal(first.id(), null),
                "a frozen advance cannot be silently repurposed as an unrelated invoice");
    }

    @Test @TestTransaction
    void existingAdvanceWithoutSourceUsesOrderedPurchaseQuantitiesAndCaptureIsIdempotent() {
        var f = fixture();
        var first = sales.createFromPurchaseOrder(request(f, plan(), SalesPurpose.PARTNER_ADVANCE));
        contents.delete(first.id()); entities.flush();
        var purchase = entities.find(PurchaseOrderEntity.class, f.purchaseId());
        purchase.lines.getFirst().orderedQuantity = 240;
        purchase.lines.getFirst().quantity = 216;
        purchase.receivedOn = LocalDate.of(2026, 12, 9);
        entities.flush(); entities.clear();
        var original = sales.get(first.id());
        var amount = sales.price(original).totals().total();
        var snapshot = contents.capture(original).orElseThrow();
        assertEquals(240, snapshot.lines().getFirst().quantity());
        assertNull(snapshot.sourceQuoteId());
        assertEquals(LocalDate.of(2026, 12, 9), snapshot.delivery().receivedOn());
        assertEquals(snapshot, contents.capture(original).orElseThrow());
        assertEquals(original, sales.get(first.id()));
        assertEquals(amount, sales.price(sales.get(first.id())).totals().total());
    }

    @Test @TestTransaction
    void historicalSourceQuoteWinsOverChangedPurchaseQuantities() {
        var f = fixture();
        var source = sales.createFromPurchaseOrder(request(f, null, SalesPurpose.STANDARD));
        var first = sales.createFromPurchaseOrder(request(f, plan(), SalesPurpose.PARTNER_ADVANCE));
        contents.delete(first.id()); entities.flush();
        var sourceEntity = entities.find(SalesOrderEntity.class, source.id());
        sourceEntity.lines.getFirst().deliveryWeek = "2026-W50";
        sourceEntity.lines.getFirst().quantity = 192;
        var invoiceEntity = entities.find(SalesOrderEntity.class, first.id());
        invoiceEntity.sourceQuoteId = source.id();
        entities.find(PurchaseOrderEntity.class, f.purchaseId()).lines.getFirst().quantity = 48;
        entities.flush(); entities.clear();
        var snapshot = contents.capture(sales.get(first.id())).orElseThrow();
        assertEquals(source.id(), snapshot.sourceQuoteId());
        assertEquals(192, snapshot.lines().getFirst().quantity());
        assertEquals(8, snapshot.totals().cartons());
        assertEquals("2026-W50", snapshot.delivery().deliveryWeek());
        assertTrue(sales.get(first.id()).lines().isEmpty());
    }

    @Test @TestTransaction
    void missingPackingIsUnknownAndRegularDocumentsGetNoAdvanceProjection() {
        var f = fixture();
        var product = entities.find(ProductEntity.class, f.productId());
        product.piecesPerCarton = 0; product.cartonLengthCm = null; product.cartonWeightKg = null;
        var purchase = entities.find(PurchaseOrderEntity.class, f.purchaseId());
        purchase.departurePort = null; purchase.destinationPort = "  ";
        entities.flush(); entities.clear();
        var first = sales.createFromPurchaseOrder(request(f, plan(), SalesPurpose.PARTNER_ADVANCE));
        var snapshot = contents.find(first).orElseThrow();
        assertEquals(240, snapshot.totals().pieces());
        assertNull(snapshot.totals().cartons()); assertNull(snapshot.totals().cbm());
        assertNull(snapshot.totals().weightKg()); assertNull(snapshot.totals().pallets());
        assertNull(snapshot.delivery().departurePort()); assertNull(snapshot.delivery().destinationPort());
        var ordinary = sales.create(f.customerId(), "BE", "DAP");
        assertTrue(contents.capture(ordinary).isEmpty());
        assertNull(resource.get(ordinary.id()).advanceContents());
        sales.delete(first.id());
        assertNotNull(entities.find(PartnerAdvanceContentsEntity.class, first.id()), "trash retains cargo facts for restoration");
    }

    @Test @TestTransaction
    void unscheduledAdvanceClaimsOneExactAmountWithSeparateCargoContext() {
        var f = fixture();
        var invoice = sales.createFromPurchaseOrder(request(f, null, SalesPurpose.PARTNER_ADVANCE));
        assertTrue(invoice.lines().isEmpty());
        assertEquals(1, invoice.extraLines().size());
        assertEquals(new BigDecimal("12000.00"), sales.price(invoice).totals().total());
        assertEquals(240, contents.find(invoice).orElseThrow().totals().pieces());
        assertEquals(invoice.id(), sales.createFromPurchaseOrder(request(f, null, SalesPurpose.PARTNER_ADVANCE)).id());
        assertEquals(new BigDecimal("12000.00"), financing.get(f.purchaseId()).committedAdvanceEur());
    }

    @Test @TestTransaction
    void ordinaryDraftUpdatesCannotDivergeFromCapturedCargoButNotesAndDueDatesRemainEditable() throws Exception {
        var f = fixture();
        var invoice = sales.createFromPurchaseOrder(request(f, null, SalesPurpose.PARTNER_ADVANCE));
        var captured = contents.find(invoice).orElseThrow();
        ObjectNode changed = json.valueToTree(invoice);
        changed.withArray("lines").addObject().put("productId", f.productId()).put("quantity", 120).put("unitPriceEur", 100);
        var wrongQuantity = json.treeToValue(changed, SalesOrder.class);
        assertThrows(BusinessRuleException.class, () -> sales.update(invoice.id(), wrongQuantity));
        changed = json.valueToTree(invoice);
        changed.put("countryCode", "NL");
        var wrongDestination = json.treeToValue(changed, SalesOrder.class);
        assertThrows(BusinessRuleException.class, () -> sales.update(invoice.id(), wrongDestination));
        changed = json.valueToTree(invoice);
        changed.put("notes", "Bel ons voor aankomst.");
        changed.put("invoiceDueDate", "2026-11-20");
        var updated = sales.update(invoice.id(), json.treeToValue(changed, SalesOrder.class));
        assertEquals("Bel ons voor aankomst.", updated.notes());
        assertEquals(LocalDate.of(2026, 11, 20), updated.invoiceDueDate());
        assertEquals(captured, contents.find(updated).orElseThrow());
        assertEquals(new BigDecimal("12000.00"), sales.price(updated).totals().total());
    }

    private static SalesOrderService.FromPurchaseOrderRequest request(Fixture f, PartnerAdvanceScheduleService.Request plan, SalesPurpose purpose) {
        return new SalesOrderService.FromPurchaseOrderRequest(f.purchaseId(), f.customerId(), "COST", BigDecimal.ZERO,
                true, new BigDecimal("50"), new BigDecimal("100"), false, List.of(), "PARTNER", null,
                purpose, SalesPaymentPlan.FULL, plan);
    }
    private static PartnerAdvanceScheduleService.Request plan() {
        return new PartnerAdvanceScheduleService.Request(List.of(
                new PartnerAdvanceScheduleService.RowRequest(null, "1/3 bij productie", null, new BigDecimal("4000"), LocalDate.of(2026, 10, 1)),
                new PartnerAdvanceScheduleService.RowRequest(null, "2/3 na productie", null, new BigDecimal("8000"), LocalDate.of(2026, 11, 1))), false);
    }
    private record Fixture(long purchaseId, long customerId, long productId) {}
    private Fixture fixture() {
        var partner = customers.create(new Customer(null, "Advance cargo partner", "Finance", null, null,
                "BE0000000000", "BE", Language.NL, "Test 1", "2000", "Antwerpen", "DAP", null, null, LocalDate.now()));
        var supplier = suppliers.save(new Supplier(null, "Cargo supplier", "CN", "Yiwu", null, null, null,
                Currency.USD, "FOB", "Ningbo", 30, null));
        var purchase = purchases.create(supplier.id(), new BigDecimal("0.14"), BigDecimal.ONE, BigDecimal.ZERO);
        purchases.setPartner(purchase.id(), new PurchaseOrderService.PartnerRequest(partner.id(), new BigDecimal("100"), new BigDecimal("50")));
        var product = new ProductEntity(); product.sku = "CARGO-" + UUID.randomUUID(); product.name = "Preserved rose display";
        product.supplierId = supplier.id(); product.cartonLengthCm = new BigDecimal("50"); product.cartonWidthCm = new BigDecimal("40");
        product.cartonHeightCm = new BigDecimal("30"); product.cartonWeightKg = new BigDecimal("12"); product.piecesPerCarton = 24;
        product.stockQuantity = 37; entities.persist(product); entities.flush();
        var stored = entities.find(PurchaseOrderEntity.class, purchase.id());
        stored.freightUsd = BigDecimal.ZERO; stored.originCosts = BigDecimal.ZERO; stored.destinationCostsEur = BigDecimal.ZERO;
        stored.defaultDutyRatePct = BigDecimal.ZERO; stored.extraRevenueEur = BigDecimal.ZERO;
        stored.expectedArrival = LocalDate.of(2026, 12, 8); stored.destinationPort = "Antwerp";
        var line = new PurchaseOrderLineEntity(); line.order = stored; line.productId = product.id;
        line.quantity = 240; line.exwPrice = new BigDecimal("50"); line.exwCurrency = Currency.EUR;
        stored.lines.add(line); entities.persist(line); entities.flush(); entities.clear();
        return new Fixture(purchase.id(), partner.id(), product.id);
    }
}
