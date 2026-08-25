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
 * Builds the quote document: Qute renders the HTML, openhtmltopdf turns it
 * into a PDF.
 *
 * Deliberately through HTML rather than a PDF library with draw calls: the
 * layout stays a plain template you can adjust without writing Java.
 *
 * The document leaves in the customer's language. The texts come from
 * {@link DocumentText}, the product names from the translations on the
 * product itself. Whatever is untranslated falls back to our own text -
 * better the base name on a French quote than an empty box.
 *
 * What is absent matters as much as what is present: cost price and margin
 * stay out. This document goes to the customer.
 */
@ApplicationScoped
public class PdfQuoteRenderer implements QuoteDocumentRenderer {

    private final Template quoteTemplate;
    private final Template packingSlipTemplate;
    private final Brand brand;
    private final CompanyProfileService company;
    private final PdfFonts fonts;

    /** Base URL of the portal; the public terms page lives under it. */
    @org.eclipse.microprofile.config.inject.ConfigProperty(name = "enrosed.portal.base-url")
    String portalBaseUrl;

    public PdfQuoteRenderer(@Location("quote.html") Template quoteTemplate,
                            @Location("packing-slip.html") Template packingSlipTemplate,
                            Brand brand,
                            CompanyProfileService company, PdfFonts fonts) {
        this.quoteTemplate = quoteTemplate;
        this.packingSlipTemplate = packingSlipTemplate;
        this.brand = brand;
        this.company = company;
        this.fonts = fonts;
    }

    @Override
    public Document render(SalesOrder order, PricedOrder priced, Customer customer, String portalUrl) {
        return render(order, priced, customer, portalUrl, null);
    }

    /**
     * @param override language the document should leave in, or null for the
     *                 customer's. Handy for handing an English copy to
     *                 someone who needs to pass the quote around internally.
     */
    public Document render(SalesOrder order, PricedOrder priced, Customer customer,
                           String portalUrl, Language override) {
        Language language = override != null ? override
                : customer == null ? Language.NL : customer.language();
        Map<String, String> text = DocumentText.of(language);

        /* The per-line delivery term is turned into text right here: the
           choice between a date, a week or "to be agreed" depends on the
           language and does not belong in the template. */
        Map<Long, String> deliveryTexts = new LinkedHashMap<>();
        Map<Long, Integer> palletPositionsByProduct = new LinkedHashMap<>();
        for (PricedOrder.Line line : priced.lines()) {
            deliveryTexts.put(line.productId(), deliveryTextOf(line, language, text));
            palletPositionsByProduct.put(line.productId(),
                    order.palletPositionsForProduct(line.productId(), line.pallets()));
        }

        boolean invoice = order.isInvoice();
        String dueDateText = DocumentText.date(order.invoiceDueDate(), language);
        String paymentInstruction = null;
        String iban = null;
        String claimAmount = null;
        if (invoice) {
            iban = company.get().iban() == null || company.get().iban().isBlank()
                    ? "-" : company.get().iban();
            /* The claim is what must actually arrive: including VAT when charged. */
            java.math.BigDecimal claim = priced.totals().vatTreatment().isExempt()
                    ? priced.totals().total() : priced.totals().totalInclVat();
            claimAmount = be.enrosed.shared.DocumentFormat.eur(claim);
            paymentInstruction = text.get("paymentInstruction").formatted(
                    claimAmount, dueDateText, iban, order.number());
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
                /* An invoice never claims to "expire": it falls due. */
                .data("validUntilSentence", invoice ? "" : text.get("validUntilSentence")
                        .formatted(DocumentText.date(order.validUntil(), language)))
                .data("isInvoice", invoice)
                .data("docLabel", text.get(invoice ? "invoice" : "quote"))
                .data("dueDateText", dueDateText)
                .data("paymentInstruction", paymentInstruction)
                .data("iban", iban)
                .data("claimAmount", claimAmount)
                /* Belgian B2B invoicing runs through Peppol; this paper copy
                   must say loudly that it is not the legal document. */
                .data("belgianInvoice", invoice && "BE".equalsIgnoreCase(order.countryCode()))
                /* The full terms ride along as the closing page: the linked
                   web page can change, the document in the mailbox cannot. */
                .data("termsText", company.get().termsFor(language))
                .data("deliveryTexts", deliveryTexts)
                .data("palletPositionsByProduct", palletPositionsByProduct)
                .data("freightPending", order.freight() == FreightState.TE_BEPALEN)
                .data("looseCartons", order.loadMode() == be.enrosed.sales.domain.LoadMode.LOOSE_CARTONS)
                .data("freightPerCbm", order.freightPricingStrategy()
                        == be.enrosed.sales.domain.FreightPricingStrategy.PER_CBM)
                .data("freightFixed", order.freightPricingStrategy()
                        == be.enrosed.sales.domain.FreightPricingStrategy.FIXED)
                .data("effectivePallets", priced.totals().palletsManual() > 0
                        ? priced.totals().palletsManual() : priced.totals().palletsStrict())
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
    @Override
    public Document packingSlip(PackingSlip slip) {
        Language slipLanguage = slip.customer() == null ? Language.NL : slip.customer().language();
        String html = packingSlipTemplate
                .data("slip", slip)
                .data("logo", brand.logoDataUri())
                .data("company", company.get())
                .data("date", be.enrosed.shared.DocumentFormat.be(java.time.LocalDate.now()))
                .data("termsTitle", DocumentText.of(slipLanguage).get("termsTitle"))
                .data("termsText", company.get().termsFor(slipLanguage))
                .render();
        return new Document(slip.order().number() + "-pakbon.pdf", fonts.render(html),
                "application/pdf");
    }

}
