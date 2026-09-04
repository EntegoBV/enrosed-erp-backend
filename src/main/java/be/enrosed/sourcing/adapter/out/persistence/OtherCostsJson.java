package be.enrosed.sourcing.adapter.out.persistence;

import be.enrosed.sourcing.domain.OtherCost;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;

/**
 * The other costs of an order as one JSON column. A handful of named
 * amounts per order does not deserve its own table, and one column keeps
 * the order row self-contained for exports and backups.
 */
final class OtherCostsJson {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<List<OtherCost>> LIST = new TypeReference<>() {};

    private OtherCostsJson() {}

    /** Null for an empty list, so untouched orders keep a null column. */
    static String write(List<OtherCost> costs) {
        if (costs == null || costs.isEmpty()) return null;
        try {
            return JSON.writeValueAsString(costs);
        } catch (Exception exception) {
            throw new IllegalStateException("Andere kosten konden niet worden opgeslagen", exception);
        }
    }

    /** An unreadable column reads as no costs rather than blocking the order. */
    static List<OtherCost> read(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            List<OtherCost> costs = JSON.readValue(json, LIST);
            return costs == null ? List.of() : costs;
        } catch (Exception exception) {
            return List.of();
        }
    }
}
