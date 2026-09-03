package be.enrosed.media;

import be.enrosed.shared.BusinessRuleException;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MediaUploadPolicyTest {

    @Test
    void sniffsPdfAndNeverTreatsHtmlOrSvgAsSafeInlineMedia() {
        byte[] pdf = "%PDF-1.7\n%%EOF".getBytes(StandardCharsets.US_ASCII);
        var validated = MediaUploadPolicy.validate("factuur.pdf", "text/html",
                new ByteArrayInputStream(pdf));
        assertEquals("application/pdf", validated.contentType());
        assertEquals(MediaKind.DOCUMENT, validated.kind());
        assertTrue(MediaUploadPolicy.safeInline(validated.contentType()));

        byte[] html = "<html><script>alert(1)</script></html>".getBytes(StandardCharsets.UTF_8);
        assertThrows(BusinessRuleException.class, () -> MediaUploadPolicy.validate(
                "factuur.html", "text/html", new ByteArrayInputStream(html)));
        byte[] svg = "<svg xmlns='http://www.w3.org/2000/svg'></svg>".getBytes(StandardCharsets.UTF_8);
        assertThrows(BusinessRuleException.class, () -> MediaUploadPolicy.validate(
                "foto.svg", "image/svg+xml", new ByteArrayInputStream(svg)));
        assertFalse(MediaUploadPolicy.safeInline("text/html"));
        assertFalse(MediaUploadPolicy.safeInline("image/svg+xml"));
    }

    @Test
    void stripsPathAndHeaderCharactersFromFilename() {
        assertEquals("evilname.pdf", MediaUploadPolicy.safeFilename("../../evil\"name.pdf"));
        assertFalse(MediaUploadPolicy.contentDispositionFilename("x\r\nInjected: y.pdf")
                .contains("\n"));
    }
}
