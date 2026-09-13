package be.enrosed.catalog.adapter.out.persistence;

import jakarta.persistence.*;
import java.time.Instant;

/** Explicit applicability of a source product's private supplier instructions; never a copy. */
@Entity
@Table(name = "product_supplier_agreement_link", indexes = {
        @Index(name = "ix_supplier_agreement_link_source", columnList = "source_product_id")})
public class ProductSupplierAgreementLinkEntity {
    @Id @Column(name = "product_id") public Long productId;
    @Column(name = "source_product_id", nullable = false) public long sourceProductId;
    @Column(name = "supplier_id", nullable = false) public long supplierId;
    @Column(name = "family_id", nullable = false) public long familyId;
    @Column(name = "updated_at", nullable = false) public Instant updatedAt;
}
