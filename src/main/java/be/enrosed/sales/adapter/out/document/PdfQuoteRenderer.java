package be.enrosed.sales.adapter.out.document;

import be.enrosed.sales.application.port.out.QuoteDocumentRenderer;
import be.enrosed.sales.application.port.out.SalesPdfOptions;
import be.enrosed.sales.domain.Customer;
import be.enrosed.sales.domain.FreightState;
import be.enrosed.sales.domain.PricedOrder;
import be.enrosed.sales.domain.SalesOrder;
import be.enrosed.shared.Brand;
import be.enrosed.shared.DocumentText;
import be.enrosed.shared.Language;
import be.enrosed.shared.PdfFonts;
import be.enrosed.shared.company.CompanyProfileService;
import be.enrosed.catalog.adapter.out.document.PdfImageEncoder;
import be.enrosed.catalog.application.ProductService;
import be.enrosed.catalog.domain.Dimensions;
import be.enrosed.shared.DocumentFormat;
import be.enrosed.catalog.domain.PackagingKind;
import be.enrosed.catalog.domain.Product;
import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.List;
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

    @Inject
    Instance<ProductService> products;
    @Inject
    Instance<PdfImageEncoder> imageEncoder;

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
        return render(order, priced, customer, portalUrl, null, SalesPdfOptions.defaults());
    }

    /**
     * @param override language the document should leave in, or null for the
     *                 customer's. Handy for handing an English copy to
     *                 someone who needs to pass the quote around internally.
     */
    public Document render(SalesOrder order, PricedOrder priced, Customer customer,
                           String portalUrl, Language override) {
        return render(order, priced, customer, portalUrl, override, SalesPdfOptions.defaults());
    }

    @Override
    public Document render(SalesOrder order, PricedOrder priced, Customer customer,
                           String portalUrl, Language override, SalesPdfOptions requestedOptions) {
        Language language = override != null ? override
                : customer == null ? Language.NL : customer.language();
        Map<String, String> text = DocumentText.of(language);
        SalesPdfOptions options = requestedOptions == null
                ? SalesPdfOptions.defaults() : requestedOptions;
        boolean invoice = order.isInvoice();
        List<LineView> lines = lineViews(order, priced, language, text, options);
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
                .data("languageCode", language.code())
                .data("documentFooterSentence", text.get(invoice ? "invoiceFooter" : "footer"))
                .data("termsSentence", text.get(invoice ? "invoiceTermsSentence" : "termsSentence"))
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
                .data("termsText", options.includeTerms() ? company.get().termsFor(language) : null)
                .data("lines", lines)
                .data("totalCbmText", priced.totals() == null ? null : DocumentFormat.cbm(priced.totals().cbm()))
                .data("includePhotos", options.includePhotos())
                .data("includeProductDetails", options.includeProductDetails())
                .data("includeLogistics", options.includeLogistics())
                .data("includeTerms", options.includeTerms())
                .data("showOuterCarton", options.showOuterCarton())
                .data("showBarcode", options.showBarcode())
                .data("hasDiscounts", hasLineDiscounts(priced))
                .data("freightPending", order.freight() == FreightState.TE_BEPALEN)
                .data("looseCartons", order.loadMode() == be.enrosed.sales.domain.LoadMode.LOOSE_CARTONS)
                .data("freightPerCbm", order.freightPricingStrategy()
                        == be.enrosed.sales.domain.FreightPricingStrategy.PER_CBM)
                .data("freightFixed", order.freightPricingStrategy()
                        == be.enrosed.sales.domain.FreightPricingStrategy.FIXED)
                .data("freightPickup", order.freightPricingStrategy()
                        == be.enrosed.sales.domain.FreightPricingStrategy.PICKUP)
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

    /** One compact fact underneath a product title; labels are customer-language text. */
    public record ProductSpec(String label, String value) {}

    /** Print projection: commercial values stay frozen, catalogue presentation stays tidy. */
    public record LineView(PricedOrder.Line commercial, String title, String variantText,
                           String description, List<ProductSpec> productSpecs,
                           String photoDataUri, int palletPositions, String metaText) {}

    private List<LineView> lineViews(SalesOrder order, PricedOrder priced, Language language,
                                     Map<String, String> text, SalesPdfOptions options) {
        List<LineView> result = new ArrayList<>();
        Map<String, String> imageCache = new LinkedHashMap<>();
        for (PricedOrder.Line line : priced.lines()) {
            Product product = product(line.productId());
            String title = product == null
                    ? cleanFallbackTitle(line.customerDescription())
                    : nonBlank(product.nameIn(language), cleanFallbackTitle(line.customerDescription()));
            String variant = product == null || !options.includeProductDetails() ? null : joinDetails(
                    product.colourIn(language), product.variantSizeIn(language));
            String description = product == null || !options.includeProductDetails()
                    ? null : distinctDescription(product.descriptionIn(language), title);
            List<ProductSpec> details = product == null
                    ? List.of() : productSpecs(product, text, options);
            String photo = options.includePhotos() ? productImage(product, imageCache) : null;
            String meta = joinDetails(
                    options.includeProductDetails() ? line.sku() : null,
                    options.includeLogistics() ? deliveryTextOf(line, language, text) : null);
            result.add(new LineView(line, title, variant, description, details, photo,
                    order.palletPositionsForProduct(line.productId(), line.pallets()), meta));
        }
        return List.copyOf(result);
    }

    private Product product(Long productId) {
        if (productId == null || products == null || !products.isResolvable()) return null;
        try {
            return products.get().get(productId);
        } catch (Exception ignored) {
            return null;
        }
    }

    private String productImage(Product product, Map<String, String> cache) {
        if (product == null || product.primaryPhoto() == null || imageEncoder == null
                || !imageEncoder.isResolvable() || products == null || !products.isResolvable()) {
            return null;
        }
        String storageKey = product.primaryPhoto().storageKey();
        if (storageKey == null || storageKey.isBlank()) return null;
        String encoded = cache.computeIfAbsent(storageKey, key -> {
            try (var input = products.get().photoData(key)) {
                String data = imageEncoder.get().encodeContained(
                        input.readAllBytes(), 240, 240, java.awt.Color.WHITE);
                return data == null ? "" : data;
            } catch (Exception ignored) {
                return "";
            }
        });
        return encoded.isBlank() ? null : encoded;
    }

    private static List<ProductSpec> productSpecs(Product product, Map<String, String> text,
                                                  SalesPdfOptions options) {
        List<ProductSpec> details = new ArrayList<>();
        if (options.includeProductDetails()) {
            addSpec(details, text.get("productDimensions"), dimensions(product.dimensions()));
        }

        if (options.showBarcode()) {
            String productBarcode = firstNonBlank(product.canonicalBarcode(),
                    product.barcodes() == null ? null : product.barcodes().inner());
            addSpec(details, "EAN", productBarcode);
        }

        if (options.includeProductDetails() && product.packaging().isPresent()) {
            String packagingLabel = product.packaging().kind() == PackagingKind.GIFT_BOX
                    ? text.get("giftPackaging") : text.get("displayPackaging");
            addSpec(details, packagingLabel, joinDetails(
                    dimensions(product.packaging().dimensions()),
                    product.packaging().unitPieces() > 1
                            ? product.packaging().unitPieces() + " " + text.get("pieces") : null,
                    options.showBarcode()
                            ? prefix("EAN ", product.packaging().barcode()) : null));
        }

        if (options.showOuterCarton() && product.carton() != null) {
            addSpec(details, text.get("catalogCarton"), joinDetails(
                    dimensions(product.carton().dimensions()),
                    Math.max(1, product.carton().piecesPerCarton()) + " " + text.get("pieces"),
                    DocumentFormat.cbm(product.carton().cbm()),
                    options.showBarcode()
                            ? prefix("EAN ", product.barcodes() == null
                                    ? null : product.barcodes().outer()) : null));
        }
        return List.copyOf(details);
    }

    private static void addSpec(List<ProductSpec> target, String label, String value) {
        if (value != null && !value.isBlank()) {
            target.add(new ProductSpec(nonBlank(label, "Detail"), value));
        }
    }

    private static String dimensions(Dimensions dimensions) {
        if (dimensions == null) return null;
        String label = dimensions.label();
        if (label == null || label.isBlank()) return null;
        int separator = label.indexOf(':');
        return separator < 0 ? label : label.substring(separator + 1).strip();
    }

    private static String distinctDescription(String description, String title) {
        if (description == null || description.isBlank()
                || description.strip().equalsIgnoreCase(nonBlank(title, "").strip())) return null;
        return description.strip();
    }

    static String cleanFallbackTitle(String value) {
        if (value == null || value.isBlank()) return "-";
        String cleaned = value.replaceAll(
                "(?i)\\s*-\\s*B\\s*[×x]\\s*D\\s*[×x]\\s*H\\s*:\\s*.*?\\s*cm(?=\\s*-|$)", "");
        return cleaned.replaceAll("\\s+-\\s+-\\s+", " - ").strip();
    }

    static boolean hasLineDiscounts(PricedOrder priced) {
        return priced != null && priced.lines() != null && priced.lines().stream().anyMatch(line ->
                line.discountPct() != null && line.discountPct().signum() > 0);
    }

    private static String joinDetails(String... values) {
        List<String> present = new ArrayList<>();
        if (values != null) {
            for (String value : values) {
                if (value != null && !value.isBlank()) present.add(value.strip());
            }
        }
        return present.isEmpty() ? null : String.join(" · ", present);
    }

    private static String prefix(String prefix, String value) {
        return value == null || value.isBlank() ? null : prefix + value.strip();
    }

    private static String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first.strip()
                : second == null || second.isBlank() ? null : second.strip();
    }

    private static String nonBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.strip();
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
        return packingSlip(slip, SalesPdfOptions.forPackingSlip(false, false));
    }

    @Override
    public Document packingSlip(PackingSlip slip, SalesPdfOptions requestedOptions) {
        SalesPdfOptions options = requestedOptions == null
                ? SalesPdfOptions.forPackingSlip(false, false) : requestedOptions;
        String html = packingSlipHtml(slip, options);
        return new Document(slip.order().number() + "-pakbon.pdf", fonts.render(html),
                "application/pdf");
    }

    /** Package-visible so the locale contract can be tested before PDF conversion. */
    String packingSlipHtml(PackingSlip slip) {
        return packingSlipHtml(slip, SalesPdfOptions.forPackingSlip(false, false));
    }

    /** Package-visible so optional product-data output remains independently testable. */
    String packingSlipHtml(PackingSlip slip, SalesPdfOptions requestedOptions) {
        SalesPdfOptions options = requestedOptions == null
                ? SalesPdfOptions.forPackingSlip(false, false) : requestedOptions;
        Language slipLanguage = slip.customer() == null ? Language.NL : slip.customer().language();
        Map<String, String> text = DocumentText.of(slipLanguage);
        return packingSlipTemplate
                .data("slip", slip)
                .data("logo", brand.logoDataUri())
                .data("company", company.get())
                .data("lang", slipLanguage.code())
                .data("date", DocumentText.date(java.time.LocalDate.now(), slipLanguage))
                .data("t", text)
                .data("showOuterCarton", options.showOuterCarton())
                .data("showBarcode", options.showBarcode())
                .render();
    }

}
