package be.enrosed.catalog.adapter.in.rest;

import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class PhotoResponsesTest {

    @Test
    void unsafeLegacyMimeCannotBeServedAsExecutableContent() {
        try (Response response = PhotoResponses.inline(
                new ByteArrayInputStream(new byte[] {1}), "text/html").build()) {
            assertEquals(MediaType.APPLICATION_OCTET_STREAM, response.getMediaType().toString());
            assertEquals("nosniff", response.getHeaderString("X-Content-Type-Options"));
        }
    }

    @Test
    void dispositionHasSafeFallbackAndUtf8Filename() {
        String disposition = PhotoResponses.contentDisposition(
                "attachment", "roos \"été\"\r\n.jpg");

        assertFalse(disposition.contains("\r"));
        assertFalse(disposition.contains("\n"));
        assertEquals(
                "attachment; filename=\"roos__t_.jpg\"; filename*=UTF-8''roos%20%C3%A9t%C3%A9.jpg",
                disposition);
    }
}
