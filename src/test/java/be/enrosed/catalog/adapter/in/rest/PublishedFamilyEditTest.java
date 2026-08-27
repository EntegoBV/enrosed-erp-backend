package be.enrosed.catalog.adapter.in.rest;

import be.enrosed.catalog.adapter.out.persistence.ProductFamilyEntity;
import be.enrosed.catalog.domain.PublicationState;
import be.enrosed.shared.BusinessRuleException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@io.quarkus.test.security.TestSecurity(user = "emre",
        roles = be.enrosed.shared.security.AdminIdentityProvider.ADMIN_ROLE)
class PublishedFamilyEditTest {
    @Inject ProductFamilyResource resource;
    @Inject EntityManager entityManager;
    @Inject ObjectMapper json;

    /** Renaming a live series is an edit, not a publication: missing copy stays a warning. */
    @Test
    @TestTransaction
    void aLiveFamilyCanBeRenamedDespiteMissingCopy() throws Exception {
        ProductFamilyEntity family = new ProductFamilyEntity();
        family.familyKey = "fam-live-rename";
        family.name = "Rose in Dome";
        family.websiteStatus = PublicationState.PUBLISHED;
        entityManager.persist(family);
        entityManager.flush();

        ProductFamilyDto dto = resource.get(family.id);
        assertFalse(dto.publicationIssues().isEmpty(), "the fixture family misses about everything");

        ObjectNode body = json.valueToTree(dto);
        body.put("name", "Rose in Dome XL");
        ProductFamilyDto renamed = resource.update(family.id,
                json.treeToValue(body, ProductFamilyDto.class));
        assertEquals("Rose in Dome XL", renamed.name());
    }

    /** Switching a channel on is the moment the completeness gate still closes. */
    @Test
    @TestTransaction
    void publishingAfreshStillDemandsCompleteness() throws Exception {
        ProductFamilyEntity family = new ProductFamilyEntity();
        family.familyKey = "fam-draft-publish";
        family.name = "Rose in Dome";
        entityManager.persist(family);
        entityManager.flush();

        ObjectNode body = json.valueToTree(resource.get(family.id));
        body.put("websiteStatus", "PUBLISHED");
        BusinessRuleException blocked = assertThrows(BusinessRuleException.class,
                () -> resource.update(family.id, json.treeToValue(body, ProductFamilyDto.class)));
        assertTrue(blocked.getMessage().startsWith("Productfamilie kan nog niet gepubliceerd worden"),
                blocked.getMessage());
    }

    @Test
    @TestTransaction
    void quickWebsiteSwitchHidesOnlyTheWebsiteChannel() {
        ProductFamilyEntity family = new ProductFamilyEntity();
        family.familyKey = "fam-quick-hide";
        family.name = "Rose display";
        family.websiteStatus = PublicationState.PUBLISHED;
        family.orderAppStatus = PublicationState.PUBLISHED;
        family.catalogueStatus = PublicationState.PUBLISHED;
        entityManager.persist(family);
        entityManager.flush();

        ProductFamilyResource.WebsiteVisibilityResult result = resource.setWebsiteVisibility(
                family.id, new ProductFamilyResource.WebsiteVisibilityRequest(false));
        ProductFamilyDto hidden = result.family();

        assertEquals(PublicationState.DRAFT, hidden.websiteStatus());
        assertEquals(PublicationState.PUBLISHED, hidden.orderAppStatus());
        assertEquals(PublicationState.PUBLISHED, hidden.catalogueStatus());
        assertEquals("Rose display", hidden.name());
    }

    @Test
    @TestTransaction
    void quickWebsiteSwitchReportsWhenAnotherPublishedFamilyBlocksTheRebuild() {
        ProductFamilyEntity target = new ProductFamilyEntity();
        target.familyKey = "fam-hide-with-blocker";
        target.name = "Family to hide";
        target.websiteStatus = PublicationState.PUBLISHED;
        entityManager.persist(target);

        ProductFamilyEntity blocker = new ProductFamilyEntity();
        blocker.familyKey = "fam-incomplete-published-blocker";
        blocker.name = "Incomplete published family";
        blocker.websiteStatus = PublicationState.PUBLISHED;
        entityManager.persist(blocker);
        entityManager.flush();

        ProductFamilyResource.WebsiteVisibilityResult result = resource.setWebsiteVisibility(
                target.id, new ProductFamilyResource.WebsiteVisibilityRequest(false));

        assertEquals(PublicationState.DRAFT, result.family().websiteStatus());
        assertTrue(!result.rebuildQueued());
        assertTrue(result.notice().contains("openstaande publicatiepunten"));
    }

    @Test
    @TestTransaction
    void quickWebsiteSwitchStillBlocksAnIncompleteDraft() {
        ProductFamilyEntity family = new ProductFamilyEntity();
        family.familyKey = "fam-quick-show";
        family.name = "Incomplete rose display";
        entityManager.persist(family);
        entityManager.flush();

        BusinessRuleException blocked = assertThrows(BusinessRuleException.class,
                () -> resource.setWebsiteVisibility(
                        family.id, new ProductFamilyResource.WebsiteVisibilityRequest(true)));

        assertTrue(blocked.getMessage().startsWith("Productfamilie kan nog niet gepubliceerd worden"),
                blocked.getMessage());
        assertEquals(PublicationState.DRAFT, entityManager.find(
                ProductFamilyEntity.class, family.id).websiteStatus);
    }
}
