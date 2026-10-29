package Services;

import Models.Video.Video;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Decision matrix for TranscodingService.isTranscodeNeededForWeb.
 * Plain JUnit, offline: the method is pure logic over (video, UA, proven flag).
 */
class TranscodingServiceTest {

    private static final String CHROME_WINDOWS =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";
    private static final String EDGE_WINDOWS =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36 Edg/131.0.0.0";
    private static final String FIREFOX_WINDOWS =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:133.0) Gecko/20100101 Firefox/133.0";
    private static final String SAFARI_MAC =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.1 Safari/605.1.15";
    private static final String CHROME_MAC =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";
    private static final String CHROME_LINUX =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";

    private TranscodingService service;

    @BeforeEach
    void setUp() {
        service = new TranscodingService();
    }

    private static Video video(String codec) {
        Video v = new Video();
        v.videoCodec = codec;
        v.audioCodec = "opus";
        return v;
    }

    @Test
    void testChromeWindowsHevcNeedsTranscodeWithoutProof() {
        // Core regression test: stock Chrome/Windows has no HEVC Extension,
        // so an unproven client must get a transcode, not a copy.
        assertTrue(service.isTranscodeNeededForWeb(video("hevc"), CHROME_WINDOWS, false));
        assertTrue(service.isTranscodeNeededForWeb(video("hevc"), EDGE_WINDOWS, false));
    }

    @Test
    void testProvenMain10SkipsTranscode() {
        // Client proved Main 10 MSE playback (?nativeHevc=1): copy path is safe.
        assertFalse(service.isTranscodeNeededForWeb(video("hevc"), CHROME_WINDOWS, true));
        assertFalse(service.isTranscodeNeededForWeb(video("h265"), CHROME_WINDOWS, true));
    }

    @Test
    void testTwoArgDefaultIsSafe() {
        // Legacy callers without a proven flag must get the safe answer.
        assertTrue(service.isTranscodeNeededForWeb(video("hevc"), CHROME_WINDOWS));
        assertFalse(service.isTranscodeNeededForWeb(video("h264"), CHROME_WINDOWS));
    }

    @Test
    void testH264NeverTranscodesForCodec() {
        assertFalse(service.isTranscodeNeededForWeb(video("h264"), CHROME_WINDOWS, false));
        assertFalse(service.isTranscodeNeededForWeb(video("avc"), FIREFOX_WINDOWS, false));
    }

    @Test
    void testFirefoxAndLinuxAlwaysTranscodeHevc() {
        assertTrue(service.isTranscodeNeededForWeb(video("hevc"), FIREFOX_WINDOWS, false));
        assertTrue(service.isTranscodeNeededForWeb(video("hevc"), CHROME_LINUX, false));
        // Even a (bogus) proven flag must not whitelist Gecko: the probe
        // excludes Firefox because its MSE cannot decode HEVC samples.
        // Server side stays conservative here; only the nativeHevc param path
        // can override per-request, and the client never sends it on Firefox.
        assertTrue(service.isTranscodeNeededForWeb(video("hevc"), FIREFOX_WINDOWS, true));
    }

    @Test
    void testMacNativeToolboxUnchanged() {
        assertFalse(service.isTranscodeNeededForWeb(video("hevc"), SAFARI_MAC, false));
        assertFalse(service.isTranscodeNeededForWeb(video("hevc"), CHROME_MAC, false));
    }

    @Test
    void testNullCodecAndNullUaAreSafe() {
        Video noCodec = new Video();
        noCodec.videoCodec = null;
        assertTrue(service.isTranscodeNeededForWeb(noCodec, CHROME_WINDOWS, false));
        assertTrue(service.isTranscodeNeededForWeb(video("hevc"), null, false));
        assertTrue(service.isTranscodeNeededForWeb(video("hevc"), null, true));
    }

    @Test
    void testUnknownCodecTranscodes() {
        assertTrue(service.isTranscodeNeededForWeb(video("theora"), CHROME_WINDOWS, false));
    }

    // ── resolveOutputCodec: capability-driven output codec (Option A) ──────

    /** Discovery stub: only the families listed in {@code supported} have a
     *  usable hardware encoder. */
    private static FFmpegDiscoveryService discoveryWithHardware(String... supported) {
        java.util.Set<String> families = java.util.Set.of(supported);
        return new FFmpegDiscoveryService() {
            @Override
            public synchronized String detectHardwareEncoder(String codecFamily) {
                if (codecFamily != null && families.contains(codecFamily.toLowerCase(java.util.Locale.ROOT))) {
                    return codecFamily.toLowerCase(java.util.Locale.ROOT) + "_nvenc";
                }
                return null;
            }
        };
    }

    private static Video video(String codec, String resolution) {
        Video v = video(codec);
        v.resolution = resolution;
        return v;
    }

    @Test
    void testPlayableSourceResolvesToCopy() {
        service.discoveryService = discoveryWithHardware("h264", "hevc");
        assertEquals(TranscodingService.CODEC_COPY, service.resolveOutputCodec(
                video("h264", "1920x1080"), CHROME_WINDOWS, java.util.Set.of("hevc"), 0, false));
    }

    @Test
    void testHevcSourceTranscodesToH264WithoutClientProof() {
        // Chrome on Windows cannot decode HEVC unless it proved MSE support.
        service.discoveryService = discoveryWithHardware("h264", "hevc");
        assertEquals(TranscodingService.CODEC_H264, service.resolveOutputCodec(
                video("hevc", "1920x1080"), CHROME_WINDOWS, java.util.Set.of(), 0, false));
    }

    @Test
    void testHevcSourceCopiesForProvenClient() {
        // A client that proved HEVC playback needs no re-encode at all: copying
        // the source is strictly better than transcoding it.
        service.discoveryService = discoveryWithHardware("h264", "hevc");
        assertEquals(TranscodingService.CODEC_COPY, service.resolveOutputCodec(
                video("hevc", "1920x1080"), CHROME_WINDOWS, java.util.Set.of("hevc"), 0, false));
    }

    @Test
    void testDownscaledHevcKeepsHevcWhenClientProvesAndHardwareExists() {
        service.discoveryService = discoveryWithHardware("h264", "hevc");
        assertEquals(TranscodingService.CODEC_HEVC, service.resolveOutputCodec(
                video("hevc", "1920x1080"), CHROME_WINDOWS, java.util.Set.of("hevc"), 720, false));
    }

    @Test
    void testHevcOutputRequiresHardwareEncoder() {
        // No hardware HEVC encoder → never libx265 for live streaming: a
        // downscale that would have kept HEVC falls back to H.264.
        service.discoveryService = discoveryWithHardware("h264");
        assertEquals(TranscodingService.CODEC_H264, service.resolveOutputCodec(
                video("hevc", "1920x1080"), CHROME_WINDOWS, java.util.Set.of("hevc"), 720, false));
    }

    @Test
    void testH264SourceNeverUpconvertsToHevc() {
        // Downscale forces a transcode, but H.264 sources must not be re-encoded
        // to HEVC even when the client and the hardware could handle it.
        service.discoveryService = discoveryWithHardware("h264", "hevc");
        assertEquals(TranscodingService.CODEC_H264, service.resolveOutputCodec(
                video("h264", "1920x1080"), CHROME_WINDOWS, java.util.Set.of("hevc"), 720, false));
    }

    @Test
    void testDownscaleForcesTranscode() {
        service.discoveryService = discoveryWithHardware("h264", "hevc");
        assertEquals(TranscodingService.CODEC_H264, service.resolveOutputCodec(
                video("h264", "1920x1080"), CHROME_WINDOWS, java.util.Set.of("hevc"), 720, false));
    }

    @Test
    void testForceTranscodeAlwaysH264() {
        service.discoveryService = discoveryWithHardware("h264", "hevc");
        assertEquals(TranscodingService.CODEC_H264, service.resolveOutputCodec(
                video("h264", "1920x1080"), SAFARI_MAC, java.util.Set.of("hevc"), 0, true));
    }

    @Test
    void testAv1SourceCopyWithClientProbe() {
        service.discoveryService = discoveryWithHardware("h264", "hevc");
        assertEquals(TranscodingService.CODEC_COPY, service.resolveOutputCodec(
                video("av1", "1920x1080"), CHROME_WINDOWS, java.util.Set.of("av1"), 0, false));
    }

    // ── parseClientCodecs ─────────────────────────────────────────────────

    @Test
    void testParseClientCodecs() {
        assertEquals(java.util.Set.of(), TranscodingService.parseClientCodecs(null));
        assertEquals(java.util.Set.of(), TranscodingService.parseClientCodecs("  "));
        assertEquals(java.util.Set.of("hevc", "av1"),
                TranscodingService.parseClientCodecs(" HEVC , av01 "));
        assertEquals(java.util.Set.of("hevc"), TranscodingService.parseClientCodecs("h265"));
        assertEquals(java.util.Set.of("hevc"), TranscodingService.parseClientCodecs("hevc,hevc"));
    }

    // ── cache identity ────────────────────────────────────────────────────

    @Test
    void testCacheKeyIsCodecPartitioned() {
        String h264 = TranscodingService.cacheKey(42L, 0, 720, TranscodingService.CODEC_H264);
        String hevc = TranscodingService.cacheKey(42L, 0, 720, TranscodingService.CODEC_HEVC);
        String copy = TranscodingService.cacheKey(42L, 0, 720, TranscodingService.CODEC_COPY);
        assertNotEquals(h264, hevc);
        assertNotEquals(h264, copy);
        assertNotEquals(hevc, copy);
        // Segment cache and transcode cache must agree on the identity.
        assertEquals(h264, SegmentCacheService.videoHashKey(42L, 0, 720, TranscodingService.CODEC_H264));
        // Legacy (null codec) key is unchanged, so pre-capability caches still match.
        assertEquals("42|0|720", TranscodingService.cacheKey(42L, 0, 720, null));
    }

    @Test
    void testCacheFilePathIsCodecPartitioned() {
        java.nio.file.Path h264 = service.getCacheFilePath(42L, 10.5, 0, 720, TranscodingService.CODEC_H264);
        java.nio.file.Path hevc = service.getCacheFilePath(42L, 10.5, 0, 720, TranscodingService.CODEC_HEVC);
        assertNotEquals(h264.getFileName().toString(), hevc.getFileName().toString());
    }
}
