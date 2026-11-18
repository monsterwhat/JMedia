package Services;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Catch-up attributes, including header defaults and legacy markers. */
class M3uParserCatchupTest {

    @Test
    void headerDefaultsApplyToEntries() {
        String m3u = "#EXTM3U catchup=\"flussonic\" catchup-days=\"7\"\n"
                + "#EXTINF:-1,Channel One\nhttp://example.test/1\n";

        List<M3uParserService.M3uEntry> entries = new M3uParserService().parse(m3u);

        assertEquals("flussonic", entries.get(0).catchupType);
        assertEquals(7, entries.get(0).catchupDays);
    }

    @Test
    void perEntryValuesWinOverTheHeader() {
        String m3u = "#EXTM3U catchup=\"flussonic\" catchup-days=\"7\"\n"
                + "#EXTINF:-1 catchup=\"default\" catchup-days=\"3\" catchup-source=\"http://example.test/a?start=${start}\",Channel\n"
                + "http://example.test/2\n";

        List<M3uParserService.M3uEntry> entries = new M3uParserService().parse(m3u);

        assertEquals("default", entries.get(0).catchupType);
        assertEquals(3, entries.get(0).catchupDays);
        assertEquals("http://example.test/a?start=${start}", entries.get(0).catchupSource);
    }

    @Test
    void catchupEnableZeroDisablesTheHeaderDefault() {
        String m3u = "#EXTM3U catchup=\"flussonic\" catchup-days=\"7\"\n"
                + "#EXTINF:-1 catchup-enable=\"0\",No Archive\nhttp://example.test/3\n";

        List<M3uParserService.M3uEntry> entries = new M3uParserService().parse(m3u);

        assertFalse(Boolean.TRUE.equals(entries.get(0).catchupEnabled));
    }

    @Test
    void unescapesAmpersandsInTheSource() {
        String m3u = "#EXTM3U\n"
                + "#EXTINF:-1 catchup=\"default\" catchup-source=\"http://example.test/a?x=1&amp;y=${start}\",Channel\n"
                + "http://example.test/7\n";

        List<M3uParserService.M3uEntry> entries = new M3uParserService().parse(m3u);

        assertEquals("http://example.test/a?x=1&y=${start}", entries.get(0).catchupSource);
    }

    @Test
    void legacyTimeshiftAndTvgRecMarkCatchup() {
        String m3u = "#EXTM3U\n"
                + "#EXTINF:-1 timeshift=\"5\",Timeshift\nhttp://example.test/4\n"
                + "#EXTINF:-1 tvg-rec=\"2\",Recorded\nhttp://example.test/5\n"
                + "#EXTINF:-1 catchup-time=\"86400\",Timed\nhttp://example.test/6\n";

        List<M3uParserService.M3uEntry> entries = new M3uParserService().parse(m3u);

        assertEquals("timeshift", entries.get(0).catchupType);
        assertEquals(5, entries.get(0).catchupDays);
        assertEquals(2, entries.get(1).catchupDays);
        assertTrue(Boolean.TRUE.equals(entries.get(1).catchupEnabled));
        assertEquals(1, entries.get(2).catchupDays);
    }
}
