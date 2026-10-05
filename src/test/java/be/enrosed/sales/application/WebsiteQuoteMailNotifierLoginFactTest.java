package be.enrosed.sales.application;

import be.enrosed.catalog.application.ProductService;
import be.enrosed.sales.domain.*;
import be.enrosed.shared.Language;
import be.enrosed.shared.mail.InternalMessageSender;
import be.enrosed.shared.mail.InternalMessageSender.TeamFact;
import be.enrosed.shared.mail.InternalMessageSender.TeamNotice;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/** The team mail of a website quote says a login was asked only on our own note line. */
class WebsiteQuoteMailNotifierLoginFactTest {
    private static final TeamFact LOGIN_FACT =
            new TeamFact("Login gevraagd", "Ja · goedkeuren bij Login-aanvragen");

    private final WebsiteQuoteMailNotifier notifier = new WebsiteQuoteMailNotifier(
            mock(SalesOrderService.class), mock(CustomerService.class), mock(ProductService.class),
            mock(InternalMessageSender.class), "https://erp.example.test");

    @Test
    void aNoteLineThatStartsWithTheMarkerAddsTheFactDirectlyBeforeTheOrderNumber() {
        TeamNotice notice = notifier.notice(order("[WEBSITE_AANVRAAG] ENR-2026-0061\n"
                + "Niet-bindende aanvraag; prijzen en logistiek door Enrosed te bevestigen.\n"
                + WebsiteQuoteLoginRequested.NOTE_MARKER
                + " De klant vraagt ook een login; goedkeuren bij Login-aanvragen."), customer());

        int at = notice.facts().indexOf(LOGIN_FACT);
        assertTrue(at > 0, notice.facts().toString());
        assertEquals(new TeamFact("Ordernummer", "ENR-2026-0061"), notice.facts().get(at + 1));
        assertEquals(notice.facts().size() - 1, at + 1);
        assertTrue(notice.textFallback().contains(
                "Login gevraagd: Ja · goedkeuren bij Login-aanvragen\nOrdernummer: ENR-2026-0061\n"));
    }

    @Test
    void theMarkerInsideALineIsNotTheFact() {
        for (String notes : List.of(
                "[WEBSITE_AANVRAAG] ENR-2026-0061\nContact op aanvraag: [LOGIN_AANVRAAG] x",
                "[WEBSITE_AANVRAAG] ENR-2026-0061 [LOGIN_AANVRAAG]",
                " [LOGIN_AANVRAAG] met een spatie ervoor",
                "[WEBSITE_AANVRAAG] ENR-2026-0061")) {
            TeamNotice notice = notifier.notice(order(notes), customer());
            assertFalse(notice.facts().contains(LOGIN_FACT), notes);
            assertFalse(notice.textFallback().contains("Login gevraagd"), notes);
        }
        TeamNotice withoutNotes = notifier.notice(order(null), null);
        assertEquals(List.of(new TeamFact("Ordernummer", "ENR-2026-0061")), withoutNotes.facts());
    }

    private static Customer customer() {
        return new Customer(9L, "Buyer BV", "Ana", "ana@example.com", null, "BE0123456789", "BE",
                Language.EN, "Street 1", "2400", "Mol", "DAP", null, null, LocalDate.now());
    }

    private static SalesOrder order(String internalNotes) {
        LocalDate today = LocalDate.now();
        return new SalesOrder(61L, "ENR-2026-0061", 9L, "BE", today, today.plusDays(30),
                QuoteStatus.CONCEPT, "DAP", null, null, MarkupMode.PRODUCT, new BigDecimal("45"),
                null, null, null, null, null, 0, null, null, null, internalNotes,
                DeliveryTermsState.VOLLEDIG, FreightState.BEREKEND, null,
                LoadMode.PALLETS, PalletProfile.EURO_120X80, null,
                FreightPricingStrategy.COUNTRY_PALLET, null, null, null,
                DocumentType.OFFERTE, null, null, null, null, List.of(), List.of());
    }
}
