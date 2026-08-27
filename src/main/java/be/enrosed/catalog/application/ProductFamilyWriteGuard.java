package be.enrosed.catalog.application;

import be.enrosed.catalog.adapter.in.rest.ProductFamilyDto;
import be.enrosed.catalog.adapter.out.persistence.CanonicalCatalogDaos;
import be.enrosed.catalog.adapter.out.persistence.CatalogDaos;
import be.enrosed.catalog.adapter.out.persistence.ProductEntity;
import be.enrosed.catalog.adapter.out.persistence.ProductFamilyEntity;
import be.enrosed.catalog.domain.CatalogChannel;
import be.enrosed.catalog.domain.Product;
import be.enrosed.catalog.domain.PublicationState;
import be.enrosed.shared.BusinessRuleException;
import be.enrosed.shared.NotFoundException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.LockModeType;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/** Serializes family-member writes and rejects changes that would break a live family. */
@ApplicationScoped
public class ProductFamilyWriteGuard {
    private final CanonicalCatalogDaos.Families families;
    private final CatalogDaos.Products productRows;
    private final ObjectMapper json;

    @Inject
    PublicLocalizationCompletenessService localization;

    public ProductFamilyWriteGuard(
            CanonicalCatalogDaos.Families families,
            CatalogDaos.Products productRows,
            ObjectMapper json) {
        this.families = families;
        this.productRows = productRows;
        this.json = json;
    }

    /** Locks in stable id order so max+1 allocation cannot race and family moves cannot deadlock. */
    public void lockFamilies(Collection<Long> familyIds) {
        normalizedIds(familyIds).stream().sorted().forEach(id -> {
            ProductFamilyEntity family = families.findById(id, LockModeType.PESSIMISTIC_WRITE);
            if (family == null) throw new BusinessRuleException("Onbekende productfamilie " + id);
        });
    }

    /** Locks the stock-bearing row after its observed family locks were acquired. */
    public Long lockProduct(long productId) {
        ProductEntity product = productRows.findById(productId, LockModeType.PESSIMISTIC_WRITE);
        if (product == null) throw new NotFoundException("Product", productId);
        /* findById may return an entity loaded before we waited for its family lock. Refresh the
           entity and its CascadeType.ALL photo/text collections while holding the row lock so a
           later full save cannot resurrect or drop a concurrently rebuilt family projection. */
        productRows.getEntityManager().refresh(product, LockModeType.PESSIMISTIC_WRITE);
        return product.familyId;
    }

    /** Locks multiple stock-bearing rows in stable order for product-to-product commands. */
    public void lockProducts(Collection<Long> productIds) {
        normalizedIds(productIds).stream().sorted().forEach(this::lockProduct);
    }

    /**
     * The backend owns positions. Existing members retain their position unless legacy data already
     * collides; creates and moves append after every current active or inactive family member.
     */
    public Product assignPosition(Product candidate, Product current) {
        if (candidate.familyId() == null) return candidate;
        List<ProductEntity> members = productRows.list("familyId", candidate.familyId());
        boolean sameFamily = current != null
                && Objects.equals(current.familyId(), candidate.familyId());
        int wanted = sameFamily ? current.variantPosition() : nextPosition(members, candidate.id());
        boolean collision = members.stream()
                .filter(member -> !Objects.equals(member.id, candidate.id()))
                .anyMatch(member -> member.variantPosition == wanted);
        int position = wanted >= 0 && !collision
                ? wanted : nextPosition(members, candidate.id());
        return candidate.withCanonicalIdentity(
                candidate.familyId(), candidate.canonicalVariantKey(),
                candidate.canonicalBarcode(), position, candidate.inventoryKnown());
    }

    /** Validates after the ORM flush; the surrounding transaction rolls every mutation back. */
    /**
     * Incremental editor saves retain structural guards but may leave public copy on the
     * translation work queue. Publication is the only boundary that requires the complete
     * all-language snapshot.
     */
    public enum WriteKind { INCREMENTAL_EDIT, PUBLICATION }

    public void validateFamilies(Collection<Long> familyIds) {
        validateFamilies(familyIds, WriteKind.PUBLICATION);
    }

    public void validateFamilies(Collection<Long> familyIds, WriteKind kind) {
        for (Long familyId : normalizedIds(familyIds)) {
            ProductFamilyEntity family = families.findById(familyId);
            if (family == null) continue;
            List<ProductEntity> members = productRows.list(
                    "familyId = ?1 order by variantPosition, id", familyId);
            List<String> issues = ProductFamilyDto.publicationIssues(family, members, json);
            List<String> localized = localization == null ? List.of() : localization.issues(family, members);
            /* An editor works incrementally: a product, category or one language can be saved
               without having to complete every other language in the same request. Missing copy
               remains visible in ProductFamilyDto.publicationIssues and the strict public build.
               Structural family invariants remain guarded here. */
            if (kind == WriteKind.INCREMENTAL_EDIT) {
                issues = issues.stream()
                        .filter(issue -> !issue.startsWith("website.") && !issue.startsWith("catalog."))
                        .toList();
            } else if (!localized.isEmpty()) {
                List<String> combined = new java.util.ArrayList<>(issues);
                combined.addAll(localized);
                issues = List.copyOf(combined);
            }
            boolean published = state(family.websiteStatus) == PublicationState.PUBLISHED
                    || state(family.orderAppStatus) == PublicationState.PUBLISHED
                    || state(family.catalogueStatus) == PublicationState.PUBLISHED;
            boolean anyReady = state(family.websiteStatus) == PublicationState.READY
                    || state(family.orderAppStatus) == PublicationState.READY
                    || state(family.catalogueStatus) == PublicationState.READY;
            List<String> blockers = published ? issues : anyReady
                    ? issues.stream().filter(issue -> issue.equals(FamilyVariantRules.OPTION_ISSUE)
                            || issue.equals(FamilyVariantRules.POSITION_ISSUE)
                            || state(family.websiteStatus) == PublicationState.READY
                                && issue.startsWith("website.")
                            || state(family.catalogueStatus) == PublicationState.READY
                                && issue.startsWith("catalog.")
                            || state(family.websiteStatus) == PublicationState.READY
                                && issue.equals("Kleurstaal ontbreekt voor een actieve gekleurde variant"))
                        .toList()
                    : List.of();
            if (!blockers.isEmpty()) {
                throw new BusinessRuleException(
                        "Wijziging maakt productfamilie " + family.familyKey
                                + " niet publiceerbaar: " + String.join("; ", blockers));
            }
        }
    }

    /**
     * A deploy hook is useful only while the complete published WEBSITE graph can satisfy the
     * strict locale endpoint. Returning false merely leaves the saved change in the existing
     * publication-issues work queue; it never rolls the editor transaction back.
     */
    public boolean websiteBuildReady() {
        try {
            for (ProductFamilyEntity family : families.listAll()) {
                if (!family.active || state(family.websiteStatus) != PublicationState.PUBLISHED) {
                    continue;
                }
                List<ProductEntity> members = productRows.list(
                        "familyId = ?1 order by variantPosition, id", family.id);
                if (!ProductFamilyDto.publicationIssues(family, members, json).isEmpty()) {
                    return false;
                }
                if (localization != null
                        && !localization.missing(family, members, CatalogChannel.WEBSITE).isEmpty()) {
                    return false;
                }
            }
            return true;
        } catch (RuntimeException invalidPublishedGraph) {
            /* Corrupt/incomplete public data should suppress an automatic deploy, not the save. */
            return false;
        }
    }

    private static int nextPosition(List<ProductEntity> members, Long currentId) {
        return members.stream().filter(member -> !Objects.equals(member.id, currentId))
                .mapToInt(member -> member.variantPosition).max().orElse(-1) + 1;
    }

    private static LinkedHashSet<Long> normalizedIds(Collection<Long> familyIds) {
        LinkedHashSet<Long> result = new LinkedHashSet<>();
        if (familyIds != null) familyIds.stream().filter(Objects::nonNull).forEach(result::add);
        return result;
    }

    private static PublicationState state(PublicationState value) {
        return value == null ? PublicationState.DRAFT : value;
    }
}
