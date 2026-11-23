package Controllers;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PlaybackControllerDjFallbackTest {

    @Test
    void unplannedDjPlaybackDoesNotUseTheOrdinaryCrossfadePath() {
        assertFalse(PlaybackController.shouldUseRegularCrossfade(true, false));
        assertFalse(PlaybackController.shouldUseRegularCrossfade(true, null));
    }

    @Test
    void plannedDjPlaybackAndNonDjPlaybackCanUseTheOrdinaryPath() {
        assertTrue(PlaybackController.shouldUseRegularCrossfade(true, true));
        assertTrue(PlaybackController.shouldUseRegularCrossfade(false, false));
    }
}
