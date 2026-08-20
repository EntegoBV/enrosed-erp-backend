package be.enrosed.sourcing.adapter.in.rest;

import be.enrosed.shared.BusinessRuleException;
import be.enrosed.shared.security.AdminIdentityProvider;
import be.enrosed.sourcing.adapter.out.persistence.SourcingEntities.FreightRateEntity;
import be.enrosed.sourcing.domain.FreightRate;
import jakarta.annotation.security.RolesAllowed;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;

import java.time.LocalDate;
import java.util.List;

/**
 * The freight-rate log behind the dashboard's market card.
 *
 * Thin on purpose: it is an append-and-list log of forwarder quotes, not a
 * workflow. Sorting happens here so the chart can just draw what it gets.
 */
@Path("/api/freight-rates")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed(AdminIdentityProvider.ADMIN_ROLE)
public class FreightRateResource {

    private final be.enrosed.sourcing.adapter.out.market.DrewryWciFetcher drewry;
    private final be.enrosed.sourcing.adapter.out.market.CcfiFetcher ccfi;
    private final be.enrosed.sourcing.adapter.out.market.NcfiFetcher ncfi;

    public FreightRateResource(be.enrosed.sourcing.adapter.out.market.DrewryWciFetcher drewry,
                               be.enrosed.sourcing.adapter.out.market.CcfiFetcher ccfi,
                               be.enrosed.sourcing.adapter.out.market.NcfiFetcher ncfi) {
        this.drewry = drewry;
        this.ccfi = ccfi;
        this.ncfi = ncfi;
    }

    @GET
    public List<FreightRate> list() {
        /* Lazily tops up the weekly indices; failures serve the cache. */
        drewry.refreshIfStale();
        ccfi.refreshIfStale();
        ncfi.refreshIfStale();
        return FreightRateEntity.<FreightRateEntity>list("order by quotedOn, id").stream()
                .map(entity -> new FreightRate(entity.id, entity.route,
                        entity.quotedOn, entity.usdPerContainer))
                .toList();
    }

    @POST
    @Transactional
    public FreightRate add(FreightRate rate) {
        if (rate.route() == null || rate.route().isBlank()
                || rate.usdPerContainer() == null) {
            throw new BusinessRuleException("Route en tarief zijn verplicht");
        }
        if (rate.usdPerContainer().signum() <= 0) {
            throw new BusinessRuleException("Vrachttarief moet groter zijn dan nul");
        }
        FreightRateEntity entity = new FreightRateEntity();
        entity.route = rate.route().trim().toUpperCase();
        entity.quotedOn = rate.quotedOn() == null ? LocalDate.now() : rate.quotedOn();
        entity.usdPerContainer = rate.usdPerContainer();
        entity.persist();
        return new FreightRate(entity.id, entity.route, entity.quotedOn, entity.usdPerContainer);
    }

    @DELETE
    @Path("/{id}")
    @Transactional
    public void remove(@PathParam("id") long id) {
        FreightRateEntity.deleteById(id);
    }
}
