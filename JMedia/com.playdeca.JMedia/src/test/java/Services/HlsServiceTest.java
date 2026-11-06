package Services;

import Models.Video.Video;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CODECS attribute of the HLS master playlist.
 *
 * <p>Plain JUnit, offline: {@code deriveCodecsString} is pure logic over
 * (video, outputCodec, outputHeight). The critical property is that the
 * declared codec describes what the encoder actually produces — a transcoded
 * HEVC source must be announced as H.264, otherwise hls.js rejects the variant.
 */
class HlsServiceTest {

    private final HlsService service = new HlsService();

    private static Video video(String codec, String audio, String resolution) {
        Video v = new Video();
        v.videoCodec = codec;
        v.audioCodec = audio;
        v.resolution = resolution;
        return v;
    }

    @Test
    void copyModeDeclaresSourceHevc() {
        String codecs = service.deriveCodecsString(
                video("hevc", "aac", "1920x1080"), TranscodingService.CODEC_COPY, 1080);
        assertTrue(codecs.startsWith("hvc1."), "copy of an HEVC source must declare hvc1: " + codecs);
        assertEquals("mp4a.40.2", codecs.split(",")[1]);
    }

    @Test
    void copyModeDeclaresHevcProfileAndMeasuredLevel() {
        // The HEVC level must cover the actual luma picture size and sample rate.
        // 2592x1080 exceeds level 4.1's 2,228,224 luma samples -> level 5.0.
        assertEquals("hvc1.2.4.L150.B0,mp4a.40.2", service.deriveCodecsString(
                hevc("2592x1080", "Main 10", 24), TranscodingService.CODEC_COPY, 1080));
        // 1080p Main 10 at 24 fps fits level 4.0.
        assertEquals("hvc1.2.4.L120.B0,mp4a.40.2", service.deriveCodecsString(
                hevc("1920x1080", "Main 10", 24), TranscodingService.CODEC_COPY, 1080));
        // 4K60 exceeds level 5.0's sample rate -> level 5.1.
        assertEquals("hvc1.2.4.L153.B0,mp4a.40.2", service.deriveCodecsString(
                hevc("3840x2160", "Main 10", 60), TranscodingService.CODEC_COPY, 2160));
        // Main profile keeps profile_idc 1.
        assertEquals("hvc1.1.4.L120.B0,mp4a.40.2", service.deriveCodecsString(
                hevc("1920x1080", "Main", 24), TranscodingService.CODEC_COPY, 1080));
    }

    private static Video hevc(String resolution, String profile, int fps) {
        Video v = video("hevc", "eac3", resolution);
        v.videoProfile = profile;
        v.frameRate = fps;
        return v;
    }

    @Test
    void copyModeDeclaresSourceH264() {
        String codecs = service.deriveCodecsString(
                video("h264", "aac", "1920x1080"), TranscodingService.CODEC_COPY, 1080);
        assertTrue(codecs.startsWith("avc1."), "copy of an H.264 source must declare avc1: " + codecs);
    }

    @Test
    void transcodeNeverDeclaresHevc() {
        // The regression this guards: HEVC source + H.264 encoder used to be
        // announced as hvc1, so hls.js refused a perfectly playable stream.
        String codecs = service.deriveCodecsString(
                video("hevc", "dts", "1920x1080"), TranscodingService.CODEC_H264, 720);
        assertTrue(codecs.startsWith("avc1."), "transcoded output must declare avc1: " + codecs);
        assertFalse(codecs.contains("hvc1"));
        assertEquals("avc1.64001F,mp4a.40.2", codecs);
    }

    @Test
    void transcodeDeclaresAacAudioEvenForMp3Source() {
        // Audio is always re-encoded to AAC on the transcode path.
        String codecs = service.deriveCodecsString(
                video("hevc", "mp3", "1920x1080"), TranscodingService.CODEC_H264, 1080);
        assertEquals("avc1.640028,mp4a.40.2", codecs);
    }

    @Test
    void avc1LevelFollowsOutputHeight() {
        // 480p at 30 fps needs level 3.1 (level 3.0's MB/s ceiling is too low).
        assertEquals("avc1.64001F,mp4a.40.2", service.deriveCodecsString(
                video("hevc", "aac", "1920x1080"), TranscodingService.CODEC_H264, 480));
        assertEquals("avc1.640033,mp4a.40.2", service.deriveCodecsString(
                video("hevc", "aac", "3840x2160"), TranscodingService.CODEC_H264, 2160));
    }

    @Test
    void avc1LevelUsesOutputWidthNotJustHeight() {
        // The real regression: 2592x1080 -> 1728x720 is "720p" but exceeds
        // level 3.1's 1280x720 frame limit, so hls.js rejected avc1.64001F.
        assertEquals("avc1.640020,mp4a.40.2", service.deriveCodecsString(
                video("hevc", "eac3", "2592x1080"), TranscodingService.CODEC_H264, 720));
        // Ultra-wide 1080p also exceeds level 4.0's 1920x1080 limit.
        assertEquals("avc1.640032,mp4a.40.2", service.deriveCodecsString(
                video("hevc", "eac3", "2592x1080"), TranscodingService.CODEC_H264, 1080));
    }

    @Test
    void unknownHeightFallsBackToSourceResolution() {
        assertEquals("avc1.640028,mp4a.40.2", service.deriveCodecsString(
                video("hevc", "aac", "1920x1080"), TranscodingService.CODEC_H264, 0));
    }
}
