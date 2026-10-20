package be.enrosed.tica;

import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityManager;
import jakarta.ws.rs.NotFoundException;
import java.math.BigDecimal;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

@ApplicationScoped
public class TicaSettlementPdfService {
    private final TicaSettlementPdfParser parser;
    private final EntityManager em;
    public TicaSettlementPdfService(TicaSettlementPdfParser parser, EntityManager em) { this.parser = parser; this.em = em; }
    public record Preview(String sourceDocumentUrl, String fileName, String reference, String location,
                          LocalDate periodFrom, LocalDate periodTo, LocalDate documentDate, LocalDate dueDate,
                          BigDecimal salesExcl, BigDecimal salesVat, BigDecimal commissionExcl,
                          BigDecimal commissionVat, BigDecimal netReceivable, List<String> warnings) {}
    public record Document(String fileName, String contentType, byte[] bytes) {}

    public Preview preview(byte[] bytes, String filename) {
        return store(parser.parse(bytes), bytes, filename, "application/pdf");
    }
    public Preview previewXml(byte[] bytes, String filename) {
        return store(new TicaSettlementXmlParser().parse(bytes), bytes, filename, "application/xml");
    }
    private Preview store(TicaSettlementPdfParser.Parsed parsed, byte[] bytes, String filename, String contentType) {
        String hash;
        try { hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (Exception impossible) { throw new IllegalStateException(impossible); }
        String safeName = filename == null ? "tica-afrekening.pdf" : filename.replaceAll("[^A-Za-z0-9._ -]", "_");
        if (safeName.length() > 190) safeName = safeName.substring(0, 190);
        String extension = contentType.equals("application/xml") ? ".xml" : ".pdf";
        if (!safeName.toLowerCase(java.util.Locale.ROOT).endsWith(extension)) safeName += extension;
        String finalName = safeName;
        TicaSettlementDocument document;
        try {
            document = QuarkusTransaction.requiringNew().call(() -> {
                var existing = findHash(hash);
                if (existing != null) return existing;
                var row = new TicaSettlementDocument(); row.id = UUID.randomUUID(); row.sha256 = hash;
                row.fileName = finalName; row.contentType = contentType; row.pdfBytes = bytes.clone(); row.createdAt = Instant.now();
                em.persist(row); em.flush(); return row;
            });
        } catch (RuntimeException conflict) {
            document = QuarkusTransaction.requiringNew().call(() -> findHash(hash));
            if (document == null) throw conflict;
        }
        return new Preview("/api/tica/settlements/documents/" + document.id, document.fileName, parsed.reference(),
                parsed.location(), parsed.periodFrom(), parsed.periodTo(), parsed.documentDate(), parsed.dueDate(), parsed.salesExcl(),
                parsed.salesVat(), parsed.commissionExcl(), parsed.commissionVat(), parsed.netReceivable(), parsed.warnings());
    }
    public Document document(UUID id) {
        return QuarkusTransaction.requiringNew().call(() -> {
            var row = em.find(TicaSettlementDocument.class, id);
            if (row == null) throw new NotFoundException();
            return new Document(row.fileName, row.contentType, row.pdfBytes.clone());
        });
    }
    private TicaSettlementDocument findHash(String hash) {
        return em.createQuery("from TicaSettlementDocument where sha256=:hash", TicaSettlementDocument.class)
                .setParameter("hash", hash).getResultStream().findFirst().orElse(null);
    }
}
