package be.enrosed.catalog.application;

import be.enrosed.catalog.adapter.out.persistence.CanonicalCatalogDaos;
import be.enrosed.catalog.adapter.out.persistence.CatalogDaos;
import be.enrosed.catalog.adapter.out.persistence.CategoryEntity;
import be.enrosed.catalog.adapter.out.persistence.ProductCollectionEntity;
import be.enrosed.catalog.adapter.out.persistence.ProductFamilyCollectionEntity;
import be.enrosed.catalog.adapter.out.persistence.ProductFamilyEntity;
import be.enrosed.shared.BusinessRuleException;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Objects;
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Makes the selected operational category the canonical primary merchandising collection. */
@ApplicationScoped
public class FamilyCollectionAlignmentService {
    private final CanonicalCatalogDaos.Collections collections;
    private final CatalogDaos.Categories categories;

    public FamilyCollectionAlignmentService(
            CanonicalCatalogDaos.Collections collections,
            CatalogDaos.Categories categories) {
        this.collections = collections;
        this.categories = categories;
    }

    public record MembershipRequest(String key, int position, boolean primary) {}

    /**
     * Replaces membership only. Shared collection copy is owned by category/collection CRUD and
     * must never be overwritten from a potentially stale family-editor snapshot.
     */
    public void replaceMemberships(
            ProductFamilyEntity family, List<MembershipRequest> requested) {
        if (requested == null) return;
        Set<String> seen = new HashSet<>();
        Map<String, MembershipRequest> wanted = new HashMap<>();
        for (MembershipRequest input : requested) {
            if (input == null || input.key() == null || input.key().isBlank()) {
                throw new BusinessRuleException("Collectiecode is verplicht");
            }
            String key = CategoryPublicKey.from(input.key());
            if (!seen.add(key)) {
                throw new BusinessRuleException("Dubbele collectiecode " + key);
            }
            wanted.put(key, input);
        }
        String categoryKey = optional(family.categoryKey);
        family.collections.removeIf(existing -> {
            String key = existing.collection == null
                    ? null : optional(existing.collection.collectionKey);
            return key == null || !wanted.containsKey(key) && !Objects.equals(key, categoryKey);
        });

        int primaryCount = 0;
        for (Map.Entry<String, MembershipRequest> entry : wanted.entrySet()) {
            String key = entry.getKey();
            MembershipRequest input = entry.getValue();
            ProductCollectionEntity collection = collections.find(
                    "collectionKey", key).firstResult();
            if (collection == null) {
                throw new BusinessRuleException("Onbekende collectie " + key);
            }
            ProductCollectionEntity selected = collection;
            ProductFamilyCollectionEntity membership = family.collections.stream()
                    .filter(existing -> existing.collection != null
                            && (existing.collection == selected
                            || selected.id != null
                                && Objects.equals(existing.collection.id, selected.id)
                            || Objects.equals(
                                existing.collection.collectionKey, selected.collectionKey)))
                    .findFirst().orElse(null);
            if (membership == null) {
                membership = new ProductFamilyCollectionEntity();
                membership.family = family;
                membership.collection = collection;
                family.collections.add(membership);
            }
            membership.position = input.position();
            membership.primaryCollection = input.primary();
            if (membership.primaryCollection) primaryCount++;
        }
        if (categoryKey == null && primaryCount > 1) {
            throw new BusinessRuleException(
                    "Een productfamilie kan maar één primaire collectie hebben");
        }
        if (categoryKey == null && !family.collections.isEmpty() && primaryCount == 0) {
            family.collections.getFirst().primaryCollection = true;
        }
    }

    public void alignPrimary(ProductFamilyEntity family) {
        String key = optional(family.categoryKey);
        if (key == null) {
            long primaryCount = family.collections.stream().filter(item -> item.primaryCollection).count();
            if (primaryCount > 1) {
                throw new BusinessRuleException(
                        "Een productfamilie kan maar één primaire collectie hebben");
            }
            family.collectionKey = family.collections.stream()
                    .filter(item -> item.primaryCollection).findFirst()
                    .map(item -> item.collection.collectionKey).orElse(null);
            return;
        }
        ProductCollectionEntity primary = collections.find("collectionKey", key).firstResult();
        if (primary == null) {
            primary = new ProductCollectionEntity();
            primary.collectionKey = key;
            primary.name = family.categoryName;
            primary.position = family.categoryPosition;
            if (family.categoryId != null) {
                CategoryEntity category = categories.findById(family.categoryId);
                if (category != null) {
                    primary.eyebrow = category.eyebrow;
                    primary.description = category.description;
                    primary.mobileName = category.mobileName;
                    primary.featuredProductId = category.featuredProductId;
                }
            }
            collections.persist(primary);
        }
        ProductCollectionEntity selected = primary;
        ProductFamilyCollectionEntity membership = family.collections.stream()
                .filter(item -> item.collection != null
                        && (item.collection == selected
                        || selected.id != null && Objects.equals(item.collection.id, selected.id)
                        || Objects.equals(item.collection.collectionKey, selected.collectionKey)))
                .findFirst().orElse(null);
        if (membership == null) {
            membership = new ProductFamilyCollectionEntity();
            membership.family = family;
            membership.collection = selected;
            membership.position = selected.position;
            family.collections.add(membership);
        }
        ProductFamilyCollectionEntity primaryMembership = membership;
        family.collections.forEach(item -> item.primaryCollection = item == primaryMembership);
        family.collectionKey = selected.collectionKey;
    }

    private static String optional(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
