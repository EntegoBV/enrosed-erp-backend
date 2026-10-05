package be.enrosed.shared.mail;

import be.enrosed.shared.Language;

/** The invitation mail of a website login: the only mail a customer gets about it. */
public interface CustomerAccountMailer {
    /**
     * FIRST: a login was given and this is the first mail about it that can reach the customer
     * (first time, again after a withdrawal, or a retry after a first mail that never left).
     * NEW_LINK: a fresh link for a login whose owner was sent a link before and never chose a
     * password. NEW_LINK_KEEPS_PASSWORD: a fresh link for an active login; the current password
     * keeps working.
     */
    enum Kind { FIRST, NEW_LINK, NEW_LINK_KEEPS_PASSWORD }

    /** validDays comes from enrosed.customer-account.invite-ttl-days; the mailer never has its own number. */
    record Invitation(String to, Language language, String contactName, String company, String token,
                      int validDays, Kind kind) {}

    /** Throws BusinessRuleException when the mail could not leave. */
    void sendInvitation(Invitation invitation);
}
