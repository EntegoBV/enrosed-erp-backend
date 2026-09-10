package be.enrosed.sales.application;

import be.enrosed.catalog.application.CatalogMutationLock;
import be.enrosed.sales.application.port.out.SalesRepositories;
import be.enrosed.sales.domain.QuoteStatus;
import be.enrosed.sales.domain.SalesOrder;
import be.enrosed.shared.BusinessRuleException;
import be.enrosed.shared.NotFoundException;
import be.enrosed.sourcing.application.PurchaseOrderService;
import be.enrosed.sourcing.domain.PurchaseOrder;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** A container and its unused advances leave the working lists in a single recoverable transaction. */
@ApplicationScoped
public class PartnerContainerDeletionService {
    @Inject CatalogMutationLock catalogLock;
    @Inject PurchaseOrderService purchases;
    @Inject SalesOrderService sales;
    @Inject SalesRepositories.Orders orders;

    public record Invoice(long id, String number, QuoteStatus status, BigDecimal totalEur) {}
    public record Preview(long purchaseOrderId, String number, boolean allowed, String blockReason, List<Invoice> invoices) {}
    public record Request(List<Long> expectedInvoiceIds) {}
    public record Deleted(long purchaseOrderId, List<Long> deletedInvoiceIds) {}

    @Transactional
    public Preview preview(long id) {
        var purchase = purchases.get(id);
        var linked = linkedDocuments(id);
        String reason = null;
        try { requireDeletable(purchase, linked); }
        catch (BusinessRuleException blocked) { reason = blocked.getMessage(); }
        var invoices = linked.stream().filter(SalesOrder::isInvoice).map(order ->
                new Invoice(order.id(), order.number(), order.status(), previewAmount(order))).toList();
        return new Preview(id, purchase.number(), reason == null, reason, invoices);
    }

    @Transactional
    public Deleted delete(long id, Request request) {
        catalogLock.acquire();
        var purchase = purchases.lockForPartnerSettlement(id);
        var linked = linkedDocuments(id);
        // Reject ordinary documents before entering the partner-specific parent/document lock order.
        requireDeletable(purchase, linked);
        // All creation/link/payment workflows lock the purchase before these documents.
        for (var order : linked) sales.lockDocumentForMutation(order.id());
        linked = linkedDocuments(id);
        requireDeletable(purchase, linked);
        List<Long> ids = linked.stream().map(SalesOrder::id).toList();
        if (request == null || request.expectedInvoiceIds() == null
                || request.expectedInvoiceIds().stream().anyMatch(Objects::isNull)
                || !ids.equals(request.expectedInvoiceIds().stream().sorted().toList())) {
            throw new BusinessRuleException("De gekoppelde voorschotfacturen zijn gewijzigd. Open de verwijderbevestiging opnieuw.");
        }
        // The purchase must remain visible while each invoice freezes its partner context and frees its slot.
        for (Long invoiceId : ids) sales.delete(invoiceId);
        purchases.delete(id);
        return new Deleted(id, ids);
    }

    private void requireDeletable(PurchaseOrder purchase, List<SalesOrder> linked) {
        if (!purchase.isPartnerContainer())
            throw new BusinessRuleException("Deze inkooporder is geen partnercontainer.");
        purchases.requireDeletableHistory(purchase);
        for (var order : linked) {
            if (!order.isInvoice() || !order.isPartnerAdvance())
                throw new BusinessRuleException("Deze container heeft een gekoppelde offerte of slotfactuur. Verwijder die eerst afzonderlijk of archiveer de container.");
            if (order.status() != QuoteStatus.CONCEPT)
                throw new BusinessRuleException("Voorschotfactuur " + order.number() + " is geen concept. Alleen ongebruikte conceptvoorschotten kunnen samen met de container verwijderd worden.");
            try { sales.requireDeletable(order); }
            catch (BusinessRuleException blocked) {
                throw new BusinessRuleException("Voorschotfactuur " + order.number() + ": " + blocked.getMessage());
            }
        }
    }

    private List<SalesOrder> linkedDocuments(long id) {
        return orders.findAll().stream().filter(order -> Long.valueOf(id).equals(order.linkedPurchaseOrderId()))
                .sorted(Comparator.comparing(SalesOrder::id)).toList();
    }

    private BigDecimal previewAmount(SalesOrder order) {
        try { return sales.price(order).totals().totalInclVat(); }
        catch (BusinessRuleException | NotFoundException incomplete) { return null; }
    }
}
