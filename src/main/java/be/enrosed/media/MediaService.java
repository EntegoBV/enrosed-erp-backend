package be.enrosed.media;

import be.enrosed.catalog.adapter.out.persistence.ProductEntity;
import be.enrosed.catalog.adapter.out.persistence.ProductFamilyEntity;
import be.enrosed.catalog.application.port.out.PhotoStorage;
import be.enrosed.catalog.application.PhotoRenditionService;
import be.enrosed.catalog.application.PhotoUploadPolicy;
import be.enrosed.planning.PlannerItemEntity;
import be.enrosed.shared.BusinessRuleException;
import be.enrosed.shared.NotFoundException;
import be.enrosed.shared.audit.ActivityChangeSet;
import be.enrosed.shared.audit.ActivityLogService;
import be.enrosed.shared.security.CurrentActor;
import be.enrosed.sourcing.adapter.out.persistence.SourcingEntities;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.transaction.Transactional;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PushbackInputStream;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Transactional core of the reusable document and media registry. */
@ApplicationScoped
public class MediaService {
    static final String ACTIVITY_ENTITY = "MEDIA_ASSET";
    private static final int MAX_NAME_LENGTH = 255;

    private final EntityManager entities;
    private final PhotoStorage storage;
    private final CurrentActor actor;
    private final ActivityLogService activity;
    private final PhotoRenditionService renditions;

    @Inject
    Event<MediaBlobCleanup.DeleteReady> blobDeleteCleanup;
    @Inject
    Event<MediaBlobCleanup.UploadReady> blobUploadCleanup;

    public MediaService(EntityManager entities, PhotoStorage storage,
                        CurrentActor actor, ActivityLogService activity,
                        PhotoRenditionService renditions) {
        this.entities = entities;
        this.storage = storage;
        this.actor = actor;
        this.activity = activity;
        this.renditions = renditions;
    }

    @Transactional
    public MediaDtos.UploadResult upload(String requestedName,
                                         MediaUploadPolicy.ValidatedFile file) {
        return upload(requestedName, file, null);
    }

    /** Uploads into a folder; a deduplicated asset keeps the folder it already has. */
    @Transactional
    public MediaDtos.UploadResult upload(String requestedName,
                                         MediaUploadPolicy.ValidatedFile file, Long folderId) {
        String sha = sha256(file.bytes());
        MediaVersionEntity existing = versionBySha(sha);
        if (existing != null) {
            /* Never unarchive or rename a deduplicated asset silently. */
            return new MediaDtos.UploadResult(detail(requiredAsset(existing.assetId)), true);
        }

        String storageKey = "sha256-" + sha
                + MediaUploadPolicy.deterministicExtension(file.originalFilename(), file.contentType());
        boolean alreadyStored = storage.exists(storageKey);
        PhotoStorage.Stored stored = storage.storeKnown(storageKey, file.originalFilename(),
                file.contentType(), file.bytes());
        if (!alreadyStored) fireUploadRollbackCleanup(storageKey);

        Instant now = Instant.now();
        MediaAssetEntity asset = new MediaAssetEntity();
        asset.name = cleanName(requestedName, file.originalFilename());
        asset.kind = file.kind();
        asset.archived = false;
        asset.createdAt = now;
        asset.updatedAt = now;
        asset.createdBy = actor.current().username();
        asset.folderId = folderId == null ? null : requiredFolder(folderId).id;
        entities.persist(asset);
        entities.flush();

        MediaVersionEntity version = new MediaVersionEntity();
        version.assetId = asset.id;
        version.versionNumber = 1;
        version.storageKey = stored.storageKey();
        version.originalFilename = file.originalFilename();
        version.contentType = file.contentType();
        version.sizeBytes = stored.sizeBytes();
        version.sha256 = sha;
        version.widthPx = stored.widthPx();
        version.heightPx = stored.heightPx();
        applyThumbnail(version, file);
        applyWeb(version, file);
        version.createdAt = now;
        version.createdBy = asset.createdBy;
        entities.persist(version);
        entities.flush();
        asset.currentVersionId = version.id;

        activity.record(ActivityLogService.ACTION_DOCUMENT_ADDED, ACTIVITY_ENTITY,
                asset.id.toString(), asset.name, "Bestand toegevoegd",
                ActivityChangeSet.create()
                        .privateValue("media.filename", "Bestand", null, version.originalFilename)
                        .add("media.kind", "Type", null, asset.kind)
                        .add("media.size", "Bestandsgrootte", null, version.sizeBytes + " bytes")
                        .build());
        return new MediaDtos.UploadResult(detail(asset), false);
    }

    @Transactional
    public List<MediaDtos.Summary> list(String query, MediaKind kind, MediaRole role,
                                        Boolean archived, MediaTargetType targetType, Long targetId,
                                        boolean includeArchived, int offset, int limit) {
        return list(query, kind, role, archived, targetType, targetId, includeArchived, offset, limit,
                null, false);
    }

    /** {@code folderId} narrows to one folder, {@code rootOnly} to the assets outside every folder. */
    @Transactional
    public List<MediaDtos.Summary> list(String query, MediaKind kind, MediaRole role,
                                        Boolean archived, MediaTargetType targetType, Long targetId,
                                        boolean includeArchived, int offset, int limit,
                                        Long folderId, boolean rootOnly) {
        return list(query, kind, role, archived, targetType, targetId, includeArchived, offset, limit,
                folderId, rootOnly, null);
    }

    /** {@code linked} false lists what nothing uses yet, true only what has a link. */
    @Transactional
    public List<MediaDtos.Summary> list(String query, MediaKind kind, MediaRole role,
                                        Boolean archived, MediaTargetType targetType, Long targetId,
                                        boolean includeArchived, int offset, int limit,
                                        Long folderId, boolean rootOnly, Boolean linked) {
        List<String> where = new ArrayList<>();
        Map<String, Object> params = new LinkedHashMap<>();
        if (linked != null) {
            where.add((linked ? "" : "not ") + "exists (select 1 from MediaLinkEntity x where x.assetId = a.id)");
        }
        if (folderId != null) {
            where.add("a.folderId = :folderId");
            params.put("folderId", folderId);
        } else if (rootOnly) {
            where.add("a.folderId is null");
        }
        if (archived != null || !includeArchived) {
            boolean archivedFilter = archived != null && archived;
            where.add("a.archived = :archived");
            params.put("archived", archivedFilter);
        }
        if (kind != null) {
            where.add("a.kind = :kind");
            params.put("kind", kind);
        }
        String q = query == null ? null : query.strip().toLowerCase(Locale.ROOT);
        if (q != null && !q.isBlank()) {
            where.add("(lower(a.name) like :query or exists (select 1 from MediaVersionEntity v "
                    + "where v.assetId = a.id and lower(v.originalFilename) like :query) "
                    + "or exists (select 1 from MediaLegacySourceEntity s where s.assetId = a.id "
                    + "and lower(s.label) like :query))");
            params.put("query", "%" + q + "%");
        }
        if (role != null || targetType != null || targetId != null) {
            List<String> link = new ArrayList<>(List.of("l.assetId = a.id"));
            if (role != null) {
                link.add("l.role = :role");
                params.put("role", role);
            }
            if (targetType != null) {
                link.add("l.targetType = :targetType");
                params.put("targetType", targetType);
            }
            if (targetId != null) {
                link.add("l.targetId = :targetId");
                params.put("targetId", targetId);
            }
            where.add("exists (select 1 from MediaLinkEntity l where "
                    + String.join(" and ", link) + ")");
        }
        String whereClause = where.isEmpty() ? "" : " where " + String.join(" and ", where);
        var selection = entities.createQuery("select a from MediaAssetEntity a"
                        + whereClause + " order by a.updatedAt desc, a.id desc",
                MediaAssetEntity.class);
        params.forEach(selection::setParameter);
        selection.setFirstResult(Math.max(0, offset));
        selection.setMaxResults(Math.max(1, Math.min(limit, 200)));
        return summaries(selection.getResultList());
    }

    @Transactional
    public MediaDtos.Detail get(long id) {
        MediaAssetEntity asset = requiredAsset(id);
        /* Opening a file is the moment an older image gets its web copy. */
        ensureWeb(asset, currentVersion(asset));
        return detail(asset);
    }

    @Transactional
    public MediaDtos.Detail rename(long id, String name) {
        MediaAssetEntity asset = lockAsset(id);
        String before = asset.name;
        asset.name = cleanName(name, currentVersion(asset).originalFilename);
        asset.updatedAt = Instant.now();
        if (!Objects.equals(before, asset.name)) {
            activity.record(ActivityLogService.ACTION_DOCUMENT_RENAMED, ACTIVITY_ENTITY,
                    asset.id.toString(), asset.name, "Medianaam gewijzigd",
                    ActivityChangeSet.create().privateValue(
                            "media.name", "Naam", before, asset.name).build());
        }
        return detail(asset);
    }

    @Transactional
    public MediaDtos.Detail replace(long id, MediaUploadPolicy.ValidatedFile file) {
        MediaAssetEntity asset = lockAsset(id);
        if (asset.archived) throw new BusinessRuleException("Herstel het bestand voordat u een versie vervangt");
        MediaVersionEntity previous = currentVersion(asset);
        String sha = sha256(file.bytes());
        MediaVersionEntity sameBytes = versionBySha(sha);
        if (sameBytes != null && !sameBytes.assetId.equals(asset.id)) {
            MediaAssetEntity owner = requiredAsset(sameBytes.assetId);
            throw new BusinessRuleException(
                    "Dit bestand bestaat al als ‘" + owner.name + "’; hergebruik die media-entry");
        }
        if (sameBytes != null) {
            if (sameBytes.id.equals(previous.id)) return detail(asset);
            asset.currentVersionId = sameBytes.id;
            asset.kind = kindOf(sameBytes);
            asset.updatedAt = Instant.now();
            activity.record(ActivityLogService.ACTION_UPDATED, ACTIVITY_ENTITY,
                    asset.id.toString(), asset.name, "Bestaande bestandsversie hersteld",
                    ActivityChangeSet.create()
                            .add("media.version", "Versie",
                                    previous.versionNumber, sameBytes.versionNumber)
                            .privateValue("media.filename", "Bestand",
                                    previous.originalFilename, sameBytes.originalFilename)
                            .build());
            return detail(asset);
        }

        String storageKey = "sha256-" + sha
                + MediaUploadPolicy.deterministicExtension(file.originalFilename(), file.contentType());
        boolean alreadyStored = storage.exists(storageKey);
        PhotoStorage.Stored stored = storage.storeKnown(storageKey, file.originalFilename(),
                file.contentType(), file.bytes());
        if (!alreadyStored) fireUploadRollbackCleanup(storageKey);

        int next = entities.createQuery(
                        "select coalesce(max(v.versionNumber), 0) from MediaVersionEntity v where v.assetId = :id",
                        Integer.class)
                .setParameter("id", asset.id).getSingleResult() + 1;
        MediaVersionEntity version = new MediaVersionEntity();
        version.assetId = asset.id;
        version.versionNumber = next;
        version.storageKey = stored.storageKey();
        version.originalFilename = file.originalFilename();
        version.contentType = file.contentType();
        version.sizeBytes = stored.sizeBytes();
        version.sha256 = sha;
        version.widthPx = stored.widthPx();
        version.heightPx = stored.heightPx();
        applyThumbnail(version, file);
        applyWeb(version, file);
        version.createdAt = Instant.now();
        version.createdBy = actor.current().username();
        entities.persist(version);
        entities.flush();

        asset.currentVersionId = version.id;
        asset.kind = file.kind();
        asset.updatedAt = version.createdAt;
        activity.record(ActivityLogService.ACTION_UPDATED, ACTIVITY_ENTITY,
                asset.id.toString(), asset.name, "Nieuwe bestandsversie toegevoegd",
                ActivityChangeSet.create()
                        .add("media.version", "Versie",
                                previous.versionNumber, version.versionNumber)
                        .privateValue("media.filename", "Bestand", null, version.originalFilename)
                        .build());
        return detail(asset);
    }

    @Transactional
    public MediaDtos.Detail link(long id, MediaDtos.LinkRequest request) {
        if (request == null || request.targetType() == null
                || request.targetId() == null || request.role() == null) {
            throw new BusinessRuleException("Kies een doel en een rol voor de koppeling");
        }
        lockAndLabel(request.targetType(), request.targetId());
        MediaAssetEntity asset = lockAsset(id);
        if (asset.archived) throw new BusinessRuleException("Een gearchiveerd bestand kan niet gekoppeld worden");
        demotePrimary(request.targetType(), request.targetId(), request.role());

        MediaLinkEntity link = entities.createQuery("select l from MediaLinkEntity l "
                        + "where l.assetId = :asset and l.targetType = :type "
                        + "and l.targetId = :target and l.role = :role", MediaLinkEntity.class)
                .setParameter("asset", asset.id)
                .setParameter("type", request.targetType())
                .setParameter("target", request.targetId())
                .setParameter("role", request.role())
                .getResultStream().findFirst().orElse(null);
        if (link == null) {
            link = new MediaLinkEntity();
            link.assetId = asset.id;
            link.targetType = request.targetType();
            link.targetId = request.targetId();
            link.role = request.role();
            link.pinnedVersionId = historical(request.targetType()) ? asset.currentVersionId : null;
            link.createdAt = Instant.now();
            link.createdBy = actor.current().username();
            entities.persist(link);
        }
        link.legacyOnly = false;
        link.primarySlot = 1;
        asset.updatedAt = Instant.now();
        activity.record(ActivityLogService.ACTION_UPDATED, ACTIVITY_ENTITY,
                asset.id.toString(), asset.name, "Bestand gekoppeld",
                ActivityChangeSet.create()
                        .add("media.targetType", "Doeltype", null, request.targetType())
                        .add("media.targetId", "Doel", null, request.targetId())
                        .add("media.role", "Rol", null, request.role())
                        .build());
        return detail(asset);
    }

    @Transactional
    public MediaDtos.Detail unlink(long assetId, long linkId) {
        MediaLinkEntity link = entities.find(MediaLinkEntity.class, linkId);
        if (link == null || !link.assetId.equals(assetId)) throw new NotFoundException("Mediakoppeling", linkId);
        lockAndLabel(link.targetType, link.targetId);
        MediaAssetEntity asset = lockAsset(assetId);
        link = entities.find(MediaLinkEntity.class, linkId, LockModeType.PESSIMISTIC_WRITE);
        if (link == null || !link.assetId.equals(assetId)) throw new NotFoundException("Mediakoppeling", linkId);
        if (link.legacyOnly) {
            long legacySources = entities.createQuery("select count(s) from MediaLegacySourceEntity s "
                            + "where s.assetId = :asset and s.targetType = :type "
                            + "and s.targetId = :target and s.role = :role", Long.class)
                    .setParameter("asset", link.assetId).setParameter("type", link.targetType)
                    .setParameter("target", link.targetId).setParameter("role", link.role)
                    .getSingleResult();
            if (legacySources > 0) {
                throw new BusinessRuleException(
                        "Deze koppeling komt uit een bestaand product of document; verwijder haar bij de bron");
            }
        }
        MediaTargetType type = link.targetType;
        Long targetId = link.targetId;
        MediaRole role = link.role;
        boolean primary = link.primary();
        entities.remove(link);
        entities.flush();
        if (primary) promoteFirst(type, targetId, role);
        asset.updatedAt = Instant.now();
        activity.record(ActivityLogService.ACTION_UPDATED, ACTIVITY_ENTITY,
                asset.id.toString(), asset.name, "Mediakoppeling verwijderd",
                ActivityChangeSet.create()
                        .add("media.targetType", "Doeltype", type, null)
                        .add("media.targetId", "Doel", targetId, null)
                        .add("media.role", "Rol", role, null)
                        .build());
        return detail(asset);
    }

    @Transactional
    public MediaDtos.Detail archive(long id) {
        MediaAssetEntity asset = lockAsset(id);
        if (!asset.archived) {
            asset.archived = true;
            asset.updatedAt = Instant.now();
            activity.record(ActivityLogService.ACTION_STATUS_CHANGED, ACTIVITY_ENTITY,
                    asset.id.toString(), asset.name, "Bestand gearchiveerd",
                    ActivityChangeSet.create().add("archived", "Gearchiveerd", false, true).build());
        }
        return detail(asset);
    }

    @Transactional
    public MediaDtos.Detail restore(long id) {
        MediaAssetEntity asset = lockAsset(id);
        if (asset.archived) {
            asset.archived = false;
            asset.updatedAt = Instant.now();
            activity.record(ActivityLogService.ACTION_STATUS_CHANGED, ACTIVITY_ENTITY,
                    asset.id.toString(), asset.name, "Bestand hersteld",
                    ActivityChangeSet.create().add("archived", "Gearchiveerd", true, false).build());
        }
        return detail(asset);
    }

    @Transactional
    public List<String> delete(long id) {
        MediaAssetEntity asset = lockAsset(id);
        if (!asset.archived) {
            throw new BusinessRuleException("Archiveer het bestand voordat u het definitief verwijdert");
        }
        long links = entities.createQuery(
                        "select count(l) from MediaLinkEntity l where l.assetId = :id", Long.class)
                .setParameter("id", id).getSingleResult();
        long legacySources = entities.createQuery(
                        "select count(s) from MediaLegacySourceEntity s where s.assetId = :id", Long.class)
                .setParameter("id", id).getSingleResult();
        if (links > 0 || legacySources > 0) {
            throw new BusinessRuleException(
                    "Verwijder eerst alle koppelingen; gekoppelde bestanden kunnen niet verdwijnen");
        }
        List<MediaVersionEntity> versions = versions(id);
        List<String> keys = versions.stream()
                .flatMap(version -> java.util.stream.Stream.of(
                        version.storageKey, version.thumbnailStorageKey))
                .filter(Objects::nonNull).filter(key -> !key.isBlank()).distinct().toList();
        versions.forEach(entities::remove);
        entities.remove(asset);
        activity.record(ActivityLogService.ACTION_DELETED, ACTIVITY_ENTITY,
                Long.toString(id), asset.name, "Media-entry definitief verwijderd");
        fireDeleteCleanup(keys);
        return keys;
    }

    @Transactional
    public FileRef file(long id) {
        MediaAssetEntity asset = requiredAsset(id);
        MediaVersionEntity version = currentVersion(asset);
        return new FileRef(storage.read(version.storageKey), version.contentType,
                version.originalFilename, version.sizeBytes);
    }

    @Transactional
    public FileRef versionFile(long assetId, long versionId) {
        requiredAsset(assetId);
        MediaVersionEntity version = entities.find(MediaVersionEntity.class, versionId);
        if (version == null || !version.assetId.equals(assetId)) {
            throw new NotFoundException("Mediaversie", versionId);
        }
        return new FileRef(storage.read(version.storageKey), version.contentType,
                version.originalFilename, version.sizeBytes);
    }

    @Transactional
    public FileRef thumbnail(long assetId) {
        MediaAssetEntity asset = requiredAsset(assetId);
        MediaVersionEntity version = currentVersion(asset);
        if (asset.kind != MediaKind.IMAGE) throw new NotFoundException("Voorbeeld", assetId);
        String key = version.thumbnailStorageKey == null
                ? version.storageKey : version.thumbnailStorageKey;
        long size = version.thumbnailSizeBytes == null
                ? version.sizeBytes : version.thumbnailSizeBytes;
        InputStream raw = storage.read(key);
        try {
            PushbackInputStream verified = new PushbackInputStream(raw, 16);
            byte[] prefix = verified.readNBytes(16);
            verified.unread(prefix);
            String sniffedType = MediaUploadPolicy.sniffSafeRaster(prefix);
            if (sniffedType == null) {
                verified.close();
                throw new NotFoundException("Voorbeeld", assetId);
            }
            return new FileRef(verified, sniffedType, version.originalFilename, size);
        } catch (RuntimeException exception) {
            try { raw.close(); } catch (Exception ignored) {}
            throw exception;
        } catch (Exception exception) {
            try { raw.close(); } catch (Exception ignored) {}
            throw new NotFoundException("Voorbeeld", assetId);
        }
    }

    /** Removes the registry relation when an old detail screen deletes its owning row. */
    @Transactional
    public void unlinkLegacy(MediaLegacySourceType sourceType, long sourceId) {
        MediaLegacySourceEntity source = entities.createQuery(
                        "select s from MediaLegacySourceEntity s where s.sourceType = :type and s.sourceId = :id",
                        MediaLegacySourceEntity.class)
                .setParameter("type", sourceType).setParameter("id", sourceId)
                .getResultStream().findFirst().orElse(null);
        if (source == null) return;
        lockAndLabel(source.targetType, source.targetId);
        lockAsset(source.assetId);
        source = entities.createQuery(
                        "select s from MediaLegacySourceEntity s where s.sourceType = :type and s.sourceId = :id",
                        MediaLegacySourceEntity.class)
                .setParameter("type", sourceType).setParameter("id", sourceId)
                .setLockMode(LockModeType.PESSIMISTIC_WRITE)
                .getResultStream().findFirst().orElse(null);
        if (source == null) return;
        MediaLinkEntity link = entities.createQuery("select l from MediaLinkEntity l "
                        + "where l.assetId = :asset and l.targetType = :type "
                        + "and l.targetId = :target and l.role = :role", MediaLinkEntity.class)
                .setParameter("asset", source.assetId).setParameter("type", source.targetType)
                .setParameter("target", source.targetId).setParameter("role", source.role)
                .setLockMode(LockModeType.PESSIMISTIC_WRITE)
                .getResultStream().findFirst().orElse(null);
        entities.remove(source);
        entities.flush();
        if (link == null || !link.legacyOnly) return;
        long remaining = entities.createQuery("select count(s) from MediaLegacySourceEntity s "
                        + "where s.assetId = :asset and s.targetType = :type "
                        + "and s.targetId = :target and s.role = :role", Long.class)
                .setParameter("asset", link.assetId).setParameter("type", link.targetType)
                .setParameter("target", link.targetId).setParameter("role", link.role)
                .getSingleResult();
        if (remaining > 0) return;
        boolean primary = link.primary();
        MediaTargetType targetType = link.targetType;
        Long targetId = link.targetId;
        MediaRole role = link.role;
        entities.remove(link);
        entities.flush();
        if (primary) promoteFirst(targetType, targetId, role);
    }

    /** Removes registry relations when their owning business record is deleted. */
    @Transactional
    public void unlinkTarget(MediaTargetType targetType, long targetId) {
        lockAndLabel(targetType, targetId);
        List<MediaLinkEntity> links = entities.createQuery(
                        "select l from MediaLinkEntity l where l.targetType = :type and l.targetId = :id",
                        MediaLinkEntity.class)
                .setParameter("type", targetType).setParameter("id", targetId).getResultList();
        List<MediaLegacySourceEntity> sources = entities.createQuery(
                        "select s from MediaLegacySourceEntity s where s.targetType = :type and s.targetId = :id",
                        MediaLegacySourceEntity.class)
                .setParameter("type", targetType).setParameter("id", targetId).getResultList();
        if (links.isEmpty() && sources.isEmpty()) return;
        LinkedHashSet<Long> changedAssets = new LinkedHashSet<>();
        links.forEach(link -> changedAssets.add(link.assetId));
        sources.forEach(source -> changedAssets.add(source.assetId));
        sources.forEach(entities::remove);
        links.forEach(entities::remove);
        if (!changedAssets.isEmpty()) {
            entities.createQuery("update MediaAssetEntity a set a.updatedAt = :now where a.id in :ids")
                    .setParameter("now", Instant.now()).setParameter("ids", changedAssets)
                    .executeUpdate();
        }
    }

    /** Idempotently indexes one legacy owner without copying its original bytes. */
    @Transactional
    public void indexLegacy(LegacyFile source) {
        if (source == null) return;
        boolean alreadyIndexed = entities.createQuery(
                        "select count(s) from MediaLegacySourceEntity s "
                                + "where s.sourceType = :type and s.sourceId = :id", Long.class)
                .setParameter("type", source.sourceType()).setParameter("id", source.sourceId())
                .getSingleResult() > 0;
        if (alreadyIndexed) return;
        lockAndLabel(source.targetType(), source.targetId());
        if (!storage.exists(source.storageKey())) {
            throw new IllegalStateException("Legacybestand ontbreekt in blobopslag: " + source.storageKey());
        }
        LegacyDigest digest = digestLegacy(source);
        String sha = digest.sha256();
        MediaVersionEntity version = versionBySha(sha);
        MediaAssetEntity asset;
        if (version == null) {
            Instant now = Instant.now();
            asset = new MediaAssetEntity();
            asset.name = cleanName(source.label(), source.originalFilename());
            asset.kind = source.kind();
            asset.archived = false;
            asset.createdAt = source.addedAt() == null ? now : source.addedAt();
            asset.updatedAt = now;
            asset.createdBy = source.createdBy() == null ? "system" : source.createdBy();
            entities.persist(asset);
            entities.flush();

            version = new MediaVersionEntity();
            version.assetId = asset.id;
            version.versionNumber = 1;
            version.storageKey = source.storageKey();
            version.originalFilename = MediaUploadPolicy.safeFilename(source.originalFilename());
            version.contentType = safeLegacyContentType(source.contentType(), source.kind());
            version.sizeBytes = digest.sizeBytes();
            version.sha256 = sha;
            version.widthPx = source.widthPx();
            version.heightPx = source.heightPx();
            version.createdAt = asset.createdAt;
            version.createdBy = asset.createdBy;
            applyLegacyThumbnail(version, source, digest.thumbnailSource());
            entities.persist(version);
            entities.flush();
            asset.currentVersionId = version.id;
        } else {
            asset = requiredAsset(version.assetId);
        }

        MediaLegacySourceEntity binding = new MediaLegacySourceEntity();
        binding.sourceType = source.sourceType();
        binding.sourceId = source.sourceId();
        binding.assetId = asset.id;
        binding.versionId = version.id;
        binding.targetType = source.targetType();
        binding.targetId = source.targetId();
        binding.role = source.role();
        binding.label = source.label() == null ? null : cleanName(source.label(), null);
        binding.indexedAt = Instant.now();
        entities.persist(binding);

        MediaLinkEntity link = entities.createQuery("select l from MediaLinkEntity l "
                        + "where l.assetId = :asset and l.targetType = :type "
                        + "and l.targetId = :target and l.role = :role", MediaLinkEntity.class)
                .setParameter("asset", asset.id).setParameter("type", source.targetType())
                .setParameter("target", source.targetId()).setParameter("role", source.role())
                .getResultStream().findFirst().orElse(null);
        if (link == null) {
            link = new MediaLinkEntity();
            link.assetId = asset.id;
            link.targetType = source.targetType();
            link.targetId = source.targetId();
            link.role = source.role();
            link.legacyOnly = true;
            link.pinnedVersionId = historical(source.targetType()) ? version.id : null;
            link.createdAt = binding.indexedAt;
            link.createdBy = "system";
            long primary = entities.createQuery("select count(l) from MediaLinkEntity l "
                            + "where l.targetType = :type and l.targetId = :target "
                            + "and l.role = :role and l.primarySlot = 1", Long.class)
                    .setParameter("type", source.targetType()).setParameter("target", source.targetId())
                    .setParameter("role", source.role()).getSingleResult();
            // The first successfully indexed source becomes primary. This is deliberately not
            // tied to legacy position zero: one missing/corrupt first row must not leave the
            // target without a usable primary media selection forever.
            if (primary == 0) link.primarySlot = 1;
            entities.persist(link);
        }
    }

    /* ---------------------------------------------------------------- folders */

    @Transactional
    public List<MediaDtos.Folder> folders() {
        Map<Long, Long> counts = new LinkedHashMap<>();
        for (Object[] row : entities.createQuery(
                "select a.folderId, count(a) from MediaAssetEntity a where a.folderId is not null "
                        + "and a.archived = false group by a.folderId", Object[].class).getResultList()) {
            counts.put((Long) row[0], (Long) row[1]);
        }
        return entities.createQuery("select f from MediaFolderEntity f order by lower(f.name), f.id",
                        MediaFolderEntity.class).getResultList().stream()
                .map(folder -> new MediaDtos.Folder(folder.id, folder.name, folder.parentId,
                        counts.getOrDefault(folder.id, 0L)))
                .toList();
    }

    @Transactional
    public MediaDtos.Folder createFolder(MediaDtos.FolderRequest request) {
        MediaFolderEntity folder = new MediaFolderEntity();
        folder.name = folderName(request == null ? null : request.name());
        folder.parentId = request == null || request.parentId() == null ? null : requiredFolder(request.parentId()).id;
        folder.createdAt = Instant.now();
        folder.createdBy = actor.current().username();
        entities.persist(folder);
        entities.flush();
        return new MediaDtos.Folder(folder.id, folder.name, folder.parentId, 0);
    }

    /** Renames and, when the parent changes, moves the folder; a folder never lands inside itself. */
    @Transactional
    public MediaDtos.Folder updateFolder(long id, MediaDtos.FolderRequest request) {
        MediaFolderEntity folder = requiredFolder(id);
        if (request != null && request.name() != null) folder.name = folderName(request.name());
        if (request != null) {
            Long parentId = request.parentId();
            if (parentId != null) {
                requiredFolder(parentId);
                for (Long cursor = parentId; cursor != null; cursor = requiredFolder(cursor).parentId) {
                    if (cursor.equals(id)) throw new BusinessRuleException("Een map kan niet in zichzelf.");
                }
            }
            folder.parentId = parentId;
        }
        long count = entities.createQuery(
                        "select count(a) from MediaAssetEntity a where a.folderId = :id and a.archived = false", Long.class)
                .setParameter("id", id).getSingleResult();
        return new MediaDtos.Folder(folder.id, folder.name, folder.parentId, count);
    }

    /** Deleting a folder hands its files and subfolders to the parent; nothing is lost. */
    @Transactional
    public void deleteFolder(long id) {
        MediaFolderEntity folder = requiredFolder(id);
        entities.createQuery("update MediaAssetEntity a set a.folderId = :parent where a.folderId = :id")
                .setParameter("parent", folder.parentId).setParameter("id", id).executeUpdate();
        entities.createQuery("update MediaFolderEntity f set f.parentId = :parent where f.parentId = :id")
                .setParameter("parent", folder.parentId).setParameter("id", id).executeUpdate();
        entities.remove(folder);
    }

    @Transactional
    public MediaDtos.Detail move(long assetId, Long folderId) {
        MediaAssetEntity asset = requiredAsset(assetId);
        asset.folderId = folderId == null ? null : requiredFolder(folderId).id;
        asset.updatedAt = Instant.now();
        return detail(asset);
    }

    private MediaFolderEntity requiredFolder(long id) {
        MediaFolderEntity folder = entities.find(MediaFolderEntity.class, id);
        if (folder == null) throw new NotFoundException("Map", id);
        return folder;
    }

    private static String folderName(String raw) {
        String name = raw == null ? "" : raw.strip();
        if (name.isEmpty()) throw new BusinessRuleException("Geef de map een naam.");
        if (name.length() > 120) throw new BusinessRuleException("Een mapnaam telt maximaal 120 tekens.");
        return name;
    }

    /* ---------------------------------------------------------------- public links */

    /** One live link per asset; asking again returns the same token. */
    @Transactional
    public MediaDtos.Detail share(long assetId) {
        MediaAssetEntity asset = requiredAsset(assetId);
        if (activeShare(assetId) == null) {
            MediaShareEntity share = new MediaShareEntity();
            share.assetId = assetId;
            share.token = newShareToken();
            share.createdAt = Instant.now();
            share.createdBy = actor.current().username();
            entities.persist(share);
            entities.flush();
            activity.record(ActivityLogService.ACTION_DOCUMENT_ADDED, ACTIVITY_ENTITY,
                    asset.id.toString(), asset.name, "Publieke link gemaakt", ActivityChangeSet.create().build());
        }
        return detail(asset);
    }

    @Transactional
    public MediaDtos.Detail unshare(long assetId) {
        MediaAssetEntity asset = requiredAsset(assetId);
        MediaShareEntity share = activeShare(assetId);
        if (share != null) {
            share.revokedAt = Instant.now();
            activity.record(ActivityLogService.ACTION_DOCUMENT_ADDED, ACTIVITY_ENTITY,
                    asset.id.toString(), asset.name, "Publieke link ingetrokken", ActivityChangeSet.create().build());
        }
        return detail(asset);
    }

    /** The current version behind a live token; unknown or revoked tokens read as not found. */
    @Transactional
    public FileRef publicFile(String token) {
        return publicFile(token, false);
    }

    @Transactional
    public FileRef publicFile(String token, boolean web) {
        if (token == null || token.length() < 20 || token.length() > 64) throw new NotFoundException("Bestand", 0L);
        List<MediaShareEntity> shares = entities.createQuery(
                        "select s from MediaShareEntity s where s.token = :token and s.revokedAt is null",
                        MediaShareEntity.class)
                .setParameter("token", token).getResultList();
        if (shares.isEmpty()) throw new NotFoundException("Bestand", 0L);
        MediaShareEntity share = shares.get(0);
        MediaAssetEntity asset = entities.find(MediaAssetEntity.class, share.assetId);
        if (asset == null || asset.archived) throw new NotFoundException("Bestand", share.assetId);
        share.downloads++;
        MediaVersionEntity version = currentVersion(asset);
        if (web) {
            ensureWeb(asset, version);
            return webRef(version);
        }
        return new FileRef(storage.read(version.storageKey), version.contentType,
                version.originalFilename, version.sizeBytes);
    }

    private MediaShareEntity activeShare(Long assetId) {
        List<MediaShareEntity> shares = entities.createQuery(
                        "select s from MediaShareEntity s where s.assetId = :id and s.revokedAt is null order by s.id desc",
                        MediaShareEntity.class)
                .setParameter("id", assetId).setMaxResults(1).getResultList();
        return shares.isEmpty() ? null : shares.get(0);
    }

    private static MediaDtos.Share share(MediaShareEntity share) {
        return share == null ? null
                : new MediaDtos.Share(share.token, share.createdAt, share.createdBy, share.downloads);
    }

    private static String newShareToken() {
        byte[] bytes = new byte[24];
        new java.security.SecureRandom().nextBytes(bytes);
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    @Transactional
    public MediaDtos.Detail detailForAsset(long id) {
        return detail(requiredAsset(id));
    }

    private MediaDtos.Summary summary(MediaAssetEntity asset) {
        MediaVersionEntity current = currentVersion(asset);
        List<MediaDtos.Link> links = links(asset.id);
        List<MediaRole> roles = links.stream().map(MediaDtos.Link::role)
                .distinct().sorted().toList();
        int versionCount = Math.toIntExact(entities.createQuery(
                        "select count(v) from MediaVersionEntity v where v.assetId = :id", Long.class)
                .setParameter("id", asset.id).getSingleResult());
        return new MediaDtos.Summary(asset.id, asset.name, current.originalFilename,
                current.contentType, current.sizeBytes, current.sha256, asset.kind,
                current.widthPx, current.heightPx, asset.archived, asset.createdAt,
                asset.updatedAt, asset.currentVersionId, roles, links, versionCount,
                asset.folderId, share(activeShare(asset.id)), web(current));
    }

    /** Hydrates a whole list page in bounded bulk queries instead of 3+N lookups per asset. */
    private List<MediaDtos.Summary> summaries(List<MediaAssetEntity> assets) {
        if (assets == null || assets.isEmpty()) return List.of();
        List<Long> assetIds = assets.stream().map(asset -> asset.id).toList();
        List<Long> currentIds = assets.stream().map(asset -> asset.currentVersionId)
                .filter(Objects::nonNull).distinct().toList();
        Map<Long, MediaShareEntity> shares = new LinkedHashMap<>();
        for (MediaShareEntity share : entities.createQuery(
                        "select s from MediaShareEntity s where s.assetId in :ids and s.revokedAt is null",
                        MediaShareEntity.class)
                .setParameter("ids", assetIds).getResultList()) {
            shares.put(share.assetId, share);
        }

        Map<Long, MediaVersionEntity> currentById = currentIds.isEmpty() ? Map.of()
                : entities.createQuery("select v from MediaVersionEntity v where v.id in :ids",
                                MediaVersionEntity.class)
                        .setParameter("ids", currentIds).getResultList().stream()
                        .collect(java.util.stream.Collectors.toMap(version -> version.id, version -> version));
        List<MediaLinkEntity> linkRows = entities.createQuery(
                        "select l from MediaLinkEntity l where l.assetId in :ids "
                                + "order by l.assetId, l.targetType, l.targetId, l.role, l.id",
                        MediaLinkEntity.class)
                .setParameter("ids", assetIds).getResultList();
        Map<TargetKey, String> targetLabels = targetLabels(linkRows);
        Map<Long, List<MediaDtos.Link>> linksByAsset = new LinkedHashMap<>();
        for (MediaLinkEntity link : linkRows) {
            linksByAsset.computeIfAbsent(link.assetId, ignored -> new ArrayList<>())
                    .add(new MediaDtos.Link(link.id, link.targetType, link.targetId,
                            targetLabels.get(new TargetKey(link.targetType, link.targetId)),
                            link.role, link.primary(), link.pinnedVersionId,
                            link.createdAt, link.createdBy));
        }
        Map<Long, Integer> versionCounts = new LinkedHashMap<>();
        for (Object[] row : entities.createQuery(
                        "select v.assetId, count(v) from MediaVersionEntity v "
                                + "where v.assetId in :ids group by v.assetId", Object[].class)
                .setParameter("ids", assetIds).getResultList()) {
            versionCounts.put((Long) row[0], Math.toIntExact((Long) row[1]));
        }

        List<MediaDtos.Summary> result = new ArrayList<>(assets.size());
        for (MediaAssetEntity asset : assets) {
            MediaVersionEntity current = currentById.get(asset.currentVersionId);
            if (current == null || !current.assetId.equals(asset.id)) {
                throw new IllegalStateException("Media-entry heeft geen geldige actuele versie");
            }
            List<MediaDtos.Link> links = List.copyOf(
                    linksByAsset.getOrDefault(asset.id, List.of()));
            List<MediaRole> roles = links.stream().map(MediaDtos.Link::role)
                    .distinct().sorted().toList();
            result.add(new MediaDtos.Summary(asset.id, asset.name, current.originalFilename,
                    current.contentType, current.sizeBytes, current.sha256, asset.kind,
                    current.widthPx, current.heightPx, asset.archived, asset.createdAt,
                    asset.updatedAt, asset.currentVersionId, roles, links,
                    versionCounts.getOrDefault(asset.id, 0),
                    asset.folderId, share(shares.get(asset.id)), web(current)));
        }
        return List.copyOf(result);
    }

    private Map<TargetKey, String> targetLabels(List<MediaLinkEntity> links) {
        Map<TargetKey, String> labels = new LinkedHashMap<>();
        for (MediaTargetType type : MediaTargetType.values()) {
            List<Long> ids = links.stream().filter(link -> link.targetType == type)
                    .map(link -> link.targetId).distinct().toList();
            if (ids.isEmpty()) continue;
            switch (type) {
                case PRODUCT -> entities.createQuery(
                                "select p from ProductEntity p where p.id in :ids", ProductEntity.class)
                        .setParameter("ids", ids).getResultList()
                        .forEach(row -> labels.put(new TargetKey(type, row.id), label(row)));
                case PRODUCT_FAMILY -> entities.createQuery(
                                "select f from ProductFamilyEntity f where f.id in :ids",
                                ProductFamilyEntity.class)
                        .setParameter("ids", ids).getResultList()
                        .forEach(row -> labels.put(new TargetKey(type, row.id), label(row)));
                /* A nested entity is not reachable by its simple name in HQL. */
                case PURCHASE_ORDER -> entities.createQuery(
                                "select p from " + entities.getMetamodel()
                                        .entity(SourcingEntities.PurchaseOrderEntity.class).getName()
                                        + " p where p.id in :ids",
                                SourcingEntities.PurchaseOrderEntity.class)
                        .setParameter("ids", ids).getResultList()
                        .forEach(row -> labels.put(new TargetKey(type, row.id), label(row)));
                case PLANNER_ITEM -> entities.createQuery(
                                "select p from PlannerItemEntity p where p.id in :ids",
                                PlannerItemEntity.class)
                        .setParameter("ids", ids).getResultList()
                        .forEach(row -> labels.put(new TargetKey(type, row.id), label(row)));
            }
        }
        return labels;
    }

    private MediaDtos.Detail detail(MediaAssetEntity asset) {
        MediaDtos.Summary summary = summary(asset);
        List<MediaDtos.Version> versions = versions(asset.id).stream()
                .map(this::toVersion).toList();
        return new MediaDtos.Detail(summary.id(), summary.name(), summary.originalFilename(),
                summary.contentType(), summary.sizeBytes(), summary.sha256(), summary.kind(),
                summary.widthPx(), summary.heightPx(), summary.archived(), summary.createdAt(),
                summary.updatedAt(), summary.currentVersionId(), summary.roles(), summary.links(),
                summary.versionCount(), versions, summary.folderId(), summary.share(), summary.web());
    }

    private List<MediaDtos.Link> links(Long assetId) {
        return entities.createQuery("select l from MediaLinkEntity l where l.assetId = :id "
                        + "order by l.targetType, l.targetId, l.role, l.id", MediaLinkEntity.class)
                .setParameter("id", assetId).getResultList().stream()
                .map(link -> new MediaDtos.Link(link.id, link.targetType, link.targetId,
                        targetLabel(link.targetType, link.targetId), link.role, link.primary(),
                        link.pinnedVersionId, link.createdAt, link.createdBy))
                .toList();
    }

    private List<MediaVersionEntity> versions(Long assetId) {
        return entities.createQuery("select v from MediaVersionEntity v where v.assetId = :id "
                        + "order by v.versionNumber desc", MediaVersionEntity.class)
                .setParameter("id", assetId).getResultList();
    }

    private MediaDtos.Version toVersion(MediaVersionEntity version) {
        return new MediaDtos.Version(version.id, version.versionNumber,
                version.originalFilename, version.contentType, version.sizeBytes, version.sha256,
                version.widthPx, version.heightPx, version.createdAt, version.createdBy, web(version));
    }

    private static MediaDtos.Rendition web(MediaVersionEntity version) {
        if (version == null || version.webStorageKey == null || version.webSizeBytes == null) return null;
        return new MediaDtos.Rendition(version.webSizeBytes, version.webWidthPx, version.webHeightPx);
    }

    private MediaAssetEntity lockAsset(long id) {
        MediaAssetEntity asset = entities.find(MediaAssetEntity.class, id, LockModeType.PESSIMISTIC_WRITE);
        if (asset == null) throw new NotFoundException("Media", id);
        return asset;
    }

    private MediaAssetEntity requiredAsset(long id) {
        MediaAssetEntity asset = entities.find(MediaAssetEntity.class, id);
        if (asset == null) throw new NotFoundException("Media", id);
        return asset;
    }

    private MediaVersionEntity currentVersion(MediaAssetEntity asset) {
        MediaVersionEntity version = asset.currentVersionId == null
                ? null : entities.find(MediaVersionEntity.class, asset.currentVersionId);
        if (version == null || !version.assetId.equals(asset.id)) {
            throw new IllegalStateException("Media-entry heeft geen geldige actuele versie");
        }
        return version;
    }

    private MediaVersionEntity versionBySha(String sha) {
        return entities.createQuery("select v from MediaVersionEntity v where v.sha256 = :sha",
                        MediaVersionEntity.class)
                .setParameter("sha", sha).getResultStream().findFirst().orElse(null);
    }

    private void demotePrimary(MediaTargetType type, Long targetId, MediaRole role) {
        entities.createQuery("update MediaLinkEntity l set l.primarySlot = null "
                        + "where l.targetType = :type and l.targetId = :target "
                        + "and l.role = :role and l.primarySlot = 1")
                .setParameter("type", type).setParameter("target", targetId)
                .setParameter("role", role).executeUpdate();
    }

    private void promoteFirst(MediaTargetType type, Long targetId, MediaRole role) {
        MediaLinkEntity next = entities.createQuery("select l from MediaLinkEntity l "
                        + "where l.targetType = :type and l.targetId = :target and l.role = :role "
                        + "order by l.id", MediaLinkEntity.class)
                .setParameter("type", type).setParameter("target", targetId)
                .setParameter("role", role).setMaxResults(1)
                .getResultStream().findFirst().orElse(null);
        if (next != null) next.primarySlot = 1;
    }

    private String lockAndLabel(MediaTargetType type, Long id) {
        Object target = switch (type) {
            case PRODUCT -> entities.find(ProductEntity.class, id, LockModeType.PESSIMISTIC_WRITE);
            case PRODUCT_FAMILY -> entities.find(ProductFamilyEntity.class, id, LockModeType.PESSIMISTIC_WRITE);
            case PURCHASE_ORDER -> entities.find(SourcingEntities.PurchaseOrderEntity.class, id,
                    LockModeType.PESSIMISTIC_WRITE);
            case PLANNER_ITEM -> entities.find(PlannerItemEntity.class, id, LockModeType.PESSIMISTIC_WRITE);
        };
        if (target == null) throw new NotFoundException(targetTypeLabel(type), id);
        return label(target);
    }

    private String targetLabel(MediaTargetType type, Long id) {
        Object target = switch (type) {
            case PRODUCT -> entities.find(ProductEntity.class, id);
            case PRODUCT_FAMILY -> entities.find(ProductFamilyEntity.class, id);
            case PURCHASE_ORDER -> entities.find(SourcingEntities.PurchaseOrderEntity.class, id);
            case PLANNER_ITEM -> entities.find(PlannerItemEntity.class, id);
        };
        return target == null ? null : label(target);
    }

    private static String label(Object target) {
        if (target instanceof ProductEntity product) {
            return product.sku == null ? product.name : product.sku + " · " + product.name;
        }
        if (target instanceof ProductFamilyEntity family) return family.name;
        if (target instanceof SourcingEntities.PurchaseOrderEntity order) {
            return order.alias == null || order.alias.isBlank()
                    ? order.number : order.number + " · " + order.alias;
        }
        if (target instanceof PlannerItemEntity item) return item.title;
        return null;
    }

    private static String targetTypeLabel(MediaTargetType type) {
        return switch (type) {
            case PRODUCT -> "Product";
            case PRODUCT_FAMILY -> "Productfamilie";
            case PURCHASE_ORDER -> "Inkooporder";
            case PLANNER_ITEM -> "Agendapunt";
        };
    }

    private static boolean historical(MediaTargetType type) {
        return type == MediaTargetType.PURCHASE_ORDER || type == MediaTargetType.PLANNER_ITEM;
    }

    private static String cleanName(String requested, String fallback) {
        String value = requested == null || requested.isBlank() ? fallback : requested;
        if (value == null || value.isBlank()) value = "Bestand";
        value = value.strip();
        if (value.length() > MAX_NAME_LENGTH) value = value.substring(0, MAX_NAME_LENGTH);
        return value;
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception impossible) {
            throw new IllegalStateException("SHA-256 is niet beschikbaar", impossible);
        }
    }

    /** Hashes every legacy byte without applying the new-manager 25 MB upload limit. */
    private LegacyDigest digestLegacy(LegacyFile source) {
        boolean retainThumbnailSource = source.kind() == MediaKind.IMAGE
                && (source.thumbnailStorageKey() == null
                || !storage.exists(source.thumbnailStorageKey()));
        try (InputStream input = storage.read(source.storageKey());
             ByteArrayOutputStream retained = retainThumbnailSource
                     ? new ByteArrayOutputStream() : null) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[64 * 1024];
            long size = 0;
            boolean retain = retainThumbnailSource;
            int read;
            while ((read = input.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
                size += read;
                if (retain && size <= MediaUploadPolicy.MAX_BYTES) {
                    retained.write(buffer, 0, read);
                } else {
                    retain = false;
                }
            }
            byte[] thumbnailSource = retain && retained != null ? retained.toByteArray() : null;
            return new LegacyDigest(HexFormat.of().formatHex(digest.digest()), size, thumbnailSource);
        } catch (Exception exception) {
            throw new IllegalStateException("Legacybestand kon niet worden gelezen", exception);
        }
    }

    private void applyThumbnail(MediaVersionEntity version,
                                MediaUploadPolicy.ValidatedFile file) {
        if (file.kind() != MediaKind.IMAGE) return;
        PhotoRenditionService.Rendition thumbnail = renditions.small(
                new PhotoUploadPolicy.ValidatedPhoto(
                        file.originalFilename(), file.contentType(), file.bytes()));
        String key;
        if (thumbnail.sha256().equals(version.sha256)) {
            key = version.storageKey;
        } else {
            key = "sha256-" + thumbnail.sha256() + thumbnail.extension();
            boolean existed = storage.exists(key);
            storage.storeKnown(key, thumbnail.filename(), thumbnail.contentType(), thumbnail.bytes());
            if (!existed) fireUploadRollbackCleanup(key);
        }
        version.thumbnailStorageKey = key;
        version.thumbnailContentType = thumbnail.contentType();
        version.thumbnailSizeBytes = (long) thumbnail.bytes().length;
        version.thumbnailWidthPx = thumbnail.width();
        version.thumbnailHeightPx = thumbnail.height();
    }

    /** The web copy: at most 1600 px wide, or the original itself when that is already light. */
    private void applyWeb(MediaVersionEntity version, MediaUploadPolicy.ValidatedFile file) {
        if (file.kind() != MediaKind.IMAGE) return;
        PhotoRenditionService.Rendition web;
        try {
            web = renditions.web(new PhotoUploadPolicy.ValidatedPhoto(
                    file.originalFilename(), file.contentType(), file.bytes()));
        } catch (RuntimeException exception) {
            return; /* An image we cannot scale keeps only its original. */
        }
        String key;
        if (web.sha256().equals(version.sha256)) {
            key = version.storageKey;
        } else {
            key = "sha256-" + web.sha256() + web.extension();
            boolean existed = storage.exists(key);
            storage.storeKnown(key, web.filename(), web.contentType(), web.bytes());
            if (!existed) fireUploadRollbackCleanup(key);
        }
        version.webStorageKey = key;
        version.webContentType = web.contentType();
        version.webSizeBytes = (long) web.bytes().length;
        version.webWidthPx = web.width();
        version.webHeightPx = web.height();
    }

    /** Older images get their web copy the first time someone asks for it. */
    private void ensureWeb(MediaAssetEntity asset, MediaVersionEntity version) {
        if (asset.kind != MediaKind.IMAGE || version.webStorageKey != null) return;
        byte[] bytes;
        try (InputStream original = storage.read(version.storageKey)) {
            bytes = original.readAllBytes();
        } catch (Exception exception) {
            return;
        }
        applyWeb(version, new MediaUploadPolicy.ValidatedFile(
                version.originalFilename, version.contentType, MediaKind.IMAGE, bytes));
    }

    /** The web-size file of the current version; documents and unscalable images fall back to the original. */
    @Transactional
    public FileRef webFile(long id) {
        MediaAssetEntity asset = requiredAsset(id);
        MediaVersionEntity version = currentVersion(asset);
        ensureWeb(asset, version);
        return webRef(version);
    }

    private FileRef webRef(MediaVersionEntity version) {
        if (version.webStorageKey == null) {
            return new FileRef(storage.read(version.storageKey), version.contentType,
                    version.originalFilename, version.sizeBytes);
        }
        return new FileRef(storage.read(version.webStorageKey), version.webContentType,
                webFilename(version.originalFilename, version.webContentType), version.webSizeBytes);
    }

    private static String webFilename(String original, String contentType) {
        String base = original == null ? "bestand" : original.replaceAll("\\.[A-Za-z0-9]+$", "");
        String extension = "image/png".equals(contentType) ? ".png" : "image/jpeg".equals(contentType) ? ".jpg" : "";
        return base + "-web" + extension;
    }

    private void applyLegacyThumbnail(MediaVersionEntity version, LegacyFile source, byte[] bytes) {
        if (source.kind() != MediaKind.IMAGE) return;
        if (source.thumbnailStorageKey() != null && storage.exists(source.thumbnailStorageKey())) {
            version.thumbnailStorageKey = source.thumbnailStorageKey();
            version.thumbnailContentType = source.thumbnailContentType();
            version.thumbnailSizeBytes = source.thumbnailSizeBytes();
            version.thumbnailWidthPx = source.thumbnailWidthPx();
            version.thumbnailHeightPx = source.thumbnailHeightPx();
            return;
        }
        // Oversized legacy originals remain indexed and downloadable. Avoid decoding them into
        // another large heap buffer merely to populate the optional manager-grid rendition.
        if (bytes == null) return;
        PhotoRenditionService.Rendition thumbnail = renditions.small(
                new PhotoUploadPolicy.ValidatedPhoto(
                        version.originalFilename, version.contentType, bytes));
        String key = thumbnail.sha256().equals(version.sha256)
                ? version.storageKey : "sha256-" + thumbnail.sha256() + thumbnail.extension();
        if (!key.equals(version.storageKey)) {
            boolean existed = storage.exists(key);
            storage.storeKnown(key, thumbnail.filename(), thumbnail.contentType(), thumbnail.bytes());
            if (!existed) fireUploadRollbackCleanup(key);
        }
        version.thumbnailStorageKey = key;
        version.thumbnailContentType = thumbnail.contentType();
        version.thumbnailSizeBytes = (long) thumbnail.bytes().length;
        version.thumbnailWidthPx = thumbnail.width();
        version.thumbnailHeightPx = thumbnail.height();
    }

    private static String safeLegacyContentType(String contentType, MediaKind kind) {
        if (contentType != null && !contentType.isBlank()) return contentType.strip();
        return kind == MediaKind.IMAGE ? "image/jpeg" : "application/octet-stream";
    }

    private static MediaKind kindOf(MediaVersionEntity version) {
        return version.contentType != null
                && version.contentType.toLowerCase(Locale.ROOT).startsWith("image/")
                ? MediaKind.IMAGE : MediaKind.DOCUMENT;
    }

    private void fireDeleteCleanup(List<String> keys) {
        if (blobDeleteCleanup != null && keys != null && !keys.isEmpty()) {
            blobDeleteCleanup.fire(new MediaBlobCleanup.DeleteReady(keys));
        }
    }

    private void fireUploadRollbackCleanup(String storageKey) {
        if (blobUploadCleanup != null) {
            blobUploadCleanup.fire(new MediaBlobCleanup.UploadReady(storageKey));
        }
    }

    public record FileRef(InputStream data, String contentType,
                          String originalFilename, long sizeBytes) {}

    private record LegacyDigest(String sha256, long sizeBytes, byte[] thumbnailSource) {}

    private record TargetKey(MediaTargetType type, Long id) {}

    public record LegacyFile(
            MediaLegacySourceType sourceType,
            long sourceId,
            MediaTargetType targetType,
            long targetId,
            MediaRole role,
            String label,
            String originalFilename,
            String contentType,
            MediaKind kind,
            String storageKey,
            Integer widthPx,
            Integer heightPx,
            String thumbnailStorageKey,
            String thumbnailContentType,
            Long thumbnailSizeBytes,
            Integer thumbnailWidthPx,
            Integer thumbnailHeightPx,
            Instant addedAt,
            String createdBy,
            boolean primaryCandidate
    ) {}
}
