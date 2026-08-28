package be.enrosed.sales.adapter.in.rest;

import be.enrosed.contact.ContactDtos;
import be.enrosed.contact.PublicContactResource;
import be.enrosed.publicform.PublicFormBodyLimited;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.Priority;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.container.ResourceInfo;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.ext.Provider;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Map;

/** Keeps photo-upload's global 64 MB allowance away from anonymous JSON endpoints. */
@Provider
@PublicFormBodyLimited
@Priority(Priorities.AUTHENTICATION - 100)
public class PublicQuoteBodyLimitFilter implements ContainerRequestFilter {
    static final long MAX_PUBLIC_QUOTE_BODY_BYTES = 64 * 1024;
    static final long MAX_PUBLIC_CONTACT_BODY_BYTES = 16 * 1024;
    /* Public quote DTOs contain only strings, numbers, booleans and lists. A
       small isolated mapper can therefore validate the same structural shape
       before RESTEasy consumes the stream. That keeps mapping failures (not
       only broken JSON punctuation) inside the public error contract. */
    private static final ObjectMapper JSON = new ObjectMapper()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    @Context
    ResourceInfo resourceInfo;

    @Override
    public void filter(ContainerRequestContext request) {
        if (!"POST".equalsIgnoreCase(request.getMethod())) return;
        Class<?> resourceClass = resourceInfo == null ? null : resourceInfo.getResourceClass();
        boolean contact = resourceClass != null
                && PublicContactResource.class.isAssignableFrom(resourceClass);
        boolean quote = resourceClass != null
                && PublicQuoteResource.class.isAssignableFrom(resourceClass);
        if (!quote && !contact) return;
        long maximum = contact ? MAX_PUBLIC_CONTACT_BODY_BYTES : MAX_PUBLIC_QUOTE_BODY_BYTES;
        int length = request.getLength();
        if (length > maximum) {
            abortTooLarge(request);
            return;
        }

        /*
         * Content-Length is legitimately absent for chunked HTTP requests. Read at most
         * one byte beyond the small public limit and restore the stream for Jackson, so
         * neither a browser nor a direct client can bypass the limit by omitting it.
         */
        try {
            byte[] body = request.getEntityStream()
                    .readNBytes((int) maximum + 1);
            if (body.length > maximum) {
                abortTooLarge(request);
                return;
            }
            boolean preview = resourceInfo.getResourceMethod() != null
                    && resourceInfo.getResourceMethod().getName().equals("preview");
            Class<?> requestType = contact ? ContactDtos.Request.class
                    : preview ? PublicQuoteDtos.PreviewRequest.class
                    : PublicQuoteDtos.SubmitRequest.class;
            if (JSON.readValue(body, requestType) == null) {
                abortInvalidJson(request);
                return;
            }
            request.setEntityStream(new ByteArrayInputStream(body));
        } catch (IOException exception) {
            abortInvalidJson(request);
        }
    }

    private static void abortInvalidJson(ContainerRequestContext request) {
        request.abortWith(Response.status(Response.Status.BAD_REQUEST)
                .header("Cache-Control", "no-store")
                .entity(new PublicQuoteDtos.ErrorResponse(
                        "INVALID_REQUEST", "Request is not valid JSON", Map.of()))
                .type(jakarta.ws.rs.core.MediaType.APPLICATION_JSON_TYPE)
                .build());
    }

    private static void abortTooLarge(ContainerRequestContext request) {
        request.abortWith(Response.status(413)
                .header("Cache-Control", "no-store")
                .entity(new PublicQuoteDtos.ErrorResponse(
                        "PAYLOAD_TOO_LARGE", "Request is too large", Map.of()))
                .type(jakarta.ws.rs.core.MediaType.APPLICATION_JSON_TYPE)
                .build());
    }
}
