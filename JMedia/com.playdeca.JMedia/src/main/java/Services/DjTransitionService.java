package Services;

import Models.Song;
import Models.SongAnalysis;
import Models.SongAnalysis.BeatInfo;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

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

    @Inject
    AudioAnalysisService audioAnalysisService;

    /**
     * Result of a DJ transition calculation.
     */
    public static class DjTransition {
        private final double exitTime;      // Where to exit current song (seconds)
        private final double entryTime;     // Where to enter next song (seconds)
        private final int crossfadeSeconds; // Recommended crossfade duration
        private final double confidence;    // 0.0-1.0 how good this match is
        private final String reason;        // Human-readable explanation

        public DjTransition(double exitTime, double entryTime, int crossfadeSeconds, double confidence, String reason) {
            this.exitTime = exitTime;
            this.entryTime = entryTime;
            this.crossfadeSeconds = crossfadeSeconds;
            this.confidence = confidence;
            this.reason = reason;
        }

        public double getExitTime() { return exitTime; }
        public double getEntryTime() { return entryTime; }
        public int getCrossfadeSeconds() { return crossfadeSeconds; }
        public double getConfidence() { return confidence; }
        public String getReason() { return reason; }

        @Override
        public String toString() {
            return String.format("DjTransition{exit=%.2fs, entry=%.2fs, crossfade=%ds, confidence=%.2f, reason='%s'}",
                exitTime, entryTime, crossfadeSeconds, confidence, reason);
        }
    }

    /**
     * Calculate the optimal beat-aligned transition between two songs.
     * 
     * @param currentSong     The song currently playing
     * @param nextSong        The song to transition into
     * @param crossfadeSeconds Duration of the crossfade in seconds
     * @return DjTransition with exit/entry times, or null if calculation not possible
     */
    @Transactional(jakarta.transaction.Transactional.TxType.REQUIRED)
    public DjTransition calculateTransition(Song currentSong, Song nextSong, int crossfadeSeconds) {
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

        // Step 1: Find all potential exit beats
        // Aim to play at least 50% or 60s of the song
        double minExitTime = Math.min(60.0, currentDuration * 0.50);
        List<BeatInfo> exitBeats = new ArrayList<>();
        for (BeatInfo beat : currentBeats) {
            if (beat.getTime() >= minExitTime) {
                exitBeats.add(beat);
            }
        }

        if (exitBeats.size() < MIN_EXIT_BEATS) {
            LOG.info("Too few beats ({}) for song {}", exitBeats.size(), currentSong.getTitle());
            // Fall back: use last beat of current, first beat of next
            return createFallbackTransition(currentBeats, nextBeats, crossfadeSeconds, "Too few exit beats, using fallback");
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

                // 4. Energy/Volume continuity
                score += calculateEnergySimilarity(exitBeat, entryBeat, currentAnalysis, nextAnalysis);

                // 5. Position scoring (prefer end of current, beginning of next)
                score += scorePosition(exitBeat, entryBeat, currentDuration, nextSong.getDurationSeconds());
                
                if (score > bestScore) {
                    bestScore = score;
                    bestCandidate = new TransitionCandidate(exitBeat.getTime(), entryBeat.getTime(), score);
                }
            }
        }

        // Step 4: If no compatible pair found, use fallback
        if (bestCandidate == null) {
            return createFallbackTransition(currentBeats, nextBeats, crossfadeSeconds, "No compatible beat pair found");
        }

        // Step 5: Build result
        double confidence = normalizeScore(bestScore);
        
        // Final confidence multiplier: BPM difference
        // If BPM ratio is 20% off, multiply confidence by 0.5
        double bpmPenalty = 1.0 - Math.min(bpmRatioDiff / (MAX_BPM_RATIO_DIFF * 2), 0.8);
        confidence *= bpmPenalty;

        // DYNAMIC CROSSFADE: Adjust based on BPM
        // Fast songs (EDM/Pop) mix better with tighter fades (6s)
        // Slow songs (Ambient/Jazz) mix better with long fades (12s)
        int adjustedCrossfade = crossfadeSeconds;
        if (nextBpm > 120) adjustedCrossfade = 6;
        else if (nextBpm < 90) adjustedCrossfade = 12;
        else adjustedCrossfade = 8;

        String reason = buildReasonString(exitBeats, limitedNextBeats, bestCandidate, currentBpm, nextBpm);

        DjTransition transition = new DjTransition(
            bestCandidate.exitTime,
            bestCandidate.entryTime,
            adjustedCrossfade,
            confidence,
            reason
        );

        LOG.info(String.format("Calculated transition: %s → %s (confidence: %.2f)", 
            currentSong.getTitle(), nextSong.getTitle(), confidence));

        return transition;
    }
/**
 * Parse JSON features once.
 */
private List<Map<String, Object>> parseFeatures(String json) {
    if (json == null || json.isEmpty()) return new ArrayList<>();
    try {
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        return mapper.readValue(json, new com.fasterxml.jackson.core.type.TypeReference<List<Map<String, Object>>>() {});
    } catch (Exception e) {
        return new ArrayList<>();
    }
}

/**
 * Optimized spectral similarity using pre-parsed features.
 */
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

/**
 * Score how well a pair of beats align based on musical structure.
 */
private double scoreBeatAlignment(BeatInfo exitBeat, BeatInfo entryBeat) {
    double score = 0;

    // Bonus for downbeats (start of bar)
    if (exitBeat.getBeatInBar() == 1) score += 0.2;
    if (entryBeat.getBeatInBar() == 1) score += 0.2;

    // PHRASE ALIGNMENT (Massive improvement for musicality)
    // Bonus for starting/ending on 4-bar or 8-bar boundaries
    // Assuming bars are 1-indexed and we want to mix on boundaries like 1, 5, 9, 13...
    if ((exitBeat.getBarNumber() - 1) % 4 == 0) score += 0.15;
    if ((exitBeat.getBarNumber() - 1) % 8 == 0) score += 0.1;

    if ((entryBeat.getBarNumber() - 1) % 4 == 0) score += 0.15;
    if ((entryBeat.getBarNumber() - 1) % 8 == 0) score += 0.1;

    // Bonus for matching relative position in bar
    if (exitBeat.getBeatInBar() == entryBeat.getBeatInBar()) score += 0.1;

    return score;
}

/**
 * Energy/Loudness continuity.
 */
private double calculateEnergySimilarity(BeatInfo exitBeat, BeatInfo entryBeat, 
                                        SongAnalysis current, SongAnalysis next) {
    // Find average loudness near the exit and entry points
    // This helps avoid mixing a loud drop into a quiet intro
    try {
        // We use the 'strength' (onset strength) as a proxy for local energy if full segments aren't compared
        double energyDiff = Math.abs(exitBeat.getStrength() - entryBeat.getStrength());
        return Math.max(0.0, 0.2 - (energyDiff * 0.2)); // Up to 0.2 bonus for similar energy
    } catch (Exception e) {
        return 0.1;
    }
}
    private double scorePosition(BeatInfo exitBeat, BeatInfo entryBeat, double currentDur, double nextDur) {
        double score = 0;
        double entryTime = entryBeat.getTime();
        double entryPosition = entryTime / nextDur;

        // Entry scoring: Prefer early but not intro. Sweet spot expanded to 40%.
        if (entryTime < 2.0) score -= 0.5; // Penalty for too early (silence/intro)
        else if (entryTime < 5.0) score -= 0.2;
        else if (entryPosition > 0.05 && entryPosition < 0.40) score += 0.2; // Expanded sweet spot (first 40%)
        else if (entryPosition >= 0.40 && entryPosition < 0.55) score += 0.05; // Mid-song OK
        else if (entryPosition >= 0.55) score -= 1.0; // EVEN STRONGER penalty for jumping past the halfway mark
        
        // Exit scoring: Prefer mixing around the 60% mark
        double exitPosition = exitBeat.getTime() / currentDur;
        if (exitPosition > 0.55 && exitPosition < 0.70) {
            score += 0.3; // Peak "sweet spot" for 60% mixing
        } else if (exitPosition > 0.70) {
            score += 0.1; // Still okay to mix later
        } else if (exitPosition <= 0.50) {
            score -= 0.5; // Stronger penalty for mixing before the halfway point
        }
        
        return score;
    }

    /**
     * Check if two beats are compatible for matching.
     * Beats are compatible if they have the same beatInBar position,
     * or both are downbeats, or both are strong beats (3 in 4/4).
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

    /**
     * Extract spectral vector from beat feature map.
     */
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

    /**
     * Calculate cosine similarity between two vectors.
     */
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

    /**
     * Create a fallback transition when optimal matching fails.
     * Uses last strong beat of current song → first strong beat of next song.
     */
    private DjTransition createFallbackTransition(List<BeatInfo> currentBeats, List<BeatInfo> nextBeats,
                                                    int crossfadeSeconds, String reason) {
        if (currentBeats.isEmpty() || nextBeats.isEmpty()) {
            LOG.warn("Cannot create fallback: empty beat lists");
            return null;
        }

        // Last beat of current song
        BeatInfo lastBeat = currentBeats.get(currentBeats.size() - 1);
        // First beat of next song (preferably a downbeat)
        BeatInfo firstBeat = nextBeats.get(0);

        // Try to find first downbeat in next song
        for (BeatInfo beat : nextBeats) {
            if (beat.getBeatInBar() == 1) {
                firstBeat = beat;
                break;
            }
        }

        // Enforce minimum entry time to avoid silence/intro
        if (firstBeat.getTime() < 5.0) {
            for (BeatInfo beat : nextBeats) {
                if (beat.getBeatInBar() == 1 && beat.getTime() >= 5.0) {
                    firstBeat = beat;
                    break;
                }
            }
            // Absolute floor: if no downbeat >= 5s, use the first beat at >= 5s
            if (firstBeat.getTime() < 5.0) {
                for (BeatInfo beat : nextBeats) {
                    if (beat.getTime() >= 5.0) {
                        firstBeat = beat;
                        break;
                    }
                }
            }
            // Last resort: use the last beat in the entry window
            if (firstBeat.getTime() < 5.0 && !nextBeats.isEmpty()) {
                firstBeat = nextBeats.get(nextBeats.size() - 1);
            }
        }

        LOG.info(String.format("Fallback transition: exit at %.2fs → entry at %.2fs (%s)", 
            lastBeat.getTime(), firstBeat.getTime(), reason));

        return new DjTransition(
            lastBeat.getTime(),
            firstBeat.getTime(),
            crossfadeSeconds,
            0.3, // Low confidence for fallback
            reason
        );
    }

    /**
     * Normalize raw score to 0-1 confidence range.
     */
    private double normalizeScore(double rawScore) {
        // Raw score max is approximately 1.1 (with bonuses)
        return Math.min(Math.max(rawScore / 1.1, 0.0), 1.0);
    }

    /**
     * Build human-readable reason string for the transition.
     */
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

    /**
     * Simple candidate holder for transition scoring.
     */
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
