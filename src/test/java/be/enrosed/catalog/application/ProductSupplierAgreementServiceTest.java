package be.enrosed.catalog.application;

import be.enrosed.catalog.adapter.out.persistence.*;
import be.enrosed.catalog.domain.Product;
import be.enrosed.shared.BusinessRuleException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import java.io.ByteArrayInputStream;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class ProductSupplierAgreementServiceTest {
    @Inject EntityManager em;
    @Inject ProductSupplierAgreementService agreements;
    @Inject ProductSupplierAgreementPhotoService photos;
    @Inject ProductService products;
    @Inject ObjectMapper json;
    private static final byte[] PNG = {(byte) 0x89,0x50,0x4e,0x47,0x0d,0x0a,0x1a,0x0a,1,0,0,0};

    @Test @TestTransaction
    void linksExplicitColoursWithoutCopyingPrivateEvidenceAndUnlinkRestoresLocalContent() throws Exception {
        long family = family("agreement-colours");
        var red = product("AGR-RED",family,101L,"Approved red source");
        var blue = product("AGR-BLUE",family,101L,"Older blue instruction");
        var future = product("AGR-GREEN",family,101L,"Independent green instruction");
        var sourcePhoto = photos.upload(red.id,"source.png",new ByteArrayInputStream(PNG),"Approved sample");
        var localPhoto = photos.upload(blue.id,"local.png",new ByteArrayInputStream(PNG),"Old blue sample");
        var before = agreements.get(red.id);
        assertTrue(before.eligibleVariants().stream().filter(v -> v.productId()==blue.id).findFirst().orElseThrow().hasOwnAgreement());
        agreements.updateApplicability(red.id,new ProductSupplierAgreementService.ApplicabilityRequest(before.revision(),List.of(red.id,blue.id)));
        em.flush(); em.clear();
        var shared = agreements.get(blue.id);
        assertTrue(shared.available()); assertTrue(shared.inherited());
        assertEquals("Approved red source",shared.note());
        assertEquals(List.of(sourcePhoto.id()),shared.photos().stream().map(ProductSupplierAgreementPhotoService.AgreementPhoto::id).toList());
        assertEquals(red.id,shared.photos().getFirst().productId());
        assertArrayEquals(PNG,photos.open(shared.sourceProductId(),sourcePhoto.id()).data().readAllBytes());
        assertEquals(agreements.resolve(red.id).groupKey(),shared.groupKey());
        assertNotEquals(shared.groupKey(),agreements.resolve(future.id).groupKey());
        assertEquals("Older blue instruction",em.find(ProductEntity.class,blue.id).supplierNote);
        assertNotNull(em.find(ProductSupplierAgreementPhotoEntity.class,localPhoto.id()));
        var restored = agreements.unlink(blue.id,new ProductSupplierAgreementService.UnlinkRequest(shared.revision()));
        assertFalse(restored.inherited()); assertEquals("Older blue instruction",restored.note());
        assertEquals(List.of(localPhoto.id()),restored.photos().stream().map(ProductSupplierAgreementPhotoService.AgreementPhoto::id).toList());
        assertEquals("Approved red source",agreements.resolve(red.id).note());
    }

    @Test @TestTransaction
    void rejectsCrossSupplierCrossFamilyAndChainedSourcesWithoutOverwritingLocalNotes() {
        long family=family("agreement-scope");
        var source=product("AGR-SOURCE",family,101L,"Source");
        var sibling=product("AGR-SIBLING",family,101L,"Sibling");
        var otherSupplier=product("AGR-OTHER-SUPPLIER",family,202L,"Private other supplier");
        var otherFamily=product("AGR-OTHER-FAMILY",family("agreement-other"),101L,"Other family");
        var request=agreements.get(source.id);
        assertThrows(BusinessRuleException.class,()->agreements.updateApplicability(source.id,
                new ProductSupplierAgreementService.ApplicabilityRequest(request.revision(),List.of(source.id,otherSupplier.id))));
        assertThrows(BusinessRuleException.class,()->agreements.updateApplicability(source.id,
                new ProductSupplierAgreementService.ApplicabilityRequest(request.revision(),List.of(source.id,otherFamily.id))));
        link(source,sibling);
        assertThrows(BusinessRuleException.class,()->agreements.updateApplicability(sibling.id,
                new ProductSupplierAgreementService.ApplicabilityRequest(agreements.get(sibling.id).revision(),List.of(sibling.id,source.id))));
        assertEquals("Private other supplier",agreements.resolve(otherSupplier.id).note());
        assertEquals("Other family",agreements.resolve(otherFamily.id).note());
    }

    @Test @TestTransaction
    void staleRevisionRejectsChangedSourceTextPhotoAndGroup() {
        long family=family("agreement-revision");
        var source=product("AGR-REV-SOURCE",family,101L,"Source");
        var sibling=product("AGR-REV-SIBLING",family,101L,"Sibling");
        var original=agreements.get(source.id);
        source.supplierNote="New saved instructions"; em.flush();
        assertThrows(BusinessRuleException.class,()->agreements.updateApplicability(source.id,
                new ProductSupplierAgreementService.ApplicabilityRequest(original.revision(),List.of(source.id,sibling.id))));
        var withText=agreements.get(source.id);
        photos.upload(source.id,"sample.png",new ByteArrayInputStream(PNG),"New reference");
        assertThrows(BusinessRuleException.class,()->agreements.updateApplicability(source.id,
                new ProductSupplierAgreementService.ApplicabilityRequest(withText.revision(),List.of(source.id,sibling.id))));
        var withPhoto=agreements.get(source.id);
        link(source,sibling);
        assertThrows(BusinessRuleException.class,()->agreements.updateApplicability(source.id,
                new ProductSupplierAgreementService.ApplicabilityRequest(withPhoto.revision(),List.of(source.id))));
        assertEquals(source.id,agreements.resolve(sibling.id).sourceProductId());
    }

    @Test @TestTransaction
    void sourceScopeAndDeletionAreProtectedWhileTargetsCanLeaveAndInactiveMembersCanBeRemoved() {
        long family=family("agreement-leaving");
        var source=product("AGR-LEAVE-SOURCE",family,101L,"Source");
        var sibling=product("AGR-LEAVE-SIBLING",family,101L,"Keep local");
        link(source,sibling);
        Product sourceDomain=products.get(source.id);
        Product targetDomain=products.get(sibling.id);
        assertThrows(BusinessRuleException.class,()->agreements.beforeProductChange(sourceDomain,withSupplier(sourceDomain,202L)));
        assertThrows(BusinessRuleException.class,()->agreements.beforeProductChange(sourceDomain,
                sourceDomain.withCanonicalIdentity(null,null,null,0,false)));
        assertThrows(BusinessRuleException.class,()->agreements.beforeProductDelete(source.id));
        agreements.beforeProductChange(targetDomain,withSupplier(targetDomain,202L));
        sibling.supplierId=202L; em.flush();
        assertFalse(agreements.get(sibling.id).inherited());
        assertEquals("Keep local",sibling.supplierNote);
        sibling.supplierId=101L; em.flush(); link(source,sibling);
        sibling.active=false; em.flush();
        var current=agreements.get(source.id);
        assertTrue(current.eligibleVariants().stream().anyMatch(v->v.productId()==sibling.id));
        agreements.updateApplicability(source.id,new ProductSupplierAgreementService.ApplicabilityRequest(current.revision(),List.of(source.id)));
        assertFalse(agreements.get(sibling.id).inherited());
        link(source,sibling);
        agreements.beforeProductChange(products.get(sibling.id),products.get(sibling.id).withCanonicalIdentity(null,null,null,0,false));
        sibling.familyId=null; em.flush();
        assertFalse(agreements.get(sibling.id).inherited());
        agreements.beforeProductDelete(source.id);
    }

    @Test @TestTransaction
    void staleInvalidScopeNeverExposesAnotherSuppliersInstructionsOrPhotos() {
        long family=family("agreement-legacy");
        var source=product("AGR-LEGACY-SOURCE",family,101L,"Private supplier 101");
        var sibling=product("AGR-LEGACY-TARGET",family,101L,"Local");
        photos.upload(source.id,"private.png",new ByteArrayInputStream(PNG),"Private");
        link(source,sibling);
        source.supplierId=202L; em.flush(); em.clear();
        var hidden=agreements.get(sibling.id);
        assertTrue(hidden.inherited()); assertFalse(hidden.available());
        assertNull(hidden.note()); assertTrue(hidden.photos().isEmpty());
        var local=agreements.unlink(sibling.id,new ProductSupplierAgreementService.UnlinkRequest(hidden.revision()));
        assertEquals("Local",local.note());
    }

    @Test @TestTransaction
    void sameTextWithoutExplicitApplicabilityDoesNotCreateASharedGroup() {
        long family=family("agreement-identical");
        var red=product("AGR-IDENTICAL-RED",family,101L,"Identical instructions");
        var blue=product("AGR-IDENTICAL-BLUE",family,101L,"Identical instructions");
        assertNotEquals(agreements.resolve(red.id).groupKey(),agreements.resolve(blue.id).groupKey());
        assertEquals(1,agreements.resolve(red.id).variants().size());
    }

    @Test @TestTransaction
    void aProductThatLeavesTheObservedFamilyIsStillLockedAndRefreshedBeforeUnlink() {
        long family=family("agreement-race-old");
        long newFamily=family("agreement-race-new");
        var source=product("AGR-RACE-SOURCE",family,101L,"Source");
        var target=product("AGR-RACE-TARGET",family,101L,"Target local");
        link(source,target);
        var before=agreements.get(target.id);
        // Simulate a committed move after the caller cached the target but before scope locks.
        em.createQuery("update ProductEntity set familyId=:family where id=:id")
                .setParameter("family",newFamily).setParameter("id",target.id).executeUpdate();
        assertEquals(family,target.familyId);
        var error=assertThrows(BusinessRuleException.class,()->agreements.unlink(target.id,
                new ProductSupplierAgreementService.UnlinkRequest(before.revision())));
        assertTrue(error.getMessage().contains("productfamilie is gewijzigd"));
        assertNotNull(em.find(ProductSupplierAgreementLinkEntity.class,target.id),
                "a stale command must not remove a link outside its acquired family scope");
    }

    private void link(ProductEntity source,ProductEntity target) {
        agreements.updateApplicability(source.id,new ProductSupplierAgreementService.ApplicabilityRequest(
                agreements.get(source.id).revision(),List.of(source.id,target.id)));
    }
    private long family(String key) {
        var row=new ProductFamilyEntity(); row.familyKey=key; row.name=key; em.persist(row); em.flush(); return row.id;
    }
    private ProductEntity product(String sku,long family,Long supplier,String note) {
        var row=new ProductEntity(); row.sku=sku; row.name=sku; row.colour=sku; row.active=true;
        row.piecesPerCarton=1; row.familyId=family; row.supplierId=supplier; row.supplierNote=note;
        em.persist(row); em.flush(); return row;
    }
    private Product withSupplier(Product value,long supplier) {
        ObjectNode tree=json.valueToTree(value); tree.put("supplierId",supplier); return json.convertValue(tree,Product.class);
    }
}
