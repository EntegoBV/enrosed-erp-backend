package be.enrosed.publicform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** Optional Cloudflare Turnstile verification, always enforced when configured. */
@ApplicationScoped
public class TurnstileVerificationService {
    private static final int MAX_TOKEN_LENGTH = 2_048;
    private final String siteKey;
    private final String secret;
    private final boolean required;
    private final Set<String> hostnames;
    private final URI verifyUri;
    private final ObjectMapper json;
    private final Transport transport;

    @Inject
    public TurnstileVerificationService(
            @ConfigProperty(name = "enrosed.public-forms.turnstile.site-key") Optional<String> siteKey,
            @ConfigProperty(name = "enrosed.public-forms.turnstile.secret") Optional<String> secret,
            @ConfigProperty(name = "enrosed.public-forms.turnstile.required",
                    defaultValue = "false") boolean required,
            @ConfigProperty(name = "enrosed.public-forms.turnstile.hostnames") Optional<String> hostnames,
            @ConfigProperty(name = "enrosed.public-forms.turnstile.verify-url",
                    defaultValue = "https://challenges.cloudflare.com/turnstile/v0/siteverify") String verifyUrl,
            ObjectMapper json) {
        this(clean(siteKey.orElse(null)), clean(secret.orElse(null)), required,
                parseHostnames(hostnames.orElse("")),
                URI.create(verifyUrl), json, new JdkTransport());
    }

    TurnstileVerificationService(String siteKey, String secret, boolean required,
                                 Set<String> hostnames,
                                 URI verifyUri, ObjectMapper json, Transport transport) {
        this.siteKey = clean(siteKey);
        this.secret = clean(secret);
        this.required = required;
        this.hostnames = Set.copyOf(hostnames);
        this.verifyUri = verifyUri;
        this.json = json;
        this.transport = transport;
    }

    public String siteKey() {
        ensureConfiguration();
        return configured() ? siteKey : null;
    }

    /** Production is configured as required, so an incomplete challenge setup fails startup. */
    void validateConfigurationAtStartup(@Observes StartupEvent ignored) {
        ensureConfiguration();
    }

    public void verify(PublicFormPurpose purpose, String challengeToken) {
        ensureConfiguration();
        if (!configured()) return;
        if (challengeToken == null || challengeToken.isBlank()) {
            throw invalid("REQUIRED");
        }
        String token = challengeToken.strip();
        if (token.length() > MAX_TOKEN_LENGTH) throw invalid("INVALID");
        try {
            String idempotencyKey = UUID.nameUUIDFromBytes(
                    token.getBytes(StandardCharsets.UTF_8)).toString();
            String body = "secret=" + encode(secret) + "&response=" + encode(token)
                    + "&idempotency_key=" + encode(idempotencyKey);
            HttpRequest request = HttpRequest.newBuilder(verifyUri)
                    .timeout(Duration.ofSeconds(8))
                    .header("content-type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = transport.send(request);
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new PublicFormServiceUnavailableException();
            }
            JsonNode result = json.readTree(response.body());
            String expectedAction = purpose == PublicFormPurpose.QUOTE
                    ? "quote_submit" : "contact_submit";
            String hostname = result.path("hostname").asText("").toLowerCase(Locale.ROOT);
            if (!result.path("success").asBoolean(false)
                    || !expectedAction.equals(result.path("action").asText())
                    || !hostnames.contains(hostname)) {
                throw invalid("INVALID");
            }
        } catch (PublicFormValidationException | PublicFormServiceUnavailableException exception) {
            throw exception;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new PublicFormServiceUnavailableException();
        } catch (Exception exception) {
            throw new PublicFormServiceUnavailableException();
        }
    }

    private boolean configured() {
        return siteKey != null && secret != null;
    }

    private void ensureConfiguration() {
        if (required && !configured()
                || (siteKey == null) != (secret == null)
                || configured() && hostnames.isEmpty()) {
            throw new PublicFormServiceUnavailableException();
        }
    }

    private static PublicFormValidationException invalid(String code) {
        return new PublicFormValidationException(Map.of("challengeToken", code));
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String clean(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static Set<String> parseHostnames(String value) {
        return Arrays.stream(value.split(","))
                .map(String::strip).filter(part -> !part.isBlank())
                .map(part -> part.toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
    }

    interface Transport {
        HttpResponse<String> send(HttpRequest request) throws Exception;
    }

    private static final class JdkTransport implements Transport {
        private static final HttpClient HTTP = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5)).build();

        @Override
        public HttpResponse<String> send(HttpRequest request) throws Exception {
            return HTTP.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        }
    }
}
