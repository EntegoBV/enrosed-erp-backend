package be.enrosed.sales.domain;

import java.math.BigDecimal;
import java.util.List;

public record SalesPaymentSummary(BigDecimal invoiceTotalEur, BigDecimal receivedEur, BigDecimal remainingEur,
                                  BigDecimal overpaidEur, BigDecimal creditEur, Status status,
                                  List<SalesPayment> payments, List<Instalment> instalments,
                                  boolean legacyPaidMarker, BigDecimal grossReceivedEur,
                                  BigDecimal refundedEur, BigDecimal refundableEur) {
    public SalesPaymentSummary(BigDecimal invoiceTotalEur, BigDecimal receivedEur, BigDecimal remainingEur,
                               BigDecimal overpaidEur, BigDecimal creditEur, Status status,
                               List<SalesPayment> payments, List<Instalment> instalments, boolean legacyPaidMarker) {
        this(invoiceTotalEur, receivedEur, remainingEur, overpaidEur, creditEur, status, payments, instalments,
                legacyPaidMarker, receivedEur.max(BigDecimal.ZERO), BigDecimal.ZERO, overpaidEur.add(creditEur));
    }
    public enum Status { UNPAID, PARTIAL, PAID, OVERPAID, CREDIT }
    public record Instalment(String key, String label, BigDecimal expectedEur, BigDecimal paidEur,
                             BigDecimal remainingEur) {}
}
