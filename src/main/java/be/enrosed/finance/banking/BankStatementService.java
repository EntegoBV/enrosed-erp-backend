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
    @Inject be.enrosed.finance.application.CompanyCostService costs;
    /** The list "Aan wie" learns the counterparty of an outgoing movement; incoming money comes from customers, who are no payees. */
    @Inject Instance<CostPartyService> parties;

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
    public record Allocation(long salesOrderId,Long existingPaymentId) {}
    public record OutgoingLinks(List<Long> purchasePaymentIds,Long companyCostId,Long costPaymentId) {}
    public record CostAllocation(long companyCostId) {}
    public record ExternalRequest(String sourceId,String account,BigDecimal amountEur,Instant bookedAt,String timeZone,
                                  String reference,String counterparty,Instant sourceUpdatedAt,Boolean sourceReconciled) {}
    public enum ImportOutcome { CREATED, UPDATED, UNCHANGED, CONFLICT }
    public record ImportResult(Long id,ImportOutcome outcome) {}

    public List<BankStatementLineEntity> list() {
        var rows=em.createQuery("from BankStatementLineEntity order by bookedAt desc,id desc",BankStatementLineEntity.class).getResultList();
        // Fetch reverse links once, including costs recorded before individual payment rows existed.
        Set<Long> purchase=new HashSet<>(em.createQuery("select distinct p.bankStatementLineId from PurchasePaymentEntity p where p.bankStatementLineId is not null",Long.class).getResultList());
        Set<Long> costs=new HashSet<>(em.createQuery("select distinct p.bankStatementLineId from CostPaymentEntity p where p.bankStatementLineId is not null",Long.class).getResultList());
        costs.addAll(em.createQuery("select distinct c.bankStatementLineId from CompanyCostEntity c where c.bankStatementLineId is not null",Long.class).getResultList());
        for(var row:rows)describe(row,purchase.contains(row.id),costs.contains(row.id));
        return rows;
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
        if(request==null)throw new BusinessRuleException("Kies de betaling die bij deze banktransactie hoort");
        if(row.amountEur.signum()>=0||row.salesPaymentId!=null)throw new BusinessRuleException("Kies een vrije uitgaande banktransactie");
        var ids=request.purchasePaymentIds()==null?List.<Long>of():request.purchasePaymentIds();
        if(ids.stream().anyMatch(Objects::isNull)||new HashSet<>(ids).size()!=ids.size())throw new BusinessRuleException("Kies elke betaling maar één keer");
        List<SourcingEntities.PurchasePaymentEntity> purchases=new ArrayList<>();
        BigDecimal total=BigDecimal.ZERO;
        // Payment corrections hold the parent order lock; take the same lock before reading amounts.
        if(!ids.isEmpty())for(Long orderId:em.createQuery("select distinct p.orderId from PurchasePaymentEntity p where p.id in :ids order by p.orderId",Long.class).setParameter("ids",ids).getResultList())
            em.find(SourcingEntities.PurchaseOrderEntity.class,orderId,LockModeType.PESSIMISTIC_WRITE);
        for(long paymentId:ids.stream().sorted().toList()) {
            var payment=em.find(SourcingEntities.PurchasePaymentEntity.class,paymentId,LockModeType.PESSIMISTIC_WRITE);
            if(payment==null)throw new BusinessRuleException("Betaling niet gevonden");
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
            if(candidates.size()>1)throw new BusinessRuleException("Kies de betaling die deze banktransactie dekt");
            if(candidates.size()==1)costPayment=em.find(CostPaymentEntity.class,candidates.getFirst().id,LockModeType.PESSIMISTIC_WRITE);
            else {
                if(cost.paidOn==null)throw new BusinessRuleException("Noteer eerst de betaling op deze kost");
                if(cost.bankStatementLineId!=null&&!cost.bankStatementLineId.equals(id))throw new BusinessRuleException("Deze kost is al aan een andere banktransactie gekoppeld");
                total=total.add(cost.toDomain().totalInclEur());
            }
        }
        if(costPayment!=null) {
            if(costPayment.bankStatementLineId!=null&&!costPayment.bankStatementLineId.equals(id))throw new BusinessRuleException("Deze betaling is al aan een andere banktransactie gekoppeld");
            // Historical free-text bank labels need not equal an imported IBAN. The selected movement is authoritative.
            total=total.add(costPayment.amountEur);
        }
        if(purchases.isEmpty()&&cost==null)throw new BusinessRuleException("Kies minstens één betaling");
        if(total.compareTo(row.amountEur.abs())!=0)throw new BusinessRuleException("Het totaal van de betalingen moet gelijk zijn aan het bankbedrag (€ "+row.amountEur.abs().toPlainString()+")");
        Set<Long> linkedPurchases=new HashSet<>(em.createQuery("select p.id from PurchasePaymentEntity p where p.bankStatementLineId=:id",Long.class).setParameter("id",id).getResultList());
        Set<Long> linkedPayments=new HashSet<>(em.createQuery("select p.id from CostPaymentEntity p where p.bankStatementLineId=:id",Long.class).setParameter("id",id).getResultList());
        Set<Long> linkedCosts=new HashSet<>(em.createQuery("select c.id from CompanyCostEntity c where c.bankStatementLineId=:id",Long.class).setParameter("id",id).getResultList());
        Long selectedCost=cost==null?null:cost.id;
        Long selectedPayment=costPayment==null?null:costPayment.id;
        if(!new HashSet<>(ids).containsAll(linkedPurchases)
                ||linkedPayments.stream().anyMatch(paymentId->!Objects.equals(paymentId,selectedPayment))
                ||linkedCosts.stream().anyMatch(costId->!Objects.equals(costId,selectedCost)))
            throw new BusinessRuleException("Deze banktransactie is al aan een andere betaling gekoppeld");
        for(var payment:purchases)payment.bankStatementLineId=id;
        if(costPayment!=null) { costPayment.bankStatementLineId=id;costPayment.account=row.account; }
        if(cost!=null&&(costPayment==null||costPayments(cost.id).size()==1))cost.bankStatementLineId=id;
        row.allocatedAt=Instant.now();em.flush();audit(row,"Banktransactie gekoppeld aan bestaande inkoop- of kostbetaling");
        return describe(row);
    }

    @Transactional
    public void unlinkExisting(long id) {
        var row=get(id,true);
        if(row.salesPaymentId!=null)throw new BusinessRuleException("Maak de factuurkoppeling ongedaan via de factuur");
        unlink(id);row.allocatedAt=null;em.flush();audit(row,"Bankkoppeling losgemaakt; betaalregistraties behouden");
    }

    /** A selected bank movement creates exactly one cost payment, including retries after a lost response. */
    @Transactional
    public BankStatementLineEntity recordCostPayment(long id,CostAllocation request) {
        var row=get(id,true);
        if(request==null||request.companyCostId()<=0)throw new BusinessRuleException("Kies de kost bij deze banktransactie");
        if(row.amountEur.signum()>=0)throw new BusinessRuleException("Kies een uitgaande banktransactie");
        var cost=em.find(CompanyCostEntity.class,request.companyCostId(),LockModeType.PESSIMISTIC_WRITE);
        if(cost==null)throw new BusinessRuleException("Kost niet gevonden");
        if(describe(row).allocated) {
            var linked=em.createQuery("from CostPaymentEntity p where p.bankStatementLineId=:id",CostPaymentEntity.class).setParameter("id",id).getResultList();
            if(row.salesPaymentId==null&&linked.size()==1&&Objects.equals(linked.getFirst().companyCostId,cost.id)
                    &&linked.getFirst().amountEur.compareTo(row.amountEur.abs())==0)return row;
            throw new BusinessRuleException("Deze banktransactie is al gekoppeld aan een andere betaling");
        }
        Set<Long> before=new HashSet<>(costPayments(cost.id).stream().map(payment->payment.id).toList());
        String note=row.reference==null?null:row.reference.substring(0,Math.min(300,row.reference.length()));
        var result=costs.addPayment(cost.id,new be.enrosed.finance.domain.CostPayment(null,cost.id,
                row.bookedAt.atZone(ZoneId.of(row.timeZone)).toLocalDate(),row.amountEur.abs(),row.account,null,note,null,null));
        var added=result.payments().stream().filter(payment->!before.contains(payment.id())).findFirst().orElseThrow();
        return linkExisting(id,new OutgoingLinks(null,cost.id,added.id()));
    }

    private static void describe(BankStatementLineEntity row,boolean purchase,boolean cost) {
        row.allocated=row.salesPaymentId!=null||purchase||cost;
        row.allocationLabel=row.salesPaymentId!=null?"Verkoopfactuur":purchase&&cost?"Inkoop en kosten":purchase?"Inkoopbetaling":cost?"Kostbetaling":null;
    }
    private BankStatementLineEntity describe(BankStatementLineEntity row) {
        boolean purchase=em.createQuery("select count(p) from PurchasePaymentEntity p where p.bankStatementLineId=:id",Long.class).setParameter("id",row.id).getSingleResult()>0;
        boolean cost=em.createQuery("select count(p) from CostPaymentEntity p where p.bankStatementLineId=:id",Long.class).setParameter("id",row.id).getSingleResult()>0
                ||em.createQuery("select count(c) from CompanyCostEntity c where c.bankStatementLineId=:id",Long.class).setParameter("id",row.id).getSingleResult()>0;
        describe(row,purchase,cost);return row;
    }

    /** Records an actual bank movement; creating this row never pays or changes an invoice. */
    @Transactional
    public BankStatementLineEntity create(ManualRequest request) {
        if(request==null)throw new BusinessRuleException("Geen bankbeweging meegestuurd");
        String account=account(request.account());
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
            int score=(invoice.number()!=null&&text.contains(PaymentReference.normalized(invoice.number()).toUpperCase(Locale.ROOT))?100:0)+(open.compareTo(amount.abs())==0?20:0);
            for(var payment:summary.payments())if(!payment.isOffset()&&payment.amountEur().compareTo(amount)==0&&!paymentLinked(payment.id()))
                matches.add(new Match(invoice.id(),invoice.number(),open,score+40,payment.id(),"Bestaande betaling koppelen: geen nieuwe boeking",payment.receivedAt(),payment.reference()));
            String fresh=invoice.isCreditNote()?"Nieuwe terugbetaling op creditnota "+invoice.number():"Nieuwe "+(amount.signum()>0?"ontvangst":"terugbetaling")+" registreren";
            if(open.signum()>0&&score>0)matches.add(new Match(invoice.id(),invoice.number(),open,score,null,fresh,null,null));
        }
        return matches.stream().sorted(Comparator.comparingInt(Match::score).reversed()).limit(8).toList();
    }

    @Transactional
    public BankStatementLineEntity allocate(long id,Allocation request) {
        var row=get(id,true);
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
            if(payment.amountEur().compareTo(row.amountEur)!=0)throw new BusinessRuleException("Bedrag of richting komen niet overeen");
            if(paymentLinked(payment.id()))throw new BusinessRuleException("Deze betaling is al aan een bankbeweging gekoppeld");
            if(!Objects.equals(payment.bankAccount(),row.account))incoming.update(order.id(),payment.id(),
                    new IncomingPaymentService.Request(payment.amountEur().abs(),payment.receivedAt(),payment.timeZone(),payment.reference(),
                            payment.amountEur().signum()<0?IncomingPaymentService.Direction.REFUND:IncomingPaymentService.Direction.RECEIPT,row.account));
            row.salesPaymentId=payment.id();row.allocationCreatedPayment=false;
        } else {
            Set<Long> before=new HashSet<>(incoming.forOrder(order.id()).stream().map(SalesPayment::id).toList());
            incoming.add(order.id(),new IncomingPaymentService.Request(row.amountEur.abs(),row.bookedAt,row.timeZone,row.reference,
                    row.amountEur.signum()<0?IncomingPaymentService.Direction.REFUND:IncomingPaymentService.Direction.RECEIPT,row.account));
            row.salesPaymentId=incoming.forOrder(order.id()).stream().filter(p->!before.contains(p.id())).max(Comparator.comparing(SalesPayment::id)).orElseThrow().id();
            row.allocationCreatedPayment=true;
        }
        row.salesOrderId=order.id();row.allocatedAt=Instant.now();em.flush();
        audit(row,"Bankbeweging expliciet gekoppeld aan "+order.number());return describe(row);
    }

    @Transactional
    public void unallocate(long id) {
        var row=get(id,true);if(row.salesPaymentId==null)return;
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
        if(row.source!=null)throw new BusinessRuleException("Een opgehaalde banktransactie kan niet worden verwijderd; je kunt alleen de koppeling losmaken");
        if(row.salesPaymentId!=null)throw new BusinessRuleException("Maak eerst de factuurkoppeling ongedaan");
        unlink(row.id);
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
        for(Long paymentId:paymentIds==null?List.<Long>of():paymentIds) {
            var payment=paymentId==null?null:em.find(SourcingEntities.PurchasePaymentEntity.class,paymentId);
            if(payment==null)throw new BusinessRuleException("Betaling niet gevonden");
            if(payment.bankStatementLineId!=null)throw new BusinessRuleException("Deze betaling is al aan een bankbeweging gekoppeld");
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
        if(parent!=null)em.find(CompanyCostEntity.class,parent,LockModeType.PESSIMISTIC_WRITE);
    }
    /** What the line carried is open again once it goes; done on the managed rows so the session sees it at once. */
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
    private void audit(BankStatementLineEntity row,String text){activity.record(ActivityLogService.ACTION_UPDATED,"BANK_STATEMENT",row.id.toString(),row.account+" · "+row.amountEur,text);}
    private static String account(String value){String account=IncomingPaymentService.normalizeAccount(value);if(account==null)throw new BusinessRuleException("Kies de bankrekening van deze beweging");return account;}
    private static String limited(String text,int length){if(text==null)return "";text=text.strip();if(text.length()>length)throw new BusinessRuleException("Een tekstveld is te lang (maximaal "+length+")");return text;}
    private static String hash(String text){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));}catch(Exception failure){throw new IllegalStateException(failure);}}
}
