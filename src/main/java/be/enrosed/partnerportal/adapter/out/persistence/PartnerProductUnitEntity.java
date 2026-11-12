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
 * The count unit staff set as the default for one product of one partner (Instellingen ›
 * Partnerportaal › Eenheid per product). No row = STUK; a stored unit the product no longer offers
 * reads as STUK too. Frans overrides it per entry.
 */
@Entity
@Table(name = "partner_product_unit",
        uniqueConstraints = @UniqueConstraint(name = "uq_partner_product_unit", columnNames = {"customer_id", "product_id"}))
public class PartnerProductUnitEntity extends PanacheEntityBase {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    public Long id;

    @Column(name = "customer_id")
    public Long customerId;

    @Column(name = "product_id")
    public Long productId;

    /** STUK, VERPAKKING or OMDOOS. */
    @Column(name = "unit", length = 12)
    public String unit;

    @Column(name = "updated_at")
    public Instant updatedAt;

    @Column(name = "updated_by", length = 64)
    public String updatedBy;
}
