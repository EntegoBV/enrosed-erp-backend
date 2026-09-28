package be.enrosed.prospect;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import java.time.Instant;
import static be.enrosed.prospect.ProspectDtos.*;

@Entity
@Table(name = "prospect", indexes = {
        @Index(name = "idx_prospect_status_country", columnList = "status,country_code"),
        @Index(name = "idx_prospect_group", columnList = "group_key")
}, uniqueConstraints = {
        @UniqueConstraint(name = "uq_prospect_email", columnNames = "email"),
        @UniqueConstraint(name = "uq_prospect_instagram", columnNames = "instagram_handle")
})
public class ProspectEntity extends PanacheEntityBase {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
    @Column(name = "business_name", nullable = false, length = 200) public String businessName;
    @Column(name = "country_code", nullable = false, length = 2) public String countryCode;
    @Column(length = 16) public String language;
    @Column(length = 254) public String email;
    @Column(length = 2000) public String website;
    @Column(name = "instagram_handle", length = 30) public String instagramHandle;
    @Column(name = "group_key", length = 200) public String groupKey;
    @Column(name = "source_url", length = 2000) public String sourceUrl;
    @Column(name = "source_type", length = 64) public String sourceType;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 32) public ProspectStatus status;
    @Column(columnDefinition = "text") public String notes;
    @Column(name = "created_at", nullable = false) public Instant createdAt;
    @Column(name = "updated_at", nullable = false) public Instant updatedAt;
    @Column(name = "last_activity_at") public Instant lastActivityAt;
    @Column(name = "last_contact_at") public Instant lastContactAt;

    public Prospect dto() {
        return new Prospect(id, businessName, countryCode, language, email, website, instagramHandle,
                groupKey, sourceUrl, sourceType, status, notes, createdAt, updatedAt, lastActivityAt, lastContactAt);
    }
}
