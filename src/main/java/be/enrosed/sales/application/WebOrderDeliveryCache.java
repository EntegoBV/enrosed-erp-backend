package be.enrosed.sales.application;

import jakarta.enterprise.context.RequestScoped;
import java.util.Map;

/** Every delivery row, loaded once for a request that prices many documents. */
@RequestScoped
public class WebOrderDeliveryCache {
    private Map<Long, WebOrderDeliveries.Delivery> rows;

    /** Null while nothing was preloaded. */
    public Map<Long, WebOrderDeliveries.Delivery> rows() { return rows; }
    public void fill(Map<Long, WebOrderDeliveries.Delivery> value) { rows = value; }
    public void clear() { rows = null; }
}
