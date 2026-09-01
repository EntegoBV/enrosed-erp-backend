package be.enrosed.sales.adapter.in.rest;

import be.enrosed.sales.application.CountryService;
import be.enrosed.sales.application.DiscountTierService;
import be.enrosed.sales.domain.Country;
import be.enrosed.sales.domain.DiscountTier;
import be.enrosed.sales.domain.TierScope;
import be.enrosed.shared.security.AdminIdentityProvider;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;

@Path("/api")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed(AdminIdentityProvider.ADMIN_ROLE)
public class CountryResource {

    private final CountryService countries;
    private final DiscountTierService tiers;

    public CountryResource(CountryService countries, DiscountTierService tiers) {
        this.countries = countries;
        this.tiers = tiers;
    }

    @GET
    @Path("/countries")
    public List<Country> listCountries() {
        return countries.list();
    }

    @PUT
    @Path("/countries")
    public Country saveCountry(Country country) {
        return countries.save(country);
    }

    @DELETE
    @Path("/countries/{code}")
    public Response deleteCountry(@PathParam("code") String code) {
        countries.delete(code);
        return Response.noContent().build();
    }

    @GET
    @Path("/discount-tiers/{scope}")
    public List<DiscountTier> listTiers(@PathParam("scope") TierScope scope) {
        return tiers.list(scope);
    }

    @PUT
    @Path("/discount-tiers/{scope}")
    public List<DiscountTier> replaceTiers(@PathParam("scope") TierScope scope, List<DiscountTier> replacement) {
        return tiers.replace(scope, replacement);
    }

    @GET
    @Path("/discount-tiers/LINE/products/{productId}")
    public List<DiscountTier> listProductLineTiers(@PathParam("productId") long productId) {
        return tiers.listForProduct(productId);
    }

    @PUT
    @Path("/discount-tiers/LINE/products/{productId}")
    public List<DiscountTier> replaceProductLineTiers(
            @PathParam("productId") long productId, List<DiscountTier> replacement) {
        return tiers.replaceForProduct(productId, replacement);
    }
}
