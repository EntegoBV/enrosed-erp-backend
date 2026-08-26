package be.enrosed.catalog.adapter.in.rest;

import be.enrosed.catalog.application.WebsiteBuilderService;
import be.enrosed.catalog.domain.HomepageSectionKey;
import be.enrosed.shared.security.AdminIdentityProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.security.PermitAll;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WebsiteBuilderResourceTest {
    @Test
    void endpointPathsAndSecurityAreExplicit() {
        Path adminPath = WebsiteBuilderResource.class.getAnnotation(Path.class);
        RolesAllowed adminRoles = WebsiteBuilderResource.class.getAnnotation(RolesAllowed.class);
        Path publicPath = PublicWebsiteLayoutResource.class.getAnnotation(Path.class);

        assertEquals("/api/website-builder/homepage", adminPath.value());
        assertArrayEquals(new String[]{AdminIdentityProvider.ADMIN_ROLE}, adminRoles.value());
        assertEquals("/api/v1/public/website-layout", publicPath.value());
        assertNotNull(PublicWebsiteLayoutResource.class.getAnnotation(PermitAll.class));
    }

    @Test
    void publicPublishedLayoutUsesTheExactWireShapeAndCachePolicy() throws Exception {
        WebsiteBuilderService builder = mock(WebsiteBuilderService.class);
        WebsiteBuilderDto.PublicDto payload = new WebsiteBuilderDto.PublicDto(
                7, new WebsiteBuilderDto.HomepageDto(List.of(
                        new WebsiteBuilderDto.SectionDto(HomepageSectionKey.HERO, true),
                        new WebsiteBuilderDto.SectionDto(HomepageSectionKey.QUOTE, true))));
        when(builder.published()).thenReturn(payload);

        Response response = new PublicWebsiteLayoutResource(builder).get();
        ObjectMapper json = new ObjectMapper();
        JsonNode wire = json.readTree(json.writeValueAsString(response.getEntity()));

        assertEquals(200, response.getStatus());
        assertEquals("public, max-age=60, stale-while-revalidate=300",
                response.getHeaderString("Cache-Control"));
        assertEquals(7, wire.path("revision").asInt());
        assertTrue(wire.has("homepage"));
        assertEquals(2, wire.path("homepage").path("sections").size());
        assertEquals("hero", wire.path("homepage").path("sections").get(0)
                .path("key").asText());
        assertEquals("quote", wire.path("homepage").path("sections").get(1)
                .path("key").asText());
    }

    @Test
    void adminResourceReturnsTheServiceAggregateForGetUpdateAndPublish() {
        WebsiteBuilderService builder = mock(WebsiteBuilderService.class);
        WebsiteBuilderDto.AdminDto payload = new WebsiteBuilderDto.AdminDto(
                3,
                new WebsiteBuilderDto.LayoutDto(List.of()),
                new WebsiteBuilderDto.LayoutDto(List.of()),
                null,
                null);
        WebsiteBuilderDto.UpdateDto update = new WebsiteBuilderDto.UpdateDto(2L, List.of());
        WebsiteBuilderDto.PublishDto publish = new WebsiteBuilderDto.PublishDto(3L);
        when(builder.get()).thenReturn(payload);
        when(builder.update(update)).thenReturn(payload);
        when(builder.publish(publish)).thenReturn(payload);

        WebsiteBuilderResource resource = new WebsiteBuilderResource(builder);

        assertEquals(payload, resource.get());
        assertEquals(payload, resource.update(update));
        assertEquals(payload, resource.publish(publish));
    }
}
