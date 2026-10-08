package be.enrosed.inventory.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * The valuation rule the closings follow: one row, FIFO per receipt lot, with the text the
 * accountant reads. It follows the closings until the first one is final and is fixed afterwards.
 */
@Entity
@Table(name = "stock_valuation_rule")
public class StockValuationRuleEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) @Column(name = "id") public Long id;
    @Column(name = "method", length = 24) public String method;
    @Column(name = "method_label", length = 80) public String methodLabel;
    @Column(name = "effective_from_year") public Integer effectiveFromYear;
    @Column(name = "rule_version", length = 16) public String ruleVersion;
    @Column(name = "rule_text", columnDefinition = "TEXT") public String ruleText;
    @Column(name = "created_by", length = 64) public String createdBy;
    @Column(name = "created_by_name", length = 120) public String createdByName;
    @Column(name = "created_at") public Instant createdAt;
}
