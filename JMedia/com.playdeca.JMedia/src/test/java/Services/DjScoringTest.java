package Services;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class DjScoringTest {

    @Test
    void testPhraseBonusBar0() {
        // Bar 0 (0-based) â†’ % 4 == 0 â†’ +0.15, % 8 == 0 â†’ +0.10 â†’ total +0.25
        double result = DjScoring.phraseBonus(0);
        assertEquals(0.25, result, "Bar 0 should give 0.15 + 0.10 = 0.25");
    }

    @Test
    void testPhraseBonusBar1() {
        // Bar 1 â†’ % 4 != 0 â†’ +0.0
        double result = DjScoring.phraseBonus(1);
        assertEquals(0.0, result, "Bar 1 should give 0.0");
    }

    @Test
    void testPhraseBonusBar3() {
        // Bar 3 â†’ % 4 == 3 â†’ +0.0 (not a 4-bar boundary)
        double result = DjScoring.phraseBonus(3);
        assertEquals(0.0, result, "Bar 3 should give 0.0");
    }

    @Test
    void testPhraseBonusBar4() {
        // Bar 4 (0-based) â†’ % 4 == 0 â†’ +0.15, % 8 != 0 â†’ total +0.15
        double result = DjScoring.phraseBonus(4);
        assertEquals(0.15, result, "Bar 4 should give 0.15");
    }

    @Test
    void testPhraseBonusBar7() {
        // Bar 7 â†’ % 4 == 3 â†’ +0.0
        double result = DjScoring.phraseBonus(7);
        assertEquals(0.0, result, "Bar 7 should give 0.0");
    }

    @Test
    void testPhraseBonusBar8() {
        // Bar 8 (0-based) â†’ % 4 == 0 â†’ +0.15, % 8 == 0 â†’ +0.10 â†’ total +0.25
        double result = DjScoring.phraseBonus(8);
        assertEquals(0.25, result, "Bar 8 should give 0.15 + 0.10 = 0.25");
    }

    @Test
    void testDownbeatBonus() {
        // Downbeat (beatInBar == 1) â†’ +0.2
        assertEquals(0.2, DjScoring.downbeatBonus(1), "Downbeat should give 0.2");
        // Non-downbeat â†’ +0.0
        assertEquals(0.0, DjScoring.downbeatBonus(2), "Non-downbeat should give 0.0");
        assertEquals(0.0, DjScoring.downbeatBonus(4), "Non-downbeat should give 0.0");
    }

    @Test
    void testExitPositionScore_band1() {
        // 0.50 â†’ < 0.55 â†’ -0.60
        assertEquals(-0.60, DjScoring.exitPositionScore(0.50), "0.50 should give -0.60");
    }

    @Test
    void testExitPositionScore_band2() {
        // 0.55 â†’ >= 0.55 â†’ -0.15
        assertEquals(-0.15, DjScoring.exitPositionScore(0.55), "0.55 should give -0.15");
    }

    @Test
    void testExitPositionScore_band3() {
        // 0.60 â†’ >= 0.55 â†’ -0.15 (but < 0.70)
        assertEquals(-0.15, DjScoring.exitPositionScore(0.60), "0.60 should give -0.15");
    }

    @Test
    void testExitPositionScore_band4() {
        // 0.70 â†’ >= 0.70 â†’ +0.20 (but < 0.78, so not the +0.45 band)
        assertEquals(0.20, DjScoring.exitPositionScore(0.70), "0.70 should give +0.20");
    }

    @Test
    void testExitPositionScore_band5() {
        // 0.75 â†’ >= 0.70 â†’ +0.20 (but < 0.78, so not the +0.45 band)
        assertEquals(0.20, DjScoring.exitPositionScore(0.75), "0.75 should give +0.20");
    }

    @Test
    void testExitPositionScore_band6() {
        // 0.78 â†’ >= 0.78 && <= 0.92 â†’ +0.45
        assertEquals(0.45, DjScoring.exitPositionScore(0.78), "0.78 should give +0.45");
    }

    @Test
    void testExitPositionScore_band7() {
        // 0.85 â†’ >= 0.78 && <= 0.92 â†’ +0.45
        assertEquals(0.45, DjScoring.exitPositionScore(0.85), "0.85 should give +0.45");
    }

    @Test
    void testExitPositionScore_band8() {
        // 0.92 â†’ >= 0.78 && <= 0.92 â†’ +0.45
        assertEquals(0.45, DjScoring.exitPositionScore(0.92), "0.92 should give +0.45");
    }

    @Test
    void testExitPositionScore_band9() {
        // 0.95 â†’ > 0.92 but >= 0.70 â†’ +0.20
        assertEquals(0.20, DjScoring.exitPositionScore(0.95), "0.95 should give +0.20");
    }

    @Test
    void testExitPositionScore_band10() {
        // 1.00 â†’ >= 0.70 â†’ +0.20
        assertEquals(0.20, DjScoring.exitPositionScore(1.00), "1.00 should give +0.20");
    }

    @Test
    void testEntryPositionScore_lessThan2s() {
        // < 2.0s â†’ -0.5
        assertEquals(-0.5, DjScoring.entryPositionScore(1.0, 0.50), "1s at 50% should give -0.5");
    }

    @Test
    void testEntryPositionScore_lessThan5s() {
        // < 5.0s but >= 2.0s â†’ -0.2
        assertEquals(-0.2, DjScoring.entryPositionScore(3.0, 0.50), "3s at 50% should give -0.2");
    }

    @Test
    void testEntryPositionScore_sweetspot() {
        // > 2.0s && < 5.0s â†’ already handled above
        // > 5.0s && 0.05 < pos < 0.40 â†’ +0.2
        assertEquals(0.2, DjScoring.entryPositionScore(10.0, 0.30), "10s at 30% should give +0.2");
    }

    @Test
    void testEntryPositionScore_midSong() {
        // >= 0.40 && < 0.55 â†’ +0.05
        assertEquals(0.05, DjScoring.entryPositionScore(10.0, 0.50), "10s at 50% should give +0.05");
    }

    @Test
    void testEnergyBonus_equal() {
        // Equal energy â†’ max(0, 0.2 - 0*0.2) = 0.2
        assertEquals(0.2, DjScoring.energyBonus(0.5, 0.5), "Equal energy should give 0.2");
    }

    @Test
    void testEnergyBonus_opposite() {
        // Large difference â†’ max(0, 0.2 - 1.0*0.2) = 0.0
        assertEquals(0.0, DjScoring.energyBonus(0.0, 1.0), "Opposite energy should give 0.0");
    }

    @Test
    void testEnergyBonus_partial() {
        // Medium difference â†’ max(0, 0.2 - 0.5*0.2) = 0.1
        assertEquals(0.1, DjScoring.energyBonus(0.5, 1.0), "Partial energy diff should give 0.1");
    }

    @Test
    void testNormalizeConfidence_raw335() {
        // raw 3.35 / 3.35 = 1.0, no BPM penalty â†’ 1.0
        assertEquals(1.0, DjScoring.normalizeConfidence(DjScoring.MAX_RAW_SCORE, 0.0), "Raw 3.35 with 0% BPM diff should give 1.0");
    }

    @Test
    void testNormalizeConfidence_raw1675() {
        // raw 1.675 / 3.35 = 0.5, no BPM penalty â†’ 0.5
        assertEquals(0.5, DjScoring.normalizeConfidence(DjScoring.MAX_RAW_SCORE / 2, 0.0), "Raw 1.675 with 0% BPM diff should give 0.5");
    }

    @Test
    void testNormalizeConfidence_bpmPenalty() {
        // raw 3.35 / 3.35 = 1.0, BPM diff 0.30 â†’ penalty = 1 - min(0.30/0.30, 0.8) = 1 - 0.8 = 0.2
        // Floating-point: may produce 0.19999999999999996 instead of 0.2
        assertEquals(0.2, DjScoring.normalizeConfidence(DjScoring.MAX_RAW_SCORE, 0.30), 0.001, "Raw 3.35 with 30% BPM diff should give 0.2");
    }

    @Test
    void testNormalizeConfidence_halfBpmPenalty() {
        // raw 3.35 / 3.35 = 1.0, BPM diff 0.15 â†’ penalty = 1 - min(0.15/0.30, 0.8) = 1 - 0.5 = 0.5
        // Floating-point: may produce 0.49999999999999994 instead of 0.5
        assertEquals(0.5, DjScoring.normalizeConfidence(DjScoring.MAX_RAW_SCORE, 0.15), 0.001, "Raw 3.35 with 15% BPM diff should give 0.5");
    }

    @Test
    void testClampExit_eof() {
        // exit at 10.0, duration 10.0, fade 1.0 â†’ latest = 10.0 - 1.0 - 0.3 = 8.7
        // min(10.0, 8.7) = 8.7
        assertEquals(8.7, DjScoring.clampExit(10.0, 10.0, 1), "Clamp at EOF with fade=1s");
    }

    @Test
    void testClampExit_negative() {
        // exit at 10.0, duration 5.0, fade 1.0 â†’ latest = 5.0 - 1.0 - 0.3 = 3.7
        // min(10.0, 3.7) = 3.7
        assertEquals(3.7, DjScoring.clampExit(10.0, 5.0, 1), "Clamp when exit past duration");
    }

    @Test
    void testClampExit_withinBounds() {
        // exit at 5.0, duration 10.0, fade 1.0 â†’ latest = 10.0 - 1.0 - 0.3 = 8.7
        // min(5.0, 8.7) = 5.0
        assertEquals(5.0, DjScoring.clampExit(5.0, 10.0, 1), "Clamp within bounds");
    }
}
