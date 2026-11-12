package Services;

/**
 * Pure scoring rules for DJ transitions.
 *
 * <p>No CDI, database, HTTP or framework imports: every function is a plain
 * calculation so the behaviour can be pinned by unit tests. {@link DjTransitionService}
 * supplies the beats and features and turns the result into a plan.
 */
public final class DjScoring {

    /**
     * Highest raw score the pair scorer can produce:
     * alignment 1.00 + spectral 1.00 + bpm 0.50 + energy 0.20 + position 0.65.
     * The previous code divided by 1.1, which saturated every decent pair at 1.0
     * and made strictness meaningless.
     */
    public static final double MAX_RAW_SCORE = 3.50;

    /** Exit beats are only considered in this fraction of the current song. */
    public static final double EXIT_WINDOW_START = 0.70;
    public static final double EXIT_WINDOW_END = 0.92;

    /** Never let a fade run into the last fraction of a second of the file. */
    public static final double MIN_TAIL_SECONDS = 0.3;

    private DjScoring() {
    }

    /** Phrase bonus for a 0-based bar index (bars 0, 4, 8... and 0, 8, 16...). */
    public static double phraseBonus(int barNumber) {
        if (barNumber < 0) {
            return 0.0;
        }
        double score = 0.0;
        if (barNumber % 4 == 0) {
            score += 0.15;
        }
        if (barNumber % 8 == 0) {
            score += 0.10;
        }
        return score;
    }

    /** Downbeat bonus for a 4/4 beat position (1-based). */
    public static double downbeatBonus(int beatInBar) {
        return beatInBar == 1 ? 0.2 : 0.0;
    }

    /** Where in the outgoing track the fade starts. */
    public static double exitPositionScore(double exitPosition) {
        if (exitPosition >= 0.78 && exitPosition <= 0.92) {
            return 0.45;
        }
        if (exitPosition >= 0.70) {
            return 0.20;
        }
        if (exitPosition >= 0.55) {
            return -0.15;
        }
        return -0.60;
    }

    /** Where in the incoming track it starts playing. */
    public static double entryPositionScore(double entrySeconds, double entryPosition) {
        if (entrySeconds < 2.0) {
            return -0.5;
        }
        if (entrySeconds < 5.0) {
            return -0.2;
        }
        if (entryPosition > 0.05 && entryPosition < 0.40) {
            return 0.2;
        }
        if (entryPosition >= 0.40 && entryPosition < 0.55) {
            return 0.05;
        }
        return 0.0;
    }

    /** Level continuity: two similar-energy beats score higher. */
    public static double energyBonus(double exitStrength, double entryStrength) {
        double diff = Math.abs(exitStrength - entryStrength);
        return Math.max(0.0, 0.2 - diff * 0.2);
    }

    /**
     * Turns a raw pair score into a 0-1 confidence, then applies the BPM penalty.
     * A 30 % tempo gap halves confidence; the penalty caps at 80 %.
     */
    public static double normalizeConfidence(double rawScore, double bpmRatioDiff) {
        double base = Math.min(Math.max(rawScore / MAX_RAW_SCORE, 0.0), 1.0);
        double penalty = 1.0 - Math.min(Math.max(bpmRatioDiff, 0.0) / 0.30, 0.8);
        return Math.min(Math.max(base * penalty, 0.0), 1.0);
    }

    /** Keeps the fade from running past the end of the file. */
    public static double clampExit(double exitSeconds, double durationSeconds, int fadeSeconds) {
        double latest = durationSeconds - fadeSeconds - MIN_TAIL_SECONDS;
        if (latest < 0) {
            latest = 0;
        }
        return Math.min(exitSeconds, latest);
    }

    /** True when a beat's position is inside the exit window. */
    public static boolean insideExitWindow(double exitPosition) {
        return exitPosition >= EXIT_WINDOW_START && exitPosition <= EXIT_WINDOW_END;
    }

    /** Camelot wheel: major keys. */
    private static final java.util.Map<String, Integer> CAMELOT_MAJOR = java.util.Map.ofEntries(
            java.util.Map.entry("C", 8), java.util.Map.entry("C#", 3), java.util.Map.entry("DB", 3),
            java.util.Map.entry("D", 10), java.util.Map.entry("D#", 5), java.util.Map.entry("EB", 5),
            java.util.Map.entry("E", 12), java.util.Map.entry("F", 7), java.util.Map.entry("F#", 2),
            java.util.Map.entry("GB", 2), java.util.Map.entry("G", 9), java.util.Map.entry("G#", 4),
            java.util.Map.entry("AB", 4), java.util.Map.entry("A", 11), java.util.Map.entry("A#", 6),
            java.util.Map.entry("BB", 6), java.util.Map.entry("B", 1));

    /** Camelot wheel: minor keys. */
    private static final java.util.Map<String, Integer> CAMELOT_MINOR = java.util.Map.ofEntries(
            java.util.Map.entry("A", 8), java.util.Map.entry("A#", 3), java.util.Map.entry("BB", 3),
            java.util.Map.entry("B", 10), java.util.Map.entry("C", 5), java.util.Map.entry("C#", 12),
            java.util.Map.entry("DB", 12), java.util.Map.entry("D", 7), java.util.Map.entry("D#", 2),
            java.util.Map.entry("EB", 2), java.util.Map.entry("E", 9), java.util.Map.entry("F", 4),
            java.util.Map.entry("F#", 11), java.util.Map.entry("GB", 11), java.util.Map.entry("G", 6),
            java.util.Map.entry("G#", 1), java.util.Map.entry("AB", 1));

    /**
     * Harmonic-mixing bonus, 0 to 0.15. Same key = 0.15, neighbouring Camelot
     * number or relative major/minor = 0.125, one step further = 0.10, and so on.
     * Returns 0 when either key is unknown so the rest of the score decides.
     */
    public static double keyBonus(String keyA, String scaleA, String keyB, String scaleB) {
        Integer numberA = camelotNumber(keyA, scaleA);
        Integer numberB = camelotNumber(keyB, scaleB);
        if (numberA == null || numberB == null) {
            return 0.0;
        }
        boolean sameLetter = isMinor(scaleA) == isMinor(scaleB);
        int diff = Math.abs(numberA - numberB);
        int circular = Math.min(diff, 12 - diff);
        int distance = sameLetter ? circular : Math.min(6, circular + 1);
        return 0.15 * Math.max(0.0, 1.0 - distance / 6.0);
    }

    static Integer camelotNumber(String key, String scale) {
        if (key == null || key.isBlank() || scale == null || scale.isBlank()) {
            return null;
        }
        String normalized = key.trim().toUpperCase(java.util.Locale.ROOT)
                .replace('♯', '#')
                .replace('♭', 'b')
                .replace(" ", "");
        return isMinor(scale) ? CAMELOT_MINOR.get(normalized) : CAMELOT_MAJOR.get(normalized);
    }

    private static boolean isMinor(String scale) {
        return scale != null && scale.trim().toLowerCase(java.util.Locale.ROOT).startsWith("min");
    }
}
