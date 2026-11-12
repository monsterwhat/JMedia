package Services;

import Models.Music.Song;
import Models.Music.SongAnalysis;
import Models.Music.SongAnalysis.BeatInfo;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import Services.DjScoring;

/**
 * Calculates beat-aligned transitions between songs for DJ Mode.
 * 
 * Uses the EternalJukebox similarity graph principle cross-song:
 * beats at the same position in the bar (e.g., downbeat → downbeat)
 * sound similar across songs, especially when BPMs are close.
 * 
 * The algorithm:
 * 1. Finds the "exit window" — last N seconds of current song (N = crossfadeSeconds)
 * 2. Finds all strong beats (downbeats) in the exit window
 * 3. Finds matching entry beats in the next song (same beatInBar position)
 * 4. Scores each pair by beat alignment, spectral similarity, and BPM compatibility
 * 5. Returns the optimal {exitTime, entryTime} pair
 */
@ApplicationScoped
public class DjTransitionService {

    private static final Logger LOG = LoggerFactory.getLogger(DjTransitionService.class);

    // Minimum BPM difference ratio for considering songs mixable
    private static final double MAX_BPM_RATIO_DIFF = 0.15; // 15%

    // Minimum number of beats needed in exit window for meaningful matching
    private static final int MIN_EXIT_BEATS = 2;

    // Upper bound for any crossfade overlap. Longer fades compound the client's
    // hard-cut + slow fade-in into dead air; mobile DJ research recommends 400-700ms.
    private static final int DEFAULT_MAX_CROSSFADE = 2;
    /** Longer fades are only allowed when the incoming deck can be tempo-matched. */
    private static final int MATCHED_MAX_CROSSFADE = 6;
    private static final double MAX_TEMPO_NUDGE = 0.06;

    @Inject
    AudioAnalysisService audioAnalysisService;

    /** Result of a DJ transition calculation. */
    public static class DjTransition {
        private final double exitTime;      // Where to exit current song (seconds)
        private final double entryTime;     // Where to enter next song (seconds)
        private final int crossfadeSeconds; // Recommended crossfade duration
        private final double confidence;    // 0.0-1.0 how good this match is
        private final String reason;        // Human-readable explanation
        private final double rate;          // Playback rate for the incoming deck (1.0 = none)

        public DjTransition(double exitTime, double entryTime, int crossfadeSeconds, double confidence,
                            String reason, double rate) {
            this.exitTime = exitTime;
            this.entryTime = entryTime;
            this.crossfadeSeconds = crossfadeSeconds;
            this.confidence = confidence;
            this.reason = reason;
            this.rate = rate;
        }

        public double getExitTime() { return exitTime; }
        public double getEntryTime() { return entryTime; }
        public int getCrossfadeSeconds() { return crossfadeSeconds; }
        public double getConfidence() { return confidence; }
        public String getReason() { return reason; }
        public double getRate() { return rate; }

        @Override
        public String toString() {
            return String.format("DjTransition{exit=%.2fs, entry=%.2fs, crossfade=%ds, confidence=%.2f, rate=%.3f, reason='%s'}",
                exitTime, entryTime, crossfadeSeconds, confidence, rate, reason);
        }
    }

    /** Calculate the optimal beat-aligned transition between two songs. */
    @Transactional(jakarta.transaction.Transactional.TxType.REQUIRED)
    public DjTransition calculateTransition(Song currentSong, Song nextSong, int crossfadeSeconds) {
        return calculateTransition(currentSong, nextSong, crossfadeSeconds, null, null);
    }

    @Transactional(jakarta.transaction.Transactional.TxType.REQUIRED)
    public DjTransition calculateTransition(Song currentSong, Song nextSong, int crossfadeSeconds,
                                            Integer crossfadeOverride, String strictness) {
        if (currentSong == null || nextSong == null) {
            LOG.warn("Null song(s) provided for transition calculation");
            return null;
        }

        // Get analysis for both songs
        SongAnalysis currentAnalysis = audioAnalysisService.getAnalysis(currentSong.id);
        SongAnalysis nextAnalysis = audioAnalysisService.getAnalysis(nextSong.id);

        if (currentAnalysis == null || !currentAnalysis.isReady() || currentAnalysis.getAverageBpm() == null) {
            LOG.info("Current song {} not analyzed or has no BPM data, cannot calculate transition", currentSong.getTitle());
            return null;
        }

        if (nextAnalysis == null || !nextAnalysis.isReady() || nextAnalysis.getAverageBpm() == null) {
            LOG.info("Next song {} not analyzed or has no BPM data, cannot calculate transition", nextSong.getTitle());
            return null;
        }

        List<BeatInfo> currentBeats = currentAnalysis.getBeatMetadata();
        List<BeatInfo> nextBeats = nextAnalysis.getBeatMetadata();

        if (currentBeats.isEmpty() || nextBeats.isEmpty()) {
            LOG.warn("Beat metadata empty for one or both songs");
            return null;
        }

        double currentDuration = currentSong.getDurationSeconds();
        if (currentDuration <= 0) {
            LOG.warn("Current song has no duration");
            return null;
        }

        // Step 1: Find all potential exit beats restricted to the exit window [0.70, 0.92]
        List<BeatInfo> exitBeats = new ArrayList<>();
        for (BeatInfo beat : currentBeats) {
            double exitPosition = beat.getTime() / currentDuration;
            if (DjScoring.insideExitWindow(exitPosition)) {
                exitBeats.add(beat);
            }
        }

        if (exitBeats.size() < MIN_EXIT_BEATS) {
            LOG.info("Too few beats ({}) for song {}", exitBeats.size(), currentSong.getTitle());
            // Fall back: use last beat of current, first beat of next
            return createFallbackTransition(currentBeats, nextBeats, crossfadeSeconds, "Too few exit beats, using fallback", currentDuration);
        }

        // Step 2: Check BPM compatibility
        double currentBpm = currentAnalysis.getAverageBpm();
        double nextBpm = nextAnalysis.getAverageBpm();
        double bpmRatioDiff = Math.abs(currentBpm - nextBpm) / Math.max(currentBpm, 1.0);

        if (bpmRatioDiff > MAX_BPM_RATIO_DIFF) {
            LOG.info(String.format("BPM difference too large: %.1f vs %.1f (ratio diff: %.2f%%)",
                currentBpm, nextBpm, bpmRatioDiff * 100));
            // Still attempt but with lower confidence
        }

        // Step 3: Find optimal exit/entry beat pair
        TransitionCandidate bestCandidate = null;
        double bestScore = -1;

        // PRE-PARSE FEATURES ONCE (Massive performance boost)
        List<Map<String, Object>> currentFeatures = parseFeatures(currentAnalysis.getSegmentFeaturesJson());
        List<Map<String, Object>> nextFeatures = parseFeatures(nextAnalysis.getSegmentFeaturesJson());

        // LIMIT ENTRY WINDOW: Only look at the first 40% of the next song
        // This makes the search much faster and ensures we don't jump to the end of the next song.
        double nextDuration = nextSong.getDurationSeconds();
        double entryWindowEnd = nextDuration * 0.40;
        List<BeatInfo> limitedNextBeats = new ArrayList<>();
        for (BeatInfo beat : nextBeats) {
            if (beat.getTime() <= entryWindowEnd) {
                limitedNextBeats.add(beat);
            }
        }

        for (BeatInfo exitBeat : exitBeats) {
            // Depends only on the exit beat: compute once per row, not per pair.
            double exitStrength = computeMeanStrength(currentFeatures, exitBeat.getIndex(), exitBeat.getTime());
            // Find matching entry beats in next song with same beatInBar position
            for (BeatInfo entryBeat : limitedNextBeats) {
                // Primary filter: same beat-in-bar position (or compatible)
                if (!beatsCompatible(exitBeat, entryBeat)) {
                    continue;
                }
                
                // Score the pair
                double score = 0;

                // 1. Beat alignment score (how close to a typical phrase start)
                score += scoreBeatAlignment(exitBeat, entryBeat);

                // 2. Spectral similarity (timbre match)
                score += calculateSpectralSimilarityOptimized(exitBeat, entryBeat, currentFeatures, nextFeatures);

                // 3. BPM compatibility
                score += (1.0 - bpmRatioDiff) * 0.5;

                // 4. Energy/Volume continuity (local onset strength, pre-parsed features)
                double entryStrength = computeMeanStrength(nextFeatures, entryBeat.getIndex(), entryBeat.getTime());
                score += DjScoring.energyBonus(exitStrength, entryStrength);

                // 5. Harmonic compatibility (Camelot), penalty only, 0 when unknown
                score += DjScoring.keyBonus(currentSong.getMusicalKey(), currentSong.getKeyScale(),
                        nextSong.getMusicalKey(), nextSong.getKeyScale());

                // 6. Position scoring (prefer end of current, beginning of next)
                score += scorePosition(exitBeat, entryBeat, currentDuration, nextDuration);
                
                if (score > bestScore) {
                    bestScore = score;
                    bestCandidate = new TransitionCandidate(exitBeat.getTime(), entryBeat.getTime(), score);
                }
            }
        }

        // Step 4: If no compatible pair found, use fallback
        if (bestCandidate == null) {
            return createFallbackTransition(currentBeats, nextBeats, crossfadeSeconds, "No compatible beat pair found", currentDuration);
        }

        // Fade length: short by default, longer only when the tempo can be matched.
        int baseCrossfade;
        if (crossfadeOverride != null && crossfadeOverride >= 0) {
            baseCrossfade = crossfadeOverride;
        } else if (nextBpm > 120) {
            baseCrossfade = 1;
        } else {
            baseCrossfade = 2;
        }
        baseCrossfade = Math.min(Math.max(baseCrossfade, 1), MATCHED_MAX_CROSSFADE);

        double bpmRatio = currentBpm > 0 ? nextBpm / currentBpm : 1.0;
        double rate = 1.0;
        if (currentBpm > 0 && nextBpm > 0 && Math.abs(bpmRatio - 1.0) <= MAX_TEMPO_NUDGE) {
            rate = Math.min(1.0 + MAX_TEMPO_NUDGE, Math.max(1.0 - MAX_TEMPO_NUDGE, 1.0 / bpmRatio));
        }
        int adjustedCrossfade = rate != 1.0
                ? baseCrossfade
                : Math.min(baseCrossfade, DEFAULT_MAX_CROSSFADE);

        // Clamp the chosen exit so fade doesn't run past the end of the file
        double clampedExitTime = DjScoring.clampExit(bestCandidate.exitTime, currentDuration, adjustedCrossfade);

        // Step 5: Build result - use DjScoring for confidence normalization
        double confidence = DjScoring.normalizeConfidence(bestScore, bpmRatioDiff);

        String reason = buildReasonString(exitBeats, limitedNextBeats, bestCandidate, currentBpm, nextBpm);

        DjTransition transition = new DjTransition(
            clampedExitTime,
            bestCandidate.entryTime,
            adjustedCrossfade,
            confidence,
            reason,
            rate
        );

        if ("HIGH".equalsIgnoreCase(strictness) && confidence < 0.4) {
            LOG.info(String.format("DJ strictness HIGH rejected low-confidence transition (confidence: %.2f)", confidence));
            return null;
        }

        LOG.info(String.format("Calculated transition: %s → %s (confidence: %.2f)", 
            currentSong.getTitle(), nextSong.getTitle(), confidence));

        return transition;
    }

    /** Parse JSON features once. */
    private List<Map<String, Object>> parseFeatures(String json) {
        if (json == null || json.isEmpty()) return new ArrayList<>();
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            return mapper.readValue(json, new com.fasterxml.jackson.core.type.TypeReference<List<Map<String, Object>>>() {});
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    /** Optimized spectral similarity using pre-parsed features. */
    private double calculateSpectralSimilarityOptimized(BeatInfo exitBeat, BeatInfo entryBeat,
                                                       List<Map<String, Object>> currentFeatures,
                                                       List<Map<String, Object>> nextFeatures) {
        if (exitBeat.getIndex() >= currentFeatures.size() || entryBeat.getIndex() >= nextFeatures.size()) {
            return 0.5;
        }

        double[] exitSpectral = extractSpectralVector(currentFeatures.get(exitBeat.getIndex()));
        double[] entrySpectral = extractSpectralVector(nextFeatures.get(entryBeat.getIndex()));

        if (exitSpectral == null || entrySpectral == null) {
            return 0.5;
        }

        return cosineSimilarity(exitSpectral, entrySpectral);
    }

    /** Score how well a pair of beats align based on musical structure.
     *  Uses DjScoring for phrase and downbeat bonuses.
     */
    private double scoreBeatAlignment(BeatInfo exitBeat, BeatInfo entryBeat) {
        double score = 0;

        // Downbeat bonus
        score += DjScoring.downbeatBonus(exitBeat.getBeatInBar());
        score += DjScoring.downbeatBonus(entryBeat.getBeatInBar());

        // Phrase bonus (0-based bar numbers; BeatInfo.barNumber is 1-based)
        score += DjScoring.phraseBonus(exitBeat.getBarNumber() - 1);
        score += DjScoring.phraseBonus(entryBeat.getBarNumber() - 1);

        // Bonus for matching relative position in bar
        if (exitBeat.getBeatInBar() == entryBeat.getBeatInBar()) score += 0.1;

        return score;
    }

    /**
     * Mean onset strength within +/-2 s of a beat, scanned over a bounded window of
     * the already-parsed features. No JSON parse and no full-list scan here: this
     * runs once per exit beat and once per entry beat inside a nested loop.
     */
    private static double computeMeanStrength(List<Map<String, Object>> features, int index, double beatTime) {
        if (features == null || features.isEmpty()) {
            return 0.5;
        }
        int center = Math.min(Math.max(index, 0), features.size() - 1);
        int from = Math.max(0, center - 8);
        int to = Math.min(features.size() - 1, center + 8);
        double sum = 0;
        int count = 0;
        for (int i = from; i <= to; i++) {
            Map<String, Object> feature = features.get(i);
            if (feature == null) {
                continue;
            }
            Object timeObj = feature.get("time");
            double time = timeObj instanceof Number ? ((Number) timeObj).doubleValue() : beatTime;
            if (Math.abs(time - beatTime) > 2.0) {
                continue;
            }
            Object strengthObj = feature.get("strength");
            if (strengthObj instanceof Number) {
                sum += ((Number) strengthObj).doubleValue();
                count++;
            }
        }
        return count > 0 ? sum / count : 0.5;
    }

    /** Score how well a pair of beats align based on musical structure.
     *  Uses DjScoring for exit/entry position scoring.
     */
    private double scorePosition(BeatInfo exitBeat, BeatInfo entryBeat, double currentDur, double nextDur) {
        double score = 0;

        // Entry scoring using DjScoring
        double entryTime = entryBeat.getTime();
        double entryPosition = entryTime / nextDur;
        score += DjScoring.entryPositionScore(entryTime, entryPosition);

        // Exit scoring using DjScoring
        double exitPosition = exitBeat.getTime() / currentDur;
        score += DjScoring.exitPositionScore(exitPosition);

        return score;
    }

    /** Check if two beats are compatible for matching.
     *  Beats are compatible if they have the same beatInBar position,
     *  or both are downbeats, or both are strong beats (3 in 4/4).
     */
    private boolean beatsCompatible(BeatInfo exitBeat, BeatInfo entryBeat) {
        // Exact match
        if (exitBeat.getBeatInBar() == entryBeat.getBeatInBar()) {
            return true;
        }
        // Both downbeats
        if (exitBeat.getBeatInBar() == 1 && entryBeat.getBeatInBar() == 1) {
            return true;
        }
        // Both on strong beats (3 in 4/4)
        if (exitBeat.getBeatInBar() == 3 && entryBeat.getBeatInBar() == 3) {
            return true;
        }
        return false;
    }

    /** Extract spectral vector from beat feature map. */
    private double[] extractSpectralVector(Map<String, Object> featureMap) {
        Object spectralObj = featureMap.get("spectral");
        if (spectralObj == null) {
            return null;
        }

        if (spectralObj instanceof List) {
            List<?> spectralList = (List<?>) spectralObj;
            double[] vector = new double[spectralList.size()];
            for (int i = 0; i < spectralList.size(); i++) {
                Object val = spectralList.get(i);
                if (val instanceof Number) {
                    vector[i] = ((Number) val).doubleValue();
                }
            }
            return vector;
        }

        return null;
    }

    /** Calculate cosine similarity between two vectors. */
    private double cosineSimilarity(double[] a, double[] b) {
        if (a.length != b.length || a.length == 0) {
            return 0;
        }

        double dotProduct = 0;
        double normA = 0;
        double normB = 0;

        for (int i = 0; i < a.length; i++) {
            dotProduct += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }

        double denominator = Math.sqrt(normA) * Math.sqrt(normB);
        if (denominator == 0) {
            return 0;
        }

        // Normalize to 0-1 range (cosine similarity is -1 to 1)
        return (dotProduct / denominator + 1) / 2;
    }

    /** Create a fallback transition when optimal matching fails.
     *  Uses the last beat within the exit window → first downbeat of next song.
     *  Clamps exit time and fade duration per Gate B specifications.
     */
    private DjTransition createFallbackTransition(List<BeatInfo> currentBeats, List<BeatInfo> nextBeats,
                                                  int crossfadeSeconds, String reason, double currentDuration) {
        if (currentBeats.isEmpty() || nextBeats.isEmpty()) {
            LOG.warn("Cannot create fallback: empty beat lists");
            return null;
        }

        // Choose the LAST beat with position <= EXIT_WINDOW_END (0.92), not blindly the last beat
        BeatInfo exitBeat = null;
        for (BeatInfo beat : currentBeats) {
            double exitPosition = beat.getTime() / currentDuration;
            if (DjScoring.insideExitWindow(exitPosition)) {
                exitBeat = beat; // Keep the last one that fits
            }
        }
        // Fallback: if no beat in exit window, use the last beat whose position is <= 0.92
        if (exitBeat == null) {
            for (BeatInfo beat : currentBeats) {
                if (beat.getTime() <= currentDuration * DjScoring.EXIT_WINDOW_END) {
                    exitBeat = beat;
                }
            }
        }
        // Absolute fallback: use the very last beat
        if (exitBeat == null) {
            exitBeat = currentBeats.get(currentBeats.size() - 1);
        }

        // Clamp exit time so fade doesn't run past the end of the file
        double exitTime = DjScoring.clampExit(exitBeat.getTime(), currentDuration, crossfadeSeconds);

        // Clamp fade to 1..6 seconds per gate specification
        int fadeSeconds = Math.max(1, Math.min(6, crossfadeSeconds));

        // Try to find first downbeat in next song (preferably a downbeat)
        BeatInfo firstBeat = nextBeats.get(0);
        for (BeatInfo beat : nextBeats) {
            if (beat.getBeatInBar() == 1) {
                firstBeat = beat;
                break;
            }
        }

        // Enforce minimum entry time to avoid silence/intro
        if (firstBeat.getTime() < 2.0) {
            for (BeatInfo beat : nextBeats) {
                if (beat.getTime() >= 2.0) {
                    firstBeat = beat;
                    break;
                }
            }
            // Absolute floor: if no beat >= 2s, use the first beat at >= 2s
            if (firstBeat.getTime() < 2.0) {
                for (BeatInfo beat : nextBeats) {
                    if (beat.getTime() >= 2.0) {
                        firstBeat = beat;
                        break;
                    }
                }
            }
            // Last resort: use the first beat in the entry window
            if (firstBeat.getTime() < 2.0 && !nextBeats.isEmpty()) {
                firstBeat = nextBeats.get(0);
            }
        }

        LOG.info(String.format("Fallback transition: exit at %.2fs → entry at %.2fs (%s)", 
            exitTime, firstBeat.getTime(), reason));

        return new DjTransition(
            exitTime,
            firstBeat.getTime(),
            fadeSeconds,
            0.3, // Low confidence for fallback
            reason,
            1.0  // no tempo nudge on the fallback path
        );
    }

    /** Normalize raw score to 0-1 confidence range (delegated to DjScoring). */
    // normalizeScore removed - use DjScoring.normalizeConfidence instead

    /** Build human-readable reason string for the transition. */
    private String buildReasonString(List<BeatInfo> exitBeats, List<BeatInfo> nextBeats,
                                       TransitionCandidate best, double currentBpm, double nextBpm) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("BPM: %.0f → %.0f", currentBpm, nextBpm));

        // Find the beat-in-bar for the best match
        for (BeatInfo exitBeat : exitBeats) {
            if (Math.abs(exitBeat.getTime() - best.exitTime) < 0.01) {
                if (exitBeat.getBeatInBar() == 1) {
                    sb.append(", downbeat match");
                } else {
                    sb.append(String.format(", beat %d match", exitBeat.getBeatInBar()));
                }
                break;
            }
        }

        return sb.toString();
    }

    /** Simple candidate holder for transition scoring. */
    private static class TransitionCandidate {
        final double exitTime;
        final double entryTime;
        final double score;

        TransitionCandidate(double exitTime, double entryTime, double score) {
            this.exitTime = exitTime;
            this.entryTime = entryTime;
            this.score = score;
        }
    }
}