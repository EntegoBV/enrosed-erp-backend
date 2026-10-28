package be.enrosed.stockradar;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.mailer.Mail;
import io.quarkus.mailer.Mailer;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * One plain-text mail per radar event, straight through the Brevo API: the
 * test environment runs the Quarkus mailer in mock mode, and this mail must
 * leave from there. Without a Brevo key it falls back to the configured mailer.
 */
@ApplicationScoped
public class StockRadarMailSender {

    private static final Logger LOG = Logger.getLogger(StockRadarMailSender.class);
    private static final URI BREVO = URI.create("https://api.brevo.com/v3/smtp/email");
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    @ConfigProperty(name = "enrosed.mail.brevo-api-key") Optional<String> brevoApiKey;
    @ConfigProperty(name = "quarkus.mailer.from", defaultValue = "offertes@enrosed.be") String from;
    private final Mailer mailer;
    private final ObjectMapper json;

    public StockRadarMailSender(Mailer mailer, ObjectMapper json) {
        this.mailer = mailer;
        this.json = json;
    }

    /** Returns the provider's HTTP status, 0 for the local mailer, -1 when nothing could be sent. */
    public int send(String to, String subject, String text) {
        if (to == null || to.isBlank()) return -2;
        String key = brevoApiKey.orElse("").trim();
        try {
            if (key.isEmpty()) {
                mailer.send(Mail.withText(to, subject, text));
                return 0;
            }
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("sender", Map.of("name", "Enrosed voorraadradar", "email", senderEmail()));
            payload.put("to", List.of(Map.of("email", to)));
            payload.put("subject", subject);
            payload.put("textContent", text);
            HttpRequest request = HttpRequest.newBuilder(BREVO).timeout(Duration.ofSeconds(25))
                    .header("api-key", key).header("content-type", "application/json").header("accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(payload), StandardCharsets.UTF_8)).build();
            HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 300) {
                String detail = response.body() == null ? "" : response.body();
                LOG.warnf("Voorraadradar-mail geweigerd (%d): %s", response.statusCode(), detail.substring(0, Math.min(200, detail.length())));
            }
            return response.statusCode();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return -1;
        } catch (Exception failure) {
            LOG.warnf("Voorraadradar-mail niet verstuurd: %s", failure.getClass().getSimpleName());
            return -1;
        }
    }

    private String senderEmail() {
        int open = from.indexOf('<');
        int close = from.indexOf('>');
        return open >= 0 && close > open ? from.substring(open + 1, close).trim() : from.trim();
    }
}
