package be.enrosed.catalog.application.port.out;

import be.enrosed.catalog.domain.HsCode;

import java.util.List;
import java.util.Optional;

public interface HsCodeRepository {
    List<HsCode> findAll();
    Optional<HsCode> findByCode(String code);
    HsCode save(HsCode hsCode);
    void deleteByCode(String code);
}
