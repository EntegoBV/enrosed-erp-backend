package be.enrosed.sourcing.adapter.out.document;

import be.enrosed.catalog.adapter.out.document.PdfImageEncoder;
import be.enrosed.catalog.application.ProductService;
import be.enrosed.catalog.application.ProductSupplierAgreementPhotoService;
import be.enrosed.catalog.application.ProductSupplierAgreementService;
import be.enrosed.catalog.domain.Dimensions;
import be.enrosed.catalog.domain.Product;
import be.enrosed.shared.Brand;
import be.enrosed.shared.Language;
import be.enrosed.shared.NotFoundException;
import be.enrosed.shared.PdfFonts;
import be.enrosed.sourcing.domain.PurchaseOrder;
import be.enrosed.sourcing.domain.PurchaseOrderLine;
import be.enrosed.sourcing.domain.Supplier;
import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.BadRequestException;
import org.jboss.logging.Logger;

import java.awt.Color;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** A blank inspection worksheet. Financial order/product objects never enter its template. */
@ApplicationScoped
public class PdfPurchaseInspectionRenderer {
    private static final Logger LOG = Logger.getLogger(PdfPurchaseInspectionRenderer.class);
    private final Template template;
    private final Brand brand;
    private final PdfFonts fonts;
    private final ProductService products;
    private final ProductSupplierAgreementService agreements;
    private final ProductSupplierAgreementPhotoService agreementPhotos;
    private final PdfImageEncoder images;

    public PdfPurchaseInspectionRenderer(@Location("purchase-inspection.html") Template template,
            Brand brand, PdfFonts fonts, ProductService products,
            ProductSupplierAgreementService agreements,
            ProductSupplierAgreementPhotoService agreementPhotos, PdfImageEncoder images) {
        this.template = template;
        this.brand = brand;
        this.fonts = fonts;
        this.products = products;
        this.agreements = agreements;
        this.agreementPhotos = agreementPhotos;
        this.images = images;
    }

    public static Language language(String value) {
        String normalized = value == null || value.isBlank() ? "EN" : value.strip().toUpperCase(Locale.ROOT);
        if (!normalized.equals("EN") && !normalized.equals("NL")) {
            throw new BadRequestException("Inspection PDF language must be EN or NL");
        }
        return Language.valueOf(normalized);
    }

    public record Options(Language language, boolean includePhotos, boolean includeSupplierAgreements) {
        public Options {
            language = PdfPurchaseInspectionRenderer.language(language == null ? null : language.name());
        }
    }

    public record Fact(String label, String value) {}
    public record InspectionLine(int position, Long lineId, Long productId, String sku, String name,
            String colour, String variantSize, int ordered, String piecesPerCarton, String cartons,
            String photo, List<Fact> facts, String agreementReference) {}
    public record ReferencePhoto(int position, String caption, String dataUri) {}
    public record Agreement(String reference, List<String> appliesTo, List<String> paragraphs,
            List<ReferencePhoto> photos) {}
    public record Brief(String number, String generatedOn, List<Fact> shipment,
            String supplierName, List<String> supplierAddress, List<Fact> supplierContact,
            List<InspectionLine> lines, List<Agreement> agreements, long orderedPieces) {}

    public PdfPurchaseRenderer.Document render(PurchaseOrder order, Supplier supplier, Options options) {
        Brief brief = prepare(order, supplier, options);
        String html = template.data("brief", brief).data("t", labels(options.language()))
                .data("logo", brand.logoDataUri()).data("language", options.language().code()).render();
        String number = order.number() == null ? String.valueOf(order.id()) : order.number();
        String filename = "inspection-" + number.replaceAll("[^\\p{L}\\p{N}._-]", "-")
                + "-" + options.language().code() + ".pdf";
        return new PdfPurchaseRenderer.Document(filename, fonts.render(html), "application/pdf");
    }

    Brief prepare(PurchaseOrder order, Supplier supplier, Options options) {
        Map<String, String> t = labels(options.language());
        Map<Long, Product> byId = new HashMap<>();
        Map<String, String> photoCache = new HashMap<>();
        Map<Long, ProductSupplierAgreementService.ResolvedAgreement> resolved = new HashMap<>();
        Map<String, Group> groups = new LinkedHashMap<>();
        List<InspectionLine> lines = new ArrayList<>();
        for (PurchaseOrderLine line : order.lines()) {
            if (line == null) continue;
            int position = lines.size() + 1;
            Product product = line.productId() == null ? null : byId.computeIfAbsent(line.productId(), id -> {
                try { return products.get(id); }
                catch (NotFoundException missing) { return null; }
            });
            String reference = null;
            if (options.includeSupplierAgreements() && product != null && order.supplierId() != null) {
                var agreement = resolved.computeIfAbsent(product.id(), agreements::resolve);
                if (agreement != null && agreement.available()
                        && ((agreement.note() != null && !agreement.note().isBlank()) || !agreement.photos().isEmpty())
                        && Objects.equals(agreement.supplierId(), order.supplierId())) {
                    Group group = groups.computeIfAbsent(agreement.groupKey(), key ->
                            new Group("A" + (groups.size() + 1), agreement));
                    group.appliesTo.add(t.get("line") + " " + position + " · " + value(product.sku())
                            + " · " + value(product.colourIn(options.language())));
                    reference = group.reference;
                }
            }
            Integer packing = product != null && product.carton() != null
                    && product.carton().piecesPerCarton() > 0 ? product.carton().piecesPerCarton() : null;
            int ordered = line.ordered();
            String cartons = packing == null ? "-" : Long.toString((ordered + (long) packing - 1) / packing);
            List<Fact> facts = new ArrayList<>();
            facts.add(new Fact(t.get("productDimensions"), dimensions(product == null ? null : product.dimensions())));
            facts.add(new Fact(t.get("productBarcode"), value(productBarcode(product))));
            facts.add(new Fact(t.get("innerBarcode"), value(product == null || product.barcodes() == null ? null : product.barcodes().inner())));
            var packaging = product == null ? null : product.packaging();
            facts.add(new Fact(t.get("presentation"), packaging == null || !packaging.isPresent() ? "-"
                    : (options.language() == Language.NL ? packaging.kind().dutchLabel()
                            : packaging.kind() == be.enrosed.catalog.domain.PackagingKind.DISPLAY ? "Display" : "Gift box")
                            + " · " + dimensions(packaging.dimensions())));
            facts.add(new Fact(t.get("packagingBarcode"), value(packaging == null ? null : packaging.barcode())));
            facts.add(new Fact(t.get("packagingPieces"), packaging == null || !packaging.isPresent()
                    || packaging.piecesPerUnit() == null ? "-" : packaging.piecesPerUnit().toString()));
            var carton = product == null ? null : product.carton();
            facts.add(new Fact(t.get("cartonDimensions"), dimensions(carton == null ? null : carton.dimensions())));
            facts.add(new Fact(t.get("cartonWeight"), carton == null || carton.weightKg() == null
                    || carton.weightKg().signum() <= 0 ? "-" : decimal(carton.weightKg()) + " kg"));
            facts.add(new Fact(t.get("cartonBarcode"), value(product == null || product.barcodes() == null ? null : product.barcodes().outer())));
            lines.add(new InspectionLine(position, line.id(), line.productId(),
                    value(product == null ? null : product.sku()),
                    product == null ? t.get("missingProduct") + " #" + line.productId() : value(product.nameIn(options.language())),
                    value(product == null ? null : product.colourIn(options.language())),
                    value(product == null ? null : product.variantSizeIn(options.language())), ordered,
                    packing == null ? "-" : packing.toString(), cartons,
                    options.includePhotos() ? photo(product, photoCache) : null, List.copyOf(facts), reference));
        }
        List<Agreement> preparedAgreements = new ArrayList<>();
        for (Group group : groups.values()) {
            List<ReferencePhoto> photos = new ArrayList<>();
            for (var reference : group.source.photos()) {
                String dataUri = null;
                try (InputStream data = agreementPhotos.open(group.source.sourceProductId(), reference.id()).data()) {
                    dataUri = images.encodeContained(data.readAllBytes(), 1000, 680, Color.WHITE);
                } catch (Exception failure) {
                    LOG.warnf("Inspection agreement image %d unavailable", reference.id());
                }
                // Keep a numbered missing-image placeholder rather than silently dropping evidence.
                photos.add(new ReferencePhoto(reference.position() + 1,
                        reference.caption() == null ? null : wrapTokens(reference.caption()), dataUri));
            }
            preparedAgreements.add(new Agreement(group.reference, List.copyOf(group.appliesTo),
                    paragraphs(group.source.note()), List.copyOf(photos)));
        }
        List<Fact> shipment = List.of(
                new Fact(t.get("orderDate"), date(order.orderDate())),
                new Fact(t.get("container"), containerLabel(order)),
                new Fact(t.get("departure"), value(order.recordedDeparturePort())),
                new Fact(t.get("destination"), value(order.recordedDestinationPort())),
                new Fact(t.get("shipped"), date(order.shippedOn())),
                new Fact(t.get("arrival"), date(order.expectedArrival())),
                new Fact(t.get("tracking"), value(order.trackingReference())));
        return new Brief(value(order.number()), LocalDate.now().toString(), shipment,
                value(supplier == null ? null : supplier.name()),
                supplier == null ? List.of() : supplier.documentAddressLines().stream().map(PdfPurchaseInspectionRenderer::wrapTokens).toList(),
                List.of(new Fact(t.get("contact"), value(supplier == null ? null : supplier.contact())),
                        new Fact(t.get("email"), value(supplier == null ? null : supplier.email())),
                        new Fact(t.get("phone"), value(supplier == null ? null : supplier.phone()))),
                List.copyOf(lines), List.copyOf(preparedAgreements),
                lines.stream().mapToLong(InspectionLine::ordered).sum());
    }

    private static final class Group {
        final String reference;
        final ProductSupplierAgreementService.ResolvedAgreement source;
        final List<String> appliesTo = new ArrayList<>();
        Group(String reference, ProductSupplierAgreementService.ResolvedAgreement source) {
            this.reference = reference;
            this.source = source;
        }
    }

    private String photo(Product product, Map<String, String> cache) {
        var photo = product == null ? null : product.primaryPhoto();
        if (photo == null || photo.storageKey() == null) return null;
        return cache.computeIfAbsent(photo.storageKey(), key -> {
            try (InputStream data = products.photoData(key)) {
                return images.encodeContained(data.readAllBytes(), 700, 700, Color.WHITE);
            } catch (Exception failure) {
                LOG.warnf("Inspection product image unavailable for product %d", product.id());
                return null;
            }
        });
    }

    static List<String> paragraphs(String text) {
        if (text == null || text.isBlank()) return List.of();
        List<String> result = new ArrayList<>();
        for (String paragraph : text.strip().split("\\R")) {
            // Bounded table rows can flow over pages even for very long unbroken instructions.
            for (int offset = 0; offset < paragraph.length();) {
                int end = Math.min(offset + 500, paragraph.length());
                if (end < paragraph.length()) {
                    int boundary = end;
                    while (boundary > offset && !Character.isWhitespace(paragraph.charAt(boundary))) boundary--;
                    if (boundary > offset) end = boundary;
                    else while (end < paragraph.length() && !Character.isWhitespace(paragraph.charAt(end))) end++;
                }
                result.add(wrapTokens(paragraph.substring(offset, end).strip()));
                offset = end;
                while (offset < paragraph.length() && Character.isWhitespace(paragraph.charAt(offset))) offset++;
            }
        }
        return List.copyOf(result);
    }

    static String dimensions(Dimensions dimensions) {
        if (dimensions == null || dimensions.isBlank()) return "-";
        return positive(dimensions.lengthCm()) + " × " + positive(dimensions.widthCm()) + " × "
                + positive(dimensions.heightCm()) + " cm";
    }

    private static String productBarcode(Product product) {
        if (product == null) return null;
        if (product.barcodes() != null && product.barcodes().inner() != null
                && !product.barcodes().inner().isBlank()) return product.barcodes().inner();
        return product.canonicalBarcode();
    }

    private static String containerLabel(PurchaseOrder order) {
        if (order.containerType() == null) return "-";
        return order.containerType().code() + " · " + order.containerType().label();
    }

    private static String positive(BigDecimal value) { return value == null || value.signum() <= 0 ? "-" : decimal(value); }
    private static String decimal(BigDecimal value) { return value.stripTrailingZeros().toPlainString(); }
    private static String date(LocalDate value) { return value == null ? "-" : value.toString(); }
    private static String value(String value) { return value == null || value.isBlank() ? "-" : wrapTokens(value.strip()); }
    private static String wrapTokens(String text) { return text.replaceAll("(\\S{32})(?=\\S)", "$1\u200b"); }

    static Map<String, String> labels(Language language) {
        boolean nl = language == Language.NL;
        Map<String, String> t = new LinkedHashMap<>();
        String[][] rows = {
                {"title", "Pre-shipment inspection", "Inspectie vóór verzending"},
                {"subtitle", "Inspection brief & blank worksheet", "Inspectiebrief en invulformulier"},
                {"draft", "To be completed by the inspector. No results or approval have been recorded.", "In te vullen door de inspecteur. Er zijn geen resultaten of goedkeuring vastgelegd."},
                {"reference", "Purchase order", "Inkooporder"}, {"generated", "Prepared", "Opgesteld"},
                {"supplier", "Supplier / factory contact", "Leverancier / fabriekscontact"},
                {"factoryNote", "Confirm the actual inspection location with the supplier.", "Bevestig de daadwerkelijke inspectielocatie met de leverancier."},
                {"shipment", "Shipment reference", "Zendingsgegevens"},
                {"orderDate", "Order date", "Orderdatum"}, {"container", "Container type", "Containertype"},
                {"departure", "Port of loading", "Vertrekhaven"}, {"destination", "Port of discharge", "Aankomsthaven"},
                {"shipped", "Recorded departure", "Vastgelegd vertrek"}, {"arrival", "Expected arrival", "Verwachte aankomst"},
                {"tracking", "Container / shipment reference", "Container- / zendingsreferentie"},
                {"contact", "Contact", "Contact"}, {"email", "Email", "E-mail"}, {"phone", "Phone", "Telefoon"},
                {"manifest", "Ordered assortment", "Besteld assortiment"}, {"line", "Line", "Regel"},
                {"product", "Product / variant", "Product / variant"}, {"colour", "Colour", "Kleur"},
                {"sizeLabel", "Size option", "Maatvariant"}, {"ordered", "Ordered pcs", "Besteld (st)"},
                {"packing", "Pcs / carton", "St / omdoos"}, {"cartons", "Cartons*", "Omdozen*"},
                {"total", "Total ordered pieces", "Totaal bestelde stuks"},
                {"baseline", "Order quantities use the original placed-order quantity when recorded. Specifications and reference images reflect the current product records; confirm them against approved samples.", "Aantallen volgen de oorspronkelijke bestelhoeveelheid waar vastgelegd. Specificaties en referentiefoto's komen uit de huidige productgegevens; vergelijk ze met goedgekeurde monsters."},
                {"unknown", "- = not recorded. * Cartons are calculated from ordered pieces and the recorded carton capacity; count the actual packed cartons, including partial cartons.", "- = niet vastgelegd. * Omdozen zijn berekend uit bestelde stuks en de vastgelegde doosinhoud; tel de werkelijk verpakte omdozen, inclusief deeldozen."},
                {"details", "Product checks", "Productcontroles"}, {"photo", "Product reference", "Productreferentie"},
                {"noPhoto", "Reference image not included or unavailable", "Referentiefoto niet toegevoegd of niet beschikbaar"},
                {"productDimensions", "Product W × D × H", "Product B × D × H"},
                {"productBarcode", "Product barcode", "Productbarcode"}, {"innerBarcode", "Inner barcode", "Binnenbarcode"},
                {"presentation", "Presentation packaging", "Presentatieverpakking"},
                {"packagingBarcode", "Packaging barcode", "Verpakkingsbarcode"}, {"packagingPieces", "Pcs / presentation unit", "St / presentatie-eenheid"},
                {"cartonDimensions", "Outer carton W × D × H", "Omdoos B × D × H"},
                {"cartonWeight", "Recorded carton weight", "Vastgelegd doosgewicht"}, {"cartonBarcode", "Outer carton barcode", "Omdoosbarcode"},
                {"missingProduct", "Product record unavailable", "Productgegevens niet beschikbaar"},
                {"agreement", "Supplier instruction", "Leveranciersafspraak"},
                {"sampled", "Actual sampled pcs", "Werkelijk onderzochte stuks"}, {"packed", "Actual packed pcs / cartons", "Werkelijk verpakt st / dozen"},
                {"measured", "Measured dimensions / weight", "Gemeten afmetingen / gewicht"},
                {"defects", "Defective pcs / defect references", "Stuks met gebreken / gebrekreferenties"},
                {"photoRefs", "Inspection photo references", "Fotoverwijzingen inspectie"},
                {"observations", "Findings / differences from the reference", "Bevindingen / afwijkingen van de referentie"},
                {"agreements", "Linked supplier instructions", "Gekoppelde leveranciersafspraken"},
                {"agreementLead", "Each instruction is included once for the order lines listed below. Supplier wording and image captions are reproduced as recorded.", "Elke afspraak staat eenmaal bij de hieronder genoemde orderregels. Leverancierstekst en fotobijschriften zijn overgenomen zoals vastgelegd."},
                {"appliesTo", "Applies to", "Van toepassing op"}, {"imageReference", "Reference image", "Referentiefoto"},
                {"missingReference", "Image unavailable - obtain this reference before checking it.", "Foto niet beschikbaar - vraag deze referentie op vóór de controle."},
                {"checklist", "Inspection record", "Inspectieverslag"},
                {"method", "Record the sampling method and sample size actually used. No sampling standard, acceptance limit or result is prescribed by this worksheet.", "Noteer de werkelijk gebruikte steekproefmethode en omvang. Dit formulier schrijft geen steekproefnorm, acceptatiegrens of uitkomst voor."},
                {"inspector", "Inspector / inspection company", "Inspecteur / inspectiebedrijf"},
                {"inspectionDate", "Inspection date / location", "Inspectiedatum / locatie"},
                {"sampling", "Sampling method / selection / actual sample size", "Steekproefmethode / selectie / werkelijk aantal"},
                {"check", "Check", "Controle"}, {"result", "Result / evidence / not checked", "Resultaat / bewijs / niet gecontroleerd"},
                {"identityCheck", "Product identity, colour and size vs approved reference", "Productidentiteit, kleur en maat versus goedgekeurde referentie"},
                {"quantityCheck", "Piece counts, carton counts and assortment", "Stuks, omdozen en assortimentsverdeling"},
                {"qualityCheck", "Appearance, damage, finish and assembly", "Uiterlijk, beschadiging, afwerking en montage"},
                {"functionCheck", "Function / lighting where applicable", "Werking / verlichting waar van toepassing"},
                {"packingCheck", "Protection, packaging and carton condition", "Bescherming, verpakking en staat van omdozen"},
                {"labelsCheck", "SKU, barcode readability and carton markings", "SKU, leesbaarheid barcodes en doosmarkeringen"},
                {"instructionCheck", "Linked supplier instructions and reference photos", "Gekoppelde leveranciersafspraken en referentiefoto's"},
                {"defectLog", "Defect and evidence log", "Gebreken- en bewijsregistratie"},
                {"defectDetail", "Line / defect / affected pcs / image reference", "Regel / gebrek / betreffende stuks / fotoreferentie"},
                {"conclusion", "Inspector conclusion / corrective action / follow-up", "Conclusie inspecteur / herstelactie / opvolging"},
                {"signature", "Inspector name / signature / date", "Naam / handtekening / datum inspecteur"},
                {"acknowledgement", "Supplier representative / acknowledgement / date", "Leveranciersvertegenwoordiger / kennisname / datum"},
                {"footer", "Blank inspection worksheet - complete from actual observations", "Blanco inspectieformulier - vul in op basis van werkelijke waarnemingen"}
        };
        for (String[] row : rows) t.put(row[0], row[nl ? 2 : 1]);
        return Map.copyOf(t);
    }
}
