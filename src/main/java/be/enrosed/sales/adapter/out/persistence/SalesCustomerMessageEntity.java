package be.enrosed.sales.adapter.out.persistence;
import jakarta.persistence.*;
import java.time.Instant;

@Entity @Table(name = "sales_customer_request_message")
public class SalesCustomerMessageEntity {
    @Id @Column(name = "sales_order_id") public Long salesOrderId;
    @MapsId @OneToOne(fetch = FetchType.LAZY) @JoinColumn(name = "sales_order_id", nullable = false)
    public SalesEntities.SalesOrderEntity order;
    @Column(length = 2000) public String message;
    @Column(name = "captured_at", nullable = false) public Instant capturedAt;
}
