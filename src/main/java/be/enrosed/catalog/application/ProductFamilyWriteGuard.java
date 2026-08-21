package be.enrosed.catalog.application;

import be.enrosed.catalog.adapter.in.rest.ProductFamilyDto;
import be.enrosed.catalog.adapter.out.persistence.CanonicalCatalogDaos;
import be.enrosed.catalog.adapter.out.persistence.CatalogDaos;
import be.enrosed.catalog.adapter.out.persistence.ProductEntity;
import be.enrosed.catalog.adapter.out.persistence.ProductFamilyEntity;
import be.enrosed.catalog.domain.Product;
import be.enrosed.catalog.domain.PublicationState;
import be.enrosed.shared.BusinessRuleException;
import be.enrosed.shared.NotFoundException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
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
        return product.familyId;
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
    public void validateFamilies(Collection<Long> familyIds) {
        for (Long familyId : normalizedIds(familyIds)) {
            ProductFamilyEntity family = families.findById(familyId);
            if (family == null) continue;
            List<ProductEntity> members = productRows.list(
                    "familyId = ?1 order by variantPosition, id", familyId);
            List<String> issues = ProductFamilyDto.publicationIssues(family, members, json);
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
                                && issue.equals("Kleurstaal ontbreekt voor een actieve gekleurde variant"))
                        .toList()
                    : List.of();
            if (!blockers.isEmpty()) {
                throw new BusinessRuleException(
                        "Productwijziging maakt productfamilie " + family.familyKey
                                + " niet publiceerbaar: " + String.join("; ", blockers));
            }
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
