package be.enrosed.inventory.application;

import be.enrosed.catalog.adapter.out.persistence.ProductEntity;
import be.enrosed.catalog.application.StockService;
import be.enrosed.catalog.domain.StockLocation;
import be.enrosed.catalog.domain.StockMovement;
import be.enrosed.inventory.adapter.in.rest.StockClosingDtos;
import be.enrosed.inventory.adapter.in.rest.StockClosingFinalResource;
import be.enrosed.inventory.adapter.in.rest.StockClosingFinalResource.FinalizeRequest;
import be.enrosed.inventory.adapter.in.rest.StockClosingFinalResource.VersionRequest;
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
import be.enrosed.inventory.application.ClosingNotices.Notice;
import be.enrosed.inventory.application.StockClosingDecisionService.Write;
import be.enrosed.inventory.application.StockClosingService.View;
import be.enrosed.inventory.application.StockCountService.LineWrite;
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
import be.enrosed.sourcing.adapter.out.persistence.SourcingEntities.PurchaseOrderLineEntity;
import be.enrosed.sourcing.application.PurchaseOrderService;
import be.enrosed.sourcing.application.SupplierService;
import be.enrosed.sourcing.domain.PurchaseOrder;
import be.enrosed.sourcing.domain.PurchaseOrderLine;
import be.enrosed.sourcing.domain.PurchaseOrderStatus;
import be.enrosed.sourcing.domain.PurchasePayment.Payee;
import be.enrosed.sourcing.domain.Supplier;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import jakarta.persistence.Column;
import jakarta.persistence.EntityManager;
import jakarta.ws.rs.core.Response;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The whole walk on H2, from the count to a corrected closing and the year
 * after it: what "Definitief maken" freezes, what a new version shows, and
 * what a later year takes over.
 *
 * The walk runs inside one rolled-back transaction on an emptied book, so
 * every figure is the test's own and is computed by hand in the comments.
 * Both years lie in the past; the counts are booked today.
 */
@QuarkusTest
@TestSecurity(user = "emre", roles = "admin")
class StockClosingFlowTest {
    private static final int YEAR2 = InventoryClock.today().getYear() - 1;
    private static final int YEAR1 = YEAR2 - 1;
    private static final LocalDate END1 = LocalDate.of(YEAR1, 12, 31);
    private static final LocalDate OPENING_DAY = LocalDate.of(YEAR1 - 1, 12, 31);
    private static final String OPENING_SOURCE = "Inventaris 31/12/" + (YEAR1 - 1);

    @Inject StockClosingService closings;
    @Inject StockClosingFinalizer finalizer;
    @Inject StockClosingResource resource;
    @Inject StockClosingFinalResource finalResource;
    @Inject StockOpeningLayerService openingLayers;
    @Inject StockCountService counts;
    @Inject StockService stock;
    @Inject PurchaseOrderService purchases;
    @Inject SupplierService suppliers;
    @Inject SalesOrderService sales;
    @Inject CustomerService customers;
    @Inject InventoryStore.Closings closingRows;
    @Inject InventoryStore.Decisions decisionRows;
    @Inject ObjectMapper json;
    @Inject EntityManager em;

    private Supplier supplier;

    /** The pieces of the walk that later steps come back to. */
    private StockLocation main, shelf;
    private long rose, old, water;
    private long c1, c2, transit;
    private long mainCount;
    private SalesOrder olderInvoice;

    @Test @TestTransaction
    void theWalkFromTheCountToACorrectedClosingAndTheYearAfterIt() throws IOException {
        long first = aConceptWithEveryDecisionTaken();

        /* ------------------------------------------------ definitief maken */

        View concept = closings.view(first);
        String seen = concept.closing().dataSha256;
        assertTrue(concept.canFinalize());
        assertEquals("Vul in wie de inventaris ondertekent", assertThrows(UnprocessableBusinessRuleException.class,
                () -> finalizer.makeFinal(first, seen, " ")).getMessage());
        InventoryRefusal moved = assertThrows(InventoryRefusal.class, () -> finalizer.makeFinal(first, "0".repeat(64), "Emre Yilmaz"));
        assertEquals("CIJFERS_GEWIJZIGD", moved.code());
        assertEquals("De gegevens zijn intussen gewijzigd. Herbereken en kijk de cijfers opnieuw na", moved.getMessage());
        assertEquals("CONCEPT", closingRows.findById(first).status);
        assertNull(closingRows.findById(first).signerName);

        StockClosingDtos.ClosingView shown = finalResource.finalizeClosing(first, new FinalizeRequest(seen, " Emre Yilmaz "));
        assertEquals("DEFINITIEF", shown.status());
        assertEquals(seen, shown.dataSha256(), "the figures the user saw are the figures that were frozen");
        assertEquals("Emre Yilmaz", shown.signerName());
        assertEquals("emre", shown.finalizedByName());
        assertNotNull(shown.finalizedAt());
        assertEquals(64, shown.pdfSha256().length());
        assertEquals(64, shown.xlsxSha256().length());
        assertFalse(shown.canFinalize());
        assertTrue(shown.canCorrect());
        assertFalse(shown.superseded());
        assertTrue(shown.versions().getFirst().hasFiles());
        assertMoney("2150.32", shown.totals().totalValueEur());

        StockClosingEntity frozen = closingRows.findById(first);
        assertEquals("emre", frozen.finalizedBy);
        assertEquals("Enrosed BV", frozen.companyName, "the company identity is copied onto the closing");
        assertEquals("BE 1034.273.386", frozen.companyVat);
        assertEquals("Vekeblok 17, 2400 Mol, BE", frozen.companyAddress);
        assertNotNull(frozen.pdfStorageKey);
        assertNotNull(frozen.xlsxStorageKey);
        assertNull(frozen.supersededById);
        InventoryRefusal again = assertThrows(InventoryRefusal.class, () -> finalizer.makeFinal(first, seen, "Emre Yilmaz"));
        assertEquals("DEFINITIEF", again.code());

        byte[] pdf = finalizer.pdf(first).content();
        byte[] xlsx = finalizer.xlsx(first).content();
        assertEquals(frozen.pdfSha256, StockClosingFinalizer.sha256(pdf), "the stored bytes are the hashed bytes");
        assertEquals(frozen.xlsxSha256, StockClosingFinalizer.sha256(xlsx));
        assertEquals((long) pdf.length, frozen.pdfSizeBytes);
        assertEquals((long) xlsx.length, frozen.xlsxSizeBytes);
        assertEquals("jaarinventaris-" + YEAR1 + "-v1.pdf", finalizer.pdf(first).filename());
        assertEquals("jaarinventaris-" + YEAR1 + "-v1.xlsx", finalizer.xlsx(first).filename());
        Response download = finalResource.pdf(first);
        assertEquals("attachment; filename=\"jaarinventaris-" + YEAR1 + "-v1.pdf\"", download.getHeaderString("Content-Disposition"));
        assertEquals("no-store", download.getHeaderString("Cache-Control"));
        assertEquals("application/pdf", download.getMediaType().toString());
        assertEquals("%PDF", new String(pdf, 0, 4, StandardCharsets.US_ASCII));
        String paper = text(pdf);
        assertTrue(paper.contains(frozen.dataSha256), "the paper prints the fingerprint of the data");
        assertTrue(paper.contains(frozen.xlsxSha256), "and the hash of the workbook");
        assertTrue(paper.replaceAll("\\s+", "").contains(("Boekjaar " + YEAR1 + " · versie 1 · Definitief").replaceAll("\\s+", "")));
        assertTrue(paper.replaceAll("\\s+", "").contains("Opgemaaktdoor:EmreYilmaz"));
        assertTrue(paper.replaceAll("\\s+", "").contains("EnrosedBV"));
        assertTrue(paper.replaceAll("\\s+", "").contains("€2.150,32"));
        assertTrue(paper.replaceAll("\\s+", "").contains("emrebevestigdeop"), "the VAT confirmation as a statement of the user");
        StockClosingWorkbookTest.assertWorkbookAddsUp(xlsx, frozen);
        Map<String, Object> summary = new LinkedHashMap<>();
        for (List<Object> row : StockClosingWorkbookTest.read(xlsx).get("Samenvatting")) {
            if (row.size() > 1) summary.put((String) row.get(0), row.get(1));
        }
        StockClosingWorkbookTest.assertMoney("2150.32", summary.get("Totaal voorraadwaarde volgens de genomen beslissingen"));
        StockClosingWorkbookTest.assertMoney("83.00", summary.get("Waardeverminderingen"));
        assertEquals(frozen.dataSha256, summary.get("Gegevenscontrole"));
        assertEquals("Emre Yilmaz", summary.get("Ondertekenaar"));

        ActivityLogEntity logged = activity(StockClosingFinalizer.ACTION_FINALIZED, first);
        assertEquals("STOCK_CLOSING", logged.entityType);
        assertEquals("Jaarinventaris " + YEAR1 + " versie 1 definitief: € 2.150,32. data " + frozen.dataSha256.substring(0, 8)
                + " · pdf " + frozen.pdfSha256.substring(0, 8) + " · excel " + frozen.xlsxSha256.substring(0, 8), logged.summary);

        /* --------------------------------------- the live data moves on */

        Map<String, List<String>> rowsBefore = stored(first);
        String viewBefore = shownAsJson(first);

        /* A payment is added and the price of a container is changed: lot C1 would now be worth 2,0240 per piece. */
        PurchaseOrderLineEntity priced = em.createQuery("from " + PurchaseOrderLineEntity.class.getName()
                + " where order.id = ?1", PurchaseOrderLineEntity.class).setParameter(1, c1).getSingleResult();
        priced.exwPrice = new BigDecimal("2.20");
        em.flush(); em.clear();
        pay(c1, LocalDate.of(YEAR1, 12, 1), new BigDecimal("18.40"), Currency.EUR);
        /* A level is changed and the sale that was booked during the count is deleted from the stock book. */
        stock.setLevel(old, shelf.id(), 45, StockMovement.Kind.MANUAL_CORRECTION, "na de afsluiting");
        StockMovement sale = stock.movementsFor(rose).stream().filter(row -> row.kind() == StockMovement.Kind.SALE).findFirst().orElseThrow();
        assertEquals(1, em.createQuery("delete from StockMovementEntity where id = ?1").setParameter(1, sale.id()).executeUpdate());
        /* The opening value is retired and replaced: the same figures, another row. */
        long openingBefore = openingLayers.active().getFirst().id;
        StockOpeningLayerEntity replacement = openingLayers.save(new StockOpeningLayerService.Write(OPENING_DAY, OPENING_SOURCE,
                List.of(new StockOpeningLayerService.Row(old, 50, new BigDecimal("1.50"), "Herzien na controle")))).getFirst();
        assertNotEquals(openingBefore, replacement.id);
        assertNotNull(em.find(StockOpeningLayerEntity.class, openingBefore).retiredAt);
        /* A correction of the count is booked for the warehouse and the older invoice is afgepunt after all. */
        long correction = counts.start(YEAR1, main.id(), null, mainCount).summary().count().id;
        var added = counts.addLine(correction, rose).line().row();
        counts.saveLine(correction, added.id, new LineWrite(95, null, null, added.revision, null, null));
        counts.book(correction, counts.bookingCheck(correction).checkToken());
        sales.shipGoods(olderInvoice.id());
        em.flush(); em.clear();

        assertEquals(rowsBefore, stored(first), "every stored row is unchanged, field by field");
        assertEquals(viewBefore, shownAsJson(first), "the view of the final closing is unchanged");
        assertEquals(frozen.pdfSha256, StockClosingFinalizer.sha256(finalizer.pdf(first).content()), "the same PDF, byte for byte");
        assertEquals(frozen.xlsxSha256, StockClosingFinalizer.sha256(finalizer.xlsx(first).content()), "the same workbook");
        StockClosingDtos.ClosingView still = resource.get(first);
        assertEquals(List.of(openingBefore), still.openingLayers().stream().map(StockClosingDtos.OpeningLayer::id).toList(),
                "the final closing shows the opening value it used, not its replacement");
        assertEquals(50, still.openingLayers().getFirst().quantity());
        assertNull(still.openingLayers().getFirst().note());
        StockClosingDtos.ClosingLocation warehouse = still.locations().stream().filter(row -> row.locationId() == main.id())
                .findFirst().orElseThrow();
        assertEquals(mainCount, warehouse.countId());
        assertEquals(0, warehouse.correctionCount(), "a correction booked afterwards changes nothing in the final view");
        assertEquals(1, warehouse.differenceCount());
        assertEquals(List.of(olderInvoice.id()), still.olderInvoices().stream().map(StockClosingDtos.OlderInvoice::salesOrderId).toList(),
                "the list of older invoices is the one it was frozen with");
        for (InventoryRefusal refused : List.of(
                assertThrows(InventoryRefusal.class, () -> closings.recompute(first)),
                assertThrows(InventoryRefusal.class, () -> closings.compute(first), "not even a direct call rebuilds a final closing"),
                assertThrows(InventoryRefusal.class, () -> closings.saveDecision(first, vat())),
                assertThrows(InventoryRefusal.class, () -> closings.setClosingDate(first, END1.minusDays(1))),
                assertThrows(InventoryRefusal.class, () -> closings.delete(first)))) {
            assertEquals("DEFINITIEF", refused.code());
        }

        /* --------------------------------------------- a new version */

        assertEquals("Geef de reden van de correctie", assertThrows(UnprocessableBusinessRuleException.class,
                () -> finalizer.newVersion(first, " ")).getMessage());
        Response started = finalResource.newVersion(first, new VersionRequest("Factuur van de leverancier kwam later"));
        assertEquals(201, started.getStatus());
        StockClosingDtos.ClosingView second = (StockClosingDtos.ClosingView) started.getEntity();
        long secondId = second.id();
        assertEquals("CONCEPT", second.status());
        assertEquals(2, second.versionNo());
        assertEquals(first, second.supersedesId());
        assertEquals("Factuur van de leverancier kwam later", second.correctionReason());
        assertEquals(END1, second.closingDate());
        assertEquals(List.of(2, 1), second.versions().stream().map(StockClosingDtos.ClosingSummary::versionNo).toList());
        ActivityLogEntity versionLogged = activity(StockClosingFinalizer.ACTION_VERSION_STARTED, secondId);
        assertEquals("STOCK_CLOSING", versionLogged.entityType);
        assertEquals("Jaarinventaris " + YEAR1 + ": versie 2 gestart ter correctie van versie 1."
                + " Reden: Factuur van de leverancier kwam later", versionLogged.summary);

        /* The old version stays final, readable and downloadable; a second concept is refused. */
        assertEquals("DEFINITIEF", resource.get(first).status());
        assertFalse(resource.get(first).canCorrect(), "a concept for the year is open");
        assertFalse(resource.get(first).superseded(), "replaced only once the new version is final");
        assertEquals(frozen.pdfSha256, StockClosingFinalizer.sha256(finalizer.pdf(first).content()));
        InventoryRefusal open = assertThrows(InventoryRefusal.class, () -> finalizer.newVersion(first, "nog eens"));
        assertEquals("CONCEPT_BESTAAT", open.code());
        assertEquals("Voor " + YEAR1 + " staat al een concept open", open.getMessage());
        assertEquals(secondId, open.details().get("closingId"));
        InventoryRefusal notFinal = assertThrows(InventoryRefusal.class, () -> finalizer.newVersion(secondId, "van een concept"));
        assertEquals("GEEN_DEFINITIEVE", notFinal.code());
        assertEquals("Alleen van de geldige definitieve versie kan een nieuwe versie gemaakt worden", notFinal.getMessage());

        /* The decisions are copies in the order they were taken, with who decided and when. */
        List<StockClosingDecisionEntity> taken = decisionRows.list("closingId = ?1 order by id", first);
        List<StockClosingDecisionEntity> copied = decisionRows.list("closingId = ?1 order by id", secondId);
        assertEquals(4, taken.size());
        assertEquals(taken.stream().map(StockClosingFlowTest::content).toList(), copied.stream().map(StockClosingFlowTest::content).toList());
        assertEquals(List.of("WRITE_DOWN", "WRITE_DOWN", "TRANSIT", "VAT_CONFIRMATION"), copied.stream().map(row -> row.kind).toList());
        for (StockClosingDecisionEntity copy : copied) {
            assertEquals("emre", copy.decidedByName);
            assertTrue(copy.id > taken.getLast().id, "only the id and the closing are new");
        }
        View version = closings.view(secondId);
        assertEquals(writeDownAmounts(closings.view(first)), writeDownAmounts(version),
                "two write-downs of one product give the same amounts per layer as in version 1");
        assertEquals(List.of("20 x 6.00", "40 x 52.00", "50 x 25.00"), writeDownAmounts(version));

        /* Rebuilt from the data of today: exactly what moved since version 1 is listed. */
        ClosingVersionDiff.Changes changes = version.versionChanges();
        assertEquals(first, changes.againstClosingId());
        assertEquals(1, changes.againstVersionNo());
        assertMoney("2150.32", changes.totalBeforeEur());
        assertMoney("2162.28", changes.totalAfterEur());
        assertEquals(List.of(rose), changes.articles().stream().map(ClosingVersionDiff.ArticleChange::productId).toList());
        assertEquals(98, changes.articles().getFirst().quantityBefore());
        assertEquals(95, changes.articles().getFirst().quantityAfter(), "the deleted sale is no longer added back");
        assertMoney("180.32", changes.articles().getFirst().costValueBeforeEur());
        assertMoney("192.28", changes.articles().getFirst().costValueAfterEur());
        assertEquals(1, changes.lots().size());
        assertEquals(c1, changes.lots().getFirst().purchaseOrderId());
        assertEquals(rose, changes.lots().getFirst().productId());
        assertEquals(0, new BigDecimal("1.84").compareTo(changes.lots().getFirst().unitValueBeforeEur()));
        assertEquals(0, new BigDecimal("2.024").compareTo(changes.lots().getFirst().unitValueAfterEur()));
        assertEquals(List.of(sale.id()), changes.movements().stream().map(ClosingVersionDiff.MovementChange::movementId).toList());
        assertEquals(-3, changes.movements().getFirst().effectBefore());
        assertNull(changes.movements().getFirst().effectAfter(), "no longer in the list");
        assertEquals(List.of(openingBefore, replacement.id),
                changes.openingLayers().stream().map(ClosingVersionDiff.OpeningChange::openingLayerId).toList());
        assertEquals(50, changes.openingLayers().get(0).quantityBefore());
        assertNull(changes.openingLayers().get(0).quantityAfter());
        assertNull(changes.openingLayers().get(1).quantityBefore());
        assertEquals(50, changes.openingLayers().get(1).quantityAfter());
        assertNotice(version, "VERSCHIL_MET_VORIGE_VERSIE", "WARNING", "afsluiten", "Tegenover versie 1: 1 product, 1 partij,"
                + " 1 beweging en 2 beginwaarden anders. Totaal € 2.150,32 → € 2.162,28.");
        assertEquals(1, resource.get(secondId).locations().stream().filter(row -> row.locationId() == main.id())
                .findFirst().orElseThrow().correctionCount(), "the concept does see the correction of the count");
        assertEquals(List.of(), blockers(version));
        assertMoney("405.28", version.closing().costValueEur, "95 x 2,0240 + 213,00");
        assertMoney("83.00", version.closing().writeDownEur);

        /* A concept file is rendered now, says that it is one and names the version it replaces. */
        assertEquals("jaarinventaris-" + YEAR1 + "-v2-concept.pdf", finalizer.pdf(secondId).filename());
        assertEquals("jaarinventaris-" + YEAR1 + "-v2-concept.xlsx", finalizer.xlsx(secondId).filename());
        String conceptPaper = text(finalizer.pdf(secondId).content()).replaceAll("\\s+", "");
        assertTrue(conceptPaper.contains("CONCEPT,nietdefinitief"));
        assertTrue(conceptPaper.contains("Vervangtversie1:Factuurvandeleverancierkwamlater"));
        assertTrue(conceptPaper.contains("Wijzigingentegenoverversie1"));
        assertTrue(StockClosingWorkbookTest.read(finalizer.xlsx(secondId).content()).containsKey("Wijzigingen"));
        assertNull(closingRows.findById(secondId).pdfStorageKey, "nothing is stored for a concept");

        /* Once the new version is final the old one is marked as replaced; both still download. */
        finalizer.makeFinal(secondId, version.closing().dataSha256, "Emre Yilmaz");
        em.flush(); em.clear();
        StockClosingEntity replaced = closingRows.findById(first);
        assertEquals(secondId, replaced.supersededById);
        assertNotNull(replaced.supersededAt);
        assertEquals(rowsBefore.entrySet().stream().filter(entry -> !"StockClosingEntity".equals(entry.getKey())).toList(),
                stored(first).entrySet().stream().filter(entry -> !"StockClosingEntity".equals(entry.getKey())).toList());
        assertEquals(unsuperseded(rowsBefore.get("StockClosingEntity")), unsuperseded(stored(first).get("StockClosingEntity")),
                "the only fields ever written on a final row");
        assertTrue(resource.get(first).superseded());
        assertFalse(resource.get(first).canCorrect());
        assertTrue(resource.get(secondId).canCorrect());
        assertNull(closingRows.findById(secondId).supersededById);
        assertEquals(frozen.pdfSha256, StockClosingFinalizer.sha256(finalizer.pdf(first).content()));
        assertEquals(frozen.xlsxSha256, StockClosingFinalizer.sha256(finalizer.xlsx(first).content()));
        assertEquals(closingRows.findById(secondId).pdfSha256, StockClosingFinalizer.sha256(finalizer.pdf(secondId).content()));
        assertNotEquals(frozen.pdfSha256, closingRows.findById(secondId).pdfSha256);
        assertEquals("GEEN_DEFINITIEVE", assertThrows(InventoryRefusal.class, () -> finalizer.newVersion(first, "van de oude")).code());
        StockClosingWorkbookTest.assertWorkbookAddsUp(finalizer.xlsx(secondId).content(), closingRows.findById(secondId));

        /* ------------------------------------------------ the next year */

        /* The invoice of lot C1 grows once more after version 2 was frozen: 212,40 / 100 = 2,1240 against 2,0240. */
        pay(c1, LocalDate.of(YEAR2, 3, 1), new BigDecimal("10.00"), Currency.EUR);
        count(YEAR2, main.id());
        count(YEAR2, shelf.id());
        long next = closings.create(YEAR2, null).closing().id;
        closings.saveDecision(next, vat());
        View year2 = closings.saveDecision(next, transit(transit, true, LocalDate.of(YEAR1, 12, 15), "FOB: risico over bij het laden"));
        assertEquals(secondId, year2.previous().id);
        List<StockClosingLayerEntity> carried = layers(year2, rose);
        assertEquals(1, carried.size());
        assertEquals("VORIG", carried.getFirst().source);
        assertEquals(new BigDecimal("2.0240"), carried.getFirst().unitValueEur, "the unit value version 2 froze, not the one of today");
        assertEquals(95, carried.getFirst().quantity);
        assertEquals(List.of("VORIG", "VORIG"), layers(year2, old).stream().map(layer -> layer.source).toList());
        assertEquals(List.of(new BigDecimal("2.3000"), new BigDecimal("1.5000")),
                layers(year2, old).stream().map(layer -> layer.unitValueEur).toList());
        assertEquals("BEGINWAARDE", layers(year2, old).getLast().originSource);
        assertMoney("192.28", article(year2, rose).costValueEur, "95 x 2,0240");
        assertMoney("205.50", article(year2, old).costValueEur, "60 x 2,30 + 45 x 1,50");
        assertMoney("0.00", year2.closing().writeDownEur, "a new year starts without waardeverminderingen");
        assertEquals(List.of(), year2.writeDowns());
        assertMoney("83.00", article(year2, old).previousWriteDownEur);
        assertNotice(year2, "PARTIJ_GEWIJZIGD", "WARNING", "waarde", "1 partij heeft nu een andere waarde per stuk dan in de"
                + " afsluiting van " + YEAR1 + ": verschil € 9,50, niet verwerkt.");
        assertMoney("2237.78", year2.closing().totalValueEur, "192,28 + 205,50 + 1.840,00 on the water");
        assertEquals(List.of(), blockers(year2));
        finalizer.makeFinal(next, year2.closing().dataSha256, "Emre Yilmaz");

        /* Year 1 gets a version 3 while year 2 is final on version 2. */
        long third = finalizer.newVersion(secondId, "Betaling van maart hoort bij de partij").closing().id;
        View thirdView = closings.view(third);
        assertEquals(3, thirdView.closing().versionNo);
        assertNotice(thirdView, "LATER_JAAR_AFGESLOTEN", "WARNING", "afsluiten", "Boekjaar " + YEAR2 + " is afgesloten op versie 2"
                + " van dit boekjaar. Maak daarna ook van " + YEAR2 + " een nieuwe versie.");
        assertMoney("2171.78", thirdView.closing().totalValueEur, "95 x 2,1240 + 213,00 - 83,00 + 1.840,00");
        finalizer.makeFinal(third, thirdView.closing().dataSha256, "Emre Yilmaz");
        em.flush(); em.clear();
        assertEquals(third, closingRows.findById(secondId).supersededById);
        assertEquals(secondId, closingRows.findById(first).supersededById, "version 1 keeps pointing at the version that replaced it");

        StockClosingDtos.ClosingView builtOnOld = resource.get(next);
        assertEquals(secondId, builtOnOld.previousClosing().id(), "a final closing keeps the version it was frozen on");
        assertEquals(third, builtOnOld.previousClosingReplacedBy().id());
        assertEquals(3, builtOnOld.previousClosingReplacedBy().versionNo());

        /* A new version of year 2 resolves its previous closing afresh. */
        long nextSecond = finalizer.newVersion(next, "Steunt op versie 3 van " + YEAR1).closing().id;
        View rebuilt = closings.view(nextSecond);
        assertEquals(third, rebuilt.previous().id);
        assertNull(rebuilt.previousReplacedBy());
        assertNotice(rebuilt, "VORIGE_VERSIE_VERVANGEN", "WARNING", "afsluiten",
                "Deze versie steunt op versie 3 van " + YEAR1 + "; de vorige versie steunde op versie 2.");
        assertEquals(new BigDecimal("2.1240"), layers(rebuilt, rose).getFirst().unitValueEur);
        assertNotNull(notice(rebuilt, "VERSCHIL_MET_VORIGE_VERSIE"));

        /* A decision reason changed since the client read the figures: the hash no longer fits. */
        String read = rebuilt.closing().dataSha256;
        assertEquals(List.of(), blockers(rebuilt));
        View reworded = closings.saveDecision(nextSecond, transit(transit, true, LocalDate.of(YEAR1, 12, 15), "Bill of lading 15/12"));
        assertMoney(rebuilt.closing().totalValueEur.toPlainString(), reworded.closing().totalValueEur, "no figure moved");
        InventoryRefusal stale = assertThrows(InventoryRefusal.class, () -> finalizer.makeFinal(nextSecond, read, "Emre Yilmaz"));
        assertEquals("CIJFERS_GEWIJZIGD", stale.code());
        assertEquals("CONCEPT", closingRows.findById(nextSecond).status);

        assertEquals(4, em.createQuery("select count(a) from ActivityLogEntity a where a.action = 'CLOSING_FINALIZED'", Long.class)
                .getSingleResult());
        assertEquals(3, em.createQuery("select count(a) from ActivityLogEntity a where a.action = 'CLOSING_VERSION_STARTED'", Long.class)
                .getSingleResult());
    }

    @Test @TestTransaction
    void aConceptWithABlockerIsNotMadeFinalAndThePointsAreNamed() {
        quiet();
        long product = product("Roos");
        simpleContainer("C1", product, 10, "2.00", LocalDate.of(YEAR1, 11, 20));
        count(YEAR1, stock.mainLocation().id());
        View concept = closings.create(YEAR1, null);
        long id = concept.closing().id;
        assertEquals(List.of("BTW_BEVESTIGING"), blockers(concept));

        InventoryRefusal blocked = assertThrows(InventoryRefusal.class,
                () -> finalResource.finalizeClosing(id, new FinalizeRequest(concept.closing().dataSha256, "Emre Yilmaz")));
        assertEquals("GEBLOKKEERD", blocked.code());
        assertEquals("Nog 1 punt houdt de afsluiting tegen", blocked.getMessage());
        List<?> named = (List<?>) blocked.details().get("notices");
        assertEquals(1, named.size());
        StockClosingDtos.Notice point = (StockClosingDtos.Notice) named.getFirst();
        assertEquals("BTW_BEVESTIGING", point.code());
        assertEquals("BLOCKER", point.severity());
        assertEquals("afsluiten", point.segment());
        assertEquals("CONCEPT", closingRows.findById(id).status);
        assertNull(closingRows.findById(id).pdfStorageKey);
        assertEquals(0, em.createQuery("select count(a) from ActivityLogEntity a where a.action = 'CLOSING_FINALIZED'", Long.class)
                .getSingleResult());
        assertEquals("GEEN_DEFINITIEVE", assertThrows(InventoryRefusal.class, () -> finalizer.newVersion(id, "te vroeg")).code());
        assertEquals("Afsluiting 987654321 bestaat niet", assertThrows(NotFoundException.class,
                () -> finalizer.pdf(987_654_321L)).getMessage());
        assertEquals("Afsluiting 987654321 bestaat niet", assertThrows(NotFoundException.class,
                () -> finalizer.newVersion(987_654_321L, "bestaat niet")).getMessage());
    }

    /* --------------------------------------------------------------- the walk */

    /**
     * Year 1 up to a concept that nothing blocks.
     *
     * Container C1: 100 roses at USD 2,00 x 0,92 = 1,84. Container C2: 60 pieces of "Oud" at USD 2,50
     * = 2,30. Fifty more pieces of "Oud" lie in the shop from before the containers. Container T1 is
     * on the water with 1.000 pieces at 1,84, paid in full.
     *
     * The warehouse is counted with one difference (roses: 100 in the book, 98 counted) and three
     * roses are sold while the count is open: the book ends at 100 - 3 - 2 = 95 and the closing
     * quantity, with the sale after the closing date added back, is 98.
     */
    private long aConceptWithEveryDecisionTaken() {
        quiet();
        main = stock.mainLocation();
        shelf = location("Winkel");
        rose = product("Roos");
        old = product("Oud");
        water = product("Onderweg");
        c1 = simpleContainer("C1", rose, 100, "2.00", LocalDate.of(YEAR1, 11, 20));
        c2 = simpleContainer("C2", old, 60, "2.50", LocalDate.of(YEAR1, 6, 14));
        stock.setLevel(old, shelf.id(), 50, StockMovement.Kind.MANUAL_CORRECTION, "stond er al");
        transit = container("T1", null, LocalDate.of(YEAR1, 12, 1), water, 1000, "2.00");
        pay(transit, LocalDate.of(YEAR1, 9, 1), new BigDecimal("1840.00"), Currency.EUR);
        olderInvoice = invoice(old, 2, LocalDate.of(YEAR1 - 1, 6, 1));

        var session = counts.start(YEAR1, main.id(), null, null);
        mainCount = session.summary().count().id;
        for (var line : session.lines()) {
            if (line.liveQuantity() == 0) continue;
            boolean roses = line.row().productId == rose;
            counts.saveLine(mainCount, line.row().id, new LineWrite(roses ? 98 : line.liveQuantity(), roses ? "BESCHADIGD" : null,
                    null, line.row().revision, null, null));
        }
        stock.sell(rose, main.id(), 3, "F-TIJDENS");
        counts.book(mainCount, counts.bookingCheck(mainCount).checkToken());
        assertEquals(95, stock.quantityAt(rose, main.id()), "the sale during the count survives: 97 live less the difference of 2");
        count(YEAR1, shelf.id());

        View created = closings.create(YEAR1, null);
        long id = created.closing().id;
        assertEquals(List.of("ZONDER_WAARDE", "BESLISSING_ONDERWEG", "BTW_BEVESTIGING"), blockers(created));
        assertEquals(50, created.closing().unvaluedQuantity);
        assertEquals(98, article(created, rose).closingQuantity);
        assertEquals(3, article(created, rose).rollDelta, "the sale of three after the closing date is added back");
        StockClosingLineEntity counted = created.lines().stream().filter(line -> line.productId == rose).findFirst().orElseThrow();
        assertEquals(100, counted.expectedQuantity);
        assertEquals(98, counted.countedQuantity);
        assertEquals(-2, counted.countDifference);
        assertEquals("BESCHADIGD", counted.countReasonCode);
        assertEquals(1, created.separates().stream().filter(row -> "OUDER".equals(row.kind)).count());
        assertFalse(created.canFinalize());

        /* The opening value: 50 x 1,50. Writing it computes nothing; the screen recomputes. */
        openingLayers.save(new StockOpeningLayerService.Write(OPENING_DAY, OPENING_SOURCE,
                List.of(new StockOpeningLayerService.Row(old, 50, new BigDecimal("1.50"), null))));
        View valued = closings.recompute(id);
        assertEquals(0, valued.closing().unvaluedQuantity);
        assertMoney("213.00", article(valued, old).costValueEur, "60 x 2,30 + 50 x 1,50");
        assertMoney("75.00", article(valued, old).openingEur);
        assertMoney("180.32", article(valued, rose).costValueEur, "98 x 1,84");
        assertMoney("116.18", line(valued, old, main.id()).costValueEur, "213,00 x 60 / 110");
        assertMoney("96.82", line(valued, old, shelf.id()).costValueEur);

        /* Two waardeverminderingen on "Oud", the dearest pieces first: 20 x (2,30 - 2,00), then the other 90 at 1,00. */
        closings.saveDecision(id, writeDown(old, 20, "2.00", "BESCHADIGD", "Dozen ingedeukt"));
        View written = closings.saveDecision(id, writeDown(old, null, "1.00", "TRAAG", "Oude collectie"));
        assertEquals(List.of("20 x 6.00", "40 x 52.00", "50 x 25.00"), writeDownAmounts(written));
        assertMoney("83.00", article(written, old).writeDownEur);
        assertMoney("130.00", article(written, old).ownValueEur);

        /* The container on the water is included from the day the risk passed; the VAT confirmation closes the list. */
        closings.saveDecision(id, transit(transit, true, LocalDate.of(YEAR1, 12, 15), "FOB: risico over bij het laden"));
        View ready = closings.saveDecision(id, vat());
        assertEquals(List.of(), blockers(ready));
        StockClosingEntity totals = ready.closing();
        assertMoney("393.32", totals.costValueEur, "180,32 + 213,00");
        assertMoney("83.00", totals.writeDownEur);
        assertMoney("310.32", totals.ownValueEur);
        assertMoney("0.00", totals.demoValueEur);
        assertMoney("0.00", totals.partnerIncludedEur);
        assertMoney("0.00", totals.partnerExcludedEur);
        assertMoney("1840.00", totals.transitIncludedEur, "1.000 x 1,84");
        assertMoney("0.00", totals.transitExcludedEur);
        assertMoney("0.00", totals.invoicedOutEur);
        assertMoney("2150.32", totals.totalValueEur, "310,32 + 1.840,00");
        assertMoney("0.00", totals.estimatedEur);
        assertEquals(208, totals.ownQuantity, "98 + 110");
        assertEquals(0, totals.unvaluedQuantity);
        assertNotice(ready, "APART_ZONDER_MARKTTOETS", "WARNING", "apart", "Op opgenomen partnercontainers en goederen onderweg"
                + " (€ 1.840,00) is geen lagere marktwaarde ingevoerd.");
        return id;
    }

    /* --------------------------------------------------------------- helpers */

    private void quiet() {
        for (String entity : List.of("StockClosingLineEntity", "StockClosingLayerEntity", "StockClosingLotEntity",
                "StockClosingContainerEntity", "StockClosingArticleEntity", "StockClosingSeparateEntity",
                "StockClosingWriteDownEntity", "StockClosingMovementEntity", "StockClosingDecisionEntity",
                "StockClosingEntity", "StockOpeningLayerEntity", "StockValuationRuleEntity", "StockMovementEntity")) {
            em.createQuery("delete from " + entity).executeUpdate();
        }
        em.createQuery("delete from ActivityLogEntity where entityType = 'STOCK_CLOSING'").executeUpdate();
        em.createQuery("update " + PurchaseOrderEntity.class.getName() + " set status = ?1").setParameter(1, PurchaseOrderStatus.CONCEPT).executeUpdate();
        em.createQuery("update " + SalesOrderEntity.class.getName() + " set status = ?1").setParameter(1, QuoteStatus.CONCEPT).executeUpdate();
        em.createQuery("update StockLevelEntity set quantity = 0").executeUpdate();
        em.createQuery("update StockCountEntity set status = 'GEANNULEERD'").executeUpdate();
        em.createQuery("update ProductEntity set active = false").executeUpdate();
        em.clear();
        supplier = suppliers.save(new Supplier(null, "Flow Co", "CN", "Yiwu", null, null, null, Currency.USD,
                "FOB Ningbo", "Ningbo", 30, null));
    }

    private static Write vat() {
        return new Write(null, "VAT_CONFIRMATION", null, null, null, null, null, null, null, null, true, null, null, null,
                null, null, null, null);
    }

    private static Write transit(long purchaseOrderId, boolean included, LocalDate ownedFrom, String reason) {
        return new Write(null, "TRANSIT", purchaseOrderId, null, null, null, null, null, null, null, included, null, null, null,
                ownedFrom, null, reason, null);
    }

    private static Write writeDown(long productId, Integer quantity, String marketUnit, String reasonCode, String note) {
        return new Write(null, "WRITE_DOWN", null, null, null, productId, null, null, null, null, null, quantity,
                new BigDecimal(marketUnit), null, null, reasonCode, note, null);
    }

    private long product(String name) {
        var product = new ProductEntity();
        product.sku = "FLOW-" + UUID.randomUUID();
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

    /**
     * A container of one product at USD/EUR 0,92. With a receipt day it is received in full and booked
     * into the warehouse today and then dated; without one it stays on the water, shipped on the given day.
     */
    private long container(String name, LocalDate receivedOn, LocalDate shippedOn, long productId, int quantity, String priceUsd) {
        PurchaseOrder created = purchases.create(supplier.id(), new BigDecimal("0.14"), new BigDecimal("0.92"), BigDecimal.ZERO);
        purchases.update(created.id(), created.withReceipt(PurchaseOrderStatus.BESTELD, null, null, false, null,
                List.of(new PurchaseOrderLine(null, productId, quantity, new BigDecimal(priceUsd), Currency.USD, null, null))));
        PurchaseOrderEntity stored = em.find(PurchaseOrderEntity.class, created.id());
        stored.alias = name;
        em.flush(); em.clear();
        if (receivedOn != null) {
            purchases.receive(created.id(), new PurchaseOrderService.Receipt(
                    List.of(new PurchaseOrderService.ReceivedLine(productId, quantity, 0)), true, null, LocalDate.now(), null));
        }
        stored = em.find(PurchaseOrderEntity.class, created.id());
        stored.orderDate = (receivedOn != null ? receivedOn : shippedOn).minusDays(60);
        stored.receivedOn = receivedOn;
        stored.shippedOn = shippedOn;
        if (receivedOn == null) stored.status = PurchaseOrderStatus.ONDERWEG;
        em.flush(); em.clear();
        return created.id();
    }

    /** A container received in full and paid in euro: the unit value is its price at 0,92. */
    private long simpleContainer(String name, long productId, int quantity, String priceUsd, LocalDate receivedOn) {
        long id = container(name, receivedOn, receivedOn.minusDays(40), productId, quantity, priceUsd);
        pay(id, receivedOn.minusDays(30), new BigDecimal(priceUsd).multiply(new BigDecimal("0.92")).multiply(BigDecimal.valueOf(quantity)),
                Currency.EUR);
        return id;
    }

    private void pay(long orderId, LocalDate paidOn, BigDecimal amount, Currency currency) {
        purchases.addPayment(orderId, paidOn, amount, currency, "leverancier", Payee.SUPPLIER, false, null, null);
        em.flush(); em.clear();
    }

    /** A booked full count of one location, every product at the level of the book. */
    private long count(int year, long locationId) {
        var session = counts.start(year, locationId, null, null);
        long id = session.summary().count().id;
        for (var line : session.lines()) {
            if (line.liveQuantity() == 0) continue;
            counts.saveLine(id, line.row().id, new LineWrite(line.liveQuantity(), null, null, line.row().revision, null, null));
        }
        counts.book(id, counts.bookingCheck(id).checkToken());
        em.flush();
        return id;
    }

    /** An issued Belgian invoice for one product that is not afgepunt. */
    private SalesOrder invoice(long productId, int quantity, LocalDate orderDate) {
        var customer = customers.create(new Customer(null, "Klant " + UUID.randomUUID(), "Buyer", "flow@example.invalid", null,
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

    private ActivityLogEntity activity(String action, long closingId) {
        return em.createQuery("from ActivityLogEntity where action = ?1 and entityId = ?2", ActivityLogEntity.class)
                .setParameter(1, action).setParameter(2, String.valueOf(closingId)).getSingleResult();
    }

    /** The final closing as the screen receives it. */
    private String shownAsJson(long closingId) throws JsonProcessingException {
        em.flush(); em.clear();
        return json.writeValueAsString(resource.get(closingId));
    }

    /** What a decision says, without its id and its closing. */
    private static String content(StockClosingDecisionEntity decision) {
        return String.join("|", decision.kind, Objects.toString(decision.purchaseOrderId), Objects.toString(decision.productId),
                Objects.toString(decision.choice), Objects.toString(decision.flag), Objects.toString(decision.quantity),
                Objects.toString(decision.unitValueEur), Objects.toString(decision.decisionDate), Objects.toString(decision.reasonCode),
                Objects.toString(decision.reason), decision.decidedBy, decision.decidedByName, Objects.toString(decision.decidedAt));
    }

    /** The waardevermindering rows of "Oud" in the order they were processed: pieces and amount per layer. */
    private List<String> writeDownAmounts(View view) {
        return view.writeDowns().stream().filter(row -> row.productId == old)
                .map(row -> row.quantity + " x " + row.amountEur.setScale(2).toPlainString()).toList();
    }

    private static List<String> unsuperseded(List<String> closingRow) {
        return closingRow.stream().map(row -> row.replaceAll("supersededById=[^;]*;", "").replaceAll("supersededAt=[^;]*;", "")).toList();
    }

    private static String text(byte[] pdf) {
        try (PDDocument document = Loader.loadPDF(pdf)) {
            return new PDFTextStripper().getText(document);
        } catch (IOException unreadable) {
            throw new UncheckedIOException(unreadable);
        }
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

    private static Notice notice(View view, String code) {
        return view.notices().stream().filter(notice -> code.equals(notice.code())).findFirst().orElse(null);
    }

    private static List<String> blockers(View view) {
        return view.notices().stream().filter(Notice::blocker).map(Notice::code).distinct().toList();
    }

    private static void assertMoney(String expected, BigDecimal actual) {
        assertMoney(expected, actual, "amount");
    }

    private static void assertMoney(String expected, BigDecimal actual, String message) {
        assertNotNull(actual, message);
        assertEquals(0, new BigDecimal(expected).compareTo(actual), message + ": expected " + expected + " but was " + actual);
    }

    private static void assertNotice(View view, String code, String severity, String segment, String message) {
        Notice found = view.notices().stream().filter(notice -> code.equals(notice.code()) && message.equals(notice.message()))
                .findFirst().orElse(null);
        assertNotNull(found, code + " with this text; raised: " + view.notices().stream().filter(notice -> code.equals(notice.code()))
                .map(Notice::message).toList());
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
