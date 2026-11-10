package be.enrosed.partnerportal.adapter.out.persistence;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

/**
 * The portal login of one partner customer (Verhoeven: "verhoeven"). Staff set the password in
 * the ERP; only its bcrypt hash is stored. The username is stored lower case and never equals a
 * configured staff username.
 */
@Entity
@Table(name = "partner_login", uniqueConstraints = {
        @UniqueConstraint(name = "uq_partner_login_customer", columnNames = "customer_id"),
        @UniqueConstraint(name = "uq_partner_login_username", columnNames = "username")
})
public class PartnerLoginEntity extends PanacheEntityBase {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    public Long id;

    @Column(name = "customer_id")
    public Long customerId;

    @Column(name = "username", length = 64)
    public String username;

    @Column(name = "display_name", length = 100)
    public String displayName;

    @Column(name = "password_hash", length = 100)
    public String passwordHash;

    @Column(name = "active")
    public Boolean active;

    @Column(name = "password_set_at")
    public Instant passwordSetAt;

    @Column(name = "last_login_at")
    public Instant lastLoginAt;

    @Column(name = "created_at")
    public Instant createdAt;

    @Column(name = "created_by", length = 64)
    public String createdBy;

    @Column(name = "updated_at")
    public Instant updatedAt;

    @Column(name = "updated_by", length = 64)
    public String updatedBy;

    public boolean isActive() {
        return Boolean.TRUE.equals(active);
    }

    @Override
    public String toString() {
        return "PartnerLogin[id=" + id + ", customerId=" + customerId + ", username=" + username + "]";
    }
}
