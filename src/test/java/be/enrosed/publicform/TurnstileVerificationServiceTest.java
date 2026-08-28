package be.enrosed.publicform;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.io.ByteArrayOutputStream;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TurnstileVerificationServiceTest {
    @Test
    void localUnconfiguredIsUsableButRequiredConfigurationFailsClosed() {
        assertDoesNotThrow(() -> service(null, null, false, Set.of(), response(200, "{}"), null)
                .verify(PublicFormPurpose.CONTACT, null));
        assertThrows(PublicFormServiceUnavailableException.class,
                () -> service(null, null, true, Set.of(), response(200, "{}"), null)
                        .verify(PublicFormPurpose.CONTACT, null));
    }

    @Test
    void verifiesSuccessExactActionAndHostnameAndSendsStableIdempotencyKey() throws Exception {
        AtomicReference<HttpRequest> sent = new AtomicReference<>();
        TurnstileVerificationService verifier = service("site", "secret", true,
                Set.of("www.enrosed.com"), response(200,
                        "{\"success\":true,\"action\":\"contact_submit\","
                                + "\"hostname\":\"www.enrosed.com\"}"), sent);

        assertDoesNotThrow(() -> verifier.verify(PublicFormPurpose.CONTACT, "challenge"));
        String firstBody = body(sent.get());
        assertTrue(firstBody.contains("secret=secret"));
        assertTrue(firstBody.contains("response=challenge"));
        assertTrue(firstBody.contains("idempotency_key="));

        assertDoesNotThrow(() -> verifier.verify(PublicFormPurpose.CONTACT, "challenge"));
        assertEquals(firstBody, body(sent.get()));
    }

    @Test
    void wrongActionOrProviderOutageCreatesAClosedFailure() {
        TurnstileVerificationService wrongAction = service("site", "secret", true,
                Set.of("www.enrosed.com"), response(200,
                        "{\"success\":true,\"action\":\"quote_submit\","
                                + "\"hostname\":\"www.enrosed.com\"}"), null);
        assertThrows(PublicFormValidationException.class,
                () -> wrongAction.verify(PublicFormPurpose.CONTACT, "challenge"));
        assertThrows(PublicFormServiceUnavailableException.class,
                () -> service("site", "secret", true, Set.of("www.enrosed.com"),
                        response(503, "{}"), null)
                        .verify(PublicFormPurpose.CONTACT, "challenge"));
    }

    @Test
    void wrongHostnameFailedChallengeMalformedReplyAndTransportExceptionAllFailClosed() {
        assertThrows(PublicFormValidationException.class,
                () -> service("site", "secret", true, Set.of("www.enrosed.com"),
                        response(200, "{\"success\":true,\"action\":\"contact_submit\","
                                + "\"hostname\":\"attacker.example\"}"), null)
                        .verify(PublicFormPurpose.CONTACT, "challenge"));
        assertThrows(PublicFormValidationException.class,
                () -> service("site", "secret", true, Set.of("www.enrosed.com"),
                        response(200, "{\"success\":false}"), null)
                        .verify(PublicFormPurpose.CONTACT, "challenge"));
        assertThrows(PublicFormServiceUnavailableException.class,
                () -> service("site", "secret", true, Set.of("www.enrosed.com"),
                        response(200, "not-json"), null)
                        .verify(PublicFormPurpose.CONTACT, "challenge"));
        TurnstileVerificationService transportFailure = new TurnstileVerificationService(
                "site", "secret", true, Set.of("www.enrosed.com"),
                URI.create("https://example.test/siteverify"), new ObjectMapper(),
                request -> { throw new java.net.http.HttpTimeoutException("test timeout"); });
        assertThrows(PublicFormServiceUnavailableException.class,
                () -> transportFailure.verify(PublicFormPurpose.CONTACT, "challenge"));
    }

    @Test
    void incompleteRequiredConfigurationFailsReadinessValidation() {
        TurnstileVerificationService incomplete = service("site", null, true,
                Set.of("www.enrosed.com"), response(200, "{}"), null);
        assertThrows(PublicFormServiceUnavailableException.class,
                () -> incomplete.validateConfigurationAtStartup(null));
    }

    private static TurnstileVerificationService service(
            String site, String secret, boolean required, Set<String> hostnames,
            HttpResponse<String> response, AtomicReference<HttpRequest> sent) {
        return new TurnstileVerificationService(site, secret, required, hostnames,
                URI.create("https://example.test/siteverify"), new ObjectMapper(), request -> {
                    if (sent != null) sent.set(request);
                    return response;
                });
    }

    @SuppressWarnings("unchecked")
    private static HttpResponse<String> response(int status, String body) {
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.body()).thenReturn(body);
        return response;
    }

    private static String body(HttpRequest request) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        CompletableFuture<Void> complete = new CompletableFuture<>();
        request.bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<>() {
            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(ByteBuffer item) {
                byte[] bytes = new byte[item.remaining()];
                item.get(bytes);
                output.writeBytes(bytes);
            }

            @Override
            public void onError(Throwable throwable) {
                complete.completeExceptionally(throwable);
            }

            @Override
            public void onComplete() {
                complete.complete(null);
            }
        });
        complete.get(2, TimeUnit.SECONDS);
        return output.toString(StandardCharsets.UTF_8);
    }
}
