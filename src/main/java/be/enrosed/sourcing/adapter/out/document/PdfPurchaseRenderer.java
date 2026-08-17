package be.enrosed.sourcing.adapter.out.document;

import be.enrosed.shared.Brand;
import be.enrosed.shared.DocumentFormat;
import be.enrosed.shared.PdfFonts;
import be.enrosed.shared.company.CompanyProfileService;
import be.enrosed.sourcing.domain.LandedCost;
import be.enrosed.sourcing.domain.PurchaseOrder;
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import jakarta.enterprise.context.ApplicationScoped;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * De inkoopcalculatie als PDF, om te bewaren of aan tafel te laten zien.
 *
 * Twee weergaven van hetzelfde blad:
 *
 *  - **intern** — alles erop, inclusief de gewenste extra opbrengst als eigen
 *    regel. Dat is het blad dat je bewaart.
 *  - **klantweergave** — diezelfde calculatie zonder die regel. Het bedrag zit
 *    wél in het totaal verrekend, zodat de kostprijs per stuk klopt met wat wij
 *    hanteren. Een klant die meekijkt ziet dus waar wij op uitkomen, niet hoeveel
 *    marge daarin zit.
 *
 * Welke van de twee je krijgt hangt af van de stand van de dubbelklikschakelaar
 * op het scherm. Dat is bewust dezelfde knop: één stand die bepaalt wat er te
 * zien is, op het scherm én op papier. Twee losse instellingen betekent vroeg of
 * laat dat je het scherm afdekt maar het verkeerde blad uitprint.
 */
@ApplicationScoped
public class PdfPurchaseRenderer {

    private final Template template;
    private final Brand brand;
    private final CompanyProfileService company;
    private final PdfFonts fonts;

    public PdfPurchaseRenderer(@Location("purchase.html") Template template, Brand brand,
                               CompanyProfileService company, PdfFonts fonts) {
        this.template = template;
        this.brand = brand;
        this.company = company;
        this.fonts = fonts;
    }

    public record Document(String filename, byte[] content, String contentType) {}

    /**
     * @param showRevenue toont de gewenste extra opbrengst als eigen regel. Staat
     *                    hij uit, dan blijft ze in het totaal maar niet in beeld.
     */
    public Document render(PurchaseOrder order, LandedCost costing, String supplierName,
                           boolean showRevenue) {
        String html = template
                .data("order", order)
                .data("costing", costing)
                .data("supplierName", supplierName == null ? "-" : supplierName)
                .data("orderDate", DocumentFormat.be(order.orderDate()))
                .data("logo", brand.logoDataUri())
                .data("company", company.get())
                .data("showRevenue", showRevenue)
                .render();

        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PdfRendererBuilder builder = new PdfRendererBuilder();
            builder.useFastMode();
            fonts.applyTo(builder);
            builder.withHtmlContent(html, null);
            builder.toStream(out);
            builder.run();

            String suffix = showRevenue ? "" : "-klantweergave";
            return new Document(order.number() + suffix + ".pdf", out.toByteArray(),
                    "application/pdf");
        } catch (IOException e) {
            throw new UncheckedIOException("Kan de inkoop-PDF niet opbouwen", e);
        }
    }
}
