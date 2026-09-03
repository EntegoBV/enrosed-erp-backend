package be.enrosed.media;

import java.time.Instant;
import java.util.List;

public final class MediaDtos {
    private MediaDtos() {}

    public record Link(
            Long id,
            MediaTargetType targetType,
            Long targetId,
            String targetLabel,
            MediaRole role,
            boolean primary,
            Long pinnedVersionId,
            Instant createdAt,
            String createdBy
    ) {}

    public record Version(
            Long id,
            int versionNumber,
            String originalFilename,
            String contentType,
            long sizeBytes,
            String sha256,
            Integer widthPx,
            Integer heightPx,
            Instant createdAt,
            String createdBy,
            Rendition web
    ) {}

    /** A derived, lighter file of an image version. */
    public record Rendition(long sizeBytes, Integer widthPx, Integer heightPx) {}

    public record Summary(
            Long id,
            String name,
            String originalFilename,
            String contentType,
            long sizeBytes,
            String sha256,
            MediaKind kind,
            Integer widthPx,
            Integer heightPx,
            boolean archived,
            Instant createdAt,
            Instant updatedAt,
            Long currentVersionId,
            List<MediaRole> roles,
            List<Link> links,
            int versionCount,
            Long folderId,
            Share share,
            Rendition web
    ) {}

    public record Detail(
            Long id,
            String name,
            String originalFilename,
            String contentType,
            long sizeBytes,
            String sha256,
            MediaKind kind,
            Integer widthPx,
            Integer heightPx,
            boolean archived,
            Instant createdAt,
            Instant updatedAt,
            Long currentVersionId,
            List<MediaRole> roles,
            List<Link> links,
            int versionCount,
            List<Version> versions,
            Long folderId,
            Share share,
            Rendition web
    ) {}

    public record UploadResult(Detail asset, boolean reused) {}

    public record MetadataRequest(String name) {}

    /** A folder of the library tree with how many assets sit directly in it. */
    public record Folder(Long id, String name, Long parentId, long assetCount) {}

    public record FolderRequest(String name, Long parentId) {}

    /** Where an asset moves to; null is the root. */
    public record MoveRequest(Long folderId) {}

    /** The live public link of an asset; the URL is built by the caller from the token. */
    public record Share(String token, Instant createdAt, String createdBy, long downloads) {}

    public record LinkRequest(MediaTargetType targetType, Long targetId, MediaRole role) {}
}
