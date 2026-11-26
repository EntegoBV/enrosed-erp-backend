package be.enrosed.sales.application;

import be.enrosed.sales.domain.SalesVatChoice;
import jakarta.enterprise.context.RequestScoped;
import java.util.Map;

/** Every stored VAT choice, read once for a request that prices many documents (the table holds few rows). */
@RequestScoped
public class SalesVatChoiceCache {
    private Map<Long, SalesVatChoice> all;

    /** Null while nothing was read, or after a change in this request. */
    public Map<Long, SalesVatChoice> all() { return all; }
    public void fill(Map<Long, SalesVatChoice> all) { this.all = all; }
}
