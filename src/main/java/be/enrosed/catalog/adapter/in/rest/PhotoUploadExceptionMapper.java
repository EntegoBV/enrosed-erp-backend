package be.enrosed.catalog.adapter.in.rest;

import be.enrosed.catalog.application.PhotoUploadPolicy.InvalidPhotoException;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

import java.time.Instant;
import java.util.Map;

/** Maps rejected image bytes to a useful client error instead of a server error. */
@Provider
public class PhotoUploadExceptionMapper implements ExceptionMapper<InvalidPhotoException> {

    @Override
    public Response toResponse(InvalidPhotoException exception) {
        return Response.status(Response.Status.BAD_REQUEST)
                .entity(Map.of(
                        "status", Response.Status.BAD_REQUEST.getStatusCode(),
                        "message", exception.getMessage(),
                        "timestamp", Instant.now().toString()))
                .build();
    }
}
