package be.enrosed.catalog.adapter.out.document;

import be.enrosed.catalog.application.CatalogExportService;
import be.enrosed.catalog.application.ProductService;
import be.enrosed.catalog.application.port.out.CatalogDocumentRenderer;
import be.enrosed.catalog.domain.Category;
import be.enrosed.catalog.domain.Photo;
import be.enrosed.catalog.domain.Product;
import be.enrosed.shared.Brand;
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

    /** Eén productregel, klaar voor het sjabloon. */
    public record Item(String sku, String name, String size, String colour, String category,
                       String barcodeInner, String barcodeOuter,
                       int piecesPerCarton, String cartonSize,
                       String priceLabel, Integer stockQuantity,
                       /** Hoofdfoto groot, de rest als kleine bijbeelden. */
                       String photoDataUri, List<String> extraPhotos) {}

    @Override
    public Document render(List<Product> selection, Map<Long, Category> categoriesById,
                           CatalogExportService.Request request) {

        List<Item> items = new ArrayList<>();
        for (Product product : selection) {
            Category category = product.categoryId() == null
                    ? null : categoriesById.get(product.categoryId());

            items.add(new Item(
                    product.sku(),
                    product.name(),
                    product.dimensions() == null ? "" : product.dimensions().label(),
                    product.colour(),
                    category == null ? "" : category.name(),
                    product.barcodes() == null ? null : product.barcodes().inner(),
                    product.barcodes() == null ? null : product.barcodes().outer(),
                    product.carton() == null ? 0 : product.carton().piecesPerCarton(),
                    product.carton() == null || product.carton().dimensions() == null
                            ? "" : product.carton().dimensions().label(),
                    request.includePrices() ? priceLabel(product) : null,
                    product.stockQuantity(),
                    request.includePhotos() ? photoDataUri(product.primaryPhoto()) : null,
                    request.includePhotos() ? extraPhotos(product, request) : List.of()));
        }

        /* De sjabloontaal kan geen modulo, dus de rijen worden hier al gevormd.
           Twee kaarten per rij; de laatste rij krijgt eventueel een lege cel. */
        List<List<Item>> rows = new ArrayList<>();
        for (int i = 0; i < items.size(); i += 2) {
            rows.add(items.subList(i, Math.min(i + 2, items.size())));
        }

        String html = template
                .data("items", items)
                .data("rows", rows)
                .data("title", request.title() == null || request.title().isBlank()
                        ? "Productcatalogus" : request.title())
                .data("intro", request.intro())
                .data("today", LocalDate.now())
                .data("logo", brand.logoDataUri())
                .data("company", company.get())
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

    private String priceLabel(Product product) {
        BigDecimal cost = Money.nz(product.landedCostEur());
        BigDecimal price = product.fixedSalesPriceEur() != null
                && product.fixedSalesPriceEur().signum() > 0
                ? product.fixedSalesPriceEur()
                : Money.addPercent(cost, Money.nz(product.markupPct()));
        return DocumentFormat.eur(Money.money(price));
    }

    /** Bijbeelden na de hoofdfoto; standaard maximaal drie, anders wordt het rommelig. */
    private List<String> extraPhotos(Product product, CatalogExportService.Request request) {
        int limit = request.photosPerProduct() == null ? 3 : Math.max(0, request.photosPerProduct() - 1);
        if (limit == 0) return List.of();

        List<String> extras = new ArrayList<>();
        List<Photo> photos = product.photos();
        for (int i = 1; i < photos.size() && extras.size() < limit; i++) {
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
