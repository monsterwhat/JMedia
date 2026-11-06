package Services;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The cleanup invariant: a session that has not registered an encoder yet
 * (STARTING) must never be reaped just because no process is alive.
 */
class HlsServiceSessionCleanupTest {

    private static final long NOW = 1_000_000L;
    private static final long STARTUP_DEADLINE = 30_000L;
    private static final long IDLE_TTL = 60_000L;

    @Test
    void startingSessionIsNeverReapedDuringItsStartupWindow() {
        assertFalse(HlsService.shouldReapSession(
                HlsService.SessionState.STARTING,
                NOW - 1_000L, NOW, false, false, false, NOW));
        assertFalse(HlsService.shouldReapSession(
                HlsService.SessionState.STARTING,
                NOW - (STARTUP_DEADLINE - 1), NOW, false, false, false, NOW));
    }

    @Test
    void startingSessionIsReapedOnlyAfterTheExplicitDeadline() {
        assertTrue(HlsService.shouldReapSession(
                HlsService.SessionState.STARTING,
                NOW - STARTUP_DEADLINE, NOW, false, false, false, NOW));
    }

    @Test
    void activeSessionWithALiveProcessIsReapedOnlyWhenIdle() {
        assertFalse(HlsService.shouldReapSession(
                HlsService.SessionState.ACTIVE,
                NOW - 120_000L, NOW - 1_000L, false, true, false, NOW));
        assertTrue(HlsService.shouldReapSession(
                HlsService.SessionState.ACTIVE,
                NOW - 120_000L, NOW - (IDLE_TTL + 1), false, true, false, NOW));
    }

    @Test
    void activeSessionWithADeadProcessIsReaped() {
        assertTrue(HlsService.shouldReapSession(
                HlsService.SessionState.ACTIVE,
                NOW - 120_000L, NOW, false, false, false, NOW));
    }

    @Test
    void completedSessionKeepsFilesWhileTheClientIsStillReading() {
        assertFalse(HlsService.shouldReapSession(
                HlsService.SessionState.ACTIVE,
                NOW - 120_000L, NOW - 1_000L, true, false, false, NOW));
        assertTrue(HlsService.shouldReapSession(
                HlsService.SessionState.ACTIVE,
                NOW - 120_000L, NOW - (IDLE_TTL + 1), true, false, false, NOW));
    }

    @Test
    void restartWindowIsProtected() {
        assertFalse(HlsService.shouldReapSession(
                HlsService.SessionState.ACTIVE,
                NOW - 120_000L, NOW - (IDLE_TTL + 1), false, false, true, NOW));
    }

    @Test
    void failedSessionIsReapedImmediately() {
        assertTrue(HlsService.shouldReapSession(
                HlsService.SessionState.FAILED,
                NOW - 1_000L, NOW, false, false, false, NOW));
    }
}
