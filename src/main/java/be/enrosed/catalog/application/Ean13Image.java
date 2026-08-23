package be.enrosed.catalog.application;

import be.enrosed.shared.BusinessRuleException;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriter;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * Draws an EAN-13 as a print-ready PNG.
 *
 * No library: the symbology is three pattern tables and a parity map, and
 * a dependency for that is one more thing to keep up to date. The image
 * carries its resolution, so a label printer or a designer's tool reads
 * the real size instead of guessing.
 */
public final class Ean13Image {

    private Ean13Image() {}

    /* Left-hand patterns: L (odd parity) and G (even parity); right-hand R. */
    private static final String[] L = {
        "0001101", "0011001", "0010011", "0111101", "0100011",
        "0110001", "0101111", "0111011", "0110111", "0001011" };
    private static final String[] G = {
        "0100111", "0110011", "0011011", "0100001", "0011101",
        "0111001", "0000101", "0010001", "0001001", "0010111" };
    private static final String[] R = {
        "1110010", "1100110", "1101100", "1000010", "1011100",
        "1001110", "1010000", "1000100", "1001000", "1110100" };
    /* Which of the six left digits use G, by the leading digit. */
    private static final String[] PARITY = {
        "LLLLLL", "LLGLGG", "LLGGLG", "LLGGGL", "LGLLGG",
        "LGGLLG", "LGGGLL", "LGLGLG", "LGLGGL", "LGGLGL" };

    /** The 95 modules of the symbol as a string of 0/1. */
    static String modules(String ean13) {
        if (ean13 == null || !ean13.matches("\\d{13}")) {
            throw new BusinessRuleException("Een EAN-13 heeft 13 cijfers");
        }
        int first = ean13.charAt(0) - '0';
        StringBuilder bits = new StringBuilder("101");
        String parity = PARITY[first];
        for (int i = 1; i <= 6; i++) {
            int digit = ean13.charAt(i) - '0';
            bits.append(parity.charAt(i - 1) == 'L' ? L[digit] : G[digit]);
        }
        bits.append("01010");
        for (int i = 7; i <= 12; i++) {
            bits.append(R[ean13.charAt(i) - '0']);
        }
        bits.append("101");
        return bits.toString();
    }

    /**
     * Renders at the given resolution. The nominal module is 0.33 mm; at 300
     * dpi that rounds to 4 px, so the symbol comes out at true size.
     */
    public static byte[] png(String ean13, int dpi) {
        String bits = modules(ean13);
        double pxPerMm = dpi / 25.4;
        int module = Math.max(1, (int) Math.round(0.33 * pxPerMm));
        int quietLeft = 11 * module;
        int quietRight = 7 * module;
        int barHeight = (int) Math.round(22.85 * pxPerMm);
        int guardExtra = (int) Math.round(1.65 * pxPerMm);
        int textHeight = (int) Math.round(3.0 * pxPerMm);
        int width = quietLeft + bits.length() * module + quietRight;
        int height = barHeight + guardExtra + textHeight + module * 2;

        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D g = image.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, width, height);
        g.setColor(Color.BLACK);
        for (int i = 0; i < bits.length(); i++) {
            if (bits.charAt(i) != '1') continue;
            /* Guard bars (start, middle, end) reach down between the digits. */
            boolean guard = i < 3 || (i >= 45 && i < 50) || i >= 92;
            g.fillRect(quietLeft + i * module, 0, module, barHeight + (guard ? guardExtra : 0));
        }
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, textHeight));
        int baseline = barHeight + guardExtra + textHeight - module;
        drawCentered(g, ean13.substring(0, 1), 0, quietLeft - module, baseline);
        drawCentered(g, ean13.substring(1, 7), quietLeft + 3 * module, quietLeft + 45 * module, baseline);
        drawCentered(g, ean13.substring(7), quietLeft + 50 * module, quietLeft + 92 * module, baseline);
        g.dispose();

        try {
            return write(image, dpi);
        } catch (IOException e) {
            throw new IllegalStateException("Barcode kon niet getekend worden", e);
        }
    }

    private static void drawCentered(Graphics2D g, String text, int from, int to, int baseline) {
        int textWidth = g.getFontMetrics().stringWidth(text);
        g.drawString(text, from + (to - from - textWidth) / 2, baseline);
    }

    /** PNG with a pHYs chunk, so the file says "300 dpi" instead of 72. */
    private static byte[] write(BufferedImage image, int dpi) throws IOException {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("png").next();
        IIOMetadata metadata = writer.getDefaultImageMetadata(
                ImageTypeSpecifier.createFromRenderedImage(image), writer.getDefaultWriteParam());
        long pixelsPerMeter = Math.round(dpi / 0.0254);
        IIOMetadataNode phys = new IIOMetadataNode("pHYs");
        phys.setAttribute("pixelsPerUnitXAxis", Long.toString(pixelsPerMeter));
        phys.setAttribute("pixelsPerUnitYAxis", Long.toString(pixelsPerMeter));
        phys.setAttribute("unitSpecifier", "meter");
        IIOMetadataNode root = new IIOMetadataNode("javax_imageio_png_1.0");
        root.appendChild(phys);
        metadata.mergeTree("javax_imageio_png_1.0", root);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ImageOutputStream stream = ImageIO.createImageOutputStream(out)) {
            writer.setOutput(stream);
            writer.write(null, new IIOImage(image, null, metadata), writer.getDefaultWriteParam());
        } finally {
            writer.dispose();
        }
        return out.toByteArray();
    }
}
