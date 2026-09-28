package be.enrosed.prospect;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import java.time.Instant;
import static be.enrosed.prospect.ProspectDtos.*;

@Entity
@Table(name = "prospect_activity", indexes = {
        @Index(name = "idx_prospect_activity_timeline", columnList = "prospect_id,occurred_at"),
        @Index(name = "idx_prospect_activity_email_day", columnList = "channel,status,occurred_at")
}, uniqueConstraints = @UniqueConstraint(name = "uq_prospect_activity_external", columnNames = "external_id"))
public class ProspectActivityEntity extends PanacheEntityBase {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "prospect_id", nullable = false) public ProspectEntity prospect;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16) public Channel channel;
    @Column(nullable = false, length = 64) public String type;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16) public ActivityStatus status;
    @Column(length = 300) public String subject;
    @Column(columnDefinition = "text") public String body;
    @Column(name = "occurred_at", nullable = false) public Instant occurredAt;
    @Column(name = "external_id", length = 200) public String externalId;
    @Column(name = "attachment_name", length = 255) public String attachmentName;
    @Column(name = "source_url", length = 2000) public String sourceUrl;
    @Column(name = "created_at", nullable = false) public Instant createdAt;
    @Column(name = "created_by", nullable = false, length = 100) public String createdBy;
    @Column(name = "recipient_email", length = 254) public String recipientEmail;
    @Column(name = "group_key", length = 200) public String groupKey;

    public Activity dto() {
        return new Activity(id, prospect.id, channel, type, status, subject, body, occurredAt,
                externalId, attachmentName, sourceUrl, createdAt, createdBy, recipientEmail, groupKey);
    }
}
