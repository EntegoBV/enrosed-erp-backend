package be.enrosed.sales.application;

import be.enrosed.sales.application.port.out.SalesRepositories;
import be.enrosed.sales.domain.QuoteEvent;
import be.enrosed.sales.domain.QuoteStatus;
import be.enrosed.sales.domain.SalesOrder;
import be.enrosed.shared.BusinessRuleException;
import be.enrosed.shared.security.ActorRef;
import be.enrosed.shared.security.CurrentActor;
import be.enrosed.sourcing.application.PurchaseOrderService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Retires unused legacy partner quotes without rewriting a document or duplicating a term invoice. */
@ApplicationScoped
public class LegacyPartnerQuoteDrafts {
    @Inject SalesOrderService sales;
    @Inject SalesRepositories.Orders orders;
    @Inject SalesRepositories.Events events;
    @Inject PurchaseOrderService purchases;
    @Inject PartnerAdvanceQuotes snapshots;
    @Inject PartnerAdvanceSchedules plans;
    @Inject PartnerAdvanceScheduleService schedules;
    @Inject IncomingPaymentService incoming;
    @Inject CurrentActor actor;

    public record Row(Long scheduleRowId, String label, BigDecimal amountEur, Long invoiceId, String invoiceNumber) {}
    public record Preview(boolean eligible, boolean alreadyConverted, String reason, long quoteId,
                          String quoteNumber, Long purchaseOrderId, List<Row> rows) {}
    public record Invoice(long id, String number) {}
    public record Result(long quoteId, long purchaseOrderId, List<Invoice> invoices, boolean archived) {}

    /** Cheap startup candidate check; the locked conversion repeats the complete assessment. */
    static boolean untouchedDraft(SalesOrder quote) {
        return quote != null && !quote.isInvoice() && quote.isPartnerAdvance()
                && quote.status() == QuoteStatus.CONCEPT && quote.sentAt() == null
                && quote.viewedAt() == null && quote.viewCount() == 0 && quote.decidedAt() == null
                && quote.signedByName() == null && quote.paidAt() == null
                && (quote.portalToken() == null || quote.portalToken().isBlank());
    }

    public Preview preview(long quoteId) {
        SalesOrder quote = sales.get(quoteId);
        List<Row> rows = new ArrayList<>();
        if (!untouchedDraft(quote)) return blocked(quote, rows,
                "Alleen een nog niet verstuurde of beoordeelde conceptofferte kan automatisch worden overgezet.");
        if (quote.linkedPurchaseOrderId() == null) return blocked(quote, rows, "De gekoppelde inkooporder ontbreekt.");
        if (incoming.hasHistory(quoteId) || events.findByOrder(quoteId).stream().anyMatch(LegacyPartnerQuoteDrafts::customerHistory)) {
            return blocked(quote, rows, "Deze offerte heeft verzend-, klant- of betaalhistorie; bewaar het oorspronkelijke document.");
        }
        var purchase = purchases.get(quote.linkedPurchaseOrderId());
        if (purchase.partnerCustomerId() == null || !Objects.equals(quote.customerId(), purchase.partnerCustomerId())) {
            return blocked(quote, rows, "De partner verschilt van de oorspronkelijke offerte.");
        }
        var snapshot = snapshots.find(quoteId);
        if (snapshot == null) {
            if (!plans.rows(purchase.id()).isEmpty()) return blocked(quote, rows,
                    "Er bestaat inmiddels een termijnplanning die niet bij deze offerte is vastgelegd.");
            var linked = orders.findAll().stream().filter(order -> order.isInvoice()
                    && Objects.equals(order.sourceQuoteId(), quoteId)
                    && order.status() != QuoteStatus.GEANNULEERD).toList();
            if (linked.size() > 1) return blocked(quote, rows, "Meerdere facturen verwijzen naar deze offerte; controleer de bestaande facturen.");
            SalesOrder invoice = linked.isEmpty() ? null : linked.getFirst();
            if (invoice == null && orders.findAll().stream().anyMatch(document -> document.isInvoice()
                    && document.isPartnerAdvance() && PartnerFinancingService.live(document)
                    && Objects.equals(document.linkedPurchaseOrderId(), purchase.id()))) {
                return blocked(quote, rows, "Er bestaat al een voorschotfactuur voor deze inkooporder zonder koppeling aan deze offerte; controleer eerst de bestaande facturen.");
            }
            rows.add(new Row(null, "Voorschot", sales.price(quote).totals().total(),
                    invoice == null ? null : invoice.id(), invoice == null ? null : invoice.number()));
        } else {
            var agreement = plans.find(purchase.id());
            var planned = plans.rows(purchase.id());
            if (agreement == null || snapshot.purchaseOrderId() != purchase.id()
                    || agreement.partnerCustomerId() != quote.customerId()
                    || !same(snapshot.sharePct(), purchase.partnerSharePctOrDefault())
                    || !same(snapshot.sharePct(), quote.partnerSharePct())
                    || !same(snapshot.financingPct(), agreement.financingPct())
                    || !same(snapshot.agreedAmountEur(), agreement.agreedAmountEur())
                    || snapshot.rows().size() != planned.size()
                    || planned.stream().anyMatch(row -> snapshot.rows().stream().noneMatch(frozen ->
                        Objects.equals(row.id(), frozen.scheduleRowId()) && row.label().equals(frozen.label())
                        && same(row.percentage(), frozen.percentage()) && same(row.amountEur(), frozen.amountEur())
                        && Objects.equals(row.dueDate(), frozen.dueDate())))) {
                return blocked(quote, rows, "De actuele termijnplanning verschilt van de vastgelegde offerte; controleer eerst de afspraken.");
            }
            BigDecimal plannedTotal = planned.stream().map(PartnerAdvanceSchedules.Row::amountEur)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            if (!same(plannedTotal, snapshot.agreedAmountEur())) return blocked(quote, rows,
                    "De termijnen dekken het afgesproken voorschot niet exact.");
            for (var row : planned) {
                SalesOrder invoice = row.invoiceId() == null ? null : sales.get(row.invoiceId());
                if (invoice != null && invoice.status() == QuoteStatus.GEANNULEERD) return blocked(quote, rows,
                        "Een termijn verwijst naar een geannuleerde factuur; controleer eerst de planning.");
                if (invoice != null && (!invoice.isInvoice() || !invoice.isPartnerAdvance()
                        || !Objects.equals(invoice.linkedPurchaseOrderId(), purchase.id())
                        || !Objects.equals(invoice.customerId(), quote.customerId())
                        || !same(sales.price(invoice).totals().total(), row.amountEur()))) {
                    return blocked(quote, rows, "Een gekoppelde termijnfactuur wijkt af van de afspraak.");
                }
                rows.add(new Row(row.id(), row.label(), row.amountEur(),
                        invoice == null ? null : invoice.id(), invoice == null ? null : invoice.number()));
            }
        }
        boolean complete = rows.stream().allMatch(row -> row.invoiceId() != null);
        if (quote.isArchived() && !complete) return blocked(quote, rows,
                "De offerte is gearchiveerd; er worden geen nieuwe facturen voor aangemaakt.");
        return new Preview(true, quote.isArchived() && complete, null, quoteId, quote.number(), purchase.id(), List.copyOf(rows));
    }

    /** One transaction covers every term, source links and archiving. A failed term leaves no partial migration. */
    @Transactional
    public Result convert(long quoteId) {
        SalesOrder original = sales.get(quoteId);
        if (original.linkedPurchaseOrderId() == null) throw new BusinessRuleException("De gekoppelde inkooporder ontbreekt.");
        purchases.lockForPartnerSettlement(original.linkedPurchaseOrderId());
        orders.lockById(quoteId);
        if (!Objects.equals(sales.get(quoteId).linkedPurchaseOrderId(), original.linkedPurchaseOrderId())) {
            throw new BusinessRuleException("De inkoopkoppeling is gewijzigd; controleer de offerte en probeer opnieuw.");
        }
        Preview preview = preview(quoteId);
        if (!preview.eligible()) throw new BusinessRuleException(preview.reason());
        List<Invoice> invoices = new ArrayList<>();
        for (Row row : preview.rows()) {
            SalesOrder invoice = row.invoiceId() != null ? sales.get(row.invoiceId())
                    : row.scheduleRowId() == null ? sales.createInvoiceFrom(quoteId)
                    : schedules.createInvoice(preview.purchaseOrderId(), row.scheduleRowId(), quoteId);
            invoices.add(new Invoice(invoice.id(), invoice.number()));
        }
        if (!preview.alreadyConverted()) {
            events.add(new QuoteEvent(null, quoteId, QuoteEvent.Type.GEFACTUREERD, Instant.now(),
                    actor == null ? ActorRef.SYSTEM.displayName() : actor.current().displayName(), false,
                    "Ongebruikte conceptofferte verwerkt via voorschotfacturen",
                    invoices.stream().map(invoice -> invoice.number() + " (#" + invoice.id() + ")")
                            .collect(java.util.stream.Collectors.joining(", "))));
        }
        sales.archive(quoteId);
        return new Result(quoteId, preview.purchaseOrderId(), List.copyOf(invoices), true);
    }

    private static Preview blocked(SalesOrder quote, List<Row> rows, String reason) {
        return new Preview(false, false, reason, quote.id(), quote.number(), quote.linkedPurchaseOrderId(), List.copyOf(rows));
    }

    private static boolean customerHistory(QuoteEvent event) {
        return event.byCustomer() || switch (event.type()) {
            case VERSTUURD, UITGEREIKT, BEKEKEN, GETEKEND, AFGEWEZEN, BETAALD, BESTELLING_VERZONDEN -> true;
            default -> false;
        };
    }

    private static boolean same(BigDecimal left, BigDecimal right) {
        return left == null ? right == null : right != null && left.compareTo(right) == 0;
    }
}
