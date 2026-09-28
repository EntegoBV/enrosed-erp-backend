package be.enrosed.prospect;

import be.enrosed.shared.BusinessRuleException;
import be.enrosed.shared.NotFoundException;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.BadRequestException;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.*;
import static be.enrosed.prospect.ProspectDtos.*;

@ApplicationScoped
public class ProspectService {
    public static final ZoneId OUTREACH_ZONE = ZoneId.of("Europe/Brussels");
    public static final int DAILY_EMAIL_LIMIT = 10;
    private static final Set<String> COUNTRIES = Set.of(Locale.getISOCountries());
    private static final Set<ProspectStatus> NO_NEW_OUTREACH = EnumSet.of(
            ProspectStatus.DO_NOT_CONTACT, ProspectStatus.NOT_INTERESTED, ProspectStatus.CUSTOMER);

    @Inject EntityManager em;
    @Inject SecurityIdentity identity;
    @ConfigProperty(name = "quarkus.datasource.db-kind") String databaseKind;

    /** The production migration seeds this too. Both supported databases use a race-safe upsert. */
    @Transactional
    void initializeLock(@Observes StartupEvent event) {
        String sql = "postgresql".equals(databaseKind)
                ? "insert into prospect_outreach_lock (id) values (1) on conflict (id) do nothing"
                : "merge into prospect_outreach_lock (id) key (id) values (1)";
        em.createNativeQuery(sql).executeUpdate();
    }

    @Transactional
    public Page list(String search, ProspectStatus status, String country, int page, int size) {
        if (page < 0 || page > 100000 || size < 1 || size > 200) throw invalid("Ongeldige paginagrootte of pagina");
        StringBuilder where = new StringBuilder("1 = 1");
        Map<String, Object> parameters = new HashMap<>();
        if (status != null) {
            where.append(" and status = :status");
            parameters.put("status", status);
        }
        if (clean(country, 2, "countryCode") != null) {
            where.append(" and countryCode = :country");
            parameters.put("country", countryCode(country));
        }
        String term = clean(search, 200, "search");
        if (term != null) {
            where.append(" and (lower(businessName) like :term escape '!' or lower(email) like :term escape '!'"
                    + " or lower(instagramHandle) like :term escape '!' or lower(groupKey) like :term escape '!')");
            parameters.put("term", "%" + term.toLowerCase(Locale.ROOT).replace("!", "!!")
                    .replace("%", "!%").replace("_", "!_") + "%");
        }
        long total = ProspectEntity.count(where.toString(), parameters);
        List<ProspectEntity> rows = ProspectEntity.find(where + " order by updatedAt desc, id desc", parameters)
                .page(page, size).list();
        return new Page(rows.stream().map(ProspectEntity::dto).toList(), total, page, size);
    }

    @Transactional
    public Detail detail(long id) {
        ProspectEntity prospect = required(id);
        List<ProspectActivityEntity> rows = ProspectActivityEntity.list(
                "prospect.id = ?1 order by occurredAt desc, id desc", id);
        return new Detail(prospect.dto(), rows.stream().map(ProspectActivityEntity::dto).toList());
    }

    @Transactional
    public Prospect create(ProspectInput input) {
        lock();
        ProspectEntity entity = new ProspectEntity();
        apply(entity, input);
        rejectDuplicate(entity);
        entity.createdAt = now();
        entity.updatedAt = entity.createdAt;
        entity.persistAndFlush();
        return entity.dto();
    }

    @Transactional
    public Prospect update(long id, ProspectInput input) {
        lock();
        ProspectEntity entity = required(id);
        ProspectEntity candidate = new ProspectEntity();
        candidate.id = id;
        apply(candidate, input);
        rejectDuplicate(candidate);
        apply(entity, input);
        entity.updatedAt = now();
        em.flush();
        return entity.dto();
    }

    @Transactional
    public Activity record(long id, ActivityInput input) {
        if (input == null || input.channel() == null || input.status() == null) {
            throw invalid("Activiteit, kanaal en status zijn verplicht");
        }
        if (input.status() == ActivityStatus.RESERVED) throw invalid("Gebruik email-reservations om een e-mail te reserveren");
        lock();
        ProspectEntity prospect = required(id);
        String externalId = clean(input.externalId(), 200, "externalId");
        ProspectActivityEntity existing = byExternalId(externalId);
        if (existing != null && !existing.prospect.id.equals(id)) throw conflict("externalId hoort bij een andere prospect");
        if (input.status() == ActivityStatus.SCHEDULED && (existing == null
                || !Set.of(ActivityStatus.RESERVED, ActivityStatus.SCHEDULED).contains(existing.status))) {
            throw conflict("Reserveer eerst een e-mailslot voor de geplande verzenddatum");
        }
        ProspectActivityEntity activity = validatedActivity(prospect, input, externalId, existing);
        if (existing != null) {
            if (existing.status == ActivityStatus.RESERVED
                    || (existing.status == ActivityStatus.SCHEDULED && activity.status != ActivityStatus.SCHEDULED)) {
                if (activity.channel != Channel.EMAIL || !Objects.equals(activity.type, existing.type)
                        || !EnumSet.of(ActivityStatus.SCHEDULED, ActivityStatus.SENT, ActivityStatus.FAILED, ActivityStatus.CANCELLED).contains(activity.status)) {
                    throw conflict("Een gereserveerde e-mail kan alleen worden ingepland of als SENT, FAILED of CANCELLED worden afgesloten");
                }
                // Preserve the reviewed draft and its reservation identity on completion.
                if (!Objects.equals(activity.subject, existing.subject) || !Objects.equals(activity.body, existing.body)
                        || !Objects.equals(activity.attachmentName, existing.attachmentName)
                        || !Objects.equals(activity.sourceUrl, existing.sourceUrl)) {
                    throw conflict("De activiteit wijkt af van de gereserveerde e-mail");
                }
                existing.status = activity.status;
                if (activity.status == ActivityStatus.SCHEDULED && !activity.occurredAt.equals(existing.occurredAt)) {
                    throw conflict("Een geplande e-mail moet de gereserveerde verzendtijd behouden");
                }
                existing.occurredAt = activity.occurredAt;
                updateTimeline(prospect, existing);
                em.flush();
                return existing.dto();
            }
            if (!sameActivity(existing, activity, input.occurredAt() != null)) {
                throw conflict("externalId is al gebruikt voor een andere activiteit");
            }
            return existing.dto();
        }
        activity.persistAndFlush();
        updateTimeline(prospect, activity);
        return activity.dto();
    }

    /** Reserve before sending outside the ERP. This service never sends or queues mail. */
    @Transactional
    public Activity reserveEmail(long id, EmailReservationInput input) {
        if (input == null) throw invalid("Geen reserveringsgegevens meegestuurd");
        String externalId = requiredText(input.externalId(), 200, "externalId");
        lock();
        ProspectEntity prospect = required(id);
        Instant now = now();
        Instant scheduledFor = input.scheduledFor() == null ? now : input.scheduledFor().truncatedTo(ChronoUnit.MICROS);
        if (input.scheduledFor() != null && scheduledFor.isBefore(now)) {
            throw invalid("De geplande verzendtijd ligt in het verleden");
        }
        ActivityInput activityInput = new ActivityInput(Channel.EMAIL, "OUTREACH", ActivityStatus.RESERVED,
                input.subject(), input.body(), scheduledFor, externalId, input.attachmentName(), input.sourceUrl());
        ProspectActivityEntity candidate = validatedActivity(prospect, activityInput, externalId, null);
        ProspectActivityEntity existing = byExternalId(externalId);
        if (existing != null) {
            if (!existing.prospect.id.equals(id) || existing.channel != Channel.EMAIL
                    || !Objects.equals(existing.type, candidate.type)
                    || !Objects.equals(existing.subject, candidate.subject) || !Objects.equals(existing.body, candidate.body)
                    || !Objects.equals(existing.attachmentName, candidate.attachmentName)
                    || !Objects.equals(existing.sourceUrl, candidate.sourceUrl)
                    || (input.scheduledFor() != null && !existing.occurredAt.equals(candidate.occurredAt))) {
                throw conflict("externalId is al gebruikt voor een andere reservering");
            }
            // Never create another send opportunity for a completed, cancelled or old reservation.
            if (existing.status != ActivityStatus.RESERVED || dayOf(existing.occurredAt).isBefore(dayOf(now))) {
                throw conflict("Deze reservering is al afgesloten of hoort bij een eerdere dag");
            }
            if (!Objects.equals(existing.recipientEmail, prospect.email)
                    || (existing.groupKey != null && !Objects.equals(existing.groupKey, prospect.groupKey))) {
                throw conflict("De ontvanger of bedrijfsgroep is gewijzigd; annuleer deze reservering en reserveer opnieuw");
            }
            rejectSuppressed(prospect);
            return existing.dto();
        }
        rejectSuppressed(prospect);
        if (prospect.email == null) throw conflict("Prospect heeft geen e-mailadres");
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("channel", Channel.EMAIL);
        parameters.put("statuses", List.of(ActivityStatus.RESERVED, ActivityStatus.SCHEDULED, ActivityStatus.SENT));
        parameters.put("id", id);
        parameters.put("email", prospect.email);
        String duplicate = "channel = :channel and status in :statuses and (prospect.id = :id or recipientEmail = :email";
        if (prospect.groupKey != null) {
            // A group discovered after the original reservation still suppresses
            // sibling prospects. Known historical snapshots remain authoritative.
            duplicate += " or groupKey = :groupKey or (groupKey is null and prospect.groupKey = :groupKey)";
            parameters.put("groupKey", prospect.groupKey);
        }
        if (ProspectActivityEntity.count(duplicate + ")", parameters) > 0) {
            throw conflict("Prospect of bedrijfsgroep heeft al een verstuurde of gereserveerde e-mail");
        }
        EmailSummary summary = emailSummary(dayOf(scheduledFor));
        if (summary.remaining() == 0) throw conflict("De daglimiet van " + DAILY_EMAIL_LIMIT + " e-mails is bereikt");
        candidate.persistAndFlush();
        updateTimeline(prospect, candidate);
        return candidate.dto();
    }

    @Transactional
    public EmailSummary emailSummary(LocalDate date) {
        LocalDate day = date == null ? LocalDate.now(OUTREACH_ZONE) : date;
        Instant start = day.atStartOfDay(OUTREACH_ZONE).toInstant();
        Instant end = day.plusDays(1).atStartOfDay(OUTREACH_ZONE).toInstant();
        List<ProspectActivityEntity> rows = ProspectActivityEntity.list(
                "channel = ?1 and occurredAt >= ?2 and occurredAt < ?3 order by occurredAt desc, id desc",
                Channel.EMAIL, start, end);
        long sent = rows.stream().filter(row -> row.status == ActivityStatus.SENT).count();
        long reserved = rows.stream().filter(row -> row.status == ActivityStatus.RESERVED).count();
        long scheduled = rows.stream().filter(row -> row.status == ActivityStatus.SCHEDULED).count();
        return new EmailSummary(day, OUTREACH_ZONE.getId(), DAILY_EMAIL_LIMIT, sent, reserved, scheduled,
                Math.max(0, DAILY_EMAIL_LIMIT - sent - reserved - scheduled), rows.stream().map(ProspectActivityEntity::dto).toList());
    }

    private void lock() {
        if (em.find(ProspectOutreachLockEntity.class, 1L, LockModeType.PESSIMISTIC_WRITE) == null) {
            throw new IllegalStateException("Prospect database lock is missing; apply the prospect migration");
        }
    }

    private ProspectEntity required(long id) {
        ProspectEntity entity = ProspectEntity.findById(id);
        if (entity == null) throw new NotFoundException("Prospect", id);
        return entity;
    }

    private ProspectActivityEntity byExternalId(String externalId) {
        return externalId == null ? null : ProspectActivityEntity.find("externalId", externalId).firstResult();
    }

    private void rejectDuplicate(ProspectEntity entity) {
        long ignoredId = entity.id == null ? -1 : entity.id;
        if (entity.email != null && ProspectEntity.count("email = ?1 and id <> ?2", entity.email, ignoredId) > 0) {
            throw conflict("Er bestaat al een prospect met dit e-mailadres");
        }
        if (entity.instagramHandle != null && ProspectEntity.count("instagramHandle = ?1 and id <> ?2", entity.instagramHandle, ignoredId) > 0) {
            throw conflict("Er bestaat al een prospect met dit Instagram-account");
        }
    }

    private void rejectSuppressed(ProspectEntity prospect) {
        if (NO_NEW_OUTREACH.contains(prospect.status)) throw conflict("De prospectstatus staat nieuwe acquisitie niet toe");
        if (prospect.groupKey != null && ProspectEntity.count("groupKey = ?1 and status in ?2", prospect.groupKey,
                List.of(ProspectStatus.DO_NOT_CONTACT, ProspectStatus.NOT_INTERESTED, ProspectStatus.CUSTOMER)) > 0) {
            throw conflict("De bedrijfsgroep is uitgesloten van nieuwe acquisitie");
        }
    }

    private static void apply(ProspectEntity entity, ProspectInput input) {
        if (input == null) throw invalid("Geen prospectgegevens meegestuurd");
        entity.businessName = requiredText(input.businessName(), 200, "businessName");
        entity.countryCode = countryCode(input.countryCode());
        entity.language = clean(input.language(), 16, "language");
        if (entity.language != null && !entity.language.matches("[A-Za-z]{2,3}(-[A-Za-z]{2}|-[0-9]{3})?")) {
            throw invalid("Ongeldige taalcode");
        }
        entity.email = lower(clean(input.email(), 254, "email"));
        if (entity.email != null && !entity.email.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")) throw invalid("Ongeldig e-mailadres");
        entity.website = url(input.website(), "website");
        entity.instagramHandle = instagram(input.instagramHandle());
        entity.groupKey = lower(clean(input.groupKey(), 200, "groupKey"));
        entity.sourceUrl = url(input.sourceUrl(), "sourceUrl");
        entity.sourceType = clean(input.sourceType(), 64, "sourceType");
        entity.status = input.status() == null ? (entity.status == null ? ProspectStatus.NEW : entity.status) : input.status();
        entity.notes = clean(input.notes(), 20000, "notes");
    }

    private ProspectActivityEntity validatedActivity(ProspectEntity prospect, ActivityInput input,
                                                     String externalId, ProspectActivityEntity existing) {
        ProspectActivityEntity row = new ProspectActivityEntity();
        row.prospect = prospect;
        row.channel = input.channel();
        row.status = input.status();
        row.type = requiredText(input.type(), 64, "type").toUpperCase(Locale.ROOT);
        if (!row.type.matches("[A-Z][A-Z0-9_]{0,63}")) throw invalid("Ongeldig activiteitstype");
        row.subject = clean(input.subject(), 300, "subject");
        row.body = clean(input.body(), 100000, "body");
        row.attachmentName = clean(input.attachmentName(), 255, "attachmentName");
        row.sourceUrl = url(input.sourceUrl(), "sourceUrl");
        if (existing != null) {
            if (input.subject() == null) row.subject = existing.subject;
            if (input.body() == null) row.body = existing.body;
            if (input.attachmentName() == null) row.attachmentName = existing.attachmentName;
            if (input.sourceUrl() == null) row.sourceUrl = existing.sourceUrl;
        }
        if (row.subject == null && row.body == null) throw invalid("Geef een onderwerp of inhoud voor de activiteit");
        row.occurredAt = input.occurredAt() == null ? now() : input.occurredAt().truncatedTo(ChronoUnit.MICROS);
        if (row.status == ActivityStatus.SCHEDULED && existing != null && input.occurredAt() == null) row.occurredAt = existing.occurredAt;
        if (row.status != ActivityStatus.RESERVED && row.status != ActivityStatus.SCHEDULED
                && row.occurredAt.isAfter(now().plusSeconds(300))) throw invalid("Activiteit kan niet in de toekomst liggen");
        row.externalId = externalId;
        row.createdAt = now();
        row.recipientEmail = row.channel == Channel.EMAIL ? prospect.email : null;
        row.groupKey = prospect.groupKey;
        String actor = identity == null || identity.isAnonymous() ? "system" : identity.getPrincipal().getName();
        row.createdBy = clean(actor, 100, "createdBy");
        return row;
    }

    private static boolean sameActivity(ProspectActivityEntity a, ProspectActivityEntity b, boolean compareTime) {
        return a.channel == b.channel && a.status == b.status && Objects.equals(a.type, b.type)
                && Objects.equals(a.subject, b.subject) && Objects.equals(a.body, b.body)
                && Objects.equals(a.attachmentName, b.attachmentName) && Objects.equals(a.sourceUrl, b.sourceUrl)
                && (!compareTime || a.occurredAt.equals(b.occurredAt));
    }

    private static void updateTimeline(ProspectEntity prospect, ProspectActivityEntity row) {
        Instant activityTime = row.status == ActivityStatus.RESERVED || row.status == ActivityStatus.SCHEDULED ? now() : row.occurredAt;
        if (prospect.lastActivityAt == null || activityTime.isAfter(prospect.lastActivityAt)) prospect.lastActivityAt = activityTime;
        if (row.channel != Channel.NOTE && !Set.of("LIKE", "PROFILE_REVIEW").contains(row.type)
                && (row.status == ActivityStatus.SENT || row.status == ActivityStatus.COMPLETED)) {
            if (prospect.lastContactAt == null || row.occurredAt.isAfter(prospect.lastContactAt)) prospect.lastContactAt = row.occurredAt;
            if (prospect.status == ProspectStatus.NEW || prospect.status == ProspectStatus.QUALIFIED) prospect.status = ProspectStatus.CONTACTED;
        }
        prospect.updatedAt = now();
    }

    private static String instagram(String value) {
        String handle = clean(value, 200, "instagramHandle");
        if (handle == null) return null;
        if (handle.startsWith("https://") || handle.startsWith("http://")) {
            URI uri;
            try { uri = URI.create(handle); } catch (IllegalArgumentException e) { throw invalid("Ongeldig Instagram-account"); }
            if (uri.getHost() == null || !Set.of("instagram.com", "www.instagram.com").contains(lower(uri.getHost()))) throw invalid("Ongeldig Instagram-account");
            handle = uri.getPath().replaceAll("^/|/$", "");
        }
        if (handle.startsWith("@")) handle = handle.substring(1);
        if (!handle.matches("[A-Za-z0-9._]{1,30}")) throw invalid("Ongeldig Instagram-account");
        return handle.toLowerCase(Locale.ROOT);
    }

    private static String url(String value, String field) {
        String result = clean(value, 2000, field);
        if (result == null) return null;
        try {
            URI uri = URI.create(result);
            if ((!"https".equalsIgnoreCase(uri.getScheme()) && !"http".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null || uri.getUserInfo() != null) throw invalid("Ongeldige URL: " + field);
        } catch (IllegalArgumentException invalid) { throw invalid("Ongeldige URL: " + field); }
        return result;
    }

    private static String countryCode(String value) {
        String country = requiredText(value, 2, "countryCode").toUpperCase(Locale.ROOT);
        if (!COUNTRIES.contains(country)) throw invalid("Gebruik een ISO-landcode, bijvoorbeeld BE, NL of GB");
        return country;
    }

    private static String requiredText(String value, int max, String field) {
        String result = clean(value, max, field);
        if (result == null) throw invalid(field + " is verplicht");
        return result;
    }

    private static String clean(String value, int max, String field) {
        if (value == null || value.isBlank()) return null;
        String result = value.strip();
        if (result.length() > max) throw invalid(field + " is te lang (maximaal " + max + ")");
        return result;
    }

    private static String lower(String value) { return value == null ? null : value.toLowerCase(Locale.ROOT); }
    private static Instant now() { return Instant.now().truncatedTo(ChronoUnit.MICROS); }
    private static LocalDate dayOf(Instant instant) { return instant.atZone(OUTREACH_ZONE).toLocalDate(); }
    private static BadRequestException invalid(String message) { return new BadRequestException(message); }
    private static BusinessRuleException conflict(String message) { return new BusinessRuleException(message); }
}
