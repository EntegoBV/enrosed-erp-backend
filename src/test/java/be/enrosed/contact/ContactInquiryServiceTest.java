package be.enrosed.contact;

import be.enrosed.publicform.PublicFormValidationException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ContactInquiryServiceTest {
    private final ContactInquiryService service = new ContactInquiryService();

    @Test
    void enforcesMessageBoundsAndSingleLineControls() {
        assertDoesNotThrow(() -> service.validate(request("A useful message", "Buyer BV")));
        assertEquals("TOO_SHORT", error(request("123456789", "Buyer BV"), "message"));
        assertEquals("TOO_LONG", error(request("x".repeat(2_001), "Buyer BV"), "message"));
        assertEquals("INVALID", error(request("A useful message", "Buyer\nBV"), "companyName"));
        assertEquals("INVALID", error(new ContactDtos.Request("EN", "A\tName",
                "buyer@example.com", "Buyer BV", null, "GENERAL", "A useful message",
                true, null, "/contact", "", "form", "challenge"), "contactName"));
    }

    @Test
    void consentTopicAndEmailAreValidated() {
        ContactDtos.Request invalid = new ContactDtos.Request("XX", "Ana", "not-email",
                null, null, "UNKNOWN", "A useful message", false,
                null, null, "", null, null);
        PublicFormValidationException exception = assertThrows(
                PublicFormValidationException.class, () -> service.validate(invalid));
        assertEquals("UNSUPPORTED", exception.fieldErrors().get("language"));
        assertEquals("UNSUPPORTED", exception.fieldErrors().get("topic"));
        assertEquals("INVALID", exception.fieldErrors().get("email"));
        assertEquals("REQUIRED", exception.fieldErrors().get("privacyAccepted"));
    }

    @Test
    void sourcePageMustBeASameSiteRelativePath() {
        ContactDtos.Request external = new ContactDtos.Request("EN", "Ana",
                "buyer@example.com", "Buyer BV", null, "GENERAL", "A useful message",
                true, null, "https://attacker.example/contact", "", null, null);
        ContactDtos.Request protocolRelative = new ContactDtos.Request("EN", "Ana",
                "buyer@example.com", "Buyer BV", null, "GENERAL", "A useful message",
                true, null, "//attacker.example/contact", "", null, null);

        assertEquals("INVALID", error(external, "sourcePage"));
        assertEquals("INVALID", error(protocolRelative, "sourcePage"));
        assertDoesNotThrow(() -> service.validate(request("A useful message", "Buyer BV")));
    }

    private String error(ContactDtos.Request request, String field) {
        return assertThrows(PublicFormValidationException.class,
                () -> service.validate(request)).fieldErrors().get(field);
    }

    static ContactDtos.Request request(String message, String company) {
        return new ContactDtos.Request("EN", "Ana", "buyer@example.com", company,
                "+32 1 23 45 67", "GENERAL", message, true, "attacker-value",
                "/contact", "", "form", "challenge");
    }
}
