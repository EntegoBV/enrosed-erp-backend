package be.enrosed.catalog.application;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PhotoExportNamesTest {

    @Test
    void folderJoinsSkuNameColourAndOptionalSize() {
        assertEquals("ENR-101 - Zeeproos in doos - Rood - Groot",
                PhotoExportNames.folder("ENR-101", "Zeeproos in doos", "Rood", "Groot"));
        assertEquals("ENR-101 - Zeeproos in doos - Rood",
                PhotoExportNames.folder("ENR-101", " Zeeproos in doos ", "Rood", "  "));
        assertEquals("ENR-102 - Zeeproos", PhotoExportNames.folder("ENR-102", "Zeeproos", null, null));
    }

    @Test
    void charactersWindowsOrMacosRejectAreReplaced() {
        String safe = PhotoExportNames.sanitize("A/B\\C:D*E?F\"G<H>I|J\tK\u0000L");
        assertEquals("A-B-C-D-E-F-G-H-I-J K L", safe);
        for (char forbidden : "/\\:*?\"<>|".toCharArray()) {
            assertFalse(safe.indexOf(forbidden) >= 0, "still contains " + forbidden);
        }
    }

    @Test
    void trailingDotsSpacesAndLeadingDotsAreTrimmedAndBlankBecomesProduct() {
        assertEquals("Roos", PhotoExportNames.sanitize("  ..Roos. . "));
        assertEquals("product", PhotoExportNames.sanitize(" ... "));
        assertEquals("product", PhotoExportNames.sanitize(null));
        assertEquals("Twee spaties", PhotoExportNames.sanitize("Twee \n\n  spaties"));
    }

    @Test
    void reservedWindowsDeviceNamesAreEscaped() {
        assertEquals("_CON", PhotoExportNames.sanitize("CON"));
        assertEquals("_nul.txt", PhotoExportNames.sanitize("nul.txt"));
        assertEquals("_COM1", PhotoExportNames.sanitize("COM1"));
        assertEquals("CONSOLE", PhotoExportNames.sanitize("CONSOLE"), "only exact device names are reserved");
    }

    @Test
    void longNamesAreCutAtTheLimitWithoutSplittingACharacter() {
        String longName = "ENR-1 - " + "Rozen🌹".repeat(40);
        String safe = PhotoExportNames.sanitize(longName);
        assertEquals(PhotoExportNames.MAX_FOLDER_LENGTH, safe.codePointCount(0, safe.length()));
        assertFalse(Character.isHighSurrogate(safe.charAt(safe.length() - 1)));
        assertTrue(PhotoExportNames.sanitize("Geëxporteerd – é".repeat(20)).length() <= 120);
    }

    @Test
    void uniqueNamesIgnoreCaseAndStayWithinTheLimit() {
        PhotoExportNames.Unique names = new PhotoExportNames.Unique();
        assertEquals("ENR-1 - Roos", names.claim("ENR-1 - Roos"));
        assertEquals("enr-1 - roos (2)", names.claim("enr-1 - roos"));
        assertEquals("ENR-1 - Roos (3)", names.claim("ENR-1 - Roos"));
        assertEquals("ENR-2", names.claim("ENR-2"));

        String max = "X".repeat(PhotoExportNames.MAX_FOLDER_LENGTH);
        assertEquals(max, names.claim(max));
        String second = names.claim(max);
        assertEquals(PhotoExportNames.MAX_FOLDER_LENGTH, second.length());
        assertTrue(second.endsWith(" (2)"));
    }

    @Test
    void filesAreNumberedWithTheLeadMarked() {
        assertEquals("01-hoofdfoto.jpg", PhotoExportNames.file(1, 3, true, "jpg"));
        assertEquals("02.png", PhotoExportNames.file(2, 3, false, "png"));
        assertEquals("007.webp", PhotoExportNames.file(7, 120, false, "webp"));
    }

    @Test
    void extensionFollowsTheStoredContentType() {
        assertEquals("jpg", PhotoExportNames.extension("image/jpeg", "foto.png"));
        assertEquals("png", PhotoExportNames.extension("IMAGE/PNG", null));
        assertEquals("webp", PhotoExportNames.extension("image/webp; charset=binary", "x"));
        assertEquals("heic", PhotoExportNames.extension("application/octet-stream", "IMG_1.HEIC"));
        assertEquals("bin", PhotoExportNames.extension(null, "zonder-extensie"));
        assertEquals("bin", PhotoExportNames.extension(null, "rare.ex/tensie!"));
    }
}
