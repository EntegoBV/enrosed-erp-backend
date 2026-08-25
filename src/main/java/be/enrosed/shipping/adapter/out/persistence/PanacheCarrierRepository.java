package be.enrosed.shipping.adapter.out.persistence;

import be.enrosed.shipping.adapter.out.persistence.ShippingEntities.CarrierEntity;
import be.enrosed.shipping.adapter.out.persistence.ShippingEntities.CarrierLaneEntity;
import be.enrosed.shipping.adapter.out.persistence.ShippingEntities.CarrierTierEntity;
import be.enrosed.shipping.adapter.out.persistence.ShippingEntities.CarrierZoneEntity;
import be.enrosed.shipping.application.CarrierRepository;
import be.enrosed.shipping.domain.Carrier;
import be.enrosed.shipping.domain.CarrierLane;
import be.enrosed.shipping.domain.CarrierTier;
import be.enrosed.shipping.domain.CarrierZone;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@ApplicationScoped
public class PanacheCarrierRepository implements CarrierRepository {

    @Override
    public List<Carrier> findAll() {
        return CarrierEntity.<CarrierEntity>listAll().stream()
                .map(this::toDomain)
                .sorted(Comparator.comparing(Carrier::name, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    @Override
    public Optional<Carrier> findById(long id) {
        return Optional.ofNullable(CarrierEntity.<CarrierEntity>findById(id)).map(this::toDomain);
    }

    @Override
    public Optional<Carrier> findByName(String name) {
        return CarrierEntity.<CarrierEntity>find("lower(name)", name.toLowerCase())
                .firstResultOptional().map(this::toDomain);
    }

    @Override
    @Transactional
    public Carrier save(Carrier carrier) {
        CarrierEntity entity = carrier.id() == null
                ? new CarrierEntity() : CarrierEntity.findById(carrier.id());
        if (entity == null) entity = new CarrierEntity();
        entity.name = carrier.name();
        entity.fullName = carrier.fullName();
        entity.active = carrier.active();
        entity.dieselSurchargePct = carrier.dieselSurchargePct();
        entity.validUntil = carrier.validUntil();
        entity.notes = carrier.notes();
        entity.persist();

        /* The tariff is replaced as one document: simpler and safe, because
           lanes carry no foreign identity of their own. */
        List<CarrierLaneEntity> oldLanes =
                CarrierLaneEntity.<CarrierLaneEntity>list("carrierId", entity.id);
        for (CarrierLaneEntity lane : oldLanes) {
            CarrierZoneEntity.delete("laneId", lane.id);
            CarrierTierEntity.delete("laneId", lane.id);
        }
        CarrierLaneEntity.delete("carrierId", entity.id);

        for (CarrierLane lane : carrier.lanes()) {
            CarrierLaneEntity laneEntity = new CarrierLaneEntity();
            laneEntity.carrierId = entity.id;
            laneEntity.countryCode = lane.countryCode() == null
                    ? null : lane.countryCode().toUpperCase();
            laneEntity.surchargePct = lane.surchargePct();
            laneEntity.surchargeFixedEur = lane.surchargeFixedEur();
            laneEntity.surchargeNote = lane.surchargeNote();
            laneEntity.persist();
            int zonePosition = 0;
            for (CarrierZone zone : lane.zones()) {
                CarrierZoneEntity zoneEntity = new CarrierZoneEntity();
                zoneEntity.laneId = laneEntity.id;
                zoneEntity.name = zone.name();
                zoneEntity.postcodes = zone.postcodes();
                zoneEntity.position = zonePosition++;
                zoneEntity.persist();
            }
            int tierPosition = 0;
            for (CarrierTier tier : lane.tiers()) {
                CarrierTierEntity tierEntity = new CarrierTierEntity();
                tierEntity.laneId = laneEntity.id;
                tierEntity.epMax = tier.epMax();
                tierEntity.bpMax = tier.bpMax();
                tierEntity.ldmMax = tier.ldmMax();
                tierEntity.kgMax = tier.kgMax();
                tierEntity.position = tierPosition++;
                tierEntity.prices = tier.prices().stream()
                        .map(price -> price == null ? "" : price.toPlainString())
                        .collect(Collectors.joining(","));
                tierEntity.persist();
            }
        }
        return findById(entity.id).orElseThrow();
    }

    @Override
    @Transactional
    public void delete(long id) {
        for (CarrierLaneEntity lane : CarrierLaneEntity.<CarrierLaneEntity>list("carrierId", id)) {
            CarrierZoneEntity.delete("laneId", lane.id);
            CarrierTierEntity.delete("laneId", lane.id);
        }
        CarrierLaneEntity.delete("carrierId", id);
        CarrierEntity.deleteById(id);
    }

    private Carrier toDomain(CarrierEntity entity) {
        List<CarrierLane> lanes = new ArrayList<>();
        for (CarrierLaneEntity lane : CarrierLaneEntity
                .<CarrierLaneEntity>list("carrierId = ?1 order by countryCode", entity.id)) {
            List<CarrierZone> zones = CarrierZoneEntity
                    .<CarrierZoneEntity>list("laneId = ?1 order by position", lane.id).stream()
                    .map(zone -> new CarrierZone(zone.id, zone.name, zone.postcodes, zone.position))
                    .toList();
            List<CarrierTier> tiers = CarrierTierEntity
                    .<CarrierTierEntity>list("laneId = ?1 order by position", lane.id).stream()
                    .map(tier -> new CarrierTier(tier.id, tier.epMax, tier.bpMax, tier.ldmMax,
                            tier.kgMax, tier.position, parsePrices(tier.prices)))
                    .toList();
            lanes.add(new CarrierLane(lane.id, lane.countryCode, lane.surchargePct,
                    lane.surchargeFixedEur, lane.surchargeNote, zones, tiers));
        }
        return new Carrier(entity.id, entity.name, entity.fullName, entity.active,
                entity.dieselSurchargePct, entity.validUntil, entity.notes, lanes);
    }

    private static List<BigDecimal> parsePrices(String csv) {
        if (csv == null || csv.isBlank()) return List.of();
        List<BigDecimal> prices = new ArrayList<>();
        for (String part : csv.split(",", -1)) {
            prices.add(part.isBlank() ? null : new BigDecimal(part));
        }
        return prices;
    }
}
