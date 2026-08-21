package be.enrosed.catalog.adapter.out.document;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertNotNull;
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
    }
}
