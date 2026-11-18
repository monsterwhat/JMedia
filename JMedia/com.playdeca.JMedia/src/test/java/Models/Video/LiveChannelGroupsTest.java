package Models.Video;

import org.junit.jupiter.api.Test;

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
}
