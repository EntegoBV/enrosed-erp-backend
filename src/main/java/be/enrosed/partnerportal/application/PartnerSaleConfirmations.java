package be.enrosed.partnerportal.application;

import be.enrosed.partnerportal.adapter.out.persistence.PartnerPortalRepositories;
import be.enrosed.partnerportal.adapter.out.persistence.PartnerSaleReportEntity;
import be.enrosed.partnerportal.adapter.out.persistence.PartnerSaleReportLineEntity;
import be.enrosed.partnerportal.adapter.out.persistence.PartnerPortalQueries;
import be.enrosed.partnerportal.domain.PartnerPortalRules;
import be.enrosed.sales.application.port.out.SalesRepositories;
import be.enrosed.sales.domain.Customer;
import be.enrosed.shared.BusinessRuleException;
import be.enrosed.shared.audit.ActivityLogService;
import be.enrosed.shared.security.ActorRef;
import be.enrosed.shared.security.CurrentActor;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Bevestigen and Terugzetten (staff, in bulk): a confirmed melding is final for the partner (he can no
 * longer edit or undo it) and Factureren takes it. Every request is one transaction that takes the partner
 * locks first (ascending partner id, the same lock a partner's edit and Factureren take), then reads each
 * melding as it is now: a melding that changed since staff loaded it (its editToken differs) is refused, so
 * staff never confirm a version they did not see. All or nothing: one refusal changes nothing.
 */
@ApplicationScoped
public class PartnerSaleConfirmations {

    public static final String ACTION_CONFIRMED = "CONFIRMED";
    public static final String ACTION_UNCONFIRMED = "UNCONFIRMED";
    static final int MAX_REPORTS = 500;

    private final PartnerPortalRepositories.Settings settingsRows;
    private final PartnerPortalRepositories.Reports reports;
    private final PartnerPortalRepositories.Lines lines;
    private final PartnerOpenParts openParts;
    private final SalesRepositories.Customers customers;
    private final CurrentActor actor;
    private final ActivityLogService activity;

    public PartnerSaleConfirmations(PartnerPortalRepositories.Settings settingsRows,
                                    PartnerPortalRepositories.Reports reports, PartnerPortalRepositories.Lines lines,
                                    PartnerOpenParts openParts, SalesRepositories.Customers customers,
                                    CurrentActor actor, ActivityLogService activity) {
        this.settingsRows = settingsRows;
        this.reports = reports;
        this.lines = lines;
        this.openParts = openParts;
        this.customers = customers;
        this.actor = actor;
        this.activity = activity;
    }

    /**
     * Confirms (confirm true) or sets back these meldingen; answers their ids in id order. A melding already in
     * the asked state is left as it is. Refused: an unknown id, an old version, a withdrawn melding, a token that
     * no longer matches, and setting back a melding a document already holds a part of.
     */
    @Transactional
    public List<Long> apply(PartnerSaleRequests.ConfirmRequest request, boolean confirm) {
        Map<Long, String> tokens = tokens(request);
        Map<Long, PartnerSaleReportEntity> found = new TreeMap<>();
        for (Long id : tokens.keySet()) {
            PartnerSaleReportEntity report = reports.findById(id);
            if (report == null || report.customerId == null) {
                throw new BusinessRuleException(PartnerPortalRules.reportNumber(id) + " bestaat niet");
            }
            found.put(id, report);
        }
        /* Lock 1 of every partner involved, ascending, before anything is read for good. */
        for (Long customerId : new TreeSet<>(found.values().stream().map(report -> report.customerId).toList())) {
            settingsRows.lockOrCreate(customerId);
        }
        ActorRef who = actor.current();
        Instant now = Instant.now();
        Map<Long, String> partnerNames = new HashMap<>();
        List<PartnerSaleReportEntity> changed = new ArrayList<>();
        for (PartnerSaleReportEntity report : found.values()) {
            reports.getEntityManager().refresh(report);
            String number = PartnerPortalRules.reportNumber(report.id);
            if (report.isArchive()) throw new BusinessRuleException(number + " is een oude versie");
            if (report.isVoided()) throw new BusinessRuleException(number + " is ingetrokken");
            String token = tokens.get(report.id);
            if (token != null && !token.equals(report.editToken())) {
                throw new BusinessRuleException(number + " is intussen gewijzigd; bekijk ze opnieuw");
            }
            if (confirm == (report.confirmedAt != null)) continue;
            if (!confirm) {
                List<PartnerSaleReportLineEntity> parts = lines.byReport(report.id).stream()
                        .filter(line -> line.isSale() || line.isBuyback()).toList();
                PartnerOpenParts.Rule rule = openParts.forLines(parts);
                for (PartnerSaleReportLineEntity part : parts) {
                    PartnerPortalQueries.DocumentState holder = rule.holder(part);
                    if (holder != null) {
                        throw new BusinessRuleException(number + " staat al op " + (holder.number() == null
                                ? "een factuur" : holder.number()) + "; terugzetten kan niet meer");
                    }
                }
            }
            report.confirmedAt = confirm ? now : null;
            report.confirmedBy = confirm ? who.username() : null;
            report.confirmedByName = confirm ? cut(who.displayName(), 100) : null;
            changed.add(report);
        }
        reports.flush();
        for (PartnerSaleReportEntity report : changed) {
            String partner = partnerNames.computeIfAbsent(report.customerId,
                    id -> customers.findById(id).map(Customer::company).orElse(null));
            String number = PartnerPortalRules.reportNumber(report.id);
            activity.record(confirm ? ACTION_CONFIRMED : ACTION_UNCONFIRMED, PartnerSaleReportService.ENTITY,
                    report.id.toString(), number + (partner == null ? "" : " · " + partner),
                    confirm ? "Bevestigd" : "Teruggezet: nog te bevestigen");
        }
        return List.copyOf(found.keySet());
    }

    /** id -> the token to check (null = the legacy reportIds shape, no check), in id order, at most 500. */
    private static Map<Long, String> tokens(PartnerSaleRequests.ConfirmRequest request) {
        Map<Long, String> result = new LinkedHashMap<>();
        if (request != null && request.reports() != null) {
            for (PartnerSaleRequests.ConfirmItem item : request.reports()) {
                if (item == null || item.id() == null) continue;
                String token = item.token() == null || item.token().isBlank() ? null : item.token().strip();
                if (token == null) throw PartnerRefusals.badRequest("Stuur bij elke melding de editToken mee als token");
                result.put(item.id(), token);
            }
        } else if (request != null && request.reportIds() != null) {
            for (Long id : request.reportIds()) if (id != null) result.putIfAbsent(id, null);
        }
        if (result.isEmpty()) throw new BusinessRuleException("Kies minstens één melding");
        if (result.size() > MAX_REPORTS) throw new BusinessRuleException("Hoogstens " + MAX_REPORTS + " meldingen tegelijk");
        return result;
    }

    private static String cut(String value, int length) {
        return value == null || value.length() <= length ? value : value.substring(0, length);
    }
}
