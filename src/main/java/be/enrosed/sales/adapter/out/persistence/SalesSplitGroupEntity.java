package be.enrosed.sales.adapter.out.persistence;

import jakarta.persistence.*;
import java.time.Instant;

@Entity @Table(name = "sales_split_group")
public class SalesSplitGroupEntity {
    @Id @Column(length = 36) public String id;
    @Column(name = "root_order_id", nullable = false, unique = true) public Long rootOrderId;
    @Column(name = "later_order_id", nullable = false, unique = true) public Long laterOrderId;
    @Column(name = "request_id", nullable = false, unique = true, length = 36) public String requestId;
    @Column(name = "request_hash", nullable = false, length = 64) public String requestHash;
    @Column(name = "created_at", nullable = false) public Instant createdAt;
}
