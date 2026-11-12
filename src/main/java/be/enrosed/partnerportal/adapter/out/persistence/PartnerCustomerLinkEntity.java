package be.enrosed.partnerportal.adapter.out.persistence;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

/**
 * An ERP customer a partner sold to directly: one the partner picked, or one it added itself
 * (added_by_partner, with the login that did it). Staff see "Aangemaakt door Frans via
 * Partnerportaal" on such a customer until they mark it reviewed.
 */
@Entity
@Table(name = "partner_customer_link",
        uniqueConstraints = @UniqueConstraint(name = "uq_partner_customer_link",
                columnNames = {"partner_customer_id", "customer_id"}),
        indexes = @Index(name = "idx_partner_customer_link_customer", columnList = "customer_id"))
public class PartnerCustomerLinkEntity extends PanacheEntityBase {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    public Long id;

    /** The partner (customer with the partner flag). */
    @Column(name = "partner_customer_id")
    public Long partnerCustomerId;

    /** The buyer. */
    @Column(name = "customer_id")
    public Long customerId;

    @Column(name = "added_by_partner")
    public Boolean addedByPartner;

    @Column(name = "login_id")
    public Long loginId;

    @Column(name = "created_at")
    public Instant createdAt;

    @Column(name = "created_by", length = 64)
    public String createdBy;

    @Column(name = "created_by_name", length = 100)
    public String createdByName;

    @Column(name = "reviewed_at")
    public Instant reviewedAt;

    @Column(name = "reviewed_by", length = 64)
    public String reviewedBy;

    public boolean isAddedByPartner() {
        return Boolean.TRUE.equals(addedByPartner);
    }
}
