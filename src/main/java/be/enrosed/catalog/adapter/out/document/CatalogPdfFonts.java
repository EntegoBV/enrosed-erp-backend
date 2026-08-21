package be.enrosed.catalog.adapter.out.document;

import be.enrosed.shared.PdfFonts;
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import jakarta.enterprise.context.ApplicationScoped;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;

/** Embedded ENROSED display and UI typefaces for deterministic catalogue output. */
@ApplicationScoped
public class CatalogPdfFonts {

    private final PdfFonts fallback;
    private final Map<String, Path> extracted = new HashMap<>();

    public CatalogPdfFonts(PdfFonts fallback) {
        this.fallback = fallback;
    }

    public byte[] render(String html) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PdfRendererBuilder builder = new PdfRendererBuilder();
            builder.useFastMode();
            fallback.applyTo(builder);
            font(builder, "fonts/catalog/Jost-400-Book.ttf", "Jost", 400,
                    PdfRendererBuilder.FontStyle.NORMAL);
            font(builder, "fonts/catalog/Jost-500-Medium.ttf", "Jost", 500,
                    PdfRendererBuilder.FontStyle.NORMAL);
            font(builder, "fonts/catalog/Jost-600-Semi.ttf", "Jost", 600,
                    PdfRendererBuilder.FontStyle.NORMAL);
            font(builder, "fonts/catalog/CormorantGaramond-Regular.ttf",
                    "Cormorant Garamond", 400, PdfRendererBuilder.FontStyle.NORMAL);
            font(builder, "fonts/catalog/CormorantGaramond-SemiBold.ttf",
                    "Cormorant Garamond", 600, PdfRendererBuilder.FontStyle.NORMAL);
            font(builder, "fonts/catalog/CormorantGaramond-Italic.ttf",
                    "Cormorant Garamond", 400, PdfRendererBuilder.FontStyle.ITALIC);
            builder.withHtmlContent(html, null);
            builder.toStream(out);
            builder.run();
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not build the catalogue PDF", e);
        }
    }

    private void font(PdfRendererBuilder builder, String resource, String family,
                      int weight, PdfRendererBuilder.FontStyle style) {
        builder.useFont(extract(resource).toFile(), family, weight, style, true);
    }

    private synchronized Path extract(String resource) {
        return extracted.computeIfAbsent(resource, key -> {
            try (InputStream in = Thread.currentThread().getContextClassLoader()
                    .getResourceAsStream(key)) {
                if (in == null) throw new IllegalStateException("Missing catalogue font " + key);
                Path target = Files.createTempFile("enrosed-catalog-font-", ".ttf");
                target.toFile().deleteOnExit();
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
                return target;
            } catch (IOException e) {
                throw new UncheckedIOException("Could not extract catalogue font " + key, e);
            }
        });
    }
}
