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

    public static BadRequestException badRequest(String message) {
        return new BadRequestException(message, Response.status(Response.Status.BAD_REQUEST)
                .type(MediaType.APPLICATION_JSON_TYPE)
                .entity(Map.of("status", 400, "message", message, "timestamp", Instant.now().toString()))
                .build());
    }
}
