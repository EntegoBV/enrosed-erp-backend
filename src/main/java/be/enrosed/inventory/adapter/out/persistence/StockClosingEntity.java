package be.enrosed.inventory.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * One version of the year-end closing of a financial year: CONCEPT or DEFINITIEF. Holds the
 * totals, the notices, the rule it was valued with and, once final, the signer and the two files.
 */
@Entity
@Table(name = "stock_closing")
public class StockClosingEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) @Column(name = "id") public Long id;
    @Column(name = "closing_year") public Integer closingYear;
    @Column(name = "version_no") public Integer versionNo;
    @Column(name = "closing_date") public LocalDate closingDate;
    @Column(name = "cutoff_at") public Instant cutoffAt;
    @Column(name = "status", length = 16) public String status;
    @Column(name = "correction_reason", length = 1000) public String correctionReason;
    @Column(name = "supersedes_id") public Long supersedesId;
    @Column(name = "superseded_by_id") public Long supersededById;
    @Column(name = "superseded_at") public Instant supersededAt;
    @Column(name = "previous_closing_id") public Long previousClosingId;
    /** The rule is copied onto the closing at every compute and frozen with it. */
    @Column(name = "rule_id") public Long ruleId;
    @Column(name = "rule_method", length = 24) public String ruleMethod;
    @Column(name = "rule_method_label", length = 80) public String ruleMethodLabel;
    @Column(name = "rule_effective_from_year") public Integer ruleEffectiveFromYear;
    @Column(name = "rule_version", length = 16) public String ruleVersion;
    @Column(name = "rule_text", columnDefinition = "TEXT") public String ruleText;
    /** The company identity is copied at finalize; empty on a concept. */
    @Column(name = "company_name", length = 255) public String companyName;
    @Column(name = "company_vat", length = 64) public String companyVat;
    @Column(name = "company_address", length = 500) public String companyAddress;
    @Column(name = "cost_value_eur", precision = 19, scale = 2) public BigDecimal costValueEur;
    @Column(name = "write_down_eur", precision = 19, scale = 2) public BigDecimal writeDownEur;
    @Column(name = "own_value_eur", precision = 19, scale = 2) public BigDecimal ownValueEur;
    @Column(name = "demo_value_eur", precision = 19, scale = 2) public BigDecimal demoValueEur;
    @Column(name = "partner_included_eur", precision = 19, scale = 2) public BigDecimal partnerIncludedEur;
    @Column(name = "partner_excluded_eur", precision = 19, scale = 2) public BigDecimal partnerExcludedEur;
    @Column(name = "transit_included_eur", precision = 19, scale = 2) public BigDecimal transitIncludedEur;
    @Column(name = "transit_excluded_eur", precision = 19, scale = 2) public BigDecimal transitExcludedEur;
    @Column(name = "invoiced_out_eur", precision = 19, scale = 2) public BigDecimal invoicedOutEur;
    @Column(name = "total_value_eur", precision = 19, scale = 2) public BigDecimal totalValueEur;
    @Column(name = "estimated_eur", precision = 19, scale = 2) public BigDecimal estimatedEur;
    @Column(name = "own_quantity") public Integer ownQuantity;
    @Column(name = "unvalued_quantity") public Integer unvaluedQuantity;
    @Column(name = "blocker_count") public Integer blockerCount;
    @Column(name = "warning_count") public Integer warningCount;
    @Column(name = "notices_json", columnDefinition = "TEXT") public String noticesJson;
    @Column(name = "computed_at") public Instant computedAt;
    @Column(name = "computed_by", length = 64) public String computedBy;
    @Column(name = "data_sha256", length = 64) public String dataSha256;
    @Column(name = "created_by", length = 64) public String createdBy;
    @Column(name = "created_by_name", length = 120) public String createdByName;
    @Column(name = "created_at") public Instant createdAt;
    @Column(name = "finalized_by", length = 64) public String finalizedBy;
    @Column(name = "finalized_by_name", length = 120) public String finalizedByName;
    @Column(name = "finalized_at") public Instant finalizedAt;
    @Column(name = "signer_name", length = 160) public String signerName;
    @Column(name = "pdf_storage_key", length = 120) public String pdfStorageKey;
    @Column(name = "pdf_sha256", length = 64) public String pdfSha256;
    @Column(name = "pdf_size_bytes") public Long pdfSizeBytes;
    @Column(name = "xlsx_storage_key", length = 120) public String xlsxStorageKey;
    @Column(name = "xlsx_sha256", length = 64) public String xlsxSha256;
    @Column(name = "xlsx_size_bytes") public Long xlsxSizeBytes;
}
