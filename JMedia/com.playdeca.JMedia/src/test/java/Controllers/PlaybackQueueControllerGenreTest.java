package Controllers;

import Models.Music.PlaybackState;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlaybackQueueControllerGenreTest {

    private static PlaybackState stateWithPool(String... pool) {
        PlaybackState state = new PlaybackState();
        state.setDjGenrePool(new java.util.ArrayList<>(List.of(pool)));
        return state;
    }

    @Test
    void poolMatchesEveryCanonicalVariant() {
        PlaybackQueueController controller = new PlaybackQueueController();
        PlaybackState state = stateWithPool("Hip-Hop");
        assertTrue(controller.isGenreAllowed(state, "Hip Hop"));
        assertTrue(controller.isGenreAllowed(state, "Hip-Hop"));
        assertTrue(controller.isGenreAllowed(state, "hip-hop"));
        assertTrue(controller.isGenreAllowed(state, "Hip-Hop/Rap"));
        assertFalse(controller.isGenreAllowed(state, "Rock"));
    }

    @Test
    void multiGenrePoolMatchesAnyResolvedGenre() {
        PlaybackQueueController controller = new PlaybackQueueController();
        PlaybackState state = stateWithPool("Rock", "Pop");
        assertTrue(controller.isGenreAllowed(state, "Rock/Pop"));
        assertTrue(controller.isGenreAllowed(state, "Pop, Rock"));
        assertTrue(controller.isGenreAllowed(state, "rock"));
        assertFalse(controller.isGenreAllowed(state, "Jazz"));
    }

    @Test
    void unidentifiedPoolMatchesBlankAndSentinelSongs() {
        PlaybackQueueController controller = new PlaybackQueueController();
        PlaybackState state = stateWithPool("Unidentified");
        assertTrue(controller.isGenreAllowed(state, null));
        assertTrue(controller.isGenreAllowed(state, ""));
        assertTrue(controller.isGenreAllowed(state, "Unknown Genre"));
        assertFalse(controller.isGenreAllowed(state, "Rock"));
    }

}
