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
        assertEquals("avc1.64001E,mp4a.40.2", service.deriveCodecsString(
                video("hevc", "aac", "1920x1080"), TranscodingService.CODEC_H264, 480));
        assertEquals("avc1.640033,mp4a.40.2", service.deriveCodecsString(
                video("hevc", "aac", "3840x2160"), TranscodingService.CODEC_H264, 2160));
    }

    @Test
    void unknownHeightFallsBackToSourceResolution() {
        assertEquals("avc1.640028,mp4a.40.2", service.deriveCodecsString(
                video("hevc", "aac", "1920x1080"), TranscodingService.CODEC_H264, 0));
    }
}
