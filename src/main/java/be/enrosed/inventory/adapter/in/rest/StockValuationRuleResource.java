package be.enrosed.inventory.adapter.in.rest;

import be.enrosed.inventory.adapter.in.rest.StockClosingDtos.ValuationRule;
import be.enrosed.inventory.adapter.out.persistence.StockValuationRuleEntity;
import be.enrosed.inventory.application.StockValuationRuleService;
import be.enrosed.shared.security.AdminIdentityProvider;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/** The valuation rule of the company; it exists from the first closing on. */
@Path("/api/stock-valuation-rule")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed(AdminIdentityProvider.ADMIN_ROLE)
public class StockValuationRuleResource {

    private final StockValuationRuleService rules;

    public StockValuationRuleResource(StockValuationRuleService rules) {
        this.rules = rules;
    }

    @GET
    public Response get() {
        StockValuationRuleEntity rule = rules.current();
        return rule == null ? Response.noContent().build() : Response.ok(ValuationRule.from(rule)).build();
    }
}
