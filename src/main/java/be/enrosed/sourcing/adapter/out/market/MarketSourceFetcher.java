package be.enrosed.sourcing.adapter.out.market;

import be.enrosed.sourcing.domain.MarketSourceStatus;

/** One licensed provider connector with cache-first failure behaviour. */
public interface MarketSourceFetcher {
    void refreshIfDue();
    MarketSourceStatus status();
}
