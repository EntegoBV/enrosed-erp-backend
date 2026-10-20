package be.enrosed.finance.application;

import be.enrosed.finance.domain.CompanyCost;
import be.enrosed.finance.domain.CostKind;
import be.enrosed.finance.domain.CostPayment;
import be.enrosed.finance.domain.ExpenseType;
import be.enrosed.finance.domain.VatRegime;
import be.enrosed.shared.BusinessRuleException;
import be.enrosed.shared.NotFoundException;
import be.enrosed.shared.audit.ActivityLogService;
import be.enrosed.shared.security.CurrentActor;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Booking the company's own costs: what was paid, when, to whom and why,
 * under which VAT regime, and in how many payments. "Betaald op" is never
 * typed: it follows from the payments (the last payment date once they
 * cover the cash total), so every "open" figure reads the remainder.
 */
@ApplicationScoped
public class CompanyCostService {

    public static final String ACTIVITY_ENTITY = "COMPANY_COST";
    private static final Logger LOG = Logger.getLogger(CompanyCostService.class);
    private static final BigDecimal HUNDRED = new BigDecimal("100");
    /** How far an entered inclusive total may sit from excl. plus the rate: the rounding of either side. */
    private static final BigDecimal TOLERANCE = new BigDecimal("0.02");

    /** The company's calendar day: Brussels, not the server's UTC clock, so a payment typed just after midnight is not "tomorrow". */
    private static LocalDate today() {
        return LocalDate.now(ZoneId.of("Europe/Brussels"));
    }
    /** A cost is paid once the payments reach its cash total within a cent. */
    private static final BigDecimal CENT = new BigDecimal("0.01");
    /** The 27 member states; XI (Northern Ireland) counts as EU for goods only. */
    static final Set<String> EU_MEMBERS = Set.of("AT", "BE", "BG", "HR", "CY", "CZ", "DK", "EE", "FI", "FR", "DE", "GR",
            "HU", "IE", "IT", "LV", "LT", "LU", "MT", "NL", "PL", "PT", "RO", "SK", "SI", "ES", "SE");
    private static final int ACCOUNT_LENGTH = 120;
    private static final int NOTE_LENGTH = 300;

    private final CompanyCosts costs;
    private final CostPayments payments;

    @Inject
    Instance<ActivityLogService> activity;

    @Inject
    Instance<be.enrosed.media.MediaService> media;

    /** The list "Aan wie" learns every name a cost is booked to; absent in the unit tests, which build the service by hand. */
    @Inject
    Instance<CostPartyService> parties;

    /** Who notes a payment; absent in the unit tests. */
    @Inject
    Instance<CurrentActor> actor;

    @Inject
    Instance<be.enrosed.finance.banking.BankStatementService> banking;
    @Inject Instance<be.enrosed.finance.odoo.OdooCostImportStore> imported;
    @Inject Instance<be.enrosed.tica.TicaSettlementGuard> ticaSettlements;

    public CompanyCostService(CompanyCosts costs, CostPayments payments) {
        this.costs = costs;
        this.payments = payments;
    }

    /** A cost as the screen gets it: its fields at the top level, its payments and what they add up to. */
    public record CostView(@JsonUnwrapped CompanyCost cost, List<CostPayment> payments, BigDecimal paidEur,
                           BigDecimal openEur, BigDecimal cashTotalEur,
                           @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) Long ticaSettlementId) {
        public CostView(CompanyCost cost, List<CostPayment> payments, BigDecimal paidEur, BigDecimal openEur, BigDecimal cashTotalEur) {
            this(cost, payments, paidEur, openEur, cashTotalEur, null);
        }
    }

    /** What the payments of a cost add up to and the "Betaald op" they imply. */
    public record PaymentTotals(BigDecimal paidEur, BigDecimal openEur, LocalDate paidOn) {}

    /**
     * The totals of a cost over its payments. A cost from before the payment
     * rows that carries "Betaald op" without a row reads as paid in full on
     * that day; otherwise the rows decide: paid once they reach the cash
     * total within a cent, on the last payment date, open for the rest.
     */
    public static PaymentTotals totalsOf(CompanyCost cost, List<CostPayment> payments) {
        BigDecimal cash = cost.totalInclEur();
        if ((payments == null || payments.isEmpty()) && cost.paidOn() != null) {
            return new PaymentTotals(cash, BigDecimal.ZERO.setScale(2), cost.paidOn());
        }
        BigDecimal paid = BigDecimal.ZERO.setScale(2);
        LocalDate last = null;
        for (CostPayment payment : payments == null ? List.<CostPayment>of() : payments) {
            paid = paid.add(payment.amountEur() == null ? BigDecimal.ZERO : payment.amountEur());
            if (payment.paidOn() != null && (last == null || payment.paidOn().isAfter(last))) last = payment.paidOn();
        }
        int direction=cash.signum()<0?-1:1;
        boolean settled = paid.multiply(BigDecimal.valueOf(direction)).compareTo(cash.abs().subtract(CENT)) >= 0;
        /* Settled within a cent reads open 0.00: one "open" figure, so a cost paid a cent short never shows 'Deels' next to 'Betaald'. */
        BigDecimal open = settled ? BigDecimal.ZERO.setScale(2) : cash.subtract(paid).setScale(2, RoundingMode.HALF_UP);
        return new PaymentTotals(paid.setScale(2, RoundingMode.HALF_UP), open, settled ? last : null);
    }

    public List<CompanyCost> list(LocalDate from, LocalDate to) {
        if (from != null && to != null && to.isBefore(from)) {
            throw new BusinessRuleException("De einddatum ligt voor de begindatum");
        }
        return costs.findBetween(from, to);
    }

    public CompanyCost get(long id) {
        return costs.findById(id).orElseThrow(() -> new NotFoundException("Kost", id));
    }

    public CostView view(CompanyCost cost) {
        CostView result = view(cost, cost.id() == null ? List.of() : payments.findByCost(cost.id()));
        Long settlement = cost.id() == null ? null : ticaCostLinks(List.of(cost.id())).get(cost.id());
        return new CostView(result.cost(), result.payments(), result.paidEur(), result.openEur(), result.cashTotalEur(), settlement);
    }

    /** One query for the payments of every cost in the list. */
    public List<CostView> views(List<CompanyCost> list) {
        Map<Long, List<CostPayment>> byCost = payments.findByCosts(list.stream().map(CompanyCost::id).filter(id -> id != null).toList());
        Map<Long, Long> settlements = ticaCostLinks(list.stream().map(CompanyCost::id).filter(java.util.Objects::nonNull).toList());
        return list.stream().map(cost -> {
            CostView base = view(cost, byCost.getOrDefault(cost.id(), List.of()));
            return new CostView(base.cost(), base.payments(), base.paidEur(), base.openEur(), base.cashTotalEur(), settlements.get(cost.id()));
        }).toList();
    }
    private Map<Long, Long> ticaCostLinks(java.util.Collection<Long> ids) {
        return ticaSettlements != null && ticaSettlements.isResolvable() ? ticaSettlements.get().costLinks(ids) : Map.of();
    }

    public List<CostPayment> paymentsOf(long costId) {
        get(costId);
        return payments.findByCost(costId);
    }

    private static CostView view(CompanyCost cost, List<CostPayment> rows) {
        PaymentTotals totals = totalsOf(cost, rows);
        return new CostView(cost, rows, totals.paidEur(), totals.openEur(), cost.totalInclEur());
    }

    @Transactional
    public CompanyCost create(CompanyCost cost) {
        /* The bank link is the bank service's to set, never the form's. */
        CompanyCost clean = validated(cost, null, Instant.now(), null, null);
        CompanyCost saved = costs.save(clean);
        if (saved.paidOn() != null) {
            /* The "Betaald" switch: one payment for the whole cash total on that day. */
            payments.save(new CostPayment(null, saved.id(), saved.paidOn(), saved.totalInclEur(), null, null, "Betaald gezet",
                    Instant.now(), actorName()));
        }
        saved = derivePaidOn(saved);
        recordActivity(ActivityLogService.ACTION_CREATED, saved, "Kost geboekt");
        learnParty(saved);
        return saved;
    }

    /** Provider totals are original document facts, including mixed VAT rates and supplier credits. */
    @Transactional
    public CompanyCost createImported(be.enrosed.finance.odoo.OdooDocumentService.PurchaseSnapshot snapshot,String category) {
        if(snapshot==null||snapshot.totalEur()==null||snapshot.totalEur().signum()==0
                ||snapshot.netEur().add(snapshot.vatEur()).compareTo(snapshot.totalEur())!=0)
            throw new BusinessRuleException("De ontvangen factuur heeft ongeldige bedragen");
        var document=snapshot.document();
        String reference=document.reference().isBlank()?document.name():document.reference();
        String description=(snapshot.totalEur().signum()<0?"Leverancierscreditnota ":"Ontvangen factuur ")+reference;
        String selected=clean(category);if(selected==null)selected="OVERIG";
        if(selected.length()>60)throw new BusinessRuleException("De categorie is maximaal 60 tekens");
        var cost=new CompanyCost(null,LocalDate.parse(document.date()),selected.toUpperCase(Locale.ROOT),
                description.substring(0,Math.min(300,description.length())),document.partner().substring(0,Math.min(200,document.partner().length())),
                snapshot.netEur(),snapshot.totalEur(),null,reference.substring(0,Math.min(120,reference.length())),null,null,
                "Overgenomen uit Odoo. Controleer de btw-regeling en aftrekbaarheid aan de hand van de originele factuur.",
                Instant.now(),null,null,null,false,snapshot.supplierCountry(),null,null,null,null,null,null,null);
        var saved=costs.save(cost);recordActivity(ActivityLogService.ACTION_CREATED,saved,"Ontvangen Odoo-document geregistreerd; originele bedragen behouden");return saved;
    }

    /**
     * The payment rows decide "Betaald op"; the form's date only counts
     * while there are none (the switch: one full payment) or while the one
     * payment is not on the bank yet (its date moves). Taking the date away
     * from a settled cost with rows is refused: the payments go first. A cost
     * the rows do not cover yet carries no date, so a body without one (the
     * view as the screen holds it) changes nothing there: the rows decide.
     */
    @Transactional
    public CompanyCost update(long id, CompanyCost changes) {
        requireNotTicaCost(id);
        if(changes==null)throw new BusinessRuleException("Geen kostgegevens meegestuurd");
        CompanyCost current = costs.findByIdForUpdate(id).orElseThrow(() -> new NotFoundException("Kost", id));
        boolean sourceDocument=imported!=null&&imported.isResolvable()&&imported.get().isImported(id);
        if(sourceDocument&&changes!=null) {
            if(changes.amountExclEur().compareTo(current.amountExclEur())!=0
                    ||!sameMoney(changes.amountInclEur(),current.amountInclEur())||!java.util.Objects.equals(changes.date(),current.date())
                    ||!java.util.Objects.equals(changes.reference(),current.reference())||!java.util.Objects.equals(changes.party(),current.party()))
                throw new BusinessRuleException("De originele ontvangen factuurgegevens blijven behouden; corrigeer de bronfactuur in Odoo");
        }
        List<CostPayment> rows = payments.findByCost(id);
        if (changes != null && changes.paidOn() == null && current.paidOn() != null && !rows.isEmpty()) {
            throw new BusinessRuleException("Verwijder eerst de betalingen");
        }
        /* The link to the recurring definition is the booking job's, not the form's. */
        CompanyCost linked = changes == null ? null : new CompanyCost(null, changes.date(), changes.category(),
                changes.description(), changes.party(), changes.amountExclEur(), changes.amountInclEur(), changes.vatPct(),
                changes.reference(), changes.paidOn(), changes.salesChannel(), changes.notes(), null,
                current.recurringCostId(), null, changes.vatRegime(), changes.vatRegimeConfirmed(), changes.supplierCountry(),
                changes.costKind(), changes.expenseType(), changes.vatDeductiblePct(), changes.taxDeductiblePct(),
                changes.depreciationYears(), changes.depreciationStart(),
                /* The due date is its own endpoint's: a PUT (also from an older screen that never sends it) keeps it. */
                current.dueDate());
        CompanyCost saved = costs.save(sourceDocument?validatedImportedUpdate(linked,current):validated(linked, current.id(), current.createdAt(), current.bankStatementLineId(), current));
        if (saved.paidOn() != null) {
            if (rows.isEmpty()) {
                /* The switch on a cost without rows, also a cost from before the rows: one full payment, on the bank where the cost is. */
                payments.save(new CostPayment(null, saved.id(), saved.paidOn(), saved.totalInclEur(), null,
                        current.bankStatementLineId(), "Betaald gezet", Instant.now(), actorName()));
            } else if (rows.size() == 1 && !rows.getFirst().onTheBank()) {
                CostPayment only = rows.getFirst();
                boolean wasFull = only.amountEur() != null && only.amountEur().compareTo(current.totalInclEur()) == 0;
                BigDecimal amount = wasFull ? saved.totalInclEur() : only.amountEur();
                if (!saved.paidOn().equals(only.paidOn()) || amount.compareTo(only.amountEur()) != 0) {
                    payments.save(new CostPayment(only.id(), only.companyCostId(), saved.paidOn(), amount, only.account(),
                            only.bankStatementLineId(), only.note(), only.recordedAt(), only.actor()));
                }
            }
            /* Several rows, or one on the bank: the rows decide, the form's date is ignored. */
        }
        saved = derivePaidOn(saved);
        recordActivity(ActivityLogService.ACTION_UPDATED, saved, "Kost bijgewerkt");
        learnParty(saved);
        return saved;
    }

    /** Settles what is still open on a day: the accountant's invoice was paid, the direct debit went through. */
    @Transactional
    public CompanyCost markPaid(long id, LocalDate paidOn) {
        requireNotTicaCost(id);
        CompanyCost current = costs.findByIdForUpdate(id).orElseThrow(() -> new NotFoundException("Kost", id));
        PaymentTotals totals = totalsOf(current, payments.findByCost(id));
        if (totals.openEur().signum() == 0) throw new BusinessRuleException("Deze kost is al volledig betaald");
        LocalDate day = paidOn == null ? today() : paidOn;
        payments.save(new CostPayment(null, id, day, totals.openEur(), null, null, null, Instant.now(), actorName()));
        CompanyCost saved = derivePaidOn(current);
        recordActivity(ActivityLogService.ACTION_UPDATED, saved, "Kost betaald op " + day);
        return saved;
    }

    /**
     * When the invoice falls due, for the cash line; null clears it. The one write of the due date after
     * creation: the cost's own PUT keeps the stored value.
     */
    @Transactional
    public CostView setDueDate(long id, LocalDate dueDate) {
        requireNotTicaCost(id);
        CompanyCost current = costs.findByIdForUpdate(id).orElseThrow(() -> new NotFoundException("Kost", id));
        requireDueDate(current.date(), dueDate);
        if (java.util.Objects.equals(dueDate, current.dueDate())) return view(current);
        CompanyCost saved = costs.save(new CompanyCost(current.id(), current.date(), current.category(), current.description(),
                current.party(), current.amountExclEur(), current.amountInclEur(), current.vatPct(), current.reference(),
                current.paidOn(), current.salesChannel(), current.notes(), current.createdAt(), current.recurringCostId(),
                current.bankStatementLineId(), current.vatRegime(), current.vatRegimeConfirmed(), current.supplierCountry(),
                current.costKind(), current.expenseType(), current.vatDeductiblePct(), current.taxDeductiblePct(),
                current.depreciationYears(), current.depreciationStart(), dueDate));
        recordActivity(ActivityLogService.ACTION_UPDATED, saved, dueDate == null ? "Vervaldag gewist" : "Vervalt op " + dueDate);
        return view(saved);
    }

    private static void requireDueDate(LocalDate date, LocalDate dueDate) {
        if (dueDate != null && date != null && dueDate.isBefore(date)) {
            throw new BusinessRuleException("De vervaldag ligt vóór de kostdatum");
        }
    }

    @Transactional
    public CostView addPayment(long costId, CostPayment request) {
        requireNotTicaCost(costId);
        CompanyCost cost = costs.findByIdForUpdate(costId).orElseThrow(() -> new NotFoundException("Kost", costId));
        CostPayment clean = paymentChecked(request,cost.totalInclEur().signum());
        CostPayment saved = payments.save(new CostPayment(null, costId, clean.paidOn(), clean.amountEur(), clean.account(), null,
                clean.note(), Instant.now(), actorName()));
        CompanyCost derived = derivePaidOn(cost);
        recordActivity(ActivityLogService.ACTION_UPDATED, derived, "Betaling van € " + saved.amountEur().toPlainString() + " genoteerd");
        return view(derived);
    }

    /** A payment on the bank keeps its amount, date and account: unlink it before correcting those facts. */
    @Transactional
    public CostView updatePayment(long costId, long paymentId, CostPayment request) {
        requireNotTicaCost(costId);
        CompanyCost cost = costs.findByIdForUpdate(costId).orElseThrow(() -> new NotFoundException("Kost", costId));
        CostPayment current = paymentOf(costId, paymentId);
        CostPayment clean = paymentChecked(request,cost.totalInclEur().signum());
        if (current.onTheBank() && (clean.amountEur().compareTo(current.amountEur()) != 0 || !clean.paidOn().equals(current.paidOn())
                || !java.util.Objects.equals(clean.account(), current.account()))) {
            throw new BusinessRuleException("Deze betaling staat op de bank; maak de bankbeweging eerst los");
        }
        payments.save(new CostPayment(current.id(), costId, clean.paidOn(), clean.amountEur(), clean.account(),
                current.bankStatementLineId(), clean.note(), current.recordedAt(), current.actor()));
        CompanyCost derived = derivePaidOn(cost);
        recordActivity(ActivityLogService.ACTION_UPDATED, derived, "Betaling gewijzigd");
        return view(derived);
    }

    /** A standalone banked payment may be removed; a shared transfer must first be explicitly unlinked. */
    @Transactional
    public CostView deletePayment(long costId, long paymentId) {
        requireNotTicaCost(costId);
        CompanyCost cost = costs.findByIdForUpdate(costId).orElseThrow(() -> new NotFoundException("Kost", costId));
        CostPayment removed = paymentOf(costId, paymentId);
        requireExclusiveBankLink(removed.bankStatementLineId(), costId, paymentId);
        payments.deleteById(paymentId);
        List<CostPayment> remaining = payments.findByCost(costId);
        // An explicit deletion is not a historical cost without payment rows: reopen it when the last row goes.
        LocalDate paidOn = remaining.isEmpty() ? null : totalsOf(cost, remaining).paidOn();
        Long bankLine = cost.bankStatementLineId();
        if (remaining.isEmpty() || bankLine != null && java.util.Objects.equals(bankLine, removed.bankStatementLineId())
                && remaining.stream().noneMatch(payment -> java.util.Objects.equals(cost.bankStatementLineId(), payment.bankStatementLineId()))) {
            bankLine = null;
        }
        CompanyCost derived = paymentState(cost, paidOn, bankLine);
        recordActivity(ActivityLogService.ACTION_UPDATED, derived, "Betaling verwijderd");
        return view(derived);
    }

    @Transactional
    public void delete(long id) {
        requireNotTicaCost(id);
        CompanyCost cost = costs.findByIdForUpdate(id).orElseThrow(() -> new NotFoundException("Kost", id));
        var bankIds = new java.util.HashSet<Long>();
        if (cost.bankStatementLineId() != null) bankIds.add(cost.bankStatementLineId());
        payments.findByCost(id).stream().map(CostPayment::bankStatementLineId)
                .filter(java.util.Objects::nonNull).forEach(bankIds::add);
        for (Long bankId : bankIds) requireExclusiveBankLink(bankId, id, null);
        /* The payments go first (no foreign key holds them); the invoice stays in the library, only its link to this cost goes. */
        payments.deleteByCost(id);
        if (media != null && media.isResolvable()) media.get().unlinkTarget(be.enrosed.media.MediaTargetType.COMPANY_COST, id);
        costs.deleteById(id);
        recordActivity(ActivityLogService.ACTION_DELETED, cost, "Kost verwijderd");
    }

    private void requireExclusiveBankLink(Long bankId, long costId, Long paymentId) {
        if (bankId != null && banking != null && banking.isResolvable()) {
            banking.get().requireExclusiveCostLink(bankId, costId, paymentId);
        }
    }

    /** "Betaald op" as the payments imply it; a cost from before the rows keeps its date until it gets rows. */
    private CompanyCost derivePaidOn(CompanyCost cost) {
        if (cost.id() == null) return cost;
        List<CostPayment> rows = payments.findByCost(cost.id());
        if (rows.isEmpty() && cost.paidOn() != null) return cost;
        LocalDate derived = totalsOf(cost, rows).paidOn();
        return paymentState(cost, derived, cost.bankStatementLineId());
    }

    private CompanyCost paymentState(CompanyCost cost, LocalDate paidOn, Long bankStatementLineId) {
        if (java.util.Objects.equals(paidOn, cost.paidOn())
                && java.util.Objects.equals(bankStatementLineId, cost.bankStatementLineId())) return cost;
        return costs.save(new CompanyCost(cost.id(), cost.date(), cost.category(), cost.description(), cost.party(),
                cost.amountExclEur(), cost.amountInclEur(), cost.vatPct(), cost.reference(), paidOn, cost.salesChannel(),
                cost.notes(), cost.createdAt(), cost.recurringCostId(), bankStatementLineId, cost.vatRegime(),
                cost.vatRegimeConfirmed(), cost.supplierCountry(), cost.costKind(), cost.expenseType(), cost.vatDeductiblePct(),
                cost.taxDeductiblePct(), cost.depreciationYears(), cost.depreciationStart(), cost.dueDate()));
    }

    private CostPayment paymentOf(long costId, long paymentId) {
        return payments.findById(paymentId).filter(payment -> payment.companyCostId() != null && payment.companyCostId() == costId)
                .orElseThrow(() -> new NotFoundException("Betaling", paymentId));
    }

    /** A payment as the form sends it: a positive amount in cents, a day not in the future, short texts; the bank link is the bank's. */
    private void requireNotTicaCost(long id) {
        if (ticaSettlements != null && ticaSettlements.isResolvable()) ticaSettlements.get().requireEditableCost(id);
    }

    private static CostPayment paymentChecked(CostPayment request,int costDirection) {
        if (request == null) throw new BusinessRuleException("Geen betaling meegestuurd");
        if (request.amountEur() == null || request.amountEur().signum()==0 || request.amountEur().signum()!=(costDirection<0?-1:1)) {
            throw new BusinessRuleException(costDirection<0?"Een leverancierscreditnota krijgt een negatieve terugbetaling":"Geef een bedrag groter dan nul op");
        }
        BigDecimal amount;
        try {
            amount = request.amountEur().setScale(2, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException fraction) {
            throw new BusinessRuleException("Gebruik maximaal twee decimalen");
        }
        if (request.paidOn() == null) throw new BusinessRuleException("Geef de dag van de betaling");
        if (request.paidOn().isAfter(today())) throw new BusinessRuleException("Een betaling kan niet in de toekomst liggen");
        String account = clean(request.account());
        if (account != null && account.length() > ACCOUNT_LENGTH) throw new BusinessRuleException("De rekening is maximaal 120 tekens");
        String note = clean(request.note());
        if (note != null && note.length() > NOTE_LENGTH) throw new BusinessRuleException("De opmerking is maximaal 300 tekens");
        return new CostPayment(null, null, request.paidOn(), amount, account, null, note, null, null);
    }

    /**
     * The bank line is the stored cost's or none: the bank service sets it, a
     * form never does. The regime fields follow the rules of the regime; on
     * an update a body without a regime keeps the stored one (rows from
     * before the regimes; the sheet enforces the pick).
     */
    private static CompanyCost validated(CompanyCost cost, Long id, Instant createdAt, Long bankStatementLineId, CompanyCost current) {
        if (cost == null) throw new BusinessRuleException("Geen kostgegevens meegestuurd");
        if (cost.date() == null) throw new BusinessRuleException("Geef de datum van de kost");
        String category = clean(cost.category());
        if (category == null) throw new BusinessRuleException("Kies een categorie");
        String description = clean(cost.description());
        if (description == null) throw new BusinessRuleException("Omschrijf de kost");
        if (cost.amountExclEur() == null || cost.amountExclEur().signum() < 0) {
            throw new BusinessRuleException("Het bedrag kan niet negatief zijn");
        }
        if (cost.vatPct() != null && (cost.vatPct().signum() < 0 || cost.vatPct().compareTo(HUNDRED) > 0)) {
            throw new BusinessRuleException("Het btw-percentage ligt tussen 0 en 100");
        }
        BigDecimal excl = cost.amountExclEur().setScale(2, RoundingMode.HALF_UP);
        String channel = clean(cost.salesChannel());
        VatRegime regime = cost.vatRegime();
        Boolean confirmed = cost.vatRegimeConfirmed();
        if (regime == null) {
            if (current == null) throw new BusinessRuleException("Kies de btw-regeling");
            regime = current.vatRegime();
            confirmed = current.vatRegimeConfirmed();
        }
        RegimeFields fields = regimeChecked(regime, confirmed, cost.supplierCountry(), cost.costKind(), cost.expenseType(),
                cost.vatDeductiblePct(), cost.taxDeductiblePct(), excl, cost.amountInclEur(), cost.vatPct());
        Integer years = null;
        LocalDate start = null;
        if (fields.costKind() == CostKind.INVESTMENT) {
            if (cost.depreciationYears() == null || cost.depreciationYears() < 1 || cost.depreciationYears() > 50) {
                throw new BusinessRuleException("Een investering schrijf je af over 1 tot 50 jaar");
            }
            years = cost.depreciationYears();
            start = cost.depreciationStart() == null ? cost.date() : cost.depreciationStart();
        }
        /* A new cost is checked here; a stored due date travels unchecked (its own endpoint checks it). */
        if (current == null) requireDueDate(cost.date(), cost.dueDate());
        return new CompanyCost(id, cost.date(), category.toUpperCase(), description, clean(cost.party()),
                excl, fields.amountInclEur(), fields.vatPct(),
                clean(cost.reference()), cost.paidOn(),
                channel == null ? null : channel.toUpperCase(), clean(cost.notes()),
                createdAt == null ? Instant.now() : createdAt, cost.recurringCostId(), bankStatementLineId,
                fields.vatRegime(), fields.vatRegimeConfirmed(), fields.supplierCountry(), fields.costKind(), fields.expenseType(),
                fields.vatDeductiblePct(), fields.taxDeductiblePct(), years, start, cost.dueDate());
    }

    private static boolean sameMoney(BigDecimal first,BigDecimal second) {return first==null?second==null:second!=null&&first.compareTo(second)==0;}
    private static CompanyCost validatedImportedUpdate(CompanyCost changes,CompanyCost current) {
        // Validate classification against absolute source totals. No invented blended VAT percentage is needed.
        BigDecimal absolute=current.amountExclEur().abs();
        var positive=new CompanyCost(changes.id(),changes.date(),changes.category(),changes.description(),changes.party(),absolute,null,
                changes.vatPct(),changes.reference(),null,changes.salesChannel(),changes.notes(),current.createdAt(),null,null,
                changes.vatRegime(),changes.vatRegimeConfirmed(),changes.supplierCountry(),changes.costKind(),changes.expenseType(),
                changes.vatDeductiblePct(),changes.taxDeductiblePct(),changes.depreciationYears(),changes.depreciationStart(),current.dueDate());
        var checked=validated(positive,current.id(),current.createdAt(),current.bankStatementLineId(),current);
        if(checked.vatRegime()!=null&&checked.vatRegime()!=VatRegime.BE&&checked.vatRegime()!=VatRegime.FOREIGN_VAT
                &&current.totalInclEur().abs().compareTo(absolute)!=0)
            throw new BusinessRuleException("Deze btw-regeling past niet bij de originele factuurbedragen");
        return new CompanyCost(current.id(),current.date(),checked.category(),checked.description(),current.party(),current.amountExclEur(),current.amountInclEur(),
                checked.vatPct(),current.reference(),current.paidOn(),checked.salesChannel(),checked.notes(),current.createdAt(),current.recurringCostId(),current.bankStatementLineId(),
                checked.vatRegime(),checked.vatRegimeConfirmed(),checked.supplierCountry(),checked.costKind(),checked.expenseType(),checked.vatDeductiblePct(),checked.taxDeductiblePct(),checked.depreciationYears(),checked.depreciationStart(),current.dueDate());
    }

    /** The regime fields of a cost or a recurring definition as they are stored, after the rules of the regime. */
    record RegimeFields(VatRegime vatRegime, boolean vatRegimeConfirmed, String supplierCountry, CostKind costKind,
                        ExpenseType expenseType, BigDecimal vatDeductiblePct, BigDecimal taxDeductiblePct,
                        BigDecimal vatPct, BigDecimal amountInclEur) {}

    /**
     * The rules of a regime, shared with the recurring definitions: the
     * country it needs, the VAT it carries or not, the deductibility preset.
     * A null regime (a row from before the regimes, kept on an update) takes
     * the Belgian money rules and no country rule; {@code incl} is the
     * entered total (null for a cost entered excl. and for a definition).
     */
    static RegimeFields regimeChecked(VatRegime regime, Boolean confirmed, String countryText, CostKind kind, ExpenseType type,
                                      BigDecimal vatDeductible, BigDecimal taxDeductible, BigDecimal excl, BigDecimal incl,
                                      BigDecimal vatPct) {
        String country = clean(countryText);
        if (country != null) {
            country = country.toUpperCase(Locale.ROOT);
            if (!country.matches("[A-Z]{2}")) throw new BusinessRuleException("De landcode bestaat uit twee letters");
        }
        if (regime != null) switch (regime) {
            case BE -> country = "BE";
            case EU_GOODS, EU_SERVICES -> {
                if (country == null) throw new BusinessRuleException("Kies het land van de leverancier");
                boolean eu = EU_MEMBERS.contains(country) || (regime == VatRegime.EU_GOODS && "XI".equals(country));
                if (!eu || "BE".equals(country)) {
                    throw new BusinessRuleException("Voor btw verlegd binnen de EU kies je een EU-land, niet België");
                }
            }
            case NON_EU_SERVICES -> {
                if (country == null) throw new BusinessRuleException("Kies het land van de leverancier");
                if (EU_MEMBERS.contains(country)) {
                    throw new BusinessRuleException("Voor een dienst van buiten de EU kies je een land buiten de EU");
                }
            }
            case FOREIGN_VAT -> {
                if (country == null) throw new BusinessRuleException("Kies het land van de leverancier");
                if ("BE".equals(country)) throw new BusinessRuleException("Buitenlandse btw: kies een ander land dan België");
            }
            default -> { }
        }
        BigDecimal pct = vatPct;
        BigDecimal total = incl;
        if (regime != null && regime.reverseCharge()) {
            if (pct == null || pct.signum() <= 0) throw new BusinessRuleException("Bij btw verlegd geef je het btw-percentage op");
            total = null;
        } else if (regime == VatRegime.EXEMPT || regime == VatRegime.OUT_OF_SCOPE || regime == VatRegime.NO_INVOICE) {
            boolean noVat = (pct == null || pct.signum() == 0)
                    && (incl == null || incl.setScale(2, RoundingMode.HALF_UP).compareTo(excl) == 0);
            if (!noVat) throw new BusinessRuleException("Deze regeling draagt geen btw");
            pct = null;
            total = null;
        } else if (regime == VatRegime.IMPORT_VAT_PAID) {
            if (excl.signum() != 0 || incl == null || incl.signum() <= 0) {
                throw new BusinessRuleException("Invoer-btw: geef het betaalde btw-bedrag op");
            }
            pct = null;
            total = incl.setScale(2, RoundingMode.HALF_UP);
        } else {
            total = inclusiveChecked(excl, incl, pct);
        }
        ExpenseType preset = type == null ? ExpenseType.STANDARD : type;
        BigDecimal vatShare = vatDeductible == null ? HUNDRED : vatDeductible;
        BigDecimal taxShare = taxDeductible == null ? HUNDRED : taxDeductible;
        if (preset != ExpenseType.CUSTOM) {
            vatShare = preset.vatPct();
            taxShare = preset.taxPct();
        }
        if (regime == VatRegime.IMPORT_VAT_PAID) {
            preset = ExpenseType.STANDARD;
            vatShare = HUNDRED;
            taxShare = HUNDRED;
        }
        for (BigDecimal share : List.of(vatShare, taxShare)) {
            if (share.signum() < 0 || share.compareTo(HUNDRED) > 0) {
                throw new BusinessRuleException("Het aftrekbare percentage ligt tussen 0 en 100");
            }
        }
        return new RegimeFields(regime, Boolean.TRUE.equals(confirmed), country, kind, preset, vatShare, taxShare, pct, total);
    }

    /**
     * The inclusive total as entered, when there is one: two decimals, never
     * below excl., and what it leaves above excl. must be the VAT the rate
     * gives, within the rounding of either side; without a rate it equals excl.
     */
    private static BigDecimal inclusiveChecked(BigDecimal excl, BigDecimal entered, BigDecimal vatPct) {
        if (entered == null) return null;
        BigDecimal incl = entered.setScale(2, RoundingMode.HALF_UP);
        if (incl.compareTo(excl) < 0) throw new BusinessRuleException("Het bedrag incl. btw kan niet kleiner zijn dan excl.");
        BigDecimal vat = incl.subtract(excl);
        boolean fits = vatPct == null || vatPct.signum() == 0
                ? vat.signum() == 0
                : vat.subtract(excl.multiply(vatPct).divide(HUNDRED, 4, RoundingMode.HALF_UP)).abs().compareTo(TOLERANCE) <= 0;
        if (!fits) throw new BusinessRuleException("Het bedrag incl. btw past niet bij het btw-percentage");
        return incl;
    }

    static String clean(String value) {
        if (value == null) return null;
        String trimmed = value.strip();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String actorName() {
        if (actor == null || !actor.isResolvable()) return null;
        return actor.get().current().displayName();
    }

    /**
     * The list learns the name after the cost is in, in its own transaction:
     * a failure of the list never undoes the booking. The channel is a
     * default worth keeping only for a fair cost; a rent paid under WEBSITE
     * says nothing about the landlord. The regime is taught only once it is
     * confirmed: a derived one is no default.
     */
    private void learnParty(CompanyCost cost) {
        if (parties == null || !parties.isResolvable()) return;
        try {
            parties.get().learn(cost.party(), cost.category(), cost.vatPct(),
                    CostPartyService.FAIR_CATEGORY.equals(cost.category()) ? cost.salesChannel() : null,
                    cost.supplierCountry(), cost.vatRegimeConfirmed() ? cost.vatRegime() : null);
        } catch (RuntimeException failure) {
            LOG.debug("De lijst Aan wie leerde de kost niet: " + failure.getMessage(), failure);
        }
    }

    private void recordActivity(String action, CompanyCost cost, String summary) {
        if (activity == null || !activity.isResolvable()) return;
        activity.get().record(action, ACTIVITY_ENTITY,
                cost.id() == null ? null : cost.id().toString(),
                cost.description() + " · € " + cost.amountExclEur().toPlainString(), summary);
    }
}
