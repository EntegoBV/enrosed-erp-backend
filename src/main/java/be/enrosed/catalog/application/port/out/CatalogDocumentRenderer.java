package be.enrosed.catalog.application.port.out;

import be.enrosed.catalog.application.CatalogExportService;
import be.enrosed.catalog.domain.Category;
import be.enrosed.catalog.domain.Product;

import java.util.List;
import java.util.Map;

/** Uitgaande poort die van een productselectie een catalogus-PDF maakt. */
public interface CatalogDocumentRenderer {

    record Document(String filename, byte[] content, String contentType) {}

    Document render(List<Product> products, Map<Long, Category> categoriesById,
                    CatalogExportService.Request request);
}
