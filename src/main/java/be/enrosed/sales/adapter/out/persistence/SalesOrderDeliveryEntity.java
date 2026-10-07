package be.enrosed.sales.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * Delivery address and contact of one document: a side row with the id of
 * its sales_order, so the positional order record and a stale staff save
 * can neither drop nor overwrite it. The destination country stays on the
 * order. The row names the customer it was typed for and is void for a
 * document of any other customer.
 */
@Entity
@Table(name = "sales_order_delivery")
public class SalesOrderDeliveryEntity {
    @Id @Column(name = "sales_order_id") public Long salesOrderId;
    @Column(name = "customer_id") public Long customerId;
    /** DELIVERY or PICKUP. */
    @Column(name = "fulfillment", length = 16) public String fulfillment;
    @Column(name = "address", length = 200) public String address;
    @Column(name = "postal_code", length = 24) public String postalCode;
    @Column(name = "city", length = 100) public String city;
    @Column(name = "pickup_location_id") public Long pickupLocationId;
    @Column(name = "pickup_label", length = 255) public String pickupLabel;
    @Column(name = "pickup_address", length = 500) public String pickupAddress;
    @Column(name = "contact_name", length = 120) public String contactName;
    @Column(name = "contact_phone", length = 50) public String contactPhone;
    /** The document this row was copied from: an invoice, a split part or a new copy carries the order's row. */
    @Column(name = "copied_from_order_id") public Long copiedFromOrderId;
    @Column(name = "saved_at") public Instant savedAt;
}
