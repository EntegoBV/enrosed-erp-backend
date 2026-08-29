package be.enrosed.catalog.adapter.out.document;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.awt.Color;
import java.awt.Graphics2D;
import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PdfImageEncoderTest {

    private final PdfImageEncoder encoder = new PdfImageEncoder();

    @Test
    void realCanonicalWebpBecomesPdfSafeAndBounded() throws Exception {
        byte[] webp;
        try (InputStream in = getClass().getResourceAsStream(
                "/images/soap-roos-in-box-480.webp")) {
            webp = java.util.Objects.requireNonNull(in).readAllBytes();
        }

        String encoded = encoder.encode(webp, 420);

        assertNotNull(encoded);
        assertTrue(encoded.startsWith("data:image/jpeg;base64,")
                || encoded.startsWith("data:image/png;base64,"));
        byte[] bytes = Base64.getDecoder().decode(encoded.substring(encoded.indexOf(',') + 1));
        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(bytes));
        assertNotNull(decoded);
        assertTrue(Math.max(decoded.getWidth(), decoded.getHeight()) <= 420);
    }

    @Test
    void corruptOrUnknownImagesFailSoft() {
        assertNull(encoder.encode(new byte[] {1, 2, 3, 4}));
        assertNull(encoder.encodeContained(new byte[] {1, 2, 3, 4},
                400, 300, Color.WHITE));
    }

    @Test
    void fixedCanvasContainsPortraitAndLandscapeWithoutChangingTheirAspectRatio() throws Exception {
        BufferedImage portrait = solid(120, 360, new Color(166, 31, 62));
        BufferedImage landscape = solid(480, 120, new Color(56, 105, 72));

        BufferedImage encodedPortrait = decode(encoder.encodeContained(
                png(portrait), 600, 400, Color.WHITE));
        BufferedImage encodedLandscape = decode(encoder.encodeContained(
                png(landscape), 600, 400, Color.WHITE));

        assertEquals(600, encodedPortrait.getWidth());
        assertEquals(400, encodedPortrait.getHeight());
        assertEquals(600, encodedLandscape.getWidth());
        assertEquals(400, encodedLandscape.getHeight());
        assertTrue(red(encodedPortrait.getRGB(300, 200)) > 120,
                "portrait must stay centred on the fixed canvas");
        assertTrue(green(encodedLandscape.getRGB(300, 200)) > 70,
                "landscape must stay centred on the fixed canvas");
        assertTrue(red(encodedPortrait.getRGB(20, 20)) > 230,
                "portrait must be contained instead of cropped to the canvas");
        assertTrue(red(encodedLandscape.getRGB(20, 20)) > 230,
                "landscape must be contained instead of stretched vertically");
    }

    private static BufferedImage solid(int width, int height, Color colour) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(colour);
        graphics.fillRect(0, 0, width, height);
        graphics.dispose();
        return image;
    }

    private static byte[] png(BufferedImage image) throws Exception {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", out);
            return out.toByteArray();
        }
    }

    private static BufferedImage decode(String dataUri) throws Exception {
        assertNotNull(dataUri);
        byte[] bytes = Base64.getDecoder().decode(dataUri.substring(dataUri.indexOf(',') + 1));
        return ImageIO.read(new ByteArrayInputStream(bytes));
    }

    private static int red(int rgb) {
        return (rgb >> 16) & 0xff;
    }

    private static int green(int rgb) {
        return (rgb >> 8) & 0xff;
    }
}
