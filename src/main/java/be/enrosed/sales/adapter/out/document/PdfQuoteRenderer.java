package be.enrosed.sales.adapter.out.document;

import be.enrosed.sales.application.port.out.QuoteDocumentRenderer;
import be.enrosed.sales.domain.Customer;
import be.enrosed.sales.domain.FreightState;
import be.enrosed.sales.domain.PricedOrder;
import be.enrosed.sales.domain.SalesOrder;
import be.enrosed.shared.Brand;
import be.enrosed.shared.DocumentText;
import be.enrosed.shared.Language;
import be.enrosed.shared.PdfFonts;
import be.enrosed.shared.company.CompanyProfileService;
import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Maakt het offertedocument: Qute rendert de HTML, openhtmltopdf zet die om
 * naar PDF.
 *
 * Bewust via HTML en niet via een PDF-bibliotheek met tekenopdrachten: de
 * lay-out blijft dan gewoon een sjabloon dat je kan aanpassen zonder Java te
 * schrijven.
 *
 * Het document vertrekt in de taal van de klant. De teksten komen uit
 * {@link DocumentText}, de productnamen uit de vertalingen bij het product
 * zelf. Wat niet vertaald is valt terug op onze eigen tekst - liever de
 * basisnaam op een Franse offerte dan een leeg vak.
 *
 * Wat er niet in staat is even belangrijk als wat er wel in staat: kostprijs
 * en marge blijven eruit. Dit document gaat naar de klant.
 */
@ApplicationScoped
public class PdfQuoteRenderer implements QuoteDocumentRenderer {

    private final Template quoteTemplate;
    private final Brand brand;
    private final CompanyProfileService company;
    private final PdfFonts fonts;

    /** Base URL of the portal; the public terms page lives under it. */
    @org.eclipse.microprofile.config.inject.ConfigProperty(name = "enrosed.portal.base-url")
    String portalBaseUrl;

    public PdfQuoteRenderer(@Location("quote.html") Template quoteTemplate, Brand brand,
                            CompanyProfileService company, PdfFonts fonts) {
        this.quoteTemplate = quoteTemplate;
        this.brand = brand;
        this.company = company;
        this.fonts = fonts;
    }

    @Override
    public Document render(SalesOrder order, PricedOrder priced, Customer customer, String portalUrl) {
        return render(order, priced, customer, portalUrl, null);
    }

    /**
     * @param override taal waarin het document moet vertrekken, of null voor de
     *                 taal van de klant. Handig om even een Engelse versie mee te
     *                 geven aan iemand die de offerte intern moet doorgeven.
     */
    public Document render(SalesOrder order, PricedOrder priced, Customer customer,
                           String portalUrl, Language override) {
        Language language = override != null ? override
                : customer == null ? Language.NL : customer.language();
        Map<String, String> text = DocumentText.of(language);

        /* De levertermijn per regel wordt hier al tot tekst gemaakt: de keuze
           tussen een datum, een week of "in overleg" hangt van de taal af en
           hoort niet in het sjabloon thuis. */
        Map<Long, String> deliveryTexts = new LinkedHashMap<>();
        for (PricedOrder.Line line : priced.lines()) {
            deliveryTexts.put(line.productId(), deliveryTextOf(line, language, text));
        }

        String html = quoteTemplate
                .data("order", order)
                .data("priced", priced)
                .data("customer", customer)
                .data("portalUrl", portalUrl)
                .data("logo", brand.logoDataUri())
                .data("company", company.get())
                .data("footerText", company.get().footerFor(language))
                /* The order's own terms win; the customer's are the default. */
                .data("paymentText", be.enrosed.shared.PaymentTermsNames.translate(
                        order.paymentTermsOr(customer == null ? null : customer.paymentTerms()),
                        language))
                .data("t", text)
                .data("orderDateText", DocumentText.date(order.orderDate(), language))
                .data("validUntilText", DocumentText.date(order.validUntil(), language))
                .data("validUntilSentence", text.get("validUntilSentence")
                        .formatted(DocumentText.date(order.validUntil(), language)))
                .data("deliveryTexts", deliveryTexts)
                .data("freightPending", order.freight() == FreightState.TE_BEPALEN)
                .data("vatLabel", priced.totals().vatTreatment().labelIn(language))
                .data("vatMention", priced.totals().vatTreatment().legalMentionIn(language))
                /* Dutch documents link to the Dutch terms; every other
                   language gets English - the only other version we maintain. */
                .data("termsUrl", portalBaseUrl + "/voorwaarden"
                        + (language == Language.NL ? "" : "?lang=en"))
                .render();

        return new Document(order.number() + ".pdf", fonts.render(html), "application/pdf");
    }

    /** "vanaf 19/08/2026", "week 42 (12/10 - 18/10/2026)" of "in overleg". */
    private static String deliveryTextOf(PricedOrder.Line line, Language language,
                                         Map<String, String> text) {
        if (line.inStock() && line.deliveryDate() != null) {
            return text.get("from") + " "
                    + DocumentText.date(java.time.LocalDate.parse(line.deliveryDate()), language);
        }
        if (line.deliveryWeek() != null && !line.deliveryWeek().isBlank()) {
            return DocumentText.week(line.deliveryWeek(), language);
        }
        return text.get("toBeAgreed");
    }
}
