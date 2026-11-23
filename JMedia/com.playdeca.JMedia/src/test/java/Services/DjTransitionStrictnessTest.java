package Services;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Strictness applies to every planned transition, including the fallback plan
 * (fixed 0.3 confidence), which used to return before the HIGH check.
 */
class DjTransitionStrictnessTest {

    private static DjTransitionService.DjTransition plan(double confidence) {
        return new DjTransitionService.DjTransition(100, 10, 2, confidence, "test", 1.0);
    }

    @Test
    void highRejectsTheFallbackConfidence() {
        assertFalse(DjTransitionService.passesStrictness(plan(0.3), "HIGH"));
        assertFalse(DjTransitionService.passesStrictness(plan(0.3), "high"));
    }

    @Test
    void highAcceptsConfidentPlans() {
        assertTrue(DjTransitionService.passesStrictness(plan(0.4), "HIGH"));
        assertTrue(DjTransitionService.passesStrictness(plan(0.9), "HIGH"));
    }

    @Test
    void otherLevelsAcceptEverything() {
        assertTrue(DjTransitionService.passesStrictness(plan(0.3), "MEDIUM"));
        assertTrue(DjTransitionService.passesStrictness(plan(0.1), "LOW"));
        assertTrue(DjTransitionService.passesStrictness(plan(0.1), null));
    }

    @Test
    void noPlanIsNotAStrictnessFailure() {
        assertTrue(DjTransitionService.passesStrictness(null, "HIGH"));
    }
}
