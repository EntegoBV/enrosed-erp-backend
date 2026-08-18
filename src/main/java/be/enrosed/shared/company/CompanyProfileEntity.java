package be.enrosed.shared.company;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Eén rij; het bedrijf heeft maar één profiel. */
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

    /** General terms and conditions, plain text. */
    @Column(length = 20000)
    public String termsAndConditions;

    @Column(length = 20000)
    public String termsAndConditionsEn;

    @Column(length = 20000)
    public String privacyPolicy;

    @Column(length = 20000)
    public String privacyPolicyEn;
}
