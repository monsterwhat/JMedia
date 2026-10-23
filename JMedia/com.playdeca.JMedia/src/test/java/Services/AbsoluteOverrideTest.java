package Services;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 4b per-show override tests (offline, plain JUnit, no DB): JSON
 * validation of the {@code absoluteSeasonLengths} override shape and the
 * pure precedence selector (override beats the enriched column only when
 * forced on and valid; everything else falls through).
 */
public class AbsoluteOverrideTest {

    // ── Override JSON validation ─────────────────────────────────────

    @Test
    void validOverrideParses() {
        Map<Integer, Integer> parsed =
                MetadataSeasonLengthProvider.parseAndValidateOverride("{\"1\":25,\"2\":25}");
        assertEquals(Map.of(1, 25, 2, 25), parsed);
    }

    @Test
    void unorderedKeysStillValidate() {
        Map<Integer, Integer> parsed =
                MetadataSeasonLengthProvider.parseAndValidateOverride("{\"2\":25,\"1\":25}");
        assertEquals(Map.of(1, 25, 2, 25), parsed);
    }

    @Test
    void gappedSeasonsAreInvalid() {
        assertNull(MetadataSeasonLengthProvider.parseAndValidateOverride("{\"1\":25,\"3\":10}"),
                "gap at season 2 must be rejected");
        assertNull(MetadataSeasonLengthProvider.parseAndValidateOverride("{\"2\":25}"),
                "missing season 1 must be rejected");
    }

    @Test
    void malformedAndEmptyOverridesAreInvalid() {
        assertNull(MetadataSeasonLengthProvider.parseAndValidateOverride(null));
        assertNull(MetadataSeasonLengthProvider.parseAndValidateOverride("  "));
        assertNull(MetadataSeasonLengthProvider.parseAndValidateOverride("not-json{{"));
        assertNull(MetadataSeasonLengthProvider.parseAndValidateOverride("{}"),
                "empty object carries no lengths");
        assertNull(MetadataSeasonLengthProvider.parseAndValidateOverride("{\"1\":0,\"2\":25}"),
                "non-positive counts must be rejected");
        assertNull(MetadataSeasonLengthProvider.parseAndValidateOverride("{\"x\":25}"),
                "non-numeric seasons must be rejected");
    }

    // ── Precedence ───────────────────────────────────────────────────

    @Test
    void forcedValidOverrideBeatsEnriched() {
        Map<Integer, Integer> selected = MetadataSeasonLengthProvider.selectLengths(
                "{\"1\":13,\"2\":13}", Boolean.TRUE, "{\"1\":25,\"2\":25}");
        assertEquals(Map.of(1, 13, 2, 13), selected,
                "forced-on valid override must win over the enriched column");
    }

    @Test
    void unforcedOverrideFallsThroughToEnriched() {
        Map<Integer, Integer> selected = MetadataSeasonLengthProvider.selectLengths(
                "{\"1\":13,\"2\":13}", Boolean.FALSE, "{\"1\":25,\"2\":25}");
        assertEquals(Map.of(1, 25, 2, 25), selected,
                "override without the force flag must be ignored (default-off)");
        Map<Integer, Integer> nullFlag = MetadataSeasonLengthProvider.selectLengths(
                "{\"1\":13,\"2\":13}", null, "{\"1\":25,\"2\":25}");
        assertEquals(Map.of(1, 25, 2, 25), nullFlag,
                "null flag behaves as false");
    }

    @Test
    void invalidForcedOverrideFallsThroughWithWarning() {
        Map<Integer, Integer> selected = MetadataSeasonLengthProvider.selectLengths(
                "{\"1\":25,\"3\":10}", Boolean.TRUE, "{\"1\":25,\"2\":25}");
        assertEquals(Map.of(1, 25, 2, 25), selected,
                "gapped override must fall through to the enriched column, never throw");
    }

    @Test
    void nullsYieldNull() {
        assertNull(MetadataSeasonLengthProvider.selectLengths(null, Boolean.TRUE, null));
        assertNull(MetadataSeasonLengthProvider.selectLengths(null, null, "  "));
        assertNull(MetadataSeasonLengthProvider.selectLengths("{\"1\":25}", null, null),
                "unforced override alone yields nothing (default-off)");
    }
}
