package be.enrosed.finance.odoo;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OdooClientTest {
    static OdooConfiguration config() { return config("https://fixture.odoo.com"); }
    static OdooConfiguration config(String url) { return new OdooConfiguration(true,Optional.of(url),Optional.of("fixture"),Optional.of("test-only-key"),Optional.of(7L),Optional.empty()); }
    @Test void onlyReadMethodsUseTheConfiguredHttpsOriginAndCredentialsStayInHeaders() throws Exception {
        HttpClient http=mock(HttpClient.class);
        when(http.send(any(HttpRequest.class),any(HttpResponse.BodyHandler.class))).thenAnswer(call->{
            HttpRequest request=call.getArgument(0);
            assertEquals("https://fixture.odoo.com/json/2/res.company/search_read",request.uri().toString());
            assertEquals("Bearer test-only-key",request.headers().firstValue("Authorization").orElseThrow());
            assertEquals("fixture",request.headers().firstValue("X-Odoo-Database").orElseThrow());
            assertEquals(Duration.ofSeconds(30),request.timeout().orElseThrow());
            return response(200,"[]");
        });
        var client=new OdooClient(config(),new ObjectMapper(),http);
        assertTrue(client.search("res.company",List.of(),List.of("id"),1,"id asc").isEmpty());
        assertEquals("INVALID_REQUEST",assertThrows(OdooFailure.class,()->client.call("account.move","action_post",Map.of())).code);
        assertEquals("INVALID_REQUEST",assertThrows(OdooFailure.class,()->client.call("ir.config_parameter","search_read",Map.of())).code);
        verify(http,times(1)).send(any(HttpRequest.class),any(HttpResponse.BodyHandler.class));
    }
    @Test void refusedOrMalformedResponsesNeverExposeTheProviderBodyOrKey() throws Exception {
        HttpClient http=mock(HttpClient.class);
        var client=new OdooClient(config(),new ObjectMapper(),http);
        for(int status:List.of(401,403,404,429,500,302)) {
            var rejected=response(status,"secret bank/customer details test-only-key");
            when(http.send(any(HttpRequest.class),any(HttpResponse.BodyHandler.class))).thenReturn(rejected);
            var failure=assertThrows(OdooFailure.class,()->client.search("res.company",List.of(),List.of("id"),1,"id asc"));
            assertFalse(failure.toString().contains("secret"));assertNull(failure.getCause());assertFalse(failure.userMessage().contains("test-only-key"));
        }
        var malformed=response(200,"{\"traceback\":\"secret\"}");
        when(http.send(any(HttpRequest.class),any(HttpResponse.BodyHandler.class))).thenReturn(malformed);
        assertEquals("INVALID_RESPONSE",assertThrows(OdooFailure.class,()->client.search("res.company",List.of(),List.of("id"),1,"id asc")).code);
    }
    @Test void disabledMissingAndUnsafeConfigurationIsActionableWithoutNetwork() {
        assertEquals("DISABLED",new OdooConfiguration(false,Optional.empty(),Optional.empty(),Optional.empty(),Optional.empty(),Optional.empty()).problem());
        assertEquals("NOT_CONFIGURED",new OdooConfiguration(true,Optional.empty(),Optional.empty(),Optional.empty(),Optional.empty(),Optional.empty()).problem());
        for(String url:List.of("http://fixture.odoo.com","https://user:password@fixture.odoo.com","https://fixture.odoo.com/path","https://fixture.odoo.com?key=secret"))
            assertEquals("INVALID_CONFIGURATION",config(url).problem());
        assertEquals(config().sourceNamespace(),config("https://FIXTURE.odoo.com/").sourceNamespace());
    }
    @Test void outboxWritesRequireIndependentGatesAndCannotCallUnlistedMethods() {
        HttpClient http=mock(HttpClient.class);var json=new ObjectMapper();var client=new OdooClient(config(),json,http);
        assertEquals("OUTBOUND_DISABLED",assertThrows(OdooFailure.class,()->client.write("account.move","create",Map.of())).code);
        client.outbound=new OdooOutboundConfiguration(true,false,Optional.of(8L),Optional.of(9L),Optional.of("{}"),json);
        for(String method:List.of("create","action_send_and_print"))
            assertEquals("OUTBOUND_DISABLED",assertThrows(OdooFailure.class,()->client.write("account.move.send.wizard",method,Map.of())).code);
        assertEquals("OUTBOUND_DISABLED",assertThrows(OdooFailure.class,()->client.write("account.move","action_post",Map.of())).code);
        assertEquals("INVALID_REQUEST",assertThrows(OdooFailure.class,()->client.write("account.move","unlink",Map.of())).code);
        verifyNoInteractions(http);
    }
    @SuppressWarnings("unchecked") static HttpResponse<byte[]> response(int status,String body) {
        HttpResponse<byte[]> response=mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);when(response.body()).thenReturn(body.getBytes(StandardCharsets.UTF_8));
        return response;
    }
}
