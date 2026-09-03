package be.enrosed.analytics;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * One page view on the public website. Nothing here identifies a person:
 * the visitor is a hash of network address and browser that changes every
 * day, made at the website's edge, so the address itself never reaches us.
 */
@Entity
@Table(name = "website_visit", indexes = {
        @Index(name = "idx_website_visit_occurred", columnList = "occurred_at"),
        @Index(name = "idx_website_visit_visitor", columnList = "visitor,occurred_at")
})
public class WebsiteVisitEntity extends PanacheEntityBase {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "occurred_at", nullable = false)
    public Instant occurredAt;

    @Column(nullable = false, length = 64)
    public String visitor;

    @Column(nullable = false, length = 255)
    public String path;

    /** HOME, PRODUCTS, COLLECTION, PRODUCT, QUOTE, CONTACT, LEGAL or OTHER. */
    @Column(name = "page_kind", nullable = false, length = 24)
    public String pageKind;

    @Column(length = 8)
    public String locale;

    @Column(length = 2)
    public String country;

    @Column(length = 80)
    public String city;

    @Column(name = "referrer_host", length = 120)
    public String referrerHost;

    @Column(length = 64)
    public String source;

    @Column(length = 64)
    public String medium;

    @Column(length = 120)
    public String campaign;

    /** MOBILE, TABLET or DESKTOP. */
    @Column(nullable = false, length = 12)
    public String device;

    @Column(name = "screen_width")
    public Integer screenWidth;
}
