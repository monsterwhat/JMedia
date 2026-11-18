package Services;

import Models.Video.LiveChannel;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CatchupServiceTest {

    private final CatchupService service = new CatchupService();

    private static LiveChannel channel(String url, String type, String source, Integer days) {
        LiveChannel ch = new LiveChannel();
        ch.id = 1L;
        ch.streamUrl = url;
        ch.catchupType = type;
        ch.catchupSource = source;
        ch.catchupDays = days;
        return ch;
    }

    @Test
    void defaultModeFillsStartAndEnd() {
        LiveChannel ch = channel("https://p/live/u/p/1.ts", "default",
                "https://p/archive?start=${start}&end=${end}", 7);

        String url = service.resolveUrl(ch, 1_700_000_000L, 60, 1_700_010_000L);

        assertEquals("https://p/archive?start=1700000000&end=1700003600", url);
    }

    @Test
    void fillsBraceTokensAndDuration() {
        LiveChannel ch = channel("https://p/live/u/p/1.ts", "default",
                "https://p/a?utc={utc}&utcend={utcend}&d={duration:60}", 7);

        String url = service.resolveUrl(ch, 1_700_000_000L, 60, 1_700_010_000L);

        assertEquals("https://p/a?utc=1700000000&utcend=1700003600&d=60", url);
    }

    @Test
    void durationTokensDivideOnlyWhenRequested() {
        LiveChannel ch = channel("https://p/live/u/p/1.ts", "default",
                "https://p/a?d={duration}&m={duration:60}&q=${duration:60}&min={durmin}", 7);

        assertEquals("https://p/a?d=3600&m=60&q=60&min=60",
                service.resolveUrl(ch, 1_700_000_000L, 60, 1_700_010_000L));
    }

    @Test
    void supportsJavaAndPhpDateFormats() {
        LiveChannel javaStyle = channel("https://p/live/u/p/1.ts", "default",
                "https://p/a?start=${(b)yyyyMMddHHmmss|UTC}&end=${(e)yyyyMMddHHmmss|UTC}", 7);
        LiveChannel phpStyle = channel("https://p/live/u/p/1.ts", "default",
                "https://p/a?t={utc:Ymd-H-M}", 7);

        assertEquals("https://p/a?start=20231114221320&end=20231114231320",
                service.resolveUrl(javaStyle, 1_700_000_000L, 60, 1_700_010_000L));
        assertEquals("https://p/a?t=20231114-22-13",
                service.resolveUrl(phpStyle, 1_700_000_000L, 60, 1_700_010_000L));
    }

    @Test
    void timestampAndOffsetTokensUseTheCurrentTime() {
        LiveChannel ch = channel("https://p/live/u/p/1.ts", "default",
                "https://p/a?ts=${timestamp}&off={offset}&offm={offset:60}", 7);

        assertEquals("https://p/a?ts=1700010000&off=10000&offm=166",
                service.resolveUrl(ch, 1_700_000_000L, 60, 1_700_010_000L));
    }

    @Test
    void providerCredentialsComeFromTheLiveUrl() {
        LiveChannel ch = channel("https://p/live/user%40x/pass%2Fy/1.ts", "default",
                "https://p/a?u=${login}&p=${password}", 7);

        assertEquals("https://p/a?u=user@x&p=pass/y",
                service.resolveUrl(ch, 1_700_000_000L, 60, 1_700_010_000L));
    }

    @Test
    void isoTokensAreAvailable() {
        LiveChannel ch = channel("https://p/live/u/p/1.ts", "default",
                "https://p/a?s={start_iso}&e={end_iso}", 7);

        assertEquals("https://p/a?s=2023-11-14T22:13:20Z&e=2023-11-14T23:13:20Z",
                service.resolveUrl(ch, 1_700_000_000L, 60, 1_700_010_000L));
    }

    @Test
    void appendModeAddsToTheLiveUrl() {
        LiveChannel ch = channel("https://p/live/u/p/1.ts", "append", "?start=${start}", 7);

        assertEquals("https://p/live/u/p/1.ts?start=1700000000",
                service.resolveUrl(ch, 1_700_000_000L, 60, 1_700_010_000L));
    }

    @Test
    void shiftModeAppendsUtcAndNow() {
        LiveChannel ch = channel("https://p/live/u/p/1.ts", "shift", null, 7);

        assertEquals("https://p/live/u/p/1.ts?utc=1700000000&lutc=1700010000",
                service.resolveUrl(ch, 1_700_000_000L, 60, 1_700_010_000L));
    }

    @Test
    void xcModeBuildsTheXtreamTimeshiftPath() {
        LiveChannel ch = channel("https://p/live/user/pass/123.ts", "xc", null, 7);

        assertEquals("https://p/timeshift/user/pass/60/2023-11-14:22-13/123.ts",
                service.resolveUrl(ch, 1_700_000_000L, 60, 1_700_010_000L));
    }

    @Test
    void flussonicHlsRewritesIndexAndVideoPlaylists() {
        LiveChannel index = channel("https://p/channel/index.m3u8", "flussonic-hls", null, 7);
        LiveChannel video = channel("https://p/channel/video.m3u8", "flussonic-hls", null, 7);

        assertEquals("https://p/channel/archive-1700000000-3600.m3u8",
                service.resolveUrl(index, 1_700_000_000L, 60, 1_700_010_000L));
        assertEquals("https://p/channel/video-1700000000-3600.m3u8",
                service.resolveUrl(video, 1_700_000_000L, 60, 1_700_010_000L));
    }

    @Test
    void phpDateTokensFormatStartAndEnd() {
        LiveChannel ch = channel("https://p/live/u/p/1.ts", "default",
                "https://p/a?start=${(b)Y-m-d:H:i}&end=${(e)Y-m-d:H:i}", 7);

        assertEquals("https://p/a?start=2023-11-14:22:13&end=2023-11-14:23:13",
                service.resolveUrl(ch, 1_700_000_000L, 60, 1_700_010_000L));
    }

    @Test
    void parsesSupportedStartFormats() {
        assertEquals(1_700_000_000L, service.parseStart("1700000000"));
        assertEquals(LocalDateTime.of(2023, 11, 14, 22, 13).toEpochSecond(ZoneOffset.UTC),
                service.parseStart("2023-11-14:22-13"));
        long expected = LocalDateTime.of(2023, 11, 14, 22, 0).toEpochSecond(ZoneOffset.UTC);
        assertEquals(expected, service.parseStart("2023-11-14-22-00"));
        assertNull(service.parseStart("not-a-date"));
    }

    @Test
    void enforcesTheArchiveWindow() {
        LiveChannel ch = channel("https://p/live/u/p/1.ts", "shift", null, 7);
        long now = 1_700_000_000L;

        assertTrue(service.isWithinArchive(ch, now - 60, now));
        assertTrue(service.isWithinArchive(ch, now - 6 * 86_400L, now));
        assertFalse(service.isWithinArchive(ch, now - 8 * 86_400L, now));
        assertFalse(service.isWithinArchive(ch, now + 900, now));
    }

    @Test
    void unavailableWithoutAnyCatchupMetadata() {
        LiveChannel ch = channel("https://p/live/u/p/1.ts", null, null, null);

        assertFalse(service.isAvailable(ch));
        assertEquals(0, service.archiveDays(ch));
        assertNull(service.resolveUrl(ch, 1_700_000_000L, 60, 1_700_010_000L));
    }
}
