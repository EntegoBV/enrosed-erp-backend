package be.enrosed.sourcing.application;

import be.enrosed.catalog.application.ProductService;
import be.enrosed.catalog.domain.Barcodes;
import be.enrosed.catalog.domain.Carton;
import be.enrosed.catalog.domain.Dimensions;
import be.enrosed.catalog.domain.Product;
import be.enrosed.shared.BusinessRuleException;
import be.enrosed.shared.Currency;
import be.enrosed.shared.audit.ActivityChangeDto;
import be.enrosed.shared.audit.ActivityLogService;
import be.enrosed.shared.security.ActorRef;
import be.enrosed.shared.security.CurrentActor;
import be.enrosed.sourcing.application.port.out.SourcingRepositories;
import be.enrosed.sourcing.domain.Allocation;
import be.enrosed.sourcing.domain.ContainerType;
import be.enrosed.sourcing.domain.LandedCost;
import be.enrosed.sourcing.domain.OtherCost;
import be.enrosed.sourcing.domain.PurchaseOrder;
import be.enrosed.sourcing.domain.PurchaseOrderLine;
import be.enrosed.sourcing.domain.PurchaseOrderStatus;
import be.enrosed.sourcing.domain.Supplier;
import org.junit.jupiter.api.Test;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.inject.Instance;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PurchaseOrderServiceTest {

    @Test
    void lifecycleOnlyMovesForwardIncludingLegacyUnderwayPath() {
        assertDoesNotThrow(() -> PurchaseOrderService.requireForwardTransition(
                PurchaseOrderStatus.CONCEPT, PurchaseOrderStatus.BESTELD));
        assertDoesNotThrow(() -> PurchaseOrderService.requireForwardTransition(
                PurchaseOrderStatus.CONCEPT, PurchaseOrderStatus.ONDERWEG));
        assertDoesNotThrow(() -> PurchaseOrderService.requireForwardTransition(
                PurchaseOrderStatus.BESTELD, PurchaseOrderStatus.ONTVANGEN));
        assertDoesNotThrow(() -> PurchaseOrderService.requireForwardTransition(
                PurchaseOrderStatus.ONDERWEG, PurchaseOrderStatus.ONTVANGEN));

        assertThrows(BusinessRuleException.class, () -> PurchaseOrderService.requireForwardTransition(
                PurchaseOrderStatus.CONCEPT, PurchaseOrderStatus.ONTVANGEN));
        assertThrows(BusinessRuleException.class, () -> PurchaseOrderService.requireForwardTransition(
                PurchaseOrderStatus.BESTELD, PurchaseOrderStatus.CONCEPT));
        assertThrows(BusinessRuleException.class, () -> PurchaseOrderService.requireForwardTransition(
                PurchaseOrderStatus.ONTVANGEN, PurchaseOrderStatus.ONDERWEG));
    }

    @Test
    void receivingCountsTheContainerAndBooksTheUsablePiecesOnce() {
        InMemoryOrders orders = new InMemoryOrders(order(PurchaseOrderStatus.BESTELD, 6, 6));
        RecordingProducts products = new RecordingProducts();
        PurchaseOrderService service = service(orders, products);

        /* Five arrived of the six ordered, one of them broken; book at once. */
        PurchaseOrder received = service.receive(10L, new PurchaseOrderService.Receipt(
                List.of(new PurchaseOrderService.ReceivedLine(1L, 5, 1)), true,
                new java.math.BigDecimal("1234.56"), java.time.LocalDate.of(2026, 8, 23), "Doos 3 was nat."));

        assertEquals(PurchaseOrderStatus.ONTVANGEN, received.status());
        assertEquals(4, products.stockDelta, "arrived minus broken goes on the shelf");
        assertEquals(5, received.lines().getFirst().quantity());
        assertEquals(6, received.lines().getFirst().orderedQuantity(), "the order remembers what was ordered");
        assertEquals(1, received.lines().getFirst().damaged());
        assertEquals(new BigDecimal("3.6000"), received.lines().getFirst().receiptUnitValueEur());
        assertEquals(new BigDecimal("3.60"), received.lines().getFirst().missingValueEur());
        assertEquals(new BigDecimal("3.60"), received.lines().getFirst().damagedValueEur());
        assertEquals(new BigDecimal("7.20"), received.lines().getFirst().totalLossValueEur());
        assertEquals(new java.math.BigDecimal("1234.56"), received.paidTotalEur());
        assertTrue(received.isStockBooked());
        assertTrue(received.notes().contains("Ontvangst 23/08/2026:"), received.notes());
        assertTrue(received.notes().contains("besteld 6, ontvangen 5, 1 beschadigd"), received.notes());
        assertTrue(received.notes().contains("Doos 3 was nat."), received.notes());

        assertThrows(BusinessRuleException.class, () -> service.bookStock(10L), "never twice");
        assertEquals(4, products.stockDelta);
    }

    @Test
    void receivingWithoutBookingLeavesStockForLater() {
        InMemoryOrders orders = new InMemoryOrders(order(PurchaseOrderStatus.ONDERWEG, 6, 6));
        RecordingProducts products = new RecordingProducts();
        PurchaseOrderService service = service(orders, products);

        PurchaseOrder received = service.receive(10L, new PurchaseOrderService.Receipt(
                List.of(), false, null, null, null));
        assertEquals(0, products.stockDelta, "received, not booked");
        assertFalse(received.isStockBooked());
        assertTrue(received.notes().contains("alles volgens bestelling"), received.notes());
        PurchaseOrderService.ReceiptVarianceTotals summary = service.receiptVarianceSummary(received);
        assertEquals(0, summary.affectedLines());
        assertEquals(6, summary.orderedPieces());
        assertEquals(6, summary.receivedPieces());
        assertEquals(6, summary.usablePieces(),
                "a perfect receipt still needs meaningful quantities on its order card");

        PurchaseOrder booked = service.bookStock(10L);
        assertEquals(6, products.stockDelta);
        assertTrue(booked.isStockBooked());
    }

    @Test
    void explicitReceiptValueDrivesHistoricalMetricsAndCanBeCorrectedOrCleared() {
        InMemoryOrders orders = new InMemoryOrders(order(PurchaseOrderStatus.BESTELD, 6, 6));
        PurchaseOrderService service = service(orders, new RecordingProducts());

        PurchaseOrder received = service.receive(10L, new PurchaseOrderService.Receipt(
                List.of(new PurchaseOrderService.ReceivedLine(
                        1L, 4, 1, new BigDecimal("7.25"))), false,
                null, LocalDate.of(2026, 8, 23), null));

        assertEquals(new BigDecimal("7.2500"), received.lines().getFirst().receiptUnitValueEur());
        PurchaseOrderService.ReceiptVarianceReport report = service.receiptVariances(
                null, null, null, null, null);
        assertEquals(1, report.rows().size());
        PurchaseOrderService.ReceiptVarianceRow row = report.rows().getFirst();
        assertEquals(6, row.orderedPieces());
        assertEquals(4, row.receivedPieces());
        assertEquals(2, row.missingPieces());
        assertEquals(1, row.damagedPieces());
        assertEquals(3, row.usablePieces());
        assertEquals(new BigDecimal("14.50"), row.missingValueEur());
        assertEquals(new BigDecimal("7.25"), row.damagedValueEur());
        assertEquals(new BigDecimal("21.75"), row.totalLossValueEur());
        assertEquals("Leverancier", row.supplierName());
        assertEquals(1, report.totals().affectedOrders());
        assertEquals(1, report.totals().affectedLines());
        assertEquals(0, report.totals().unvaluedLossPieces(),
                "all three lost pieces are valued");
        assertTrue(report.totals().valuationComplete());
        assertTrue(service.receiptVariances(LocalDate.of(2026, 8, 24), null,
                null, null, null).rows().isEmpty());
        assertTrue(service.receiptVariances(null, null, null, 999L, null).rows().isEmpty());

        service.setReceiptUnitValue(10L, 100L, new BigDecimal("8"));
        assertEquals(new BigDecimal("24.00"), service.receiptVariances(
                null, null, null, null, null).totals().totalLossValueEur());

        service.setReceiptUnitValue(10L, 100L, null);
        PurchaseOrderService.ReceiptVarianceReport unvalued = service.receiptVariances(
                null, null, null, null, null);
        assertEquals(3, unvalued.totals().unvaluedLossPieces());
        assertFalse(unvalued.totals().valuationComplete());
        assertEquals(new BigDecimal("0.00"), unvalued.totals().totalLossValueEur());
        assertThrows(BusinessRuleException.class,
                () -> service.setReceiptUnitValue(10L, 100L, new BigDecimal("-0.01")));
    }

    @Test
    void receiptRejectsDuplicateAndUnknownProductCounts() {
        InMemoryOrders orders = new InMemoryOrders(order(PurchaseOrderStatus.BESTELD, 6, 6));
        PurchaseOrderService service = service(orders, new RecordingProducts());

        assertThrows(BusinessRuleException.class, () -> service.receive(10L,
                new PurchaseOrderService.Receipt(List.of(
                        new PurchaseOrderService.ReceivedLine(1L, 5, 0),
                        new PurchaseOrderService.ReceivedLine(1L, 4, 0)),
                        false, null, null, null)));
        assertThrows(BusinessRuleException.class, () -> service.receive(10L,
                new PurchaseOrderService.Receipt(List.of(
                        new PurchaseOrderService.ReceivedLine(999L, 1, 0)),
                        false, null, null, null)));
    }

    @Test
    void receiptSnapshotUsesCostingBeforeReceivedCountsReplaceOrderedCounts() {
        InMemoryOrders orders = new InMemoryOrders(order(PurchaseOrderStatus.BESTELD, 6, 6));
        LandedCostCalculator calculator = mock(LandedCostCalculator.class);
        LandedCost costing = costing(1L, new BigDecimal("24.00"));
        when(calculator.calculate(any(PurchaseOrder.class), anyMap())).thenAnswer(invocation -> {
            PurchaseOrder beforeReceipt = invocation.getArgument(0);
            assertEquals(6, beforeReceipt.lines().getFirst().quantity(),
                    "costing must run while the ordered count is still present");
            return costing;
        });
        PurchaseOrderService service = new PurchaseOrderService(
                orders, new FixedSuppliers(true), new RecordingProducts(), calculator);

        PurchaseOrder received = service.receive(10L, new PurchaseOrderService.Receipt(
                List.of(new PurchaseOrderService.ReceivedLine(1L, 3, 0)),
                false, null, null, null));

        assertEquals(new BigDecimal("4.0000"), received.lines().getFirst().receiptUnitValueEur(),
                "24 euro ordered goods / 6 ordered pieces is the frozen unit basis");
    }

    @Test
    void missingExchangeRateLeavesReceiptValueUnknownInsteadOfInventingZero() {
        PurchaseOrder historical = withRates(
                order(PurchaseOrderStatus.BESTELD, 6, 6),
                (BigDecimal) null, (BigDecimal) null);
        InMemoryOrders orders = new InMemoryOrders(historical);
        LandedCostCalculator calculator = mock(LandedCostCalculator.class);
        LandedCost costing = costing(1L, BigDecimal.ZERO);
        when(calculator.calculate(any(PurchaseOrder.class), anyMap())).thenReturn(costing);
        PurchaseOrderService service = new PurchaseOrderService(
                orders, new FixedSuppliers(true), new RecordingProducts(), calculator);

        PurchaseOrder received = service.receive(10L, new PurchaseOrderService.Receipt(
                List.of(new PurchaseOrderService.ReceivedLine(1L, 5, 0)),
                false, null, null, null));

        assertNull(received.lines().getFirst().receiptUnitValueEur());
        assertEquals(1, service.receiptVariances(null, null, null, null, null)
                .totals().unvaluedLossPieces());
    }

    @Test
    void fullOrderUpdatesCannotOverwriteReceiptValueSnapshot() {
        InMemoryOrders orders = new InMemoryOrders(order(PurchaseOrderStatus.BESTELD, 6, 6));
        PurchaseOrderService service = service(orders, new RecordingProducts());
        PurchaseOrder received = service.receive(10L, new PurchaseOrderService.Receipt(
                List.of(new PurchaseOrderService.ReceivedLine(1L, 5, 1)),
                false, null, LocalDate.of(2026, 8, 23), null));
        PurchaseOrderLine line = received.lines().getFirst();
        PurchaseOrder malicious = received.withReceipt(received.status(), received.receivedOn(),
                received.paidTotalEur(), received.stockBooked(), received.notes(),
                List.of(new PurchaseOrderLine(line.id(), line.productId(), line.quantity(),
                        new BigDecimal("99"), line.exwCurrency(), line.extraUnitCost(),
                        line.orderedQuantity(), line.priceBasis(), line.damagedQuantity(),
                        new BigDecimal("999"))));

        PurchaseOrder saved = service.update(10L, malicious).order();

        assertEquals(new BigDecimal("3.6000"), saved.lines().getFirst().receiptUnitValueEur());
        assertEquals(new BigDecimal("99"), saved.lines().getFirst().exwPrice(),
                "commercial edits remain independent from the frozen receipt value");
    }

    @Test
    void cartonWarningOnlySpeaksWhenTheCountItselfChanged() {
        /* 7 pieces in boxes of 6 was accepted weeks ago: editing a rate or a
           note must not repeat the warning; a new count earns it again. */
        InMemoryOrders orders = new InMemoryOrders(order(PurchaseOrderStatus.BESTELD, 7, 7));
        PurchaseOrderService service = service(orders, new RecordingProducts());

        assertTrue(service.update(10L, order(PurchaseOrderStatus.BESTELD, 7, 7)).adjustments().isEmpty(),
                "same count, no warning");
        var warned = service.update(10L, order(PurchaseOrderStatus.BESTELD, 8, 7)).adjustments();
        assertEquals(1, warned.size(), "a fresh count that fills no full carton warns");
        assertEquals(12, warned.getFirst().adjusted());
    }

    @Test
    void storedOrderedQuantityWinsOverClientValueAfterPlacement() {
        InMemoryOrders orders = new InMemoryOrders(order(PurchaseOrderStatus.BESTELD, 6, 6));
        PurchaseOrderService service = service(orders, new RecordingProducts());

        PurchaseOrder saved = service.update(10L,
                order(PurchaseOrderStatus.BESTELD, 9, 999)).order();

        assertEquals(9, saved.lines().getFirst().quantity());
        assertEquals(6, saved.lines().getFirst().orderedQuantity());
    }

    @Test
    void receivedCountsMayBeCorrectedButTheProductSetAndTheOrderStay() {
        InMemoryOrders orders = new InMemoryOrders(order(PurchaseOrderStatus.ONTVANGEN, 6, 6));
        PurchaseOrderService service = service(orders, new RecordingProducts());

        /* A recount is a correction, not a forbidden edit; the snapshot stays. */
        PurchaseOrder corrected = service.update(10L, order(PurchaseOrderStatus.ONTVANGEN, 7, 6)).order();
        assertEquals(7, corrected.lines().getFirst().quantity());
        assertEquals(6, corrected.lines().getFirst().orderedQuantity());

        /* A line without its stored id reads as another product: refused. */
        PurchaseOrder swapped = order(PurchaseOrderStatus.ONTVANGEN, 7, 6);
        PurchaseOrderLine line = swapped.lines().getFirst();
        PurchaseOrder otherLine = swapped.withReceipt(swapped.status(), swapped.receivedOn(),
                swapped.paidTotalEur(), swapped.stockBooked(), swapped.notes(),
                List.of(new PurchaseOrderLine(null, line.productId(), line.quantity(), line.exwPrice(),
                        line.exwCurrency(), line.extraUnitCost(), line.orderedQuantity())));
        assertThrows(BusinessRuleException.class, () -> service.update(10L, otherLine));
        assertThrows(BusinessRuleException.class, () -> service.delete(10L));
        assertFalse(orders.deleted);
    }

    @Test
    void invalidReferencesAndNegativeCommercialValuesAreBusinessRules() {
        InMemoryOrders orders = new InMemoryOrders(order(PurchaseOrderStatus.CONCEPT, 6, null));
        PurchaseOrderService missingSupplier = new PurchaseOrderService(
                orders, new FixedSuppliers(false), new RecordingProducts(), null);

        assertThrows(BusinessRuleException.class, () -> missingSupplier.update(
                10L, order(PurchaseOrderStatus.CONCEPT, 6, null)));

        PurchaseOrder base = order(PurchaseOrderStatus.CONCEPT, 6, null);
        PurchaseOrder negativeFreight = new PurchaseOrder(
                base.id(), base.number(), base.alias(), base.supplierId(), base.orderDate(), base.status(),
                base.containerType(), base.cnyToUsd(), base.usdToEurGoods(), base.usdToEurTransport(),
                new BigDecimal("-1"), base.originCosts(), base.originCurrency(),
                base.destinationCostsEur(), base.defaultDutyRatePct(), base.extraRevenueEur(),
                base.allocFreight(), base.allocOrigin(), base.allocDestination(), base.allocExtra(),
                base.departurePort(), base.destinationPort(), base.notes(), base.lines());
        assertThrows(BusinessRuleException.class,
                () -> service(orders, new RecordingProducts()).update(10L, negativeFreight));
    }

    @Test
    void otherCostsKeepTheirTrimmedNamesWhileAnEmptyRowIsDroppedAndANamelessAmountIsRefused() {
        InMemoryOrders orders = new InMemoryOrders(order(PurchaseOrderStatus.CONCEPT, 6, null));
        PurchaseOrder base = order(PurchaseOrderStatus.CONCEPT, 6, null);
        PurchaseOrder booked = base.withInspectionCost(new BigDecimal("250")).withOtherCosts(List.of(
                new OtherCost("  Certificaat ", new BigDecimal("120")),
                /* The row the plus button adds, never filled in. */
                new OtherCost("", null)));

        PurchaseOrder saved = service(orders, new RecordingProducts()).update(10L, booked).order();

        assertEquals(new BigDecimal("250"), saved.inspectionCostEur());
        assertEquals(List.of(new OtherCost("Certificaat", new BigDecimal("120"))), saved.otherCosts());
        assertTrue(saved.hasSeparateCosts());

        PurchaseOrder nameless = base.withOtherCosts(List.of(new OtherCost(" ", new BigDecimal("50"))));
        assertThrows(BusinessRuleException.class,
                () -> service(orders, new RecordingProducts()).update(10L, nameless));
        PurchaseOrder negative = base.withOtherCosts(List.of(new OtherCost("Labo", new BigDecimal("-1"))));
        assertThrows(BusinessRuleException.class,
                () -> service(orders, new RecordingProducts()).update(10L, negative));
    }

    @Test
    void createUsesOneUsdRateAndNewPurchaseDefaults() {
        InMemoryOrders orders = new InMemoryOrders(null);
        PurchaseOrder created = service(orders, new RecordingProducts()).create(
                7L, new BigDecimal("0.14"), new BigDecimal("0.91"), new BigDecimal("5"));

        assertEquals(ContainerType.FORTY_HQ, created.containerType(),
                "older clients that omit the choice keep the former default");
        assertEquals(new BigDecimal("0.91"), created.usdToEurGoods());
        assertEquals(created.usdToEurGoods(), created.usdToEurTransport());
        assertEquals(new BigDecimal("2000"), created.extraRevenueEur());
        assertEquals("Ningbo", created.departurePort());
        assertEquals("Rotterdam", created.destinationPort());
        assertEquals(ActorRef.SYSTEM, created.createdBy());
        assertNotNull(created.createdAt());
    }

    @Test
    void createStoresEveryChosenFullContainerAndRejectsLcl() {
        for (ContainerType type : List.of(
                ContainerType.TWENTY_GP, ContainerType.FORTY_GP, ContainerType.FORTY_HQ)) {
            PurchaseOrder created = service(new InMemoryOrders(null), new RecordingProducts()).create(
                    7L, new BigDecimal("0.14"), new BigDecimal("0.91"),
                    new BigDecimal("5"), type);
            assertEquals(type, created.containerType());
        }

        PurchaseOrder legacyClient = service(new InMemoryOrders(null), new RecordingProducts()).create(
                7L, new BigDecimal("0.14"), new BigDecimal("0.91"), new BigDecimal("5"), null);
        assertEquals(ContainerType.FORTY_HQ, legacyClient.containerType());

        assertThrows(BusinessRuleException.class, () ->
                service(new InMemoryOrders(null), new RecordingProducts()).create(
                        7L, new BigDecimal("0.14"), new BigDecimal("0.91"),
                        new BigDecimal("5"), ContainerType.LCL));
    }

    @Test
    @SuppressWarnings("unchecked")
    void authenticatedCreatorIsImmutableAndDrivesAuditAndAfterCommitPush() {
        InMemoryOrders orders = new InMemoryOrders(null);
        PurchaseOrderService service = service(orders, new RecordingProducts());
        ActorRef emre = new ActorRef("emre", "Emre");

        Instance<CurrentActor> actors = mock(Instance.class);
        CurrentActor currentActor = mock(CurrentActor.class);
        when(actors.isResolvable()).thenReturn(true);
        when(actors.get()).thenReturn(currentActor);
        when(currentActor.current()).thenReturn(emre);
        service.actor = actors;

        Instance<ActivityLogService> activities = mock(Instance.class);
        ActivityLogService activityLog = mock(ActivityLogService.class);
        when(activities.isResolvable()).thenReturn(true);
        when(activities.get()).thenReturn(activityLog);
        service.activity = activities;
        service.purchasePush = mock(Event.class);

        PurchaseOrder created = service.create(
                7L, new BigDecimal("0.14"), new BigDecimal("0.91"), new BigDecimal("5"));

        assertEquals(emre, created.createdBy());
        assertNotNull(created.createdAt());
        verify(activityLog).record(ActivityLogService.ACTION_CREATED,
                ActivityLogService.ENTITY_PURCHASE_ORDER, "10", created.number(), "Inkooporder aangemaakt");
        verify(service.purchasePush).fire(argThat(ready -> ready.kind() == PurchasePushNotifier.Kind.CREATED
                && emre.equals(ready.actor()) && created.number().equals(ready.number())));

        ActorRef berat = new ActorRef("berat", "Berat");
        when(currentActor.current()).thenReturn(berat);
        PurchaseOrder changed = created.withReceipt(created.status(), created.receivedOn(),
                created.paidTotalEur(), created.stockBooked(), "Nieuwe interne notitie", created.lines());

        PurchaseOrder updated = service.update(10L, changed).order();

        assertEquals(emre, updated.createdBy(), "an editor must never replace the original creator");
        verify(activityLog).record(ActivityLogService.ACTION_UPDATED,
                ActivityLogService.ENTITY_PURCHASE_ORDER, "10", updated.number(),
                "Inkooporder bijgewerkt",
                List.of(new ActivityChangeDto("notes", "Notities", null, null)));
    }

    @Test
    void placingOrderSnapshotsQuantityAndUnifiesLegacyRates() {
        PurchaseOrder concept = withRates(
                order(PurchaseOrderStatus.CONCEPT, 6, null), "0.82", "0.94");
        InMemoryOrders orders = new InMemoryOrders(concept);

        PurchaseOrder placed = service(orders, new RecordingProducts())
                .update(10L, withStatus(concept, PurchaseOrderStatus.BESTELD)).order();

        assertEquals(PurchaseOrderStatus.BESTELD, placed.status());
        assertEquals(6, placed.lines().getFirst().orderedQuantity());
        assertEquals(new BigDecimal("0.82"), placed.usdToEurGoods());
        assertEquals(placed.usdToEurGoods(), placed.usdToEurTransport());
        assertEquals("Shanghai", placed.departurePort());
    }

    @Test
    void placingOrderSnapshotsTheCompleteProductPurchasePricePair() {
        PurchaseOrder concept = withLinePrice(
                order(PurchaseOrderStatus.CONCEPT, 6, null), null, null);
        InMemoryOrders orders = new InMemoryOrders(concept);
        RecordingProducts products = new RecordingProducts(
                product(7L, new BigDecimal("12.34"), Currency.CNY));
        PurchaseOrderService service = service(orders, products);

        PurchaseOrder placed = service.update(
                10L, withStatus(concept, PurchaseOrderStatus.BESTELD)).order();

        assertEquals(new BigDecimal("12.34"), placed.lines().getFirst().exwPrice());
        assertEquals(Currency.CNY, placed.lines().getFirst().exwCurrency());

        products.current = product(7L, new BigDecimal("99.99"), Currency.EUR);
        PurchaseOrder savedAgain = service.update(10L, placed).order();
        assertEquals(new BigDecimal("12.34"), savedAgain.lines().getFirst().exwPrice(),
                "a later product-master change must not alter the placed agreement");
        assertEquals(Currency.CNY, savedAgain.lines().getFirst().exwCurrency());
    }

    @Test
    void placingOrderKeepsAnExplicitLinePriceInsteadOfTheProductMaster() {
        PurchaseOrder concept = withLinePrice(
                order(PurchaseOrderStatus.CONCEPT, 6, null),
                new BigDecimal("8.75"), Currency.EUR);
        RecordingProducts products = new RecordingProducts(
                product(7L, new BigDecimal("12.34"), Currency.CNY));

        PurchaseOrder placed = service(new InMemoryOrders(concept), products)
                .update(10L, withStatus(concept, PurchaseOrderStatus.BESTELD)).order();

        assertEquals(new BigDecimal("8.75"), placed.lines().getFirst().exwPrice());
        assertEquals(Currency.EUR, placed.lines().getFirst().exwCurrency());
    }

    @Test
    void purchasePriceAmountAndCurrencyMustBeProvidedTogether() {
        PurchaseOrder amountOnly = withLinePrice(
                order(PurchaseOrderStatus.CONCEPT, 6, null),
                new BigDecimal("8.75"), null);
        PurchaseOrder currencyOnly = withLinePrice(
                order(PurchaseOrderStatus.CONCEPT, 6, null), null, Currency.EUR);

        BusinessRuleException missingCurrency = assertThrows(BusinessRuleException.class,
                () -> service(new InMemoryOrders(amountOnly), new RecordingProducts())
                        .update(10L, amountOnly));
        assertTrue(missingCurrency.getMessage().contains("zowel de inkoopprijs als de valuta"));

        BusinessRuleException missingAmount = assertThrows(BusinessRuleException.class,
                () -> service(new InMemoryOrders(currencyOnly), new RecordingProducts())
                        .update(10L, currencyOnly));
        assertTrue(missingAmount.getMessage().contains("zowel de inkoopprijs als de valuta"));
    }

    @Test
    void orderRejectsAProductOwnedByAnotherSupplier() {
        PurchaseOrder concept = order(PurchaseOrderStatus.CONCEPT, 6, null);
        RecordingProducts products = new RecordingProducts(
                product(8L, new BigDecimal("4"), Currency.USD));

        BusinessRuleException exception = assertThrows(BusinessRuleException.class,
                () -> service(new InMemoryOrders(concept), products).update(10L, concept));

        assertTrue(exception.getMessage().contains("hoort niet bij de gekozen leverancier"));
    }

    @Test
    void historicalSupplierReassignmentAllowsHeaderEditButNotANewLine() {
        PurchaseOrder historical = order(PurchaseOrderStatus.BESTELD, 6, 6);
        InMemoryOrders orders = new InMemoryOrders(historical);
        RecordingProducts products = new RecordingProducts(
                product(8L, new BigDecimal("4"), Currency.USD));
        PurchaseOrderService service = service(orders, products);

        PurchaseOrder updated = service.update(
                10L, withTrackingReference(historical, "MSCU-HISTORICAL-123")).order();

        assertEquals("MSCU-HISTORICAL-123", updated.trackingReference(),
                "a later product reassignment must not block an unrelated header edit");

        PurchaseOrderLine stored = updated.lines().getFirst();
        PurchaseOrderLine newLine = new PurchaseOrderLine(
                null, stored.productId(), stored.quantity(), stored.exwPrice(),
                stored.exwCurrency(), stored.extraUnitCost(), stored.orderedQuantity(),
                stored.priceBasis(), stored.damagedQuantity(), stored.receiptUnitValueEur());
        PurchaseOrder replacedByNewLine = updated.withReceipt(
                updated.status(), updated.receivedOn(), updated.paidTotalEur(),
                updated.stockBooked(), updated.notes(), List.of(newLine));

        BusinessRuleException exception = assertThrows(BusinessRuleException.class,
                () -> service.update(10L, replacedByNewLine));
        assertTrue(exception.getMessage().contains("hoort niet bij de gekozen leverancier"));
    }

    @Test
    void duplicateOfHistoricalOrderBecomesSingleRateDraft() {
        PurchaseOrder historical = withRates(
                order(PurchaseOrderStatus.BESTELD, 6, 6), "0.80", "0.93");
        PurchaseOrderLine historicalLine = historical.lines().getFirst();
        historical = historical.withReceipt(PurchaseOrderStatus.ONTVANGEN,
                LocalDate.of(2026, 8, 23), null, true, historical.notes(),
                List.of(new PurchaseOrderLine(historicalLine.id(), historicalLine.productId(), 5,
                        historicalLine.exwPrice(), historicalLine.exwCurrency(),
                        historicalLine.extraUnitCost(), historicalLine.orderedQuantity(),
                        historicalLine.priceBasis(), 1, new BigDecimal("3.2000"))));
        InMemoryOrders orders = new InMemoryOrders(historical);

        PurchaseOrder copy = service(orders, new RecordingProducts()).duplicate(10L);

        assertEquals(PurchaseOrderStatus.CONCEPT, copy.status());
        assertEquals(new BigDecimal("0.80"), copy.usdToEurGoods());
        assertEquals(copy.usdToEurGoods(), copy.usdToEurTransport());
        assertEquals(historical.departurePort(), copy.departurePort());
        assertNull(copy.lines().getFirst().receiptUnitValueEur(),
                "a duplicate is a fresh draft, never another copy of historical receipt loss");
    }

    private static PurchaseOrderService service(InMemoryOrders orders, RecordingProducts products) {
        return new PurchaseOrderService(orders, new FixedSuppliers(true), products, null);
    }

    private static PurchaseOrder order(PurchaseOrderStatus status, int quantity,
                                       Integer orderedQuantity) {
        return new PurchaseOrder(10L, "PO-TEST", null, 7L, LocalDate.now(), status,
                ContainerType.FORTY_HQ, new BigDecimal("0.14"), new BigDecimal("0.90"),
                new BigDecimal("0.90"), BigDecimal.ZERO, BigDecimal.ZERO, Currency.USD,
                BigDecimal.ZERO, new BigDecimal("5"), BigDecimal.ZERO,
                Allocation.CBM, Allocation.CBM, Allocation.CBM, Allocation.PIECES,
                "Shanghai", "Rotterdam", null,
                List.of(new PurchaseOrderLine(100L, 1L, quantity,
                        new BigDecimal("4"), Currency.USD, BigDecimal.ZERO, orderedQuantity)));
    }

    private static PurchaseOrder withRates(PurchaseOrder source, String goods, String transport) {
        return withRates(source, new BigDecimal(goods), new BigDecimal(transport));
    }

    private static PurchaseOrder withRates(PurchaseOrder source, BigDecimal goods, BigDecimal transport) {
        return new PurchaseOrder(source.id(), source.number(), source.alias(), source.supplierId(),
                source.orderDate(), source.status(), source.containerType(), source.cnyToUsd(),
                goods, transport, source.freightUsd(),
                source.originCosts(), source.originCurrency(), source.destinationCostsEur(),
                source.defaultDutyRatePct(), source.extraRevenueEur(), source.allocFreight(),
                source.allocOrigin(), source.allocDestination(), source.allocExtra(),
                source.departurePort(), source.destinationPort(), source.notes(), source.lines());
    }

    private static LandedCost costing(long productId, BigDecimal goodsEur) {
        LandedCost.Line line = new LandedCost.Line(
                productId, "Testproduct", 0, 0, null,
                null, goodsEur, null, null, null,
                null, null, null, null, null,
                null, null, null, null, null);
        return new LandedCost(List.of(line), null, null);
    }

    private static PurchaseOrder withStatus(PurchaseOrder source, PurchaseOrderStatus status) {
        return new PurchaseOrder(source.id(), source.number(), source.alias(), source.supplierId(),
                source.orderDate(), status, source.containerType(), source.cnyToUsd(),
                source.usdToEurGoods(), source.usdToEurTransport(), source.freightUsd(),
                source.originCosts(), source.originCurrency(), source.destinationCostsEur(),
                source.defaultDutyRatePct(), source.extraRevenueEur(), source.allocFreight(),
                source.allocOrigin(), source.allocDestination(), source.allocExtra(),
                source.departurePort(), source.destinationPort(), source.notes(), source.lines());
    }

    private static PurchaseOrder withLinePrice(PurchaseOrder source, BigDecimal amount,
                                               Currency currency) {
        PurchaseOrderLine line = source.lines().getFirst();
        PurchaseOrderLine changed = new PurchaseOrderLine(
                line.id(), line.productId(), line.quantity(), amount, currency,
                line.extraUnitCost(), line.orderedQuantity(), line.priceBasis(),
                line.damagedQuantity(), line.receiptUnitValueEur());
        return source.withReceipt(source.status(), source.receivedOn(), source.paidTotalEur(),
                source.stockBooked(), source.notes(), List.of(changed));
    }

    private static PurchaseOrder withTrackingReference(PurchaseOrder source, String tracking) {
        return new PurchaseOrder(
                source.id(), source.number(), source.alias(), source.supplierId(), source.orderDate(),
                source.status(), source.containerType(), source.cnyToUsd(), source.usdToEurGoods(),
                source.usdToEurTransport(), source.freightUsd(), source.originCosts(),
                source.originCurrency(), source.destinationCostsEur(), source.defaultDutyRatePct(),
                source.extraRevenueEur(), source.allocFreight(), source.allocOrigin(),
                source.allocDestination(), source.allocExtra(), source.departurePort(),
                source.destinationPort(), source.receivingLocationId(), source.groupVariants(),
                source.expectedArrival(), source.receivedOn(), source.paidTotalEur(),
                source.stockBooked(), source.paymentTerms(), source.shippedOn(), tracking,
                source.createdBy(), source.createdAt(), source.notes(), source.lines());
    }

    private static Product product() {
        return product(7L, new BigDecimal("4"), Currency.USD);
    }

    private static Product product(Long supplierId, BigDecimal price, Currency currency) {
        return new Product(1L, "SKU-1", "Testproduct", Dimensions.empty(), null, null,
                1L, supplierId, true, Barcodes.none(), null,
                new Carton(Dimensions.empty(), 6, BigDecimal.ONE),
                price, currency, BigDecimal.ZERO,
                null, null, BigDecimal.ZERO, null, 0, List.of(), List.of());
    }

    private static final class RecordingProducts extends ProductService {
        private int stockDelta;
        private Product current;

        private RecordingProducts() {
            this(product());
        }

        private RecordingProducts(Product current) {
            super(null, null, null);
            this.current = current;
        }

        @Override
        public List<Product> list() {
            return List.of(current);
        }

        @Override
        public void receiveStock(long productId, int delta, String reference, Long locationId) {
            assertEquals(1L, productId);
            assertTrue(reference != null && !reference.isBlank(),
                    "a receipt books stock under its purchase order number");
            stockDelta += delta;
        }
    }

    private static final class FixedSuppliers implements SourcingRepositories.Suppliers {
        private final boolean exists;

        private FixedSuppliers(boolean exists) {
            this.exists = exists;
        }

        @Override
        public List<Supplier> findAll() {
            return exists ? List.of(supplier()) : List.of();
        }

        @Override
        public Optional<Supplier> findById(long id) {
            return exists && id == 7L ? Optional.of(supplier()) : Optional.empty();
        }

        @Override
        public Supplier save(Supplier supplier) {
            return supplier;
        }

        @Override
        public void deleteById(long id) {}

        private static Supplier supplier() {
            return new Supplier(7L, "Leverancier", "CN", null, null, null, null,
                    Currency.USD, "FOB", "Shanghai", 30, null);
        }
    }

    private static final class InMemoryOrders implements SourcingRepositories.PurchaseOrders {
        private PurchaseOrder current;
        private boolean deleted;

        private InMemoryOrders(PurchaseOrder current) {
            this.current = current;
        }

        @Override
        public List<PurchaseOrder> findAll() {
            return current == null ? List.of() : List.of(current);
        }

        @Override
        public Optional<PurchaseOrder> findById(long id) {
            return current != null && current.id() == id ? Optional.of(current) : Optional.empty();
        }

        @Override
        public PurchaseOrder save(PurchaseOrder order) {
            current = order.id() == null ? withId(order, 10L) : order;
            return current;
        }

        @Override
        public void deleteById(long id) {
            deleted = true;
            current = null;
        }

        private static PurchaseOrder withId(PurchaseOrder order, long id) {
            return new PurchaseOrder(id, order.number(), order.alias(), order.supplierId(), order.orderDate(),
                    order.status(), order.containerType(), order.cnyToUsd(), order.usdToEurGoods(),
                    order.usdToEurTransport(), order.freightUsd(), order.originCosts(), order.originCurrency(),
                    order.destinationCostsEur(), order.defaultDutyRatePct(), order.extraRevenueEur(),
                    order.allocFreight(), order.allocOrigin(), order.allocDestination(), order.allocExtra(),
                    order.departurePort(), order.destinationPort(), order.receivingLocationId(),
                    order.groupVariants(), order.expectedArrival(), order.receivedOn(), order.paidTotalEur(),
                    order.stockBooked(), order.paymentTerms(), order.shippedOn(), order.trackingReference(),
                    order.createdBy(), order.createdAt(), order.notes(), order.lines());
        }
    }
}
