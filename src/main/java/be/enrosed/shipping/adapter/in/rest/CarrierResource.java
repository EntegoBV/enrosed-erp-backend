package be.enrosed.shipping.adapter.in.rest;

import be.enrosed.shared.BusinessRuleException;
import be.enrosed.shipping.application.CarrierRepository;
import be.enrosed.shipping.domain.Carrier;
import be.enrosed.shipping.domain.CarrierLane;
import be.enrosed.shipping.domain.CarrierPricing;
import be.enrosed.shipping.domain.CarrierQuote;
import be.enrosed.shipping.domain.CarrierTier;
import be.enrosed.shipping.domain.CarrierZone;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** Shipping organisations and their tariffs; quotes one shipment on demand. */
@Path("/api/carriers")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed(be.enrosed.shared.security.AdminIdentityProvider.ADMIN_ROLE)
public class CarrierResource {

    private final CarrierRepository carriers;

    public CarrierResource(CarrierRepository carriers) {
        this.carriers = carriers;
    }

    public record ZoneDto(Long id, String name, String postcodes) {}
    public record TierDto(Long id, BigDecimal epMax, BigDecimal bpMax, BigDecimal ldmMax,
                          BigDecimal kgMax, List<BigDecimal> prices) {}
    public record LaneDto(Long id, String countryCode, BigDecimal surchargePct,
                          BigDecimal surchargeFixedEur, String surchargeNote,
                          List<ZoneDto> zones, List<TierDto> tiers) {}
    public record CarrierDto(Long id, String name, String fullName, Boolean active,
                             BigDecimal dieselSurchargePct, LocalDate validUntil, String notes,
                             List<LaneDto> lanes) {}

    @GET
    public List<CarrierDto> list() {
        return carriers.findAll().stream().map(CarrierResource::toDto).toList();
    }

    @POST
    public CarrierDto create(CarrierDto dto) {
        if (dto == null || dto.name() == null || dto.name().isBlank()) {
            throw new BusinessRuleException("Geef de verzendorganisatie een naam");
        }
        return toDto(carriers.save(fromDto(null, dto)));
    }

    @PUT
    @Path("/{id}")
    public CarrierDto update(@PathParam("id") long id, CarrierDto dto) {
        carriers.findById(id).orElseThrow(
                () -> new be.enrosed.shared.NotFoundException("Verzendorganisatie", id));
        return toDto(carriers.save(fromDto(id, dto)));
    }

    @DELETE
    @Path("/{id}")
    public void delete(@PathParam("id") long id) {
        carriers.delete(id);
    }

    public record QuoteDto(BigDecimal baseEur, BigDecimal dieselPct, BigDecimal dieselEur,
                           BigDecimal surchargePct, BigDecimal surchargePctEur,
                           BigDecimal surchargeFixedEur, BigDecimal totalEur,
                           String zoneName, String tierLabel, boolean postcodeMatched,
                           String surchargeNote) {}

    /** Prices one hypothetical shipment; the order editor shows the breakdown. */
    @GET
    @Path("/{id}/quote")
    public QuoteDto quote(@PathParam("id") long id,
                          @QueryParam("country") String country,
                          @QueryParam("postcode") String postcode,
                          @QueryParam("pallets") int pallets,
                          @QueryParam("palletType") String palletType,
                          @QueryParam("weightKg") BigDecimal weightKg) {
        Carrier carrier = carriers.findById(id).orElseThrow(
                () -> new be.enrosed.shared.NotFoundException("Verzendorganisatie", id));
        CarrierPricing.PalletKind kind = "BLOCK".equalsIgnoreCase(palletType)
                || "BLOCKPALLET".equalsIgnoreCase(palletType)
                ? CarrierPricing.PalletKind.BLOCKPALLET
                : CarrierPricing.PalletKind.EUROPALLET;
        CarrierQuote quote = CarrierPricing.quote(carrier, country, postcode, pallets, kind, weightKg);
        if (quote == null) return null;
        return new QuoteDto(quote.baseEur(), quote.dieselPct(), quote.dieselEur(),
                quote.surchargePct(), quote.surchargePctEur(), quote.surchargeFixedEur(),
                quote.totalEur(), quote.zoneName(), quote.tierLabel(), quote.postcodeMatched(),
                quote.surchargeNote());
    }

    private static CarrierDto toDto(Carrier carrier) {
        return new CarrierDto(carrier.id(), carrier.name(), carrier.fullName(), carrier.active(),
                carrier.dieselSurchargePct(), carrier.validUntil(), carrier.notes(),
                carrier.lanes().stream().map(lane -> new LaneDto(lane.id(), lane.countryCode(),
                        lane.surchargePct(), lane.surchargeFixedEur(), lane.surchargeNote(),
                        lane.zones().stream()
                                .map(zone -> new ZoneDto(zone.id(), zone.name(), zone.postcodes()))
                                .toList(),
                        lane.tiers().stream()
                                .map(tier -> new TierDto(tier.id(), tier.epMax(), tier.bpMax(),
                                        tier.ldmMax(), tier.kgMax(), tier.prices()))
                                .toList())).toList());
    }

    private static Carrier fromDto(Long id, CarrierDto dto) {
        List<CarrierLane> lanes = dto.lanes() == null ? List.of() : dto.lanes().stream()
                .map(lane -> new CarrierLane(null, lane.countryCode(), lane.surchargePct(),
                        lane.surchargeFixedEur(), lane.surchargeNote(),
                        indexZones(lane.zones()), indexTiers(lane.tiers())))
                .toList();
        return new Carrier(id, dto.name().trim(), dto.fullName(),
                dto.active() == null || dto.active(), dto.dieselSurchargePct(),
                dto.validUntil(), dto.notes(), lanes);
    }

    private static List<CarrierZone> indexZones(List<ZoneDto> zones) {
        if (zones == null) return List.of();
        return java.util.stream.IntStream.range(0, zones.size())
                .mapToObj(i -> new CarrierZone(null, zones.get(i).name(),
                        zones.get(i).postcodes(), i))
                .toList();
    }

    private static List<CarrierTier> indexTiers(List<TierDto> tiers) {
        if (tiers == null) return List.of();
        return java.util.stream.IntStream.range(0, tiers.size())
                .mapToObj(i -> new CarrierTier(null, tiers.get(i).epMax(), tiers.get(i).bpMax(),
                        tiers.get(i).ldmMax(), tiers.get(i).kgMax(), i, tiers.get(i).prices()))
                .toList();
    }
}
