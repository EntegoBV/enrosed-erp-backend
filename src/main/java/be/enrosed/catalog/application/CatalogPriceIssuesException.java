package be.enrosed.catalog.application;

import be.enrosed.shared.BusinessRuleException;

import java.util.List;

/**
 * The prices asked for a catalogue PDF cannot be printed as they are: the mapper answers 409
 * CATALOG_PRICES_INVALID with one issue per product, so the studio can list them next to the sheet.
 */
public class CatalogPriceIssuesException extends BusinessRuleException {

    private final List<CatalogExportService.PriceIssue> issues;

    public CatalogPriceIssuesException(String message, List<CatalogExportService.PriceIssue> issues) {
        super(message);
        this.issues = List.copyOf(issues);
    }

    public List<CatalogExportService.PriceIssue> issues() {
        return issues;
    }
}
