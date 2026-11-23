package Services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;


import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import Models.Video.AudioTrack;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Permanent conversion must not lose the original or quietly downgrade it:
 * originals are kept, there is no default resolution cap, 10-bit stays
 * 10-bit for HEVC/AV1, HDR is never squeezed into H.264, and audio keeps its
 * channel layout per track.
 */
class VideoConversionSafetyTest {

    private static VideoConversionService.SourceVideoInfo src(String pixFmt, String transfer) {
        return new VideoConversionService.SourceVideoInfo(pixFmt, transfer, null, null);
    }

    private static AudioTrack track(String codec, int channels) {
        AudioTrack t = new AudioTrack();
        t.codec = codec;
        t.channels = channels;
        return t;
    }

    // ── defaults ──

    @Test
    void defaultsKeepTheOriginalAndDoNotCapResolution() {
        VideoConversionService.ConversionOptions d = VideoConversionService.ConversionOptions.defaults();
        assertTrue(d.keepOriginal());
        assertEquals(0, d.maxHeight());
    }

    @Test
    void sixArgumentOptionsKeepTheOriginal() {
        assertTrue(new VideoConversionService.ConversionOptions("h264", "mp4", "aac", 23, null, 720).keepOriginal());
    }

    // ── kept original ──

    @Test
    void backupGoesNextToTheSourceWithANonVideoExtension(@TempDir Path dir) throws Exception {
        Path input = Files.createFile(dir.resolve("Movie.mkv"));
        assertEquals(dir.resolve("Movie.mkv.original"), VideoConversionService.backupPathFor(input));
    }

    @Test
    void backupNeverOverwritesAnEarlierBackup(@TempDir Path dir) throws Exception {
        Path input = Files.createFile(dir.resolve("Movie.mp4"));
        Files.createFile(dir.resolve("Movie.mp4.original"));
        Files.createFile(dir.resolve("Movie.mp4.original.1"));
        assertEquals(dir.resolve("Movie.mp4.original.2"), VideoConversionService.backupPathFor(input));
    }

    // ── bit depth and HDR ──

    @Test
    void recognisesHighBitDepthFormats() {
        assertTrue(src("yuv420p10le", null).isHighBitDepth());
        assertTrue(src("p010le", null).isHighBitDepth());
        assertTrue(src("yuv422p12le", null).isHighBitDepth());
        assertFalse(src("yuv420p", null).isHighBitDepth());
        assertFalse(src(null, null).isHighBitDepth());
    }

    @Test
    void recognisesHdrTransfers() {
        assertTrue(src("yuv420p10le", "smpte2084").isHdr());
        assertTrue(src("yuv420p10le", "arib-std-b67").isHdr());
        assertFalse(src("yuv420p10le", "bt709").isHdr());
        assertFalse(src("yuv420p", null).isHdr());
    }

    @Test
    void tenBitStaysTenBitForHevcAndAv1Only() {
        assertTrue(VideoConversionService.keepsHighBitDepth("hevc", src("yuv420p10le", null)));
        assertTrue(VideoConversionService.keepsHighBitDepth("av1", src("p010le", null)));
        assertFalse(VideoConversionService.keepsHighBitDepth("h264", src("yuv420p10le", null)));
        assertFalse(VideoConversionService.keepsHighBitDepth("hevc", src("yuv420p", null)));
    }

    @Test
    void hdrToH264IsToneMapped() {
        assertTrue(VideoConversionService.needsToneMap("h264", src("yuv420p10le", "smpte2084")));
        assertTrue(VideoConversionService.needsToneMap("h264", src("yuv420p10le", "arib-std-b67")));
    }

    @Test
    void hdrToHevcOrAv1KeepsHdrAndSdrIsLeftAlone() {
        assertFalse(VideoConversionService.needsToneMap("hevc", src("yuv420p10le", "smpte2084")));
        assertFalse(VideoConversionService.needsToneMap("av1", src("yuv420p10le", "smpte2084")));
        assertFalse(VideoConversionService.needsToneMap("h264", src("yuv420p10le", "bt709")));
        assertFalse(VideoConversionService.needsToneMap("h264", VideoConversionService.SourceVideoInfo.UNKNOWN));
    }

    @Test
    void toneMapEndsInEightBitBt709() {
        String f = VideoConversionService.TONE_MAP_FILTER;
        assertTrue(f.contains("tonemap="));
        assertTrue(f.endsWith("zscale=t=bt709:m=bt709:r=tv,format=yuv420p"));
    }

    // ── audio ──

    @Test
    void surroundIsEncodedAtItsOwnChannelCount() {
        List<String> args = VideoConversionService.audioArgs(List.of(track("dts", 6)), "aac", false);
        assertEquals(List.of("-c:a:0", "aac", "-b:a:0", "384k"), args);
        assertFalse(args.contains("-ac"), "no stereo downmix");
    }

    @Test
    void eachTrackIsJudgedOnItsOwnCodec() {
        // AAC copies into MP4, DTS does not: the old check looked at the first track only.
        List<String> args = VideoConversionService.audioArgs(
                List.of(track("aac", 2), track("dts", 6)), "aac", false);
        assertEquals(List.of(
                "-c:a:0", "copy", "-bsf:a:0", "aac_adtstoasc",
                "-c:a:1", "aac", "-b:a:1", "384k"), args);
    }

    @Test
    void copyIntoMp4StillEncodesTracksMp4CannotHold() {
        List<String> args = VideoConversionService.audioArgs(
                List.of(track("ac3", 6), track("truehd", 8)), "copy", false);
        assertEquals(List.of(
                "-c:a:0", "copy",
                "-c:a:1", "aac", "-b:a:1", "512k"), args);
    }

    @Test
    void copyIntoMkvCopiesEverything() {
        assertEquals(List.of("-c:a:0", "copy", "-c:a:1", "copy"),
                VideoConversionService.audioArgs(List.of(track("truehd", 8), track("dts", 6)), "copy", true));
    }

    @Test
    void dolbyEncodersGetAtMostFivePointOne() {
        assertEquals(List.of("-ac:a:0", "6", "-c:a:0", "eac3", "-b:a:0", "448k"),
                VideoConversionService.audioArgs(List.of(track("truehd", 8)), "eac3", true));
    }

    @Test
    void stereoStaysStereo() {
        assertEquals(List.of("-c:a:0", "aac", "-b:a:0", "192k"),
                VideoConversionService.audioArgs(List.of(track("flac", 2)), "aac", true));
    }
}
