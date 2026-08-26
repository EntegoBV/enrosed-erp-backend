package be.enrosed.catalog.adapter.in.rest;

import be.enrosed.catalog.domain.HomepageSectionKey;

import java.time.Instant;
import java.util.List;

/** Typed website-builder wire contract; deliberately contains no HTML, CSS or free-form keys. */
public final class WebsiteBuilderDto {
    private WebsiteBuilderDto() {}

    public record SectionDto(HomepageSectionKey key, boolean enabled) {}

    public record LayoutDto(List<SectionDto> sections) {
        public LayoutDto {
            sections = sections == null ? null : List.copyOf(sections);
        }
    }

    public record AdminDto(
            long revision,
            LayoutDto draft,
            LayoutDto published,
            Instant updatedAt,
            Instant publishedAt
    ) {}

    public record UpdateDto(Long revision, List<SectionDto> sections) {
        public UpdateDto {
            sections = sections == null ? null : List.copyOf(sections);
        }
    }

    public record PublishDto(Long revision) {}

    public record PublicDto(long revision, HomepageDto homepage) {}

    public record HomepageDto(List<SectionDto> sections) {
        public HomepageDto {
            sections = List.copyOf(sections);
        }
    }
}
