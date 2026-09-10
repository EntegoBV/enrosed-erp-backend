package be.enrosed.sales.application;

import be.enrosed.sales.adapter.out.persistence.PartnerInvoiceDeclarationEntity;
import be.enrosed.sales.adapter.out.persistence.SalesEntities.SalesOrderEntity;
import be.enrosed.sales.domain.*;
import be.enrosed.shared.BusinessRuleException;
import be.enrosed.shared.Language;
import be.enrosed.shared.audit.ActivityChangeDto;
import be.enrosed.shared.audit.ActivityLogService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import java.time.Instant;
import java.util.List;
import java.util.Locale;

/** Staff-selected wording, persisted independently of all tax and price calculations. */
@ApplicationScoped
public class PartnerInvoiceDeclarations {
    public enum Mode { DEFAULT, CUSTOMS_REPRESENTATIVE, REVERSE_CHARGE }
    /** Approved wording is deliberately English on every language version of this document. */
    public static final String CUSTOMS_TEXT_V1 = "Custom cleared in The Netherlands by our Limited Fiscal Representative: 24/7 Customs BV with VAT-no: NL858617262B02";
    public static final String REVERSE_CHARGE_TEXT_V1 = "“REVERSE CHARGE”: VAT shifted to Dutch customer according to article 12.3 Dutch VAT-Law.";
    public record Declaration(Mode mode, String reference, int textVersion) {
        public static Declaration defaults() { return new Declaration(Mode.DEFAULT, null, 1); }
    }
    public record Presentation(boolean replaceCustomsLine, String additionalText, String legalMentionOverride,
                               String referenceText) {}

    @Inject EntityManager entities;
    @Inject SalesOrderService sales;
    @Inject CustomerService customers;
    @Inject ActivityLogService activity;

    public Declaration get(long id) {
        sales.get(id); // Missing documents must not appear to have usable defaults.
        return find(id);
    }

    private Declaration find(long id) {
        var stored = entities.find(PartnerInvoiceDeclarationEntity.class, id);
        return stored == null ? Declaration.defaults() : domain(stored);
    }

    @Transactional
    public Declaration save(long id, Declaration request) {
        // Same purchase -> document lock order as issue/send; refreshes an earlier cached CONCEPT.
        sales.lockDocumentForMutation(id);
        SalesOrder order = sales.get(id);
        requirePartnerInvoice(order);
        if (order.status() != QuoteStatus.CONCEPT || order.archivedAt() != null)
            throw new BusinessRuleException("De factuurvermelding kan alleen op een actieve conceptfactuur worden gewijzigd");
        Declaration clean = clean(request);
        validate(clean, order, sales.price(order), customers.get(order.customerId()));
        var stored = entities.find(PartnerInvoiceDeclarationEntity.class, id);
        if (stored != null) entities.refresh(stored);
        Declaration before = stored == null ? Declaration.defaults() : domain(stored);
        if (before.equals(clean)) return clean;
        if (clean.mode() == Mode.DEFAULT) {
            if (stored != null) entities.remove(stored);
        } else {
            boolean fresh = stored == null;
            if (fresh) {
                stored = new PartnerInvoiceDeclarationEntity();
                stored.salesOrderId = id;
                stored.order = entities.getReference(SalesOrderEntity.class, id);
            }
            stored.mode = clean.mode();
            stored.reference = clean.reference(); stored.textVersion = clean.textVersion();
            stored.updatedAt = Instant.now();
            if (fresh) entities.persist(stored);
        }
        entities.flush();
        activity.record(ActivityLogService.ACTION_UPDATED, "SALES_ORDER", Long.toString(id), order.number(),
                "Factuurvermelding bijgewerkt", List.of(
                        new ActivityChangeDto("invoiceDeclaration.mode", "Factuurvermelding", before.mode().name(), clean.mode().name()),
                        new ActivityChangeDto("invoiceDeclaration.reference", "Dossierreferentie", before.reference(), clean.reference())));
        return clean;
    }

    /** Explicit issuance and dispatch also check the current tax context, without repricing or changing it. */
    public void validateForIssue(SalesOrder order, Customer customer) {
        if (order.id() == null) return;
        Declaration declaration = find(order.id());
        if (declaration.mode() != Mode.DEFAULT) validate(declaration, order, sales.price(order), customer);
    }

    /** Shared by downloaded and emailed PDFs; never controlled by optional presentation switches. */
    public Presentation presentation(SalesOrder order, PricedOrder priced, Customer customer, Language language) {
        return presentation(order.id() == null ? Declaration.defaults() : find(order.id()), order, priced, customer, language);
    }

    public static Presentation presentation(Declaration declaration, SalesOrder order, PricedOrder priced,
                                            Customer customer, Language language) {
        validate(declaration, order, priced, customer);
        if (declaration.mode() == Mode.DEFAULT) return new Presentation(false, null, null, null);
        String reference = declaration.reference() == null ? null
                : "File reference: " + declaration.reference() + ".";
        if (declaration.mode() == Mode.CUSTOMS_REPRESENTATIVE) {
            return new Presentation(true, CUSTOMS_TEXT_V1, REVERSE_CHARGE_TEXT_V1, reference);
        }
        return new Presentation(false, null, REVERSE_CHARGE_TEXT_V1, reference);
    }

    private static Declaration clean(Declaration request) {
        if (request == null || request.mode() == null)
            throw new BusinessRuleException("Kies een factuurvermelding");
        if (request.textVersion() != 1)
            throw new BusinessRuleException("Deze tekstversie wordt niet ondersteund; laad de factuurinstellingen opnieuw");
        if (request.mode() == Mode.DEFAULT) return Declaration.defaults();
        return new Declaration(request.mode(), line(request.reference(), "Dossierreferentie"), 1);
    }

    private static void validate(Declaration declaration, SalesOrder order, PricedOrder priced, Customer customer) {
        Declaration clean = clean(declaration);
        if (clean.mode() == Mode.DEFAULT) return;
        requirePartnerInvoice(order);
        if (clean.mode() == Mode.CUSTOMS_REPRESENTATIVE && !order.isPartnerAdvance())
            throw new BusinessRuleException("De inklaringsvermelding hoort alleen bij een partnervoorschotfactuur");
        if (clean.mode() == Mode.REVERSE_CHARGE && order.purpose() != SalesPurpose.PARTNER_SETTLEMENT)
            throw new BusinessRuleException("Deze verleggingsvermelding hoort alleen bij een partnerslotfactuur");
        String vat = customer == null || customer.vatNumber() == null ? ""
                : customer.vatNumber().replaceAll("[\\s.]", "").toUpperCase(Locale.ROOT);
        if (!"NL".equalsIgnoreCase(order.countryCode())
                || !vat.matches("NL[0-9A-Z]{2,12}") || priced == null || priced.totals() == null
                || priced.totals().vatTreatment() != VatTreatment.VERLEGD_FISCAAL_VERTEGENWOORDIGER
                || priced.totals().vatRatePct() == null || priced.totals().vatAmount() == null
                || priced.totals().vatRatePct().signum() != 0 || priced.totals().vatAmount().signum() != 0)
            throw new BusinessRuleException("Deze factuurvermelding vereist een Nederlandse bestemming, Nederlands klant-btw-nummer en de bestaande btw-verlegging via fiscaal vertegenwoordiger. Controleer de btw-instellingen of kies de standaardvermelding");
        if (blank(customer.address()) || blank(customer.postalCode()) || blank(customer.city()) || blank(customer.countryCode()))
            throw new BusinessRuleException("Vul het volledige klantadres in (straat, postcode, plaats en land) voor deze factuurvermelding");
    }

    private static void requirePartnerInvoice(SalesOrder order) {
        if (order == null || !order.isInvoice() || order.purpose() != SalesPurpose.PARTNER_ADVANCE
                && order.purpose() != SalesPurpose.PARTNER_SETTLEMENT)
            throw new BusinessRuleException("Deze factuurvermelding is alleen beschikbaar voor partnerfacturen");
    }

    private static String line(String value, String label) {
        if (value == null || value.isBlank()) return null;
        String clean = value.strip();
        if (clean.length() > 160 || clean.codePoints().anyMatch(Character::isISOControl))
            throw new BusinessRuleException(label + " mag maximaal 160 tekens op één regel bevatten");
        return clean;
    }

    private static Declaration domain(PartnerInvoiceDeclarationEntity row) {
        return new Declaration(row.mode, row.reference, row.textVersion);
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }
}
