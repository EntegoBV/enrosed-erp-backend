package be.enrosed.catalog.adapter.out.persistence;

import be.enrosed.catalog.application.port.out.CatalogFamilyReader;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/** Customer-safe catalogue projection; audit/provenance records never cross this port. */
@ApplicationScoped
public class PanacheCatalogFamilyReader implements CatalogFamilyReader {

    private final CanonicalCatalogDaos.Families families;
    private final ObjectMapper json;

    public PanacheCatalogFamilyReader(CanonicalCatalogDaos.Families families, ObjectMapper json) {
        this.families = families;
        this.json = json;
    }

    @Override
    public List<Family> findByIds(Set<Long> ids) {
        if (ids == null || ids.isEmpty()) return List.of();
        return families.listAll().stream()
                .filter(item -> ids.contains(item.id))
                .sorted(Comparator.comparingInt((ProductFamilyEntity item) -> item.categoryPosition)
                        .thenComparingInt(item -> item.productPosition)
                        .thenComparing(item -> item.id))
                .map(this::toFamily)
                .toList();
    }

    private Family toFamily(ProductFamilyEntity entity) {
        List<Text> texts = entity.texts.stream().map(item -> new Text(
                item.language, item.name, item.summary, item.description, item.format,
                strings(item.highlightsJson))).toList();
        List<PackageInfo> packages = entity.packages.stream()
                .sorted(Comparator.comparingInt(item -> item.position))
                .map(item -> new PackageInfo(
                        item.productId, item.packageType, item.position,
                        item.lengthValue, item.widthValue, item.heightValue,
                        item.dimensionUnit, item.piecesPerPackage,
                        item.weightValue, item.weightUnit, item.operational))
                .toList();
        List<GalleryPhoto> photos = entity.photos.stream()
                .sorted(Comparator.comparingInt(item -> item.position))
                .map(item -> new GalleryPhoto(
                        item.id, item.largeStorageKey, item.largeContentType, item.position,
                        item.variantProduct == null ? null : item.variantProduct.id))
                .toList();
        return new Family(
                entity.id, entity.familyKey, entity.publicHandle, entity.categoryId,
                entity.categoryKey, entity.categoryName, entity.categoryPosition,
                entity.productPosition, entity.name, entity.summary, entity.description,
                entity.format, strings(entity.highlightsJson),
                new Dimensions(entity.dimensionLength, entity.dimensionWidth,
                        entity.dimensionHeight, entity.dimensionUnit),
                texts, packages, photos);
    }

    private List<String> strings(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        try {
            JsonNode root = json.readTree(raw);
            if (!root.isArray()) return List.of();
            List<String> result = new ArrayList<>();
            for (JsonNode item : root) {
                String value = item.asText(null);
                if (value != null && !value.isBlank()) result.add(value);
            }
            return List.copyOf(result);
        } catch (Exception ignored) {
            return List.of();
        }
    }

}
