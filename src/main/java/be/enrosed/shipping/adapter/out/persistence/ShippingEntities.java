package be.enrosed.shipping.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Carrier tariff rows.
 *
 * A tier's zone prices live in one CSV column aligned with the lane's zone
 * positions. The ladder is reference data that is replaced as a whole when
 * a new rate sheet arrives; per-cell rows would triple the table count for
 * no queryable gain.
 */
public final class ShippingEntities {

    private ShippingEntities() {}

    @Entity
    @Table(name = "carrier")
    public static class CarrierEntity extends io.quarkus.hibernate.orm.panache.PanacheEntityBase {
        @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
        public Long id;
        @Column(nullable = false, unique = true)
        public String name;
        public String fullName;
        @Column(nullable = false)
        @org.hibernate.annotations.ColumnDefault("true")
        public boolean active = true;
        @Column(precision = 19, scale = 2)
        public BigDecimal dieselSurchargePct;
        public LocalDate validUntil;
        @Column(length = 4000)
        public String notes;
    }

    @Entity
    @Table(name = "carrier_lane")
    public static class CarrierLaneEntity extends io.quarkus.hibernate.orm.panache.PanacheEntityBase {
        @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
        public Long id;
        @Column(nullable = false)
        public Long carrierId;
        @Column(nullable = false, length = 2)
        public String countryCode;
        @Column(precision = 19, scale = 2)
        public BigDecimal surchargePct;
        @Column(precision = 19, scale = 2)
        public BigDecimal surchargeFixedEur;
        @Column(length = 2000)
        public String surchargeNote;
    }

    @Entity
    @Table(name = "carrier_zone")
    public static class CarrierZoneEntity extends io.quarkus.hibernate.orm.panache.PanacheEntityBase {
        @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
        public Long id;
        @Column(nullable = false)
        public Long laneId;
        public String name;
        @Column(length = 2000)
        public String postcodes;
        public int position;
    }

    @Entity
    @Table(name = "carrier_tier")
    public static class CarrierTierEntity extends io.quarkus.hibernate.orm.panache.PanacheEntityBase {
        @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
        public Long id;
        @Column(nullable = false)
        public Long laneId;
        @Column(precision = 19, scale = 2)
        public BigDecimal epMax;
        @Column(precision = 19, scale = 2)
        public BigDecimal bpMax;
        @Column(precision = 19, scale = 2)
        public BigDecimal ldmMax;
        @Column(precision = 19, scale = 2)
        public BigDecimal kgMax;
        public int position;
        /** Zone prices, comma-separated, aligned with the lane's zone positions. */
        @Column(length = 4000)
        public String prices;
    }
}
