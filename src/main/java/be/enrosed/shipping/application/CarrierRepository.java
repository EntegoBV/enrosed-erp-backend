package be.enrosed.shipping.application;

import be.enrosed.shipping.domain.Carrier;

import java.util.List;
import java.util.Optional;

public interface CarrierRepository {
    List<Carrier> findAll();
    Optional<Carrier> findById(long id);
    Optional<Carrier> findByName(String name);
    Carrier save(Carrier carrier);
    void delete(long id);
}
