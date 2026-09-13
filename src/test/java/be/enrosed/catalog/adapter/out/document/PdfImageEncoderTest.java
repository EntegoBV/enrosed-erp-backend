package be.enrosed.catalog.adapter.out.document;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.awt.Color;
import java.awt.Graphics2D;
import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.Base64;
import java.util.Arrays;
import java.util.zip.CRC32;

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
        assertEquals(new PdfImageEncoder.ImageSize(0, 0),
                encoder.inspect(new byte[] {1, 2, 3, 4}));
        assertEquals(new PdfImageEncoder.ImageSize(0, 0), encoder.inspect(null));
    }

    @Test
    void layoutInspectionReadsDimensionsWithoutRequiringOrAllocatingPixelData() throws Exception {
        // A valid 24-megapixel PNG header, deliberately without image-data chunks:
        // inspecting its dimensions must not attempt a 72+ MB raster decode.
        byte[] header = Arrays.copyOf(png(solid(1, 1, Color.WHITE)), 33);
        ByteBuffer.wrap(header).putInt(16, 6_000).putInt(20, 4_000);
        CRC32 crc = new CRC32();
        crc.update(header, 12, 17);
        ByteBuffer.wrap(header).putInt(29, (int) crc.getValue());

        assertEquals(new PdfImageEncoder.ImageSize(6_000, 4_000), encoder.inspect(header));
    }

    @Test
    void metadataInspectionSupportsTheRealCanonicalWebpReader() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/images/soap-roos-in-box-480.webp")) {
            byte[] source = java.util.Objects.requireNonNull(in).readAllBytes();
            BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(source));
            assertEquals(new PdfImageEncoder.ImageSize(decoded.getWidth(), decoded.getHeight()),
                    encoder.inspect(source));
        }
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

    @Test
    void coverCropRemovesNearWhiteUploadMarginsAndFillsTheRequestedAspect() throws Exception {
        BufferedImage source = solid(600, 600, Color.WHITE);
        Graphics2D graphics = source.createGraphics();
        graphics.setColor(new Color(166, 31, 62));
        graphics.fillRect(150, 120, 300, 360);
        graphics.dispose();

        BufferedImage cropped = decode(encoder.encodeCoverCropped(
                png(source), 16, 9, 1_200, new Color(255, 252, 248)));

        assertEquals(16d / 9d, (double) cropped.getWidth() / cropped.getHeight(), .01d);
        assertTrue(red(cropped.getRGB(0, 0)) > 120,
                "near-white source margins must not survive inside a full-bleed image tile");
        assertTrue(red(cropped.getRGB(cropped.getWidth() - 1, cropped.getHeight() - 1)) > 120);
    }

    @Test
    void coverCropNeverUpscalesALowResolutionSourceToAFakePrintCanvas() throws Exception {
        BufferedImage source = solid(800, 600, new Color(56, 105, 72));

        BufferedImage cropped = decode(encoder.encodeCoverCropped(
                png(source), 16, 9, 2_400, Color.WHITE));

        assertEquals(800, cropped.getWidth());
        assertEquals(450, cropped.getHeight());
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
