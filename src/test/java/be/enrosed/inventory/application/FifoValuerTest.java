package be.enrosed.inventory.application;

import be.enrosed.inventory.adapter.out.persistence.StockClosingArticleEntity;
import be.enrosed.inventory.adapter.out.persistence.StockClosingLayerEntity;
import be.enrosed.inventory.adapter.out.persistence.StockClosingSeparateEntity;
import be.enrosed.inventory.adapter.out.persistence.StockClosingWriteDownEntity;
import be.enrosed.inventory.application.FifoValuer.Article;
import be.enrosed.inventory.application.FifoValuer.Decided;
import be.enrosed.inventory.application.FifoValuer.Invoice;
import be.enrosed.inventory.application.FifoValuer.LocationQuantity;
import be.enrosed.inventory.application.FifoValuer.LocationValue;
import be.enrosed.inventory.application.FifoValuer.Lot;
import be.enrosed.inventory.application.FifoValuer.Opening;
import be.enrosed.inventory.application.FifoValuer.PartnerLot;
import be.enrosed.inventory.application.FifoValuer.Result;
import be.enrosed.inventory.application.FifoValuer.ThirdParty;
import be.enrosed.inventory.application.FifoValuer.WriteDown;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FIFO per receipt lot on hand-built layers: the closing quantity is filled
 * from the newest lot down, and what leaves the own stock leaves from the
 * oldest layer.
 */
class FifoValuerTest {

    private static final LocalDate CLOSING = LocalDate.of(2026, 12, 31);
    private static final LocalDate PREVIOUS = LocalDate.of(2025, 12, 31);
    private static final long ROSE = 11, TULIP = 12, WAREHOUSE = 1, TICA = 2;
    private static final Instant DECIDED = Instant.parse("2027-01-10T09:00:00Z");

    private final Map<Long, List<LocationQuantity>> quantities = new LinkedHashMap<>();
    private final List<ThirdParty> thirdParties = new ArrayList<>();
    private final List<PartnerLot> partnerLots = new ArrayList<>();
    private final List<Lot> lots = new ArrayList<>();
    private final List<StockClosingLayerEntity> carried = new ArrayList<>();
    private final List<Opening> openings = new ArrayList<>();
    private final Set<Long> consumed = new HashSet<>();
    private final List<Invoice> invoices = new ArrayList<>();
    private final List<WriteDown> writeDowns = new ArrayList<>();
    private final Map<Long, BigDecimal> previousWriteDowns = new HashMap<>();
    private LocalDate previousClosingDate;

    /* --------------------------------------------------- worked example 3.8 */

    @Test
    void workedExampleOneProductThreeOwnContainers() {
        exampleOfSection38();
        invoices.add(new Invoice(118, "F-2026-118", LocalDate.of(2026, 12, 20), "Bloemen Peeters", Map.of(ROSE, 30),
                decision(7, FifoValuer.CHOICE_OUT, null), Set.of()));
        writeDowns.add(new WriteDown(9, ROSE, 12, new BigDecimal("0.50"), "BESCHADIGD", "Stolpen gebarsten", "Emre", DECIDED));

        Result result = value();

        List<StockClosingLayerEntity> layers = result.layers(ROSE);
        assertEquals(4, layers.size());
        assertLayer(layers.get(0), "EIGEN", 1, 14L, 930, 930, "2.5449", "2366.76");
        assertLayer(layers.get(1), "EIGEN", 2, 9L, 300, 300, "2.4100", "723.00");
        assertLayer(layers.get(2), "EIGEN", 3, 3L, 500, 40, "2.3000", "92.00");
        assertLayer(layers.get(3), "GEFACTUREERD", 3, 3L, null, 30, "2.3000", "69.00");
        assertEquals(118L, layers.get(3).salesOrderId);
        assertEquals("PARTIJ", layers.get(0).source);
        assertEquals("PARTIJ", layers.get(0).originSource);
        assertEquals(new BigDecimal("188.33"), layers.get(0).estimatedEur, "930 x 0,2025");

        StockClosingArticleEntity article = result.article(ROSE);
        assertEquals(1260, article.countedQuantity);
        assertEquals(40, article.rollDelta);
        assertEquals(1300, article.closingQuantity);
        assertEquals(0, article.thirdPartyQuantity);
        assertEquals(0, article.partnerQuantity);
        assertEquals(30, article.invoicedOutQuantity);
        assertEquals(1270, article.ownQuantity);
        assertEquals(0, article.unvaluedQuantity);
        assertEquals(new BigDecimal("3181.76"), article.costValueEur);
        assertEquals(new BigDecimal("2.5053"), article.averageUnitEur);
        assertEquals(new BigDecimal("188.33"), article.estimatedEur);
        assertEquals(new BigDecimal("24.54"), article.writeDownEur);
        assertEquals(new BigDecimal("3157.22"), article.ownValueEur);
        assertEquals("OK", article.status);
        /* 930 x 0,6526 and 930 x 0,0923; the goods carry the rounding of their layer. */
        assertEquals(new BigDecimal("606.92"), article.logisticsEur);
        assertEquals(new BigDecimal("85.84"), article.separateEur);
        assertEquals(new BigDecimal("0.00"), article.transportEur);
        assertEquals(new BigDecimal("2489.00"), article.goodsEur);
        assertEquals(article.costValueEur, article.goodsEur.add(article.transportEur).add(article.logisticsEur)
                .add(article.separateEur).add(article.openingEur));

        assertEquals(1, result.writeDowns().size());
        StockClosingWriteDownEntity lowered = result.writeDowns().getFirst();
        assertEquals(1, lowered.layerPosition, "the dearest layer first");
        assertEquals(12, lowered.quantity);
        assertEquals(new BigDecimal("2.5449"), lowered.layerUnitEur);
        assertEquals(new BigDecimal("0.5000"), lowered.marketUnitEur);
        assertEquals(new BigDecimal("24.54"), lowered.amountEur);
        assertEquals("PO-2026-014 · ontvangen 20/11/2026", lowered.layerLabel);
        assertEquals(12, layers.get(0).writeDownQuantity);
        assertEquals(new BigDecimal("2366.76"), layers.get(0).valueEur, "the acquisition value stays on the row");

        LocationValue warehouse = location(result, ROSE, WAREHOUSE);
        LocationValue tica = location(result, ROSE, TICA);
        assertEquals(new BigDecimal("3059.38"), warehouse.costValueEur());
        assertEquals(new BigDecimal("122.38"), tica.costValueEur());
        assertEquals(article.costValueEur, warehouse.costValueEur().add(tica.costValueEur()));
        assertEquals(article.writeDownEur, warehouse.writeDownEur().add(tica.writeDownEur()));
        assertEquals(article.estimatedEur, warehouse.estimatedEur().add(tica.estimatedEur()));
        assertEquals(article.goodsEur, warehouse.goodsEur().add(tica.goodsEur()));
        assertEquals(article.logisticsEur, warehouse.logisticsEur().add(tica.logisticsEur()));
        assertEquals(article.separateEur, warehouse.separateEur().add(tica.separateEur()));
        assertEquals(article.ownValueEur, warehouse.ownValueEur().add(tica.ownValueEur()));

        Map<Long, Integer> held = new LinkedHashMap<>();
        held.put(WAREHOUSE, 1250);
        held.put(TICA, 50);
        assertEquals(Map.of(WAREHOUSE, 1221, TICA, 49), FifoValuer.ownQuantityPerLocation(1270, held));

        StockClosingSeparateEntity invoiced = separate(result, FifoValuer.KIND_INVOICED);
        assertEquals("F-2026-118", invoiced.documentNumber);
        assertEquals(30, invoiced.quantity);
        assertEquals("UIT", invoiced.choice);
        assertEquals(new BigDecimal("69.00"), invoiced.valueEur, "shown apart, not in the total");
        assertEquals(new BigDecimal("2.3000"), invoiced.unitValueEur);
        assertFalse(invoiced.automatic);
        assertEquals(7L, invoiced.decisionId);
    }

    @Test
    void noPieceLeavesTwice() {
        /* The sale behind the invoice was already taken out by the movements of step 2: nothing more is carved. */
        exampleOfSection38();
        quantities.put(ROSE, List.of(new LocationQuantity(WAREHOUSE, 1210, 0, 1210), new LocationQuantity(TICA, 50, 0, 50)));
        invoices.add(new Invoice(118, "F-2026-118", LocalDate.of(2026, 12, 20), "Bloemen Peeters", Map.of(ROSE, 40),
                decision(7, FifoValuer.CHOICE_OUT, null), Set.of(ROSE)));
        Result automatic = value();
        StockClosingSeparateEntity row = separate(automatic, FifoValuer.KIND_INVOICED);
        assertEquals("AL_WEG", row.choice);
        assertTrue(row.automatic);
        assertEquals(FifoValuer.AUTOMATIC_REASON, row.reason);
        assertNull(row.decisionId);
        assertNull(row.valueEur);
        assertEquals(40, row.quantity);
        assertEquals(1260, automatic.article(ROSE).ownQuantity);
        assertEquals(0, automatic.article(ROSE).invoicedOutQuantity);
        assertTrue(automatic.layers(ROSE).stream().allMatch(layer -> "EIGEN".equals(layer.block)));

        /* "Stuks waren al weg" by hand carves nothing either; undecided counts as "blijft". */
        for (Decided choice : new Decided[] {decision(7, FifoValuer.CHOICE_GONE, "Op 30/12 opgehaald"),
                decision(7, FifoValuer.CHOICE_STAYS, "Staat klaar voor de klant"), null}) {
            invoices.clear();
            invoices.add(new Invoice(118, "F-2026-118", LocalDate.of(2026, 12, 20), "Bloemen Peeters", Map.of(ROSE, 40), choice, Set.of()));
            Result manual = value();
            assertEquals(1260, manual.article(ROSE).ownQuantity);
            assertEquals(choice == null ? null : choice.choice(), separate(manual, FifoValuer.KIND_INVOICED).choice);
            assertNull(separate(manual, FifoValuer.KIND_INVOICED).valueEur);
            assertEquals(new BigDecimal("3158.76"), manual.article(ROSE).costValueEur,
                    "1.260 pieces: 2.366,76 + 723,00 + 30 x 2,30 of the oldest lot");
        }
    }

    @Test
    void invoicesAreCarvedFromTheOldestLayersUpwardInOrderOfDate() {
        exampleOfSection38();
        invoices.add(new Invoice(201, "F-2026-201", LocalDate.of(2026, 12, 28), "B", Map.of(ROSE, 100), decision(2, "UIT", null), Set.of()));
        invoices.add(new Invoice(200, "F-2026-200", LocalDate.of(2026, 12, 21), "A", Map.of(ROSE, 50), decision(1, "UIT", null), Set.of()));

        Result result = value();

        List<StockClosingLayerEntity> out = result.layers(ROSE).stream().filter(layer -> "GEFACTUREERD".equals(layer.block)).toList();
        assertEquals(3, out.size());
        assertLayer(out.get(0), "GEFACTUREERD", 3, 3L, null, 50, "2.3000", "115.00");
        assertEquals(200L, out.get(0).salesOrderId, "the earlier invoice first");
        assertLayer(out.get(1), "GEFACTUREERD", 3, 3L, null, 20, "2.3000", "46.00");
        assertLayer(out.get(2), "GEFACTUREERD", 2, 9L, null, 80, "2.4100", "192.80");
        assertEquals(201L, out.get(2).salesOrderId);
        List<StockClosingLayerEntity> own = result.layers(ROSE).stream().filter(layer -> "EIGEN".equals(layer.block)).toList();
        assertEquals(2, own.size(), "the oldest layer is used up");
        assertEquals(220, own.get(1).quantity);
        assertEquals(1150, result.article(ROSE).ownQuantity);
        assertEquals(150, result.article(ROSE).invoicedOutQuantity);
    }

    /* ---------------------------------------------------------------- layers */

    @Test
    void theNewestLotIsFilledFirstAndTheIdDecidesBetweenEqualDays() {
        quantity(ROSE, 250);
        lots.add(lot(5, LocalDate.of(2026, 3, 1), ROSE, 100, "1.0000"));
        lots.add(lot(7, LocalDate.of(2026, 6, 1), ROSE, 100, "3.0000"));
        lots.add(lot(8, LocalDate.of(2026, 6, 1), ROSE, 100, "4.0000"));

        List<StockClosingLayerEntity> layers = value().layers(ROSE);

        assertLayer(layers.get(0), "EIGEN", 1, 8L, 100, 100, "4.0000", "400.00");
        assertLayer(layers.get(1), "EIGEN", 2, 7L, 100, 100, "3.0000", "300.00");
        assertLayer(layers.get(2), "EIGEN", 3, 5L, 100, 50, "1.0000", "50.00");
    }

    @Test
    void theCapacityOfALotIsItsUsableQuantityWhateverWasReportedLater() {
        /* Lot C of section 2.13: 600 received, none damaged on the line; the 6 reported later left through the stock book. */
        quantity(ROSE, 700);
        lots.add(lot(21, LocalDate.of(2026, 12, 10), ROSE, 600, "8.8519"));
        lots.add(lot(4, LocalDate.of(2026, 2, 1), ROSE, 400, "8.0000"));

        Result result = value();

        assertLayer(result.layers(ROSE).get(0), "EIGEN", 1, 21L, 600, 600, "8.8519", "5311.14");
        assertLayer(result.layers(ROSE).get(1), "EIGEN", 2, 4L, 400, 100, "8.0000", "800.00");
        assertEquals(new BigDecimal("6111.14"), result.article(ROSE).costValueEur);
    }

    @Test
    void whatTheLotsDoNotCoverGoesToAnOpeningValueAndTheRestHasNoValue() {
        quantity(ROSE, 500);
        lots.add(lot(5, LocalDate.of(2026, 3, 1), ROSE, 100, "2.0000"));
        openings.add(new Opening(31, ROSE, 150, new BigDecimal("1.5000"), LocalDate.of(2025, 12, 31), "inventaris 31/12/2025"));
        openings.add(new Opening(32, ROSE, 50, new BigDecimal("1.7500"), LocalDate.of(2026, 6, 30), "telling juni"));
        openings.add(new Opening(33, ROSE, 999, new BigDecimal("9.0000"), LocalDate.of(2027, 1, 15), "te laat"));

        Result result = value();

        List<StockClosingLayerEntity> layers = result.layers(ROSE);
        assertEquals(3, layers.size());
        assertLayer(layers.get(1), "EIGEN", 2, null, 50, 50, "1.7500", "87.50");
        assertEquals("BEGINWAARDE", layers.get(1).source);
        assertEquals(32L, layers.get(1).openingLayerId);
        assertEquals("telling juni", layers.get(1).openingSource);
        assertEquals(LocalDate.of(2026, 6, 30), layers.get(1).receivedOn, "the date of the value");
        assertLayer(layers.get(2), "EIGEN", 3, null, 150, 150, "1.5000", "225.00");
        StockClosingArticleEntity article = result.article(ROSE);
        assertEquals(200, article.unvaluedQuantity);
        assertEquals(300, article.ownQuantity);
        assertEquals("ZONDER_WAARDE", article.status);
        assertEquals(new BigDecimal("312.50"), article.openingEur);
        assertEquals(new BigDecimal("200.00"), article.goodsEur);
        assertEquals(new BigDecimal("512.50"), article.costValueEur);
        assertEquals(List.of(), result.unusedOpenings(), "a first closing uses every opening value up to its date");
    }

    @Test
    void anOpeningValueFromBeforeThePreviousClosingIsNotUsedAndOnlyReportedWhenNothingConsumedIt() {
        previousClosingDate = PREVIOUS;
        quantity(ROSE, 100);
        openings.add(new Opening(31, ROSE, 100, new BigDecimal("1.5000"), PREVIOUS, "inventaris 31/12/2025"));
        openings.add(new Opening(32, ROSE, 100, new BigDecimal("1.6000"), PREVIOUS.minusDays(40), "oude lijst"));
        consumed.add(32L);

        Result result = value();

        assertEquals(List.of(), result.layers(ROSE));
        assertEquals(100, result.article(ROSE).unvaluedQuantity);
        assertEquals(List.of(31L), result.unusedOpenings().stream().map(Opening::id).toList(),
                "the one an earlier closing used did its work then");

        /* Dated one day later it is a layer of this closing. */
        openings.set(0, new Opening(31, ROSE, 100, new BigDecimal("1.5000"), PREVIOUS.plusDays(1), "inventaris"));
        assertEquals(0, value().article(ROSE).unvaluedQuantity);
    }

    @Test
    void theLayersOfThePreviousClosingAreCarriedAtTheirFrozenUnitAndALateLotSitsBelowTheOnesReceivedAfterIt() {
        previousClosingDate = PREVIOUS;
        previousWriteDowns.put(ROSE, new BigDecimal("12.00"));
        quantity(ROSE, 1000);
        lots.add(lot(30, LocalDate.of(2026, 5, 1), ROSE, 200, "3.0000"));
        /* Registered late with a receipt day in last year: no closing held it. */
        lots.add(lot(31, LocalDate.of(2025, 8, 15), ROSE, 150, "2.7000"));
        carried.add(frozen(1, 20L, LocalDate.of(2025, 11, 1), 300, "2.6000", "PARTIJ", null, null));
        carried.add(frozen(2, 18L, LocalDate.of(2025, 6, 1), 120, "2.5000", "PARTIJ", 70L, null));
        carried.add(frozen(3, null, LocalDate.of(2024, 12, 31), 400, "2.0000", "BEGINWAARDE", null, 55L));

        Result result = value();

        List<StockClosingLayerEntity> layers = result.layers(ROSE);
        assertEquals(5, layers.size());
        assertLayer(layers.get(0), "EIGEN", 1, 30L, 200, 200, "3.0000", "600.00");
        assertLayer(layers.get(1), "EIGEN", 2, 20L, 300, 300, "2.6000", "780.00");
        assertEquals("VORIG", layers.get(1).source);
        assertEquals("PARTIJ", layers.get(1).originSource);
        assertEquals(90L, layers.get(1).originClosingId, "first valued in the previous closing");
        assertLayer(layers.get(2), "EIGEN", 3, 31L, 150, 150, "2.7000", "405.00");
        assertEquals("PARTIJ", layers.get(2).source, "the late lot, at its own cost, below the layer received after it");
        assertLayer(layers.get(3), "EIGEN", 4, 18L, 120, 120, "2.5000", "300.00");
        assertEquals(70L, layers.get(3).originClosingId, "carried for the second time");
        assertLayer(layers.get(4), "EIGEN", 5, null, 400, 230, "2.0000", "460.00");
        assertEquals("VORIG", layers.get(4).source);
        assertEquals("BEGINWAARDE", layers.get(4).originSource);
        assertEquals(55L, layers.get(4).openingLayerId);
        assertEquals(Set.of(31L), result.lateContainers());

        StockClosingArticleEntity article = result.article(ROSE);
        assertEquals(new BigDecimal("460.00"), article.openingEur);
        assertEquals(new BigDecimal("2545.00"), article.costValueEur);
        assertEquals(new BigDecimal("12.00"), article.previousWriteDownEur);
        assertEquals(new BigDecimal("0.00"), article.writeDownEur, "a new year starts without waardeverminderingen");

        /* A late lot older than every dated layer goes above the opening value, the oldest layer of all. */
        lots.set(1, lot(31, LocalDate.of(2025, 3, 1), ROSE, 150, "2.7000"));
        assertEquals(List.of(30L, 20L, 18L, 31L), value().layers(ROSE).stream().filter(layer -> layer.purchaseOrderId != null)
                .map(layer -> layer.purchaseOrderId).toList());

        /* A carried layer gives at most what the previous closing froze. */
        quantity(ROSE, 5000);
        Result more = value();
        assertEquals(300, more.layers(ROSE).get(1).quantity);
        assertEquals(5000 - 200 - 300 - 150 - 120 - 400, more.article(ROSE).unvaluedQuantity);
    }

    /* ------------------------------------------------- third parties, partner */

    @Test
    void goodsOfThirdPartiesAndPartnerPiecesLeaveTheQuantityFirstAndAreNeverInAnOwnLayer() {
        quantity(ROSE, 500);
        lots.add(lot(5, LocalDate.of(2026, 3, 1), ROSE, 1000, "2.0000"));
        thirdParties.add(new ThirdParty(3, ROSE, 40, "Atelier Mira", "In bewaring", "Emre", DECIDED));
        partnerLots.add(new PartnerLot(lot(60, LocalDate.of(2026, 9, 1), ROSE, 300, "4.2500"), "Partner NV", 180, null,
                new Decided(4L, true, null, "Eigendom tot de afrekening", "Emre", DECIDED)));
        partnerLots.add(new PartnerLot(lot(61, LocalDate.of(2026, 10, 1), ROSE, 200, "4.5000"), "Partner NV", 0, 90, null));
        partnerLots.add(new PartnerLot(lot(62, LocalDate.of(2026, 10, 2), TULIP, 80, "1.2500"), "Partner NV", 0, null, null));

        Result result = value();

        StockClosingArticleEntity article = result.article(ROSE);
        assertEquals(40, article.thirdPartyQuantity);
        assertEquals(210, article.partnerQuantity);
        assertEquals(250, article.ownQuantity);
        assertEquals(new BigDecimal("500.00"), article.costValueEur);
        assertEquals(1, result.layers(ROSE).size());

        StockClosingSeparateEntity third = separate(result, FifoValuer.KIND_THIRD_PARTY);
        assertEquals(40, third.quantity);
        assertEquals("Atelier Mira", third.counterparty);
        assertNull(third.valueEur, "never valued");
        List<StockClosingSeparateEntity> partner = result.separates().stream()
                .filter(row -> FifoValuer.KIND_PARTNER.equals(row.kind)).toList();
        assertEquals(3, partner.size());
        StockClosingSeparateEntity newest = partner.get(0);
        assertEquals(61L, newest.purchaseOrderId, "the newest container first");
        assertEquals(200, newest.proposedQuantity);
        assertEquals(90, newest.quantity, "the user's own figure");
        assertEquals(new BigDecimal("405.00"), newest.valueEur);
        assertNull(newest.included, "undecided");
        StockClosingSeparateEntity older = partner.get(1);
        assertEquals(120, older.proposedQuantity, "300 minus the 180 that shipped");
        assertEquals(120, older.quantity);
        assertEquals(new BigDecimal("4.2500"), older.unitValueEur);
        assertEquals(new BigDecimal("510.00"), older.valueEur);
        assertTrue(older.included);
        assertEquals("Partner NV", older.counterparty);
        assertEquals(0, partner.get(2).quantity, "a product that lies nowhere");
        assertEquals(80, partner.get(2).proposedQuantity);
        assertEquals(Set.of(), result.thirdPartyExcess());

        /* More than was counted blocks, and a partner lot gets at most what is left. */
        thirdParties.add(new ThirdParty(5, ROSE, 480, "Atelier Mira", "In bewaring", "Emre", DECIDED));
        Result excess = value();
        assertEquals(Set.of(ROSE), excess.thirdPartyExcess());
        assertEquals(0, excess.article(ROSE).ownQuantity);
        quantity(ROSE, 100);
        thirdParties.clear();
        Result capped = value();
        assertEquals(100, capped.article(ROSE).partnerQuantity);
        assertEquals(List.of(90, 10, 0), capped.separates().stream().map(row -> row.quantity).toList());
    }

    /* ------------------------------------------------------------ write-downs */

    @Test
    void aLowerMarketValueTakesTheDearestPiecesFirstAndNeverRaisesAboveCost() {
        quantity(ROSE, 300);
        lots.add(lot(8, LocalDate.of(2026, 6, 1), ROSE, 100, "2.0000"));
        lots.add(lot(7, LocalDate.of(2026, 5, 1), ROSE, 100, "5.0000"));
        lots.add(lot(6, LocalDate.of(2026, 4, 1), ROSE, 100, "3.0000"));
        writeDowns.add(new WriteDown(21, ROSE, 120, new BigDecimal("1.00"), "TRAAG", "Geen verkoop sinds mei", "Emre", DECIDED));
        writeDowns.add(new WriteDown(22, ROSE, null, new BigDecimal("2.50"), "MARKT", "Prijslijst 2027", "Emre", DECIDED));
        writeDowns.add(new WriteDown(20, ROSE, 10, new BigDecimal("0.00"), "BESCHADIGD", "Gebroken", "Emre", DECIDED));

        Result result = value();

        List<StockClosingWriteDownEntity> rows = result.writeDowns();
        /* Decision 20 first: 10 of the dearest layer to nothing. Then 21: the 90 left there and 30 of the next. Then 22: the rest. */
        assertEquals(List.of("20:2:10:50.00", "21:2:90:360.00", "21:3:30:60.00", "22:3:70:35.00", "22:1:100:0.00"),
                rows.stream().map(row -> row.decisionId + ":" + row.layerPosition + ":" + row.quantity + ":" + row.amountEur).toList());
        StockClosingArticleEntity article = result.article(ROSE);
        assertEquals(new BigDecimal("1000.00"), article.costValueEur);
        assertEquals(new BigDecimal("505.00"), article.writeDownEur);
        assertEquals(new BigDecimal("495.00"), article.ownValueEur);
        assertEquals(Set.of(), result.writeDownExcess(), "the open quantity never counts towards too much");
        assertEquals(List.of(), result.withoutEffect());
        assertEquals(100, result.layers(ROSE).get(1).writeDownQuantity);
        assertEquals(new BigDecimal("410.00"), result.layers(ROSE).get(1).writeDownEur);

        /* A market value above cost lowers nothing and is reported. */
        writeDowns.clear();
        writeDowns.add(new WriteDown(30, ROSE, 50, new BigDecimal("9.00"), "MARKT", "Te hoog", "Emre", DECIDED));
        Result none = value();
        assertEquals(new BigDecimal("0.00"), none.article(ROSE).writeDownEur);
        assertEquals(List.of(30L), none.withoutEffect().stream().map(WriteDown::decisionId).toList());

        /* Explicit quantities above the own quantity block; the decisions take what is there. */
        writeDowns.clear();
        writeDowns.add(new WriteDown(40, ROSE, 200, new BigDecimal("1.00"), "TRAAG", "a", "Emre", DECIDED));
        writeDowns.add(new WriteDown(41, ROSE, 101, new BigDecimal("1.00"), "TRAAG", "b", "Emre", DECIDED));
        Result tooMany = value();
        assertEquals(Set.of(ROSE), tooMany.writeDownExcess());
        assertEquals(300, tooMany.writeDowns().stream().mapToInt(row -> row.quantity).sum());
    }

    @Test
    void aNegativeLocationCountsAsNothingAndIsNamed() {
        quantities.put(ROSE, List.of(new LocationQuantity(WAREHOUSE, -20, 0, -20), new LocationQuantity(TICA, 60, 0, 60)));
        lots.add(lot(8, LocalDate.of(2026, 6, 1), ROSE, 100, "2.0000"));

        Result result = value();

        assertEquals(60, result.article(ROSE).closingQuantity);
        assertEquals("NEGATIEF", result.article(ROSE).status);
        assertEquals(List.of(new StockRoll.Key(ROSE, WAREHOUSE)), result.negativeLocations());
        assertEquals(new BigDecimal("0.00"), location(result, ROSE, WAREHOUSE).costValueEur());
        assertEquals(new BigDecimal("120.00"), location(result, ROSE, TICA).costValueEur());
        Map<Long, Integer> held = new LinkedHashMap<>();
        held.put(WAREHOUSE, -20);
        held.put(TICA, 60);
        assertEquals(Map.of(WAREHOUSE, 0, TICA, 60), FifoValuer.ownQuantityPerLocation(60, held));
    }

    @Test
    void anAmountIsSharedToTheCentWithTheLargestRemainderFirst() {
        assertEquals(List.of(new BigDecimal("33.34"), new BigDecimal("33.33"), new BigDecimal("33.33")),
                FifoValuer.allocate(new BigDecimal("100.00"), List.of(BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE)));
        assertEquals(List.of(new BigDecimal("0.00"), new BigDecimal("10.00")),
                FifoValuer.allocate(new BigDecimal("10.00"), List.of(BigDecimal.ZERO, new BigDecimal("7"))));
        assertEquals(List.of(new BigDecimal("5.01"), new BigDecimal("5.00")),
                FifoValuer.allocate(new BigDecimal("10.01"), List.of(BigDecimal.ZERO, BigDecimal.ZERO)), "no weight at all: equal parts");
        Map<Long, Integer> tie = new LinkedHashMap<>();
        tie.put(TICA, 1);
        tie.put(WAREHOUSE, 1);
        assertEquals(Map.of(WAREHOUSE, 2, TICA, 1), FifoValuer.ownQuantityPerLocation(3, tie), "a tie goes to the lower location id");
    }

    /* ---------------------------------------------------------------- helpers */

    /** Product "Roos in stolp rood" of section 3.8: 1.250 in the warehouse, 50 at TICA, three own containers. */
    private void exampleOfSection38() {
        quantities.put(ROSE, List.of(new LocationQuantity(WAREHOUSE, 1210, 40, 1250), new LocationQuantity(TICA, 50, 0, 50)));
        lots.add(new Lot(14, "PO-2026-014", "PO-2026-014", LocalDate.of(2026, 11, 20), ROSE, 930, new BigDecimal("2.5449"),
                new BigDecimal("1.8000"), new BigDecimal("0.0000"), new BigDecimal("0.6526"), new BigDecimal("0.0923"),
                new BigDecimal("0.2025")));
        lots.add(lot(9, LocalDate.of(2026, 6, 14), ROSE, 300, "2.4100"));
        lots.add(lot(3, LocalDate.of(2026, 2, 2), ROSE, 500, "2.3000"));
    }

    private Result value() {
        Map<Long, Article> products = new LinkedHashMap<>();
        products.put(ROSE, new Article(ROSE, "ROOS-R", "Roos in stolp rood", "Rozen", "stuk", "PIECE", null, false, true));
        products.put(TULIP, new Article(TULIP, "TULP-W", "Tulp wit", "Tulpen", "stuk", "PIECE", null, false, true));
        return FifoValuer.value(new FifoValuer.Input(CLOSING, previousClosingDate, previousClosingDate == null ? null : 90L,
                products, quantities, thirdParties, partnerLots, lots, carried, openings, consumed, invoices, writeDowns,
                Map.of(90L, 2025, 70L, 2024), previousWriteDowns));
    }

    private void quantity(long productId, int quantity) {
        quantities.put(productId, List.of(new LocationQuantity(WAREHOUSE, quantity, 0, quantity)));
    }

    private static Lot lot(long orderId, LocalDate receivedOn, long productId, int capacity, String unit) {
        String number = "PO-" + orderId;
        return new Lot(orderId, number, number, receivedOn, productId, capacity, new BigDecimal(unit), new BigDecimal(unit),
                new BigDecimal("0.0000"), new BigDecimal("0.0000"), new BigDecimal("0.0000"), new BigDecimal("0.0000"));
    }

    /** A layer as the previous final closing (id 90) stored it. */
    private static StockClosingLayerEntity frozen(int position, Long orderId, LocalDate receivedOn, int quantity, String unit,
                                                  String originSource, Long originClosingId, Long openingLayerId) {
        StockClosingLayerEntity layer = new StockClosingLayerEntity();
        layer.closingId = 90L;
        layer.productId = ROSE;
        layer.block = "EIGEN";
        layer.position = position;
        layer.source = originClosingId == null ? originSource : "VORIG";
        layer.originSource = originSource;
        layer.originClosingId = originClosingId;
        layer.purchaseOrderId = orderId;
        layer.orderNumber = orderId == null ? null : "PO-" + orderId;
        layer.displayName = layer.orderNumber;
        layer.receivedOn = receivedOn;
        layer.openingLayerId = openingLayerId;
        layer.openingSource = openingLayerId == null ? null : "inventaris 2024";
        layer.capacity = quantity + 50;
        layer.quantity = quantity;
        layer.unitValueEur = new BigDecimal(unit);
        layer.unitGoodsEur = openingLayerId == null ? new BigDecimal(unit) : null;
        layer.unitEstimatedEur = new BigDecimal("0.0000");
        layer.writeDownQuantity = 5;
        layer.writeDownEur = new BigDecimal("12.00");
        return layer;
    }

    private static Decided decision(long id, String choice, String reason) {
        return new Decided(id, null, choice, reason, "Emre", DECIDED);
    }

    private static void assertLayer(StockClosingLayerEntity layer, String block, int position, Long orderId, Integer capacity,
                                    int quantity, String unit, String value) {
        assertEquals(block, layer.block);
        assertEquals(position, layer.position);
        assertEquals(orderId, layer.purchaseOrderId);
        assertEquals(capacity, layer.capacity);
        assertEquals(quantity, layer.quantity);
        assertEquals(new BigDecimal(unit), layer.unitValueEur);
        assertEquals(new BigDecimal(value), layer.valueEur);
    }

    private static LocationValue location(Result result, long productId, long locationId) {
        return result.locations().stream().filter(value -> value.productId() == productId && value.locationId() == locationId)
                .findFirst().orElseThrow();
    }

    private static StockClosingSeparateEntity separate(Result result, String kind) {
        return result.separates().stream().filter(row -> kind.equals(row.kind)).findFirst().orElseThrow();
    }
}
