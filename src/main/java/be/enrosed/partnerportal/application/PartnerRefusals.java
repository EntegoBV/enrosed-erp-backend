package be.enrosed.partnerportal.application;

import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.time.Instant;
import java.util.Map;

/**
 * A request the client should never have built (400, not the 409 of a business rule): mixed
 * request shapes, a staff customerId that does not match the melding. Same JSON body as the
 * business-rule answers, so the screens show the message the same way.
 */
public final class PartnerRefusals {

    private PartnerRefusals() {}

    /** 429 with Retry-After and the usual JSON body (a limit per day was reached). */
    public static jakarta.ws.rs.WebApplicationException tooMany(String message, long retryAfterSeconds) {
        return new jakarta.ws.rs.WebApplicationException(message, Response.status(429)
                .type(MediaType.APPLICATION_JSON_TYPE)
                .header("Retry-After", Math.max(1, retryAfterSeconds))
                .header("Cache-Control", "no-store")
                .entity(Map.of("status", 429, "message", message, "timestamp", Instant.now().toString()))
                .build());
    }

    public static BadRequestException badRequest(String message) {
        return new BadRequestException(message, Response.status(Response.Status.BAD_REQUEST)
                .type(MediaType.APPLICATION_JSON_TYPE)
                .entity(Map.of("status", 400, "message", message, "timestamp", Instant.now().toString()))
                .build());
    }
}
