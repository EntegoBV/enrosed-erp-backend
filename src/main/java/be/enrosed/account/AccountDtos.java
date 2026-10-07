package be.enrosed.account;

import java.time.Instant;

/** Wire shapes of the customer login: the public account endpoints and the staff login block. */
public final class AccountDtos {
    private AccountDtos() {}

    /** Explicit allow-list: nothing else from the customer record leaves the ERP. */
    public record Profile(
            String email,
            String contactName,
            String company,
            String companyCountryCode,
            String vatNumber,
            String phone,
            String language
    ) {}

    public record SessionResponse(String sessionToken, Instant expiresAt, Profile profile) {}

    /** The running session as the website restores it; the token itself is never repeated. */
    public record SessionInfo(Instant expiresAt, Profile profile) {}

    public record LoginSubmit(String email, String password, String formToken,
                              String challengeToken) {}

    public record TokenSubmit(String token) {}

    /** What the holder of a live invitation link may know before choosing a password. */
    public record TokenInfo(String email, String company, Instant expiresAt) {}

    public record ActivationSubmit(String token, String password) {}

    /** Staff view of a login. Carries no password hash, token or token hash. */
    public record AccountView(
            Long id,
            Long customerId,
            String customerCompany,
            String email,
            String contactName,
            String language,
            String status,
            Instant passwordSetAt,
            Instant lastLoginAt,
            Instant lastLinkSentAt,
            String lastLinkError,
            Instant linkExpiresAt,
            Instant createdAt,
            String createdBy,
            Instant disabledAt,
            String disabledBy,
            long activeSessions
    ) {}

    /** Outcome of the invitation mail; expiresAt is the real end of the link. */
    public record Invitation(boolean sent, Instant expiresAt, String error) {}

    /** A null e-mail means the customer's own address. */
    public record GrantRequest(Long customerId, String email) {}

    public record GrantResponse(AccountView account, Invitation invitation) {}
}
