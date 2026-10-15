package be.enrosed.finance.banking;

import be.enrosed.finance.adapter.out.persistence.CompanyCostEntity;
import be.enrosed.finance.application.CompanyCostService;
import be.enrosed.finance.domain.CompanyCost;
import be.enrosed.shared.BusinessRuleException;
import be.enrosed.shared.NotFoundException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.*;
import jakarta.transaction.Transactional;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

/** Create an ordinary local cost and its payment from one existing bank line, without an orphan-cost retry window. */
@ApplicationScoped
public class BankCostRegistrationService {
    @Inject EntityManager em;
    @Inject CompanyCostService costs;
    @Inject BankStatementService bank;
    @Inject ObjectMapper json;

    public record Request(String requestId,CompanyCost cost) {}
    public record Result(BankStatementLineEntity line,CompanyCostService.CostView cost) {}
    public static class Conflict extends BusinessRuleException {
        public final String code;
        Conflict(String code,String message) {super(message);this.code=code;}
    }

    @Transactional
    public Result register(long id,Request request) {
        if(request==null||request.cost()==null)throw new BusinessRuleException("Vul de gegevens van de nieuwe kost in");
        String requestId=checkedId(request.requestId());
        if(request.cost().id()!=null)throw new BusinessRuleException("Kies een bestaande kost via de factuurlijst");
        var line=em.find(BankStatementLineEntity.class,id,LockModeType.PESSIMISTIC_WRITE);
        if(line==null)throw new NotFoundException("Banktransactie",id);
        if(line.amountEur.signum()>=0)throw new BusinessRuleException("Een nieuwe uitgave hoort bij een uitgaande banktransactie");
        var clean=unpaid(request.cost());
        String hash=hash(line,clean);
        var receipt=em.find(BankCostRegistrationEntity.class,id);
        if(receipt!=null) {
            if(!receipt.requestId.equals(requestId))throw new Conflict("BANK_COST_REGISTRATION_EXISTS","Voor deze banktransactie werd al een kost aangemaakt. Vernieuw het overzicht en hergebruik die kost");
            if(!receipt.requestHash.equals(hash))throw new Conflict("BANK_COST_REGISTRATION_CHANGED","De kost is al geregistreerd met andere gegevens. Herhaal het oorspronkelijke verzoek of open de bestaande kost");
            var cost=em.find(CompanyCostEntity.class,receipt.companyCostId,LockModeType.PESSIMISTIC_WRITE);
            if(cost==null)throw changedAfterRegistration();
            var view=costs.view(cost.toDomain());
            var linked=view.payments().stream().filter(payment->Objects.equals(payment.bankStatementLineId(),id)).toList();
            var described=bank.describe(line);
            if(linked.size()!=1||!BankAmountMatch.matches(line.amountEur,linked.getFirst().amountEur().negate())
                    ||!BankAmountMatch.matches(line.amountEur,described.allocatedAmountEur))throw changedAfterRegistration();
            return new Result(described,view);
        }
        if(bank.describe(line).allocated)throw new Conflict("BANK_ALREADY_ALLOCATED","Deze banktransactie is intussen gekoppeld. Vernieuw het overzicht; er werd geen nieuwe kost aangemaakt");
        // Normal local validation owns VAT, classification and entered invoice amounts. Never infer those from bank text.
        var cost=costs.create(clean);
        if(!BankAmountMatch.matches(line.amountEur,cost.totalInclEur().negate()))
            throw new BusinessRuleException("Het factuurtotaal mag maximaal € 0,02 afwijken van de banktransactie. Gebruik een bestaande kost voor een deelbetaling");
        var saved=bank.recordCostPayment(id,new BankStatementService.CostAllocation(cost.id()));
        receipt=new BankCostRegistrationEntity();receipt.bankStatementLineId=id;receipt.requestId=requestId;
        receipt.requestHash=hash;receipt.companyCostId=cost.id();receipt.createdAt=Instant.now();em.persist(receipt);em.flush();
        return new Result(saved,costs.view(costs.get(cost.id())));
    }
    private static Conflict changedAfterRegistration() {
        return new Conflict("BANK_COST_REGISTRATION_CHANGED","De eerder geregistreerde kost of bankkoppeling is gewijzigd. Controleer de bestaande registratie; er wordt geen nieuwe kost aangemaakt");
    }
    private static String checkedId(String value) {
        try {if(value==null||!UUID.fromString(value).toString().equals(value))throw new IllegalArgumentException();return value;}
        catch(IllegalArgumentException invalid) {throw new BusinessRuleException("De registratiecode ontbreekt of is ongeldig; open het formulier opnieuw");}
    }
    private static CompanyCost unpaid(CompanyCost value) {
        return new CompanyCost(null,value.date(),value.category(),value.description(),value.party(),value.amountExclEur(),value.amountInclEur(),value.vatPct(),
                value.reference(),null,value.salesChannel(),value.notes(),null,null,null,value.vatRegime(),value.vatRegimeConfirmed(),value.supplierCountry(),
                value.costKind(),value.expenseType(),value.vatDeductiblePct(),value.taxDeductiblePct(),value.depreciationYears(),value.depreciationStart(),null);
    }
    private String hash(BankStatementLineEntity line,CompanyCost cost) {
        try {
            String content=line.id+"\n"+line.account+"\n"+line.amountEur.toPlainString()+"\n"+line.bookedAt+"\n"+line.timeZone+"\n"+json.writeValueAsString(cost);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch(Exception impossible) {throw new IllegalStateException(impossible);}
    }
}
