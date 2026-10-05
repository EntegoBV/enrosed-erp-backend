package be.enrosed.account;

import java.time.Instant;
import java.util.List;

/** Wire shapes of a login request: the two website forms and the staff inbox. */
public final class LoginRequestDtos {
    private LoginRequestDtos() {}

    /** The standalone request form. */
    public record Submit(
            String language,
            String companyName,
            String companyCountryCode,
            String vatNumber,
            String contactName,
            String email,
            String phone,
            String message,
            Boolean privacyAccepted,
            /** Honeypot; real clients keep it empty. */
            String website,
            String formToken,
            String challengeToken
    ) {}

    /** The one-field form "request a new password link". */
    public record LinkSubmit(
            String email,
            String language,
            /** Honeypot; real clients keep it empty. */
            String website,
            String formToken,
            String challengeToken
    ) {}

    /** The same answer for a new request, a repeat, an address with a login and a dropped request. */
    public record Accepted(String reference, String status) {}

    /** The answer to the new-link form: it never tells whether the address has a login. */
    public record LinkAccepted(String status) {}

    /**
     * A later, differing submission kept beside the first one. An entry with source NEW_LINK
     * only marks that the holder of the existing login asked for a new link meanwhile and
     * carries nothing but at and source.
     */
    public record LaterSubmission(
            Instant at,
            String source,
            String companyName,
            String companyCountryCode,
            String vatNumber,
            String contactName,
            String phone,
            String message,
            String language,
            Long customerId,
            Long salesOrderId,
            String salesOrderNumber
    ) {}

    public record LoginRequestView(
            Long id,
            String reference,
            String status,
            String source,
            String language,
            String companyName,
            String companyCountryCode,
            String vatNumber,
            String contactName,
            String email,
            String phone,
            String message,
            Long customerId,
            String customerCompany,
            Long salesOrderId,
            String salesOrderNumber,
            Long accountId,
            int repeatCount,
            boolean hasExistingLogin,
            boolean previouslyRejected,
            List<LaterSubmission> laterSubmissions,
            boolean laterSubmissionsFull,
            Instant createdAt,
            Instant decidedAt,
            String decidedBy,
            String decisionNote
    ) {}

    public record MatchLogin(Long id, String email, String status) {}

    /** A customer the request may belong to; matchedOn holds LOGIN, QUOTE, EMAIL and VAT. */
    public record Match(
            Long customerId,
            String company,
            String contact,
            String email,
            String vatNumber,
            String countryCode,
            String city,
            List<String> matchedOn,
            List<MatchLogin> logins
    ) {}

    public record Detail(LoginRequestView request, List<Match> matches,
                         AccountDtos.AccountView existingAccount) {}

    public record NewCustomer(String company, String vatNumber, String countryCode,
                              String contact, String phone, String language) {}

    /** Exactly one of customerId and newCustomer; ignored for a new-link request. */
    public record ApproveRequest(Long customerId, NewCustomer newCustomer,
                                 Boolean confirmEmailMismatch) {}

    public record RejectRequest(String note) {}

    public record ApproveResponse(LoginRequestView request, AccountDtos.AccountView account,
                                  AccountDtos.Invitation invitation) {}

    public record Summary(long pending, boolean intakeFull, List<String> intakeFullSources) {}
}
