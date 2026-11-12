package Services;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * Camelot mapping for harmonic mixing. Offline, no CDI, no mocks.
 */
class DjScoringKeyTest {

    @Test
    void sameKeyIsTheHighestBonus() {
        assertEquals(0.15, DjScoring.keyBonus("C", "major", "C", "major"), 0.0001);
    }

    @Test
    void neighbourOnTheWheelScoresHighly() {
        // C major is 8B, G major is 9B.
        assertEquals(0.15 * (1.0 - 1.0 / 6.0),
                DjScoring.keyBonus("C", "major", "G", "major"), 0.0001);
    }

    @Test
    void relativeMajorMinorIsOneStepAway() {
        // C major is 8B, A minor is 8A.
        assertEquals(0.15 * (1.0 - 1.0 / 6.0),
                DjScoring.keyBonus("C", "major", "A", "minor"), 0.0001);
    }

    @Test
    void oppositeSideOfTheWheelScoresZero() {
        // C major 8B, F# major 2B: six steps apart.
        assertEquals(0.0, DjScoring.keyBonus("C", "major", "F#", "major"), 0.0001);
    }

    @Test
    void flatsAreEquivalentToSharps() {
        assertEquals(0.15, DjScoring.keyBonus("Db", "major", "C#", "major"), 0.0001);
    }

    @Test
    void unknownKeyIsNeutral() {
        assertEquals(0.0, DjScoring.keyBonus(null, "major", "C", "major"), 0.0001);
        assertEquals(0.0, DjScoring.keyBonus("C", null, "C", "major"), 0.0001);
        assertEquals(0.0, DjScoring.keyBonus("H", "major", "C", "major"), 0.0001);
    }
}
