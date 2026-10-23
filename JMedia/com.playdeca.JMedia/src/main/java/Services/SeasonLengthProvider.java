package Services;

import java.util.Map;

/**
 * Supplies per-season episode counts for a show, used to translate
 * absolute (series-wide) episode numbers into season-relative ones.
 *
 * <p>Phase 2 provides the metadata-backed implementation. Returning
 * {@code null} or an empty map means "unknown" — callers must keep
 * the parsed number untouched in that case.
 *
 * <p>Keys are 1-based season numbers, values are episode counts.
 * The map must cover seasons contiguously starting at 1; consumers
 * refuse to guess when seasons are missing.
 */
public interface SeasonLengthProvider {

    /**
     * @param showKey show identifier as parsed from the filename or folder
     *                (unnormalized; implementations normalize internally)
     * @return season -&gt; episode count, or null/empty when unknown
     */
    Map<Integer, Integer> getSeasonLengths(String showKey);
}
