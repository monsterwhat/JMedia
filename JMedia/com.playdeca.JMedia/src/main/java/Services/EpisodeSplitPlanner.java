package Services;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure decision logic for splitting a file that holds two episodes.
 *
 * <p>A real episode break in a concatenated file shows three things at once:
 * a fade to black, an audio gap overlapping that black, and a position near the
 * middle of the file. Any one of them alone is not enough — a single episode
 * also fades to black, and a quiet scene also produces silence. Requiring all
 * three is what keeps this from cutting a normal episode in half.
 *
 * <p>No I/O here: ffmpeg output is parsed by {@link EpisodeSplitService} and
 * handed over as plain ranges, so every decision below is unit-testable.
 */
public final class EpisodeSplitPlanner {

    /** Only look for the break in this fraction of the file. */
    public static final double WINDOW_START_FRACTION = 0.30;
    public static final double WINDOW_END_FRACTION = 0.65;

    /** Shortest black stretch worth considering (filters scene-transition noise). */
    public static final double MIN_BLACK_SECONDS = 0.50;

    /** Minimum audio-gap overlap with a black run. */
    public static final double MIN_SILENCE_OVERLAP_SECONDS = 0.20;

    /** Weight of the midpoint prior, in score units per second of distance. */
    public static final double MIDPOINT_PENALTY = 0.02;

    /** Do not snap to a keyframe further back than this. */
    public static final double MAX_KEYFRAME_GAP_SECONDS = 8.0;

    /** Nearest keyframe may sit on either side of the boundary, up to this far. */
    public static final double NEAREST_KEYFRAME_GAP_SECONDS = 3.0;

    /** A next-episode preview between the two episodes is this long, roughly. */
    public static final double MIN_PREVIEW_SECONDS = 10.0;
    public static final double MAX_PREVIEW_SECONDS = 180.0;

    /** Ignore anything this close to the first cut: it is part of the same fade. */
    public static final double TRAILING_SEARCH_GAP_SECONDS = 15.0;

    private static final Pattern RANGE_PATTERN = Pattern.compile(
            "(?i)^(.*?[sS]\\d{1,2}[\\s\\._-]*[eE])(\\d{1,3})(\\s*[-–]\\s*[eE]?)(\\d{1,3})(.*)$");

    private EpisodeSplitPlanner() {
    }

    /** Half-open time range in seconds, offset from the start of the file. */
    public record TimeRange(double start, double end) {
        public double length() {
            return Math.max(0, end - start);
        }

        public double overlap(TimeRange other) {
            return Math.max(0, Math.min(end, other.end()) - Math.max(start, other.start()));
        }
    }

    /** A candidate break: the end of the black run, plus what supported it. */
    public record Candidate(double boundarySeconds, double blackSeconds, double silenceSeconds,
                            double midpointDistanceSeconds, double score) {
    }

    /** The episode numbers the filename declares. */
    public record EpisodeRange(int first, int last) {
    }

    /**
     * Reads the {@code SxxEaa-bb} range out of a filename. Returns null when the
     * name declares a single episode, which means the file must not be split.
     */
    static EpisodeRange episodeRange(String filename) {
        if (filename == null || filename.isBlank()) return null;
        Matcher m = RANGE_PATTERN.matcher(filename);
        if (!m.matches()) return null;
        try {
            int first = Integer.parseInt(m.group(2));
            int last = Integer.parseInt(m.group(4));
            if (last <= first) return null;
            return new EpisodeRange(first, last);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Picks the best break. A black run qualifies only when it is long enough,
     * sits inside the search window, and overlaps an audio gap. Ties and near
     * ties are settled by distance from the midpoint.
     */
    static Candidate chooseCandidate(List<TimeRange> blacks, List<TimeRange> silences, double duration) {
        if (blacks == null || silences == null || duration <= 0) return null;
        double midpoint = duration / 2.0;
        double windowStart = duration * WINDOW_START_FRACTION;
        double windowEnd = duration * WINDOW_END_FRACTION;
        Candidate best = null;
        for (TimeRange black : blacks) {
            if (black.length() < MIN_BLACK_SECONDS) continue;
            if (black.start() < windowStart || black.end() > windowEnd) continue;
            double silence = 0;
            for (TimeRange gap : silences) {
                silence += black.overlap(gap);
            }
            if (silence < MIN_SILENCE_OVERLAP_SECONDS) continue;
            double distance = Math.abs(black.end() - midpoint);
            double score = black.length() + silence - MIDPOINT_PENALTY * distance;
            Candidate candidate = new Candidate(black.end(), black.length(), silence, distance, score);
            if (best == null || candidate.score() > best.score()) {
                best = candidate;
            }
        }
        return best;
    }

    /**
     * Finds a second break shortly after the first, which marks the end of a
     * next-episode preview. Returns null when there is only one break (a plain
     * two-in-one file). Nearest qualifying run wins.
     */
    static Candidate chooseTrailingCandidate(List<TimeRange> blacks, List<TimeRange> silences,
                                             double duration, double afterSeconds) {
        if (blacks == null || silences == null || duration <= 0 || afterSeconds <= 0) return null;
        double windowStart = afterSeconds + TRAILING_SEARCH_GAP_SECONDS;
        double windowEnd = duration * 0.80;
        Candidate best = null;
        for (TimeRange black : blacks) {
            if (black.length() < MIN_BLACK_SECONDS) continue;
            if (black.start() < windowStart || black.end() > windowEnd) continue;
            double gap = black.end() - afterSeconds;
            if (gap < MIN_PREVIEW_SECONDS || gap > MAX_PREVIEW_SECONDS) continue;
            double silence = 0;
            for (TimeRange silenceRange : silences) {
                silence += black.overlap(silenceRange);
            }
            if (silence < MIN_SILENCE_OVERLAP_SECONDS) continue;
            Candidate candidate = new Candidate(black.end(), black.length(), silence, gap, -gap);
            if (best == null || candidate.score() > best.score()) {
                best = candidate;
            }
        }
        return best;
    }

    /**
     * Snaps to whichever keyframe is closest, before or after the boundary.
     * Used for the trailing cut of a preview: the preview is discarded, so
     * starting episode two a few frames after the fade costs nothing, while
     * snapping back would leave part of the preview on its head.
     */
    static double snapToNearestKeyframe(double boundary, List<Double> keyframes) {
        if (keyframes == null || keyframes.isEmpty()) return boundary;
        double best = -1;
        double bestDistance = Double.MAX_VALUE;
        for (double keyframe : keyframes) {
            double distance = Math.abs(keyframe - boundary);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = keyframe;
            }
        }
        if (best < 0 || bestDistance > NEAREST_KEYFRAME_GAP_SECONDS) {
            return snapToKeyframe(boundary, keyframes);
        }
        return best;
    }

    /**
     * A stream-copy cut must start on a keyframe. The last keyframe at or before
     * the boundary keeps the cut inside the black run: the second part opens on
     * black instead of skipping the first frames of the episode.
     */
    static double snapToKeyframe(double boundary, List<Double> keyframes) {
        if (keyframes == null || keyframes.isEmpty()) return boundary;
        double best = -1;
        for (double keyframe : keyframes) {
            if (keyframe <= boundary + 0.05 && keyframe > best) {
                best = keyframe;
            }
        }
        if (best < 0 || boundary - best > MAX_KEYFRAME_GAP_SECONDS) {
            return boundary;
        }
        return best;
    }

    /**
     * Builds the two output filenames by replacing the range with each episode
     * number, preserving the original zero padding and everything after it.
     */
    static String[] partNames(String filename, EpisodeRange range) {
        Matcher m = RANGE_PATTERN.matcher(filename);
        if (!m.matches() || range == null) return null;
        String firstRaw = m.group(2);
        int width = Math.max(2, firstRaw.length());
        String first = String.format("%0" + width + "d", range.first());
        String last = String.format("%0" + width + "d", range.last());
        return new String[]{
                m.group(1) + first + m.group(5),
                m.group(1) + last + m.group(5)
        };
    }
}
