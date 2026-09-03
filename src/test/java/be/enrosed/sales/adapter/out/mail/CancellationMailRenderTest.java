package be.enrosed.sales.adapter.out.mail;

import be.enrosed.shared.DocumentText;
import be.enrosed.shared.Language;
import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The cancellation mail reads well in every language and never leaks markup. */
@QuarkusTest
class CancellationMailRenderTest {

    @Inject
    @Location("cancellation-mail.html")
    Template template;

    @Inject
    SmtpQuoteMailer mailer;

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
}
