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

    private static final int MAX_PRINT_EDGE = 2_400;
    private static final float JPEG_QUALITY = 0.92f;

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

    /**
     * Places an image on a predictable print canvas without cropping it. Keeping the canvas
     * dimensions stable prevents mixed portrait and landscape uploads from changing the PDF
     * card geometry.
     */
    String encodeContained(byte[] source, int canvasWidth, int canvasHeight, Color background) {
        if (source == null || source.length == 0 || canvasWidth < 1 || canvasHeight < 1) return null;
        try {
            BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(source));
            if (decoded == null) return null;
            BufferedImage canvas = contain(decoded, canvasWidth, canvasHeight,
                    background == null ? Color.WHITE : background);
            return "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(jpeg(canvas));
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * Produces a full-bleed print rendition from the actual source image. Near-white upload
     * margins are removed before the requested aspect ratio is cropped. Unlike a fixed print
     * canvas, this method never enlarges the source pixels merely to claim a higher resolution;
     * the PDF renderer may scale the resulting image visually without bloating the document.
     */
    String encodeCoverCropped(
            byte[] source, int aspectWidth, int aspectHeight, int maxEdge, Color background) {
        if (source == null || source.length == 0 || aspectWidth < 1 || aspectHeight < 1) return null;
        try {
            BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(source));
            if (decoded == null) return null;
            BufferedImage trimmed = trimNearWhite(decoded);
            BufferedImage cropped = cropToAspect(trimmed, aspectWidth, aspectHeight);
            BufferedImage scaled = scale(cropped,
                    Math.max(320, Math.min(MAX_PRINT_EDGE, maxEdge)));
            BufferedImage printable = flatten(scaled,
                    background == null ? Color.WHITE : background);
            return "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(jpeg(printable));
        } catch (Exception ignored) {
            return null;
        }
    }

    ImageSize inspect(byte[] source) {
        if (source == null || source.length == 0) return ImageSize.EMPTY;
        try {
            BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(source));
            return decoded == null ? ImageSize.EMPTY
                    : new ImageSize(decoded.getWidth(), decoded.getHeight());
        } catch (Exception ignored) {
            return ImageSize.EMPTY;
        }
    }

    record ImageSize(int width, int height) {
        private static final ImageSize EMPTY = new ImageSize(0, 0);

        long pixels() {
            return (long) width * height;
        }

        double aspect() {
            return height <= 0 ? 0d : (double) width / height;
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

    private static BufferedImage contain(
            BufferedImage source, int canvasWidth, int canvasHeight, Color background) {
        BufferedImage canvas = new BufferedImage(canvasWidth, canvasHeight, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = canvas.createGraphics();
        try {
            graphics.setColor(background);
            graphics.fillRect(0, 0, canvasWidth, canvasHeight);
            double factor = Math.min((double) canvasWidth / source.getWidth(),
                    (double) canvasHeight / source.getHeight());
            int width = Math.max(1, (int) Math.round(source.getWidth() * factor));
            int height = Math.max(1, (int) Math.round(source.getHeight() * factor));
            int x = (canvasWidth - width) / 2;
            int y = (canvasHeight - height) / 2;
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING,
                    RenderingHints.VALUE_RENDER_QUALITY);
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.drawImage(source, x, y, width, height, null);
        } finally {
            graphics.dispose();
        }
        return canvas;
    }

    private static BufferedImage trimNearWhite(BufferedImage source) {
        int minX = source.getWidth();
        int minY = source.getHeight();
        int maxX = -1;
        int maxY = -1;
        for (int y = 0; y < source.getHeight(); y++) {
            for (int x = 0; x < source.getWidth(); x++) {
                if (nearWhiteOrTransparent(source.getRGB(x, y))) continue;
                minX = Math.min(minX, x);
                minY = Math.min(minY, y);
                maxX = Math.max(maxX, x);
                maxY = Math.max(maxY, y);
            }
        }
        if (maxX < minX || maxY < minY) return source;

        int x = minX;
        int y = minY;
        int right = maxX + 1;
        int bottom = maxY + 1;
        if (x == 0 && y == 0 && right == source.getWidth() && bottom == source.getHeight()) {
            return source;
        }
        return source.getSubimage(x, y, right - x, bottom - y);
    }

    private static boolean nearWhiteOrTransparent(int argb) {
        int alpha = (argb >>> 24) & 0xff;
        if (alpha < 24) return true;
        int red = (argb >>> 16) & 0xff;
        int green = (argb >>> 8) & 0xff;
        int blue = argb & 0xff;
        int darkest = Math.min(red, Math.min(green, blue));
        int lightest = Math.max(red, Math.max(green, blue));
        return darkest >= 238 && lightest - darkest <= 18;
    }

    private static BufferedImage cropToAspect(
            BufferedImage source, int aspectWidth, int aspectHeight) {
        double requested = (double) aspectWidth / aspectHeight;
        double current = (double) source.getWidth() / source.getHeight();
        int width = source.getWidth();
        int height = source.getHeight();
        int x = 0;
        int y = 0;
        if (current > requested) {
            width = Math.max(1, (int) Math.round(height * requested));
            x = Math.max(0, (source.getWidth() - width) / 2);
        } else if (current < requested) {
            height = Math.max(1, (int) Math.round(width / requested));
            y = Math.max(0, (source.getHeight() - height) / 2);
        }
        return source.getSubimage(x, y, width, height);
    }

    private static BufferedImage flatten(BufferedImage source, Color background) {
        BufferedImage target = new BufferedImage(
                source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = target.createGraphics();
        try {
            graphics.setColor(background);
            graphics.fillRect(0, 0, target.getWidth(), target.getHeight());
            graphics.drawImage(source, 0, 0, null);
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
