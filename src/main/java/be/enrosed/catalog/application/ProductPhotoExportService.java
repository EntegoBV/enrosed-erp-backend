package be.enrosed.catalog.application;

import be.enrosed.catalog.adapter.out.persistence.CanonicalCatalogDaos;
import be.enrosed.catalog.adapter.out.persistence.CatalogDaos;
import be.enrosed.catalog.adapter.out.persistence.ProductEntity;
import be.enrosed.catalog.adapter.out.persistence.ProductFamilyEntity;
import be.enrosed.catalog.adapter.out.persistence.ProductFamilyPhotoEntity;
import be.enrosed.catalog.application.PhotoExportTokenStore.Ticket;
import be.enrosed.catalog.application.ProductPhotoExport.FileEntry;
import be.enrosed.catalog.application.ProductPhotoExport.PhotoSelection;
import be.enrosed.catalog.application.ProductPhotoExport.Plan;
import be.enrosed.catalog.application.ProductPhotoExport.ProductEntry;
import be.enrosed.catalog.application.ProductPhotoExport.Request;
import be.enrosed.catalog.application.ProductPhotoExport.Scope;
import be.enrosed.catalog.application.ProductPhotoExport.Source;
import be.enrosed.catalog.application.port.out.PhotoStorage;
import be.enrosed.catalog.domain.CatalogChannel;
import be.enrosed.catalog.domain.Category;
import be.enrosed.catalog.domain.Photo;
import be.enrosed.catalog.domain.PhotoRole;
import be.enrosed.catalog.domain.Product;
import be.enrosed.catalog.domain.PublicationState;
import be.enrosed.shared.Language;
import be.enrosed.shared.LanguageFallback;
import be.enrosed.shared.NotFoundException;
import be.enrosed.shared.UnprocessableBusinessRuleException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Exports product photos as one ZIP: a folder per product (SKU) with the
 * original uploads, plus a read-me without prices.
 *
 * Highest quality means the stored bytes as uploaded. Product photos keep a
 * single stored original; series (family) photos keep the untouched upload
 * as their "large" object (the ERP's /original and /large routes serve that
 * same key) next to a 480 px "small" rendition, which is never exported.
 * The medium and custom sizes are rendered on request and never stored.
 *
 * The plan is metadata only. The ZIP is streamed entry by entry, reading
 * one blob at a time, with STORED entries: photos are already compressed,
 * so deflating them again would only cost CPU.
 */
@ApplicationScoped
public class ProductPhotoExportService {

    private static final Logger LOG = Logger.getLogger(ProductPhotoExportService.class);
    /** Dates and times in the export are Belgian local time, whatever the server's zone. */
    static final ZoneId ZONE = ZoneId.of("Europe/Brussels");

    private final ProductService products;
    private final CatalogDaos.Products productRows;
    private final CanonicalCatalogDaos.Families families;
    private final CategoryService categories;
    private final ProductOverviewOrder overviewOrder;
    private final PublicFamilyPhotoProjection publicPhotos;
    private final FamilyPhotoPublicationPolicy photoPublication;
    private final PhotoStorage photoStorage;
    private final PhotoExportTokenStore tokens;

    public ProductPhotoExportService(
            ProductService products,
            CatalogDaos.Products productRows,
            CanonicalCatalogDaos.Families families,
            CategoryService categories,
            ProductOverviewOrder overviewOrder,
            PublicFamilyPhotoProjection publicPhotos,
            FamilyPhotoPublicationPolicy photoPublication,
            PhotoStorage photoStorage,
            PhotoExportTokenStore tokens) {
        this.products = products;
        this.productRows = productRows;
        this.families = families;
        this.categories = categories;
        this.overviewOrder = overviewOrder;
        this.publicPhotos = publicPhotos;
        this.photoPublication = photoPublication;
        this.photoStorage = photoStorage;
        this.tokens = tokens;
    }

    /** A prepared export: its download ticket and what the ZIP will hold. */
    public record Prepared(Ticket ticket, Plan plan, long totalBytes) {}

    /** Plans the export for the counts on screen and issues a 15-minute download ticket. */
    @Transactional
    public Prepared prepare(Request request, String principal) {
        Plan plan = plan(request);
        if (plan.products().isEmpty()) {
            throw new UnprocessableBusinessRuleException("Er zijn geen producten voor deze selectie");
        }
        ZonedDateTime now = ZonedDateTime.now(ZONE);
        Ticket ticket = tokens.issue(request, principal, now.toLocalDate());
        long totalBytes = plan.photoBytes() + PhotoExportReadme.bytes(plan, now).length;
        LOG.infof("Photo export prepared by %s: scope %s, photos %s, %d products, %d photos, %d bytes",
                principal, request.scope(), request.photos(), plan.productCount(), plan.photoCount(),
                totalBytes);
        return new Prepared(ticket, plan, totalBytes);
    }

    public Optional<Ticket> ticket(String token) {
        return tokens.find(token);
    }

    /**
     * Selects the products and their photos, in the stable merchandising
     * order of the product overview. Read-only: nothing is written.
     */
    @Transactional
    public Plan plan(Request request) {
        Language language = request.language();
        Map<Long, ProductEntity> rows = new HashMap<>();
        Map<Long, List<ProductEntity>> membersByFamily = new HashMap<>();
        for (ProductEntity row : productRows.listAll()) {
            rows.put(row.id, row);
            if (row.familyId != null) {
                membersByFamily.computeIfAbsent(row.familyId, id -> new ArrayList<>()).add(row);
            }
        }
        membersByFamily.values().forEach(members -> members.sort(
                Comparator.comparingInt((ProductEntity member) -> member.variantPosition)
                        .thenComparing(member -> member.id)));
        Map<Long, ProductFamilyEntity> familyById = families.listAll().stream()
                .collect(Collectors.toMap(family -> family.id, Function.identity()));
        Map<Long, Category> categoryById = categories.list().stream()
                .collect(Collectors.toMap(Category::id, Function.identity()));

        List<Product> selected = overviewOrder.sort(products.list().stream()
                .filter(product -> inScope(product, request.scope(), familyById))
                .toList());

        Map<Long, List<ProductFamilyPhotoEntity>> websiteImagesByFamily = new HashMap<>();
        PhotoExportNames.Unique folders = new PhotoExportNames.Unique();
        List<ProductEntry> entries = new ArrayList<>();
        int photoCount = 0;
        long photoBytes = 0;
        for (Product product : selected) {
            ProductEntity row = rows.get(product.id());
            ProductFamilyEntity family = product.familyId() == null ? null : familyById.get(product.familyId());
            List<ProductEntity> members = family == null
                    ? List.of() : membersByFamily.getOrDefault(family.id, List.of());
            List<FileEntry> files = files(product, row, family, members, request.photos(),
                    websiteImagesByFamily);

            String name = product.nameIn(language);
            String colour = product.colourIn(language);
            String size = product.variantSizeIn(language);
            String folder = files.isEmpty() ? null
                    : folders.claim(PhotoExportNames.folder(product.sku(), name, colour, size));
            Category category = product.categoryId() == null ? null : categoryById.get(product.categoryId());
            entries.add(new ProductEntry(
                    folder, product.sku(), name, colour, size,
                    family == null ? null : LanguageFallback.text(family.texts, language,
                            text -> text.language, text -> text.name, family.name).value(),
                    category == null ? null : category.nameIn(language),
                    product.dimensions(), product.packaging(), product.carton(),
                    firstPresent(product.canonicalBarcode(),
                            product.barcodes() == null ? null : product.barcodes().inner()),
                    product.barcodes() == null ? null : blankToNull(product.barcodes().outer()),
                    files));
            photoCount += files.size();
            photoBytes += files.stream().mapToLong(FileEntry::sizeBytes).sum();
        }
        return new Plan(request, entries, photoCount, photoBytes);
    }

    /**
     * Streams the ZIP. Photos are read one blob at a time; the read-me is
     * written last so it lists exactly the files that made it into the
     * archive (a photo deleted since the plan is skipped and logged).
     *
     * On any other failure the exception propagates without finishing the
     * archive, so the browser reports a failed download instead of saving a
     * ZIP that silently misses photos.
     */
    public void write(Plan plan, LocalDate exportDate, ZonedDateTime exportedAt, OutputStream output)
            throws IOException {
        String root = ProductPhotoExport.baseName(exportDate) + "/";
        LocalDateTime stamp = exportedAt.withZoneSameInstant(ZONE).toLocalDateTime();
        ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(output, 1 << 16),
                StandardCharsets.UTF_8);
        directory(zip, root, stamp);
        List<ProductEntry> written = new ArrayList<>(plan.products().size());
        int photoCount = 0;
        long photoBytes = 0;
        for (ProductEntry product : plan.products()) {
            if (product.folder() == null) {
                written.add(product);
                continue;
            }
            String folder = root + product.folder() + "/";
            directory(zip, folder, stamp);
            List<FileEntry> files = new ArrayList<>(product.files().size());
            for (FileEntry file : product.files()) {
                byte[] bytes = original(file);
                if (bytes == null) continue;
                stored(zip, folder + file.name(), bytes, stamp);
                files.add(file);
                photoCount++;
                photoBytes += bytes.length;
            }
            written.add(new ProductEntry(product.folder(), product.sku(), product.name(),
                    product.colour(), product.size(), product.series(), product.category(),
                    product.dimensions(), product.packaging(), product.carton(),
                    product.pieceEan(), product.outerItf(), files));
        }
        Plan actual = new Plan(plan.request(), written, photoCount, photoBytes);
        stored(zip, root + PhotoExportReadme.fileName(plan.request().language()),
                PhotoExportReadme.bytes(actual, exportedAt.withZoneSameInstant(ZONE)), stamp);
        zip.finish();
        zip.flush();
        LOG.infof("Photo export streamed: %d products, %d photos, %d photo bytes",
                actual.productCount(), photoCount, photoBytes);
    }

    /** The export time as the read-me prints it. */
    public ZonedDateTime now() {
        return ZonedDateTime.now(ZONE);
    }

    /* ------------------------------------------------------------ selection */

    static boolean inScope(Product product, Scope scope, Map<Long, ProductFamilyEntity> families) {
        if (product.demo()) return false;
        return switch (scope) {
            case ALL -> true;
            case ACTIVE -> product.active();
            case WEBSITE -> product.active() && websitePublished(product, families);
        };
    }

    /** Same rule as the website quote form: the family decides; flat legacy SKUs keep their own state. */
    private static boolean websitePublished(Product product, Map<Long, ProductFamilyEntity> families) {
        if (product.familyId() == null) return product.isPublishedTo(CatalogChannel.WEBSITE);
        ProductFamilyEntity family = families.get(product.familyId());
        return family != null && family.active && family.websiteStatus == PublicationState.PUBLISHED;
    }

    private List<FileEntry> files(Product product, ProductEntity row, ProductFamilyEntity family,
                                  List<ProductEntity> members, PhotoSelection selection,
                                  Map<Long, List<ProductFamilyPhotoEntity>> websiteImagesByFamily) {
        Map<Long, ProductFamilyPhotoEntity> familyPhotos = family == null ? Map.of()
                : family.photos.stream().filter(image -> image.id != null)
                        .collect(Collectors.toMap(image -> image.id, Function.identity(), (a, b) -> a));
        /* The carousel: every photo the ERP product shows, own and inherited, in its order. */
        List<Candidate> carousel = new ArrayList<>();
        for (Photo photo : product.photos()) {
            carousel.add(Candidate.of(photo,
                    photo.inherited() ? familyPhotos.get(photo.familyPhotoId()) : null));
        }
        List<Candidate> website = new ArrayList<>();
        if (family == null) {
            /* A flat legacy SKU shows its own photos, website lead first, while published itself. */
            if (product.isPublishedTo(CatalogChannel.WEBSITE)) {
                for (Photo photo : product.photosFor(PhotoRole.WEBSITE)) {
                    if (photo.inherited()) continue;
                    find(carousel, "p" + photo.id()).ifPresent(website::add);
                }
            }
        } else if (row != null) {
            /* Exactly the public gallery for this SKU; independent of the family's publication
               state so an unpublished series still exports the photos selected for the site. */
            List<ProductFamilyPhotoEntity> images = websiteImagesByFamily.computeIfAbsent(family.id,
                    id -> publicPhotos.images(family, members, CatalogChannel.WEBSITE));
            List<ProductFamilyPhotoEntity> usable = images.stream()
                    .filter(image -> image.id != null)
                    .filter(image -> photoPublication.isUsableBy(image, row, members, CatalogChannel.WEBSITE))
                    .toList();
            ProductFamilyPhotoEntity primary = usable.isEmpty() ? null
                    : publicPhotos.primary(family, row, members, CatalogChannel.WEBSITE);
            List<ProductFamilyPhotoEntity> ordered = new ArrayList<>(usable.size());
            if (primary != null) {
                usable.stream().filter(image -> Objects.equals(image.id, primary.id)).findFirst()
                        .ifPresent(ordered::add);
            }
            usable.stream().filter(image -> !ordered.contains(image)).forEach(ordered::add);
            for (ProductFamilyPhotoEntity image : ordered) {
                Optional<Candidate> match = image.id < 0
                        ? find(carousel, "p" + (-image.id))
                        : carousel.stream().filter(candidate ->
                                Objects.equals(candidate.familyPhotoId(), image.id)).findFirst();
                Candidate candidate = match.orElseGet(() -> Candidate.of(image));
                if (match.isEmpty()) carousel.add(candidate);
                website.add(candidate);
            }
        }
        Photo lead = product.photoForSalesDocument();
        return arrange(carousel, website, lead == null ? null : lead.id(), selection);
    }

    /**
     * Orders one folder: the Hoofdfoto first, then the website photos in
     * their gallery order, then the rest in carousel order. Identical content
     * (same stored object, or same size, dimensions and type) is exported
     * once; the kept file inherits the duplicate's website flag.
     */
    static List<FileEntry> arrange(List<Candidate> carousel, List<Candidate> website,
                                   Long leadPhotoId, PhotoSelection selection) {
        List<Candidate> pool = selection == PhotoSelection.WEBSITE ? website : carousel;
        if (pool.isEmpty()) return List.of();
        Set<String> websiteIds = website.stream().map(Candidate::identity)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Candidate lead = pool.stream()
                .filter(candidate -> leadPhotoId != null && Objects.equals(candidate.productPhotoId(), leadPhotoId))
                .findFirst()
                .orElse(selection == PhotoSelection.WEBSITE || website.isEmpty() ? pool.get(0) : website.get(0));

        List<Candidate> ordered = new ArrayList<>(pool.size());
        ordered.add(lead);
        for (Candidate candidate : website) {
            if (!ordered.contains(candidate) && pool.contains(candidate)) ordered.add(candidate);
        }
        for (Candidate candidate : pool) {
            if (!ordered.contains(candidate)) ordered.add(candidate);
        }

        List<Kept> kept = new ArrayList<>();
        Map<String, Kept> byContent = new HashMap<>();
        for (Candidate candidate : ordered) {
            boolean onWebsite = websiteIds.contains(candidate.identity());
            Kept duplicate = candidate.contentKeys().stream().map(byContent::get)
                    .filter(Objects::nonNull).findFirst().orElse(null);
            if (duplicate != null) {
                duplicate.website |= onWebsite;
                if (candidate.source().ordinal() > duplicate.source.ordinal()) duplicate.source = candidate.source();
                continue;
            }
            Kept entry = new Kept(candidate, onWebsite);
            kept.add(entry);
            candidate.contentKeys().forEach(key -> byContent.put(key, entry));
        }

        List<FileEntry> files = new ArrayList<>(kept.size());
        for (int index = 0; index < kept.size(); index++) {
            Kept entry = kept.get(index);
            Candidate candidate = entry.candidate;
            boolean isLead = index == 0;
            files.add(new FileEntry(
                    PhotoExportNames.file(index + 1, kept.size(), isLead,
                            PhotoExportNames.extension(candidate.contentType(), candidate.originalFilename())),
                    candidate.storageKey(), candidate.contentType(), candidate.sizeBytes(),
                    candidate.widthPx(), candidate.heightPx(), candidate.originalFilename(),
                    isLead, entry.website, entry.source));
        }
        return List.copyOf(files);
    }

    private static Optional<Candidate> find(List<Candidate> candidates, String identity) {
        return candidates.stream().filter(candidate -> candidate.identity().equals(identity)).findFirst();
    }

    /** A photo that may go into a folder, already pointing at its highest-quality stored object. */
    record Candidate(String identity, Long productPhotoId, Long familyPhotoId, String storageKey,
                     String contentType, long sizeBytes, Integer widthPx, Integer heightPx,
                     String originalFilename, Source source) {

        static Candidate of(Photo photo, ProductFamilyPhotoEntity familyPhoto) {
            if (!photo.inherited()) {
                return new Candidate("p" + photo.id(), photo.id(), null, photo.storageKey(),
                        photo.contentType(), photo.sizeBytes(), photo.widthPx(), photo.heightPx(),
                        photo.originalFilename(), Source.PRODUCT);
            }
            if (familyPhoto == null) {
                /* A stale projection without its series row: the projection already carries
                   the series photo's large (original) object. */
                return new Candidate("p" + photo.id(), photo.id(), photo.familyPhotoId(), photo.storageKey(),
                        photo.contentType(), photo.sizeBytes(), photo.widthPx(), photo.heightPx(),
                        photo.originalFilename(), Source.VARIANT);
            }
            Version best = Version.best(familyPhoto);
            return new Candidate("p" + photo.id(), photo.id(), familyPhoto.id, best.storageKey(),
                    best.contentType(), best.sizeBytes(), best.widthPx(), best.heightPx(),
                    firstPresent(familyPhoto.originalFilename, photo.originalFilename()),
                    source(familyPhoto));
        }

        /** A website image that has no projection on the product (yet). */
        static Candidate of(ProductFamilyPhotoEntity image) {
            Version best = Version.best(image);
            return new Candidate("f" + image.id, null, image.id, best.storageKey(), best.contentType(),
                    best.sizeBytes(), best.widthPx(), best.heightPx(), image.originalFilename,
                    source(image));
        }

        private static Source source(ProductFamilyPhotoEntity image) {
            return FamilyPhotoVariantResolver.familyWide(image) ? Source.SERIES : Source.VARIANT;
        }

        /** Same stored object, or same size + pixel dimensions + type: the same picture uploaded twice. */
        List<String> contentKeys() {
            List<String> keys = new ArrayList<>(2);
            if (storageKey != null && !storageKey.isBlank()) keys.add("key:" + storageKey);
            if (sizeBytes > 0) {
                keys.add("meta:" + sizeBytes + "|" + widthPx + "|" + heightPx + "|"
                        + (contentType == null ? "" : contentType.toLowerCase(Locale.ROOT)));
            }
            return keys;
        }
    }

    /** One stored object of a series photo. */
    record Version(String storageKey, String contentType, long sizeBytes, Integer widthPx, Integer heightPx) {

        /**
         * The untouched upload ("large") unless, against every write path,
         * the "small" object is the bigger one. The largest pixel count wins.
         */
        static Version best(ProductFamilyPhotoEntity image) {
            Version large = new Version(image.largeStorageKey, image.largeContentType, image.largeSizeBytes,
                    image.largeWidthPx, image.largeHeightPx);
            Version small = new Version(image.smallStorageKey, image.smallContentType, image.smallSizeBytes,
                    image.smallWidthPx, image.smallHeightPx);
            if (blank(large.storageKey())) return small;
            if (!blank(small.storageKey()) && small.pixels() > large.pixels()) return small;
            return large;
        }

        long pixels() {
            return widthPx == null || heightPx == null ? 0 : (long) widthPx * heightPx;
        }
    }

    /** Mutable bookkeeping while merging duplicates. */
    private static final class Kept {
        final Candidate candidate;
        boolean website;
        Source source;

        Kept(Candidate candidate, boolean website) {
            this.candidate = candidate;
            this.website = website;
            this.source = candidate.source();
        }
    }

    /* ------------------------------------------------------------ zip */

    private byte[] original(FileEntry file) throws IOException {
        try (InputStream data = photoStorage.read(file.storageKey())) {
            return data.readAllBytes();
        } catch (NotFoundException missing) {
            LOG.warnf("Photo export skipped %s: stored object %s no longer exists",
                    file.name(), file.storageKey());
            return null;
        }
    }

    /** STORED: CRC and size come from the bytes in hand, so no data descriptor is needed. */
    private static void stored(ZipOutputStream zip, String name, byte[] bytes, LocalDateTime stamp)
            throws IOException {
        ZipEntry entry = new ZipEntry(name);
        entry.setMethod(ZipEntry.STORED);
        entry.setSize(bytes.length);
        entry.setCompressedSize(bytes.length);
        CRC32 crc = new CRC32();
        crc.update(bytes);
        entry.setCrc(crc.getValue());
        entry.setTimeLocal(stamp);
        zip.putNextEntry(entry);
        zip.write(bytes);
        zip.closeEntry();
    }

    private static void directory(ZipOutputStream zip, String name, LocalDateTime stamp) throws IOException {
        ZipEntry entry = new ZipEntry(name);
        entry.setMethod(ZipEntry.STORED);
        entry.setSize(0);
        entry.setCompressedSize(0);
        entry.setCrc(0);
        entry.setTimeLocal(stamp);
        zip.putNextEntry(entry);
        zip.closeEntry();
    }

    private static String firstPresent(String first, String second) {
        String value = blankToNull(first);
        return value != null ? value : blankToNull(second);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
