package be.enrosed.sales.adapter.out.mail;

import be.enrosed.sales.domain.Customer;
import be.enrosed.sales.domain.DeliveryTermsState;
import be.enrosed.sales.domain.DocumentType;
import be.enrosed.sales.domain.FreightPricingStrategy;
import be.enrosed.sales.domain.FreightState;
import be.enrosed.sales.domain.LoadMode;
import be.enrosed.sales.domain.MarkupMode;
import be.enrosed.sales.domain.PalletProfile;
import be.enrosed.sales.domain.QuoteStatus;
import be.enrosed.sales.domain.SalesOrder;
import be.enrosed.shared.DocumentText;
import be.enrosed.shared.Language;
import io.quarkus.mailer.Mail;
import io.quarkus.mailer.MockMailbox;
import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The cancellation mail reads well in every language and never leaks markup. */
@QuarkusTest
class CancellationMailRenderTest {

    @Inject
    @Location("cancellation-mail.html")
    Template template;

    @Inject
    SmtpQuoteMailer mailer;

    @Inject
    MockMailbox mailbox;

    @Test
    void everyLanguageRendersTheRequestVariantWithItsOwnWebsiteLink() throws Exception {
        Path preview = Path.of("target", "mail-preview");
        Files.createDirectories(preview);
        for (Language language : Language.values()) {
            Map<String, String> text = DocumentText.of(language);
            String html = template
                    .data("languageCode", language.code())
                    .data("logoUrl", "https://enrosed.com/photos/logo-gold.png")
                    .data("websiteUrl", "https://enrosed.com")
                    .data("customer", Map.of("contact", "Anna <Muster>"))
                    .data("kicker", "ENR-2026-0042")
                    .data("title", text.get("mailRequestCancelledTitle"))
                    .data("intro", text.get("mailRequestCancelledIntro").formatted("ENR-2026-0042"))
                    .data("message", "Per ongeluk verstuurd - geen probleem.\nTot een volgende keer.")
                    .data("whatNowTitle", text.get("mailCancelledWhatNowTitle"))
                    .data("whatNow", text.get("mailRequestCancelledWhatNow"))
                    .data("buttonUrl", mailer.websitePage(language, "quote"))
                    .data("buttonLabel", text.get("mailCancelledButtonRequest"))
                    .data("portalUrl", "https://erp.enrosed.com/offerte/token")
                    .data("t", text)
                    .render();
            Files.writeString(preview.resolve("cancellation-" + language.code().toLowerCase() + ".html"), html);
            assertTrue(html.contains(text.get("mailRequestCancelledTitle")), language.name());
            assertTrue(html.contains(text.get("mailCancelledButtonRequest")), language.name());
            assertTrue(html.contains("Anna &lt;Muster&gt;"), "names are escaped");
            assertTrue(html.contains("Per ongeluk verstuurd"), language.name());
            assertFalse(html.contains("{t."), "every key resolved for " + language.name());
            assertFalse(html.contains("NOT_FOUND"), language.name());
        }
        assertTrue(mailer.websitePage(Language.EN, "quote").endsWith("enrosed.com/quote/"));
        assertTrue(mailer.websitePage(Language.DE, "contact").endsWith("enrosed.com/de/contact/"));
    }

    @Test
    void everyCustomerMailCarriesTheOfficeInCopy() {
        assertEquals("admin@enrosed.com", mailer.copyFor("klant@example.com"));
        assertNull(mailer.copyFor("Admin@enrosed.com"), "never copies itself");
        assertNull(mailer.copyFor(mailer.internalRecipient), "internal mail carries no copy");

        Customer customer = new Customer(
                7L, "Royal Garden Center Group", "Anne van den Berg",
                "inkoop@royalgarden.example", "+32 3 555 01 02", "BE 0123.456.789",
                "BE", Language.NL, "Bloemenlaan 112", "2000", "Antwerpen",
                "DAP", "30 dagen na factuurdatum", null, LocalDate.of(2024, 3, 12));
        LocalDate date = LocalDate.of(2026, 8, 27);
        SalesOrder request = new SalesOrder(
                148L, "ENR-2026-0148", 7L, "BE", date, date.plusDays(30), QuoteStatus.CONCEPT,
                "DAP", "30 dagen na factuurdatum", null,
                MarkupMode.PRODUCT, new BigDecimal("45"), new BigDecimal("5"), null,
                null, null, null, 0, null, null, null, null,
                DeliveryTermsState.VOLLEDIG, FreightState.BEREKEND, new BigDecimal("220"),
                LoadMode.PALLETS, PalletProfile.EURO_120X80, new BigDecimal("180"),
                FreightPricingStrategy.FIXED, null, null, null,
                DocumentType.OFFERTE, null, null, null, null,
                List.of(), List.of());

        mailbox.clear();
        mailer.sendCancellation(request, customer, "https://erp.enrosed.com/offerte/token", "Per vergissing ingediend");

        List<Mail> sent = mailbox.getMailsSentTo("inkoop@royalgarden.example");
        assertEquals(1, sent.size());
        assertTrue(sent.get(0).getBcc().contains("admin@enrosed.com"), "the office reads along in bcc: " + sent.get(0).getBcc());
        assertTrue(sent.get(0).getCc().isEmpty(), "the customer never sees who reads along: " + sent.get(0).getCc());
        assertTrue(sent.get(0).getSubject().contains("ENR-2026-0148"));
    }

    @Test
    void everySentQuotationReachesTheTeamAsAReadableCopyWithThePdf() {
        Customer customer = new Customer(
                7L, "Royal Garden Center Group", "Anne van den Berg",
                "inkoop@royalgarden.example", "+32 3 555 01 02", "BE 0123.456.789",
                "BE", Language.NL, "Bloemenlaan 112", "2000", "Antwerpen",
                "DAP", "30 dagen na factuurdatum", null, LocalDate.of(2024, 3, 12));
        LocalDate date = LocalDate.of(2026, 8, 27);
        SalesOrder quote = new SalesOrder(
                149L, "ENR-2026-0149", 7L, "BE", date, date.plusDays(30), QuoteStatus.CONCEPT,
                "DAP", "30 dagen na factuurdatum", null,
                MarkupMode.PRODUCT, new BigDecimal("45"), new BigDecimal("5"), null,
                null, null, null, 0, null, null, null, null,
                DeliveryTermsState.VOLLEDIG, FreightState.BEREKEND, new BigDecimal("220"),
                LoadMode.PALLETS, PalletProfile.EURO_120X80, new BigDecimal("180"),
                FreightPricingStrategy.FIXED, null, null, null,
                DocumentType.OFFERTE, null, null, null, null,
                List.of(), List.of());
        var document = new be.enrosed.sales.application.port.out.QuoteDocumentRenderer.Document(
                "ENR-2026-0149.pdf", "%PDF-1.4 test".getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                "application/pdf");
        var summary = new be.enrosed.sales.application.port.out.QuoteMailer.Summary(
                480, 2, new BigDecimal("6240.00"), new BigDecimal("220.00"), new BigDecimal("6460.00"),
                List.of(new be.enrosed.sales.application.port.out.QuoteMailer.SummaryLine(
                                "Gepreserveerde roos in stolp 25 cm", 240, new BigDecimal("4080.00")),
                        new be.enrosed.sales.application.port.out.QuoteMailer.SummaryLine(
                                "Soaproos in box", 240, new BigDecimal("2160.00"))));

        mailbox.clear();
        mailer.sendQuote(quote, customer, "https://erp.enrosed.com/offerte/token", document,
                "Fijn dat we u op de beurs spraken.",
                List.of(new be.enrosed.sales.application.port.out.QuoteMailer.DeliveryLine(
                        "Gepreserveerde roos in stolp 25 cm", "week 40", true)),
                be.enrosed.sales.application.port.out.QuoteMailer.Notice.none(), summary);

        List<Mail> toCustomer = mailbox.getMailsSentTo("inkoop@royalgarden.example");
        assertEquals(1, toCustomer.size());
        assertTrue(toCustomer.get(0).getBcc().contains("admin@enrosed.com"));

        List<Mail> toTeam = mailbox.getMailsSentTo("hello@enrosed.com");
        assertEquals(1, toTeam.size(), "one readable copy for the team");
        Mail copy = toTeam.get(0);
        assertEquals("Offerte ENR-2026-0149 verzonden naar Royal Garden Center Group", copy.getSubject());
        assertEquals(1, copy.getAttachments().size(), "the same PDF travels along");
        assertEquals("ENR-2026-0149.pdf", copy.getAttachments().get(0).getName());
        String html = copy.getHtml();
        try {
            Path preview = Path.of("target", "mail-preview");
            Files.createDirectories(preview);
            Files.writeString(preview.resolve("quote-sent-internal.html"), html);
        } catch (java.io.IOException ignored) {
            /* The preview is a convenience for a designer's eye, not the assertion. */
        }
        assertTrue(html.contains("6.460,00 EUR"), html);
        assertTrue(html.contains("480"), html);
        assertTrue(html.contains("Gepreserveerde roos in stolp 25 cm"), html);
        assertTrue(html.contains("Fijn dat we u op de beurs spraken."), html);
        assertTrue(html.contains("/sales/149"), "links back to the order in the ERP: " + html);
        assertTrue(html.contains("https://erp.enrosed.com/offerte/token"), html);
        assertTrue(copy.getBcc().isEmpty() && copy.getCc().isEmpty(), "the team copy carries no further copies");
        assertFalse(html.contains("{"), "every placeholder resolved: " + html);
    }

    @Test
    void websiteRequestsReachTheTeamMailboxAsAStyledNotice() {
        var notice = new be.enrosed.shared.mail.InternalMessageSender.TeamNotice(
                "Nieuwe websiteaanvraag ENR-2026-0150 · Bloemenhuis Peeters",
                "Website · nieuwe offerteaanvraag",
                "Bloemenhuis Peeters",
                "Klaar voor beoordeling in Verkoop · 240 stuks in 1 regel.",
                List.of(new be.enrosed.shared.mail.InternalMessageSender.TeamFact("Bedrijf", "Bloemenhuis Peeters"),
                        new be.enrosed.shared.mail.InternalMessageSender.TeamFact("E-mail", "info@peeters.example"),
                        new be.enrosed.shared.mail.InternalMessageSender.TeamFact("Land", "BE")),
                List.of(new be.enrosed.shared.mail.InternalMessageSender.TeamLine(
                        "Gepreserveerde roos in stolp 25 cm - Rood", "240 st", "ENR-P10 · 60 dozen van 4")),
                "Opmerking van de klant", "Graag levering voor Valentijn <3",
                "Open in het ERP", "https://erp.enrosed.com/sales/150",
                "Mail de klant", "mailto:info@peeters.example",
                "Nieuwe websiteaanvraag ENR-2026-0150");

        mailbox.clear();
        mailer.sendTeamNotice(notice);

        List<Mail> toTeam = mailbox.getMailsSentTo("hello@enrosed.com");
        assertEquals(1, toTeam.size(), "styled notices go to the team mailbox");
        Mail mail = toTeam.get(0);
        assertEquals("Nieuwe websiteaanvraag ENR-2026-0150 · Bloemenhuis Peeters", mail.getSubject());
        String html = mail.getHtml();
        try {
            Path preview = Path.of("target", "mail-preview");
            Files.createDirectories(preview);
            Files.writeString(preview.resolve("team-notice.html"), html);
        } catch (java.io.IOException ignored) {
            /* Preview only. */
        }
        assertTrue(html.contains("Bloemenhuis Peeters"), html);
        assertTrue(html.contains("60 dozen van 4"), html);
        assertTrue(html.contains("Graag levering voor Valentijn &lt;3"), "the customer's text is escaped: " + html);
        assertTrue(html.contains("https://erp.enrosed.com/sales/150"), html);
        assertTrue(html.contains("mailto:info@peeters.example"), html);
        assertFalse(html.contains("{notice"), "every placeholder resolved: " + html);
        assertEquals("Nieuwe websiteaanvraag ENR-2026-0150", mail.getText());
    }
}
