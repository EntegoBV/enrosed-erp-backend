package be.enrosed.planning;

import be.enrosed.shared.audit.ActivityDto;
import be.enrosed.shared.audit.ActivityLogService;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@io.quarkus.test.security.TestSecurity(user = "emre",
        roles = be.enrosed.shared.security.AdminIdentityProvider.ADMIN_ROLE)
class PlannerResourceTest {
    @Inject PlannerResource planner;
    @Inject ActivityLogService activities;

    @Test
    @TestTransaction
    void appointmentsSortByDateAndTasksTickOff() {
        var meeting = planner.create(new PlannerResource.PlannerItem(null, PlannerItemEntity.Kind.EVENT,
                "Side Arendonk bezoek", LocalDate.of(2026, 9, 3), "10:30", "stand bespreken", false, true, null, null));
        planner.create(new PlannerResource.PlannerItem(null, PlannerItemEntity.Kind.TASK,
                "Dozen bestellen", null, null, null, false, null, null, null));
        planner.create(new PlannerResource.PlannerItem(null, PlannerItemEntity.Kind.EVENT,
                "Beurs Gent", LocalDate.of(2026, 8, 30), null, null, false, null, null, null));

        /* The fair-planning seed shares the table: assert relative order, not counts. */
        var items = planner.list();
        int gent = indexOf(items, "Beurs Gent");
        int arendonk = indexOf(items, "Side Arendonk bezoek");
        int loose = indexOf(items, "Dozen bestellen");
        assertTrue(gent >= 0 && arendonk > gent, "earliest date first");
        assertTrue(loose > arendonk, "loose tasks come last");
        assertNull(items.get(loose).onDate());

        assertTrue(items.get(arendonk).pinned(), "the pin rides along");

        var created = (PlannerResource.PlannerItem) meeting.getEntity();
        var edited = planner.update(created.id(), new PlannerResource.PlannerItem(created.id(),
                created.kind(), created.title(), created.onDate(), created.atTime(),
                "stand en timing bespreken", false, false, null, null));
        var done = planner.update(created.id(), new PlannerResource.PlannerItem(created.id(),
                edited.kind(), edited.title(), edited.onDate(), edited.atTime(), edited.note(), true,
                false, null, null));
        assertTrue(done.done());

        int before = planner.list().size();
        planner.delete(created.id());
        assertEquals(before - 1, planner.list().size());

        var itemActivity = activities.list("emre", "PLANNER_ITEM", created.id().toString(), null, 20);
        assertEquals(java.util.List.of(
                        ActivityLogService.ACTION_DELETED,
                        ActivityLogService.ACTION_STATUS_CHANGED,
                        ActivityLogService.ACTION_UPDATED,
                        ActivityLogService.ACTION_CREATED),
                itemActivity.items().stream().map(ActivityDto::action).toList());
        assertTrue(itemActivity.items().stream()
                .allMatch(activity -> activity.actor().displayName().equalsIgnoreCase("Emre")));
        assertEquals("Agendapunt verwijderd", itemActivity.items().getFirst().summary());
    }

    private static int indexOf(java.util.List<PlannerResource.PlannerItem> items, String title) {
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).title().equals(title)) return i;
        }
        return -1;
    }
}
