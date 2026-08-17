package be.enrosed.catalog.application.port.out;

import be.enrosed.catalog.domain.Product;

import java.util.List;
import java.util.Optional;

/** Uitgaande poort naar de opslag van producten. */
public interface ProductRepository {
    List<Product> findAll();
    List<Product> findBySupplier(long supplierId);
    Optional<Product> findById(long id);
    Optional<Product> findBySku(String sku);
    Product save(Product product);
    void deleteById(long id);
    long countByCategory(long categoryId);
    long countByHsCode(String hsCode);
    long countBySupplier(long supplierId);
}
