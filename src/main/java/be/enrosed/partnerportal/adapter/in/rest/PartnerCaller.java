package be.enrosed.partnerportal.adapter.in.rest;

import be.enrosed.shared.security.PartnerIdentity;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.ws.rs.ForbiddenException;

/** The signed-in partner, read only from the server identity (never from the request). */
record PartnerCaller(long customerId, long loginId, String auth, Long sessionId) {

    static PartnerCaller of(SecurityIdentity identity) {
        if (identity == null || identity.isAnonymous() || !identity.hasRole(PartnerIdentity.ROLE)) {
            throw new ForbiddenException();
        }
        Long customerId = identity.getAttribute(PartnerIdentity.CUSTOMER_ID);
        Long loginId = identity.getAttribute(PartnerIdentity.LOGIN_ID);
        if (customerId == null || loginId == null) throw new ForbiddenException();
        return new PartnerCaller(customerId, loginId, identity.getAttribute(PartnerIdentity.AUTH),
                identity.getAttribute(PartnerIdentity.SESSION_ID));
    }
}
