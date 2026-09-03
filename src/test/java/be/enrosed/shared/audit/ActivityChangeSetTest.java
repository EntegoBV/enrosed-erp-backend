package be.enrosed.shared.audit;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The log records what a reader would see change, not what a scale or a blank makes different. */
class ActivityChangeSetTest {

    @Test
    void sameValueInAnotherScaleOrBlankFormIsNoChange() {
        List<ActivityChangeDto> changes = ActivityChangeSet.create()
                .add("weight", "Kartongewicht", new BigDecimal("14.0"), new BigDecimal("14.00"))
                .add("price", "EXW-prijs", new BigDecimal("113.10"), new BigDecimal("113.1"))
                .add("note", "Notitie", "", null)
                .add("name", "Naam", " Glass dome ", "Glass dome")
                .privateValue("secret", "Leveranciersnotitie", null, "")
                .build();

        assertEquals(List.of(), changes);
    }

    @Test
    void aRealChangeKeepsBothSidesAsShown() {
        List<ActivityChangeDto> changes = ActivityChangeSet.create()
                .add("weight", "Kartongewicht", new BigDecimal("14.0"), new BigDecimal("15"))
                .add("active", "Actief", true, false)
                .privateValue("secret", "Leveranciersnotitie", "old", "new")
                .build();

        assertEquals(List.of(
                new ActivityChangeDto("weight", "Kartongewicht", "14", "15"),
                new ActivityChangeDto("active", "Actief", "Ja", "Nee"),
                new ActivityChangeDto("secret", "Leveranciersnotitie", null, null)), changes);
    }
}
