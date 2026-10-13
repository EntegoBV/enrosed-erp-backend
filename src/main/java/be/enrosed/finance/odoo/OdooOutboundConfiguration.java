package be.enrosed.finance.odoo;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import java.util.*;

/** Immutable fields are read by collaborators, so inject the instance rather than a normal-scope client proxy. */
@Singleton
public class OdooOutboundConfiguration {
    final boolean enabled,sendEnabled;
    final Long journalId,incomeAccountId;
    final Map<String,Long> taxIds;
    final boolean validTaxMap;
    @Inject public OdooOutboundConfiguration(
            @ConfigProperty(name="enrosed.odoo.outbound-enabled",defaultValue="false") boolean enabled,
            @ConfigProperty(name="enrosed.odoo.send-enabled",defaultValue="false") boolean sendEnabled,
            @ConfigProperty(name="enrosed.odoo.sales-journal-id") Optional<Long> journalId,
            @ConfigProperty(name="enrosed.odoo.income-account-id") Optional<Long> incomeAccountId,
            @ConfigProperty(name="enrosed.odoo.vat-tax-ids") Optional<String> taxMap,ObjectMapper json) {
        this.enabled=enabled;this.sendEnabled=sendEnabled;this.journalId=journalId.orElse(null);this.incomeAccountId=incomeAccountId.orElse(null);
        Map<String,Long> values=new TreeMap<>();boolean valid=true;
        try {
            var node=json.readTree(taxMap.orElse("{}"));if(!node.isObject())throw new IllegalArgumentException();
            var fields=node.fields();while(fields.hasNext()) {var field=fields.next();
                be.enrosed.sales.domain.VatTreatment.valueOf(field.getKey());
                if(!field.getValue().isIntegralNumber()||field.getValue().longValue()<=0)throw new IllegalArgumentException();
                values.put(field.getKey(),field.getValue().longValue());
            }
        } catch(Exception invalid) {valid=false;}
        this.taxIds=Map.copyOf(values);this.validTaxMap=valid;
    }
    List<String> missing(String treatment) {
        List<String> missing=new ArrayList<>();
        if(journalId==null||journalId<=0)missing.add("ENROSED_ODOO_SALES_JOURNAL_ID");
        if(incomeAccountId==null||incomeAccountId<=0)missing.add("ENROSED_ODOO_INCOME_ACCOUNT_ID");
        if(!validTaxMap||treatment==null||!taxIds.containsKey(treatment))missing.add("ENROSED_ODOO_VAT_TAX_IDS");
        return missing;
    }
    void requireWrite(boolean sending) {
        if(!enabled||sending&&!sendEnabled)throw new OdooFailure("OUTBOUND_DISABLED");
    }
    void requireMapping(String treatment) {
        requireWrite(false);if(!missing(treatment).isEmpty())throw new OdooFailure("OUTBOUND_NOT_CONFIGURED");
    }
}
