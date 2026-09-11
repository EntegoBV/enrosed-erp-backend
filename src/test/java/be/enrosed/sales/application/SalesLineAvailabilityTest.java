package be.enrosed.sales.application;

import be.enrosed.catalog.adapter.out.persistence.ProductEntity;
import be.enrosed.catalog.application.CatalogMutationLock;
import be.enrosed.catalog.application.StockService;
import be.enrosed.catalog.domain.StockMovement;
import be.enrosed.sales.adapter.in.rest.CustomerQuoteMapper;
import be.enrosed.sales.adapter.in.rest.SalesOrderResource;
import be.enrosed.sales.adapter.out.persistence.SalesEntities.*;
import be.enrosed.sales.adapter.out.persistence.SalesPaymentEntity;
import be.enrosed.sales.application.port.out.SalesRepositories;
import be.enrosed.sales.domain.*;
import be.enrosed.shared.BusinessRuleException;
import be.enrosed.shared.Language;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest @TestSecurity(user="emre",roles="admin")
class SalesLineAvailabilityTest {
    @Inject SalesOrderService sales;
    @Inject SalesSplits splits;
    @Inject SalesOrderResource resource;
    @Inject SalesRepositories.Orders orders;
    @Inject SalesRepositories.Revisions revisions;
    @Inject QuoteService quotes;
    @Inject CustomerService customers;
    @Inject StockService stock;
    @Inject CatalogMutationLock catalogLock;
    @Inject EntityManager em;

    @Test @TestTransaction
    void fixedDiscountCentAllocationKeepsVisibleGoodsArithmeticAndFullRestoreExact() {
        var source=source();var a=source.lines().get(0);var b=source.lines().get(1);
        var order=SalesSplits.copy(source,source.id(),source.number(),List.of(a.withAvailability(false,1,1),b.withAvailability(true,0,1)),List.of(),BigDecimal.ZERO,new BigDecimal("20"),null);
        var baselines=List.of(new SalesSplitPricing.Line(a.productId(),1,new BigDecimal("0.03"),BigDecimal.ZERO,BigDecimal.ZERO,new BigDecimal("0.03"),BigDecimal.ZERO,new BigDecimal("0.03"),BigDecimal.ZERO,BigDecimal.ZERO),
                new SalesSplitPricing.Line(b.productId(),1,new BigDecimal("0.03"),BigDecimal.ZERO,BigDecimal.ZERO,new BigDecimal("0.03"),BigDecimal.ZERO,new BigDecimal("0.03"),BigDecimal.ZERO,BigDecimal.ZERO));
        var terms=new SalesSplitPricing(baselines,new BigDecimal("16.6667"),new BigDecimal("0.01"),new BigDecimal("20"),new BigDecimal("0.01"),BigDecimal.ZERO,BigDecimal.ZERO,true,BigDecimal.ZERO,VatTreatment.BINNENLAND,new BigDecimal("0.04"));
        var active=SalesSplits.activePricing(order,terms);
        assertEquals(new BigDecimal("0.01"),active.goodsTotal());
        assertEquals(active.lines().stream().map(SalesSplitPricing.Line::net).reduce(BigDecimal.ZERO,BigDecimal::add).subtract(active.orderDiscountAmount()).subtract(active.extraDiscountAmount()),active.goodsTotal());
        var restored=SalesSplits.activePricing(order.withLinesAndPallets(List.of(a.withAvailability(false,1,1),b.withAvailability(false,1,1)),List.of()),terms);
        assertEquals(new BigDecimal("0.04"),restored.goodsTotal());
    }

    @Test @TestTransaction
    void readyIgnoresExcludedUnknownStockButRejectsEntirelyExcludedDelivery() {
        var source=source();var request=new SalesSplits.Request(List.of(new SalesSplits.Choice(source.lines().get(0).id(),24),new SalesSplits.Choice(source.lines().get(1).id(),12)),null,null,null);
        var result=confirm(source,request,splits.preview(source.id(),request));
        var later=sales.update(result.later().id(),change(result.later(),0,true,0,null));
        em.find(ProductEntity.class,later.lines().getFirst().productId()).inventoryKnown=false;em.flush();em.clear();
        assertEquals(SalesSplits.Status.PLANNED,splits.fulfillment(splits.ready(later.id())).status());
        var all=sales.update(later.id(),change(sales.get(later.id()),1,true,0,null));
        assertThrows(BusinessRuleException.class,()->splits.ready(all.id()));
    }

    @Test @TestTransaction
    void unsavedRequestedQuantityAndUnusedPortalTokenArePreservedAndOnlyAffectedPalletsArePruned() {
        var source=source();em.find(SalesOrderEntity.class,source.id()).portalToken=UUID.randomUUID().toString();em.flush();em.clear();source=sales.get(source.id());
        var proposed=change(source,1,true,0,48);
        var empty=new OrderPallet(null,"Lege planning","Europallet",100,List.of());
        var held=new OrderPallet(null,"Te verwijderen","Europallet",100,List.of(new OrderPallet.Item(source.lines().get(1).productId(),2)));
        var preview=resource.preview(source.id(),proposed.withLinesAndPallets(proposed.lines(),List.of(empty,held)));
        assertEquals(48,preview.order().lines().get(1).requestedQuantity());assertEquals(List.of(empty),preview.order().pallets());
        var saved=sales.update(source.id(),proposed);
        assertEquals(48,saved.lines().get(1).requestedQuantity());
        assertEquals(48,sales.update(saved.id(),change(saved,1,false,0,null)).lines().get(1).quantity());
        var unchanged=resource.preview(source.id(),sales.get(source.id()).withLinesAndPallets(sales.get(source.id()).lines(),List.of(empty)));
        assertEquals(List.of(empty),unchanged.order().pallets());
    }

    @Test @TestTransaction
    void previewAndSaveKeepExcludedRowAndRemoveOnlyItsMoneyQuantitiesAndDelivery() {
        var source=source();long productId=source.lines().getFirst().productId();
        var preview=resource.preview(source.id(),change(source,0,true,0,48));
        assertEquals(48,sales.get(source.id()).lines().getFirst().quantity());
        var parked=preview.order().lines().getFirst();assertTrue(parked.isUnavailable());assertEquals(0,parked.quantity());assertEquals(48,parked.requestedQuantity());
        var displayed=preview.priced().lines().getFirst();assertTrue(displayed.unavailable());assertEquals(48,displayed.requestedQuantity());
        assertEquals(0,displayed.net().signum());assertEquals(0,displayed.costTotal().signum());assertEquals(0,displayed.cartons());assertEquals(0,displayed.pallets());
        assertEquals(0,displayed.cbm().signum());assertEquals(0,displayed.weightKg().signum());assertFalse(displayed.inStock());assertNull(displayed.deliveryWeek());assertNull(displayed.deliveryDate());
        assertEquals(48,preview.priced().totals().pieces());assertEquals(0,preview.priced().totals().orderDiscountPercent().signum());
        var saved=sales.update(source.id(),change(source,0,true,0,48));em.flush();em.clear();
        assertEquals(preview.priced().totals().total(),sales.price(sales.get(saved.id())).totals().total());
        assertTrue(em.find(ProductEntity.class,productId).active);assertEquals(100,em.find(ProductEntity.class,productId).stockQuantity);
    }

    @Test @TestTransaction
    void oldClientDoesNotClearAvailabilityOrForgeRememberedQuantityAndRestoreIsExact() {
        var source=source();var original=sales.price(source).totals().total();
        var parked=sales.update(source.id(),change(source,0,true,0,48));
        var legacy=sales.update(source.id(),change(parked,0,null,777,888));
        assertTrue(legacy.lines().getFirst().isUnavailable());assertEquals(0,legacy.lines().getFirst().quantity());assertEquals(48,legacy.lines().getFirst().requestedQuantity());
        var restored=sales.update(source.id(),change(legacy,0,false,0,777));
        assertEquals(48,restored.lines().getFirst().quantity());assertEquals(48,restored.lines().getFirst().requestedQuantity());
        assertEquals(original,sales.price(restored).totals().total());
    }

    @Test @TestTransaction
    void allExcludedDraftCanBeSavedButNeitherIssuedNorSentEvenWithExtraCharges() {
        var source=source();var all=source;
        for(int i=0;i<all.lines().size();i++) all=change(all,i,true,0,null);
        var saved=sales.update(source.id(),all);
        assertEquals(0,sales.price(saved).totals().pieces());
        assertThrows(BusinessRuleException.class,()->sales.issueInvoice(saved.id()));
        assertThrows(BusinessRuleException.class,()->sales.validateForSend(saved));
        assertEquals(QuoteStatus.CONCEPT,sales.get(saved.id()).status());assertNull(sales.get(saved.id()).sentAt());
    }

    @Test @TestTransaction
    void legacyUnresolvedZeroStaysDistinctAndUnknownRequestedAmountNeedsExplicitRestoreQuantity() {
        var source=source();var zero=sales.update(source.id(),change(source,0,null,0,null));
        assertFalse(zero.lines().getFirst().isUnavailable());assertNull(zero.lines().getFirst().requestedQuantity());
        assertThrows(BusinessRuleException.class,()->sales.validateForSend(zero));
        assertThrows(BusinessRuleException.class,()->sales.issueInvoice(zero.id()));
        var parked=sales.update(source.id(),change(zero,0,true,0,null));assertNull(parked.lines().getFirst().requestedQuantity());
        assertThrows(BusinessRuleException.class,()->sales.update(parked.id(),change(parked,0,false,0,null)));
        assertEquals(24,sales.update(parked.id(),change(parked,0,false,24,null)).lines().getFirst().quantity());
    }

    @Test @TestTransaction
    void excludingRemovesManualPalletAssignmentsAndActualShipmentOnlyBooksActiveProducts() {
        var source=source();var pallets=List.of(new OrderPallet(null,"Rood","Europallet",100,List.of(new OrderPallet.Item(source.lines().getFirst().productId(),4))),
                new OrderPallet(null,"Overige","Europallet",100,List.of(new OrderPallet.Item(source.lines().get(1).productId(),2),new OrderPallet.Item(source.lines().get(2).productId(),2))));
        source=orders.save(source.withLinesAndPallets(source.lines(),pallets));
        var parked=sales.update(source.id(),change(source,0,true,0,null));
        assertEquals(1,parked.pallets().size());assertEquals("Overige",parked.pallets().getFirst().label());
        long warehouse=stock.locations().stream().filter(location->location.receivesByDefault()).findFirst().orElseThrow().id();
        for(var line:parked.lines())stock.setLevel(line.productId(),warehouse,100,StockMovement.Kind.STOCKTAKE,"Availability QA");
        sales.issueInvoice(parked.id());sales.shipGoods(parked.id());
        assertEquals(100,em.find(ProductEntity.class,parked.lines().getFirst().productId()).stockQuantity);
        assertEquals(76,em.find(ProductEntity.class,parked.lines().get(1).productId()).stockQuantity);
    }

    @Test @TestTransaction
    void partnerUsedInvoiceAndPaymentHistoryCannotUseExclusion() {
        var source=source();long id=source.id();var changed=change(source,0,true,0,null);
        em.find(SalesOrderEntity.class,id).purpose=SalesPurpose.PARTNER_ADVANCE;em.flush();em.clear();
        assertThrows(BusinessRuleException.class,()->sales.update(id,changed));
        em.find(SalesOrderEntity.class,id).purpose=SalesPurpose.STANDARD;em.find(SalesOrderEntity.class,id).status=QuoteStatus.UITGEREIKT;em.flush();em.clear();
        assertThrows(BusinessRuleException.class,()->sales.update(id,changed));
        em.find(SalesOrderEntity.class,id).status=QuoteStatus.CONCEPT;
        var payment=new SalesPaymentEntity();payment.salesOrderId=id;payment.amountEur=BigDecimal.ONE;payment.receivedAt=Instant.now();payment.recordedAt=payment.receivedAt;
        payment.timeZone="Europe/Brussels";payment.voidedAt=Instant.now();em.persist(payment);em.flush();em.clear();
        assertThrows(BusinessRuleException.class,()->sales.update(id,changed));
    }

    @Test @TestTransaction
    void splitExclusionIsNotLaterZeroAndFrozenAllocationRestoresWithoutRepricing() {
        var source=source();var request=new SalesSplits.Request(List.of(new SalesSplits.Choice(source.lines().get(0).id(),0,true),
                new SalesSplits.Choice(source.lines().get(1).id(),12),new SalesSplits.Choice(source.lines().get(2).id(),12)),null,null,null);
        var preview=splits.preview(source.id(),request);assertEquals(48,preview.excludedQuantity());assertEquals(48,preview.current().unavailableQuantity());
        assertEquals(0,preview.later().unavailableQuantity());
        var result=confirm(source,request,preview);var baseline=sales.price(result.current()).totals().total();
        assertTrue(result.current().lines().getFirst().isUnavailable());assertEquals(3,result.current().lines().size());assertEquals(2,result.later().lines().size());
        var restored=sales.update(result.current().id(),change(result.current(),0,false,999,999));
        assertEquals(48,restored.lines().getFirst().quantity());var restoredAmount=sales.price(restored).totals().total();
        em.createNativeQuery("delete from discount_tier").executeUpdate();em.find(ProductEntity.class,restored.lines().getFirst().productId()).fixedSalesPriceEur=new BigDecimal("999");em.flush();em.clear();
        var parked=sales.update(restored.id(),change(sales.get(restored.id()),0,true,0,null));assertEquals(baseline,sales.price(parked).totals().total());
        var again=sales.update(parked.id(),change(parked,0,false,0,null));assertEquals(restoredAmount,sales.price(again).totals().total());
        assertEquals(preview.later().totalExclVatEur(),sales.price(sales.get(result.later().id())).totals().total());
    }

    @Test @TestTransaction
    void preexistingExcludedKnownQuantityHasNoInitialSplitChargeAndCanLaterRestoreItsOwnFrozenReference() {
        var source=source();source=sales.update(source.id(),change(source,0,true,0,null));
        var request=new SalesSplits.Request(List.of(new SalesSplits.Choice(source.lines().get(1).id(),12),new SalesSplits.Choice(source.lines().get(2).id(),12)),null,null,null);
        var preview=splits.preview(source.id(),request);assertEquals(0,preview.excludedQuantity());assertEquals(0,preview.deltaExclVatEur().signum());
        var result=confirm(source,request,preview);var first=sales.price(result.current());
        var restored=sales.update(result.current().id(),change(result.current(),0,false,0,null));var priced=sales.price(restored);
        assertEquals(48,restored.lines().getFirst().quantity());assertTrue(priced.totals().total().compareTo(first.totals().total())>0);
        for(int i=1;i<3;i++)assertEquals(first.lines().get(i).net(),priced.lines().get(i).net());
        em.createNativeQuery("delete from discount_tier").executeUpdate();em.flush();em.clear();
        var parked=sales.update(restored.id(),change(sales.get(restored.id()),0,true,0,null));assertEquals(first.totals().total(),sales.price(parked).totals().total());
        assertEquals(priced.totals().total(),sales.price(sales.update(parked.id(),change(parked,0,false,0,null))).totals().total());
    }

    @Test @TestTransaction
    void portalRevisionCannotReactivateExcludedProductAndZeroProposalDoesNotDeleteItsRow() {
        var source=source();var parked=sales.update(source.id(),change(source,0,true,0,null));
        var forbidden=revisions.save(new QuoteRevision(null,source.id(),RevisionStatus.IN_AFWACHTING,Instant.now(),"Klant",null,null,null,null,
                List.of(new QuoteRevision.Line(null,parked.lines().getFirst().productId(),48,null))));
        assertThrows(BusinessRuleException.class,()->quotes.approveRevision(forbidden.id(),"emre",null));
        var allowed=revisions.save(new QuoteRevision(null,source.id(),RevisionStatus.IN_AFWACHTING,Instant.now(),"Klant",null,null,null,null,
                List.of(new QuoteRevision.Line(null,parked.lines().getFirst().productId(),0,null))));
        var saved=quotes.approveRevision(allowed.id(),"emre",null);assertEquals(3,saved.lines().size());assertTrue(saved.lines().getFirst().isUnavailable());
    }

    private SalesSplits.Result confirm(SalesOrder source,SalesSplits.Request request,SalesSplits.Preview preview) {
        return splits.split(source.id(),new SalesSplits.Request(request.lines(),request.deliveryWeek(),preview.previewToken(),UUID.randomUUID().toString()));
    }
    private SalesOrder change(SalesOrder source,int index,Boolean flag,int quantity,Integer requested) {
        var lines=new ArrayList<>(source.lines());var old=lines.get(index);
        lines.set(index,new SalesOrderLine(old.id(),old.productId(),quantity,old.unitPriceEur(),old.manualDiscountPct(),old.deliveryWeek(),old.unitCostEur(),flag,requested));
        return source.withLinesAndPallets(lines,source.pallets());
    }
    private SalesOrder source() {
        catalogLock.acquire();em.createNativeQuery("delete from discount_tier").executeUpdate();
        var country=em.find(CountryEntity.class,"BE");country.minOrderValue=BigDecimal.ZERO;country.handling=new BigDecimal("15.50");
        var buyer=customers.create(new Customer(null,"Availability QA","Buyer",null,null,"BE0123456789","BE",Language.NL,"Voorbeeld 1","2400","Mol","DAP","30 dagen",null,LocalDate.now()));
        var lines=new ArrayList<SalesOrderLine>();
        for(int i=0;i<3;i++){
            var product=new ProductEntity();product.sku="AVAILABLE-"+UUID.randomUUID();product.name="Rozen "+i;product.active=true;
            product.cartonLengthCm=BigDecimal.TEN;product.cartonWidthCm=BigDecimal.TEN;product.cartonHeightCm=BigDecimal.TEN;product.cartonWeightKg=BigDecimal.ONE;
            product.piecesPerCarton=12;product.stockQuantity=100;product.inventoryKnown=true;product.fixedSalesPriceEur=new BigDecimal("7.1234");product.landedCostEur=new BigDecimal("2.1111");em.persist(product);em.flush();
            var tier=new DiscountTierEntity();tier.scope=TierScope.LINE;tier.productId=product.id;tier.minQuantity=24;tier.percent=new BigDecimal("7");em.persist(tier);
            lines.add(new SalesOrderLine(null,product.id,i==0?48:24,product.fixedSalesPriceEur,new BigDecimal("3"),null,product.landedCostEur));
        }
        var tier=new DiscountTierEntity();tier.scope=TierScope.ORDER;tier.minQuantity=72;tier.percent=new BigDecimal("8");em.persist(tier);
        var created=sales.create(buyer.id(),"BE","DAP",DocumentType.FACTUUR);
        orders.save(SalesSplits.copy(created,created.id(),created.number(),lines,List.of(new SalesExtraLine("Verpakken",BigDecimal.ONE,new BigDecimal("9.99"))),new BigDecimal("30.25"),new BigDecimal("3.9"),null));
        em.flush();em.clear();return sales.get(created.id());
    }
}
