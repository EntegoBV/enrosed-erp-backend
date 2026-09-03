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
            String createdBy
    ) {}

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
            int versionCount
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
            List<Version> versions
    ) {}

    public record UploadResult(Detail asset, boolean reused) {}

    public record MetadataRequest(String name) {}

    public record LinkRequest(MediaTargetType targetType, Long targetId, MediaRole role) {}
}
