package be.enrosed.catalog.application;

import org.junit.jupiter.api.Test;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Iterator;
import java.util.Objects;
import java.util.Random;
import java.util.zip.CRC32;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PhotoRenditionServiceTest {

    private final PhotoRenditionService renditions = new PhotoRenditionService();

    @Test
    void resizesOpaqueJpegByWidthWithoutCroppingAndUsesFewerBytes() throws Exception {
        byte[] original = jpeg(noisyRgb(1200, 800), 0.98f);

        PhotoRenditionService.Rendition small = renditions.small(
                validated("display.webp", original));

        assertTrue(small.resized());
        assertEquals("image/jpeg", small.contentType());
        assertEquals("display-small.jpg", small.filename());
        assertEquals(480, small.width());
        assertEquals(320, small.height());
        assertTrue(small.bytes().length < original.length);
        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(small.bytes()));
        assertEquals(480, decoded.getWidth());
        assertEquals(320, decoded.getHeight());
        assertEquals(1200d / 800d, (double) decoded.getWidth() / decoded.getHeight(), 0.001);
    }

    @Test
    void widthBoundKeepsTallAlreadySmallPhotoExact() throws Exception {
        byte[] original = png(noisyArgb(480, 720));

        PhotoRenditionService.Rendition small = renditions.small(
                validated("portrait.png", original));

        assertFalse(small.resized());
        assertEquals(PhotoRenditionService.ReuseReason.ALREADY_SMALL, small.reuseReason());
        assertEquals(480, small.width());
        assertEquals(720, small.height());
        assertArrayEquals(original, small.bytes());
    }

    @Test
    void alphaSourceStaysAlphaPngAtTheSameAspectRatio() throws Exception {
        byte[] original = png(noisyArgb(1000, 700));

        PhotoRenditionService.Rendition small = renditions.small(
                validated("alpha.png", original));

        assertTrue(small.resized());
        assertEquals("image/png", small.contentType());
        assertEquals(480, small.width());
        assertEquals(336, small.height());
        assertTrue(small.bytes().length < original.length);
        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(small.bytes()));
        assertTrue(decoded.getColorModel().hasAlpha());
        boolean hasTransparency = false;
        for (int y = 0; y < decoded.getHeight() && !hasTransparency; y++) {
            for (int x = 0; x < decoded.getWidth(); x++) {
                if ((decoded.getRGB(x, y) >>> 24) != 255) {
                    hasTransparency = true;
                    break;
                }
            }
        }
        assertTrue(hasTransparency);
    }

    @Test
    void realWebpIsRecognisedAndRetainedWhenItAlreadyFits() throws Exception {
        byte[] original;
        try (var input = Objects.requireNonNull(getClass().getResourceAsStream(
                "/images/soap-roos-in-box-480.webp"))) {
            original = input.readAllBytes();
        }

        PhotoRenditionService.Rendition small = renditions.small(
                validated("soap.webp", original));

        assertFalse(small.resized());
        assertEquals("image/webp", small.contentType());
        assertEquals(480, small.width());
        assertEquals(320, small.height());
        assertArrayEquals(original, small.bytes());
    }

    @Test
    void animatedGifIsNeverCollapsedToItsFirstFrame() throws Exception {
        byte[] original = animatedGif(720, 400);

        PhotoRenditionService.Rendition small = renditions.small(
                validated("animated.gif", original));

        assertFalse(small.resized());
        assertEquals(PhotoRenditionService.ReuseReason.ANIMATED, small.reuseReason());
        assertArrayEquals(original, small.bytes());
    }

    @Test
    void nonNormalExifOrientationKeepsTheExactJpeg() throws Exception {
        byte[] original = withExifOrientation(jpeg(noisyRgb(800, 600), 0.94f), 6);
        assertEquals(6, PhotoRenditionService.jpegExifOrientation(original));

        PhotoRenditionService.Rendition small = renditions.small(
                validated("oriented.jpg", original));

        assertFalse(small.resized());
        assertEquals(PhotoRenditionService.ReuseReason.JPEG_ORIENTATION, small.reuseReason());
        assertArrayEquals(original, small.bytes());
    }

    @Test
    void generatedSmallThatDoesNotSaveBytesRetainsTheSource() throws Exception {
        BufferedImage line = new BufferedImage(481, 1, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = line.createGraphics();
        graphics.setColor(Color.BLACK);
        graphics.fillRect(0, 0, 481, 1);
        graphics.dispose();
        byte[] original = png(line);

        PhotoRenditionService.Rendition small = renditions.small(
                validated("compressed.png", original));

        assertFalse(small.resized());
        assertEquals(PhotoRenditionService.ReuseReason.NOT_SMALLER, small.reuseReason());
        assertArrayEquals(original, small.bytes());
    }

    @Test
    void malformedAndDecompressionBombInputsFailBeforePixelDecode() throws Exception {
        assertThrows(PhotoUploadPolicy.InvalidPhotoException.class,
                () -> renditions.small(validated("broken.jpg", new byte[] {
                        (byte) 0xff, (byte) 0xd8, (byte) 0xff, 0, 1, 2, 3
                })));

        PhotoUploadPolicy.InvalidPhotoException bomb = assertThrows(
                PhotoUploadPolicy.InvalidPhotoException.class,
                () -> renditions.small(validated(
                        "bomb.png", pngHeaderOnly(10_000, 5_000))));
        assertTrue(bomb.getMessage().contains("te veel pixels"));
    }

    private static PhotoUploadPolicy.ValidatedPhoto validated(String filename, byte[] bytes) {
        return PhotoUploadPolicy.validate(filename, new ByteArrayInputStream(bytes));
    }

    private static BufferedImage noisyRgb(int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Random random = new Random(0x454e524f534544L);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int red = (x * 255 / Math.max(1, width - 1) + random.nextInt(32)) & 0xff;
                int green = (y * 255 / Math.max(1, height - 1) + random.nextInt(32)) & 0xff;
                int blue = (x + y + random.nextInt(64)) & 0xff;
                image.setRGB(x, y, red << 16 | green << 8 | blue);
            }
        }
        return image;
    }

    private static BufferedImage noisyArgb(int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Random random = new Random(0x524f5345L);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int alpha = (x + y) % 7 == 0 ? 0 : 32 + random.nextInt(224);
                int colour = random.nextInt(1 << 24);
                image.setRGB(x, y, alpha << 24 | colour);
            }
        }
        return image;
    }

    private static byte[] jpeg(BufferedImage image, float quality) throws Exception {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
        ImageWriter writer = writers.next();
        try (ByteArrayOutputStream output = new ByteArrayOutputStream();
             ImageOutputStream imageOutput = ImageIO.createImageOutputStream(output)) {
            writer.setOutput(imageOutput);
            ImageWriteParam parameters = writer.getDefaultWriteParam();
            parameters.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            parameters.setCompressionQuality(quality);
            writer.write(null, new IIOImage(image, null, null), parameters);
            imageOutput.flush();
            return output.toByteArray();
        } finally {
            writer.dispose();
        }
    }

    private static byte[] png(BufferedImage image) throws Exception {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            assertTrue(ImageIO.write(image, "png", output));
            return output.toByteArray();
        }
    }

    private static byte[] animatedGif(int width, int height) throws Exception {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("gif");
        ImageWriter writer = writers.next();
        try (ByteArrayOutputStream output = new ByteArrayOutputStream();
             ImageOutputStream imageOutput = ImageIO.createImageOutputStream(output)) {
            writer.setOutput(imageOutput);
            writer.prepareWriteSequence(null);
            for (Color colour : new Color[] { Color.RED, Color.BLUE }) {
                BufferedImage frame = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
                Graphics2D graphics = frame.createGraphics();
                graphics.setColor(colour);
                graphics.fillRect(0, 0, width, height);
                graphics.dispose();
                writer.writeToSequence(new IIOImage(frame, null, null), null);
            }
            writer.endWriteSequence();
            imageOutput.flush();
            return output.toByteArray();
        } finally {
            writer.dispose();
        }
    }

    private static byte[] withExifOrientation(byte[] jpeg, int orientation) {
        ByteBuffer payload = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN);
        payload.put(new byte[] {'E', 'x', 'i', 'f', 0, 0});
        payload.put((byte) 'I').put((byte) 'I').putShort((short) 42).putInt(8);
        payload.putShort((short) 1);
        payload.putShort((short) 0x0112).putShort((short) 3).putInt(1);
        payload.putShort((short) orientation).putShort((short) 0);
        payload.putInt(0);
        byte[] app1 = payload.array();
        ByteArrayOutputStream output = new ByteArrayOutputStream(jpeg.length + app1.length + 4);
        output.writeBytes(Arrays.copyOfRange(jpeg, 0, 2));
        output.write(0xff);
        output.write(0xe1);
        int length = app1.length + 2;
        output.write(length >>> 8);
        output.write(length);
        output.writeBytes(app1);
        output.writeBytes(Arrays.copyOfRange(jpeg, 2, jpeg.length));
        return output.toByteArray();
    }

    private static byte[] pngHeaderOnly(int width, int height) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.writeBytes(new byte[] {
                (byte) 0x89, 'P', 'N', 'G', 0x0d, 0x0a, 0x1a, 0x0a
        });
        ByteBuffer ihdr = ByteBuffer.allocate(13).order(ByteOrder.BIG_ENDIAN);
        ihdr.putInt(width).putInt(height).put((byte) 8).put((byte) 2)
                .put((byte) 0).put((byte) 0).put((byte) 0);
        chunk(output, "IHDR", ihdr.array());
        chunk(output, "IEND", new byte[0]);
        return output.toByteArray();
    }

    private static void chunk(ByteArrayOutputStream output, String type, byte[] data)
            throws Exception {
        output.write(ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN)
                .putInt(data.length).array());
        byte[] name = type.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        output.write(name);
        output.write(data);
        CRC32 crc = new CRC32();
        crc.update(name);
        crc.update(data);
        output.write(ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN)
                .putInt((int) crc.getValue()).array());
    }
}
