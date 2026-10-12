package be.enrosed.finance.odoo;

import jakarta.persistence.*;
import java.time.Instant;

@Entity @Table(name="odoo_outbox")
public class OdooOutboxEntity {
    @Id @Column(name="sales_order_id") public Long salesOrderId;
    @Column(name="source_namespace",nullable=false,length=64) public String sourceNamespace;
    @Column(nullable=false,unique=true,length=180) public String marker;
    @Column(name="snapshot_hash",nullable=false,length=64) public String snapshotHash;
    @Column(name="snapshot_json",nullable=false,columnDefinition="text") public String snapshotJson;
    @Column(nullable=false,length=32) public String state;
    @Column(name="odoo_move_id") public Long odooMoveId;
    @Column(name="peppol_state",length=40) public String peppolState;
    @Column(name="message_code",length=64) public String messageCode;
    @Column(name="updated_at",nullable=false) public Instant updatedAt;
}
