package be.enrosed.sales.adapter.in.rest;

import be.enrosed.sales.application.PublicQuoteValidationException;
import be.enrosed.sales.application.PublicQuoteRateLimitException;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

@Provider
public class PublicQuoteExceptionMapper implements ExceptionMapper<PublicQuoteValidationException> {
    @Override
    public Response toResponse(PublicQuoteValidationException exception) {
        boolean idempotencyConflict = "CONFLICT".equals(
                exception.fieldErrors().get("idempotencyKey"));
        return Response.status(idempotencyConflict ? 409 : 422)
                .header("Cache-Control", "no-store")
                .entity(new PublicQuoteDtos.ErrorResponse(
                        "VALIDATION_ERROR",
                        "The quote request contains invalid or missing fields",
                        exception.fieldErrors()))
                .build();
    }
}

@Provider
class PublicQuoteRateLimitMapper implements ExceptionMapper<PublicQuoteRateLimitException> {
    @Override
    public Response toResponse(PublicQuoteRateLimitException exception) {
        return Response.status(429)
                .header("Retry-After", exception.retryAfterSeconds())
                .header("Cache-Control", "no-store")
                .entity(new PublicQuoteDtos.ErrorResponse(
                        "RATE_LIMITED", "Too many quote requests; try again later",
                        java.util.Map.of()))
                .build();
    }
}
