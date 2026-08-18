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
 * The typeface for our PDFs.
 *
 * Without an embedded font, openhtmltopdf falls back to the built-in PDF
 * fonts. Those only know Western European script: a Polish quote then shows
 * "P#atno##" instead of "Płatność" and a Turkish one "numaras#" instead of
 * "numarası". You never notice at build time - only the customer sees it,
 * on the document they are asked to sign.
 *
 * DejaVu Sans covers Latin Extended A and B, so Polish, Turkish, Czech and
 * the Baltic languages. It ships inside the jar so it works everywhere and
 * does not depend on whatever happens to be installed on the server.
 *
 * Licence: DejaVu is free to use and redistribute, commercially included
 * (see fonts/LICENSE.txt). Hence deliberately this typeface and not a system
 * font from the development machine - those are rarely free to ship.
 */
@ApplicationScoped
public class PdfFonts {

    private static final String FAMILY = "DejaVu Sans";

    private Path regular;
    private Path bold;

    /**
     * Attaches the typefaces to a renderer.
     *
     * openhtmltopdf wants a file on disk, not a stream from the jar, so they
     * are unpacked once into a temporary file that lives as long as the
     * process does.
     */
    public void applyTo(PdfRendererBuilder builder) {
        ensureExtracted();
        builder.useFont(regular.toFile(), FAMILY, 400,
                PdfRendererBuilder.FontStyle.NORMAL, true);
        builder.useFont(bold.toFile(), FAMILY, 700,
                PdfRendererBuilder.FontStyle.NORMAL, true);
    }

    /** The name as the templates use it in font-family. */
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
