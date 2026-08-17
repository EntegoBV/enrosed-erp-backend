package be.enrosed.sales.adapter.out.mail;

import be.enrosed.sales.application.port.out.QuoteDocumentRenderer;
import be.enrosed.sales.application.port.out.QuoteMailer;
import be.enrosed.sales.domain.Customer;
import be.enrosed.sales.domain.SalesOrder;

import be.enrosed.shared.DocumentText;
import be.enrosed.shared.Language;

import java.util.List;
import java.util.Map;
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
 * In ontwikkeling staat quarkus.mailer.mock aan: er vertrekt niets, de mail
 * komt in de log. Zo kan de flow getest worden zonder dat er per ongeluk post
 * naar een echte klant gaat.
 */
@ApplicationScoped
public class SmtpQuoteMailer implements QuoteMailer {

    private static final Logger LOG = Logger.getLogger(SmtpQuoteMailer.class);

    private final Mailer mailer;
    private final Template quoteMailTemplate;

    @ConfigProperty(name = "enrosed.mail.internal-recipient", defaultValue = "verkoop@enrosed.be")
    String internalRecipient;

    /** Staat de mailer in testmodus? Dan wordt er niets verstuurd. */
    @ConfigProperty(name = "quarkus.mailer.mock", defaultValue = "false")
    boolean mock;

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

        Mail mail = Mail.withHtml(customer.email(), subject, body)
                .addAttachment(document.filename(), document.content(), document.contentType());

        mailer.send(mail);

        if (mock) {
            LOG.warnf("MAILER STAAT IN TESTMODUS - er vertrok GEEN mail naar %s. De offerte %s is"
                            + " wel opgebouwd. Zet quarkus.mailer.mock=false en vul de SMTP-gegevens"
                            + " in application.properties in om echt te versturen.",
                    customer.email(), order.number());
        } else {
            LOG.infof("Offerte %s verstuurd naar %s (portaallink %s)",
                    order.number(), customer.email(), portalUrl);
        }
    }

    @Override
    public void notifyInternal(String subject, String body) {
        mailer.send(Mail.withText(internalRecipient, subject, body));
        LOG.infof("Interne melding: %s", subject);
    }
}
