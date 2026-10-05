package be.enrosed.account;

import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import org.jboss.resteasy.reactive.server.ServerResponseFilter;

/**
 * Every answer of the two staff resources of website logins is kept out of caches, the error
 * answers included: a refusal names an e-mail address or a customer just as a success does.
 * The resources set the header on what they build themselves; this covers what the exception
 * mappers build.
 */
public class StaffLoginNoStoreFilter {
    @ServerResponseFilter
    public void noStore(ContainerRequestContext request, ContainerResponseContext response) {
        String path = request.getUriInfo().getPath();
        if (path == null) return;
        if (!path.startsWith("/")) path = "/" + path;
        if (!isUnder(path, "/api/customer-logins") && !isUnder(path, "/api/login-requests")) return;
        if (response.getHeaders().containsKey("Cache-Control")) return;
        response.getHeaders().putSingle("Cache-Control", "no-store");
    }

    private static boolean isUnder(String path, String base) {
        return path.equals(base) || path.startsWith(base + "/");
    }
}
