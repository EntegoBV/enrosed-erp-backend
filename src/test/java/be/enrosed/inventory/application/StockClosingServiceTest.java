package be.enrosed.inventory.application;

import be.enrosed.catalog.adapter.out.persistence.ProductEntity;
import be.enrosed.catalog.application.StockService;
import be.enrosed.catalog.domain.StockLevel;
import be.enrosed.catalog.domain.StockLocation;
import be.enrosed.catalog.domain.StockMovement;
import be.enrosed.inventory.adapter.in.rest.StockClosingDtos;
import be.enrosed.inventory.adapter.in.rest.StockClosingResource;
import be.enrosed.inventory.adapter.out.persistence.InventoryStore;
import be.enrosed.inventory.adapter.out.persistence.StockClosingArticleEntity;
import be.enrosed.inventory.adapter.out.persistence.StockClosingContainerEntity;
import be.enrosed.inventory.adapter.out.persistence.StockClosingDecisionEntity;
import be.enrosed.inventory.adapter.out.persistence.StockClosingEntity;
import be.enrosed.inventory.adapter.out.persistence.StockClosingLayerEntity;
import be.enrosed.inventory.adapter.out.persistence.StockClosingLineEntity;
import be.enrosed.inventory.adapter.out.persistence.StockClosingLotEntity;
import be.enrosed.inventory.adapter.out.persistence.StockClosingMovementEntity;
import be.enrosed.inventory.adapter.out.persistence.StockClosingSeparateEntity;
import be.enrosed.inventory.adapter.out.persistence.StockClosingWriteDownEntity;
import be.enrosed.inventory.adapter.out.persistence.StockOpeningLayerEntity;
import be.enrosed.inventory.adapter.out.persistence.StockValuationRuleEntity;
import be.enrosed.inventory.application.ClosingNotices.Notice;
import be.enrosed.inventory.application.StockClosingDecisionService.Write;
import be.enrosed.inventory.application.StockClosingService.LocationView;
import be.enrosed.inventory.application.StockClosingService.View;
import be.enrosed.inventory.application.StockCountService.LineWrite;
import be.enrosed.inventory.domain.ValuationRuleText;
import be.enrosed.sales.adapter.out.persistence.SalesEntities.SalesOrderEntity;
import be.enrosed.sales.adapter.out.persistence.SalesEntities.SalesOrderLineEntity;
import be.enrosed.sales.application.CustomerService;
import be.enrosed.sales.application.SalesOrderService;
import be.enrosed.sales.domain.Customer;
import be.enrosed.sales.domain.DocumentType;
import be.enrosed.sales.domain.FreightPricingStrategy;
import be.enrosed.sales.domain.FreightState;
import be.enrosed.sales.domain.QuoteStatus;
import be.enrosed.sales.domain.SalesOrder;
import be.enrosed.shared.Currency;
import be.enrosed.shared.Language;
import be.enrosed.shared.NotFoundException;
import be.enrosed.shared.UnprocessableBusinessRuleException;
import be.enrosed.shared.audit.ActivityLogEntity;
import be.enrosed.sourcing.adapter.out.persistence.SourcingEntities.PurchaseOrderEntity;
import be.enrosed.sourcing.application.PurchaseOrderService;
import be.enrosed.sourcing.application.PurchaseSupplierCreditService;
import be.enrosed.sourcing.application.PurchaseSupplierCreditService.CreditRequest;
import be.enrosed.sourcing.application.SupplierService;
import be.enrosed.sourcing.domain.Allocation;
import be.enrosed.sourcing.domain.LotCost;
import be.enrosed.sourcing.domain.PaymentTerms;
import be.enrosed.sourcing.domain.PurchaseOrder;
import be.enrosed.sourcing.domain.PurchaseOrderLine;
import be.enrosed.sourcing.domain.PurchaseOrderStatus;
import be.enrosed.sourcing.domain.PurchasePayment;
import be.enrosed.sourcing.domain.PurchasePayment.Payee;
import be.enrosed.sourcing.domain.PurchaseSupplierCredit;
import be.enrosed.sourcing.domain.PurchaseSupplierCredit.Reason;
import be.enrosed.sourcing.domain.Supplier;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import jakarta.persistence.Column;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The closing through the services on H2: what a compute stores, which
 * points stand between a concept and "Definitief maken", what each decision
 * does to the figures, and what a later year takes over from a final one.
 *
 * Every test starts from an emptied book inside its own rolled-back
 * transaction, so the figures are the test's own. Year 1 and year 2 lie in
 * the past; the counts are booked today, after both closing dates.
 */
@QuarkusTest
@TestSecurity(user = "emre", roles = "admin")
class StockClosingServiceTest {
    private static final int YEAR2 = InventoryClock.today().getYear() - 1;
    private static final int YEAR1 = YEAR2 - 1;
    private static final LocalDate END1 = LocalDate.of(YEAR1, 12, 31);
    private static final LocalDate END2 = LocalDate.of(YEAR2, 12, 31);

    @Inject StockClosingService closings;
    @Inject StockClosingResource resource;
    @Inject StockOpeningLayerService openingLayers;
    @Inject StockValuationRuleService rules;
    @Inject StockCountService counts;
    @Inject StockService stock;
    @Inject PurchaseOrderService purchases;
    @Inject PurchaseSupplierCreditService credits;
    @Inject SupplierService suppliers;
    @Inject SalesOrderService sales;
    @Inject CustomerService customers;
    @Inject InventoryStore.Closings closingRows;
    @Inject InventoryStore.Decisions decisionRows;
    @Inject EntityManager em;

    private Supplier supplier;
    private boolean bookOnReceipt = true;

    /* -------------------------------------------------- create, rule, delete */

    @Test @TestTransaction
    void aClosingIsCreatedOncePerYearAndTheRuleFollowsTheClosingsUntilOneIsFinal() {
        quiet();
        assertNull(rules.current(), "no rule before the first closing");
        assertEquals("Kies een boekjaar", assertThrows(UnprocessableBusinessRuleException.class,
                () -> closings.create(null, null)).getMessage());
        assertEquals("De afsluitdatum ligt meer dan twee jaar van het boekjaar", assertThrows(UnprocessableBusinessRuleException.class,
                () -> closings.create(YEAR2, LocalDate.of(YEAR2 + 3, 1, 1))).getMessage());
        assertEquals("Afsluiting 987654321 bestaat niet", assertThrows(NotFoundException.class,
                () -> closings.view(987_654_321L)).getMessage());

        View second = closings.create(YEAR2, null);
        assertEquals(END2, second.closing().closingDate, "31 December by default");
        assertEquals(InventoryClock.cutoffAt(END2), second.closing().cutoffAt);
        assertEquals("CONCEPT", second.closing().status);
        assertEquals(1, second.closing().versionNo);
        assertEquals("emre", second.closing().createdBy);
        assertNotNull(second.closing().computedAt, "computed once at creation");
        assertEquals(64, second.closing().dataSha256.length());
        StockValuationRuleEntity rule = rules.current();
        assertEquals("FIFO_LOT", rule.method);
        assertEquals("FIFO per ontvangen partij", rule.methodLabel);
        assertEquals("1", rule.ruleVersion);
        assertEquals(YEAR2, rule.effectiveFromYear);
        assertEquals(ValuationRuleText.render(YEAR2), rule.ruleText);
        assertTrue(rule.ruleText.endsWith("Deze regel geldt sinds boekjaar " + YEAR2 + "."));
        assertEquals(rule.ruleText, second.closing().ruleText, "copied onto the closing");
        assertEquals(YEAR2, second.closing().ruleEffectiveFromYear);

        InventoryRefusal twice = assertThrows(InventoryRefusal.class, () -> closings.create(YEAR2, null));
        assertEquals("BESTAAT_AL", twice.code());
        assertEquals("Voor " + YEAR2 + " bestaat al een afsluiting. Open ze, of maak een nieuwe versie", twice.getMessage());
        assertEquals(second.closing().id, twice.details().get("closingId"));

        /* A closing for an earlier year, while none is final: the rule moves with it. */
        InventoryRefusal order = assertThrows(InventoryRefusal.class, () -> closings.create(YEAR2 + 1, END2));
        assertEquals("DATUM_VOLGORDE", order.code());
        assertEquals("De afsluitdatum moet na die van " + YEAR2 + " liggen", order.getMessage());
        View first = closings.create(YEAR1, null);
        assertEquals(YEAR1, rules.current().effectiveFromYear);
        assertEquals(ValuationRuleText.render(YEAR1), rules.current().ruleText);
        assertEquals(List.of(second.closing().id, first.closing().id),
                closings.overview().closings().stream().map(closing -> closing.id).toList(), "newest year first");
        assertEquals(rules.current().id, closings.overview().rule().id);
        assertEquals("DATUM_VOLGORDE", assertThrows(InventoryRefusal.class,
                () -> closings.setClosingDate(second.closing().id, END1)).code());

        /* Deleting the concept writes the log entry and brings the rule back in line. */
        closings.delete(first.closing().id);
        assertEquals(YEAR2, rules.current().effectiveFromYear);
        assertEquals(ValuationRuleText.render(YEAR2), rules.current().ruleText);
        ActivityLogEntity entry = em.createQuery("from ActivityLogEntity where entityType = 'STOCK_CLOSING' and entityId = ?1",
                ActivityLogEntity.class).setParameter(1, String.valueOf(first.closing().id)).getSingleResult();
        assertEquals("CLOSING_CONCEPT_DELETED", entry.action);
        assertEquals("Concept jaarinventaris " + YEAR1 + " versie 1 verwijderd", entry.summary);
        assertEquals("Afsluiting " + first.closing().id + " bestaat niet", assertThrows(NotFoundException.class,
                () -> closings.view(first.closing().id)).getMessage());

        /* Once a closing is final the rule stands, and only then is an earlier year refused. */
        markFinal(second.closing().id);
        InventoryRefusal early = assertThrows(InventoryRefusal.class, () -> closings.create(YEAR1, null));
        assertEquals("VOOR_REGEL", early.code());
        assertEquals("De waarderingsregel geldt vanaf " + YEAR2 + "; een vroeger boekjaar kan niet", early.getMessage());
        InventoryRefusal kept = assertThrows(InventoryRefusal.class, () -> closings.delete(second.closing().id));
        assertEquals("DEFINITIEF", kept.code());
        assertEquals("Een definitieve afsluiting kan niet verwijderd worden", kept.getMessage());
        assertEquals(YEAR2, rules.current().effectiveFromYear);
    }

    @Test @TestTransaction
    void deletingTheLastClosingDeletesTheRule() {
        quiet();
        View only = closings.create(YEAR1, LocalDate.of(YEAR1, 6, 30));
        assertEquals(LocalDate.of(YEAR1, 6, 30), only.closing().closingDate, "free per closing");
        View moved = closings.setClosingDate(only.closing().id, END1);
        assertEquals(END1, moved.closing().closingDate);
        assertEquals(InventoryClock.cutoffAt(END1), moved.closing().cutoffAt);
        closings.saveDecision(only.closing().id, vat());
        closings.delete(only.closing().id);
        assertNull(rules.current());
        assertEquals(0, decisionRows.count("closingId", only.closing().id));
        assertEquals(List.of(), closings.overview().closings());
    }

    /* ------------------------------------------------------------ first closing */

    @Test @TestTransaction
    void aFirstClosingValuesTheCountedStockPerLotAndACorrectionOfTheCountMovesOnlyItsProduct() {
        quiet();
        long rose = product("Roos");
        long tulip = product("Tulp");
        long first = simpleContainer("C1", rose, 100, "2.00", LocalDate.of(YEAR1, 11, 20));
        simpleContainer("C2", tulip, 40, "5.00", LocalDate.of(YEAR1, 6, 14));
        StockLocation main = stock.mainLocation();
        long count = countAll(YEAR1).get(main.id());

        View view = closings.create(YEAR1, null);
        long id = view.closing().id;

        assertNull(view.previous());
        StockClosingArticleEntity roses = article(view, rose);
        assertEquals(100, roses.countedQuantity);
        assertEquals(0, roses.rollDelta);
        assertEquals(100, roses.closingQuantity);
        assertEquals(100, roses.ownQuantity);
        assertEquals(0, roses.unvaluedQuantity);
        assertMoney("184.00", roses.costValueEur);
        assertMoney("184.00", roses.goodsEur);
        assertEquals(0, new BigDecimal("1.84").compareTo(roses.averageUnitEur));
        assertEquals("OK", roses.status);
        assertEquals("Roos - rood", roses.productName);
        assertEquals("stuk", roses.unitKey);
        assertEquals("PIECE", roses.salesUnit);

        StockClosingLayerEntity layer = layers(view, rose).getFirst();
        assertEquals("EIGEN", layer.block);
        assertEquals("PARTIJ", layer.source);
        assertEquals(first, layer.purchaseOrderId);
        assertEquals(100, layer.capacity);
        assertEquals(100, layer.quantity);
        assertEquals(LocalDate.of(YEAR1, 11, 20), layer.receivedOn);
        StockClosingContainerEntity container = container(view, first);
        assertEquals("EIGEN", container.role);
        assertEquals("ONTVANGST", container.rateCutoffSource);
        assertEquals(LocalDate.of(YEAR1, 11, 20), container.rateCutoffDate);
        assertEquals("ONTVANGEN", container.quantityBasis);
        assertEquals("BESTELD", container.billedBasis);
        assertEquals("Closing Co", container.supplierName);
        assertEquals("FOB Ningbo", container.supplierIncoterm);
        assertMoney("184.00", container.acquisitionEur);
        StockClosingLotEntity lot = lot(view, first, rose);
        assertEquals(container.id, lot.containerId);
        assertEquals(lot.id, layer.lotId);
        assertEquals("EIGEN", lot.role);
        assertEquals("OK", lot.status);
        assertNull(lot.previousUnitValueEur);

        /* The receipt was booked today, after the closing date, for a container that lay there: listed, not taken out. */
        StockClosingMovementEntity receipt = view.movements().stream()
                .filter(row -> row.productId == rose && "PURCHASE_RECEIPT".equals(row.kind)).findFirst().orElseThrow();
        assertEquals(LocalDate.of(YEAR1, 11, 20), receipt.businessDate);
        assertFalse(receipt.defaultApplied);
        assertFalse(receipt.applied);
        assertTrue(receipt.defaultNote.startsWith("Ontvangen op 20/11/" + YEAR1 + ", bijgeboekt op "));
        assertTrue(receipt.defaultNote.endsWith(": lag er al op de afsluitdatum"));

        LocationView warehouse = location(view, main.id());
        assertEquals("TELLING", warehouse.anchor());
        assertEquals(count, warehouse.countId());
        assertTrue(warehouse.countAfterClosingDate());
        assertEquals("emre", warehouse.countedByName());
        assertEquals(0, warehouse.correctionCount());
        assertEquals(2, warehouse.lineCount());
        assertEquals(0, warehouse.differenceCount());
        assertEquals(2, warehouse.movementCount(), "the two receipts, booked between the closing date and the count");
        assertEquals(0, warehouse.reviewCount());

        assertMoney("368.00", view.closing().costValueEur);
        assertMoney("368.00", view.closing().totalValueEur);
        assertMoney("368.00", view.closing().ownValueEur);
        assertMoney("0.00", view.closing().estimatedEur);
        assertEquals(140, view.closing().ownQuantity);
        assertEquals(0, view.closing().unvaluedQuantity);
        assertEquals(List.of("BTW_BEVESTIGING"), blockers(view));
        assertEquals("afsluiten", notice(view, "BTW_BEVESTIGING").segment());
        assertEquals("Bevestig dat de betalingen onder Leverancier, Douane & transport en Inspectie & andere kosten"
                + " zonder aftrekbare btw zijn ingevoerd.", notice(view, "BTW_BEVESTIGING").message());
        assertEquals("De koersen zijn op de container ingevoerd en blijven wijzigbaar tot de afsluiting definitief is.",
                notice(view, "KOERS_INGEVOERD").message());
        assertEquals("WARNING", notice(view, "KOERS_INGEVOERD").severity());
        assertFalse(view.canFinalize());
        assertFalse(view.canCorrect());
        assertEquals(1, view.closing().blockerCount);

        String unconfirmed = view.closing().dataSha256;
        View confirmed = closings.saveDecision(id, vat());
        assertEquals(List.of(), blockers(confirmed));
        assertTrue(confirmed.canFinalize());
        assertEquals("emre", confirmed.decisions().getFirst().decidedByName);
        assertNotEquals(unconfirmed, confirmed.closing().dataSha256, "the confirmation is in the fingerprint");

        /* A correction session for one product: its anchor moves to the correction, the tulips keep the base count. */
        long correction = counts.start(YEAR1, main.id(), null, count).summary().count().id;
        var added = counts.addLine(correction, rose).line().row();
        counts.saveLine(correction, added.id, new LineWrite(98, "NIET_GEVONDEN", null, added.revision, null, null));
        counts.book(correction, counts.bookingCheck(correction).checkToken());
        View corrected = closings.recompute(id);

        StockClosingLineEntity roseLine = line(corrected, rose, main.id());
        assertEquals(count, roseLine.countId, "the base session, also for the corrected product");
        assertEquals(added.id, roseLine.countLineId);
        assertEquals(98, roseLine.anchorQuantity);
        assertEquals(98, roseLine.closingQuantity);
        assertEquals(100, roseLine.expectedQuantity);
        assertEquals(98, roseLine.countedQuantity);
        assertEquals(-2, roseLine.countDifference);
        assertEquals("NIET_GEVONDEN", roseLine.countReasonCode);
        assertMoney("180.32", roseLine.costValueEur);
        StockClosingLineEntity tulipLine = line(corrected, tulip, main.id());
        assertEquals(40, tulipLine.closingQuantity);
        assertTrue(tulipLine.anchoredAt.isBefore(roseLine.anchoredAt));
        LocationView after = location(corrected, main.id());
        assertEquals(count, after.countId());
        assertEquals(1, after.correctionCount());
        assertEquals(2, after.lineCount());
        assertEquals(1, after.differenceCount());
        assertEquals(roseLine.anchoredAt, after.anchoredAt());
        /* The base count's own row of the rose is now a replaced count: listed, counting for nothing. */
        StockClosingMovementEntity replaced = corrected.movements().stream()
                .filter(row -> row.productId == rose && "STOCKTAKE".equals(row.kind)).findFirst().orElseThrow();
        assertEquals(StockRoll.NOTE_REPLACED_COUNT, replaced.defaultNote);
        assertFalse(replaced.applied);
        assertFalse(replaced.review);
        assertMoney("180.32", article(corrected, rose).costValueEur);
        assertEquals(98, article(corrected, rose).ownQuantity);

        /* What the screen reads: the own quantity per location is derived from the stored rows. */
        StockClosingDtos.ClosingView json = resource.get(id);
        var shown = json.articles().stream().filter(row -> row.productId() == rose).findFirst().orElseThrow();
        assertEquals(98, shown.locations().getFirst().ownQuantity());
        assertTrue(shown.locations().getFirst().countAfterClosingDate());
        assertEquals("Niet gevonden", shown.locations().getFirst().countReasonLabel());
        assertEquals("PARTIJ", shown.layers().getFirst().originSource());
        assertNull(shown.layers().getFirst().originClosingYear());
        assertEquals("FIFO_LOT", json.rule().method());
        assertEquals(YEAR1, json.rule().effectiveFromYear());
        assertEquals(5, json.writeDownReasons().size());
        assertEquals("Lagere marktwaarde", json.writeDownReasons().get(4).label());
        assertEquals(1, json.versions().size());
        assertEquals("Bevestiging btw", json.decisions().getFirst().kindLabel());
        assertEquals("Ontvangstdatum", json.containers().getFirst().rateCutoffSourceLabel());
        assertEquals("Lokale kosten bij vertrek: volume · Zeevracht: volume · Kosten na aankomst: volume"
                + " · Inspectie & andere kosten: apart, naar ontvangen waarde · Invoerrechten: per product volgens HS-code"
                + " · Varianten van één reeks gelijkgetrokken: ja", json.containers().getFirst().allocationLabel());
    }

    /* ------------------------------------------------- lot cost, keys, accrual */

    @Test @TestTransaction
    void theLotsOfAContainerAreStoredWithTheirKeysAndAnAccrualIsConfirmedAgainstTheOpenAmount() {
        quiet();
        long a = product("Stolp");
        long b = product("Koepel");
        LocalDate received = LocalDate.of(YEAR1, 11, 20);
        /* 1.000 x US$ 2 and 500 x US$ 4 at 0,92; US$ 1.000 freight, € 530 at arrival, € 180 inspection apart. */
        long box = container("PO-014", received, received.minusDays(40), List.of(new Ordered(a, 1000, "2.00", 950, 20),
                new Ordered(b, 500, "4.00", 500, 0)), stored -> {
            stored.freightUsd = new BigDecimal("1000");
            stored.destinationCostsEur = new BigDecimal("530");
            stored.inspectionCostEur = new BigDecimal("180");
            stored.extraRevenueEur = new BigDecimal("2000");
            stored.allocFreight = stored.allocOrigin = stored.allocDestination = Allocation.PIECES;
        });
        pay(box, received.minusDays(80), new BigDecimal("1200"), Currency.USD, Payee.SUPPLIER, false, "1116.00");
        PurchasePayment balance = pay(box, received.plusDays(15), new BigDecimal("2800"), Currency.USD, Payee.SUPPLIER, false, "2604.00");
        pay(box, received, new BigDecimal("1000"), Currency.EUR, Payee.LOGISTICS, false, null);
        pay(box, received, new BigDecimal("180"), Currency.EUR, Payee.SEPARATE, false, null);
        pay(box, received, new BigDecimal("35"), Currency.EUR, Payee.OTHER, false, null);
        PurchaseSupplierCredit price = credit(box, "100", Reason.PRICE);
        credit(box, "100", Reason.SHORTAGE);
        countAll(YEAR1);

        View view = closings.create(YEAR1, null);
        long id = view.closing().id;
        closings.saveDecision(id, vat());
        view = closings.view(id);

        StockClosingContainerEntity container = container(view, box);
        assertEquals("PO-014", container.displayName);
        assertEquals("PAID", container.supplierStatus);
        assertMoney("3680.00", container.supplierPlannedEur);
        assertMoney("3692.00", container.supplierPaidEur, "1.116,00 kept, 2.800 x 0,92 counted");
        assertMoney("0.00", container.supplierOpenEur);
        assertMoney("3692.00", container.supplierIncludedEur);
        assertMoney("3692.00", container.supplierGoodsEur);
        assertMoney("0.00", container.supplierTransportEur);
        assertMoney("1450.00", container.logisticsPlannedEur);
        assertMoney("1000.00", container.logisticsPaidEur);
        assertMoney("450.00", container.logisticsOpenEur);
        assertMoney("1450.00", container.logisticsIncludedEur);
        assertMoney("450.00", container.logisticsEstimatedEur);
        assertMoney("180.00", container.separateIncludedEur);
        assertMoney("35.00", container.otherExcludedEur);
        assertMoney("92.00", container.priceCreditEur);
        assertMoney("92.00", container.lossCreditEur);
        assertMoney("28.00", container.exchangeDifferenceEur);
        assertMoney("2000.00", container.enrosedCostExcludedEur);
        assertMoney("5230.00", container.acquisitionEur);
        assertMoney("450.00", container.estimatedEur);
        assertEquals(0, new BigDecimal("0.92").compareTo(container.usdToEurGoods));
        assertFalse(container.cif);
        assertEquals("PIECES", container.allocOrigin);
        assertEquals("PIECES", container.allocFreight);
        assertEquals("PIECES", container.allocDestination);
        assertEquals("SEPARATE", container.allocSeparate, "the container's own settings");
        assertFalse(container.separateInPiecePrice);
        assertNull(container.notes);

        StockClosingLotEntity lotA = lot(view, box, a);
        StockClosingLotEntity lotB = lot(view, box, b);
        assertEquals(1000, lotA.orderedQuantity);
        assertEquals(950, lotA.receivedQuantity);
        assertEquals(20, lotA.damagedQuantity);
        assertEquals(1000, lotA.goodsDivisor);
        assertEquals(950, lotA.costDivisor);
        assertEquals(930, lotA.capacity);
        assertEquals("TEKORT", lotA.status);
        assertEquals(new BigDecimal("1.8000"), lotA.unitGoodsEur);
        assertEquals(new BigDecimal("1.0000"), lotA.unitLogisticsEur);
        assertEquals(new BigDecimal("0.0923"), lotA.unitSeparateEur);
        assertEquals(new BigDecimal("2.8923"), lotA.unitValueEur, "kept at four decimals");
        assertEquals(new BigDecimal("4.7846"), lotB.unitValueEur);
        assertEquals(lotA.unitValueEur, lotA.unitGoodsEur.add(lotA.unitTransportEur).add(lotA.unitLogisticsEur).add(lotA.unitSeparateEur));
        assertMoney("950.00", lotA.logisticsKeyEur);
        assertMoney("500.00", lotB.logisticsKeyEur);
        assertMoney("1748.00", lotA.separateKeyEur);
        assertMoney("1840.00", lotB.separateKeyEur);
        assertMoney("1840.00", lotA.goodsKeyEur);
        assertNull(lotA.transportKeyEur, "not CIF");
        /* Each share follows from the stored keys: amount x key / sum of the keys, to the cent. */
        for (StockClosingLotEntity lot : List.of(lotA, lotB)) {
            assertShare(container.supplierGoodsEur, lot.goodsKeyEur, lotA.goodsKeyEur.add(lotB.goodsKeyEur), lot.goodsEur);
            assertShare(container.logisticsIncludedEur, lot.logisticsKeyEur, lotA.logisticsKeyEur.add(lotB.logisticsKeyEur), lot.logisticsEur);
            assertShare(container.separateIncludedEur, lot.separateKeyEur, lotA.separateKeyEur.add(lotB.separateKeyEur), lot.separateEur);
            assertMoney("0.00", lot.transportEur);
        }
        assertMoney("5230.00", lotA.lotCostEur.add(lotB.lotCostEur));

        assertMoney("2689.84", article(view, a).costValueEur, "930 x 2,8923");
        assertMoney("2392.30", article(view, b).costValueEur);
        assertMoney("5082.14", view.closing().totalValueEur);
        assertMoney("443.73", view.closing().estimatedEur, "930 x 0,3103 + 500 x 0,3103");
        assertEquals(List.of(), blockers(view));
        assertNotice(view, "GESCHAT", "WARNING", "waarde", "1 container met geschatte kosten: € 443,73 in de voorraadwaarde.");
        assertNotice(view, "KOERSVERSCHIL", "WARNING", "waarde", "€ 28,00 koersverschil staat buiten de voorraadwaarde.");
        assertNotice(view, "BIJKOMENDE_KOSTEN", "WARNING", "waarde", "€ 35,00 bank- en betalingskosten en andere bedragen onder"
                + " 'Bijkomende kosten' zijn niet opgenomen. Hoort een bedrag bij de zending, zet het dan op de container onder"
                + " 'Inspectie & andere kosten'. Btw die je terugkrijgt hoort hier wel.");
        assertNotice(view, "TEGOED_BUITEN_WAARDE", "WARNING", "waarde", "Container PO-014: tegoed leverancier voor tekort of schade"
                + " € 92,00 staat buiten de voorraadwaarde. Is het een korting op stuks die er liggen, geef dat dan aan bij het tegoed.");

        StockClosingDtos.ClosingContainer shown = shown(id, box);
        assertTrue(shown.hasShortage());
        assertMoney("147.85", shown.missingAndDamagedCostEur(), "50 x 1,8000 = 90,00 and 20 x 2,8923 = 57,85");
        var logistics = shown.streams().get(1);
        assertEquals("LOGISTICS", logistics.payee());
        assertEquals("Douane & transport", logistics.payeeLabel());
        assertEquals("PARTIAL", logistics.status());
        assertMoney("450.00", logistics.openEur());
        assertMoney("1450.00", logistics.includedEur());
        assertEquals("GESCHAT", logistics.state());
        assertNull(logistics.accrual());
        assertEquals("WERKELIJK", shown.streams().get(0).state());
        assertEquals(5, shown.payments().size());
        Map<String, Object> late = shown.payments().stream().filter(row -> balance.id().equals(((Number) row.get("paymentId")).longValue()))
                .findFirst().orElseThrow();
        assertMoney("2604.00", (BigDecimal) late.get("storedEur"));
        assertMoney("2576.00", (BigDecimal) late.get("countedEur"));
        assertEquals("Koers goederen van de container (betaald na de aankoop)", late.get("rule"));
        assertEquals(true, late.get("inValue"));
        assertEquals("Leverancier", late.get("payeeLabel"));
        Map<String, Object> fee = shown.payments().stream().filter(row -> "OTHER".equals(row.get("payee"))).findFirst().orElseThrow();
        assertEquals(false, fee.get("inValue"));
        assertEquals("Bijkomende kosten", fee.get("payeeLabel"));
        assertEquals(List.of("VERLAAGT", "BUITEN"), shown.credits().stream().map(row -> row.get("treatment")).toList());
        assertEquals("verlaagt de aanschafwaarde", shown.credits().getFirst().get("treatmentLabel"));
        assertEquals("Prijsverschil", shown.credits().getFirst().get("reasonLabel"));
        assertEquals(false, shown.credits().getFirst().get("decisionRequired"));
        assertEquals(price.id(), ((Number) shown.credits().getFirst().get("creditId")).longValue());

        /* An entered amount with "factuur ontvangen" replaces the open Afspraak and carries no estimate. */
        assertEquals("Geef een reden", assertThrows(UnprocessableBusinessRuleException.class, () -> closings.saveDecision(id,
                decision("ACCRUAL", d -> { d.put("purchaseOrderId", box); d.put("payee", "LOGISTICS"); d.put("amountEur", new BigDecimal("400")); })))
                .getMessage());
        assertEquals("Geef een bedrag van nul of meer", assertThrows(UnprocessableBusinessRuleException.class, () -> closings.saveDecision(id,
                decision("ACCRUAL", d -> { d.put("purchaseOrderId", box); d.put("payee", "LOGISTICS"); d.put("amountEur", new BigDecimal("-1")); d.put("reason", "x"); })))
                .getMessage());
        Write accrual = decision("ACCRUAL", d -> { d.put("purchaseOrderId", box); d.put("payee", "LOGISTICS");
            d.put("amountEur", new BigDecimal("400")); d.put("flag", true); d.put("reason", "Eindfactuur expediteur 12/12"); });
        view = closings.saveDecision(id, accrual);
        StockClosingDecisionEntity stored = view.decisions().stream().filter(row -> "ACCRUAL".equals(row.kind)).findFirst().orElseThrow();
        assertMoney("450.00", stored.basisAmountEur, "set by the server: what was open at that moment");
        logistics = shown(id, box).streams().get(1);
        assertMoney("450.00", logistics.openEur(), "the open Afspraak, before the accrual");
        assertMoney("1400.00", logistics.includedEur());
        assertMoney("0.00", logistics.estimatedEur());
        assertEquals("BEVESTIGD", logistics.state());
        assertEquals(stored.id, logistics.accrual().decisionId());
        assertTrue(logistics.accrual().invoiceReceived());
        assertFalse(logistics.accrual().stale());
        assertEquals("Eindfactuur expediteur 12/12", logistics.accrual().reason());
        assertNull(notice(view, "GESCHAT"));
        assertMoney("0.00", view.closing().estimatedEur);

        /* The open amount moves: the confirmation is asked again and is not applied meanwhile. */
        pay(box, received.plusDays(20), new BigDecimal("100"), Currency.EUR, Payee.LOGISTICS, false, null);
        view = closings.recompute(id);
        assertNotice(view, "GESCHAT_BEDRAG_VEROUDERD", "BLOCKER", "waarde", "Container PO-014, Douane & transport: het open bedrag"
                + " is gewijzigd sinds het werd bevestigd. Bevestig het opnieuw.");
        logistics = shown(id, box).streams().get(1);
        assertTrue(logistics.accrual().stale());
        assertMoney("350.00", logistics.openEur());
        assertMoney("1450.00", logistics.includedEur(), "paid 1.100 plus the open 350");
        assertEquals("GESCHAT", logistics.state());
        view = closings.saveDecision(id, accrual);
        assertEquals(List.of(), blockers(view));
        assertEquals(1, view.decisions().stream().filter(row -> "ACCRUAL".equals(row.kind)).count(), "the same key replaces");
        logistics = shown(id, box).streams().get(1);
        assertMoney("1500.00", logistics.includedEur());
        assertEquals("BEVESTIGD", logistics.state());

        /* The fingerprint: the same without a change, another one with a figure, a reason, a payment outside the value, the rule. */
        String fingerprint = view.closing().dataSha256;
        Map<String, List<String>> rows = stored(id);
        closings.recompute(id);
        closings.recompute(id);
        assertEquals(fingerprint, closingRows.findById(id).dataSha256, "stable across computes");
        Map<String, List<String>> again = stored(id);
        rows.forEach((table, lines) -> assertEquals(lines.size(), again.get(table).size(), table + " holds the same number of rows"));
        closings.saveDecision(id, decision("ACCRUAL", d -> { d.put("purchaseOrderId", box); d.put("payee", "LOGISTICS");
            d.put("amountEur", new BigDecimal("400")); d.put("flag", true); d.put("reason", "Eindfactuur expediteur 14/12"); }));
        String reasoned = closingRows.findById(id).dataSha256;
        assertNotEquals(fingerprint, reasoned, "a decision reason");
        pay(box, received, new BigDecimal("5"), Currency.EUR, Payee.OTHER, false, null);
        closings.recompute(id);
        String withFee = closingRows.findById(id).dataSha256;
        assertNotEquals(reasoned, withFee, "a payment under 'Bijkomende kosten'");
        rules.current().ruleText = rules.current().ruleText + " (aangevuld)";
        em.flush();
        closings.recompute(id);
        String reworded = closingRows.findById(id).dataSha256;
        assertNotEquals(withFee, reworded, "the rule text");
        closings.deleteDecision(id, view.decisions().stream().filter(row -> "VAT_CONFIRMATION".equals(row.kind)).findFirst().orElseThrow().id);
        assertNotEquals(reworded, closingRows.findById(id).dataSha256, "the VAT confirmation");
        assertEquals(List.of("BTW_BEVESTIGING"), blockers(closings.view(id)));

        /* The day ownership passed, stated before the deposit: the deposit then counts at the container rate too. */
        view = closings.saveDecision(id, ownership(box, received.minusDays(90), "Verkoopcontract: EXW vanaf 22/08"));
        assertEquals("BESLISSING", container(view, box).rateCutoffSource);
        assertEquals(received.minusDays(90), container(view, box).rateCutoffDate);
        assertMoney("3680.00", container(view, box).supplierPaidEur, "1.200 x 0,92 and 2.800 x 0,92");
        assertMoney("40.00", container(view, box).exchangeDifferenceEur, "12,00 on the deposit and 28,00 on the balance");
        view = closings.deleteDecision(id, view.decisions().stream().filter(row -> "OWNERSHIP_DATE".equals(row.kind)).findFirst().orElseThrow().id);
        assertEquals("ONTVANGST", container(view, box).rateCutoffSource);
        assertMoney("3692.00", container(view, box).supplierPaidEur);

        /* Without "factuur ontvangen" the entered amount is still an estimate; taken back, the Afspraak returns. */
        view = closings.saveDecision(id, decision("ACCRUAL", d -> { d.put("purchaseOrderId", box); d.put("payee", "LOGISTICS");
            d.put("amountEur", new BigDecimal("400")); d.put("reason", "Offerte expediteur"); }));
        logistics = shown(id, box).streams().get(1);
        assertEquals("GESCHAT", logistics.state());
        assertMoney("400.00", logistics.estimatedEur());
        long accrualId = logistics.accrual().decisionId();
        closings.deleteDecision(id, accrualId);
        assertMoney("1450.00", shown(id, box).streams().get(1).includedEur());
        assertEquals("Beslissing " + accrualId + " bestaat niet", assertThrows(NotFoundException.class,
                () -> closings.deleteDecision(id, accrualId)).getMessage());
    }

    /* ------------------------------------------------------- credits, settling */

    @Test @TestTransaction
    void whatACreditIsAndWhatALowerSettlementMeansIsAskedWhereTheBookCannotTell() {
        quiet();
        LocalDate received = LocalDate.of(YEAR1, 9, 10);
        long a = product("A"), b = product("B"), c = product("C"), d = product("D");
        /* K1: Afspraak 184,00, settled at 150,00, with a price credit of US$ 10. */
        long k1 = container("K1", received, received.minusDays(40), List.of(new Ordered(a, 100, "2.00", 100, 0)), null);
        pay(k1, received, new BigDecimal("150"), Currency.EUR, Payee.SUPPLIER, true, null);
        PurchaseSupplierCredit priceCredit = credit(k1, "10", Reason.PRICE);
        /* K2: 10 pieces short and settled lower. */
        long k2 = container("K2", received, received.minusDays(40), List.of(new Ordered(b, 100, "2.00", 90, 0)), null);
        pay(k2, received, new BigDecimal("160"), Currency.EUR, Payee.SUPPLIER, true, null);
        /* K3: paid in full, a credit without a kind. */
        long k3 = container("K3", received, received.minusDays(40), List.of(new Ordered(c, 100, "2.00", 100, 0)), null);
        pay(k3, received, new BigDecimal("184"), Currency.EUR, Payee.SUPPLIER, false, null);
        PurchaseSupplierCredit otherCredit = credit(k3, "20", Reason.OTHER);
        /* K4: nothing missing, and still a credit for shortage. */
        long k4 = container("K4", received, received.minusDays(40), List.of(new Ordered(d, 100, "2.00", 100, 0)), null);
        pay(k4, received, new BigDecimal("184"), Currency.EUR, Payee.SUPPLIER, false, null);
        PurchaseSupplierCredit shortageCredit = credit(k4, "50", Reason.SHORTAGE);
        countAll(YEAR1);

        View view = closings.create(YEAR1, null);
        long id = view.closing().id;

        assertEquals("SETTLED_LOWER", container(view, k1).supplierStatus);
        assertMoney("0.00", container(view, k1).supplierOpenEur);
        assertMoney("150.00", container(view, k1).supplierIncludedEur, "what was not paid lowers the acquisition value");
        assertNotice(view, "TEGOED_EN_LAGER_AFGEREKEND", "BLOCKER", "waarde", "Container K1: de leverancier is lager afgerekend"
                + " en er staat een prijstegoed van € 9,20. Geef aan of dat tegoed al van de betaling is afgetrokken.");
        assertNotice(view, "LAGER_AFGEREKEND", "WARNING", "waarde", "Container K1, Leverancier: € 34,00 minder betaald dan de"
                + " Afspraak en vereffend; het lagere bedrag is als aanschafwaarde opgenomen.");
        Notice settled = notices(view, "LAGER_AFGEREKEND").stream().filter(notice -> notice.purchaseOrderId() == k1).findFirst().orElseThrow();
        assertEquals("SUPPLIER", settled.payee());
        assertMoney("34.00", settled.amountEur());
        assertNotice(view, "LEVERANCIER_LAGER_AFGEREKEND", "BLOCKER", "waarde", "Container K2: de leverancier is lager afgerekend"
                + " en er zijn stuks te weinig geleverd. Geef aan welke stuks de leverancier aanrekende.");
        assertNotice(view, "TEGOED_ZONDER_SOORT", "BLOCKER", "waarde", "Container K3: tegoed leverancier van € 18,40 zonder soort."
                + " Geef aan of het de aanschafwaarde verlaagt of buiten de voorraadwaarde blijft.");
        assertNotice(view, "TEGOED_MEER_DAN_VERLIES", "BLOCKER", "waarde", "Container K4: tegoed voor tekort of schade € 46,00,"
                + " terwijl de ontbrekende en beschadigde stuks samen € 0,00 kostten. Geef per tegoed aan wat het is.");
        assertNull(notices(view, "TEGOED_BUITEN_WAARDE").stream().filter(notice -> notice.purchaseOrderId() == k4).findFirst().orElse(null));
        assertEquals(new BigDecimal("1.4080"), lot(view, k1, a).unitValueEur, "the default: the credit comes on top of the lower payment");
        assertEquals(new BigDecimal("1.6000"), lot(view, k2, b).unitValueEur, "160,00 over the 100 ordered pieces");
        assertEquals(new BigDecimal("1.8400"), lot(view, k3, c).unitValueEur, "an undecided credit is not subtracted");
        assertEquals("NOG_TE_BESLISSEN", shown(id, k3).credits().getFirst().get("treatment"));
        assertEquals("nog te beslissen", shown(id, k3).credits().getFirst().get("treatmentLabel"));
        assertEquals("Andere", shown(id, k3).credits().getFirst().get("reasonLabel"));
        assertEquals(true, shown(id, k3).credits().getFirst().get("decisionRequired"));
        assertEquals(true, shown(id, k1).credits().getFirst().get("decisionRequired"));
        assertEquals(true, shown(id, k4).credits().getFirst().get("decisionRequired"));

        /* The decisions, each with a reason. */
        assertEquals("Kies wat dit tegoed is", assertThrows(UnprocessableBusinessRuleException.class, () -> closings.saveDecision(id,
                decision("CREDIT_TREATMENT", w -> { w.put("purchaseOrderId", k1); w.put("creditId", priceCredit.id()); w.put("reason", "x"); })))
                .getMessage());
        assertEquals("Dit tegoed hoort niet bij deze container", assertThrows(UnprocessableBusinessRuleException.class,
                () -> closings.saveDecision(id, treatment(k1, otherCredit.id(), "VERLAAGT", "x"))).getMessage());
        assertEquals("Geef een reden", assertThrows(UnprocessableBusinessRuleException.class,
                () -> closings.saveDecision(id, treatment(k1, priceCredit.id(), "IN_BETALING", " "))).getMessage());
        closings.saveDecision(id, treatment(k1, priceCredit.id(), "IN_BETALING", "Afgetrokken van de slotbetaling"));
        closings.saveDecision(id, decision("SUPPLIER_BILLED", w -> { w.put("purchaseOrderId", k2); w.put("choice", "GELEVERD");
            w.put("reason", "Factuur vermeldt 90 stuks"); }));
        closings.saveDecision(id, treatment(k3, otherCredit.id(), "VERLAAGT", "Kwaliteitskorting"));
        view = closings.saveDecision(id, treatment(k4, shortageCredit.id(), "BUITEN", "Vergoeding voor een vorige zending"));

        assertEquals(List.of("BTW_BEVESTIGING"), blockers(view));
        assertEquals(new BigDecimal("1.5000"), lot(view, k1, a).unitValueEur, "already inside the lower payment: not subtracted again");
        assertEquals("IN_BETALING", shown(id, k1).credits().getFirst().get("treatment"));
        assertEquals("reeds in de betaling", shown(id, k1).credits().getFirst().get("treatmentLabel"));
        assertEquals("Afgetrokken van de slotbetaling", shown(id, k1).credits().getFirst().get("reason"));
        assertNotNull(shown(id, k1).credits().getFirst().get("decisionId"));
        assertMoney("0.00", container(view, k1).priceCreditEur);
        assertEquals("GELEVERD", container(view, k2).billedBasis);
        assertEquals(90, lot(view, k2, b).billedQuantity);
        assertEquals(new BigDecimal("1.7778"), lot(view, k2, b).unitValueEur, "160,00 over the 90 delivered pieces");
        assertEquals(new BigDecimal("1.6560"), lot(view, k3, c).unitValueEur);
        assertMoney("18.40", container(view, k3).priceCreditEur);
        assertEquals(new BigDecimal("1.8400"), lot(view, k4, d).unitValueEur);
        assertNotice(view, "TEGOED_BUITEN_WAARDE", "WARNING", "waarde", "Container K4: tegoed leverancier voor tekort of schade"
                + " € 46,00 staat buiten de voorraadwaarde. Is het een korting op stuks die er liggen, geef dat dan aan bij het tegoed.");
        assertEquals(2, notices(view, "LAGER_AFGEREKEND").size(), "the lower settlement of K1 and K2 stays a point of attention");

        /* Entered back as an amount still owed, the lower settlement is in the value again. */
        view = closings.saveDecision(id, decision("ACCRUAL", w -> { w.put("purchaseOrderId", k1); w.put("payee", "SUPPLIER");
            w.put("amountEur", new BigDecimal("34")); w.put("flag", true); w.put("reason", "Korting contant, financieel resultaat"); }));
        assertEquals(List.of(k2), notices(view, "LAGER_AFGEREKEND").stream().map(Notice::purchaseOrderId).toList());
        assertMoney("184.00", container(view, k1).supplierIncludedEur);
        assertEquals(new BigDecimal("1.8400"), lot(view, k1, a).unitValueEur);
        var supplierStream = shown(id, k1).streams().getFirst();
        assertMoney("0.00", supplierStream.openEur());
        assertEquals("BEVESTIGD", supplierStream.state());
        assertFalse(supplierStream.accrual().stale(), "confirmed against an open amount of zero");
        assertEquals("Container K1 · Leverancier", resource.get(id).decisions().stream()
                .filter(row -> "ACCRUAL".equals(row.kind())).findFirst().orElseThrow().subjectLabel());
    }

    @Test @TestTransaction
    void whatAContainerShowsAboutItsReceiptAndItsPaymentsIsNamed() {
        quiet();
        LocalDate received = LocalDate.of(YEAR1, 9, 10);
        long e = product("E"), f = product("F"), g = product("G"), h = product("H"), i = product("I");
        /* K5: ten pieces more than ordered and € 16 more paid than the Afspraak. */
        long k5 = container("K5", received, received.minusDays(40), List.of(new Ordered(e, 100, "2.00", 110, 0)), null);
        pay(k5, received, new BigDecimal("200"), Currency.EUR, Payee.SUPPLIER, false, null);
        /* K6: a line without a price. */
        long k6 = container("K6", received, received.minusDays(40), List.of(new Ordered(f, 50, "0.00", 50, 0)), null);
        /* K7: received, but the receipt day was emptied. */
        long k7 = container("K7", received, received.minusDays(40), List.of(new Ordered(g, 10, "2.00", 10, 0)), null);
        em.find(PurchaseOrderEntity.class, k7).receivedOn = null;
        /* K8: a plan of 30 % and 70 % with the deposit above its term; USD 4.000 of 4.000 is paid. */
        long k8 = container("K8", received, received.minusDays(40), List.of(new Ordered(h, 1000, "2.00", 1000, 0),
                new Ordered(i, 500, "4.00", 500, 0)), stored -> {
            stored.paymentTerms = PaymentTerms.CUSTOM;
            stored.payPctOrdered = new BigDecimal("30");
            stored.payPctArrived = new BigDecimal("70");
        });
        purchases.addPayment(k8, received.minusDays(70), new BigDecimal("1500"), Currency.USD, "aanbetaling", Payee.SUPPLIER, false,
                PaymentTerms.Moment.ORDERED, new BigDecimal("1395.00"));
        purchases.addPayment(k8, received.plusDays(15), new BigDecimal("2500"), Currency.USD, "saldo", Payee.SUPPLIER, false,
                PaymentTerms.Moment.ARRIVED, new BigDecimal("2330.00"));
        em.flush(); em.clear();
        countAll(YEAR1);

        View view = closings.create(YEAR1, null);
        long id = view.closing().id;

        assertNotice(view, "MEER_ONTVANGEN", "WARNING", "waarde", "Container K5, E - rood: meer ontvangen dan besteld."
                + " Rekent de leverancier de extra stuks nog aan, pas dan de Afspraak aan.");
        assertEquals("MEER_ONTVANGEN", lot(view, k5, e).status);
        assertEquals(new BigDecimal("1.8182"), lot(view, k5, e).unitValueEur, "200,00 over the 110 that arrived");
        assertNotice(view, "MEER_BETAALD", "WARNING", "waarde", "Container K5, Leverancier: € 16,00 meer betaald dan de Afspraak;"
                + " volledig opgenomen.");
        assertEquals(1, notices(view, "MEER_BETAALD").size());
        Notice overpaid = notice(view, "MEER_BETAALD");
        assertEquals(k5, overpaid.purchaseOrderId());
        assertEquals("SUPPLIER", overpaid.payee());
        assertMoney("16.00", overpaid.amountEur());
        assertMoney("200.00", container(view, k5).supplierIncludedEur);

        assertNotice(view, "GEEN_PRIJS", "BLOCKER", "waarde", "Partij zonder inkoopprijs: container K6, F - rood."
                + " Vul de inkoopprijs aan op de container.");
        assertEquals("GEEN_PRIJS", lot(view, k6, f).status);

        assertNotice(view, "GEEN_ONTVANGSTDATUM", "BLOCKER", "waarde", "Container K7 heeft geen ontvangstdatum.");
        assertNull(container(view, k7), "no lot and no candidate for goods in transit");
        assertEquals(List.of(), separate(view, "ONDERWEG"));
        assertEquals(0, article(view, g).closingQuantity,
                "without a receipt day its receipt of today counts as a movement after the closing date");

        /* The instalment plan: the reconciliation reads a gap on the second term, but nothing is owed. */
        StockClosingContainerEntity plan = container(view, k8);
        assertMoney("3680.00", plan.supplierPlannedEur);
        assertMoney("0.00", plan.supplierOpenEur);
        assertMoney("3695.00", plan.supplierPaidEur, "1.395,00 kept and 2.500 x 0,92");
        assertMoney("3695.00", plan.supplierIncludedEur);
        assertMoney("0.00", plan.supplierEstimatedEur);
        assertMoney("30.00", plan.exchangeDifferenceEur);
        assertEquals("WERKELIJK", shown(id, k8).streams().getFirst().state());
        assertNull(notice(view, "GESCHAT"));
        assertTrue(notices(view, "MEER_BETAALD").stream().noneMatch(notice -> notice.purchaseOrderId() == k8));

        /* With its receipt day back the container is a lot, and the blocker is gone. */
        em.find(PurchaseOrderEntity.class, k7).receivedOn = received;
        em.flush(); em.clear();
        view = closings.recompute(id);
        assertNull(notice(view, "GEEN_ONTVANGSTDATUM"));
        assertEquals("EIGEN", container(view, k7).role);
        assertEquals(10, article(view, g).ownQuantity, "received before the closing date: it lay there");
        assertMoney("18.40", article(view, g).costValueEur);
        assertNotice(view, "GESCHAT", "WARNING", "waarde", "1 container met geschatte kosten: € 18,40 in de voorraadwaarde.");
    }

    /* ------------------------------------------------------- counting, rolling */

    @Test @TestTransaction
    void aLocationThatIsNotCountedBlocksAndTheMovementsAroundTheClosingDateAreTheUsersToJudge() {
        quiet();
        StockLocation shelf = location("Winkel");
        long rose = product("Roos");
        long gone = product("Verdwenen");
        level(rose, shelf, 30);
        level(gone, shelf, 7);
        stock.sell(rose, shelf.id(), 5, "F-TEST-1");
        em.remove(em.find(ProductEntity.class, gone));
        em.flush(); em.clear();

        View view = closings.create(YEAR1, null);
        long id = view.closing().id;

        assertNotice(view, "TELLING_ONTBREEKT", "BLOCKER", "tellen", shelf.name() + " is voor " + YEAR1 + " nog niet geteld.");
        assertEquals(shelf.id(), notice(view, "TELLING_ONTBREEKT").locationId());
        assertNotice(view, "VERWIJDERD_PRODUCT", "WARNING", "tellen",
                "Voorraadstand van een verwijderd product (id " + gone + ", 7 stuks) is niet opgenomen.");
        assertNull(article(view, gone), "such a level is in no quantity");
        LocationView uncounted = location(view, shelf.id());
        assertEquals("BOEKSTAND", uncounted.anchor());
        assertNull(uncounted.countId());
        assertNull(uncounted.anchoredAt());
        assertEquals(0, uncounted.lineCount());
        assertEquals(2, uncounted.movementCount());
        assertEquals(1, uncounted.reviewCount());
        StockClosingLineEntity line = line(view, rose, shelf.id());
        assertEquals("BOEKSTAND", line.anchor);
        assertEquals(25, line.anchorQuantity);
        assertEquals(5, line.rollDelta, "the sale of today happened after the closing date");
        assertEquals(30, line.closingQuantity);
        assertNotice(view, "ZONDER_WAARDE", "BLOCKER", "waarde",
                "1 product (30 stuks) zonder gewaardeerde partij. Vul een beginwaarde met bron in.");
        assertEquals("ZONDER_WAARDE", article(view, rose).status);

        /* The level was set today by hand: no physical movement, to be looked at. */
        StockClosingMovementEntity set = view.movements().stream().filter(row -> row.productId == rose
                && "MANUAL_CORRECTION".equals(row.kind)).findFirst().orElseThrow();
        assertFalse(set.defaultApplied);
        assertTrue(set.review);
        assertEquals(StockRoll.NOTE_CORRECTION, set.defaultNote);
        assertTrue(set.noAnchor, "no earlier booking of this product here");
        assertNotice(view, "BEWEGING_NAKIJKEN", "WARNING", "datum", "1 beweging rond de afsluitdatum moet je nog nakijken.");
        StockClosingMovementEntity sale = view.movements().stream().filter(row -> row.productId == rose && "SALE".equals(row.kind))
                .findFirst().orElseThrow();
        assertTrue(sale.applied);
        assertEquals(-5, sale.effectiveDelta);
        long saleId = sale.movementId;
        long setId = set.movementId;

        /* A decision flips one row, with a reason. */
        assertEquals("Geef een reden", assertThrows(UnprocessableBusinessRuleException.class,
                () -> closings.saveDecision(id, movement(saleId, false, null))).getMessage());
        assertEquals("Kies het onderwerp van deze beslissing", assertThrows(UnprocessableBusinessRuleException.class,
                () -> closings.saveDecision(id, movement(987_654_321L, false, "x"))).getMessage());
        view = closings.saveDecision(id, movement(setId, false, "Nagekeken: startstand"));
        assertNull(notice(view, "BEWEGING_NAKIJKEN"), "looked at, also when the answer stays the same");
        assertEquals(0, location(view, shelf.id()).reviewCount());
        view = closings.saveDecision(id, movement(saleId, false, "Op 30/12 meegegeven"));
        StockClosingMovementEntity unticked = view.movements().stream().filter(row -> row.movementId == saleId).findFirst().orElseThrow();
        assertFalse(unticked.applied);
        assertTrue(unticked.defaultApplied);
        assertEquals("Op 30/12 meegegeven", unticked.appliedReason);
        assertEquals(25, line(view, rose, shelf.id()).closingQuantity);
        assertNotNull(resource.get(id).movements().stream().filter(row -> row.movementId() == saleId).findFirst().orElseThrow().decisionId());

        /* Below zero on the closing date blocks. */
        stock.sell(rose, shelf.id(), 40, "F-TEST-2");
        long second = em.createQuery("from StockMovementEntity where reference = 'F-TEST-2'",
                be.enrosed.catalog.adapter.out.persistence.StockMovementEntity.class).getSingleResult().id;
        closings.recompute(id);
        view = closings.saveDecision(id, movement(second, false, "Was al weg"));
        assertNotice(view, "NEGATIEF", "BLOCKER", "datum", "Roos - rood staat op " + shelf.name() + " op -15 op de afsluitdatum."
                + " Verkopen worden altijd uit het magazijn afgeboekt, ook als de stuks op een verkooppunt lagen. Los dit op met een"
                + " verplaatsing en corrigeer de telling voor dat product, of pas hieronder aan welke bewegingen meetellen.");
        assertEquals("NEGATIEF", article(view, rose).status);
        assertEquals(0, article(view, rose).closingQuantity, "a negative location counts as nothing");
        assertEquals(-15, line(view, rose, shelf.id()).closingQuantity);
        view = closings.deleteDecision(id, view.decisions().stream().filter(row -> Objects.equals(row.movementId, second))
                .findFirst().orElseThrow().id);
        assertNull(notice(view, "NEGATIEF"));
        assertEquals(25, line(view, rose, shelf.id()).closingQuantity);

        /* A row that is struck from the stock book stays listed, counts for nothing and is named. */
        em.createQuery("delete from StockMovementEntity where id = ?1").setParameter(1, saleId).executeUpdate();
        em.clear();
        view = closings.recompute(id);
        assertNotice(view, "BEWEGING_VERDWENEN", "WARNING", "datum",
                "1 beweging uit de vorige berekening staat niet meer in de voorraadgeschiedenis: F-TEST-1."
                        + " Ze telt niet mee in het aantal op de afsluitdatum.");
        StockClosingMovementEntity vanished = view.movements().stream().filter(row -> row.movementId == saleId).findFirst().orElseThrow();
        assertTrue(vanished.removed);
        assertFalse(vanished.applied);
        view = closings.recompute(id);
        assertNotNull(notice(view, "BEWEGING_VERDWENEN"), "the notice survives every later compute");
        assertTrue(view.movements().stream().anyMatch(row -> row.movementId == saleId && row.removed));

        /* An open session blocks; booked, the location is anchored on the count. */
        long session = counts.start(YEAR1, shelf.id(), null, null).summary().count().id;
        view = closings.recompute(id);
        assertNotice(view, "TELLING_OPEN", "BLOCKER", "tellen", "De telling van " + shelf.name() + " is nog niet geboekt.");
        assertNotNull(notice(view, "TELLING_ONTBREEKT"));
        var counted = counts.view(session).lines().stream().filter(row -> row.row().productId == rose).findFirst().orElseThrow();
        counts.saveLine(session, counted.row().id, new LineWrite(0, "TELFOUT", null, counted.row().revision, null, null));
        counts.book(session, counts.bookingCheck(session).checkToken());
        view = closings.recompute(id);
        assertNull(notice(view, "TELLING_OPEN"));
        assertNull(notice(view, "TELLING_ONTBREEKT"));
        assertEquals("TELLING", location(view, shelf.id()).anchor());
        assertEquals(session, location(view, shelf.id()).countId());
        assertEquals(1, location(view, shelf.id()).differenceCount());

        /* A closing date that is not over yet. */
        View early = closings.create(InventoryClock.today().getYear(), null);
        assertNotice(early, "DATUM_TOEKOMST", "BLOCKER", "afsluiten", "De afsluitdatum is nog niet voorbij.");
        assertNull(notice(view, "DATUM_TOEKOMST"));
    }

    @Test @TestTransaction
    void aContainerThatIsNotBookedInBlocksAndOneBookedAfterTheCountIsPointedOut() {
        quiet();
        long rose = product("Roos");
        long tulip = product("Tulp");
        simpleContainer("C1", rose, 100, "2.00", LocalDate.of(YEAR1, 3, 1));
        bookOnReceipt = false;
        long late = simpleContainer("C2", tulip, 40, "5.00", LocalDate.of(YEAR1, 11, 20));
        countAll(YEAR1);

        View view = closings.create(YEAR1, null);
        long id = view.closing().id;
        assertNotice(view, "CONTAINER_NIET_BIJGEBOEKT", "BLOCKER", "tellen", "Container C2 is ontvangen op 20/11/" + YEAR1
                + " maar nog niet bijgeboekt. Boek de voorraad bij en corrigeer de telling voor die producten.");
        assertEquals(late, notice(view, "CONTAINER_NIET_BIJGEBOEKT").purchaseOrderId());
        assertNull(article(view, tulip), "not in the book, not counted");

        purchases.bookStock(late);
        em.flush(); em.clear();
        view = closings.recompute(id);
        assertNull(notice(view, "CONTAINER_NIET_BIJGEBOEKT"));
        assertNotice(view, "ONTVANGST_NA_TELLING", "WARNING", "datum", "Container C2 (ontvangen 20/11/" + YEAR1
                + ") is pas na de telling bijgeboekt. Kijk na of die stuks geteld zijn.");
        StockClosingMovementEntity booked = view.movements().stream().filter(row -> row.productId == tulip).findFirst().orElseThrow();
        assertFalse(booked.applied, "booked after the count: not taken out of the counted figure");
        assertEquals(0, line(view, tulip, stock.mainLocation().id()).closingQuantity, "the count did not hold them");
    }

    /* ------------------------------------------------------------ shown apart */

    @Test @TestTransaction
    void goodsOnTheWaterPartnerContainersAndThirdPartyGoodsAreShownApartAndDecidedByTheUser() {
        quiet();
        long a = product("A"), b = product("B"), own = product("Eigen"), shared = product("Partner"), empty = product("Leeg");
        simpleContainer("C1", own, 100, "2.00", LocalDate.of(YEAR1, 3, 1));
        /* On the water on the closing date: shipped 01/12, a deposit of US$ 1.200 paid on 01/09. */
        long transit = container("T1", null, LocalDate.of(YEAR1, 12, 1), List.of(new Ordered(a, 1000, "2.00", 0, 0),
                new Ordered(b, 500, "4.00", 0, 0)), null);
        pay(transit, LocalDate.of(YEAR1, 9, 1), new BigDecimal("1200"), Currency.USD, Payee.SUPPLIER, false, "1116.00");
        /* Ordered before the closing date, neither shipped nor paid by then: no candidate. */
        long ordered = container("T0", null, LocalDate.of(YEAR1, 12, 20), List.of(new Ordered(a, 10, "2.00", 0, 0)), null);
        em.find(PurchaseOrderEntity.class, ordered).shippedOn = null;
        var partner = customers.create(new Customer(null, "Partner NV", "Buyer", "partner@example.invalid", null, "BE0000000000", "BE",
                Language.NL, "Main 1", "2000", "Antwerpen", "DAP", null, null, LocalDate.now()));
        long p1 = container("P1", LocalDate.of(YEAR1, 10, 5), LocalDate.of(YEAR1, 9, 1), List.of(new Ordered(shared, 100, "2.00", 100, 0)),
                stored -> stored.partnerCustomerId = partner.id());
        pay(p1, LocalDate.of(YEAR1, 10, 1), new BigDecimal("184"), Currency.EUR, Payee.SUPPLIER, false, null);
        long p2 = container("P2", LocalDate.of(YEAR1, 10, 6), LocalDate.of(YEAR1, 9, 1), List.of(new Ordered(empty, 20, "2.00", 20, 0)),
                stored -> stored.partnerCustomerId = partner.id());
        pay(p2, LocalDate.of(YEAR1, 10, 1), new BigDecimal("36.80"), Currency.EUR, Payee.SUPPLIER, false, null);
        stock.setLevel(empty, stock.mainLocation().id(), 0, StockMovement.Kind.MANUAL_CORRECTION, "alles verkocht");
        em.flush(); em.clear();
        countAll(YEAR1);

        View view = closings.create(YEAR1, null);
        long id = view.closing().id;

        /* Goods in transit. */
        assertNull(container(view, ordered));
        StockClosingContainerEntity water = container(view, transit);
        assertEquals("ONDERWEG", water.role);
        assertEquals("BESTELD", water.quantityBasis);
        assertEquals("AFSLUITDATUM", water.rateCutoffSource);
        assertEquals(END1, water.rateCutoffDate);
        assertNull(water.receivedOn);
        assertMoney("1116.00", water.supplierPaidEur);
        assertMoney("2576.00", water.supplierOpenEur);
        assertMoney("3692.00", water.supplierIncludedEur);
        assertNotice(view, "BESLISSING_ONDERWEG", "BLOCKER", "apart",
                "Container T1 was onderweg op de afsluitdatum: beslis of hij opgenomen wordt.");
        List<StockClosingSeparateEntity> rows = separate(view, "ONDERWEG");
        assertEquals(2, rows.size());
        StockClosingSeparateEntity first = rows.stream().filter(row -> row.productId == a).findFirst().orElseThrow();
        assertEquals(1000, first.quantity, "the ordered pieces");
        assertEquals(new BigDecimal("1.8460"), first.unitValueEur);
        assertMoney("1846.00", first.valueEur);
        assertNull(first.included);
        assertEquals(LocalDate.of(YEAR1, 12, 1), first.shippedOn);
        assertMoney("1116.00", first.paidUntilClosingEur);
        assertEquals("Closing Co", first.counterparty);
        assertEquals("T1", first.documentName);
        assertMoney("3692.00", view.closing().transitExcludedEur, "undecided counts as not included");
        assertMoney("0.00", view.closing().transitIncludedEur);
        var item = resource.get(id).separate().stream().filter(row -> "ONDERWEG".equals(row.kind())).findFirst().orElseThrow();
        assertEquals("FOB Ningbo", item.supplierIncoterm());
        assertEquals(false, item.transportViaSupplier());

        assertEquals("Geef de datum waarop eigendom of risico overging", assertThrows(UnprocessableBusinessRuleException.class,
                () -> closings.saveDecision(id, transit(transit, true, null, "FOB"))).getMessage());
        assertEquals("Die datum moet op of voor de afsluitdatum liggen", assertThrows(UnprocessableBusinessRuleException.class,
                () -> closings.saveDecision(id, transit(transit, true, END1.plusDays(1), "FOB"))).getMessage());
        assertEquals("Geef een reden", assertThrows(UnprocessableBusinessRuleException.class,
                () -> closings.saveDecision(id, transit(transit, false, null, null))).getMessage());
        assertEquals("Kies het onderwerp van deze beslissing", assertThrows(UnprocessableBusinessRuleException.class,
                () -> closings.saveDecision(id, transit(987_654_321L, false, null, "x"))).getMessage());
        view = closings.saveDecision(id, transit(transit, false, null, "Risico nog bij de leverancier"));
        assertNull(notice(view, "BESLISSING_ONDERWEG"));
        assertEquals(false, separate(view, "ONDERWEG").getFirst().included);
        assertMoney("0.00", view.closing().transitIncludedEur);
        view = closings.saveDecision(id, transit(transit, true, LocalDate.of(YEAR1, 12, 15), "FOB: risico over bij het laden"));
        assertEquals(1, view.decisions().stream().filter(row -> "TRANSIT".equals(row.kind)).count());
        water = container(view, transit);
        assertEquals("ONDERWEG", water.rateCutoffSource);
        assertEquals(LocalDate.of(YEAR1, 12, 15), water.rateCutoffDate);
        first = separate(view, "ONDERWEG").stream().filter(row -> row.productId == a).findFirst().orElseThrow();
        assertTrue(first.included);
        assertEquals(LocalDate.of(YEAR1, 12, 15), first.ownershipDate);
        assertEquals("FOB: risico over bij het laden", first.reason);
        assertEquals("emre", first.decidedByName);
        assertMoney("3692.00", view.closing().transitIncludedEur);
        assertMoney("0.00", view.closing().transitExcludedEur);
        assertMoney("3876.00", view.closing().totalValueEur, "own 184,00 plus the goods on the water");
        assertMoney("2576.00", view.closing().estimatedEur, "the balance is still owed");
        assertNotice(view, "APART_ZONDER_MARKTTOETS", "WARNING", "apart",
                "Op opgenomen partnercontainers en goederen onderweg (€ 3.692,00) is geen lagere marktwaarde ingevoerd.");
        assertNotice(view, "GESCHAT", "WARNING", "waarde", "1 container met geschatte kosten: € 2.576,00 in de voorraadwaarde.");

        /* Partner containers: the pieces that still lie there, at the unit value of the partner lot. */
        assertEquals("PARTNER", container(view, p1).role);
        assertEquals("Partner NV", container(view, p1).partnerName);
        assertNotice(view, "BESLISSING_PARTNER", "BLOCKER", "apart", "Partnercontainer P1: beslis of hij opgenomen wordt.");
        assertEquals(1, notices(view, "BESLISSING_PARTNER").size(), "none for the container without a piece left");
        StockClosingSeparateEntity lying = separate(view, "PARTNER").stream().filter(row -> row.purchaseOrderId == p1).findFirst().orElseThrow();
        assertEquals(100, lying.proposedQuantity);
        assertEquals(100, lying.quantity);
        assertMoney("184.00", lying.valueEur);
        assertNull(lying.included);
        StockClosingSeparateEntity nothing = separate(view, "PARTNER").stream().filter(row -> row.purchaseOrderId == p2).findFirst().orElseThrow();
        assertEquals(0, nothing.quantity);
        assertEquals(100, article(view, shared).partnerQuantity);
        assertEquals(0, article(view, shared).ownQuantity);
        assertEquals(List.of(), layers(view, shared), "partner lots are not in the own layers");
        assertMoney("184.00", view.closing().partnerExcludedEur);

        assertEquals("Aantal kan niet negatief zijn", assertThrows(UnprocessableBusinessRuleException.class, () -> closings.saveDecision(id,
                decision("PARTNER_QUANTITY", w -> { w.put("purchaseOrderId", p1); w.put("productId", shared); w.put("quantity", -1); w.put("reason", "x"); })))
                .getMessage());
        closings.saveDecision(id, decision("PARTNER_QUANTITY", w -> { w.put("purchaseOrderId", p1); w.put("productId", shared);
            w.put("quantity", 60); w.put("reason", "40 stuks zijn van een oudere eigen container"); }));
        view = closings.saveDecision(id, decision("PARTNER_CONTAINER", w -> { w.put("purchaseOrderId", p1); w.put("flag", true);
            w.put("reason", "Eigendom tot de afrekening"); }));
        assertNull(notice(view, "BESLISSING_PARTNER"));
        lying = separate(view, "PARTNER").stream().filter(row -> row.purchaseOrderId == p1).findFirst().orElseThrow();
        assertEquals(60, lying.quantity);
        assertEquals(100, lying.proposedQuantity);
        assertTrue(lying.included);
        assertMoney("110.40", view.closing().partnerIncludedEur);
        assertEquals(40, article(view, shared).unvaluedQuantity, "the rest has no own lot");
        assertNotice(view, "APART_ZONDER_MARKTTOETS", "WARNING", "apart",
                "Op opgenomen partnercontainers en goederen onderweg (€ 3.802,40) is geen lagere marktwaarde ingevoerd.");

        /* Goods of third parties leave the quantity first. */
        assertEquals("Vermeld de eigenaar", assertThrows(UnprocessableBusinessRuleException.class,
                () -> closings.saveDecision(id, thirdParty(null, own, 10, " ", "x"))).getMessage());
        assertEquals("Geef een aantal groter dan nul", assertThrows(UnprocessableBusinessRuleException.class,
                () -> closings.saveDecision(id, thirdParty(null, own, 0, "Atelier Mira", "x"))).getMessage());
        assertEquals("Product 987654321 bestaat niet", assertThrows(NotFoundException.class,
                () -> closings.saveDecision(id, thirdParty(null, 987_654_321L, 1, "Atelier Mira", "x"))).getMessage());
        view = closings.saveDecision(id, thirdParty(null, own, 10, "Atelier Mira", "In bewaring"));
        view = closings.saveDecision(id, thirdParty(null, own, 5, "Bloemen Peeters", "Afgehaald in januari"));
        assertEquals(2, separate(view, "DERDEN").size(), "a third-party quantity always adds");
        assertEquals(15, article(view, own).thirdPartyQuantity);
        assertEquals(85, article(view, own).ownQuantity);
        assertMoney("156.40", article(view, own).costValueEur);
        long thirdId = separate(view, "DERDEN").getFirst().decisionId;
        view = closings.saveDecision(id, thirdParty(thirdId, own, 500, "Atelier Mira", "In bewaring"));
        assertEquals(2, separate(view, "DERDEN").size(), "the same row, changed");
        assertNotice(view, "DERDEN_TE_VEEL", "BLOCKER", "apart", "Eigen - rood: meer goederen van derden dan er geteld zijn.");
        view = closings.deleteDecision(id, thirdId);
        assertNull(notice(view, "DERDEN_TE_VEEL"));
        assertEquals(95, article(view, own).ownQuantity);
    }

    @Test @TestTransaction
    void anInvoiceThatWasNotAfgepuntIsAskedAboutAndNoPieceLeavesTwice() {
        quiet();
        long rose = product("Roos");
        simpleContainer("C1", rose, 100, "2.00", LocalDate.of(YEAR1, 3, 1));
        SalesOrder first = invoice(rose, 30, LocalDate.of(YEAR1, 12, 20));
        SalesOrder second = invoice(rose, 20, LocalDate.of(YEAR1, 12, 10));
        SalesOrder shipped = invoice(rose, 15, LocalDate.of(YEAR1, 12, 28));
        SalesOrder old = invoice(rose, 8, LocalDate.of(YEAR1 - 1, 6, 15));
        SalesOrder next = invoice(rose, 3, LocalDate.of(YEAR2, 1, 10));
        /* Afgepunt today, before the count: the count no longer holds those 15. */
        sales.shipGoods(shipped.id());
        em.flush(); em.clear();
        countAll(YEAR1);

        View view = closings.create(YEAR1, null);
        long id = view.closing().id;

        assertEquals(100, article(view, rose).closingQuantity, "85 counted plus the 15 that left after the closing date");
        List<StockClosingSeparateEntity> rows = separate(view, "GEFACTUREERD");
        assertEquals(List.of(second.id(), first.id(), shipped.id()), rows.stream().map(row -> row.salesOrderId).toList(),
                "the invoices of the year, by date");
        assertTrue(rows.stream().allMatch(row -> row.choice == null && !row.automatic && row.valueEur == null));
        assertEquals(first.number(), rows.get(1).documentNumber);
        assertEquals(LocalDate.of(YEAR1, 12, 20), rows.get(1).documentDate);
        assertTrue(rows.get(1).counterparty.startsWith("Klant "));
        assertEquals(3, notices(view, "BESLISSING_GEFACTUREERD").size());
        assertNotice(view, "BESLISSING_GEFACTUREERD", "BLOCKER", "apart", "Factuur " + first.number()
                + " is gefactureerd maar nog niet afgepunt: geef aan of de stuks er op de afsluitdatum nog lagen.");
        assertEquals(100, article(view, rose).ownQuantity, "undecided counts as 'blijft'");

        /* Older than the year and never afgepunt: listed, stored, never asked about. */
        List<StockClosingSeparateEntity> older = separate(view, "OUDER");
        assertEquals(1, older.size());
        assertEquals(old.id(), older.getFirst().salesOrderId);
        assertEquals(8, older.getFirst().quantity);
        assertNull(older.getFirst().productId);
        assertNull(older.getFirst().valueEur);
        StockClosingDtos.ClosingView json = resource.get(id);
        assertEquals(1, json.olderInvoices().size());
        assertEquals(old.number(), json.olderInvoices().getFirst().number());
        assertEquals(LocalDate.of(YEAR1 - 1, 6, 15), json.olderInvoices().getFirst().orderDate());
        assertEquals(8, json.olderInvoices().getFirst().quantity());
        assertTrue(json.separate().stream().noneMatch(row -> "OUDER".equals(row.kind())));

        assertEquals("Kies wat er met de stuks van deze factuur was", assertThrows(UnprocessableBusinessRuleException.class,
                () -> closings.saveDecision(id, invoiced(List.of(first.id()), "MISSCHIEN", "x"))).getMessage());
        assertEquals("Geef een reden", assertThrows(UnprocessableBusinessRuleException.class,
                () -> closings.saveDecision(id, invoiced(List.of(first.id()), "BLIJFT", null))).getMessage());
        assertEquals("Factuur " + old.number() + " hoort niet bij deze afsluiting", assertThrows(UnprocessableBusinessRuleException.class,
                () -> closings.saveDecision(id, invoiced(List.of(first.id(), old.id()), "UIT", null))).getMessage());
        assertEquals("Factuur " + next.number() + " hoort niet bij deze afsluiting", assertThrows(UnprocessableBusinessRuleException.class,
                () -> closings.saveDecision(id, invoiced(List.of(next.id()), "UIT", null))).getMessage());
        assertEquals(0, decisionRows.count("closingId = ?1 and kind = 'INVOICED'", id), "a refused write stores nothing");

        /* One write for several invoices: a decision per invoice, one compute. */
        view = closings.saveDecision(id, invoiced(List.of(first.id(), second.id()), "UIT", null));
        assertEquals(2, view.decisions().stream().filter(row -> "INVOICED".equals(row.kind)).count());
        assertEquals(1, notices(view, "BESLISSING_GEFACTUREERD").size());
        assertEquals(50, article(view, rose).invoicedOutQuantity);
        assertEquals(50, article(view, rose).ownQuantity);
        assertMoney("92.00", view.closing().invoicedOutEur);
        assertMoney("92.00", view.closing().totalValueEur, "the carved pieces are shown apart, not in the total");
        StockClosingSeparateEntity carved = separate(view, "GEFACTUREERD").stream().filter(row -> row.salesOrderId == first.id()).findFirst().orElseThrow();
        assertEquals("UIT", carved.choice);
        assertMoney("55.20", carved.valueEur);
        assertEquals(2, layers(view, rose).stream().filter(layer -> "GEFACTUREERD".equals(layer.block)).count());

        view = closings.saveDecision(id, invoiced(List.of(first.id()), "AL_WEG", "Op 22/12 opgehaald"));
        assertEquals(2, view.decisions().stream().filter(row -> "INVOICED".equals(row.kind)).count(), "the same invoice replaces");
        assertEquals(20, article(view, rose).invoicedOutQuantity);
        assertEquals(80, article(view, rose).ownQuantity, "nothing is carved for pieces that were already gone");

        /* The sale of the third invoice is unticked in step 2: those pieces left before the closing date. */
        long saleRow = view.movements().stream().filter(row -> "SALE".equals(row.kind) && shipped.number().equals(row.refText))
                .findFirst().orElseThrow().movementId;
        closings.saveDecision(id, invoiced(List.of(shipped.id()), "UIT", null));
        view = closings.saveDecision(id, movement(saleRow, false, "Op 29/12 meegegeven"));
        assertEquals(85, article(view, rose).closingQuantity);
        StockClosingSeparateEntity automatic = separate(view, "GEFACTUREERD").stream().filter(row -> row.salesOrderId == shipped.id())
                .findFirst().orElseThrow();
        assertTrue(automatic.automatic);
        assertEquals("AL_WEG", automatic.choice, "whatever the invoice's decision says");
        assertEquals("Al verwerkt bij de bewegingen van stap 2", automatic.reason);
        assertNull(automatic.valueEur);
        assertEquals(20, article(view, rose).invoicedOutQuantity);
        assertEquals(65, article(view, rose).ownQuantity);
        assertNull(notice(view, "BESLISSING_GEFACTUREERD"));
        closings.deleteDecision(id, view.decisions().stream().filter(row -> Objects.equals(row.salesOrderId, shipped.id())).findFirst().orElseThrow().id);
        assertNull(notice(closings.view(id), "BESLISSING_GEFACTUREERD"), "an automatic row needs no decision");
    }

    /* ------------------------------------------ opening values and write-downs */

    @Test @TestTransaction
    void stockWithoutALotNeedsAnOpeningValueAndALowerMarketValueTakesTheDearestPiecesFirst() {
        quiet();
        StockLocation shelf = location("Winkel");
        long old = product("Oud");
        ProductEntity demo = em.find(ProductEntity.class, product("Demo"));
        demo.demo = true;
        em.flush();
        long demoId = demo.id;
        level(old, shelf, 80);
        long c1 = simpleContainer("C1", old, 100, "2.00", LocalDate.of(YEAR1, 3, 1));
        simpleContainer("C2", demoId, 10, "5.00", LocalDate.of(YEAR1, 4, 1));
        countAll(YEAR1);

        View view = closings.create(YEAR1, null);
        long id = view.closing().id;
        assertNotice(view, "ZONDER_WAARDE", "BLOCKER", "waarde",
                "1 product (80 stuks) zonder gewaardeerde partij. Vul een beginwaarde met bron in.");
        assertEquals(80, view.closing().unvaluedQuantity);
        assertNotice(view, "DEMO_VOL", "WARNING", "waarde", "1 demoproduct staat aan volle aanschafwaarde.");
        assertMoney("46.00", view.closing().demoValueEur);
        assertTrue(article(view, demoId).demo);

        /* Opening values: one date and one source for all rows; nothing computes by itself. */
        assertEquals("Vermeld de bron van deze beginwaarde", assertThrows(UnprocessableBusinessRuleException.class,
                () -> openingLayers.save(opening(END1.minusDays(1), " ", old, 80, "1.50"))).getMessage());
        assertEquals("Geef de datum van deze waarde", assertThrows(UnprocessableBusinessRuleException.class,
                () -> openingLayers.save(opening(null, "inventaris", old, 80, "1.50"))).getMessage());
        assertEquals("Geef per product een aantal groter dan nul", assertThrows(UnprocessableBusinessRuleException.class,
                () -> openingLayers.save(opening(END1, "inventaris", old, 0, "1.50"))).getMessage());
        assertEquals("Geef per product een waarde per stuk", assertThrows(UnprocessableBusinessRuleException.class,
                () -> openingLayers.save(opening(END1, "inventaris", old, 80, null))).getMessage());
        assertEquals("Product 987654321 bestaat niet", assertThrows(NotFoundException.class,
                () -> openingLayers.save(opening(END1, "inventaris", 987_654_321L, 80, "1.50"))).getMessage());
        assertEquals("Beginwaarde 987654321 bestaat niet", assertThrows(NotFoundException.class,
                () -> openingLayers.retire(987_654_321L)).getMessage());
        LocalDate asOf = LocalDate.of(YEAR1 - 1, 12, 31);
        StockOpeningLayerEntity first = openingLayers.save(opening(asOf, "inventaris 31/12/" + (YEAR1 - 1), old, 60, "1.40")).getFirst();
        assertEquals("Oud - rood", first.productName);
        assertEquals("emre", first.createdBy);
        assertEquals(80, article(closings.view(id), old).unvaluedQuantity, "writing a layer computes nothing");
        /* The same product and date again: the earlier row is retired, never edited. */
        StockOpeningLayerEntity replaced = openingLayers.save(opening(asOf, "inventaris 31/12/" + (YEAR1 - 1), old, 80, "1.50")).getFirst();
        assertNotEquals(first.id, replaced.id);
        assertNotNull(em.find(StockOpeningLayerEntity.class, first.id).retiredAt);
        assertEquals(60, em.find(StockOpeningLayerEntity.class, first.id).quantity);
        assertEquals(List.of(replaced.id), openingLayers.active().stream().map(layer -> layer.id).toList());

        view = closings.recompute(id);
        assertNull(notice(view, "ZONDER_WAARDE"));
        List<StockClosingLayerEntity> built = layers(view, old);
        assertEquals(2, built.size());
        assertEquals("PARTIJ", built.get(0).source);
        assertEquals("BEGINWAARDE", built.get(1).source, "always the oldest layer");
        assertEquals(replaced.id, built.get(1).openingLayerId);
        assertEquals(asOf, built.get(1).receivedOn);
        assertEquals(80, built.get(1).quantity);
        assertMoney("120.00", article(view, old).openingEur);
        assertMoney("304.00", article(view, old).costValueEur);
        assertEquals(List.of(replaced.id), view.openingLayers().stream().map(StockClosingService.OpeningLayerView::id).toList());
        /* Spread over the two locations in proportion to quantity, adding up to the product. */
        StockClosingLineEntity inShop = line(view, old, shelf.id());
        StockClosingLineEntity inWarehouse = line(view, old, stock.mainLocation().id());
        assertMoney("135.11", inShop.costValueEur, "304,00 x 80 / 180");
        assertMoney("168.89", inWarehouse.costValueEur);
        assertMoney("120.00", inShop.openingEur.add(inWarehouse.openingEur));
        var shown = resource.get(id).articles().stream().filter(row -> row.productId() == old).findFirst().orElseThrow();
        assertEquals(180, shown.locations().stream().mapToInt(StockClosingDtos.ClosingArticleLocation::ownQuantity).sum());

        /* Waardeverminderingen. */
        assertEquals("Kies een reden voor de waardevermindering", assertThrows(UnprocessableBusinessRuleException.class,
                () -> closings.saveDecision(id, writeDown(null, old, 10, "0.50", "ROEST", "x"))).getMessage());
        assertEquals("Marktwaarde per stuk kan niet negatief zijn", assertThrows(UnprocessableBusinessRuleException.class,
                () -> closings.saveDecision(id, writeDown(null, old, 10, "-0.01", "BESCHADIGD", "x"))).getMessage());
        assertEquals("Geef een aantal groter dan nul, of laat het leeg voor alle stuks", assertThrows(UnprocessableBusinessRuleException.class,
                () -> closings.saveDecision(id, writeDown(null, old, 0, "0.50", "BESCHADIGD", "x"))).getMessage());
        assertEquals("Geef een reden", assertThrows(UnprocessableBusinessRuleException.class,
                () -> closings.saveDecision(id, writeDown(null, old, 10, "0.50", "BESCHADIGD", null))).getMessage());
        InventoryRefusal tooMany = assertThrows(InventoryRefusal.class,
                () -> closings.saveDecision(id, writeDown(null, old, 181, "0.50", "BESCHADIGD", "x")));
        assertEquals("AFWAARDERING_TE_VEEL", tooMany.code());
        assertEquals("De aantallen met een waardevermindering zijn samen meer dan de eigen voorraad (180)", tooMany.getMessage());

        view = closings.saveDecision(id, writeDown(null, old, 12, "0.50", "BESCHADIGD", "Stolpen gebarsten"));
        StockClosingWriteDownEntity lowered = view.writeDowns().getFirst();
        assertEquals(1, lowered.layerPosition, "the dearest layer: the container at 1,84");
        assertEquals(12, lowered.quantity);
        assertMoney("16.08", lowered.amountEur, "12 x (1,84 - 0,50)");
        assertEquals("C1 · ontvangen 01/03/" + YEAR1, lowered.layerLabel);
        assertMoney("16.08", article(view, old).writeDownEur);
        assertMoney("287.92", article(view, old).ownValueEur);
        assertMoney("304.00", article(view, old).costValueEur, "the acquisition value stays");
        assertMoney("16.08", view.closing().writeDownEur);
        assertMoney("350.00", view.closing().costValueEur);
        assertMoney("333.92", view.closing().totalValueEur);
        assertEquals("Beschadigd", resource.get(id).writeDowns().getFirst().reasonLabel());
        long firstDecision = lowered.decisionId;

        /* A second decision without a quantity takes every piece the first left untouched. */
        view = closings.saveDecision(id, writeDown(null, old, null, "1.60", "MARKT", "Prijslijst " + YEAR2));
        assertEquals(List.of("12:16.08", "88:21.12", "80:0.00"), view.writeDowns().stream()
                .map(row -> row.quantity + ":" + row.amountEur.setScale(2)).toList());
        assertMoney("37.20", article(view, old).writeDownEur);
        assertNull(notice(view, "AFWAARDERING_ZONDER_EFFECT"));

        /* Edited in place, the first keeps its turn; a market value above cost lowers nothing. */
        view = closings.saveDecision(id, writeDown(firstDecision, old, 12, "9.00", "BESCHADIGD", "Toch verkoopbaar"));
        assertEquals(2, view.decisions().stream().filter(row -> "WRITE_DOWN".equals(row.kind)).count());
        assertEquals(firstDecision, view.writeDowns().getFirst().decisionId);
        assertMoney("0.00", view.writeDowns().getFirst().amountEur);
        assertNotice(view, "AFWAARDERING_ZONDER_EFFECT", "WARNING", "waarde",
                "Oud - rood: de marktwaarde ligt niet onder de aanschafwaarde; er is geen waardevermindering.");

        /* A demo piece with a waardevermindering is no longer at full value. */
        view = closings.saveDecision(id, writeDown(null, demoId, null, "1.00", "DEMO", "Toonstukken"));
        assertNull(notice(view, "DEMO_VOL"));
        assertMoney("10.00", view.closing().demoValueEur, "10 x 1,00");

        /* The explicit quantities stay on file when the own stock shrinks: then they block. */
        long correction = counts.start(YEAR1, shelf.id(), null, counts.anchors(YEAR1).stream()
                .filter(anchor -> anchor.locationId() == shelf.id()).findFirst().orElseThrow().base().id).summary().count().id;
        var added = counts.addLine(correction, old).line().row();
        counts.saveLine(correction, added.id, new LineWrite(0, "NIET_GEVONDEN", null, added.revision, null, null));
        counts.book(correction, counts.bookingCheck(correction).checkToken());
        closings.deleteDecision(id, firstDecision);
        closings.saveDecision(id, writeDown(null, old, 90, "0.50", "BESCHADIGD", "a"));
        view = closings.saveDecision(id, writeDown(null, old, 10, "0.50", "BESCHADIGD", "b"));
        assertNull(notice(view, "AFWAARDERING_TE_VEEL"), "100 of 100");
        em.find(PurchaseOrderEntity.class, c1).status = PurchaseOrderStatus.CONCEPT;
        em.flush(); em.clear();
        view = closings.recompute(id);
        assertNotice(view, "AFWAARDERING_TE_VEEL", "BLOCKER", "waarde",
                "Oud - rood: de aantallen met een waardevermindering zijn samen meer dan de eigen voorraad.");
    }

    /* ---------------------------------------------------- from year to year */

    @Test @TestTransaction
    void aLaterYearCarriesTheFrozenLayersAndTheBorderDateOfTheFinalClosingBeforeIt() {
        quiet();
        StockLocation shelf = location("Winkel");
        long rose = product("Roos"), old = product("Oud"), a = product("A"), b = product("B"), shared = product("Partner");
        long c1 = simpleContainer("C1", rose, 100, "2.00", LocalDate.of(YEAR1, 11, 20));
        /* Sold out before the count of year 1: a lot of that year without a layer. */
        long soldOut = product("Uitverkocht");
        long c5 = simpleContainer("C5", soldOut, 20, "2.00", LocalDate.of(YEAR1, 5, 1));
        stock.setLevel(soldOut, stock.mainLocation().id(), 0, StockMovement.Kind.MANUAL_CORRECTION, "alles verkocht");
        level(old, shelf, 50);
        StockOpeningLayerEntity used = openingLayers.save(opening(LocalDate.of(YEAR1 - 1, 12, 31), "inventaris", old, 50, "1.50")).getFirst();
        long transit = container("T1", null, LocalDate.of(YEAR1, 12, 1), List.of(new Ordered(a, 1000, "2.00", 1000, 0),
                new Ordered(b, 500, "4.00", 500, 0)), null);
        pay(transit, LocalDate.of(YEAR1, 9, 1), new BigDecimal("1200"), Currency.USD, Payee.SUPPLIER, false, "1116.00");
        var partner = customers.create(new Customer(null, "Partner NV", "Buyer", "partner@example.invalid", null, "BE0000000000", "BE",
                Language.NL, "Main 1", "2000", "Antwerpen", "DAP", null, null, LocalDate.now()));
        long p1 = container("P1", LocalDate.of(YEAR1, 10, 5), LocalDate.of(YEAR1, 9, 1), List.of(new Ordered(shared, 100, "2.00", 100, 0)),
                stored -> stored.partnerCustomerId = partner.id());
        pay(p1, LocalDate.of(YEAR1, 10, 1), new BigDecimal("184"), Currency.EUR, Payee.SUPPLIER, false, null);
        SalesOrder unshipped = invoice(rose, 2, LocalDate.of(YEAR1, 12, 20));
        countAll(YEAR1);

        long first = closings.create(YEAR1, null).closing().id;
        closings.saveDecision(first, vat());
        closings.saveDecision(first, invoiced(List.of(unshipped.id()), "BLIJFT", "Staat klaar voor de klant"));
        closings.saveDecision(first, transit(transit, true, LocalDate.of(YEAR1, 12, 15), "FOB: risico over bij het laden"));
        closings.saveDecision(first, decision("PARTNER_CONTAINER", w -> { w.put("purchaseOrderId", p1); w.put("flag", true); w.put("reason", "Eigendom"); }));
        closings.saveDecision(first, writeDown(null, old, 10, "0.50", "VEROUDERD", "Oude collectie"));
        /* The border is free in the year that first values the container. */
        assertEquals("Die datum moet op of voor de ontvangstdatum en de afsluitdatum liggen", assertThrows(UnprocessableBusinessRuleException.class,
                () -> closings.saveDecision(first, ownership(c1, LocalDate.of(YEAR1, 11, 21), "x"))).getMessage());
        assertEquals("Geef de datum waarop eigendom of risico overging", assertThrows(UnprocessableBusinessRuleException.class,
                () -> closings.saveDecision(first, ownership(c1, null, "x"))).getMessage());
        View year1 = closings.saveDecision(first, ownership(c1, LocalDate.of(YEAR1, 10, 30), "Bill of lading"));
        assertEquals("EIGEN", lot(year1, c5, soldOut).role);
        assertEquals(List.of(), layers(year1, soldOut));
        assertEquals("BESLISSING", container(year1, c1).rateCutoffSource);
        assertEquals(LocalDate.of(YEAR1, 10, 30), container(year1, c1).rateCutoffDate);
        assertEquals("Eigendom of risico (ingevoerd)", shown(first, c1).rateCutoffSourceLabel());
        assertNotNull(shown(first, c1).ownershipDecisionId());
        assertEquals("ONTVANGST", container(year1, p1).rateCutoffSource);
        assertEquals(List.of(), blockers(year1));
        assertMoney("10.00", article(year1, old).writeDownEur);
        markFinal(first);
        View frozen = closings.view(first);
        assertTrue(frozen.canCorrect());
        assertFalse(frozen.canFinalize());

        /* After the closing: the balance is paid, the container arrives, an old container is edited, one is registered late. */
        pay(transit, LocalDate.of(YEAR2, 1, 5), new BigDecimal("2800"), Currency.USD, Payee.SUPPLIER, false, "2604.00");
        purchases.receive(transit, new PurchaseOrderService.Receipt(List.of(new PurchaseOrderService.ReceivedLine(a, 1000, 0),
                new PurchaseOrderService.ReceivedLine(b, 500, 0)), true, null, LocalDate.now(), null));
        em.find(PurchaseOrderEntity.class, transit).receivedOn = LocalDate.of(YEAR2, 1, 20);
        pay(c1, LocalDate.of(YEAR2, 2, 1), new BigDecimal("10"), Currency.EUR, Payee.SUPPLIER, false, null);
        em.find(PurchaseOrderEntity.class, c1).receivedOn = LocalDate.of(YEAR2, 2, 15);
        long late = simpleContainer("C9", rose, 30, "3.00", LocalDate.of(YEAR1, 8, 1));
        em.find(PurchaseOrderEntity.class, c5).receivedOn = LocalDate.of(YEAR2, 3, 10);
        stock.setLevel(soldOut, stock.mainLocation().id(), 20, StockMovement.Kind.MANUAL_CORRECTION, "teruggevonden");
        StockOpeningLayerEntity unused = openingLayers.save(opening(LocalDate.of(YEAR1 - 1, 12, 1), "oude lijst", old, 5, "1.20")).getFirst();
        em.flush(); em.clear();
        countAll(YEAR2);

        View year2 = closings.create(YEAR2, null);
        long second = year2.closing().id;
        assertEquals(first, year2.previous().id);
        assertEquals(first, year2.closing().previousClosingId);

        /* The frozen border: the date of last year's decision, also now that the container is in. */
        StockClosingContainerEntity arrived = container(year2, transit);
        assertEquals("EIGEN", arrived.role);
        assertEquals("VORIGE_AFSLUITING", arrived.rateCutoffSource);
        assertEquals(LocalDate.of(YEAR1, 12, 15), arrived.rateCutoffDate);
        assertMoney("3692.00", arrived.supplierPaidEur, "the balance of US$ 2.800 keeps counting 2.576,00");
        assertMoney("28.00", arrived.exchangeDifferenceEur);
        String fixed = "De datum van eigendom of risico ligt vast sinds de afsluiting van " + YEAR1 + ": 15/12/" + YEAR1 + ".";
        assertEquals(fixed, assertThrows(UnprocessableBusinessRuleException.class,
                () -> closings.saveDecision(second, ownership(transit, LocalDate.of(YEAR2, 1, 10), "x"))).getMessage());
        assertEquals(fixed, assertThrows(UnprocessableBusinessRuleException.class,
                () -> closings.saveDecision(second, transit(transit, true, LocalDate.of(YEAR2, 1, 10), "x"))).getMessage());
        assertEquals(0, decisionRows.count("closingId", second), "nothing is stored that can have no effect");
        assertMoney("3692.00", container(closings.recompute(second), transit).supplierPaidEur);
        assertEquals("Overgenomen uit de vorige afsluiting", shown(second, transit).rateCutoffSourceLabel());
        /* A partner container is valued again every year, on the same date. */
        assertEquals("PARTNER", container(year2, p1).role);
        assertEquals("VORIGE_AFSLUITING", container(year2, p1).rateCutoffSource);
        assertEquals(LocalDate.of(YEAR1, 10, 5), container(year2, p1).rateCutoffDate);
        assertEquals("De datum van eigendom of risico ligt vast sinds de afsluiting van " + YEAR1 + ": 05/10/" + YEAR1 + ".",
                assertThrows(UnprocessableBusinessRuleException.class,
                        () -> closings.saveDecision(second, ownership(p1, LocalDate.of(YEAR1, 9, 1), "x"))).getMessage());

        /* Membership: the container of last year is no lot again, whatever its receipt day says today. */
        year2 = closings.view(second);
        StockClosingContainerEntity known = container(year2, c1);
        assertEquals("VORIG", known.role, "recomputed for comparison only");
        assertEquals("VORIGE_AFSLUITING", known.rateCutoffSource);
        assertEquals(LocalDate.of(YEAR1, 10, 30), known.rateCutoffDate);
        List<StockClosingLayerEntity> roses = layers(year2, rose);
        assertEquals(2, roses.size());
        assertEquals("VORIG", roses.get(0).source);
        assertEquals("PARTIJ", roses.get(0).originSource);
        assertEquals(first, roses.get(0).originClosingId);
        assertEquals(c1, roses.get(0).purchaseOrderId);
        assertEquals(100, roses.get(0).capacity, "capped at what the previous closing froze");
        assertEquals(new BigDecimal("1.8400"), roses.get(0).unitValueEur, "the frozen unit, not the 1,94 of today");
        assertEquals(LocalDate.of(YEAR1, 11, 20), roses.get(0).receivedOn);
        assertEquals(YEAR1, resource.get(second).articles().stream().filter(row -> row.productId() == rose).findFirst().orElseThrow()
                .layers().getFirst().originClosingYear());
        assertNull(container(year2, c5), "a lot of year 1 is never a lot again, also when nothing of it was carried");
        assertEquals(20, article(year2, soldOut).unvaluedQuantity);
        assertEquals(List.of(), layers(year2, soldOut));
        /* The late lot: at its own cost, below the carried layer that was received after it. */
        assertEquals("PARTIJ", roses.get(1).source);
        assertEquals(late, roses.get(1).purchaseOrderId);
        assertEquals(new BigDecimal("2.7600"), roses.get(1).unitValueEur);
        assertMoney("266.80", article(year2, rose).costValueEur, "100 x 1,84 + 30 x 2,76");
        assertNotice(year2, "LAAT_ONTVANGEN_VORIG_JAAR", "WARNING", "waarde", "Container C9: ontvangen op 01/08/" + YEAR1
                + ", niet in de afsluiting van " + YEAR1 + ": opgenomen als partij van dit jaar.");
        StockClosingLotEntity compared = lot(year2, c1, rose);
        assertEquals("VORIG", compared.role);
        assertEquals(new BigDecimal("1.9400"), compared.unitValueEur);
        assertEquals(new BigDecimal("1.8400"), compared.previousUnitValueEur);
        assertEquals(first, compared.previousClosingId);
        assertNotice(year2, "PARTIJ_GEWIJZIGD", "WARNING", "waarde", "1 partij heeft nu een andere waarde per stuk dan in de"
                + " afsluiting van " + YEAR1 + ": verschil € 10,00, niet verwerkt.");
        assertTrue(notices(year2, "MEER_BETAALD").isEmpty(), "a container of an earlier year raises nothing of its own");

        /* The opening value of last year lives on in the carried layer and is not offered for removal. */
        StockClosingLayerEntity carried = layers(year2, old).getFirst();
        assertEquals("VORIG", carried.source);
        assertEquals("BEGINWAARDE", carried.originSource);
        assertEquals(used.id, carried.openingLayerId);
        assertEquals("inventaris", carried.openingSource);
        assertEquals(new BigDecimal("1.5000"), carried.unitValueEur);
        assertMoney("75.00", article(year2, old).openingEur);
        assertEquals(List.of(unused.id), year2.openingLayers().stream().map(StockClosingService.OpeningLayerView::id).toList());
        assertEquals(1, notices(year2, "BEGINWAARDE_BUITEN_PERIODE").size(), "only the one no closing used");
        assertNotice(year2, "BEGINWAARDE_BUITEN_PERIODE", "WARNING", "waarde", "Beginwaarde van Oud - rood per 01/12/" + (YEAR1 - 1)
                + " ligt niet na de vorige afsluitdatum en wordt niet gebruikt.");
        assertEquals(old, notice(year2, "BEGINWAARDE_BUITEN_PERIODE").productId());

        /* A new year starts without waardeverminderingen. */
        assertMoney("0.00", article(year2, old).writeDownEur);
        assertMoney("10.00", article(year2, old).previousWriteDownEur);
        assertEquals(List.of(), year2.writeDowns());
        assertNotice(year2, "VORIG_AFGEWAARDEERD", "WARNING", "waarde",
                "Vorig jaar een waardevermindering, dit jaar niet: 1 product, € 10,00.");
        assertEquals(List.of(), separate(year2, "ONDERWEG"));
        /* An invoice from before the previous closing date that was never afgepunt is only listed. */
        assertEquals(List.of(unshipped.id()), separate(year2, "OUDER").stream().map(row -> row.salesOrderId).toList());
        assertEquals(List.of(), separate(year2, "GEFACTUREERD"));
        assertNull(notice(year2, "BESLISSING_GEFACTUREERD"));

        /* A final closing shows its own copy of the opening values, whatever happens to them later. */
        openingLayers.retire(used.id);
        View stillFrozen = closings.view(first);
        assertEquals(List.of(used.id), stillFrozen.openingLayers().stream().map(StockClosingService.OpeningLayerView::id).toList());
        assertEquals(50, stillFrozen.openingLayers().getFirst().quantity());
        assertEquals("inventaris", stillFrozen.openingLayers().getFirst().source());
        assertEquals("emre", stillFrozen.openingLayers().getFirst().createdByName());
    }

    /* ------------------------------------------------------------ final rows */

    @Test @TestTransaction
    void aFinalClosingRefusesEveryWriteAndKeepsEveryRow() {
        quiet();
        long rose = product("Roos");
        long box = simpleContainer("C1", rose, 100, "2.00", LocalDate.of(YEAR1, 11, 20));
        SalesOrder invoice = invoice(rose, 5, LocalDate.of(YEAR1, 12, 20));
        countAll(YEAR1);
        long id = closings.create(YEAR1, null).closing().id;
        closings.saveDecision(id, vat());
        View concept = closings.saveDecision(id, invoiced(List.of(invoice.id()), "UIT", null));
        long decisionId = concept.decisions().getFirst().id;
        markFinal(id);
        Map<String, List<String>> before = stored(id);
        assertEquals(1, before.get("StockClosingEntity").size());
        assertFalse(before.get("StockClosingLayerEntity").isEmpty());
        assertFalse(before.get("StockClosingMovementEntity").isEmpty());

        /* The live data moves on. */
        pay(box, LocalDate.of(YEAR2, 1, 5), new BigDecimal("50"), Currency.EUR, Payee.SUPPLIER, false, null);
        stock.sell(rose, stock.mainLocation().id(), 10, "F-LATER");

        String refused = "Deze afsluiting is definitief. Maak een nieuwe versie om iets te corrigeren";
        List<InventoryRefusal> refusals = List.of(
                assertThrows(InventoryRefusal.class, () -> closings.recompute(id)),
                assertThrows(InventoryRefusal.class, () -> closings.saveDecision(id, writeDown(null, rose, 1, "0.50", "MARKT", "x"))),
                assertThrows(InventoryRefusal.class, () -> closings.deleteDecision(id, decisionId)),
                assertThrows(InventoryRefusal.class, () -> closings.setClosingDate(id, END1.minusDays(1))),
                assertThrows(InventoryRefusal.class, () -> closings.requireConcept(id)));
        for (InventoryRefusal refusal : refusals) {
            assertEquals("DEFINITIEF", refusal.code());
            assertEquals(refused, refusal.getMessage());
        }
        assertEquals("DEFINITIEF", assertThrows(InventoryRefusal.class, () -> closings.delete(id)).code());
        assertEquals(before, stored(id), "every stored row is unchanged, field by field");
        assertEquals("Afsluiting 987654321 bestaat niet", assertThrows(NotFoundException.class,
                () -> closings.requireConcept(987_654_321L)).getMessage());

        View frozen = closings.view(id);
        assertEquals("DEFINITIEF", frozen.closing().status);
        assertMoney("174.80", frozen.closing().totalValueEur, "95 x 1,84: the figures of the day it was frozen");
        assertEquals(1, frozen.versions().size());
        assertNull(frozen.previousReplacedBy());
        assertEquals(before, stored(id), "reading changes nothing");
    }

    /* ---------------------------------------------------------- corrections */

    @Test @TestTransaction
    void aCorrectionShowsWhatMovedSinceTheVersionItReplacesAndALaterYearThatWasBuiltOnIt() {
        quiet();
        long rose = product("Roos");
        long box = simpleContainer("C1", rose, 100, "2.00", LocalDate.of(YEAR1, 11, 20));
        countAll(YEAR1);
        long first = closings.create(YEAR1, null).closing().id;
        closings.saveDecision(first, vat());
        markFinal(first);
        countAll(YEAR2);
        long later = closings.create(YEAR2, null).closing().id;
        markFinal(later);

        /* Version 2 of year 1, as "nieuwe versie" starts it: a concept that names the version it replaces. */
        long second = version(first, "Betaling vergeten");
        closings.compute(second);
        View unchanged = closings.view(second);
        assertEquals(first, unchanged.closing().supersedesId);
        assertNull(notice(unchanged, "VERSCHIL_MET_VORIGE_VERSIE"), "rebuilt from the same data: nothing differs");
        assertTrue(unchanged.versionChanges().isEmpty());
        assertEquals(first, unchanged.versionChanges().againstClosingId());
        assertEquals(1, unchanged.versionChanges().againstVersionNo());
        assertNotice(unchanged, "LATER_JAAR_AFGESLOTEN", "WARNING", "afsluiten", "Boekjaar " + YEAR2 + " is afgesloten op versie 1"
                + " van dit boekjaar. Maak daarna ook van " + YEAR2 + " een nieuwe versie.");
        assertEquals(List.of(2, 1), unchanged.versions().stream().map(version -> version.versionNo).toList());
        assertFalse(closings.view(first).canCorrect(), "a concept for the year is open");
        assertEquals("BESTAAT_AL", assertThrows(InventoryRefusal.class, () -> closings.create(YEAR1, null)).code());

        /* A payment that was missing: the lot and the product move, and the list says so. */
        pay(box, LocalDate.of(YEAR1, 12, 1), new BigDecimal("16"), Currency.EUR, Payee.SUPPLIER, false, null);
        View moved = closings.recompute(second);
        assertNotice(moved, "VERSCHIL_MET_VORIGE_VERSIE", "WARNING", "afsluiten", "Tegenover versie 1: 1 product, 1 partij,"
                + " 0 bewegingen en 0 beginwaarden anders. Totaal € 184,00 → € 200,00.");
        ClosingVersionDiff.Changes changes = moved.versionChanges();
        assertMoney("184.00", changes.totalBeforeEur());
        assertMoney("200.00", changes.totalAfterEur());
        assertEquals(rose, changes.articles().getFirst().productId());
        assertMoney("184.00", changes.articles().getFirst().costValueBeforeEur());
        assertMoney("200.00", changes.articles().getFirst().costValueAfterEur());
        assertEquals(100, changes.articles().getFirst().quantityBefore());
        assertEquals(box, changes.lots().getFirst().purchaseOrderId());
        assertEquals("C1", changes.lots().getFirst().displayName());
        assertEquals(0, new BigDecimal("1.84").compareTo(changes.lots().getFirst().unitValueBeforeEur()));
        assertEquals(0, new BigDecimal("2.00").compareTo(changes.lots().getFirst().unitValueAfterEur()));
        assertEquals(1, resource.get(second).versionChanges().lots().size());

        /* Once version 2 is the valid one, the later year reads that the version it was built on is replaced. */
        markFinal(second);
        closingRows.findById(first).supersededById = second;
        closingRows.flush();
        View builtOnOld = closings.view(later);
        assertEquals(first, builtOnOld.previous().id, "a final closing keeps the id it was frozen with");
        assertEquals(second, builtOnOld.previousReplacedBy().id);
        assertEquals(2, builtOnOld.previousReplacedBy().versionNo);
        assertEquals(second, resource.get(later).previousClosingReplacedBy().id());
        assertTrue(resource.get(first).superseded());
        assertFalse(closings.view(first).canCorrect());
        assertTrue(closings.view(second).canCorrect());

        /* A new version of the later year resolves its previous closing afresh. */
        long laterSecond = version(later, "Steunt op de nieuwe versie van " + YEAR1);
        closings.compute(laterSecond);
        View rebuilt = closings.view(laterSecond);
        assertEquals(second, rebuilt.previous().id);
        assertNull(rebuilt.previousReplacedBy());
        assertNotice(rebuilt, "VORIGE_VERSIE_VERVANGEN", "WARNING", "afsluiten",
                "Deze versie steunt op versie 2 van " + YEAR1 + "; de vorige versie steunde op versie 1.");
        assertEquals(new BigDecimal("2.0000"), layers(rebuilt, rose).getFirst().unitValueEur, "the layer version 2 froze");
        assertNotNull(notice(rebuilt, "VERSCHIL_MET_VORIGE_VERSIE"));
    }

    @Test
    void aPaymentWithoutItsEuroValueBlocks() {
        /* The payment table refuses such a row today, so the container is built by hand. */
        BigDecimal zero = new BigDecimal("0.00");
        LotCost.Container cost = new LotCost.Container(7L, LotCost.QuantityBasis.ONTVANGEN, LotCost.BilledBasis.BESTELD, END1,
                false, true, false, "CBM", "CBM", "CBM", "SEPARATE", List.of(), zero, zero, zero, zero, zero, zero, zero, zero, zero,
                zero, zero, false, false, true, List.of(), List.of(), List.of(), List.of());
        ClosingNotices notices = new ClosingNotices();
        notices.container(7L, "PO-7", cost, false, Map.of());
        assertEquals(1, notices.list().size());
        Notice raised = notices.list().getFirst();
        assertEquals("BETALING_ZONDER_EURO", raised.code());
        assertEquals("BLOCKER", raised.severity());
        assertEquals("waarde", raised.segment());
        assertEquals("Container PO-7: een betaling mist haar eurowaarde.", raised.message());
        assertEquals(7L, raised.purchaseOrderId());
        assertEquals(List.of(raised), ClosingNotices.fromJson(ClosingNotices.toJson(notices.list())), "stored and read back");
    }

    @Test
    void theVersionComparisonListsWhatDiffersBetweenTwoRowSetsAndNothingElse() {
        ClosingVersionDiff.Rows old = rows(1, 1, "500.00");
        ClosingVersionDiff.Rows same = rows(2, 2, "500.00");
        ClosingVersionDiff.Changes none = ClosingVersionDiff.between(same, old);
        assertTrue(none.isEmpty());
        assertEquals(1, none.againstClosingId());
        assertEquals(1, none.againstVersionNo());

        ClosingVersionDiff.Rows now = rows(2, 2, "470.00");
        now.articles().getFirst().closingQuantity = 95;
        now.articles().getFirst().costValueEur = new BigDecimal("190.00");
        now.articles().getFirst().writeDownEur = new BigDecimal("12.00");
        now.lots().getFirst().unitValueEur = new BigDecimal("2.1000");
        now.lots().get(1).unitValueEur = new BigDecimal("9.9999");
        now.movements().getFirst().applied = false;
        now.movements().get(1).removed = true;
        now.movements().remove(2);
        now.layers().getFirst().quantity = 30;
        now.layers().remove(1);
        StockClosingLayerEntity added = openingLayer(73, 12, 5, "1.1000");
        now.layers().add(added);

        ClosingVersionDiff.Changes changes = ClosingVersionDiff.between(now, old);
        assertFalse(changes.isEmpty());
        assertMoney("500.00", changes.totalBeforeEur());
        assertMoney("470.00", changes.totalAfterEur());
        assertEquals(1, changes.articles().size(), "the tulips did not move");
        var article = changes.articles().getFirst();
        assertEquals(11, article.productId());
        assertEquals("Roos", article.productName());
        assertEquals(100, article.quantityBefore());
        assertEquals(95, article.quantityAfter());
        assertMoney("200.00", article.costValueBeforeEur());
        assertMoney("190.00", article.costValueAfterEur());
        assertMoney("0.00", article.writeDownBeforeEur());
        assertMoney("12.00", article.writeDownAfterEur());
        assertEquals(1, changes.lots().size(), "the lot recomputed for an earlier year is not compared");
        assertEquals(5, changes.lots().getFirst().purchaseOrderId());
        assertEquals("PO-5", changes.lots().getFirst().displayName());
        assertEquals(new BigDecimal("2.0000"), changes.lots().getFirst().unitValueBeforeEur());
        assertEquals(new BigDecimal("2.1000"), changes.lots().getFirst().unitValueAfterEur());
        assertEquals(List.of("901:-4:0", "902:-6:null", "903:0:null"), changes.movements().stream()
                .map(row -> row.movementId() + ":" + row.effectBefore() + ":" + row.effectAfter()).toList(),
                "no longer applied, removed, and no longer listed");
        assertEquals(List.of("71:40:30", "72:10:null", "73:null:5"), changes.openingLayers().stream()
                .map(row -> row.openingLayerId() + ":" + row.quantityBefore() + ":" + row.quantityAfter()).toList());
        assertEquals("inventaris", changes.openingLayers().getFirst().source());
        assertEquals("Roos", changes.openingLayers().getFirst().productName());
        assertEquals(new BigDecimal("1.1000"), changes.openingLayers().get(2).unitValueAfterEur());
    }

    /* ---------------------------------------------------------------- helpers */

    /**
     * Empties the book for this test: no container, level, movement, count, closing or opening value
     * of another test is left to value. Rolled back with the test.
     */
    private void quiet() {
        for (String entity : List.of("StockClosingLineEntity", "StockClosingLayerEntity", "StockClosingLotEntity",
                "StockClosingContainerEntity", "StockClosingArticleEntity", "StockClosingSeparateEntity",
                "StockClosingWriteDownEntity", "StockClosingMovementEntity", "StockClosingDecisionEntity",
                "StockClosingEntity", "StockOpeningLayerEntity", "StockValuationRuleEntity", "StockMovementEntity")) {
            em.createQuery("delete from " + entity).executeUpdate();
        }
        em.createQuery("update " + PurchaseOrderEntity.class.getName() + " set status = ?1").setParameter(1, PurchaseOrderStatus.CONCEPT).executeUpdate();
        em.createQuery("update " + SalesOrderEntity.class.getName() + " set status = ?1").setParameter(1, QuoteStatus.CONCEPT).executeUpdate();
        em.createQuery("update StockLevelEntity set quantity = 0").executeUpdate();
        em.createQuery("update StockCountEntity set status = 'GEANNULEERD'").executeUpdate();
        em.createQuery("update ProductEntity set active = false").executeUpdate();
        em.clear();
        supplier = suppliers.save(new Supplier(null, "Closing Co", "CN", "Yiwu", null, null, null, Currency.USD,
                "FOB Ningbo", "Ningbo", 30, null));
    }

    /**
     * Finalize is not built here: this sets what "Definitief maken" sets on the row, after a compute.
     * It runs inside the test's own rolled-back transaction, which is the only one that sees the row.
     */
    private void markFinal(long closingId) {
        closings.compute(closingId);
        StockClosingEntity closing = closingRows.findById(closingId);
        closing.status = "DEFINITIEF";
        closing.finalizedAt = Instant.now();
        closing.finalizedBy = "emre";
        closing.finalizedByName = "emre";
        closingRows.flush();
    }

    private static Write vat() {
        return new Write(null, "VAT_CONFIRMATION", null, null, null, null, null, null, null, null, true, null, null, null,
                null, null, null, null);
    }

    private static Write decision(String kind, Consumer<Map<String, Object>> fields) {
        Map<String, Object> given = new LinkedHashMap<>();
        fields.accept(given);
        @SuppressWarnings("unchecked") List<Long> invoices = (List<Long>) given.get("salesOrderIds");
        return new Write((Long) given.get("id"), kind, (Long) given.get("purchaseOrderId"), (Long) given.get("salesOrderId"),
                invoices, (Long) given.get("productId"), (Long) given.get("movementId"), (Long) given.get("creditId"),
                (String) given.get("payee"), (String) given.get("choice"), (Boolean) given.get("flag"),
                (Integer) given.get("quantity"), (BigDecimal) given.get("unitValueEur"), (BigDecimal) given.get("amountEur"),
                (LocalDate) given.get("decisionDate"), (String) given.get("reasonCode"), (String) given.get("reason"),
                (String) given.get("counterparty"));
    }

    private static Write treatment(long purchaseOrderId, long creditId, String choice, String reason) {
        return decision("CREDIT_TREATMENT", w -> { w.put("purchaseOrderId", purchaseOrderId); w.put("creditId", creditId);
            w.put("choice", choice); w.put("reason", reason); });
    }

    private static Write movement(long movementId, boolean applied, String reason) {
        return decision("MOVEMENT", w -> { w.put("movementId", movementId); w.put("flag", applied); w.put("reason", reason); });
    }

    private static Write transit(long purchaseOrderId, boolean included, LocalDate ownedFrom, String reason) {
        return decision("TRANSIT", w -> { w.put("purchaseOrderId", purchaseOrderId); w.put("flag", included);
            w.put("decisionDate", ownedFrom); w.put("reason", reason); });
    }

    private static Write ownership(long purchaseOrderId, LocalDate ownedFrom, String reason) {
        return decision("OWNERSHIP_DATE", w -> { w.put("purchaseOrderId", purchaseOrderId); w.put("decisionDate", ownedFrom);
            w.put("reason", reason); });
    }

    private static Write thirdParty(Long id, long productId, int quantity, String owner, String reason) {
        return decision("THIRD_PARTY", w -> { w.put("id", id); w.put("productId", productId); w.put("quantity", quantity);
            w.put("counterparty", owner); w.put("reason", reason); });
    }

    private static Write invoiced(List<Long> salesOrderIds, String choice, String reason) {
        return decision("INVOICED", w -> { w.put("salesOrderIds", salesOrderIds); w.put("choice", choice); w.put("reason", reason); });
    }

    private static Write writeDown(Long id, long productId, Integer quantity, String marketUnit, String reasonCode, String note) {
        return decision("WRITE_DOWN", w -> { w.put("id", id); w.put("productId", productId); w.put("quantity", quantity);
            w.put("unitValueEur", new BigDecimal(marketUnit)); w.put("reasonCode", reasonCode); w.put("reason", note); });
    }

    private static StockOpeningLayerService.Write opening(LocalDate asOf, String source, long productId, int quantity, String unit) {
        return new StockOpeningLayerService.Write(asOf, source, List.of(new StockOpeningLayerService.Row(productId, quantity,
                unit == null ? null : new BigDecimal(unit), null)));
    }

    /** The container as the closing screen reads it. */
    private StockClosingDtos.ClosingContainer shown(long closingId, long purchaseOrderId) {
        return resource.get(closingId).containers().stream().filter(row -> row.purchaseOrderId() == purchaseOrderId)
                .findFirst().orElseThrow();
    }

    /**
     * A new version of a final closing as "nieuwe versie" starts it: a concept that names the version
     * it replaces, with a copy of its decisions in their order. Starting one is not built here.
     */
    private long version(long replacedId, String reason) {
        StockClosingEntity replaced = closingRows.findById(replacedId);
        StockClosingEntity version = new StockClosingEntity();
        version.closingYear = replaced.closingYear;
        version.versionNo = replaced.versionNo + 1;
        version.closingDate = replaced.closingDate;
        version.cutoffAt = replaced.cutoffAt;
        version.status = "CONCEPT";
        version.supersedesId = replaced.id;
        version.correctionReason = reason;
        version.createdBy = "emre";
        version.createdByName = "emre";
        version.createdAt = Instant.now();
        closingRows.persist(version);
        closingRows.flush();
        for (StockClosingDecisionEntity decision : decisionRows.list("closingId = ?1 order by id", replacedId)) {
            StockClosingDecisionEntity copy = new StockClosingDecisionEntity();
            copy.closingId = version.id;
            copy.kind = decision.kind;
            copy.purchaseOrderId = decision.purchaseOrderId;
            copy.salesOrderId = decision.salesOrderId;
            copy.productId = decision.productId;
            copy.movementId = decision.movementId;
            copy.creditId = decision.creditId;
            copy.payee = decision.payee;
            copy.choice = decision.choice;
            copy.flag = decision.flag;
            copy.quantity = decision.quantity;
            copy.unitValueEur = decision.unitValueEur;
            copy.amountEur = decision.amountEur;
            copy.basisAmountEur = decision.basisAmountEur;
            copy.decisionDate = decision.decisionDate;
            copy.reasonCode = decision.reasonCode;
            copy.reason = decision.reason;
            copy.counterparty = decision.counterparty;
            copy.decidedBy = decision.decidedBy;
            copy.decidedByName = decision.decidedByName;
            copy.decidedAt = decision.decidedAt;
            decisionRows.persist(copy);
        }
        decisionRows.flush();
        return version.id;
    }

    /** The stored rows of a version, built by hand: two products, a lot, a compared lot, three movements, two opening values. */
    private static ClosingVersionDiff.Rows rows(long closingId, int versionNo, String total) {
        StockClosingEntity closing = new StockClosingEntity();
        closing.id = closingId;
        closing.versionNo = versionNo;
        closing.totalValueEur = new BigDecimal(total);
        List<StockClosingArticleEntity> articles = new ArrayList<>();
        articles.add(articleRow(11, "Roos", 100, "200.00"));
        articles.add(articleRow(12, "Tulp", 50, "300.00"));
        List<StockClosingLotEntity> lots = new ArrayList<>();
        lots.add(lotRow(5, "EIGEN", "2.0000"));
        lots.add(lotRow(4, "VORIG", "1.5000"));
        StockClosingContainerEntity container = new StockClosingContainerEntity();
        container.purchaseOrderId = 5L;
        container.displayName = "PO-5";
        List<StockClosingMovementEntity> movements = new ArrayList<>();
        movements.add(movementRow(901, true, -4));
        movements.add(movementRow(902, true, -6));
        movements.add(movementRow(903, false, 3));
        List<StockClosingLayerEntity> layers = new ArrayList<>();
        layers.add(openingLayer(71, 11, 40, "1.0000"));
        layers.add(openingLayer(72, 12, 10, "1.2000"));
        return new ClosingVersionDiff.Rows(closing, articles, lots, new ArrayList<>(List.of(container)), movements, layers);
    }

    private static StockClosingArticleEntity articleRow(long productId, String name, int quantity, String cost) {
        StockClosingArticleEntity article = new StockClosingArticleEntity();
        article.productId = productId;
        article.productName = name;
        article.closingQuantity = quantity;
        article.costValueEur = new BigDecimal(cost);
        article.writeDownEur = new BigDecimal("0.00");
        return article;
    }

    private static StockClosingLotEntity lotRow(long purchaseOrderId, String role, String unit) {
        StockClosingLotEntity lot = new StockClosingLotEntity();
        lot.purchaseOrderId = purchaseOrderId;
        lot.role = role;
        lot.productId = 11L;
        lot.productName = "Roos";
        lot.unitValueEur = new BigDecimal(unit);
        return lot;
    }

    private static StockClosingMovementEntity movementRow(long movementId, boolean applied, int effectiveDelta) {
        StockClosingMovementEntity row = new StockClosingMovementEntity();
        row.movementId = movementId;
        row.productName = "Roos";
        row.locationName = "Magazijn";
        row.refText = "F-" + movementId;
        row.applied = applied;
        row.effectiveDelta = effectiveDelta;
        row.removed = false;
        return row;
    }

    private static StockClosingLayerEntity openingLayer(long openingLayerId, long productId, int quantity, String unit) {
        StockClosingLayerEntity layer = new StockClosingLayerEntity();
        layer.productId = productId;
        layer.block = "EIGEN";
        layer.source = "BEGINWAARDE";
        layer.openingLayerId = openingLayerId;
        layer.openingSource = "inventaris";
        layer.quantity = quantity;
        layer.unitValueEur = new BigDecimal(unit);
        return layer;
    }

    private static void assertShare(BigDecimal amount, BigDecimal key, BigDecimal keys, BigDecimal share) {
        BigDecimal exact = amount.multiply(key).divide(keys, 6, java.math.RoundingMode.HALF_UP);
        assertTrue(exact.subtract(share).abs().compareTo(new BigDecimal("0.01")) <= 0,
                share + " should be " + amount + " x " + key + " / " + keys + " = " + exact + " within a cent");
    }

    private static void assertMoney(String expected, BigDecimal actual, String message) {
        assertNotNull(actual, message);
        assertEquals(0, new BigDecimal(expected).compareTo(actual), message + ": expected " + expected + " but was " + actual);
    }

    private long product(String name) {
        var product = new ProductEntity();
        product.sku = "CLOSE-" + UUID.randomUUID();
        product.name = name;
        product.colour = "rood";
        product.active = true;
        product.supplierId = supplier.id();
        product.piecesPerCarton = 10;
        product.productLengthCm = product.productWidthCm = product.productHeightCm = BigDecimal.ONE;
        product.cartonLengthCm = product.cartonWidthCm = product.cartonHeightCm = BigDecimal.TEN;
        product.cartonWeightKg = BigDecimal.ONE;
        em.persist(product);
        em.flush();
        return product.id;
    }

    private StockLocation location(String name) {
        return stock.saveLocation(new StockLocation(null, null, name + " " + UUID.randomUUID().toString().substring(0, 8),
                StockLocation.Kind.SALES_POINT, null, true, false, false, 9));
    }

    private void level(long productId, StockLocation location, int quantity) {
        stock.setLevel(productId, location.id(), quantity, StockMovement.Kind.MANUAL_CORRECTION, "test");
    }

    /** One ordered line of a container under construction. */
    private record Ordered(long productId, int ordered, String priceUsd, int received, int damaged) {}

    /**
     * A container at USD/EUR 0,92. With a receipt day it is received and booked into the warehouse
     * today and then dated; without one it stays on the water, shipped on the given day.
     */
    private long container(String name, LocalDate receivedOn, LocalDate shippedOn, List<Ordered> lines,
                           Consumer<PurchaseOrderEntity> costs) {
        PurchaseOrder created = purchases.create(supplier.id(), new BigDecimal("0.14"), new BigDecimal("0.92"), BigDecimal.ZERO);
        purchases.update(created.id(), created.withReceipt(PurchaseOrderStatus.BESTELD, null, null, false, null,
                lines.stream().map(line -> new PurchaseOrderLine(null, line.productId(), line.ordered(),
                        new BigDecimal(line.priceUsd()), Currency.USD, null, null)).toList()));
        PurchaseOrderEntity stored = em.find(PurchaseOrderEntity.class, created.id());
        stored.alias = name;
        if (costs != null) costs.accept(stored);
        em.flush(); em.clear();
        if (receivedOn != null) {
            purchases.receive(created.id(), new PurchaseOrderService.Receipt(lines.stream()
                    .map(line -> new PurchaseOrderService.ReceivedLine(line.productId(), line.received(), line.damaged())).toList(),
                    bookOnReceipt, null, LocalDate.now(), null));
        }
        stored = em.find(PurchaseOrderEntity.class, created.id());
        stored.orderDate = (receivedOn != null ? receivedOn : shippedOn).minusDays(60);
        stored.receivedOn = receivedOn;
        stored.shippedOn = shippedOn;
        if (receivedOn == null) stored.status = PurchaseOrderStatus.ONDERWEG;
        em.flush(); em.clear();
        return created.id();
    }

    /** A container with one product, received in full and paid in euro: the unit value is its price at 0,92. */
    private long simpleContainer(String name, long productId, int quantity, String priceUsd, LocalDate receivedOn) {
        long id = container(name, receivedOn, receivedOn.minusDays(40), List.of(new Ordered(productId, quantity, priceUsd, quantity, 0)), null);
        pay(id, receivedOn.minusDays(30), new BigDecimal(priceUsd).multiply(new BigDecimal("0.92")).multiply(BigDecimal.valueOf(quantity)),
                Currency.EUR, Payee.SUPPLIER, false, null);
        return id;
    }

    private PurchasePayment pay(long orderId, LocalDate paidOn, BigDecimal amount, Currency currency, Payee payee,
                                boolean settles, String bankEur) {
        PurchasePayment payment = purchases.addPayment(orderId, paidOn, amount, currency, payee.name().toLowerCase(), payee, settles, null,
                bankEur == null ? null : new BigDecimal(bankEur));
        em.flush(); em.clear();
        return payment;
    }

    private PurchaseSupplierCredit credit(long orderId, String amountUsd, Reason reason) {
        PurchaseSupplierCredit credit = credits.add(orderId, new CreditRequest(LocalDate.now(), new BigDecimal(amountUsd),
                Currency.USD, null, reason, null));
        em.flush(); em.clear();
        return credit;
    }

    /** Books a full count of every location that holds stock, each product at the level of the book; by location id. */
    private Map<Long, Long> countAll(int year) {
        Map<Long, Long> booked = new LinkedHashMap<>();
        Map<Long, Boolean> holds = new LinkedHashMap<>();
        for (StockLevel level : stock.allLevels()) {
            if (level.quantity() != 0) holds.put(level.location().id(), true);
        }
        for (Long locationId : holds.keySet()) booked.put(locationId, count(year, locationId, Map.of()));
        return booked;
    }

    /** A booked full count of one location; a product not named is counted at the level of the book. */
    private long count(int year, long locationId, Map<Long, Integer> counted) {
        var session = counts.start(year, locationId, null, null);
        long id = session.summary().count().id;
        for (var line : session.lines()) {
            Integer quantity = counted.get(line.row().productId);
            if (quantity == null && line.liveQuantity() == 0) continue;
            counts.saveLine(id, line.row().id, new LineWrite(quantity == null ? line.liveQuantity() : quantity, "TELFOUT", null,
                    line.row().revision, null, null));
        }
        counts.book(id, counts.bookingCheck(id).checkToken());
        em.flush();
        return id;
    }

    /** An issued Belgian invoice for one product that is not afgepunt. */
    private SalesOrder invoice(long productId, int quantity, LocalDate orderDate) {
        var customer = customers.create(new Customer(null, "Klant " + UUID.randomUUID(), "Buyer", "closing@example.invalid", null,
                "BE0000000000", "BE", Language.NL, "Main 1", "2000", "Antwerpen", "DAP", null, null, LocalDate.now()));
        var created = sales.create(customer.id(), "BE", "DAP", DocumentType.FACTUUR);
        var stored = em.find(SalesOrderEntity.class, created.id());
        stored.freightPricingStrategy = FreightPricingStrategy.FIXED;
        stored.manualFreightEur = new BigDecimal("120.00");
        stored.freight = FreightState.AANGEVULD;
        stored.status = QuoteStatus.UITGEREIKT;
        stored.orderDate = orderDate;
        var line = new SalesOrderLineEntity();
        line.order = stored; line.productId = productId; line.quantity = quantity;
        line.unitPriceEur = new BigDecimal("8.45"); line.unitCostEur = new BigDecimal("5.10");
        stored.lines.add(line);
        em.flush(); em.clear();
        return sales.get(created.id());
    }

    private static StockClosingArticleEntity article(View view, long productId) {
        return view.articles().stream().filter(article -> article.productId == productId).findFirst().orElse(null);
    }

    private static List<StockClosingLayerEntity> layers(View view, long productId) {
        return view.layers().stream().filter(layer -> layer.productId == productId).toList();
    }

    private static StockClosingLineEntity line(View view, long productId, long locationId) {
        return view.lines().stream().filter(line -> line.productId == productId && line.locationId == locationId)
                .findFirst().orElse(null);
    }

    private static StockClosingContainerEntity container(View view, long purchaseOrderId) {
        return view.containers().stream().filter(container -> container.purchaseOrderId == purchaseOrderId).findFirst().orElse(null);
    }

    private static StockClosingLotEntity lot(View view, long purchaseOrderId, long productId) {
        return view.lots().stream().filter(lot -> lot.purchaseOrderId == purchaseOrderId && lot.productId == productId)
                .findFirst().orElse(null);
    }

    private static LocationView location(View view, long locationId) {
        return view.locations().stream().filter(location -> location.locationId() == locationId).findFirst().orElse(null);
    }

    private static List<StockClosingSeparateEntity> separate(View view, String kind) {
        return view.separates().stream().filter(row -> kind.equals(row.kind)).toList();
    }

    private static Notice notice(View view, String code) {
        return view.notices().stream().filter(notice -> code.equals(notice.code())).findFirst().orElse(null);
    }

    private static List<Notice> notices(View view, String code) {
        return view.notices().stream().filter(notice -> code.equals(notice.code())).toList();
    }

    private static List<String> blockers(View view) {
        return view.notices().stream().filter(Notice::blocker).map(Notice::code).distinct().toList();
    }

    private static List<String> warnings(View view) {
        return view.notices().stream().filter(notice -> !notice.blocker()).map(Notice::code).distinct().toList();
    }

    private static void assertMoney(String expected, BigDecimal actual) {
        assertNotNull(actual, "expected " + expected);
        assertEquals(0, new BigDecimal(expected).compareTo(actual), "expected " + expected + " but was " + actual);
    }

    private static void assertNotice(View view, String code, String severity, String segment, String message) {
        Notice found = view.notices().stream().filter(notice -> code.equals(notice.code()) && message.equals(notice.message()))
                .findFirst().orElse(null);
        assertNotNull(found, code + " with this text; raised: " + notices(view, code).stream().map(Notice::message).toList());
        assertEquals(severity, found.severity(), code);
        assertEquals(segment, found.segment(), code);
    }

    /** Every stored row of a closing, field by field as read back from the database. */
    private Map<String, List<String>> stored(long closingId) {
        em.flush(); em.clear();
        Map<String, List<String>> rows = new LinkedHashMap<>();
        for (Class<?> entity : List.of(StockClosingEntity.class, StockClosingLineEntity.class, StockClosingLayerEntity.class,
                StockClosingLotEntity.class, StockClosingContainerEntity.class, StockClosingArticleEntity.class,
                StockClosingSeparateEntity.class, StockClosingWriteDownEntity.class, StockClosingMovementEntity.class,
                StockClosingDecisionEntity.class)) {
            String key = entity == StockClosingEntity.class ? "id" : "closingId";
            List<String> lines = new ArrayList<>();
            for (Object row : em.createQuery("from " + entity.getSimpleName() + " where " + key + " = ?1 order by id", entity)
                    .setParameter(1, closingId).getResultList()) {
                StringBuilder line = new StringBuilder();
                for (Field field : entity.getDeclaredFields()) {
                    if (field.getAnnotation(Column.class) == null) continue;
                    try {
                        field.setAccessible(true);
                        Object value = field.get(row);
                        line.append(field.getName()).append('=').append(value instanceof BigDecimal number
                                ? number.stripTrailingZeros().toPlainString() : Objects.toString(value)).append(';');
                    } catch (IllegalAccessException impossible) {
                        throw new IllegalStateException(impossible);
                    }
                }
                lines.add(line.toString());
            }
            rows.put(entity.getSimpleName(), lines);
        }
        return rows;
    }
}
