package be.enrosed.sales.adapter.in.rest;

import be.enrosed.publicform.PublicFormBodyLimited;
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

    @Test
    void declaredCapOfSixteenKibHoldsForTrailingSlashAndMatrixPathVariants() throws Exception {
        for (String path : new String[]{"api/v1/public/account/requests",
                "api/v1/public/account/requests/", "api/v1/public/account/requests;v=1"}) {
            ContainerRequestContext tooLarge = request(path, -1, "x".repeat(16 * 1024 + 1));
            declaredFilter(DeclaredCaps.class, "form").filter(tooLarge);
            verify(tooLarge).abortWith(argThat(response -> response.getStatus() == 413));

            ContainerRequestContext announced = request(path, 16 * 1024 + 1, "{}");
            declaredFilter(DeclaredCaps.class, "form").filter(announced);
            verify(announced).abortWith(argThat(response -> response.getStatus() == 413));
            verify(announced, never()).getEntityStream();

            String exact = "{\"email\":\"" + "a".repeat(16 * 1024 - 12) + "\"}";
            assertEquals(16 * 1024, exact.length());
            ContainerRequestContext fits = request(path, -1, exact);
            declaredFilter(DeclaredCaps.class, "form").filter(fits);
            verify(fits, never()).abortWith(any());
            assertEquals(exact, restored(fits));
        }
    }

    @Test
    void declaredBodyClassIsPreParsedLikeTheLegacyBranches() {
        for (String body : new String[]{"", "{", "[]", "{}{}", "{\"email\":[]}"}) {
            ContainerRequestContext request = request("api/v1/public/account/requests", -1, body);

            declaredFilter(DeclaredCaps.class, "form").filter(request);

            ArgumentCaptor<Response> aborted = ArgumentCaptor.forClass(Response.class);
            verify(request).abortWith(aborted.capture());
            assertEquals(400, aborted.getValue().getStatus());
            assertEquals("no-store", aborted.getValue().getHeaderString("Cache-Control"));
            assertEquals("INVALID_REQUEST",
                    ((PublicQuoteDtos.ErrorResponse) aborted.getValue().getEntity()).code());
        }
    }

    @Test
    void capOnlyLetsAnEmptyBodyAndAnObjectThroughAndStillRefusesOneByteTooMany() throws Exception {
        ContainerRequestContext empty = request("api/v1/public/account/session/logout", -1, "");
        declaredFilter(DeclaredCaps.class, "capOnly").filter(empty);
        verify(empty, never()).abortWith(any());
        assertEquals("", restored(empty));

        ContainerRequestContext object = request("api/v1/public/account/session/logout", -1, "{}");
        declaredFilter(DeclaredCaps.class, "capOnly").filter(object);
        verify(object, never()).abortWith(any());
        assertEquals("{}", restored(object), "the resource can still read what was sent");

        ContainerRequestContext notJson = request(
                "api/v1/public/account/session/logout", -1, "x".repeat(1024));
        declaredFilter(DeclaredCaps.class, "capOnly").filter(notJson);
        verify(notJson, never()).abortWith(any());

        ContainerRequestContext oneTooMany = request(
                "api/v1/public/account/session/logout", -1, "x".repeat(1025));
        declaredFilter(DeclaredCaps.class, "capOnly").filter(oneTooMany);
        ArgumentCaptor<Response> aborted = ArgumentCaptor.forClass(Response.class);
        verify(oneTooMany).abortWith(aborted.capture());
        assertEquals(413, aborted.getValue().getStatus());
        assertEquals("PAYLOAD_TOO_LARGE",
                ((PublicQuoteDtos.ErrorResponse) aborted.getValue().getEntity()).code());
    }

    @Test
    void methodAnnotationWinsOverTheClassAndAClassCapCoversUnannotatedMethods() {
        ContainerRequestContext methodCap = request("api/v1/public/account/x", -1, "x".repeat(1025));
        declaredFilter(ClassCap.class, "ownCap").filter(methodCap);
        verify(methodCap).abortWith(argThat(response -> response.getStatus() == 413));

        ContainerRequestContext classCap = request("api/v1/public/account/x", -1, "x".repeat(1025));
        declaredFilter(ClassCap.class, "inherits").filter(classCap);
        verify(classCap, never()).abortWith(any());

        ContainerRequestContext overClassCap = request("api/v1/public/account/x", -1, "x".repeat(2049));
        declaredFilter(ClassCap.class, "inherits").filter(overClassCap);
        verify(overClassCap).abortWith(argThat(response -> response.getStatus() == 413));
    }

    @Test
    void annotationWithoutACapLeavesOtherResourcesAlone() {
        ContainerRequestContext request = request("api/v1/public/other", -1, "x".repeat(200_000));

        declaredFilter(NoCap.class, "post").filter(request);

        verify(request, never()).abortWith(any());
        verify(request, never()).getEntityStream();
    }

    record AccountForm(String email) {}

    static class DeclaredCaps {
        @PublicFormBodyLimited(maxBytes = 16 * 1024, body = AccountForm.class)
        public void form() {}

        @PublicFormBodyLimited(maxBytes = 1024)
        public void capOnly() {}
    }

    @PublicFormBodyLimited(maxBytes = 2048)
    static class ClassCap {
        @PublicFormBodyLimited(maxBytes = 1024)
        public void ownCap() {}

        public void inherits() {}
    }

    @PublicFormBodyLimited
    static class NoCap {
        public void post() {}
    }

    private static PublicQuoteBodyLimitFilter declaredFilter(Class<?> resource, String method) {
        PublicQuoteBodyLimitFilter filter = new PublicQuoteBodyLimitFilter();
        ResourceInfo info = mock(ResourceInfo.class);
        try {
            doReturn(resource).when(info).getResourceClass();
            when(info.getResourceMethod()).thenReturn(resource.getMethod(method));
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
        filter.resourceInfo = info;
        return filter;
    }

    private static String restored(ContainerRequestContext request) throws java.io.IOException {
        ArgumentCaptor<java.io.InputStream> restored =
                ArgumentCaptor.forClass(java.io.InputStream.class);
        verify(request).setEntityStream(restored.capture());
        return new String(restored.getValue().readAllBytes(), StandardCharsets.UTF_8);
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
