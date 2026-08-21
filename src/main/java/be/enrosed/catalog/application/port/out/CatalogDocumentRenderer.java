package be.enrosed.catalog.application.port.out;

import be.enrosed.catalog.application.CatalogExportService;
/** Outbound port turning a product selection into a catalogue PDF. */
public interface CatalogDocumentRenderer {

    record Document(String filename, byte[] content, String contentType) {}

    Document render(CatalogExportService.Model catalog);
}
