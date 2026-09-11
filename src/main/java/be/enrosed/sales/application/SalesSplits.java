package be.enrosed.sales.application;

import be.enrosed.catalog.application.CatalogMutationLock;
import be.enrosed.catalog.application.ProductService;
import be.enrosed.catalog.domain.Product;
import be.enrosed.sales.adapter.out.persistence.*;
import be.enrosed.sales.application.port.out.SalesRepositories;
import be.enrosed.sales.domain.*;
import be.enrosed.shared.BusinessRuleException;
import be.enrosed.shared.Money;
import be.enrosed.shared.audit.ActivityLogService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Two delivery portions of one unused draft, with a durable commercial allocation. */
@ApplicationScoped
public class SalesSplits {
    public record Choice(Long lineId, int laterQuantity, Boolean unavailable) {
        public Choice(Long lineId, int laterQuantity) { this(lineId, laterQuantity, null); }
    }
    public record Request(List<Choice> lines, String deliveryWeek, BigDecimal currentFreightEur,
                          BigDecimal laterFreightEur, BigDecimal currentExtraDiscountPct, BigDecimal laterExtraDiscountPct,
                          String previewToken, String requestId) {
        public Request(List<Choice> lines, String deliveryWeek, String previewToken, String requestId) {
            this(lines, deliveryWeek, null, null, null, null, previewToken, requestId);
        }
    }
    public record LineChoice(long lineId, long productId, String description, int quantity,
                             Integer piecesPerCarton, Integer stockQuantity, boolean inventoryKnown,
                             boolean unavailable, Integer requestedQuantity) {}
    public record Eligibility(boolean allowed, String reason, long sourceId, String sourceNumber,
                              List<LineChoice> lines, String existingGroupId) {}
    public record PartPreview(int quantity, BigDecimal totalExclVatEur, BigDecimal vatEur, BigDecimal totalInclVatEur,
                              BigDecimal freightEur, BigDecimal handlingEur, BigDecimal extraLinesEur, BigDecimal goodsEur,
                              int unavailableQuantity) {}
    public record Preview(String previewToken, long sourceId, PartPreview original, PartPreview current,
                          PartPreview later, List<String> warnings, BigDecimal deltaExclVatEur, BigDecimal deltaInclVatEur,
                          int excludedQuantity) {}
    public record Result(String groupId, SalesOrder current, SalesOrder later) {}
    public enum Status { PLANNED, WAITING_FOR_STOCK, SHIPPED }
    public record Fulfillment(String groupId, long rootOrderId, int part, Status status,
                              Long siblingId, String siblingNumber, boolean financialsLocked) {}
    private record Selection(List<Choice> lines, String deliveryWeek, BigDecimal currentFreightEur,
                             BigDecimal laterFreightEur, BigDecimal currentExtraDiscountPct, BigDecimal laterExtraDiscountPct) {}
    private record Plan(SalesOrder source, SalesOrder current, SalesOrder later, SalesSplitPricing firstTerms,
                        SalesSplitPricing laterTerms, Preview preview, Selection selection) {}
    @Inject SalesOrderService sales;
    @Inject ProductService products;
    @Inject SalesRepositories.Orders orders;
    @Inject SalesRepositories.Events events;
    @Inject SalesRepositories.Revisions revisions;
    @Inject IncomingPaymentService incoming;
    @Inject EntityManager entities;
    @Inject ObjectMapper json;
    @Inject CatalogMutationLock catalogLock;
    @Inject ActivityLogService activity;

    public Eligibility eligibility(long id) {
        SalesOrder source = sales.get(id);
        String reason = null;
        try { requireEligible(source); } catch (BusinessRuleException blocked) { reason = blocked.getMessage(); }
        Map<Long, Product> catalog = catalog();
        List<LineChoice> lines = source.lines().stream().filter(line -> line.id() != null).map(line -> {
            Product p = catalog.get(line.productId());
            Integer pack = p == null || p.carton() == null ? null : p.carton().piecesPerCarton();
            return new LineChoice(line.id(), line.productId(), p == null ? "Product " + line.productId() : p.nameWithColour(),
                    line.quantity(), pack, p != null && p.inventoryKnown() ? p.stockQuantity() : null,
                    p != null && p.inventoryKnown(), line.isUnavailable(), line.requestedQuantity());
        }).toList();
        var part = part(id);
        return new Eligibility(reason == null, reason, id, source.number(), lines, part == null ? null : part.group.id);
    }

    public Preview preview(long id, Request request) { return plan(sales.get(id), request).preview(); }

    @Transactional
    public Result split(long id, Request request) {
        String requestId;
        try { requestId = UUID.fromString(request == null ? "" : request.requestId()).toString(); }
        catch (RuntimeException invalid) { throw new BusinessRuleException("De verdeelopdracht mist een geldige aanvraagcode; open de verdeling opnieuw"); }
        // Catalog mutations/trash use the same first lock; source edits and issue use the document lock.
        catalogLock.acquire();
        sales.lockDocumentForMutation(id);
        String requestHash = hash(List.of(id, cleanSelection(request), Objects.toString(request.previewToken(), "")));
        var existing = entities.createQuery("from SalesSplitGroupEntity g where g.requestId=:request", SalesSplitGroupEntity.class)
                .setParameter("request", requestId).getResultStream().findFirst().orElse(null);
        if (existing != null) {
            if (existing.rootOrderId != id || !existing.requestHash.equals(requestHash))
                throw new BusinessRuleException("Deze aanvraagcode hoort bij een andere verdeling; laad de bestelling opnieuw");
            return new Result(existing.id, sales.get(existing.rootOrderId), sales.get(existing.laterOrderId));
        }
        Plan plan = plan(sales.get(id), request);
        if (request.previewToken() == null || !request.previewToken().equals(plan.preview().previewToken()))
            throw new BusinessRuleException("De bestelling, voorraad of prijzen zijn intussen gewijzigd; bekijk eerst een nieuwe verdeling");
        SalesOrder first = orders.save(plan.current());
        SalesOrder later = orders.save(sales.numberSplitDraft(plan.later()));
        var group = new SalesSplitGroupEntity();
        group.id = UUID.randomUUID().toString(); group.rootOrderId = first.id(); group.laterOrderId = later.id();
        group.requestId = requestId; group.requestHash = requestHash; group.createdAt = Instant.now();
        entities.persist(group);
        savePart(first, group, 1, false, plan.firstTerms());
        savePart(later, group, 2, true, plan.laterTerms());
        entities.flush();
        sales.copyCustomerRequest(plan.source(), later);
        for (SalesOrder document : List.of(first, later)) {
            events.add(new QuoteEvent(null, document.id(), QuoteEvent.Type.OPGEMAAKT, group.createdAt, null, false,
                    "Bestelling opgesplitst: " + first.number() + " en " + later.number(),
                    "Deel 1: " + plan.preview().current().quantity() + " stuks; deel 2: " + plan.preview().later().quantity()
                            + " stuks. Deel 2 wacht op voorraad. Er is niets verstuurd of afgeboekt."));
            activity.record(ActivityLogService.ACTION_UPDATED, "SALES_ORDER", document.id().toString(), document.number(),
                    "Bestelling verdeeld in twee leveringen");
        }
        return new Result(group.id, first, later);
    }

    public SalesSplitPricing pricing(SalesOrder order) {
        var part = order.id() == null ? null : part(order.id());
        return part == null ? null : read(part.pricingJson);
    }

    public Integer allocatedQuantity(SalesOrder order, Long productId) {
        var terms = pricing(order);
        if (terms != null) {
            var reference = terms.unavailableReferences().stream().filter(row -> Objects.equals(productId, row.line().productId())).findFirst();
            if (reference.isPresent()) return reference.get().line().quantity();
        }
        return terms == null ? null : terms.lines().stream().filter(line -> Objects.equals(productId, line.productId()))
                .map(SalesSplitPricing.Line::quantity).findFirst().orElse(null);
    }

    static SalesSplitPricing activePricing(SalesOrder order, SalesSplitPricing terms) {
        if (terms == null) return null;
        Set<Long> parked = order.lines().stream().filter(SalesOrderLine::isUnavailable).map(SalesOrderLine::productId).collect(Collectors.toSet());
        Map<Long, SalesSplitPricing.UnavailableReference> references = terms.unavailableReferences().stream()
                .collect(Collectors.toMap(row -> row.line().productId(), Function.identity()));
        var lines = terms.lines().stream().map(line -> parked.contains(line.productId())
                ? new SalesSplitPricing.Line(line.productId(), 0, line.unitPrice(), line.tierPercent(), line.manualPercent(),
                    Money.money(null), Money.money(null), Money.money(null), line.landedUnitCost(), Money.money(null))
                : references.containsKey(line.productId()) ? references.get(line.productId()).line() : line).toList();
        var restoredReferences = terms.unavailableReferences().stream().filter(row -> !parked.contains(row.line().productId())).toList();
        BigDecimal restoredDiscount = restoredReferences.stream().map(SalesSplitPricing.UnavailableReference::orderDiscountAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal restoredNet = restoredReferences.stream().map(row -> row.line().net()).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal activeNet = lines.stream().map(SalesSplitPricing.Line::net).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal orderDiscount = activeAmount(terms.orderDiscountAmount(), terms, parked).add(restoredDiscount);
        BigDecimal extraPct = Money.nz(order.extraDiscountPct());
        BigDecimal restoredExtra = Money.money(Money.percentOf(restoredNet.subtract(restoredDiscount), extraPct));
        BigDecimal extra = same(extraPct, terms.extraDiscountPercent()) ? activeAmount(terms.extraDiscountAmount(), terms, parked).add(restoredExtra)
                : Money.money(Money.percentOf(activeNet.subtract(orderDiscount), extraPct));
        BigDecimal originalResidual = terms.goodsTotal().subtract(subtotal(terms)).add(terms.orderDiscountAmount()).add(terms.extraDiscountAmount());
        BigDecimal residual = same(extraPct, terms.extraDiscountPercent()) ? activeAmount(originalResidual, terms, parked) : Money.money(null);
        return new SalesSplitPricing(lines, terms.orderTierPercent(), orderDiscount, extraPct, extra,
                Money.money(order.manualFreightEur()), terms.handling(), terms.sourceMeetsMinimum(), terms.vatRatePct(), terms.vatTreatment(),
                activeNet.subtract(orderDiscount).subtract(extra).add(residual));
    }

    /** Allocate fixed aggregates once by original line value; no new tiers, and a full restore is exact. */
    private static BigDecimal activeAmount(BigDecimal amount, SalesSplitPricing terms, Set<Long> parked) {
        if (parked.isEmpty()) return amount;
        BigDecimal whole = subtotal(terms), cumulative = BigDecimal.ZERO, previous = Money.money(null), active = Money.money(null);
        for (var line : terms.lines()) {
            cumulative = cumulative.add(line.net());
            BigDecimal allocated = proportional(amount, cumulative, whole);
            if (!parked.contains(line.productId())) active = active.add(allocated.subtract(previous));
            previous = allocated;
        }
        return active;
    }

    public Fulfillment fulfillment(SalesOrder order) {
        var own = order.id() == null ? null : part(order.id());
        if (own == null) return null;
        List<SalesSplitPartEntity> family = entities.createQuery("from SalesSplitPartEntity p where p.group.id=:group", SalesSplitPartEntity.class)
                .setParameter("group", own.group.id).getResultList();
        SalesOrder sibling = family.stream().filter(p -> p.partNumber != own.partNumber)
                .map(p -> orders.findById(p.salesOrderId).orElse(null)).filter(Objects::nonNull)
                .filter(o -> o.status() != QuoteStatus.GEANNULEERD)
                .sorted(Comparator.<SalesOrder, Boolean>comparing(SalesOrder::isInvoice).reversed()
                        .thenComparing(SalesOrder::id, Comparator.reverseOrder())).findFirst().orElse(null);
        return new Fulfillment(own.group.id, own.group.rootOrderId, own.partNumber,
                order.goodsShippedAt() != null ? Status.SHIPPED : own.waitingForStock ? Status.WAITING_FOR_STOCK : Status.PLANNED,
                sibling == null ? null : sibling.id(), sibling == null ? null : sibling.number(), true);
    }

    @Transactional
    public SalesOrder ready(long id) {
        sales.lockDocumentForMutation(id);
        SalesOrder order = sales.get(id);
        var part = part(id);
        if (part == null) throw new BusinessRuleException("Deze bestelling heeft geen uitgestelde levering");
        if (order.archivedAt() != null || order.goodsShippedAt() != null || order.status() == QuoteStatus.GEANNULEERD
                || order.status() == QuoteStatus.AFGEWEZEN || order.status() == QuoteStatus.VERLOPEN
                || !order.isInvoice() && orders.existsBySourceQuoteId(order.id()))
            throw new BusinessRuleException("Deze levering kan niet meer worden vrijgegeven");
        Map<Long, Integer> needed = new HashMap<>();
        order.lines().stream().filter(line -> !line.isUnavailable() && line.quantity() > 0)
                .forEach(line -> needed.merge(line.productId(), line.quantity(), Math::addExact));
        if (needed.isEmpty()) throw new BusinessRuleException("Deze levering bevat geen actieve producten om vrij te geven");
        if (!part.waitingForStock) return order;
        Map<Long, Product> catalog = catalog();
        for (var line : needed.entrySet()) {
            Product product = catalog.get(line.getKey());
            if (product == null || !product.inventoryKnown() || product.stockQuantity() < line.getValue())
                throw new BusinessRuleException("Er is nog onvoldoende bevestigde voorraad om deze levering vrij te geven");
        }
        part.waitingForStock = false;
        activity.record(ActivityLogService.ACTION_UPDATED, "SALES_ORDER", order.id().toString(), order.number(), "Uitgestelde levering vrijgegeven voor planning");
        return order;
    }

    void requireShippable(SalesOrder order) {
        var part = order.id() == null ? null : part(order.id());
        if (part != null && part.waitingForStock)
            throw new BusinessRuleException("Deze levering wacht op voorraad; geef ze eerst vrij voor planning");
    }

    void copyToInvoice(SalesOrder source, SalesOrder invoice) {
        var sourcePart = part(source.id());
        if (sourcePart != null) savePart(invoice, sourcePart.group, sourcePart.partNumber, sourcePart.waitingForStock, pricing(source));
    }

    void requireUpdate(SalesOrder before, SalesOrder after) {
        if (part(before.id()) == null) return;
        boolean changed = !Objects.equals(before.customerId(), after.customerId())
                || !Objects.equals(before.countryCode(), after.countryCode())
                || before.markupMode() != after.markupMode() || !same(before.orderMarkupPct(), after.orderMarkupPct())
                || !sameExtras(before.extraLines(), after.extraLines())
                || before.lines().size() != (after.lines() == null ? -1 : after.lines().size())
                || (after.freightPricingStrategyOrNull() != null && after.freightPricingStrategy() != FreightPricingStrategy.FIXED)
                || (after.freightOrNull() != null && after.freight() != before.freight())
                || after.partnerPurchaseOrderId() != null
                || (after.loadModeOrNull() != null && after.loadMode() != before.loadMode())
                || (after.palletProfileOrNull() != null && after.palletProfile() != before.palletProfile())
                || !same(before.maxPalletHeightCm(), after.maxPalletHeightCm())
                || !Objects.equals(SalesLineAvailability.prunePallets(before.pallets(), after.lines().stream()
                        .filter(SalesOrderLine::isUnavailable).map(SalesOrderLine::productId).collect(Collectors.toSet())), after.pallets());
        if (!changed) for (int i = 0; i < before.lines().size(); i++) {
            var a = before.lines().get(i); var b = after.lines().get(i);
            Integer maximum = allocatedQuantity(before, a.productId());
            if (b == null || !Objects.equals(a.productId(), b.productId()) || (b.isUnavailable() ? b.quantity() != 0
                    : maximum == null || b.quantity() != maximum)
                    || !same(a.unitPriceEur(), b.unitPriceEur()) || !same(a.manualDiscountPct(), b.manualDiscountPct())
                    || !same(a.unitCostEur(), b.unitCostEur())) { changed = true; break; }
        }
        if (changed) throw new BusinessRuleException("Producten, aantallen en prijsstaffels van een gesplitste bestelling staan vast; alleen transport, extra korting, teksten en levertermijnen kunnen nog aangepast worden");
        amount(after.manualFreightEur(), "Transport"); percentage(after.extraDiscountPct());
    }

    private Plan plan(SalesOrder source, Request request) {
        requireEligible(source);
        Selection selection = cleanSelection(request);
        if (source.freight() == FreightState.TE_BEPALEN
                && (selection.currentFreightEur() == null || selection.laterFreightEur() == null))
            throw new BusinessRuleException("Het transport is nog niet bepaald; vul eerst voor beide leveringen een expliciet transportbedrag in, ook als dit nul is");
        Map<Long, Choice> laterById = new HashMap<>();
        for (Choice choice : selection.lines()) if (laterById.put(choice.lineId(), choice) != null)
            throw new BusinessRuleException("Een productregel staat dubbel in de verdeling");
        PricedOrder original = sales.price(source);
        if (original.lines().size() != source.lines().size()) throw new BusinessRuleException("Niet alle producten kunnen worden geprijsd; controleer de bestelling");
        Map<Long, Product> catalog = catalog();
        List<SalesOrderLine> currentLines = new ArrayList<>(), laterLines = new ArrayList<>();
        List<SalesSplitPricing.Line> currentTerms = new ArrayList<>(), laterTerms = new ArrayList<>();
        List<SalesSplitPricing.UnavailableReference> references = new ArrayList<>();
        for (int i = 0; i < source.lines().size(); i++) {
            var line = source.lines().get(i); var priced = original.lines().get(i);
            Choice choice = laterById.remove(line.id());
            int later = choice == null ? 0 : choice.laterQuantity();
            if (line.isUnavailable()) {
                if (later != 0 || choice != null && Boolean.FALSE.equals(choice.unavailable()))
                    throw new BusinessRuleException("Herstel een eerder uitgesloten product eerst op de order voordat je de leveringen verdeelt");
                currentLines.add(line(line, 0, line.deliveryWeek(), false, priced));
                currentTerms.add(allocate(priced, 0, false));
                if (line.requestedQuantity() != null && line.requestedQuantity() > 0) {
                    SalesOrder restored = source.withLinesAndPallets(source.lines().stream().map(candidate -> candidate.id().equals(line.id())
                            ? candidate.withAvailability(false, line.requestedQuantity(), line.requestedQuantity()) : candidate).toList(), source.pallets());
                    var reference = sales.price(restored).lines().stream().filter(row -> row.productId().equals(line.productId())).findFirst().orElseThrow();
                    if (reference.quantity() != line.requestedQuantity())
                        throw new BusinessRuleException("De doosinhoud van een uitgesloten product is gewijzigd; herstel en controleer dat product eerst op de order");
                    var referenceLine = allocate(reference, reference.quantity(), false);
                    references.add(new SalesSplitPricing.UnavailableReference(referenceLine,
                            Money.money(Money.percentOf(referenceLine.net(), original.totals().orderDiscountPercent()))));
                }
                continue;
            }
            Product product = catalog.get(line.productId());
            int pack = product == null || product.carton() == null ? 0 : product.carton().piecesPerCarton();
            if (line.id() == null || line.quantity() <= 0 || pack <= 0 || line.quantity() % pack != 0
                    || priced.quantity() != line.quantity()) throw new BusinessRuleException("Controleer eerst de doosaantallen van alle productregels");
            boolean exclude = choice != null && Boolean.TRUE.equals(choice.unavailable());
            if (exclude && later != 0) throw new BusinessRuleException("Een uitgesloten product kan niet tegelijk naar de latere levering worden verplaatst");
            if (later < 0 || later > line.quantity() || later % pack != 0)
                throw new BusinessRuleException("Verdeel " + product.nameWithColour() + " in volle dozen van " + pack + " stuks, binnen het bestelde aantal");
            int current = line.quantity() - later;
            SalesSplitPricing.Line first = allocate(priced, current, false);
            SalesSplitPricing.Line second = allocate(priced, current, true);
            if (current > 0) {
                var currentLine = line(line, current, line.deliveryWeek(), false, priced);
                currentLines.add(exclude ? currentLine.withAvailability(true, 0, current) : currentLine);
                currentTerms.add(first);
            }
            if (later > 0) { laterLines.add(line(line, later, selection.deliveryWeek(), true, priced)); laterTerms.add(second); }
        }
        if (!laterById.isEmpty()) throw new BusinessRuleException("De verdeling bevat een regel die niet meer op deze bestelling staat");
        if (currentLines.stream().noneMatch(line -> line.quantity() > 0) || laterLines.stream().noneMatch(line -> line.quantity() > 0))
            throw new BusinessRuleException("Beide leveringen moeten minstens één actieve volle doos bevatten");
        var total = original.totals();
        carryAggregateCents(currentTerms, laterTerms, total);
        BigDecimal currentNet = currentTerms.stream().map(SalesSplitPricing.Line::net).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal currentOrderDiscount = proportional(total.orderDiscountAmount(), currentNet, total.subtotal());
        BigDecimal laterOrderDiscount = total.orderDiscountAmount().subtract(currentOrderDiscount);
        BigDecimal currentExtra = proportional(total.extraDiscountAmount(), currentNet.subtract(currentOrderDiscount),
                total.subtotal().subtract(total.orderDiscountAmount()));
        BigDecimal laterExtra = total.extraDiscountAmount().subtract(currentExtra);
        BigDecimal firstDefaultGoods = currentNet.subtract(currentOrderDiscount).subtract(currentExtra);
        BigDecimal laterDefaultGoods = total.goodsTotal().subtract(firstDefaultGoods);
        BigDecimal currentPct = selection.currentExtraDiscountPct() == null ? Money.nz(source.extraDiscountPct()) : selection.currentExtraDiscountPct();
        BigDecimal laterPct = selection.laterExtraDiscountPct() == null ? Money.nz(source.extraDiscountPct()) : selection.laterExtraDiscountPct();
        if (selection.currentExtraDiscountPct() != null) currentExtra = Money.money(Money.percentOf(currentNet.subtract(currentOrderDiscount), currentPct));
        if (selection.laterExtraDiscountPct() != null) laterExtra = Money.money(Money.percentOf(total.subtotal().subtract(currentNet).subtract(laterOrderDiscount), laterPct));
        BigDecimal currentFreight = selection.currentFreightEur() == null ? total.freight() : selection.currentFreightEur();
        BigDecimal laterFreight = selection.laterFreightEur() == null ? Money.money(null) : selection.laterFreightEur();
        var firstTerms = new SalesSplitPricing(currentTerms, total.orderDiscountPercent(), currentOrderDiscount, currentPct, currentExtra,
                currentFreight, total.handling(), original.validation().meetsMinimum(), total.vatRatePct(), total.vatTreatment(),
                selection.currentExtraDiscountPct() == null ? firstDefaultGoods : currentNet.subtract(currentOrderDiscount).subtract(currentExtra), references);
        var lastTerms = new SalesSplitPricing(laterTerms, total.orderDiscountPercent(), laterOrderDiscount, laterPct, laterExtra,
                laterFreight, Money.money(null), original.validation().meetsMinimum(), total.vatRatePct(), total.vatTreatment(),
                selection.laterExtraDiscountPct() == null ? laterDefaultGoods : total.subtotal().subtract(currentNet).subtract(laterOrderDiscount).subtract(laterExtra));
        SalesOrder current = copy(source, source.id(), source.number(), currentLines, source.extraLines(), currentFreight, currentPct, source.sourceQuoteId());
        // The part's group is provenance. Only part1 retains the original quote->invoice link.
        SalesOrder later = copy(source, null, null, laterLines, List.of(), laterFreight, laterPct, null);
        PricedOrder firstPriced = sales.priceSplit(current, firstTerms), laterPriced = sales.priceSplit(later, lastTerms);
        BigDecimal delta = firstPriced.totals().total().add(laterPriced.totals().total()).subtract(total.total());
        BigDecimal deltaIncl = firstPriced.totals().totalInclVat().add(laterPriced.totals().totalInclVat()).subtract(total.totalInclVat());
        int excludedQuantity = total.pieces() - firstPriced.totals().pieces() - laterPriced.totals().pieces();
        List<String> warnings = new ArrayList<>(List.of("Product- en orderstaffels blijven behouden. Extra regels en afhandeling staan alleen op deel 1.",
                "Dit maakt twee conceptdocumenten; er wordt niets verstuurd, betaald of uit voorraad afgeboekt."));
        if (!source.pallets().isEmpty()) warnings.add("De handmatige palletindeling wordt opnieuw berekend per levering; de afgesproken transportbedragen blijven volgens deze verdeling staan.");
        if (excludedQuantity > 0) warnings.add(excludedQuantity + " aangevraagde stuks zijn tijdelijk niet bestelbaar en tellen niet mee in deze bedragen of leveringen.");
        if (delta.signum() != 0) warnings.add("Door de gekozen transportkosten, extra korting of uitgesloten producten verandert het totaal exclusief btw met € " + delta.toPlainString() + ".");
        if (deltaIncl.subtract(delta).signum() != 0) warnings.add("Btw wordt per document berekend; het gecombineerde btw-bedrag kan door afronding of gewijzigde bedragen verschillen.");
        String token = hash(List.of(source, original, selection));
        return new Plan(source, current, later, firstTerms, lastTerms,
                new Preview(token, source.id(), summary(original), summary(firstPriced), summary(laterPriced), List.copyOf(warnings), delta, deltaIncl, excludedQuantity), selection);
    }

    private void requireEligible(SalesOrder source) {
        if (source.purpose() != SalesPurpose.STANDARD || source.isPartnerDeal()) throw new BusinessRuleException("Partnerfacturen worden beheerd via de container en kunnen niet als verkoopbestelling worden gesplitst");
        if (part(source.id()) != null) throw new BusinessRuleException("Deze bestelling is al opgesplitst; open de gekoppelde leveringen");
        if (source.archivedAt() != null) throw new BusinessRuleException("Haal het concept eerst uit het archief");
        if (source.status() != QuoteStatus.CONCEPT || source.sentAt() != null || source.viewedAt() != null
                || source.viewCount() != 0 || source.decidedAt() != null
                || !revisions.findByOrder(source.id()).isEmpty())
            throw new BusinessRuleException(source.isInvoice() ? "Alleen een ongebruikte conceptfactuur kan worden opgesplitst"
                    : "Deze offerte is al verstuurd of behandeld; maak er eerst een conceptfactuur van en splits die factuur");
        if (source.goodsShippedAt() != null || source.paidAt() != null || incoming.hasHistory(source.id()))
            throw new BusinessRuleException("Een bestelling met verzendingen of betaalhistoriek kan niet worden opgesplitst");
        if (events.findByOrder(source.id()).stream().anyMatch(event -> switch(event.type()) {
            case VERSTUURD, UITGEREIKT, BEKEKEN, GETEKEND, BESTELLING_VERZONDEN, BETAALD -> true;
            default -> false;
        })) throw new BusinessRuleException("Dit document is eerder gebruikt; splits alleen een ongebruikte conceptfactuur");
        if (!source.isInvoice() && orders.existsBySourceQuoteId(source.id())) throw new BusinessRuleException("Er bestaat al een factuur bij deze offerte; verdeel de ongebruikte conceptfactuur");
        if (source.lines().isEmpty()) throw new BusinessRuleException("Voeg eerst producten toe om de bestelling te verdelen");
        if (source.lines().stream().map(SalesOrderLine::productId).distinct().count() != source.lines().size())
            throw new BusinessRuleException("Voeg dubbele productregels eerst samen; een standaardbestelling bevat één regel per product");
        if (source.extraLines().stream().anyMatch(line -> line.total().signum() < 0))
            throw new BusinessRuleException("Verdeel vrije kortingsregels eerst via de gewone extra korting; negatieve vrije regels kunnen niet automatisch over leveringen worden verdeeld");
    }

    private Selection cleanSelection(Request request) {
        if (request == null || request.lines() == null || request.lines().isEmpty()) throw new BusinessRuleException("Kies welke aantallen later geleverd worden");
        if (request.lines().stream().anyMatch(c -> c == null || c.lineId() == null)) throw new BusinessRuleException("Elke verdeling moet naar een bestaande productregel verwijzen");
        String week = request.deliveryWeek() == null || request.deliveryWeek().isBlank() ? null : request.deliveryWeek().strip();
        if (week != null && !week.matches("[0-9]{4}-W(0[1-9]|[1-4][0-9]|5[0-3])")) throw new BusinessRuleException("Gebruik een geldige leverweek, bijvoorbeeld 2026-W42");
        return new Selection(request.lines().stream().sorted(Comparator.comparing(Choice::lineId)).toList(), week,
                amount(request.currentFreightEur(), "Transport van deel 1"), amount(request.laterFreightEur(), "Transport van deel 2"),
                percentage(request.currentExtraDiscountPct()), percentage(request.laterExtraDiscountPct()));
    }
    private static BigDecimal amount(BigDecimal value, String label) {
        if (value == null) return null;
        if (value.signum() < 0 || value.precision() > 19 || value.stripTrailingZeros().scale() > 2)
            throw new BusinessRuleException(label + " moet positief of nul zijn, met maximaal twee decimalen");
        return Money.money(value);
    }
    private static BigDecimal percentage(BigDecimal value) {
        if (value == null) return null;
        if (value.signum() < 0 || value.compareTo(Money.HUNDRED) > 0 || value.stripTrailingZeros().scale() > 4)
            throw new BusinessRuleException("Extra korting moet tussen 0 en 100 procent liggen, met maximaal vier decimalen");
        return value;
    }
    private static SalesSplitPricing.Line allocate(PricedOrder.Line line, int currentQuantity, boolean later) {
        BigDecimal gross = portion(line.gross(), currentQuantity, line.quantity(), later);
        BigDecimal discount = portion(line.discountAmount(), currentQuantity, line.quantity(), later);
        return new SalesSplitPricing.Line(line.productId(), later ? line.quantity() - currentQuantity : currentQuantity,
                line.unitPrice(), line.tierPercent(), line.manualPercent(), gross, discount, portion(line.net(), currentQuantity, line.quantity(), later),
                line.landedUnitCost(), portion(line.costTotal(), currentQuantity, line.quantity(), later));
    }
    /** Source totals round after aggregation; carry each aggregate's cent remainder into the last later line. */
    private static void carryAggregateCents(List<SalesSplitPricing.Line> first, List<SalesSplitPricing.Line> later, PricedOrder.Totals original) {
        List<SalesSplitPricing.Line> all = new ArrayList<>(first); all.addAll(later);
        var last = later.getLast();
        BigDecimal gross = original.gross().subtract(all.stream().map(SalesSplitPricing.Line::gross).reduce(BigDecimal.ZERO, BigDecimal::add));
        BigDecimal discount = original.lineDiscountTotal().subtract(all.stream().map(SalesSplitPricing.Line::discountAmount).reduce(BigDecimal.ZERO, BigDecimal::add));
        BigDecimal net = original.subtotal().subtract(all.stream().map(SalesSplitPricing.Line::net).reduce(BigDecimal.ZERO, BigDecimal::add));
        BigDecimal cost = original.costTotal().subtract(all.stream().map(SalesSplitPricing.Line::costTotal).reduce(BigDecimal.ZERO, BigDecimal::add));
        later.set(later.size() - 1, new SalesSplitPricing.Line(last.productId(), last.quantity(), last.unitPrice(), last.tierPercent(), last.manualPercent(),
                last.gross().add(gross), last.discountAmount().add(discount), last.net().add(net), last.landedUnitCost(), last.costTotal().add(cost)));
    }
    private static BigDecimal portion(BigDecimal total, int first, int all, boolean later) {
        BigDecimal allocated = proportional(total, BigDecimal.valueOf(first), BigDecimal.valueOf(all));
        return later ? total.subtract(allocated) : allocated;
    }
    private static BigDecimal proportional(BigDecimal amount, BigDecimal first, BigDecimal all) {
        return all.signum() == 0 ? Money.money(null) : amount.multiply(first).divide(all, 2, RoundingMode.HALF_UP);
    }
    private static BigDecimal subtotal(SalesSplitPricing terms) { return terms.lines().stream().map(SalesSplitPricing.Line::net).reduce(BigDecimal.ZERO, BigDecimal::add); }
    private static SalesOrderLine line(SalesOrderLine source, int quantity, String week, boolean fresh, PricedOrder.Line priced) {
        return new SalesOrderLine(fresh ? null : source.id(), source.productId(), quantity, priced.unitPrice(), source.manualDiscountPct(), week, priced.landedUnitCost(), source.unavailable(), source.requestedQuantity());
    }
    static SalesOrder copy(SalesOrder s, Long id, String number, List<SalesOrderLine> lines, List<SalesExtraLine> extras,
                           BigDecimal freight, BigDecimal extraPct, Long sourceQuoteId) {
        return new SalesOrder(id, number, s.customerId(), s.countryCode(), s.orderDate(), s.validUntil(), QuoteStatus.CONCEPT,
                s.incoterm(), s.paymentTerms(), s.notes(), s.markupMode(), s.orderMarkupPct(), extraPct, s.extraDiscountLabel(),
                null, null, null, 0, null, null, s.customerMessage(), s.internalNotes(), s.deliveryTerms(),
                FreightState.BEREKEND, freight, s.loadMode(), s.palletProfile(), s.maxPalletHeightCm(),
                FreightPricingStrategy.FIXED, null, s.freightCarrierId(), BigDecimal.ZERO,
                s.docType(), s.invoiceDueDate(), null, sourceQuoteId, null, lines, List.of(), s.pickupLocation())
                .withExtraLines(extras).withSalesChannel(s.rawSalesChannel()).withPurpose(SalesPurpose.STANDARD, s.sourcePurchaseOrderId(), s.paymentPlan());
    }
    private static PartPreview summary(PricedOrder priced) {
        var t = priced.totals(); return new PartPreview(t.pieces(), t.total(), t.vatAmount(), t.totalInclVat(), t.freight(), t.handling(), t.extraLinesTotal(), t.goodsTotal(),
                priced.lines().stream().filter(PricedOrder.Line::unavailable).mapToInt(line -> line.requestedQuantity() == null ? 0 : line.requestedQuantity()).sum());
    }
    private Map<Long, Product> catalog() { return products.list().stream().collect(Collectors.toMap(Product::id, Function.identity())); }
    private SalesSplitPartEntity part(long id) { return entities.find(SalesSplitPartEntity.class, id); }
    private void savePart(SalesOrder order, SalesSplitGroupEntity group, int number, boolean waiting, SalesSplitPricing pricing) {
        var part = new SalesSplitPartEntity(); part.salesOrderId = order.id(); part.order = entities.getReference(SalesEntities.SalesOrderEntity.class, order.id());
        part.group = group; part.partNumber = number; part.waitingForStock = waiting; part.pricingJson = write(pricing); entities.persist(part);
    }
    private SalesSplitPricing read(String value) { try { return json.readValue(value, SalesSplitPricing.class); } catch (Exception e) { throw new IllegalStateException("Opgeslagen orderverdeling is niet leesbaar", e); } }
    private String write(Object value) { try { return json.writeValueAsString(value); } catch (Exception e) { throw new IllegalStateException("Orderverdeling kon niet vastgelegd worden", e); } }
    private String hash(Object value) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(write(value).getBytes(StandardCharsets.UTF_8))); } catch (Exception e) { throw new IllegalStateException(e); } }
    private static boolean same(BigDecimal a, BigDecimal b) { return Money.nz(a).compareTo(Money.nz(b)) == 0; }
    private static boolean sameExtras(List<SalesExtraLine> before, List<SalesExtraLine> after) {
        if (before.size() != after.size()) return false;
        for (int i = 0; i < before.size(); i++) {
            var a = before.get(i); var b = after.get(i);
            if (b == null || !Objects.equals(a.description(), b.description())
                    || !same(a.quantity(), b.quantity()) || !same(a.unitPriceEur(), b.unitPriceEur())) return false;
        }
        return true;
    }
}
