package be.enrosed.catalog.adapter.out.persistence;

import be.enrosed.catalog.application.PublicFamilyPhotoProjection;
import be.enrosed.catalog.application.SharedProductDimensions;
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
    private final PublicFamilyPhotoProjection publicPhotos;
    private final ObjectMapper json;

    public PanacheCatalogFamilyReader(
            CanonicalCatalogDaos.Families families,
            CatalogDaos.Products products,
            PublicFamilyPhotoProjection publicPhotos,
            ObjectMapper json) {
        this.families = families;
        this.products = products;
        this.publicPhotos = publicPhotos;
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
        var size = SharedProductDimensions.resolve(members);
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
        List<GalleryPhoto> photos = publicPhotos.images(entity, members, CatalogChannel.CATALOGUE).stream()
                .map(item -> new GalleryPhoto(
                        item.id, item.largeStorageKey, item.largeContentType, item.position,
                        item.variantProduct == null ? null : item.variantProduct.id))
                .toList();
        return new Family(
                entity.id, entity.familyKey, entity.publicHandle, entity.categoryId,
                entity.categoryKey, entity.categoryName, entity.categoryPosition,
                entity.productPosition, entity.name, entity.summary, entity.description,
                entity.format, strings(entity.highlightsJson),
                size == null ? null : new Dimensions(
                        size.lengthCm(), size.widthCm(), size.heightCm(), "cm"),
                texts, packages, photos, selectedPhoto(entity, members, entity.catalogueOverviewPhotoId),
                selectedPhoto(entity, members, entity.catalogueDetailPhotoId), entity.catalogueDetailSize);
    }

    private GalleryPhoto selectedPhoto(ProductFamilyEntity family, List<ProductEntity> members, Long id) {
        if (id == null) return null;
        return be.enrosed.catalog.application.CataloguePhotoChoices.available(family, members, json)
                .stream().filter(choice -> id.equals(choice.id()))
                .map(choice -> new GalleryPhoto(choice.id(), choice.storageKey(), choice.contentType(),
                        -1, choice.productId())).findFirst().orElse(null);
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
