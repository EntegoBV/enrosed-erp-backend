package be.enrosed.finance.application;

import be.enrosed.finance.domain.CompanyCost;
import be.enrosed.finance.domain.CostKind;
import be.enrosed.finance.domain.CostPayment;
import be.enrosed.finance.domain.ExpenseType;
import be.enrosed.finance.domain.VatRegime;
import be.enrosed.shared.BusinessRuleException;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CompanyCostServiceTest {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 12);

    private CompanyCosts costs;
    private CostPayments payments;
    private CompanyCostService service;
    /** The payment rows, as the mock serves them: save adds or replaces, findByCost filters, deleteById removes. */
    private final List<CostPayment> rows = new ArrayList<>();

    @BeforeEach
    void setUp() {
        costs = mock(CompanyCosts.class);
        when(costs.findByIdForUpdate(anyLong())).thenAnswer(call -> costs.findById(call.getArgument(0)));
        AtomicLong costIds = new AtomicLong(500);
        when(costs.save(any(CompanyCost.class))).thenAnswer(call -> {
            CompanyCost cost = call.getArgument(0);
            return cost.id() != null ? cost : withId(cost, costIds.incrementAndGet());
        });
        payments = mock(CostPayments.class);
        AtomicLong paymentIds = new AtomicLong(0);
        when(payments.save(any(CostPayment.class))).thenAnswer(call -> {
            CostPayment payment = call.getArgument(0);
            CostPayment saved = payment.id() != null ? payment : new CostPayment(paymentIds.incrementAndGet(), payment.companyCostId(),
                    payment.paidOn(), payment.amountEur(), payment.account(), payment.bankStatementLineId(), payment.note(),
                    payment.recordedAt(), payment.actor());
            rows.removeIf(row -> Objects.equals(row.id(), saved.id()));
            rows.add(saved);
            return saved;
        });
        when(payments.findByCost(anyLong())).thenAnswer(call -> {
            long costId = call.getArgument(0);
            return rows.stream().filter(row -> row.companyCostId() != null && row.companyCostId() == costId).toList();
        });
        when(payments.findById(anyLong())).thenAnswer(call -> {
            long id = call.getArgument(0);
            return rows.stream().filter(row -> row.id() == id).findFirst();
        });
        org.mockito.Mockito.doAnswer(call -> {
            long id = call.getArgument(0);
            rows.removeIf(row -> row.id() == id);
            return null;
        }).when(payments).deleteById(anyLong());
        service = new CompanyCostService(costs, payments);
    }

    @Test
    void bookingACostCleansItAndComputesTheVat() {
        CompanyCost saved = service.create(be(new CompanyCost(null, LocalDate.of(2026, 10, 3), " beurs ", "Stand TICA oktober",
                " TICA Trends & Trade ", new BigDecimal("1250.005"), new BigDecimal("21"), "F-2026-77", null, "tica", "", null)));

        assertEquals("BEURS", saved.category());
        assertEquals("TICA Trends & Trade", saved.party());
        assertEquals(new BigDecimal("1250.01"), saved.amountExclEur());
        assertEquals(new BigDecimal("262.50"), saved.vatEur());
        assertEquals(new BigDecimal("1512.51"), saved.totalInclEur());
        assertNull(saved.amountInclEur(), "entered excl.: no inclusive total is stored");
        assertEquals("TICA", saved.salesChannel());
        assertNull(saved.notes(), "blank notes are stored as nothing");
        assertNotNull(saved.createdAt());
        assertEquals("BE", saved.supplierCountry(), "a Belgian invoice is from Belgium");
        assertEquals(ExpenseType.STANDARD, saved.expenseType());
        assertEquals(new BigDecimal("100"), saved.vatDeductiblePct());
    }

    @Test
    void aCostNeedsADateACategoryADescriptionANonNegativeAmountAndARegime() {
        assertThrows(BusinessRuleException.class, () -> service.create(be(new CompanyCost(null, null, "HUUR", "Magazijn",
                null, BigDecimal.TEN, null, null, null, null, null, null))));
        assertThrows(BusinessRuleException.class, () -> service.create(be(new CompanyCost(null, LocalDate.now(), " ", "Magazijn",
                null, BigDecimal.TEN, null, null, null, null, null, null))));
        assertThrows(BusinessRuleException.class, () -> service.create(be(new CompanyCost(null, LocalDate.now(), "HUUR", "",
                null, BigDecimal.TEN, null, null, null, null, null, null))));
        assertThrows(BusinessRuleException.class, () -> service.create(be(new CompanyCost(null, LocalDate.now(), "HUUR", "Magazijn",
                null, new BigDecimal("-1"), null, null, null, null, null, null))));
        assertThrows(BusinessRuleException.class, () -> service.create(be(new CompanyCost(null, LocalDate.now(), "HUUR", "Magazijn",
                null, BigDecimal.TEN, new BigDecimal("120"), null, null, null, null, null))));
        BusinessRuleException noRegime = assertThrows(BusinessRuleException.class, () -> service.create(new CompanyCost(null,
                LocalDate.now(), "HUUR", "Magazijn", null, BigDecimal.TEN, null, null, null, null, null, null)));
        assertEquals("Kies de btw-regeling", noRegime.getMessage());
        assertThrows(BusinessRuleException.class, () -> service.list(LocalDate.of(2026, 2, 1), LocalDate.of(2026, 1, 1)));
    }

    @Test
    void fifteenArgConstructorMeansUnknownUnconfirmedRegime() {
        CompanyCost legacy = new CompanyCost(1L, DAY, "HUUR", "Magazijn", "Immo", new BigDecimal("100.00"), null,
                new BigDecimal("21"), null, null, null, null, Instant.now(), null, null);

        assertNull(legacy.vatRegime());
        assertEquals(Boolean.FALSE, legacy.vatRegimeConfirmed());
        assertEquals(new BigDecimal("100"), legacy.vatDeductiblePct(), "fully deductible until someone says otherwise");
        assertEquals(new BigDecimal("100"), legacy.taxDeductiblePct());
        assertFalse(legacy.investment());
        assertEquals(new BigDecimal("121.00"), legacy.totalInclEur(), "an unknown regime pays like a Belgian invoice");
        assertEquals(new BigDecimal("21.00"), legacy.returnVatEur());
    }

    @Test
    void updatingKeepsTheIdentityAndTheBookingMoment() {
        CompanyCost existing = new CompanyCost(9L, LocalDate.of(2026, 1, 5), "BOEKHOUDER", "Jaarrekening", "Accountant BV",
                new BigDecimal("900.00"), new BigDecimal("21"), null, null, null, null, java.time.Instant.parse("2026-01-05T10:00:00Z"));
        when(costs.findById(9L)).thenReturn(Optional.of(existing));

        CompanyCost updated = service.update(9L, be(new CompanyCost(null, LocalDate.of(2026, 1, 6), "BOEKHOUDER", "Jaarrekening 2025",
                "Accountant BV", new BigDecimal("950"), new BigDecimal("21"), "2026-014", LocalDate.of(2026, 1, 20), null, null, null)));

        assertEquals(9L, updated.id());
        assertEquals(existing.createdAt(), updated.createdAt());
        assertEquals(LocalDate.of(2026, 1, 20), updated.paidOn());
        assertEquals(new BigDecimal("950.00"), updated.amountExclEur());
        assertEquals(VatRegime.BE, updated.vatRegime());
    }

    @Test
    void anUpdateWithoutARegimeKeepsTheStoredOne() {
        CompanyCost existing = withRegime(new CompanyCost(9L, LocalDate.of(2026, 1, 5), "BOEKHOUDER", "Jaarrekening", "Accountant BV",
                new BigDecimal("900.00"), new BigDecimal("21"), null, null, null, null, Instant.parse("2026-01-05T10:00:00Z")),
                VatRegime.BE, true, "BE");
        when(costs.findById(9L)).thenReturn(Optional.of(existing));

        CompanyCost updated = service.update(9L, new CompanyCost(null, LocalDate.of(2026, 1, 6), "BOEKHOUDER", "Jaarrekening 2025",
                "Accountant BV", new BigDecimal("950"), new BigDecimal("21"), "2026-014", null, null, null, null));

        assertEquals(VatRegime.BE, updated.vatRegime(), "a body without a regime leaves the stored one");
        assertEquals(Boolean.TRUE, updated.vatRegimeConfirmed());

        CompanyCost legacy = new CompanyCost(10L, LocalDate.of(2026, 1, 5), "BANK", "Kosten", null,
                new BigDecimal("9.00"), null, null, null, null, null, Instant.parse("2026-01-05T10:00:00Z"));
        when(costs.findById(10L)).thenReturn(Optional.of(legacy));
        assertNull(service.update(10L, new CompanyCost(null, LocalDate.of(2026, 1, 6), "BANK", "Kosten", null,
                new BigDecimal("9.00"), null, null, null, null, null, null)).vatRegime(), "a row from before stays unknown");
    }

    @Test
    void markingPaidKeepsEverythingElseIncludingTheRecurringLink() {
        CompanyCost booked = new CompanyCost(12L, LocalDate.of(2026, 9, 1), "HUUR", "Huur magazijn", "Immo Ham",
                new BigDecimal("850.00"), null, new BigDecimal("21"), null, null, null, "Automatisch geboekt", Instant.parse("2026-09-01T06:00:00Z"), 3L, null);
        when(costs.findById(12L)).thenReturn(Optional.of(booked));

        CompanyCost paid = service.markPaid(12L, LocalDate.of(2026, 9, 4));

        assertEquals(LocalDate.of(2026, 9, 4), paid.paidOn());
        assertEquals(3L, paid.recurringCostId());
        assertEquals(booked.createdAt(), paid.createdAt());
        assertEquals(1, rows.size());
        assertEquals(new BigDecimal("1028.50"), rows.getFirst().amountEur(), "the remainder: the whole cash total");

        when(costs.findById(12L)).thenReturn(Optional.of(paid));
        CompanyCost edited = service.update(12L, new CompanyCost(null, LocalDate.of(2026, 9, 1), "HUUR", "Huur magazijn september",
                "Immo Ham", new BigDecimal("850"), null, new BigDecimal("21"), null, LocalDate.of(2026, 9, 4), null, null, null, null, null));
        assertEquals(3L, edited.recurringCostId(), "editing the text does not cut the cost loose from its definition");
    }

    @Test
    void aCostEnteredInclusiveKeepsItsTotalAndTakesTheVatFromIt() {
        CompanyCost saved = service.create(cost("82.64", "100", "21"));

        assertEquals(new BigDecimal("100.00"), saved.amountInclEur());
        assertEquals(new BigDecimal("82.64"), saved.amountExclEur());
        assertEquals(new BigDecimal("17.36"), saved.vatEur(), "what the invoice shows, not 82.64 × 21 % = 17.35");
        assertEquals(new BigDecimal("100.00"), saved.totalInclEur());

        CompanyCost existing = new CompanyCost(4L, LocalDate.of(2026, 10, 1), "HUUR", "Magazijn", "Immo",
                new BigDecimal("50.00"), null, null, null, null, null, null, Instant.parse("2026-10-01T06:00:00Z"), null, null);
        when(costs.findById(4L)).thenReturn(Optional.of(existing));
        CompanyCost updated = service.update(4L, cost("82.64", "100", "21"));
        assertEquals(new BigDecimal("100.00"), updated.amountInclEur(), "an edit carries the entered total through");
        when(costs.findById(4L)).thenReturn(Optional.of(updated));
        assertEquals(new BigDecimal("100.00"), service.markPaid(4L, LocalDate.of(2026, 10, 12)).amountInclEur(),
                "settling the cost keeps the entered total");
    }

    @Test
    void belgianInvoiceKeepsRoundOneTotals() {
        CompanyCost saved = service.create(cost("82.64", "100", "21"));

        assertEquals(new BigDecimal("17.36"), saved.vatEur());
        assertEquals(new BigDecimal("100.00"), saved.totalInclEur());
        assertEquals(new BigDecimal("17.36"), saved.returnVatEur());
        assertEquals(new BigDecimal("17.36"), saved.deductibleVatEur(), "grid 59");
        assertEquals(new BigDecimal("0.00"), saved.nonDeductibleVatEur());
        assertEquals(new BigDecimal("82.64"), saved.expenseEur());
        assertFalse(saved.reverseCharge());
    }

    @Test
    void anInclusiveTotalSitsAboveExclAndMatchesTheRate() {
        assertThrows(BusinessRuleException.class, () -> service.create(cost("100", "90", "21")), "below excl.");
        assertThrows(BusinessRuleException.class, () -> service.create(cost("82.64", "110", "21")), "far from excl. × 21 %");
        assertThrows(BusinessRuleException.class, () -> service.create(cost("82.64", "82.65", null)), "without VAT incl. equals excl.");
        assertThrows(BusinessRuleException.class, () -> service.create(cost("82.64", "82.65", "0")), "at 0 % incl. equals excl.");
        assertEquals(new BigDecimal("82.64"), service.create(cost("82.64", "82.64", null)).amountInclEur());
        assertEquals(new BigDecimal("100.01"), service.create(cost("82.65", "100.01", "21")).amountInclEur(), "a cent of rounding fits");
        assertEquals(new BigDecimal("100.00"), service.create(cost("82.64", "99.995", "21")).amountInclEur(), "scaled to cents");
    }

    @Test
    void anUpdateWithoutAnInclusiveTotalClearsTheStoredOne() {
        CompanyCost entered = new CompanyCost(5L, LocalDate.of(2026, 10, 1), "HUUR", "Magazijn", "Immo",
                new BigDecimal("82.64"), new BigDecimal("100.00"), new BigDecimal("21"), null, null, null, null,
                Instant.parse("2026-10-01T06:00:00Z"), null, null);
        when(costs.findById(5L)).thenReturn(Optional.of(entered));

        CompanyCost updated = service.update(5L, cost("82.64", null, "21"));

        assertNull(updated.amountInclEur(), "the form said excl.: the entered total goes");
        assertEquals(new BigDecimal("17.35"), updated.vatEur(), "the VAT follows the rate again");
        assertEquals(new BigDecimal("99.99"), updated.totalInclEur());
    }

    @Test
    void aCostEnteredExclusiveStaysWithoutAStoredTotal() {
        CompanyCost saved = service.create(cost("82.64", null, "21"));

        assertNull(saved.amountInclEur());
        assertEquals(new BigDecimal("17.35"), saved.vatEur());
        assertEquals(new BigDecimal("99.99"), saved.totalInclEur());
    }

    @Test
    void theBankLineThatPaidACostIsTheBanksToSetAndSurvivesEveryEdit() {
        CompanyCost onTheBank = new CompanyCost(7L, LocalDate.of(2026, 9, 8), "BANK", "Kosten overschrijving", "KBC",
                new BigDecimal("12.50"), null, null, null, LocalDate.of(2026, 9, 8), null, null,
                Instant.parse("2026-09-08T10:00:00Z"), null, 41L);
        when(costs.findById(7L)).thenReturn(Optional.of(onTheBank));

        CompanyCost edited = service.update(7L, withRegime(new CompanyCost(null, LocalDate.of(2026, 9, 8), "BANK",
                "Kosten overschrijving september", "KBC", new BigDecimal("12.50"), null, null, null,
                LocalDate.of(2026, 9, 8), null, null, null, null, 99L), VatRegime.OUT_OF_SCOPE, true, null));
        assertEquals(41L, edited.bankStatementLineId(), "an edit keeps the stored link and ignores the form's");
        assertEquals(1, rows.size(), "the first edit of a cost from before the rows writes its one full payment");
        assertEquals(41L, rows.getFirst().bankStatementLineId(), "on the bank where the cost is");
        when(costs.findById(7L)).thenReturn(Optional.of(edited));
        assertThrows(BusinessRuleException.class, () -> service.markPaid(7L, LocalDate.of(2026, 9, 9)), "paid in full already");

        CompanyCost booked = service.create(withRegime(new CompanyCost(null, LocalDate.of(2026, 9, 8), "BANK", "Kosten overschrijving",
                "KBC", new BigDecimal("12.50"), null, null, null, null, null, null, null, null, 99L), VatRegime.OUT_OF_SCOPE, true, null));
        assertNull(booked.bankStatementLineId(), "a new cost is on the bank only once the bank service says so");
    }

    @Test
    void bookingACostTeachesTheListAanWieTheNameAndItsDefaults() {
        CostPartyService learner = mock(CostPartyService.class);
        @SuppressWarnings("unchecked") Instance<CostPartyService> parties = mock(Instance.class);
        when(parties.isResolvable()).thenReturn(true);
        when(parties.get()).thenReturn(learner);
        service.parties = parties;

        service.create(be(new CompanyCost(null, LocalDate.of(2026, 10, 3), " beurs ", "Stand TICA oktober",
                " TICA Trends & Trade ", BigDecimal.TEN, new BigDecimal("21"), null, null, "tica", null, null)));

        verify(learner).learn("TICA Trends & Trade", "BEURS", new BigDecimal("21"), "TICA", "BE", VatRegime.BE);
    }

    @Test
    void onlyAFairCostTeachesItsChannelAndAFailingListNeverUndoesTheBooking() {
        CostPartyService learner = mock(CostPartyService.class);
        @SuppressWarnings("unchecked") Instance<CostPartyService> parties = mock(Instance.class);
        when(parties.isResolvable()).thenReturn(true);
        when(parties.get()).thenReturn(learner);
        service.parties = parties;

        service.create(withRegime(new CompanyCost(null, LocalDate.of(2026, 10, 3), "HUUR", "Magazijn oktober", "Immo Ham",
                new BigDecimal("850"), new BigDecimal("21"), null, null, "WEBSITE", null, null), VatRegime.BE, false, null));
        verify(learner).learn("Immo Ham", "HUUR", new BigDecimal("21"), null, "BE", null);

        org.mockito.Mockito.doThrow(new IllegalStateException("lijst stuk")).when(learner).learn(any(), any(), any(), any(), any(), any());
        CompanyCost booked = service.create(be(new CompanyCost(null, LocalDate.of(2026, 10, 4), "HUUR", "Magazijn november", "Immo Ham",
                new BigDecimal("850"), new BigDecimal("21"), null, null, null, null, null)));
        assertEquals("Magazijn november", booked.description(), "the booking stands whatever the list does");
    }

    @Test
    void reverseChargeCostPaysExclAndSelfAssessesVat() {
        CompanyCost saved = service.create(regime(VatRegime.EU_SERVICES, "IE", "100", "121", "21", null, null));

        assertEquals(new BigDecimal("100.00"), saved.totalInclEur(), "nothing goes to Google but the net");
        assertEquals(new BigDecimal("21.00"), saved.vatEur());
        assertEquals(new BigDecimal("21.00"), saved.returnVatEur(), "declared in 55 and deducted in 59");
        assertEquals(new BigDecimal("21.00"), saved.deductibleVatEur());
        assertNull(saved.amountInclEur(), "a reverse-charged invoice never stores a total");
        assertTrue(saved.reverseCharge());
        assertEquals(new BigDecimal("100.00"), saved.expenseEur());
        assertThrows(BusinessRuleException.class, () -> service.create(regime(VatRegime.EU_SERVICES, "IE", "100", null, null, null, null)),
                "the rate is what we self-assess");
    }

    @Test
    void reverseChargeNeedsAnEuCountryOtherThanBelgium() {
        assertEquals("Voor btw verlegd binnen de EU kies je een EU-land, niet België", assertThrows(BusinessRuleException.class,
                () -> service.create(regime(VatRegime.EU_SERVICES, "BE", "100", null, "21", null, null))).getMessage());
        assertThrows(BusinessRuleException.class, () -> service.create(regime(VatRegime.EU_GOODS, "US", "100", null, "21", null, null)));
        assertEquals("Kies het land van de leverancier", assertThrows(BusinessRuleException.class,
                () -> service.create(regime(VatRegime.EU_SERVICES, null, "100", null, "21", null, null))).getMessage());
        assertEquals("IE", service.create(regime(VatRegime.EU_SERVICES, "ie", "100", null, "21", null, null)).supplierCountry());
        assertEquals("XI", service.create(regime(VatRegime.EU_GOODS, "XI", "100", null, "21", null, null)).supplierCountry(),
                "Northern Ireland counts as EU for goods");
        assertThrows(BusinessRuleException.class, () -> service.create(regime(VatRegime.EU_SERVICES, "XI", "100", null, "21", null, null)));
        assertEquals("Voor een dienst van buiten de EU kies je een land buiten de EU", assertThrows(BusinessRuleException.class,
                () -> service.create(regime(VatRegime.NON_EU_SERVICES, "DE", "100", null, "21", null, null))).getMessage());
        assertEquals("US", service.create(regime(VatRegime.NON_EU_SERVICES, "US", "100", null, "21", null, null)).supplierCountry());
        assertEquals("De landcode bestaat uit twee letters", assertThrows(BusinessRuleException.class,
                () -> service.create(regime(VatRegime.NON_EU_SERVICES, "USA", "100", null, "21", null, null))).getMessage());
    }

    @Test
    void partialDeductionSplitsTheVat() {
        CompanyCost restaurant = service.create(regime(VatRegime.BE, null, "1000", null, "21", ExpenseType.RESTAURANT, null));
        assertEquals(new BigDecimal("0.00"), restaurant.deductibleVatEur(), "grid 59");
        assertEquals(new BigDecimal("210.00"), restaurant.nonDeductibleVatEur());
        assertEquals(new BigDecimal("1210.00"), restaurant.expenseEur(), "the VAT we cannot deduct is cost");
        assertEquals(new BigDecimal("1210.00"), restaurant.totalInclEur());
        assertEquals(new BigDecimal("0"), restaurant.vatDeductiblePct());
        assertEquals(new BigDecimal("69"), restaurant.taxDeductiblePct());

        CompanyCost car = service.create(regime(VatRegime.BE, null, "1000", null, "21", ExpenseType.CAR, null));
        assertEquals(new BigDecimal("105.00"), car.deductibleVatEur());
        assertEquals(new BigDecimal("105.00"), car.nonDeductibleVatEur());
        assertEquals(new BigDecimal("1105.00"), car.expenseEur());

        CompanyCost custom = service.create(withDeduction(regime(VatRegime.EU_SERVICES, "IE", "100", null, "21", ExpenseType.CUSTOM, null),
                new BigDecimal("50"), new BigDecimal("80")));
        assertEquals(new BigDecimal("10.50"), custom.deductibleVatEur());
        assertEquals(new BigDecimal("110.50"), custom.expenseEur());
        assertEquals(new BigDecimal("80"), custom.taxDeductiblePct());
        assertEquals("Het aftrekbare percentage ligt tussen 0 en 100", assertThrows(BusinessRuleException.class, () -> service.create(
                withDeduction(regime(VatRegime.BE, null, "100", null, "21", ExpenseType.CUSTOM, null), new BigDecimal("101"), null))).getMessage());
    }

    @Test
    void importVatPaidIsVatOnly() {
        CompanyCost saved = service.create(regime(VatRegime.IMPORT_VAT_PAID, null, "0", "1234.56", null, null, null));

        assertEquals(new BigDecimal("1234.56"), saved.vatEur());
        assertEquals(new BigDecimal("1234.56"), saved.totalInclEur());
        assertEquals(new BigDecimal("1234.56"), saved.returnVatEur());
        assertEquals(new BigDecimal("1234.56"), saved.deductibleVatEur(), "always deducted in full");
        assertEquals(new BigDecimal("0.00"), saved.expenseEur(), "a deduction, not a cost");
        assertEquals(new BigDecimal("0.00"), saved.amountExclEur());
        assertNull(saved.vatPct());
        assertEquals("Invoer-btw: geef het betaalde btw-bedrag op", assertThrows(BusinessRuleException.class,
                () -> service.create(regime(VatRegime.IMPORT_VAT_PAID, null, "10", "1234.56", null, null, null))).getMessage());
        assertThrows(BusinessRuleException.class, () -> service.create(regime(VatRegime.IMPORT_VAT_PAID, null, "0", null, null, null, null)));
    }

    @Test
    void noInvoiceAndExemptCarryNoVat() {
        assertEquals("Deze regeling draagt geen btw", assertThrows(BusinessRuleException.class,
                () -> service.create(regime(VatRegime.NO_INVOICE, null, "25", null, "21", null, null))).getMessage());
        assertThrows(BusinessRuleException.class, () -> service.create(regime(VatRegime.EXEMPT, null, "25", "30", null, null, null)));

        CompanyCost ticket = service.create(regime(VatRegime.NO_INVOICE, null, "25.00", null, null, null, null));
        assertEquals(new BigDecimal("25.00"), ticket.totalInclEur());
        assertEquals(new BigDecimal("0.00"), ticket.returnVatEur());
        assertEquals(new BigDecimal("25.00"), ticket.expenseEur());

        CompanyCost insurance = service.create(regime(VatRegime.EXEMPT, null, "300", "300", "0", null, null));
        assertNull(insurance.vatPct());
        assertNull(insurance.amountInclEur());
        assertEquals(new BigDecimal("300.00"), insurance.totalInclEur());

        CompanyCost bank = service.create(regime(VatRegime.OUT_OF_SCOPE, null, "12.50", null, null, null, null));
        assertEquals(new BigDecimal("12.50"), bank.expenseEur());
        assertFalse(bank.vatRegime().inReturn());
    }

    @Test
    void foreignVatIsCostNotDeduction() {
        CompanyCost hotel = service.create(regime(VatRegime.FOREIGN_VAT, "NL", "100", null, "9", null, null));

        assertEquals(new BigDecimal("109.00"), hotel.totalInclEur());
        assertEquals(new BigDecimal("0.00"), hotel.returnVatEur());
        assertEquals(new BigDecimal("109.00"), hotel.expenseEur());
        assertEquals("Buitenlandse btw: kies een ander land dan België", assertThrows(BusinessRuleException.class,
                () -> service.create(regime(VatRegime.FOREIGN_VAT, "BE", "100", null, "9", null, null))).getMessage());
    }

    @Test
    void investmentNeedsYearsAndDefaultsItsStart() {
        assertEquals("Een investering schrijf je af over 1 tot 50 jaar", assertThrows(BusinessRuleException.class,
                () -> service.create(investment(0, null))).getMessage());
        assertThrows(BusinessRuleException.class, () -> service.create(investment(51, null)));

        CompanyCost laptop = service.create(investment(3, null));
        assertEquals(3, laptop.depreciationYears());
        assertEquals(DAY, laptop.depreciationStart(), "the cost date");
        assertTrue(laptop.investment());
        assertEquals(LocalDate.of(2026, 7, 1), service.create(investment(3, LocalDate.of(2026, 7, 1))).depreciationStart());

        CompanyCost expensed = service.create(regime(VatRegime.BE, null, "100", null, "21", null, CostKind.SERVICES));
        assertNull(expensed.depreciationYears(), "no depreciation outside an investment");
        assertNull(expensed.depreciationStart());
    }

    @Test
    void createWithPaidOnWritesOneFullPayment() {
        CompanyCost saved = service.create(withPaidOn(cost("82.64", "100", "21"), LocalDate.of(2026, 10, 5)));

        assertEquals(1, rows.size());
        CostPayment payment = rows.getFirst();
        assertEquals(saved.id(), payment.companyCostId());
        assertEquals(LocalDate.of(2026, 10, 5), payment.paidOn());
        assertEquals(new BigDecimal("100.00"), payment.amountEur());
        assertNull(payment.account());
        assertEquals("Betaald gezet", payment.note());
        assertEquals(LocalDate.of(2026, 10, 5), saved.paidOn());
        assertEquals(new BigDecimal("0.00"), service.view(saved).openEur());

        rows.clear();
        assertNull(service.create(cost("10", null, null)).paidOn());
        assertTrue(rows.isEmpty(), "without the switch nothing is written");
    }

    @Test
    void putWithPaidOnMovesTheSinglePayment() {
        CompanyCost saved = service.create(withPaidOn(cost("82.64", "100", "21"), LocalDate.of(2026, 10, 5)));
        when(costs.findById(saved.id())).thenReturn(Optional.of(saved));

        CompanyCost moved = service.update(saved.id(), withPaidOn(cost("82.64", "100", "21"), LocalDate.of(2026, 10, 7)));

        assertEquals(1, rows.size(), "the same payment, on another day");
        assertEquals(LocalDate.of(2026, 10, 7), rows.getFirst().paidOn());
        assertEquals(LocalDate.of(2026, 10, 7), moved.paidOn());

        when(costs.findById(saved.id())).thenReturn(Optional.of(moved));
        CompanyCost repriced = service.update(saved.id(), withPaidOn(cost("90", null, "21"), LocalDate.of(2026, 10, 7)));
        assertEquals(new BigDecimal("108.90"), rows.getFirst().amountEur(), "the full payment follows the cash total");
        assertEquals(LocalDate.of(2026, 10, 7), repriced.paidOn());
    }

    @Test
    void putWithPaidOnIgnoresTheDateWithSeveralPayments() {
        CompanyCost saved = service.create(cost("42", null, null));
        when(costs.findById(saved.id())).thenReturn(Optional.of(saved));
        service.addPayment(saved.id(), payment("7", LocalDate.of(2026, 10, 1)));
        service.addPayment(saved.id(), payment("35", LocalDate.of(2026, 10, 3)));

        CompanyCost edited = service.update(saved.id(), withPaidOn(cost("42", null, null), LocalDate.of(2026, 10, 9)));

        assertEquals(LocalDate.of(2026, 10, 3), edited.paidOn(), "the rows decide, not the form");
        assertEquals(List.of(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 3)), rows.stream().map(CostPayment::paidOn).toList());
    }

    @Test
    void putWithPaidOnNullAndPaymentsIsRefused() {
        CompanyCost saved = service.create(withPaidOn(cost("42", null, null), LocalDate.of(2026, 10, 5)));
        when(costs.findById(saved.id())).thenReturn(Optional.of(saved));

        BusinessRuleException refused = assertThrows(BusinessRuleException.class,
                () -> service.update(saved.id(), cost("42", null, null)));
        assertEquals("Verwijder eerst de betalingen", refused.getMessage());
        assertEquals(1, rows.size());
    }

    @Test
    void paymentsDerivePaidOn() {
        CompanyCost saved = service.create(cost("42", null, null));
        when(costs.findById(saved.id())).thenReturn(Optional.of(saved));

        service.addPayment(saved.id(), payment("7", LocalDate.of(2026, 10, 1)));
        CompanyCostService.CostView twice = service.addPayment(saved.id(), payment("7", LocalDate.of(2026, 10, 2)));
        assertEquals(new BigDecimal("28.00"), twice.openEur());
        assertEquals(new BigDecimal("14.00"), twice.paidEur());
        assertEquals(new BigDecimal("42.00"), twice.cashTotalEur());
        assertNull(twice.cost().paidOn());

        CompanyCostService.CostView settled = service.addPayment(saved.id(), payment("28", LocalDate.of(2026, 10, 9)));
        assertEquals(LocalDate.of(2026, 10, 9), settled.cost().paidOn(), "the last payment date");
        assertEquals(new BigDecimal("0.00"), settled.openEur());
        assertEquals(3, settled.payments().size());

        CompanyCostService.CostView reopened = service.deletePayment(saved.id(), settled.payments().getLast().id());
        assertNull(reopened.cost().paidOn(), "open again");
        assertEquals(new BigDecimal("28.00"), reopened.openEur());

        CompanyCostService.CostView withinACent = service.addPayment(saved.id(), payment("27.99", LocalDate.of(2026, 10, 10)));
        assertEquals(LocalDate.of(2026, 10, 10), withinACent.cost().paidOn(), "a cent short still counts as paid");
        assertEquals(new BigDecimal("0.01"), withinACent.openEur());
    }

    @Test
    void overpaymentIsAllowed() {
        CompanyCost saved = service.create(cost("42", null, null));
        when(costs.findById(saved.id())).thenReturn(Optional.of(saved));

        CompanyCostService.CostView view = service.addPayment(saved.id(), payment("50", LocalDate.of(2026, 10, 1)));

        assertEquals(new BigDecimal("50.00"), view.paidEur());
        assertEquals(new BigDecimal("0.00"), view.openEur());
        assertEquals(LocalDate.of(2026, 10, 1), view.cost().paidOn());
    }

    @Test
    void markPaidPaysTheRemainderAndRefusesAPaidCost() {
        CompanyCost saved = service.create(cost("42", null, null));
        when(costs.findById(saved.id())).thenReturn(Optional.of(saved));
        service.addPayment(saved.id(), payment("7", LocalDate.of(2026, 10, 1)));

        CompanyCost paid = service.markPaid(saved.id(), LocalDate.of(2026, 10, 8));

        assertEquals(LocalDate.of(2026, 10, 8), paid.paidOn());
        assertEquals(new BigDecimal("35.00"), rows.getLast().amountEur(), "the remainder");
        assertNull(rows.getLast().note());
        when(costs.findById(saved.id())).thenReturn(Optional.of(paid));
        assertEquals("Deze kost is al volledig betaald", assertThrows(BusinessRuleException.class,
                () -> service.markPaid(saved.id(), null)).getMessage());
    }

    @Test
    void aPaymentNeedsAnAmountInCentsAndADayNotInTheFuture() {
        CompanyCost saved = service.create(cost("42", null, null));
        when(costs.findById(saved.id())).thenReturn(Optional.of(saved));

        assertEquals("Geef een bedrag groter dan nul op", assertThrows(BusinessRuleException.class,
                () -> service.addPayment(saved.id(), payment("0", DAY))).getMessage());
        assertEquals("Gebruik maximaal twee decimalen", assertThrows(BusinessRuleException.class,
                () -> service.addPayment(saved.id(), payment("1.005", DAY))).getMessage());
        assertEquals("Een betaling kan niet in de toekomst liggen", assertThrows(BusinessRuleException.class,
                () -> service.addPayment(saved.id(), payment("1", LocalDate.now().plusDays(1)))).getMessage());
        assertThrows(BusinessRuleException.class, () -> service.addPayment(saved.id(), new CostPayment(null, null, null, BigDecimal.ONE,
                null, null, null, null, null)));
        assertTrue(rows.isEmpty());
    }

    @Test
    void paymentOnTheBankCannotChangeAmountDateOrAccount() {
        CompanyCost saved = service.create(cost("42", null, null));
        when(costs.findById(saved.id())).thenReturn(Optional.of(saved));
        CostPayment noted = service.addPayment(saved.id(), payment("7", LocalDate.of(2026, 10, 1))).payments().getFirst();
        rows.set(0, new CostPayment(noted.id(), noted.companyCostId(), noted.paidOn(), noted.amountEur(), "KBC", 77L, null,
                noted.recordedAt(), noted.actor()));

        assertEquals("Deze betaling staat op de bank; maak de bankbeweging eerst los", assertThrows(BusinessRuleException.class,
                () -> service.updatePayment(saved.id(), noted.id(), payment("8", LocalDate.of(2026, 10, 1)))).getMessage());
        assertThrows(BusinessRuleException.class,
                () -> service.updatePayment(saved.id(), noted.id(), payment("7", LocalDate.of(2026, 10, 2))));
        assertThrows(BusinessRuleException.class, () -> service.updatePayment(saved.id(), noted.id(),
                new CostPayment(null, null, noted.paidOn(), new BigDecimal("7"), "ING", null, "Deel 1", null, null)));
        CompanyCostService.CostView renoted = service.updatePayment(saved.id(), noted.id(),
                new CostPayment(null, null, noted.paidOn(), new BigDecimal("7"), "KBC", 5L, "Deel 1", null, null));
        assertEquals("KBC", renoted.payments().getFirst().account(), "the account remains the bank transaction's account");
        assertEquals("Deel 1", renoted.payments().getFirst().note());
        assertEquals(77L, renoted.payments().getFirst().bankStatementLineId(), "the bank link is the bank's, not the form's");

        CompanyCostService.CostView gone = service.deletePayment(saved.id(), noted.id());
        assertTrue(gone.payments().isEmpty(), "deleting a banked payment is allowed: the line stays, the link goes with the row");
    }

    @Test
    void deletingTheLastPaymentReopensTheCostAndClearsItsOneShotBankLink() {
        CompanyCost booked = new CompanyCost(9L, DAY, "BANK", "Rekeningkosten", "KBC", new BigDecimal("42.00"),
                null, null, null, DAY, null, null, Instant.parse("2026-10-12T09:00:00Z"), null, 77L);
        when(costs.findById(9L)).thenReturn(Optional.of(booked));
        rows.add(new CostPayment(11L, 9L, DAY, new BigDecimal("42.00"), "BE12345678901234", 77L,
                null, Instant.parse("2026-10-12T09:00:00Z"), "Emre"));

        CompanyCostService.CostView reopened = service.deletePayment(9L, 11L);

        assertTrue(reopened.payments().isEmpty());
        assertNull(reopened.cost().paidOn(), "an explicitly deleted payment must not become a legacy paid cost");
        assertNull(reopened.cost().bankStatementLineId(), "no stale reverse allocation remains on the bank transaction");
        assertEquals(new BigDecimal("0.00"), reopened.paidEur());
        assertEquals(new BigDecimal("42.00"), reopened.openEur());
    }

    @Test
    void deletingABankedPaymentKeepsOtherPaymentsAndTheirBankLinks() {
        CompanyCost booked = new CompanyCost(9L, DAY, "BANK", "Rekeningkosten", "KBC", new BigDecimal("42.00"),
                null, null, null, DAY, null, null, Instant.parse("2026-10-12T09:00:00Z"), null, 77L);
        when(costs.findById(9L)).thenReturn(Optional.of(booked));
        rows.add(new CostPayment(11L, 9L, DAY, new BigDecimal("30.00"), "KBC", 77L, null, Instant.now(), "Emre"));
        rows.add(new CostPayment(12L, 9L, DAY, new BigDecimal("12.00"), "KBC", 78L, null, Instant.now(), "Emre"));

        CompanyCostService.CostView reopened = service.deletePayment(9L, 11L);

        assertEquals(1, reopened.payments().size());
        assertEquals(78L, reopened.payments().getFirst().bankStatementLineId());
        assertNull(reopened.cost().bankStatementLineId());
        assertNull(reopened.cost().paidOn());
        assertEquals(new BigDecimal("12.00"), reopened.paidEur());
        assertEquals(new BigDecimal("30.00"), reopened.openEur());
    }

    @Test
    void deleteRemovesPaymentsFirst() {
        CompanyCost existing = new CompanyCost(9L, LocalDate.of(2026, 1, 5), "BOEKHOUDER", "Jaarrekening", "Accountant BV",
                new BigDecimal("900.00"), new BigDecimal("21"), null, null, null, null, Instant.parse("2026-01-05T10:00:00Z"));
        when(costs.findById(9L)).thenReturn(Optional.of(existing));

        service.delete(9L);

        InOrder order = inOrder(payments, costs);
        order.verify(payments).deleteByCost(9L);
        order.verify(costs).deleteById(9L);
    }

    @Test
    void legacyPaidCostWithoutRowsReadsFullyPaid() {
        CompanyCost legacy = new CompanyCost(7L, LocalDate.of(2026, 9, 8), "BANK", "Kosten overschrijving", "KBC",
                new BigDecimal("12.50"), null, null, null, LocalDate.of(2026, 9, 8), null, null,
                Instant.parse("2026-09-08T10:00:00Z"), null, 41L);

        CompanyCostService.PaymentTotals totals = CompanyCostService.totalsOf(legacy, List.of());
        assertEquals(new BigDecimal("12.50"), totals.paidEur());
        assertEquals(new BigDecimal("0.00"), totals.openEur());
        assertEquals(LocalDate.of(2026, 9, 8), totals.paidOn());

        CompanyCostService.CostView view = service.view(legacy);
        assertTrue(view.payments().isEmpty());
        assertEquals(new BigDecimal("0.00"), view.openEur());
        assertEquals(new BigDecimal("12.50"), view.cashTotalEur());

        CompanyCost open = new CompanyCost(8L, LocalDate.of(2026, 9, 8), "BANK", "Kosten", "KBC",
                new BigDecimal("12.50"), null, null, null, null, null, null, Instant.parse("2026-09-08T10:00:00Z"), null, null);
        assertEquals(new BigDecimal("12.50"), CompanyCostService.totalsOf(open, List.of()).openEur());
    }

    private static CompanyCost cost(String excl, String incl, String vat) {
        return be(new CompanyCost(null, DAY, "HUUR", "Magazijn", "Immo", new BigDecimal(excl),
                incl == null ? null : new BigDecimal(incl), vat == null ? null : new BigDecimal(vat),
                null, null, null, null, null, null, null));
    }

    private static CompanyCost regime(VatRegime regime, String country, String excl, String incl, String vat, ExpenseType type, CostKind kind) {
        return new CompanyCost(null, DAY, "SOFTWARE", "Google Workspace", "Google", new BigDecimal(excl),
                incl == null ? null : new BigDecimal(incl), vat == null ? null : new BigDecimal(vat),
                null, null, null, null, null, null, null, regime, true, country, kind, type, null, null, null, null);
    }

    private static CompanyCost investment(int years, LocalDate start) {
        return new CompanyCost(null, DAY, "MATERIAAL", "Laptop", "Coolblue", new BigDecimal("3000"), null, new BigDecimal("21"),
                null, null, null, null, null, null, null, VatRegime.BE, true, "BE", CostKind.INVESTMENT, null, null, null, years, start);
    }

    private static CompanyCost withDeduction(CompanyCost cost, BigDecimal vatPct, BigDecimal taxPct) {
        return new CompanyCost(cost.id(), cost.date(), cost.category(), cost.description(), cost.party(), cost.amountExclEur(),
                cost.amountInclEur(), cost.vatPct(), cost.reference(), cost.paidOn(), cost.salesChannel(), cost.notes(), cost.createdAt(),
                cost.recurringCostId(), cost.bankStatementLineId(), cost.vatRegime(), cost.vatRegimeConfirmed(), cost.supplierCountry(),
                cost.costKind(), cost.expenseType(), vatPct, taxPct, cost.depreciationYears(), cost.depreciationStart());
    }

    private static CompanyCost withPaidOn(CompanyCost cost, LocalDate paidOn) {
        return new CompanyCost(cost.id(), cost.date(), cost.category(), cost.description(), cost.party(), cost.amountExclEur(),
                cost.amountInclEur(), cost.vatPct(), cost.reference(), paidOn, cost.salesChannel(), cost.notes(), cost.createdAt(),
                cost.recurringCostId(), cost.bankStatementLineId(), cost.vatRegime(), cost.vatRegimeConfirmed(), cost.supplierCountry(),
                cost.costKind(), cost.expenseType(), cost.vatDeductiblePct(), cost.taxDeductiblePct(), cost.depreciationYears(),
                cost.depreciationStart());
    }

    /** A Belgian invoice, confirmed: the regime every round-one booking took. */
    private static CompanyCost be(CompanyCost cost) {
        return withRegime(cost, VatRegime.BE, true, "BE");
    }

    private static CompanyCost withRegime(CompanyCost cost, VatRegime regime, boolean confirmed, String country) {
        return new CompanyCost(cost.id(), cost.date(), cost.category(), cost.description(), cost.party(), cost.amountExclEur(),
                cost.amountInclEur(), cost.vatPct(), cost.reference(), cost.paidOn(), cost.salesChannel(), cost.notes(), cost.createdAt(),
                cost.recurringCostId(), cost.bankStatementLineId(), regime, confirmed, country, cost.costKind(), cost.expenseType(),
                cost.vatDeductiblePct(), cost.taxDeductiblePct(), cost.depreciationYears(), cost.depreciationStart());
    }

    private static CompanyCost withId(CompanyCost cost, long id) {
        return new CompanyCost(id, cost.date(), cost.category(), cost.description(), cost.party(), cost.amountExclEur(),
                cost.amountInclEur(), cost.vatPct(), cost.reference(), cost.paidOn(), cost.salesChannel(), cost.notes(), cost.createdAt(),
                cost.recurringCostId(), cost.bankStatementLineId(), cost.vatRegime(), cost.vatRegimeConfirmed(), cost.supplierCountry(),
                cost.costKind(), cost.expenseType(), cost.vatDeductiblePct(), cost.taxDeductiblePct(), cost.depreciationYears(),
                cost.depreciationStart());
    }

    private static CostPayment payment(String amount, LocalDate day) {
        return new CostPayment(null, null, day, new BigDecimal(amount), null, null, null, null, null);
    }
}
