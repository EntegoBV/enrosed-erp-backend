package be.enrosed.catalog.application;

import be.enrosed.catalog.adapter.out.persistence.CatalogDaos;
import be.enrosed.catalog.adapter.out.persistence.ProductEntity;
import be.enrosed.catalog.adapter.out.persistence.ProductSupplierAgreementLinkEntity;
import be.enrosed.catalog.application.ProductSupplierAgreementPhotoService.AgreementPhoto;
import be.enrosed.catalog.domain.Product;
import be.enrosed.shared.BusinessRuleException;
import be.enrosed.shared.NotFoundException;
import be.enrosed.shared.audit.ActivityLogService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.transaction.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

/** One private agreement source, explicitly reused by selected variants of the same supplier/family. */
@ApplicationScoped
public class ProductSupplierAgreementService {
    private final CatalogDaos.Products products;
    private final ProductFamilyWriteGuard locks;
    private final ProductSupplierAgreementPhotoService photos;
    private final EntityManager entities;
    private final ObjectMapper json;
    private final ActivityLogService activity;

    public ProductSupplierAgreementService(CatalogDaos.Products products,
            ProductFamilyWriteGuard locks,
            ProductSupplierAgreementPhotoService photos, EntityManager entities,
            ObjectMapper json, ActivityLogService activity) {
        this.products = products; this.locks = locks;
        this.photos = photos; this.entities = entities; this.json = json; this.activity = activity;
    }

    public record Variant(long productId, String sku, String name, String color, boolean hasOwnAgreement) {
        public Variant(long productId, String sku, String name, String color) {
            this(productId, sku, name, color, false);
        }
    }
    public record ResolvedAgreement(String groupKey, long sourceProductId, Long supplierId,
            Long familyId, String note, List<AgreementPhoto> photos, List<Variant> variants,
            boolean available) {}
    public record Snapshot(long productId, long sourceProductId, Long supplierId, Long familyId,
            String groupKey, boolean inherited, boolean available, String revision, String note,
            List<AgreementPhoto> photos, List<Variant> variants, List<Variant> eligibleVariants) {}
    public record ApplicabilityRequest(String revision, List<Long> productIds) {}
    public record UnlinkRequest(String revision) {}

    @Transactional
    public ResolvedAgreement resolve(long productId) {
        ProductEntity target = product(productId);
        ProductSupplierAgreementLinkEntity link = link(productId);
        ProductEntity source = link == null ? target : products.findById(link.sourceProductId);
        boolean available = source != null && target.supplierId != null
                && Objects.equals(target.supplierId, source.supplierId)
                && (link == null || (Objects.equals(target.familyId, source.familyId)
                    && Objects.equals(target.familyId, link.familyId)
                    && Objects.equals(target.supplierId, link.supplierId)
                    && link(source.id) == null));
        long sourceId = link == null ? productId : link.sourceProductId;
        String key = "product:" + sourceId + ":supplier:" + target.supplierId;
        if (!available) return new ResolvedAgreement(key, sourceId, target.supplierId,
                target.familyId, null, List.of(), List.of(), false);
        List<ProductEntity> members = members(source);
        List<Variant> variants = members.stream()
                .filter(row -> Objects.equals(row.id, source.id) || applies(row, source))
                .map(this::variant).toList();
        return new ResolvedAgreement(key, source.id, source.supplierId, source.familyId,
                source.supplierNote, photos.list(source.id), variants, true);
    }

    @Transactional
    public Snapshot get(long productId) {
        ProductEntity target = product(productId);
        ResolvedAgreement value = resolve(productId);
        return new Snapshot(productId, value.sourceProductId(), value.supplierId(), value.familyId(),
                value.groupKey(), value.sourceProductId() != productId, value.available(),
                revision(target), value.note(), value.photos(), value.variants(),
                members(target).stream().filter(row -> row.active || Objects.equals(row.id, target.id)
                        || applies(row, products.findById(value.sourceProductId())))
                        .map(this::variant).toList());
    }

    /** Complete explicit set, including the source itself; absence never means "all future colours". */
    @Transactional
    public Snapshot updateApplicability(long sourceId, ApplicabilityRequest request) {
        ProductEntity source = lockScope(sourceId);
        requireRevision(source, request == null ? null : request.revision());
        if (source.familyId == null || source.supplierId == null) {
            throw new BusinessRuleException("Koppel eerst een familie en leverancier om afspraken te delen");
        }
        if (link(sourceId) != null) {
            throw new BusinessRuleException("Beheer de kleuren bij het bronproduct van deze gedeelde afspraak");
        }
        List<Long> ids = request == null ? null : request.productIds();
        if (ids == null || ids.stream().anyMatch(Objects::isNull)
                || new HashSet<>(ids).size() != ids.size() || !ids.contains(sourceId)) {
            throw new BusinessRuleException("Selecteer de bronkleur en elke toepasselijke variant exact één keer");
        }
        Map<Long, ProductEntity> eligible = new HashMap<>();
        members(source).forEach(row -> eligible.put(row.id, row));
        for (Long id : ids) {
            ProductEntity target = eligible.get(id);
            if (target == null) throw new BusinessRuleException(
                    "Afspraken kunnen alleen binnen dezelfde productfamilie en leverancier worden gedeeld");
            if (id == sourceId) continue;
            ProductSupplierAgreementLinkEntity current = link(id);
            if (current != null && current.sourceProductId != sourceId) {
                throw new BusinessRuleException("Een geselecteerde kleur gebruikt al een andere afspraak; koppel die eerst los");
            }
            if (!sourceLinks(id).isEmpty()) throw new BusinessRuleException(
                    "Een geselecteerde kleur is zelf bron van gedeelde afspraken; koppel die eerst los");
        }
        List<ProductSupplierAgreementLinkEntity> current = sourceLinks(sourceId);
        Set<Long> wanted = new HashSet<>(ids); wanted.remove(sourceId);
        if (current.stream().map(row -> row.productId).collect(java.util.stream.Collectors.toSet()).equals(wanted)) {
            return get(sourceId);
        }
        for (ProductSupplierAgreementLinkEntity row : current) {
            if (!wanted.contains(row.productId)) entities.remove(row);
        }
        for (Long id : wanted) {
            if (link(id) != null) continue;
            ProductSupplierAgreementLinkEntity row = new ProductSupplierAgreementLinkEntity();
            row.productId = id; row.sourceProductId = sourceId;
            row.supplierId = source.supplierId; row.familyId = source.familyId; row.updatedAt = Instant.now();
            entities.persist(row);
        }
        entities.flush();
        record(source, "Toepasselijke kleuren van leveranciersafspraak bijgewerkt");
        return get(sourceId);
    }

    /** Restores the target's own untouched note/photos instead of copying the shared material. */
    @Transactional
    public Snapshot unlink(long productId, UnlinkRequest request) {
        ProductEntity target = lockScope(productId);
        requireRevision(target, request == null ? null : request.revision());
        ProductSupplierAgreementLinkEntity current = link(productId);
        if (current != null) {
            entities.remove(current); entities.flush();
            record(target, "Kleur losgekoppeld van gedeelde leveranciersafspraak");
        }
        return get(productId);
    }

    /** Called with the normal family/product locks held, before the product is saved. */
    @Transactional(Transactional.TxType.MANDATORY)
    public void beforeProductChange(Product before, Product after) {
        if (Objects.equals(before.familyId(), after.familyId())
                && Objects.equals(before.supplierId(), after.supplierId())) return;
        requireNotSource(before.id());
        ProductSupplierAgreementLinkEntity own = link(before.id());
        if (own != null) entities.remove(own);
    }

    @Transactional(Transactional.TxType.MANDATORY)
    public void beforeProductDelete(long productId) {
        requireNotSource(productId);
        ProductSupplierAgreementLinkEntity own = link(productId);
        if (own != null) { entities.remove(own); entities.flush(); }
    }

    private void requireNotSource(long productId) {
        if (!sourceLinks(productId).isEmpty()) throw new BusinessRuleException(
                "Dit product is bron van gedeelde leveranciersafspraken. Koppel eerst de andere kleuren los");
    }

    private ProductEntity lockScope(long id) {
        ProductEntity observed = product(id);
        Long familyId = observed.familyId;
        if (familyId != null) locks.lockFamilies(List.of(familyId));
        Set<Long> scopeIds = new HashSet<>();
        scopeIds.add(id); // It may have left the observed family while we waited for its lock.
        if (familyId != null) products.list("familyId", familyId).forEach(row -> scopeIds.add(row.id));
        List<Long> ids = scopeIds.stream().sorted().toList();
        locks.lockProducts(ids);
        ProductEntity current = product(id);
        if (!Objects.equals(familyId, current.familyId)) throw new BusinessRuleException(
                "De productfamilie is gewijzigd; laad de afspraken opnieuw");
        // A prior GET may have cached sidecars before we waited. These command-entry rows
        // have no pending writes; discard only their cached copies and read current membership.
        for (Long productId : ids) {
            ProductSupplierAgreementLinkEntity row = link(productId);
            if (row != null) entities.detach(row);
        }
        for (var row : entities.createQuery(
                "from ProductSupplierAgreementPhotoEntity p where p.productId in :ids",
                be.enrosed.catalog.adapter.out.persistence.ProductSupplierAgreementPhotoEntity.class)
                .setParameter("ids", ids).getResultList()) entities.refresh(row);
        return current;
    }

    private ProductEntity product(long id) {
        ProductEntity value = products.findById(id);
        if (value == null) throw new NotFoundException("Product", id);
        return value;
    }
    private ProductSupplierAgreementLinkEntity link(long id) {
        return entities.find(ProductSupplierAgreementLinkEntity.class, id);
    }
    private List<ProductSupplierAgreementLinkEntity> sourceLinks(long id) {
        return entities.createQuery("from ProductSupplierAgreementLinkEntity l where l.sourceProductId=:id order by l.productId",
                ProductSupplierAgreementLinkEntity.class).setParameter("id", id).getResultList();
    }
    private List<ProductEntity> members(ProductEntity source) {
        if (source.familyId == null || source.supplierId == null) return List.of(source);
        return products.list("familyId = ?1 and supplierId = ?2 order by variantPosition, id",
                source.familyId, source.supplierId);
    }
    private boolean applies(ProductEntity row, ProductEntity source) {
        if (source == null || !Objects.equals(row.supplierId, source.supplierId)
                || !Objects.equals(row.familyId, source.familyId)) return false;
        ProductSupplierAgreementLinkEntity link = link(row.id);
        return link != null && link.sourceProductId == source.id
                && Objects.equals(row.supplierId, link.supplierId)
                && Objects.equals(row.familyId, link.familyId);
    }
    private Variant variant(ProductEntity row) {
        return new Variant(row.id, row.sku, row.name, row.colour,
                row.supplierNote != null && !row.supplierNote.isBlank() || !photos.list(row.id).isEmpty());
    }
    private String revision(ProductEntity target) {
        List<Object> state = new ArrayList<>();
        state.add(target.id); state.add(target.familyId); state.add(target.supplierId);
        for (ProductEntity member : members(target)) {
            ProductSupplierAgreementLinkEntity link = link(member.id);
            state.add(Arrays.asList(member.id, member.supplierId, member.familyId, member.active,
                    member.sku, member.name, member.colour, member.supplierNote,
                    link == null ? null : Arrays.asList(link.sourceProductId, link.supplierId, link.familyId),
                    photos.list(member.id)));
        }
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(json.writeValueAsBytes(state))); }
        catch (Exception e) { throw new IllegalStateException("Kan afsprakenrevisie niet berekenen", e); }
    }
    private void requireRevision(ProductEntity target, String expected) {
        if (expected == null || !MessageDigest.isEqual(revision(target).getBytes(StandardCharsets.US_ASCII),
                expected.getBytes(StandardCharsets.US_ASCII))) throw new BusinessRuleException(
                "De leveranciersafspraak of kleuren zijn intussen gewijzigd; laad opnieuw voor je bewaart");
    }
    private void record(ProductEntity source, String message) {
        activity.record(ActivityLogService.ACTION_UPDATED, "PRODUCT", source.id.toString(), source.sku, message);
    }
}
