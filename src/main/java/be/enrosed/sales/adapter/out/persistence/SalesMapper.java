package be.enrosed.sales.adapter.out.persistence;

import be.enrosed.sales.adapter.out.persistence.SalesEntities.*;
import be.enrosed.sales.domain.*;

import java.util.ArrayList;
import java.util.List;

/** Translates between JPA entities and domain records. */
final class SalesMapper {

    private SalesMapper() {}

    /* --------------------------------------------------------- customer */

    static Customer toDomain(CustomerEntity entity) {
        return new Customer(entity.id, entity.company, entity.contact, entity.email, entity.phone,
                entity.vatNumber, entity.countryCode, entity.language,
                entity.address, entity.postalCode, entity.city,
                entity.incoterm, entity.paymentTerms, entity.notes, entity.createdAt);
    }

    static void apply(Customer customer, CustomerEntity entity) {
        entity.company = customer.company();
        entity.contact = customer.contact();
        entity.email = customer.email();
        entity.phone = customer.phone();
        entity.vatNumber = customer.vatNumber();
        entity.countryCode = customer.countryCode();
        entity.language = customer.language();
        entity.address = customer.address();
        entity.postalCode = customer.postalCode();
        entity.city = customer.city();
        entity.incoterm = customer.incoterm();
        entity.paymentTerms = customer.paymentTerms();
        entity.notes = customer.notes();
        entity.createdAt = customer.createdAt();
    }

    /* ------------------------------------------------------------- land */

    static Country toDomain(CountryEntity entity) {
        return new Country(entity.code, entity.name, entity.minOrderValue, entity.freightPerPallet,
                entity.minFreight, entity.handling, entity.vatRatePct, entity.transitDays, entity.euMember);
    }

    static void apply(Country country, CountryEntity entity) {
        entity.code = country.code();
        entity.name = country.name();
        entity.minOrderValue = country.minOrderValue();
        entity.freightPerPallet = country.freightPerPallet();
        entity.minFreight = country.minFreight();
        entity.handling = country.handling();
        entity.vatRatePct = country.vatRatePct();
        entity.transitDays = country.transitDays();
        entity.euMember = country.euMember();
    }

    /* ---------------------------------------------------------- staffel */

    static DiscountTier toDomain(DiscountTierEntity entity) {
        return new DiscountTier(entity.id, entity.scope, entity.minQuantity, entity.percent,
                entity.productId);
    }

    /* ------------------------------------------------------------ order */

    static SalesOrder toDomain(SalesOrderEntity entity) {
        List<SalesOrderLine> lines = new ArrayList<>();
        for (SalesOrderLineEntity line : entity.lines) {
            lines.add(new SalesOrderLine(line.id, line.productId, line.quantity,
                    line.unitPriceEur, line.manualDiscountPct, line.deliveryWeek));
        }
        List<OrderPallet> pallets = new ArrayList<>();
        for (SalesEntities.SalesPalletEntity pallet : entity.pallets) {
            pallets.add(new OrderPallet(pallet.id, pallet.label, pallet.palletType, pallet.heightCm,
                    pallet.items.stream()
                            .map(item -> new OrderPallet.Item(item.productId, item.cartons))
                            .toList()));
        }
        return new SalesOrder(entity.id, entity.number, entity.customerId, entity.countryCode,
                entity.orderDate, entity.validUntil, entity.status, entity.incoterm,
                entity.paymentTerms, entity.notes,
                entity.markupMode, entity.orderMarkupPct,
                entity.extraDiscountPct, entity.extraDiscountLabel,
                entity.portalToken, entity.sentAt, entity.viewedAt, entity.viewCount,
                entity.decidedAt, entity.signedByName, entity.customerMessage,
                entity.internalNotes, entity.deliveryTerms, entity.freight,
                entity.manualFreightEur,
                entity.loadMode, entity.palletProfile, entity.maxPalletHeightCm,
                entity.freightPricingStrategy, entity.freightRatePerCbmEur,
                entity.freightCarrierId, entity.freightCarrierExtraEur,
                entity.docType, entity.invoiceDueDate,
                entity.paidAt, entity.sourceQuoteId, entity.goodsShippedAt,
                lines, pallets, pickupSnapshot(entity), entity.archivedAt);
    }

    private static PickupLocationSnapshot pickupSnapshot(SalesOrderEntity entity) {
        if (entity.pickupLocationId == null && entity.pickupLocationLabel == null
                && entity.pickupLocationAddress == null
                && entity.pickupLocationInstructions == null) return null;
        return new PickupLocationSnapshot(entity.pickupLocationId,
                entity.pickupLocationLabel, entity.pickupLocationAddress,
                entity.pickupLocationInstructions);
    }

    static void apply(SalesOrder order, SalesOrderEntity entity) {
        entity.number = order.number();
        entity.customerId = order.customerId();
        entity.countryCode = order.countryCode();
        entity.orderDate = order.orderDate();
        entity.validUntil = order.validUntil();
        entity.status = order.status();
        entity.incoterm = order.incoterm();
        entity.paymentTerms = order.paymentTerms();
        entity.notes = order.notes();
        entity.markupMode = order.markupMode();
        entity.orderMarkupPct = order.orderMarkupPct();
        entity.extraDiscountPct = order.extraDiscountPct();
        entity.extraDiscountLabel = order.extraDiscountLabel();
        entity.portalToken = order.portalToken();
        entity.sentAt = order.sentAt();
        entity.viewedAt = order.viewedAt();
        entity.viewCount = order.viewCount();
        entity.decidedAt = order.decidedAt();
        entity.signedByName = order.signedByName();
        entity.customerMessage = order.customerMessage();
        entity.internalNotes = order.internalNotes();
        entity.deliveryTerms = order.deliveryTerms();
        entity.freight = order.freight();
        entity.manualFreightEur = order.manualFreightEur();
        entity.loadMode = order.loadMode();
        entity.palletProfile = order.palletProfile();
        entity.maxPalletHeightCm = order.maxPalletHeightCm();
        entity.freightPricingStrategy = order.freightPricingStrategy();
        entity.freightRatePerCbmEur = order.freightRatePerCbmEur();
        entity.freightCarrierId = order.freightCarrierId();
        entity.freightCarrierExtraEur = order.freightCarrierExtraEur();
        entity.docType = order.docType();
        entity.invoiceDueDate = order.invoiceDueDate();
        entity.paidAt = order.paidAt();
        entity.sourceQuoteId = order.sourceQuoteId();
        entity.goodsShippedAt = order.goodsShippedAt();
        /* Null means an older update client omitted the new field. Preserve an
           already captured website snapshot instead of silently erasing it. */
        if (order.pickupLocation() != null) {
            entity.pickupLocationId = order.pickupLocation().locationId();
            entity.pickupLocationLabel = order.pickupLocation().label();
            entity.pickupLocationAddress = order.pickupLocation().address();
            entity.pickupLocationInstructions = order.pickupLocation().instructions();
        }

        List<SalesOrderLine> wanted = order.lines();
        entity.lines.removeIf(existing -> wanted.stream()
                .noneMatch(line -> line.id() != null && line.id().equals(existing.id)));

        for (SalesOrderLine line : wanted) {
            SalesOrderLineEntity target = entity.lines.stream()
                    .filter(existing -> line.id() != null && line.id().equals(existing.id))
                    .findFirst()
                    .orElse(null);
            if (target == null) {
                target = new SalesOrderLineEntity();
                target.order = entity;
                entity.lines.add(target);
            }
            target.productId = line.productId();
            target.quantity = line.quantity();
            target.unitPriceEur = line.unitPriceEur();
            target.manualDiscountPct = line.manualDiscountPct();
            target.deliveryWeek = line.deliveryWeek();
        }

        /* Pallets carry no outside references, so wipe-and-rebuild is the
           simplest correct thing - unlike lines, whose ids feed revisions. */
        entity.pallets.clear();
        int position = 0;
        for (OrderPallet pallet : order.pallets()) {
            SalesEntities.SalesPalletEntity target = new SalesEntities.SalesPalletEntity();
            target.order = entity;
            target.position = position++;
            target.label = pallet.label();
            target.palletType = pallet.type();
            target.heightCm = pallet.heightCm();
            for (OrderPallet.Item item : pallet.items()) {
                SalesEntities.SalesPalletItemEntity row = new SalesEntities.SalesPalletItemEntity();
                row.pallet = target;
                row.productId = item.productId();
                row.cartons = item.cartons();
                target.items.add(row);
            }
            entity.pallets.add(target);
        }
    }

    /* --------------------------------------------------------- voorstel */

    static QuoteRevision toDomain(QuoteRevisionEntity entity) {
        List<QuoteRevision.Line> lines = new ArrayList<>();
        for (QuoteRevisionLineEntity line : entity.lines) {
            lines.add(new QuoteRevision.Line(line.id, line.productId, line.quantity, line.note));
        }
        return new QuoteRevision(entity.id, entity.salesOrderId, entity.status, entity.proposedAt,
                entity.proposedBy, entity.message, entity.handledAt, entity.handledBy,
                entity.responseMessage, lines);
    }

    static void apply(QuoteRevision revision, QuoteRevisionEntity entity) {
        entity.salesOrderId = revision.salesOrderId();
        entity.status = revision.status();
        entity.proposedAt = revision.proposedAt();
        entity.proposedBy = revision.proposedBy();
        entity.message = revision.message();
        entity.handledAt = revision.handledAt();
        entity.handledBy = revision.handledBy();
        entity.responseMessage = revision.responseMessage();

        if (entity.lines.isEmpty()) {
            for (QuoteRevision.Line line : revision.lines()) {
                QuoteRevisionLineEntity target = new QuoteRevisionLineEntity();
                target.revision = entity;
                target.productId = line.productId();
                target.quantity = line.quantity();
                target.note = line.note();
                entity.lines.add(target);
            }
        }
    }

    /* -------------------------------------------------------- geschiedenis */

    static QuoteEvent toDomain(SalesEntities.QuoteEventEntity entity) {
        return new QuoteEvent(entity.id, entity.salesOrderId, entity.type, entity.at,
                entity.actor, entity.byCustomer, entity.summary, entity.detail);
    }
}
