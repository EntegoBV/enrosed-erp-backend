package be.enrosed.sales.adapter.out.persistence;

import jakarta.persistence.*;

@Entity @Table(name = "sales_split_part")
public class SalesSplitPartEntity {
    @Id @Column(name = "sales_order_id") public Long salesOrderId;
    @MapsId @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "sales_order_id", nullable = false)
    public SalesEntities.SalesOrderEntity order;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "group_id", nullable = false)
    public SalesSplitGroupEntity group;
    @Column(name = "part_number", nullable = false) public int partNumber;
    @Column(name = "waiting_for_stock", nullable = false) public boolean waitingForStock;
    @Column(name = "pricing_json", nullable = false, columnDefinition = "text") public String pricingJson;
}
