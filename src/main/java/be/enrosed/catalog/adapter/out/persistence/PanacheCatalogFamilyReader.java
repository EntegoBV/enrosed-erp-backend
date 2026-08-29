package be.enrosed.catalog.adapter.out.persistence;

import be.enrosed.catalog.application.FamilyPhotoPublicationPolicy;
import be.enrosed.catalog.application.port.out.CatalogFamilyReader;
import be.enrosed.catalog.domain.CatalogChannel;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Customer-safe catalogue projection; audit/provenance records never cross this port. */
@ApplicationScoped
public class PanacheCatalogFamilyReader implements CatalogFamilyReader {

    private final CanonicalCatalogDaos.Families families;
    private final CatalogDaos.Products products;
    private final FamilyPhotoPublicationPolicy photoPublication;
    private final ObjectMapper json;

    public PanacheCatalogFamilyReader(
            CanonicalCatalogDaos.Families families,
            CatalogDaos.Products products,
            FamilyPhotoPublicationPolicy photoPublication,
            ObjectMapper json) {
        this.families = families;
        this.products = products;
        this.photoPublication = photoPublication;
        this.json = json;
    }

    @Override
    public List<Family> findByIds(Set<Long> ids) {
        if (ids == null || ids.isEmpty()) return List.of();
        Map<Long, List<ProductEntity>> membersByFamily = products.list(
                        "familyId in ?1 order by familyId, variantPosition, id", ids)
                .stream()
                .collect(Collectors.groupingBy(item -> item.familyId,
                        LinkedHashMap::new, Collectors.toList()));
        return families.listAll().stream()
                .filter(item -> ids.contains(item.id))
                .sorted(Comparator.comparingInt((ProductFamilyEntity item) -> item.categoryPosition)
                        .thenComparingInt(item -> item.productPosition)
                        .thenComparing(item -> item.id))
                .map(item -> toFamily(item,
                        membersByFamily.getOrDefault(item.id, List.of())))
                .toList();
    }

    private Family toFamily(ProductFamilyEntity entity, List<ProductEntity> members) {
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
                .filter(item -> photoPublication.isPublic(
                        item, members, CatalogChannel.CATALOGUE))
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
