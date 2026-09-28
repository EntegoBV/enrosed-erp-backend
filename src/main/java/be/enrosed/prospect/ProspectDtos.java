package be.enrosed.prospect;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** Private CRM API records; no prospect data is exposed by the public catalogue. */
public final class ProspectDtos {
    private ProspectDtos() {}

    public enum ProspectStatus { NEW, QUALIFIED, CONTACTED, INTERESTED, NOT_INTERESTED, CUSTOMER, DO_NOT_CONTACT }
    public enum Channel { EMAIL, INSTAGRAM, PHONE, NOTE }
    public enum ActivityStatus { DRAFT, RESERVED, SCHEDULED, SENT, FAILED, RECEIVED, COMPLETED, CANCELLED }

    public record ProspectInput(String businessName, String countryCode, String language, String email,
                                String website, String instagramHandle, String groupKey, String sourceUrl,
                                String sourceType, ProspectStatus status, String notes) {}

    public record Prospect(Long id, String businessName, String countryCode, String language, String email,
                           String website, String instagramHandle, String groupKey, String sourceUrl,
                           String sourceType, ProspectStatus status, String notes, Instant createdAt,
                           Instant updatedAt, Instant lastActivityAt, Instant lastContactAt) {}

    public record Page(List<Prospect> items, long total, int page, int size) {}
    public record Detail(Prospect prospect, List<Activity> activities) {}

    public record ActivityInput(Channel channel, String type, ActivityStatus status, String subject,
                                String body, Instant occurredAt, String externalId, String attachmentName,
                                String sourceUrl) {}

    public record Activity(Long id, Long prospectId, Channel channel, String type, ActivityStatus status,
                           String subject, String body, Instant occurredAt, String externalId,
                           String attachmentName, String sourceUrl, Instant createdAt, String createdBy,
                           String recipientEmail, String groupKey) {}

    public record EmailReservationInput(String externalId, String subject, String body,
                                        String attachmentName, String sourceUrl, Instant scheduledFor) {}

    public record EmailSummary(LocalDate date, String timezone, int limit, long sent, long reserved, long scheduled,
                               long remaining, List<Activity> activities) {}
}
