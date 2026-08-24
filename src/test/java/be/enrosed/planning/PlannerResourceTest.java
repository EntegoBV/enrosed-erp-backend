package be.enrosed.planning;

import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@io.quarkus.test.security.TestSecurity(user = "enrosedadmin",
        roles = be.enrosed.shared.security.AdminIdentityProvider.ADMIN_ROLE)
class PlannerResourceTest {
    @Inject PlannerResource planner;

    @Test
    @TestTransaction
    void appointmentsSortByDateAndTasksTickOff() {
        var meeting = planner.create(new PlannerResource.PlannerItem(null, PlannerItemEntity.Kind.EVENT,
                "Side Arendonk bezoek", LocalDate.of(2026, 9, 3), "10:30", "stand bespreken", false, true, null));
        planner.create(new PlannerResource.PlannerItem(null, PlannerItemEntity.Kind.TASK,
                "Dozen bestellen", null, null, null, false, null, null));
        planner.create(new PlannerResource.PlannerItem(null, PlannerItemEntity.Kind.EVENT,
                "Beurs Gent", LocalDate.of(2026, 8, 30), null, null, false, null, null));

        var items = planner.list();
        assertEquals(3, items.size());
        assertEquals("Beurs Gent", items.get(0).title(), "earliest date first");
        assertEquals("Side Arendonk bezoek", items.get(1).title());
        assertNull(items.get(2).onDate(), "loose tasks come last");

        assertTrue(items.get(1).pinned(), "the pin rides along");

        var created = (PlannerResource.PlannerItem) meeting.getEntity();
        var done = planner.update(created.id(), new PlannerResource.PlannerItem(created.id(),
                created.kind(), created.title(), created.onDate(), created.atTime(), created.note(), true,
                false, null));
        assertTrue(done.done());

        planner.delete(created.id());
        assertEquals(2, planner.list().size());
    }
}
