package be.enrosed.sales.adapter.out.mail;

import be.enrosed.sales.application.port.out.QuoteDocumentRenderer;
import be.enrosed.sales.application.port.out.QuoteMailer;
import be.enrosed.sales.domain.Customer;
import be.enrosed.sales.domain.SalesOrder;

import be.enrosed.shared.BusinessRuleException;
import be.enrosed.shared.DocumentText;
import be.enrosed.shared.Language;
import be.enrosed.shared.mail.InternalMessageSender;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.mailer.Mail;
import io.quarkus.mailer.Mailer;
import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Sends the quote by mail, with the PDF attached and the portal link in the
 * body.
 *
 * Two routes out:
 *  - with a Brevo key (BREVO_API_KEY) the mail goes through their HTTPS
 *    API. That is plain web traffic, and therefore the only route that works
 *    on hosting that blocks outbound SMTP - Railway does below the Pro plan;
 *  - otherwise classic SMTP through the Quarkus mailer.
 *
 * In development quarkus.mailer.mock is on: nothing leaves, the mail lands
 * in the log. That lets the flow be tested without accidental post to a
 * real customer.
 */
@ApplicationScoped
public class SmtpQuoteMailer implements QuoteMailer, InternalMessageSender {

    private static final Logger LOG = Logger.getLogger(SmtpQuoteMailer.class);
    private static final URI BREVO_ENDPOINT = URI.create("https://api.brevo.com/v3/smtp/email");
    private static final String BRAND_LOGO_URL = "https://enrosed.com/photos/logo-gold.png";

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private static final ObjectMapper JSON = new ObjectMapper();

    private final Mailer mailer;
    private final Template quoteMailTemplate;

    @ConfigProperty(name = "enrosed.mail.internal-recipient", defaultValue = "verkoop@enrosed.be")
    String internalRecipient;

    /** Is the mailer in mock mode? Then nothing is sent. */
    @ConfigProperty(name = "quarkus.mailer.mock", defaultValue = "false")
    boolean mock;

    @ConfigProperty(name = "quarkus.mailer.host", defaultValue = "")
    String host;

    /* Optional, not String: an empty ${BREVO_API_KEY:} is read as null by
       SmallRye, and a bare String injection then stops the app from even
       starting. Empty simply means: send through SMTP. */
    @ConfigProperty(name = "enrosed.mail.brevo-api-key")
    Optional<String> brevoApiKey;

    /** Afzender, als "Naam <adres>" of kaal adres; gedeeld door beide routes. */
    @ConfigProperty(name = "quarkus.mailer.from", defaultValue = "offertes@enrosed.be")
    String from;

    public SmtpQuoteMailer(Mailer mailer, @Location("quote-mail.html") Template quoteMailTemplate) {
        this.mailer = mailer;
        this.quoteMailTemplate = quoteMailTemplate;
    }

    @Override
    public void sendInvoice(SalesOrder order, Customer customer,
                            QuoteDocumentRenderer.Document document, String personalMessage,
                            String paymentSentence) {
        Language language = customer.language();
        Map<String, String> text = DocumentText.of(language);

        String body = quoteMailTemplate
                .data("languageCode", language.code())
                .data("logoUrl", BRAND_LOGO_URL)
                .data("order", order)
                .data("customer", customer)
                .data("portalUrl", null)
                .data("personalMessage", personalMessage)
                .data("deliveryLines", List.<DeliveryLine>of())
                .data("allDeliveryKnown", true)
                .data("termsJustAdded", false)
                .data("freightPending", false)
                .data("freightAdded", false)
                .data("t", text)
                .data("intro", text.get("mailIntroInvoice").formatted(order.number()))
                .data("paymentSentence", paymentSentence)
                .data("validUntilSentence", "")
                .render();

        String subject = text.get("mailSubjectInvoice").formatted(order.number());
        deliver(customer, subject, body, document, order.number());
    }

    /** One door out for both document sorts: mock, Brevo or plain SMTP. */
    private void deliver(Customer customer, String subject, String body,
                         QuoteDocumentRenderer.Document document, String number) {
        if (mock) {
            mailer.send(Mail.withHtml(customer.email(), subject, body)
                    .addAttachment(document.filename(), document.content(), document.contentType()));
            LOG.warnf("MAILER STAAT IN TESTMODUS - er vertrok GEEN klantmail voor %s.", number);
            return;
        }
        String brevoKey = brevoApiKey.orElse("").trim();
        if (!brevoKey.isEmpty()) {
            try {
                sendViaBrevo(customer.email(), subject, body, null, document);
            } catch (Exception e) {
                LOG.errorf(e, "Klantmail voor %s via Brevo mislukt", number);
                throw new BusinessRuleException(
                        "De mail kon niet verzonden worden via de maildienst: " + e.getMessage()
                        + " Het document staat nog klaar en is niet als verzonden gemarkeerd.");
            }
            LOG.infof("Klantmail voor %s via Brevo verstuurd", number);
            return;
        }
        if (host.isBlank() || host.endsWith("example.com")) {
            throw new BusinessRuleException(
                    "Er is geen mailserver ingesteld; de mail kan niet vertrekken.");
        }
        mailer.send(Mail.withHtml(customer.email(), subject, body)
                .addAttachment(document.filename(), document.content(), document.contentType()));
    }

    @Override
    public void sendQuote(SalesOrder order, Customer customer, String portalUrl,
                          QuoteDocumentRenderer.Document document, String personalMessage,
                          List<DeliveryLine> deliveryLines, Notice notice) {

        boolean allKnown = deliveryLines.stream().allMatch(DeliveryLine::known);

        /* The mail leaves in the customer's language, just like the PDF. */
        Language language = customer.language();
        Map<String, String> text = DocumentText.of(language);

        String body = quoteMailTemplate
                .data("languageCode", language.code())
                .data("logoUrl", BRAND_LOGO_URL)
                .data("order", order)
                .data("customer", customer)
                .data("portalUrl", portalUrl)
                .data("personalMessage", personalMessage)
                .data("deliveryLines", deliveryLines)
                .data("allDeliveryKnown", allKnown)
                .data("termsJustAdded", notice.deliveryTermsAdded())
                .data("freightPending", notice.freightPending())
                .data("freightAdded", notice.freightAdded())
                .data("t", text)
                .data("intro", (notice.deliveryTermsAdded() || notice.freightAdded()
                        ? text.get("mailIntroUpdated") : text.get("mailIntro"))
                        .formatted(order.number()))
                .data("validUntilSentence", text.get("validUntilSentence")
                        .formatted(DocumentText.date(order.validUntil(), language)))
                .render();

        /* The subject says right away why this mail exists. On a second
           sending with the delivery term filled in, that is the news - not
           the quote itself - or it reads like a duplicate mail. */
        String subject = (notice.deliveryTermsAdded()
                ? text.get("mailSubjectTermsAdded") : text.get("mailSubject"))
                .formatted(order.number());

        if (mock) {
            mailer.send(Mail.withHtml(customer.email(), subject, body)
                    .addAttachment(document.filename(), document.content(), document.contentType()));
            LOG.warnf("MAILER STAAT IN TESTMODUS - er vertrok GEEN klantmail. De offerte %s is"
                            + " wel opgebouwd.", order.number());
            return;
        }

        String brevoKey = brevoApiKey.orElse("").trim();
        if (!brevoKey.isEmpty()) {
            try {
                sendViaBrevo(customer.email(), subject, body, null, document);
            } catch (Exception e) {
                LOG.errorf(e, "Klantmail voor offerte %s via Brevo mislukt", order.number());
                throw new BusinessRuleException(
                        "De mail kon niet verzonden worden via de maildienst: " + e.getMessage()
                        + " De offerte staat nog klaar en is niet als verzonden gemarkeerd.");
            }
            LOG.infof("Klantmail voor offerte %s via Brevo verstuurd", order.number());
            return;
        }

        /* Without a configured mail server every attempt is hopeless. Better
           one clear sentence on screen than a stack trace in the log - and
           the quote stays unsent, so nothing looks sent that is not. */
        if (host.isBlank() || host.endsWith("example.com")) {
            throw new BusinessRuleException(
                    "Er is nog geen mailroute ingesteld. Zet op de server BREVO_API_KEY"
                    + " (aanbevolen: SMTP-poorten zijn op deze hosting geblokkeerd) of de"
                    + " SMTP_-variabelen. Tot dan: download de PDF en verstuur hem zelf.");
        }

        try {
            mailer.send(Mail.withHtml(customer.email(), subject, body)
                    .addAttachment(document.filename(), document.content(), document.contentType()));
        } catch (RuntimeException e) {
            LOG.errorf(e, "Klantmail via %s mislukt", host);
            throw new BusinessRuleException(
                    "De mail kon niet verzonden worden: mailserver \"" + host + "\" is"
                    + " onbereikbaar of weigert de aanmelding. Op hosting die SMTP blokkeert"
                    + " (Railway onder Pro) helpt alleen de Brevo-route (BREVO_API_KEY)."
                    + " De offerte staat nog klaar en is niet als verzonden gemarkeerd.");
        }

        LOG.infof("Offerte %s verstuurd (portaallink opgenomen)", order.number());
    }

    @Override
    public void sendCancellation(SalesOrder order, Customer customer, String portalUrl, String message) {
        Language language = customer.language();
        Map<String, String> text = DocumentText.of(language);
        String subject = text.get("mailSubjectCancelled").formatted(order.number());
        String who = customer.contact() == null || customer.contact().isBlank() ? customer.company() : customer.contact();
        StringBuilder html = new StringBuilder();
        html.append("<p>").append(escape(text.get("mailGreeting"))).append(' ').append(escape(who)).append(",</p>");
        html.append("<p>").append(escape(text.get("mailCancelledIntro").formatted(order.number()))).append("</p>");
        if (message != null && !message.isBlank()) {
            html.append("<p>").append(escape(message.strip()).replace("\n", "<br/>")).append("</p>");
        }
        if (portalUrl != null && !portalUrl.isBlank()) {
            html.append("<p>").append(escape(text.get("mailCancelledPortal"))).append("<br/><a href=\"")
                    .append(escape(portalUrl)).append("\">").append(escape(portalUrl)).append("</a></p>");
        }
        html.append("<p>").append(escape(text.get("mailClosing"))).append(",<br/>Enrosed</p>");
        deliverWithoutAttachment(customer, subject, html.toString(), order.number());
    }

    private void deliverWithoutAttachment(Customer customer, String subject, String body, String number) {
        if (mock) {
            mailer.send(Mail.withHtml(customer.email(), subject, body));
            LOG.warnf("MAILER STAAT IN TESTMODUS - er vertrok GEEN klantmail voor %s.", number);
            return;
        }
        String brevoKey = brevoApiKey.orElse("").trim();
        if (!brevoKey.isEmpty()) {
            try {
                sendViaBrevo(customer.email(), subject, body, null, null);
            } catch (Exception e) {
                LOG.errorf(e, "Klantmail voor %s via Brevo mislukt", number);
                throw new BusinessRuleException(
                        "De mail kon niet verzonden worden via de maildienst: " + e.getMessage());
            }
            LOG.infof("Klantmail voor %s via Brevo verstuurd", number);
            return;
        }
        if (host.isBlank() || host.endsWith("example.com")) {
            throw new BusinessRuleException("Er is geen mailserver ingesteld; de mail kan niet vertrekken.");
        }
        mailer.send(Mail.withHtml(customer.email(), subject, body));
    }

    private static String escape(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    @Override
    public void notifyInternal(String subject, String body) {
        /* Portal actions must not fail when the notification provider is down. */
        try {
            sendInternal(subject, body);
        } catch (RuntimeException exception) {
            LOG.errorf(exception, "Interne melding kon niet gemaild worden");
        }
    }

    @Override
    public void sendInternal(String subject, String body) {
        try {
            if (!mock && !brevoApiKey.orElse("").isBlank()) {
                sendViaBrevo(internalRecipient, subject, null, body, null);
            } else {
                mailer.send(Mail.withText(internalRecipient, subject, body));
            }
            LOG.infof("Interne melding: %s", subject);
        } catch (Exception e) {
            throw new IllegalStateException("Interne melding kon niet gemaild worden", e);
        }
    }

    /**
     * Pushes one mail through the Brevo API.
     *
     * Deliberately without a client library: it is a single POST with a JSON
     * body, and every extra dependency is one more thing that can break.
     */
    private void sendViaBrevo(String to, String subject, String html, String text,
                              QuoteDocumentRenderer.Document attachment) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sender", Map.of("name", senderName(), "email", senderEmail()));
        payload.put("to", List.of(Map.of("email", to)));
        payload.put("subject", subject);
        if (html != null) {
            payload.put("htmlContent", html);
        } else {
            payload.put("textContent", text);
        }
        if (attachment != null) {
            payload.put("attachment", List.of(Map.of(
                    "name", attachment.filename(),
                    "content", Base64.getEncoder().encodeToString(attachment.content()))));
        }

        HttpRequest request = HttpRequest.newBuilder(BREVO_ENDPOINT)
                .timeout(Duration.ofSeconds(25))
                .header("api-key", brevoApiKey.orElse(""))
                .header("content-type", "application/json")
                .header("accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        JSON.writeValueAsString(payload), StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 300) {
            /* Brevo explains in the body what is wrong ("sender not valid",
               quota exhausted); exactly what the administrator needs to read. */
            String detail = response.body() == null ? "" : response.body();
            throw new IllegalStateException(
                    "maildienst antwoordde " + response.statusCode()
                    + (detail.isBlank() ? "" : " - " + detail.substring(0, Math.min(300, detail.length()))));
        }
    }

    private String senderName() {
        int bracket = from.indexOf('<');
        String name = bracket > 0 ? from.substring(0, bracket).trim() : "";
        return name.isBlank() ? "Enrosed BV" : name;
    }

    private String senderEmail() {
        int open = from.indexOf('<');
        int close = from.indexOf('>');
        return open >= 0 && close > open ? from.substring(open + 1, close).trim() : from.trim();
    }
}
