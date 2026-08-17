package be.enrosed.catalog.adapter.out.persistence;

import be.enrosed.catalog.application.port.out.HsCodeRepository;
import be.enrosed.catalog.domain.HsCode;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.Optional;

@ApplicationScoped
public class PanacheHsCodeRepository implements HsCodeRepository {

    private final CatalogDaos.HsCodes dao;

    public PanacheHsCodeRepository(CatalogDaos.HsCodes dao) {
        this.dao = dao;
    }

    @Override
    public List<HsCode> findAll() {
        return dao.listAll().stream().map(CatalogMapper::toDomain).toList();
    }

    @Override
    public Optional<HsCode> findByCode(String code) {
        return dao.find("code", code).firstResultOptional().map(CatalogMapper::toDomain);
    }

    @Override
    public HsCode save(HsCode hsCode) {
        HsCodeEntity entity = dao.find("code", hsCode.code()).firstResult();
        if (entity == null) entity = new HsCodeEntity();
        CatalogMapper.apply(hsCode, entity);
        if (entity.id == null) dao.persist(entity);
        dao.flush();
        return CatalogMapper.toDomain(entity);
    }

    @Override
    public void deleteByCode(String code) {
        dao.delete("code", code);
    }
}
