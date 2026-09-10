package be.enrosed.shared.trash;

import jakarta.persistence.*;
import java.time.Instant;

/** Retains a read-only snapshot; expiry closes user access, never destroys accounting data. */
@Entity(name = "DeletedItem")
@Table(name = "deleted_item", uniqueConstraints = @UniqueConstraint(columnNames = {"source_kind", "source_id"}),
        indexes = @Index(columnList = "expires_at,deleted_at"))
public class DeletedItemEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
    @Column(name = "source_kind", nullable = false, length = 16) public String sourceKind;
    @Column(name = "source_id", nullable = false) public Long sourceId;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) public DeletedItemDtos.Type type;
    public String number;
    public String partyName;
    public String status;
    @Column(name = "deleted_at", nullable = false) public Instant deletedAt;
    @Column(name = "expires_at", nullable = false) public Instant expiresAt;
    public String deletedBy;
    @Column(name = "snapshot_json", nullable = false, columnDefinition = "text") public String snapshotJson;
    @Column(name = "order_json", nullable = false, columnDefinition = "text") public String orderJson;
    @Column(name = "partner_context_json", columnDefinition = "text") public String partnerContextJson;
}
