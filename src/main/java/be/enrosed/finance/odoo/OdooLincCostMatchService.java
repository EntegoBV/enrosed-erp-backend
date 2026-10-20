package be.enrosed.finance.odoo;

import be.enrosed.finance.adapter.out.persistence.CompanyCostEntity;
import be.enrosed.finance.adapter.out.persistence.CostPaymentEntity;
import be.enrosed.finance.banking.BankStatementLineEntity;
import be.enrosed.finance.banking.BankAmountMatch;
import be.enrosed.shared.BusinessRuleException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import static be.enrosed.finance.odoo.OdooBankMapper.*;

/** Supplier-specific automation: a verified Linc invoice and its exact direct debit must both exist first. */
@ApplicationScoped @Transactional(Transactional.TxType.NOT_SUPPORTED)
public class OdooLincCostMatchService {
    static final String PROVIDER_VAT = "BE1018089234", RECIPIENT_VAT = "BE1034273386";
    private static final int PAGE_SIZE = 100, MAX_DOCUMENTS = 2000;
    @Inject OdooConfiguration config;
    @Inject OdooClient client;
    @Inject OdooDocumentService documents;
    @Inject OdooCostImportStore store;
    @Inject OdooCostImportSlots slots;
    @Inject OdooSyncStateStore state;
    @Inject EntityManager em;
    @Inject ObjectMapper json;
    @ConfigProperty(name = "enrosed.odoo.bank-auto-sync.enabled", defaultValue = "true") boolean automaticEnabled = true;

    public record Item(long documentId, String reference, String code, Long bankStatementLineId, Long companyCostId) {}
    public record Report(int scanned, int registered, int existing, int pending, boolean complete, String code, List<Item> items) {}
    public record Status(boolean enabled, boolean configured, Instant lastAttemptAt, Instant lastSuccessAt,
                         Instant nextRunAt, String lastCode, Report lastReport) {}
    record BankCandidate(long id, String account, String sourceId, BigDecimal amount, Instant bookedAt, String reference) {}
    record Selection(BankCandidate bank, Long costId, boolean registered) {}

    public Status status() {
        String problem = config.problem();
        if (problem != null) return new Status(automaticEnabled, false, null, null, null, problem, null);
        String key = scheduleKey(config.sourceNamespace());
        var schedule = state.bankSchedule(key); var snapshot = state.snapshot(key); Report report = null;
        try { if (snapshot.lastSyncJson() != null) report = json.readValue(snapshot.lastSyncJson(), Report.class); }
        catch (Exception ignored) { /* Older status formats do not expose private provider payloads. */ }
        return new Status(automaticEnabled, true, schedule.lastAttemptAt(), schedule.lastSuccessAt(), schedule.nextRunAt(), schedule.lastCode(), report);
    }

    public Optional<Report> syncIfDue() {
        if (!automaticEnabled || config.problem() != null) return Optional.empty();
        String source = config.sourceNamespace(), key = scheduleKey(source), owner = UUID.randomUUID().toString();
        try { if (!state.acquireBank(key, owner, true)) return Optional.empty(); }
        catch (OdooFailure failure) { throw failure; }
        catch (RuntimeException race) { throw new OdooFailure("BUSY"); }
        List<Item> items = new ArrayList<>(); String code = "READY"; int scanned = 0;
        try {
            requireRecipient(); List<Long> partners = providerPartners();
            if (!client.fields("account.move").contains("payment_reference")) throw new OdooFailure("UNSUPPORTED_SCHEMA");
            long cursor = 0;
            while (!partners.isEmpty()) {
                state.renew(key, owner);
                JsonNode rows = client.search("account.move", List.of(List.of("company_id", "=", config.companyId),
                                List.of("partner_id", "in", partners), List.of("move_type", "=", "in_invoice"),
                                List.of("state", "in", List.of("draft", "posted")), List.of("id", ">", cursor)),
                        List.of("id", "company_id", "partner_id", "move_type", "state", "payment_reference"), PAGE_SIZE, "id asc");
                if (rows.isEmpty()) break;
                for (JsonNode row : rows) {
                    state.renew(key, owner);
                    long id = positiveId(row.path("id"));
                    if (id <= cursor || relationId(row.path("company_id")) != config.companyId
                            || !partners.contains(relationId(row.path("partner_id"))) || !"in_invoice".equals(text(row, "move_type"))
                            || !Set.of("draft", "posted").contains(text(row, "state"))) throw new OdooFailure("INVALID_RESPONSE");
                    cursor = id; if (++scanned > MAX_DOCUMENTS) throw new OdooFailure("LINC_DOCUMENT_LIMIT");
                    try { items.add(process(id, text(row, "payment_reference"), partners, key, owner)); }
                    catch (BusinessRuleException | OdooFailure needsReview) {
                        if (needsReview instanceof OdooFailure failure && Set.of("UNAVAILABLE", "RATE_LIMITED", "AUTH_FAILED",
                                "ACCESS_DENIED", "INVALID_RESPONSE", "UNSUPPORTED_SCHEMA", "JSON2_UNAVAILABLE", "LEASE_LOST", "BUSY").contains(failure.code))
                            throw failure;
                        items.add(new Item(id, null, needsReview instanceof OdooFailure failure ? failure.code : "REVIEW_EXISTING_COST", null, null));
                    }
                }
            }
        } catch (OdooFailure failure) { code = failure.code; }
        catch (RuntimeException unavailable) { code = "UNAVAILABLE"; }
        int registered = (int) items.stream().filter(i -> "REGISTERED".equals(i.code())).count();
        int existing = (int) items.stream().filter(i -> "ALREADY_REGISTERED".equals(i.code())).count();
        int pending = items.size() - registered - existing;
        boolean scanCompleted = code.equals("READY");
        boolean complete = scanCompleted && pending == 0;
        if (code.equals("READY") && !complete) code = "LINC_REVIEW";
        Report report = new Report(scanned, registered, existing, pending, complete, code, List.copyOf(items));
        // Known review items are a completed scan, not a technical failure: keep the requested twelve-hour cadence.
        try { state.finishBank(key, owner, json.writeValueAsString(report), scanCompleted, code); }
        catch (OdooFailure failure) { throw failure; }
        catch (Exception unavailable) { throw new OdooFailure("UNAVAILABLE"); }
        return Optional.of(report);
    }

    private Item process(long id, String paymentReference, List<Long> partners, String leaseKey, String owner) {
        OdooDocumentService.PurchaseSnapshot snapshot = documents.purchaseSnapshot(id);
        String reference = snapshot.document().reference();
        if (!PROVIDER_VAT.equals(normalized(snapshot.partnerVat())) || snapshot.companyId() != config.companyId
                || !snapshot.namespace().equals(config.sourceNamespace()) || !partners.contains(snapshot.partnerId())
                || !"in_invoice".equals(snapshot.document().moveType()) || snapshot.totalEur().signum() <= 0
                || !"EUR".equals(snapshot.document().currency()) || !Set.of("draft", "posted").contains(snapshot.document().state()))
            throw new OdooFailure("INVALID_RESPONSE");
        String structured = structuredReference(paymentReference);
        if (structured == null) return new Item(id, reference, "STRUCTURED_REFERENCE_MISSING", null, null);
        List<BankCandidate> banks = matchingBanks(snapshot, structured, partners);
        if (banks.size() != 1) return new Item(id, reference, banks.isEmpty() ? "WAITING_FOR_BANK" : "AMBIGUOUS_BANK", null, null);
        BankCandidate matched = banks.getFirst();
        Selection selected = QuarkusTransaction.requiringNew().call(() -> select(snapshot, matched));
        if (selected == null) return new Item(id, reference, "REVIEW_EXISTING_COST", matched.id(), null);
        if (selected.registered()) return new Item(id, reference, "ALREADY_REGISTERED", matched.id(), selected.costId());
        List<OdooCostImportStore.Downloaded> files = new ArrayList<>();
        var attachments = snapshot.document().attachments().stream().filter(a -> Set.of("PDF", "XML").contains(a.kind())).toList();
        if (attachments.isEmpty() || attachments.size() > 12) return new Item(id, reference, "DOCUMENT_MISSING", matched.id(), selected.costId());
        long bytes = 0;
        for (var attachment : attachments) {
            state.renew(leaseKey, owner); var file = documents.download(id, attachment.id()); bytes += file.bytes().length;
            if (bytes > 30L * 1024 * 1024) throw new OdooFailure("ATTACHMENT_TOO_LARGE");
            files.add(new OdooCostImportStore.Downloaded(attachment.id(), attachment.kind(), file));
        }
        var refreshed = documents.purchaseSnapshot(id);
        if (!refreshed.hash().equals(snapshot.hash())) return new Item(id, reference, "SOURCE_CHANGED", matched.id(), selected.costId());
        // The payment reference is not part of the generic invoice snapshot hash: verify it separately after downloads.
        JsonNode current = client.search("account.move", List.of(List.of("id", "=", id), List.of("company_id", "=", config.companyId)),
                List.of("id", "payment_reference"), 1, "id asc");
        if (current.size() != 1 || positiveId(current.get(0).path("id")) != id
                || !structured.equals(structuredReference(text(current.get(0), "payment_reference"))))
            return new Item(id, reference, "SOURCE_CHANGED", matched.id(), selected.costId());
        try { slots.reserve(snapshot.namespace(), snapshot.companyId(), id); }
        catch (RuntimeException raced) { if (!slots.exists(snapshot.namespace() + ":" + id)) throw raced; }
        var registered = QuarkusTransaction.requiringNew().call(() -> register(snapshot, selected, files));
        return new Item(id, reference, registered.alreadyRegistered() ? "ALREADY_REGISTERED" : "REGISTERED", matched.id(), registered.companyCostId());
    }

    /** Only exact invoice references are automatically adopted; similar amounts/party names stay reviewable. */
    private Selection select(OdooDocumentService.PurchaseSnapshot snapshot, BankCandidate candidate) {
        var binding = store.find(snapshot.namespace(), snapshot.document().id());
        Long selected = binding == null ? null : binding.companyCostId;
        if (binding != null && selected != null && !Objects.equals(binding.snapshotHash, snapshot.hash())) return null;
        var plan = store.plan(candidate.id(), snapshot, selected);
        if (selected == null && !plan.existingCandidates().isEmpty()) {
            var exact = plan.existingCandidates().stream().filter(c -> exactReference(c.reference(), snapshot)).toList();
            if (exact.size() != 1 || plan.existingCandidates().size() != 1) return null;
            selected = exact.getFirst().id(); plan = store.plan(candidate.id(), snapshot, selected);
        }
        if (plan.unavailableReason() != null || plan.paymentAmountEur() == null
                || !BankAmountMatch.matches(plan.paymentAmountEur(), snapshot.totalEur())) return null;
        if (selected != null) {
            var cost = em.find(CompanyCostEntity.class, selected);
            if (cost == null || !exactReference(cost.reference, snapshot)) return null;
            List<CostPaymentEntity> payments = em.createQuery("from CostPaymentEntity where companyCostId = :id", CostPaymentEntity.class)
                    .setParameter("id", selected).getResultList();
            if (payments.stream().anyMatch(p -> p.settlementOffset || p.bankStatementLineId != null && !p.bankStatementLineId.equals(candidate.id()))) return null;
            boolean already = binding != null && binding.companyCostId != null && payments.stream().anyMatch(p -> Objects.equals(p.bankStatementLineId, candidate.id()));
            return new Selection(candidate, selected, already);
        }
        return new Selection(candidate, null, false);
    }

    private OdooCostImportStore.Result register(OdooDocumentService.PurchaseSnapshot snapshot, Selection selected, List<OdooCostImportStore.Downloaded> files) {
        // Follow the existing import lock order: source binding, bank, cost. Never hold DB locks during provider I/O.
        em.find(OdooCostImportEntity.class, snapshot.namespace() + ":" + snapshot.document().id(), LockModeType.PESSIMISTIC_WRITE);
        BankStatementLineEntity current = em.find(BankStatementLineEntity.class, selected.bank().id(), LockModeType.PESSIMISTIC_WRITE);
        if (!sameBank(current, selected.bank())) throw new OdooFailure("SOURCE_CHANGED");
        if (selected.costId() != null) em.find(CompanyCostEntity.class, selected.costId(), LockModeType.PESSIMISTIC_WRITE);
        Selection checked = select(snapshot, selected.bank());
        if (checked == null || !Objects.equals(checked.costId(), selected.costId())) throw new BusinessRuleException("Controleer de bestaande Linc-registratie.");
        return store.register(current.id, snapshot, snapshot.hash(), checked.costId(), "BOEKHOUDER", List.copyOf(files));
    }

    private List<BankCandidate> matchingBanks(OdooDocumentService.PurchaseSnapshot snapshot, String structured, List<Long> partners) {
        List<Object> domain = new ArrayList<>(List.of(List.of("company_id", "=", config.companyId), List.of("partner_id", "in", partners),
                List.of("amount", "=", snapshot.totalEur().negate())));
        if (!config.journalIds.isEmpty()) domain.add(List.of("journal_id", "in", config.journalIds));
        JsonNode rows = client.search("account.bank.statement.line", domain,
                List.of("id", "company_id", "partner_id", "journal_id", "amount", "date", "payment_ref"), 1000, "id asc");
        if (rows.size() >= 1000) throw new OdooFailure("LINC_BANK_LIMIT");
        LocalDate invoiceDate = LocalDate.parse(snapshot.document().date()); List<BankCandidate> result = new ArrayList<>();
        for (JsonNode remote : rows) {
            if (relationId(remote.path("company_id")) != config.companyId || !partners.contains(relationId(remote.path("partner_id")))) throw new OdooFailure("INVALID_RESPONSE");
            if (!config.journalIds.isEmpty() && !config.journalIds.contains(relationId(remote.path("journal_id")))) throw new OdooFailure("INVALID_RESPONSE");
            if (!remote.path("amount").isNumber() || remote.path("amount").decimalValue().compareTo(snapshot.totalEur().negate()) != 0) throw new OdooFailure("INVALID_RESPONSE");
            LocalDate booked = LocalDate.parse(text(remote, "date"));
            if (Math.abs(ChronoUnit.DAYS.between(booked, invoiceDate)) > 45 || !containsStructured(text(remote, "payment_ref"), structured)) continue;
            String sourceId = snapshot.namespace() + ":" + positiveId(remote.path("id"));
            BankCandidate local = QuarkusTransaction.requiringNew().call(() -> {
                var candidates = em.createQuery("from BankStatementLineEntity where source = 'ODOO' and sourceId = :source", BankStatementLineEntity.class)
                        .setParameter("source", sourceId).getResultList();
                if (candidates.size() != 1) return null;
                var bank = candidates.getFirst();
                if (bank.amountEur.compareTo(remote.path("amount").decimalValue()) != 0
                        || !bank.bookedAt.atZone(ZoneId.of(bank.timeZone)).toLocalDate().equals(booked)
                        || !containsStructured(bank.reference, structured)) return null;
                return new BankCandidate(bank.id, bank.account, bank.sourceId, bank.amountEur, bank.bookedAt, bank.reference);
            });
            if (local != null) result.add(local);
        }
        return List.copyOf(result);
    }

    private void requireRecipient() {
        JsonNode company = client.search("res.company", List.of(List.of("id", "=", config.companyId)), List.of("id", "vat"), 1, "id asc");
        if (company.size() != 1 || positiveId(company.get(0).path("id")) != config.companyId
                || !RECIPIENT_VAT.equals(normalized(text(company.get(0), "vat")))) throw new OdooFailure("LINC_COMPANY_MISMATCH");
    }
    private List<Long> providerPartners() {
        JsonNode rows = client.search("res.partner", List.of(List.of("vat", "ilike", "1018")), List.of("id", "vat"), 100, "id asc");
        if (rows.size() >= 100) throw new OdooFailure("INVALID_RESPONSE");
        List<Long> ids = new ArrayList<>(); for (JsonNode row : rows)
            if (PROVIDER_VAT.equals(normalized(text(row, "vat")))) ids.add(positiveId(row.path("id")));
        return List.copyOf(ids);
    }
    static boolean sameBank(BankStatementLineEntity row, BankCandidate expected) {
        return row != null && "ODOO".equals(row.source) && Objects.equals(row.sourceId, expected.sourceId())
                && Objects.equals(row.account, expected.account()) && row.amountEur.compareTo(expected.amount()) == 0
                && Objects.equals(row.bookedAt, expected.bookedAt()) && Objects.equals(row.reference, expected.reference());
    }
    static boolean exactReference(String reference, OdooDocumentService.PurchaseSnapshot source) {
        String value = normalized(reference); return value.length() >= 4
                && (value.equals(normalized(source.document().reference())) || value.equals(normalized(source.document().name())));
    }
    static String structuredReference(String value) {
        if (value == null || !value.matches("[+0-9/ .-]+")) return null;
        String digits = value.replaceAll("[^0-9]", "");
        if (digits.length() != 12) return null;
        long base = Long.parseLong(digits.substring(0, 10)); int check = Integer.parseInt(digits.substring(10));
        return check == (base % 97 == 0 ? 97 : base % 97) ? digits : null;
    }
    static boolean containsStructured(String value, String reference) {
        if (value == null || reference == null) return false;
        return java.util.regex.Pattern.compile("(?<![0-9])" + java.util.regex.Pattern.quote(reference) + "(?![0-9])")
                .matcher(value.replaceAll("[+/ .-]", "")).find();
    }
    private static String normalized(String value) { return value == null ? "" : value.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", ""); }
    static String scheduleKey(String source) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest((source + ":linc-costs").getBytes(StandardCharsets.UTF_8))); }
        catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }
}
