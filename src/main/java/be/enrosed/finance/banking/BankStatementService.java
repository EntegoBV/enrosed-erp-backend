package be.enrosed.finance.banking;

import be.enrosed.sales.application.*;
import be.enrosed.sales.application.port.out.SalesRepositories;
import be.enrosed.sales.domain.*;
import be.enrosed.shared.*;
import be.enrosed.shared.audit.ActivityLogService;
import be.enrosed.shared.security.CurrentActor;
import jakarta.enterprise.context.ApplicationScoped;
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
    @Inject EntityManager em;
    @Inject SalesOrderService sales;
    @Inject SalesRepositories.Orders orders;
    @Inject IncomingPaymentService incoming;
    @Inject CurrentActor actor;
    @Inject ActivityLogService activity;

    public enum Direction { INCOMING, OUTGOING }
    public record ManualRequest(String account, BigDecimal amountEur, Direction direction, Instant bookedAt,
                                String timeZone, String reference, String counterparty, String requestId) {}
    public record Match(long salesOrderId,String number,BigDecimal openEur,int score,Long existingPaymentId,String reason,Instant receivedAt,String reference) {}
    public record Allocation(long salesOrderId,Long existingPaymentId) {}

    public List<BankStatementLineEntity> list() {
        return em.createQuery("from BankStatementLineEntity order by bookedAt desc,id desc",BankStatementLineEntity.class).getResultList();
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
            return existing;
        }
        var row=new BankStatementLineEntity();row.account=account;row.fingerprint=fingerprint;row.amountEur=amount;
        row.bookedAt=request.bookedAt();row.timeZone=zone;row.reference=reference;row.counterparty=counterparty;
        row.recordedAt=Instant.now();row.actor=actor.current().displayName();em.persist(row);em.flush();
        audit(row,"Bankbeweging handmatig geregistreerd: "+row.bookedAt+" · "+row.amountEur+" · "+reference);
        return row;
    }

    public List<Match> suggestions(long id) {var row=get(id,false);return suggestions(orders.findAll().stream().filter(SalesOrder::isInvoice).toList(),row.amountEur,row.reference,row.account);}

    private List<Match> suggestions(List<SalesOrder> invoices,BigDecimal amount,String reference,String account) {
        List<Match> matches=new ArrayList<>();String text=Objects.toString(reference,"").toUpperCase(Locale.ROOT);
        for(var invoice:invoices) {
            if(Set.of(QuoteStatus.CONCEPT,QuoteStatus.GEANNULEERD,QuoteStatus.AFGEWEZEN,QuoteStatus.VERLOPEN).contains(invoice.status()))continue;
            var summary=incoming.summary(invoice,sales.price(invoice));
            BigDecimal open=amount.signum()>0?summary.remainingEur():summary.refundableEur();
            int score=(invoice.number()!=null&&text.contains(invoice.number().toUpperCase(Locale.ROOT))?100:0)+(open.compareTo(amount.abs())==0?20:0);
            for(var payment:summary.payments())if(payment.amountEur().compareTo(amount)==0&&(payment.bankAccount()==null||account.equals(payment.bankAccount()))&&!paymentLinked(payment.id()))
                matches.add(new Match(invoice.id(),invoice.number(),open,score+40,payment.id(),"Bestaande betaling koppelen: geen nieuwe boeking",payment.receivedAt(),payment.reference()));
            if(open.signum()>0&&score>0)matches.add(new Match(invoice.id(),invoice.number(),open,score,null,"Nieuwe "+(amount.signum()>0?"ontvangst":"terugbetaling")+" registreren",null,null));
        }
        return matches.stream().sorted(Comparator.comparingInt(Match::score).reversed()).limit(8).toList();
    }

    @Transactional
    public BankStatementLineEntity allocate(long id,Allocation request) {
        var row=get(id,true);
        if(row.salesPaymentId!=null)throw new BusinessRuleException("Deze bankbeweging is al gekoppeld; maak de koppeling eerst ongedaan");
        if(request==null)throw new BusinessRuleException("Kies een factuur of bestaande betaling");
        // Serialize matching with receipt corrections and voids on this invoice.
        orders.lockById(request.salesOrderId());
        var order=sales.get(request.salesOrderId());
        if(request.existingPaymentId()!=null) {
            var payment=incoming.forOrder(order.id()).stream().filter(p->p.id().equals(request.existingPaymentId())).findFirst().orElseThrow(()->new BusinessRuleException("De gekozen betaling hoort niet bij deze factuur"));
            if(payment.amountEur().compareTo(row.amountEur)!=0||payment.bankAccount()!=null&&!row.account.equals(payment.bankAccount()))throw new BusinessRuleException("Bedrag, richting of bankrekening komen niet overeen");
            if(paymentLinked(payment.id()))throw new BusinessRuleException("Deze betaling is al aan een bankbeweging gekoppeld");
            row.salesPaymentId=payment.id();row.allocationCreatedPayment=false;
        } else {
            Set<Long> before=new HashSet<>(incoming.forOrder(order.id()).stream().map(SalesPayment::id).toList());
            incoming.add(order.id(),new IncomingPaymentService.Request(row.amountEur.abs(),row.bookedAt,row.timeZone,row.reference,
                    row.amountEur.signum()<0?IncomingPaymentService.Direction.REFUND:IncomingPaymentService.Direction.RECEIPT,row.account));
            row.salesPaymentId=incoming.forOrder(order.id()).stream().filter(p->!before.contains(p.id())).max(Comparator.comparing(SalesPayment::id)).orElseThrow().id();
            row.allocationCreatedPayment=true;
        }
        row.salesOrderId=order.id();row.allocatedAt=Instant.now();em.flush();
        audit(row,"Bankbeweging expliciet gekoppeld aan "+order.number());return row;
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
    @Transactional
    public void delete(long id) {
        var row=get(id,true);
        if(row.salesPaymentId!=null)throw new BusinessRuleException("Maak eerst de factuurkoppeling ongedaan");
        audit(row,"Bankbeweging ingetrokken: "+row.bookedAt+" · "+row.amountEur+" · "+row.reference);
        em.remove(row);
    }
    private BankStatementLineEntity get(long id,boolean lock) {var row=lock?em.find(BankStatementLineEntity.class,id,LockModeType.PESSIMISTIC_WRITE):em.find(BankStatementLineEntity.class,id);if(row==null)throw new NotFoundException("Bankbeweging",id);return row;}
    private void audit(BankStatementLineEntity row,String text){activity.record(ActivityLogService.ACTION_UPDATED,"BANK_STATEMENT",row.id.toString(),row.account+" · "+row.amountEur,text);}
    private static String account(String value){String account=IncomingPaymentService.normalizeAccount(value);if(account==null)throw new BusinessRuleException("Kies de bankrekening van deze beweging");return account;}
    private static String limited(String text,int length){if(text==null)return "";text=text.strip();if(text.length()>length)throw new BusinessRuleException("Een tekstveld is te lang (maximaal "+length+")");return text;}
    private static String hash(String text){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));}catch(Exception failure){throw new IllegalStateException(failure);}}
}
