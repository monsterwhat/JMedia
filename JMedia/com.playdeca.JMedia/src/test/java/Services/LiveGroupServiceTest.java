package Services;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Stable allocation rules for Xtream live-group category ids. */
class LiveGroupServiceTest {

    @Test
    void keepsTheLegacyIdWhenItIsFree() {
        String key = "movies";
        Map<String, String> planned = LiveGroupService.planNewIds(Map.of(), List.of(key));

        assertEquals(LiveGroupService.legacyId(key), planned.get(key));
    }

    @Test
    void movesToTheAllocatedRangeOnCollision() {
        String key = "sports";
        String legacy = LiveGroupService.legacyId(key);
        Map<String, String> existing = new HashMap<>();
        existing.put("some other group", legacy);

        Map<String, String> planned = LiveGroupService.planNewIds(existing, List.of(key));

        String id = planned.get(key);
        assertNotNull(id);
        assertNotEquals(legacy, id);
        assertTrue(Long.parseLong(id) > LiveGroupService.LEGACY_ID_MAX,
                "collision ids must stay above the legacy hash range");
    }

    @Test
    void collidingGroupsGetDistinctIds() {
        String first = "alpha";
        String second = "beta";
        Map<String, String> existing = new HashMap<>();
        existing.put("taken", LiveGroupService.legacyId(first));

        Map<String, String> planned = LiveGroupService.planNewIds(existing, List.of(first, second));

        assertNotEquals(planned.get(first), planned.get(second));
        assertEquals(2, planned.size());
    }

    @Test
    void existingMappingsNeverChange() {
        Map<String, String> existing = Map.of("movies", "1234567");

        assertTrue(LiveGroupService.planNewIds(existing, List.of("movies")).isEmpty());
        assertEquals("1234567", existing.get("movies"));
    }
}
