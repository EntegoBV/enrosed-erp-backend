package be.enrosed.catalog.application;

import be.enrosed.catalog.application.ProductPhotoExport.Request;
import jakarta.enterprise.context.ApplicationScoped;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Base64;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Short-lived download links for the photo ZIP.
 *
 * The administrator prepares an export with an authenticated POST; the
 * browser then downloads it natively from a URL that carries this token
 * instead of an Authorization header, so hundreds of megabytes never pass
 * through JavaScript memory. The token itself is the authorization: 256
 * random bits, valid for 15 minutes, reusable within that window so an
 * interrupted download can simply be retried.
 *
 * Tickets live in memory only. That assumes a single backend instance,
 * which is how Railway runs this service; a second instance (or a restart)
 * simply answers 404 and the administrator prepares the ZIP again. Move the
 * tickets to the database before scaling out.
 */
@ApplicationScoped
public class PhotoExportTokenStore {

    public static final Duration VALIDITY = Duration.ofMinutes(15);
    /** Bounds memory even if someone keeps preparing exports; the oldest ticket gives way. */
    static final int MAX_LIVE_TICKETS = 100;
    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9_-]{43}");

    private final SecureRandom random = new SecureRandom();
    private final Map<String, Ticket> tickets = new ConcurrentHashMap<>();
    private final Clock clock;

    public PhotoExportTokenStore() {
        this(Clock.systemUTC());
    }

    PhotoExportTokenStore(Clock clock) {
        this.clock = clock;
    }

    /** What a token unlocks: the request as prepared, by whom, and until when. */
    public record Ticket(String token, Request request, String principal, LocalDate exportDate,
                         Instant issuedAt, Instant expiresAt) {}

    public Ticket issue(Request request, String principal, LocalDate exportDate) {
        Instant now = clock.instant();
        purge(now);
        byte[] secret = new byte[32];
        random.nextBytes(secret);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
        Ticket ticket = new Ticket(token, request, principal, exportDate, now, now.plus(VALIDITY));
        tickets.put(token, ticket);
        while (tickets.size() > MAX_LIVE_TICKETS) {
            tickets.values().stream().min(Comparator.comparing(Ticket::issuedAt))
                    .ifPresent(oldest -> tickets.remove(oldest.token()));
        }
        return ticket;
    }

    /** The live ticket for this token; empty when unknown, malformed or expired. */
    public Optional<Ticket> find(String token) {
        if (token == null || !TOKEN.matcher(token).matches()) return Optional.empty();
        Instant now = clock.instant();
        Ticket ticket = tickets.get(token);
        if (ticket == null) return Optional.empty();
        if (!now.isBefore(ticket.expiresAt())) {
            tickets.remove(token, ticket);
            return Optional.empty();
        }
        return Optional.of(ticket);
    }

    int liveTickets() {
        purge(clock.instant());
        return tickets.size();
    }

    private void purge(Instant now) {
        tickets.values().removeIf(ticket -> !now.isBefore(ticket.expiresAt()));
    }
}
