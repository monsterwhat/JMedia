package Services;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Kodi-style #EXTGRP directives supply the group when #EXTINF has none. */
class M3uParserExtgrpTest {

    @Test
    void appliesPersistentExtgrpToFollowingEntries() {
        String m3u = "#EXTM3U\n"
                + "#EXTGRP:Documentaries\n"
                + "#EXTINF:-1,Deep Sea\nhttp://example.test/1\n"
                + "#EXTINF:-1,Wild Life\nhttp://example.test/2\n";

        List<M3uParserService.M3uEntry> entries = new M3uParserService().parse(m3u);

        assertEquals("Documentaries", entries.get(0).groupTitle);
        assertEquals("Documentaries", entries.get(1).groupTitle);
    }

    @Test
    void appliesExtgrpAfterExtinfBeforeTheUrl() {
        String m3u = "#EXTM3U\n"
                + "#EXTINF:-1,Match Day\n"
                + "#EXTGRP:Sports\n"
                + "http://example.test/3\n";

        List<M3uParserService.M3uEntry> entries = new M3uParserService().parse(m3u);

        assertEquals("Sports", entries.get(0).groupTitle);
        assertEquals(List.of("Sports"), entries.get(0).groupTitles);
    }

    @Test
    void extgrpAfterExtinfOverridesAnInheritedPersistentGroup() {
        String m3u = "#EXTM3U\n"
                + "#EXTGRP:Documentaries\n"
                + "#EXTINF:-1,Match Day\n"
                + "#EXTGRP:Sports\n"
                + "http://example.test/7\n";

        List<M3uParserService.M3uEntry> entries = new M3uParserService().parse(m3u);

        assertEquals("Sports", entries.get(0).groupTitle);
        assertEquals(List.of("Sports"), entries.get(0).groupTitles);
    }

    @Test
    void explicitGroupTitleWinsOverExtgrp() {
        String m3u = "#EXTM3U\n"
                + "#EXTGRP:Documentaries\n"
                + "#EXTINF:-1 group-title=\"Sports\",Match Day\nhttp://example.test/4\n";

        List<M3uParserService.M3uEntry> entries = new M3uParserService().parse(m3u);

        assertEquals("Sports", entries.get(0).groupTitle);
    }

    @Test
    void emptyExtgrpClearsTheInheritedGroup() {
        String m3u = "#EXTM3U\n"
                + "#EXTGRP:Documentaries\n"
                + "#EXTINF:-1,Deep Sea\nhttp://example.test/5\n"
                + "#EXTGRP:\n"
                + "#EXTINF:-1,No Group\nhttp://example.test/6\n";

        List<M3uParserService.M3uEntry> entries = new M3uParserService().parse(m3u);

        assertEquals("Documentaries", entries.get(0).groupTitle);
        assertNull(entries.get(1).groupTitle);
    }
}
