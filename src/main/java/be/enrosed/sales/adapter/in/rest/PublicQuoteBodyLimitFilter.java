package be.enrosed.sales.adapter.in.rest;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.Priority;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Map;

/** Keeps photo-upload's global 64 MB allowance away from anonymous JSON endpoints. */
@Provider
@Priority(Priorities.AUTHENTICATION - 100)
public class PublicQuoteBodyLimitFilter implements ContainerRequestFilter {
    static final long MAX_PUBLIC_QUOTE_BODY_BYTES = 64 * 1024;
    /* Public quote DTOs contain only strings, numbers, booleans and lists. A
       small isolated mapper can therefore validate the same structural shape
       before RESTEasy consumes the stream. That keeps mapping failures (not
       only broken JSON punctuation) inside the public error contract. */
    private static final ObjectMapper JSON = new ObjectMapper()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    @Override
    public void filter(ContainerRequestContext request) {
        String path = request.getUriInfo().getPath().replaceFirst("^/", "");
        if (!"POST".equalsIgnoreCase(request.getMethod())
                || !(path.equals("api/v1/public/quotes/preview")
                || path.equals("api/v1/public/quotes/requests"))) return;
        int length = request.getLength();
        if (length > MAX_PUBLIC_QUOTE_BODY_BYTES) {
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
                    .readNBytes((int) MAX_PUBLIC_QUOTE_BODY_BYTES + 1);
            if (body.length > MAX_PUBLIC_QUOTE_BODY_BYTES) {
                abortTooLarge(request);
                return;
            }
            Class<?> requestType = path.endsWith("/preview")
                    ? PublicQuoteDtos.PreviewRequest.class
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
                        "INVALID_REQUEST", "Quote request is not valid JSON", Map.of()))
                .type(jakarta.ws.rs.core.MediaType.APPLICATION_JSON_TYPE)
                .build());
    }

    private static void abortTooLarge(ContainerRequestContext request) {
        request.abortWith(Response.status(413)
                .header("Cache-Control", "no-store")
                .entity(new PublicQuoteDtos.ErrorResponse(
                        "PAYLOAD_TOO_LARGE", "Quote request is too large", Map.of()))
                .type(jakarta.ws.rs.core.MediaType.APPLICATION_JSON_TYPE)
                .build());
    }
}
