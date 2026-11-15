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
 * One plain-text mail per radar event, straight through the Brevo API with
 * the radar's own sender name. Without a Brevo key it falls back to the
 * configured mailer. While the mailer is in mock mode (local, test) nothing
 * leaves: the mail only goes to the log.
 */
@ApplicationScoped
public class StockRadarMailSender {

    private static final Logger LOG = Logger.getLogger(StockRadarMailSender.class);
    private static final URI BREVO = URI.create("https://api.brevo.com/v3/smtp/email");
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    @ConfigProperty(name = "enrosed.mail.brevo-api-key") Optional<String> brevoApiKey;
    @ConfigProperty(name = "quarkus.mailer.from", defaultValue = "offertes@enrosed.be") String from;
    @ConfigProperty(name = "quarkus.mailer.mock", defaultValue = "false") boolean mock;
    private final Mailer mailer;
    private final ObjectMapper json;

    public StockRadarMailSender(Mailer mailer, ObjectMapper json) {
        this.mailer = mailer;
        this.json = json;
    }

    /** True while the mailer only logs (local, Railway test): a "sent" test mail never left. */
    public boolean mock() { return mock; }

    /** The provider's HTTP status (0 = local mailer or mock, -1 = nothing sent) and, on a refusal, the provider's own words. */
    public record Result(int status, String detail) {}

    public int send(String to, String subject, String text) {
        return sendWithDetail(to, subject, text).status();
    }

    public Result sendWithDetail(String to, String subject, String text) {
        if (to == null || to.isBlank()) return new Result(-2, "Geen ontvanger");
        if (mock) {
            LOG.infof("Voorraadradar-mail (mock, niet verstuurd) naar %s: %s", to, subject);
            return new Result(0, "mock");
        }
        String key = brevoApiKey.orElse("").trim();
        try {
            if (key.isEmpty()) {
                mailer.send(Mail.withText(to, subject, text));
                return new Result(0, null);
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
            String detail = null;
            if (response.statusCode() >= 300) {
                String body = response.body() == null ? "" : response.body();
                detail = body.substring(0, Math.min(300, body.length()));
                LOG.warnf("Voorraadradar-mail geweigerd (%d): %s", response.statusCode(), detail);
            }
            return new Result(response.statusCode(), detail);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return new Result(-1, "Onderbroken");
        } catch (Exception failure) {
            LOG.warnf("Voorraadradar-mail niet verstuurd: %s", failure.getClass().getSimpleName());
            return new Result(-1, failure.getClass().getSimpleName());
        }
    }

    private String senderEmail() {
        int open = from.indexOf('<');
        int close = from.indexOf('>');
        return open >= 0 && close > open ? from.substring(open + 1, close).trim() : from.trim();
    }
}
