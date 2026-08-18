package be.enrosed.catalog.application;

import be.enrosed.catalog.application.port.out.HsCodeRepository;
import be.enrosed.catalog.application.port.out.ProductRepository;
import be.enrosed.catalog.domain.HsCode;
import be.enrosed.shared.NotFoundException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;

/**
 * Manages the customs tariffs.
 *
 * The import duty hangs on the tariff code, not on the product: a tariff
 * change means editing one row instead of dozens of products.
 */
@ApplicationScoped
public class HsCodeService {

    private final HsCodeRepository hsCodes;
    private final ProductRepository products;

    public HsCodeService(HsCodeRepository hsCodes, ProductRepository products) {
        this.hsCodes = hsCodes;
        this.products = products;
    }

    public List<HsCode> list() {
        return hsCodes.findAll().stream().sorted(Comparator.comparing(HsCode::code)).toList();
    }

    public HsCode get(String code) {
        return hsCodes.findByCode(code).orElseThrow(() -> new NotFoundException("HS-code", code));
    }

    /** Rate for a code; empty or unknown yields the given fallback percentage. */
    public BigDecimal dutyRateFor(String code, BigDecimal fallbackPct) {
        if (code == null || code.isBlank()) return fallbackPct;
        return hsCodes.findByCode(code).map(HsCode::dutyRatePct).orElse(fallbackPct);
    }

    @Transactional
    public HsCode save(HsCode hsCode) {
        return hsCodes.save(hsCode);
    }

    @Transactional
    public void delete(String code) {
        get(code);
        hsCodes.deleteByCode(code);
    }

    public long productsUsing(String code) {
        return products.countByHsCode(code);
    }
}
