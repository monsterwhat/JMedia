package Services;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 3 one-shot backfill tests (offline, plain JUnit, no DB): the pure
 * per-row decision {@link VideoService#absoluteBackfillTarget}, plus the
 * idempotency argument — a remapped row recomputes to its stored values on
 * the next pass, so the decision is null and nothing is rewritten.
 */
public class AbsoluteBackfillTest {

    @Test
    void appliesWhenRecomputedDiffers() {
        // Legacy absolute S02E26 remapped to season-relative S02E01.
        int[] target = VideoService.absoluteBackfillTarget(2, 26, 2, 1, false, false);
        assertNotNull(target, "differing recomputed values must produce a write");
        assertArrayEquals(new int[]{2, 1}, target);
    }

    @Test
    void noOpWhenValuesAlreadyMatch() {
        assertNull(VideoService.absoluteBackfillTarget(2, 1, 2, 1, false, false),
                "identical values must not write (second-pass no-op)");
        assertNull(VideoService.absoluteBackfillTarget(1, 5, 1, 5, false, false));
    }

    @Test
    void neverOverwritesManuallyEditedRows() {
        assertNull(VideoService.absoluteBackfillTarget(2, 26, 2, 1, true, false),
                "season manually edited must never be overwritten");
        assertNull(VideoService.absoluteBackfillTarget(2, 26, 2, 1, false, true),
                "episode manually edited must never be overwritten");
        assertNull(VideoService.absoluteBackfillTarget(2, 26, 2, 1, true, true));
    }

    @Test
    void skipsNulls() {
        assertNull(VideoService.absoluteBackfillTarget(null, 26, 2, 1, false, false));
        assertNull(VideoService.absoluteBackfillTarget(2, null, 2, 1, false, false));
        assertNull(VideoService.absoluteBackfillTarget(2, 26, null, 1, false, false));
        assertNull(VideoService.absoluteBackfillTarget(2, 26, 2, null, false, false));
    }

    @Test
    void secondPassIsANoOpByConstruction() {
        // First pass remaps absolute 26 (S1=25) to S02E01...
        Map<Integer, Integer> lengths = new HashMap<>(Map.of(1, 25, 2, 25));
        int[] remapped = SmartNamingService.resolveAbsoluteNumber(2, 26, lengths);
        assertNotNull(remapped);
        // ...and the remapped values resolve to null (already relative), so a
        // second backfill pass recomputes identical values and writes nothing.
        assertNull(SmartNamingService.resolveAbsoluteNumber(2, remapped[1], lengths),
                "remapped row must resolve to null on the next pass");
        assertNull(VideoService.absoluteBackfillTarget(remapped[0], remapped[1],
                remapped[0], remapped[1], false, false),
                "stored == recomputed must not write");
    }
}
