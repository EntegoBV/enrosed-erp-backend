package be.enrosed.partnerportal.adapter.out.persistence;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.math.BigDecimal;

/**
 * One extra cost line of a melding (0..5, line_no 1..n in the order typed): an optional description,
 * AMOUNT (euros for the whole melding, 2 dp) or PCT (percent of each sale entry's gross, 4 dp). The
 * parts carry the money; these rows say what it was made of. An edit that rebooks or changes them
 * moves the old rows to the archive row of the melding. The columns are cost_mode / cost_value
 * because H2 reserves VALUE.
 */
@Entity
@Table(name = "partner_sale_report_cost", indexes = {
        @Index(name = "idx_partner_sale_report_cost_report", columnList = "report_id")
})
public class PartnerSaleReportCostEntity extends PanacheEntityBase {
    public static final String AMOUNT = "AMOUNT";
    public static final String PCT = "PCT";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    public Long id;

    @Column(name = "report_id")
    public Long reportId;

    @Column(name = "line_no")
    public Integer lineNo;

    @Column(name = "description", length = 80)
    public String description;

    /** AMOUNT or PCT. */
    @Column(name = "cost_mode", length = 10)
    public String costMode;

    @Column(name = "cost_value", precision = 19, scale = 4)
    public BigDecimal costValue;
}
