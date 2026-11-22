package Services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Fade policy for planned DJ transitions. Offline, no CDI, no analysis data.
 */
class DjTransitionFadePolicyTest {

    private static final double NO_NUDGE = 1.0;
    private static final double NUDGED = 1.03;
    private static final double NUDGED_DOWN = 0.97;

    @Test
    void fastTrackGetsTheShortestFade() {
        assertEquals(1, DjTransitionService.resolveCrossfadeSeconds(null, 128, NO_NUDGE));
        assertEquals(1, DjTransitionService.resolveCrossfadeSeconds(null, 121, NO_NUDGE));
    }

    @Test
    void slowerTrackGetsTwoSeconds() {
        assertEquals(2, DjTransitionService.resolveCrossfadeSeconds(null, 120, NO_NUDGE));
        assertEquals(2, DjTransitionService.resolveCrossfadeSeconds(null, 90, NO_NUDGE));
    }

    @Test
    void unmatchedTempoCapsTheOverrideAtTwoSeconds() {
        assertEquals(2, DjTransitionService.resolveCrossfadeSeconds(4, 120, NO_NUDGE));
        assertEquals(2, DjTransitionService.resolveCrossfadeSeconds(6, 100, NO_NUDGE));
    }

    @Test
    void tempoMatchedPairMayUseTheLongerOverride() {
        assertEquals(4, DjTransitionService.resolveCrossfadeSeconds(4, 120, NUDGED));
        assertEquals(4, DjTransitionService.resolveCrossfadeSeconds(4, 120, NUDGED_DOWN));
    }

    @Test
    void matchedFadeNeverExceedsTheCeiling() {
        assertEquals(6, DjTransitionService.resolveCrossfadeSeconds(30, 120, NUDGED));
        assertEquals(6, DjTransitionService.resolveCrossfadeSeconds(Integer.MAX_VALUE, 120, NUDGED));
    }

    @Test
    void fadeNeverDropsBelowOneSecond() {
        assertEquals(1, DjTransitionService.resolveCrossfadeSeconds(0, 120, NUDGED));
        assertEquals(1, DjTransitionService.resolveCrossfadeSeconds(0, 120, NO_NUDGE));
    }

    @Test
    void negativeOverrideMeansUnset() {
        assertEquals(2, DjTransitionService.resolveCrossfadeSeconds(-1, 100, NO_NUDGE));
        assertEquals(2, DjTransitionService.resolveCrossfadeSeconds(-1, 100, NUDGED));
        assertEquals(1, DjTransitionService.resolveCrossfadeSeconds(-1, 130, NO_NUDGE));
    }

    @Test
    void everyCombinationStaysWithinOneToSixSeconds() {
        Integer[] overrides = {null, -3, 0, 1, 2, 4, 6, 10, 60};
        double[] bpms = {0, 80, 120, 121, 140, 200};
        double[] rates = {NO_NUDGE, NUDGED, NUDGED_DOWN};

        for (Integer override : overrides) {
            for (double bpm : bpms) {
                for (double rate : rates) {
                    int fade = DjTransitionService.resolveCrossfadeSeconds(override, bpm, rate);
                    assertTrue(fade >= 1 && fade <= 6,
                            "fade out of bounds: " + fade + " for override=" + override
                                    + " bpm=" + bpm + " rate=" + rate);
                }
            }
        }
    }
}
