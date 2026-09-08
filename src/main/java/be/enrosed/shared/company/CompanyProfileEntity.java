package be.enrosed.shared.company;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** One row; the company has only one profile. */
@Entity
@Table(name = "company_profile")
public class CompanyProfileEntity {

    @Id
    public Long id = 1L;

    public String name;
    public String legalName;
    public String vatNumber;
    public String registrationNumber;

    public String addressLine;
    public String postalCode;
    public String city;
    public String countryCode;

    public String email;
    public String phone;
    public String website;

    public String iban;
    public String bic;

    @Column(length = 2000)
    public String documentFooter;

    @Column(length = 2000)
    public String documentFooterEn;

    /** General terms and conditions, plain text. */
    @Column(length = 20000)
    public String termsAndConditions;

    @Column(length = 20000)
    public String termsAndConditionsEn;

    @Column(length = 20000)
    public String privacyPolicy;

    @Column(length = 20000)
    public String privacyPolicyEn;

    /** Our limited fiscal representative in the Netherlands, named on documents for customers cleared through it. */
    @Column(name = "fiscal_representative_name")
    public String fiscalRepresentativeName;
    @Column(name = "fiscal_representative_vat")
    public String fiscalRepresentativeVat;

    /** The letters in front of document numbers: ENR-2026-0001, F-2026-0001. */
    @Column(name = "quote_number_prefix", length = 12)
    public String quoteNumberPrefix;
    @Column(name = "invoice_number_prefix", length = 12)
    public String invoiceNumberPrefix;

    /** Partner documents number their own series, written from a pattern such as partner/{jaar}/{nr:3}. */
    @Column(name = "partner_quote_number_pattern", length = 60)
    public String partnerQuoteNumberPattern;
    @Column(name = "partner_invoice_number_pattern", length = 60)
    public String partnerInvoiceNumberPattern;
    @Column(name = "partner_quote_next_number")
    public Integer partnerQuoteNextNumber;
    @Column(name = "partner_invoice_next_number")
    public Integer partnerInvoiceNextNumber;
}
