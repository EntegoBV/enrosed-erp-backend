package be.enrosed.sourcing.adapter.out.market;

import be.enrosed.sourcing.domain.MarketSourceStatus;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MarketSourceRefreshJobTest {

    @Test
    void oneFailingConnectorDoesNotBlockTheOthers() {
        AtomicInteger refreshes = new AtomicInteger();
        MarketSourceFetcher first = source(refreshes, false);
        MarketSourceFetcher failing = source(refreshes, true);
        MarketSourceFetcher last = source(refreshes, false);

        new MarketSourceRefreshJob(List.of(first, failing, last)).refreshDaily();

        assertEquals(3, refreshes.get());
    }

    private static MarketSourceFetcher source(AtomicInteger refreshes, boolean fail) {
        return new MarketSourceFetcher() {
            @Override
            public void refreshIfDue() {
                refreshes.incrementAndGet();
                if (fail) throw new IllegalStateException("provider unavailable");
            }

            @Override
            public MarketSourceStatus status() {
                return null;
            }
        };
    }
}
