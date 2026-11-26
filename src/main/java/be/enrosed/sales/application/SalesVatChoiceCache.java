package be.enrosed.sales.application;

import jakarta.enterprise.context.RequestScoped;
import java.util.Map;

/** Every stored VAT row, read once for a request that prices many documents (one small row per document at most). */
@RequestScoped
public class SalesVatChoiceCache {
    private Map<Long, SalesVatChoices.Row> all;

    /** Null while nothing was read, or after a change in this request. */
    public Map<Long, SalesVatChoices.Row> all() { return all; }
    public void fill(Map<Long, SalesVatChoices.Row> all) { this.all = all; }
}
