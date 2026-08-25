package be.enrosed.sales.application;

import be.enrosed.catalog.application.ProductService;
import be.enrosed.catalog.domain.Carton;
import be.enrosed.catalog.domain.Dimensions;
import be.enrosed.catalog.domain.Product;
import be.enrosed.sales.application.port.out.SalesRepositories;
import be.enrosed.sales.domain.*;
import be.enrosed.shared.BusinessRuleException;
import be.enrosed.shared.NotFoundException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Drafting and pricing sales orders.
 *
 * Sending, the customer view and the change proposals live in
 * {@link QuoteService}; only composing the order stays here.
 */
@ApplicationScoped
public class SalesOrderService {

    private final SalesRepositories.Orders orders;
    private final ProductService products;
    private final CountryService countries;
    private final DiscountTierService tiers;
    private final SalesPricingCalculator pricing;
    private final PalletCalculator palletCalculator;
    private final SalesSettings settings;
    private final SalesRepositories.Events events;
    private final SalesRepositories.Revisions revisions;
    private final CustomerService customers;
    private final VatCalculator vat;
    private final be.enrosed.shipping.application.CarrierRepository shippingCarriers;

    public SalesOrderService(SalesRepositories.Orders orders, ProductService products,
                             CountryService countries, DiscountTierService tiers,
                             SalesPricingCalculator pricing, PalletCalculator palletCalculator,
                             SalesSettings settings,
                             CustomerService customers, VatCalculator vat,
                             SalesRepositories.Events events,
                             SalesRepositories.Revisions revisions,
                             be.enrosed.shipping.application.CarrierRepository shippingCarriers) {
        this.shippingCarriers = shippingCarriers;
        this.orders = orders;
        this.products = products;
        this.countries = countries;
        this.tiers = tiers;
        this.pricing = pricing;
        this.palletCalculator = palletCalculator;
        this.settings = settings;
        this.customers = customers;
        this.events = events;
        this.revisions = revisions;
        this.vat = vat;
    }

    public List<SalesOrder> list() {
        return orders.findAll();
    }

    public SalesOrder get(long id) {
        return orders.findById(id).orElseThrow(() -> new NotFoundException("Verkooporder", id));
    }

    /** Prices an order with the current prices, tiers and rates. */
    public PricedOrder price(SalesOrder order) {
        Map<Long, Product> byId = products.list().stream()
                .collect(Collectors.toMap(Product::id, Function.identity()));
        Country country = countries.find(order.countryCode());
        Customer customer = order.customerId() == null ? null : customers.get(order.customerId());

        be.enrosed.shipping.domain.Carrier carrier = order.freightCarrierId() == null
                ? null : shippingCarriers.findById(order.freightCarrierId()).orElse(null);

        return pricing.price(order, byId, new SalesPricingCalculator.Context(
                country,
                customer,
                settings.pallet(order.palletProfile(), order.maxPalletHeightCm()),
                tiers.list(TierScope.LINE),
                tiers.list(TierScope.ORDER),
                vat.determine(country, customer),
                carrier));
    }

    /** Price this order uses for this product; the portal uses it too. */
    public BigDecimal unitPriceFor(be.enrosed.catalog.domain.Product product, SalesOrder order) {
        return pricing.unitPriceFor(product, order, null);
    }

    @Transactional
    public SalesOrder create(long customerId, String countryCode, String incoterm) {
        return create(customerId, countryCode, incoterm, DocumentType.OFFERTE);
    }

    @Transactional
    public SalesOrder create(long customerId, String countryCode, String incoterm,
                             DocumentType docType) {
        boolean invoice = docType == DocumentType.FACTUUR;
        LocalDate today = LocalDate.now();
        SalesOrder draft = new SalesOrder(
                null, invoice ? nextInvoiceNumber() : nextNumber(),
                customerId, countryCode, today, today.plusDays(30),
                QuoteStatus.CONCEPT, incoterm == null ? "DAP" : incoterm, null, "",
                MarkupMode.PRODUCT, settings.defaultMarkupPct(),
                null, null,
                null, null, null, 0, null, null, null, null,
                DeliveryTermsState.VOLLEDIG, FreightState.BEREKEND, null,
                LoadMode.PALLETS, PalletProfile.EURO_120X80, null,
                FreightPricingStrategy.COUNTRY_PALLET, null, null, null,
                docType, invoice ? today.plusDays(30) : null, null, null,
                List.of(), List.of());
        validateForSave(draft);
        SalesOrder created = orders.save(draft);

        events.add(new QuoteEvent(null, created.id(), QuoteEvent.Type.OPGEMAAKT,
                java.time.Instant.now(), null, false,
                invoice ? "Factuur opgemaakt" : "Offerte opgemaakt", null));
        return created;
    }

    /**
     * A new invoice with the quote's whole content frozen in.
     *
     * The quote keeps living its own life: it can be re-invoiced (partial
     * deliveries) and its history records that this invoice left from it.
     */
    @Transactional
    public SalesOrder createInvoiceFrom(long quoteId) {
        SalesOrder source = get(quoteId);
        if (source.isInvoice()) {
            throw new BusinessRuleException("Dit is al een factuur; maak facturen vanuit een offerte");
        }
        LocalDate today = LocalDate.now();
        SalesOrder invoice = new SalesOrder(
                null, nextInvoiceNumber(), source.customerId(), source.countryCode(),
                today, today.plusDays(30), QuoteStatus.CONCEPT, source.incoterm(),
                source.paymentTerms(), source.notes(),
                source.markupMode(), source.orderMarkupPct(),
                source.extraDiscountPct(), source.extraDiscountLabel(),
                null, null, null, 0, null, null, null, source.internalNotes(),
                DeliveryTermsState.VOLLEDIG, source.freight(), source.manualFreightEur(),
                source.loadMode(), source.palletProfile(), source.maxPalletHeightCm(),
                source.freightPricingStrategy(), source.freightRatePerCbmEur(),
                source.freightCarrierId(), source.freightCarrierExtraEur(),
                DocumentType.FACTUUR, today.plusDays(30), null, source.id(),
                source.lines().stream()
                        .map(line -> new SalesOrderLine(null, line.productId(), line.quantity(),
                                line.unitPriceEur(), line.manualDiscountPct(), line.deliveryWeek()))
                        .toList(),
                source.pallets().stream()
                        .map(pallet -> new OrderPallet(null, pallet.label(), pallet.type(),
                                pallet.heightCm(), pallet.items()))
                        .toList());
        validateForSave(invoice);
        SalesOrder created = orders.save(invoice);

        events.add(new QuoteEvent(null, created.id(), QuoteEvent.Type.OPGEMAAKT,
                java.time.Instant.now(), null, false,
                "Factuur opgemaakt vanuit " + source.number(), null));
        events.add(new QuoteEvent(null, source.id(), QuoteEvent.Type.GEFACTUREERD,
                java.time.Instant.now(), null, false,
                "Factuur " + created.number() + " aangemaakt", null));
        return created;
    }

    /** Invoices skip the portal: sending is a bookkeeping fact, not a mail flow. */
    @Transactional
    public SalesOrder markInvoiceSent(long id) {
        SalesOrder invoice = requireInvoice(get(id));
        if (invoice.status() != QuoteStatus.CONCEPT) {
            throw new BusinessRuleException("Alleen een conceptfactuur kan verstuurd worden");
        }
        validateInvoiceForSend(invoice);
        SalesOrder sent = withStatus(invoice, QuoteStatus.VERZONDEN, java.time.Instant.now(), null);
        SalesOrder saved = orders.save(sent);
        events.add(new QuoteEvent(null, id, QuoteEvent.Type.VERSTUURD,
                java.time.Instant.now(), null, false, "Factuur verstuurd", null));
        return saved;
    }

    @Transactional
    public SalesOrder markInvoicePaid(long id) {
        SalesOrder invoice = requireInvoice(get(id));
        if (invoice.status() == QuoteStatus.BETAALD) return invoice;
        if (invoice.status() == QuoteStatus.CONCEPT) {
            throw new BusinessRuleException("Verstuur de factuur voor je ze betaald meldt");
        }
        SalesOrder paid = withStatus(invoice, QuoteStatus.BETAALD,
                invoice.sentAt(), java.time.Instant.now());
        SalesOrder saved = orders.save(paid);
        events.add(new QuoteEvent(null, id, QuoteEvent.Type.BETAALD,
                java.time.Instant.now(), null, false, "Factuur betaald", null));
        return saved;
    }

    /**
     * A quote may leave with open ends - freight to be determined, delivery
     * in consultation. An invoice may not: it is a payment claim, and every
     * open end becomes a discussion about the amount. This is the line
     * between the two document sorts.
     */
    public void validateInvoiceForSend(SalesOrder invoice) {
        if (invoice.lines().isEmpty()) {
            throw new BusinessRuleException("Een factuur zonder regels kan niet verstuurd worden");
        }
        if (invoice.customerId() == null) {
            throw new BusinessRuleException("Koppel eerst een klant aan de factuur");
        }
        if (invoice.freight() == FreightState.TE_BEPALEN) {
            throw new BusinessRuleException(
                    "De vracht staat nog op 'later bepalen' - een factuur moet volledig geprijsd zijn");
        }
        if (invoice.invoiceDueDate() == null) {
            throw new BusinessRuleException("Vul de vervaldatum van de factuur in");
        }
        Customer customer = customers.get(invoice.customerId());
        if (isBlank(customer.address()) || isBlank(customer.postalCode()) || isBlank(customer.city())) {
            throw new BusinessRuleException("Vul het volledige adres van " + customer.company()
                    + " in - een factuur zonder adres is niet geldig");
        }
        Country country = countries.find(invoice.countryCode());
        boolean intraEu = country != null && country.euMember()
                && !"BE".equalsIgnoreCase(invoice.countryCode());
        if (intraEu && isBlank(customer.vatNumber())) {
            throw new BusinessRuleException("Vul het BTW-nummer van " + customer.company()
                    + " in - zonder geldig BTW-nummer kan de BTW niet verlegd worden");
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static SalesOrder requireInvoice(SalesOrder order) {
        if (!order.isInvoice()) {
            throw new BusinessRuleException("Dit document is geen factuur");
        }
        return order;
    }

    private static SalesOrder withStatus(SalesOrder order, QuoteStatus status,
                                         java.time.Instant sentAt, java.time.Instant paidAt) {
        return new SalesOrder(order.id(), order.number(), order.customerId(),
                order.countryCode(), order.orderDate(), order.validUntil(), status,
                order.incoterm(), order.paymentTerms(), order.notes(), order.markupMode(),
                order.orderMarkupPct(), order.extraDiscountPct(), order.extraDiscountLabel(),
                order.portalToken(), sentAt, order.viewedAt(), order.viewCount(),
                order.decidedAt(), order.signedByName(), order.customerMessage(),
                order.internalNotes(), order.deliveryTerms(), order.freight(),
                order.manualFreightEur(), order.loadMode(), order.palletProfile(),
                order.maxPalletHeightCm(), order.freightPricingStrategy(),
                order.freightRatePerCbmEur(), order.freightCarrierId(),
                order.freightCarrierExtraEur(), order.docType(),
                order.invoiceDueDate(), paidAt, order.sourceQuoteId(),
                order.lines(), order.pallets());
    }

    @Transactional
    public SalesOrder update(long id, SalesOrder changes) {
        SalesOrder current = get(id);
        SalesLifecycle.requireEditable(current);
        if (changes == null) {
            throw new BusinessRuleException("Geen offertegegevens meegestuurd");
        }

        FreightPricingStrategy freightStrategy = freightStrategyForUpdate(current, changes);
        BigDecimal manualFreight = freightStrategy == FreightPricingStrategy.FIXED
                ? changes.manualFreightEur() : null;
        BigDecimal cbmRate = freightStrategy == FreightPricingStrategy.PER_CBM
                ? changes.freightRatePerCbmEur()
                : null;
        /* The chosen organisation is remembered even while another strategy
           is selected, so switching back does not lose the pick. */
        Long carrierId = changes.freightCarrierId() == null
                ? current.freightCarrierId() : changes.freightCarrierId();

        SalesOrder updated = new SalesOrder(
                current.id(), numberFor(current, changes),
                changes.customerId(), changes.countryCode(),
                changes.orderDate(), changes.validUntil(),
                /* The status is driven by the quote workflow, not by a plain update. */
                current.status(),
                changes.incoterm(), changes.paymentTerms(), changes.notes(),
                changes.markupMode() == null ? current.markupMode() : changes.markupMode(),
                changes.orderMarkupPct() == null ? current.orderMarkupPct() : changes.orderMarkupPct(),
                changes.extraDiscountPct(), changes.extraDiscountLabel(),
                current.portalToken(), current.sentAt(), current.viewedAt(), current.viewCount(),
                current.decidedAt(), current.signedByName(), current.customerMessage(),
                /* Internal notes DO come from the form. */
                changes.internalNotes(),
                /* The delivery-terms state is driven by the quote workflow: it
                   follows from what was sent, not from what is filled in. */
                current.deliveryTerms(),
                /* The freight state is ours to set on screen ("to be
                   determined later"), so it DOES come from the form. When not
                   sent along, what was there stays. */
                changes.freightOrNull() == null ? current.freight() : changes.freight(),
                manualFreight,
                changes.loadModeOrNull() == null ? current.loadMode() : changes.loadMode(),
                changes.palletProfileOrNull() == null
                        ? current.palletProfile() : changes.palletProfile(),
                /* Null deliberately means: return to the configured default. */
                changes.maxPalletHeightCm(),
                freightStrategy, cbmRate, carrierId,
                changes.freightCarrierExtraEur() == null
                        ? current.freightCarrierExtraEur() : changes.freightCarrierExtraEur(),
                current.docType(),
                changes.invoiceDueDate() == null ? current.invoiceDueDate() : changes.invoiceDueDate(),
                current.paidAt(), current.sourceQuoteId(),
                roundLinesToCartons(changes.lines()), changes.pallets());
        validateForSave(updated);
        return orders.save(updated);
    }

    /** One line whose promised delivery week may be filled in separately. */
    public record DeliveryWeekChange(Long productId, String deliveryWeek) {}

    /**
     * Narrow update for a promise that was left open on a sent quotation.
     * Prices, quantities, customer data and every other field stay untouched.
     */
    @Transactional
    public SalesOrder updateDeliveryWeeks(long id, List<DeliveryWeekChange> requested) {
        SalesOrder current = get(id);
        SalesLifecycle.requireTermsEditable(current);
        if (requested == null || requested.isEmpty()) {
            throw new BusinessRuleException("Geef minstens een levertermijn mee");
        }

        Map<Long, String> weeks = new HashMap<>();
        Set<Long> seen = new HashSet<>();
        Set<Long> productsOnOrder = current.lines().stream()
                .map(SalesOrderLine::productId)
                .collect(Collectors.toSet());
        for (DeliveryWeekChange change : requested) {
            if (change == null || change.productId() == null) {
                throw new BusinessRuleException("Elke levertermijn moet bij een product horen");
            }
            if (!productsOnOrder.contains(change.productId())) {
                throw new BusinessRuleException(
                        "Product " + change.productId() + " staat niet op deze offerte");
            }
            if (!seen.add(change.productId())) {
                throw new BusinessRuleException(
                        "Product " + change.productId() + " staat dubbel in de levertermijnen");
            }
            weeks.put(change.productId(), clean(change.deliveryWeek()));
        }

        List<SalesOrderLine> lines = current.lines().stream()
                .map(line -> weeks.containsKey(line.productId())
                        ? new SalesOrderLine(line.id(), line.productId(), line.quantity(),
                                line.unitPriceEur(), line.manualDiscountPct(), weeks.get(line.productId()))
                        : line)
                .toList();
        return orders.save(copyWithTerms(current, current.freight(), current.manualFreightEur(),
                current.freightPricingStrategy(), current.freightRatePerCbmEur(), lines));
    }

    /**
     * Narrow freight update for a sent quotation. Moving away from an open
     * freight item records AANGEVULD so the following mail can announce it.
     */
    @Transactional
    public SalesOrder updateFreight(long id, FreightState requestedState, BigDecimal manualFreightEur) {
        return updateFreight(id, requestedState, manualFreightEur, null, null);
    }

    /**
     * Narrow freight update including its calculation basis. Older clients
     * omit the two new fields: a supplied fixed amount keeps its old meaning.
     */
    @Transactional
    public SalesOrder updateFreight(long id, FreightState requestedState, BigDecimal manualFreightEur,
                                    FreightPricingStrategy requestedStrategy,
                                    BigDecimal freightRatePerCbmEur) {
        return updateFreight(id, requestedState, manualFreightEur, requestedStrategy,
                freightRatePerCbmEur, null);
    }

    @Transactional
    public SalesOrder updateFreight(long id, FreightState requestedState, BigDecimal manualFreightEur,
                                    FreightPricingStrategy requestedStrategy,
                                    BigDecimal freightRatePerCbmEur, Long freightCarrierId) {
        SalesOrder current = get(id);
        SalesLifecycle.requireTermsEditable(current);
        if (requestedState == null) {
            throw new BusinessRuleException("Kies of de vracht berekend of nog te bepalen is");
        }
        requireNonNegative(manualFreightEur, "Handmatige vracht");
        requireNonNegative(freightRatePerCbmEur, "CBM-vrachttarief");

        FreightState state = current.freight() == FreightState.TE_BEPALEN
                && requestedState != FreightState.TE_BEPALEN
                ? FreightState.AANGEVULD
                : requestedState;
        FreightPricingStrategy strategy = requestedStrategy;
        if (strategy == null) {
            if (manualFreightEur != null) strategy = FreightPricingStrategy.FIXED;
            else if (current.freightPricingStrategy() == FreightPricingStrategy.FIXED) {
                strategy = FreightPricingStrategy.COUNTRY_PALLET;
            } else strategy = current.freightPricingStrategy();
        }
        BigDecimal fixedTotal = strategy == FreightPricingStrategy.FIXED
                ? manualFreightEur : null;
        BigDecimal cbmRate = strategy == FreightPricingStrategy.PER_CBM
                ? (freightRatePerCbmEur == null
                        ? current.freightRatePerCbmEur() : freightRatePerCbmEur)
                : null;
        SalesOrder updated = copyWithTerms(current, state, fixedTotal,
                strategy, cbmRate, current.lines());
        if (freightCarrierId != null) {
            updated = withCarrier(updated, freightCarrierId);
        }
        validateNarrowFreightUpdate(updated);
        return orders.save(updated);
    }

    /**
     * A sent legacy quote is commercially frozen. Filling its open freight
     * item must therefore validate only the selected tariff basis, not newly
     * reject historic pallet layouts or unrelated product data that the
     * narrow endpoint cannot repair.
     */
    private void validateNarrowFreightUpdate(SalesOrder order) {
        if (order.freight() == FreightState.TE_BEPALEN) return;

        switch (order.freightPricingStrategy()) {
            case FIXED -> {
                if (order.manualFreightEur() == null) {
                    throw new BusinessRuleException("Vul het vaste vrachtbedrag in");
                }
            }
            case PER_CBM -> {
                if (order.freightRatePerCbmEur() == null
                        || order.freightRatePerCbmEur().signum() <= 0) {
                    throw new BusinessRuleException("De vracht staat op een m3-tarief zonder bedrag"
                        + " - open Transport & levering en kies een andere vrachtberekening");
                }
                requireOuterCartonsForFreight(order, false);
            }
            case COUNTRY_PALLET -> {
                if (order.loadMode() == LoadMode.LOOSE_CARTONS) {
                    throw new BusinessRuleException(
                            "Kies bij losse dozen vracht per CBM of een vast vrachtbedrag");
                }
                /* A stored manual layout already supplies the billed number
                   of positions. Do not reopen its historic assignments. */
                if (order.pallets().isEmpty()) requireOuterCartonsForFreight(order, true);
            }
        }
    }

    private void requireOuterCartonsForFreight(SalesOrder order, boolean requirePalletFit) {
        Map<Long, Product> byId = products.list().stream()
                .collect(Collectors.toMap(Product::id, Function.identity()));
        PalletSpec palletSpec = settings.pallet(order.palletProfile(), order.maxPalletHeightCm());
        for (SalesOrderLine line : order.lines()) {
            if (line == null || line.quantity() <= 0) continue;
            Product product = byId.get(line.productId());
            if (product == null || !hasValidOuterCarton(product.carton())) {
                String description = product == null ? "product " + line.productId() : product.describe();
                throw new BusinessRuleException(
                        "Vul geldige omdoosafmetingen en stuks per doos in voor " + description);
            }
            if (requirePalletFit
                    && palletCalculator.fit(product.carton(), palletSpec).cartonsPerPallet() <= 0) {
                throw new BusinessRuleException(product.describe()
                        + " past niet binnen het gekozen palletprofiel, de hoogte of het gewicht");
            }
        }
    }

    /**
     * The order number as it should be after an update.
     *
     * Being able to set a number by hand matters: numbering carries on after
     * migrating from another system, and sometimes a quote belongs to an
     * existing file. But two orders with the same number make every reference
     * ambiguous - in a mail, on an invoice, in the books - so that is
     * refused. Leaving it empty means: keep what was there.
     */
    private String numberFor(SalesOrder current, SalesOrder changes) {
        String wanted = changes.number() == null ? null : changes.number().trim();
        if (wanted == null || wanted.isBlank() || wanted.equals(current.number())) {
            return current.number();
        }
        boolean taken = orders.findAll().stream()
                .anyMatch(other -> !other.id().equals(current.id())
                        && wanted.equalsIgnoreCase(other.number()));
        if (taken) {
            throw new BusinessRuleException("Er bestaat al een verkooporder met nummer " + wanted);
        }
        return wanted;
    }

    @Transactional
    public void delete(long id) {
        SalesOrder order = get(id);
        boolean hasRevisions = !revisions.findByOrder(id).isEmpty();
        SalesLifecycle.requireDeletable(order, hasRevisions);
        events.deleteByOrder(id);
        orders.deleteById(id);
    }

    @Transactional
    public SalesOrder duplicate(long id) {
        SalesOrder source = get(id);
        LocalDate today = LocalDate.now();
        SalesOrder duplicate = new SalesOrder(
                null, source.isInvoice() ? nextInvoiceNumber() : nextNumber(),
                source.customerId(), source.countryCode(),
                today, today.plusDays(30), QuoteStatus.CONCEPT, source.incoterm(),
                source.paymentTerms(), source.notes(),
                source.markupMode(), source.orderMarkupPct(),
                source.extraDiscountPct(), source.extraDiscountLabel(),
                null, null, null, 0, null, null, null, source.internalNotes(),
                /* A copy starts clean: that quote has not left yet. */
                DeliveryTermsState.VOLLEDIG, FreightState.BEREKEND, source.manualFreightEur(),
                source.loadMode(), source.palletProfile(), source.maxPalletHeightCm(),
                source.freightPricingStrategy(), source.freightRatePerCbmEur(),
                source.freightCarrierId(), source.freightCarrierExtraEur(),
                source.docType(), source.isInvoice() ? today.plusDays(30) : null, null, null,
                source.lines().stream()
                        .map(line -> new SalesOrderLine(null, line.productId(), line.quantity(),
                                line.unitPriceEur(), line.manualDiscountPct(), line.deliveryWeek()))
                        .toList(),
                source.pallets().stream()
                        .map(pallet -> new OrderPallet(null, pallet.label(), pallet.type(),
                                pallet.heightCm(), pallet.items()))
                        .toList());
        validateForSave(duplicate);
        return orders.save(duplicate);
    }

    /** Rechecks an existing draft/open quotation before a document leaves. */
    public void validateForSend(SalesOrder order) {
        validateForSave(order);
        validateLogisticsReady(order);
    }

    private void validateForSave(SalesOrder order) {
        if (order == null) {
            throw new BusinessRuleException("Geen offertegegevens meegestuurd");
        }
        if (order.customerId() == null || order.customerId() <= 0) {
            throw new BusinessRuleException("Koppel een geldige klant aan de offerte");
        }
        try {
            customers.get(order.customerId());
        } catch (NotFoundException exception) {
            throw new BusinessRuleException("De gekozen klant bestaat niet meer");
        }
        if (order.countryCode() == null || order.countryCode().isBlank()) {
            throw new BusinessRuleException("Kies een geldig bestemmingsland");
        }
        if (countries.find(order.countryCode()) == null) {
            /* Say which code is the problem: nine times out of ten the customer
               carries a country that Landen & vracht does not ship to yet. */
            throw new BusinessRuleException("Bestemmingsland " + order.countryCode()
                    + " staat niet bij Landen & vracht; voeg het daar toe of pas het land van de klant aan");
        }
        if (order.orderDate() == null || order.validUntil() == null) {
            throw new BusinessRuleException("Orderdatum en geldigheidsdatum zijn verplicht");
        }
        if (order.validUntil().isBefore(order.orderDate())) {
            throw new BusinessRuleException("De geldigheidsdatum kan niet vóór de orderdatum liggen");
        }
        if (order.incoterm() == null || order.incoterm().isBlank()) {
            throw new BusinessRuleException("Incoterm is verplicht");
        }
        if (order.markupMode() == null) {
            throw new BusinessRuleException("Kies hoe de opslag wordt berekend");
        }
        requireNonNegative(order.orderMarkupPct(), "Opslagpercentage");
        requirePercentage(order.extraDiscountPct(), "Extra korting");
        requireNonNegative(order.manualFreightEur(), "Handmatige vracht");
        requireNonNegative(order.freightRatePerCbmEur(), "CBM-vrachttarief");

        PalletSpec palletSpec = settings.pallet(order.palletProfile(), order.maxPalletHeightCm());
        if (order.maxPalletHeightCm() != null) {
            if (order.maxPalletHeightCm().compareTo(palletSpec.baseHeightCm()) <= 0) {
                throw new BusinessRuleException(
                        "Maximale pallethoogte moet hoger zijn dan de palletbasis van "
                                + palletSpec.baseHeightCm().stripTrailingZeros().toPlainString() + " cm");
            }
            if (order.maxPalletHeightCm().compareTo(BigDecimal.valueOf(300)) > 0) {
                throw new BusinessRuleException("Maximale pallethoogte kan niet hoger zijn dan 300 cm");
            }
        }

        Map<Long, Product> byId = products.list().stream()
                .collect(Collectors.toMap(Product::id, Function.identity()));
        Set<Long> seen = new HashSet<>();
        for (SalesOrderLine line : order.lines()) {
            if (line == null || line.productId() == null) {
                throw new BusinessRuleException("Elke offerteregel moet bij een product horen");
            }
            if (!seen.add(line.productId())) {
                throw new BusinessRuleException("Product " + line.productId() + " staat dubbel op de offerte");
            }
            if (!byId.containsKey(line.productId())) {
                throw new BusinessRuleException("Product " + line.productId() + " bestaat niet meer");
            }
            if (line.quantity() < 0) {
                throw new BusinessRuleException("Een productaantal kan niet negatief zijn");
            }
            requireNonNegative(line.unitPriceEur(), "Handmatige stukprijs");
            requirePercentage(line.manualDiscountPct(), "Regelkorting");
        }
        validatePallets(order, byId, palletSpec);
    }

    /**
     * Draft autosave may be incomplete while a seller picks a strategy and
     * then types its amount. A quote may only leave once logistics is fully
     * priceable and every shipping carton can be measured.
     */
    private void validateLogisticsReady(SalesOrder order) {
        if (order.freight() != FreightState.TE_BEPALEN) {
            if (order.loadMode() == LoadMode.LOOSE_CARTONS
                    && order.freightPricingStrategy() == FreightPricingStrategy.COUNTRY_PALLET) {
                throw new BusinessRuleException(
                        "Kies bij losse dozen vracht per CBM of een vast vrachtbedrag");
            }
            if (order.freightPricingStrategy() == FreightPricingStrategy.PER_CBM
                    && (order.freightRatePerCbmEur() == null
                        || order.freightRatePerCbmEur().signum() <= 0)) {
                throw new BusinessRuleException("De vracht staat op een m3-tarief zonder bedrag"
                        + " - open Transport & levering en kies een andere vrachtberekening");
            }
            if (order.freightPricingStrategy() == FreightPricingStrategy.FIXED
                    && order.manualFreightEur() == null) {
                throw new BusinessRuleException("Vul het vaste vrachtbedrag in");
            }
        }

        Map<Long, Product> byId = products.list().stream()
                .collect(Collectors.toMap(Product::id, Function.identity()));
        PalletSpec palletSpec = settings.pallet(order.palletProfile(), order.maxPalletHeightCm());
        for (SalesOrderLine line : order.lines()) {
            if (line == null || line.quantity() <= 0) continue;
            Product product = byId.get(line.productId());
            if (product == null) continue; // validateForSave reports the clearer missing-product error.
            if (!hasValidOuterCarton(product.carton())) {
                throw new BusinessRuleException("Vul geldige omdoosafmetingen en stuks per doos in voor "
                        + product.describe());
            }
            if (order.loadMode() == LoadMode.PALLETS
                    && palletCalculator.fit(product.carton(), palletSpec).cartonsPerPallet() <= 0) {
                throw new BusinessRuleException(product.describe()
                        + " past niet binnen het gekozen palletprofiel, de hoogte of het gewicht");
            }
        }

        if (order.loadMode() == LoadMode.PALLETS && !order.pallets().isEmpty()) {
            Map<Long, Integer> assigned = new HashMap<>();
            for (OrderPallet pallet : order.pallets()) {
                if (pallet.items().isEmpty()) {
                    throw new BusinessRuleException(
                            "Verwijder lege pallets of zet er minstens één doos op");
                }
                for (OrderPallet.Item item : pallet.items()) {
                    assigned.merge(item.productId(), item.cartons(), Integer::sum);
                }
            }
            for (SalesOrderLine line : order.lines()) {
                Product product = byId.get(line.productId());
                if (product == null) continue;
                int ordered = product.carton() == null
                        ? Math.max(0, line.quantity())
                        : product.carton().cartonsFor(line.quantity());
                if (assigned.getOrDefault(line.productId(), 0) != ordered) {
                    throw new BusinessRuleException("Verdeel alle " + ordered
                            + " dozen van " + product.describe() + " over de pallets");
                }
            }
        }
    }

    private void validatePallets(SalesOrder order, Map<Long, Product> byId, PalletSpec palletSpec) {
        /* A loose-carton draft keeps its previous warehouse layout so the
           seller can switch back without rebuilding it. While loose, that
           hidden layout is deliberately ignored and may temporarily be stale. */
        if (order.loadMode() == LoadMode.LOOSE_CARTONS) return;

        Set<Long> productsOnOrder = order.lines().stream()
                .map(SalesOrderLine::productId)
                .collect(Collectors.toSet());
        Map<Long, Integer> assigned = new HashMap<>();

        for (OrderPallet pallet : order.pallets()) {
            if (pallet == null) {
                throw new BusinessRuleException("Een pallet mag niet leeg zijn");
            }
            if (pallet.heightCm() != null
                    && BigDecimal.valueOf(pallet.heightCm()).compareTo(palletSpec.baseHeightCm()) < 0) {
                throw new BusinessRuleException("Pallethoogte kan niet lager zijn dan de palletbasis");
            }
            if (pallet.heightCm() != null
                    && BigDecimal.valueOf(pallet.heightCm()).compareTo(palletSpec.maxHeightCm()) > 0) {
                throw new BusinessRuleException("Pallethoogte kan niet hoger zijn dan "
                        + palletSpec.maxHeightCm().stripTrailingZeros().toPlainString() + " cm");
            }
            for (OrderPallet.Item item : pallet.items()) {
                if (item == null || item.productId() <= 0 || item.cartons() <= 0) {
                    throw new BusinessRuleException("Elke palletregel moet een product en positief aantal dozen hebben");
                }
                if (!productsOnOrder.contains(item.productId()) || !byId.containsKey(item.productId())) {
                    throw new BusinessRuleException(
                            "Product " + item.productId() + " staat niet op deze offerte");
                }
                assigned.merge(item.productId(), item.cartons(), Integer::sum);
            }
        }

        for (SalesOrderLine line : order.lines()) {
            Product product = byId.get(line.productId());
            int orderedCartons = product.carton() == null
                    ? Math.max(0, line.quantity())
                    : product.carton().cartonsFor(line.quantity());
            if (assigned.getOrDefault(line.productId(), 0) > orderedCartons) {
                throw new BusinessRuleException(
                        "Er staan meer dozen van " + product.describe() + " op pallets dan op de offerte");
            }
        }
    }

    private static SalesOrder withCarrier(SalesOrder order, Long freightCarrierId) {
        return new SalesOrder(order.id(), order.number(), order.customerId(), order.countryCode(),
                order.orderDate(), order.validUntil(), order.status(), order.incoterm(),
                order.paymentTerms(), order.notes(), order.markupMode(), order.orderMarkupPct(),
                order.extraDiscountPct(), order.extraDiscountLabel(), order.portalToken(),
                order.sentAt(), order.viewedAt(), order.viewCount(), order.decidedAt(),
                order.signedByName(), order.customerMessage(), order.internalNotes(),
                order.deliveryTerms(), order.freight(), order.manualFreightEur(),
                order.loadMode(), order.palletProfile(), order.maxPalletHeightCm(),
                order.freightPricingStrategy(), order.freightRatePerCbmEur(), freightCarrierId,
                order.freightCarrierExtraEur(),
                order.docType(), order.invoiceDueDate(), order.paidAt(), order.sourceQuoteId(),
                order.lines(), order.pallets());
    }

    private static SalesOrder copyWithTerms(SalesOrder order, FreightState freight,
                                            BigDecimal manualFreightEur,
                                            FreightPricingStrategy freightPricingStrategy,
                                            BigDecimal freightRatePerCbmEur,
                                            List<SalesOrderLine> lines) {
        return new SalesOrder(order.id(), order.number(), order.customerId(), order.countryCode(),
                order.orderDate(), order.validUntil(), order.status(), order.incoterm(),
                order.paymentTerms(), order.notes(), order.markupMode(), order.orderMarkupPct(),
                order.extraDiscountPct(), order.extraDiscountLabel(), order.portalToken(),
                order.sentAt(), order.viewedAt(), order.viewCount(), order.decidedAt(),
                order.signedByName(), order.customerMessage(), order.internalNotes(),
                order.deliveryTerms(), freight, manualFreightEur,
                order.loadMode(), order.palletProfile(), order.maxPalletHeightCm(),
                freightPricingStrategy, freightRatePerCbmEur, order.freightCarrierId(),
                order.freightCarrierExtraEur(),
                order.docType(), order.invoiceDueDate(), order.paidAt(), order.sourceQuoteId(),
                lines, order.pallets());
    }

    private static FreightPricingStrategy freightStrategyForUpdate(SalesOrder current,
                                                                    SalesOrder changes) {
        if (changes.freightPricingStrategyOrNull() != null) {
            return changes.freightPricingStrategy();
        }
        if (changes.manualFreightEur() != null) return FreightPricingStrategy.FIXED;
        if (current.freightPricingStrategy() == FreightPricingStrategy.FIXED) {
            return FreightPricingStrategy.COUNTRY_PALLET;
        }
        return current.freightPricingStrategy();
    }

    private static boolean hasValidOuterCarton(Carton carton) {
        if (carton == null || carton.piecesPerCarton() <= 0 || carton.dimensions() == null) {
            return false;
        }
        Dimensions size = carton.dimensions();
        return positive(size.lengthCm()) && positive(size.widthCm()) && positive(size.heightCm());
    }

    private static boolean positive(BigDecimal value) {
        return value != null && value.signum() > 0;
    }

    private static void requirePercentage(BigDecimal value, String label) {
        if (value != null && (value.signum() < 0 || value.compareTo(BigDecimal.valueOf(100)) > 0)) {
            throw new BusinessRuleException(label + " moet tussen 0 en 100% liggen");
        }
    }

    private static void requireNonNegative(BigDecimal value, String label) {
        if (value != null && value.signum() < 0) {
            throw new BusinessRuleException(label + " kan niet negatief zijn");
        }
    }

    private static String clean(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /**
     * We never sell half a carton: every quantity rounds up to whole outer
     * cartons on save. The screen announces the correction; this makes it
     * true no matter which client wrote the order.
     */
    private List<SalesOrderLine> roundLinesToCartons(List<SalesOrderLine> lines) {
        if (lines == null || lines.isEmpty()) return lines;
        Map<Long, Product> byId = products.list().stream()
                .collect(Collectors.toMap(Product::id, Function.identity()));
        return lines.stream().map(line -> {
            if (line == null || line.quantity() <= 0) return line;
            Product product = byId.get(line.productId());
            int per = product == null || product.carton() == null ? 1
                    : Math.max(1, product.carton().piecesPerCarton());
            int rounded = (int) Math.ceil(line.quantity() / (double) per) * per;
            return rounded == line.quantity() ? line
                    : new SalesOrderLine(line.id(), line.productId(), rounded,
                            line.unitPriceEur(), line.manualDiscountPct(), line.deliveryWeek());
        }).toList();
    }

    private String nextNumber() {
        return nextNumber("ENR-");
    }

    /** Invoices number their own gapless-enough series: F-2026-0001. */
    private String nextInvoiceNumber() {
        return nextNumber("F-");
    }

    private String nextNumber(String base) {
        int year = LocalDate.now().getYear();
        String prefix = base + year + "-";
        int highest = orders.findAll().stream()
                .map(SalesOrder::number)
                .filter(number -> number != null && number.startsWith(prefix))
                .map(number -> number.substring(prefix.length()))
                .filter(suffix -> suffix.matches("\\d+"))
                .mapToInt(Integer::parseInt)
                .max()
                .orElse(0);
        return prefix + String.format("%04d", highest + 1);
    }
}
