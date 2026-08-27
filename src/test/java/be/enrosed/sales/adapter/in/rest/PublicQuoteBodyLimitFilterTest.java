package be.enrosed.sales.adapter.in.rest;

import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.*;

class PublicQuoteBodyLimitFilterTest {

    @Test
    void chunkedBodyCannotBypassTheAnonymousLimit() {
        ContainerRequestContext request = request(-1, "x".repeat(
                (int) PublicQuoteBodyLimitFilter.MAX_PUBLIC_QUOTE_BODY_BYTES + 1));

        new PublicQuoteBodyLimitFilter().filter(request);

        ArgumentCaptor<Response> aborted = ArgumentCaptor.forClass(Response.class);
        verify(request).abortWith(aborted.capture());
        assertEquals(413, aborted.getValue().getStatus());
        assertEquals("no-store", aborted.getValue().getHeaderString("Cache-Control"));
    }

    @Test
    void chunkedBodyWithinTheLimitIsRestoredForJsonDeserialization() throws Exception {
        ContainerRequestContext request = request(-1, "{\"items\":[]}");
        ArgumentCaptor<java.io.InputStream> restored =
                ArgumentCaptor.forClass(java.io.InputStream.class);

        new PublicQuoteBodyLimitFilter().filter(request);

        verify(request, never()).abortWith(any());
        verify(request).setEntityStream(restored.capture());
        assertNotNull(restored.getValue());
        assertEquals("{\"items\":[]}",
                new String(restored.getValue().readAllBytes(), StandardCharsets.UTF_8));
    }

    @Test
    void malformedOrNonObjectJsonHasAnActionableNonCacheableResponse() {
        for (String body : new String[]{"{", "[]", "   ", "{}{}",
                "{\"items\":\"not-a-list\"}"}) {
            ContainerRequestContext request = request(-1, body);

            new PublicQuoteBodyLimitFilter().filter(request);

            ArgumentCaptor<Response> aborted = ArgumentCaptor.forClass(Response.class);
            verify(request).abortWith(aborted.capture());
            Response response = aborted.getValue();
            assertEquals(400, response.getStatus());
            assertEquals("no-store", response.getHeaderString("Cache-Control"));
            assertEquals("INVALID_REQUEST",
                    ((PublicQuoteDtos.ErrorResponse) response.getEntity()).code());
        }
    }

    private static ContainerRequestContext request(int length, String body) {
        ContainerRequestContext request = mock(ContainerRequestContext.class);
        UriInfo uri = mock(UriInfo.class);
        when(request.getMethod()).thenReturn("POST");
        when(request.getLength()).thenReturn(length);
        when(request.getUriInfo()).thenReturn(uri);
        when(uri.getPath()).thenReturn("api/v1/public/quotes/requests");
        when(request.getEntityStream()).thenReturn(new ByteArrayInputStream(
                body.getBytes(StandardCharsets.UTF_8)));
        return request;
    }
}
