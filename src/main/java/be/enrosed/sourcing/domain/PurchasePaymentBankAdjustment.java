package be.enrosed.sourcing.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * What a purchase payment was before it first followed its bank transaction:
 * the screen says "aangepast aan de bank (was € 12.480,00 op 13/10)". Kept
 * beside the payment, written once, removed by a hand edit of the amount or
 * the date and by deleting the payment.
 */
public record PurchasePaymentBankAdjustment(long paymentId, BigDecimal fromAmountEur, LocalDate fromPaidOn,
                                            Long bankStatementLineId, Instant adjustedAt, String actor) {}
