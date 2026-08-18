package be.enrosed.sales.adapter.out.persistence;

import be.enrosed.sales.domain.*;
import be.enrosed.shared.Language;
import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** De JPA-entiteiten van de verkoopkant. */
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
         * Taal waarin deze klant zijn offerte en mail krijgt.
         *
         * columnDefinition staat er met opzet: zonder die zet Hibernate een
         * CHECK-constraint op de kolom met precies de talen die op dat moment
         * bestaan. Bij een nieuwe taal weigert de database dan de waarde, en
         * "update" als schemastrategie verbreedt zo'n constraint niet. De
         * toegestane waarden bewaakt de enum hier al.
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
        /** Lidstaat van de EU? Bepaalt het BTW-regime op een levering. */
        public boolean euMember = true;
    }

    @Entity
    @Table(name = "discount_tier")
    public static class DiscountTierEntity {
        @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
        public Long id;
        @Enumerated(EnumType.STRING)
        public TierScope scope;
        public int minQuantity;
        @Column(precision = 19, scale = 4)
        public BigDecimal percent;
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

        /** Extra korting bovenop de staffels, bv. een beurskorting. Optioneel. */
        @Column(precision = 19, scale = 4)
        public BigDecimal extraDiscountPct;
        public String extraDiscountLabel;

        /** Sleutel waarmee de klant de offerte opent, zonder account. */
        @Column(unique = true, length = 64)
        public String portalToken;
        public Instant sentAt;
        public Instant viewedAt;
        /** Hoe vaak de klant de offerte geopend heeft. */
        public int viewCount;
        public Instant decidedAt;
        public String signedByName;
        @Column(length = 4000)
        public String customerMessage;

        /** Notities voor onszelf; komen nooit op het klantdocument. */
        @Column(length = 4000)
        public String internalNotes;

        /** Of er nog een levertermijn moest komen, en of die intussen ingevuld is. */
        @Enumerated(EnumType.STRING)
        @Column(length = 20)
        public DeliveryTermsState deliveryTerms = DeliveryTermsState.VOLLEDIG;

        /** Of de vracht nog bepaald moest worden, en of dat intussen gebeurd is. */
        @Enumerated(EnumType.STRING)
        @Column(length = 20)
        public FreightState freight = FreightState.BEREKEND;

        /**
         * Vracht die wij zelf invullen in plaats van het landtarief te gebruiken.
         * Leeg betekent: reken het tarief.
         */
        @Column(precision = 19, scale = 2)
        public java.math.BigDecimal manualFreightEur;

        @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
        @OrderBy("id ASC")
        public List<SalesOrderLineEntity> lines = new ArrayList<>();
    }

    /**
     * Eén stap in het leven van een offerte.
     *
     * Losse tabel en geen kind van de order: gebeurtenissen worden alleen
     * toegevoegd en nooit samen met de order herschreven. Als kind zou elke
     * bewaring van de order de hele reeks opnieuw wegschrijven, en dan is één
     * fout genoeg om de geschiedenis kwijt te zijn.
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

    @Entity
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
        /** Zelf ingevulde leverweek, bv. "2026-W34". Optioneel. */
        public String deliveryWeek;
    }

    @Entity
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

    @Entity
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
