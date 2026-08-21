package be.enrosed.shared.adapter.in.rest;

import be.enrosed.shared.BusinessRuleException;
import be.enrosed.shared.LocalizationIncompleteException;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

import java.time.Instant;
import java.util.Map;

@Provider
public class BusinessRuleMapper implements ExceptionMapper<BusinessRuleException> {
    @Override
    public Response toResponse(BusinessRuleException exception) {
        if (exception instanceof LocalizationIncompleteException localized) {
            return Response.status(409)
                    .entity(Map.of("message", localized.getMessage(),
                            "missingPaths", localized.missingPaths()))
                    .build();
        }
        return Response.status(409)
                .entity(Map.of("status", 409,
                               "message", exception.getMessage(),
                               "timestamp", Instant.now().toString()))
                .build();
    }
}
