package be.enrosed.sales.adapter.in.rest;

import be.enrosed.publicform.PublicFormRateLimitException;
import be.enrosed.publicform.PublicFormServiceUnavailableException;
import be.enrosed.publicform.PublicFormValidationException;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

@Provider
public class PublicQuoteExceptionMapper implements ExceptionMapper<PublicFormValidationException> {
    @Override
    public Response toResponse(PublicFormValidationException exception) {
        boolean idempotencyConflict = "CONFLICT".equals(
                exception.fieldErrors().get("idempotencyKey"));
        return Response.status(idempotencyConflict ? 409 : 422)
                .header("Cache-Control", "no-store")
                .entity(new PublicQuoteDtos.ErrorResponse(
                        "VALIDATION_ERROR",
                        "The request contains invalid or missing fields",
                        exception.fieldErrors()))
                .build();
    }
}

@Provider
class PublicQuoteRateLimitMapper implements ExceptionMapper<PublicFormRateLimitException> {
    @Override
    public Response toResponse(PublicFormRateLimitException exception) {
        return Response.status(429)
                .header("Retry-After", exception.retryAfterSeconds())
                .header("Cache-Control", "no-store")
                .entity(new PublicQuoteDtos.ErrorResponse(
                        "RATE_LIMITED", "Too many requests; try again later",
                        java.util.Map.of()))
                .build();
    }
}

@Provider
class PublicFormServiceUnavailableMapper
        implements ExceptionMapper<PublicFormServiceUnavailableException> {
    @Override
    public Response toResponse(PublicFormServiceUnavailableException exception) {
        return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                .header("Retry-After", "30")
                .header("Cache-Control", "no-store")
                .entity(new PublicQuoteDtos.ErrorResponse(
                        "SERVICE_UNAVAILABLE",
                        "The form service is temporarily unavailable",
                        java.util.Map.of()))
                .build();
    }
}
