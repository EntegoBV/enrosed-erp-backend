package be.enrosed.tica;

import be.enrosed.finance.application.CompanyCostService;
import be.enrosed.finance.banking.BankStatementLineEntity;
import be.enrosed.finance.banking.BankStatementService;
import be.enrosed.finance.domain.*;
import be.enrosed.shared.BusinessRuleException;
import be.enrosed.shared.NotFoundException;
import be.enrosed.shared.security.CurrentActor;
import be.enrosed.tica.TicaEntities.Sale;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.transaction.Transactional;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.*;
import java.util.*;

@ApplicationScoped
public class TicaSettlementService {
    private static final BigDecimal TOLERANCE = new BigDecimal("0.02");
    private final EntityManager entities;
    private final CompanyCostService costs;
    private final BankStatementService bank;
    private final CurrentActor actor;
    private final TicaService tica;
    public TicaSettlementService(EntityManager entities, CompanyCostService costs, BankStatementService bank,
                                 CurrentActor actor, TicaService tica) {
        this.entities = entities; this.costs = costs; this.bank = bank; this.actor = actor; this.tica = tica;
    }
    public record Request(String reference, String location, LocalDate documentDate, LocalDate dueDate,
                          LocalDate periodFrom, LocalDate periodTo, BigDecimal salesExcl, BigDecimal salesVat,
                          BigDecimal commissionExcl, BigDecimal commissionVat, BigDecimal netReceivable,
                          String documentNote, String sourceDocumentUrl) {}
    public record Dto(Long id, String reference, String location, LocalDate documentDate, LocalDate dueDate,
                      LocalDate periodFrom, LocalDate periodTo, BigDecimal salesExcl, BigDecimal salesVat,
                      BigDecimal commissionExcl, BigDecimal commissionVat, BigDecimal netReceivable,
                      String documentNote, String sourceDocumentUrl, String status, BigDecimal importedRevenueExcl,
                      BigDecimal reconciliationDifference, Long commissionCostId, Long bankStatementLineId,
                      Instant paidAt, Instant createdAt, String sourceKey) {}

    @Transactional public List<Dto> list() { return TicaSettlementEntity.<TicaSettlementEntity>find("order by documentDate desc, id desc").list().stream().map(this::dto).toList(); }
    @Transactional public Dto get(long id) { return dto(find(id, false)); }

    @Transactional public Optional<Dto> findBySource(String sourceKey) {
        return TicaSettlementEntity.<TicaSettlementEntity>find("sourceKey", sourceKey).firstResultOptional().map(this::dto);
    }
    public Dto create(Request request) { return createImported(request, null); }
    public Dto createImported(Request request, String sourceKey) {
        if (sourceKey != null && (sourceKey.isBlank() || sourceKey.length() > 300)) throw new BusinessRuleException("Ongeldige TICA-bronidentiteit.");
        tica.ensureSettings();
        return io.quarkus.narayana.jta.QuarkusTransaction.requiringNew().call(() -> {
            TicaService.lockedSettings(); // Serializes reference checks across instances.
            Request checked = checked(request);
            TicaSettlementEntity sourced = sourceKey == null ? null : TicaSettlementEntity.<TicaSettlementEntity>find("sourceKey", sourceKey).firstResult();
            if (sourced != null) {
                if (sameAmounts(sourced, checked)) return dto(sourced);
                throw new BusinessRuleException("Het TICA-brondocument is gewijzigd; controleer de bestaande afrekening.");
            }
            TicaSettlementEntity existing = TicaSettlementEntity.<TicaSettlementEntity>find("reference", checked.reference()).firstResult();
            if (existing != null) {
                if (sameAmounts(existing, checked) && Objects.equals(existing.sourceKey, sourceKey)) return dto(existing);
                if (sameAmounts(existing, checked) && existing.sourceKey == null && sourceKey != null) {
                    existing.sourceKey = sourceKey; return dto(existing);
                }
                throw new BusinessRuleException("Dit TICA-documentnummer bestaat al met andere gegevens.");
            }
            TicaSettlementEntity row = new TicaSettlementEntity(); apply(row, checked); row.sourceKey = sourceKey; row.createdAt = Instant.now(); row.persistAndFlush();
            return dto(row);
        });
    }

    @Transactional public Dto update(long id, Request request) {
        TicaService.lockedSettings();
        TicaSettlementEntity row = find(id, true);
        if (!row.status.equals("DRAFT")) throw new BusinessRuleException("Een bevestigde TICA-afrekening is vergrendeld.");
        if (row.sourceKey != null) throw new BusinessRuleException("Een geïmporteerd TICA-document behoudt zijn oorspronkelijke gegevens.");
        Request checked = checked(request);
        if (TicaSettlementEntity.count("reference = ?1 and id <> ?2", checked.reference(), id) != 0)
            throw new BusinessRuleException("Dit TICA-documentnummer bestaat al.");
        apply(row, checked); return dto(row);
    }

    @Transactional public Dto confirm(long id) {
        TicaService.lockedSettings(); // Same lock as the importer: amount checks and posting see one complete snapshot.
        TicaSettlementEntity row = find(id, true);
        if (!row.status.equals("DRAFT")) return dto(row);
        checked(requestOf(row));
        if (row.sourceDocumentUrl == null || row.sourceDocumentUrl.isBlank())
            throw new BusinessRuleException("Voeg eerst het TICA-afrekeningsdocument toe voordat je de afrekening bevestigt.");
        if (row.sourceKey != null) {
            var source = entities.find(be.enrosed.finance.odoo.OdooTicaDocumentEntity.class, row.sourceKey, LockModeType.PESSIMISTIC_WRITE);
            if (source == null || !"IMPORTED".equals(source.code) || !"posted".equals(source.sourceState)
                    || source.acceptedHash == null || !source.acceptedHash.equals(source.observedHash)
                    || !Objects.equals(source.settlementId, row.id))
                throw new BusinessRuleException("Het Odoo-brondocument is gewijzigd of niet meer beschikbaar. Controleer dit eerst voordat je de afrekening bevestigt.");
        }
        if (!row.location.equals("TICA - Brussel"))
            throw new BusinessRuleException("Voor deze vestiging is nog geen btw-configuratie voor de commissie ingesteld.");
        var state = TicaEntities.Settings.<TicaEntities.Settings>findById(1L);
        if (state == null || state.lastSuccessAt == null || state.coverageFrom == null || state.coverageTo == null
                || state.coverageFrom.isAfter(row.periodFrom) || state.coverageTo.isBefore(row.periodTo))
            throw new BusinessRuleException("Importeer eerst een volledig TICA-verkoopoverzicht dat deze afrekenperiode bevat.");
        if (revenue(row).subtract(row.salesExcl).abs().compareTo(TOLERANCE) > 0)
            throw new BusinessRuleException("De omzet op het document wijkt meer dan € 0,02 af van de geïmporteerde verkopen.");
        if (TicaSettlementEntity.count("id <> ?1 and status <> 'DRAFT' and location = ?2 and periodFrom <= ?3 and periodTo >= ?4",
                id, row.location, row.periodTo, row.periodFrom) > 0)
            throw new BusinessRuleException("Deze periode overlapt met een eerder bevestigde TICA-afrekening voor dezelfde vestiging.");
        if (entities.createQuery("select count(c) from CompanyCostEntity c where c.reference = :ref and (c.salesChannel = 'TICA' or lower(c.party) like '%tica%')", Long.class)
                .setParameter("ref", row.reference).getSingleResult() > 0)
            throw new BusinessRuleException("Voor dit TICA-document is al een kost geboekt. Controleer die registratie voordat je de afrekening bevestigt.");
        if (row.commissionExcl.signum() > 0) {
            // The exact document VAT is retained via inclusive total. No percentage commission is estimated.
            BigDecimal vatRate = row.commissionVat.multiply(new BigDecimal("100")).divide(row.commissionExcl, 0, RoundingMode.HALF_UP);
            if (row.commissionExcl.multiply(vatRate).divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP)
                    .subtract(row.commissionVat).abs().compareTo(TOLERANCE) > 0)
                throw new BusinessRuleException("De btw op de commissie past niet bij één btw-tarief; splits dit document eerst uit.");
            CompanyCost cost = costs.create(new CompanyCost(null, row.documentDate, "COMMISSIE", "TICA-commissie · " + row.reference,
                    "TICA Brussel", row.commissionExcl, row.commissionExcl.add(row.commissionVat), vatRate, row.reference,
                    null, "TICA", "Verrekend op de afrekening. Geen aparte bankuitgave. " + Optional.ofNullable(row.documentNote).orElse(""),
                    null, null, null, VatRegime.BE, true, "BE", CostKind.SERVICES, ExpenseType.STANDARD,
                    new BigDecimal("100"), new BigDecimal("100"), null, null, row.dueDate));
            var paid = costs.addPayment(cost.id(), new CostPayment(null, cost.id(), row.documentDate,
                    row.commissionExcl.add(row.commissionVat), null, null, "Verrekend in TICA-afrekening " + row.reference,
                    null, null));
            row.commissionCostId = cost.id(); row.commissionPaymentId = paid.payments().getLast().id();
            entities.find(be.enrosed.finance.adapter.out.persistence.CostPaymentEntity.class, row.commissionPaymentId).settlementOffset = true;
        }
        row.status = "OPEN"; row.confirmedAt = Instant.now(); return dto(row);
    }

    @Transactional public Dto linkBank(long id, long bankLineId) {
        // Bank first, as in the existing bank allocation services.
        BankStatementLineEntity line = entities.find(BankStatementLineEntity.class, bankLineId, LockModeType.PESSIMISTIC_WRITE);
        if (line == null) throw new NotFoundException("Bankbeweging", bankLineId);
        TicaSettlementEntity row = find(id, true);
        if (Objects.equals(row.bankStatementLineId, bankLineId) && Objects.equals(line.ticaSettlementId, id)) return dto(row);
        if (!row.status.equals("OPEN") || row.bankStatementLineId != null)
            throw new BusinessRuleException("Bevestig eerst de afrekening; een reeds gekoppelde afrekening kan niet opnieuw worden betaald.");
        if (bank.list().stream().filter(b -> b.id.equals(bankLineId)).anyMatch(b -> b.allocated))
            throw new BusinessRuleException("Deze banktransactie is al gekoppeld of geboekt.");
        if (line.amountEur.signum() <= 0 || line.amountEur.subtract(row.netReceivable).abs().compareTo(TOLERANCE) > 0)
            throw new BusinessRuleException("Kies een inkomende banktransactie die maximaal € 0,02 afwijkt van het te ontvangen bedrag.");
        line.classification = "TICA_SETTLEMENT"; line.classificationNote = "TICA-afrekening " + row.reference;
        line.classifiedAt = Instant.now(); line.classifiedBy = actor.name();
        line.ticaSettlementId = row.id; line.ticaSettlementAmountEur = row.netReceivable;
        row.bankStatementLineId = line.id; row.paidAt = line.bookedAt; row.status = "PAID";
        return dto(row);
    }

    @Transactional public Dto unlinkBank(long id) {
        List<Long> bankIds = entities.createQuery("select s.bankStatementLineId from TicaSettlement s where s.id = :id", Long.class)
                .setParameter("id", id).getResultList();
        Long bankId = bankIds.isEmpty() ? null : bankIds.getFirst();
        BankStatementLineEntity line = bankId == null ? null : entities.find(BankStatementLineEntity.class, bankId, LockModeType.PESSIMISTIC_WRITE);
        TicaSettlementEntity row = find(id, true);
        if (!Objects.equals(row.bankStatementLineId, bankId))
            throw new BusinessRuleException("De bankkoppeling is ondertussen gewijzigd. Vernieuw de afrekening en probeer opnieuw.");
        if (line != null && Objects.equals(line.ticaSettlementId, id)) {
            line.classification = null; line.classificationNote = null; line.classifiedAt = null; line.classifiedBy = null;
            line.ticaSettlementId = null; line.ticaSettlementAmountEur = null;
        }
        if (row.bankStatementLineId != null) { row.bankStatementLineId = null; row.paidAt = null; row.status = "OPEN"; }
        return dto(row);
    }

    private TicaSettlementEntity find(long id, boolean lock) {
        TicaSettlementEntity row = lock ? TicaSettlementEntity.findById(id, LockModeType.PESSIMISTIC_WRITE) : TicaSettlementEntity.findById(id);
        if (row == null) throw new NotFoundException("TICA-afrekening", id); return row;
    }
    private BigDecimal revenue(TicaSettlementEntity row) {
        return entities.createQuery("select coalesce(sum(s.netTotal), 0) from TicaSale s where s.active = true and s.location = :location and s.soldAt >= :start and s.soldAt < :end", BigDecimal.class)
                .setParameter("location", row.location).setParameter("start", row.periodFrom.atStartOfDay())
                .setParameter("end", row.periodTo.plusDays(1).atStartOfDay()).getSingleResult();
    }
    private Dto dto(TicaSettlementEntity row) {
        BigDecimal imported = revenue(row);
        return new Dto(row.id, row.reference, row.location, row.documentDate, row.dueDate, row.periodFrom, row.periodTo,
                row.salesExcl, row.salesVat, row.commissionExcl, row.commissionVat, row.netReceivable, row.documentNote,
                row.sourceDocumentUrl, row.status, imported, imported.subtract(row.salesExcl), row.commissionCostId,
                row.bankStatementLineId, row.paidAt, row.createdAt, row.sourceKey);
    }
    private static Request checked(Request r) {
        if (r == null || r.documentDate == null || r.dueDate == null || r.periodFrom == null || r.periodTo == null
                || r.periodFrom.isAfter(r.periodTo) || java.time.temporal.ChronoUnit.DAYS.between(r.periodFrom, r.periodTo) > 365
                || r.periodTo.isAfter(r.documentDate) || r.dueDate.isBefore(r.documentDate))
            throw new BusinessRuleException("Controleer documentdatum, vervaldag en afrekenperiode.");
        String ref = required(r.reference, 120), location = required(r.location, 160);
        BigDecimal sales = money(r.salesExcl), vat = money(r.salesVat), fee = money(r.commissionExcl), feeVat = money(r.commissionVat), net = money(r.netReceivable);
        if (sales.signum() <= 0 || fee.signum() < 0 || vat.signum() < 0 || feeVat.signum() < 0 || net.signum() <= 0
                || (fee.signum() == 0 && feeVat.signum() != 0)) throw new BusinessRuleException("Gebruik de positieve bedragen van een TICA-uitbetalingsafrekening.");
        if (sales.add(vat).subtract(fee).subtract(feeVat).compareTo(net) != 0)
            throw new BusinessRuleException("Omzet + omzet-btw − commissie − commissie-btw moet exact het uit te betalen bedrag zijn.");
        String note = optional(r.documentNote, 2000), url = optional(r.sourceDocumentUrl, 2000);
        if (url != null && !url.matches("https://[^\\s]+") && !url.matches("/api/tica/settlements/documents/[a-fA-F0-9-]{36}"))
            throw new BusinessRuleException("Gebruik een geldige documentlink.");
        return new Request(ref, location, r.documentDate, r.dueDate, r.periodFrom, r.periodTo, sales, vat, fee, feeVat, net, note, url);
    }
    private static String required(String s, int max) { String value = optional(s, max); if (value == null) throw new BusinessRuleException("Documentnummer en vestiging zijn verplicht."); return value; }
    private static String optional(String s, int max) { if (s == null || s.isBlank()) return null; s = s.strip(); if (s.length() > max) throw new BusinessRuleException("Een documentveld is te lang."); return s; }
    private static BigDecimal money(BigDecimal value) {
        if (value == null || value.abs().compareTo(new BigDecimal("1000000000")) >= 0) throw new BusinessRuleException("Een documentbedrag ontbreekt of is te groot.");
        try { return value.setScale(2, RoundingMode.UNNECESSARY); }
        catch (ArithmeticException e) { throw new BusinessRuleException("Gebruik maximaal twee decimalen voor documentbedragen."); }
    }
    private static Request requestOf(TicaSettlementEntity r) { return new Request(r.reference, r.location, r.documentDate, r.dueDate,
            r.periodFrom, r.periodTo, r.salesExcl, r.salesVat, r.commissionExcl, r.commissionVat, r.netReceivable, r.documentNote, r.sourceDocumentUrl); }
    private static boolean sameAmounts(TicaSettlementEntity row, Request r) {
        return row.reference.equals(r.reference) && row.location.equals(r.location) && row.documentDate.equals(r.documentDate)
                && row.dueDate.equals(r.dueDate) && row.periodFrom.equals(r.periodFrom) && row.periodTo.equals(r.periodTo)
                && row.salesExcl.compareTo(r.salesExcl) == 0 && row.salesVat.compareTo(r.salesVat) == 0
                && row.commissionExcl.compareTo(r.commissionExcl) == 0 && row.commissionVat.compareTo(r.commissionVat) == 0
                && row.netReceivable.compareTo(r.netReceivable) == 0;
    }
    private static void apply(TicaSettlementEntity r, Request q) {
        r.reference = q.reference; r.location = q.location; r.documentDate = q.documentDate; r.dueDate = q.dueDate;
        r.periodFrom = q.periodFrom; r.periodTo = q.periodTo; r.salesExcl = q.salesExcl; r.salesVat = q.salesVat;
        r.commissionExcl = q.commissionExcl; r.commissionVat = q.commissionVat; r.netReceivable = q.netReceivable;
        r.documentNote = q.documentNote; r.sourceDocumentUrl = q.sourceDocumentUrl;
    }
}
