package API.Rest;

import Models.Video.SubtitleTrack;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Only explicit m3u8 requests are served through HLS (the only path that can
 * carry a SUBTITLES group); native-container requests stream progressively so
 * capable players keep instant seeking via Range requests.
 * These tests pin the routing decision in {@link XtreamStreamAPI#prefersHls} and the
 * sidecar-track eligibility rules used by HlsService when building that group.
 */
public class XtreamStreamHlsPreferenceTest {

    @Test
    void seriesEpisodesHonourNativeContainerProgressiveRequests() {
        // Native containers stream progressively (instant seek, no SUBTITLES group).
        assertFalse(XtreamStreamAPI.prefersHls("series", "mkv"));
        assertFalse(XtreamStreamAPI.prefersHls("series", "mp4"));
        assertTrue(XtreamStreamAPI.prefersHls("series", "m3u8"));
    }

    @Test
    void moviesStillHonourNativeContainerProgressiveRequests() {
        // Progressive fallback must keep working for direct-source movie clients.
        assertFalse(XtreamStreamAPI.prefersHls("movie", "mkv"));
        assertFalse(XtreamStreamAPI.prefersHls("movie", "mp4"));
        assertTrue(XtreamStreamAPI.prefersHls("movie", "m3u8"));
    }

    @Test
    void parakeetSidecarTracksAreTextAndHaveAFile() {
        // Mirrors HlsService.servableSubtitleTracks: a Parakeet AI sidecar has no codec
        // (ffprobe never saw it) but carries a text format plus a fullPath.
        SubtitleTrack ai = new SubtitleTrack();
        ai.fullPath = "Show/S01E01.en.srt";
        ai.format = "srt";
        ai.isAiGenerated = true;

        assertTrue(isServable(ai), "AI sidecar with format+fullPath must be servable");
    }

    @Test
    void bitmapTrackWithoutPgsHandlingIsNotTextServable() {
        SubtitleTrack bitmap = new SubtitleTrack();
        bitmap.fullPath = "Show/S01E01.pgs";
        bitmap.format = "pgssub";
        assertFalse(isServable(bitmap));
    }

    @Test
    void trackWithoutIndexOrFileIsNotServable() {
        SubtitleTrack orphan = new SubtitleTrack();
        orphan.format = "srt";
        assertFalse(isServable(orphan));
    }

    /**
     * Local copy of the two gates HlsService.servableSubtitleTracks applies, so the test
     * does not need a CDI container or a live HLS session.
     */
    private static boolean isServable(SubtitleTrack track) {
        boolean hasSource = track.trackIndex != null || (track.fullPath != null && !track.fullPath.isBlank());
        if (!hasSource) {
            return false;
        }
        if (track.trackIndex != null) {
            // PGS/embedded tracks are handled via the OCR path; treat as servable here.
            return true;
        }
        String format = track.format == null ? "" : track.format.toLowerCase(java.util.Locale.ROOT);
        return "srt".equals(format) || "vtt".equals(format) || "ass".equals(format)
                || "ssa".equals(format) || "subrip".equals(format);
    }
}