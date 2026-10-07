package be.enrosed.sales.adapter.in.rest;

import be.enrosed.sales.application.StaffWebOrderRevision;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.ext.Provider;

import java.util.regex.Pattern;

/**
 * Carries the revision of the website order a staff screen works from, sent
 * as {@code ?webOrderRevision=n} on every mutation of a sales document, to
 * where the gate reads it. Nothing is decided here: the comparison runs after
 * the locks, in {@link be.enrosed.sales.application.WebOrders}. A query
 * parameter rather than a header, so the allowed CORS headers stay as they are.
 */
@Provider
public class WebOrderRevisionFilter implements ContainerRequestFilter {
    static final String PARAMETER = "webOrderRevision";
    private static final String PATH = "/api/sales-orders/";
    private static final Pattern REVISION = Pattern.compile("\\d{1,9}");

    @Inject
    StaffWebOrderRevision revision;

    @Override
    public void filter(ContainerRequestContext request) {
        String path = request.getUriInfo().getPath();
        if (path == null) return;
        if (!path.startsWith("/")) path = "/" + path;
        if (!path.startsWith(PATH)) return;
        String value = request.getUriInfo().getQueryParameters().getFirst(PARAMETER);
        /* Anything that is not a plain number is no revision at all. */
        if (value != null && REVISION.matcher(value).matches()) revision.set(Integer.valueOf(value));
    }
}
