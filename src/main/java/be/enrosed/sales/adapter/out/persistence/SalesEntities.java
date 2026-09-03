package be.enrosed.sales.adapter.out.persistence;

import be.enrosed.sales.domain.*;
import be.enrosed.shared.Language;
import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** The JPA entities of the sales side. */
public final class SalesEntities {

    private SalesEntities() {}

    @Entity
    @Table(name = "customer")
    public static class CustomerEntity {
        @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
        public Long id;
        public String company;
        public String contact;
        public String email;
        public String phone;
        public String vatNumber;
        public String countryCode;
        /**
         * Language this customer receives their quote and mail in.
         *
         * columnDefinition is there on purpose: without it Hibernate puts a
         * CHECK constraint on the column with exactly the languages that
         * exist at that moment. A new language then gets refused by the
         * database, and "update" as schema strategy does not widen such a
         * constraint. The enum here already guards the allowed values.
         */
        @Enumerated(EnumType.STRING)
        @Column(columnDefinition = "varchar(4)")
        public Language language = Language.NL;
        public String address;
        public String postalCode;
        public String city;
        public String incoterm;
        public String paymentTerms;
        @Column(length = 2000)
        public String notes;
        public LocalDate createdAt;
    }

    @Entity
    @Table(name = "country")
    public static class CountryEntity {
        @Id
        public String code;
        public String name;
        @Column(precision = 19, scale = 2) public BigDecimal minOrderValue;
        @Column(precision = 19, scale = 2) public BigDecimal freightPerPallet;
        @Column(precision = 19, scale = 2) public BigDecimal minFreight;
        @Column(precision = 19, scale = 2) public BigDecimal handling;
        @Column(precision = 19, scale = 2) public BigDecimal vatRatePct;
        public int transitDays;
        /** EU member state? Determines the VAT regime on a delivery. */
        public boolean euMember = true;
    }

    /** One-time country-policy rollouts; later dashboard edits must survive restarts. */
    @Entity
    @Table(name = "country_policy_version")
    public static class CountryPolicyVersionEntity {
        @Id
        @Column(length = 80)
        public String version;
        @Column(nullable = false)
        public Instant appliedAt;
    }

    @Entity
    @Table(name = "discount_tier",
            indexes = @Index(name = "idx_discount_tier_scope_product", columnList = "scope,product_id"),
            uniqueConstraints = @UniqueConstraint(
                    name = "uk_discount_tier_scope_product_threshold",
                    columnNames = {"scope", "product_id", "minQuantity"}))
    public static class DiscountTierEntity {
        @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
        public Long id;
        @Enumerated(EnumType.STRING)
        public TierScope scope;
        public int minQuantity;
        @Column(precision = 19, scale = 4)
        public BigDecimal percent;
        /** Null only for order tiers and inert legacy global line tiers. */
        @Column(name = "product_id")
        public Long productId;
    }

    @Entity
    @Table(name = "sales_order")
    public static class SalesOrderEntity {
        @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
        public Long id;
        @Column(unique = true)
        public String number;
        public Long customerId;
        public String countryCode;
        public LocalDate orderDate;
        public LocalDate validUntil;
        @Enumerated(EnumType.STRING)
        @Column(columnDefinition = "varchar(32)")
        public QuoteStatus status = QuoteStatus.CONCEPT;
        public String incoterm;
        /** Order-specific payment terms; empty means the customer's default. */
        public String paymentTerms;
        @Column(length = 2000)
        public String notes;

        @Enumerated(EnumType.STRING)
        public MarkupMode markupMode = MarkupMode.PRODUCT;
        @Column(precision = 19, scale = 4)
        public BigDecimal orderMarkupPct;

        /** Extra discount on top of the tiers, e.g. a fair discount. Optional. */
        @Column(precision = 19, scale = 4)
        public BigDecimal extraDiscountPct;
        public String extraDiscountLabel;

        /** Key the customer opens the quote with, no account needed. */
        @Column(unique = true, length = 64)
        public String portalToken;
        public Instant sentAt;
        public Instant viewedAt;
        /** How many times the customer opened the quote. */
        public int viewCount;
        public Instant decidedAt;
        public String signedByName;
        @Column(length = 4000)
        public String customerMessage;

        /** Notes for ourselves; never appear on the customer document. */
        @Column(length = 4000)
        public String internalNotes;

        /** Whether a delivery term was still owed, and whether it has been filled in. */
        @Enumerated(EnumType.STRING)
        @Column(length = 20)
        public DeliveryTermsState deliveryTerms = DeliveryTermsState.VOLLEDIG;

        /** Whether the freight still had to be determined, and whether that happened. */
        @Enumerated(EnumType.STRING)
        @Column(length = 20)
        public FreightState freight = FreightState.BEREKEND;

        /**
         * Freight we fill in ourselves instead of using the country rate.
         * Empty means: charge the rate.
         */
        @Column(precision = 19, scale = 2)
        public java.math.BigDecimal manualFreightEur;

        /** Null on legacy rows means PALLETS. */
        @Enumerated(EnumType.STRING)
        @Column(length = 24)
        public LoadMode loadMode;

        /** Null on legacy rows means EURO_120X80. */
        @Enumerated(EnumType.STRING)
        @Column(length = 24)
        public PalletProfile palletProfile;

        /** Total stack height, including the pallet base; null uses the configured default. */
        @Column(precision = 8, scale = 2)
        public BigDecimal maxPalletHeightCm;

        /** Null on legacy rows resolves from manualFreightEur. */
        @Enumerated(EnumType.STRING)
        @Column(columnDefinition = "varchar(24)")
        public FreightPricingStrategy freightPricingStrategy;

        @Column(precision = 19, scale = 4)
        public BigDecimal freightRatePerCbmEur;

        public Long freightCarrierId;
        @Column(precision = 19, scale = 2)
        public BigDecimal freightCarrierExtraEur;
        @Enumerated(EnumType.STRING)
        @Column(length = 16)
        public be.enrosed.sales.domain.DocumentType docType;
        public java.time.LocalDate invoiceDueDate;
        public java.time.Instant paidAt;
        public Long sourceQuoteId;
        public Instant goodsShippedAt;

        /** Immutable public collection snapshot for website requests. */
        @Column(name = "pickup_location_id")
        public Long pickupLocationId;
        @Column(name = "pickup_location_label")
        public String pickupLocationLabel;
        @Column(name = "pickup_location_address", length = 500)
        public String pickupLocationAddress;
        @Column(name = "pickup_location_instructions", length = 2000)
        public String pickupLocationInstructions;

        @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
        @OrderBy("id ASC")
        public List<SalesOrderLineEntity> lines = new ArrayList<>();

        @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
        @OrderBy("position ASC")
        public List<SalesPalletEntity> pallets = new ArrayList<>();
    }

    /** One hand-built pallet; position keeps the seller's ordering. */
    @Entity
    @Table(name = "sales_pallet")
    public static class SalesPalletEntity {
        @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
        public Long id;
        @ManyToOne(fetch = FetchType.LAZY)
        @JoinColumn(name = "order_id")
        public SalesOrderEntity order;
        public int position;
        public String label;
        /** Pallet type; "Europallet" unless the seller picked another. */
        public String palletType;
        /** Stacked height in cm; the transporter asks for it on every booking. */
        public Integer heightCm;

        @OneToMany(mappedBy = "pallet", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
        @OrderBy("id ASC")
        public List<SalesPalletItemEntity> items = new ArrayList<>();
    }

    @Entity(name = "SalesPalletItemEntity")
    @Table(name = "sales_pallet_item")
    public static class SalesPalletItemEntity {
        @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
        public Long id;
        @ManyToOne(fetch = FetchType.LAZY)
        @JoinColumn(name = "pallet_id")
        public SalesPalletEntity pallet;
        public Long productId;
        public int cartons;
    }

    /**
     * One step in the life of a quote.
     *
     * A separate table, not a child of the order: events are only appended
     * and never rewritten along with the order. As a child, every save of the
     * order would rewrite the whole series, and then one mistake is enough to
     * lose the history.
     */
    @Entity
    @Table(name = "quote_event", indexes = @Index(columnList = "salesOrderId"))
    public static class QuoteEventEntity {
        @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
        public Long id;

        public Long salesOrderId;

        @Enumerated(EnumType.STRING)
        @Column(columnDefinition = "varchar(32)", nullable = false)
        public QuoteEvent.Type type;

        public Instant at;
        public String actor;
        public boolean byCustomer;

        @Column(length = 500)
        public String summary;

        @Column(length = 4000)
        public String detail;
    }

    @Entity(name = "SalesOrderLineEntity")
    @Table(name = "sales_order_line")
    public static class SalesOrderLineEntity {
        @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
        public Long id;
        @ManyToOne(fetch = FetchType.LAZY)
        @JoinColumn(name = "order_id")
        public SalesOrderEntity order;
        public Long productId;
        public int quantity;
        @Column(precision = 19, scale = 4) public BigDecimal unitPriceEur;
        @Column(precision = 19, scale = 4) public BigDecimal manualDiscountPct;
        /** Hand-picked delivery week, e.g. "2026-W34". Optional. */
        public String deliveryWeek;
    }

    @Entity(name = "QuoteRevisionEntity")
    @Table(name = "quote_revision")
    public static class QuoteRevisionEntity {
        @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
        public Long id;
        public Long salesOrderId;
        @Enumerated(EnumType.STRING)
        public RevisionStatus status = RevisionStatus.IN_AFWACHTING;
        public Instant proposedAt;
        public String proposedBy;
        @Column(length = 4000)
        public String message;
        public Instant handledAt;
        public String handledBy;
        @Column(length = 4000)
        public String responseMessage;

        @OneToMany(mappedBy = "revision", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
        @OrderBy("id ASC")
        public List<QuoteRevisionLineEntity> lines = new ArrayList<>();
    }

    @Entity(name = "QuoteRevisionLineEntity")
    @Table(name = "quote_revision_line")
    public static class QuoteRevisionLineEntity {
        @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
        public Long id;
        @ManyToOne(fetch = FetchType.LAZY)
        @JoinColumn(name = "revision_id")
        public QuoteRevisionEntity revision;
        public Long productId;
        public int quantity;
        @Column(length = 1000)
        public String note;
    }
}
