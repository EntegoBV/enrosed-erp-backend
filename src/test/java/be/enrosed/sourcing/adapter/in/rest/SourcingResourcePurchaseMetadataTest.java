package be.enrosed.sourcing.adapter.in.rest;

import be.enrosed.shared.Currency;
import be.enrosed.shared.security.ActorRef;
import be.enrosed.sourcing.domain.Allocation;
import be.enrosed.sourcing.domain.ContainerType;
import be.enrosed.sourcing.domain.PurchaseOrder;
import be.enrosed.sourcing.domain.PurchaseOrderStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class SourcingResourcePurchaseMetadataTest {

    @Test
    void creatorMetadataIsReadOnlyAndOutsideTheMutableOrderPayload() throws Exception {
        ActorRef creator = new ActorRef("emre", "Emre");
        Instant createdAt = Instant.parse("2026-08-27T08:15:30Z");
        PurchaseOrder order = new PurchaseOrder(
                41L, "PO-2026-041", null, 7L, LocalDate.of(2026, 8, 27),
                PurchaseOrderStatus.CONCEPT, ContainerType.FORTY_HQ,
                new BigDecimal("0.14"), new BigDecimal("0.91"), new BigDecimal("0.91"),
                BigDecimal.ZERO, BigDecimal.ZERO, Currency.USD, BigDecimal.ZERO,
                new BigDecimal("5"), BigDecimal.ZERO,
                Allocation.CBM, Allocation.CBM, Allocation.CBM, Allocation.PIECES,
                "Ningbo", "Rotterdam", null, List.of())
                .withCreationMetadata(creator, createdAt);
        SourcingResource.PurchaseOrderView view = new SourcingResource.PurchaseOrderView(
                order, null, List.of(), null, null, List.of(), creator, createdAt);

        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules()
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        JsonNode json = mapper.readTree(mapper.writeValueAsBytes(view));

        assertEquals("emre", json.path("createdBy").path("username").asText());
        assertEquals("Emre", json.path("createdBy").path("displayName").asText());
        assertEquals(createdAt.toString(), json.path("createdAt").asText());
        assertFalse(json.path("order").has("createdBy"));
        assertFalse(json.path("order").has("createdAt"));
    }
}
