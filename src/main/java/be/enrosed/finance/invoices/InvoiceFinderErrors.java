package be.enrosed.finance.invoices;

import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The finder's request errors: HTTP 400 (404 for NO_PDF, 413 for a file too large to preview) with
 * {@code {"status", "code", "message"}} and a Dutch sentence. Odoo failures, not-found and rule conflicts keep their
 * existing mappers.
 */
public final class InvoiceFinderErrors {
    private InvoiceFinderErrors() {}

    /** A refused request; {@link #code} is what the screens switch on. */
    public static final class Invalid extends WebApplicationException {
        public final String code;
        public final int status;

        Invalid(int status, String code, String message) {
            super(message, response(status, code, message));
            this.code = code;
            this.status = status;
        }
    }

    public static Invalid invalid(String code) {
        return new Invalid(statusOf(code), code, message(code));
    }

    public static Invalid invalid(String code, String message) {
        return new Invalid(statusOf(code), code, message);
    }

    static int statusOf(String code) {
        return switch (code) {
            case "NO_PDF" -> 404;
            case "FILE_TOO_LARGE" -> 413;
            default -> 400;
        };
    }

    static String message(String code) {
        return switch (code) {
            case "INVALID_SCOPE" -> "Deze selectie bestaat niet voor facturen.";
            case "INVALID_QUERY" -> "De zoektekst is te lang (maximaal 200 tekens).";
            case "INVALID_PERIOD" -> "Geef een geldige periode op (jjjj-mm-dd, begin niet na einde).";
            case "INVALID_LIMIT" -> "Vraag tussen 1 en 100 facturen per keer op.";
            case "INVALID_CURSOR" -> "De lijst is intussen veranderd. Laad ze opnieuw.";
            case "INVALID_KEY" -> "Deze factuurverwijzing bestaat niet.";
            case "INVALID_ORDER" -> "Deze container bestaat niet.";
            case "INVALID_MARK" -> "Deze markering is niet geldig.";
            case "NOT_INCOMING" -> "Alleen een ontvangen factuur kan gemarkeerd worden.";
            case "NO_PDF" -> "Geen PDF in dit bestand.";
            case "FILE_TOO_LARGE" -> "Dit bestand is te groot voor een voorbeeld (maximaal 15 MB).";
            default -> "Ongeldige aanvraag.";
        };
    }

    static Response response(int status, String code, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", status);
        body.put("code", code);
        body.put("message", message);
        return Response.status(status).type(MediaType.APPLICATION_JSON_TYPE).header("Cache-Control", "no-store")
                .entity(body).build();
    }
}
