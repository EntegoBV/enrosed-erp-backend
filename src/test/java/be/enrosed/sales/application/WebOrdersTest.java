package be.enrosed.sales.application;

import be.enrosed.publicform.PublicFormAction;
import be.enrosed.publicform.PublicFormPurpose;
import be.enrosed.sales.adapter.out.persistence.SalesWebOrderEntity;
import be.enrosed.sales.application.WebOrders.Row;
import be.enrosed.sales.application.WebOrders.TermsState;
import be.enrosed.sales.application.port.out.SalesRepositories;
import be.enrosed.sales.domain.DocumentType;
import be.enrosed.sales.domain.PricedOrder;
import be.enrosed.sales.domain.QuoteEvent;
import be.enrosed.sales.domain.QuoteStatus;
import be.enrosed.sales.domain.SalesOrder;
import be.enrosed.sales.domain.SalesPurpose;
import be.enrosed.shared.BusinessRuleException;
import be.enrosed.shared.security.ActorRef;
import be.enrosed.shared.security.CurrentActor;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.enterprise.context.ContextNotActiveException;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static be.enrosed.sales.application.WebOrderTermsTest.extra;
import static be.enrosed.sales.application.WebOrderTermsTest.line;
import static be.enrosed.sales.application.WebOrderTermsTest.order;
import static be.enrosed.sales.application.WebOrderTermsTest.ordered;
import static be.enrosed.sales.application.WebOrderTermsTest.priced;
import static be.enrosed.sales.application.WebOrderTermsTest.snapshot;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The website order row: whose order it is, how long the customer may still
 * change it, the gate a staff mutation passes after the document lock, and
 * the rule that only an unchanged or approved order is invoiced. The gate
 * runs against the real table with a named actor and request state.
 */
@QuarkusTest
class WebOrdersTest {
    private static final AtomicLong IDS = new AtomicLong(8_000_000_000L + System.nanoTime() % 1_000_000_000L);
    private static final long CUSTOMER = 2L;

    @Inject EntityManager em;
    @Inject WebOrders wired;

    // ------------------------------------------------------------------------------------------ predicates

    @Test
    void theCustomerMayChangeOnlyAnUntouchedWebsiteConcept() {
        Row untaken = row(4711, 1);
        assertTrue(WebOrders.customerMayChange(order().build(), untaken, false));
        assertTrue(WebOrders.customerMayChange(order().salesChannel("website").purpose(SalesPurpose.STANDARD).build(), untaken, false));

        assertFalse(WebOrders.customerMayChange(order().build(), null, false), "no row: a legacy website request");
        assertFalse(WebOrders.customerMayChange(order().build(), taken(untaken), false), "taken into processing");
        assertFalse(WebOrders.customerMayChange(order().build(), cancelled(untaken), false), "cancelled by the customer");
        assertFalse(WebOrders.customerMayChange(order().sentAt(Instant.now()).build(), untaken, false), "sent, then reopened");
        assertFalse(WebOrders.customerMayChange(order().portalToken("token").build(), untaken, false), "a portal token exists");
        assertFalse(WebOrders.customerMayChange(order().archivedAt(Instant.now()).build(), untaken, false), "archived");
        assertFalse(WebOrders.customerMayChange(order().build(), untaken, true), "an invoice was made from it");
        assertFalse(WebOrders.customerMayChange(order().salesChannel("DIRECT").build(), untaken, false), "another channel");
        assertFalse(WebOrders.customerMayChange(order().salesChannel(null).build(), untaken, false), "no channel");
        assertFalse(WebOrders.customerMayChange(order().docType(DocumentType.FACTUUR).build(), untaken, false), "an invoice");
        assertFalse(WebOrders.customerMayChange(order().docType(DocumentType.CREDITNOTA).build(), untaken, false), "a credit note");
        assertFalse(WebOrders.customerMayChange(order().partnerPurchaseOrderId(9L).purpose(SalesPurpose.STANDARD).build(), untaken, false),
                "linked to a partner container");
        assertFalse(WebOrders.customerMayChange(order().purpose(SalesPurpose.PARTNER_ADVANCE).build(), untaken, false), "a partner advance");
        assertFalse(WebOrders.customerMayChange(order().purpose(SalesPurpose.PARTNER_SETTLEMENT).build(), untaken, false), "a partner settlement");
        for (QuoteStatus status : QuoteStatus.values()) {
            if (status != QuoteStatus.CONCEPT)
                assertFalse(WebOrders.customerMayChange(order().status(status).build(), untaken, false), status.name());
        }
    }

    @Test
    void aRelinkedDocumentIsNobodysOrder() {
        Row row = row(4711, 1);
        assertTrue(WebOrders.owns(order().build(), row, CUSTOMER));
        assertFalse(WebOrders.owns(order().build(), row, 3L), "another customer's session");
        assertFalse(WebOrders.owns(order().build(), null, CUSTOMER), "no row");
        assertFalse(WebOrders.owns(order().customerId(null).build(), row, CUSTOMER));
        SalesOrder relinked = order().customerId(3L).build();
        assertFalse(WebOrders.owns(relinked, row, CUSTOMER), "the customer who placed it no longer sees it");
        assertFalse(WebOrders.owns(relinked, row, 3L), "and for the new customer it is a plain quote");
    }

    @Test
    void theTermsStateFollowsTheTableOfTheGuard() {
        Row unknown = row(4711, 1);
        Row ordered = terms(unknown, "A", null, null);
        assertNull(WebOrders.termsState(order().build(), null, "A"));

        assertEquals(TermsState.ORDER_UNKNOWN, WebOrders.termsState(order().build(), unknown, "A"));
        assertEquals(TermsState.ORDER_EQUAL, WebOrders.termsState(order().build(), ordered, "A"));
        assertEquals(TermsState.ORDER_DIFFERENT, WebOrders.termsState(order().build(), ordered, "B"));
        assertEquals(TermsState.RESEND_REQUIRED, WebOrders.termsState(order().sentAt(Instant.now()).build(), ordered, "A"),
                "sent, then reopened: the ordered figures no longer decide");

        for (QuoteStatus sent : List.of(QuoteStatus.VERZONDEN, QuoteStatus.BEKEKEN)) {
            SalesOrder document = order().status(sent).sentAt(Instant.now()).build();
            assertEquals(TermsState.AWAITING_APPROVAL, WebOrders.termsState(document, ordered, "B"), "sent before terms were kept");
            assertEquals(TermsState.AWAITING_APPROVAL, WebOrders.termsState(document, terms(unknown, "A", "B", null), "B"));
            assertEquals(TermsState.RESEND_REQUIRED, WebOrders.termsState(document, terms(unknown, "A", "B", null), "C"));
        }

        SalesOrder accepted = order().status(QuoteStatus.GEACCEPTEERD).sentAt(Instant.now()).build();
        assertEquals(TermsState.APPROVED, WebOrders.termsState(accepted, terms(unknown, "A", "B", null), "C"));
        assertEquals(TermsState.APPROVED, WebOrders.termsState(accepted, terms(unknown, "A", "B", "B"), "B"));
        assertEquals(TermsState.RESEND_REQUIRED, WebOrders.termsState(accepted, terms(unknown, "A", "B", "B"), "C"));

        for (QuoteStatus none : List.of(QuoteStatus.GEANNULEERD, QuoteStatus.AFGEWEZEN, QuoteStatus.VERLOPEN,
                QuoteStatus.WIJZIGING_GEVRAAGD, QuoteStatus.UITGEREIKT, QuoteStatus.BETAALD))
            assertNull(WebOrders.termsState(order().status(none).build(), ordered, "A"), none.name());
    }

    @Test
    void theNewRateLimitSurfacesNeverCountPerEmailAndTheNamespaceFitsItsColumn() {
        for (PublicFormAction action : List.of(PublicFormAction.ACCOUNT_ORDER_READ, PublicFormAction.ACCOUNT_ORDER_DEFAULTS,
                PublicFormAction.ACCOUNT_ORDER_WRITE, PublicFormAction.ACCOUNT_ORDER_PDF)) {
            assertEquals(0, action.emailLimit(), action.name());
            assertEquals(3_600, action.windowSeconds(), action.name());
        }
        assertEquals(240, PublicFormAction.ACCOUNT_ORDER_READ.ipLimit());
        assertEquals(600, PublicFormAction.ACCOUNT_ORDER_DEFAULTS.ipLimit());
        assertEquals(20, PublicFormAction.ACCOUNT_ORDER_WRITE.ipLimit());
        assertEquals(30, PublicFormAction.ACCOUNT_ORDER_PDF.ipLimit());
        assertEquals(13, PublicFormPurpose.ACCOUNT_ORDER.name().length());
        assertEquals(10, PublicFormAction.ACCOUNT_QUOTE_SUBMIT.emailLimit(), "the placement budget is untouched");
    }

    // ------------------------------------------------------------------------------------------ the row

    @Test @TestTransaction
    void anOrderStartsAtRevisionOneAndOnlyCustomerActionsMoveIt() {
        Gate gate = gate(staff("emre", "Emre"));
        long id = IDS.incrementAndGet();
        Instant before = Instant.now();
        Row created = gate.orders.create(id, CUSTOMER, 31L, "buyer@example.com", "NL", "{\"v\":1}", "A");
        assertEquals(1, created.revision());
        assertEquals(CUSTOMER, created.customerId());
        assertEquals(31L, created.accountId());
        assertEquals("buyer@example.com", created.accountEmail());
        assertEquals("NL", created.language());
        assertEquals("A", created.orderedTerms());
        assertEquals("{\"v\":1}", created.orderSnapshot());
        assertFalse(created.placedAt().isBefore(before));
        assertNull(created.processingStartedAt());
        assertEquals(created, gate.orders.find(id).orElseThrow());
        assertEquals(created, gate.orders.lock(id).orElseThrow());

        Row changed = gate.orders.recordChange(id, 32L, "second@example.com", "FR", "{\"v\":1,\"revision\":2}", null,
                "ER-RED: 4 → 6 dozen" + " ".repeat(1200));
        assertEquals(2, changed.revision());
        assertEquals(32L, changed.accountId());
        assertEquals("second@example.com", changed.accountEmail());
        assertEquals("FR", changed.language());
        assertNull(changed.orderedTerms(), "recomputed at every change, also back to unknown");
        assertEquals("{\"v\":1,\"revision\":2}", changed.orderSnapshot());
        assertEquals(1000, changed.customerChangeSummary().length());
        assertNotNull(changed.customerChangedAt());
        assertEquals(created.placedAt(), changed.placedAt());

        gate.orders.recordSent(id, "S");
        gate.orders.recordAccepted(id, "T");
        Row cancelled = gate.orders.recordCustomerCancel(id);
        assertEquals(3, cancelled.revision());
        assertNotNull(cancelled.customerCancelledAt());
        assertEquals("S", cancelled.sentTerms());
        assertEquals("T", cancelled.acceptedTerms());
        em.clear();
        assertEquals(cancelled, gate.orders.find(id).orElseThrow(), "every write reached the table");

        long none = IDS.incrementAndGet();
        gate.orders.recordSent(none, "S");
        gate.orders.recordAccepted(none, "T");
        assertTrue(gate.orders.find(none).isEmpty(), "a document without a row keeps none");
        assertTrue(gate.orders.lock(none).isEmpty());
    }

    @Test @TestTransaction
    void theListReadsEveryStateInOneQueryWithoutTheSnapshot() {
        Gate gate = gate(staff("emre", "Emre"));
        long first = IDS.incrementAndGet();
        long second = IDS.incrementAndGet();
        gate.orders.create(first, CUSTOMER, 31L, "buyer@example.com", "NL", snapshot(ordered()).toJson(), "A");
        gate.orders.create(second, 3L, 40L, "other@example.com", "EL", "{}", null);
        gate.orders.recordChange(second, 40L, "other@example.com", "EL", "{}", "B", "opmerking gewijzigd");
        em.clear();

        var states = gate.orders.indexStates();
        Row state = states.get(first);
        assertNull(state.orderSnapshot());
        assertEquals(new Row(first, CUSTOMER, 31L, "buyer@example.com", "NL", 1, state.placedAt(), null, null, null, null,
                null, null, "A", null, null, null, null, null, null), state);
        assertNotNull(state.placedAt());
        assertEquals(2, states.get(second).revision());
        assertEquals("opmerking gewijzigd", states.get(second).customerChangeSummary());
        assertEquals("B", states.get(second).orderedTerms());
        assertNull(states.get(second).orderSnapshot());

        var indexed = gate.orders.index(List.of(first, IDS.incrementAndGet()));
        assertEquals(List.of(first), List.copyOf(indexed.keySet()));
        assertNotNull(indexed.get(first).orderSnapshot(), "the detail reads the snapshot");
        assertTrue(gate.orders.index(List.of()).isEmpty());
        assertTrue(gate.orders.index(null).isEmpty());
    }

    // ------------------------------------------------------------------------------------------ the staff gate

    @Test @TestTransaction
    void aDocumentWithoutARowPassesTheGateUntouched() {
        Gate gate = gate(staff("emre", "Emre"));
        gate.orders.afterStaffLock(IDS.incrementAndGet());
        assertTrue(gate.events.isEmpty());
        verify(gate.taken, never()).fire(any());
    }

    @Test @TestTransaction
    void theSystemNeverTakesAnOrder() {
        Gate gate = gate(ActorRef.SYSTEM);
        long id = placed(gate, 3);
        gate.presented.set(1);
        gate.orders.afterStaffLock(id);
        assertNull(gate.orders.find(id).orElseThrow().processingStartedAt(), "the website intake and jobs save as the system");
        assertTrue(gate.events.isEmpty());
        verify(gate.taken, never()).fire(any());
    }

    @Test @TestTransaction
    void theFirstStaffMutationTakesTheOrderAutomatically() {
        Gate gate = gate(staff("emre", "Emre Demir"));
        long id = placed(gate, 1);
        Instant before = Instant.now();
        gate.orders.afterStaffLock(id);

        Row taken = gate.orders.find(id).orElseThrow();
        assertFalse(taken.processingStartedAt().isBefore(before));
        assertEquals("Emre Demir", taken.processingStartedBy());
        assertEquals("AUTOMATISCH", taken.processingTrigger());
        assertEquals(1, taken.revision(), "staff never move the revision");
        assertEquals(1, gate.events.size());
        QuoteEvent event = gate.events.getFirst();
        assertEquals(id, event.salesOrderId());
        assertEquals(QuoteEvent.Type.IN_VERWERKING, event.type());
        assertEquals("In verwerking genomen (automatisch bij een wijziging door Emre Demir)", event.summary());
        assertEquals("Emre Demir", event.actor());
        assertFalse(event.byCustomer());
        verify(gate.taken).fire(new WebOrderEvents.Taken(id, null));
        em.clear();
        assertEquals(taken, gate.orders.find(id).orElseThrow());
    }

    @Test @TestTransaction
    void theButtonTakesTheOrderExplicitly() {
        Gate gate = gate(staff("sara", " "));
        long id = placed(gate, 4);
        gate.presented.set(4);
        gate.presented.markExplicitTake();
        gate.orders.afterStaffLock(id);

        Row taken = gate.orders.find(id).orElseThrow();
        assertEquals("KNOP", taken.processingTrigger());
        assertEquals("sara", taken.processingStartedBy(), "the username when no display name is known");
        assertEquals("In verwerking genomen", gate.events.getFirst().summary());
    }

    @Test @TestTransaction
    void aMissingRevisionPassesOnlyWhileTheOrderWasNeverChanged() {
        Gate gate = gate(staff("emre", "Emre"));
        long unchanged = placed(gate, 1);
        gate.orders.afterStaffLock(unchanged);
        assertNotNull(gate.orders.find(unchanged).orElseThrow().processingStartedAt());

        long changed = placed(gate, 2);
        WebOrderChangedException refused = assertThrows(WebOrderChangedException.class, () -> gate.orders.afterStaffLock(changed));
        assertTrue(refused.missingParameter());
        assertEquals(2, refused.currentRevision());
        assertEquals("De klant heeft deze websitebestelling intussen gewijzigd (versie 2). Dit scherm is verouderd; "
                + "laad de laatste versie van de bestelling voor je verdergaat.", refused.getMessage());
        assertTrue(refused instanceof BusinessRuleException);
        assertNull(gate.orders.find(changed).orElseThrow().processingStartedAt());
        assertEquals(1, gate.events.size(), "only the first order was taken");
    }

    @Test @TestTransaction
    void aStaleRevisionIsRefusedAndAnEqualOnePasses() {
        Gate gate = gate(staff("emre", "Emre"));
        long id = placed(gate, 4);
        gate.presented.set(3);
        WebOrderChangedException refused = assertThrows(WebOrderChangedException.class, () -> gate.orders.afterStaffLock(id));
        assertFalse(refused.missingParameter());
        assertEquals(4, refused.currentRevision());
        assertEquals("De klant heeft deze bestelling intussen gewijzigd of geannuleerd. Je wijzigingen zijn niet opgeslagen; "
                + "laad de laatste versie.", refused.getMessage());
        assertNull(gate.orders.find(id).orElseThrow().processingStartedAt());
        assertTrue(gate.events.isEmpty());
        verify(gate.taken, never()).fire(any());

        gate.presented.set(5);
        assertThrows(WebOrderChangedException.class, () -> gate.orders.afterStaffLock(id), "a revision from the future is no better");

        gate.presented.set(4);
        gate.orders.afterStaffLock(id);
        assertNotNull(gate.orders.find(id).orElseThrow().processingStartedAt());
    }

    @Test @TestTransaction
    void theRevisionIsCheckedAlsoAfterAColleagueTookTheOrder() {
        Gate colleague = gate(staff("sara", "Sara"));
        long changed = placed(colleague, 2);
        colleague.presented.set(2);
        colleague.orders.afterStaffLock(changed);
        Row taken = colleague.orders.find(changed).orElseThrow();
        assertEquals("Sara", taken.processingStartedBy());

        Gate stale = gate(staff("emre", "Emre"));
        stale.presented.set(1);
        assertFalse(assertThrows(WebOrderChangedException.class, () -> stale.orders.afterStaffLock(changed)).missingParameter(),
                "a screen opened before the customer's change would overwrite that change");
        Gate old = gate(staff("emre", "Emre"));
        assertTrue(assertThrows(WebOrderChangedException.class, () -> old.orders.afterStaffLock(changed)).missingParameter());

        Gate fresh = gate(staff("emre", "Emre"));
        fresh.presented.set(2);
        fresh.presented.markExplicitTake();
        fresh.orders.afterStaffLock(changed);
        assertEquals(taken, fresh.orders.find(changed).orElseThrow(), "already taken: nothing changes, not who or how");
        assertTrue(fresh.events.isEmpty());
        verify(fresh.taken, never()).fire(any());

        Gate first = gate(staff("sara", "Sara"));
        long unchanged = placed(first, 1);
        first.orders.afterStaffLock(unchanged);
        Gate tab = gate(staff("emre", "Emre"));
        tab.orders.afterStaffLock(unchanged);
        assertEquals("Sara", tab.orders.find(unchanged).orElseThrow().processingStartedBy(),
                "without a revision a taken order passes while it is still the only version");
        assertTrue(tab.events.isEmpty());
    }

    @Test @TestTransaction
    void aCustomerCancelledOrderIsNeverTaken() {
        Gate gate = gate(staff("emre", "Emre"));
        long id = placed(gate, 1);
        gate.orders.recordCustomerCancel(id);
        gate.presented.set(1);
        assertThrows(WebOrderChangedException.class, () -> gate.orders.afterStaffLock(id), "the cancellation moved the revision");
        gate.presented.set(2);
        gate.orders.afterStaffLock(id);
        Row row = gate.orders.find(id).orElseThrow();
        assertNull(row.processingStartedAt(), "staff may delete it; nothing is taken");
        assertNull(row.processingTrigger());
        assertTrue(gate.events.isEmpty());
        verify(gate.taken, never()).fire(any());
    }

    @Test @TestTransaction
    @SuppressWarnings("unchecked")
    void outsideARequestTheGateReadsNoRevision() {
        Gate gate = gate(staff("emre", "Emre"));
        StaffWebOrderRevision inactive = mock(StaffWebOrderRevision.class);
        when(inactive.value()).thenThrow(new ContextNotActiveException());
        when(inactive.explicitTake()).thenThrow(new ContextNotActiveException());
        gate.orders.staffRevision = mock(Instance.class);
        when(gate.orders.staffRevision.isResolvable()).thenReturn(true);
        when(gate.orders.staffRevision.get()).thenReturn(inactive);

        long unchanged = placed(gate, 1);
        gate.orders.afterStaffLock(unchanged);
        assertEquals("AUTOMATISCH", gate.orders.find(unchanged).orElseThrow().processingTrigger());
        long changed = placed(gate, 2);
        assertTrue(assertThrows(WebOrderChangedException.class, () -> gate.orders.afterStaffLock(changed)).missingParameter());
    }

    @Test @TestTransaction
    @TestSecurity(user = "emre", roles = "admin")
    void theWiredGateTakesAnOrderForTheSignedInStaffMember() {
        long id = IDS.incrementAndGet();
        wired.create(id, CUSTOMER, 31L, "buyer@example.com", "NL", "{}", "A");
        wired.afterStaffLock(id);
        Row taken = wired.find(id).orElseThrow();
        assertEquals("emre", taken.processingStartedBy());
        assertEquals("AUTOMATISCH", taken.processingTrigger());
        assertEquals(1L, em.createQuery("select count(e) from " + em.getMetamodel()
                        .entity(be.enrosed.sales.adapter.out.persistence.SalesEntities.QuoteEventEntity.class).getName()
                        + " e where e.salesOrderId = :id and e.type = :type", Long.class)
                .setParameter("id", id).setParameter("type", QuoteEvent.Type.IN_VERWERKING).getSingleResult());
    }

    @Test @TestTransaction
    void withoutAStaffIdentityTheWiredGateLeavesTheOrderAlone() {
        long id = IDS.incrementAndGet();
        wired.create(id, CUSTOMER, 31L, "buyer@example.com", "NL", "{}", "A");
        wired.afterStaffLock(id);
        assertNull(wired.find(id).orElseThrow().processingStartedAt());
    }

    // ------------------------------------------------------------------------------------------ the D1 guard

    @Test @TestTransaction
    void anInvoiceIsMadeOnlyFromAnUnchangedOrApprovedOrder() {
        Gate gate = gate(staff("emre", "Emre"));
        PricedOrder asOrdered = ordered();
        String terms = WebOrderTerms.of(asOrdered);
        gate.orders.requireInvoiceable(order().id(IDS.incrementAndGet()).build(), asOrdered);

        long id = placed(gate, 1, snapshot(asOrdered).toJson(), terms);
        gate.orders.requireInvoiceable(order().id(id).build(), asOrdered);

        PricedOrder changed = priced(List.of(line(12, "ER-RED", 120, "1.85", "5", "210.90")), "42.00", "21", List.of());
        assertEquals("Deze websitebestelling wijkt af van wat de klant bestelde (besteld € 210,72 excl. btw, nu € 252,90): "
                        + "Aantal ER-RED: besteld 96, nu 120; Totaal excl. btw: besteld € 210,72, nu € 252,90. "
                        + "Verstuur ze ter goedkeuring; factureren kan zodra de klant akkoord gaat.",
                refusal(() -> gate.orders.requireInvoiceable(order().id(id).build(), changed)));
        assertTrue(refusal(() -> gate.orders.requireInvoiceable(order().id(id).build(),
                priced(List.of(line(12, "ER-RED", 96, "1.85", "5", "168.72")), "42.00", "21", List.of(extra("Montage", "25.00")))))
                .contains("Extra regels: niet besteld, nu € 25,00"));

        long open = placed(gate, 1, "{}", null);
        assertEquals("Bij deze websitebestelling stond de vracht of een prijs nog open toen de klant bestelde. "
                        + "Verstuur ze ter goedkeuring; factureren kan zodra de klant akkoord gaat.",
                refusal(() -> gate.orders.requireInvoiceable(order().id(open).build(), asOrdered)));

        Instant sent = Instant.now();
        gate.orders.recordSent(id, terms);
        assertEquals("Deze websitebestelling wacht nog op het akkoord van de klant op de verstuurde versie. "
                        + "Factureren kan zodra de klant akkoord gaat.",
                refusal(() -> gate.orders.requireInvoiceable(order().id(id).status(QuoteStatus.VERZONDEN).sentAt(sent).build(), asOrdered)));
        assertEquals("De cijfers van deze websitebestelling zijn gewijzigd sinds de versie die de klant kreeg. "
                        + "Verstuur ze opnieuw; factureren kan zodra de klant akkoord gaat.",
                refusal(() -> gate.orders.requireInvoiceable(order().id(id).status(QuoteStatus.BEKEKEN).sentAt(sent).build(), changed)));
        assertEquals("Deze websitebestelling is al ter goedkeuring verstuurd en daarna heropend. "
                        + "Verstuur de nieuwe versie; factureren kan zodra de klant akkoord gaat.",
                refusal(() -> gate.orders.requireInvoiceable(order().id(id).sentAt(sent).build(), asOrdered)),
                "also when the figures are back at what was ordered");

        gate.orders.recordAccepted(id, terms);
        SalesOrder accepted = order().id(id).status(QuoteStatus.GEACCEPTEERD).sentAt(sent).build();
        gate.orders.requireInvoiceable(accepted, asOrdered);
        assertEquals("De cijfers van deze websitebestelling wijken af van de versie waarmee de klant akkoord ging "
                        + "(een prijslijst, staffel of vrachttabel is sindsdien gewijzigd). "
                        + "Maak een nieuwe kopie met de huidige cijfers en verstuur die ter goedkeuring.",
                refusal(() -> gate.orders.requireInvoiceable(accepted, changed)));

        for (QuoteStatus none : List.of(QuoteStatus.GEANNULEERD, QuoteStatus.AFGEWEZEN, QuoteStatus.VERLOPEN, QuoteStatus.WIJZIGING_GEVRAAGD))
            assertEquals("Deze websitebestelling kan in deze status niet gefactureerd worden.",
                    refusal(() -> gate.orders.requireInvoiceable(order().id(id).status(none).build(), asOrdered)));
    }

    @Test @TestTransaction
    void theInvoiceIsComparedAgainWhenItIsIssued() {
        Gate gate = gate(staff("emre", "Emre"));
        PricedOrder asOrdered = ordered();
        String terms = WebOrderTerms.of(asOrdered);
        PricedOrder edited = priced(List.of(line(12, "ER-RED", 96, "1.95", "5", "177.84")), "42.00", "21", List.of());
        PricedOrder slotfactuur = priced(List.of(line(12, "ER-RED", 96, "1.85", "5", "168.72")), "42.00", "21",
                List.of(extra("Voorschot F-2026-0001", "-60.00")));
        BigDecimal deducted = new BigDecimal("60.00");

        gate.orders.requireIssuable(order().docType(DocumentType.FACTUUR).build(), edited, null, BigDecimal.ZERO);
        long plain = IDS.incrementAndGet();
        gate.orders.requireIssuable(invoiceOf(plain), edited, order().id(plain).build(), BigDecimal.ZERO);

        long id = placed(gate, 1, snapshot(asOrdered).toJson(), terms);
        SalesOrder unchanged = order().id(id).archivedAt(Instant.now()).build();
        gate.orders.requireIssuable(invoiceOf(id), asOrdered, unchanged, BigDecimal.ZERO);
        gate.orders.requireIssuable(invoiceOf(id), slotfactuur, unchanged, deducted);
        assertEquals("Deze factuur wijkt af van wat de klant bestelde: Prijs ER-RED: besteld € 1,85, nu € 1,95; "
                        + "Totaal excl. btw: besteld € 210,72, nu € 219,84. Zet de factuur terug gelijk aan de bestelling, "
                        + "of verwijder het concept en verstuur de bestelling ter goedkeuring.",
                refusal(() -> gate.orders.requireIssuable(invoiceOf(id), edited, unchanged, BigDecimal.ZERO)));
        assertTrue(refusal(() -> gate.orders.requireIssuable(invoiceOf(id), slotfactuur, unchanged, BigDecimal.ZERO))
                .contains("Extra regels: niet besteld, nu € -60,00"), "a deduction that is not one counts as an extra line");

        long open = placed(gate, 1, null, null);
        assertEquals("Deze factuur wijkt af van wat de klant bestelde. Zet de factuur terug gelijk aan de bestelling, "
                        + "of verwijder het concept en verstuur de bestelling ter goedkeuring.",
                refusal(() -> gate.orders.requireIssuable(invoiceOf(open), asOrdered, order().id(open).build(), BigDecimal.ZERO)),
                "an order with open figures is never invoiced unchanged");

        SalesOrder accepted = order().id(id).status(QuoteStatus.GEACCEPTEERD).sentAt(Instant.now()).build();
        gate.orders.requireIssuable(invoiceOf(id), edited, accepted, BigDecimal.ZERO);
        gate.orders.recordAccepted(id, WebOrderTerms.of(edited));
        gate.orders.requireIssuable(invoiceOf(id), edited, accepted, BigDecimal.ZERO);
        PricedOrder editedSlotfactuur = priced(List.of(line(12, "ER-RED", 96, "1.95", "5", "177.84")), "42.00", "21",
                List.of(extra("Voorschot F-2026-0001", "-60.00")));
        gate.orders.requireIssuable(invoiceOf(id), editedSlotfactuur, accepted, deducted);
        assertEquals("Deze factuur wijkt af van de versie waarmee de klant akkoord ging. Zet de factuur terug gelijk aan die versie, "
                        + "of verwijder het concept, maak een nieuwe kopie van de offerte en verstuur die ter goedkeuring.",
                refusal(() -> gate.orders.requireIssuable(invoiceOf(id), asOrdered, accepted, BigDecimal.ZERO)),
                "the approved version decides, no longer what was first ordered");
    }

    @Test @TestTransaction
    void theCustomerApprovesOnlyTheVersionThatWasMailed() {
        Gate gate = gate(staff("emre", "Emre"));
        PricedOrder mailed = ordered();
        PricedOrder changed = priced(List.of(line(12, "ER-RED", 96, "1.85", "5", "168.72")), "55.00", "21", List.of());
        gate.orders.requireAcceptable(order().id(IDS.incrementAndGet()).build(), changed);

        long id = placed(gate, 1);
        SalesOrder sent = order().id(id).status(QuoteStatus.VERZONDEN).sentAt(Instant.now()).build();
        gate.orders.requireAcceptable(sent, changed);
        gate.orders.recordSent(id, WebOrderTerms.of(mailed));
        gate.orders.requireAcceptable(sent, mailed);
        assertEquals("Deze offerte wordt momenteel bijgewerkt. De nieuwe versie is pas zichtbaar nadat Enrosed ze opnieuw heeft verstuurd.",
                refusal(() -> gate.orders.requireAcceptable(sent, changed)));
    }

    @Test @TestTransaction
    void splitPartnerDealDeleteAndReopenFollowTheOrder() {
        Gate gate = gate(staff("emre", "Emre"));
        long id = placed(gate, 1);
        long plain = IDS.incrementAndGet();
        String split = "Een websitebestelling splits je pas nadat de klant akkoord ging. "
                + "Verstuur ze ter goedkeuring; maak na het akkoord de conceptfactuur en splits die.";
        SalesOrder concept = order().id(id).build();
        SalesOrder accepted = order().id(id).status(QuoteStatus.GEACCEPTEERD).build();

        assertEquals(split, refusal(() -> gate.orders.requireSplittable(concept, null)));
        assertEquals(split, refusal(() -> gate.orders.requireSplittable(invoiceOf(id), concept)));
        gate.orders.requireSplittable(accepted, null);
        gate.orders.requireSplittable(invoiceOf(id), accepted);
        gate.orders.requireSplittable(order().id(plain).build(), null);
        gate.orders.requireSplittable(invoiceOf(plain), order().id(plain).build());
        gate.orders.requireSplittable(invoiceOf(id), null);

        assertEquals("Een websitebestelling koppel je niet aan een partnerdeal: de klant zou ze niet meer zien onder "
                + "Mijn bestellingen. Maak een nieuwe kopie en koppel die.", refusal(() -> gate.orders.requireNoPartnerDeal(accepted)));
        gate.orders.requireNoPartnerDeal(order().id(plain).build());

        String delete = "Een websitebestelling verwijder je niet zolang ze niet geannuleerd is. "
                + "Annuleer ze: dan ziet de klant dat onder Mijn bestellingen.";
        assertEquals(delete, refusal(() -> gate.orders.requireDeletable(concept)));
        assertEquals(delete, refusal(() -> gate.orders.requireDeletable(accepted)));
        gate.orders.requireDeletable(order().id(id).status(QuoteStatus.GEANNULEERD).build());
        gate.orders.requireDeletable(order().id(id).status(QuoteStatus.AFGEWEZEN).build());
        gate.orders.requireDeletable(order().id(plain).build());

        SalesOrder cancelled = order().id(id).status(QuoteStatus.GEANNULEERD).build();
        gate.orders.requireReopenable(cancelled);
        gate.orders.requireReopenable(order().id(plain).status(QuoteStatus.GEANNULEERD).build());
        gate.orders.recordCustomerCancel(id);
        assertEquals("Deze bestelling is door de klant geannuleerd. Maak een nieuwe offerte als de klant toch wil bestellen.",
                refusal(() -> gate.orders.requireReopenable(cancelled)));
    }

    // ------------------------------------------------------------------------------------------ fixtures

    /** WebOrders on the real table, with a named actor and the request state of one staff call. */
    private final class Gate {
        final WebOrders orders = new WebOrders();
        final List<QuoteEvent> events = new ArrayList<>();
        final StaffWebOrderRevision presented = new StaffWebOrderRevision();
        final Event<WebOrderEvents.Taken> taken;

        @SuppressWarnings("unchecked")
        Gate(ActorRef who) {
            taken = mock(Event.class);
            orders.entities = em;
            orders.actor = new CurrentActor() {
                @Override public ActorRef current() { return who; }
            };
            orders.events = new SalesRepositories.Events() {
                @Override public List<QuoteEvent> findByOrder(long salesOrderId) { return events; }
                @Override public QuoteEvent add(QuoteEvent event) { events.add(event); return event; }
                @Override public void deleteByOrder(long salesOrderId) { events.clear(); }
            };
            orders.staffRevision = mock(Instance.class);
            when(orders.staffRevision.isResolvable()).thenReturn(true);
            when(orders.staffRevision.get()).thenReturn(presented);
            orders.taken = taken;
        }
    }

    private Gate gate(ActorRef who) {
        return new Gate(who);
    }

    private static ActorRef staff(String username, String displayName) {
        return new ActorRef(username, displayName);
    }

    private long placed(Gate gate, int revision) {
        return placed(gate, revision, "{}", "A");
    }

    private long placed(Gate gate, int revision, String snapshotJson, String orderedTerms) {
        long id = IDS.incrementAndGet();
        gate.orders.create(id, CUSTOMER, 31L, "buyer@example.com", "NL", snapshotJson, orderedTerms);
        em.find(SalesWebOrderEntity.class, id).revision = revision;
        em.flush();
        return id;
    }

    private static SalesOrder invoiceOf(long quoteId) {
        return order().id(quoteId + 1_000_000_000L).docType(DocumentType.FACTUUR).sourceQuoteId(quoteId).build();
    }

    private static String refusal(Runnable action) {
        BusinessRuleException refused = assertThrows(BusinessRuleException.class, action::run);
        assertFalse(refused instanceof WebOrderChangedException);
        return refused.getMessage();
    }

    private static Row row(long id, int revision) {
        return new Row(id, CUSTOMER, 31L, "buyer@example.com", "NL", revision, Instant.now(), null, null, null, null,
                null, null, null, null, null, null, null, null, null);
    }

    private static Row taken(Row row) {
        return new Row(row.salesOrderId(), row.customerId(), row.accountId(), row.accountEmail(), row.language(),
                row.revision(), row.placedAt(), null, null, null, Instant.now(), "Emre", "KNOP", row.orderedTerms(),
                row.sentTerms(), row.acceptedTerms(), null, null, null, null);
    }

    private static Row cancelled(Row row) {
        return new Row(row.salesOrderId(), row.customerId(), row.accountId(), row.accountEmail(), row.language(),
                row.revision() + 1, row.placedAt(), null, null, Instant.now(), null, null, null, row.orderedTerms(),
                row.sentTerms(), row.acceptedTerms(), null, null, null, null);
    }

    private static Row terms(Row row, String ordered, String sent, String accepted) {
        return new Row(row.salesOrderId(), row.customerId(), row.accountId(), row.accountEmail(), row.language(),
                row.revision(), row.placedAt(), null, null, null, null, null, null, ordered, sent, accepted, null, null,
                null, null);
    }
}
