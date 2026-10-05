package be.enrosed.account;

import be.enrosed.sales.adapter.out.mail.SmtpQuoteMailer;
import be.enrosed.sales.application.port.out.QuoteDocumentRenderer;
import be.enrosed.shared.BusinessRuleException;
import be.enrosed.shared.DocumentText;
import be.enrosed.shared.Language;
import be.enrosed.shared.mail.CustomerAccountMailer;
import be.enrosed.shared.mail.CustomerAccountMailer.Invitation;
import be.enrosed.shared.mail.CustomerAccountMailer.Kind;
import io.quarkus.mailer.Mail;
import io.quarkus.mailer.MockMailbox;
import io.quarkus.qute.Engine;
import io.quarkus.runtime.LaunchMode;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The invitation mail: three wordings, nine languages, one link, and nobody reading along. */
@QuarkusTest
class AccountInviteMailTest {
    private static final String TOKEN = "eci1_" + "aB3-_".repeat(8) + "xyz";
    private static final String TO = "inkoop@royalgarden.example";
    private static final List<String> KEYS = List.of(
            "mailAccountInviteSubject", "mailAccountInviteKicker", "mailAccountInviteTitle",
            "mailAccountInviteTitleNewLink", "mailAccountInviteIntro", "mailAccountInviteIntroNewLink",
            "mailAccountInviteWhatTitle", "mailAccountInviteWhat", "mailAccountInviteButton",
            "mailAccountInviteExpiry", "mailAccountInviteKeepsPassword", "mailAccountInviteFallback",
            "mailAccountInviteIgnore");
    /* A customer never reads "order screen": the page is the quote request in every language. */
    private static final List<String> BANNED = List.of(
            "order screen", "bestelscherm", "écran de commande", "bestellbereich", "pantalla de pedidos",
            "ekran zamówień", "ecrã de encomendas", "sipariş ekranı", "οθόνη παραγγελιών");

    @Inject CustomerAccountMailer mailer;
    @Inject MockMailbox mailbox;
    @Inject Engine engine;

    @Test
    void everyLanguageRendersTheThreeWordingsWithOneLinkAndNoCopy() throws Exception {
        assertTrue(AccountTokens.isInvitationToken(TOKEN));
        Path preview = Path.of("target", "mail-preview");
        Files.createDirectories(preview);
        for (Language language : Language.values()) {
            Map<String, String> text = DocumentText.of(language);
            String code = language.code().toLowerCase(Locale.ROOT);
            String link = "https://enrosed.com" + (language == Language.EN ? "" : "/" + code)
                    + "/account/#activate=" + TOKEN;
            String firstTitle = escaped(text.get("mailAccountInviteTitle"));
            String firstIntro = escaped(text.get("mailAccountInviteIntro").formatted("Fleurs & Co"));
            String newLinkTitle = escaped(text.get("mailAccountInviteTitleNewLink"));
            String newLinkIntro = escaped(text.get("mailAccountInviteIntroNewLink").formatted("Fleurs & Co"));
            String keepsPassword = escaped(text.get("mailAccountInviteKeepsPassword"));
            for (Kind kind : Kind.values()) {
                String where = language + " " + kind;
                Mail mail = send(new Invitation(TO, language, "Anna <Muster>", "Fleurs & Co", TOKEN, 7, kind));
                String html = mail.getHtml();
                Files.writeString(preview.resolve("account-invite-" + code + "-"
                        + kind.name().toLowerCase(Locale.ROOT) + ".html"), html);

                assertEquals(text.get("mailAccountInviteSubject"), mail.getSubject(), where);
                assertTrue(mail.getBcc().isEmpty() && mail.getCc().isEmpty(),
                        "the link goes to the customer alone: " + where);
                assertTrue(html.contains("<html lang=\"" + language.code() + "\">"), where);
                assertTrue(html.contains(escaped(text.get("mailAccountInviteKicker"))), where);
                assertTrue(html.contains(escaped(text.get("mailGreeting")) + " Anna &lt;Muster&gt;,"), where);
                assertTrue(html.contains(escaped(text.get("mailAccountInviteWhatTitle"))), where);
                assertTrue(html.contains(escaped(text.get("mailAccountInviteWhat"))), where);
                assertTrue(html.contains(escaped(text.get("mailAccountInviteButton"))), where);
                assertTrue(html.contains(escaped(text.get("mailAccountInviteFallback"))), where);
                assertTrue(html.contains(escaped(text.get("mailAccountInviteIgnore"))), where);
                assertTrue(html.contains(escaped(text.get("mailClosing")) + ","), where);
                assertTrue(html.contains(escaped(text.get("mailAccountInviteExpiry").formatted(7))), where);

                boolean first = kind == Kind.FIRST;
                assertEquals(first, html.contains(firstTitle), where);
                assertEquals(first, html.contains(firstIntro), where);
                assertEquals(!first, html.contains(newLinkTitle), where);
                assertEquals(!first, html.contains(newLinkIntro), where);
                assertEquals(kind == Kind.NEW_LINK_KEEPS_PASSWORD, html.contains(keepsPassword), where);

                assertTrue(link.endsWith("#activate=" + TOKEN));
                assertEquals(3, count(html, link), "button, fallback target and visible text: " + where);
                assertEquals(2, count(html, "href=\"" + link + "\""), where);
                assertEquals(1, count(html, ">" + link + "</a>"), "once as visible text: " + where);
                assertEquals(3, count(html, "eci1_"), where);

                assertFalse(html.contains("{"), "every placeholder resolved: " + where);
                assertFalse(html.contains("NOT_FOUND"), where);
                String lower = html.toLowerCase(Locale.ROOT);
                for (String banned : BANNED) {
                    assertFalse(lower.contains(banned), banned + " in " + where);
                }
            }
        }
    }

    @Test
    void theNumberOfDaysComesFromTheCallerAndIsNeverTypedIntoTheTexts() {
        for (Language language : Language.values()) {
            Map<String, String> text = DocumentText.of(language);
            for (String key : KEYS) {
                assertTrue(text.containsKey(key), language + " misses " + key);
                assertFalse(text.get(key).chars().anyMatch(Character::isDigit),
                        language + " " + key + " carries a number of its own");
            }
            for (int days : new int[]{7, 5}) {
                String html = send(new Invitation(TO, language, null, "Royal Garden", TOKEN, days,
                        Kind.FIRST)).getHtml();
                assertTrue(html.contains(escaped(text.get("mailAccountInviteExpiry").formatted(days))),
                        language + " " + days);
                assertTrue(html.contains(escaped(text.get("mailGreeting")) + ","),
                        "without a contact name the greeting stands alone: " + language);
            }
        }
    }

    @Test
    void theMailProviderRouteCarriesNoOfficeCopy() throws Exception {
        List<Boolean> officeCopy = new ArrayList<>();
        List<Map<String, Object>> payloads = new ArrayList<>();
        SmtpQuoteMailer brevo = new SmtpQuoteMailer(null, null, null, null, null) {
            @Override
            protected Map<String, Object> brevoPayload(String to, String subject, String html, String text,
                                                       QuoteDocumentRenderer.Document attachment,
                                                       boolean withOfficeCopy) {
                officeCopy.add(withOfficeCopy);
                payloads.add(super.brevoPayload(to, subject, html, text, attachment, withOfficeCopy));
                throw new IllegalStateException("nothing leaves from a test");
            }
        };
        set(brevo, "accountInviteTemplate", engine.getTemplate("account-invite-mail.html"));
        set(brevo, "brevoApiKey", Optional.of("test-key"));
        set(brevo, "customerCc", Optional.of("admin@enrosed.com"));
        set(brevo, "internalRecipient", "verkoop@enrosed.be");
        set(brevo, "websiteBaseUrl", "https://enrosed.com/");
        set(brevo, "from", "Enrosed <offertes@enrosed.be>");

        BusinessRuleException refused = assertThrows(BusinessRuleException.class, () -> brevo.sendInvitation(
                new Invitation(TO, Language.EN, "Alex", "Royal Garden", TOKEN, 7, Kind.NEW_LINK)));

        assertTrue(refused.getMessage().startsWith("De mail kon niet verzonden worden via de maildienst"));
        assertEquals(List.of(false), officeCopy);
        Map<String, Object> payload = payloads.getFirst();
        assertFalse(payload.containsKey("bcc"), payload.keySet().toString());
        assertFalse(payload.containsKey("cc"));
        assertEquals(List.of(Map.of("email", TO)), payload.get("to"));
        assertEquals(DocumentText.of(Language.EN).get("mailAccountInviteSubject"), payload.get("subject"));
        assertTrue(((String) payload.get("htmlContent"))
                .contains("href=\"https://enrosed.com/account/#activate=" + TOKEN + "\""));
    }

    @Test
    void aDeployedEnvironmentInMockModeRefusesInsteadOfLoggingTheLink() {
        LaunchMode before = LaunchMode.current();
        mailbox.clear();
        LaunchMode.set(LaunchMode.NORMAL);
        try {
            BusinessRuleException refused = assertThrows(BusinessRuleException.class,
                    () -> mailer.sendInvitation(new Invitation(TO, Language.NL, "Anne", "Royal Garden",
                            TOKEN, 7, Kind.FIRST)));
            assertEquals("De mailer staat in testmodus; de link is niet verstuurd", refused.getMessage());
        } finally {
            LaunchMode.set(before);
        }
        assertEquals(0, mailbox.getTotalMessagesSent());
    }

    private Mail send(Invitation invitation) {
        mailbox.clear();
        mailer.sendInvitation(invitation);
        assertEquals(1, mailbox.getTotalMessagesSent());
        List<Mail> mails = mailbox.getMailsSentTo(invitation.to());
        assertEquals(1, mails.size());
        return mails.getFirst();
    }

    /** The texts as the HTML template writes them. */
    private static String escaped(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }

    private static int count(String text, String part) {
        int count = 0;
        for (int at = text.indexOf(part); at >= 0; at = text.indexOf(part, at + part.length())) count++;
        return count;
    }

    private static void set(SmtpQuoteMailer target, String field, Object value) throws Exception {
        Field declared = SmtpQuoteMailer.class.getDeclaredField(field);
        declared.setAccessible(true);
        declared.set(target, value);
    }
}
