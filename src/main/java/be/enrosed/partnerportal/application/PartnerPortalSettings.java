package be.enrosed.partnerportal.application;

import be.enrosed.catalog.application.StockService;
import be.enrosed.catalog.domain.StockLocation;
import be.enrosed.partnerportal.adapter.out.persistence.PartnerPortalRepositories;
import be.enrosed.partnerportal.adapter.out.persistence.PartnerPortalSettingsEntity;
import be.enrosed.partnerportal.domain.PartnerPortalRules;
import jakarta.enterprise.context.ApplicationScoped;

import java.math.BigDecimal;

/**
 * The portal settings of a partner as they apply: the stored percents or the defaults, the
 * stored stock location or MAIN (a location deleted since reads as MAIN too).
 */
@ApplicationScoped
public class PartnerPortalSettings {

    /** stored* are null when the default applies; the rest is what applies. */
    public record Effective(long customerId, BigDecimal storedCommissionPct, BigDecimal storedTransportPct,
                            Long storedLocationId, BigDecimal auctionCommissionPct, BigDecimal auctionTransportPct,
                            StockLocation location) {}

    private final PartnerPortalRepositories.Settings settings;
    private final StockService stock;

    public PartnerPortalSettings(PartnerPortalRepositories.Settings settings, StockService stock) {
        this.settings = settings;
        this.stock = stock;
    }

    public Effective of(long customerId) {
        return of(customerId, settings.findById(customerId));
    }

    public Effective of(long customerId, PartnerPortalSettingsEntity row) {
        BigDecimal commission = row == null ? null : row.auctionCommissionPct;
        BigDecimal transport = row == null ? null : row.auctionTransportPct;
        Long locationId = row == null ? null : row.locationId;
        return new Effective(customerId, commission, transport, locationId,
                PartnerPortalRules.commissionOrDefault(commission), PartnerPortalRules.transportOrDefault(transport),
                location(locationId));
    }

    private StockLocation location(Long locationId) {
        if (locationId != null) {
            for (StockLocation location : stock.locations()) {
                if (locationId.equals(location.id())) return location;
            }
        }
        return stock.mainLocation();
    }
}
