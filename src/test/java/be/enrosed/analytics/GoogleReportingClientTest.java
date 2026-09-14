package be.enrosed.analytics;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.io.ByteArrayOutputStream;
import java.util.zip.GZIPOutputStream;
import java.util.concurrent.Executors;
import java.security.KeyPairGenerator;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class GoogleReportingClientTest {
    private final ObjectMapper json=new ObjectMapper();
    @Test void credentialsOnlyAcceptFixedServiceAccountEndpointAndReadOnlyScopes() throws Exception {
        var value=json.readTree("{\"type\":\"service_account\",\"token_uri\":\"https://evil.invalid/token\",\"client_email\":\"test@fixture.iam.gserviceaccount.com\",\"private_key\":\"-----BEGIN PRIVATE KEY-----secret\"}");
        assertThrows(GoogleReportingClient.Failure.class,()->GoogleReportingClient.validateCredentialDocument(value));
        assertEquals(Set.of("https://www.googleapis.com/auth/analytics.readonly","https://www.googleapis.com/auth/webmasters.readonly"),Set.copyOf(GoogleReportingClient.SCOPES));
        var client=new GoogleReportingClient(json,"secret that must not appear",mock(HttpClient.class),Clock.systemUTC());
        var error=assertThrows(GoogleReportingClient.Failure.class,()->client.post("https://analyticsdata.googleapis.com/v1beta/properties/1:runReport",Map.of()));
        assertEquals("INVALID_CONFIGURATION",error.getMessage()); assertNull(error.getCause());
        assertFalse(error.toString().contains("must not appear"));
    }
    @Test void oneFailedTokenRequestIsBoundedAndSharedByAllProviderQueries() throws Exception {
        HttpClient http=mock(HttpClient.class); AtomicInteger calls=new AtomicInteger();
        HttpResponse<byte[]> response=response(400,"{\"error\":\"invalid_grant\",\"error_description\":\"sensitive-upstream-value\"}".getBytes(StandardCharsets.UTF_8));
        when(http.send(any(HttpRequest.class),any(HttpResponse.BodyHandler.class))).thenAnswer(invocation->{
            HttpRequest request=invocation.getArgument(0); calls.incrementAndGet();
            assertEquals("https://oauth2.googleapis.com/token",request.uri().toString());
            assertEquals(Duration.ofSeconds(12),request.timeout().orElseThrow());
            return response;
        });
        var clock=new MutableClock();
        var client=new GoogleReportingClient(json,credential(),http,clock);
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            var tasks=java.util.stream.IntStream.range(0,6).mapToObj(i->executor.submit(()->{
                var error=assertThrows(GoogleReportingClient.Failure.class,()->client.post("https://analyticsdata.googleapis.com/v1beta/properties/1:runReport",Map.of()));
                assertEquals("AUTH_FAILED",error.getMessage()); assertFalse(error.toString().contains("sensitive-upstream"));
            })).toList();
            for(var task:tasks) task.get();
        }
        assertEquals(1,calls.get());
        clock.now=clock.now.plusSeconds(31);
        assertThrows(GoogleReportingClient.Failure.class,()->client.post("https://analyticsdata.googleapis.com/v1beta/properties/1:runReport",Map.of()));
        assertEquals(2,calls.get(),"retry only after the shared auth cooldown");
    }
    @Test void cachedBearerIsServerOnlyAndGoogleApiCallsHaveTimeoutAndNoArbitraryHost() throws Exception {
        HttpClient http=mock(HttpClient.class); AtomicInteger tokenCalls=new AtomicInteger(),apiCalls=new AtomicInteger();
        when(http.send(any(HttpRequest.class),any(HttpResponse.BodyHandler.class))).thenAnswer(invocation->{
            HttpRequest request=invocation.getArgument(0);
            assertEquals(Duration.ofSeconds(12),request.timeout().orElseThrow());
            if(request.uri().getHost().equals("oauth2.googleapis.com")) {
                tokenCalls.incrementAndGet();
                var buffer=new ByteArrayOutputStream();
                try(var gzip=new GZIPOutputStream(buffer)) { gzip.write("{\"access_token\":\"test-only-bearer\",\"expires_in\":3600,\"token_type\":\"Bearer\"}".getBytes(StandardCharsets.UTF_8)); }
                HttpResponse<byte[]> tokenResponse=response(200,buffer.toByteArray());
                when(tokenResponse.headers()).thenReturn(HttpHeaders.of(Map.of("content-type",List.of("application/json"),"content-encoding",List.of("gzip")),(a,b)->true));
                return tokenResponse;
            }
            apiCalls.incrementAndGet(); assertEquals("Bearer test-only-bearer",request.headers().firstValue("Authorization").orElseThrow());
            return response(403,"private provider details");
        });
        var client=new GoogleReportingClient(json,credential(),http,Clock.systemUTC());
        for(int i=0;i<2;i++) assertEquals("ACCESS_DENIED",assertThrows(GoogleReportingClient.Failure.class,
                ()->client.post("https://analyticsdata.googleapis.com/v1beta/properties/1:runReport",Map.of())).code);
        assertEquals(1,tokenCalls.get()); assertEquals(2,apiCalls.get());
        assertThrows(GoogleReportingClient.Failure.class,()->client.post("https://attacker.invalid/token",Map.of()));
        assertEquals(2,apiCalls.get());
    }
    private String credential() throws Exception {
        var generator=KeyPairGenerator.getInstance("RSA"); generator.initialize(2048);
        String key="-----BEGIN PRIVATE KEY-----\n"+Base64.getMimeEncoder(64,new byte[]{'\n'}).encodeToString(generator.generateKeyPair().getPrivate().getEncoded())+"\n-----END PRIVATE KEY-----\n";
        return json.writeValueAsString(Map.of("type","service_account","token_uri","https://oauth2.googleapis.com/token",
                "client_email","test@fixture.iam.gserviceaccount.com","client_id","123","private_key",key,"private_key_id","fixture"));
    }
    private static final class MutableClock extends Clock {
        Instant now=Instant.now();
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return Clock.fixed(now,zone); }
        public Instant instant() { return now; }
    }
    @SuppressWarnings("unchecked") private <T> HttpResponse<T> response(int code,T body) {
        HttpResponse<T> response=mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(code); when(response.body()).thenReturn(body);
        when(response.headers()).thenReturn(HttpHeaders.of(Map.of("content-type",List.of("application/json")),(a,b)->true)); return response;
    }
}
