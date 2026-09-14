package be.enrosed.analytics;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.io.ByteArrayOutputStream;
import java.net.URLDecoder;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPOutputStream;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class GoogleReportingUserCredentialsTest {
    private final ObjectMapper json=new ObjectMapper();
    private static final String ENDPOINT="https://analyticsdata.googleapis.com/v1beta/properties/554014865:runRealtimeReport";
    private static final String CLIENT_ID="123-fixture.apps.googleusercontent.com";
    private static final String CLIENT_SECRET="fixture-secret&only";
    private static final String REFRESH_TOKEN="fixture-refresh+/=only";

    @Test void internalOAuthRefreshUsesExactEncodedFieldsFixedEndpointAndCachedGzipBearer() throws Exception {
        HttpClient http=mock(HttpClient.class); var tokenCalls=new AtomicInteger(); var reportCalls=new AtomicInteger();
        when(http.send(any(HttpRequest.class),any(HttpResponse.BodyHandler.class))).thenAnswer(invocation->{
            HttpRequest request=invocation.getArgument(0);
            assertEquals(Duration.ofSeconds(12),request.timeout().orElseThrow());
            if(request.uri().getHost().equals("oauth2.googleapis.com")) {
                tokenCalls.incrementAndGet(); assertEquals("https://oauth2.googleapis.com/token",request.uri().toString());
                assertEquals("POST",request.method());
                Map<String,String> fields=form(request);
                assertEquals(Map.of("grant_type","refresh_token","client_id",CLIENT_ID,"client_secret",CLIENT_SECRET,
                        "refresh_token",REFRESH_TOKEN),fields,"refresh neither impersonates a user nor requests broader scopes");
                byte[] bytes;
                try(var buffer=new ByteArrayOutputStream()) {
                    try(var gzip=new GZIPOutputStream(buffer)) {
                        gzip.write("{\"access_token\":\"fixture-oauth-access\",\"expires_in\":3600,\"token_type\":\"Bearer\"}".getBytes(StandardCharsets.UTF_8));
                    }
                    bytes=buffer.toByteArray();
                }
                HttpResponse<byte[]> token=response(200,bytes);
                when(token.headers()).thenReturn(HttpHeaders.of(Map.of("content-type",List.of("application/json"),
                        "content-encoding",List.of("gzip")),(a,b)->true)); return token;
            }
            reportCalls.incrementAndGet();
            assertEquals("Bearer fixture-oauth-access",request.headers().firstValue("Authorization").orElseThrow());
            assertEquals(ENDPOINT,request.uri().toString()); return response(200,"{\"metricHeaders\":[{\"name\":\"activeUsers\"}]}");
        });
        var client=new GoogleReportingClient(json,"",credential(),http,Clock.systemUTC());
        assertTrue(client.configured());
        assertTrue(client.post(ENDPOINT,Map.of()).has("metricHeaders"));
        client.post(ENDPOINT,Map.of());
        assertEquals(1,tokenCalls.get()); assertEquals(2,reportCalls.get());
    }

    @Test void oauthDenialHasOneSharedRetryWindowAndNeverExposesProviderOrCredentialContent() throws Exception {
        HttpClient http=mock(HttpClient.class); var calls=new AtomicInteger(); var clock=new MutableClock();
        when(http.send(any(HttpRequest.class),any(HttpResponse.BodyHandler.class))).thenAnswer(invocation->{
            calls.incrementAndGet();
            return response(400,("{\"error\":\"invalid_grant\",\"error_description\":\""+REFRESH_TOKEN+"\"}").getBytes(StandardCharsets.UTF_8));
        });
        var client=new GoogleReportingClient(json,"",credential(),http,clock);
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            var pending=java.util.stream.IntStream.range(0,6).mapToObj(i->executor.submit(()->{
                var error=assertThrows(GoogleReportingClient.Failure.class,()->client.post(ENDPOINT,Map.of()));
                assertEquals("AUTH_FAILED",error.code); assertNull(error.getCause());
                assertFalse(error.toString().contains(REFRESH_TOKEN)); assertFalse(error.toString().contains(CLIENT_SECRET));
            })).toList();
            for(var task:pending) task.get();
        }
        assertEquals(1,calls.get());
        clock.now=clock.now.plusSeconds(29);
        assertThrows(GoogleReportingClient.Failure.class,()->client.post(ENDPOINT,Map.of())); assertEquals(1,calls.get());
        clock.now=clock.now.plusSeconds(2);
        assertThrows(GoogleReportingClient.Failure.class,()->client.post(ENDPOINT,Map.of())); assertEquals(2,calls.get());
    }

    @Test void ambiguousCredentialsAndInvalidUserEnvelopesAreRefusedBeforeNetwork() throws Exception {
        HttpClient http=mock(HttpClient.class);
        var both=new GoogleReportingClient(json,"another-server-secret",credential(),http,Clock.systemUTC());
        assertTrue(both.configured());
        assertEquals("INVALID_CONFIGURATION",assertThrows(GoogleReportingClient.Failure.class,()->both.post(ENDPOINT,Map.of())).code);
        var original=json.readTree(credential());
        for(String field:List.of("type","client_id","client_secret","refresh_token","token_uri")) {
            var invalid=original.deepCopy();
            ((com.fasterxml.jackson.databind.node.ObjectNode)invalid).put(field,field.equals("token_uri")?"https://attacker.invalid/token":"");
            var client=new GoogleReportingClient(json,"",json.writeValueAsString(invalid),http,Clock.systemUTC());
            var failure=assertThrows(GoogleReportingClient.Failure.class,()->client.post(ENDPOINT,Map.of()));
            assertEquals("INVALID_CONFIGURATION",failure.code); assertNull(failure.getCause());
        }
        verifyNoInteractions(http);
    }

    @Test void tokenRedirectIsRefusedWithoutSendingCredentialsToAnotherEndpoint() throws Exception {
        HttpClient http=mock(HttpClient.class);
        HttpResponse<byte[]> redirect=response(302,new byte[0]);
        when(redirect.headers()).thenReturn(HttpHeaders.of(Map.of("location",List.of("https://attacker.invalid/token")),(a,b)->true));
        when(http.send(any(HttpRequest.class),any(HttpResponse.BodyHandler.class))).thenReturn((HttpResponse)redirect);
        var client=new GoogleReportingClient(json,"",credential(),http,Clock.systemUTC());
        assertEquals("AUTH_FAILED",assertThrows(GoogleReportingClient.Failure.class,()->client.post(ENDPOINT,Map.of())).code);
        verify(http,times(1)).send(argThat(request->request.uri().toString().equals("https://oauth2.googleapis.com/token")),any(HttpResponse.BodyHandler.class));
    }

    private String credential() throws Exception {
        return json.writeValueAsString(Map.of("type","authorized_user","client_id",CLIENT_ID,
                "client_secret",CLIENT_SECRET,"refresh_token",REFRESH_TOKEN));
    }
    private static Map<String,String> form(HttpRequest request) throws Exception {
        var bytes=new ByteArrayOutputStream(); var done=new CompletableFuture<byte[]>();
        request.bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<ByteBuffer>() {
            public void onSubscribe(Flow.Subscription subscription) { subscription.request(Long.MAX_VALUE); }
            public void onNext(ByteBuffer item) { byte[] buffer=new byte[item.remaining()]; item.get(buffer); bytes.writeBytes(buffer); }
            public void onError(Throwable failure) { done.completeExceptionally(failure); }
            public void onComplete() { done.complete(bytes.toByteArray()); }
        });
        String encoded=new String(done.get(5,TimeUnit.SECONDS),StandardCharsets.UTF_8);
        Map<String,String> result=new LinkedHashMap<>();
        for(String part:encoded.split("&")) {
            String[] pair=part.split("=",2);
            result.put(URLDecoder.decode(pair[0],StandardCharsets.UTF_8),URLDecoder.decode(pair[1],StandardCharsets.UTF_8));
        }
        return result;
    }
    private static final class MutableClock extends Clock {
        Instant now=Instant.now(); public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return Clock.fixed(now,zone); } public Instant instant() { return now; }
    }
    @SuppressWarnings("unchecked") private <T> HttpResponse<T> response(int code,T body) {
        HttpResponse<T> response=mock(HttpResponse.class); when(response.statusCode()).thenReturn(code); when(response.body()).thenReturn(body);
        when(response.headers()).thenReturn(HttpHeaders.of(Map.of("content-type",List.of("application/json")),(a,b)->true)); return response;
    }
}
