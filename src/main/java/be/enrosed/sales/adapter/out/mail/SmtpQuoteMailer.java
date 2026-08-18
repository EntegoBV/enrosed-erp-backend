package be.enrosed.sales.adapter.out.mail;

import be.enrosed.sales.application.port.out.QuoteDocumentRenderer;
import be.enrosed.sales.application.port.out.QuoteMailer;
import be.enrosed.sales.domain.Customer;
import be.enrosed.sales.domain.SalesOrder;

import be.enrosed.shared.BusinessRuleException;
import be.enrosed.shared.DocumentText;
import be.enrosed.shared.Language;

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
 * Stuurt de offerte per mail, met de PDF als bijlage en de portallink in de
 * tekst.
 *
 * Twee routes naar buiten:
 *  - staat er een Brevo-sleutel (BREVO_API_KEY), dan gaat de mail via hun
 *    HTTPS-API. Dat is gewoon webverkeer, en dus de enige route die werkt op
 *    hosting die uitgaand SMTP blokkeert - Railway doet dat onder het Pro-plan;
 *  - anders klassiek SMTP via de Quarkus-mailer.
 *
 * In ontwikkeling staat quarkus.mailer.mock aan: er vertrekt niets, de mail
 * komt in de log. Zo kan de flow getest worden zonder dat er per ongeluk post
 * naar een echte klant gaat.
 */
@ApplicationScoped
public class SmtpQuoteMailer implements QuoteMailer {

    private static final Logger LOG = Logger.getLogger(SmtpQuoteMailer.class);
    private static final URI BREVO_ENDPOINT = URI.create("https://api.brevo.com/v3/smtp/email");

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private static final ObjectMapper JSON = new ObjectMapper();

    private final Mailer mailer;
    private final Template quoteMailTemplate;

    @ConfigProperty(name = "enrosed.mail.internal-recipient", defaultValue = "verkoop@enrosed.be")
    String internalRecipient;

    /** Staat de mailer in testmodus? Dan wordt er niets verstuurd. */
    @ConfigProperty(name = "quarkus.mailer.mock", defaultValue = "false")
    boolean mock;

    @ConfigProperty(name = "quarkus.mailer.host", defaultValue = "")
    String host;

    /* Optional, niet String: een lege ${BREVO_API_KEY:} wordt door SmallRye
       als null gelezen en een kale String-injectie laat de app dan niet eens
       opstarten. Leeg betekent gewoon: verstuur via SMTP. */
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
    public void sendQuote(SalesOrder order, Customer customer, String portalUrl,
                          QuoteDocumentRenderer.Document document, String personalMessage,
                          List<DeliveryLine> deliveryLines, Notice notice) {

        boolean allKnown = deliveryLines.stream().allMatch(DeliveryLine::known);

        /* De mail vertrekt in de taal van de klant, net als de PDF. */
        Language language = customer.language();
        Map<String, String> text = DocumentText.of(language);

        String body = quoteMailTemplate
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

        /* Het onderwerp zegt meteen waarom deze mail er is. Bij een tweede
           zending met de ingevulde levertermijn is dát het nieuws, niet de
           offerte zelf - anders lijkt het een dubbele mail. */
        String subject = (notice.deliveryTermsAdded()
                ? text.get("mailSubjectTermsAdded") : text.get("mailSubject"))
                .formatted(order.number());

        if (mock) {
            mailer.send(Mail.withHtml(customer.email(), subject, body)
                    .addAttachment(document.filename(), document.content(), document.contentType()));
            LOG.warnf("MAILER STAAT IN TESTMODUS - er vertrok GEEN mail naar %s. De offerte %s is"
                            + " wel opgebouwd.",
                    customer.email(), order.number());
            return;
        }

        String brevoKey = brevoApiKey.orElse("").trim();
        if (!brevoKey.isEmpty()) {
            try {
                sendViaBrevo(customer.email(), subject, body, null, document);
            } catch (Exception e) {
                LOG.errorf(e, "Mail naar %s via Brevo mislukt", customer.email());
                throw new BusinessRuleException(
                        "De mail kon niet verzonden worden via de maildienst: " + e.getMessage()
                        + " De offerte staat nog klaar en is niet als verzonden gemarkeerd.");
            }
            LOG.infof("Offerte %s via Brevo verstuurd naar %s (portaallink %s)",
                    order.number(), customer.email(), portalUrl);
            return;
        }

        /* Zonder ingevulde mailserver is elke poging kansloos. Dan liever een
           duidelijke zin op het scherm dan een stacktrace in de log - en de
           offerte blijft onverstuurd, dus niets lijkt verzonden dat het niet is. */
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
            LOG.errorf(e, "Mail naar %s via %s mislukt", customer.email(), host);
            throw new BusinessRuleException(
                    "De mail kon niet verzonden worden: mailserver \"" + host + "\" is"
                    + " onbereikbaar of weigert de aanmelding. Op hosting die SMTP blokkeert"
                    + " (Railway onder Pro) helpt alleen de Brevo-route (BREVO_API_KEY)."
                    + " De offerte staat nog klaar en is niet als verzonden gemarkeerd.");
        }

        LOG.infof("Offerte %s verstuurd naar %s (portaallink %s)",
                order.number(), customer.email(), portalUrl);
    }

    @Override
    public void notifyInternal(String subject, String body) {
        /* Deze melding vertrekt terwijl een KLANT in het portaal bezig is. Een
           haperende mailserver mag diens actie niet blokkeren - de gebeurtenis
           staat toch al in de geschiedenis van de offerte. */
        try {
            if (!mock && !brevoApiKey.orElse("").isBlank()) {
                sendViaBrevo(internalRecipient, subject, null, body, null);
            } else {
                mailer.send(Mail.withText(internalRecipient, subject, body));
            }
            LOG.infof("Interne melding: %s", subject);
        } catch (Exception e) {
            LOG.errorf(e, "Interne melding \"%s\" kon niet gemaild worden", subject);
        }
    }

    /**
     * Een mail door de Brevo-API duwen.
     *
     * Bewust zonder aparte client-bibliotheek: het is een enkele POST met een
     * JSON-lijf, en elke extra afhankelijkheid is er een die kan breken.
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
            /* Brevo legt in de body uit wat er schort ("sender not valid",
               quota op); dat is precies wat de beheerder moet lezen. */
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
