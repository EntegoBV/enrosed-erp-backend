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
        assertTrue(sent.get(0).getCc().contains("admin@enrosed.com"), "the office is in copy: " + sent.get(0).getCc());
        assertTrue(sent.get(0).getSubject().contains("ENR-2026-0148"));
    }
}
