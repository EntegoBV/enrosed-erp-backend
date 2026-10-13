package be.enrosed.finance.odoo;

import jakarta.persistence.*;
import java.time.Instant;

/** One durable source identity; a removed local cost must never make its source appear new again. */
@Entity @Table(name="odoo_cost_import",uniqueConstraints={
        @UniqueConstraint(name="uq_odoo_cost_source",columnNames={"source_namespace","company_id","odoo_move_id"}),
        @UniqueConstraint(name="uq_odoo_cost_local",columnNames="company_cost_id")})
public class OdooCostImportEntity {
    @Id @Column(length=100) public String id;
    @Column(name="source_namespace",nullable=false,length=64) public String namespace;
    @Column(name="company_id",nullable=false) public long companyId;
    @Column(name="odoo_move_id",nullable=false) public long moveId;
    @Column(name="company_cost_id") public Long companyCostId;
    @Column(name="snapshot_hash",length=64) public String snapshotHash;
    @Column(name="snapshot_json",columnDefinition="text") public String snapshotJson;
    @Column(name="documents_json",columnDefinition="text") public String documentsJson;
    @Column(name="created_at",nullable=false) public Instant createdAt;
    @Column(name="registered_at") public Instant registeredAt;
}
