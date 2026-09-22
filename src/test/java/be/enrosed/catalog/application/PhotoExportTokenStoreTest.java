package be.enrosed.catalog.application;

import be.enrosed.catalog.application.ProductPhotoExport.PhotoSelection;
import be.enrosed.catalog.application.ProductPhotoExport.Request;
import be.enrosed.catalog.application.ProductPhotoExport.Scope;
import be.enrosed.shared.Language;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PhotoExportTokenStoreTest {

    private static final Request REQUEST = new Request(Scope.WEBSITE, PhotoSelection.WEBSITE, Language.FR);
    private static final LocalDate DAY = LocalDate.of(2026, 9, 22);

    @Test
    void aTokenIs256RandomUrlSafeBitsAndCarriesTheRequest() {
        PhotoExportTokenStore store = new PhotoExportTokenStore(new MovableClock());
        var ticket = store.issue(REQUEST, "emre", DAY);

        assertEquals(43, ticket.token().length(), "32 bytes, unpadded base64url");
        assertTrue(ticket.token().matches("[A-Za-z0-9_-]{43}"));
        assertEquals(REQUEST, store.find(ticket.token()).orElseThrow().request());
        assertEquals("emre", store.find(ticket.token()).orElseThrow().principal());
        assertEquals(DAY, ticket.exportDate());
        assertNotEquals(ticket.token(), store.issue(REQUEST, "emre", DAY).token());
    }

    @Test
    void aTokenWorksRepeatedlyForFifteenMinutesAndThenNeverAgain() {
        MovableClock clock = new MovableClock();
        PhotoExportTokenStore store = new PhotoExportTokenStore(clock);
        var ticket = store.issue(REQUEST, "berat", DAY);
        assertEquals(clock.instant().plus(Duration.ofMinutes(15)), ticket.expiresAt());

        clock.advance(Duration.ofMinutes(5));
        assertTrue(store.find(ticket.token()).isPresent(), "first download");
        assertTrue(store.find(ticket.token()).isPresent(), "a retry within the window");

        clock.advance(Duration.ofMinutes(10).minusSeconds(1));
        assertTrue(store.find(ticket.token()).isPresent(), "one second before expiry");

        clock.advance(Duration.ofSeconds(1));
        assertTrue(store.find(ticket.token()).isEmpty(), "expired exactly at 15 minutes");
        clock.rewind(Duration.ofMinutes(1));
        assertTrue(store.find(ticket.token()).isEmpty(), "an expired ticket is forgotten, not revived");
        assertEquals(0, store.liveTickets());
    }

    @Test
    void unknownOrMalformedTokensFindNothing() {
        PhotoExportTokenStore store = new PhotoExportTokenStore(new MovableClock());
        store.issue(REQUEST, "emre", DAY);
        assertTrue(store.find(null).isEmpty());
        assertTrue(store.find("").isEmpty());
        assertTrue(store.find("../../etc/passwd").isEmpty());
        assertTrue(store.find("A".repeat(43)).isEmpty());
    }

    @Test
    void liveTicketsAreBoundedOldestFirst() {
        MovableClock clock = new MovableClock();
        PhotoExportTokenStore store = new PhotoExportTokenStore(clock);
        var first = store.issue(REQUEST, "emre", DAY);
        Set<String> tokens = new HashSet<>();
        for (int i = 0; i < PhotoExportTokenStore.MAX_LIVE_TICKETS; i++) {
            clock.advance(Duration.ofMillis(1));
            tokens.add(store.issue(REQUEST, "emre", DAY).token());
        }
        assertEquals(PhotoExportTokenStore.MAX_LIVE_TICKETS, store.liveTickets());
        assertTrue(store.find(first.token()).isEmpty(), "the oldest ticket gave way");
        assertTrue(tokens.stream().allMatch(token -> store.find(token).isPresent()));
    }

    /** A clock the test moves by hand. */
    private static final class MovableClock extends Clock {
        private Instant now = Instant.parse("2026-09-22T10:00:00Z");

        void advance(Duration duration) { now = now.plus(duration); }
        void rewind(Duration duration) { now = now.minus(duration); }

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
