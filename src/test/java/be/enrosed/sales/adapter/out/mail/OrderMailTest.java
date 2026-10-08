package be.enrosed.sales.adapter.out.mail;

import be.enrosed.account.CustomerAccountEntity;
import be.enrosed.sales.application.WebOrderRecipients;
import be.enrosed.sales.application.WebOrders;
import be.enrosed.sales.application.port.out.QuoteDocumentRenderer;
import be.enrosed.sales.application.port.out.QuoteMailer;
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
import be.enrosed.shared.BusinessRuleException;
import be.enrosed.shared.DocumentText;
import be.enrosed.shared.Language;
import be.enrosed.shared.mail.CustomerOrderMailer;
import be.enrosed.shared.mail.CustomerOrderMailer.Line;
import be.enrosed.shared.mail.CustomerOrderMailer.OrderMail;
import io.quarkus.mailer.Mail;
import io.quarkus.mailer.MockMailbox;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.qute.Engine;
import io.quarkus.runtime.LaunchMode;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The customer mails of a website order: the two order mails in nine
 * languages, and the order wording and recipient that the existing quote
 * and cancellation mail take for a document with a website-order row, while
 * every other document mails exactly as before.
 */
@QuarkusTest
class OrderMailTest {
    private static final AtomicLong IDS = new AtomicLong(5_000_000_000L + System.nanoTime() % 1_000_000_000L);
    private static final String NUMBER = "OF-2026-0123";
    private static final String RECORD = "inkoop@royalgarden.example";
    private static final List<String> KEYS = List.of(
            "mailOrderKicker", "mailOrderReceivedSubject", "mailOrderReceivedTitle", "mailOrderReceivedIntro",
            "mailOrderReceivedChange", "mailOrderProcessingSubject", "mailOrderProcessingTitle",
            "mailOrderProcessingIntro", "mailOrderProcessingNext", "mailOrderSummaryTitle", "mailOrderDeliveryCosts",
            "mailOrderShippingToConfirm", "mailOrderTotalExclVat", "mailOrderPickup", "mailOrderCartons",
            "mailOrderButton", "mailSubjectOrderCancelled", "mailOrderCancelledTitle", "mailOrderCancelledIntro",
            "mailOrderCancelledWhatNow", "mailSubjectOrderApproval", "mailOrderApprovalIntro");
    /** The texts that carry the order number. */
    private static final List<String> NUMBERED = List.of(
            "mailOrderKicker", "mailOrderReceivedSubject", "mailOrderReceivedIntro", "mailOrderProcessingSubject",
            "mailOrderProcessingIntro", "mailSubjectOrderCancelled", "mailOrderCancelledIntro",
            "mailSubjectOrderApproval", "mailOrderApprovalIntro");

    @Inject SmtpQuoteMailer mailer;
    @Inject CustomerOrderMailer orderMailer;
    @Inject MockMailbox mailbox;
    @Inject Engine engine;
    @Inject WebOrders webOrders;
    @Inject Instance<WebOrders> webOrdersInstance;
    @Inject Instance<WebOrderRecipients> recipientsInstance;
    @Inject EntityManager em;

    private final List<Long> orderIds = new ArrayList<>();
    private final List<Long> loginIds = new ArrayList<>();

    @BeforeEach
    void emptyMailbox() {
        mailbox.clear();
    }

    @AfterEach
    void removeRows() {
        QuarkusTransaction.requiringNew().run(() -> {
            orderIds.forEach(id -> em.createQuery("delete from SalesWebOrderEntity w where w.salesOrderId = :id")
                    .setParameter("id", id).executeUpdate());
            loginIds.forEach(id -> em.createQuery("delete from CustomerAccountEntity a where a.id = :id")
                    .setParameter("id", id).executeUpdate());
        });
    }

    // ------------------------------------------------------------------------------------------ texts

    @Test
    void theTwentyTwoOrderTextsExistInAllNineLanguagesAndFitTheirCsv() {
        assertEquals(22, KEYS.size());
        for (Language language : Language.values()) {
            Map<String, String> text = DocumentText.of(language);
            for (String key : KEYS) {
                String value = text.get(key);
                assertTrue(value != null && !value.isBlank(), language + " misses " + key);
                assertFalse(value.contains(";"), language + " " + key + " would break the CSV row");
                assertEquals(NUMBERED.contains(key) ? 1 : 0, count(value, "%s"), language + " " + key);
                assertEquals(count(value, "%s"), count(value, "%"), language + " " + key + " carries a stray percent sign");
            }
        }
        /* An order is never called a request, and Enrosed's own confirmation is never the customer's approval. */
        for (String key : KEYS) {
            String turkish = DocumentText.of(Language.TR).get(key).toLowerCase(Locale.ROOT);
            assertFalse(turkish.contains("talep") || turkish.contains("teklif"), "tr " + key);
            assertFalse(DocumentText.of(Language.EL).get(key).contains("αίτημα"), "el " + key);
            assertFalse(DocumentText.of(Language.NL).get(key).toLowerCase(Locale.ROOT).contains("aanvraag"), "nl " + key);
        }
    }

    // ------------------------------------------------------------------------------------------ the two order mails

    @Test
    void theReceivedMailShowsWhatWasOrderedInEveryLanguageAndEscapesWhatTheCustomerTyped() throws Exception {
        Path preview = Path.of("target", "mail-preview");
        Files.createDirectories(preview);
        for (Language language : Language.values()) {
            Map<String, String> text = DocumentText.of(language);
            String code = language.code().toLowerCase(Locale.ROOT);
            String account = "https://enrosed.com" + (language == Language.EN ? "" : "/" + code) + "/account/";

            mailbox.clear();
            orderMailer.sendOrderReceived(orderMail(language, false));

            Mail mail = single("buyer@login.example");
            String html = mail.getHtml();
            Files.writeString(preview.resolve("order-received-" + code + ".html"), html);
            String where = language.name();
            assertEquals(text.get("mailOrderReceivedSubject").formatted(NUMBER), mail.getSubject(), where);
            assertTrue(mail.getCc().isEmpty(), where);
            assertTrue(mail.getBcc().contains("admin@enrosed.com"), "the office reads along: " + where);
            assertTrue(html.contains("<html lang=\"" + language.code() + "\">"), where);
            assertTrue(html.contains(escaped(text.get("mailOrderKicker").formatted(NUMBER))), where);
            assertTrue(html.contains(escaped(text.get("mailOrderReceivedTitle"))), where);
            assertTrue(html.contains(escaped(text.get("mailGreeting")) + " Anna &lt;script&gt;alert(1)&lt;/script&gt;,"), where);
            assertTrue(html.contains(escaped(text.get("mailOrderReceivedIntro").formatted(NUMBER))), where);
            assertTrue(html.contains(escaped(text.get("mailOrderSummaryTitle"))), where);
            assertTrue(html.contains("Rode roos &lt;b&gt;XL&lt;/b&gt;"), where);
            assertTrue(html.contains("4 " + escaped(text.get("mailOrderCartons")) + " × 24"), where);
            assertTrue(html.contains("3 " + escaped(text.get("mailOrderCartons")) + "</span>"), "carton content unknown: " + where);
            assertTrue(html.contains(money("168.72", language)), where);
            assertTrue(html.contains(escaped(text.get("mailOrderDeliveryCosts"))) && html.contains(money("42.00", language)), where);
            assertTrue(html.contains(escaped(text.get("mailOrderTotalExclVat"))) && html.contains(money("210.72", language)), where);
            assertTrue(html.contains(">" + escaped(text.get("vat")) + "<") && html.contains(money("44.25", language)), where);
            assertTrue(html.contains(escaped(text.get("totalInclVat"))) && html.contains(money("254.97", language)), where);
            assertTrue(html.contains(">" + escaped(text.get("delivery")) + "<"), where);
            assertTrue(html.contains("Industrieweg 1 &lt;script&gt;alert(2)&lt;/script&gt;, 3980 Tessenderlo, België"), where);
            assertTrue(html.contains(escaped(text.get("mailOrderReceivedChange"))), where);
            assertTrue(html.contains(escaped(text.get("mailOrderButton"))), where);
            assertEquals(1, count(html, "href=\"" + account + "\""), "the button leads to My orders: " + where);
            assertTrue(html.contains(escaped(text.get("mailClosing")) + ","), where);
            assertFalse(html.contains(escaped(text.get("mailOrderProcessingNext"))), where);
            assertFalse(html.contains("<script"), "nothing the customer typed is markup: " + where);
            assertFalse(html.contains("{"), "every placeholder resolved: " + where);
            assertFalse(html.contains("NOT_FOUND"), where);
        }
    }

    @Test
    void theProcessingMailSaysWhatHappensNextAndListsNoLines() throws Exception {
        Path preview = Path.of("target", "mail-preview");
        Files.createDirectories(preview);
        for (Language language : Language.values()) {
            Map<String, String> text = DocumentText.of(language);
            mailbox.clear();
            orderMailer.sendOrderInProcessing(orderMail(language, false));

            Mail mail = single("buyer@login.example");
            String html = mail.getHtml();
            Files.writeString(preview.resolve("order-processing-" + language.code().toLowerCase(Locale.ROOT) + ".html"), html);
            String where = language.name();
            assertEquals(text.get("mailOrderProcessingSubject").formatted(NUMBER), mail.getSubject(), where);
            assertTrue(mail.getCc().isEmpty(), where);
            assertTrue(html.contains(escaped(text.get("mailOrderKicker").formatted(NUMBER))), where);
            assertTrue(html.contains(escaped(text.get("mailOrderProcessingTitle"))), where);
            assertTrue(html.contains(escaped(text.get("mailGreeting")) + " Anna &lt;script&gt;alert(1)&lt;/script&gt;,"), where);
            assertTrue(html.contains(escaped(text.get("mailOrderProcessingIntro").formatted(NUMBER))), where);
            assertTrue(html.contains(escaped(text.get("mailOrderProcessingNext"))), where);
            assertTrue(html.contains(escaped(text.get("mailOrderButton"))), where);
            assertFalse(html.contains("Rode roos") || html.contains(money("168.72", language)), "no lines table: " + where);
            assertFalse(html.contains(escaped(text.get("mailOrderSummaryTitle")) + "<"), where);
            assertFalse(html.contains("Industrieweg"), where);
            assertFalse(html.contains(escaped(text.get("mailOrderReceivedChange"))), where);
            assertFalse(html.contains("<script"), where);
            assertFalse(html.contains("{"), "every placeholder resolved: " + where);
        }
    }

    @Test
    void anOrderThatWasStillToConfirmShowsNoTotalAndACollectionItsPickupPoint() {
        Map<String, String> text = DocumentText.of(Language.NL);

        String html = mailer.orderMailHtml(orderMail(Language.NL, true), true);

        assertTrue(html.contains(text.get("mailOrderDeliveryCosts")), html);
        assertEquals(2, count(html, text.get("mailOrderShippingToConfirm")), "the freight and the line without a price: " + html);
        assertFalse(html.contains(text.get("mailOrderTotalExclVat")) || html.contains(text.get("totalInclVat")), html);
        assertTrue(html.contains(">" + text.get("mailOrderPickup") + "<"), html);
        assertTrue(html.contains("Magazijn Tessenderlo, Industrieweg 1"), html);

        OrderMail collected = new OrderMail("buyer@login.example", Language.NL, " ", "Fleurs & Co", NUMBER,
                List.of(new Line("Rode roos", 4, 24, 96, new BigDecimal("168.72"))), new BigDecimal("168.72"),
                BigDecimal.ZERO, false, true, new BigDecimal("168.72"), new BigDecimal("35.43"), new BigDecimal("204.15"), null);
        String free = mailer.orderMailHtml(collected, true);
        assertFalse(free.contains(text.get("mailOrderDeliveryCosts")), "a collection without a charge names no delivery costs: " + free);
        assertTrue(free.contains("204,15 EUR"), free);
        assertFalse(free.contains(">" + text.get("mailOrderPickup") + "<"), "no address known: no block");
        assertTrue(free.contains(text.get("mailGreeting") + ","), "without a contact the greeting stands alone: " + free);
    }

    @Test
    void aDeployedEnvironmentInMockModeRefusesBothOrderMails() {
        LaunchMode before = LaunchMode.current();
        LaunchMode.set(LaunchMode.NORMAL);
        try {
            for (boolean received : new boolean[]{true, false}) {
                BusinessRuleException refused = assertThrows(BusinessRuleException.class, () -> {
                    if (received) orderMailer.sendOrderReceived(orderMail(Language.NL, false));
                    else orderMailer.sendOrderInProcessing(orderMail(Language.NL, false));
                });
                assertEquals("De mailer staat in testmodus; de e-mail aan de klant is niet verstuurd", refused.getMessage());
            }
        } finally {
            LaunchMode.set(before);
        }
        assertEquals(0, mailbox.getTotalMessagesSent());
    }

    // ------------------------------------------------------------------------------------------ cancellation mail

    @Test
    void theCancellationOfAWebsiteOrderSpeaksOfTheOrderAndGoesToTheLoginThatOrdered() {
        for (Language language : List.of(Language.NL, Language.TR)) {
            Map<String, String> text = DocumentText.of(language);
            long customerId = IDS.incrementAndGet();
            String login = "buyer-" + customerId + "@login.example";
            long id = webOrder(customerId, login(customerId, login, "ACTIVE"), login, language.name());
            /* The record's own language is another one: the page the order was placed on decides. */
            Customer customer = customer(customerId, Language.FR);

            for (Instant sentAt : new Instant[]{null, Instant.parse("2026-10-08T09:00:00Z")}) {
                mailbox.clear();
                mailer.sendCancellation(order(id, customerId, sentAt), customer, "https://erp.enrosed.com/offerte/token",
                        "Niet meer leverbaar <dit seizoen>");

                String where = language + (sentAt == null ? " never sent" : " sent before");
                Mail mail = single(login);
                assertEquals(List.of(RECORD), mail.getCc(), "the record keeps a copy: " + where);
                assertTrue(mail.getBcc().contains("admin@enrosed.com"), where);
                assertEquals(text.get("mailSubjectOrderCancelled").formatted(NUMBER), mail.getSubject(), where);
                String html = mail.getHtml();
                assertTrue(html.contains("<html lang=\"" + language.code() + "\">"), where);
                assertTrue(html.contains(escaped(text.get("mailOrderCancelledTitle"))), where);
                assertTrue(html.contains(escaped(text.get("mailOrderCancelledIntro").formatted(NUMBER))), where);
                assertTrue(html.contains(escaped(text.get("mailOrderCancelledWhatNow"))), where);
                assertTrue(html.contains(escaped(text.get("mailOrderButton"))), where);
                assertTrue(html.contains("href=\"" + mailer.websitePage(language, "account") + "\""), where);
                assertTrue(html.contains(escaped(text.get("mailCancelledWhatNowTitle"))), where);
                assertTrue(html.contains(escaped(text.get("mailCancelledMessageTitle"))), where);
                assertTrue(html.contains(escaped(text.get("mailCancelledReply"))), where);
                assertTrue(html.contains("Niet meer leverbaar &lt;dit seizoen&gt;"), where);
                assertFalse(html.contains(escaped(text.get("mailCancelledPortal"))), "My orders is the place, not a quotation page: " + where);
                assertFalse(html.contains("offerte/token"), where);
                String lower = (mail.getSubject() + html).toLowerCase(Locale.ROOT);
                assertFalse(lower.contains("aanvraag"), where);
                assertFalse(lower.contains("teklif talebi"), where);
                assertFalse(html.contains("{t."), where);
            }
        }
    }

    @Test
    void withoutARowBothCancellationVariantsAreUnchanged() {
        long customerId = IDS.incrementAndGet();
        Customer customer = customer(customerId, Language.NL);
        Map<String, String> text = DocumentText.of(Language.NL);

        mailer.sendCancellation(order(IDS.incrementAndGet(), customerId, null), customer, "https://erp.enrosed.com/offerte/token", null);
        Mail request = single(RECORD);
        assertTrue(request.getCc().isEmpty());
        assertEquals(text.get("mailSubjectRequestCancelled").formatted(NUMBER), request.getSubject());
        assertTrue(request.getHtml().contains(text.get("mailRequestCancelledTitle")));
        assertTrue(request.getHtml().contains(text.get("mailRequestCancelledIntro").formatted(NUMBER)));
        assertTrue(request.getHtml().contains(text.get("mailCancelledButtonRequest")));
        assertTrue(request.getHtml().contains("href=\"" + mailer.websitePage(Language.NL, "quote") + "\""));
        assertTrue(request.getHtml().contains(text.get("mailCancelledPortal")) && request.getHtml().contains("offerte/token"));

        mailbox.clear();
        mailer.sendCancellation(order(IDS.incrementAndGet(), customerId, Instant.parse("2026-10-08T09:00:00Z")), customer, null, null);
        Mail quotation = single(RECORD);
        assertTrue(quotation.getCc().isEmpty());
        assertEquals(text.get("mailSubjectCancelled").formatted(NUMBER), quotation.getSubject());
        assertTrue(quotation.getHtml().contains(text.get("mailCancelledTitle")));
        assertTrue(quotation.getHtml().contains(text.get("mailCancelledButtonContact")));
        assertTrue(quotation.getHtml().contains("href=\"" + mailer.websitePage(Language.NL, "contact") + "\""));
        assertFalse(quotation.getHtml().contains(text.get("mailOrderButton")));
    }

    @Test
    void aWebsiteOrderRelinkedToAnotherCustomerIsThatCustomersPlainQuote() {
        long placedFor = IDS.incrementAndGet();
        String login = "buyer-" + placedFor + "@login.example";
        long id = webOrder(placedFor, login(placedFor, login, "ACTIVE"), login, "TR");
        long other = IDS.incrementAndGet();
        Customer customer = customer(other, Language.NL);
        Map<String, String> text = DocumentText.of(Language.NL);

        mailer.sendCancellation(order(id, other, null), customer, null, null);
        Mail cancellation = single(RECORD);
        assertTrue(cancellation.getCc().isEmpty());
        assertEquals(text.get("mailSubjectRequestCancelled").formatted(NUMBER), cancellation.getSubject());

        mailbox.clear();
        mailer.sendQuote(order(id, other, null), customer, "https://erp.enrosed.com/offerte/token", pdf(), null, List.of(),
                QuoteMailer.Notice.none(), null);
        Mail quote = mailbox.getMailsSentTo(RECORD).getFirst();
        assertTrue(quote.getCc().isEmpty());
        assertEquals(text.get("mailSubject").formatted(NUMBER), quote.getSubject());
        assertEquals(0, mailbox.getMailsSentTo(login).size(), "the first customer's login hears nothing");
    }

    // ------------------------------------------------------------------------------------------ approval mail

    @Test
    void theQuoteMailOfAWebsiteOrderIsTheAdjustedVersionOfThatOrderForTheLoginThatOrdered() {
        for (Language language : List.of(Language.NL, Language.TR, Language.EL)) {
            Map<String, String> text = DocumentText.of(language);
            long customerId = IDS.incrementAndGet();
            String login = "buyer-" + customerId + "@login.example";
            /* Ordered on the German page: the approval mail still follows the record, like its PDF. */
            long id = webOrder(customerId, login(customerId, login, "ACTIVE"), login, "DE");
            Customer customer = customer(customerId, language);

            mailbox.clear();
            mailer.sendQuote(order(id, customerId, null), customer, "https://erp.enrosed.com/offerte/token", pdf(), null,
                    List.of(), new QuoteMailer.Notice(true, false, true), null);

            String where = language.name();
            List<Mail> toLogin = mailbox.getMailsSentTo(login);
            assertEquals(1, toLogin.size(), where);
            Mail mail = toLogin.getFirst();
            assertEquals(List.of(RECORD), mail.getCc(), "the record keeps a copy: " + where);
            assertTrue(mail.getBcc().contains("admin@enrosed.com"), where);
            assertEquals(1, mail.getAttachments().size(), "the same PDF: " + where);
            assertEquals(text.get("mailSubjectOrderApproval").formatted(NUMBER), mail.getSubject(), where);
            String html = mail.getHtml();
            assertTrue(html.contains("<html lang=\"" + language.code() + "\">"), where);
            assertTrue(html.contains(escaped(text.get("mailOrderApprovalIntro").formatted(NUMBER))), where);
            assertFalse(html.contains(escaped(text.get("mailIntro").formatted(NUMBER))), where);
            assertFalse(html.contains(escaped(text.get("mailIntroUpdated").formatted(NUMBER))), where);
            assertTrue(html.contains("https://erp.enrosed.com/offerte/token"), "the portal link stays: " + where);
        }
        String turkish = DocumentText.of(Language.TR).get("mailSubjectOrderApproval").toLowerCase(Locale.ROOT);
        assertTrue(turkish.contains("sipariş") && !turkish.contains("teklif"), turkish);
    }

    @Test
    void withoutARowSubjectIntroAndRecipientOfTheQuoteMailAreTodays() {
        long customerId = IDS.incrementAndGet();
        Customer customer = customer(customerId, Language.NL);
        Map<String, String> text = DocumentText.of(Language.NL);

        mailer.sendQuote(order(IDS.incrementAndGet(), customerId, null), customer, "https://erp.enrosed.com/offerte/token",
                pdf(), null, List.of(), QuoteMailer.Notice.none(), null);
        Mail plain = mailbox.getMailsSentTo(RECORD).getFirst();
        assertTrue(plain.getCc().isEmpty());
        assertEquals(text.get("mailSubject").formatted(NUMBER), plain.getSubject());
        assertTrue(plain.getHtml().contains(text.get("mailIntro").formatted(NUMBER)));

        mailbox.clear();
        mailer.sendQuote(order(IDS.incrementAndGet(), customerId, null), customer, "https://erp.enrosed.com/offerte/token",
                pdf(), null, List.of(), new QuoteMailer.Notice(true, false, false), null);
        Mail termsAdded = mailbox.getMailsSentTo(RECORD).getFirst();
        assertEquals(text.get("mailSubjectTermsAdded").formatted(NUMBER), termsAdded.getSubject());
        assertTrue(termsAdded.getHtml().contains(text.get("mailIntroUpdated").formatted(NUMBER)));
    }

    // ------------------------------------------------------------------------------------------ mail provider route

    @Test
    void theMailProviderRouteCarriesTheVisibleCopyOnlyForAWebsiteOrder() throws Exception {
        List<Map<String, Object>> payloads = new ArrayList<>();
        SmtpQuoteMailer brevo = new SmtpQuoteMailer(null, engine.getTemplate("quote-mail.html"),
                engine.getTemplate("cancellation-mail.html"), null, null) {
            @Override
            protected BrevoAnswer postToBrevo(Map<String, Object> payload) {
                payloads.add(payload);
                return new BrevoAnswer(201, "{}");
            }
        };
        set(brevo, "orderMailTemplate", engine.getTemplate("order-mail.html"));
        set(brevo, "webOrders", webOrdersInstance);
        set(brevo, "webOrderRecipients", recipientsInstance);
        set(brevo, "brevoApiKey", Optional.of("test-key"));
        set(brevo, "customerCc", Optional.of("admin@enrosed.com"));
        set(brevo, "salesCopy", Optional.empty());
        set(brevo, "internalRecipient", "verkoop@enrosed.be");
        set(brevo, "websiteBaseUrl", "https://enrosed.com/");
        set(brevo, "from", "Enrosed <offertes@enrosed.be>");
        long customerId = IDS.incrementAndGet();
        String login = "buyer-" + customerId + "@login.example";
        long id = webOrder(customerId, login(customerId, login, "ACTIVE"), login, "NL");
        Customer customer = customer(customerId, Language.NL);

        brevo.sendCancellation(order(id, customerId, null), customer, null, null);
        brevo.sendQuote(order(id, customerId, null), customer, "https://erp.enrosed.com/offerte/token", pdf(), null,
                List.of(), QuoteMailer.Notice.none(), null);
        brevo.sendOrderReceived(orderMail(Language.NL, false));
        brevo.sendCancellation(order(IDS.incrementAndGet(), customerId, null), customer, null, null);

        assertEquals(4, payloads.size());
        for (Map<String, Object> web : payloads.subList(0, 2)) {
            assertEquals(List.of(Map.of("email", login)), web.get("to"));
            assertEquals(List.of(Map.of("email", RECORD)), web.get("cc"));
            assertEquals(List.of(Map.of("email", "admin@enrosed.com")), web.get("bcc"));
        }
        assertTrue(payloads.get(1).containsKey("attachment"));
        assertEquals(List.of(Map.of("email", "buyer@login.example")), payloads.get(2).get("to"));
        assertFalse(payloads.get(2).containsKey("cc"), "an order mail goes to the login alone");
        assertEquals(List.of(Map.of("email", "admin@enrosed.com")), payloads.get(2).get("bcc"));
        assertEquals(List.of(Map.of("email", RECORD)), payloads.get(3).get("to"));
        assertFalse(payloads.get(3).containsKey("cc"), "a document without a row has no visible copy");
    }

    // ------------------------------------------------------------------------------------------ fixtures

    private static OrderMail orderMail(Language language, boolean toConfirm) {
        List<Line> lines = List.of(
                new Line("Rode roos <b>XL</b>", 4, 24, 96, new BigDecimal("168.72")),
                new Line("Zeeproos in box", 3, null, 0, null));
        return new OrderMail("buyer@login.example", language, "Anna <script>alert(1)</script>", "Fleurs & Co", NUMBER, lines,
                new BigDecimal("168.72"), toConfirm ? null : new BigDecimal("42.00"), toConfirm, toConfirm,
                toConfirm ? null : new BigDecimal("210.72"), toConfirm ? null : new BigDecimal("44.25"),
                toConfirm ? null : new BigDecimal("254.97"),
                toConfirm ? "Magazijn Tessenderlo, Industrieweg 1"
                        : "Industrieweg 1 <script>alert(2)</script>, 3980 Tessenderlo, België");
    }

    private long webOrder(long customerId, long accountId, String accountEmail, String language) {
        long id = IDS.incrementAndGet();
        QuarkusTransaction.requiringNew().run(() -> webOrders.create(id, customerId, accountId, accountEmail, language, null, null));
        orderIds.add(id);
        return id;
    }

    private long login(long customerId, String email, String status) {
        long id = QuarkusTransaction.requiringNew().call(() -> {
            CustomerAccountEntity login = new CustomerAccountEntity();
            login.customerId = customerId;
            login.email = email;
            login.status = status;
            login.language = "nl";
            login.createdAt = Instant.now();
            em.persist(login);
            em.flush();
            return login.id;
        });
        loginIds.add(id);
        return id;
    }

    private static Customer customer(long id, Language language) {
        return new Customer(id, "Royal Garden Center Group", "Anne van den Berg", RECORD, "+32 3 555 01 02",
                "BE 0123.456.789", "BE", language, "Bloemenlaan 112", "2000", "Antwerpen", "DAP",
                "30 dagen na factuurdatum", null, LocalDate.of(2024, 3, 12));
    }

    private static SalesOrder order(long id, long customerId, Instant sentAt) {
        LocalDate date = LocalDate.of(2026, 10, 8);
        return new SalesOrder(id, NUMBER, customerId, "BE", date, date.plusDays(30), QuoteStatus.CONCEPT,
                "DAP", "30 dagen na factuurdatum", null,
                MarkupMode.PRODUCT, new BigDecimal("45"), new BigDecimal("5"), null,
                null, sentAt, null, 0, null, null, null, null,
                DeliveryTermsState.VOLLEDIG, FreightState.BEREKEND, new BigDecimal("220"),
                LoadMode.PALLETS, PalletProfile.EURO_120X80, new BigDecimal("180"),
                FreightPricingStrategy.FIXED, null, null, null,
                DocumentType.OFFERTE, null, null, null, null,
                List.of(), List.of());
    }

    private static QuoteDocumentRenderer.Document pdf() {
        return new QuoteDocumentRenderer.Document(NUMBER + ".pdf",
                "%PDF-1.4 test".getBytes(java.nio.charset.StandardCharsets.US_ASCII), "application/pdf");
    }

    /** The one customer mail in the mailbox; it is counted once per address it reached. */
    private Mail single(String address) {
        List<Mail> sent = mailbox.getMailsSentTo(address);
        assertEquals(1, sent.size(), "to " + address);
        Mail mail = sent.getFirst();
        assertEquals(1 + mail.getCc().size() + mail.getBcc().size(), mailbox.getTotalMessagesSent(), "exactly one mail left");
        return mail;
    }

    private static String money(String amount, Language language) {
        return DocumentText.money(new BigDecimal(amount), language) + " EUR";
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
