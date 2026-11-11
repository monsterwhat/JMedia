package Services;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Offline tests for the multi-episode split decision.
 *
 * <p>Fixtures are the measurements taken from the real library, so the tests
 * encode why each rule exists:
 *
 * <pre>
 *   S01E01-02  dur 1353  black 671.59-672.38  silence 671.90-672.43  cut 671.50
 *   S01E35-36  dur 1387  black 674.67-677.68  silence 674.21-677.75  cut 677.68
 *   S01E48-49  dur 1361  black 677.72-683.22  silence 677.75-679.09  cut 683.22
 *   S01E47     dur  696  black 673.01-676.68  silence 673.51-678.90  no split
 * </pre>
 *
 * <p>Plain JUnit 5: no QuarkusTest, no mocks, no network.
 */
class EpisodeSplitPlannerTest {

    private static EpisodeSplitPlanner.TimeRange range(double start, double end) {
        return new EpisodeSplitPlanner.TimeRange(start, end);
    }

    // ── filename parsing ────────────────────────────────────────────────────

    @Test
    void readsTheDeclaredEpisodeRange() {
        EpisodeSplitPlanner.EpisodeRange range =
                EpisodeSplitPlanner.episodeRange("Steven Universe - S01E01-02 - [x265.AAC].mp4");

        assertEquals(1, range.first());
        assertEquals(2, range.last());
    }

    @Test
    void readsTheRangeWithAnExplicitSecondEpisodeMarker() {
        EpisodeSplitPlanner.EpisodeRange range =
                EpisodeSplitPlanner.episodeRange("Show - S02E14-E15 - Title.mkv");

        assertEquals(14, range.first());
        assertEquals(15, range.last());
    }

    @Test
    void refusesASingleEpisodeFile() {
        assertNull(EpisodeSplitPlanner.episodeRange("Steven Universe - S04E08 - [x265.AAC].mp4"),
                "Gem Harvest is one double-length episode, not two files");
        assertNull(EpisodeSplitPlanner.episodeRange("Some Movie (2019).mkv"));
        assertNull(EpisodeSplitPlanner.episodeRange(null));
    }

    @Test
    void buildsPartNamesPreservingPaddingAndSuffix() {
        EpisodeSplitPlanner.EpisodeRange range =
                EpisodeSplitPlanner.episodeRange("Steven Universe - S01E01-02 - [x265.AAC].mp4");

        String[] names = EpisodeSplitPlanner.partNames(
                "Steven Universe - S01E01-02 - [x265.AAC].mp4", range);

        assertArrayEquals(new String[]{
                "Steven Universe - S01E01 - [x265.AAC].mp4",
                "Steven Universe - S01E02 - [x265.AAC].mp4"
        }, names);
    }

    // ── candidate selection ─────────────────────────────────────────────────

    @Test
    void picksTheBreakThatHasBothBlackAndSilence() {
        EpisodeSplitPlanner.Candidate candidate = EpisodeSplitPlanner.chooseCandidate(
                List.of(range(671.59, 672.38)),
                List.of(range(671.90, 672.43)),
                1352.98);

        assertEquals(672.38, candidate.boundarySeconds(), 0.01);
    }

    @Test
    void rejectsBlackWithoutAnAudioGap() {
        assertNull(EpisodeSplitPlanner.chooseCandidate(
                List.of(range(671.59, 672.38)),
                List.of(),
                1352.98),
                "black alone is a scene transition, not an episode break");
    }

    @Test
    void rejectsAShortBlackRun() {
        assertNull(EpisodeSplitPlanner.chooseCandidate(
                List.of(range(684.39, 684.60)),
                List.of(range(684.40, 684.70)),
                1352.98));
    }

    @Test
    void rejectsABreakOutsideTheMiddleWindow() {
        // A real single episode: black + 5.4s silence at 96.7% of the file.
        assertNull(EpisodeSplitPlanner.chooseCandidate(
                List.of(range(673.01, 676.68)),
                List.of(range(673.51, 678.90)),
                696.17),
                "an episode that fades near its own end must not be split");
    }

    @Test
    void prefersTheLongestSupportedRunOverAnEarlierShortOne() {
        // S01E48-49: a short black at 674 and the real 5.5s break at 683.
        EpisodeSplitPlanner.Candidate candidate = EpisodeSplitPlanner.chooseCandidate(
                List.of(range(673.88, 674.72), range(677.72, 683.22)),
                List.of(range(674.04, 674.80), range(677.75, 679.09), range(679.95, 680.46)),
                1360.79);

        assertEquals(683.22, candidate.boundarySeconds(), 0.01);
    }

    @Test
    void theMidpointBreaksATie() {
        // S01E35-36 has two 3s black runs: 674.7-677.7 and 711.9-714.9.
        EpisodeSplitPlanner.Candidate candidate = EpisodeSplitPlanner.chooseCandidate(
                List.of(range(674.67, 677.68), range(711.92, 714.92)),
                List.of(range(674.21, 677.75), range(711.90, 714.90)),
                1386.86);

        assertEquals(677.68, candidate.boundarySeconds(), 0.01,
                "the run nearer the midpoint wins");
    }

    // ── keyframe snapping ───────────────────────────────────────────────────

    @Test
    void snapsToTheLastKeyframeAtOrBeforeTheBoundary() {
        double cut = EpisodeSplitPlanner.snapToKeyframe(672.38, List.of(661.08, 671.50, 675.20));

        assertEquals(671.50, cut, 0.001,
                "part two must open on black, not two seconds into the episode");
    }

    @Test
    void ignoresAKeyframeThatIsTooFarBack() {
        double cut = EpisodeSplitPlanner.snapToKeyframe(672.38, List.of(660.00));

        assertEquals(672.38, cut, 0.001);
    }

    @Test
    void keepsTheBoundaryWhenThereAreNoKeyframes() {
        assertEquals(672.38, EpisodeSplitPlanner.snapToKeyframe(672.38, List.of()), 0.001);
        assertEquals(672.38, EpisodeSplitPlanner.snapToKeyframe(672.38, null), 0.001);
    }

    @Test
    void snapsTheTrailingCutForwardWhenThatIsCloser() {
        // S01E37-38: keyframes 710.21 and 715.04 around a 714.92 boundary.
        double cut = EpisodeSplitPlanner.snapToNearestKeyframe(714.92, List.of(710.208, 715.042));

        assertEquals(715.042, cut, 0.001,
                "rounding forward keeps the preview out of episode two");
    }

    @Test
    void fallsBackToThePreviousKeyframeWhenNoneIsClose() {
        double cut = EpisodeSplitPlanner.snapToNearestKeyframe(714.92, List.of(700.00, 730.00));

        assertEquals(714.92, cut, 0.001);
    }

    // ── preview detection (three-way cut) ───────────────────────────────────

    @Test
    void findsThePreviewBetweenTwoEpisodes() {
        // S01E35-36: the two black+silence breaks 34s apart.
        EpisodeSplitPlanner.Candidate trailing = EpisodeSplitPlanner.chooseTrailingCandidate(
                List.of(range(674.67, 677.68), range(711.92, 714.92)),
                List.of(range(674.21, 677.75), range(711.90, 715.00)),
                1386.86, 677.68);

        assertEquals(714.92, trailing.boundarySeconds(), 0.01,
                "the second fade closes the preview and opens episode 2");
    }

    @Test
    void aNormalPairHasNoPreview() {
        // The 684s black is 0.21s of scene noise, far below the threshold.
        assertNull(EpisodeSplitPlanner.chooseTrailingCandidate(
                List.of(range(671.59, 672.38), range(684.39, 684.60)),
                List.of(range(671.90, 672.43), range(684.40, 684.70)),
                1352.98, 671.50));
    }

    @Test
    void rejectsATrailingRunThatIsTooClose() {
        assertNull(EpisodeSplitPlanner.chooseTrailingCandidate(
                List.of(range(677.68, 683.00)),
                List.of(range(677.75, 683.10)),
                1386.86, 677.68));
    }

    @Test
    void rejectsATrailingRunThatIsTooFar() {
        assertNull(EpisodeSplitPlanner.chooseTrailingCandidate(
                List.of(range(900.00, 903.00)),
                List.of(range(900.10, 903.10)),
                1386.86, 677.68));
    }

    @Test
    void prefersTheNearestTrailingRun() {
        EpisodeSplitPlanner.Candidate trailing = EpisodeSplitPlanner.chooseTrailingCandidate(
                List.of(range(695.00, 698.00), range(711.92, 714.92)),
                List.of(range(695.10, 698.10), range(711.90, 715.00)),
                1386.86, 677.68);

        assertEquals(698.00, trailing.boundarySeconds(), 0.01);
    }

    // ── the measured library cases end to end ───────────────────────────────

    @Test
    void reproducesTheMeasuredCutForAStandardPair() {
        EpisodeSplitPlanner.Candidate candidate = EpisodeSplitPlanner.chooseCandidate(
                List.of(range(671.59, 672.38), range(684.39, 684.60)),
                List.of(range(671.21, 671.64), range(671.90, 672.43)),
                1352.98);

        assertEquals(672.38, candidate.boundarySeconds(), 0.01);
        assertEquals(671.50, EpisodeSplitPlanner.snapToKeyframe(
                candidate.boundarySeconds(), List.of(661.08, 671.50)), 0.001);
    }

    @Test
    void reproducesTheMeasuredCutForTheLatePair() {
        EpisodeSplitPlanner.Candidate candidate = EpisodeSplitPlanner.chooseCandidate(
                List.of(range(674.67, 677.68), range(711.92, 714.92)),
                List.of(range(674.21, 677.75)),
                1386.86);

        assertEquals(677.68, EpisodeSplitPlanner.snapToKeyframe(
                candidate.boundarySeconds(), List.of(674.67, 677.68)), 0.001);
    }
}
