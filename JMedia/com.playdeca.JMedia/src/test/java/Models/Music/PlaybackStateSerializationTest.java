package Models.Music;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/**
 * The WebSocket broadcasts {@code PlaybackState} through a plain ObjectMapper,
 * and the client only adopts properties its default state object declares. A
 * field missing from the JSON therefore never reaches the audio engine; this
 * test pins the two DJ plan fields that were dropped that way.
 */
class PlaybackStateSerializationTest {

    @Test
    void plannedFadeAndTempoNudgeAreSerialized() throws Exception {
        PlaybackState state = new PlaybackState();
        state.setDjNextSongId(42L);
        state.setDjEntryTime(30.5);
        state.setDjExitTime(208.9);
        state.setDjTransitionPlanned(true);
        state.setDjTransitionCrossfade(1);
        state.setDjTransitionRate(1.03);

        String json = new ObjectMapper().writeValueAsString(state);

        assertTrue(json.contains("\"djTransitionCrossfade\":1"), json);
        assertTrue(json.contains("\"djTransitionRate\":1.03"), json);
        assertTrue(json.contains("\"djNextSongId\":42"), json);
        assertTrue(json.contains("\"djExitTime\":208.9"), json);
    }
}
