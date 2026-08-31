package be.enrosed.catalog.application.port.out;

import be.enrosed.catalog.application.CatalogExportService;

import java.util.List;

/** Outbound port turning a product selection into a catalogue PDF. */
public interface CatalogDocumentRenderer {

    record Document(String filename, byte[] content, String contentType) {}

    Document render(CatalogExportService.Model catalog);

    /** Exact requested-language fields required by the selected catalogue layout. */
    List<String> missingTranslations(CatalogExportService.Model catalog);
}
