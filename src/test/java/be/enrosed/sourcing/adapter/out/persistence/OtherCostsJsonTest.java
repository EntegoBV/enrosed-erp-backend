package be.enrosed.sourcing.adapter.out.persistence;

import be.enrosed.sourcing.domain.OtherCost;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class OtherCostsJsonTest {

    @Test
    void roundTripsNamesAndAmountsAndKeepsAnEmptyListAsNull() {
        List<OtherCost> costs = List.of(
                new OtherCost("Certificaat \"CE\"", new BigDecimal("120.00")),
                new OtherCost("Labo", null));

        String json = OtherCostsJson.write(costs);

        assertEquals(costs, OtherCostsJson.read(json));
        assertNull(OtherCostsJson.write(List.of()));
        assertEquals(List.of(), OtherCostsJson.read(null));
        assertEquals(List.of(), OtherCostsJson.read(" "));
    }

    @Test
    void anUnreadableColumnReadsAsNoCostsInsteadOfBlockingTheOrder() {
        assertEquals(List.of(), OtherCostsJson.read("{not json"));
    }
}
