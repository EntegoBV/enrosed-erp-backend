package be.enrosed.partnerportal.adapter.out.persistence;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Portal settings of one partner customer. A null percent reads as the default (4.50 commission,
 * 5.50 transport), a null location as MAIN. The row doubles as the partner lock: every melding,
 * void and invoice group of this partner takes it FOR UPDATE first (created when missing).
 */
@Entity
@Table(name = "partner_portal_settings")
public class PartnerPortalSettingsEntity extends PanacheEntityBase {
    @Id
    @Column(name = "customer_id")
    public Long customerId;

    @Column(name = "auction_commission_pct", precision = 7, scale = 4)
    public BigDecimal auctionCommissionPct;

    @Column(name = "auction_transport_pct", precision = 7, scale = 4)
    public BigDecimal auctionTransportPct;

    @Column(name = "location_id")
    public Long locationId;

    @Column(name = "updated_at")
    public Instant updatedAt;

    @Column(name = "updated_by", length = 64)
    public String updatedBy;
}
