package Services;

import Models.Settings.Profile;
import Models.Video.Video;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase A (HLS opt-in flag) unit tests:
 * - Profile.hlsStreaming defaults to false (opt-in, never on by default)
 * - VideoService.PlaybackData carries the flag through to the playback fragment
 */
public class PlaybackDataHlsFlagTest {

    @Test
    void profileHlsStreamingDefaultsFalse() {
        assertFalse(new Profile().hlsStreaming);
    }

    private static VideoService.PlaybackData dataWithFlag(boolean hlsStreaming) {
        return new VideoService.PlaybackData(
                new Video(), 0.0, false, false,
                null, null, null, null,
                false, false, false, "simple",
                new ArrayList<>(), 0, "", false, null, null,
                new LinkedHashMap<>(), hlsStreaming);
    }

    @Test
    void playbackDataCarriesHlsStreamingTrue() {
        assertTrue(dataWithFlag(true).hlsStreaming);
    }

    @Test
    void playbackDataCarriesHlsStreamingFalse() {
        assertFalse(dataWithFlag(false).hlsStreaming);
    }
}
