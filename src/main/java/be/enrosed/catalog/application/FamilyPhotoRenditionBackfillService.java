package be.enrosed.catalog.application;

import be.enrosed.catalog.adapter.out.persistence.CanonicalCatalogDaos;
import be.enrosed.catalog.adapter.out.persistence.ProductFamilyEntity;
import be.enrosed.catalog.adapter.out.persistence.ProductFamilyPhotoEntity;
import be.enrosed.catalog.application.PhotoUploadPolicy.InvalidPhotoException;
import be.enrosed.catalog.application.port.out.PhotoStorage;
import be.enrosed.shared.NotFoundException;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.panache.common.Page;
import io.quarkus.panache.common.Parameters;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.jboss.logging.Logger;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Bounded, retryable migration for legacy administrator uploads whose website "small" blob is
 * still the exact large source. Every photo commits independently, so a missing blob or database
 * error cannot roll back successful siblings or delay application readiness.
 */
@ApplicationScoped
public class FamilyPhotoRenditionBackfillService {

    static final int BATCH_SIZE = 8;
    private static final String ADMIN_SOURCE_PATTERN = "admin-%";
    private static final Logger LOG = Logger.getLogger(FamilyPhotoRenditionBackfillService.class);

    private final CanonicalCatalogDaos.FamilyPhotos photos;
    private final CanonicalCatalogDaos.Families families;
    private final ProductFamilyWriteGuard familyWrites;
    private final CatalogMutationLock mutationLock;
    private final PhotoStorage storage;
    private final PhotoRenditionService renditions;
    private final WebsiteRebuildService websiteRebuild;
    private final EntityManager entityManager;
    private final AtomicBoolean complete = new AtomicBoolean(false);

    public FamilyPhotoRenditionBackfillService(
            CanonicalCatalogDaos.FamilyPhotos photos,
            CanonicalCatalogDaos.Families families,
            ProductFamilyWriteGuard familyWrites,
            CatalogMutationLock mutationLock,
            PhotoStorage storage,
            PhotoRenditionService renditions,
            WebsiteRebuildService websiteRebuild,
            EntityManager entityManager) {
        this.photos = photos;
        this.families = families;
        this.familyWrites = familyWrites;
        this.mutationLock = mutationLock;
        this.storage = storage;
        this.renditions = renditions;
        this.websiteRebuild = websiteRebuild;
        this.entityManager = entityManager;
    }

    @Scheduled(
            identity = "catalog-family-photo-rendition-backfill",
            cron = "{enrosed.catalog.photo-rendition.cron}",
            concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void work() {
        if (complete.get()) return;
        BatchResult result = processPending(BATCH_SIZE);
        if (result.failedRows() > 0) {
            LOG.warnf("Kleine catalogusfoto's: %d tijdelijke fout(en); veilige rijen zijn wel "
                            + "afzonderlijk opgeslagen", result.failedRows());
        }
        if (result.exhausted()) {
            LOG.infof("Kleine catalogusfoto's voltooid: %d bekeken, %d verkleind, "
                            + "%d bewust behouden, rebuildcontrole=%s",
                    result.examinedRows(), result.resizedRows(), result.reusedRows(),
                    result.rebuildChecked());
        }
    }

    /** Public for focused migration tests and an explicit operational retry; never one big tx. */
    public BatchResult processPending(int maximumRows) {
        int limit = Math.max(1, Math.min(maximumRows, BATCH_SIZE));
        List<Candidate> candidates = QuarkusTransaction.requiringNew()
                .call(() -> candidates(limit));
        int examined = 0;
        int resized = 0;
        int reused = 0;
        int failed = 0;
        List<Long> failures = new ArrayList<>();
        for (Candidate candidate : candidates) {
            try {
                RowResult row = QuarkusTransaction.requiringNew()
                        .call(() -> processOne(candidate));
                if (row.outcome() == Outcome.SKIPPED) continue;
                examined++;
                if (row.outcome() == Outcome.RESIZED) resized++;
                else reused++;
            } catch (RuntimeException failure) {
                failed++;
                failures.add(candidate.photoId());
                LOG.warnf("Kleine versie van familiefoto %d wordt later opnieuw geprobeerd (%s)",
                        candidate.photoId(), safeMessage(failure));
            }
        }

        Completion completion = QuarkusTransaction.requiringNew()
                .call(this::completeIfExhausted);
        if (completion.exhausted()) complete.set(true);
        return new BatchResult(
                candidates.size(), examined, resized, reused, failed, List.copyOf(failures),
                completion.exhausted(), completion.rebuildChecked());
    }

    private List<Candidate> candidates(int limit) {
        return photos.find("""
                        sourceKey like :admin
                        and (smallRenditionVersion is null
                             or smallRenditionVersion <> :version)
                        and largeWidthPx > :maximumWidth
                        and smallStorageKey is not null and smallStorageKey <> ''
                        and largeStorageKey is not null and largeStorageKey <> ''
                        and (smallStorageKey = largeStorageKey
                             or (smallSha256 is not null and largeSha256 is not null
                                 and lower(smallSha256) = lower(largeSha256)))
                        order by family.id, position, id
                        """,
                Parameters.with("admin", ADMIN_SOURCE_PATTERN)
                        .and("version", PhotoRenditionService.POLICY_VERSION)
                        .and("maximumWidth", PhotoRenditionService.MAX_SMALL_WIDTH))
                .page(Page.ofSize(limit))
                .list().stream()
                .map(photo -> new Candidate(photo.id, photo.family.id))
                .toList();
    }

    private RowResult processOne(Candidate candidate) {
        /* Stable ordering across replicas: global catalog lock, owning family, then photo row. */
        mutationLock.acquire();
        familyWrites.lockFamilies(List.of(candidate.familyId()));
        ProductFamilyEntity family = families.findById(
                candidate.familyId(), LockModeType.PESSIMISTIC_WRITE);
        if (family == null) return RowResult.skipped();
        entityManager.refresh(family, LockModeType.PESSIMISTIC_WRITE);
        ProductFamilyPhotoEntity photo = photos.findById(
                candidate.photoId(), LockModeType.PESSIMISTIC_WRITE);
        if (photo == null || photo.family == null
                || !Objects.equals(photo.family.id, candidate.familyId())
                || !candidate(photo)) {
            return RowResult.skipped();
        }
        entityManager.refresh(photo, LockModeType.PESSIMISTIC_WRITE);
        if (!candidate(photo)) return RowResult.skipped();

        if (!storage.exists(photo.largeStorageKey)) {
            /* A historical reference to a blob that no longer exists cannot heal on retry. */
            photo.smallRenditionVersion = PhotoRenditionService.POLICY_VERSION;
            entityManager.flush();
            LOG.warnf("Familiefoto %d mist de grote bronblob en behoudt de huidige metadata",
                    photo.id);
            return new RowResult(Outcome.REUSED_INVALID);
        }
        /* If the blob disappears after exists(), read() deliberately escapes. Its transaction
           rolls back and retries instead of misclassifying a concurrent storage failure. */
        byte[] largeBytes = read(photo.largeStorageKey);
        PhotoUploadPolicy.ValidatedPhoto source;
        PhotoRenditionService.Rendition small;
        try {
            source = PhotoUploadPolicy.validate(
                    photo.originalFilename, new java.io.ByteArrayInputStream(largeBytes));
            small = renditions.small(source);
        } catch (InvalidPhotoException permanentlyInvalid) {
            /* The original remains available and unchanged. Marking the policy prevents one
               corrupt historical upload from occupying every future batch. */
            photo.smallRenditionVersion = PhotoRenditionService.POLICY_VERSION;
            entityManager.flush();
            LOG.warnf("Familiefoto %d is niet veilig decodeerbaar en behoudt het origineel (%s)",
                    photo.id, safeMessage(permanentlyInvalid));
            return new RowResult(Outcome.REUSED_INVALID);
        }

        Outcome outcome;
        if (small.resized()) {
            String smallStorageKey = "sha256-" + small.sha256() + small.extension();
            PhotoStorage.Stored stored = storage.storeKnown(
                    smallStorageKey, small.filename(), small.contentType(), small.bytes());
            applySmall(photo, smallStorageKey, small.contentType(), small.sha256(),
                    stored.sizeBytes(), stored.widthPx(), stored.heightPx());
            outcome = Outcome.RESIZED;
        } else {
            /* Reusing the large key removes any redundant equal-checksum reference. The old blob
               itself is deliberately left for a transaction-safe orphan cleanup. */
            applySmall(photo, photo.largeStorageKey, source.contentType(),
                    PhotoRenditionService.sha256(largeBytes), largeBytes.length,
                    small.width(), small.height());
            outcome = Outcome.REUSED_SOURCE;
        }
        photo.smallRenditionVersion = PhotoRenditionService.POLICY_VERSION;
        entityManager.flush();
        /* Do not delete an old equal-checksum key inside this transaction. Production storage may
           become external later, where a blob delete cannot roll back with JPA. Current affected
           rows share small=large; a rare historical duplicate is safer left for orphan cleanup. */
        return new RowResult(outcome);
    }

    private Completion completeIfExhausted() {
        mutationLock.acquire();
        if (!candidates(1).isEmpty()) return new Completion(false, false);
        /* queue() is revision-idempotent: it only mutates the durable outbox when one of the
           committed small SHA/dimension changes altered the public catalog digest. Calling it
           here also survives a process restart immediately after the final row commit. */
        boolean checked = false;
        if (familyWrites.websiteBuildReady()) {
            websiteRebuild.queue();
            checked = true;
        }
        return new Completion(true, checked);
    }

    private boolean candidate(ProductFamilyPhotoEntity photo) {
        if (photo == null || !startsWithAdmin(photo.sourceKey)
                || Objects.equals(PhotoRenditionService.POLICY_VERSION,
                        photo.smallRenditionVersion)
                || photo.largeWidthPx == null
                || photo.largeWidthPx <= PhotoRenditionService.MAX_SMALL_WIDTH
                || !notBlank(photo.smallStorageKey) || !notBlank(photo.largeStorageKey)) {
            return false;
        }
        return Objects.equals(photo.smallStorageKey, photo.largeStorageKey)
                || notBlank(photo.smallSha256) && notBlank(photo.largeSha256)
                && photo.smallSha256.equalsIgnoreCase(photo.largeSha256);
    }

    private byte[] read(String storageKey) {
        try (InputStream input = storage.read(storageKey)) {
            return input.readAllBytes();
        } catch (NotFoundException missing) {
            throw missing;
        } catch (Exception exception) {
            throw new IllegalStateException("Fotoblob " + storageKey + " kon niet worden gelezen",
                    exception);
        }
    }

    private static void applySmall(
            ProductFamilyPhotoEntity photo, String storageKey, String contentType, String sha256,
            long sizeBytes, Integer widthPx, Integer heightPx) {
        photo.smallStorageKey = storageKey;
        photo.smallContentType = contentType;
        photo.smallSha256 = sha256;
        photo.smallSizeBytes = sizeBytes;
        photo.smallWidthPx = widthPx;
        photo.smallHeightPx = heightPx;
    }

    private static boolean startsWithAdmin(String value) {
        return value != null && value.startsWith("admin-");
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private static String safeMessage(Throwable failure) {
        String message = failure == null ? null : failure.getMessage();
        return message == null || message.isBlank()
                ? failure == null ? "onbekende fout" : failure.getClass().getSimpleName()
                : message;
    }

    private record Candidate(long photoId, long familyId) {}
    private record Completion(boolean exhausted, boolean rebuildChecked) {}
    private record RowResult(Outcome outcome) {
        static RowResult skipped() { return new RowResult(Outcome.SKIPPED); }
    }
    private enum Outcome { SKIPPED, RESIZED, REUSED_SOURCE, REUSED_INVALID }

    public record BatchResult(
            int selectedRows,
            int examinedRows,
            int resizedRows,
            int reusedRows,
            int failedRows,
            List<Long> failedPhotoIds,
            boolean exhausted,
            boolean rebuildChecked) {}
}
