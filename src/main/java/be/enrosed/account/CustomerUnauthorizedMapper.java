package be.enrosed.account;

import be.enrosed.sales.adapter.in.rest.PublicQuoteDtos;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

import java.util.Map;

/**
 * One 401 body per code, whatever the cause, and no WWW-Authenticate header: a browser
 * must never answer a customer login with its own Basic dialog.
 */
@Provider
public class CustomerUnauthorizedMapper implements ExceptionMapper<CustomerUnauthorizedException> {
    @Override
    public Response toResponse(CustomerUnauthorizedException exception) {
        return Response.status(Response.Status.UNAUTHORIZED)
                .header("Cache-Control", "no-store")
                .type(MediaType.APPLICATION_JSON_TYPE)
                .entity(new PublicQuoteDtos.ErrorResponse(
                        exception.code(), exception.getMessage(), Map.of()))
                .build();
    }
}
