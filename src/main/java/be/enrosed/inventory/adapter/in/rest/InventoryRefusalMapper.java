package be.enrosed.inventory.adapter.in.rest;

import be.enrosed.inventory.application.InventoryRefusal;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** Answers a refusal of the count or the closing as a 409 that names its code; the more general rule mapper never sees it. */
@Provider
public class InventoryRefusalMapper implements ExceptionMapper<InventoryRefusal> {
    @Override
    public Response toResponse(InventoryRefusal refusal) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", 409);
        body.put("code", refusal.code());
        body.put("message", refusal.getMessage());
        body.put("details", refusal.details());
        body.put("timestamp", Instant.now().toString());
        return Response.status(409)
                .type(MediaType.APPLICATION_JSON_TYPE)
                .entity(body)
                .build();
    }
}
