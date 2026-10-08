package be.enrosed.inventory.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Output: one container in a closing with its role (EIGEN, PARTNER, ONDERWEG or VORIG), the
 * rates, the border date for exchange differences, the four amounts per payee and what stays outside the value.
 */
@Entity
@Table(name = "stock_closing_container")
public class StockClosingContainerEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) @Column(name = "id") public Long id;
    @Column(name = "closing_id") public Long closingId;
    @Column(name = "purchase_order_id") public Long purchaseOrderId;
    @Column(name = "order_number", length = 120) public String orderNumber;
    @Column(name = "display_name", length = 255) public String displayName;
    @Column(name = "supplier_name", length = 255) public String supplierName;
    @Column(name = "role", length = 16) public String role;
    @Column(name = "partner_name", length = 255) public String partnerName;
    @Column(name = "order_date") public LocalDate orderDate;
    @Column(name = "shipped_on") public LocalDate shippedOn;
    @Column(name = "received_on") public LocalDate receivedOn;
    /** The day the goods were bought: foreign payments up to it keep their bank euro. */
    @Column(name = "rate_cutoff_date") public LocalDate rateCutoffDate;
    @Column(name = "rate_cutoff_source", length = 24) public String rateCutoffSource;
    @Column(name = "supplier_incoterm", length = 120) public String supplierIncoterm;
    @Column(name = "quantity_basis", length = 16) public String quantityBasis;
    @Column(name = "billed_basis", length = 16) public String billedBasis;
    @Column(name = "cny_to_usd", precision = 19, scale = 8) public BigDecimal cnyToUsd;
    @Column(name = "usd_to_eur_goods", precision = 19, scale = 8) public BigDecimal usdToEurGoods;
    @Column(name = "usd_to_eur_transport", precision = 19, scale = 8) public BigDecimal usdToEurTransport;
    @Column(name = "cif") public Boolean cif;
    @Column(name = "group_variants") public Boolean groupVariants;
    @Column(name = "separate_in_piece_price") public Boolean separateInPiecePrice;
    @Column(name = "alloc_origin", length = 16) public String allocOrigin;
    @Column(name = "alloc_freight", length = 16) public String allocFreight;
    @Column(name = "alloc_destination", length = 16) public String allocDestination;
    @Column(name = "alloc_separate", length = 16) public String allocSeparate;
    /** Per payee: the reconciliation status, the Afspraak, the counted payments, the open Afspraak, what is in the value and its estimated part. */
    @Column(name = "supplier_status", length = 24) public String supplierStatus;
    @Column(name = "supplier_planned_eur", precision = 19, scale = 2) public BigDecimal supplierPlannedEur;
    @Column(name = "supplier_paid_eur", precision = 19, scale = 2) public BigDecimal supplierPaidEur;
    @Column(name = "supplier_open_eur", precision = 19, scale = 2) public BigDecimal supplierOpenEur;
    @Column(name = "supplier_included_eur", precision = 19, scale = 2) public BigDecimal supplierIncludedEur;
    @Column(name = "supplier_estimated_eur", precision = 19, scale = 2) public BigDecimal supplierEstimatedEur;
    @Column(name = "supplier_goods_eur", precision = 19, scale = 2) public BigDecimal supplierGoodsEur;
    @Column(name = "supplier_transport_eur", precision = 19, scale = 2) public BigDecimal supplierTransportEur;
    @Column(name = "logistics_status", length = 24) public String logisticsStatus;
    @Column(name = "logistics_planned_eur", precision = 19, scale = 2) public BigDecimal logisticsPlannedEur;
    @Column(name = "logistics_paid_eur", precision = 19, scale = 2) public BigDecimal logisticsPaidEur;
    @Column(name = "logistics_open_eur", precision = 19, scale = 2) public BigDecimal logisticsOpenEur;
    @Column(name = "logistics_included_eur", precision = 19, scale = 2) public BigDecimal logisticsIncludedEur;
    @Column(name = "logistics_estimated_eur", precision = 19, scale = 2) public BigDecimal logisticsEstimatedEur;
    @Column(name = "separate_status", length = 24) public String separateStatus;
    @Column(name = "separate_planned_eur", precision = 19, scale = 2) public BigDecimal separatePlannedEur;
    @Column(name = "separate_paid_eur", precision = 19, scale = 2) public BigDecimal separatePaidEur;
    @Column(name = "separate_open_eur", precision = 19, scale = 2) public BigDecimal separateOpenEur;
    @Column(name = "separate_included_eur", precision = 19, scale = 2) public BigDecimal separateIncludedEur;
    @Column(name = "separate_estimated_eur", precision = 19, scale = 2) public BigDecimal separateEstimatedEur;
    @Column(name = "other_excluded_eur", precision = 19, scale = 2) public BigDecimal otherExcludedEur;
    @Column(name = "price_credit_eur", precision = 19, scale = 2) public BigDecimal priceCreditEur;
    @Column(name = "loss_credit_eur", precision = 19, scale = 2) public BigDecimal lossCreditEur;
    @Column(name = "exchange_difference_eur", precision = 19, scale = 2) public BigDecimal exchangeDifferenceEur;
    @Column(name = "enrosed_cost_excluded_eur", precision = 19, scale = 2) public BigDecimal enrosedCostExcludedEur;
    @Column(name = "acquisition_eur", precision = 19, scale = 2) public BigDecimal acquisitionEur;
    @Column(name = "estimated_eur", precision = 19, scale = 2) public BigDecimal estimatedEur;
    @Column(name = "payments_json", columnDefinition = "TEXT") public String paymentsJson;
    @Column(name = "credits_json", columnDefinition = "TEXT") public String creditsJson;
    @Column(name = "notes", length = 2000) public String notes;
}
