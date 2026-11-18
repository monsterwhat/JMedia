package Models.Video;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Storage format for multi-group channels. */
class LiveChannelGroupsTest {

    @Test
    void storedMembershipsIncludeThePrimaryGroup() {
        assertEquals(List.of("A", "B", "C"), LiveChannel.parseGroupTitles("A", ";A;B;C;"));
    }

    @Test
    void fallsBackToThePrimaryGroupForOldRows() {
        assertEquals(List.of("A"), LiveChannel.parseGroupTitles("A", null));
        assertTrue(LiveChannel.parseGroupTitles(null, null).isEmpty());
    }

    @Test
    void encodesOnlyMultiGroupRows() {
        assertEquals(";A;B;", LiveChannel.encodeGroupTitles(List.of("A", "B")));
        assertNull(LiveChannel.encodeGroupTitles(List.of("A")));
        assertNull(LiveChannel.encodeGroupTitles(null));
    }

    @Test
    void allGroupTitlesUsesTheStoredMemberships() {
        LiveChannel ch = new LiveChannel();
        ch.groupTitle = "A";
        ch.groupTitles = ";A;B;";
        assertEquals(List.of("A", "B"), ch.allGroupTitles());
    }

    @Test
    void storedMembershipsStayInsideTheColumnLimit() {
        List<String> groups = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            groups.add("Group-" + i + "-" + "x".repeat(100));
        }

        String encoded = LiveChannel.encodeGroupTitles(groups);

        assertTrue(encoded.length() <= 2000, "encoded length " + encoded.length());
        assertTrue(encoded.startsWith(";"));
        assertTrue(encoded.endsWith(";"));
        for (String part : encoded.split(";")) {
            if (!part.isEmpty()) {
                assertTrue(groups.contains(part), "partial group stored: " + part);
            }
        }
    }
}
