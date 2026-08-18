package be.enrosed.shared;

import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import jakarta.enterprise.context.ApplicationScoped;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Het lettertype voor onze PDF's.
 *
 * Zonder ingesloten lettertype valt openhtmltopdf terug op de ingebouwde
 * PDF-fonts. Die kennen alleen West-Europees schrift: een Poolse offerte toont
 * dan "P#atno##" in plaats van "Płatność" en een Turkse "numaras#" in plaats
 * van "numarası". Dat merk je niet bij het bouwen - alleen de klant ziet het,
 * op het document dat hij moet tekenen.
 *
 * DejaVu Sans dekt Latijns uitgebreid A en B, dus Pools, Turks, Tsjechisch en
 * de Baltische talen. Het staat in de jar zodat het overal werkt en niet
 * afhangt van wat er toevallig op de server geïnstalleerd is.
 *
 * Licentie: DejaVu is vrij te gebruiken en te herdistribueren, ook commercieel
 * (zie fonts/LICENSE.txt). Vandaar bewust dit lettertype en niet een systeemfont
 * van de ontwikkelmachine - die zijn zelden vrij mee te leveren.
 */
@ApplicationScoped
public class PdfFonts {

    private static final String FAMILY = "DejaVu Sans";

    private Path regular;
    private Path bold;

    /**
     * Hangt de lettertypes aan een renderer.
     *
     * openhtmltopdf wil een bestand op schijf, geen stream uit de jar, dus
     * worden ze één keer uitgepakt naar een tijdelijk bestand dat blijft staan
     * zolang het proces draait.
     */
    public void applyTo(PdfRendererBuilder builder) {
        ensureExtracted();
        builder.useFont(regular.toFile(), FAMILY, 400,
                PdfRendererBuilder.FontStyle.NORMAL, true);
        builder.useFont(bold.toFile(), FAMILY, 700,
                PdfRendererBuilder.FontStyle.NORMAL, true);
    }

    /** De naam zoals de sjablonen hem in font-family gebruiken. */
    public String family() {
        return FAMILY;
    }

    private synchronized void ensureExtracted() {
        if (regular != null && bold != null) return;
        regular = extract("fonts/DejaVuSans.ttf", "enrosed-dejavu-sans");
        bold = extract("fonts/DejaVuSans-Bold.ttf", "enrosed-dejavu-sans-bold");
    }

    private static Path extract(String resource, String prefix) {
        try (InputStream in = Thread.currentThread().getContextClassLoader()
                .getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("Lettertype " + resource + " zit niet in de jar");
            }
            Path target = Files.createTempFile(prefix, ".ttf");
            target.toFile().deleteOnExit();
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            return target;
        } catch (IOException e) {
            throw new UncheckedIOException("Kan lettertype " + resource + " niet uitpakken", e);
        }
    }

    /**
     * Renders HTML to a PDF with the embedded fonts applied.
     *
     * Every document in the system goes through this one method, so the
     * builder setup (fast mode, fonts) cannot drift apart between the
     * quote, the catalogue and the purchase sheet.
     */
    public byte[] render(String html) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PdfRendererBuilder builder = new PdfRendererBuilder();
            builder.useFastMode();
            applyTo(builder);
            builder.withHtmlContent(html, null);
            builder.toStream(out);
            builder.run();
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not build the PDF", e);
        }
    }
}
