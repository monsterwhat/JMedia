package Services;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Semicolon-separated group-title memberships survive parsing. */
class M3uParserGroupsTest {

    @Test
    void keepsEveryMembershipAndUsesTheFirstAsPrimary() {
        String m3u = "#EXTM3U\n"
                + "#EXTINF:-1 group-title=\"News;World News;News\",Global Report\nhttp://example.test/live/1\n";

        List<M3uParserService.M3uEntry> entries = new M3uParserService().parse(m3u);

        assertEquals(1, entries.size());
        assertEquals("News", entries.get(0).groupTitle);
        assertEquals(List.of("News", "World News"), entries.get(0).groupTitles);
    }

    @Test
    void singleGroupStaysASingleMembership() {
        String m3u = "#EXTM3U\n"
                + "#EXTINF:-1 group-title=\"Sports\",Match Day\nhttp://example.test/live/2\n";

        List<M3uParserService.M3uEntry> entries = new M3uParserService().parse(m3u);

        assertEquals("Sports", entries.get(0).groupTitle);
        assertEquals(List.of("Sports"), entries.get(0).groupTitles);
    }
}
