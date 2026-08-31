package be.enrosed.shared.adapter.in.rest;

import be.enrosed.shared.BusinessRuleException;
import be.enrosed.shared.LocalizationIncompleteException;
import be.enrosed.shared.UnprocessableBusinessRuleException;
import jakarta.ws.rs.core.MediaType;
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
                    .type(MediaType.APPLICATION_JSON_TYPE)
                    .entity(Map.of("status", 409,
                            "code", "LOCALIZATION_INCOMPLETE",
                            "message", localized.getMessage(),
                            "missingPaths", localized.missingPaths()))
                    .build();
        }
        if (exception instanceof UnprocessableBusinessRuleException) {
            return Response.status(422)
                    .entity(Map.of("status", 422,
                                   "message", exception.getMessage(),
                                   "timestamp", Instant.now().toString()))
                    .build();
        }
        return Response.status(409)
                .entity(Map.of("status", 409,
                               "message", exception.getMessage(),
                               "timestamp", Instant.now().toString()))
                .build();
    }
}
