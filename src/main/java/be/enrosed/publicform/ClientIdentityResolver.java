package be.enrosed.publicform;

import io.vertx.core.http.HttpServerRequest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.Optional;

/** Resolves one coarse client network without enabling spoofable global proxy forwarding. */
@ApplicationScoped
public class ClientIdentityResolver {
    private final boolean trustRailwayRealIp;
    private final boolean railwayEnvironment;

    @Inject
    public ClientIdentityResolver(
            @ConfigProperty(name = "enrosed.public-forms.trust-railway-x-real-ip",
                    defaultValue = "false") boolean trustRailwayRealIp,
            @ConfigProperty(name = "RAILWAY_ENVIRONMENT_ID") Optional<String> railwayEnvironmentId) {
        this.trustRailwayRealIp = trustRailwayRealIp;
        this.railwayEnvironment = railwayEnvironmentId.filter(value -> !value.isBlank()).isPresent();
    }

    ClientIdentityResolver(boolean trustRailwayRealIp, boolean railwayEnvironment) {
        this.trustRailwayRealIp = trustRailwayRealIp;
        this.railwayEnvironment = railwayEnvironment;
    }

    public String resolve(HttpServerRequest request) {
        if (request == null) return "unavailable";
        if (trustRailwayRealIp && railwayEnvironment) {
            String trusted = normalizeLiteral(request.getHeader("X-Real-IP"));
            if (trusted != null) return trusted;
        }
        try {
            String socket = request.remoteAddress() == null
                    ? null : request.remoteAddress().hostAddress();
            String normalized = normalizeLiteral(socket);
            return normalized == null ? "unavailable" : normalized;
        } catch (RuntimeException exception) {
            return "unavailable";
        }
    }

    static String normalizeLiteral(String value) {
        if (value == null || value.isBlank()) return null;
        String candidate = value.strip();
        if (candidate.indexOf(',') >= 0 || candidate.indexOf(' ') >= 0
                || candidate.indexOf('%') >= 0 || candidate.length() > 64) return null;
        if (candidate.matches("(?:[0-9]{1,3}\\.){3}[0-9]{1,3}")) {
            String[] parts = candidate.split("\\.");
            int[] octets = Arrays.stream(parts).mapToInt(Integer::parseInt).toArray();
            for (int octet : octets) if (octet > 255) return null;
            return octets[0] + "." + octets[1] + "." + octets[2] + "." + octets[3];
        }
        if (!candidate.contains(":") || !candidate.matches("[0-9A-Fa-f:.]+")) return null;
        try {
            InetAddress parsed = InetAddress.getByName(candidate);
            if (!(parsed instanceof Inet6Address)) return null;
            byte[] address = parsed.getAddress();
            Arrays.fill(address, 8, address.length, (byte) 0);
            return InetAddress.getByAddress(address).getHostAddress();
        } catch (UnknownHostException exception) {
            return null;
        }
    }
}
