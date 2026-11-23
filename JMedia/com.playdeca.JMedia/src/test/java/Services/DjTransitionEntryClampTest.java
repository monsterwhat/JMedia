package Services;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class DjTransitionEntryClampTest {

    @Test
    void fallbackEntryUsesIncomingTrackDuration() {
        assertEquals(36.8, DjTransitionService.clampFallbackEntryTime(50.0, 40.0), 0.0001);
        assertEquals(45.0, DjTransitionService.clampFallbackEntryTime(60.0, 200.0));
    }

    @Test
    void invalidEntryCannotBecomeNegative() {
        assertEquals(0.0, DjTransitionService.clampFallbackEntryTime(-3.0, 100.0));
    }
}
