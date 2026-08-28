package be.enrosed.contact;

import java.time.Instant;

public final class ContactDtos {
    private ContactDtos() {}

    public record Request(
            String language,
            String contactName,
            String email,
            String companyName,
            String phone,
            String topic,
            String message,
            Boolean privacyAccepted,
            String privacyPolicyVersion,
            String sourcePage,
            /** Honeypot; real clients keep it empty. */
            String website,
            String formToken,
            String challengeToken
    ) {}

    public record Response(String reference, String status) {}

    public record StatusRequest(String status) {}

    public record View(
            Long id, String reference, String status, String language, String topic,
            String contactName, String email, String companyName, String phone,
            String message, String sourcePage, Instant privacyAcceptedAt,
            String privacyPolicyVersion, Instant createdAt, Instant updatedAt,
            String notificationStatus, int notificationAttemptCount,
            String notificationLastError, Instant notificationSentAt
    ) {}
}
