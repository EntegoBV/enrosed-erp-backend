package be.enrosed.sales.application;

import be.enrosed.sales.adapter.out.persistence.SalesEntities.SalesOrderEntity;
import be.enrosed.sales.application.port.out.SalesRepositories;
import be.enrosed.sales.domain.*;
import be.enrosed.shared.Language;
import be.enrosed.shared.company.CompanyProfile;
import be.enrosed.shared.company.CompanyProfileService;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Credit notes number their own journal; the invoice and quote series never notice them. */
@QuarkusTest
class SalesOrderCreditNoteNumberingTest {
    @Inject SalesOrderService sales;
    @Inject CustomerService customers;
    @Inject CompanyProfileService company;
    @Inject SalesRepositories.Orders orders;
    @Inject EntityManager em;

    @Test @TestTransaction
    void creditNotesNeverConsumeInvoiceNumbersAndViceVersaAndADeletedOneKeepsItsNumber() {
        long customer = customer();
        int year = LocalDate.now().getYear();
        var invoice = issued(customer);
        var cn1 = credit(invoice);
        assertTrue(cn1.number().matches("CN-" + year + "-\\d{4}"), cn1.number());
        assertEquals(DocumentType.CREDITNOTA, orders.numbersIncludingDeleted().stream()
                .filter(n -> n.id() == cn1.id()).findFirst().orElseThrow().docType());
        assertFalse(orders.numbersIncludingDeleted().stream().filter(n -> n.id() == cn1.id()).findFirst().orElseThrow().invoice());

        var nextInvoice = issued(customer);
        assertEquals(sequence(invoice.number()) + 1, sequence(nextInvoice.number()), "the credit note left the invoice series alone");
        var cn2 = credit(nextInvoice);
        assertEquals(sequence(cn1.number()) + 1, sequence(cn2.number()), "the invoice left the credit note series alone");
        var quote = sales.create(customer, "BE", "DAP", DocumentType.OFFERTE);
        var laterQuote = sales.create(customer, "BE", "DAP", DocumentType.OFFERTE);
        assertEquals(sequence(quote.number()) + 1, sequence(laterQuote.number()));

        sales.delete(cn2.id());
        var cn3 = credit(nextInvoice);
        assertEquals(sequence(cn2.number()) + 1, sequence(cn3.number()), "a deleted credit note keeps its number reserved");

        CompanyProfile profile = company.get();
        company.save(new CompanyProfile(profile.name(), profile.legalName(), profile.vatNumber(), profile.registrationNumber(),
                profile.addressLine(), profile.postalCode(), profile.city(), profile.countryCode(), profile.email(), profile.phone(),
                profile.website(), profile.iban(), profile.bic(), profile.documentFooter(), profile.documentFooterEn(),
                profile.termsAndConditions(), profile.termsAndConditionsEn(), profile.privacyPolicy(), profile.privacyPolicyEn(),
                profile.fiscalRepresentativeName(), profile.fiscalRepresentativeVat(), profile.quoteNumberPrefix(),
                profile.invoiceNumberPrefix(), profile.partnerQuoteNumberPattern(), profile.partnerInvoiceNumberPattern(),
                profile.partnerQuoteNextNumber(), profile.partnerInvoiceNextNumber(), " kn "));
        assertEquals("KN", company.get().creditNotePrefix());
        var cn4 = credit(issued(customer));
        assertEquals("KN-" + year + "-" + String.format("%04d", sequence(cn3.number()) + 1), cn4.number(), "a changed prefix carries the count on");
        assertEquals(sequence(cn4.number()) + 1, sequence(credit(issued(customer)).number()));
    }

    private static int sequence(String number) {
        return Integer.parseInt(number.substring(number.lastIndexOf('-') + 1));
    }

    private SalesOrder credit(SalesOrder invoice) {
        return sales.createCreditNote(invoice.id(), new SalesOrderService.CreditNoteRequest(CreditReason.OTHER, List.of(),
                List.of(new SalesOrderService.CreditAmount("Korting", new BigDecimal("10"))), false, null));
    }

    private long customer() {
        return customers.create(new Customer(null, "Numbering " + UUID.randomUUID(), "Buyer", null, null, "BE0000000000", "BE",
                Language.NL, "Main 1", "2000", "Antwerpen", "DAP", null, null, LocalDate.now())).id();
    }

    private SalesOrder issued(long customer) {
        var created = sales.create(customer, "BE", "DAP", DocumentType.FACTUUR);
        var stored = em.find(SalesOrderEntity.class, created.id());
        stored.extraLinesJson = "[{\"description\":\"Service\",\"quantity\":1,\"unitPriceEur\":100}]";
        stored.freightPricingStrategy = FreightPricingStrategy.FIXED;
        stored.manualFreightEur = BigDecimal.ZERO;
        stored.freight = FreightState.AANGEVULD;
        stored.status = QuoteStatus.UITGEREIKT;
        em.flush(); em.clear();
        return sales.get(created.id());
    }
}
