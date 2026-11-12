package be.enrosed.sales.application;

import be.enrosed.sales.domain.Customer;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonUnwrapped;

/**
 * A customer as /api/customers answers it: the record's own fields, plus (additive, only when a partner
 * added it in the partner portal) partnerOrigin {partnerName, createdByName, createdAt, reviewedAt}.
 */
public record CustomerView(@JsonUnwrapped Customer customer,
                           @JsonInclude(JsonInclude.Include.NON_NULL) CustomerPartnerOrigins.Origin partnerOrigin) {}
