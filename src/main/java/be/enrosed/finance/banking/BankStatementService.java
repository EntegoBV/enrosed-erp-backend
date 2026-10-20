package be.enrosed.finance.banking;

import be.enrosed.finance.adapter.out.persistence.CompanyCostEntity;
import be.enrosed.finance.adapter.out.persistence.CostPaymentEntity;
import be.enrosed.finance.application.CostPartyService;
import be.enrosed.sales.application.*;
import be.enrosed.sales.application.port.out.SalesRepositories;
import be.enrosed.sales.domain.*;
import be.enrosed.shared.*;
import be.enrosed.shared.audit.ActivityLogService;
import be.enrosed.shared.security.CurrentActor;
import be.enrosed.sourcing.adapter.out.persistence.SourcingEntities;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.persistence.*;
import jakarta.transaction.Transactional;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;

@ApplicationScoped
public class BankStatementService {
    private static final org.jboss.logging.Logger LOG=org.jboss.logging.Logger.getLogger(BankStatementService.class);
    @Inject EntityManager em;
    @Inject SalesOrderService sales;
    @Inject SalesRepositories.Orders orders;
    @Inject IncomingPaymentService incoming;
    @Inject CurrentActor actor;
    @Inject ActivityLogService activity;
    @Inject BankAccountService accounts;
    @Inject be.enrosed.sourcing.application.PurchasePaymentOriginService paymentOrigins;
    @Inject be.enrosed.finance.application.CompanyCostService costs;
    /** The list "Aan wie" learns the counterparty of an outgoing movement; incoming money comes from customers, who are no payees. */
    @Inject Instance<CostPartyService> parties;
    @Inject Instance<be.enrosed.tica.TicaSettlementGuard> ticaSettlements;

    public enum Direction { INCOMING, OUTGOING }
    /**
     * The purchase payments, the company cost and the cost payment this movement settled, when it is noted from
     * their forms; all optional. A cost payment names the exact row; the cost alone resolves to its single payment.
     */
    public record ManualRequest(String account, BigDecimal amountEur, Direction direction, Instant bookedAt,
                                String timeZone, String reference, String counterparty, String requestId,
                                List<Long> purchasePaymentIds, Long companyCostId, Long costPaymentId) {
        /** A movement noted on its own, linked to nothing. */
        public ManualRequest(String account, BigDecimal amountEur, Direction direction, Instant bookedAt,
                             String timeZone, String reference, String counterparty, String requestId) {
            this(account, amountEur, direction, bookedAt, timeZone, reference, counterparty, requestId, null, null, null);
        }
        /** Compatibility for callers written before a cost had several payments. */
        public ManualRequest(String account, BigDecimal amountEur, Direction direction, Instant bookedAt,
                             String timeZone, String reference, String counterparty, String requestId,
                             List<Long> purchasePaymentIds, Long companyCostId) {
            this(account, amountEur, direction, bookedAt, timeZone, reference, counterparty, requestId, purchasePaymentIds, companyCostId, null);
        }
    }
    public record Match(long salesOrderId,String number,BigDecimal openEur,int score,Long existingPaymentId,String reason,Instant receivedAt,String reference) {}
    /** {@code followBank}: an existing receipt takes the line's amount, moment and account before it is matched. */
    public record Allocation(long salesOrderId,Long existingPaymentId,Boolean followBank) {
        public Allocation(long salesOrderId,Long existingPaymentId) {this(salesOrderId,existingPaymentId,null);}
    }
    /**
     * {@code followBank}: the one cost payment takes the line's day, amount and account before the two-cent check.
     * {@code keepDifferenceAs} (BANKKOSTEN or KOERSVERSCHIL): the purchase payments keep their amounts and the bank's
     * surplus becomes a paid company cost on the same line.
     */
    public record OutgoingLinks(List<Long> purchasePaymentIds,Long companyCostId,Long costPaymentId,Boolean followBank,
                                String keepDifferenceAs) {
        public OutgoingLinks(List<Long> purchasePaymentIds,Long companyCostId,Long costPaymentId) {
            this(purchasePaymentIds,companyCostId,costPaymentId,null,null);
        }
    }
    static final String DIFFERENCE_NOTE="Verschil bij bankkoppeling";
    private static final java.time.format.DateTimeFormatter DAY=java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy");
    public record CostAllocation(long companyCostId,boolean confirmNewPayment) {
        public CostAllocation(long companyCostId) {this(companyCostId,false);}
    }
    public record ExternalRequest(String sourceId,String account,BigDecimal amountEur,Instant bookedAt,String timeZone,
                                  String reference,String counterparty,Instant sourceUpdatedAt,Boolean sourceReconciled) {}
    public enum ImportOutcome { CREATED, UPDATED, UNCHANGED, CONFLICT }
    public record ImportResult(Long id,ImportOutcome outcome) {}

    public List<BankStatementLineEntity> list() {
        var rows=em.createQuery("from BankStatementLineEntity order by bookedAt desc,id desc",BankStatementLineEntity.class).getResultList();
        var amounts=allocationAmounts(null);
        for(var row:rows)describe(row,amounts);
        return rows;
    }

    /**
     * The lines booked from a day on (00:00 Brussels), plus every older line that is still "te koppelen": the
     * Kasprognose needs recent movements and every open line. "Open" is the described allocation (a classified
     * line is not open), which is transient, so the filter runs here and never in SQL. Same order as {@link #list()}.
     */
    public List<BankStatementLineEntity> list(String from) {
        if(from==null||from.isBlank())return list();
        LocalDate day;
        try { day=LocalDate.parse(from.strip()); }
        catch(java.time.format.DateTimeParseException invalid) { throw new BusinessRuleException("Ongeldige datum voor from (JJJJ-MM-DD)"); }
        Instant start=day.atStartOfDay(ZoneId.of("Europe/Brussels")).toInstant();
        return list().stream().filter(row->!row.bookedAt.isBefore(start)||!row.allocated).toList();
    }

    /** The Dutch name of a "Boeken als" classification; null for none. */
    public static String classificationLabel(String classification) {
        if(classification==null)return null;
        return switch(classification) {
            case "INTERNAL_TRANSFER" -> "Interne overboeking";
            case "CANCELLATION" -> "Annulatie";
            case "VAT" -> "Btw";
            case "LOAN" -> "Lening";
            case "CAPITAL" -> "Kapitaal";
            case "PRIVATE" -> "Privé";
            case "OTHER" -> "Andere";
            case "TICA_SETTLEMENT" -> "TICA-afrekening";
            default -> classification;
        };
    }

    /**
     * A line booked as an internal transfer, VAT, a loan and so on carries no payment. Every link path calls this
     * first, right after loading or locking the line and before any retry shortcut or allocation test: the
     * described allocation of a classified line would otherwise answer "al gekoppeld".
     */
    public void requireUnclassified(BankStatementLineEntity row) {
        if(row!=null&&row.classification!=null)
            throw new BusinessRuleException("Deze banktransactie is geboekt als "+classificationLabel(row.classification)+"; maak dat eerst ongedaan");
    }

    /** Each imported row commits independently. A failed/retried sync resumes from stable external identities. */
    @Transactional
    public ImportResult importExternal(ExternalRequest request) {
        if(request==null||request.sourceId()==null||request.sourceId().isBlank()||request.sourceId().length()>180)
            throw new BusinessRuleException("De externe bankreferentie ontbreekt of is ongeldig");
        String account=account(request.account());
        if(request.amountEur()==null||request.bookedAt()==null)throw new BusinessRuleException("De banktransactie heeft geen bedrag of boekingsdatum");
        BigDecimal amount;
        try { amount=request.amountEur().setScale(2,java.math.RoundingMode.UNNECESSARY); }
        catch(ArithmeticException invalid) { throw new BusinessRuleException("Het bankbedrag bevat meer dan twee decimalen"); }
        if(amount.precision()>19)throw new BusinessRuleException("Het bankbedrag is te groot");
        String zone=request.timeZone()==null?"Europe/Brussels":request.timeZone();
        try { ZoneId.of(zone); } catch(Exception invalid) { throw new BusinessRuleException("Ongeldige tijdzone"); }
        String reference=limited(request.reference(),500),counterparty=limited(request.counterparty(),300);
        var row=em.createQuery("from BankStatementLineEntity b where b.source='ODOO' and b.sourceId=:id",BankStatementLineEntity.class)
                .setParameter("id",request.sourceId()).setLockMode(LockModeType.PESSIMISTIC_WRITE).getResultStream().findFirst().orElse(null);
        boolean created=row==null;
        if(created) { row=new BankStatementLineEntity();row.source="ODOO";row.sourceId=request.sourceId();row.fingerprint=hash("ODOO|"+request.sourceId());row.recordedAt=Instant.now();row.actor="Odoo"; }
        else {
            if(request.sourceUpdatedAt()!=null&&row.sourceUpdatedAt!=null&&request.sourceUpdatedAt().isBefore(row.sourceUpdatedAt))return new ImportResult(row.id,ImportOutcome.UNCHANGED);
            boolean financialChange=row.amountEur.compareTo(amount)!=0||!row.account.equals(account)||!row.bookedAt.equals(request.bookedAt());
            if(financialChange&&describe(row).allocated)return new ImportResult(row.id,ImportOutcome.CONFLICT);
            if(!financialChange&&Objects.equals(row.reference,reference)&&Objects.equals(row.counterparty,counterparty)
                    &&Objects.equals(row.sourceReconciled,request.sourceReconciled())&&Objects.equals(row.sourceUpdatedAt,request.sourceUpdatedAt()))
                return new ImportResult(row.id,ImportOutcome.UNCHANGED);
        }
        row.account=account;row.amountEur=amount;row.bookedAt=request.bookedAt();row.timeZone=zone;
        row.reference=reference;row.counterparty=counterparty;row.sourceUpdatedAt=request.sourceUpdatedAt();row.sourceReconciled=request.sourceReconciled();
        if(created)em.persist(row);em.flush();
        return new ImportResult(row.id,created?ImportOutcome.CREATED:ImportOutcome.UPDATED);
    }

    /** Links an already recorded expense to the real bank movement without creating another movement/payment. */
    @Transactional
    public BankStatementLineEntity linkExisting(long id,OutgoingLinks request) {
        var row=get(id,true);
        requireUnclassified(row);
        if(request==null)throw new BusinessRuleException("Kies de betaling die bij deze banktransactie hoort");
        if(row.amountEur.signum()==0||row.salesPaymentId!=null)throw new BusinessRuleException("Kies een vrije banktransactie");
        var ids=request.purchasePaymentIds()==null?List.<Long>of():request.purchasePaymentIds();
        if(ids.stream().anyMatch(Objects::isNull)||new HashSet<>(ids).size()!=ids.size())throw new BusinessRuleException("Kies elke betaling maar één keer");
        boolean follow=Boolean.TRUE.equals(request.followBank());
        if(follow&&(!ids.isEmpty()||request.companyCostId()==null&&request.costPaymentId()==null))throw followPerCostPayment();
        if(request.keepDifferenceAs()!=null)return linkWithDifference(row,request,ids);
        List<SourcingEntities.PurchasePaymentEntity> purchases=new ArrayList<>();
        BigDecimal total=BigDecimal.ZERO;
        // Payment corrections hold the parent order lock; take the same lock before reading amounts.
        if(!ids.isEmpty())for(Long orderId:em.createQuery("select distinct p.orderId from PurchasePaymentEntity p where p.id in :ids order by p.orderId",Long.class).setParameter("ids",ids).getResultList())
            em.find(SourcingEntities.PurchaseOrderEntity.class,orderId,LockModeType.PESSIMISTIC_WRITE);
        for(long paymentId:ids.stream().sorted().toList()) {
            var payment=em.find(SourcingEntities.PurchasePaymentEntity.class,paymentId,LockModeType.PESSIMISTIC_WRITE);
            if(payment==null)throw new BusinessRuleException("Betaling niet gevonden");
            paymentOrigins.requireBankEligible(paymentId);
            if(payment.amountEur.signum()<=0||row.amountEur.signum()>=0)throw new BusinessRuleException("De richting van de inkoopbetaling komt niet overeen met de banktransactie");
            if(payment.bankStatementLineId!=null&&!payment.bankStatementLineId.equals(id))throw new BusinessRuleException("Deze betaling is al aan een andere banktransactie gekoppeld");
            if(em.createQuery("select count(c) from be.enrosed.sourcing.adapter.out.persistence.SourcingEntities$PurchaseSupplierCreditEntity c where c.offsetPaymentId=:id",Long.class).setParameter("id",paymentId).getSingleResult()>0)
                throw new BusinessRuleException("Een verrekend leverancierstegoed is geen bankbetaling");
            purchases.add(payment);total=total.add(payment.amountEur);
        }
        CompanyCostEntity cost=null;CostPaymentEntity costPayment=null;
        if(request.costPaymentId()!=null) {
            var parent=em.createQuery("select p.companyCostId from CostPaymentEntity p where p.id=:id",Long.class).setParameter("id",request.costPaymentId()).getResultStream().findFirst().orElse(null);
            if(parent!=null)cost=em.find(CompanyCostEntity.class,parent,LockModeType.PESSIMISTIC_WRITE);
            costPayment=em.find(CostPaymentEntity.class,request.costPaymentId(),LockModeType.PESSIMISTIC_WRITE);
            if(costPayment==null)throw new BusinessRuleException("Kostbetaling niet gevonden");
            if(request.companyCostId()!=null&&!request.companyCostId().equals(costPayment.companyCostId))throw new BusinessRuleException("Deze betaling hoort niet bij deze kost");
            if(cost==null)throw new BusinessRuleException("Kost niet gevonden");
        } else if(request.companyCostId()!=null) {
            cost=em.find(CompanyCostEntity.class,request.companyCostId(),LockModeType.PESSIMISTIC_WRITE);
            if(cost==null)throw new BusinessRuleException("Kost niet gevonden");
            var candidates=costPayments(cost.id);
            // Following needs exactly one payment to follow; the follow refusal names that before the plain link's.
            if(follow&&candidates.size()!=1)throw followPerCostPayment();
            if(candidates.size()>1)throw new BusinessRuleException("Kies de betaling die deze banktransactie dekt");
            if(candidates.size()==1)costPayment=em.find(CostPaymentEntity.class,candidates.getFirst().id,LockModeType.PESSIMISTIC_WRITE);
            else {
                if(cost.paidOn==null)throw new BusinessRuleException("Noteer eerst de betaling op deze kost");
                if(cost.bankStatementLineId!=null&&!cost.bankStatementLineId.equals(id))throw new BusinessRuleException("Deze kost is al aan een andere banktransactie gekoppeld");
                total=total.add(cost.toDomain().totalInclEur());
            }
        }
        requireNotTicaOffset(cost == null ? null : cost.id);
        if(costPayment!=null) {
            if(costPayment.bankStatementLineId!=null&&!costPayment.bankStatementLineId.equals(id))throw new BusinessRuleException("Deze betaling is al aan een andere banktransactie gekoppeld");
            // The payment follows before its amount joins the total, so the two-cent check reads the followed amount.
            if(follow&&costPayment.bankStatementLineId==null)costPayment=followCost(row,cost,costPayment);
            // Historical free-text bank labels need not equal an imported IBAN. The selected movement is authoritative.
            total=total.add(costPayment.amountEur);
        }
        if(cost!=null) {
            BigDecimal costAmount=costPayment==null?cost.toDomain().totalInclEur():costPayment.amountEur;
            if(costAmount.signum()==0||costAmount.signum()!=cost.toDomain().totalInclEur().signum()
                    ||costAmount.signum()!=-row.amountEur.signum())
                throw new BusinessRuleException("De richting van de kostbetaling of terugbetaling komt niet overeen met de banktransactie");
        }
        if(purchases.isEmpty()&&cost==null)throw new BusinessRuleException("Kies minstens één betaling");
        if(!BankAmountMatch.matches(row.amountEur,total.negate()))throw new BusinessRuleException("Het totaal van de betalingen mag maximaal € 0,02 van het bankbedrag verschillen");
        Set<Long> linkedPurchases=new HashSet<>(em.createQuery("select p.id from PurchasePaymentEntity p where p.bankStatementLineId=:id",Long.class).setParameter("id",id).getResultList());
        Set<Long> linkedPayments=new HashSet<>(em.createQuery("select p.id from CostPaymentEntity p where p.bankStatementLineId=:id",Long.class).setParameter("id",id).getResultList());
        Set<Long> linkedCosts=new HashSet<>(em.createQuery("select c.id from CompanyCostEntity c where c.bankStatementLineId=:id",Long.class).setParameter("id",id).getResultList());
        linkedCosts.addAll(em.createQuery("select p.companyCostId from CostPaymentEntity p where p.bankStatementLineId=:id",Long.class).setParameter("id",id).getResultList());
        Long selectedCost=cost==null?null:cost.id;
        Long selectedPayment=costPayment==null?null:costPayment.id;
        boolean alreadyLinked=!linkedPurchases.isEmpty()||!linkedPayments.isEmpty()||!linkedCosts.isEmpty();
        if(alreadyLinked&&(!new HashSet<>(ids).equals(linkedPurchases)
                ||!linkedPayments.equals(selectedPayment==null?Set.of():Set.of(selectedPayment))
                ||!linkedCosts.equals(selectedCost==null?Set.of():Set.of(selectedCost))))
            throw new BusinessRuleException("Deze banktransactie is al aan een andere betaling gekoppeld");
        for(var payment:purchases)payment.bankStatementLineId=id;
        if(costPayment!=null) { costPayment.bankStatementLineId=id;costPayment.account=row.account; }
        if(cost!=null&&(costPayment==null||costPayments(cost.id).size()==1))cost.bankStatementLineId=id;
        row.allocatedAt=Instant.now();em.flush();audit(row,"Banktransactie gekoppeld aan bestaande inkoop- of kostbetaling · toegewezen "+total.negate()+" · verschil "+row.amountEur.add(total));
        return describe(row);
    }

    private static BusinessRuleException followPerCostPayment() {
        return new BusinessRuleException("Betaling volgt de bank kan per kostbetaling; inkoopbetalingen volgen de bank via hun eigen koppeling");
    }

    /**
     * "Betaling volgt de bank" for a cost payment: it takes the line's local day, amount and account through the
     * cost's own payment update (totals and "Betaald op" follow), under the locks the link already holds. A cost
     * would never be paid more than its cash total plus two cents.
     */
    private CostPaymentEntity followCost(BankStatementLineEntity row,CompanyCostEntity cost,CostPaymentEntity payment) {
        LocalDate day=localDay(row);
        BigDecimal amount=row.amountEur.negate();
        if(day.equals(payment.paidOn)&&amount.compareTo(payment.amountEur)==0&&Objects.equals(row.account,payment.account))return payment;
        BigDecimal cash=cost.toDomain().totalInclEur();
        if(cash.signum()==0||amount.signum()!=cash.signum())
            throw new BusinessRuleException("De richting van de kostbetaling of terugbetaling komt niet overeen met de banktransactie");
        BigDecimal others=costPayments(cost.id).stream().filter(other->!other.id.equals(payment.id))
                .map(other->other.amountEur).reduce(BigDecimal.ZERO,BigDecimal::add);
        if(others.add(amount).abs().subtract(cash.abs()).compareTo(BankAmountMatch.MAX_DIFFERENCE)>0)
            throw new BusinessRuleException("De transactie is hoger dan wat er op deze kost openstaat; registreer het verschil apart");
        BigDecimal wasAmount=payment.amountEur;LocalDate wasDay=payment.paidOn;
        costs.updatePayment(cost.id,payment.id,new be.enrosed.finance.domain.CostPayment(null,null,day,amount,row.account,null,payment.note,null,null));
        var followed=em.find(CostPaymentEntity.class,payment.id);
        em.refresh(followed);
        audit(row,"Kostbetaling volgt de bank: "+eur(amount)+" op "+DAY.format(day)+" (was "+eur(wasAmount)+" op "+DAY.format(wasDay)+")");
        return followed;
    }

    /**
     * "Betaling houden, verschil als Bankkosten / Koersverschil": the bank paid more than the purchase payments, and
     * they keep their amounts. The difference becomes a paid company cost on the same line (created unpaid, so
     * create adds no payment of its own, then one payment on the line's day and account), linked together with
     * the purchase payments in this transaction. A retry of a link that already carries exactly those payments and
     * one such difference answers the line and creates nothing.
     */
    private BankStatementLineEntity linkWithDifference(BankStatementLineEntity row,OutgoingLinks request,List<Long> ids) {
        String keep=request.keepDifferenceAs().strip();
        if(ids.isEmpty()||request.companyCostId()!=null||request.costPaymentId()!=null)
            throw new BusinessRuleException("Een verschil boeken kan alleen bij inkoopbetalingen");
        if(!Set.of("BANKKOSTEN","KOERSVERSCHIL").contains(keep))throw new BusinessRuleException("Kies Bankkosten of Koersverschil");
        long id=row.id;
        lockOutgoingParents(ids,null,null);
        BigDecimal total=BigDecimal.ZERO;
        Set<String> orderNumbers=new TreeSet<>();
        for(long paymentId:ids.stream().sorted().toList()) {
            var payment=em.find(SourcingEntities.PurchasePaymentEntity.class,paymentId,LockModeType.PESSIMISTIC_WRITE);
            if(payment==null)throw new BusinessRuleException("Betaling niet gevonden");
            paymentOrigins.requireBankEligible(paymentId);
            /* The link's own guards, checked before anything is created. */
            if(payment.bankStatementLineId!=null&&!payment.bankStatementLineId.equals(id))throw new BusinessRuleException("Deze betaling is al aan een andere banktransactie gekoppeld");
            if(payment.amountEur.signum()<=0)throw new BusinessRuleException("De richting van de inkoopbetaling komt niet overeen met de banktransactie");
            total=total.add(payment.amountEur);
            var order=em.find(SourcingEntities.PurchaseOrderEntity.class,payment.orderId);
            if(order!=null&&order.number!=null)orderNumbers.add(order.number);
        }
        BigDecimal difference=row.amountEur.negate().subtract(total);
        Set<Long> linkedPurchases=new HashSet<>(em.createQuery("select p.id from PurchasePaymentEntity p where p.bankStatementLineId=:id",Long.class).setParameter("id",id).getResultList());
        var linkedCosts=em.createQuery("from CostPaymentEntity p where p.bankStatementLineId=:id",CostPaymentEntity.class).setParameter("id",id).getResultList();
        if(linkedPurchases.equals(new HashSet<>(ids))&&linkedCosts.size()==1&&DIFFERENCE_NOTE.equals(linkedCosts.getFirst().note)
                &&linkedCosts.getFirst().amountEur.subtract(difference).abs().compareTo(BankAmountMatch.MAX_DIFFERENCE)<=0)return describe(row);
        if(row.amountEur.signum()>=0)throw new BusinessRuleException("De richting van de inkoopbetaling komt niet overeen met de banktransactie");
        if(difference.compareTo(BankAmountMatch.MAX_DIFFERENCE.negate())<0)
            throw new BusinessRuleException("De bank betaalde minder dan de betaling; laat de betaling de bank volgen");
        if(difference.compareTo(BankAmountMatch.MAX_DIFFERENCE)<=0)throw new BusinessRuleException("Er is geen verschil om te boeken; koppel gewoon");
        if(!linkedPurchases.isEmpty()||!linkedCosts.isEmpty()||describe(row).allocated)
            throw new BusinessRuleException("Deze banktransactie is al aan een andere betaling gekoppeld");
        boolean bankFees=keep.equals("BANKKOSTEN");
        String counterparty=row.counterparty==null||row.counterparty.isBlank()?null:row.counterparty.strip();
        String description=bankFees?(counterparty==null?"Bankkosten":"Bankkosten · "+counterparty):"Koersverschil · "+String.join(", ",orderNumbers);
        LocalDate day=localDay(row);
        var cost=costs.create(new be.enrosed.finance.domain.CompanyCost(null,day,bankFees?"VERZEKERING":"ANDERE",shortened(description,300),
                counterparty==null?null:shortened(counterparty,200),difference,null,null,null,null,null,null,null,null,null,
                bankFees?be.enrosed.finance.domain.VatRegime.EXEMPT:be.enrosed.finance.domain.VatRegime.OUT_OF_SCOPE,true,null,
                null,null,null,null,null,null,null));
        var payments=costs.addPayment(cost.id(),new be.enrosed.finance.domain.CostPayment(null,cost.id(),day,difference,row.account,null,
                DIFFERENCE_NOTE,null,null)).payments();
        long costPaymentId=payments.stream().filter(payment->payment.bankStatementLineId()==null)
                .map(be.enrosed.finance.domain.CostPayment::id).max(Long::compare).orElseThrow();
        var linked=linkExisting(id,new OutgoingLinks(ids,cost.id(),costPaymentId));
        audit(row,"Verschil "+eur(difference)+" geboekt als "+(bankFees?"Bankkosten":"Koersverschil"));
        return linked;
    }

    private static String shortened(String text,int length) {return text.length()<=length?text:text.substring(0,length);}
    private static LocalDate localDay(BankStatementLineEntity row) {return row.bookedAt.atZone(ZoneId.of(row.timeZone)).toLocalDate();}
    /** Belgian euro: a point every three digits, a comma before the cents. */
    static String eur(BigDecimal amount) {
        return "€ "+String.format(Locale.forLanguageTag("nl-BE"),"%,.2f",amount.setScale(2,java.math.RoundingMode.HALF_UP));
    }

    @Transactional
    public void unlinkExisting(long id) {
        var row=get(id,true);
        requireUnclassified(row);
        if(row.salesPaymentId!=null)throw new BusinessRuleException("Maak de factuurkoppeling ongedaan via de factuur");
        unlink(id);row.allocatedAt=null;em.flush();audit(row,"Bankkoppeling losgemaakt; betaalregistraties behouden");
    }

    /** A selected bank movement creates exactly one cost payment, including retries after a lost response. */
    @Transactional
    public BankStatementLineEntity recordCostPayment(long id,CostAllocation request) {
        var row=get(id,true);
        requireUnclassified(row);
        if(request==null||request.companyCostId()<=0)throw new BusinessRuleException("Kies de kost bij deze banktransactie");
        if(row.amountEur.signum()==0)throw new BusinessRuleException("Kies een banktransactie met een bedrag");
        var cost=em.find(CompanyCostEntity.class,request.companyCostId(),LockModeType.PESSIMISTIC_WRITE);
        if(cost==null)throw new BusinessRuleException("Kost niet gevonden");
        requireNotTicaOffset(cost.id);
        if(describe(row).allocated) {
            var linked=em.createQuery("from CostPaymentEntity p where p.bankStatementLineId=:id",CostPaymentEntity.class).setParameter("id",id).getResultList();
            if(row.salesPaymentId==null&&linked.size()==1&&Objects.equals(linked.getFirst().companyCostId,cost.id)
                    &&em.createQuery("select count(p) from PurchasePaymentEntity p where p.bankStatementLineId=:id",Long.class).setParameter("id",id).getSingleResult()==0
                    &&BankAmountMatch.matches(row.amountEur,linked.getFirst().amountEur.negate()))return row;
            throw new BusinessRuleException("Deze banktransactie is al gekoppeld aan een andere betaling");
        }
        var existing=costPayments(cost.id);
        Set<Long> before=new HashSet<>(existing.stream().map(payment->payment.id).toList());
        BigDecimal remaining=costs.view(cost.toDomain()).openEur();
        if(remaining.signum()==0)throw new BusinessRuleException("Deze kost is al volledig betaald; koppel de bestaande betaling");
        if(remaining.signum()!=-row.amountEur.signum())throw new BusinessRuleException("De richting van de betaling komt niet overeen met de kost of creditnota");
        if(row.amountEur.abs().subtract(remaining.abs()).compareTo(BankAmountMatch.MAX_DIFFERENCE)>0)
            throw new BusinessRuleException("Het bankbedrag is groter dan het openstaande kostbedrag; maximaal € 0,02 verschil is toegestaan");
        if(!request.confirmNewPayment()&&existing.stream().anyMatch(payment->BankAmountMatch.matches(row.amountEur,payment.amountEur.negate())
                &&potentiallyUnmatched(payment)))
            throw new BusinessRuleException("Er bestaat al een passende betaling op deze kost. Koppel die bestaande betaling, of bevestig expliciet dat dit een nieuwe betaling is");
        BigDecimal paymentAmount=BankAmountMatch.matches(row.amountEur,remaining.negate())?remaining:row.amountEur.negate();
        String note=row.reference==null?null:row.reference.substring(0,Math.min(300,row.reference.length()));
        var result=costs.addPayment(cost.id,new be.enrosed.finance.domain.CostPayment(null,cost.id,
                row.bookedAt.atZone(ZoneId.of(row.timeZone)).toLocalDate(),paymentAmount,row.account,null,note,null,null));
        var added=result.payments().stream().filter(payment->!before.contains(payment.id())).findFirst().orElseThrow();
        return linkExisting(id,new OutgoingLinks(null,cost.id,added.id()));
    }

    /** A historical manual movement is not proof that the same payment reached an imported bank account. */
    private boolean potentiallyUnmatched(CostPaymentEntity payment) {
        if(payment.bankStatementLineId==null)return true;
        var previous=em.find(BankStatementLineEntity.class,payment.bankStatementLineId);
        return previous==null||previous.source==null;
    }

    private record AllocationAmounts(Map<Long,BigDecimal> purchases,Map<Long,BigDecimal> costs,Map<Long,BigDecimal> sales) {}

    /** Batch the list's reverse links and avoid counting a cost-level compatibility link twice. */
    private AllocationAmounts allocationAmounts(Long bankId) {
        Map<Long,BigDecimal> purchase=paymentAmounts("PurchasePaymentEntity",bankId);
        Map<Long,BigDecimal> cost=paymentAmounts("CostPaymentEntity",bankId);
        String restriction=bankId==null?"":" and c.bankStatementLineId=:bank";
        var legacy=em.createQuery("from CompanyCostEntity c where c.bankStatementLineId is not null"+restriction
                +" and not exists (select p.id from CostPaymentEntity p where p.companyCostId=c.id and p.bankStatementLineId=c.bankStatementLineId)",CompanyCostEntity.class);
        if(bankId!=null)legacy.setParameter("bank",bankId);
        for(var row:legacy.getResultList())cost.merge(row.bankStatementLineId,row.toDomain().totalInclEur(),BigDecimal::add);
        Map<Long,BigDecimal> receipts=new HashMap<>();
        var sales=em.createQuery("select b.id,p.amountEur from BankStatementLineEntity b,SalesPaymentEntity p where b.salesPaymentId=p.id"
                +(bankId==null?"":" and b.id=:bank"),Object[].class);
        if(bankId!=null)sales.setParameter("bank",bankId);
        for(var row:sales.getResultList())receipts.put((Long)row[0],(BigDecimal)row[1]);
        return new AllocationAmounts(purchase,cost,receipts);
    }

    private Map<Long,BigDecimal> paymentAmounts(String entity,Long bankId) {
        var query=em.createQuery("select p.bankStatementLineId,sum(p.amountEur) from "+entity
                +" p where p.bankStatementLineId is not null"+(bankId==null?"":" and p.bankStatementLineId=:bank")
                +" group by p.bankStatementLineId",Object[].class);
        if(bankId!=null)query.setParameter("bank",bankId);
        Map<Long,BigDecimal> result=new HashMap<>();
        for(var row:query.getResultList())result.put((Long)row[0],(BigDecimal)row[1]);
        return result;
    }

    private static void describe(BankStatementLineEntity row,AllocationAmounts amounts) {
        if(row.classification!=null) {
            /* Booked as a transfer, VAT, a loan...: explained in full, so it counts as allocated everywhere. */
            row.allocated=true;row.allocationLabel=classificationLabel(row.classification);
            row.allocatedAmountEur="TICA_SETTLEMENT".equals(row.classification) && row.ticaSettlementAmountEur != null
                    ? row.ticaSettlementAmountEur : row.amountEur;
            row.allocationDifferenceEur=row.amountEur.subtract(row.allocatedAmountEur);
            return;
        }
        boolean purchase=amounts.purchases.containsKey(row.id),cost=amounts.costs.containsKey(row.id);
        row.allocated=row.salesPaymentId!=null||purchase||cost;
        row.allocationLabel=row.salesPaymentId!=null?"Verkoopfactuur":purchase&&cost?"Inkoop en kosten":purchase?"Inkoopbetaling":cost?"Kostbetaling":null;
        row.allocatedAmountEur=!row.allocated?null:row.salesPaymentId!=null?amounts.sales.get(row.id)
                :amounts.purchases.getOrDefault(row.id,BigDecimal.ZERO).add(amounts.costs.getOrDefault(row.id,BigDecimal.ZERO)).negate();
        row.allocationDifferenceEur=row.allocatedAmountEur==null?null:row.amountEur.subtract(row.allocatedAmountEur);
    }
    BankStatementLineEntity describe(BankStatementLineEntity row) {
        describe(row,allocationAmounts(row.id));return row;
    }

    /** Records an actual bank movement; creating this row never pays or changes an invoice. */
    @Transactional
    public BankStatementLineEntity create(ManualRequest request) {
        if(request==null)throw new BusinessRuleException("Geen bankbeweging meegestuurd");
        String account=account(request.account());
        accounts.requireManualMovements(account);
        if(request.amountEur()==null || request.amountEur().signum()<=0)throw new BusinessRuleException("Geef een bedrag groter dan nul op");
        BigDecimal amount;
        try { amount=request.amountEur().setScale(2,java.math.RoundingMode.UNNECESSARY); }
        catch(ArithmeticException invalid) { throw new BusinessRuleException("Gebruik maximaal twee decimalen voor het bankbedrag"); }
        if(amount.precision()>19)throw new BusinessRuleException("Het bankbedrag is te groot");
        if(request.direction()==null)throw new BusinessRuleException("Kies inkomend of uitgaand");
        if(request.direction()==Direction.OUTGOING)amount=amount.negate();
        if(request.bookedAt()==null)throw new BusinessRuleException("Vul de bankdatum en het werkelijke tijdstip in");
        if(request.bookedAt().isAfter(Instant.now().plusSeconds(300)))throw new BusinessRuleException("Een bankbeweging kan niet in de toekomst liggen");
        String zone=request.timeZone()==null||request.timeZone().isBlank()?"Europe/Brussels":request.timeZone().strip();
        if(zone.length()>64)throw new BusinessRuleException("De tijdzone is maximaal 64 tekens");
        try { ZoneId.of(zone); } catch(Exception invalid) { throw new BusinessRuleException("Ongeldige tijdzone"); }
        String reference=limited(request.reference(),500),counterparty=limited(request.counterparty(),300);
        if(request.requestId()==null||request.requestId().isBlank()||request.requestId().length()>100)
            throw new BusinessRuleException("De registratiecode ontbreekt; open het formulier opnieuw");
        String fingerprint=hash(account+"|manual:"+request.requestId());
        var existing=em.createQuery("from BankStatementLineEntity b where b.account=:account and b.fingerprint=:fingerprint",BankStatementLineEntity.class)
                .setParameter("account",account).setParameter("fingerprint",fingerprint).getResultStream().findFirst().orElse(null);
        if(existing!=null) {
            if(existing.amountEur.compareTo(amount)!=0||!existing.bookedAt.equals(request.bookedAt())||!existing.timeZone.equals(zone)
                    ||!Objects.equals(existing.reference,reference)||!Objects.equals(existing.counterparty,counterparty))
                throw new BusinessRuleException("Deze registratiecode is al voor een andere bankbeweging gebruikt; open een nieuw formulier");
            return describe(existing);
        }
        var row=new BankStatementLineEntity();row.account=account;row.fingerprint=fingerprint;row.amountEur=amount;
        row.bookedAt=request.bookedAt();row.timeZone=zone;row.reference=reference;row.counterparty=counterparty;
        row.recordedAt=Instant.now();row.actor=actor.current().displayName();em.persist(row);em.flush();
        link(row,request.purchasePaymentIds(),request.companyCostId(),request.costPaymentId());
        audit(row,"Bankbeweging handmatig geregistreerd: "+row.bookedAt+" · "+row.amountEur+" · "+reference);
        if(request.direction()==Direction.OUTGOING&&!counterparty.isEmpty())learnParty(counterparty);
        return describe(row);
    }

    /** Invoices and credit notes alike; an open credit note only ever matches an outgoing line, through its refundable balance. */
    public List<Match> suggestions(long id) {var row=get(id,false);return suggestions(orders.findAll().stream().filter(SalesOrder::isClaimDocument).toList(),row.amountEur,row.reference,row.account);}

    private List<Match> suggestions(List<SalesOrder> invoices,BigDecimal amount,String reference,String account) {
        List<Match> matches=new ArrayList<>();String text=PaymentReference.normalized(reference).toUpperCase(Locale.ROOT);
        for(var invoice:invoices) {
            if(Set.of(QuoteStatus.CONCEPT,QuoteStatus.GEANNULEERD,QuoteStatus.AFGEWEZEN,QuoteStatus.VERLOPEN).contains(invoice.status()))continue;
            var summary=incoming.summary(invoice,sales.price(invoice));
            BigDecimal open=amount.signum()>0?summary.remainingEur():summary.refundableEur();
            int score=(invoice.number()!=null&&text.contains(PaymentReference.normalized(invoice.number()).toUpperCase(Locale.ROOT))?100:0)+(BankAmountMatch.matches(amount,open.multiply(BigDecimal.valueOf(amount.signum())))?20:0);
            for(var payment:summary.payments())if(!payment.isOffset()&&BankAmountMatch.matches(amount,payment.amountEur())&&!paymentLinked(payment.id()))
                matches.add(new Match(invoice.id(),invoice.number(),open,score+40,payment.id(),"Bestaande betaling koppelen: geen nieuwe boeking",payment.receivedAt(),payment.reference()));
            String fresh=invoice.isCreditNote()?"Nieuwe terugbetaling op creditnota "+invoice.number():"Nieuwe "+(amount.signum()>0?"ontvangst":"terugbetaling")+" registreren";
            if(open.signum()>0&&score>0)matches.add(new Match(invoice.id(),invoice.number(),open,score,null,fresh,null,null));
        }
        return matches.stream().sorted(Comparator.comparingInt(Match::score).reversed()).limit(8).toList();
    }

    @Transactional
    public BankStatementLineEntity allocate(long id,Allocation request) {
        var row=get(id,true);
        requireUnclassified(row);
        if(describe(row).allocated) {
            if(request!=null&&row.salesPaymentId!=null&&Objects.equals(row.salesOrderId,request.salesOrderId())
                    &&(request.existingPaymentId()==null&&row.allocationCreatedPayment||Objects.equals(row.salesPaymentId,request.existingPaymentId())))return row;
            throw new BusinessRuleException("Deze bankbeweging is al gekoppeld; maak de koppeling eerst ongedaan");
        }
        if(request==null)throw new BusinessRuleException("Kies een factuur of bestaande betaling");
        // Serialize matching with receipt corrections and voids on this invoice.
        orders.lockById(request.salesOrderId());
        var order=sales.get(request.salesOrderId());
        if(request.existingPaymentId()!=null) {
            var payment=incoming.forOrder(order.id()).stream().filter(p->p.id().equals(request.existingPaymentId())).findFirst().orElseThrow(()->new BusinessRuleException("De gekozen betaling hoort niet bij deze factuur"));
            if(payment.isOffset())throw new BusinessRuleException("Een verrekening is geen bankbeweging");
            if(Boolean.TRUE.equals(request.followBank()))payment=followReceipt(row,order.id(),payment);
            if(!BankAmountMatch.matches(row.amountEur,payment.amountEur()))throw new BusinessRuleException("Bedrag of richting komen niet overeen; maximaal € 0,02 verschil is toegestaan");
            if(paymentLinked(payment.id()))throw new BusinessRuleException("Deze betaling is al aan een bankbeweging gekoppeld");
            if(!Objects.equals(payment.bankAccount(),row.account))incoming.update(order.id(),payment.id(),
                    new IncomingPaymentService.Request(payment.amountEur().abs(),payment.receivedAt(),payment.timeZone(),payment.reference(),
                            payment.amountEur().signum()<0?IncomingPaymentService.Direction.REFUND:IncomingPaymentService.Direction.RECEIPT,row.account));
            row.salesPaymentId=payment.id();row.allocationCreatedPayment=false;
        } else {
            Set<Long> before=new HashSet<>(incoming.forOrder(order.id()).stream().map(SalesPayment::id).toList());
            var summary=incoming.summary(order,sales.price(order));
            BigDecimal remaining=row.amountEur.signum()<0?summary.refundableEur().negate():summary.remainingEur();
            BigDecimal paymentAmount=BankAmountMatch.matches(row.amountEur,remaining)?remaining.abs():row.amountEur.abs();
            incoming.add(order.id(),new IncomingPaymentService.Request(paymentAmount,row.bookedAt,row.timeZone,row.reference,
                    row.amountEur.signum()<0?IncomingPaymentService.Direction.REFUND:IncomingPaymentService.Direction.RECEIPT,row.account));
            row.salesPaymentId=incoming.forOrder(order.id()).stream().filter(p->!before.contains(p.id())).max(Comparator.comparing(SalesPayment::id)).orElseThrow().id();
            row.allocationCreatedPayment=true;
        }
        row.salesOrderId=order.id();row.allocatedAt=Instant.now();em.flush();
        describe(row);audit(row,"Bankbeweging expliciet gekoppeld aan "+order.number()+" · toegewezen "+row.allocatedAmountEur+" · verschil "+row.allocationDifferenceEur);return row;
    }

    /**
     * "Betaling volgt de bank" for a receipt: before the match it takes the line's amount, moment, time zone and
     * account through the receipt's own correction (the invoice's balance follows). Direction and an existing bank
     * link are refused as without following.
     */
    private SalesPayment followReceipt(BankStatementLineEntity row,long orderId,SalesPayment payment) {
        if(payment.amountEur().signum()!=row.amountEur.signum())throw new BusinessRuleException("Bedrag of richting komen niet overeen; maximaal € 0,02 verschil is toegestaan");
        if(paymentLinked(payment.id()))throw new BusinessRuleException("Deze betaling is al aan een bankbeweging gekoppeld");
        if(payment.amountEur().compareTo(row.amountEur)==0&&payment.receivedAt().equals(row.bookedAt)
                &&Objects.equals(payment.timeZone(),row.timeZone)&&Objects.equals(payment.bankAccount(),row.account))return payment;
        LocalDate wasDay=payment.receivedAt().atZone(ZoneId.of(payment.timeZone()==null?"Europe/Brussels":payment.timeZone())).toLocalDate();
        incoming.update(orderId,payment.id(),new IncomingPaymentService.Request(row.amountEur.abs(),row.bookedAt,row.timeZone,payment.reference(),
                row.amountEur.signum()<0?IncomingPaymentService.Direction.REFUND:IncomingPaymentService.Direction.RECEIPT,row.account));
        var followed=incoming.forOrder(orderId).stream().filter(p->p.id().equals(payment.id())).findFirst().orElseThrow();
        audit(row,"Ontvangst volgt de bank: "+eur(row.amountEur.abs())+" op "+DAY.format(localDay(row))
                +" (was "+eur(payment.amountEur().abs())+" op "+DAY.format(wasDay)+")");
        return followed;
    }

    @Transactional
    public void unallocate(long id) {
        var row=get(id,true);requireUnclassified(row);if(row.salesPaymentId==null)return;
        long paymentId=row.salesPaymentId,orderId=row.salesOrderId;boolean created=row.allocationCreatedPayment;
        row.salesPaymentId=null;row.salesOrderId=null;row.allocatedAt=null;row.allocationCreatedPayment=false;em.flush();
        if(created)incoming.delete(orderId,paymentId);
        audit(row,created?"Bankkoppeling ongedaan; vanuit deze bankbeweging aangemaakte betaling ingetrokken":"Bankkoppeling ongedaan; bestaande betaling behouden");
    }
    public boolean paymentLinked(long id) {return em.createQuery("select count(b) from BankStatementLineEntity b where b.salesPaymentId=:id",Long.class).setParameter("id",id).getSingleResult()>0;}

    /** Called while the cost parent is locked. Removing part of a shared transfer requires explicitly unlinking it first. */
    public void requireExclusiveCostLink(long bankId,long costId,Long paymentId) {
        var row=em.find(BankStatementLineEntity.class,bankId);
        boolean shared=row!=null&&row.salesPaymentId!=null
                ||em.createQuery("select count(p) from PurchasePaymentEntity p where p.bankStatementLineId=:id",Long.class)
                    .setParameter("id",bankId).getSingleResult()>0
                ||em.createQuery("select count(c) from CompanyCostEntity c where c.bankStatementLineId=:id and c.id<>:cost",Long.class)
                    .setParameter("id",bankId).setParameter("cost",costId).getSingleResult()>0;
        if(!shared) {
            String exclusion=paymentId==null?"p.companyCostId<>:excluded":"p.id<>:excluded";
            shared=em.createQuery("select count(p) from CostPaymentEntity p where p.bankStatementLineId=:id and "+exclusion,Long.class)
                    .setParameter("id",bankId).setParameter("excluded",paymentId==null?costId:paymentId).getSingleResult()>0;
        }
        if(shared)throw new BusinessRuleException("Deze banktransactie draagt meerdere betalingen; maak de bankkoppeling eerst los");
    }

    @Transactional
    public void delete(long id) {
        var row=get(id,true);
        requireUnclassified(row);
        if(row.source!=null)throw new BusinessRuleException("Een opgehaalde banktransactie kan niet worden verwijderd; je kunt alleen de koppeling losmaken");
        if(row.salesPaymentId!=null)throw new BusinessRuleException("Maak eerst de factuurkoppeling ongedaan");
        unlink(row.id);
        /* The line goes, so its cost-registration receipt guards nothing any more; the cost stays. */
        var receipt=em.find(BankCostRegistrationEntity.class,row.id);
        if(receipt!=null)em.remove(receipt);
        audit(row,"Bankbeweging ingetrokken: "+row.bookedAt+" · "+row.amountEur+" · "+row.reference);
        em.remove(row);
    }
    /**
     * The purchase payments and the cost payment this line settled point back at it, in the line's own transaction:
     * a refused link leaves no line, and a payment is on the bank once. A cost payment is linked by its id; a cost
     * named alone resolves to its single unlinked payment, or to the cost itself when it has no payment rows (from
     * before the rows), and several open payments need the choice. The cost-level link is set too when the payment
     * is the cost's only one: the link of a one-shot full payment. A retried request returns the line above without
     * coming here. Deleting a payment or a cost leaves the line: it is the bank's truth.
     */
    private void link(BankStatementLineEntity row,List<Long> paymentIds,Long costId,Long costPaymentId) {
        lockOutgoingParents(paymentIds,costId,costPaymentId);
        var ids=paymentIds==null?List.<Long>of():paymentIds;
        if(!ids.isEmpty())for(Long orderId:em.createQuery("select distinct p.orderId from PurchasePaymentEntity p where p.id in :ids order by p.orderId",Long.class).setParameter("ids",ids).getResultList())
            em.find(SourcingEntities.PurchaseOrderEntity.class,orderId,LockModeType.PESSIMISTIC_WRITE);
        for(Long paymentId:ids.stream().sorted(java.util.Comparator.nullsFirst(Long::compareTo)).toList()) {
            var payment=paymentId==null?null:em.find(SourcingEntities.PurchasePaymentEntity.class,paymentId,LockModeType.PESSIMISTIC_WRITE);
            if(payment==null)throw new BusinessRuleException("Betaling niet gevonden");
            em.refresh(payment,LockModeType.PESSIMISTIC_WRITE);
            paymentOrigins.requireBankEligible(paymentId);
            if(payment.bankStatementLineId!=null)throw new BusinessRuleException("Deze betaling is al aan een bankbeweging gekoppeld");
            // A credit offset never left the bank: no manual movement may carry it either.
            if(em.createQuery("select count(c) from be.enrosed.sourcing.adapter.out.persistence.SourcingEntities$PurchaseSupplierCreditEntity c where c.offsetPaymentId=:id",Long.class).setParameter("id",paymentId).getSingleResult()>0)
                throw new BusinessRuleException("Een verrekend leverancierstegoed is geen bankbetaling");
            payment.bankStatementLineId=row.id;
        }
        if(costPaymentId!=null) {
            var payment=em.find(CostPaymentEntity.class,costPaymentId);
            if(payment==null)throw new BusinessRuleException("Betaling niet gevonden");
            if(payment.bankStatementLineId!=null)throw new BusinessRuleException("Deze betaling is al aan een bankbeweging gekoppeld");
            payment.bankStatementLineId=row.id;
            var cost=payment.companyCostId==null?null:em.find(CompanyCostEntity.class,payment.companyCostId);
            if(cost!=null&&cost.bankStatementLineId==null&&costPayments(cost.id).size()==1)cost.bankStatementLineId=row.id;
            return;
        }
        if(costId==null)return;
        var cost=em.find(CompanyCostEntity.class,costId);
        if(cost==null)throw new BusinessRuleException("Kost niet gevonden");
        var payments=costPayments(costId);
        if(payments.isEmpty()) {
            if(cost.bankStatementLineId!=null)throw new BusinessRuleException("Deze kost is al aan een bankbeweging gekoppeld");
            cost.bankStatementLineId=row.id;
            return;
        }
        var open=payments.stream().filter(payment->payment.bankStatementLineId==null).toList();
        if(open.isEmpty())throw new BusinessRuleException("Deze kost is al aan een bankbeweging gekoppeld");
        if(open.size()>1)throw new BusinessRuleException("Kies de betaling die deze bankbeweging dekt");
        open.getFirst().bankStatementLineId=row.id;
        if(payments.size()==1&&cost.bankStatementLineId==null)cost.bankStatementLineId=row.id;
    }
    private List<CostPaymentEntity> costPayments(long costId) {
        return em.createQuery("from CostPaymentEntity p where p.companyCostId=:id order by p.paidOn,p.id",CostPaymentEntity.class).setParameter("id",costId).getResultList();
    }
    /** Same parent locks used by payment corrections; never change a reverse link from a stale payment snapshot. */
    private void lockOutgoingParents(List<Long> purchaseIds,Long costId,Long costPaymentId) {
        if(purchaseIds!=null&&!purchaseIds.isEmpty()) {
            var ids=purchaseIds.stream().filter(Objects::nonNull).distinct().toList();
            if(!ids.isEmpty())for(Long orderId:em.createQuery("select distinct p.orderId from PurchasePaymentEntity p where p.id in :ids order by p.orderId",Long.class).setParameter("ids",ids).getResultList())
                em.find(SourcingEntities.PurchaseOrderEntity.class,orderId,LockModeType.PESSIMISTIC_WRITE);
        }
        Long parent=costPaymentId==null?costId:em.createQuery("select p.companyCostId from CostPaymentEntity p where p.id=:id",Long.class)
                .setParameter("id",costPaymentId).getResultStream().findFirst().orElse(null);
        if(parent!=null) { em.find(CompanyCostEntity.class,parent,LockModeType.PESSIMISTIC_WRITE); requireNotTicaOffset(parent); }
    }
    private void requireNotTicaOffset(Long costId) {
        if(ticaSettlements!=null&&ticaSettlements.isResolvable())ticaSettlements.get().requireEditableCost(costId);
    }
    /**
     * What the line carried is open again once it goes; done on the managed rows so the session sees it at once.
     * A cost-registration receipt stays: while the cost it created exists, a new registration of the line would count
     * that expense twice (BankCostRegistrationService says so).
     */
    private void unlink(long id) {
        var purchaseIds=em.createQuery("select p.id from PurchasePaymentEntity p where p.bankStatementLineId=:id",Long.class).setParameter("id",id).getResultList();
        var costIds=new TreeSet<>(em.createQuery("select p.companyCostId from CostPaymentEntity p where p.bankStatementLineId=:id",Long.class).setParameter("id",id).getResultList());
        costIds.addAll(em.createQuery("select c.id from CompanyCostEntity c where c.bankStatementLineId=:id",Long.class).setParameter("id",id).getResultList());
        lockOutgoingParents(purchaseIds,null,null);
        for(Long costId:costIds)em.find(CompanyCostEntity.class,costId,LockModeType.PESSIMISTIC_WRITE);
        for(var payment:em.createQuery("from PurchasePaymentEntity p where p.bankStatementLineId=:id",SourcingEntities.PurchasePaymentEntity.class).setParameter("id",id).getResultList())payment.bankStatementLineId=null;
        for(var cost:em.createQuery("from CompanyCostEntity c where c.bankStatementLineId=:id",CompanyCostEntity.class).setParameter("id",id).getResultList())cost.bankStatementLineId=null;
        for(var payment:em.createQuery("from CostPaymentEntity p where p.bankStatementLineId=:id",CostPaymentEntity.class).setParameter("id",id).getResultList())payment.bankStatementLineId=null;
    }
    /** The list learns an outgoing counterparty once the line is in; a failure of the list never undoes the line. */
    private void learnParty(String counterparty) {
        if(parties==null||!parties.isResolvable())return;
        try { parties.get().learn(counterparty,null,null,null,null,null); }
        catch(RuntimeException failure) { LOG.debug("De lijst Aan wie leerde de bankbeweging niet: "+failure.getMessage(),failure); }
    }
    private BankStatementLineEntity get(long id,boolean lock) {var row=lock?em.find(BankStatementLineEntity.class,id,LockModeType.PESSIMISTIC_WRITE):em.find(BankStatementLineEntity.class,id);if(row==null)throw new NotFoundException("Bankbeweging",id);return row;}
    void audit(BankStatementLineEntity row,String text){activity.record(ActivityLogService.ACTION_UPDATED,"BANK_STATEMENT",row.id.toString(),row.account+" · "+row.amountEur,text);}
    private static String account(String value){String account=IncomingPaymentService.normalizeAccount(value);if(account==null)throw new BusinessRuleException("Kies de bankrekening van deze beweging");return account;}
    private static String limited(String text,int length){if(text==null)return "";text=text.strip();if(text.length()>length)throw new BusinessRuleException("Een tekstveld is te lang (maximaal "+length+")");return text;}
    private static String hash(String text){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));}catch(Exception failure){throw new IllegalStateException(failure);}}
}
