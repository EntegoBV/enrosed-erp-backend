package be.enrosed.shared.adapter.in.rest;

import be.enrosed.shared.LocalizationIncompleteException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BusinessRuleMapperTest {

    @Test
    void localizationConflictHasAnExplicitJsonContract() {
        Response response = new BusinessRuleMapper().toResponse(
                new LocalizationIncompleteException(
                        "Cataloguscopy voor fr is onvolledig",
                        List.of("families.bowl-rose-xl.name")));

        assertEquals(409, response.getStatus());
        assertEquals(MediaType.APPLICATION_JSON_TYPE, response.getMediaType());
        Map<?, ?> body = (Map<?, ?>) response.getEntity();
        assertEquals(409, body.get("status"));
        assertEquals("LOCALIZATION_INCOMPLETE", body.get("code"));
        assertEquals("Cataloguscopy voor fr is onvolledig", body.get("message"));
        assertEquals(List.of("families.bowl-rose-xl.name"), body.get("missingPaths"));
    }
}
