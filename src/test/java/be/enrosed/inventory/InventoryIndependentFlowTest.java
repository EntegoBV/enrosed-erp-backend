package be.enrosed.inventory;

import be.enrosed.account.CustomerAccountService;
import be.enrosed.catalog.adapter.out.persistence.ProductEntity;
import be.enrosed.catalog.application.StockService;
import be.enrosed.catalog.domain.StockLocation;
import be.enrosed.catalog.domain.StockMovement;
import be.enrosed.inventory.application.InventoryClock;
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
import be.enrosed.sourcing.adapter.out.persistence.SourcingEntities.PurchaseOrderEntity;
import be.enrosed.sourcing.application.PurchaseOrderService;
import be.enrosed.sourcing.application.PurchaseSupplierCreditService;
import be.enrosed.sourcing.application.PurchaseSupplierCreditService.CreditRequest;
import be.enrosed.sourcing.application.SupplierService;
import be.enrosed.sourcing.domain.PurchaseOrder;
import be.enrosed.sourcing.domain.PurchaseOrderLine;
import be.enrosed.sourcing.domain.PurchaseOrderStatus;
import be.enrosed.sourcing.domain.PurchasePayment.Payee;
import be.enrosed.sourcing.domain.PurchaseSupplierCredit.Reason;
import be.enrosed.sourcing.domain.Supplier;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The year-end inventory walked from the first count to a corrected second version, over
 * real HTTP with the staff login, written by a reviewer who had not read the builders' flow test.
 *
 * THE BOOK. Closing year 2025, closing date 31/12/2025, counted today (after the closing date).
 * Every rate: USD/EUR 0,90 for goods and transport. Default import duty 4 %. Enrosed kost
 * EUR 2.000,00 on every container; it may be in no figure below.
 *
 * Container 1, received 10/10/2025, product ROOS: 1.000 ordered, 1.000 received, none damaged, US$ 2,00.
 *   Leverancier: Afspraak 1.000 x 2,00 x 0,90 = 1.800,00.
 *     aanbetaling US$ 600 on 01/09/2025, bank euro 546,00: before the purchase day, kept     546,00
 *     saldo US$ 1.400 on 05/11/2025, bank euro 1.274,00: after it, 1.400 x 0,90            1.260,00
 *     (exchange difference 14,00, outside the value); open 1.800 - (540 + 1.260) = 0      = 1.806,00
 *     prijstegoed US$ 100 = 90,00 lowers the goods: (1.806,00 - 90,00) / 1.000              1,7160
 *   Douane & transport: freight US$ 500 = 450,00; customs value 1.800 + 450 = 2.250,00; duty 4 % =
 *     90,00; arrival 300,00. Afspraak 840,00, paid 840,00 in euro. 840,00 / 1.000           0,8400
 *   Inspectie: 100,00 paid. 100,00 / 1.000                                                   0,1000
 *   Bankkost 25,00 under Bijkomende kosten: not in the value.
 *   UNIT VALUE 1,7160 + 0,8400 + 0,1000 = 2,6560, nothing estimated. Capacity 1.000.
 *
 * Container 2, received 20/11/2025, product ROOS: 600 ordered, 590 received, 20 damaged, US$ 2,50.
 *   Leverancier: Afspraak 600 x 2,50 x 0,90 = 1.350,00. US$ 1.500 on 01/11/2025, bank euro 1.340,00:
 *     before the purchase day, kept. Open 0. Goods 1.340,00 / max(600, 590)                  2,2333
 *   Douane & transport: freight US$ 600 = 540,00; customs value on the ordered pieces
 *     1.350 + 540 = 1.890,00; duty 4 % = 75,60; arrival 360,00. Afspraak 975,60, paid 500,00,
 *     OPEN 475,60 (estimated). 975,60 / 590                                                  1,6536
 *     of which estimated 475,60 / 590                                                        0,8061
 *   Inspectie: 118,00 paid. 118,00 / 590                                                     0,2000
 *   UNIT VALUE 2,2333 + 1,6536 + 0,2000 = 4,0869, of which 0,8061 estimated. Capacity 590 - 20 = 570.
 *
 * Container 3, on the water (ordered 01/12/2025, sailed 15/12/2025), product ROOS: 400 x US$ 2,00.
 *   Leverancier: Afspraak 720,00. Aanbetaling US$ 240 on 10/12/2025, bank euro 215,00, kept. Open
 *     720,00 - 216,00 = 504,00 (estimated). 215,00 + 504,00 = 719,00; 719,00 / 400 = 1,7975.
 *   Nothing of Douane & transport was paid by the closing date. VALUE 400 x 1,7975 = 719,00, of
 *   which 504,00 estimated. Counted only once the user says ownership passed.
 *
 * Product LOOS: 40 pieces in the warehouse, on no container. Opening value 40 x 1,25 = 50,00.
 *
 * QUANTITY of ROOS on 31/12/2025.
 *   Warehouse book: +1.000 +570 -700 (invoice A, left 06/12) -100 (to the shop) = 770 at year end in
 *   the book; then -8 (invoice E of 28/12, afgepunt 03/01), -30 (invoice B of 05/01) = 732 at the count.
 *   Counted 727 (5 not found). While the count is open invoice D leaves with 12: live 720. Booking
 *   applies the difference only: 720 - 5 = 715.
 *   Roll back to the closing date: 715 + 12 (D, after the closing date) + 30 (B, after the closing
 *   date) = 757. The 8 of E had left on 29/12, so that row does not count (a decision with a reason).
 *   Shop: 100, counted 100. Q = 857.
 *
 * FIFO, newest first: container 2 gives 570, container 1 the other 287.
 *   Invoice C (20/12/2025, 20 pieces) is not afgepunt and the pieces lay there: "uit eigen voorraad",
 *   carved from the oldest layer: container 1 keeps 267, 20 x 2,6560 = 53,12 is shown apart.
 *     570 x 4,0869 = 2.329,533 -> 2.329,53
 *     267 x 2,6560 =   709,152 ->   709,15
 *     ROOS aanschafwaarde          3.038,68   (837 pieces)
 *     LOOS beginwaarde                50,00   (40 pieces)
 *     AANSCHAFWAARDE               3.088,68   (877 pieces)
 *   Waardevermindering: 15 pieces "beschadigd" at a market value of 1,00, highest unit value first
 *     15 x (4,0869 - 1,00) = 46,3035 -> 46,30
 *     EIGEN VOORRAAD               3.042,38
 *   Goederen onderweg, opgenomen     719,00
 *     TOTAAL                       3.761,38
 *   Of which estimated: 570 x 0,8061 = 459,477 -> 459,48, plus 504,00 = 963,48.
 *
 * VERSION 2 (after the final closing): arrival costs of container 2 go from 360,00 to 500,00 and
 * 475,60 is paid; the opening value of LOOS is replaced by 1,30; 3 pieces are found back.
 *   Container 2: Afspraak 540 + 75,60 + 500 = 1.115,60, paid 975,60, open 140,00.
 *     1.115,60 / 590 = 1,8908; estimated 140,00 / 590 = 0,2373. Unit value 4,3241.
 *   Warehouse: correction count 715 + 3 = 718; + 12 (the row of D; the row of B was deleted from the
 *   ledger after version 1 and counts for nothing: it stays listed as removed and D keeps its own 12)
 *   = 730. Q = 830: 570 + 260, of which 20 invoiced out.
 *     570 x 4,3241 = 2.464,737 -> 2.464,74
 *     240 x 2,6560 =               637,44
 *     40 x 1,30    =                52,00
 *     AANSCHAFWAARDE             3.154,18
 *     15 x (4,3241 - 1,00) = 49,8615 -> 49,86
 *     EIGEN VOORRAAD             3.104,32
 *     onderweg                     719,00
 *     TOTAAL                     3.823,32   estimated 570 x 0,2373 = 135,26 + 504,00 = 639,26
 *   Then the forwarder's final invoice is confirmed: 120,00 still owed instead of 140,00.
 *     975,60 + 120,00 = 1.095,60; / 590 = 1,8569. Unit value 4,2902, nothing of it estimated.
 *     570 x 4,2902 = 2.445,414 -> 2.445,41, + 637,44 + 52,00 = AANSCHAFWAARDE 3.134,85
 *     15 x (4,2902 - 1,00) = 49,353 -> 49,35; EIGEN VOORRAAD 3.085,50; onderweg 719,00
 *     TOTAAL VERSIE 2            3.804,50   estimated 504,00
 */
@QuarkusTest
@TestProfile(InventoryIndependentFlowTest.OwnBook.class)
class InventoryIndependentFlowTest {

    /** A book of its own, so every total of the closing is this test's and nobody else's. */
    public static class OwnBook implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("quarkus.datasource.jdbc.url", "jdbc:h2:mem:inventory-independent-flow;DB_CLOSE_DELAY=-1");
        }
    }

    private static final String STAFF = "emre";
    private static final String STAFF_PASSWORD = "named-auth-test-password";
    private static final String COUNTS = "/api/stock-counts";
    private static final String CLOSINGS = "/api/stock-closings";
    private static final String OPENING = "/api/stock-opening-layers";
    private static final String RULE = "/api/stock-valuation-rule";
    private static final int YEAR = 2025;
    private static final ObjectMapper JSON = new ObjectMapper().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    private static final Path DUMP = Path.of("target", "inventory-independent-flow");

    @Inject PurchaseOrderService orders;
    @Inject PurchaseSupplierCreditService credits;
    @Inject SupplierService suppliers;
    @Inject StockService stock;
    @Inject SalesOrderService sales;
    @Inject CustomerService customers;
    @Inject CustomerAccountService accounts;
    @Inject EntityManager em;

    private final Instant started = Instant.now();
    private final List<String> problems = new ArrayList<>();
    private long customerId;

    @Test
    void theYearIsCountedClosedFrozenAndCorrected() throws Exception {
        assertTrue(InventoryClock.today().isAfter(LocalDate.of(YEAR, 12, 31)), "the closing year lies behind us");
        Files.createDirectories(DUMP);

        /* ------------------------------------------------------------------ the book */
        Supplier supplier = suppliers.save(new Supplier(null, "Flow Roses Co", "CN", "Yiwu", null, null, null, Currency.USD,
                "FOB", "Ningbo", 30, null));
        long rose = product(supplier.id(), "FLOW-ROOS", "Roos in stolp rood");
        long loose = product(null, "FLOW-LOOS", "Losse roos zonder container");
        customerId = customers.create(new Customer(null, "Flow Bloemen BV", "An Peeters", "flow@example.invalid", null,
                "BE0000000000", "BE", Language.NL, "Bloemenlaan 5", "2000", "Antwerpen", "DAP", null, null, LocalDate.now())).id();
        long warehouse = stock.mainLocation().id();
        long shop = stock.saveLocation(new StockLocation(null, null, "Winkel Flow", StockLocation.Kind.SALES_POINT, null,
                true, false, false, 9)).id();

        long first = container(supplier.id(), rose, 1000, "2.00", "500", "300", "100", LocalDate.of(2025, 8, 20));
        orders.addPayment(first, LocalDate.of(2025, 9, 1), new BigDecimal("600"), Currency.USD, "Aanbetaling",
                Payee.SUPPLIER, false, null, new BigDecimal("546.00"));
        orders.receive(first, new PurchaseOrderService.Receipt(List.of(new PurchaseOrderService.ReceivedLine(rose, 1000, 0)),
                true, null, LocalDate.of(2025, 10, 10), null));
        backdate("2025-10-10T09:00:00Z");
        orders.addPayment(first, LocalDate.of(2025, 11, 5), new BigDecimal("1400"), Currency.USD, "Saldo",
                Payee.SUPPLIER, false, null, new BigDecimal("1274.00"));
        orders.addPayment(first, LocalDate.of(2025, 10, 20), new BigDecimal("840"), Currency.EUR, "Expediteur", Payee.LOGISTICS, false, null, null);
        orders.addPayment(first, LocalDate.of(2025, 9, 15), new BigDecimal("100"), Currency.EUR, "Inspectie", Payee.SEPARATE, false, null, null);
        orders.addPayment(first, LocalDate.of(2025, 11, 5), new BigDecimal("25"), Currency.EUR, "Bankkost", Payee.OTHER, false, null, null);
        credits.add(first, new CreditRequest(LocalDate.of(2025, 11, 1), new BigDecimal("100"), Currency.USD, null,
                Reason.PRICE, "Prijsverschil"));

        long second = container(supplier.id(), rose, 600, "2.50", "600", "360", "118", LocalDate.of(2025, 10, 1));
        orders.addPayment(second, LocalDate.of(2025, 11, 1), new BigDecimal("1500"), Currency.USD, "Volledig",
                Payee.SUPPLIER, false, null, new BigDecimal("1340.00"));
        orders.receive(second, new PurchaseOrderService.Receipt(List.of(new PurchaseOrderService.ReceivedLine(rose, 590, 20)),
                true, null, LocalDate.of(2025, 11, 20), null));
        backdate("2025-11-20T09:00:00Z");
        orders.addPayment(second, LocalDate.of(2025, 11, 25), new BigDecimal("500"), Currency.EUR, "Expediteur deel 1", Payee.LOGISTICS, false, null, null);
        orders.addPayment(second, LocalDate.of(2025, 11, 10), new BigDecimal("118"), Currency.EUR, "Inspectie", Payee.SEPARATE, false, null, null);

        long third = container(supplier.id(), rose, 400, "2.00", "400", "200", "0", LocalDate.of(2025, 12, 1));
        orders.addPayment(third, LocalDate.of(2025, 12, 10), new BigDecimal("240"), Currency.USD, "Aanbetaling",
                Payee.SUPPLIER, false, null, new BigDecimal("215.00"));
        tx(() -> {
            PurchaseOrderEntity sailing = em.find(PurchaseOrderEntity.class, third);
            sailing.status = PurchaseOrderStatus.ONDERWEG;
            sailing.shippedOn = LocalDate.of(2025, 12, 15);
        });

        SalesOrder invoiceA = invoice(rose, 700, LocalDate.of(2025, 12, 5));
        ship(invoiceA, "2025-12-06T10:00:00Z");
        stock.transfer(rose, warehouse, shop, 100, "naar de winkel");
        backdate("2025-12-08T10:00:00Z");
        stock.setLevel(loose, warehouse, 40, StockMovement.Kind.MANUAL_CORRECTION, "Beginstand");
        backdate("2025-12-09T10:00:00Z");
        SalesOrder invoiceC = invoice(rose, 20, LocalDate.of(2025, 12, 20));           // never afgepunt
        SalesOrder invoiceE = invoice(rose, 8, LocalDate.of(2025, 12, 28));
        ship(invoiceE, "2026-01-03T10:00:00Z");                                         // left 29/12, booked late
        SalesOrder invoiceB = invoice(rose, 30, LocalDate.of(2026, 1, 5));
        ship(invoiceB, "2026-01-05T10:00:00Z");
        check("warehouse book before the count", 732, level(rose, warehouse));
        check("shop book before the count", 100, level(rose, shop));

        /* ------------------------------------------------------------------ (a) the count, on a phone */
        JsonNode session = call("POST", COUNTS, Map.of("countYear", YEAR, "locationId", warehouse, "note", "Jaartelling magazijn"), 201);
        long count = session.path("id").asLong();
        check("count status", "OPEN", session.path("status").asText());
        check("a full count", true, session.path("correctsCountId").isNull());
        check("the open invoice is announced", List.of(invoiceC.number()), texts(session.at("/warnings/unshippedInvoices"), "number"));
        JsonNode roseLine = one(session.path("lines"), "productId", rose);
        JsonNode looseLine = one(session.path("lines"), "productId", loose);
        check("lines of the session", 2, session.path("lines").size());
        check("live level on the line", 732, roseLine.path("liveQuantity").asInt());
        refusal(call("POST", COUNTS, Map.of("countYear", YEAR, "locationId", warehouse), 409), "TELLING_LOOPT");

        String line = COUNTS + "/" + count + "/lines/" + roseLine.path("id").asLong();
        JsonNode counted = call("PUT", line, write(727, null, null, roseLine.path("revision").asInt(), null, null), 200);
        check("expected", 732, counted.path("expectedQuantity").asInt());
        check("difference", -5, counted.path("difference").asInt());
        check("counted by", "Emre", counted.path("countedByName").asText());
        check("the invoice that may explain it", List.of(invoiceC.number()), texts(counted.path("openDocuments"), "number"));
        /* The second phone still holds the old revision. */
        JsonNode stale = call("PUT", line, write(730, null, null, roseLine.path("revision").asInt(), null, null), 409);
        refusal(stale, "REGEL_GEWIJZIGD");
        check("the other phone is shown the count that stands", 727, stale.at("/details/line/countedQuantity").asInt());
        call("PUT", COUNTS + "/" + count + "/lines/" + looseLine.path("id").asLong(),
                write(40, null, null, looseLine.path("revision").asInt(), null, null), 200);

        JsonNode check1 = call("GET", COUNTS + "/" + count + "/booking-check", null, 200);
        check("nothing uncounted", 0, check1.path("uncounted").size());
        check("a reason is missing", List.of(roseLine.path("id").asText()), texts(check1.path("missingReasons"), null));
        refusal(call("POST", COUNTS + "/" + count + "/book", Map.of("checkToken", check1.path("checkToken").asText()), 409), "REDEN_ONTBREEKT");
        invalid(call("PUT", line, write(727, "ANDERS", null, counted.path("revision").asInt(), null, null), 422),
                "Vul bij 'Andere reden' een notitie in");

        /* The reason is chosen later: the difference stays what it was. */
        JsonNode reasoned = call("PUT", line, write(727, "NIET_GEVONDEN", "Doos 14 ontbreekt", counted.path("revision").asInt(), null, null), 200);
        check("expected after the reason", 732, reasoned.path("expectedQuantity").asInt());
        check("difference after the reason", -5, reasoned.path("difference").asInt());
        check("counted at is kept", counted.path("countedAt").asText(), reasoned.path("countedAt").asText());
        JsonNode check2 = call("GET", COUNTS + "/" + count + "/booking-check", null, 200);
        JsonNode first409 = call("POST", COUNTS + "/" + count + "/book", Map.of("checkToken", check2.path("checkToken").asText()), 409);
        refusal(first409, "EERST_AFPUNTEN");
        check("the refusal names the invoice", true, first409.path("message").asText().contains(invoiceC.number()));
        JsonNode confirmed = call("PUT", line, write(727, "NIET_GEVONDEN", "Doos 14 ontbreekt", reasoned.path("revision").asInt(), null, true), 200);
        check("confirmed", true, confirmed.path("documentsConfirmed").asBoolean());
        check("difference after confirming", -5, confirmed.path("difference").asInt());

        /* A sale is afgepunt while the count is open: the review the counter read is no longer true. */
        JsonNode check3 = call("GET", COUNTS + "/" + count + "/booking-check", null, 200);
        check("ready to book", List.of(0, 0, 0, 0, 0), List.of(check3.path("uncounted").size(), check3.path("missingReasons").size(),
                check3.path("openDocuments").size(), check3.path("moved").size(), check3.path("negative").size()));
        SalesOrder invoiceD = invoice(rose, 12, InventoryClock.today());
        sales.shipGoods(invoiceD.id());
        check("the sale went out", 720, level(rose, warehouse));
        refusal(call("POST", COUNTS + "/" + count + "/book", Map.of("checkToken", check3.path("checkToken").asText()), 409), "TELLING_GEWIJZIGD");
        check("nothing was booked by the refused call", 720, level(rose, warehouse));
        JsonNode check4 = call("GET", COUNTS + "/" + count + "/booking-check", null, 200);
        JsonNode moved = one(check4.path("moved"), "productId", rose);
        check("moved: expected", 732, moved.path("expectedQuantity").asInt());
        check("moved: counted", 727, moved.path("countedQuantity").asInt());
        check("moved: live", 720, moved.path("liveQuantity").asInt());
        check("moved: result", 715, moved.path("resultQuantity").asInt());
        check("moved: the row since the count", List.of("-12"), texts(moved.path("movements"), "delta"));
        check("moved: its reference", List.of(invoiceD.number()), texts(moved.path("movements"), "reference"));
        check("summary short", 5, check4.at("/summary/shortUnits").asInt());

        JsonNode booked = call("POST", COUNTS + "/" + count + "/book", Map.of("checkToken", check4.path("checkToken").asText()), 200);
        check("booked", "GEBOEKT", booked.path("status").asText());
        check("booked quantity on the line", 715, one(booked.path("lines"), "productId", rose).path("bookedQuantity").asInt());
        check("the sale during the count was not overwritten", 715, level(rose, warehouse));
        check("the product without a difference stays", 40, level(loose, warehouse));
        List<Object[]> takes = rows("select m.delta, m.quantityAfter, m.reference from StockMovementEntity m"
                + " where m.kind = 'STOCKTAKE' and m.productId = ?1 order by m.id", rose);
        check("one stocktake row for the product", 1, takes.size());
        if (!takes.isEmpty()) {
            check("stocktake delta", -5, takes.get(0)[0]);
            check("stocktake level", 715, takes.get(0)[1]);
            check("stocktake reference", "Jaartelling 2025 #" + count + " · Niet gevonden", takes.get(0)[2]);
        }
        refusal(call("PUT", line, write(700, "TELFOUT", null, confirmed.path("revision").asInt(), null, null), 409), "TELLING_GESLOTEN");
        refusal(call("POST", COUNTS + "/" + count + "/cancel", Map.of(), 409), "TELLING_GESLOTEN");

        JsonNode shopSession = call("POST", COUNTS, Map.of("countYear", YEAR, "locationId", shop), 201);
        long shopCount = shopSession.path("id").asLong();
        JsonNode shopLine = one(shopSession.path("lines"), "productId", rose);
        call("PUT", COUNTS + "/" + shopCount + "/lines/" + shopLine.path("id").asLong(),
                write(100, null, null, shopLine.path("revision").asInt(), null, null), 200);
        refusal(call("POST", COUNTS + "/" + shopCount + "/book", Map.of("checkToken", "x"), 409), "NIET_GETELD|TELLING_GEWIJZIGD");
        /* Every active product has a line; the loose rose has no stock in the shop and needs no count. */
        JsonNode shopCheck = call("GET", COUNTS + "/" + shopCount + "/booking-check", null, 200);
        check("shop: nothing uncounted", 0, shopCheck.path("uncounted").size());
        call("POST", COUNTS + "/" + shopCount + "/book", Map.of("checkToken", shopCheck.path("checkToken").asText()), 200);
        JsonNode overview = call("GET", COUNTS + "?year=" + YEAR, null, 200);
        check("both locations have a booked count", List.of(String.valueOf(count), String.valueOf(shopCount)),
                List.of(one(overview.path("locations"), "locationId", warehouse).at("/booked/id").asText(),
                        one(overview.path("locations"), "locationId", shop).at("/booked/id").asText()));

        /* ------------------------------------------------------------------ (b) the closing */
        check("no rule before the first closing", 204, staff().get(RULE).statusCode());
        JsonNode concept = call("POST", CLOSINGS, Map.of("closingYear", YEAR), 201);
        dump("01-concept-created", concept);
        long closing = concept.path("id").asLong();
        String decisions = CLOSINGS + "/" + closing + "/decisions";
        check("closing date", "2025-12-31", concept.path("closingDate").asText());
        check("cut-off", "2025-12-31T23:00:00Z", concept.path("cutoffAt").asText());
        check("rule", "FIFO_LOT", concept.at("/rule/method").asText());
        check("rule from", YEAR, concept.at("/rule/effectiveFromYear").asInt());
        check("blockers of the first concept",
                List.of("BESLISSING_GEFACTUREERD", "BESLISSING_GEFACTUREERD", "BESLISSING_ONDERWEG", "BTW_BEVESTIGING", "ZONDER_WAARDE"),
                codes(concept, "BLOCKER"));
        check("cannot be made final", false, concept.path("canFinalize").asBoolean());

        /* The roll to the closing date. */
        JsonNode warehouseAt = one(concept.path("locations"), "locationId", warehouse);
        check("warehouse anchored on its count", "TELLING", warehouseAt.path("anchor").asText());
        check("warehouse count id", count, warehouseAt.path("countId").asLong());
        check("counted after the closing date", true, warehouseAt.path("countAfterClosingDate").asBoolean());
        check("shop anchored on its count", "TELLING", one(concept.path("locations"), "locationId", shop).path("anchor").asText());
        JsonNode rowE = one(concept.path("movements"), "reference", invoiceE.number());
        JsonNode rowB = one(concept.path("movements"), "reference", invoiceB.number());
        JsonNode rowD = one(concept.path("movements"), "reference", invoiceD.number());
        check("rows between the closing date and the count", 3, concept.path("movements").size());
        check("E effective", -8, rowE.path("effectiveDelta").asInt());
        check("E counts by default", true, rowE.path("applied").asBoolean());
        check("E asks to be looked at", true, rowE.path("review").asBoolean());
        check("E business date", "2025-12-28", rowE.path("businessDate").asText());
        check("B counts", List.of("-30", "true", "false"), List.of(rowB.path("effectiveDelta").asText(), rowB.path("applied").asText(), rowB.path("review").asText()));
        check("D counts", List.of("-12", "true"), List.of(rowD.path("effectiveDelta").asText(), rowD.path("applied").asText()));
        check("the receipts of 2025 are not rolled", List.of(), texts(concept.path("movements"), "kind").stream().filter("PURCHASE_RECEIPT"::equals).toList());
        JsonNode roseFirst = one(concept.path("articles"), "productId", rose);
        JsonNode roseWarehouse = one(roseFirst.path("locations"), "locationId", warehouse);
        check("anchor quantity", 715, roseWarehouse.path("anchorQuantity").asInt());
        check("roll before the decision", 50, roseWarehouse.path("rollDelta").asInt());
        check("closing quantity before the decision", 765, roseWarehouse.path("closingQuantity").asInt());
        check("count facts on the closing", List.of("732", "727", "-5", "NIET_GEVONDEN"), List.of(roseWarehouse.path("expectedQuantity").asText(),
                roseWarehouse.path("countedQuantity").asText(), roseWarehouse.path("countDifference").asText(), roseWarehouse.path("countReasonCode").asText()));
        check("product closing quantity before the decision", 865, roseFirst.path("closingQuantity").asInt());
        JsonNode looseFirst = one(concept.path("articles"), "productId", loose);
        check("loose rose has no value", List.of("ZONDER_WAARDE", "40", "0"), List.of(looseFirst.path("status").asText(),
                looseFirst.path("unvaluedQuantity").asText(), looseFirst.path("ownQuantity").asText()));
        money("loose rose is worth nothing yet", "0.00", looseFirst.path("costValueEur"));
        /* Undecided invoices stay own stock in the concept: 570 x 4,0869 + 295 x 2,6560 = 2.329,53 + 783,52. */
        money("concept cost before any decision", "3113.05", concept.at("/totals/costValueEur"));
        money("transit outside the total while undecided", "719.00", concept.at("/totals/transitExcludedEur"));
        money("transit inside the total while undecided", "0.00", concept.at("/totals/transitIncludedEur"));
        money("total before any decision", "3113.05", concept.at("/totals/totalValueEur"));
        check("unvalued", 40, concept.at("/totals/unvaluedQuantity").asInt());

        /* The lots: real and estimated parts, and what stays outside. */
        JsonNode c1 = container(concept, first, "EIGEN");
        JsonNode c2 = container(concept, second, "EIGEN");
        JsonNode c3 = container(concept, third, "ONDERWEG");
        JsonNode lot1 = one(c1.path("lots"), "productId", rose);
        JsonNode lot2 = one(c2.path("lots"), "productId", rose);
        JsonNode lot3 = one(c3.path("lots"), "productId", rose);
        check("container 1 purchase day", List.of("2025-10-10", "ONTVANGST"), List.of(c1.path("rateCutoffDate").asText(), c1.path("rateCutoffSource").asText()));
        stream(c1, "SUPPLIER", "1800.00", "1806.00", "0.00", "1806.00", "0.00", "WERKELIJK");
        stream(c1, "LOGISTICS", "840.00", "840.00", "0.00", "840.00", "0.00", "WERKELIJK");
        stream(c1, "SEPARATE", "100.00", "100.00", "0.00", "100.00", "0.00", "WERKELIJK");
        money("c1 enrosed kost outside", "2000.00", c1.path("enrosedCostExcludedEur"));
        money("c1 bijkomende kosten outside", "25.00", c1.path("otherExcludedEur"));
        money("c1 exchange difference outside", "14.00", c1.path("exchangeDifferenceEur"));
        money("c1 price credit", "90.00", c1.path("priceCreditEur"));
        money("c1 acquisition", "2656.00", c1.path("acquisitionEur"));
        check("c1 credit treatment", List.of("VERLAAGT"), texts(c1.path("credits"), "treatment"));
        money("c1 unit goods", "1.7160", lot1.path("unitGoodsEur"));
        money("c1 unit logistics", "0.8400", lot1.path("unitLogisticsEur"));
        money("c1 unit separate", "0.1000", lot1.path("unitSeparateEur"));
        money("c1 unit value", "2.6560", lot1.path("unitValueEur"));
        money("c1 unit estimated", "0.0000", lot1.path("unitEstimatedEur"));
        check("c1 capacity", 1000, lot1.path("capacity").asInt());
        JsonNode balance = one(c1.path("payments"), "label", "Saldo");
        money("the late foreign payment counts at the container rate", "1260.00", balance.path("countedEur"));
        money("its bank euro is kept next to it", "1274.00", balance.path("storedEur"));
        check("bank cost is not in the value", false, one(c1.path("payments"), "label", "Bankkost").path("inValue").asBoolean());

        stream(c2, "SUPPLIER", "1350.00", "1340.00", "0.00", "1340.00", "0.00", "WERKELIJK");
        stream(c2, "LOGISTICS", "975.60", "500.00", "475.60", "975.60", "475.60", "GESCHAT");
        stream(c2, "SEPARATE", "118.00", "118.00", "0.00", "118.00", "0.00", "WERKELIJK");
        money("c2 enrosed kost outside", "2000.00", c2.path("enrosedCostExcludedEur"));
        money("c2 acquisition", "2433.60", c2.path("acquisitionEur"));
        money("c2 estimated", "475.60", c2.path("estimatedEur"));
        check("c2 lot status", "TEKORT", lot2.path("status").asText());
        check("c2 quantities", List.of("600", "590", "20", "600", "590", "570"), List.of(lot2.path("orderedQuantity").asText(),
                lot2.path("receivedQuantity").asText(), lot2.path("damagedQuantity").asText(), lot2.path("goodsDivisor").asText(),
                lot2.path("costDivisor").asText(), lot2.path("capacity").asText()));
        money("c2 unit goods", "2.2333", lot2.path("unitGoodsEur"));
        money("c2 unit logistics", "1.6536", lot2.path("unitLogisticsEur"));
        money("c2 unit separate", "0.2000", lot2.path("unitSeparateEur"));
        money("c2 unit value", "4.0869", lot2.path("unitValueEur"));
        money("c2 unit estimated", "0.8061", lot2.path("unitEstimatedEur"));
        /* For information only: 975,60 spread like the calculation on received pieces (freight 540,00, duty 4 % of
           590 x 2,25 + 540 = 74,70, arrival 360,00). */
        money("c2 duty, for information", "74.77", lot2.path("calcDutyEur"));
        money("c2 freight, for information", "540.50", lot2.path("calcFreightEur"));
        money("c2 arrival, for information", "360.33", lot2.path("calcDestinationEur"));
        /* 10 pieces never arrived: 10 x 2,2333 = 22,33; 20 arrived broken: 20 x 4,0869 = 81,74. Neither is in the stock. */
        money("c2 missing and damaged, outside", "104.07", c2.path("missingAndDamagedCostEur"));

        check("container 3 while undecided", List.of("2025-12-31", "AFSLUITDATUM", "BESTELD"),
                List.of(c3.path("rateCutoffDate").asText(), c3.path("rateCutoffSource").asText(), c3.path("quantityBasis").asText()));
        money("c3 unit value", "1.7975", lot3.path("unitValueEur"));
        money("c3 unit estimated", "1.2600", lot3.path("unitEstimatedEur"));
        JsonNode transit = one(concept.path("separate"), "kind", "ONDERWEG");
        check("transit row", List.of("400", "FOB"), List.of(transit.path("quantity").asText(), transit.path("supplierIncoterm").asText()));
        money("transit value", "719.00", transit.path("valueEur"));
        money("transit estimated", "504.00", transit.path("estimatedEur"));
        money("transit paid until the closing date", "215.00", transit.path("paidUntilClosingEur"));
        check("warnings name what is estimated and what stays outside", true,
                codes(concept, "WARNING").containsAll(List.of("GESCHAT", "KOERSVERSCHIL", "BIJKOMENDE_KOSTEN", "BEWEGING_NAKIJKEN", "KOERS_INGEVOERD")));

        /* Decisions. The 8 pieces of invoice E had left on 29/12: the row does not count, and the invoice is not asked twice. */
        invalid(call("PUT", decisions, Map.of("kind", "MOVEMENT", "movementId", rowE.path("movementId").asLong(), "flag", false), 422), "Geef een reden");
        JsonNode afterMovement = call("PUT", decisions, Map.of("kind", "MOVEMENT", "movementId", rowE.path("movementId").asLong(),
                "flag", false, "reason", "Vertrokken op 29/12, pas op 03/01 afgepunt"), 200);
        dump("02-after-movement", afterMovement);
        check("closing quantity after the decision", 857, one(afterMovement.path("articles"), "productId", rose).path("closingQuantity").asInt());
        JsonNode separateE = one(afterMovement.path("separate"), "salesOrderId", invoiceE.id());
        check("invoice E is answered by its ledger row", List.of("AL_WEG", "true"), List.of(separateE.path("choice").asText(), separateE.path("automatic").asText()));
        check("one invoice left to decide", List.of("BESLISSING_GEFACTUREERD", "BESLISSING_ONDERWEG", "BTW_BEVESTIGING", "ZONDER_WAARDE"), codes(afterMovement, "BLOCKER"));

        invalid(call("PUT", decisions, Map.of("kind", "INVOICED", "salesOrderId", invoiceC.id()), 422), "Kies wat er met de stuks van deze factuur was");
        JsonNode afterInvoice = call("PUT", decisions, Map.of("kind", "INVOICED", "salesOrderId", invoiceC.id(), "choice", "UIT"), 200);
        money("invoiced, taken out of own stock", "53.12", afterInvoice.at("/totals/invoicedOutEur"));

        invalid(call("PUT", decisions, Map.of("kind", "WRITE_DOWN", "productId", rose, "quantity", 15, "unitValueEur", 1.00,
                "reasonCode", "BESCHADIGD"), 422), "Geef een reden");
        JsonNode afterWriteDown = call("PUT", decisions, Map.of("kind", "WRITE_DOWN", "productId", rose, "quantity", 15,
                "unitValueEur", 1.00, "reasonCode", "BESCHADIGD", "reason", "Stolpen gebarsten bij het stapelen"), 200);
        money("write-down", "46.30", afterWriteDown.at("/totals/writeDownEur"));
        JsonNode afterVat = call("PUT", decisions, Map.of("kind", "VAT_CONFIRMATION", "flag", true), 200);
        check("two blockers left", List.of("BESLISSING_ONDERWEG", "ZONDER_WAARDE"), codes(afterVat, "BLOCKER"));

        /* The product without a valued lot holds the closing until its opening value has a source. */
        JsonNode blocked = call("POST", CLOSINGS + "/" + closing + "/finalize",
                Map.of("dataSha256", afterVat.path("dataSha256").asText(), "signerName", "Emre Yilmaz"), 409);
        refusal(blocked, "GEBLOKKEERD");
        check("what blocks", List.of("BESLISSING_ONDERWEG", "ZONDER_WAARDE"), new ArrayList<>(new TreeSet<>(texts(blocked.at("/details/notices"), "code"))));
        invalid(call("POST", OPENING, Map.of("asOfDate", "2025-01-01", "rows", List.of(Map.of("productId", loose, "quantity", 40, "unitValueEur", 1.25))), 422),
                "Vermeld de bron van deze beginwaarde");
        JsonNode layers = call("POST", OPENING, Map.of("asOfDate", "2025-01-01", "source", "Inventaris 31/12/2024, blad 3",
                "rows", List.of(Map.of("productId", loose, "quantity", 40, "unitValueEur", 1.25))), 201);
        long openingLayer = layers.get(0).path("id").asLong();
        /* Writing the layer computes nothing: the figures the screen holds are stale and may not be frozen. */
        refusal(call("POST", CLOSINGS + "/" + closing + "/finalize",
                Map.of("dataSha256", afterVat.path("dataSha256").asText(), "signerName", "Emre Yilmaz"), 409), "CIJFERS_GEWIJZIGD");
        JsonNode valued = call("POST", CLOSINGS + "/" + closing + "/recompute", Map.of(), 200);
        dump("03-after-opening-layer", valued);
        JsonNode looseValued = one(valued.path("articles"), "productId", loose);
        check("loose rose is valued", List.of("OK", "0", "40"), List.of(looseValued.path("status").asText(),
                looseValued.path("unvaluedQuantity").asText(), looseValued.path("ownQuantity").asText()));
        money("loose rose opening value", "50.00", looseValued.path("openingEur"));
        check("its layer keeps the source", List.of("BEGINWAARDE", "Inventaris 31/12/2024, blad 3"),
                List.of(looseValued.at("/layers/0/source").asText(), looseValued.at("/layers/0/openingSource").asText()));
        check("only the container on the water blocks", List.of("BESLISSING_ONDERWEG"), codes(valued, "BLOCKER"));

        /* The container on the water counts only after the ownership decision. */
        money("not in the total before the decision", "3042.38", valued.at("/totals/totalValueEur"));
        money("transit excluded before the decision", "719.00", valued.at("/totals/transitExcludedEur"));
        refusal(call("POST", CLOSINGS + "/" + closing + "/finalize",
                Map.of("dataSha256", valued.path("dataSha256").asText(), "signerName", "Emre Yilmaz"), 409), "GEBLOKKEERD");
        invalid(call("PUT", decisions, Map.of("kind", "TRANSIT", "purchaseOrderId", third, "flag", true, "reason", "FOB Ningbo"), 422),
                "Geef de datum waarop eigendom of risico overging");
        invalid(call("PUT", decisions, Map.of("kind", "TRANSIT", "purchaseOrderId", third, "flag", true, "decisionDate", "2026-01-02",
                "reason", "FOB Ningbo"), 422), "Die datum moet op of voor de afsluitdatum liggen");
        JsonNode ready = call("PUT", decisions, Map.of("kind", "TRANSIT", "purchaseOrderId", third, "flag", true,
                "decisionDate", "2025-12-15", "reason", "FOB Ningbo: risico over bij het laden op 15/12"), 200);
        dump("04-ready", ready);
        check("container 3 once decided", List.of("2025-12-15", "ONDERWEG"),
                List.of(container(ready, third, "ONDERWEG").path("rateCutoffDate").asText(), container(ready, third, "ONDERWEG").path("rateCutoffSource").asText()));
        check("nothing blocks", List.of(), codes(ready, "BLOCKER"));
        check("can be made final", true, ready.path("canFinalize").asBoolean());
        assertFirstVersion(ready, rose, loose, warehouse, shop, first, second);
        check("goods on the water at cost are named", true, codes(ready, "WARNING").contains("APART_ZONDER_MARKTTOETS"));

        /* The concept files carry the same figures and are not stored. */
        byte[] conceptXlsx = download(CLOSINGS + "/" + closing + "/xlsx", "jaarinventaris-2025-v1-concept.xlsx");
        download(CLOSINGS + "/" + closing + "/pdf", "jaarinventaris-2025-v1-concept.pdf");
        check("the concept workbook holds the total", true, workbookText(conceptXlsx).contains("3761.38"));
        String conceptPdf = pdfText(download(CLOSINGS + "/" + closing + "/pdf", "jaarinventaris-2025-v1-concept.pdf"));
        check("the concept pdf says it is a concept", true, conceptPdf.contains("CONCEPT"));

        /* ------------------------------------------------------------------ (c) definitief maken */
        invalid(call("POST", CLOSINGS + "/" + closing + "/finalize", Map.of("dataSha256", ready.path("dataSha256").asText()), 422),
                "Vul in wie de inventaris ondertekent");
        refusal(call("POST", CLOSINGS + "/" + closing + "/finalize", Map.of("dataSha256", "0".repeat(64), "signerName", "Emre Yilmaz"), 409), "CIJFERS_GEWIJZIGD");
        JsonNode frozen = call("POST", CLOSINGS + "/" + closing + "/finalize",
                Map.of("dataSha256", ready.path("dataSha256").asText(), "signerName", "Emre Yilmaz"), 200);
        dump("05-final", frozen);
        check("final", "DEFINITIEF", frozen.path("status").asText());
        check("signer", "Emre Yilmaz", frozen.path("signerName").asText());
        check("made final by", "Emre", frozen.path("finalizedByName").asText());
        check("the hash is the one the user saw", ready.path("dataSha256").asText(), frozen.path("dataSha256").asText());
        assertFirstVersion(frozen, rose, loose, warehouse, shop, first, second);
        byte[] pdf = download(CLOSINGS + "/" + closing + "/pdf", "jaarinventaris-2025-v1.pdf");
        byte[] xlsx = download(CLOSINGS + "/" + closing + "/xlsx", "jaarinventaris-2025-v1.xlsx");
        check("pdf hash on the closing", sha256(pdf), frozen.path("pdfSha256").asText());
        check("workbook hash on the closing", sha256(xlsx), frozen.path("xlsxSha256").asText());
        check("a pdf", "%PDF", new String(pdf, 0, 4, StandardCharsets.ISO_8859_1));
        check("the stored workbook holds the total", true, workbookText(xlsx).contains("3761.38"));
        String paper = pdfText(pdf);
        Files.writeString(DUMP.resolve("05-final.pdf.txt"), paper);
        Files.write(DUMP.resolve("05-final.xlsx"), xlsx);
        check("the pdf is no concept", false, paper.contains("CONCEPT"));
        check("the pdf prints the total", true, paper.contains("3.761,38"));
        check("the pdf prints the signer", true, paper.contains("Emre Yilmaz"));
        check("the pdf prints the data hash", true, paper.replaceAll("\\s", "").contains(frozen.path("dataSha256").asText()));
        check("the pdf prints the workbook hash", true, paper.replaceAll("\\s", "").contains(frozen.path("xlsxSha256").asText()));
        Object[] storedFiles = rows("select c.pdfStorageKey, c.pdfSizeBytes, c.xlsxStorageKey, c.xlsxSizeBytes, c.status, c.totalValueEur"
                + " from StockClosingEntity c where c.id = ?1", closing).get(0);
        check("pdf stored", List.of(true, (long) pdf.length), List.of(storedFiles[0] != null, storedFiles[1]));
        check("workbook stored", List.of(true, (long) xlsx.length), List.of(storedFiles[2] != null, storedFiles[3]));
        check("total on the row", 0, new BigDecimal("3761.38").compareTo((BigDecimal) storedFiles[5]));
        JsonNode list = call("GET", CLOSINGS, null, 200);
        JsonNode listed = one(list.path("closings"), "id", closing);
        check("listed as final with files", List.of("DEFINITIEF", "true", "false"),
                List.of(listed.path("status").asText(), listed.path("hasFiles").asText(), listed.path("superseded").asText()));
        money("listed total", "3761.38", listed.path("totalValueEur"));
        String frozenRead = staff().get(CLOSINGS + "/" + closing).then().statusCode(200).extract().asString();
        check("reading it back gives what the finalize answered", frozen, JSON.readTree(frozenRead));

        /* ------------------------------------------------------------------ (d) the book moves on */
        Response orderRead = staff().get("/api/purchase-orders/" + second);
        assertEquals(200, orderRead.statusCode(), orderRead.asString());
        ObjectNode changedOrder = (ObjectNode) JSON.readTree(orderRead.asString()).path("order");
        changedOrder.put("destinationCostsEur", new BigDecimal("500.00"));
        Response orderSaved = staff().body(changedOrder.toString()).put("/api/purchase-orders/" + second);
        assertEquals(200, orderSaved.statusCode(), orderSaved.asString());
        check("arrival costs changed", 0, new BigDecimal("500").compareTo(orders.get(second).destinationCostsEur()));
        Response paid = staff().body(Map.of("paidOn", InventoryClock.today().toString(), "amount", 475.60, "currency", "EUR",
                "label", "Expediteur saldo", "payee", "LOGISTICS")).post("/api/purchase-orders/" + second + "/payments");
        assertTrue(paid.statusCode() == 200 || paid.statusCode() == 201, paid.asString());
        long deletedRow = rowB.path("movementId").asLong();
        assertEquals(204, staff().delete("/api/products/" + rose + "/stock-movements/" + deletedRow).statusCode());
        assertEquals(204, staff().delete(OPENING + "/" + openingLayer).statusCode());
        refusal(call("POST", COUNTS, Map.of("countYear", YEAR, "locationId", warehouse, "correctsCountId", shopCount), 409), "GEEN_TELLING_OM_TE_CORRIGEREN");
        JsonNode correction = call("POST", COUNTS, Map.of("countYear", YEAR, "locationId", warehouse, "correctsCountId", count,
                "note", "Doos teruggevonden"), 201);
        long correctionId = correction.path("id").asLong();
        check("a correction starts without lines", 0, correction.path("lines").size());
        JsonNode added = call("POST", COUNTS + "/" + correctionId + "/lines", Map.of("productId", rose), 201);
        call("PUT", COUNTS + "/" + correctionId + "/lines/" + added.path("id").asLong(),
                write(718, "TERUGGEVONDEN", "3 stuks achter het rek", added.path("revision").asInt(), null, null), 200);
        JsonNode correctionCheck = call("GET", COUNTS + "/" + correctionId + "/booking-check", null, 200);
        check("a correction asks for no other product", 0, correctionCheck.path("uncounted").size());
        call("POST", COUNTS + "/" + correctionId + "/book", Map.of("checkToken", correctionCheck.path("checkToken").asText()), 200);
        check("warehouse after the correction", 718, level(rose, warehouse));

        /* Every write on the final closing is refused. */
        refusal(call("POST", CLOSINGS + "/" + closing + "/recompute", Map.of(), 409), "DEFINITIEF");
        refusal(call("PUT", CLOSINGS + "/" + closing, Map.of("closingDate", "2025-12-30"), 409), "DEFINITIEF");
        refusal(call("PUT", decisions, Map.of("kind", "WRITE_DOWN", "productId", rose, "quantity", 1, "unitValueEur", 0,
                "reasonCode", "MARKT", "reason", "nadien"), 409), "DEFINITIEF");
        refusal(call("DELETE", decisions + "/" + frozen.at("/decisions/0/id").asLong(), null, 409), "DEFINITIEF");
        refusal(call("DELETE", CLOSINGS + "/" + closing, null, 409), "DEFINITIEF");
        refusal(call("POST", CLOSINGS + "/" + closing + "/finalize",
                Map.of("dataSha256", frozen.path("dataSha256").asText(), "signerName", "Iemand anders"), 409), "DEFINITIEF");
        refusal(call("POST", CLOSINGS, Map.of("closingYear", YEAR), 409), "BESTAAT_AL");

        /* And it is what it was: the view, the hash and both files, byte for byte. */
        String afterChanges = staff().get(CLOSINGS + "/" + closing).then().statusCode(200).extract().asString();
        dump("06-final-after-changes", JSON.readTree(afterChanges));
        check("the final closing reads byte for byte as before", frozenRead, afterChanges);
        assertArrayEquals(pdf, download(CLOSINGS + "/" + closing + "/pdf", "jaarinventaris-2025-v1.pdf"), "the stored pdf");
        assertArrayEquals(xlsx, download(CLOSINGS + "/" + closing + "/xlsx", "jaarinventaris-2025-v1.xlsx"), "the stored workbook");

        /* ------------------------------------------------------------------ (e) a correction is a new version */
        invalid(call("POST", CLOSINGS + "/" + closing + "/versions", Map.of(), 422), "Geef de reden van de correctie");
        JsonNode second2 = call("POST", CLOSINGS + "/" + closing + "/versions", Map.of("reason", "Eindfactuur expediteur en teruggevonden doos"), 201);
        dump("07-version-2-created", second2);
        long version2 = second2.path("id").asLong();
        check("version 2", List.of("2", "CONCEPT", String.valueOf(closing), "Eindfactuur expediteur en teruggevonden doos"),
                List.of(second2.path("versionNo").asText(), second2.path("status").asText(), second2.path("supersedesId").asText(),
                        second2.path("correctionReason").asText()));
        refusal(call("POST", CLOSINGS + "/" + closing + "/versions", Map.of("reason", "nog eens"), 409), "CONCEPT_BESTAAT");
        check("the decisions came along", texts(frozen.path("decisions"), "kind"), texts(second2.path("decisions"), "kind"));
        check("with who decided and when", texts(frozen.path("decisions"), "decidedAt"), texts(second2.path("decisions"), "decidedAt"));
        check("the retired opening value blocks again", List.of("ZONDER_WAARDE"), codes(second2, "BLOCKER"));
        call("POST", OPENING, Map.of("asOfDate", "2025-01-01", "source", "Inventaris 31/12/2024, blad 3 (verbeterd)",
                "rows", List.of(Map.of("productId", loose, "quantity", 40, "unitValueEur", 1.30))), 201);
        JsonNode corrected = call("POST", CLOSINGS + "/" + version2 + "/recompute", Map.of(), 200);
        dump("08-version-2-ready", corrected);
        check("nothing blocks version 2", List.of(), codes(corrected, "BLOCKER"));
        JsonNode c2After = container(corrected, second, "EIGEN");
        stream(c2After, "LOGISTICS", "1115.60", "975.60", "140.00", "1115.60", "140.00", "GESCHAT");
        money("v2 container 2 unit value", "4.3241", one(c2After.path("lots"), "productId", rose).path("unitValueEur"));
        money("v2 container 2 unit estimated", "0.2373", one(c2After.path("lots"), "productId", rose).path("unitEstimatedEur"));
        JsonNode roseAfter = one(corrected.path("articles"), "productId", rose);
        JsonNode roseWarehouseAfter = one(roseAfter.path("locations"), "locationId", warehouse);
        check("v2 warehouse", List.of("718", "12", "730"), List.of(roseWarehouseAfter.path("anchorQuantity").asText(),
                roseWarehouseAfter.path("rollDelta").asText(), roseWarehouseAfter.path("closingQuantity").asText()));
        check("v2 closing quantity", 830, roseAfter.path("closingQuantity").asInt());
        money("v2 aanschafwaarde", "3154.18", corrected.at("/totals/costValueEur"));
        money("v2 waardevermindering", "49.86", corrected.at("/totals/writeDownEur"));
        money("v2 eigen voorraad", "3104.32", corrected.at("/totals/ownValueEur"));
        money("v2 onderweg", "719.00", corrected.at("/totals/transitIncludedEur"));
        money("v2 totaal", "3823.32", corrected.at("/totals/totalValueEur"));
        money("v2 geschat", "639.26", corrected.at("/totals/estimatedEur"));
        check("v2 own quantity", 850, corrected.at("/totals/ownQuantity").asInt());
        JsonNode changes = corrected.path("versionChanges");
        check("compared with version 1", List.of(String.valueOf(closing), "1"), List.of(changes.path("againstClosingId").asText(), changes.path("againstVersionNo").asText()));
        money("changes: total before", "3761.38", changes.path("totalBeforeEur"));
        money("changes: total after", "3823.32", changes.path("totalAfterEur"));
        JsonNode roseChange = one(changes.path("articles"), "productId", rose);
        check("changes: quantity", List.of("857", "830"), List.of(roseChange.path("quantityBefore").asText(), roseChange.path("quantityAfter").asText()));
        money("changes: cost before", "3038.68", roseChange.path("costValueBeforeEur"));
        money("changes: cost after", "3102.18", roseChange.path("costValueAfterEur"));
        JsonNode lotChange = one(changes.path("lots"), "purchaseOrderId", second);
        money("changes: lot before", "4.0869", lotChange.path("unitValueBeforeEur"));
        money("changes: lot after", "4.3241", lotChange.path("unitValueAfterEur"));
        check("changes: only that lot", 1, changes.path("lots").size());
        JsonNode goneRow = one(changes.path("movements"), "movementId", deletedRow);
        check("changes: the deleted ledger row", List.of("-30", "true"), List.of(goneRow.path("effectBefore").asText(), String.valueOf(goneRow.path("effectAfter").isNull())));
        check("changes: no other row took its pieces over", 1, changes.path("movements").size());
        JsonNode keptD = one(corrected.path("movements"), "movementId", rowD.path("movementId").asLong());
        check("the next sale keeps its own 12", List.of("-12", "-12", "true", "false"), List.of(keptD.path("delta").asText(),
                keptD.path("effectiveDelta").asText(), keptD.path("applied").asText(), keptD.path("removed").asText()));
        JsonNode goneB = one(corrected.path("movements"), "movementId", deletedRow);
        check("the deleted sale stays listed and counts for nothing", List.of("true", "false", "-30"),
                List.of(goneB.path("removed").asText(), goneB.path("applied").asText(), goneB.path("effectiveDelta").asText()));
        check("changes: both opening values", 2, changes.path("openingLayers").size());
        check("the differences are announced", true, codes(corrected, "WARNING").contains("VERSCHIL_MET_VORIGE_VERSIE"));
        check("the deleted row is named", true, codes(corrected, "WARNING").contains("BEWEGING_VERDWENEN"));

        /* The forwarder's final invoice is in: 120,00 is still owed, not the 140,00 the Afspraak leaves open. */
        invalid(call("PUT", CLOSINGS + "/" + version2 + "/decisions", Map.of("kind", "ACCRUAL", "purchaseOrderId", second,
                "payee", "LOGISTICS", "amountEur", 120.00, "flag", true), 422), "Geef een reden");
        JsonNode accrued = call("PUT", CLOSINGS + "/" + version2 + "/decisions", Map.of("kind", "ACCRUAL", "purchaseOrderId", second,
                "payee", "LOGISTICS", "amountEur", 120.00, "flag", true, "reason", "Eindfactuur expediteur 14/02/2026"), 200);
        dump("08b-version-2-accrued", accrued);
        JsonNode c2Accrued = container(accrued, second, "EIGEN");
        stream(c2Accrued, "LOGISTICS", "1115.60", "975.60", "140.00", "1095.60", "0.00", "BEVESTIGD");
        check("the accrual is applied", List.of("false", "true", "Eindfactuur expediteur 14/02/2026"), List.of(
                one(c2Accrued.path("streams"), "payee", "LOGISTICS").at("/accrual/stale").asText(),
                one(c2Accrued.path("streams"), "payee", "LOGISTICS").at("/accrual/invoiceReceived").asText(),
                one(c2Accrued.path("streams"), "payee", "LOGISTICS").at("/accrual/reason").asText()));
        money("accrued: container 2 unit value", "4.2902", one(c2Accrued.path("lots"), "productId", rose).path("unitValueEur"));
        money("accrued: container 2 unit estimated", "0.0000", one(c2Accrued.path("lots"), "productId", rose).path("unitEstimatedEur"));
        assertSecondVersion(accrued);
        /* A payment after the confirmation changes what is open: the confirmed amount no longer stands on its basis. */
        Response extra = staff().body(Map.of("paidOn", InventoryClock.today().toString(), "amount", 20, "currency", "EUR",
                "label", "Expediteur voorschot", "payee", "LOGISTICS")).post("/api/purchase-orders/" + second + "/payments");
        assertTrue(extra.statusCode() == 200 || extra.statusCode() == 201, extra.asString());
        JsonNode staleAccrual = call("POST", CLOSINGS + "/" + version2 + "/recompute", Map.of(), 200);
        check("a stale confirmation blocks", List.of("GESCHAT_BEDRAG_VEROUDERD"), codes(staleAccrual, "BLOCKER"));
        stream(container(staleAccrual, second, "EIGEN"), "LOGISTICS", "1115.60", "995.60", "120.00", "1115.60", "120.00", "GESCHAT");
        check("the confirmation is shown as stale", true, one(container(staleAccrual, second, "EIGEN").path("streams"), "payee", "LOGISTICS").at("/accrual/stale").asBoolean());
        assertEquals(204, staff().delete("/api/purchase-orders/" + second + "/payments/" + JSON.readTree(extra.asString()).path("id").asLong()).statusCode());
        corrected = call("POST", CLOSINGS + "/" + version2 + "/recompute", Map.of(), 200);
        check("the payment gone, the same figures and the same hash", accrued.path("dataSha256").asText(), corrected.path("dataSha256").asText());
        assertSecondVersion(corrected);

        /* ------------------------------------------------------------------ (f) nobody else comes in */
        JsonNode openSession = call("POST", COUNTS, Map.of("countYear", YEAR, "locationId", shop, "correctsCountId", shopCount), 201);
        long openCount = openSession.path("id").asLong();
        JsonNode openLine = call("POST", COUNTS + "/" + openCount + "/lines", Map.of("productId", rose), 201);
        String customerToken = customerToken();
        long decisionOfVersion2 = corrected.at("/decisions/0/id").asLong();
        String hashBefore = call("GET", CLOSINGS + "/" + version2, null, 200).path("dataSha256").asText();
        for (java.util.function.Supplier<RequestSpecification> caller : List.<java.util.function.Supplier<RequestSpecification>>of(
                () -> given().contentType("application/json"),
                () -> given().contentType("application/json").header("Authorization", "Bearer " + customerToken),
                () -> given().contentType("application/json").auth().preemptive().basic(STAFF, "not-the-password"))) {
            Map<String, Response> answers = new LinkedHashMap<>();
            answers.put("GET counts", caller.get().get(COUNTS + "?year=" + YEAR));
            answers.put("POST counts", caller.get().body(Map.of("countYear", YEAR, "locationId", warehouse)).post(COUNTS));
            answers.put("GET count", caller.get().get(COUNTS + "/" + openCount));
            answers.put("PUT line", caller.get().body(write(90, "TELFOUT", null, openLine.path("revision").asInt(), null, null))
                    .put(COUNTS + "/" + openCount + "/lines/" + openLine.path("id").asLong()));
            answers.put("POST line", caller.get().body(Map.of("productId", loose)).post(COUNTS + "/" + openCount + "/lines"));
            answers.put("GET booking-check", caller.get().get(COUNTS + "/" + openCount + "/booking-check"));
            answers.put("POST book", caller.get().body(Map.of("checkToken", "x")).post(COUNTS + "/" + openCount + "/book"));
            answers.put("POST cancel", caller.get().body(Map.of()).post(COUNTS + "/" + openCount + "/cancel"));
            answers.put("GET opening", caller.get().get(OPENING));
            answers.put("POST opening", caller.get().body(Map.of("asOfDate", "2025-06-01", "source", "indringer",
                    "rows", List.of(Map.of("productId", rose, "quantity", 5, "unitValueEur", 9)))).post(OPENING));
            answers.put("DELETE opening", caller.get().delete(OPENING + "/" + openingLayer));
            answers.put("GET rule", caller.get().get(RULE));
            answers.put("GET closings", caller.get().get(CLOSINGS));
            answers.put("POST closings", caller.get().body(Map.of("closingYear", YEAR + 1)).post(CLOSINGS));
            answers.put("GET closing", caller.get().get(CLOSINGS + "/" + closing));
            answers.put("PUT closing", caller.get().body(Map.of("closingDate", "2025-12-30")).put(CLOSINGS + "/" + version2));
            answers.put("POST recompute", caller.get().body(Map.of()).post(CLOSINGS + "/" + version2 + "/recompute"));
            answers.put("PUT decision", caller.get().body(Map.of("kind", "VAT_CONFIRMATION", "flag", false)).put(CLOSINGS + "/" + version2 + "/decisions"));
            answers.put("DELETE decision", caller.get().delete(CLOSINGS + "/" + version2 + "/decisions/" + decisionOfVersion2));
            answers.put("DELETE closing", caller.get().delete(CLOSINGS + "/" + version2));
            answers.put("POST finalize", caller.get().body(Map.of("dataSha256", hashBefore, "signerName", "Indringer")).post(CLOSINGS + "/" + version2 + "/finalize"));
            answers.put("POST versions", caller.get().body(Map.of("reason", "indringer")).post(CLOSINGS + "/" + closing + "/versions"));
            answers.put("GET pdf", caller.get().get(CLOSINGS + "/" + closing + "/pdf"));
            answers.put("GET xlsx", caller.get().get(CLOSINGS + "/" + closing + "/xlsx"));
            answers.forEach((route, answer) -> check("refused: " + route, 401, answer.statusCode()));
        }
        JsonNode untouched = call("GET", CLOSINGS + "/" + version2, null, 200);
        check("version 2 is untouched by the refused callers", List.of("CONCEPT", hashBefore), List.of(untouched.path("status").asText(), untouched.path("dataSha256").asText()));
        check("the open count is untouched", List.of("OPEN", "1"), List.of(call("GET", COUNTS + "/" + openCount, null, 200).path("status").asText(),
                String.valueOf(call("GET", COUNTS + "/" + openCount, null, 200).path("lines").size())));
        check("no opening value came in", 1, call("GET", OPENING, null, 200).size());

        /* An open count holds the closing; cancelled, it books nothing. */
        JsonNode held = call("POST", CLOSINGS + "/" + version2 + "/recompute", Map.of(), 200);
        check("an open count blocks", List.of("TELLING_OPEN"), codes(held, "BLOCKER"));
        check("cancelled", "GEANNULEERD", call("POST", COUNTS + "/" + openCount + "/cancel", Map.of(), 200).path("status").asText());
        check("shop level after the cancel", 100, level(rose, shop));
        JsonNode again = call("POST", CLOSINGS + "/" + version2 + "/recompute", Map.of(), 200);
        check("the same figures as before the open count", hashBefore, again.path("dataSha256").asText());

        JsonNode frozen2 = call("POST", CLOSINGS + "/" + version2 + "/finalize",
                Map.of("dataSha256", again.path("dataSha256").asText(), "signerName", "Emre Yilmaz"), 200);
        dump("09-version-2-final", frozen2);
        check("version 2 final", List.of("DEFINITIEF", "false", "true"),
                List.of(frozen2.path("status").asText(), frozen2.path("superseded").asText(), frozen2.path("canCorrect").asText()));
        assertSecondVersion(frozen2);
        byte[] xlsx2 = download(CLOSINGS + "/" + version2 + "/xlsx", "jaarinventaris-2025-v2.xlsx");
        byte[] pdf2 = download(CLOSINGS + "/" + version2 + "/pdf", "jaarinventaris-2025-v2.pdf");
        check("v2 workbook hash", sha256(xlsx2), frozen2.path("xlsxSha256").asText());
        check("v2 pdf hash", sha256(pdf2), frozen2.path("pdfSha256").asText());
        check("the v2 workbook holds its total", true, workbookText(xlsx2).contains("3804.5"));
        String paper2 = pdfText(pdf2);
        Files.writeString(DUMP.resolve("09-version-2-final.pdf.txt"), paper2);
        check("the v2 pdf prints its total", true, paper2.contains("3.804,50"));
        check("the v2 pdf names what it replaces", true, paper2.replaceAll("\\s", "").contains("Wijzigingentegenoverversie1"));

        /* The old version stays readable, with its own figures and its own files. */
        JsonNode old = call("GET", CLOSINGS + "/" + closing, null, 200);
        dump("10-version-1-replaced", old);
        check("version 1 is replaced, not gone", List.of("DEFINITIEF", "true", String.valueOf(version2), "false"),
                List.of(old.path("status").asText(), old.path("superseded").asText(), old.path("supersededById").asText(), old.path("canCorrect").asText()));
        check("version 1 keeps its hash", frozen.path("dataSha256").asText(), old.path("dataSha256").asText());
        assertFirstVersion(old, rose, loose, warehouse, shop, first, second);
        ObjectNode oldFigures = ((ObjectNode) old.deepCopy()).without(List.of("superseded", "supersededById", "versions", "canCorrect"));
        ObjectNode frozenFigures = ((ObjectNode) frozen.deepCopy()).without(List.of("superseded", "supersededById", "versions", "canCorrect"));
        check("apart from 'replaced by', version 1 reads as on the day it was frozen", frozenFigures, oldFigures);
        assertArrayEquals(pdf, download(CLOSINGS + "/" + closing + "/pdf", "jaarinventaris-2025-v1.pdf"), "the pdf of version 1");
        assertArrayEquals(xlsx, download(CLOSINGS + "/" + closing + "/xlsx", "jaarinventaris-2025-v1.xlsx"), "the workbook of version 1");
        refusal(call("POST", CLOSINGS + "/" + closing + "/versions", Map.of("reason", "op de oude versie"), 409), "GEEN_DEFINITIEVE");
        JsonNode both = call("GET", CLOSINGS, null, 200);
        check("both versions are listed, newest first", List.of(String.valueOf(version2), String.valueOf(closing)), texts(both.path("closings"), "id"));
        check("the rule did not move", YEAR, call("GET", RULE, null, 200).path("effectiveFromYear").asInt());

        /* ------------------------------------------------------------------ what a deleted ledger row does
         * Two sales lay between the closing date and the count: B (-30) and D (-12). The row of B was deleted before
         * version 2 and took its own 30 pieces out of the closing quantity, no more. Deleting the row of D takes its
         * 12 out as well, whatever row follows it (here the replaced count, which does not count and stays at -5):
         * 718 + 0 = 718 in the warehouse, 818 in all.
         *   570 x 4,2902 = 2.445,41; 228 x 2,6560 = 605,57; 52,00: 3.102,98 - 49,35 + 719,00 = 3.772,63,
         *   that is 12 x 2,6560 = 31,87 below version 2. */
        JsonNode third3 = call("POST", CLOSINGS + "/" + version2 + "/versions", Map.of("reason", "Proef: nog een boeking verwijderd"), 201);
        long version3 = third3.path("id").asLong();
        dump("11-version-3-created", third3);
        check("version 3 starts on the figures of version 2", List.of("3", "830"), List.of(third3.path("versionNo").asText(),
                one(third3.path("articles"), "productId", rose).path("closingQuantity").asText()));
        money("version 3 starts on the total of version 2", "3804.50", third3.at("/totals/totalValueEur"));
        check("nothing differs yet", List.of(0, 0, 0, 0), List.of(third3.at("/versionChanges/articles").size(), third3.at("/versionChanges/lots").size(),
                third3.at("/versionChanges/movements").size(), third3.at("/versionChanges/openingLayers").size()));
        assertEquals(204, staff().delete("/api/products/" + rose + "/stock-movements/" + rowD.path("movementId").asLong()).statusCode());
        JsonNode probed = call("POST", CLOSINGS + "/" + version3 + "/recompute", Map.of(), 200);
        dump("12-version-3-row-deleted", probed);
        JsonNode vanished = one(probed.path("movements"), "movementId", rowD.path("movementId").asLong());
        check("the deleted row stays listed", List.of("true", "false", "-12", "-12"), List.of(vanished.path("removed").asText(),
                vanished.path("applied").asText(), vanished.path("delta").asText(), vanished.path("effectiveDelta").asText()));
        check("and is named", true, codes(probed, "WARNING").contains("BEWEGING_VERDWENEN"));
        JsonNode replacedCount = one(probed.path("movements"), "kind", "STOCKTAKE");
        check("the replaced count keeps its own difference and does not count", List.of("-5", "-5", "false"),
                List.of(replacedCount.path("delta").asText(), replacedCount.path("effectiveDelta").asText(), replacedCount.path("applied").asText()));
        check("12 pieces leave the closing quantity for the deleted sale of 12", 818,
                one(probed.path("articles"), "productId", rose).path("closingQuantity").asInt());
        money("the total follows", "3772.63", probed.at("/totals/totalValueEur"));
        check("nothing blocks this version", List.of(), codes(probed, "BLOCKER"));
        /* The probe is thrown away: a concept can be deleted, and version 2 is the valid closing again. */
        assertEquals(204, staff().delete(CLOSINGS + "/" + version3).statusCode());
        assertEquals(404, staff().get(CLOSINGS + "/" + version3).statusCode());
        JsonNode valid = call("GET", CLOSINGS + "/" + version2, null, 200);
        check("version 2 is the valid closing", List.of("DEFINITIEF", "false", "true", frozen2.path("dataSha256").asText()),
                List.of(valid.path("status").asText(), valid.path("superseded").asText(), valid.path("canCorrect").asText(), valid.path("dataSha256").asText()));
        assertArrayEquals(pdf2, download(CLOSINGS + "/" + version2 + "/pdf", "jaarinventaris-2025-v2.pdf"), "the pdf of version 2");
        check("two closings remain", 2, call("GET", CLOSINGS, null, 200).path("closings").size());

        assertTrue(problems.isEmpty(), problems.size() + " figures differ:\n" + String.join("\n", problems));
    }

    /** Every figure of version 1 that the hand calculation in the class comment gives. */
    private void assertFirstVersion(JsonNode view, long rose, long loose, long warehouse, long shop, long first, long second) {
        String v = "v1 " + view.path("status").asText() + (view.path("superseded").asBoolean() ? " replaced" : "") + ": ";
        JsonNode totals = view.path("totals");
        money(v + "aanschafwaarde", "3088.68", totals.path("costValueEur"));
        money(v + "waardevermindering", "46.30", totals.path("writeDownEur"));
        money(v + "eigen voorraad", "3042.38", totals.path("ownValueEur"));
        money(v + "demo", "0.00", totals.path("demoValueEur"));
        money(v + "partner in", "0.00", totals.path("partnerIncludedEur"));
        money(v + "partner uit", "0.00", totals.path("partnerExcludedEur"));
        money(v + "onderweg in", "719.00", totals.path("transitIncludedEur"));
        money(v + "onderweg uit", "0.00", totals.path("transitExcludedEur"));
        money(v + "gefactureerd uit", "53.12", totals.path("invoicedOutEur"));
        money(v + "TOTAAL", "3761.38", totals.path("totalValueEur"));
        money(v + "geschat", "963.48", totals.path("estimatedEur"));
        check(v + "own quantity", 877, totals.path("ownQuantity").asInt());
        check(v + "unvalued", 0, totals.path("unvaluedQuantity").asInt());

        JsonNode article = one(view.path("articles"), "productId", rose);
        check(v + "rose quantities", List.of("857", "0", "0", "20", "837", "0"), List.of(article.path("closingQuantity").asText(),
                article.path("thirdPartyQuantity").asText(), article.path("partnerQuantity").asText(), article.path("invoicedOutQuantity").asText(),
                article.path("ownQuantity").asText(), article.path("unvaluedQuantity").asText()));
        money(v + "rose cost", "3038.68", article.path("costValueEur"));
        money(v + "rose estimated", "459.48", article.path("estimatedEur"));
        money(v + "rose write-down", "46.30", article.path("writeDownEur"));
        money(v + "rose own value", "2992.38", article.path("ownValueEur"));
        money(v + "rose average", "3.6304", article.path("averageUnitEur"));
        /* goods 570 x 2,2333 + 267 x 1,7160; logistics 570 x 1,6536 + 267 x 0,8400; separate 570 x 0,2000 + 267 x 0,1000 */
        money(v + "rose logistics part", "1166.83", article.path("logisticsEur"));
        money(v + "rose separate part", "140.70", article.path("separateEur"));
        money(v + "rose goods part", "1731.15", article.path("goodsEur"));
        check(v + "layers", 3, article.path("layers").size());
        layer(v + "newest lot", article.at("/layers/0"), "EIGEN", second, 570, "4.0869", "2329.53", "459.48", 15, "46.30");
        layer(v + "older lot", article.at("/layers/1"), "EIGEN", first, 267, "2.6560", "709.15", "0.00", 0, "0.00");
        layer(v + "invoiced out", article.at("/layers/2"), "GEFACTUREERD", first, 20, "2.6560", "53.12", "0.00", 0, "0.00");
        JsonNode atWarehouse = one(article.path("locations"), "locationId", warehouse);
        JsonNode atShop = one(article.path("locations"), "locationId", shop);
        check(v + "warehouse", List.of("715", "42", "757", "739"), List.of(atWarehouse.path("anchorQuantity").asText(), atWarehouse.path("rollDelta").asText(),
                atWarehouse.path("closingQuantity").asText(), atWarehouse.path("ownQuantity").asText()));
        check(v + "shop", List.of("100", "0", "100", "98"), List.of(atShop.path("anchorQuantity").asText(), atShop.path("rollDelta").asText(),
                atShop.path("closingQuantity").asText(), atShop.path("ownQuantity").asText()));
        /* 3.038,68 x 757 / 857 = 2.684,108 and x 100 / 857 = 354,572: the odd cent goes to the larger remainder. */
        money(v + "warehouse share", "2684.11", atWarehouse.path("costValueEur"));
        money(v + "shop share", "354.57", atShop.path("costValueEur"));

        JsonNode other = one(view.path("articles"), "productId", loose);
        money(v + "loose cost", "50.00", other.path("costValueEur"));
        money(v + "loose opening part", "50.00", other.path("openingEur"));
        check(v + "loose layer", List.of("BEGINWAARDE", "40", "Inventaris 31/12/2024, blad 3", "2025-01-01"), List.of(other.at("/layers/0/source").asText(),
                other.at("/layers/0/quantity").asText(), other.at("/layers/0/openingSource").asText(), other.at("/layers/0/receivedOn").asText()));
        money(v + "loose unit", "1.2500", other.at("/layers/0/unitValueEur"));
        check(v + "opening value on the view", List.of("Inventaris 31/12/2024, blad 3"), texts(view.path("openingLayers"), "source"));

        check(v + "write-down rows", 1, view.path("writeDowns").size());
        JsonNode writeDown = view.at("/writeDowns/0");
        check(v + "write-down row", List.of("15", "BESCHADIGD", "Stolpen gebarsten bij het stapelen", "Emre"), List.of(writeDown.path("quantity").asText(),
                writeDown.path("reasonCode").asText(), writeDown.path("reason").asText(), writeDown.path("decidedByName").asText()));
        money(v + "write-down layer unit", "4.0869", writeDown.path("layerUnitEur"));
        money(v + "write-down market unit", "1.00", writeDown.path("marketUnitEur"));
        money(v + "write-down amount", "46.30", writeDown.path("amountEur"));

        JsonNode transit = one(view.path("separate"), "kind", "ONDERWEG");
        check(v + "transit decided", List.of("true", "2025-12-15", "FOB Ningbo: risico over bij het laden op 15/12"),
                List.of(transit.path("included").asText(), transit.path("ownershipDate").asText(), transit.path("reason").asText()));
        money(v + "transit value", "719.00", transit.path("valueEur"));
        money(v + "transit unit", "1.7975", transit.path("unitValueEur"));
        check(v + "decisions", List.of("INVOICED", "MOVEMENT", "TRANSIT", "VAT_CONFIRMATION", "WRITE_DOWN"),
                texts(view.path("decisions"), "kind").stream().sorted().toList());
        money(v + "container 1 unit", "2.6560", one(container(view, first, "EIGEN").path("lots"), "productId", rose).path("unitValueEur"));
        money(v + "container 2 unit", "4.0869", one(container(view, second, "EIGEN").path("lots"), "productId", rose).path("unitValueEur"));
        money(v + "container 2 enrosed kost outside", "2000.00", container(view, second, "EIGEN").path("enrosedCostExcludedEur"));
    }

    /**
     * Version 2 once the forwarder's invoice is confirmed: 975,60 paid + 120,00 owed = 1.095,60, / 590 = 1,8569;
     * unit value 2,2333 + 1,8569 + 0,2000 = 4,2902, nothing of it estimated.
     *   570 x 4,2902 = 2.445,414 -> 2.445,41; 240 x 2,6560 = 637,44; 40 x 1,30 = 52,00: AANSCHAFWAARDE 3.134,85
     *   15 x (4,2902 - 1,00) = 49,353 -> 49,35: EIGEN VOORRAAD 3.085,50; onderweg 719,00: TOTAAL 3.804,50
     *   estimated: only the 504,00 on the water.
     */
    private void assertSecondVersion(JsonNode view) {
        String v = "v2 " + view.path("status").asText() + ": ";
        money(v + "aanschafwaarde", "3134.85", view.at("/totals/costValueEur"));
        money(v + "waardevermindering", "49.35", view.at("/totals/writeDownEur"));
        money(v + "eigen voorraad", "3085.50", view.at("/totals/ownValueEur"));
        money(v + "onderweg", "719.00", view.at("/totals/transitIncludedEur"));
        money(v + "gefactureerd uit", "53.12", view.at("/totals/invoicedOutEur"));
        money(v + "TOTAAL", "3804.50", view.at("/totals/totalValueEur"));
        money(v + "geschat", "504.00", view.at("/totals/estimatedEur"));
        check(v + "own quantity", 850, view.at("/totals/ownQuantity").asInt());
        check(v + "blockers", List.of(), codes(view, "BLOCKER"));
    }

    private void layer(String what, JsonNode layer, String block, long container, int quantity, String unit, String value,
                       String estimated, int writtenDown, String writeDown) {
        check(what + " block/container/quantity/written down", List.of(block, String.valueOf(container), String.valueOf(quantity), String.valueOf(writtenDown)),
                List.of(layer.path("block").asText(), layer.path("purchaseOrderId").asText(), layer.path("quantity").asText(), layer.path("writeDownQuantity").asText()));
        money(what + " unit", unit, layer.path("unitValueEur"));
        money(what + " value", value, layer.path("valueEur"));
        money(what + " estimated", estimated, layer.path("estimatedEur"));
        money(what + " write-down", writeDown, layer.path("writeDownEur"));
    }

    private void stream(JsonNode container, String payee, String planned, String paid, String open, String included, String estimated, String state) {
        JsonNode stream = one(container.path("streams"), "payee", payee);
        String what = container.path("orderNumber").asText() + " " + payee + " ";
        money(what + "planned", planned, stream.path("plannedEur"));
        money(what + "paid", paid, stream.path("paidEur"));
        money(what + "open", open, stream.path("openEur"));
        money(what + "included", included, stream.path("includedEur"));
        money(what + "estimated", estimated, stream.path("estimatedEur"));
        check(what + "state", state, stream.path("state").asText());
    }

    // ------------------------------------------------------------------------------------------ the book

    private long container(long supplierId, long product, int quantity, String priceUsd, String freightUsd, String arrivalEur,
                           String inspectionEur, LocalDate orderDate) {
        PurchaseOrder created = orders.create(supplierId, new BigDecimal("0.14"), new BigDecimal("0.90"), new BigDecimal("4"));
        orders.update(created.id(), created.withReceipt(PurchaseOrderStatus.BESTELD, null, null, false, null, List.of(
                new PurchaseOrderLine(null, product, quantity, new BigDecimal(priceUsd), Currency.USD, null, null))));
        tx(() -> {
            PurchaseOrderEntity stored = em.find(PurchaseOrderEntity.class, created.id());
            stored.orderDate = orderDate;
            stored.freightUsd = new BigDecimal(freightUsd);
            stored.destinationCostsEur = new BigDecimal(arrivalEur);
            stored.inspectionCostEur = new BigDecimal(inspectionEur);
            stored.extraRevenueEur = new BigDecimal("2000");
        });
        return created.id();
    }

    private long product(Long supplierId, String sku, String name) {
        long[] id = new long[1];
        tx(() -> {
            ProductEntity product = new ProductEntity();
            product.sku = sku;
            product.name = name;
            product.colour = "rood";
            product.active = true;
            product.supplierId = supplierId;
            product.piecesPerCarton = 10;
            product.productLengthCm = product.productWidthCm = product.productHeightCm = BigDecimal.ONE;
            product.cartonLengthCm = new BigDecimal("50");
            product.cartonWidthCm = product.cartonHeightCm = new BigDecimal("40");
            product.cartonWeightKg = BigDecimal.ONE;
            em.persist(product);
            em.flush();
            id[0] = product.id;
        });
        return id[0];
    }

    /** An issued Belgian invoice for one product that is not afgepunt. */
    private SalesOrder invoice(long productId, int quantity, LocalDate orderDate) {
        SalesOrder created = sales.create(customerId, "BE", "DAP", DocumentType.FACTUUR);
        tx(() -> {
            SalesOrderEntity stored = em.find(SalesOrderEntity.class, created.id());
            stored.freightPricingStrategy = FreightPricingStrategy.FIXED;
            stored.manualFreightEur = new BigDecimal("120.00");
            stored.freight = FreightState.AANGEVULD;
            stored.status = QuoteStatus.UITGEREIKT;
            stored.orderDate = orderDate;
            SalesOrderLineEntity line = new SalesOrderLineEntity();
            line.order = stored;
            line.productId = productId;
            line.quantity = quantity;
            line.unitPriceEur = new BigDecimal("8.45");
            line.unitCostEur = new BigDecimal("5.10");
            stored.lines.add(line);
        });
        return sales.get(created.id());
    }

    /** Afpunten, as it was booked on that day. */
    private void ship(SalesOrder invoice, String at) {
        sales.shipGoods(invoice.id());
        tx(() -> em.find(SalesOrderEntity.class, invoice.id()).goodsShippedAt = Instant.parse(at));
        backdate(at);
    }

    /** Gives the ledger rows written since the last call the day they belong to. */
    private void backdate(String at) {
        tx(() -> em.createQuery("update StockMovementEntity m set m.at = ?1 where m.at >= ?2")
                .setParameter(1, Instant.parse(at)).setParameter(2, started.minusSeconds(60)).executeUpdate());
    }

    private String customerToken() {
        var grant = accounts.grant(customerId, "flow-buyer@example.com", "An Peeters", Language.NL);
        return QuarkusTransaction.requiringNew().call(() -> accounts.activate(grant.rawToken(), "roses-in-a-dome").sessionToken());
    }

    /** The level as the database holds it now, not as this thread read it earlier. */
    private int level(long product, long location) {
        return QuarkusTransaction.requiringNew().call(() -> stock.quantityAt(product, location));
    }

    private void tx(Runnable work) {
        QuarkusTransaction.requiringNew().run(work);
        em.clear();
    }

    @SuppressWarnings("unchecked")
    private List<Object[]> rows(String query, Object parameter) {
        return QuarkusTransaction.requiringNew().call(() -> (List<Object[]>) em.createQuery(query).setParameter(1, parameter).getResultList());
    }

    // ------------------------------------------------------------------------------------------ HTTP

    private static RequestSpecification staff() {
        return given().auth().preemptive().basic(STAFF, STAFF_PASSWORD).contentType("application/json");
    }

    private static JsonNode call(String method, String path, Object body, int status) throws Exception {
        RequestSpecification request = staff();
        if (body != null) request = request.body(body);
        Response answer = switch (method) {
            case "GET" -> request.get(path);
            case "POST" -> request.post(path);
            case "PUT" -> request.put(path);
            default -> request.delete(path);
        };
        assertEquals(status, answer.statusCode(), method + " " + path + " answered " + answer.asString());
        String text = answer.asString();
        return text == null || text.isBlank() ? MissingNode.getInstance() : JSON.readTree(text);
    }

    private static byte[] download(String path, String filename) {
        Response answer = staff().get(path);
        assertEquals(200, answer.statusCode(), path);
        assertEquals("attachment; filename=\"" + filename + "\"", answer.header("Content-Disposition"), path);
        assertEquals("no-store", answer.header("Cache-Control"), path);
        return answer.asByteArray();
    }

    private static Map<String, Object> write(Integer counted, String reason, String note, int revision, Boolean rebase, Boolean confirmed) {
        Map<String, Object> write = new LinkedHashMap<>();
        write.put("countedQuantity", counted);
        write.put("reasonCode", reason);
        write.put("reasonNote", note);
        write.put("revision", revision);
        if (rebase != null) write.put("rebase", rebase);
        if (confirmed != null) write.put("documentsConfirmed", confirmed);
        return write;
    }

    // ------------------------------------------------------------------------------------------ reading answers

    private JsonNode one(JsonNode array, String field, Object value) {
        for (JsonNode node : array) if (node.path(field).asText().equals(String.valueOf(value))) return node;
        problems.add("no row with " + field + " = " + value + " in " + abbreviate(array));
        return MissingNode.getInstance();
    }

    private JsonNode container(JsonNode view, long order, String role) {
        for (JsonNode node : view.path("containers")) {
            if (node.path("purchaseOrderId").asLong() == order && node.path("role").asText().equals(role)) return node;
        }
        problems.add("no container " + order + " with role " + role);
        return MissingNode.getInstance();
    }

    private static List<String> texts(JsonNode array, String field) {
        List<String> texts = new ArrayList<>();
        for (JsonNode node : array) texts.add(field == null ? node.asText() : node.path(field).asText());
        return texts;
    }

    private static List<String> codes(JsonNode view, String severity) {
        List<String> codes = new ArrayList<>();
        for (JsonNode notice : view.path("notices")) if (notice.path("severity").asText().equals(severity)) codes.add(notice.path("code").asText());
        codes.sort(null);
        return codes;
    }

    private void refusal(JsonNode answer, String code) {
        if (!Set.of(code.split("\\|")).contains(answer.path("code").asText())) {
            problems.add("expected the refusal " + code + ", got " + abbreviate(answer));
        }
    }

    private void invalid(JsonNode answer, String message) {
        check("422 message", message, answer.path("message").asText());
    }

    private void check(String what, Object expected, Object actual) {
        if (!String.valueOf(expected).equals(String.valueOf(actual)) && !expected.equals(actual)) {
            problems.add(what + ": expected " + abbreviate(expected) + " but was " + abbreviate(actual));
        }
    }

    private void money(String what, String expected, JsonNode actual) {
        if (!actual.isNumber() || new BigDecimal(expected).compareTo(actual.decimalValue()) != 0) {
            problems.add(what + ": expected " + expected + " but was " + (actual.isMissingNode() ? "absent" : actual.toString()));
        }
    }

    private static String abbreviate(Object value) {
        String text = String.valueOf(value);
        return text.length() > 600 ? text.substring(0, 600) + "…" : text;
    }

    private static void dump(String name, JsonNode view) throws Exception {
        Files.writeString(DUMP.resolve(name + ".json"), JSON.writerWithDefaultPrettyPrinter().writeValueAsString(view));
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static String pdfText(byte[] pdf) throws Exception {
        try (org.apache.pdfbox.pdmodel.PDDocument document = org.apache.pdfbox.Loader.loadPDF(pdf)) {
            return new org.apache.pdfbox.text.PDFTextStripper().getText(document);
        }
    }

    /** Every xml part of the workbook as one text: enough to find a figure in it. */
    private static String workbookText(byte[] xlsx) throws Exception {
        StringBuilder text = new StringBuilder();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(xlsx))) {
            for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                if (entry.getName().endsWith(".xml")) text.append(new String(zip.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        return text.toString();
    }
}
