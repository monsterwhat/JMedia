package Controllers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.ArrayList;
import java.util.List;
import Models.Music.PlaybackState;
import org.junit.jupiter.api.Test;

/**
 * Regression guard for the recursion that locked the app on 2026-11-11.
 *
 * <p>A manual advance over a one-song cue removed the song, re-entered
 * {@code advance()}, refilled another one-song cue and recursed until the stack
 * overflowed. The overflow marked the JTA transaction rollback-only and the
 * connection pool then failed fast for every request, including login.
 *
 * <p>The depth guard runs before any injected dependency is touched, so this
 * test needs no CDI, no database and no mocks.
 */
class PlaybackQueueControllerAdvanceTest {

    @Test
    void depthGuardStopsInsteadOfRecursing() {
        PlaybackQueueController controller = new PlaybackQueueController();
        PlaybackState state = new PlaybackState();
        state.setPlaying(true);
        state.setCue(new ArrayList<>(List.of(1L)));
        state.setCueIndex(0);

        Long next = controller.advance(state, true, false, true, 1L, 4);

        assertNull(next, "no song should be returned once the guard trips");
        assertFalse(state.isPlaying(),
                "playback must stop rather than loop forever");
        assertEquals(1, state.getCue().size(), "the guard must not mutate the cue");
    }
}
