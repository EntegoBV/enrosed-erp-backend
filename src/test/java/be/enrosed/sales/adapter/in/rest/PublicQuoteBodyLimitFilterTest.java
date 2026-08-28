package be.enrosed.sales.adapter.in.rest;

import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ResourceInfo;
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
        ContainerRequestContext request = request("api/v1/public/quotes/requests", -1, "x".repeat(
                (int) PublicQuoteBodyLimitFilter.MAX_PUBLIC_QUOTE_BODY_BYTES + 1));

        filter("api/v1/public/quotes/requests").filter(request);

        ArgumentCaptor<Response> aborted = ArgumentCaptor.forClass(Response.class);
        verify(request).abortWith(aborted.capture());
        assertEquals(413, aborted.getValue().getStatus());
        assertEquals("no-store", aborted.getValue().getHeaderString("Cache-Control"));
    }

    @Test
    void chunkedBodyWithinTheLimitIsRestoredForJsonDeserialization() throws Exception {
        ContainerRequestContext request = request(
                "api/v1/public/quotes/requests", -1, "{\"items\":[]}");
        ArgumentCaptor<java.io.InputStream> restored =
                ArgumentCaptor.forClass(java.io.InputStream.class);

        filter("api/v1/public/quotes/requests").filter(request);

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
            ContainerRequestContext request = request(
                    "api/v1/public/quotes/requests", -1, body);

            filter("api/v1/public/quotes/requests").filter(request);

            ArgumentCaptor<Response> aborted = ArgumentCaptor.forClass(Response.class);
            verify(request).abortWith(aborted.capture());
            Response response = aborted.getValue();
            assertEquals(400, response.getStatus());
            assertEquals("no-store", response.getHeaderString("Cache-Control"));
            assertEquals("INVALID_REQUEST",
                    ((PublicQuoteDtos.ErrorResponse) response.getEntity()).code());
        }
    }

    @Test
    void contactBodyHasASeparate16KbLimit() {
        ContainerRequestContext request = request("api/v1/public/contact/requests", -1,
                "x".repeat((int) PublicQuoteBodyLimitFilter.MAX_PUBLIC_CONTACT_BODY_BYTES + 1));

        filter("api/v1/public/contact/requests").filter(request);

        ArgumentCaptor<Response> aborted = ArgumentCaptor.forClass(Response.class);
        verify(request).abortWith(aborted.capture());
        assertEquals(413, aborted.getValue().getStatus());
    }

    @Test
    void matchedContactResourceKeepsLimitForTrailingSlashAndMatrixPathVariants() {
        for (String path : new String[]{"api/v1/public/contact/requests/",
                "api/v1/public/contact/requests;v=1"}) {
            ContainerRequestContext request = request(path, -1,
                    "x".repeat((int) PublicQuoteBodyLimitFilter.MAX_PUBLIC_CONTACT_BODY_BYTES + 1));

            filter(path).filter(request);

            verify(request).abortWith(argThat(response -> response.getStatus() == 413));
        }
    }

    private static PublicQuoteBodyLimitFilter filter(String path) {
        PublicQuoteBodyLimitFilter filter = new PublicQuoteBodyLimitFilter();
        ResourceInfo info = mock(ResourceInfo.class);
        try {
            if (path.contains("/contact/")) {
                doReturn(be.enrosed.contact.PublicContactResource.class).when(info).getResourceClass();
                when(info.getResourceMethod()).thenReturn(
                        be.enrosed.contact.PublicContactResource.class.getMethod("submit",
                                be.enrosed.contact.ContactDtos.Request.class, String.class));
            } else {
                doReturn(PublicQuoteResource.class).when(info).getResourceClass();
                when(info.getResourceMethod()).thenReturn(PublicQuoteResource.class.getMethod(
                        path.endsWith("/preview") ? "preview" : "submit",
                        path.endsWith("/preview")
                                ? new Class<?>[]{PublicQuoteDtos.PreviewRequest.class}
                                : new Class<?>[]{PublicQuoteDtos.SubmitRequest.class, String.class}));
            }
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
        filter.resourceInfo = info;
        return filter;
    }

    private static ContainerRequestContext request(String path, int length, String body) {
        ContainerRequestContext request = mock(ContainerRequestContext.class);
        UriInfo uri = mock(UriInfo.class);
        when(request.getMethod()).thenReturn("POST");
        when(request.getLength()).thenReturn(length);
        when(request.getUriInfo()).thenReturn(uri);
        when(uri.getPath()).thenReturn(path);
        when(request.getEntityStream()).thenReturn(new ByteArrayInputStream(
                body.getBytes(StandardCharsets.UTF_8)));
        return request;
    }
}
