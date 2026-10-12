package be.enrosed.finance.odoo;

import be.enrosed.finance.banking.BankStatementService;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.RoundingMode;
import java.time.*;

final class OdooBankMapper {
    private OdooBankMapper() {}
    static BankStatementService.ExternalRequest map(JsonNode row,String namespace,String account,long companyId,
                                                     long journalId,long eurCurrencyId) {
        try {
            long id=positiveId(row.path("id"));
            if(relationId(row.path("company_id"))!=companyId||relationId(row.path("journal_id"))!=journalId
                    ||relationId(row.path("currency_id"))!=eurCurrencyId)throw new OdooFailure("INVALID_RESPONSE");
            if(!row.path("amount").isNumber()||!row.path("is_reconciled").isBoolean())throw new OdooFailure("INVALID_RESPONSE");
            var amount=row.path("amount").decimalValue().setScale(2,RoundingMode.UNNECESSARY);
            if(amount.precision()>19)throw new OdooFailure("INVALID_RESPONSE");
            // Odoo supplies a booking DATE, not the bank's actual time. Keep this distinction in the UI/docs.
            Instant bookedAt=LocalDate.parse(row.path("date").asText()).atStartOfDay(ZoneId.of("Europe/Brussels")).toInstant();
            Instant updated=LocalDateTime.parse(row.path("write_date").asText().replace(' ','T')).toInstant(ZoneOffset.UTC);
            String counterparty=text(row,"partner_name");
            if(counterparty.isBlank()&&row.path("partner_id").isArray())counterparty=row.path("partner_id").path(1).asText("");
            return new BankStatementService.ExternalRequest(namespace+":"+id,account,amount,bookedAt,"Europe/Brussels",
                    limit(text(row,"payment_ref"),500),limit(counterparty,300),updated,row.path("is_reconciled").booleanValue());
        } catch(OdooFailure failure) { throw failure; }
        catch(Exception invalid) { throw new OdooFailure("INVALID_RESPONSE"); }
    }
    static long positiveId(JsonNode value) {
        if(!value.isIntegralNumber()||!value.canConvertToLong()||value.longValue()<=0)throw new OdooFailure("INVALID_RESPONSE");
        return value.longValue();
    }
    static long relationId(JsonNode value) { return positiveId(value.isArray()?value.path(0):value); }
    static String text(JsonNode row,String field) { return row.path(field).isTextual()?row.path(field).textValue():""; }
    static String limit(String value,int max) { return value.length()>max?value.substring(0,max):value; }
}
