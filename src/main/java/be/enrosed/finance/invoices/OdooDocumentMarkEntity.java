package be.enrosed.finance.invoices;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * An incoming Odoo invoice that never becomes an ENROSED cost, marked as handled or as belonging to a container.
 * Created by {@code docs/migrations/2026-10-25/odoo-document-mark-postgresql.sql}; the NOT NULL and check
 * constraints live in the migration only (dev H2 adds the table through schema update). No foreign key.
 */
@Entity
@Table(name = "odoo_document_mark")
public class OdooDocumentMarkEntity {
    /** "<source_namespace>:<move_id>". */
    @Id @Column(name = "id", length = 100) public String id;
    @Column(name = "source_namespace", length = 64) public String namespace;
    @Column(name = "move_id") public Long moveId;
    /** HANDLED or CONTAINER; plain text, no enum annotation (the contract test refuses the word). */
    @Column(name = "resolution", length = 16) public String resolution;
    @Column(name = "purchase_order_id") public Long purchaseOrderId;
    @Column(name = "note", length = 200) public String note;
    @Column(name = "marked_at") public Instant markedAt;
    @Column(name = "marked_by", length = 100) public String markedBy;
}
