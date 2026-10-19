package be.enrosed.tica;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/** Financial evidence stays private and is only served through the admin-authenticated resource. */
@Entity @Table(name = "tica_settlement_document", uniqueConstraints = @UniqueConstraint(columnNames = "sha256"))
public class TicaSettlementDocument {
    @Id public UUID id;
    @Column(nullable = false, length = 64) public String sha256;
    @Column(name = "file_name", nullable = false, length = 200) public String fileName;
    @Column(name = "content_type", nullable = false, length = 100) public String contentType;
    @Column(name = "pdf_bytes", nullable = false) public byte[] pdfBytes;
    @Column(name = "created_at", nullable = false) public Instant createdAt;
}
