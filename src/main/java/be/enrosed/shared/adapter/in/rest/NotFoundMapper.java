package be.enrosed.shared.adapter.in.rest;

import be.enrosed.shared.NotFoundException;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

import java.time.Instant;
import java.util.Map;

@Provider
public class NotFoundMapper implements ExceptionMapper<NotFoundException> {
    @Override
    public Response toResponse(NotFoundException exception) {
        return Response.status(404)
                .entity(Map.of("status", 404,
                               "message", exception.getMessage(),
                               "timestamp", Instant.now().toString()))
                .build();
    }
}
