package be.enrosed.catalog.application;

import be.enrosed.catalog.adapter.out.persistence.BarcodePoolEntity;
import be.enrosed.catalog.adapter.out.persistence.StockDaos;
import be.enrosed.catalog.application.port.out.ProductRepository;
import be.enrosed.shared.BusinessRuleException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The company's own EAN range, as a list of codes still free to hand out.
 *
 * Codes go in by the batch (pasted from the GS1 sheet); a product takes the
 * next one with one tap and the code leaves the list - one code, one
 * article, no second thoughts.
 */
@ApplicationScoped
public class BarcodePoolService {

    private final StockDaos.BarcodePool pool;
    private final BarcodeValidator validator;
    private final ProductRepository products;

    public BarcodePoolService(StockDaos.BarcodePool pool, BarcodeValidator validator, ProductRepository products) {
        this.pool = pool;
        this.validator = validator;
        this.products = products;
    }

    public record Intake(List<String> added, List<String> invalid, List<String> inUse, List<String> duplicate) {}

    public List<String> free() {
        return pool.list("order by code").stream().map(entity -> entity.code).toList();
    }

    public long count() {
        return pool.count();
    }

    /** Splits pasted text on anything that is not a digit and keeps what is a real, free EAN. */
    @Transactional
    public Intake add(String pasted) {
        Set<String> wanted = new LinkedHashSet<>();
        for (String token : (pasted == null ? "" : pasted).split("[^0-9]+")) {
            if (!token.isBlank()) wanted.add(token.trim());
        }
        List<String> added = new ArrayList<>();
        List<String> invalid = new ArrayList<>();
        List<String> inUse = new ArrayList<>();
        List<String> duplicate = new ArrayList<>();
        List<be.enrosed.catalog.domain.Product> catalogue = products.findAll();
        for (String code : wanted) {
            if (!validator.validate(code).valid()) { invalid.add(code); continue; }
            if (BarcodeOwner.find(code, catalogue, null) != null) { inUse.add(code); continue; }
            if (pool.count("code", code) > 0) { duplicate.add(code); continue; }
            BarcodePoolEntity entity = new BarcodePoolEntity();
            entity.code = code;
            entity.addedAt = Instant.now();
            pool.persist(entity);
            added.add(code);
        }
        return new Intake(added, invalid, inUse, duplicate);
    }

    @Transactional
    public void remove(String code) {
        pool.delete("code", code);
    }

    /**
     * Shows the lowest free code without striking it: a form that is
     * abandoned, or a button tapped three times, must not eat the list.
     * The code leaves the list when a product is saved carrying it.
     */
    public String next() {
        BarcodePoolEntity next = pool.find("order by code").firstResult();
        if (next == null) {
            throw new BusinessRuleException("Geen vrije EAN-codes meer in de lijst; voeg er eerst toe onder Instellingen");
        }
        return next.code;
    }

    /** A saved product carries these codes now; whichever of them sat in the list leaves it. */
    @Transactional
    public void consume(String... codes) {
        for (String code : codes) {
            if (code != null && !code.isBlank()) pool.delete("code", code.trim());
        }
    }
}
