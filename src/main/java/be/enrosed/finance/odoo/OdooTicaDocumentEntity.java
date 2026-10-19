package be.enrosed.finance.odoo;

import jakarta.persistence.*;
import java.time.Instant;

@Entity @Table(name="odoo_tica_document",uniqueConstraints=@UniqueConstraint(name="uq_odoo_tica_document_source",columnNames={"source_namespace","move_id"}))
public class OdooTicaDocumentEntity {
    @Id @Column(length=160) public String id;
    @Column(name="source_namespace",nullable=false,length=64) public String sourceNamespace;
    @Column(name="move_id",nullable=false) public long moveId;
    @Column(name="document_reference",length=200) public String reference;
    @Column(name="source_state",length=32) public String sourceState;
    @Column(name="accepted_hash",length=64) public String acceptedHash;
    @Column(name="accepted_source_state",length=32) public String acceptedSourceState;
    @Column(name="observed_hash",length=64) public String observedHash;
    @Column(name="source_updated_at",length=64) public String sourceUpdatedAt;
    @Column(name="last_seen_at",nullable=false) public Instant lastSeenAt;
    @Column(name="last_imported_at") public Instant lastImportedAt;
    @Column(name="settlement_id") public Long settlementId;
    @Column(name="status_code",nullable=false,length=64) public String code;
}
