package be.enrosed.sales.application;

import be.enrosed.shared.BusinessRuleException;

/**
 * A staff action built on a version of a website order the customer has
 * since changed or cancelled. Nothing is written; the screen must load the
 * latest version first. Both sentences are shown to staff as they are.
 */
public class WebOrderChangedException extends BusinessRuleException {
    private final int currentRevision;
    private final boolean missingParameter;

    public WebOrderChangedException(int currentRevision, boolean missingParameter) {
        super(missingParameter
                ? "De klant heeft deze websitebestelling intussen gewijzigd (versie " + currentRevision
                        + "). Dit scherm is verouderd; laad de laatste versie van de bestelling voor je verdergaat."
                : "De klant heeft deze bestelling intussen gewijzigd of geannuleerd. Je wijzigingen zijn niet opgeslagen; laad de laatste versie.");
        this.currentRevision = currentRevision;
        this.missingParameter = missingParameter;
    }

    public int currentRevision() { return currentRevision; }

    /** True when the request named no revision at all: a screen from before website orders. */
    public boolean missingParameter() { return missingParameter; }
}
