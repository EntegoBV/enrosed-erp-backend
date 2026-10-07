package be.enrosed.sales.application;

import jakarta.enterprise.context.RequestScoped;

/**
 * What a staff request says about the website order it acts on: the revision
 * its screen was working from, and whether it is the explicit "In verwerking
 * nemen". The gate in {@link WebOrders} reads both after the locks.
 */
@RequestScoped
public class StaffWebOrderRevision {
    private Integer value;
    private boolean explicitTake;

    /** Null when the request carried no revision. */
    public Integer value() { return value; }
    public void set(Integer value) { this.value = value; }
    public boolean explicitTake() { return explicitTake; }
    public void markExplicitTake() { explicitTake = true; }
}
