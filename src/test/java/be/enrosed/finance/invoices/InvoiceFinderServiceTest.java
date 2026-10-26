package be.enrosed.finance.invoices;

import be.enrosed.finance.application.CompanyCostService;
import be.enrosed.finance.application.CostPartyService;
import be.enrosed.finance.odoo.OdooInvoiceSearch;
import be.enrosed.media.MediaService;
import be.enrosed.sales.application.CustomerService;
import be.enrosed.sales.application.IncomingPaymentService;
import be.enrosed.sales.application.SalesOrderService;
import be.enrosed.sales.domain.SalesPaymentSummary;
import be.enrosed.shared.NotFoundException;
import be.enrosed.sourcing.application.PurchaseInvoiceService;
import be.enrosed.sourcing.application.PurchaseOrderService;
import be.enrosed.sourcing.application.SupplierService;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Test;

import static be.enrosed.finance.invoices.InvoiceFinderFixtures.MARCH_FIRST;
import static be.enrosed.finance.invoices.InvoiceFinderFixtures.MARCH_LAST;
import static be.enrosed.finance.invoices.InvoiceFinderFixtures.d;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The finder over real ENROSED data and a READY fake Odoo. Fixtures are committed once per class under a token; every
 * assertion reads through that token, an order id or a 2031 period.
 */
@QuarkusTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestSecurity(user = "test-admin", roles = "admin")
class InvoiceFinderServiceTest {
    @Inject InvoiceFinderService finder;
    @Inject EntityManager em;
    @Inject SupplierService suppliers;
    @Inject PurchaseOrderService purchases;
    @Inject PurchaseInvoiceService invoices;
    @Inject CompanyCostService costs;
    @Inject CostPartyService parties;
    @Inject MediaService media;
    @Inject SalesOrderService sales;
    @Inject CustomerService customers;
    @Inject IncomingPaymentService incoming;
    @Inject OdooDocumentMarks marks;

    private static InvoiceFinderFixtures fx;

    @BeforeEach
    void setUp() {
        if (fx == null) {
            fx = new InvoiceFinderFixtures().build(new InvoiceFinderFixtures.Deps(em, suppliers, purchases, invoices, costs,
                    parties, media, sales, customers, incoming, marks));
        }
        fx.odoo.failWith = null;
        fx.odoo.queries.clear();
        QuarkusMock.installMockForType(fx.odoo, OdooInvoices.class);
    }

    /** The committed fixtures leave the lists other suites read first (sales documents, bank lines). */
    @AfterAll
    void tearDown() {
        if (fx != null) fx.tearDown(em);
        fx = null;
    }

    private InvoiceFinderService.Page find(String direction, String scope, String q, Long orderId, int limit) {
        return finder.find(new InvoiceFinderService.Query(direction, scope, q, MARCH_FIRST, MARCH_LAST, orderId, null, limit));
    }

    private static List<String> keys(InvoiceFinderService.Page page) {
        return page.rows().stream().map(InvoiceRow::key).toList();
    }

    private static InvoiceRow row(InvoiceFinderService.Page page, String key) {
        return page.rows().stream().filter(row -> row.key().equals(key)).findFirst()
                .orElseThrow(() -> new AssertionError(key + " not in " + keys(page)));
    }

    @Test
    void incomingAllListsEveryOwnRowOnceInOrderWithItsStatus() {
        InvoiceFinderService.Page page = find("IN", "ALL", fx.token, null, 100);
        assertEquals(List.of(
                "cost:" + fx.costVt1,           // d20 (M1 € 1.250 with the same number is hidden)
                "odoo:" + fx.m2,                // d19 € 1.260: new
                "odoo:" + fx.m5,                // d16 binding of a deleted cost
                "odoo:" + fx.m6,                // d15 TICA settled
                "odoo:" + fx.m7,                // d14 TICA open
                "odoo:" + fx.m8,                // d13 marked handled
                "cost:" + fx.costRecurring,     // d12 rank 3 before Odoo
                "odoo:" + fx.m9,                // d12 marked to the container
                "pi:" + fx.ciInvoice,           // d10 rank 1
                "cost:" + fx.costNoFile,        // d10 rank 3
                "pi:" + fx.fwInvoice,           // d9 (M3 € 410 with the same number is hidden)
                "pd:" + fx.piDocument,          // d8 file without data (M13 cancelled is never listed)
                "cost:" + fx.costWithFile,      // d6
                "cost:" + fx.costOdoo,          // d3 (M4 registered is excluded in the domain)
                "cost:" + fx.costTica           // d2
        ), keys(page));
        assertEquals(new InvoiceFinderService.OdooState("READY", null, null, true), page.odoo());
        assertEquals(9, page.erpCount(), "ENROSED rows of the filter, without the trashed container and the deleted cost");
        assertNull(page.nextCursor());

        InvoiceRow ci = row(page, "pi:" + fx.ciInvoice);
        assertEquals("CONTAINER_INVOICE", ci.source());
        assertEquals("Commercial invoice", ci.docLabel());
        assertEquals(new InvoiceRow.Party("Finder " + fx.token, "SUPPLIER", fx.supplierId), ci.party());
        assertEquals("PARTIAL", ci.status());
        assertEquals(new BigDecimal("100.00"), ci.paidEur());
        assertEquals(new BigDecimal("900.00"), ci.openEur());
        assertEquals("UPLOAD", ci.origin());
        assertEquals(List.of("CONTAINER", "TRANSACTION"), ci.links().stream().map(InvoiceRow.Link::type).toList());
        assertEquals("Voorjaar " + fx.token, ci.links().getFirst().label());
        assertEquals(new InvoiceRow.Link("TRANSACTION", fx.ciLine, "05/03", d(5), "BE68539007547034"), ci.links().get(1));
        assertEquals("PURCHASE_DOCUMENT", ci.files().getFirst().type());
        assertEquals(fx.orderA.id(), ci.files().getFirst().orderId());
        assertEquals("PDF", ci.files().getFirst().kind());
        assertFalse(ci.missingFile());

        InvoiceRow fw = row(page, "pi:" + fx.fwInvoice);
        assertEquals(new InvoiceRow.Party("Douane & transport", "PAYEE", null), fw.party(), "D3: the payee word, not the supplier");
        assertEquals("LOGISTICS", fw.payee());
        assertEquals("Factuur", fw.docLabel());
        assertEquals("OPEN", fw.status());
        assertEquals("MANUAL", fw.origin());
        assertTrue(fw.missingFile());

        InvoiceRow pd = row(page, "pd:" + fx.piDocument);
        assertEquals("NO_DATA", pd.status());
        assertEquals("PROFORMA", pd.kind());
        assertEquals("Proforma (PI)", pd.docLabel());
        assertEquals("UPLOADED", pd.dateKind());
        assertEquals(d(8), pd.sortDate());
        assertNull(pd.amount());

        InvoiceRow withFile = row(page, "cost:" + fx.costWithFile);
        assertEquals("COST_PARTY", withFile.party().type());
        assertNotNull(withFile.party().id(), "the cost party is found by name");
        assertEquals("UPLOAD", withFile.origin());
        assertEquals("MEDIA_ASSET", withFile.files().getFirst().type());
        assertEquals("OPEN", withFile.status());
        assertEquals(new BigDecimal("121.00"), withFile.amount());
        InvoiceRow odooCost = row(page, "cost:" + fx.costOdoo);
        assertEquals("ODOO", odooCost.origin());
        assertEquals(fx.m4, odooCost.odooMoveId());
        assertEquals("done", odooCost.peppolState());
        InvoiceRow tica = row(page, "cost:" + fx.costTica);
        assertEquals("SETTLED", tica.status());
        assertTrue(tica.links().contains(new InvoiceRow.Link("TICA", fx.ticaSettlement, "TICA", null, null)));

        assertEquals("NEW", row(page, "odoo:" + fx.m2).status());
        assertTrue(row(page, "odoo:" + fx.m2).odoo().registrable());
        InvoiceRow removed = row(page, "odoo:" + fx.m5);
        assertEquals("HANDLED", removed.status());
        assertEquals("COST_REMOVED", removed.statusReason());
        assertEquals("COST_REMOVED", removed.odoo().blockedBy());
        assertEquals("TICA", row(page, "odoo:" + fx.m6).status());
        assertEquals(List.of(new InvoiceRow.Link("TICA", fx.ticaSettlement, "TICA", null, null)), row(page, "odoo:" + fx.m6).links());
        assertEquals(List.of(), row(page, "odoo:" + fx.m7).links(), "a TICA move without settlement has no TICA link");
        assertEquals("HANDLED", row(page, "odoo:" + fx.m8).status());
        InvoiceRow container = row(page, "odoo:" + fx.m9);
        assertEquals("CONTAINER", container.status());
        assertEquals(fx.orderA.id(), container.container().id());
        assertEquals("CONTAINER", container.odoo().mark().resolution());
    }

    @Test
    void noFileListsOnlyRowsWithoutAFileThatNeedOne() {
        InvoiceFinderService.Page page = find("IN", "NO_FILE", fx.token, null, 100);
        assertEquals(Set.of("pi:" + fx.fwInvoice, "cost:" + fx.costNoFile, "cost:" + fx.costOdoo, "cost:" + fx.costTica,
                "cost:" + fx.costVt1), new HashSet<>(keys(page)), "recurring costs and files never miss a file");
        assertFalse(page.odoo().included());
        assertTrue(fx.odoo.queries.isEmpty(), "no Odoo call for a scope without Odoo");
        assertEquals(List.of("pi:" + fx.ciInvoice, "pi:" + fx.fwInvoice, "pd:" + fx.piDocument),
                keys(find("IN", "CONTAINERS", fx.token, null, 100)));
    }

    @Test
    void newInOdooExcludesEveryKnownMoveAndAllOnlyTheRegisteredOnes() {
        InvoiceFinderService.Page fresh = find("IN", "ODOO_NEW", fx.token, null, 100);
        assertEquals(List.of("odoo:" + fx.m2), keys(fresh), "M1/M3 are matched, the rest is excluded");
        assertEquals(0, fresh.erpCount());
        assertEquals(Set.of(fx.m4, fx.m5, fx.m6, fx.m7, fx.m8, fx.m9), fx.odoo.queries.getFirst().excludeMoveIds());

        fx.odoo.queries.clear();
        find("IN", "ALL", fx.token, null, 100);
        assertEquals(Set.of(fx.m4), fx.odoo.queries.getFirst().excludeMoveIds());
        assertEquals(Set.of("in_invoice", "in_refund"), fx.odoo.queries.getFirst().moveTypes());
        assertEquals(MARCH_FIRST, fx.odoo.queries.getFirst().from());
    }

    @Test
    void outgoingHidesOutboxAndNumberMatchedMovesButShowsTheCreditNote() {
        InvoiceFinderService.Page page = find("OUT", "ALL", fx.token, null, 100);
        assertEquals(List.of("so:" + fx.salesInvoice, "so:" + fx.salesCredit, "odoo:" + fx.m12), keys(page),
                "concept and cancelled never show; M10 (outbox) and M11 (our number) are the sales row");
        assertEquals(Set.of(fx.m10), fx.odoo.queries.getFirst().excludeMoveIds());
        InvoiceRow invoice = row(page, "so:" + fx.salesInvoice);
        assertEquals("done", invoice.peppolState());
        assertEquals(fx.m10, invoice.odooMoveId());
        assertEquals("ENROSED", invoice.origin());
        assertEquals("CUSTOMER", invoice.party().type());
        assertEquals(new BigDecimal("121.00"), invoice.amount());
        assertEquals(new BigDecimal("21.00"), invoice.paidEur());
        assertEquals(new BigDecimal("100.00"), invoice.openEur());
        assertEquals(List.of("TRANSACTION"), invoice.links().stream().map(InvoiceRow.Link::type).toList());
        assertEquals(fx.salesLine, invoice.links().getFirst().id());
        assertEquals("SALES_PDF", invoice.files().getFirst().type());
        assertEquals(fx.salesInvoiceNumber + ".pdf", invoice.files().getFirst().name());
        InvoiceRow credit = row(page, "so:" + fx.salesCredit);
        assertEquals("CREDIT_NOTE", credit.kind());
        assertNull(credit.openEur());
        InvoiceRow refund = row(page, "odoo:" + fx.m12);
        assertEquals("OUT", refund.direction());
        assertEquals("CREDIT_NOTE", refund.kind());
        assertEquals("OUTGOING", refund.odoo().blockedBy());

        for (long id : new long[] {fx.salesInvoice, fx.salesCredit}) {
            var order = io.quarkus.narayana.jta.QuarkusTransaction.requiringNew().call(() -> sales.get(id));
            SalesPaymentSummary summary = io.quarkus.narayana.jta.QuarkusTransaction.requiringNew()
                    .call(() -> incoming.summary(order, sales.price(order)));
            String expected = switch (summary.status()) {
                case PAID, OVERPAID -> order.isCreditNote() ? "SETTLED" : "PAID";
                case PARTIAL -> "PARTIAL";
                case UNPAID -> "OPEN";
                case CREDIT -> "OPEN";
            };
            assertEquals(expected, row(page, "so:" + id).status(), "same rule as IncomingPaymentService.summary");
        }
        assertEquals("PARTIAL", invoice.status());
        assertEquals(List.of("so:" + fx.salesInvoice), keys(find("OUT", "INVOICES", fx.token, null, 100)));
        assertEquals(List.of("so:" + fx.salesCredit, "odoo:" + fx.m12), keys(find("OUT", "CREDIT_NOTES", fx.token, null, 100)));
        assertEquals(List.of("odoo:" + fx.m12), keys(find("OUT", "ODOO", fx.token, null, 100)));
    }

    @Test
    void searchFindsNumbersSeparatorBlindAmountsContainersAndPartyAliases() {
        assertEquals(List.of("pi:" + fx.ciInvoice), keys(find("IN", "ALL", "CI " + fx.token + "/118", null, 100)));
        assertEquals(List.of("cost:" + fx.costVt1), keys(find("IN", "ALL", fx.token + " 1.250,00", null, 100)),
                "the € 1.250 Odoo copy stays hidden");
        assertEquals(List.of("cost:" + fx.costWithFile), keys(find("IN", "ALL", "Oostkust " + fx.token, null, 100)),
                "an alias of the cost party");
        List<String> byContainer = keys(find("IN", "ALL", fx.orderA.number(), null, 100));
        Set<String> mine = Set.of("odoo:" + fx.m9, "pi:" + fx.ciInvoice, "pi:" + fx.fwInvoice, "pd:" + fx.piDocument,
                "pi:" + fx.trashedInvoice);
        assertEquals(List.of("odoo:" + fx.m9, "pi:" + fx.ciInvoice, "pi:" + fx.fwInvoice, "pd:" + fx.piDocument),
                byContainer.stream().filter(mine::contains).toList(),
                "the container's rows and the move marked to it; never the trashed container");
    }

    @Test
    void aContainerFilterKeepsItsRowsAndAsksOdooOnlyForItsMarkedMoves() {
        InvoiceFinderService.Page page = find("IN", "ALL", null, fx.orderA.id(), 100);
        assertEquals(List.of("odoo:" + fx.m9, "pi:" + fx.ciInvoice, "pi:" + fx.fwInvoice, "pd:" + fx.piDocument), keys(page));
        assertEquals(Set.of(fx.m9), fx.odoo.queries.getFirst().onlyMoveIds());
        fx.odoo.queries.clear();
        InvoiceFinderService.Page trashed = find("IN", "ALL", null, fx.orderTrashed, 100);
        assertEquals(List.of(), keys(trashed));
        assertTrue(fx.odoo.queries.isEmpty(), "no marks for that container: no Odoo call");
        assertTrue(trashed.odoo().included());
    }

    @Test
    void anOdooFailureKeepsTheEnrosedRows() {
        fx.odoo.failWith = "RATE_LIMITED";
        InvoiceFinderService.Page page = find("IN", "ALL", fx.token, null, 100);
        assertEquals("ERROR", page.odoo().state());
        assertEquals("RATE_LIMITED", page.odoo().code());
        assertFalse(page.odoo().included());
        assertNotNull(page.odoo().message());
        assertEquals(9, page.rows().size());
        assertTrue(page.rows().stream().noneMatch(row -> "ODOO".equals(row.source())));
    }

    @Test
    void pagingWithASmallLimitYieldsTheSameRowsWithoutRepeatsOrGaps() {
        List<String> all = keys(find("IN", "ALL", fx.token, null, 100));
        List<String> paged = new ArrayList<>();
        String cursor = null;
        for (int guard = 0; guard < 40; guard++) {
            InvoiceFinderService.Page page = finder.find(new InvoiceFinderService.Query("IN", "ALL", fx.token, MARCH_FIRST,
                    MARCH_LAST, null, cursor, 2));
            assertTrue(page.rows().size() <= 2);
            paged.addAll(keys(page));
            if (page.nextCursor() == null) break;
            cursor = page.nextCursor();
        }
        assertEquals(all, paged);
        InvoiceFinderErrors.Invalid stale = assertThrows(InvoiceFinderErrors.Invalid.class, () -> finder.find(
                new InvoiceFinderService.Query("IN", "COSTS", fx.token, MARCH_FIRST, MARCH_LAST, null,
                        finder.find(new InvoiceFinderService.Query("IN", "ALL", fx.token, MARCH_FIRST, MARCH_LAST, null, null, 2))
                                .nextCursor(), 2)));
        assertEquals("INVALID_CURSOR", stale.code);
    }

    @Test
    void oneRowAnswersWhatNowStandsForItsKey() {
        assertEquals("pi:" + fx.ciInvoice, finder.row("pd:" + fx.ciDocument).key(), "a held file answers with its invoice");
        assertEquals("pd:" + fx.piDocument, finder.row("pd:" + fx.piDocument).key());
        assertEquals("cost:" + fx.costOdoo, finder.row("odoo:" + fx.m4).key(), "registered");
        assertEquals("cost:" + fx.costVt1, finder.row("odoo:" + fx.m1).key(), "matched to a cost");
        assertEquals("pi:" + fx.fwInvoice, finder.row("odoo:" + fx.m3).key(), "matched to a container invoice");
        assertEquals("so:" + fx.salesInvoice, finder.row("odoo:" + fx.m10).key(), "the outbox");
        assertEquals("so:" + fx.salesInvoice, finder.row("odoo:" + fx.m11).key(), "our number");
        assertEquals("odoo:" + fx.m12, finder.row("odoo:" + fx.m12).key());
        InvoiceRow cancelled = finder.row("odoo:" + fx.m13);
        assertEquals("CANCELLED", cancelled.status());
        assertEquals("CANCELLED", cancelled.odoo().blockedBy());
        assertEquals("cost:" + fx.costTica, finder.row("cost:" + fx.costTica).key());
        assertEquals("so:" + fx.salesCredit, finder.row("so:" + fx.salesCredit).key());
        assertThrows(NotFoundException.class, () -> finder.row("pi:" + fx.trashedInvoice), "trashed container");
        assertThrows(NotFoundException.class, () -> finder.row("cost:" + fx.costDeleted));
        assertThrows(NotFoundException.class, () -> finder.row("so:" + fx.salesConcept), "a concept is no invoice yet");
        assertThrows(NotFoundException.class, () -> finder.row("odoo:" + (fx.base + 99)));
        assertEquals("INVALID_KEY", assertThrows(InvoiceFinderErrors.Invalid.class, () -> finder.row("pi:0")).code);
    }

    @Test
    void theSearchedOdooWordsAreTheSplitWords() {
        find("IN", "ALL", "VT " + fx.token + "/0412", null, 100);
        List<String> words = fx.odoo.queries.getFirst().words().stream().map(OdooInvoiceSearch.Word::text).toList();
        assertEquals(List.of("VT", fx.token, "0412"), words);
    }

    @Test
    void periodFiltersOnTheSortDate() {
        InvoiceFinderService.Page page = finder.find(new InvoiceFinderService.Query("IN", "ALL", fx.token, d(10), d(12), null,
                null, 100));
        assertEquals(List.of("cost:" + fx.costRecurring, "odoo:" + fx.m9, "pi:" + fx.ciInvoice, "cost:" + fx.costNoFile),
                keys(page));
        assertEquals(LocalDate.of(2031, 3, 12), fx.odoo.queries.getFirst().to());
    }
}
