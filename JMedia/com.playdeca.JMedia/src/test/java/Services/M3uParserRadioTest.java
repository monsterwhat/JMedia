package Services;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** M3U radio markers must survive parsing so Xtream can report radio_streams. */
class M3uParserRadioTest {

    @Test
    void parsesRadioAndTvgTypeAttributes() {
        String m3u = "#EXTM3U\n"
                + "#EXTINF:-1 radio=\"true\" group-title=\"Radio\",Jazz FM\nhttp://example.test/radio/1\n"
                + "#EXTINF:-1 tvg-type=\"radio\" group-title=\"Radio\",Talk FM\nhttp://example.test/radio/2\n"
                + "#EXTINF:-1 radio=1 group-title=\"Radio\",Old FM\nhttp://example.test/radio/3\n"
                + "#EXTINF:-1 group-title=\"News\",BBC One\nhttp://example.test/live/4\n";

        List<M3uParserService.M3uEntry> entries = new M3uParserService().parse(m3u);

        assertEquals(4, entries.size());
        assertEquals("Jazz FM", entries.get(0).name);
        assertTrue(entries.get(0).isRadio);
        assertTrue(entries.get(1).isRadio);
        assertTrue(entries.get(2).isRadio);
        assertFalse(entries.get(3).isRadio);
        assertEquals("Radio", entries.get(0).groupTitle);
    }
}
