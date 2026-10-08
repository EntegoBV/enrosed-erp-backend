package be.enrosed.inventory.application;

import be.enrosed.catalog.application.CatalogWorkbook;
import be.enrosed.catalog.application.port.out.PhotoStorage;
import be.enrosed.inventory.adapter.out.persistence.InventoryStore;
import be.enrosed.inventory.adapter.out.persistence.StockClosingDecisionEntity;
import be.enrosed.inventory.adapter.out.persistence.StockClosingEntity;
import be.enrosed.inventory.application.ClosingNotices.Notice;
import be.enrosed.inventory.application.StockClosingService.View;
import be.enrosed.shared.NotFoundException;
import be.enrosed.shared.UnprocessableBusinessRuleException;
import be.enrosed.shared.audit.ActivityLogService;
import be.enrosed.shared.company.CompanyProfileService;
import be.enrosed.shared.security.ActorRef;
import be.enrosed.shared.security.CurrentActor;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * "Definitief maken", "Corrigeren (nieuwe versie)" and the two files of a
 * closing.
 *
 * Making a closing final freezes what the user saw: the figures are computed
 * once more under the row lock and refused when they differ from the ones on
 * the screen. The PDF and the workbook are rendered from the stored rows in
 * that same transaction and kept with their hashes, so every later download
 * returns the same bytes. A final closing is never changed and never
 * removed; a correction is a new version that names the one it replaces,
 * and only when that one becomes final is the old one marked as replaced.
 */
@ApplicationScoped
public class StockClosingFinalizer {

    public static final String ACTION_FINALIZED = "CLOSING_FINALIZED";
    public static final String ACTION_VERSION_STARTED = "CLOSING_VERSION_STARTED";
    public static final String PDF_MEDIA_TYPE = "application/pdf";

    /** Renders the valued stock list of a closing; the document adapter implements it. */
    public interface PdfRenderer {
        byte[] render(ClosingReportData data);
    }

    /** One of the two files with the name it is downloaded under. */
    public record File(String filename, String mediaType, byte[] content) {}

    private final StockClosingService closings;
    private final StockValuationRuleService rules;
    private final InventoryStore.Closings closingRows;
    private final InventoryStore.Decisions decisionRows;
    private final StockClosingWorkbook workbook;
    private final PdfRenderer pdf;
    private final PhotoStorage storage;
    private final CompanyProfileService company;
    private final CurrentActor actor;
    private final ActivityLogService activity;

    public StockClosingFinalizer(StockClosingService closings, StockValuationRuleService rules,
                                 InventoryStore.Closings closingRows, InventoryStore.Decisions decisionRows,
                                 StockClosingWorkbook workbook, PdfRenderer pdf, PhotoStorage storage,
                                 CompanyProfileService company, CurrentActor actor, ActivityLogService activity) {
        this.closings = closings;
        this.rules = rules;
        this.closingRows = closingRows;
        this.decisionRows = decisionRows;
        this.workbook = workbook;
        this.pdf = pdf;
        this.storage = storage;
        this.company = company;
        this.actor = actor;
        this.activity = activity;
    }

    /* -------------------------------------------------------------- finalize */

    /**
     * Makes a concept final when its figures are still the ones the client read and nothing blocks it.
     *
     * @param dataSha256 the fingerprint of the figures on the screen
     * @param signerName who signs the inventory, printed in the sign-off block
     */
    @Transactional
    public View makeFinal(long closingId, String dataSha256, String signerName) {
        StockClosingEntity closing = closings.requireConcept(closingId);
        if (signerName == null || signerName.isBlank()) {
            throw new UnprocessableBusinessRuleException("Vul in wie de inventaris ondertekent");
        }
        closings.compute(closingId);
        /* Nobody freezes figures they have not seen; the refusal rolls the compute back. */
        if (dataSha256 == null || !dataSha256.strip().equals(closing.dataSha256)) {
            throw new InventoryRefusal("CIJFERS_GEWIJZIGD", "De gegevens zijn intussen gewijzigd. Herbereken en kijk de cijfers opnieuw na");
        }
        List<Notice> blockers = ClosingNotices.fromJson(closing.noticesJson).stream().filter(Notice::blocker).toList();
        if (!blockers.isEmpty()) {
            throw new InventoryRefusal("GEBLOKKEERD", "Nog " + ClosingNotices.counted(blockers.size(), "punt houdt", "punten houden") + " de afsluiting tegen",
                    Map.of("notices", blockers));
        }

        ActorRef who = actor.current();
        Instant now = Instant.now();
        closing.status = StockClosingService.STATUS_FINAL;
        closing.finalizedBy = who.username();
        closing.finalizedByName = cut(who.displayName(), 120);
        closing.finalizedAt = now;
        closing.signerName = cut(signerName.strip(), 160);
        ClosingReportData.CompanyIdentity identity = ClosingReportData.companyIdentity(company.get());
        closing.companyName = identity.name();
        closing.companyVat = identity.vat();
        closing.companyAddress = identity.address();
        closingRows.flush();

        /* The workbook first: the PDF prints its hash, so the signed paper names the exact file. */
        ClosingReportData data = ClosingReportData.of(closings.view(closingId), null);
        byte[] sheet = workbook.render(data);
        closing.xlsxSha256 = sha256(sheet);
        byte[] paper = pdf.render(data);
        closing.pdfSha256 = sha256(paper);
        String base = "jaarinventaris-" + closing.closingYear + "-v" + closing.versionNo;
        PhotoStorage.Stored storedSheet = storage.store(base + ".xlsx", CatalogWorkbook.MEDIA_TYPE, sheet);
        closing.xlsxStorageKey = storedSheet.storageKey();
        closing.xlsxSizeBytes = (long) sheet.length;
        PhotoStorage.Stored storedPaper = storage.store(base + ".pdf", PDF_MEDIA_TYPE, paper);
        closing.pdfStorageKey = storedPaper.storageKey();
        closing.pdfSizeBytes = (long) paper.length;

        /* The only fields ever written on a final row: the version of this year that was valid until now. */
        for (StockClosingEntity other : closingRows.list("closingYear = ?1 and status = ?2 and supersededById is null and id <> ?3",
                closing.closingYear, StockClosingService.STATUS_FINAL, closing.id)) {
            other.supersededById = closing.id;
            other.supersededAt = now;
        }
        closingRows.flush();

        activity.record(ACTION_FINALIZED, StockClosingService.ENTITY_STOCK_CLOSING, String.valueOf(closing.id), label(closing),
                "Jaarinventaris " + closing.closingYear + " versie " + closing.versionNo + " definitief: € "
                        + ClosingNotices.euro(closing.totalValueEur) + ". data " + closing.dataSha256.substring(0, 8)
                        + " · pdf " + closing.pdfSha256.substring(0, 8) + " · excel " + closing.xlsxSha256.substring(0, 8));
        return closings.view(closingId);
    }

    /* ----------------------------------------------------------- new version */

    /**
     * Starts the correction of a year: a new concept that names the valid final version, carries a
     * copy of its decisions and is computed from the data of today. The old version stays final,
     * readable and downloadable.
     */
    @Transactional
    public View newVersion(long closingId, String reason) {
        if (reason == null || reason.isBlank()) throw new UnprocessableBusinessRuleException("Geef de reden van de correctie");
        /* Two starts meet on the rule row; on PostgreSQL the unique index on year and version is the last guard. */
        rules.lockForCreate();
        StockClosingEntity replaced = closingRows.findById(closingId);
        if (replaced == null) throw new NotFoundException("Afsluiting", closingId);
        List<StockClosingEntity> versions = closingRows.list("closingYear", replaced.closingYear);
        boolean valid = StockClosingService.STATUS_FINAL.equals(replaced.status) && replaced.supersededById == null
                && versions.stream().noneMatch(other -> StockClosingService.STATUS_FINAL.equals(other.status)
                        && other.versionNo > replaced.versionNo);
        if (!valid) {
            throw new InventoryRefusal("GEEN_DEFINITIEVE",
                    "Alleen van de geldige definitieve versie kan een nieuwe versie gemaakt worden");
        }
        StockClosingEntity open = versions.stream().filter(other -> StockClosingService.STATUS_CONCEPT.equals(other.status))
                .findFirst().orElse(null);
        if (open != null) {
            throw new InventoryRefusal("CONCEPT_BESTAAT", "Voor " + replaced.closingYear + " staat al een concept open",
                    Map.of("closingId", open.id));
        }

        ActorRef who = actor.current();
        StockClosingEntity version = new StockClosingEntity();
        version.closingYear = replaced.closingYear;
        version.versionNo = versions.stream().mapToInt(other -> other.versionNo).max().orElse(replaced.versionNo) + 1;
        version.closingDate = replaced.closingDate;
        version.cutoffAt = replaced.cutoffAt;
        version.status = StockClosingService.STATUS_CONCEPT;
        version.supersedesId = replaced.id;
        version.correctionReason = cut(reason.strip(), 1000);
        version.createdBy = who.username();
        version.createdByName = cut(who.displayName(), 120);
        version.createdAt = Instant.now();
        closingRows.persist(version);
        closingRows.flush();

        /* In the order they were taken, so waardeverminderingen are processed as before; who decided stays who decided. */
        for (StockClosingDecisionEntity decision : decisionRows.list("closingId = ?1 order by id", replaced.id)) {
            StockClosingDecisionEntity copy = new StockClosingDecisionEntity();
            copy.closingId = version.id;
            copy.kind = decision.kind;
            copy.purchaseOrderId = decision.purchaseOrderId;
            copy.salesOrderId = decision.salesOrderId;
            copy.productId = decision.productId;
            copy.movementId = decision.movementId;
            copy.creditId = decision.creditId;
            copy.payee = decision.payee;
            copy.choice = decision.choice;
            copy.flag = decision.flag;
            copy.quantity = decision.quantity;
            copy.unitValueEur = decision.unitValueEur;
            copy.amountEur = decision.amountEur;
            copy.basisAmountEur = decision.basisAmountEur;
            copy.decisionDate = decision.decisionDate;
            copy.reasonCode = decision.reasonCode;
            copy.reason = decision.reason;
            copy.counterparty = decision.counterparty;
            copy.decidedBy = decision.decidedBy;
            copy.decidedByName = decision.decidedByName;
            copy.decidedAt = decision.decidedAt;
            decisionRows.persist(copy);
        }
        decisionRows.flush();
        closings.compute(version.id);

        activity.record(ACTION_VERSION_STARTED, StockClosingService.ENTITY_STOCK_CLOSING, String.valueOf(version.id), label(version),
                cut("Jaarinventaris " + version.closingYear + ": versie " + version.versionNo + " gestart ter correctie van versie "
                        + replaced.versionNo + ". Reden: " + version.correctionReason, 500));
        return closings.view(version.id);
    }

    /* ----------------------------------------------------------------- files */

    /** The PDF of a closing: the stored bytes of a final one, a fresh render from the stored rows of a concept. */
    @Transactional
    public File pdf(long closingId) {
        View view = closings.view(closingId);
        ClosingReportData data = ClosingReportData.of(view, company.get());
        byte[] stored = stored(view.closing(), view.closing().pdfStorageKey);
        return new File(data.filename("pdf"), PDF_MEDIA_TYPE, stored != null ? stored : pdf.render(data));
    }

    /** The workbook of a closing, by the same rule. */
    @Transactional
    public File xlsx(long closingId) {
        View view = closings.view(closingId);
        ClosingReportData data = ClosingReportData.of(view, company.get());
        byte[] stored = stored(view.closing(), view.closing().xlsxStorageKey);
        return new File(data.filename("xlsx"), CatalogWorkbook.MEDIA_TYPE, stored != null ? stored : workbook.render(data));
    }

    /** The financial year of a closing, for a refusal that has to name it; null when the closing does not exist. */
    @Transactional
    public Integer closingYear(long closingId) {
        StockClosingEntity closing = closingRows.findById(closingId);
        return closing == null ? null : closing.closingYear;
    }

    private byte[] stored(StockClosingEntity closing, String storageKey) {
        if (!StockClosingService.STATUS_FINAL.equals(closing.status) || storageKey == null) return null;
        try (InputStream in = storage.read(storageKey)) {
            return in.readAllBytes();
        } catch (IOException unreadable) {
            throw new UncheckedIOException(unreadable);
        }
    }

    /* --------------------------------------------------------------- helpers */

    public static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static String label(StockClosingEntity closing) {
        return "Jaarinventaris " + closing.closingYear + " versie " + closing.versionNo;
    }

    private static String cut(String value, int length) {
        return value == null || value.length() <= length ? value : value.substring(0, length);
    }
}
