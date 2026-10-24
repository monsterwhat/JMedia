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
}
