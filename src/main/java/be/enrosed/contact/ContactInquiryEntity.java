package be.enrosed.contact;

import be.enrosed.shared.Language;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "contact_inquiry", indexes = {
        @Index(name = "idx_contact_inquiry_status_created", columnList = "status,created_at"),
        @Index(name = "idx_contact_inquiry_created_id", columnList = "created_at,id"),
        @Index(name = "idx_contact_inquiry_reference", columnList = "reference", unique = true)
})
public class ContactInquiryEntity extends PanacheEntityBase {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(nullable = false, unique = true, length = 32)
    public String reference;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    public ContactInquiryStatus status;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, columnDefinition = "varchar(4)")
    public Language language;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    public ContactTopic topic;

    @Column(name = "contact_name", nullable = false, length = 120)
    public String contactName;

    @Column(nullable = false, length = 254)
    public String email;

    @Column(name = "company_name", length = 160)
    public String companyName;

    @Column(length = 50)
    public String phone;

    @Column(nullable = false, length = 2_000)
    public String message;

    @Column(name = "source_page", length = 500)
    public String sourcePage;

    @Column(name = "privacy_accepted_at", nullable = false)
    public Instant privacyAcceptedAt;

    @Column(name = "privacy_policy_version", nullable = false, length = 40)
    public String privacyPolicyVersion;

    @Column(name = "created_at", nullable = false)
    public Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    public Instant updatedAt;
}
