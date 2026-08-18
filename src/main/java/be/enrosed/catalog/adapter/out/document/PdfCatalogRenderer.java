package be.enrosed.catalog.adapter.out.document;

import be.enrosed.catalog.application.CatalogExportService;
import be.enrosed.catalog.application.ProductService;
import be.enrosed.catalog.application.port.out.CatalogDocumentRenderer;
import be.enrosed.catalog.domain.Category;
import be.enrosed.catalog.domain.Photo;
import be.enrosed.catalog.domain.Product;
import be.enrosed.shared.Brand;
import be.enrosed.shared.DocumentText;
import be.enrosed.shared.Language;
import be.enrosed.shared.company.CompanyProfileService;
import be.enrosed.shared.DocumentFormat;
import be.enrosed.shared.Money;
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Bouwt de catalogus-PDF.
 *
 * De foto's worden als data-URI meegegeven: openhtmltopdf haalt geen
 * afbeeldingen op achter een aanmelding, en een catalogus zonder foto's is
 * geen catalogus.
 */
@ApplicationScoped
public class PdfCatalogRenderer implements CatalogDocumentRenderer {

    private static final Logger LOG = Logger.getLogger(PdfCatalogRenderer.class);

    private final Template template;
    private final ProductService products;
    private final Brand brand;
    private final CompanyProfileService company;
    private final be.enrosed.shared.PdfFonts fonts;

    public PdfCatalogRenderer(@Location("catalog.html") Template template,
                              ProductService products, Brand brand,
                              CompanyProfileService company,
                              be.enrosed.shared.PdfFonts fonts) {
        this.template = template;
        this.products = products;
        this.brand = brand;
        this.company = company;
        this.fonts = fonts;
    }

    /** One product card, ready for the template. */
    public record Item(String sku, String name, String description, String size, String colour,
                       String barcodeInner, String barcodeOuter,
                       int piecesPerCarton, String cartonSize,
                       String priceLabel, Integer stockQuantity,
                       /**
                        * First two photos render full size side by side; at a
                        * fair the second angle sells the product as much as
                        * the first. Anything beyond becomes a thumbnail.
                        */
                       String photoDataUri, String secondPhotoDataUri,
                       List<String> extraPhotos) {}

    /**
     * A category with its cards, three per row.
     *
     * The catalogue reads as chapters: the category name and its description
     * open a section, the products follow. At a fair that is how people
     * browse - "show me the glass domes" - not alphabetically across
     * everything at once.
     */
    public record Section(String name, String description, List<List<Item>> rows) {}

    @Override
    public Document render(List<Product> selection, Map<Long, Category> categoriesById,
                           CatalogExportService.Request request) {

        Language language = Language.of(request.language());
        Map<String, String> text = DocumentText.of(language);

        /* Group per category, keeping the configured category order. */
        Map<Long, List<Item>> byCategory = new LinkedHashMap<>();
        List<Category> ordered = categoriesById.values().stream()
                .sorted(Comparator.comparingInt(Category::position))
                .toList();
        for (Category category : ordered) {
            byCategory.put(category.id(), new ArrayList<>());
        }
        List<Item> uncategorised = new ArrayList<>();

        for (Product product : selection) {
            Item item = toItem(product, language, request);
            List<Item> bucket = product.categoryId() == null
                    ? uncategorised : byCategory.get(product.categoryId());
            (bucket == null ? uncategorised : bucket).add(item);
        }

        List<Section> sections = new ArrayList<>();
        for (Category category : ordered) {
            List<Item> items = byCategory.get(category.id());
            if (items.isEmpty()) continue;
            sections.add(new Section(category.name(), category.description(), chunk(items)));
        }
        if (!uncategorised.isEmpty()) {
            sections.add(new Section(null, null, chunk(uncategorised)));
        }

        String html = template
                .data("sections", sections)
                .data("itemCount", selection.size())
                /* The title is universal and follows the language; a manually
                   typed title would not be translated and drifts per export. */
                .data("title", text.get("catalogTitle"))
                .data("intro", request.intro())
                .data("todayText", DocumentText.date(LocalDate.now(), language))
                .data("logo", brand.logoDataUri())
                .data("company", company.get())
                .data("footerText", company.get().footerFor(language))
                .data("t", text)
                .render();

        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PdfRendererBuilder builder = new PdfRendererBuilder();
            builder.useFastMode();
            fonts.applyTo(builder);
            builder.withHtmlContent(html, null);
            builder.toStream(out);
            builder.run();
            return new Document("enrosed-catalogus.pdf", out.toByteArray(), "application/pdf");
        } catch (IOException e) {
            throw new UncheckedIOException("Kan de catalogus-PDF niet opbouwen", e);
        }
    }

    private Item toItem(Product product, Language language,
                        CatalogExportService.Request request) {
        return new Item(
                product.sku(),
                product.nameIn(language),
                product.descriptionIn(language),
                product.dimensions() == null ? "" : product.dimensions().label(),
                product.colourIn(language),
                product.barcodes() == null ? null : product.barcodes().inner(),
                product.barcodes() == null ? null : product.barcodes().outer(),
                product.carton() == null ? 0 : product.carton().piecesPerCarton(),
                product.carton() == null || product.carton().dimensions() == null
                        ? "" : product.carton().dimensions().label(),
                request.includePrices() ? priceLabel(product) : null,
                product.stockQuantity(),
                request.includePhotos() ? photoDataUri(product.primaryPhoto()) : null,
                request.includePhotos() ? photoAt(product, 1, request) : null,
                request.includePhotos() ? extraPhotos(product, request) : List.of());
    }

    /** The template language has no modulo, so rows of three are built here. */
    private static List<List<Item>> chunk(List<Item> items) {
        List<List<Item>> rows = new ArrayList<>();
        for (int i = 0; i < items.size(); i += 3) {
            rows.add(items.subList(i, Math.min(i + 3, items.size())));
        }
        return rows;
    }

    private String priceLabel(Product product) {
        BigDecimal cost = Money.nz(product.landedCostEur());
        BigDecimal price = product.fixedSalesPriceEur() != null
                && product.fixedSalesPriceEur().signum() > 0
                ? product.fixedSalesPriceEur()
                : Money.addPercent(cost, Money.nz(product.markupPct()));
        return DocumentFormat.eur(Money.money(price));
    }

    /** Bijbeelden na de hoofdfoto; standaard maximaal drie, anders wordt het rommelig. */
    /** The photo at the given position, when the export allows that many. */
    private String photoAt(Product product, int index, CatalogExportService.Request request) {
        int allowed = request.photosPerProduct() == null ? 4 : request.photosPerProduct();
        if (index >= allowed || index >= product.photos().size()) return null;
        return photoDataUri(product.photos().get(index));
    }

    /** Photos three and up, as thumbnails under the pair. */
    private List<String> extraPhotos(Product product, CatalogExportService.Request request) {
        int allowed = request.photosPerProduct() == null ? 4 : request.photosPerProduct();
        List<String> extras = new ArrayList<>();
        List<Photo> photos = product.photos();
        for (int i = 2; i < photos.size() && i < allowed; i++) {
            String uri = photoDataUri(photos.get(i));
            if (uri != null) extras.add(uri);
        }
        return extras;
    }

    private String photoDataUri(Photo photo) {
        if (photo == null) return null;
        try (InputStream in = products.photoData(photo.storageKey())) {
            String mime = photo.contentType() == null ? "image/jpeg" : photo.contentType();
            return "data:" + mime + ";base64," + Base64.getEncoder().encodeToString(in.readAllBytes());
        } catch (Exception e) {
            LOG.warnf("Foto %s kon niet in de catalogus: %s", photo.originalFilename(), e.getMessage());
            return null;
        }
    }
}
