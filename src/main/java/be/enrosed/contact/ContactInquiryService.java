package be.enrosed.contact;

import be.enrosed.publicform.PublicFormValidationException;
import be.enrosed.shared.Language;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import java.time.Instant;
import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

@ApplicationScoped
public class ContactInquiryService {
    private static final Pattern EMAIL = Pattern.compile(
            "^[A-Z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Z0-9](?:[A-Z0-9-]{0,61}[A-Z0-9])?(?:\\.[A-Z0-9](?:[A-Z0-9-]{0,61}[A-Z0-9])?)+$",
            Pattern.CASE_INSENSITIVE);
    private static final String CURRENT_PRIVACY_VERSION = "2026-08-28";

    public void validate(ContactDtos.Request request) {
        java.util.LinkedHashMap<String, String> errors = new java.util.LinkedHashMap<>();
        if (request == null) {
            throw new PublicFormValidationException(Map.of("request", "REQUIRED"));
        }
        requiredSingleLine(request.contactName(), 120, "contactName", errors);
        requiredSingleLine(request.email(), 254, "email", errors);
        if (!blank(request.email()) && !EMAIL.matcher(request.email().strip()).matches()) {
            errors.put("email", "INVALID");
        }
        optionalSingleLine(request.companyName(), 160, "companyName", errors);
        optionalSingleLine(request.phone(), 50, "phone", errors);
        requiredMessage(request.message(), errors);
        validateSourcePage(request.sourcePage(), errors);
        language(request.language(), errors);
        topic(request.topic(), errors);
        if (!Boolean.TRUE.equals(request.privacyAccepted())) {
            errors.put("privacyAccepted", "REQUIRED");
        }
        if (!errors.isEmpty()) throw new PublicFormValidationException(errors);
    }

    @Transactional
    public ContactDtos.Response submit(ContactDtos.Request request) {
        validate(request);
        Instant now = Instant.now();
        ContactInquiryEntity inquiry = new ContactInquiryEntity();
        inquiry.reference = newReference();
        inquiry.status = ContactInquiryStatus.NEW;
        inquiry.language = Language.requireSupported(request.language(), Language.EN);
        inquiry.topic = ContactTopic.valueOf(request.topic().strip().toUpperCase(Locale.ROOT));
        inquiry.contactName = clean(request.contactName());
        inquiry.email = clean(request.email()).toLowerCase(Locale.ROOT);
        inquiry.companyName = clean(request.companyName());
        inquiry.phone = clean(request.phone());
        inquiry.message = cleanMultiline(request.message());
        inquiry.sourcePage = clean(request.sourcePage());
        inquiry.privacyAcceptedAt = now;
        /* The server records the policy it actually served, never an arbitrary client label. */
        inquiry.privacyPolicyVersion = CURRENT_PRIVACY_VERSION;
        inquiry.createdAt = now;
        inquiry.updatedAt = now;
        inquiry.persistAndFlush();

        ContactNotificationOutboxEntity outbox = new ContactNotificationOutboxEntity();
        outbox.inquiryId = inquiry.id;
        outbox.status = ContactOutboxStatus.PENDING;
        outbox.nextAttemptAt = now;
        outbox.persist();
        return new ContactDtos.Response(inquiry.reference, "RECEIVED");
    }

    public List<ContactDtos.View> list(String requestedStatus, int page, int size) {
        if (page < 0 || size < 1 || size > 100) {
            throw new PublicFormValidationException(Map.of("pagination", "OUT_OF_RANGE"));
        }
        io.quarkus.hibernate.orm.panache.PanacheQuery<ContactInquiryEntity> query;
        if (blank(requestedStatus)) {
            query = ContactInquiryEntity.find("order by createdAt desc");
        } else {
            ContactInquiryStatus status;
            try {
                status = ContactInquiryStatus.valueOf(
                        requestedStatus.strip().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException exception) {
                throw new PublicFormValidationException(Map.of("status", "UNSUPPORTED"));
            }
            query = ContactInquiryEntity.find("status = ?1 order by createdAt desc", status);
        }
        return query.page(page, size).list().stream()
                .map(ContactInquiryService::view).toList();
    }

    @Transactional
    public ContactDtos.View updateStatus(long id, ContactDtos.StatusRequest request) {
        ContactInquiryEntity inquiry = ContactInquiryEntity.findById(id);
        if (inquiry == null) throw new jakarta.ws.rs.NotFoundException();
        try {
            inquiry.status = ContactInquiryStatus.valueOf(
                    request.status().strip().toUpperCase(Locale.ROOT));
        } catch (RuntimeException exception) {
            throw new PublicFormValidationException(Map.of("status", "UNSUPPORTED"));
        }
        inquiry.updatedAt = Instant.now();
        return view(inquiry);
    }

    static ContactDtos.View view(ContactInquiryEntity row) {
        ContactNotificationOutboxEntity notification = ContactNotificationOutboxEntity
                .<ContactNotificationOutboxEntity>find("inquiryId", row.id).firstResult();
        return new ContactDtos.View(row.id, row.reference, row.status.name(), row.language.name(),
                row.topic.name(), row.contactName, row.email, row.companyName, row.phone,
                row.message, row.sourcePage, row.privacyAcceptedAt, row.privacyPolicyVersion,
                row.createdAt, row.updatedAt,
                notification == null ? "MISSING" : notification.status.name(),
                notification == null ? 0 : notification.attemptCount,
                notification == null ? null : notification.lastError,
                notification == null ? null : notification.sentAt);
    }

    private static void validateSourcePage(String value, Map<String, String> errors) {
        optionalSingleLine(value, 500, "sourcePage", errors);
        if (blank(value) || errors.containsKey("sourcePage")) return;
        try {
            URI uri = URI.create(value.strip());
            if (!value.strip().startsWith("/") || value.strip().startsWith("//")
                    || uri.isAbsolute() || uri.getRawAuthority() != null
                    || uri.getRawPath() == null || uri.getRawPath().isBlank()) {
                errors.put("sourcePage", "INVALID");
            }
        } catch (IllegalArgumentException exception) {
            errors.put("sourcePage", "INVALID");
        }
    }

    private static Language language(String value, Map<String, String> errors) {
        try {
            return Language.requireSupported(value, Language.EN);
        } catch (IllegalArgumentException exception) {
            errors.put("language", "UNSUPPORTED");
            return Language.EN;
        }
    }

    private static ContactTopic topic(String value, Map<String, String> errors) {
        if (blank(value)) {
            errors.put("topic", "REQUIRED");
            return null;
        }
        try {
            return ContactTopic.valueOf(value.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            errors.put("topic", "UNSUPPORTED");
            return null;
        }
    }

    private static void requiredSingleLine(String value, int max, String field,
                                           Map<String, String> errors) {
        if (blank(value)) errors.put(field, "REQUIRED");
        else optionalSingleLine(value, max, field, errors);
    }

    private static void optionalSingleLine(String value, int max, String field,
                                           Map<String, String> errors) {
        if (value == null) return;
        String stripped = value.strip();
        if (stripped.length() > max) errors.put(field, "TOO_LONG");
        if (stripped.matches("(?s).*[\u0000-\u001f\u007f].*")) errors.put(field, "INVALID");
    }

    private static void requiredMessage(String value, Map<String, String> errors) {
        if (blank(value)) {
            errors.put("message", "REQUIRED");
            return;
        }
        int length = value.strip().length();
        if (length < 10) errors.put("message", "TOO_SHORT");
        else if (length > 2_000) errors.put("message", "TOO_LONG");
        if (value.indexOf('\0') >= 0) errors.put("message", "INVALID");
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static String clean(String value) {
        if (blank(value)) return null;
        return value.strip().replaceAll("[\\p{Cc}]", "");
    }

    private static String cleanMultiline(String value) {
        if (blank(value)) return null;
        return value.strip().replace("\r\n", "\n").replace('\r', '\n')
                .replaceAll("[\\p{Cc}&&[^\\n\\t]]", "");
    }

    private static String newReference() {
        return "CNT-" + UUID.randomUUID().toString().replace("-", "")
                .substring(0, 20).toUpperCase(Locale.ROOT);
    }
}
