package be.enrosed.sales.adapter.out.mail;

import be.enrosed.sales.domain.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import be.enrosed.shared.DocumentText;
import be.enrosed.shared.Language;
import io.quarkus.qute.Engine;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class QuoteMailTemplateRenderTest {

    @Inject
    Engine engine;

    @Test
    void unavailableDeliveryRowsHaveLocalizedStatusAndOriginalQuantityWithoutDeliveryPromise() {
        var unavailable = new be.enrosed.sales.application.port.out.QuoteMailer.DeliveryLine(
                "Unavailable rose", "2026-W37", true, true, 48);
        var available = new be.enrosed.sales.application.port.out.QuoteMailer.DeliveryLine(
                "Available rose", "2026-W40", true);
        for (var language : Language.values()) {
            var rows = SmtpQuoteMailer.deliveryRows(List.of(unavailable, available), language);
            var words = DocumentText.of(language);
            assertTrue(rows.getFirst().term() == null, "an unavailable row has no shipping promise");
            String html = engine.getTemplate("quote-mail.html")
                    .data("languageCode", language.code())
                    .data("logoUrl", "https://enrosed.com/photos/logo-gold.png")
                    .data("customer", new Customer(1L, "Fixture Retail", "Alex", "alex@example.com", null, null,
                            "BE", language, null, null, null, null, null, null, null))
                    .data("portalUrl", "https://example.com/fixture")
                    .data("personalMessage", null)
                    .data("deliveryLines", rows)
                    .data("allDeliveryKnown", true)
                    .data("termsJustAdded", false)
                    .data("freightPending", false)
                    .data("t", words)
                    .data("intro", "Fixture quote")
                    .data("paymentSentence", null)
                    .data("validUntilSentence", "")
                    .render();
            assertTrue(html.contains(words.get("lineUnavailable")), language + ": " + html);
            assertTrue(html.contains(words.get("lineRequestedQuantity").formatted(48)), language + ": " + html);
            assertFalse(html.contains("2026-W37"));
            assertTrue(html.contains("2026-W40"));
            assertFalse(html.contains("0,00"));
        }
    }

    @Test
    void officeCopyShowsExcludedRequestWithoutZeroPriceOrShippingPromise() {
        var mailer = new SmtpQuoteMailer(null, engine.getTemplate("quote-mail.html"), null,
                engine.getTemplate("quote-sent-internal.html"), null);
        mailer.portalBaseUrl = "https://example.com";
        var date = LocalDate.of(2026, 9, 11);
        var order = new SalesOrder(
                1L, "fixture/2026/001", 1L, "BE", date, date.plusDays(30), QuoteStatus.CONCEPT,
                "DAP", "30 dagen", null, MarkupMode.PRODUCT, BigDecimal.ZERO, BigDecimal.ZERO, null,
                null, null, null, 0, null, null, null, null,
                DeliveryTermsState.VOLLEDIG, FreightState.BEREKEND, BigDecimal.ZERO,
                LoadMode.PALLETS, PalletProfile.EURO_120X80, new BigDecimal("180"),
                FreightPricingStrategy.FIXED, null, null, null,
                DocumentType.OFFERTE, null, null, null, null, List.of(), List.of());
        var customer = new Customer(1L, "Fixture Retail", "Alex", "alex@example.com", null, null,
                "BE", Language.NL, null, null, null, null, null, null, null);
        var zero = java.math.BigDecimal.ZERO;
        var summary = new be.enrosed.sales.application.port.out.QuoteMailer.Summary(24, 2,
                java.math.BigDecimal.TEN, zero, java.math.BigDecimal.TEN,
                List.of(new be.enrosed.sales.application.port.out.QuoteMailer.SummaryLine("Unavailable rose", 0, zero, true, 48),
                        new be.enrosed.sales.application.port.out.QuoteMailer.SummaryLine("Available rose", 24, java.math.BigDecimal.TEN)));
        String html = mailer.salesCopyHtml(order, customer, "https://example.com/fixture", null,
                List.of(new be.enrosed.sales.application.port.out.QuoteMailer.DeliveryLine("Unavailable rose", "2026-W37", true, true, 48)), summary);
        String row = html.substring(html.indexOf("Unavailable rose"), html.indexOf("Available rose"));
        assertTrue(row.contains("Tijdelijk niet beschikbaar"));
        assertTrue(row.contains("Oorspronkelijk aangevraagd: 48 stuks"));
        assertFalse(row.contains("0,00"));
        assertTrue(row.contains(">-</td>"));
        assertFalse(html.contains("2026-W37"));
    }

    @Test
    void rendersOfficialBrandingAndCustomerLanguage() throws Exception {
        String portalUrl = "https://orders.enrosed.com/portal/example-token";
        Customer customer = new Customer(
                1L, "Example Retail", "Alex", "alex@example.com", null, null,
                "GB", Language.EN, null, null, null, null, null, null, null);

        String html = engine.getTemplate("quote-mail.html")
                .data("languageCode", Language.EN.code())
                .data("logoUrl", "https://enrosed.com/photos/logo-gold.png")
                .data("customer", customer)
                .data("portalUrl", portalUrl)
                .data("personalMessage", null)
                .data("deliveryLines", List.of())
                .data("allDeliveryKnown", true)
                .data("termsJustAdded", false)
                .data("freightPending", false)
                .data("t", DocumentText.of(Language.EN))
                .data("intro", "Please find our quotation attached.")
                .data("paymentSentence", null)
                .data("validUntilSentence", "This quotation is valid until 30 September 2026.")
                .render();

        Files.writeString(Path.of("target", "quote-mail-preview.html"), html);

        assertTrue(html.contains("<html lang=\"en\">"));
        assertTrue(html.contains("https://enrosed.com/photos/logo-gold.png"));
        assertTrue(html.contains("width=\"180\" height=\"47\""));
        assertTrue(html.contains("alt=\"ENROSED LONDON\""));
        assertTrue(html.contains("Enrosed BV"));
        assertTrue(html.contains("bgcolor=\"#e0be73\""));
        assertTrue(html.contains("Sign the quotation digitally"));
        assertTrue(html.contains(portalUrl));
        assertFalse(html.contains("ENR<span"));
    }
}
