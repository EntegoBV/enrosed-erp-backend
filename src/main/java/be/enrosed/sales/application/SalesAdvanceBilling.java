package be.enrosed.sales.application;

import be.enrosed.sales.adapter.out.persistence.SalesAdvanceBillingEntity;
import be.enrosed.sales.domain.VatTreatment;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** The stored advance and final rows of regular quotes, in the style of {@link PartnerAdvanceSchedules}. */
@ApplicationScoped
public class SalesAdvanceBilling {
    @Inject EntityManager entities;
    @Inject ObjectMapper json;

    public enum Stage { ADVANCE, FINAL }

    /** One advance invoice as the final invoice deducted it, frozen when the final invoice was made. */
    public record Deduction(long advanceInvoiceId, String number, LocalDate invoiceDate,
                            BigDecimal exclEur, BigDecimal vatEur, BigDecimal inclEur) {}

    /**
     * {@code amountExclEur}: the advance itself, or on a final invoice the sum it deducts.
     * {@code vatTreatment}/{@code vatRatePct}: the VAT regime an advance was issued in, null until then.
     */
    public record Row(long salesOrderId, long quoteId, Stage stage, BigDecimal percentage, BigDecimal amountExclEur,
                      List<Deduction> deductions, Instant createdAt, VatTreatment vatTreatment, BigDecimal vatRatePct) {
        public Row {
            deductions = deductions == null ? List.of() : List.copyOf(deductions);
        }
        public Row(long salesOrderId, long quoteId, Stage stage, BigDecimal percentage, BigDecimal amountExclEur,
                   List<Deduction> deductions, Instant createdAt) {
            this(salesOrderId, quoteId, stage, percentage, amountExclEur, deductions, createdAt, null, null);
        }
        /** The same row with the VAT regime its advance was issued in. */
        public Row issuedIn(VatTreatment treatment, BigDecimal ratePct) {
            return new Row(salesOrderId, quoteId, stage, percentage, amountExclEur, deductions, createdAt, treatment, ratePct);
        }
    }

    private static final TypeReference<List<Deduction>> DEDUCTIONS = new TypeReference<>() {};

    public Row find(long salesOrderId) {
        var entity = entities.find(SalesAdvanceBillingEntity.class, salesOrderId);
        return entity == null ? null : domain(entity);
    }

    public List<Row> forQuote(long quoteId) {
        return entities.createQuery("from SalesAdvanceBillingEntity b where b.quoteId = :quote order by b.salesOrderId",
                        SalesAdvanceBillingEntity.class)
                .setParameter("quote", quoteId).getResultList().stream().map(this::domain).toList();
    }

    /** The whole table: small, and read once per sales list. */
    public List<Row> all() {
        return entities.createQuery("from SalesAdvanceBillingEntity b order by b.salesOrderId", SalesAdvanceBillingEntity.class)
                .getResultList().stream().map(this::domain).toList();
    }

    public void save(Row row) {
        var entity = entities.find(SalesAdvanceBillingEntity.class, row.salesOrderId());
        boolean fresh = entity == null;
        if (fresh) entity = new SalesAdvanceBillingEntity();
        entity.salesOrderId = row.salesOrderId(); entity.quoteId = row.quoteId(); entity.stage = row.stage().name();
        entity.percentage = row.percentage(); entity.amountExclEur = row.amountExclEur();
        entity.deductionsJson = row.stage() == Stage.FINAL ? write(row.deductions()) : null;
        entity.vatTreatment = row.vatTreatment() == null ? null : row.vatTreatment().name();
        entity.vatRatePct = row.vatRatePct();
        entity.createdAt = row.createdAt() == null ? Instant.now() : row.createdAt();
        if (fresh) entities.persist(entity);
        entities.flush();
    }

    public void delete(long salesOrderId) {
        var entity = entities.find(SalesAdvanceBillingEntity.class, salesOrderId);
        if (entity != null) entities.remove(entity);
        entities.flush();
    }

    private Row domain(SalesAdvanceBillingEntity entity) {
        return new Row(entity.salesOrderId, entity.quoteId, Stage.valueOf(entity.stage), entity.percentage,
                entity.amountExclEur, read(entity.deductionsJson), entity.createdAt, treatment(entity.vatTreatment), entity.vatRatePct);
    }

    /** An unknown stored name reads as "not recorded", never as a failure. */
    private static VatTreatment treatment(String value) {
        if (value == null || value.isBlank()) return null;
        try { return VatTreatment.valueOf(value.strip()); } catch (IllegalArgumentException unknown) { return null; }
    }

    private String write(List<Deduction> deductions) {
        try {
            return json.writeValueAsString(deductions == null ? List.of() : deductions);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("Cannot store the deducted advance invoices", failure);
        }
    }

    private List<Deduction> read(String value) {
        if (value == null || value.isBlank()) return List.of();
        try {
            return json.readValue(value, DEDUCTIONS);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("Cannot read the deducted advance invoices", failure);
        }
    }
}
