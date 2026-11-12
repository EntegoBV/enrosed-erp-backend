package be.enrosed.catalog.adapter.out.document;

import be.enrosed.catalog.application.port.out.CatalogPdfCompression;
import jakarta.enterprise.context.ApplicationScoped;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.image.JPEGFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.jboss.logging.Logger;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.awt.image.WritableRaster;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Makes a finished catalogue PDF small enough to mail: every photo at most 1400 px on its longest
 * side, as a JPEG of quality 0.80 with its transparency kept as a soft mask. The catalogue embeds its
 * transparent product photos losslessly at print size, which made the whole range 65-70 MB; this
 * brings it to about 10 MB (measured on the brochures of 2026-10-26/30: 69.7 to 10.4 MB, 65.7 to
 * 9.9 MB), a difference that does not show at 200 dpi. Text, fonts and vector parts stay as they
 * are; a photo that would not get smaller keeps its original. Any failure hands back the original.
 */
@ApplicationScoped
public class PdfCompressor implements CatalogPdfCompression {

    private static final Logger LOG = Logger.getLogger(PdfCompressor.class);

    /** Longest side in pixels: about 170 dpi over a full A4 width, more for every smaller photo. */
    static final int MAX_EDGE = 1400;
    static final float JPEG_QUALITY = 0.80f;

    @Override
    public byte[] compress(byte[] pdf) {
        if (pdf == null || pdf.length == 0) return pdf;
        try (PDDocument document = Loader.loadPDF(pdf)) {
            Map<COSBase, PDImageXObject> recoded = new HashMap<>();
            for (PDPage page : document.getPages()) recode(document, page.getResources(), recoded, new HashSet<>());
            ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(1024, pdf.length / 4));
            document.save(out);
            byte[] smaller = out.toByteArray();
            return smaller.length < pdf.length ? smaller : pdf;
        } catch (IOException | RuntimeException failure) {
            LOG.warnf(failure, "Catalogus-PDF niet verkleind; het origineel van %d bytes gaat mee", pdf.length);
            return pdf;
        }
    }

    /** Replaces every image of these resources, and of the forms they draw, by its smaller version. */
    private void recode(PDDocument document, PDResources resources, Map<COSBase, PDImageXObject> recoded,
                        Set<COSBase> forms) throws IOException {
        if (resources == null) return;
        for (COSName name : resources.getXObjectNames()) {
            PDXObject object = resources.getXObject(name);
            if (object instanceof PDImageXObject image) {
                /* One photo can stand on several pages: it is recoded once and every page points to that one. */
                PDImageXObject smaller = recoded.get(image.getCOSObject());
                if (smaller == null) {
                    smaller = smaller(document, image);
                    recoded.put(image.getCOSObject(), smaller);
                }
                if (smaller != image) resources.put(name, smaller);
            } else if (object instanceof PDFormXObject form && forms.add(form.getCOSObject())) {
                recode(document, form.getResources(), recoded, forms);
            }
        }
    }

    /** The photo at most MAX_EDGE wide or high as a JPEG with its soft mask; the original when that is not smaller. */
    private static PDImageXObject smaller(PDDocument document, PDImageXObject image) throws IOException {
        if (image.isStencil() || image.getBitsPerComponent() < 8) return image;
        BufferedImage colour = image.getImage();
        if (colour == null) return image;
        PDImageXObject maskObject = image.getSoftMask();
        BufferedImage mask = maskObject == null ? null : maskObject.getImage();
        double factor = Math.min(1d, (double) MAX_EDGE / Math.max(colour.getWidth(), colour.getHeight()));
        int width = Math.max(1, (int) Math.round(colour.getWidth() * factor));
        int height = Math.max(1, (int) Math.round(colour.getHeight() * factor));

        BufferedImage target = new BufferedImage(width, height,
                mask == null ? BufferedImage.TYPE_INT_RGB : BufferedImage.TYPE_INT_ARGB);
        draw(target, colour);
        if (mask != null) {
            BufferedImage alpha = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY);
            draw(alpha, mask);
            WritableRaster alphaRaster = target.getAlphaRaster();
            alphaRaster.setSamples(0, 0, width, height, 0, alpha.getRaster().getSamples(0, 0, width, height, 0, (int[]) null));
        }
        PDImageXObject jpeg = JPEGFactory.createFromImage(document, target, JPEG_QUALITY);
        return size(jpeg) < size(image) ? jpeg : image;
    }

    private static void draw(BufferedImage target, BufferedImage source) {
        Graphics2D graphics = target.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            graphics.drawImage(source, 0, 0, target.getWidth(), target.getHeight(), null);
        } finally {
            graphics.dispose();
        }
    }

    /** Stored bytes of an image and its soft mask. */
    private static long size(PDImageXObject image) throws IOException {
        PDImageXObject mask = image.getSoftMask();
        return image.getCOSObject().getLength() + (mask == null ? 0 : mask.getCOSObject().getLength());
    }
}
