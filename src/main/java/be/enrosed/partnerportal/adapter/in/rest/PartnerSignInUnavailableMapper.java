package be.enrosed.partnerportal.adapter.in.rest;

import be.enrosed.partnerportal.application.PartnerSignInUnavailableException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

import java.time.Instant;
import java.util.Map;

/** 429 / 503 with Retry-After for a partner sign-in that was not checked (never 401: the password may be right). */
@Provider
public class PartnerSignInUnavailableMapper implements ExceptionMapper<PartnerSignInUnavailableException> {
    @Override
    public Response toResponse(PartnerSignInUnavailableException exception) {
        return Response.status(exception.status())
                .type(MediaType.APPLICATION_JSON_TYPE)
                .header("Retry-After", exception.retryAfterSeconds())
                .header("Cache-Control", "no-store")
                .entity(Map.of("status", exception.status(), "message", exception.getMessage(),
                        "timestamp", Instant.now().toString()))
                .build();
    }
}
