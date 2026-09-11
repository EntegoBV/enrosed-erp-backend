package be.enrosed.sales.application;

import be.enrosed.catalog.adapter.out.persistence.ProductEntity;
import be.enrosed.catalog.application.CatalogMutationLock;
import be.enrosed.catalog.application.StockService;
import be.enrosed.catalog.domain.StockMovement;
import be.enrosed.sales.adapter.in.rest.SalesOrderResource;
import be.enrosed.sales.adapter.out.persistence.*;
import be.enrosed.sales.adapter.out.persistence.SalesEntities.*;
import be.enrosed.sales.application.port.out.SalesRepositories;
import be.enrosed.sales.domain.*;
import be.enrosed.shared.BusinessRuleException;
import be.enrosed.shared.Language;
import be.enrosed.shared.trash.DeletedItemsService;
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

@QuarkusTest @TestSecurity(user = "emre", roles = "admin")
class SalesSplitsTest {
    @Inject SalesOrderService sales;
    @Inject SalesSplits splits;
    @Inject SalesCustomerMessages customerMessages;
    @Inject SalesOrderResource resource;
    @Inject CustomerService customers;
    @Inject SalesRepositories.Orders orders;
    @Inject SalesRepositories.Events events;
    @Inject IncomingPaymentService incoming;
    @Inject CatalogMutationLock catalogLock;
    @Inject StockService stock;
    @Inject EntityManager em;
    @Inject DeletedItemsService trash;
    @Inject QuoteService quotes;
    @Inject SalesRepositories.Revisions revisions;

    @Test @TestTransaction
    void customerRevisionAndPartnerLinkCannotBypassFrozenDeliveryQuantities() {
        var source = source(DocumentType.OFFERTE);
        var result = confirm(source, selection(source), splits.preview(source.id(), selection(source)));
        var later = result.later();
        var revision = revisions.save(new QuoteRevision(null, later.id(), RevisionStatus.IN_AFWACHTING,
                Instant.now(), "Klant", "Graag wijzigen", null, null, null,
                List.of(new QuoteRevision.Line(null, later.lines().getFirst().productId(), 48, null))));
        var price = sales.price(later).totals().total();
        assertThrows(BusinessRuleException.class, () -> quotes.approveRevision(revision.id(), "emre", null));
        assertEquals(RevisionStatus.IN_AFWACHTING, revisions.findById(revision.id()).orElseThrow().status());
        assertThrows(BusinessRuleException.class, () -> sales.setPartnerDeal(later.id(), new SalesOrderService.PartnerDealRequest(999L, BigDecimal.TEN, null)));
        assertEquals(36, sales.price(sales.get(later.id())).totals().pieces());
        assertEquals(price, sales.price(sales.get(later.id())).totals().total());
    }

    @Test @TestTransaction
    void restoringOneInvoicePortionWithItsActiveSiblingPreservesGroupAndPricing() {
        var quote = source(DocumentType.OFFERTE);
        var source = sales.createInvoiceFrom(quote.id());
        var result = confirm(source, selection(source), splits.preview(source.id(), selection(source)));
        var price = sales.price(result.later()).totals().total();
        sales.delete(result.later().id());
        var item = trash.list().items().stream().filter(row -> row.sourceId() == result.later().id()
                && row.type() == be.enrosed.shared.trash.DeletedItemDtos.Type.INVOICE).findFirst().orElseThrow();
        assertTrue(trash.detail(item.id()).restoreAllowed());
        trash.restore(item.id());
        var restored = sales.get(result.later().id());
        assertEquals(result.groupId(), splits.fulfillment(restored).groupId());
        assertEquals(result.current().id(), splits.fulfillment(restored).siblingId());
        assertEquals(price, sales.price(restored).totals().total());
        assertEquals(source.id(), sales.createInvoiceFrom(quote.id()).id());
    }

    @Test @TestTransaction
    void EquivalentExtraLineDecimalScalesDoNotBlockAnOrdinaryDraftUpdate() {
        var source = source(DocumentType.FACTUUR);
        var result = confirm(source, selection(source), splits.preview(source.id(), selection(source)));
        var current = sales.get(result.current().id());
        var equivalent = current.withExtraLines(List.of(new SalesExtraLine("Verpakken", new BigDecimal("1"), new BigDecimal("9.9900"))));
        assertEquals(sales.price(current).totals().total(), sales.price(sales.update(current.id(), equivalent)).totals().total());
    }

    @Test @TestTransaction
    void vatRoundsOnEachDocumentAndAnyCentDifferenceIsExplicitInThePreview() {
        var source = source(DocumentType.FACTUUR);
        em.createNativeQuery("delete from discount_tier").executeUpdate();
        var country=em.find(CountryEntity.class,"BE");country.minOrderValue=BigDecimal.ZERO;country.handling=BigDecimal.ZERO;
        var lines=source.lines().stream().map(line->new SalesOrderLine(line.id(),line.productId(),line.quantity(),new BigDecimal("0.01"),BigDecimal.ZERO,null,new BigDecimal("0.001"))).toList();
        orders.save(SalesSplits.copy(source,source.id(),source.number(),lines,List.of(),BigDecimal.ZERO,BigDecimal.ZERO,null));em.flush();em.clear();
        source=sales.get(source.id());var preview=splits.preview(source.id(),selection(source));
        assertEquals(new BigDecimal("0.00"),preview.deltaExclVatEur());
        assertEquals(new BigDecimal("0.01"),preview.deltaInclVatEur());
        assertEquals(new BigDecimal("0.08"),preview.current().vatEur());assertEquals(new BigDecimal("0.08"),preview.later().vatEur());
        assertTrue(preview.warnings().stream().anyMatch(w->w.startsWith("Btw wordt per document")));
    }

    @Test @TestTransaction
    void unknownTransportNeedsTwoExplicitPricesAndPurchaseProvenanceIsRetained() {
        var source=source(DocumentType.FACTUUR);var stored=em.find(SalesOrderEntity.class,source.id());stored.freight=FreightState.TE_BEPALEN;
        stored.sourcePurchaseOrderId=998877L;em.flush();em.clear();source=sales.get(source.id());
        long id=source.id();var request=selection(source);
        assertThrows(BusinessRuleException.class,()->splits.preview(id,request));
        var explicit=new SalesSplits.Request(request.lines(),null,new BigDecimal("10"),BigDecimal.ZERO,null,null,null,null);
        var result=confirm(source,explicit,splits.preview(id,explicit));
        assertEquals(998877L,result.current().sourcePurchaseOrderId());assertEquals(998877L,result.later().sourcePurchaseOrderId());
        assertNull(result.later().sourceQuoteId());
    }

    @Test @TestTransaction
    void invoiceSplitRetainsOriginalQuoteLinkOnlyOnFirstPartAndCannotCreateAThirdClaim() {
        var quote=source(DocumentType.OFFERTE);var invoice=sales.createInvoiceFrom(quote.id());
        var result=confirm(invoice,selection(invoice),splits.preview(invoice.id(),selection(invoice)));
        assertEquals(quote.id(),result.current().sourceQuoteId());assertNull(result.later().sourceQuoteId());
        assertEquals(result.current().id(),sales.createInvoiceFrom(quote.id()).id());
        assertEquals(result.groupId(),resource.get(result.later().id()).fulfillment().groupId());
    }

    @Test @TestTransaction
    void declinedOrExpiredPortionsCannotBeReleasedForDelivery() {
        var source=source(DocumentType.OFFERTE);var result=confirm(source,selection(source),splits.preview(source.id(),selection(source)));
        long id=result.later().id();em.find(SalesOrderEntity.class,id).status=QuoteStatus.AFGEWEZEN;em.flush();em.clear();
        assertThrows(BusinessRuleException.class,()->splits.ready(id));
        em.find(SalesOrderEntity.class,id).status=QuoteStatus.VERLOPEN;em.flush();em.clear();
        assertThrows(BusinessRuleException.class,()->splits.ready(id));
    }

    @Test @TestSecurity(user="viewer",roles="viewer")
    void splitAndFulfillmentEndpointsRemainAdminOnly() {
        io.restassured.RestAssured.given().get("/api/sales-orders/1/split").then().statusCode(403);
        io.restassured.RestAssured.given().contentType("application/json").body("{}").post("/api/sales-orders/1/split").then().statusCode(403);
        io.restassured.RestAssured.given().contentType("application/json").post("/api/sales-orders/1/fulfillment-ready").then().statusCode(403);
    }

    @Test @TestTransaction
    void productRowsKeepTheirOwnPricesAndStaffelsAndAllCentsAcrossTwoConcepts() {
        var source = source(DocumentType.OFFERTE);
        var before = sales.price(source);
        var preview = splits.preview(source.id(), selection(source));
        assertEquals(0, preview.deltaExclVatEur().signum());
        var result = confirm(source, selection(source), preview);
        assertEquals(source.id(), result.current().id());
        assertNotEquals(source.id(), result.later().id());
        assertNotEquals(result.current().number(), result.later().number());
        var first = sales.price(sales.get(result.current().id())); var later = sales.price(sales.get(result.later().id()));
        assertEquals(72, first.totals().pieces() + later.totals().pieces());
        assertEquals(before.totals().total(), first.totals().total().add(later.totals().total()));
        assertEquals(before.totals().goodsTotal(), first.totals().goodsTotal().add(later.totals().goodsTotal()));
        assertEquals(before.totals().lineDiscountTotal(), first.totals().lineDiscountTotal().add(later.totals().lineDiscountTotal()));
        assertEquals(before.totals().orderDiscountAmount(), first.totals().orderDiscountAmount().add(later.totals().orderDiscountAmount()));
        assertEquals(before.totals().extraDiscountAmount(), first.totals().extraDiscountAmount().add(later.totals().extraDiscountAmount()));
        assertEquals(before.totals().costTotal(), first.totals().costTotal().add(later.totals().costTotal()));
        assertEquals(before.totals().handling(), first.totals().handling()); assertEquals(0, later.totals().handling().signum());
        assertEquals(before.totals().extraLinesTotal(), first.totals().extraLinesTotal()); assertTrue(result.later().extraLines().isEmpty());
        for (int i=0; i<2; i++) {
            assertEquals(before.lines().get(i).unitPrice(), later.lines().get(i).unitPrice());
            assertEquals(before.lines().get(i).tierPercent(), later.lines().get(i).tierPercent());
        }
        assertTrue(first.validation().meetsMinimum()); assertTrue(later.validation().meetsMinimum());
        assertTrue(first.totals().goodsTotal().compareTo(new BigDecimal("300")) < 0);
        for (var part : List.of(result.current(), result.later())) {
            assertEquals(QuoteStatus.CONCEPT, part.status()); assertNull(part.sentAt()); assertNull(part.paidAt()); assertNull(part.goodsShippedAt());
            assertFalse(incoming.hasHistory(part.id()));
            assertTrue(events.findByOrder(part.id()).stream().noneMatch(e -> e.type() == QuoteEvent.Type.VERSTUURD || e.type() == QuoteEvent.Type.UITGEREIKT));
        }
        assertEquals(SalesSplits.Status.PLANNED, splits.fulfillment(result.current()).status());
        assertEquals(SalesSplits.Status.WAITING_FOR_STOCK, splits.fulfillment(result.later()).status());
    }

    @Test @TestTransaction
    void explicitFreightAndExtraDiscountOverridesShowDeltaAndRemainEditableWithoutRepricingStaffels() {
        var source = source(DocumentType.FACTUUR);
        var request = new SalesSplits.Request(selection(source).lines(), "2026-W45", new BigDecimal("25"), new BigDecimal("60"),
                new BigDecimal("10"), BigDecimal.ZERO, null, null);
        var preview = splits.preview(source.id(), request);
        assertNotEquals(0, preview.deltaExclVatEur().signum());
        assertEquals(preview.current().totalExclVatEur().add(preview.later().totalExclVatEur()).subtract(preview.original().totalExclVatEur()), preview.deltaExclVatEur());
        var result = confirm(source, request, preview);
        var later = sales.get(result.later().id());
        assertEquals(new BigDecimal("60.00"), sales.price(later).totals().freight());
        var edited = SalesSplits.copy(later, later.id(), later.number(), later.lines(), later.extraLines(), new BigDecimal("45.50"), new BigDecimal("7"), later.sourceQuoteId());
        var saved = sales.update(later.id(), edited);
        assertEquals(new BigDecimal("45.50"), sales.price(saved).totals().freight());
        assertEquals(0, sales.price(saved).totals().extraDiscountPercent().compareTo(new BigDecimal("7")));
        assertEquals(new BigDecimal("10.0000"), sales.price(saved).lines().getFirst().tierPercent());
        assertThrows(BusinessRuleException.class, () -> splits.preview(source.id(), new SalesSplits.Request(request.lines(), null, new BigDecimal("-1"), null, null, null, null, null)));
    }

    @Test @TestTransaction
    void idempotentConfirmReturnsSameDocumentsAndRejectsChangedSelectionUnderSameKey() {
        var source = source(DocumentType.FACTUUR); var request = selection(source);
        var preview = splits.preview(source.id(), request); String key = UUID.randomUUID().toString();
        var exact = withConfirm(request, preview.previewToken(), key);
        var first = splits.split(source.id(), exact); var second = splits.split(source.id(), exact);
        assertEquals(first.groupId(), second.groupId()); assertEquals(first.later().id(), second.later().id());
        assertEquals(1L, em.createQuery("select count(g) from SalesSplitGroupEntity g where g.rootOrderId=:id", Long.class).setParameter("id", source.id()).getSingleResult());
        var different = new SalesSplits.Request(request.lines(), "2026-W46", preview.previewToken(), key);
        assertThrows(BusinessRuleException.class, () -> splits.split(source.id(), different));
    }

    @Test @TestTransaction
    void changedSourceAfterPreviewRejectsWithoutCreatingChildOrMovingQuantities() {
        var source = source(DocumentType.OFFERTE); var request = selection(source); var preview = splits.preview(source.id(), request);
        em.find(SalesOrderEntity.class, source.id()).manualFreightEur = new BigDecimal("45"); em.flush(); em.clear();
        assertThrows(BusinessRuleException.class, () -> splits.split(source.id(), withConfirm(request, preview.previewToken(), UUID.randomUUID().toString())));
        assertNull(splits.fulfillment(sales.get(source.id())));
        assertEquals(72, sales.get(source.id()).lines().stream().mapToInt(SalesOrderLine::quantity).sum());
    }

    @Test @TestTransaction
    void invalidCartonAndEmptyPortionsNeverMutateTheOrder() {
        var source = source(DocumentType.OFFERTE);
        assertThrows(BusinessRuleException.class, () -> splits.preview(source.id(), new SalesSplits.Request(List.of(new SalesSplits.Choice(source.lines().getFirst().id(), 1)), null, null, null)));
        assertThrows(BusinessRuleException.class, () -> splits.preview(source.id(), new SalesSplits.Request(List.of(new SalesSplits.Choice(source.lines().getFirst().id(), 0)), null, null, null)));
        assertThrows(BusinessRuleException.class, () -> splits.preview(source.id(), new SalesSplits.Request(List.of(new SalesSplits.Choice(-1L, 12)), null, null, null)));
        assertEquals(72, sales.get(source.id()).lines().stream().mapToInt(SalesOrderLine::quantity).sum());
    }

    @Test @TestTransaction
    void catalogAndDiscountChangesDoNotAlterCommittedAmountsAndInvoiceConversionInheritsThePart() {
        var source = source(DocumentType.OFFERTE); var result = confirm(source, selection(source), splits.preview(source.id(), selection(source)));
        var before = sales.price(result.later());
        em.find(SalesOrderEntity.class, result.later().id()).customerMessage = "Gelieve bij de achterdeur te leveren.";
        var product = em.find(ProductEntity.class, source.lines().getFirst().productId());
        product.fixedSalesPriceEur = new BigDecimal("999"); product.landedCostEur = new BigDecimal("100"); product.piecesPerCarton = 7;
        em.createNativeQuery("delete from discount_tier").executeUpdate(); em.flush(); em.clear();
        var after = sales.price(sales.get(result.later().id()));
        assertEquals(before.totals().total(), after.totals().total()); assertEquals(before.totals().costTotal(), after.totals().costTotal());
        assertEquals(36, after.totals().pieces());
        var invoice = sales.createInvoiceFrom(result.later().id());
        assertEquals(before.totals().total(), sales.price(invoice).totals().total());
        assertEquals(result.groupId(), resource.get(invoice.id()).fulfillment().groupId());
        assertEquals(2, resource.get(invoice.id()).fulfillment().part());
        assertEquals(invoice.id(), sales.createInvoiceFrom(result.later().id()).id());
        assertEquals(QuoteStatus.CONCEPT, invoice.status());
        assertEquals("Gelieve bij de achterdeur te leveren.", invoice.customerMessage());
    }

    @Test @TestTransaction
    void usedDocumentsAndFinancialMutationsAreBlockedButConceptIssuanceStillWorks() {
        var source = source(DocumentType.FACTUUR); var result = confirm(source, selection(source), splits.preview(source.id(), selection(source)));
        var later = sales.get(result.later().id());
        var changedLine = later.lines().getFirst();
        var changed = SalesSplits.copy(later, later.id(), later.number(), List.of(new SalesOrderLine(changedLine.id(), changedLine.productId(), 12,
                changedLine.unitPriceEur(), changedLine.manualDiscountPct(), changedLine.deliveryWeek(), changedLine.unitCostEur())), later.extraLines(), later.manualFreightEur(), later.extraDiscountPct(), null);
        assertThrows(BusinessRuleException.class, () -> sales.update(later.id(), changed));
        assertThrows(BusinessRuleException.class, () -> sales.duplicate(later.id()));
        assertEquals(QuoteStatus.UITGEREIKT, sales.issueInvoice(later.id()).status());
        var other = source(DocumentType.FACTUUR);
        em.find(SalesOrderEntity.class, other.id()).status = QuoteStatus.UITGEREIKT; em.flush(); em.clear();
        assertFalse(splits.eligibility(other.id()).allowed());
        em.find(SalesOrderEntity.class, other.id()).status = QuoteStatus.CONCEPT;
        var payment = new SalesPaymentEntity(); payment.salesOrderId = other.id(); payment.amountEur = BigDecimal.ONE;
        payment.receivedAt = Instant.now().minusSeconds(60); payment.recordedAt = payment.receivedAt; payment.timeZone = "Europe/Brussels";
        payment.voidedAt = Instant.now(); em.persist(payment); em.flush(); em.clear();
        assertFalse(splits.eligibility(other.id()).allowed());
    }

    @Test @TestTransaction
    void laterPortionRequiresStockThenExplicitReleaseAndOnlyRealShipmentChangesShippedStatus() {
        var source = source(DocumentType.FACTUUR); var result = confirm(source, selection(source), splits.preview(source.id(), selection(source)));
        long laterId = result.later().id(); long productId = source.lines().getFirst().productId();
        em.find(ProductEntity.class, productId).stockQuantity = 12; em.flush(); em.clear();
        assertThrows(BusinessRuleException.class, () -> splits.ready(laterId));
        assertEquals(SalesSplits.Status.WAITING_FOR_STOCK, splits.fulfillment(sales.get(laterId)).status());
        long warehouse = stock.locations().stream().filter(location -> location.receivesByDefault()).findFirst().orElseThrow().id();
        stock.setLevel(productId, warehouse, 72, StockMovement.Kind.STOCKTAKE, "Split QA stock");
        stock.setLevel(source.lines().get(1).productId(), warehouse, 24, StockMovement.Kind.STOCKTAKE, "Split QA stock");
        em.flush(); em.clear();
        splits.ready(laterId);
        assertEquals(SalesSplits.Status.PLANNED, splits.fulfillment(sales.get(laterId)).status());
        assertNull(sales.get(laterId).goodsShippedAt());
        var invoice = sales.issueInvoice(laterId); assertNull(invoice.sentAt());
        var shipped = sales.shipGoods(laterId);
        assertEquals(SalesSplits.Status.SHIPPED, splits.fulfillment(shipped).status());
        assertEquals(48, em.find(ProductEntity.class, productId).stockQuantity);
        assertNull(sales.get(result.current().id()).goodsShippedAt());
    }

    @Test @TestTransaction
    void originalWebsiteMessageSurvivesChannelAndPrefixChangesAndBothDeliveryDocuments() {
        var source = source(DocumentType.OFFERTE);
        var row = em.find(SalesOrderEntity.class, source.id()); row.salesChannel = "WEBSITE";
        row.notes = "Graag eerst de rode dozen leveren."; row.internalNotes = "[WEBSITE_AANVRAAG] test";
        em.flush(); em.clear(); source = sales.get(source.id());
        var changes = source.withSalesChannel("DIRECT");
        var saved = sales.update(source.id(), changes);
        em.find(SalesOrderEntity.class, saved.id()).internalNotes = "Eigen opvolging"; em.flush(); em.clear();
        var message = resource.get(saved.id()); assertTrue(message.customerRequestMessageReadonly()); assertEquals(row.notes, message.customerRequestMessage());
        var changed = SalesSplits.copy(sales.get(saved.id()), saved.id(), saved.number(), saved.lines(), saved.extraLines(), saved.manualFreightEur(), saved.extraDiscountPct(), null);
        var changedEntity = em.find(SalesOrderEntity.class, saved.id());
        String originalNotes = changedEntity.notes;
        // JSON roundtrip is unnecessary: a new order record with a different note demonstrates the ordinary PUT guard.
        SalesOrder malicious = new SalesOrder(changed.id(), changed.number(), changed.customerId(), changed.countryCode(), changed.orderDate(), changed.validUntil(), changed.status(),
                changed.incoterm(), changed.paymentTerms(), "Staff replacement", changed.markupMode(), changed.orderMarkupPct(), changed.extraDiscountPct(), changed.extraDiscountLabel(),
                null,null,null,0,null,null,null,changed.internalNotes(),changed.deliveryTerms(),changed.freight(),changed.manualFreightEur(),changed.loadMode(),changed.palletProfile(),changed.maxPalletHeightCm(),
                changed.freightPricingStrategy(),changed.freightRatePerCbmEur(),changed.freightCarrierId(),changed.freightCarrierExtraEur(),changed.docType(),changed.invoiceDueDate(),null,null,null,changed.lines(),changed.pallets());
        assertThrows(BusinessRuleException.class, () -> sales.update(saved.id(), malicious));
        var original = sales.get(saved.id()); var result = confirm(original, selection(original), splits.preview(original.id(), selection(original)));
        assertTrue(resource.get(result.later().id()).customerRequestMessageReadonly());
        assertEquals(originalNotes, resource.get(result.later().id()).customerRequestMessage());
        assertEquals(originalNotes, sales.get(saved.id()).notes());
    }

    private SalesSplits.Result confirm(SalesOrder source, SalesSplits.Request request, SalesSplits.Preview preview) {
        return splits.split(source.id(), withConfirm(request, preview.previewToken(), UUID.randomUUID().toString()));
    }
    private SalesSplits.Request withConfirm(SalesSplits.Request r, String token, String requestId) {
        return new SalesSplits.Request(r.lines(), r.deliveryWeek(), r.currentFreightEur(), r.laterFreightEur(), r.currentExtraDiscountPct(), r.laterExtraDiscountPct(), token, requestId);
    }
    private SalesSplits.Request selection(SalesOrder source) {
        return new SalesSplits.Request(List.of(new SalesSplits.Choice(source.lines().get(0).id(),24), new SalesSplits.Choice(source.lines().get(1).id(),12)), "2026-W45", null, null);
    }
    private SalesOrder source(DocumentType type) {
        catalogLock.acquire();
        em.createNativeQuery("delete from discount_tier").executeUpdate();
        var country = em.find(CountryEntity.class,"BE"); country.minOrderValue = new BigDecimal("300"); country.handling = new BigDecimal("15.50");
        var buyer = customers.create(new Customer(null,"Split QA","Buyer",null,null,"BE0123456789","BE",Language.NL,
                "Voorbeeld 1","2400","Mol","DAP","30 dagen",null,LocalDate.now()));
        var product = new ProductEntity(); product.sku="SPLIT-"+UUID.randomUUID(); product.name="Testrozen"; product.active=true;
        product.cartonLengthCm=BigDecimal.TEN;product.cartonWidthCm=BigDecimal.TEN;product.cartonHeightCm=BigDecimal.TEN;
        product.cartonWeightKg=BigDecimal.ONE;product.piecesPerCarton=12;product.stockQuantity=100;product.inventoryKnown=true;
        product.landedCostEur=new BigDecimal("2.1111"); product.fixedSalesPriceEur=new BigDecimal("7.1234"); em.persist(product);em.flush();
        var secondProduct = new ProductEntity();secondProduct.sku="SPLIT-B-"+UUID.randomUUID();secondProduct.name="Testrozen blauw";secondProduct.active=true;
        secondProduct.cartonLengthCm=BigDecimal.TEN;secondProduct.cartonWidthCm=BigDecimal.TEN;secondProduct.cartonHeightCm=BigDecimal.TEN;
        secondProduct.cartonWeightKg=BigDecimal.ONE;secondProduct.piecesPerCarton=12;secondProduct.stockQuantity=100;secondProduct.inventoryKnown=true;
        secondProduct.landedCostEur=new BigDecimal("2.1111");secondProduct.fixedSalesPriceEur=new BigDecimal("8.4321");em.persist(secondProduct);em.flush();
        for(long productId:List.of(product.id,secondProduct.id))for(int threshold:new int[]{24,48}) {var tier=new DiscountTierEntity();tier.scope=TierScope.LINE;tier.productId=productId;tier.minQuantity=threshold;tier.percent=new BigDecimal(threshold==24?"7":"10");em.persist(tier);}
        var orderTier=new DiscountTierEntity();orderTier.scope=TierScope.ORDER;orderTier.minQuantity=60;orderTier.percent=new BigDecimal("5");em.persist(orderTier);em.flush();
        var created=sales.create(buyer.id(),"BE","DAP",type);
        var lines=List.of(new SalesOrderLine(null,product.id,48,new BigDecimal("7.1234"),new BigDecimal("3"),null,new BigDecimal("2.1111")),
                new SalesOrderLine(null,secondProduct.id,24,new BigDecimal("8.4321"),new BigDecimal("5"),null,new BigDecimal("2.1111")));
        var order=SalesSplits.copy(created,created.id(),created.number(),lines,List.of(new SalesExtraLine("Verpakken",BigDecimal.ONE,new BigDecimal("9.99"))),new BigDecimal("30.25"),new BigDecimal("3.9"),null);
        orders.save(order);em.flush();em.clear();return sales.get(created.id());
    }
}
