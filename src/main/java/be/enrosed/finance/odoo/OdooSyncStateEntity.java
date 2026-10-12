package be.enrosed.finance.odoo;

import jakarta.persistence.*;
import java.time.Instant;

@Entity @Table(name="odoo_sync_state")
public class OdooSyncStateEntity {
    @Id @Column(length=64) public String id;
    @Column(name="lease_owner",length=36) public String leaseOwner;
    @Column(name="lease_until") public Instant leaseUntil;
    @Column(name="last_check_at") public Instant lastCheckAt;
    @Column(name="last_check_code",length=64) public String lastCheckCode;
    @Column(name="last_sync_at") public Instant lastSyncAt;
    @Column(name="last_sync_json",length=4000) public String lastSyncJson;
}
