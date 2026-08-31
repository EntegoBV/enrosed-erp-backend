package be.enrosed.sales.adapter.out.mail;

import be.enrosed.sales.domain.Customer;
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
