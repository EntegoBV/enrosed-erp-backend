package be.enrosed.sourcing.adapter.out.persistence;

import be.enrosed.shared.Currency;
import be.enrosed.sourcing.domain.Allocation;
import be.enrosed.sourcing.domain.PurchaseOrderStatus;
import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

public final class SourcingEntities {

    private SourcingEntities() {}

    @Entity
    @Table(name = "supplier")
    public static class SupplierEntity {
        @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
        public Long id;
        public String name;
        public String country;
        public String city;
        public String contact;
        public String email;
        public String phone;
        @Enumerated(EnumType.STRING)
        public Currency currency = Currency.USD;
        public String incoterm;
        public String portOfLoading;
        public int leadTimeDays;
        @Column(length = 2000)
        public String notes;
    }

    @Entity
    @Table(name = "purchase_order")
    public static class PurchaseOrderEntity {
        @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
        public Long id;
        @Column(unique = true)
        public String number;
        /** Nickname next to the number, e.g. "voor Frans". */
        public String alias;
        public Long supplierId;
        public LocalDate orderDate;
        @Enumerated(EnumType.STRING)
        public PurchaseOrderStatus status = PurchaseOrderStatus.CONCEPT;
        public String containerType;

        @Column(precision = 19, scale = 6) public BigDecimal cnyToUsd;
        @Column(precision = 19, scale = 6) public BigDecimal usdToEurGoods;
        @Column(precision = 19, scale = 6) public BigDecimal usdToEurTransport;

        @Column(precision = 19, scale = 2) public BigDecimal freightUsd;
        /* Costs up to the ship in China - count towards the customs value. */
        @Column(precision = 19, scale = 2) public BigDecimal originCosts;
        @Enumerated(EnumType.STRING)
        public Currency originCurrency = Currency.USD;
        /* Costs from the port of discharge - fall outside the customs value. */
        @Column(precision = 19, scale = 2) public BigDecimal destinationCostsEur;

        @Column(precision = 19, scale = 4) public BigDecimal defaultDutyRatePct;
        @Column(precision = 19, scale = 2) public BigDecimal extraRevenueEur;

        @Enumerated(EnumType.STRING) public Allocation allocFreight = Allocation.CBM;
        @Enumerated(EnumType.STRING) public Allocation allocOrigin = Allocation.CBM;
        @Enumerated(EnumType.STRING) public Allocation allocDestination = Allocation.CBM;
        @Enumerated(EnumType.STRING) public Allocation allocExtra = Allocation.PIECES;

        /** Port of arrival; drives the destination-cost labels. */
        public String destinationPort = "Rotterdam";

        @Column(length = 2000)
        public String notes;

        @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
        @OrderBy("id ASC")
        public List<PurchaseOrderLineEntity> lines = new ArrayList<>();
    }

    @Entity
    @Table(name = "purchase_order_line")
    public static class PurchaseOrderLineEntity {
        @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
        public Long id;
        @ManyToOne(fetch = FetchType.LAZY)
        @JoinColumn(name = "order_id")
        public PurchaseOrderEntity order;
        public Long productId;
        public int quantity;
        /** Quantity at the moment of ordering; null for lines added later. */
        public Integer orderedQuantity;
        @Column(precision = 19, scale = 6) public BigDecimal exwPrice;
        @Enumerated(EnumType.STRING) public Currency exwCurrency;
        @Column(precision = 19, scale = 6) public BigDecimal extraUnitCost;
    }
}
