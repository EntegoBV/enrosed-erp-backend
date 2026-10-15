package be.enrosed.finance.forecast;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * What the owner chose for one container in the Kasprognose: how it sells
 * (price, cost basis, pace, payment days, season), whether it counts, the
 * expected ship date of an ordered container and the planned order day of a
 * concept. Kept apart from the order: the order's PUT replaces the whole
 * record, so a column it does not know would be nulled by every older ERP
 * build. Explicit snake_case columns because the PostgreSQL migration creates
 * the table before startup; the modes are plain text, NOT NULL lives in the
 * migration only, and there is no foreign key: an order in the trash keeps
 * its row for a restore.
 */
@Entity(name = "PurchaseOrderForecastEntity")
@Table(name = "purchase_order_forecast")
public class PurchaseOrderForecastEntity {
    @Id @Column(name = "order_id") public Long orderId;
    /** MARKUP_PCT, EUR_PER_UNIT, LIST or HISTORIC. */
    @Column(name = "price_mode", length = 16) public String priceMode;
    @Column(name = "markup_pct", precision = 7, scale = 2) public BigDecimal markupPct;
    @Column(name = "markup_eur_per_unit", precision = 19, scale = 4) public BigDecimal markupEurPerUnit;
    /** EXTERNAL or PRICING (with the Enrosed cost). */
    @Column(name = "cost_basis", length = 16) public String costBasis;
    @Column(name = "sell_weeks") public Integer sellWeeks;
    @Column(name = "payment_days") public Integer paymentDays;
    @Column(name = "seasonal") public Boolean seasonal;
    @Column(name = "included") public Boolean included;
    @Column(name = "expected_ship_on") public LocalDate expectedShipOn;
    @Column(name = "order_date_override") public LocalDate orderDateOverride;
    @Column(name = "updated_at") public Instant updatedAt;
    @Column(name = "actor", length = 255) public String actor;
}
