package be.enrosed.finance.banking;

import be.enrosed.sales.application.IncomingPaymentService;
import be.enrosed.shared.BusinessRuleException;
import be.enrosed.shared.NotFoundException;
import be.enrosed.shared.security.CurrentActor;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.transaction.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;

/**
 * "Boeken als": a bank transaction that pays no invoice or cost, booked as an
 * internal transfer between our own accounts, a VAT payment or refund of a
 * quarter, a loan, capital, private money or something else (with a note).
 * A classified line counts as allocated everywhere (the described allocation
 * explains it in full), so it leaves "Te koppelen", an Odoo re-import that
 * changes its amount is a conflict, and every link path refuses it until it
 * is undone. The two legs of an internal transfer point at each other and are
 * booked and undone together. The classification is plain text, validated
 * here, so a later value needs no migration.
 */
@ApplicationScoped
public class BankLineClassificationService {
    static final Set<String> CLASSIFICATIONS = Set.of("INTERNAL_TRANSFER", "VAT", "LOAN", "CAPITAL", "PRIVATE", "OTHER");
    private static final int NOTE_LENGTH = 300;
    private static final int TRANSFER_DAYS = 5;

    @Inject EntityManager em;
    @Inject BankStatementService bank;
    @Inject CurrentActor actor;

    public record Request(String classification, String note, Long transferLineId, String vatPeriod) {}
    /** The classified line and, for an internal transfer, its other leg, by ascending id, described as the list describes them. */
    public record Result(List<BankStatementLineEntity> lines) {}

    @Transactional
    public Result classify(long id, Request request) {
        Long requestedOther = request == null ? null : request.transferLineId();
        List<BankStatementLineEntity> locked = lock(id, requestedOther);
        BankStatementLineEntity line = locked.stream().filter(row -> row.id == id).findFirst()
                .orElseThrow(() -> new NotFoundException("Bankbeweging", id));
        if (line.amountEur.signum() == 0) throw new BusinessRuleException("Kies een banktransactie met een bedrag");
        String classification = request == null || request.classification() == null ? null : request.classification().strip();
        String note = request == null || request.note() == null || request.note().isBlank() ? null : request.note().strip();
        String vatPeriod = request == null || request.vatPeriod() == null || request.vatPeriod().isBlank() ? null : request.vatPeriod().strip();
        if (line.classification != null) {
            /* A retry of the same booking answers what is there; anything else must be undone first. */
            if (line.classification.equals(classification) && Objects.equals(line.classificationNote, note)
                    && Objects.equals(line.vatPeriod, vatPeriod) && Objects.equals(line.transferLineId, requestedOther)) {
                return result(line, line.transferLineId == null ? null : em.find(BankStatementLineEntity.class, line.transferLineId));
            }
            throw new BusinessRuleException("Deze banktransactie is al geboekt als "
                    + BankStatementService.classificationLabel(line.classification) + "; maak dat eerst ongedaan");
        }
        if (bank.describe(line).allocated) throw new BusinessRuleException("Deze banktransactie is al gekoppeld; maak de koppeling eerst los");
        if (classification == null || !CLASSIFICATIONS.contains(classification)) {
            throw new BusinessRuleException("Kies hoe je deze banktransactie boekt");
        }
        if (note != null && note.length() > NOTE_LENGTH) throw new BusinessRuleException("De uitleg mag maximaal 300 tekens bevatten");
        if (classification.equals("OTHER") && note == null) throw new BusinessRuleException("Vul bij Andere een korte uitleg in");
        if (classification.equals("VAT")) {
            if (vatPeriod == null || !vatPeriod.matches("\\d{4}-Q[1-4]")) throw new BusinessRuleException("Kies het btw-kwartaal (bijv. 2026-Q3)");
        } else if (vatPeriod != null) {
            throw new BusinessRuleException("Een btw-kwartaal hoort alleen bij een btw-betaling");
        }
        BankStatementLineEntity other = null;
        if (requestedOther != null) {
            if (!classification.equals("INTERNAL_TRANSFER")) {
                throw new BusinessRuleException("Een andere kant hoort alleen bij een interne overboeking");
            }
            other = locked.stream().filter(row -> row.id.equals(requestedOther)).findFirst()
                    .orElseThrow(() -> new NotFoundException("Bankbeweging", requestedOther));
            if (other.id == id || canonical(other.account).equals(canonical(line.account))) {
                throw new BusinessRuleException("De andere kant van de overboeking moet op een andere eigen rekening staan");
            }
            if (other.amountEur.compareTo(line.amountEur.negate()) != 0) {
                throw new BusinessRuleException("De andere kant moet het tegengestelde bedrag hebben");
            }
            if (Math.abs(ChronoUnit.DAYS.between(day(line), day(other))) > TRANSFER_DAYS) {
                throw new BusinessRuleException("De andere kant ligt meer dan 5 dagen verder");
            }
            if (bank.describe(other).allocated) throw new BusinessRuleException("De andere kant is al gekoppeld of geboekt");
        }
        Instant now = Instant.now();
        String by = actor.current().displayName();
        book(line, classification, note, other == null ? null : other.id, vatPeriod, now, by);
        if (other != null) book(other, classification, note, line.id, null, now, by);
        em.flush();
        bank.audit(line, booked(line));
        if (other != null) bank.audit(other, booked(other));
        return result(line, other);
    }

    /** Undoes the booking of this line and, for an internal transfer, of its other leg; nothing to do on an open line. */
    @Transactional
    public void unclassify(long id) {
        List<BankStatementLineEntity> locked = lock(id, null);
        BankStatementLineEntity line = locked.stream().filter(row -> row.id == id).findFirst()
                .orElseThrow(() -> new NotFoundException("Bankbeweging", id));
        if (line.classification == null) return;
        BankStatementLineEntity other = line.transferLineId == null ? null : locked.stream()
                .filter(row -> row.id.equals(line.transferLineId) && "INTERNAL_TRANSFER".equals(row.classification)
                        && Long.valueOf(id).equals(row.transferLineId))
                .findFirst().orElse(null);
        clear(line);
        if (other != null) clear(other);
        em.flush();
        bank.audit(line, "Boeken als ongedaan gemaakt");
        if (other != null) bank.audit(other, "Boeken als ongedaan gemaakt");
    }

    /**
     * The line, the requested other leg and the stored other leg, locked in ascending id as every bank write does.
     * The stored leg is read as a value first, so no unlocked copy of the line sits in the session.
     */
    private List<BankStatementLineEntity> lock(long id, Long requestedOther) {
        List<Long> stored = em.createQuery("select b.transferLineId from BankStatementLineEntity b where b.id=:id", Long.class)
                .setParameter("id", id).getResultList();
        Long storedOther = stored.isEmpty() ? null : stored.getFirst();
        SortedSet<Long> ids = new TreeSet<>();
        ids.add(id);
        if (requestedOther != null) ids.add(requestedOther);
        if (storedOther != null) ids.add(storedOther);
        List<BankStatementLineEntity> rows = new ArrayList<>();
        for (Long lineId : ids) {
            var row = em.find(BankStatementLineEntity.class, lineId, LockModeType.PESSIMISTIC_WRITE);
            if (row != null) rows.add(row);
        }
        return rows;
    }

    private static void book(BankStatementLineEntity row, String classification, String note, Long other, String vatPeriod,
                             Instant at, String by) {
        row.classification = classification;
        row.classificationNote = note;
        row.transferLineId = other;
        row.vatPeriod = vatPeriod;
        row.classifiedAt = at;
        row.classifiedBy = by;
    }

    private static void clear(BankStatementLineEntity row) {
        book(row, null, null, null, null, null, null);
    }

    private static String booked(BankStatementLineEntity row) {
        return "Geboekt als " + BankStatementService.classificationLabel(row.classification)
                + (row.vatPeriod == null ? "" : " · kwartaal " + row.vatPeriod)
                + (row.transferLineId == null ? "" : " · ⇄ lijn " + row.transferLineId);
    }

    private Result result(BankStatementLineEntity line, BankStatementLineEntity other) {
        List<BankStatementLineEntity> lines = new ArrayList<>();
        lines.add(bank.describe(line));
        if (other != null) lines.add(bank.describe(other));
        lines.sort(java.util.Comparator.comparing(row -> row.id));
        return new Result(List.copyOf(lines));
    }

    /** An alias groups old names under its account: two names of one account are not two accounts. */
    private String canonical(String account) {
        String key = IncomingPaymentService.normalizeAccount(account);
        if (key == null) return "";
        var row = em.find(BankAccountEntity.class, key);
        return row != null && row.canonicalKey != null ? row.canonicalKey : key;
    }

    private static LocalDate day(BankStatementLineEntity row) {
        return row.bookedAt.atZone(ZoneId.of(row.timeZone)).toLocalDate();
    }
}
