package be.enrosed.catalog.adapter.out.document;

import jakarta.enterprise.context.ApplicationScoped;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.Iterator;

/** Converts every supported dashboard image, including WebP, to a PDF-safe data URI. */
@ApplicationScoped
public class PdfImageEncoder {

    private static final int MAX_PRINT_EDGE = 1_800;
    private static final float JPEG_QUALITY = 0.88f;

    public String encode(byte[] source) {
        return encode(source, MAX_PRINT_EDGE);
    }

    public String encode(byte[] source, int maxEdge) {
        if (source == null || source.length == 0) return null;
        try {
            BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(source));
            if (decoded == null) return null;
            BufferedImage scaled = scale(decoded, Math.max(320, Math.min(MAX_PRINT_EDGE, maxEdge)));
            boolean alpha = scaled.getColorModel().hasAlpha();
            byte[] encoded = alpha ? png(scaled) : jpeg(scaled);
            String mime = alpha ? "image/png" : "image/jpeg";
            return "data:" + mime + ";base64," + Base64.getEncoder().encodeToString(encoded);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static BufferedImage scale(BufferedImage source, int maxEdge) {
        double factor = Math.min(1d, (double) maxEdge
                / Math.max(source.getWidth(), source.getHeight()));
        int width = Math.max(1, (int) Math.round(source.getWidth() * factor));
        int height = Math.max(1, (int) Math.round(source.getHeight() * factor));
        int type = source.getColorModel().hasAlpha()
                ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB;
        BufferedImage target = new BufferedImage(width, height, type);
        Graphics2D graphics = target.createGraphics();
        try {
            if (!source.getColorModel().hasAlpha()) {
                graphics.setColor(Color.WHITE);
                graphics.fillRect(0, 0, width, height);
            }
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING,
                    RenderingHints.VALUE_RENDER_QUALITY);
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.drawImage(source, 0, 0, width, height, null);
        } finally {
            graphics.dispose();
        }
        return target;
    }

    private static byte[] png(BufferedImage image) throws Exception {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            if (!ImageIO.write(image, "png", out)) return new byte[0];
            return out.toByteArray();
        }
    }

    private static byte[] jpeg(BufferedImage image) throws Exception {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
        if (!writers.hasNext()) throw new IllegalStateException("No JPEG ImageIO writer available");
        ImageWriter writer = writers.next();
        try (ByteArrayOutputStream out = new ByteArrayOutputStream();
             ImageOutputStream imageOut = ImageIO.createImageOutputStream(out)) {
            writer.setOutput(imageOut);
            ImageWriteParam params = writer.getDefaultWriteParam();
            if (params.canWriteCompressed()) {
                params.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                params.setCompressionQuality(JPEG_QUALITY);
            }
            writer.write(null, new IIOImage(image, null, null), params);
            imageOut.flush();
            return out.toByteArray();
        } finally {
            writer.dispose();
        }
    }
}
