package be.enrosed.analytics;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.auth.oauth2.ServiceAccountCredentials;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.auth.oauth2.UserCredentials;
import com.google.auth.oauth2.AccessToken;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.http.LowLevelHttpRequest;
import com.google.api.client.http.LowLevelHttpResponse;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.util.ArrayList;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Fixed Google endpoints and credentials stay on the server; consent grants only reporting scopes. */
@ApplicationScoped
public class GoogleReportingClient {
    static final List<String> SCOPES = List.of("https://www.googleapis.com/auth/analytics.readonly",
            "https://www.googleapis.com/auth/webmasters.readonly");
    private static final Set<String> HOSTS = Set.of("analyticsdata.googleapis.com", "www.googleapis.com");
    private final ObjectMapper json;
    private final String serviceAccountJson;
    private final String userCredentialsJson;
    private final HttpClient http;
    private final Clock clock;
    private String authFailure;
    private Instant authRetryAfter;
    private GoogleCredentials credentials;
    private AccessToken token;

    @Inject
    public GoogleReportingClient(ObjectMapper json,
            @ConfigProperty(name="enrosed.google-reporting.service-account-json") Optional<String> secret,
            @ConfigProperty(name="enrosed.google-reporting.user-credentials-json") Optional<String> userSecret) {
        this(json,secret.orElse(""),userSecret.orElse(""),HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8))
                .followRedirects(HttpClient.Redirect.NEVER).build(),Clock.systemUTC());
    }
    GoogleReportingClient(ObjectMapper json,Optional<String> secret) { this(json,secret,Optional.empty()); }
    GoogleReportingClient(ObjectMapper json,String secret,HttpClient http,Clock clock) {
        this(json,secret,"",http,clock);
    }
    GoogleReportingClient(ObjectMapper json,String secret,String userSecret,HttpClient http,Clock clock) {
        this.json=json; this.serviceAccountJson=secret.trim(); this.userCredentialsJson=userSecret.trim();
        this.http=http; this.clock=clock;
    }
    public boolean configured() { return !serviceAccountJson.isEmpty() || !userCredentialsJson.isEmpty(); }

    public JsonNode post(String endpoint, Map<String,Object> body) {
        URI uri=URI.create(endpoint);
        if (!"https".equals(uri.getScheme()) || !HOSTS.contains(uri.getHost()) || uri.getUserInfo()!=null
                || uri.getPort()!=-1 || uri.getFragment()!=null) throw new Failure("INVALID_CONFIGURATION");
        String bearer=accessToken();
        try {
            HttpRequest request=HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(12))
                    .header("Authorization","Bearer "+bearer).header("Content-Type","application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(body))).build();
            HttpResponse<String> response=http.send(request,HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode()!=200) throw new Failure(codeForStatus(response.statusCode()));
            JsonNode parsed=json.readTree(response.body());
            if (parsed==null || !parsed.isObject() || parsed.has("error")) throw new Failure("INVALID_RESPONSE");
            return parsed;
        } catch (Failure failure) { throw failure; }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new Failure("UNAVAILABLE"); }
        catch (Exception unavailable) { throw new Failure("UNAVAILABLE"); }
    }

    private synchronized String accessToken() {
        if (!configured()) throw new Failure("NOT_CONFIGURED");
        if (!serviceAccountJson.isEmpty() && !userCredentialsJson.isEmpty()) throw rememberAuthFailure("INVALID_CONFIGURATION");
        if (authFailure!=null && authRetryAfter!=null && clock.instant().isBefore(authRetryAfter)) throw new Failure(authFailure);
        if (token!=null && token.getExpirationTime()!=null
                && token.getExpirationTime().toInstant().isAfter(clock.instant().plusSeconds(60))) return token.getTokenValue();
        if (credentials==null) {
            try {
                if (!userCredentialsJson.isEmpty()) {
                    JsonNode value=json.readTree(userCredentialsJson);
                    validateUserCredentialDocument(value);
                    // This refreshes the two read-only scopes granted at internal OAuth consent.
                    // No generic ADC/envelope loader, delegated user or configurable token endpoint.
                    credentials=UserCredentials.newBuilder()
                            .setClientId(value.path("client_id").asText())
                            .setClientSecret(value.path("client_secret").asText())
                            .setRefreshToken(value.path("refresh_token").asText())
                            .setTokenServerUri(URI.create("https://oauth2.googleapis.com/token"))
                            .setHttpTransportFactory(this::tokenTransport).build();
                } else {
                    JsonNode value=json.readTree(serviceAccountJson);
                    validateCredentialDocument(value);
                    ServiceAccountCredentials service=(ServiceAccountCredentials)ServiceAccountCredentials.fromStream(
                            new ByteArrayInputStream(serviceAccountJson.getBytes(StandardCharsets.UTF_8)),this::tokenTransport)
                            .createScoped(SCOPES);
                    credentials=service.toBuilder().setServiceAccountUser(null).setDefaultRetriesEnabled(false).build();
                }
            } catch (Exception invalid) { throw rememberAuthFailure("INVALID_CONFIGURATION"); }
        }
        try {
            token=credentials.refreshAccessToken();
            if (token==null || token.getTokenValue()==null || token.getTokenValue().isBlank()) throw new Failure("AUTH_FAILED");
            authFailure=null; authRetryAfter=null;
            return token.getTokenValue();
        } catch (Exception rejected) { throw rememberAuthFailure("AUTH_FAILED"); }
    }
    private Failure rememberAuthFailure(String code) {
        authFailure=code; authRetryAfter=clock.instant().plusSeconds(30); return new Failure(code);
    }

    /** Google Auth prepares the token request; this adapter bounds its network request too. */
    private HttpTransport tokenTransport() {
        return new HttpTransport() {
            @Override protected LowLevelHttpRequest buildRequest(String method,String url) throws IOException {
                if (!"POST".equals(method) || !"https://oauth2.googleapis.com/token".equals(url)) throw new IOException("Token endpoint refused");
                return new LowLevelHttpRequest() {
                    final List<Map.Entry<String,String>> headers=new ArrayList<>();
                    @Override public void addHeader(String name,String value) { headers.add(Map.entry(name,value)); }
                    @Override public LowLevelHttpResponse execute() throws IOException {
                        var bytes=new ByteArrayOutputStream();
                        if (getStreamingContent()!=null) getStreamingContent().writeTo(bytes);
                        var request=HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(12));
                        for (var header:headers) if (!"content-length".equalsIgnoreCase(header.getKey())) request.header(header.getKey(),header.getValue());
                        if (getContentType()!=null) request.header("Content-Type",getContentType());
                        if (getContentEncoding()!=null) request.header("Content-Encoding",getContentEncoding());
                        HttpResponse<byte[]> response;
                        try { response=http.send(request.POST(HttpRequest.BodyPublishers.ofByteArray(bytes.toByteArray())).build(),
                                HttpResponse.BodyHandlers.ofByteArray()); }
                        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IOException("Token request interrupted"); }
                        if (response.statusCode()>=300 && response.statusCode()<400) throw new IOException("Token redirect refused");
                        byte[] body=response.body();
                        List<Map.Entry<String,String>> responseHeaders=new ArrayList<>();
                        response.headers().map().forEach((name,values)->values.forEach(value->responseHeaders.add(Map.entry(name,value))));
                        return new LowLevelHttpResponse() {
                            @Override public InputStream getContent() { return new ByteArrayInputStream(body); }
                            @Override public String getContentEncoding() { return response.headers().firstValue("content-encoding").orElse(null); }
                            @Override public long getContentLength() { return body.length; }
                            @Override public String getContentType() { return response.headers().firstValue("content-type").orElse("application/json"); }
                            @Override public String getStatusLine() { return "HTTP/1.1 "+response.statusCode(); }
                            @Override public int getStatusCode() { return response.statusCode(); }
                            @Override public String getReasonPhrase() { return null; }
                            @Override public int getHeaderCount() { return responseHeaders.size(); }
                            @Override public String getHeaderName(int index) { return responseHeaders.get(index).getKey(); }
                            @Override public String getHeaderValue(int index) { return responseHeaders.get(index).getValue(); }
                        };
                    }
                };
            }
        };
    }
    static void validateCredentialDocument(JsonNode value) {
        if (value==null || !"service_account".equals(value.path("type").asText())
                || !"https://oauth2.googleapis.com/token".equals(value.path("token_uri").asText())
                || !value.path("client_email").asText().endsWith(".gserviceaccount.com")
                || !value.path("private_key").asText().contains("-----BEGIN PRIVATE KEY-----")
                || !"googleapis.com".equals(value.path("universe_domain").asText("googleapis.com"))) {
            throw new Failure("INVALID_CONFIGURATION");
        }
    }
    static void validateUserCredentialDocument(JsonNode value) {
        if (value==null || !"authorized_user".equals(value.path("type").asText())
                || !value.path("client_id").isTextual() || !value.path("client_id").asText().endsWith(".apps.googleusercontent.com")
                || !value.path("client_secret").isTextual() || value.path("client_secret").asText().isBlank()
                || !value.path("refresh_token").isTextual() || value.path("refresh_token").asText().isBlank()
                || value.has("token_uri") && !"https://oauth2.googleapis.com/token".equals(value.path("token_uri").asText())) {
            throw new Failure("INVALID_CONFIGURATION");
        }
    }
    static String codeForStatus(int code) {
        return switch(code) { case 400 -> "INVALID_REQUEST"; case 401 -> "AUTH_FAILED";
            case 403 -> "ACCESS_DENIED"; case 404 -> "NOT_FOUND"; case 429 -> "QUOTA_EXCEEDED";
            default -> "UNAVAILABLE"; };
    }
    /** No upstream body, token, key or exception cause can reach HTTP errors or logs. */
    public static final class Failure extends RuntimeException {
        public final String code;
        public Failure(String code) { super(code, null, false, false); this.code=code; }
    }
}
