package be.enrosed.sales.application;

import be.enrosed.sales.application.port.out.SalesRepositories;
import be.enrosed.sales.domain.*;
import be.enrosed.shared.BusinessDays;
import be.enrosed.shared.BusinessRuleException;
import be.enrosed.shared.DocumentText;
import be.enrosed.shared.Money;
import be.enrosed.shared.NotFoundException;
import be.enrosed.shared.security.ActorRef;
import be.enrosed.sourcing.domain.PurchaseOrderName;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Down payments on a regular sale, a whole container sold to a customer
 * included: advance invoices ("voorschotfacturen") on the quote, and finally
 * the quote's ordinary invoice as slotfactuur, which deducts every issued
 * advance with a server-owned negative line so the VAT falls on the balance.
 *
 * Nothing changes on sales_order: an advance is a plain STANDARD F-series
 * invoice without sourceQuoteId; the link lives in {@link SalesAdvanceBilling}.
 * An advance recognises its own turnover and no cost; the slotfactuur's
 * deduction lines lower its turnover, so all documents together make the sale once.
 */
@ApplicationScoped
public class SalesAdvanceBillingService {
    @Inject SalesOrderService sales;
    @Inject SalesAdvanceBilling store;
    @Inject SalesRepositories.Orders orders;
    @Inject SalesRepositories.Events events;
    @Inject IncomingPaymentService incoming;
    @Inject CustomerService customers;
    @Inject jakarta.enterprise.inject.Instance<WebOrders> webOrders;

    private static final String BRUSSELS = "Europe/Brussels";
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final Pattern TERM_DAYS = Pattern.compile("(\\d{1,3})\\s*(dagen|dag|days|day|jours|jour|tage|tagen|días|dias)",
            Pattern.CASE_INSENSITIVE);
    private static final String DEDUCTION_PREFIX = "Voorschotfactuur ";
    private static final BigDecimal HALF_CENT = new BigDecimal("0.005");

    /** A percentage of the quote total excl. VAT, or an amount excl. VAT; an optional due date. */
    public record AdvanceRequest(BigDecimal percentage, BigDecimal amountEur, LocalDate dueDate) {}
    /** One receipt on an advance, on its local day. */
    public record Receipt(LocalDate receivedOn, BigDecimal amountEur) {}
    /** The document's own role: an advance on a quote, or the final invoice deducting them. */
    public record Billing(SalesAdvanceBilling.Stage stage, long quoteId, String quoteNumber,
                          BigDecimal percentage, BigDecimal amountExclEur) {}
    /** An advance as its quote lists it; {@code creditedExclEur} is what its issued credit notes took back, excl. VAT. */
    public record AdvanceInvoice(long id, String number, QuoteStatus status, LocalDate invoiceDate, BigDecimal percentage,
                                 BigDecimal amountExclEur, BigDecimal totalInclVatEur, Instant paidAt,
                                 BigDecimal receivedEur, BigDecimal remainingEur, List<Receipt> receipts,
                                 BigDecimal creditedExclEur) {}
    /** An advance as the final invoice deducted it, with the day it was paid (the last receipt) when it was. */
    public record AdvanceDeduction(long advanceInvoiceId, String number, LocalDate invoiceDate, BigDecimal exclEur,
                                   BigDecimal vatEur, BigDecimal inclEur, LocalDate paidOn, List<Receipt> receipts) {}
    /** What a final invoice deducts, before it is saved. */
    record FinalDeductions(List<SalesExtraLine> lines, List<SalesAdvanceBilling.Deduction> deductions) {
        BigDecimal totalExclEur() {
            return Money.money(deductions.stream().map(SalesAdvanceBilling.Deduction::exclEur).reduce(BigDecimal.ZERO, BigDecimal::add));
        }
    }

    /* ============================================================ create */

    /**
     * A concept advance invoice on a regular quote: F-series number, the
     * quote's customer, country, incoterm and payment terms, no product
     * lines, freight fixed at zero and one line "Voorschot 30 % · offerte
     * OF-…". Together the live advances, net of their issued credit notes,
     * never bill more than the quote. Each percentage rounds on its own, so
     * a percentage that overshoots the rest by that rounding only (half a
     * cent per percentage advance, this one included) takes the rest:
     * 50 % + 50 % of € 6.468,01 is € 3.234,01 + € 3.234,00.
     */
    @Transactional
    public SalesOrder createAdvanceInvoice(long quoteId, AdvanceRequest request) {
        if (request == null) throw new BusinessRuleException("Geef een percentage of een bedrag voor het voorschot op");
        sales.lockDocumentForMutation(quoteId);
        SalesOrder quote = sales.get(quoteId);
        requireAdvanceSource(quote);
        PricedOrder priced = sales.price(quote);
        /* An advance on a website order is an invoice too: only on what the customer ordered or approved. */
        if (webOrders != null && webOrders.isResolvable()) webOrders.get().requireInvoiceable(quote, priced);
        BigDecimal base = Money.money(priced.totals().total());
        if (base.signum() <= 0) throw new BusinessRuleException("Offerte " + quote.number() + " heeft nog geen bedrag om een voorschot op te factureren");
        BigDecimal percentage = request.percentage();
        BigDecimal amount;
        if (percentage != null && request.amountEur() != null)
            throw new BusinessRuleException("Geef een percentage of een bedrag op, niet allebei");
        if (percentage != null) {
            if (percentage.signum() <= 0 || percentage.compareTo(Money.HUNDRED) > 0 || percentage.stripTrailingZeros().scale() > 4)
                throw new BusinessRuleException("Het voorschotpercentage ligt boven 0 en maximaal op 100, met maximaal 4 decimalen");
            amount = base.multiply(percentage).divide(Money.HUNDRED, 2, RoundingMode.HALF_UP);
        } else if (request.amountEur() != null) {
            amount = Money.money(request.amountEur());
        } else throw new BusinessRuleException("Geef een percentage of een bedrag voor het voorschot op");
        if (amount.signum() <= 0) throw new BusinessRuleException("Een voorschot is groter dan nul");
        List<SalesOrder> live = liveAdvances(quote.id());
        BigDecimal billed = billedExcl(live);
        BigDecimal room = base.subtract(billed);
        if (percentage != null && room.signum() > 0 && amount.compareTo(room) > 0
                && amount.subtract(room).compareTo(roundingSlack(quote.id(), live)) <= 0) amount = room;
        if (billed.add(amount).compareTo(base) > 0)
            throw new BusinessRuleException("Met dit voorschot zou meer gefactureerd worden dan de offerte (€ " + euro(base) + " excl. btw)");

        Customer customer = customers.get(quote.customerId());
        PurchaseOrderName container = container(quote);
        String label = percentage == null ? "Voorschot · offerte " + quote.number()
                : "Voorschot " + percentage.stripTrailingZeros().toPlainString() + " % · offerte " + quote.number();
        String notes = container == null ? null : containerPhrase(customer.language(), container.displayName());
        String internalNotes = "Voorschotfactuur op offerte " + quote.number()
                + (container == null ? "" : " · inkooporder " + reference(container)) + ".";
        LocalDate today = LocalDate.now();
        LocalDate due = request.dueDate() != null ? request.dueDate()
                : dueFromTerms(today, quote.paymentTermsOr(customer.paymentTerms()));
        SalesOrder draft = new SalesOrder(null, sales.nextInvoiceNumber(), quote.customerId(), quote.countryCode(),
                today, BusinessDays.add(today, 30), QuoteStatus.CONCEPT, quote.incoterm(), quote.paymentTerms(), notes,
                MarkupMode.PRODUCT, BigDecimal.ZERO, null, null, null, null, null, 0, null, null, null, internalNotes,
                DeliveryTermsState.VOLLEDIG, FreightState.AANGEVULD, BigDecimal.ZERO, LoadMode.PALLETS,
                PalletProfile.EURO_120X80, null, FreightPricingStrategy.FIXED, null, null, null,
                DocumentType.FACTUUR, due, null, null, null, List.of(), List.of())
                .withExtraLines(List.of(new SalesExtraLine(label, BigDecimal.ONE, amount)))
                .withSalesChannel(quote.rawSalesChannel())
                .withPurpose(SalesPurpose.STANDARD, quote.sourcePurchaseOrderId(), SalesPaymentPlan.FULL);
        sales.validateForSave(draft);
        SalesOrder created = orders.save(draft);
        store.save(new SalesAdvanceBilling.Row(created.id(), quote.id(), SalesAdvanceBilling.Stage.ADVANCE, percentage,
                amount, List.of(), Instant.now()));
        ActorRef creator = sales.currentActor();
        String what = percentage == null ? "€ " + euro(amount) + " excl. btw" : percentage.stripTrailingZeros().toPlainString() + " %";
        events.add(new QuoteEvent(null, created.id(), QuoteEvent.Type.OPGEMAAKT, Instant.now(), creator.displayName(), false,
                "Voorschotfactuur opgemaakt vanuit offerte " + quote.number() + " (" + what + ")", null));
        events.add(new QuoteEvent(null, quote.id(), QuoteEvent.Type.GEFACTUREERD, Instant.now(), creator.displayName(), false,
                "Voorschotfactuur " + created.number() + " aangemaakt (" + what + ")", null));
        sales.recordActivity(created, "Voorschotfactuur aangemaakt vanuit offerte " + quote.number() + " (" + what + ")");
        if (container != null) sales.recordPurchaseActivity(container.id(), container.number(),
                "Voorschotfactuur " + created.number() + " (" + what + ") gemaakt op offerte " + quote.number());
        sales.fireCreationPush(SalesCreationPushNotifier.Ready.invoiceFromQuoteCreated(created.id(), created.number(), quote.number(), creator));
        return created;
    }

    /** Half a cent for every live percentage advance and the new one: what their separate roundings can add up to. */
    private BigDecimal roundingSlack(long quoteId, List<SalesOrder> live) {
        var liveIds = live.stream().map(SalesOrder::id).collect(Collectors.toSet());
        long percentages = store.forQuote(quoteId).stream().filter(row -> row.stage() == SalesAdvanceBilling.Stage.ADVANCE
                && row.percentage() != null && liveIds.contains(row.salesOrderId())).count();
        return HALF_CENT.multiply(BigDecimal.valueOf(percentages + 1));
    }

    private void requireAdvanceSource(SalesOrder quote) {
        if (quote.isClaimDocument()) throw new BusinessRuleException("Een voorschotfactuur maak je vanuit een offerte");
        if (quote.purpose() != SalesPurpose.STANDARD || quote.isPartnerDeal() || sales.hasAdvanceAgreement(quote))
            throw new BusinessRuleException("Een partnercontainer factureert zijn voorschotten via het termijnplan op de inkooporder");
        if (quote.isArchived()) throw new BusinessRuleException("Haal offerte " + quote.number() + " eerst uit het archief");
        if (sales.isSplitPart(quote)) throw new BusinessRuleException("Een gesplitste levering krijgt geen voorschotfacturen");
        if (quote.status() == QuoteStatus.AFGEWEZEN || quote.status() == QuoteStatus.VERLOPEN || quote.status() == QuoteStatus.GEANNULEERD)
            throw new BusinessRuleException("Offerte " + quote.number() + " staat op " + quote.status().name().toLowerCase(java.util.Locale.ROOT)
                    + "; heropen ze eerst");
        SalesOrder invoice = liveInvoiceOf(quote.id());
        if (invoice != null)
            throw new BusinessRuleException("Offerte " + quote.number() + " heeft al factuur " + invoice.number() + "; een voorschot volgt niet meer na de slotfactuur");
        if (quote.freight() == FreightState.TE_BEPALEN) throw new BusinessRuleException("Vul eerst de vracht in");
    }

    /* ============================================================ final invoice */

    /**
     * What the slotfactuur of this quote deducts: every live advance must be
     * issued, without a concept credit note, in the quote's VAT regime; each
     * one is deducted at its total excl. VAT minus the issued credit notes on
     * it. Null when the quote has no live advance: an ordinary invoice.
     */
    FinalDeductions prepareFinal(SalesOrder quote) {
        if (quote.id() == null) return null;
        List<SalesOrder> advances = liveAdvances(quote.id());
        if (advances.isEmpty()) return null;
        PricedOrder quotePrice = sales.price(quote);
        List<SalesExtraLine> lines = new ArrayList<>();
        List<SalesAdvanceBilling.Deduction> deductions = new ArrayList<>();
        for (SalesOrder advance : advances) {
            if (advance.status() == QuoteStatus.CONCEPT)
                throw new BusinessRuleException("Reik eerst voorschotfactuur " + advance.number() + " uit of verwijder ze");
            List<SalesOrder> credits = sales.liveCreditNotesOf(advance.id());
            SalesOrder conceptCredit = credits.stream().filter(note -> note.status() == QuoteStatus.CONCEPT).findFirst().orElse(null);
            if (conceptCredit != null)
                throw new BusinessRuleException("Reik eerst creditnota " + conceptCredit.number() + " op voorschotfactuur "
                        + advance.number() + " uit of verwijder ze");
            PricedOrder advancePrice = sales.price(advance);
            /* Both are priced live from the same customer and country; the regime the advance was issued in tells a later change. */
            if (!sameVat(quotePrice, advancePrice) || !issuedIn(store.find(advance.id()), quotePrice))
                throw new BusinessRuleException("Voorschotfactuur " + advance.number() + " heeft een andere btw-regeling dan de offerte");
            var deduction = deduction(advance, advancePrice, credits);
            if (deduction.exclEur().signum() <= 0) continue;
            lines.add(deductionLine(deduction));
            deductions.add(deduction);
        }
        return deductions.isEmpty() ? null : new FinalDeductions(List.copyOf(lines), List.copyOf(deductions));
    }

    /** Called in the invoice transaction right after the slotfactuur was saved. */
    void saveFinal(SalesOrder created, SalesOrder quote, FinalDeductions prepared) {
        store.save(new SalesAdvanceBilling.Row(created.id(), quote.id(), SalesAdvanceBilling.Stage.FINAL, null,
                prepared.totalExclEur(), prepared.deductions(), Instant.now()));
        String summary = "Slotfactuur · voorschotten verrekend € " + euro(prepared.totalExclEur());
        events.add(new QuoteEvent(null, created.id(), QuoteEvent.Type.OPGEMAAKT, Instant.now(), sales.currentActor().displayName(),
                false, summary, prepared.deductions().stream().map(SalesAdvanceBilling.Deduction::number).collect(Collectors.joining(", "))));
        sales.recordActivity(created, summary);
    }

    private SalesAdvanceBilling.Deduction deduction(SalesOrder advance, PricedOrder advancePrice, List<SalesOrder> credits) {
        BigDecimal excl = Money.money(advancePrice.totals().total());
        BigDecimal vat = Money.money(Money.nz(advancePrice.totals().vatAmount()));
        BigDecimal incl = Money.money(advancePrice.totals().totalInclVat());
        for (SalesOrder note : credits) {
            if (!PartnerFinancingService.issued(note)) continue;
            PricedOrder notePrice = sales.price(note);
            excl = excl.subtract(Money.money(notePrice.totals().total()));
            vat = vat.subtract(Money.money(Money.nz(notePrice.totals().vatAmount())));
            incl = incl.subtract(Money.money(notePrice.totals().totalInclVat()));
        }
        return new SalesAdvanceBilling.Deduction(advance.id(), advance.number(), advance.orderDate(), excl, vat, incl);
    }

    private static SalesExtraLine deductionLine(SalesAdvanceBilling.Deduction deduction) {
        return new SalesExtraLine(DEDUCTION_PREFIX + deduction.number() + " van "
                + (deduction.invoiceDate() == null ? "-" : DAY.format(deduction.invoiceDate())), BigDecimal.ONE, deduction.exclEur().negate());
    }

    private static boolean sameVat(PricedOrder quote, PricedOrder advance) {
        return quote.totals().vatTreatment() == advance.totals().vatTreatment()
                && Money.nz(quote.totals().vatRatePct()).compareTo(Money.nz(advance.totals().vatRatePct())) == 0;
    }

    /** Whether an advance was issued in this document's VAT regime; an advance without a recorded regime passes. */
    private static boolean issuedIn(SalesAdvanceBilling.Row advance, PricedOrder document) {
        if (advance == null || advance.vatTreatment() == null) return true;
        return advance.vatTreatment() == document.totals().vatTreatment()
                && Money.nz(advance.vatRatePct()).compareTo(Money.nz(document.totals().vatRatePct())) == 0;
    }

    /**
     * The server owns an advance's amount line and a slotfactuur's deduction
     * lines: an edit keeps the advance as it was made and re-applies the
     * deductions from the snapshot, whatever the client sent.
     */
    SalesOrder withServerLines(SalesOrder current, SalesOrder updated) {
        var row = current.id() == null ? null : store.find(current.id());
        if (row == null) return updated;
        if (row.stage() == SalesAdvanceBilling.Stage.ADVANCE)
            return updated.withLinesAndPallets(List.of(), List.of()).withExtraLines(current.extraLines());
        List<SalesExtraLine> deductions = row.deductions().stream().map(SalesAdvanceBillingService::deductionLine).toList();
        var owned = deductions.stream().map(SalesExtraLine::description).collect(Collectors.toSet());
        List<SalesExtraLine> extras = new ArrayList<>(updated.extraLines().stream()
                .filter(line -> line == null || !owned.contains(line.description() == null ? null : line.description().strip())).toList());
        extras.addAll(deductions);
        return updated.withExtraLines(List.copyOf(extras));
    }

    /**
     * Before a slotfactuur leaves concept: its balance is not negative, and
     * the advances it deducts still stand as they were deducted.
     */
    void requireIssuable(SalesOrder invoice) {
        if (!invoice.isInvoice() || invoice.status() != QuoteStatus.CONCEPT || invoice.id() == null) return;
        var row = store.find(invoice.id());
        if (row == null || row.stage() != SalesAdvanceBilling.Stage.FINAL) return;
        PricedOrder priced = sales.price(invoice);
        if (priced.totals().total().signum() < 0)
            throw new BusinessRuleException("De slotfactuur is lager dan de voorschotten; maak een creditnota op een voorschotfactuur");
        for (var deducted : row.deductions()) {
            if (!issuedIn(store.find(deducted.advanceInvoiceId()), priced))
                throw new BusinessRuleException("Voorschotfactuur " + deducted.number() + " heeft een andere btw-regeling dan slotfactuur "
                        + invoice.number());
            SalesOrder advance = orders.findById(deducted.advanceInvoiceId()).orElse(null);
            boolean stale = advance == null || !PartnerFinancingService.issued(advance);
            if (!stale) {
                List<SalesOrder> credits = sales.liveCreditNotesOf(advance.id());
                stale = credits.stream().anyMatch(note -> note.status() == QuoteStatus.CONCEPT)
                        || deduction(advance, sales.price(advance), credits).exclEur().compareTo(deducted.exclEur()) != 0;
            }
            if (stale)
                throw new BusinessRuleException("Voorschotfactuur " + deducted.number() + " is gewijzigd sinds slotfactuur "
                        + invoice.number() + " werd opgemaakt; verwijder het concept en maak de slotfactuur opnieuw vanuit de offerte");
        }
    }

    /**
     * Right after a document left concept. An advance keeps the VAT regime it
     * was issued in, which the slotfactuur is held to. A slotfactuur the
     * advances covered in full claims nothing and is paid on issue: it never
     * turns overdue, and a receipt on zero is refused anyway.
     */
    SalesOrder afterIssue(SalesOrder issued) {
        if (issued.id() == null || issued.isCreditNote() || issued.status() == QuoteStatus.CONCEPT) return issued;
        var row = store.find(issued.id());
        if (row == null) return issued;
        PricedOrder priced = sales.price(issued);
        if (row.stage() == SalesAdvanceBilling.Stage.ADVANCE) {
            store.save(row.issuedIn(priced.totals().vatTreatment(), priced.totals().vatRatePct()));
            return issued;
        }
        if (issued.status() == QuoteStatus.BETAALD || Money.money(priced.totals().totalInclVat()).signum() != 0) return issued;
        Instant now = Instant.now();
        SalesOrder paid = orders.save(issued.withPaymentState(QuoteStatus.BETAALD, now));
        events.add(new QuoteEvent(null, issued.id(), QuoteEvent.Type.BETAALD, now, sales.currentActor().displayName(), false,
                "Slotfactuur volledig verrekend met de voorschotfacturen", null));
        return paid;
    }

    /** What a slotfactuur deducted incl. VAT, zero on any other document: its credit notes may reach the sale itself. */
    BigDecimal deductedInclEur(SalesOrder invoice) {
        var row = invoice.id() == null ? null : store.find(invoice.id());
        if (row == null || row.stage() != SalesAdvanceBilling.Stage.FINAL) return BigDecimal.ZERO;
        return Money.money(row.deductions().stream().map(SalesAdvanceBilling.Deduction::inclEur).filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add));
    }

    /* ============================================================ guards */

    /**
     * A quote with a live advance keeps existing: delete, reopen and cancel
     * wait for the advance. An issued advance its credit notes took back in
     * full no longer holds the quote, as the slotfactuur skips it too.
     */
    void requireQuoteWithoutAdvances(SalesOrder quote) {
        if (quote.isClaimDocument() || quote.id() == null) return;
        List<SalesOrder> live = standingAdvances(quote.id());
        if (!live.isEmpty())
            throw new BusinessRuleException("Deze offerte heeft voorschotfactuur " + live.getFirst().number() + "; verwijder of annuleer die eerst");
    }

    /** An advance deducted on an issued slotfactuur stays as it was: correct the slotfactuur instead. */
    void requireAdvanceNotSettled(SalesOrder advance, String hint) {
        if (!advance.isInvoice() || advance.id() == null) return;
        var row = store.find(advance.id());
        if (row == null || row.stage() != SalesAdvanceBilling.Stage.ADVANCE) return;
        for (var other : store.forQuote(row.quoteId())) {
            if (other.stage() != SalesAdvanceBilling.Stage.FINAL
                    || other.deductions().stream().noneMatch(deduction -> deduction.advanceInvoiceId() == advance.id())) continue;
            SalesOrder settlement = orders.findById(other.salesOrderId()).orElse(null);
            if (settlement != null && PartnerFinancingService.issued(settlement))
                throw new BusinessRuleException("Voorschotfactuur " + advance.number() + " is verrekend op slotfactuur "
                        + settlement.number() + (hint == null ? "" : hint));
        }
    }

    /** The customer and country of a quote with advances, and of an advance itself, follow the first advance. */
    void requireParties(SalesOrder before, SalesOrder changes) {
        if (before.id() == null || changes == null) return;
        boolean moved = !Objects.equals(before.customerId(), changes.customerId()) || !Objects.equals(before.countryCode(), changes.countryCode());
        if (!moved) return;
        var row = store.find(before.id());
        if (row != null && row.stage() == SalesAdvanceBilling.Stage.ADVANCE)
            throw new BusinessRuleException("De klant en het land van een voorschotfactuur volgen de offerte");
        if (!before.isClaimDocument()) {
            List<SalesOrder> live = standingAdvances(before.id());
            if (!live.isEmpty())
                throw new BusinessRuleException("De klant en het land van offerte " + before.number() + " liggen vast zolang voorschotfactuur "
                        + live.getFirst().number() + " bestaat");
        }
    }

    /** Whether this document is an advance invoice on a regular quote. */
    boolean isAdvance(SalesOrder order) {
        var row = order.id() == null ? null : store.find(order.id());
        return row != null && row.stage() == SalesAdvanceBilling.Stage.ADVANCE;
    }

    /** Whether this document is an advance or a slotfactuur of a regular quote. */
    boolean hasBilling(SalesOrder order) {
        return order.id() != null && store.find(order.id()) != null;
    }

    boolean hasLiveAdvances(SalesOrder quote) {
        return quote.id() != null && !quote.isClaimDocument() && !liveAdvances(quote.id()).isEmpty();
    }

    /** A concept slotfactuur that is deleted gives its advances back; they are deducted again on the next one. */
    void onDelete(SalesOrder order) {
        var row = order.id() == null ? null : store.find(order.id());
        if (row != null && row.stage() == SalesAdvanceBilling.Stage.FINAL) store.delete(order.id());
    }

    /** Why a trashed advance or slotfactuur cannot come back; null when it can. */
    public String restoreReason(SalesOrder order) {
        if (order == null || !order.isInvoice()) return null;
        if (order.sourceQuoteId() != null && !liveAdvances(order.sourceQuoteId()).isEmpty())
            return "Maak de slotfactuur opnieuw vanuit de offerte; de voorschotfacturen worden dan opnieuw verrekend.";
        var row = order.id() == null ? null : store.find(order.id());
        if (row == null || row.stage() != SalesAdvanceBilling.Stage.ADVANCE) return null;
        SalesOrder quote = orders.findById(row.quoteId()).orElse(null);
        if (quote == null) return "Herstel eerst de offerte van dit voorschot.";
        SalesOrder invoice = liveInvoiceOf(quote.id());
        if (invoice != null) return "Offerte " + quote.number() + " heeft intussen factuur " + invoice.number() + "; dit voorschot kan niet terug.";
        try {
            BigDecimal base = Money.money(sales.price(quote).totals().total());
            BigDecimal billed = billedExcl(liveAdvances(quote.id())).add(Money.money(sales.price(order).totals().total()));
            if (billed.compareTo(base) > 0) return "Met dit voorschot zou meer gefactureerd worden dan offerte " + quote.number() + ".";
        } catch (BusinessRuleException | NotFoundException incomplete) {
            return "Het voorschot kan niet meer tegen de offerte gecontroleerd worden. Maak een nieuw voorschot.";
        }
        return null;
    }

    /* ============================================================ views */

    /** What a document prints about its advance billing: its role and, on a slotfactuur, the deducted advances. */
    public record Presentation(Billing billing, List<AdvanceDeduction> deductions) {}

    /** Null for any document that is neither an advance invoice nor a slotfactuur. */
    public Presentation presentation(SalesOrder order) {
        if (order == null || order.id() == null || store.find(order.id()) == null) return null;
        Views views = views(List.of(order));
        return new Presentation(views.billing(order), views.deductions(order));
    }

    /** Answers the view fields of many documents from one read of the billing table. */
    public Views views(Collection<SalesOrder> known) {
        return new Views(store.all(), known);
    }

    public final class Views {
        private final Map<Long, SalesAdvanceBilling.Row> byOrder = new HashMap<>();
        private final Map<Long, List<SalesAdvanceBilling.Row>> byQuote = new HashMap<>();
        private final Map<Long, SalesOrder> documents;

        Views(List<SalesAdvanceBilling.Row> rows, Collection<SalesOrder> known) {
            for (var row : rows) {
                byOrder.put(row.salesOrderId(), row);
                byQuote.computeIfAbsent(row.quoteId(), key -> new ArrayList<>()).add(row);
            }
            documents = known == null ? new HashMap<>() : known.stream().filter(order -> order.id() != null)
                    .collect(Collectors.toMap(SalesOrder::id, Function.identity(), (left, right) -> left, HashMap::new));
        }

        /** The document's own role, or null for any other document. */
        public Billing billing(SalesOrder order) {
            var row = order.id() == null ? null : byOrder.get(order.id());
            if (row == null) return null;
            SalesOrder quote = document(row.quoteId());
            return new Billing(row.stage(), row.quoteId(), quote == null ? null : quote.number(), row.percentage(), row.amountExclEur());
        }

        /** On a regular quote its live advances, oldest first (empty when none); null on any other document. */
        public List<AdvanceInvoice> advanceInvoices(SalesOrder order) {
            if (order.id() == null || order.isClaimDocument() || order.purpose() != SalesPurpose.STANDARD || order.isPartnerDeal()) return null;
            List<AdvanceInvoice> result = new ArrayList<>();
            for (var row : byQuote.getOrDefault(order.id(), List.of())) {
                if (row.stage() != SalesAdvanceBilling.Stage.ADVANCE) continue;
                SalesOrder advance = document(row.salesOrderId());
                if (advance == null || !PartnerFinancingService.live(advance)) continue;
                PricedOrder priced = sales.price(advance);
                SalesPaymentSummary paid = incoming.summary(advance, priced);
                BigDecimal excl = Money.money(priced.totals().total());
                BigDecimal credited = excl.subtract(deduction(advance, priced, sales.liveCreditNotesOf(advance.id())).exclEur());
                result.add(new AdvanceInvoice(advance.id(), advance.number(), advance.status(), advance.orderDate(), row.percentage(),
                        excl, Money.money(priced.totals().totalInclVat()), advance.paidAt(),
                        paid.receivedEur(), paid.remainingEur(), receipts(settlements(advance, paid.payments())), Money.money(credited)));
            }
            return List.copyOf(result);
        }

        /** On a slotfactuur the advances it deducted, with how they were paid; null on any other document. */
        public List<AdvanceDeduction> deductions(SalesOrder order) {
            var row = order.id() == null ? null : byOrder.get(order.id());
            if (row == null || row.stage() != SalesAdvanceBilling.Stage.FINAL) return null;
            return row.deductions().stream().map(deducted -> {
                SalesOrder advance = document(deducted.advanceInvoiceId());
                List<SalesPayment> settled = advance == null ? List.of() : settlements(advance, incoming.forOrder(advance.id()));
                return new AdvanceDeduction(deducted.advanceInvoiceId(), deducted.number(), deducted.invoiceDate(),
                        deducted.exclEur(), deducted.vatEur(), deducted.inclEur(), paidOn(settled, deducted.inclEur()),
                        receipts(settled));
            }).toList();
        }

        /**
         * What paid an advance: receipts, refunds and offsets from other
         * documents. An offset from a credit note on this very advance is that
         * credit, already off the deducted amount, never a payment.
         */
        private List<SalesPayment> settlements(SalesOrder advance, List<SalesPayment> rows) {
            return rows.stream().filter(payment -> !payment.isOffset() || !creditsOn(advance, payment.offsetOrderId())).toList();
        }

        private boolean creditsOn(SalesOrder advance, Long otherId) {
            SalesOrder other = otherId == null ? null : document(otherId);
            return other != null && other.isCreditNote() && Objects.equals(other.creditedInvoiceId(), advance.id());
        }

        private SalesOrder document(long id) {
            return documents.computeIfAbsent(id, key -> orders.findById(key).orElse(null));
        }
    }

    /** Every receipt and refund on its own local day, oldest first. */
    static List<Receipt> receipts(List<SalesPayment> settled) {
        return oldestFirst(settled).stream().map(payment -> new Receipt(localDay(payment), Money.money(payment.amountEur()))).toList();
    }

    /**
     * The day the payments first covered what the slotfactuur deducted incl.
     * VAT (the advance net of its issued credit notes) and still do: the last
     * receipt it took. Null while part of it is open.
     */
    static LocalDate paidOn(List<SalesPayment> settled, BigDecimal deductedInclEur) {
        if (deductedInclEur == null || deductedInclEur.signum() <= 0) return null;
        BigDecimal cumulative = BigDecimal.ZERO;
        SalesPayment completed = null;
        for (SalesPayment payment : oldestFirst(settled)) {
            cumulative = cumulative.add(payment.amountEur());
            if (cumulative.compareTo(deductedInclEur) < 0) completed = null;
            else if (completed == null) completed = payment;
        }
        return completed == null ? null : localDay(completed);
    }

    private static List<SalesPayment> oldestFirst(List<SalesPayment> rows) {
        return rows.stream().sorted(Comparator.comparing(SalesPayment::receivedAt).thenComparing(SalesPayment::id,
                Comparator.nullsLast(Comparator.naturalOrder()))).toList();
    }

    private static LocalDate localDay(SalesPayment payment) {
        String zone = payment.timeZone() == null || payment.timeZone().isBlank() ? BRUSSELS : payment.timeZone();
        return payment.receivedAt().atZone(ZoneId.of(zone)).toLocalDate();
    }

    /* ============================================================ helpers */

    /** What these advances bill excl. VAT: each one's total minus its issued credit notes. */
    private BigDecimal billedExcl(List<SalesOrder> advances) {
        return Money.money(advances.stream().map(advance -> deduction(advance, sales.price(advance), sales.liveCreditNotesOf(advance.id()))
                .exclEur().max(BigDecimal.ZERO)).reduce(BigDecimal.ZERO, BigDecimal::add));
    }

    /** The live advances that still bill something: an issued one credited in full, with no concept credit note waiting, does not. */
    private List<SalesOrder> standingAdvances(long quoteId) {
        return liveAdvances(quoteId).stream().filter(advance -> !creditedInFull(advance)).toList();
    }

    private boolean creditedInFull(SalesOrder advance) {
        if (!PartnerFinancingService.issued(advance)) return false;
        List<SalesOrder> credits = sales.liveCreditNotesOf(advance.id());
        if (credits.isEmpty() || credits.stream().anyMatch(note -> note.status() == QuoteStatus.CONCEPT)) return false;
        return deduction(advance, sales.price(advance), credits).exclEur().signum() <= 0;
    }

    /** The live advance invoices of a quote, oldest first. */
    List<SalesOrder> liveAdvances(long quoteId) {
        return store.forQuote(quoteId).stream().filter(row -> row.stage() == SalesAdvanceBilling.Stage.ADVANCE)
                .map(row -> orders.findById(row.salesOrderId()).orElse(null))
                .filter(Objects::nonNull).filter(PartnerFinancingService::live).toList();
    }

    /** The quote's own invoice (the slotfactuur when it has advances), cancelled ones aside. */
    private SalesOrder liveInvoiceOf(long quoteId) {
        return orders.findAll().stream().filter(order -> order.isInvoice() && Objects.equals(order.sourceQuoteId(), quoteId)
                && order.status() != QuoteStatus.GEANNULEERD).findFirst().orElse(null);
    }

    private PurchaseOrderName container(SalesOrder quote) {
        Long id = quote.sourcePurchaseOrderId();
        return id == null ? null : sales.containerNames(List.of(id)).get(id);
    }

    private static String reference(PurchaseOrderName container) {
        String number = container.number() == null || container.number().isBlank() ? "#" + container.id() : container.number().strip();
        String name = container.displayName();
        return name.equals(number) ? number : number + " (" + name + ")";
    }

    /** "conteneur PO-2026-011" in the customer's language, or just our name when it already says container. */
    static String containerPhrase(be.enrosed.shared.Language language, String name) {
        String clean = name == null ? "" : name.strip();
        if (clean.regionMatches(true, 0, "container", 0, "container".length())) return clean;
        String pattern = DocumentText.of(language == null ? be.enrosed.shared.Language.NL : language).get("partnerContainer");
        return pattern == null ? clean : pattern.formatted(clean).strip();
    }

    /** "30 dagen netto" falls due thirty days on; anything else takes the usual thirty business days. */
    static LocalDate dueFromTerms(LocalDate today, String terms) {
        if (terms != null) {
            Matcher days = TERM_DAYS.matcher(terms);
            if (days.find()) return today.plusDays(Integer.parseInt(days.group(1)));
        }
        return BusinessDays.add(today, 30);
    }

    private static String euro(BigDecimal amount) {
        return String.format(java.util.Locale.forLanguageTag("nl-BE"), "%,.2f", amount.setScale(2, RoundingMode.HALF_UP));
    }
}
