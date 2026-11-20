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
    /**
     * Whether the website showed the customer prices when they sent this request or order, stored at the intake:
     * a visitor without login only while the website price switch was on, a logged-in customer always (their own
     * price or the list). Null: not known (older requests, documents staff made).
     */
    @Column(name = "prices_shown") public Boolean pricesShown;
}
