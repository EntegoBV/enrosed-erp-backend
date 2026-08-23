package be.enrosed.catalog.application;

import be.enrosed.shared.BusinessRuleException;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Ean13ImageTest {

    /** The textbook example: 5901234123457, with its well-known module string. */
    @Test
    void encodesTheReferenceCode() {
        String bits = Ean13Image.modules("5901234123457");
        assertEquals(95, bits.length());
        assertTrue(bits.startsWith("101"));
        assertTrue(bits.endsWith("101"));
        assertEquals("01010", bits.substring(45, 50), "centre guard");
        /* Leading 5 -> parity LGGLLG: digit 9 as L, 0 as G, 1 as G, 2 as L, 3 as L, 4 as G. */
        assertEquals("0001011", bits.substring(3, 10), "9 in L");
        assertEquals("0100111", bits.substring(10, 17), "0 in G");
        assertEquals("0110011", bits.substring(17, 24), "1 in G");
        assertEquals("0010011", bits.substring(24, 31), "2 in L");
        assertEquals("1100110", bits.substring(50, 57), "right-hand 1 is R");
    }

    @Test
    void rendersAPngAtTheAskedResolution() throws Exception {
        byte[] png = Ean13Image.png("5901234123457", 300);
        assertEquals("PNG", new String(png, 1, 3, StandardCharsets.ISO_8859_1));
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(png));
        /* 113 modules of 4 px at 300 dpi. */
        assertEquals(452, image.getWidth());
        assertTrue(image.getHeight() > 300, "bars plus digits");
        /* The pHYs chunk is in the bytes: 300 dpi = 11811 px/m = 0x00002E23, both axes. */
        String hex = java.util.HexFormat.of().formatHex(png);
        assertTrue(hex.contains("7048597300002e2300002e23"), "pHYs chunk carries 11811 px/m both ways");
    }

    @Test
    void refusesAnythingThatIsNotThirteenDigits() {
        assertThrows(BusinessRuleException.class, () -> Ean13Image.modules("123"));
        assertThrows(BusinessRuleException.class, () -> Ean13Image.modules("15410000000016"));
    }
}
