package be.enrosed.publicform;

import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.net.SocketAddress;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ClientIdentityResolverTest {
    @Test
    void railwayHeaderIsUsedOnlyWithBothExplicitTrustSignals() {
        HttpServerRequest request = request("198.51.100.44", "10.0.0.2");

        assertEquals("198.51.100.44", new ClientIdentityResolver(true, true).resolve(request));
        assertEquals("10.0.0.2", new ClientIdentityResolver(false, true).resolve(request));
        assertEquals("10.0.0.2", new ClientIdentityResolver(true, false).resolve(request));
    }

    @Test
    void invalidOrForwardedListsNeverOverrideTheSocketPeer() {
        assertEquals("10.0.0.2", new ClientIdentityResolver(true, true)
                .resolve(request("198.51.100.1, 203.0.113.1", "10.0.0.2")));
        assertEquals("10.0.0.2", new ClientIdentityResolver(true, true)
                .resolve(request("not-an-ip", "10.0.0.2")));
    }

    @Test
    void ipv6IdentityIsCoarsenedToItsNetworkPrefix() {
        assertEquals("2001:db8:abcd:12:0:0:0:0",
                ClientIdentityResolver.normalizeLiteral("2001:db8:abcd:12:1111:2222:3333:4444"));
        assertNull(ClientIdentityResolver.normalizeLiteral("999.1.1.1"));
    }

    private static HttpServerRequest request(String header, String socket) {
        HttpServerRequest request = mock(HttpServerRequest.class);
        when(request.getHeader("X-Real-IP")).thenReturn(header);
        when(request.remoteAddress()).thenReturn(SocketAddress.inetSocketAddress(443, socket));
        return request;
    }
}
