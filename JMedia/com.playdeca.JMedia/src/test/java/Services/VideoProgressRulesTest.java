package Services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import Models.Video.VideoState;
import org.junit.jupiter.api.Test;

/** Progress reports must never wipe a resume point, and "watched" is kept. */
class VideoProgressRulesTest {

    private static VideoState state(double time, double progress, boolean watched) {
        VideoState s = new VideoState();
        s.currentTime = time;
        s.watchProgress = progress;
        s.watched = watched;
        return s;
    }

    @Test
    void zeroReportDoesNotWipeAResumePoint() {
        VideoState s = state(3600, 0.5, false);
        assertFalse(VideoStateService.applyProgress(s, 0, 7200));
        assertEquals(3600, s.currentTime, 0.001);
        assertEquals(0.5, s.watchProgress, 0.001);
    }

    @Test
    void startingOverIsSavedOnceItHasPlayed() {
        VideoState s = state(3600, 0.5, false);
        assertTrue(VideoStateService.applyProgress(s, 5, 7200));
        assertEquals(5, s.currentTime, 0.001);
    }

    @Test
    void firstReportOfANewVideoIsSaved() {
        VideoState s = state(0, 0, false);
        assertTrue(VideoStateService.applyProgress(s, 0.4, 7200));
        assertTrue(VideoStateService.applyProgress(s, 12, 7200));
        assertEquals(12, s.currentTime, 0.001);
    }

    @Test
    void watchedIsSetAt95PercentAndKept() {
        VideoState s = state(0, 0, false);
        VideoStateService.applyProgress(s, 6900, 7200);
        assertTrue(s.watched);
        VideoStateService.applyProgress(s, 600, 7200);
        assertTrue(s.watched, "re-watching a scene keeps a finished title watched");
        assertEquals(600, s.currentTime, 0.001);
    }
}
