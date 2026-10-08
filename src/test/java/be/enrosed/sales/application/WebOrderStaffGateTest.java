package be.enrosed.sales.application;

import be.enrosed.account.CustomerAccountEntity;
import be.enrosed.catalog.adapter.out.persistence.ProductEntity;
import be.enrosed.catalog.domain.PublicationState;
import be.enrosed.sales.adapter.in.rest.SalesOrderResource;
import be.enrosed.sales.adapter.out.persistence.SalesCustomerMessageEntity;
import be.enrosed.sales.adapter.out.persistence.SalesEntities.SalesOrderEntity;
import be.enrosed.sales.adapter.out.persistence.SalesOrderDeliveryEntity;
import be.enrosed.sales.adapter.out.persistence.SalesWebOrderEntity;
import be.enrosed.sales.application.WebOrders.Row;
import be.enrosed.sales.application.WebOrders.TermsState;
import be.enrosed.sales.application.port.out.SalesRepositories;
import be.enrosed.sales.domain.Customer;
import be.enrosed.sales.domain.FreightPricingStrategy;
import be.enrosed.sales.domain.FreightState;
import be.enrosed.sales.domain.PricedOrder;
import be.enrosed.sales.domain.QuoteEvent;
import be.enrosed.sales.domain.QuoteStatus;
import be.enrosed.sales.domain.SalesExtraLine;
import be.enrosed.sales.domain.SalesOrder;
import be.enrosed.sales.domain.SalesOrderLine;
import be.enrosed.shared.BusinessRuleException;
import be.enrosed.shared.Language;
import be.enrosed.shipping.application.CarrierRepository;
import be.enrosed.shipping.domain.Carrier;
import be.enrosed.shipping.domain.CarrierLane;
import be.enrosed.shipping.domain.CarrierTier;
import be.enrosed.shipping.domain.CarrierZone;
import io.quarkus.arc.Arc;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A website order of a logged-in customer in the hands of staff, against the
 * real tables: the gate every staff mutation passes after the document lock,
 * the rule that only what the customer ordered or approved is invoiced and
 * issued, and the address of the order in freight and on the packing slip.
 * The customer side is played with the primitives the customer endpoints use.
 */
@QuarkusTest
@TestSecurity(user = "emre", roles = "admin")
public class WebOrderStaffGateTest {
    @Inject SalesOrderService sales;
    @Inject QuoteService quotes;
    @Inject SalesAdvanceBillingService advances;
    @Inject SalesSplits splits;
    @Inject WebOrders webOrders;
    @Inject WebOrderDeliveries deliveries;
    @Inject StaffWebOrderRevision presented;
    @Inject NotificationService notifications;
    @Inject SalesOrderResource resource;
    @Inject CustomerService customers;
    @Inject EntityManager em;
    @Inject WebsiteQuoteMailNotifier teamMails;
    @Inject io.quarkus.mailer.MockMailbox mailbox;

    private final Shop shop = new Shop();

    @AfterEach
    void removeRows() {
        presented.set(null);
        shop.remove();
    }

    // ------------------------------------------------------------------------------------------ the gate

    @Test
    void everyStaffMutationTakesAnUntouchedOrderOnceAndAutomatically() {
        Map<String, Consumer<Shop.Placed>> mutations = new LinkedHashMap<>();
        mutations.put("update", order -> sales.update(order.id(), order(order.id())));
        mutations.put("shipping", order -> sales.updateShipping(order.id(), shipping(order(order.id()), "95.00")));
        mutations.put("freight", order -> sales.updateFreight(order.id(), FreightState.BEREKEND, new BigDecimal("95.00")));
        mutations.put("delivery terms", order -> sales.updateDeliveryWeeks(order.id(),
                List.of(new SalesOrderService.DeliveryWeekChange(order.productId(), "2027-W02"))));
        mutations.put("send", order -> quotes.send(order.id(), null));
        mutations.put("cancel", order -> quotes.cancel(order.id(), null, false));
        mutations.put("invoice", order -> sales.createInvoiceFrom(order.id()));
        mutations.put("advance invoice", order -> advances.createAdvanceInvoice(order.id(),
                new SalesAdvanceBillingService.AdvanceRequest(new BigDecimal("30"), null, null)));
        mutations.put("archive", order -> sales.archive(order.id()));

        mutations.forEach((name, mutation) -> {
            Shop.Placed order = shop.place();
            mutation.accept(order);

            Row row = row(order.id());
            assertNotNull(row.processingStartedAt(), name + " takes the order into processing");
            assertEquals(WebOrders.TRIGGER_AUTOMATIC, row.processingTrigger(), name);
            assertEquals("emre", row.processingStartedBy(), name);
            List<QuoteEvent> taken = taken(order.id());
            assertEquals(1, taken.size(), name + " takes it once");
            assertEquals("In verwerking genomen (automatisch bij een wijziging door emre)", taken.getFirst().summary());
            assertFalse(taken.getFirst().byCustomer());
            assertEquals(1, row.revision(), "the revision moves through the customer only");
        });
    }

    @Test
    void aStaleOrMissingRevisionRefusesEveryMutationAndLeavesTheOrderAsTheCustomerLeftIt() {
        Map<String, Consumer<Shop.Placed>> mutations = new LinkedHashMap<>();
        mutations.put("update", order -> sales.update(order.id(), shop.edited(order(order.id()), 36, "10.00", "120.00")));
        mutations.put("shipping", order -> sales.updateShipping(order.id(), shipping(order(order.id()), "95.00")));
        mutations.put("freight", order -> sales.updateFreight(order.id(), FreightState.BEREKEND, new BigDecimal("95.00")));
        mutations.put("delivery terms", order -> sales.updateDeliveryWeeks(order.id(),
                List.of(new SalesOrderService.DeliveryWeekChange(order.productId(), "2027-W02"))));
        mutations.put("send", order -> quotes.send(order.id(), null));
        mutations.put("cancel", order -> quotes.cancel(order.id(), null, false));
        mutations.put("invoice", order -> sales.createInvoiceFrom(order.id()));
        mutations.put("advance invoice", order -> advances.createAdvanceInvoice(order.id(),
                new SalesAdvanceBillingService.AdvanceRequest(new BigDecimal("30"), null, null)));
        mutations.put("archive", order -> sales.archive(order.id()));
        mutations.put("delete", order -> sales.delete(order.id()));
        mutations.put("take", order -> sales.takeIntoProcessing(order.id()));

        Shop.Placed order = shop.place();
        assertEquals(2, shop.customerChanges(order.id(), 144));
        SalesOrder asChanged = order(order.id());
        Row rowAsChanged = row(order.id());

        mutations.forEach((name, mutation) -> {
            presented.set(1);
            WebOrderChangedException stale = assertThrows(WebOrderChangedException.class, () -> mutation.accept(order), name);
            assertEquals(2, stale.currentRevision());
            assertFalse(stale.missingParameter());
            assertEquals("De klant heeft deze bestelling intussen gewijzigd of geannuleerd. Je wijzigingen zijn niet opgeslagen; laad de laatste versie.",
                    stale.getMessage());

            presented.set(null);
            WebOrderChangedException old = assertThrows(WebOrderChangedException.class, () -> mutation.accept(order), name);
            assertTrue(old.missingParameter(), "a screen from before website orders, at revision 2");
            assertEquals("De klant heeft deze websitebestelling intussen gewijzigd (versie 2). Dit scherm is verouderd; laad de laatste versie van de bestelling voor je verdergaat.",
                    old.getMessage());

            assertEquals(asChanged, order(order.id()), name + " wrote nothing");
            assertEquals(rowAsChanged, row(order.id()), name + " left the marker empty");
            assertTrue(taken(order.id()).isEmpty());
        });
    }

    @Test
    void theRevisionCheckDoesNotEndWithTheMarker() {
        Shop.Placed order = shop.place();
        shop.customerChanges(order.id(), 144);
        presented.set(2);
        sales.update(order.id(), order(order.id()));
        assertNotNull(row(order.id()).processingStartedAt(), "a colleague whose screen shows revision 2 takes the order");

        /* A screen opened before the customer's change would replace the customer's lines with its own. */
        SalesOrder fromRevisionOne = shop.edited(order(order.id()), 120, "10.00", "120.00");
        presented.set(1);
        assertFalse(assertThrows(WebOrderChangedException.class, () -> sales.update(order.id(), fromRevisionOne)).missingParameter());
        presented.set(null);
        assertTrue(assertThrows(WebOrderChangedException.class, () -> sales.update(order.id(), fromRevisionOne)).missingParameter());
        presented.set(1);
        assertThrows(WebOrderChangedException.class, () -> quotes.cancel(order.id(), null, false));

        assertEquals(144, order(order.id()).lines().getFirst().quantity(), "the lines stay the customer's");
        assertEquals(QuoteStatus.CONCEPT, order(order.id()).status());
        presented.set(2);
        assertEquals(132, sales.update(order.id(), shop.edited(order(order.id()), 132, "10.00", "120.00")).lines().getFirst().quantity());
        assertEquals(1, taken(order.id()).size());
    }

    @Test
    void theWebsiteIntakeAFailedSaveAndAPlainQuoteLeaveNoMarker() throws Exception {
        Shop.Placed order = shop.place();

        /* The public intake saves without a staff identity: outside a staff request the actor is the system. */
        SalesOrder asPlaced = order(order.id());
        CompletableFuture.runAsync(() -> sales.update(order.id(), asPlaced)).get(30, TimeUnit.SECONDS);
        assertNull(row(order.id()).processingStartedAt(), "the system never takes an order");

        SalesOrder invalid = order(order.id());
        SalesOrder withBadDiscount = new SalesOrder(invalid.id(), invalid.number(), invalid.customerId(), invalid.countryCode(),
                invalid.orderDate(), invalid.validUntil(), invalid.status(), invalid.incoterm(), invalid.paymentTerms(),
                invalid.notes(), invalid.markupMode(), invalid.orderMarkupPct(), new BigDecimal("150"), "Te veel",
                invalid.portalToken(), invalid.sentAt(), invalid.viewedAt(), invalid.viewCount(), invalid.decidedAt(),
                invalid.signedByName(), invalid.customerMessage(), invalid.internalNotes(), invalid.deliveryTerms(),
                invalid.freight(), invalid.manualFreightEur(), invalid.loadMode(), invalid.palletProfile(),
                invalid.maxPalletHeightCm(), invalid.freightPricingStrategy(), invalid.freightRatePerCbmEur(),
                invalid.freightCarrierId(), invalid.freightCarrierExtraEur(), invalid.docType(), invalid.invoiceDueDate(),
                invalid.paidAt(), invalid.sourceQuoteId(), invalid.goodsShippedAt(), invalid.lines(), invalid.pallets())
                .carrying(invalid);
        assertThrows(BusinessRuleException.class, () -> sales.update(order.id(), withBadDiscount));
        assertNull(row(order.id()).processingStartedAt(), "a staff save that fails rolls the marker back");
        assertTrue(taken(order.id()).isEmpty());
        assertTrue(WebOrders.customerMayChange(order(order.id()), row(order.id()), false));

        SalesOrder plain = sales.create(order.customerId(), "BE", "DAP");
        sales.update(plain.id(), order(plain.id()));
        assertTrue(webOrders.find(plain.id()).isEmpty(), "a document that is no website order stays one");
        assertTrue(taken(plain.id()).isEmpty());
        assertEquals("Dit document is geen websitebestelling van een ingelogde klant.",
                assertThrows(BusinessRuleException.class, () -> sales.takeIntoProcessing(plain.id())).getMessage());
    }

    @Test
    void theCustomerPathsNeverTakeAnOrderWhoeverIsSignedIn() {
        /* This test runs with a staff identity: "customer" is said by the method called, never inferred. */
        Shop.Placed order = shop.place();
        assertEquals(2, shop.customerChanges(order.id(), 96));
        Row changed = row(order.id());
        assertNull(changed.processingStartedAt());
        assertEquals(96, order(order.id()).lines().getFirst().quantity());
        assertEquals("Graag voor vrijdag", shop.inTransaction(() -> em.find(SalesCustomerMessageEntity.class, order.id())).message,
                "the customer's remark is replaced, not refused");

        assertEquals(3, shop.customerCancels(order.id()));
        Row cancelled = row(order.id());
        assertNull(cancelled.processingStartedAt());
        assertNotNull(cancelled.customerCancelledAt());
        SalesOrder document = order(order.id());
        assertEquals(QuoteStatus.GEANNULEERD, document.status());
        assertNotNull(document.decidedAt());
        assertNull(document.portalToken(), "no portal link is made for a customer's own cancellation");
        QuoteEvent event = history(order.id()).stream()
                .filter(e -> e.type() == QuoteEvent.Type.GEANNULEERD).findFirst().orElseThrow();
        assertTrue(event.byCustomer());
        assertEquals("Bestelling geannuleerd door de klant", event.summary());
        assertTrue(taken(order.id()).isEmpty());

        /* A cancelled order is not taken by what staff do next, and it stays cancelled. */
        presented.set(3);
        assertEquals("De klant heeft deze bestelling geannuleerd.",
                assertThrows(BusinessRuleException.class, () -> sales.takeIntoProcessing(order.id())).getMessage());
        assertEquals("Deze bestelling is door de klant geannuleerd. Maak een nieuwe offerte als de klant toch wil bestellen.",
                assertThrows(BusinessRuleException.class, () -> quotes.reopen(order.id())).getMessage());
        assertNull(row(order.id()).processingStartedAt());
        sales.delete(order.id());
        assertTrue(documents().stream().noneMatch(listed -> order.id() == listed.id()), "staff may delete what the customer cancelled");
    }

    @Test
    void aLiveOrderIsNotDeletedSplitOrTiedToAPartnerDeal() {
        Shop.Placed order = shop.place();

        assertEquals("Een websitebestelling verwijder je niet zolang ze niet geannuleerd is. Annuleer ze: dan ziet de klant dat onder Mijn bestellingen.",
                assertThrows(BusinessRuleException.class, () -> sales.delete(order.id())).getMessage());
        assertNull(row(order.id()).processingStartedAt(), "the refused delete rolls its own take back");
        assertTrue(taken(order.id()).isEmpty());

        String noSplit = "Een websitebestelling splits je pas nadat de klant akkoord ging. Verstuur ze ter goedkeuring; maak na het akkoord de conceptfactuur en splits die.";
        SalesSplits.Request half = new SalesSplits.Request(List.of(new SalesSplits.Choice(
                order(order.id()).lines().getFirst().id(), 60)), null, "token", UUID.randomUUID().toString());
        assertEquals(noSplit, assertThrows(BusinessRuleException.class, () -> splits.preview(order.id(), half)).getMessage());
        assertEquals(noSplit, assertThrows(BusinessRuleException.class, () -> splits.split(order.id(), half)).getMessage());
        assertEquals(noSplit, shop.inTransaction(() -> splits.eligibility(order.id())).reason());
        assertNull(row(order.id()).processingStartedAt());

        /* The purchasing screens present no revision: this refusal reaches them at every revision, before the gate. */
        shop.customerChanges(order.id(), 144);
        String noDeal = "Een websitebestelling koppel je niet aan een partnerdeal: de klant zou ze niet meer zien onder Mijn bestellingen. Maak een nieuwe kopie en koppel die.";
        assertEquals(noDeal, assertThrows(BusinessRuleException.class, () -> sales.setPartnerDeal(order.id(),
                new SalesOrderService.PartnerDealRequest(987_654L, new BigDecimal("50"), null))).getMessage());
        assertNull(row(order.id()).processingStartedAt());
        assertTrue(taken(order.id()).isEmpty());

        /* Cutting a tie that is not there is an ordinary staff mutation. */
        presented.set(2);
        sales.setPartnerDeal(order.id(), new SalesOrderService.PartnerDealRequest(null, null, null));
        assertNotNull(row(order.id()).processingStartedAt());

        /* The draft invoice of an order that was never sent is the order itself: no split either. */
        SalesOrder invoice = sales.createInvoiceFrom(order.id());
        SalesSplits.Request invoiceHalf = new SalesSplits.Request(List.of(new SalesSplits.Choice(
                order(invoice.id()).lines().getFirst().id(), 72)), null, "token", UUID.randomUUID().toString());
        assertEquals(noSplit, assertThrows(BusinessRuleException.class, () -> splits.preview(invoice.id(), invoiceHalf)).getMessage());

        /* Once cancelled by Enrosed the order may go. */
        presented.set(null);
        Shop.Placed second = shop.place();
        quotes.cancel(second.id(), "Niet leverbaar", false);
        sales.delete(second.id());
        assertTrue(documents().stream().noneMatch(listed -> second.id() == listed.id()));
    }

    @Test
    void anArchivedAndRestoredOrderIsTakenAndNoLongerTheCustomersToChange() {
        Shop.Placed order = shop.place();
        assertTrue(WebOrders.customerMayChange(order(order.id()), row(order.id()), false));

        sales.archive(order.id());
        assertFalse(WebOrders.customerMayChange(order(order.id()), row(order.id()), false));
        sales.unarchive(order.id());

        assertNotNull(row(order.id()).processingStartedAt());
        assertEquals(1, taken(order.id()).size());
        assertFalse(WebOrders.customerMayChange(order(order.id()), row(order.id()), false));
        assertThrows(WebOrderRefusal.class, () -> shop.customerChanges(order.id(), 144));
    }

    // ------------------------------------------------------------------------------------------ invoiced as ordered or approved

    @Test
    void theDraftInvoiceIsComparedWithTheOrderAgainWhenItIsIssued() {
        Shop.Placed order = shop.place();
        SalesOrder invoice = sales.createInvoiceFrom(order.id());
        assertEquals(TermsState.ORDER_EQUAL, WebOrders.termsState(order(order.id()), row(order.id()),
                WebOrderTerms.of(price(order(order.id())))));
        assertEquals(row(order.id()).orderedTerms(), WebOrderTerms.of(price(invoice)), "the invoice is the order");

        sales.update(invoice.id(), shop.edited(order(invoice.id()), 120, "11.00", "120.00"));
        String differs = "Deze factuur wijkt af van wat de klant bestelde: Prijs " + order.sku()
                + ": besteld € 10,00, nu € 11,00; Totaal excl. btw: besteld € 1.320,00, nu € 1.440,00. "
                + "Zet de factuur terug gelijk aan de bestelling, of verwijder het concept en verstuur de bestelling ter goedkeuring.";
        assertEquals(differs, assertThrows(BusinessRuleException.class, () -> sales.issueInvoice(invoice.id())).getMessage());
        assertEquals(differs, assertThrows(BusinessRuleException.class, () -> sales.markInvoiceSent(invoice.id())).getMessage());
        assertEquals(differs, assertThrows(BusinessRuleException.class, () -> quotes.send(invoice.id(), null)).getMessage());
        assertEquals(QuoteStatus.CONCEPT, order(invoice.id()).status());

        sales.update(invoice.id(), shop.edited(order(invoice.id()), 120, "10.00", "120.00"));
        assertEquals(QuoteStatus.UITGEREIKT, sales.issueInvoice(invoice.id()).status());

        /* An invoice that is issued is never held back by what changed afterwards. */
        shop.inTransaction(() -> em.find(SalesWebOrderEntity.class, order.id()).orderedTerms = "a price list changed since");
        assertEquals(QuoteStatus.VERZONDEN, quotes.send(invoice.id(), null).status());
    }

    @Test
    void anAdvanceAndItsSlotfactuurOnAnUnchangedOrderAreIssued() {
        Shop.Placed order = shop.place();
        SalesOrder advance = advances.createAdvanceInvoice(order.id(),
                new SalesAdvanceBillingService.AdvanceRequest(new BigDecimal("30"), null, null));
        assertNotNull(row(order.id()).processingStartedAt());
        assertEquals(QuoteStatus.UITGEREIKT, sales.issueInvoice(advance.id()).status(), "an advance claims its amount, not the order");

        SalesOrder slot = sales.createInvoiceFrom(order.id());
        assertTrue(slot.extraLines().stream().anyMatch(line -> line.total().signum() < 0), "the advance is deducted on a line of its own");
        assertEquals(QuoteStatus.UITGEREIKT, sales.issueInvoice(slot.id()).status(),
                "with its deductions added back the slotfactuur is the order");
    }

    @Test
    void theCustomerApprovesOnlyTheVersionThatWasMailedAndTheInvoiceMustEqualIt() {
        Shop.Placed order = shop.place("buyer@login.example");
        sales.update(order.id(), shop.edited(order(order.id()), 120, "10.00", "140.00"));
        assertEquals(TermsState.ORDER_DIFFERENT, view(order.id()).webOrder().termsState());
        assertEquals(List.of("Vracht: besteld € 120,00, nu € 140,00", "Totaal excl. btw: besteld € 1.320,00, nu € 1.340,00"),
                view(order.id()).webOrder().differences());

        SalesOrder sent = quotes.send(order.id(), null);
        String token = sent.portalToken();
        Row mailed = row(order.id());
        assertEquals(WebOrderTerms.of(price(sent)), mailed.sentTerms());
        assertEquals(TermsState.AWAITING_APPROVAL, view(order.id()).webOrder().termsState());
        QuoteEvent sentEvent = history(order.id()).stream()
                .filter(e -> e.type() == QuoteEvent.Type.VERSTUURD).findFirst().orElseThrow();
        assertEquals("Naar buyer@login.example, kopie aan " + shop.recordEmail(order.customerId()), sentEvent.detail(),
                "the history names who was mailed: the login that ordered, and the record in copy");
        assertEquals("Deze websitebestelling wacht nog op het akkoord van de klant op de verstuurde versie. Factureren kan zodra de klant akkoord gaat.",
                assertThrows(BusinessRuleException.class, () -> sales.createInvoiceFrom(order.id())).getMessage());

        /* Freight changed on the sent order and not mailed again: the link refuses, and staff are prompted. */
        sales.updateFreight(order.id(), FreightState.BEREKEND, new BigDecimal("160.00"));
        assertEquals(TermsState.RESEND_REQUIRED, view(order.id()).webOrder().termsState());
        assertEquals("Deze offerte wordt momenteel bijgewerkt. De nieuwe versie is pas zichtbaar nadat Enrosed ze opnieuw heeft verstuurd.",
                assertThrows(BusinessRuleException.class, () -> quotes.acceptByCustomer(token, "An Peeters", null)).getMessage());
        assertEquals(QuoteStatus.VERZONDEN, order(order.id()).status());
        assertNull(row(order.id()).acceptedTerms());
        assertEquals("De cijfers van deze websitebestelling zijn gewijzigd sinds de versie die de klant kreeg. Verstuur ze opnieuw; factureren kan zodra de klant akkoord gaat.",
                assertThrows(BusinessRuleException.class, () -> sales.createInvoiceFrom(order.id())).getMessage());
        var prompt = feed().items().stream()
                .filter(item -> order.id() == item.orderId() && "Websitebestelling opnieuw versturen".equals(item.title())).toList();
        assertEquals(1, prompt.size());
        assertTrue(prompt.getFirst().actionNeeded());
        assertEquals("De cijfers zijn gewijzigd sinds de verstuurde versie; de klant kan niet goedkeuren tot je opnieuw verstuurt.",
                prompt.getFirst().detail());

        quotes.send(order.id(), null);
        assertEquals(TermsState.AWAITING_APPROVAL, view(order.id()).webOrder().termsState());
        assertTrue(feed().items().stream().noneMatch(item -> order.id() == item.orderId()
                && "Websitebestelling opnieuw versturen".equals(item.title())), "after the resend the prompt is gone");

        SalesOrder accepted = quotes.acceptByCustomer(token, "An Peeters", null);
        assertEquals(QuoteStatus.GEACCEPTEERD, accepted.status());
        assertEquals(WebOrderTerms.of(price(accepted)), row(order.id()).acceptedTerms());
        assertEquals(TermsState.APPROVED, view(order.id()).webOrder().termsState());
        assertEquals(1, taken(order.id()).size(), "the approval through the link is no staff mutation");

        /* Path B: the invoice of an approved order equals the approved version when it is issued. */
        SalesOrder invoice = sales.createInvoiceFrom(order.id());
        sales.updateFreight(invoice.id(), FreightState.BEREKEND, new BigDecimal("175.00"));
        assertEquals("Deze factuur wijkt af van de versie waarmee de klant akkoord ging. Zet de factuur terug gelijk aan die versie, "
                        + "of verwijder het concept, maak een nieuwe kopie van de offerte en verstuur die ter goedkeuring.",
                assertThrows(BusinessRuleException.class, () -> sales.issueInvoice(invoice.id())).getMessage());
        sales.updateFreight(invoice.id(), FreightState.BEREKEND, new BigDecimal("160.00"));

        /* After the approval the order is delivered in two parts when staff split the draft invoice, as before this round. */
        SalesSplits.Request selection = new SalesSplits.Request(List.of(new SalesSplits.Choice(
                order(invoice.id()).lines().getFirst().id(), 60)), null, new BigDecimal("80.00"), new BigDecimal("80.00"),
                null, null, null, UUID.randomUUID().toString());
        String previewToken = splits.preview(invoice.id(), selection).previewToken();
        var parts = splits.split(invoice.id(), new SalesSplits.Request(selection.lines(), null, selection.currentFreightEur(),
                selection.laterFreightEur(), null, null, previewToken, selection.requestId()));
        assertEquals(60, parts.current().lines().getFirst().quantity());
        assertEquals(60, parts.later().lines().getFirst().quantity());
        assertEquals(QuoteStatus.UITGEREIKT, sales.issueInvoice(parts.current().id()).status());
    }

    @Test
    void aPlainQuoteIsAcceptedAsBeforeAndAnAcceptAfterAReopenIsRefused() {
        Shop.Placed order = shop.place();
        SalesOrder plain = sales.create(order.customerId(), "BE", "DAP");
        sales.update(plain.id(), shop.edited(order(plain.id()), order.productId(), 120, "10.00", "120.00"));
        String plainToken = quotes.send(plain.id(), null).portalToken();
        assertEquals(QuoteStatus.GEACCEPTEERD, quotes.acceptByCustomer(plainToken, "An Peeters", null).status());
        assertTrue(webOrders.find(plain.id()).isEmpty());

        /* Sent, then reopened by staff: the link must not approve what is being changed. */
        String token = quotes.send(order.id(), null).portalToken();
        quotes.reopen(order.id());
        assertThrows(BusinessRuleException.class, () -> quotes.acceptByCustomer(token, "An Peeters", null));
        assertEquals(QuoteStatus.CONCEPT, order(order.id()).status());
        assertEquals(TermsState.RESEND_REQUIRED, view(order.id()).webOrder().termsState());
        assertEquals("Deze websitebestelling is al ter goedkeuring verstuurd en daarna heropend. Verstuur de nieuwe versie; factureren kan zodra de klant akkoord gaat.",
                assertThrows(BusinessRuleException.class, () -> sales.createInvoiceFrom(order.id())).getMessage(),
                "also when the figures are the ordered ones again");
    }

    @Test
    void aCancellationByEnrosedIsRecordedForTheLoginThatOrdered() {
        Shop.Placed order = shop.place("buyer@login.example");
        quotes.cancel(order.id(), "Niet meer leverbaar", true);
        QuoteEvent event = history(order.id()).stream()
                .filter(e -> e.type() == QuoteEvent.Type.GEANNULEERD).findFirst().orElseThrow();
        assertEquals("Offerte geannuleerd, klant verwittigd op buyer@login.example", event.summary());
        assertFalse(event.byCustomer());
        assertNull(row(order.id()).customerCancelledAt(), "cancelled by Enrosed, not by the customer");
    }

    // ------------------------------------------------------------------------------------------ the address of the order

    @Test
    void freightAndThePackingSlipFollowTheAddressOfTheOrderOnEveryDocumentMadeFromIt() throws Exception {
        long carrier = shop.carrier();
        Shop.Placed order = shop.placeWithCarrier(carrier);
        BigDecimal ordered = price(order(order.id())).totals().shippingTotal();
        assertEquals(0, new BigDecimal("150.00").compareTo(ordered), "the zone of the order's postal code, not the record's: " + ordered);
        assertTrue(packingSlip(order.id()).contains("Industrieweg 1") && packingSlip(order.id()).contains("3980 Tessenderlo")
                && packingSlip(order.id()).contains("Jan Besteller"), packingSlip(order.id()));
        assertFalse(packingSlip(order.id()).contains("Bloemenlaan 5"), "never the address of the customer record");

        /* "Nieuwe kopie": a plain website concept that keeps the ordered address. */
        SalesOrder copy = sales.duplicate(order.id());
        assertEquals(order.id(), shop.inTransaction(() -> em.find(SalesOrderDeliveryEntity.class, copy.id())).copiedFromOrderId);
        assertEquals(0, ordered.compareTo(price(order(copy.id())).totals().shippingTotal()));
        assertNull(view(copy.id()).webOrder(), "a copy is no website order");
        assertEquals("Industrieweg 1", view(copy.id()).delivery().address());
        assertTrue(view(copy.id()).delivery().differsFromCustomerRecord());
        assertTrue(packingSlip(copy.id()).contains("Industrieweg 1"));
        assertNull(row(order.id()).processingStartedAt(), "copying reads the order and takes nothing");

        SalesOrder invoice = sales.createInvoiceFrom(order.id());
        assertEquals(0, ordered.compareTo(price(invoice).totals().shippingTotal()), "the invoice is priced on the same address");
        assertNull(view(invoice.id()).webOrder());
        assertEquals("3980", view(invoice.id()).delivery().postalCode());
        assertEquals("BE", view(invoice.id()).delivery().countryCode());
        assertEquals("Jan Besteller", view(invoice.id()).delivery().contactName());
        SalesOrder creditNote = creditNoteOn(invoice);
        assertTrue(shop.inTransaction(() -> deliveries.forDocument(creditNote)).isPresent(), "a credit note reads the row of its invoice");
        assertEquals(List.of(price(order(order.id())).totals().shippingTotal(), price(order(copy.id())).totals().shippingTotal()),
                sales.priceAll(List.of(order(order.id()), order(copy.id()))).stream()
                        .map(priced -> priced.totals().shippingTotal()).toList(), "a page of documents prices like each of them");

        /* Re-linked to another customer the address is nobody's: freight, screen and slip follow the new record. */
        long other = shop.customer("Andere Klant BV", "Havenlaan 9", "2000", "Antwerpen");
        SalesOrder before = order(copy.id());
        sales.update(copy.id(), shop.relinked(before, other));
        assertEquals(0, new BigDecimal("100.00").compareTo(price(order(copy.id())).totals().shippingTotal()));
        assertNull(view(copy.id()).delivery());
        String slip = packingSlip(copy.id());
        assertTrue(slip.contains("Havenlaan 9") && !slip.contains("Industrieweg 1"), slip);
    }

    @Test
    void theIntakesPickupSnapshotIsStillDroppedByASave() {
        /* Pins today's behaviour (reported, not fixed here): update() never stores the pickup location it is handed,
           which is why the delivery row of a website order carries the pickup label and address itself. */
        Shop.Placed order = shop.place();
        SalesOrder stored = order(order.id());
        SalesOrder withPickup = new SalesOrder(stored.id(), stored.number(), stored.customerId(), stored.countryCode(),
                stored.orderDate(), stored.validUntil(), stored.status(), "EXW", stored.paymentTerms(), stored.notes(),
                stored.markupMode(), stored.orderMarkupPct(), stored.extraDiscountPct(), stored.extraDiscountLabel(),
                stored.portalToken(), stored.sentAt(), stored.viewedAt(), stored.viewCount(), stored.decidedAt(),
                stored.signedByName(), stored.customerMessage(), stored.internalNotes(), stored.deliveryTerms(),
                stored.freight(), stored.manualFreightEur(), stored.loadMode(), stored.palletProfile(),
                stored.maxPalletHeightCm(), stored.freightPricingStrategy(), stored.freightRatePerCbmEur(),
                stored.freightCarrierId(), stored.freightCarrierExtraEur(), stored.docType(), stored.invoiceDueDate(),
                stored.paidAt(), stored.sourceQuoteId(), stored.goodsShippedAt(), stored.lines(), stored.pallets(),
                new be.enrosed.sales.domain.PickupLocationSnapshot(7L, "Magazijn Mol", "Vekeblok 17, 2400 Mol", null)).carrying(stored);
        assertNotNull(withPickup.pickupLocation());
        SalesOrder saved = sales.update(order.id(), withPickup);
        assertEquals("EXW", saved.incoterm());
        assertNull(saved.pickupLocation());
        assertNull(order(order.id()).pickupLocation());
    }

    // ------------------------------------------------------------------------------------------ what staff are told

    @Test
    void theFeedSaysWhereAnOrderStandsAndALegacyRequestReadsAsBefore() {
        Shop.Placed order = shop.place();
        long legacy = shop.legacyRequests(1).getFirst();

        NotificationService.Notification fresh = feedItem(order.id());
        assertEquals(NotificationService.Kind.WEBSITE_AANVRAAG, fresh.kind(), "no new kind for the screens to learn");
        assertEquals("Nieuwe websitebestelling", fresh.title());
        assertEquals("De klant kan nog wijzigen. Controleer en neem in verwerking.", fresh.detail());
        assertTrue(fresh.actionNeeded());
        assertEquals("Nieuwe websiteaanvraag", feedItem(legacy).title());
        assertEquals("Controleer aantallen, prijzen, btw en levering en stuur daarna de offerte.", feedItem(legacy).detail());

        shop.customerChanges(order.id(), 144);
        assertEquals("Nieuwe websitebestelling", feedItem(order.id()).title());
        assertEquals("Door de klant gewijzigd (versie 2). Controleer opnieuw en neem in verwerking.", feedItem(order.id()).detail());

        presented.set(2);
        sales.update(order.id(), order(order.id()));
        assertEquals("Websitebestelling in verwerking", feedItem(order.id()).title());
        assertEquals("Ongewijzigd: factuur maken. Gewijzigd: versturen ter goedkeuring.", feedItem(order.id()).detail());
        assertTrue(feedItem(order.id()).actionNeeded());

        /* A cancellation by the customer is news for two weeks, never work. */
        Shop.Placed cancelled = shop.place();
        shop.customerCancels(cancelled.id());
        NotificationService.Notification news = feedItem(cancelled.id());
        assertEquals(NotificationService.Kind.AFGEWEZEN, news.kind());
        assertEquals("Bestelling geannuleerd door de klant", news.title());
        assertEquals("De klant annuleerde websitebestelling " + cancelled.number() + " op de website.", news.detail());
        assertFalse(news.actionNeeded());
        assertEquals(row(cancelled.id()).customerCancelledAt(), news.at());
        shop.inTransaction(() -> em.find(SalesWebOrderEntity.class, cancelled.id()).customerCancelledAt =
                Instant.now().minus(java.time.Duration.ofDays(15)));
        assertTrue(feed().items().stream().noneMatch(item -> cancelled.id() == item.orderId()));
    }

    @Test
    void theTeamIsPushedAndMailedWhenACustomerPlacesChangesOrCancelsAnOrder() {
        var phones = org.mockito.Mockito.mock(be.enrosed.push.WebPushNotifier.class);
        WebsiteQuotePushNotifier push = new WebsiteQuotePushNotifier(phones);
        push.afterOrderPlaced(new WebOrderEvents.Placed(41L, "OF-2026-0041"));
        push.afterOrderChanged(new WebOrderEvents.Changed(41L, "OF-2026-0041", 3, "ER-RED: 4 → 6 dozen, " + "x".repeat(200)));
        push.afterOrderCancelled(new WebOrderEvents.Cancelled(41L, "OF-2026-0041"));
        org.mockito.Mockito.verify(phones).notifyAll("sale-quote", "Nieuwe websitebestelling OF-2026-0041",
                "Klant kan nog wijzigen · neem in verwerking in Verkoop", "/sales/41");
        org.mockito.Mockito.verify(phones).notifyAll("sale-quote", "Websitebestelling OF-2026-0041 gewijzigd door de klant",
                "Versie 3 · " + ("ER-RED: 4 → 6 dozen, " + "x".repeat(200)).substring(0, 120), "/sales/41");
        org.mockito.Mockito.verify(phones).notifyAll("sale-quote", "Websitebestelling OF-2026-0041 geannuleerd door de klant",
                "De klant annuleerde de bestelling op de website", "/sales/41");
        org.mockito.Mockito.doThrow(new IllegalStateException("push unavailable")).when(phones)
                .notifyAll(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
        push.afterOrderPlaced(new WebOrderEvents.Placed(41L, "OF-2026-0041"));

        Shop.Placed order = shop.place("buyer@login.example");
        String company = customers.get(order.customerId()).company();
        mailbox.clear();
        teamMails.afterOrderPlaced(new WebOrderEvents.Placed(order.id(), order.number()));
        teamMails.afterOrderChanged(new WebOrderEvents.Changed(order.id(), order.number(), 2, "leveradres gewijzigd"));
        teamMails.afterOrderCancelled(new WebOrderEvents.Cancelled(order.id(), order.number()));
        var mails = mailbox.getMailsSentTo(org.eclipse.microprofile.config.ConfigProvider.getConfig()
                .getValue("enrosed.mail.sales-copy", String.class));
        assertEquals(List.of("Nieuwe websitebestelling " + order.number() + " · " + company,
                        "Websitebestelling " + order.number() + " gewijzigd door de klant · " + company,
                        "Websitebestelling " + order.number() + " geannuleerd door de klant · " + company),
                mails.stream().map(io.quarkus.mailer.Mail::getSubject).toList());

        String placed = mails.get(0).getHtml();
        assertTrue(placed.contains("Een ingelogde klant plaatste een bestelling via de website. De klant kan ze nog wijzigen of annuleren tot je ze in verwerking neemt."), placed);
        assertTrue(placed.contains("Levering (DAP) · Industrieweg 1, 3980 Tessenderlo"), "the address of this order: " + placed);
        assertFalse(placed.contains("Bloemenlaan 5"), "not the address of the customer record");
        assertTrue(placed.contains("Contact voor deze bestelling") && placed.contains("Jan Besteller · +32 13 00 00 00"), placed);
        assertTrue(placed.contains("Besteld via klantlogin") && placed.contains("buyer@login.example"), placed);
        assertTrue(placed.contains("Website order test") && placed.contains("10 dozen van 12"), placed);
        assertTrue(placed.contains("Graag voor donderdag"), "the customer's remark: " + placed);

        String changed = mails.get(1).getHtml();
        assertTrue(changed.contains("De klant wijzigde de bestelling op de website (versie 2): leveradres gewijzigd. Open de laatste versie voor je ze in verwerking neemt."), changed);
        assertTrue(changed.contains("Website order test"), "the lines of the new version");

        String cancelled = mails.get(2).getHtml();
        assertTrue(cancelled.contains("De klant annuleerde deze bestelling op de website voor ze in verwerking was genomen. Er hoeft niets meer te gebeuren."), cancelled);
        assertTrue(cancelled.contains("Besteld via klantlogin"), cancelled);
        assertFalse(cancelled.contains("Website order test"), "facts only: " + cancelled);
    }

    // ------------------------------------------------------------------------------------------ helpers

    private NotificationService.Notification feedItem(long orderId) {
        List<NotificationService.Notification> items = feed().items().stream().filter(item -> orderId == item.orderId()).toList();
        assertEquals(1, items.size(), "one item for the document: " + items);
        return items.getFirst();
    }

    private Row row(long id) {
        return shop.inTransaction(() -> webOrders.find(id).orElseThrow());
    }

    /** A fresh read: outside a transaction this test's own request would answer from what it read before. */
    private SalesOrder order(long id) {
        return shop.inTransaction(() -> sales.get(id));
    }

    private PricedOrder price(SalesOrder order) {
        return shop.inTransaction(() -> sales.price(order));
    }

    private List<QuoteEvent> history(long id) {
        return shop.inTransaction(() -> quotes.history(id));
    }

    private List<SalesOrder> documents() {
        return shop.inTransaction(() -> sales.list());
    }

    private NotificationService.Feed feed() {
        return shop.inTransaction(() -> notifications.feed());
    }

    private List<QuoteEvent> taken(long id) {
        return history(id).stream().filter(event -> event.type() == QuoteEvent.Type.IN_VERWERKING).toList();
    }

    private SalesOrderResource.OrderView view(long id) {
        return shop.inTransaction(() -> resource.get(id));
    }

    private String packingSlip(long id) throws Exception {
        byte[] pdf = shop.inTransaction(() -> quotes.packingSlip(id)).content();
        try (var document = Loader.loadPDF(pdf)) {
            return new PDFTextStripper().getText(document);
        }
    }

    private SalesOrder creditNoteOn(SalesOrder draftInvoice) {
        SalesOrder issued = sales.issueInvoice(draftInvoice.id());
        return sales.createCreditNote(issued.id(), new SalesOrderService.CreditNoteRequest(
                be.enrosed.sales.domain.CreditReason.PRICE_CORRECTION, List.of(),
                List.of(new SalesOrderService.CreditAmount("Correctie", BigDecimal.TEN)), false, null));
    }

    private static SalesOrderService.ShippingUpdate shipping(SalesOrder order, String fixedFreight) {
        return new SalesOrderService.ShippingUpdate(order.loadMode(), order.palletProfile(), order.maxPalletHeightCm(),
                FreightPricingStrategy.FIXED, null, new BigDecimal(fixedFreight), order.freightCarrierId(),
                order.freightCarrierExtraEur(), FreightState.BEREKEND, order.pallets());
    }

    /**
     * Committed customers, products and website orders as the customer
     * endpoints store them, and their removal. A customer change or cancel is
     * played with the same primitives those endpoints call, in their order.
     */
    public static final class Shop {
        public record Placed(long id, String number, long customerId, long productId, String sku, long accountId) {}

        private final List<Long> customerIds = new ArrayList<>();
        private final List<Long> productIds = new ArrayList<>();
        private final List<Long> loginIds = new ArrayList<>();
        private final List<Long> carrierIds = new ArrayList<>();

        private static <T> T bean(Class<T> type) {
            return Arc.container().instance(type).get();
        }

        public <T> T inTransaction(java.util.concurrent.Callable<T> work) {
            return QuarkusTransaction.requiringNew().call(work);
        }

        public void inTransaction(Runnable work) {
            QuarkusTransaction.requiringNew().run(work);
        }

        /** A Belgian customer whose record sits in Antwerp, with every field an invoice needs. */
        public long customer(String company, String address, String postalCode, String city) {
            Customer created = bean(CustomerService.class).create(new Customer(null, company + " " + UUID.randomUUID(),
                    "An Peeters", "inkoop-" + UUID.randomUUID().toString().substring(0, 12) + "@record.example", "+32 3 000 00 00",
                    "BE0123456789", "BE", Language.NL, address, postalCode, city, "DAP", null, null, null));
            customerIds.add(created.id());
            return created.id();
        }

        public String recordEmail(long customerId) {
            return bean(CustomerService.class).get(customerId).email();
        }

        /** A published product: 12 per carton, list price 10. */
        public long product() {
            long id = inTransaction(() -> {
                ProductEntity product = new ProductEntity();
                product.sku = "WEB-ORDER-" + UUID.randomUUID();
                product.name = "Website order test";
                product.active = true;
                product.websiteStatus = PublicationState.PUBLISHED;
                product.piecesPerCarton = 12;
                product.inventoryKnown = true;
                product.stockQuantity = 100_000;
                product.cartonLengthCm = new BigDecimal("40");
                product.cartonWidthCm = new BigDecimal("30");
                product.cartonHeightCm = new BigDecimal("20");
                product.cartonWeightKg = new BigDecimal("5");
                product.landedCostEur = BigDecimal.ONE;
                product.fixedSalesPriceEur = BigDecimal.TEN;
                bean(EntityManager.class).persist(product);
                bean(EntityManager.class).flush();
                return product.id;
            });
            productIds.add(id);
            return id;
        }

        /** An ACTIVE login of the customer. */
        public long login(long customerId, String email) {
            long id = inTransaction(() -> {
                CustomerAccountEntity login = new CustomerAccountEntity();
                login.customerId = customerId;
                login.email = email;
                login.status = "ACTIVE";
                login.language = "nl";
                login.createdAt = Instant.now();
                bean(EntityManager.class).persist(login);
                bean(EntityManager.class).flush();
                return login.id;
            });
            loginIds.add(id);
            return id;
        }

        /** A carrier that is nobody's default: Antwerp (20-29) costs 100 per pallet, Limburg (35-39) 150. */
        public long carrier() {
            CarrierLane lane = new CarrierLane(null, "BE", null, null, null,
                    List.of(new CarrierZone(null, "Antwerpen", "20-29", 0), new CarrierZone(null, "Limburg", "35-39", 1)),
                    List.of(new CarrierTier(null, new BigDecimal("33"), new BigDecimal("26"), null, new BigDecimal("24000"), 0,
                            List.of(new BigDecimal("100"), new BigDecimal("150")))));
            long id = bean(CarrierRepository.class).save(new Carrier(null, "WEB-ORDER-" + UUID.randomUUID().toString().substring(0, 8),
                    null, false, BigDecimal.ZERO, null, null, List.of(lane))).id();
            carrierIds.add(id);
            return id;
        }

        /** 10 cartons of 12 at 10,00 with 120,00 fixed freight, delivered in Tessenderlo; ordered through the record's own address. */
        public Placed place() {
            return place(null, null);
        }

        /** The same order, placed by a login with its own address. */
        public Placed place(String loginEmail) {
            return place(loginEmail, null);
        }

        /** The same order with the freight of a carrier that prices by postal code. */
        public Placed placeWithCarrier(long carrierId) {
            return place(null, carrierId);
        }

        private Placed place(String loginEmail, Long carrierId) {
            long customerId = customer("Bloemen Peeters BV", "Bloemenlaan 5", "2000", "Antwerpen");
            long productId = product();
            String email = loginEmail == null ? recordEmail(customerId) : loginEmail;
            long accountId = login(customerId, email);
            SalesOrderService sales = bean(SalesOrderService.class);
            return inTransaction(() -> {
                SalesOrder draft = sales.createWebsiteRequest(customerId, "BE", "DAP");
                SalesOrder request = edited(draft, productId, 120, "10.00", carrierId == null ? "120.00" : null);
                SalesOrder changes = new SalesOrder(request.id(), request.number(), request.customerId(), request.countryCode(),
                        request.orderDate(), request.validUntil(), request.status(), request.incoterm(), request.paymentTerms(),
                        "Graag voor donderdag", request.markupMode(), request.orderMarkupPct(), request.extraDiscountPct(),
                        request.extraDiscountLabel(), request.portalToken(), request.sentAt(), request.viewedAt(),
                        request.viewCount(), request.decidedAt(), request.signedByName(), request.customerMessage(),
                        "[WEBSITE_AANVRAAG] " + draft.number() + "\nWebsitebestelling van een ingelogde klant; bindend na bevestiging door Enrosed.\n"
                                + "Besteld via klantlogin " + email,
                        request.deliveryTerms(), request.freight(), request.manualFreightEur(), request.loadMode(),
                        request.palletProfile(), request.maxPalletHeightCm(),
                        carrierId == null ? FreightPricingStrategy.FIXED : FreightPricingStrategy.CARRIER,
                        request.freightRatePerCbmEur(), carrierId, request.freightCarrierExtraEur(), request.docType(),
                        request.invoiceDueDate(), request.paidAt(), request.sourceQuoteId(), request.goodsShippedAt(),
                        request.lines(), request.pallets()).carrying(request);
                SalesOrder saved = sales.updateByCustomer(draft.id(), changes);
                bean(WebOrderDeliveries.class).save(saved.id(), new WebOrderDeliveries.Delivery(customerId,
                        WebOrderDeliveries.DELIVERY, "Industrieweg 1", "3980", "Tessenderlo", null, null, null,
                        "Jan Besteller", "+32 13 00 00 00", null));
                PricedOrder priced = sales.price(saved);
                WebOrderSnapshot snapshot = snapshot(saved, priced, 1);
                bean(WebOrders.class).create(saved.id(), customerId, accountId, email, "NL", snapshot.toJson(),
                        snapshot.complete() ? WebOrderTerms.of(priced) : null);
                String sku = priced.lines().getFirst().sku();
                return new Placed(saved.id(), saved.number(), customerId, productId, sku, accountId);
            });
        }

        /** Website concepts as the old request route leaves them: no order row, no delivery row. */
        public List<Long> legacyRequests(int count) {
            long customerId = customer("Bloemen Peeters BV", "Bloemenlaan 5", "2000", "Antwerpen");
            long productId = product();
            SalesOrderService sales = bean(SalesOrderService.class);
            return inTransaction(() -> {
                List<Long> ids = new ArrayList<>();
                for (int i = 0; i < count; i++) {
                    SalesOrder draft = sales.createWebsiteRequest(customerId, "BE", "DAP");
                    ids.add(sales.updateByCustomer(draft.id(), edited(draft, productId, 120, "10.00", "120.00")).id());
                    bean(EntityManager.class).find(SalesOrderEntity.class, draft.id()).internalNotes = "[WEBSITE_AANVRAAG] " + draft.number();
                }
                return ids;
            });
        }

        /** Gives stored website concepts the two side rows of an order, without touching the documents. */
        public void turnIntoOrders(List<Long> ids) {
            SalesOrderService sales = bean(SalesOrderService.class);
            inTransaction(() -> {
                for (long id : ids) {
                    SalesOrder saved = sales.get(id);
                    bean(WebOrderDeliveries.class).save(id, new WebOrderDeliveries.Delivery(saved.customerId(),
                            WebOrderDeliveries.DELIVERY, "Industrieweg 1", "3980", "Tessenderlo", null, null, null,
                            "Jan Besteller", "+32 13 00 00 00", null));
                    PricedOrder priced = sales.price(saved);
                    bean(WebOrders.class).create(id, saved.customerId(), 1L, "buyer@login.example", "NL",
                            snapshot(saved, priced, 1).toJson(), WebOrderTerms.of(priced));
                }
            });
        }

        /**
         * The customer changes the quantity and the remark, as the change
         * endpoint does it: the document lock without the gate, the row
         * lock, the one predicate, the replace, the new snapshot.
         *
         * @return the new revision
         */
        public int customerChanges(long id, int quantity) {
            SalesOrderService sales = bean(SalesOrderService.class);
            WebOrders webOrders = bean(WebOrders.class);
            return inTransaction(() -> {
                sales.lockDocumentForCustomer(id);
                SalesOrder order = sales.get(id);
                Row row = webOrders.lock(id).orElseThrow();
                if (!WebOrders.customerMayChange(order, row, bean(SalesRepositories.Orders.class).existsBySourceQuoteId(id)))
                    throw new WebOrderRefusal(WebOrderRefusal.Code.LOCKED);
                SalesOrder edited = edited(order, quantity, "10.00", order.manualFreightEur() == null ? null
                        : order.manualFreightEur().toPlainString());
                SalesOrder changes = new SalesOrder(edited.id(), edited.number(), edited.customerId(), edited.countryCode(),
                        edited.orderDate(), edited.validUntil(), edited.status(), edited.incoterm(), edited.paymentTerms(),
                        "Graag voor vrijdag", edited.markupMode(), edited.orderMarkupPct(), edited.extraDiscountPct(),
                        edited.extraDiscountLabel(), edited.portalToken(), edited.sentAt(), edited.viewedAt(),
                        edited.viewCount(), edited.decidedAt(), edited.signedByName(), edited.customerMessage(),
                        edited.internalNotes(), edited.deliveryTerms(), edited.freight(), edited.manualFreightEur(),
                        edited.loadMode(), edited.palletProfile(), edited.maxPalletHeightCm(), edited.freightPricingStrategy(),
                        edited.freightRatePerCbmEur(), edited.freightCarrierId(), edited.freightCarrierExtraEur(),
                        edited.docType(), edited.invoiceDueDate(), edited.paidAt(), edited.sourceQuoteId(),
                        edited.goodsShippedAt(), edited.lines(), edited.pallets()).carrying(edited);
                SalesOrder saved = sales.updateByCustomer(id, changes);
                PricedOrder priced = sales.price(saved);
                WebOrderSnapshot snapshot = snapshot(saved, priced, row.revision() + 1);
                return webOrders.recordChange(id, row.accountId(), row.accountEmail(), "NL", snapshot.toJson(),
                        snapshot.complete() ? WebOrderTerms.of(priced) : null, "aantal gewijzigd, opmerking gewijzigd").revision();
            });
        }

        /** The customer cancels, as the cancellation endpoint does it. @return the new revision */
        public int customerCancels(long id) {
            SalesOrderService sales = bean(SalesOrderService.class);
            WebOrders webOrders = bean(WebOrders.class);
            return inTransaction(() -> {
                sales.lockDocumentForCustomer(id);
                SalesOrder order = sales.get(id);
                Row row = webOrders.lock(id).orElseThrow();
                if (!WebOrders.customerMayChange(order, row, bean(SalesRepositories.Orders.class).existsBySourceQuoteId(id)))
                    throw new WebOrderRefusal(WebOrderRefusal.Code.LOCKED);
                bean(QuoteService.class).cancelByCustomer(id);
                return webOrders.recordCustomerCancel(id).revision();
            });
        }

        /** The document with another quantity, unit price and fixed freight on its one line; null freight keeps the strategy. */
        public SalesOrder edited(SalesOrder order, int quantity, String unitPrice, String fixedFreight) {
            return edited(order, order.lines().getFirst().productId(), quantity, unitPrice, fixedFreight);
        }

        public SalesOrder edited(SalesOrder order, long productId, int quantity, String unitPrice, String fixedFreight) {
            SalesOrderLine stored = order.lines().isEmpty() ? null : order.lines().getFirst();
            SalesOrderLine line = new SalesOrderLine(stored == null ? null : stored.id(), productId, quantity,
                    new BigDecimal(unitPrice), null, null, stored == null ? null : stored.unitCostEur());
            return rebuilt(order, order.customerId(), List.of(line),
                    fixedFreight == null ? order.freightPricingStrategy() : FreightPricingStrategy.FIXED,
                    fixedFreight == null ? order.manualFreightEur() : new BigDecimal(fixedFreight));
        }

        public SalesOrder withExtraLine(SalesOrder order, String description, String amount) {
            return order.withExtraLines(List.of(new SalesExtraLine(description, BigDecimal.ONE, new BigDecimal(amount))));
        }

        /** The same document for another customer, as staff re-link it in the editor. */
        public SalesOrder relinked(SalesOrder order, long customerId) {
            return rebuilt(order, customerId, order.lines(), order.freightPricingStrategy(), order.manualFreightEur());
        }

        private static SalesOrder rebuilt(SalesOrder order, Long customerId, List<SalesOrderLine> lines,
                                          FreightPricingStrategy strategy, BigDecimal manualFreight) {
            return new SalesOrder(order.id(), order.number(), customerId, order.countryCode(), order.orderDate(),
                    order.validUntil(), order.status(), order.incoterm(), order.paymentTerms(), order.notes(),
                    order.markupMode(), order.orderMarkupPct(), order.extraDiscountPct(), order.extraDiscountLabel(),
                    order.portalToken(), order.sentAt(), order.viewedAt(), order.viewCount(), order.decidedAt(),
                    order.signedByName(), order.customerMessage(), order.internalNotes(), order.deliveryTerms(),
                    order.freight(), manualFreight, order.loadMode(), order.palletProfile(), order.maxPalletHeightCm(),
                    strategy, order.freightRatePerCbmEur(), order.freightCarrierId(), order.freightCarrierExtraEur(),
                    order.docType(), order.invoiceDueDate(), order.paidAt(), order.sourceQuoteId(), order.goodsShippedAt(),
                    lines, order.pallets()).carrying(order);
        }

        private static WebOrderSnapshot snapshot(SalesOrder saved, PricedOrder priced, int revision) {
            Map<Long, Integer> perCarton = new LinkedHashMap<>();
            saved.lines().forEach(line -> perCarton.put(line.productId(), 12));
            return WebOrderSnapshot.of(saved, priced, revision, Instant.now(), "NL", WebOrderDeliveries.DELIVERY, perCarton, Map.of());
        }

        /** Every document of the customers made here, with what hangs on it; then the customers, logins, products and carriers. */
        public void remove() {
            EntityManager em = bean(EntityManager.class);
            inTransaction(() -> {
                for (long customerId : customerIds) {
                    em.createNativeQuery("update sales_order set deleted_at = null where customerId = :id")
                            .setParameter("id", customerId).executeUpdate();
                    List<SalesOrderEntity> documents = em.createQuery("from " + SalesOrderEntity.class.getName()
                            + " o where o.customerId = :id order by o.id desc", SalesOrderEntity.class)
                            .setParameter("id", customerId).getResultList();
                    for (SalesOrderEntity document : documents) {
                        long id = document.id;
                        em.createNativeQuery("delete from sales_split_part where sales_order_id = :id or group_id in "
                                + "(select id from sales_split_group where root_order_id = :id or later_order_id = :id)")
                                .setParameter("id", id).executeUpdate();
                        em.createNativeQuery("delete from sales_split_group where root_order_id = :id or later_order_id = :id")
                                .setParameter("id", id).executeUpdate();
                        em.createNativeQuery("delete from sales_advance_billing where sales_order_id = :id or quote_id = :id")
                                .setParameter("id", id).executeUpdate();
                        em.createNativeQuery("delete from sales_payment where sales_order_id = :id").setParameter("id", id).executeUpdate();
                        em.createNativeQuery("delete from sales_customer_request_message where sales_order_id = :id")
                                .setParameter("id", id).executeUpdate();
                        em.createNativeQuery("delete from quote_event where salesOrderId = :id").setParameter("id", id).executeUpdate();
                        em.createNativeQuery("delete from activity_log where entity_type = 'SALES_ORDER' and entity_id = :id")
                                .setParameter("id", Long.toString(id)).executeUpdate();
                        em.createNativeQuery("delete from deleted_item where source_kind = 'SALES' and source_id = :id")
                                .setParameter("id", id).executeUpdate();
                        em.createQuery("delete from SalesWebOrderEntity w where w.salesOrderId = :id").setParameter("id", id).executeUpdate();
                        em.createQuery("delete from SalesOrderDeliveryEntity d where d.salesOrderId = :id").setParameter("id", id).executeUpdate();
                    }
                    em.flush();
                    em.clear();
                    em.createQuery("from " + SalesOrderEntity.class.getName() + " o where o.customerId = :id order by o.id desc",
                            SalesOrderEntity.class).setParameter("id", customerId).getResultList().forEach(em::remove);
                    em.flush();
                }
                loginIds.forEach(id -> em.createQuery("delete from CustomerAccountEntity a where a.id = :id")
                        .setParameter("id", id).executeUpdate());
            });
            customerIds.forEach(bean(CustomerService.class)::delete);
            inTransaction(() -> productIds.forEach(id -> {
                ProductEntity product = em.find(ProductEntity.class, id);
                if (product != null) em.remove(product);
            }));
            carrierIds.forEach(bean(CarrierRepository.class)::delete);
            customerIds.clear();
            loginIds.clear();
            productIds.clear();
            carrierIds.clear();
        }
    }
}
