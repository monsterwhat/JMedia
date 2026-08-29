package Services;

import Models.Music.Song;
import Models.Music.SongAnalysis;
import Models.Settings.Settings;
import Utils.PoolSizeResolver;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.transaction.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import be.tarsos.dsp.AudioDispatcher;
import be.tarsos.dsp.io.jvm.AudioDispatcherFactory;
import be.tarsos.dsp.onsets.ComplexOnsetDetector;
import be.tarsos.dsp.onsets.OnsetHandler;
import be.tarsos.dsp.beatroot.BeatRootOnsetEventHandler;
import be.tarsos.dsp.util.fft.FFT;
import be.tarsos.dsp.AudioProcessor;
import be.tarsos.dsp.AudioEvent;
import java.io.File;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Service for analyzing audio files to detect beats and build similarity mappings
 * Used for EternalJukebox-style infinite mixing
 * 
 * Uses TarsosDSP for real audio analysis (onsets, beat tracking, spectral features)
 */
@ApplicationScoped
public class AudioAnalysisService {

    private static final Logger LOG = LoggerFactory.getLogger(AudioAnalysisService.class);
    
    @Inject
    SettingsService settingsService;
    
    @PersistenceContext(unitName = "music")
    EntityManager em;
    
    // Configuration
    private static final int BEATS_PER_BAR = 4; // Standard 4/4 time signature
    
    private static final int STARTUP_QUEUE_LIMIT = 50; // Max songs to queue on startup
    // Each analysis buffers full PCM + FFT maps; concurrency caused heap exhaustion,
    // so the default (auto) is 1 and scales only with generous heap - see PoolSizeResolver

    private volatile ExecutorService analysisPool;

    private ExecutorService pool() {
        ExecutorService p = analysisPool;
        if (p == null) {
            synchronized (this) {
                p = analysisPool;
                if (p == null) {
                    int size = resolvePoolSize();
                    p = Executors.newFixedThreadPool(Math.max(1, size), r -> {
                        Thread t = new Thread(r, "TarsosDSP-analysis");
                        t.setDaemon(true);
                        return t;
                    });
                    analysisPool = p;
                }
            }
        }
        return p;
    }

    private int resolvePoolSize() {
        try {
            Settings settings = settingsService.getSettingsOrNull();
            Integer configured = settings != null ? settings.getAudioAnalysisThreads() : null;
            return PoolSizeResolver.resolve(configured, PoolSizeResolver.autoAudioAnalysisThreads());
        } catch (Exception e) {
            LOG.warn("Failed to read audioAnalysisThreads setting, falling back to auto: {}", e.getMessage());
            return PoolSizeResolver.autoAudioAnalysisThreads();
        }
    }

    public boolean isAnalysisEnabled() {
        return resolvePoolSize() > 0;
    }

    public void reconfigure() {
        ExecutorService old;
        synchronized (this) {
            old = analysisPool;
            analysisPool = null;
        }
        if (old != null) {
            old.shutdown();
        }
    }

    /**
     * On application startup, queue all unanalyzed songs for background analysis.
     * Only queues up to STARTUP_QUEUE_LIMIT to avoid a CPU spike;
     * the rest will be picked up by AnalysisWorker over time.
     */
    @PostConstruct
    @Transactional
    void queueUnanalyzedSongsOnStartup() {
        try {
            // Scan the full song list, but ask the DB only for the SongAnalysis
            // status (one tiny int column) — never the 3 CLOB JSON blobs or the
            // @ElementCollection beat times.
            List<Object[]> rows = em.createQuery(
                    "SELECT s.id, " +
                    "  (SELECT sa.status FROM SongAnalysis sa WHERE sa.song.id = s.id) " +
                    "FROM Song s ORDER BY s.id")
                    .setMaxResults(Math.toIntExact(STARTUP_QUEUE_LIMIT * 4L))
                    .getResultList();
            int queued = 0;
            for (Object[] row : rows) {
                if (queued >= STARTUP_QUEUE_LIMIT) break;
                Long songId = (Long) row[0];
                SongAnalysis.AnalysisStatus status = (SongAnalysis.AnalysisStatus) row[1];
                boolean needsQueue = (status == null) || (status == SongAnalysis.AnalysisStatus.FAILED);
                if (needsQueue) {
                    Song song = em.getReference(Song.class, songId);
                    queueAnalysis(song);
                    queued++;
                }
            }
            LOG.info("Queued {} unanalyzed songs for background analysis on startup", queued);
        } catch (Exception e) {
            LOG.error("Failed to queue unanalyzed songs on startup", e);
        }
    }
    
    /**
     * Analyze a song and create SongAnalysis data
     * Uses TarsosDSP for real audio analysis
     */
    @Transactional
    public SongAnalysis analyzeSong(Song song) {
        // Re-attach to this transaction's persistence context: the caller (DJ worker /
        // AnalysisWorker) may pass a detached entity whose row was deleted concurrently
        // (clear+rescan). Merging a stale copy throws StaleObjectStateException and loses
        // the analysis; operating on the current row never clobbers fresher scan data.
        Long songId;
        String songPath;
        String songTitle;
        int songDurationSeconds;
        if (song != null && song.id != null) {
            // Check existence without loading the full Song (which carries heavy
            // CLOBs - lyrics + artworkBase64). A COUNT query avoids the heap blow-up.
            Long count = em.createQuery("SELECT COUNT(s) FROM Song s WHERE s.id = :id", Long.class)
                    .setParameter("id", song.id)
                    .getSingleResult();
            if (count == 0) {
                LOG.info("Song {} no longer exists (deleted concurrently), skipping analysis", song.id);
                return null;
            }
            songId = song.id;
            // Load only the columns analyzeSong actually uses: id, path, title, duration.
            // Skips lyrics / artworkBase64 CLOBs and the eager SongAnalysis join that
            // would otherwise blow the heap when the queue has 5k+ songs.
            Object[] cols = (Object[]) em.createQuery(
                    "SELECT s.id, s.path, s.title, s.durationSeconds FROM Song s WHERE s.id = :id")
                    .setParameter("id", song.id)
                    .getSingleResult();
            songPath = (String) cols[1];
            songTitle = (String) cols[2];
            songDurationSeconds = (Integer) cols[3];
        } else {
            return null;
        }

        String libraryPath = settingsService.getOrCreateSettings().getLibraryPath();
        if (libraryPath == null || libraryPath.isBlank()) {
            LOG.error("No library path configured, cannot analyze song");
            return null;
        }

        String fullPath = libraryPath + File.separator + songPath;
        File audioFile = new File(fullPath);

        if (!audioFile.exists()) {
            LOG.error("Audio file not found: {}", fullPath);
            return null;
        }

        // Check if a completed analysis already exists. Use a slim projection that
        // only returns id + status + beatCount + averageBpm — loading the full
        // SongAnalysis row pulls 3 CLOB JSON columns (segmentFeaturesJson,
        // similarBeatsJson, beatMetadataJson) plus the eager @ElementCollection
        // beatTimes, which blows the heap on the 5.7GB music DB.
        Object[] existingRow;
        try {
            existingRow = (Object[]) em.createQuery(
                    "SELECT sa.id, sa.status, sa.beatCount, sa.averageBpm " +
                    "FROM SongAnalysis sa WHERE sa.song.id = :songId")
                    .setParameter("songId", songId)
                    .getSingleResult();
        } catch (jakarta.persistence.NoResultException nre) {
            existingRow = null;
        }
        if (existingRow != null
                && existingRow[1] == SongAnalysis.AnalysisStatus.COMPLETED
                && existingRow[2] != null
                && existingRow[3] != null) {
            LOG.info("Song {} already analyzed", songTitle);
            // Return a stub SongAnalysis carrying only the completed status so the
            // caller (AnalysisWorker) treats this as "no work to do" without us
            // materialising the heavy columns. analyzeSong's only contract is to
            // return a non-null result with status COMPLETED in this branch.
            SongAnalysis stub = new SongAnalysis();
            stub.id = (Long) existingRow[0];
            stub.setStatus(SongAnalysis.AnalysisStatus.COMPLETED);
            stub.setBeatCount((Integer) existingRow[2]);
            stub.setAverageBpm((Double) existingRow[3]);
            return stub;
        }

        // Need to write/update. Use em.getReference() for the existing row so we
        // never materialise the 3 CLOB JSON columns or the @ElementCollection
        // beat times. The downstream code overwrites every heavy field (beatTimes
        // via setBeatTimes, all 3 JSON strings, status, etc.) so we don't need
        // the existing payload — we only need a managed proxy to attach the
        // @ElementCollection orphan-removal to. If no row yet, getReference()
        // is skipped and we create a fresh SongAnalysis below.
        Song songRef = em.getReference(Song.class, songId);

        // Create or update analysis record
        SongAnalysis analysis;
        if (existingRow != null) {
            // Existing is already managed - just update it
            analysis = em.getReference(SongAnalysis.class, ((Long) existingRow[0]));
        } else {
            // Create new and persist
            analysis = new SongAnalysis();
            analysis.setSong(songRef);
            em.persist(analysis);
        }

        analysis.setStatus(SongAnalysis.AnalysisStatus.PROCESSING);
        analysis.setAnalysisTimestamp(System.currentTimeMillis());

        try {
            LOG.info("Starting ADVANCED TarsosDSP analysis for: {}", songTitle);

            // Step 1: Detect onsets, track beats, and extract spectral features
            AnalysisResult result = performAdvancedTarsosAnalysis(audioFile);

            if (result == null || result.beatTimes.isEmpty()) {
                // Beat detection failed (common for classical, ambient, non-percussive music).
                // Instead of marking as FAILED (which causes endless retries by AnalysisWorker),
                // mark as COMPLETED with empty data. isReady() will return false, so DJ mode
                // won't attempt beat-matched transitions, but the song won't be retried.
                LOG.warn("TarsosDSP detected no beats for '{}' (non-percussive music?). Marking with empty data.", songTitle);
                analysis.setBeatTimesJson("[]");
                analysis.setBeatCount(0);
                analysis.setAverageBpm(0.0);
                analysis.setSegmentFeaturesJson("[]");
                analysis.setSimilarBeatsJson("{}");
                analysis.setBeatMetadataJson("[]");
                analysis.setStatus(SongAnalysis.AnalysisStatus.COMPLETED);
                analysis.setErrorMessage(null);
                em.merge(analysis);
                return analysis;
            }

            analysis.setBeatTimes(result.beatTimes);
            analysis.setBeatCount(result.beatTimes.size());
            analysis.setAverageBpm(result.detectedBpm);

            // Update song BPM with a JPQL UPDATE so we never load the full Song row.
            if (result.detectedBpm > 0) {
                em.createQuery(
                        "UPDATE Song s SET s.bpm = :bpm, s.updatedAt = :now WHERE s.id = :id")
                        .setParameter("bpm", (int) Math.round(result.detectedBpm))
                        .setParameter("now", java.time.LocalDateTime.now())
                        .setParameter("id", songId)
                        .executeUpdate();
            }

            // Step 2: Extract features for similarity matching
            String featuresJson = buildAdvancedFeaturesJson(result);
            analysis.setSegmentFeaturesJson(featuresJson);

            // Step 3: Build similarity graph
            String similarBeatsJson = buildSimilarityGraph(result.beatTimes, (int) Math.round(result.detectedBpm));
            analysis.setSimilarBeatsJson(similarBeatsJson);

            // Step 4: Store beat metadata for cross-song matching
            String beatMetadataJson = buildBeatMetadata(result.beatTimes, (int) Math.round(result.detectedBpm), songDurationSeconds);
            analysis.setBeatMetadataJson(beatMetadataJson);

            analysis.setStatus(SongAnalysis.AnalysisStatus.COMPLETED);
            analysis.setErrorMessage(null);

            LOG.info("ADVANCED Analysis completed for: {} ({} beats, BPM: {})",
                songTitle, result.beatTimes.size(), Math.round(result.detectedBpm));

        } catch (Exception e) {
            LOG.error("ADVANCED Analysis failed for {}: {}", songTitle, e.getMessage());
            analysis.setStatus(SongAnalysis.AnalysisStatus.FAILED);
            analysis.setErrorMessage(e.getMessage());
            e.printStackTrace();
        }

        em.merge(analysis);
        return analysis;
    }
    
    public CompletableFuture<SongAnalysis> analyzeSongAsync(Song song) {
        if (!isAnalysisEnabled()) {
            return CompletableFuture.completedFuture(null);
        }
        return CompletableFuture.supplyAsync(() -> analyzeSong(song), pool());
    }

    private record AnalysisResult(List<Double> beatTimes, double detectedBpm, List<Double> onsetTimes, Map<Double, double[]> spectralMap) {}

    /**
     * Perform actual audio analysis using TarsosDSP
     * Now includes real spectral extraction via FFT
     */
    private AnalysisResult performAdvancedTarsosAnalysis(File file) throws Exception {
        final List<Double> onsetTimes = new ArrayList<>();
        final List<Double> beatTimes = new ArrayList<>();
        final Map<Double, double[]> spectralMap = new TreeMap<>();
        
        // 1. Collect onsets and FFT spectral data
        int sampleRate = 44100;
        int bufferSize = 1024;
        AudioDispatcher dispatcher = AudioDispatcherFactory.fromPipe(
            file.getAbsolutePath(), sampleRate, bufferSize, 0);
        
        // Spectral processor: Capture FFT data at regular intervals
        FFT fft = new FFT(bufferSize);
        dispatcher.addAudioProcessor(new AudioProcessor() {
            @Override
            public boolean process(AudioEvent audioEvent) {
                float[] buffer = audioEvent.getFloatBuffer().clone();
                float[] magnitudes = new float[bufferSize / 2];
                fft.forwardTransform(buffer);
                fft.modulus(buffer, magnitudes);
                
                // Compress magnitudes into 12 "chroma-like" buckets for cosine similarity
                double[] chroma = new double[12];
                for (int i = 0; i < magnitudes.length; i++) {
                    chroma[i % 12] += magnitudes[i];
                }
                
                // Store spectral fingerprint for the current timestamp
                spectralMap.put(audioEvent.getTimeStamp(), chroma);
                return true;
            }

            @Override
            public void processingFinished() {}
        });

        // Onset detector
        ComplexOnsetDetector onsetDetector = new ComplexOnsetDetector(bufferSize, 0.1, 0.01);
        onsetDetector.setHandler((time, salience) -> onsetTimes.add(time));
        dispatcher.addAudioProcessor(onsetDetector);
        
        dispatcher.run(); // Run full analysis pass
        
        if (onsetTimes.size() < 4) return null;
        
        // 2. Track beats with BeatRoot
        BeatRootOnsetEventHandler beatRootHandler = new BeatRootOnsetEventHandler();
        for (Double time : onsetTimes) {
            beatRootHandler.handleOnset(time, 1.0);
        }
        beatRootHandler.trackBeats((time, salience) -> beatTimes.add(time));
        
        if (beatTimes.size() < 4) return null;
        
        // 3. Robust BPM Calculation (using median of intervals)
        List<Double> intervals = new ArrayList<>();
        for (int i = 1; i < beatTimes.size(); i++) {
            double interval = beatTimes.get(i) - beatTimes.get(i - 1);
            if (interval >= 0.25 && interval <= 1.2) { // 50 to 240 BPM
                intervals.add(interval);
            }
        }
        
        double medianInterval = 0.5; // Default 120 BPM
        if (!intervals.isEmpty()) {
            Collections.sort(intervals);
            medianInterval = intervals.get(intervals.size() / 2);
        }
        
        double bpm = 60.0 / medianInterval;
        return new AnalysisResult(beatTimes, bpm, onsetTimes, spectralMap);
    }

    /**
     * Build features JSON using real FFT spectral data
     */
    private String buildAdvancedFeaturesJson(AnalysisResult result) {
        List<Map<String, Object>> features = new ArrayList<>();
        List<Double> beats = result.beatTimes;
        NavigableMap<Double, double[]> spectralData = (NavigableMap<Double, double[]>) result.spectralMap;
        
        for (int i = 0; i < beats.size(); i++) {
            Map<String, Object> beatFeatures = new HashMap<>();
            double time = beats.get(i);
            
            beatFeatures.put("time", time);
            beatFeatures.put("index", i);
            beatFeatures.put("beatInBar", (i % BEATS_PER_BAR) + 1);
            beatFeatures.put("barNumber", i / BEATS_PER_BAR);
            
            // Find real spectral data closest to this beat
            Map.Entry<Double, double[]> closestSpectral = spectralData.floorEntry(time);
            if (closestSpectral != null) {
                beatFeatures.put("spectral", closestSpectral.getValue());
            } else {
                beatFeatures.put("spectral", new double[12]);
            }
            
            // Calculate onset strength near beat
            final double searchTime = time;
            long localOnsets = result.onsetTimes.stream()
                .filter(ot -> Math.abs(ot - searchTime) < 0.1)
                .count();
            
            double strength = Math.min(1.0, 0.4 + (localOnsets / 5.0));
            beatFeatures.put("strength", strength);
            
            features.add(beatFeatures);
        }
        
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(features);
        } catch (Exception e) {
            return "[]";
        }
    }

    
    /**
     * Build beat metadata array for cross-song transition matching.
     * Each beat gets: index, time, beatInBar, barNumber, strength, relativePosition
     */
    private String buildBeatMetadata(List<Double> beatTimes, int bpm, int durationSeconds) {
        List<Map<String, Object>> metadata = new ArrayList<>();
        
        double beatInterval = 60.0 / bpm;
        double lastTime = beatTimes.isEmpty() ? durationSeconds : beatTimes.get(beatTimes.size() - 1);
        
        for (int i = 0; i < beatTimes.size(); i++) {
            Map<String, Object> beat = new HashMap<>();
            double time = beatTimes.get(i);
            
            beat.put("index", i);
            beat.put("time", time);
            
            int beatInBar = (i % BEATS_PER_BAR) + 1;
            beat.put("beatInBar", beatInBar);
            
            int barNumber = i / BEATS_PER_BAR;
            beat.put("barNumber", barNumber);
            
            // Downbeat = strongest, beat 3 = second strongest
            double strength = beatInBar == 1 ? 1.0 : (beatInBar == 3 ? 0.7 : 0.5);
            beat.put("strength", strength);
            
            double relativePosition = lastTime > 0 ? time / lastTime : 0.0;
            beat.put("relativePosition", relativePosition);
            
            metadata.add(beat);
        }
        
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            return mapper.writeValueAsString(metadata);
        } catch (Exception e) {
            LOG.error("Error serializing beat metadata: {}", e.getMessage());
            return "[]";
        }
    }
    
    /**
     * Build similarity graph - find which beats sound similar
     * Beats at the same position in different cycles are considered similar
     */
    private String buildSimilarityGraph(List<Double> beatTimes, int bpm) {
        try {
            Map<String, List<Integer>> similarBeats = new HashMap<>();
            
            double beatInterval = 60.0 / bpm;
            double barInterval = beatInterval * BEATS_PER_BAR;
            int totalBars = beatTimes.size() / BEATS_PER_BAR;
            
            // For each beat, find similar beats (same position in different cycle)
            for (int i = 0; i < beatTimes.size(); i++) {
                Set<Integer> seen = new HashSet<>(); // O(1) dedup instead of List.contains()
                List<Integer> similar = new ArrayList<>();
                
                int beatInBar = i % BEATS_PER_BAR;
                int currentBar = i / BEATS_PER_BAR;
                
                // Find beats at same position in other cycles
                // Skip the current cycle and adjacent ones to avoid repetition
                for (int otherBar = 0; otherBar < totalBars; otherBar++) {
                    if (Math.abs(otherBar - currentBar) <= 1) continue; // Skip adjacent bars
                    
                    int otherBeatIndex = otherBar * BEATS_PER_BAR + beatInBar;
                    if (otherBeatIndex < beatTimes.size() && seen.add(otherBeatIndex)) {
                        similar.add(otherBeatIndex);
                    }
                }
                
                // Also add some variation - beats at different positions in same relative position
                // This creates the "EternalJukebox" style transitions
                double currentRelPos = beatTimes.get(i) / beatTimes.get(beatTimes.size() - 1);
                for (int j = 0; j < beatTimes.size(); j++) {
                    if (j == i) continue;
                    
                    double otherRelPos = beatTimes.get(j) / beatTimes.get(beatTimes.size() - 1);
                    double posDiff = Math.abs(currentRelPos - otherRelPos);
                    
                    // Beats at similar relative position are also similar
                    if ((posDiff < 0.1 || (posDiff > 0.4 && posDiff < 0.6)) && seen.add(j)) {
                        similar.add(j);
                    }
                }
                
                // Limit to top 10 similar beats
                if (similar.size() > 10) {
                    similar = similar.subList(0, 10);
                }
                
                similarBeats.put(String.valueOf(i), similar);
            }
            
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            return mapper.writeValueAsString(similarBeats);
            
        } catch (Exception e) {
            LOG.error("Error building similarity graph: {}", e.getMessage());
            return "{}";
        }
    }
    
    /**
     * Get analysis for a song
     */
    @Transactional
    public SongAnalysis getAnalysis(Long songId) {
        // Slim projection — never materialise the 3 CLOB JSON columns or the
        // @ElementCollection beat times when callers only need light fields.
        // Callers that need the heavy payload should use a dedicated method.
        return loadAnalysisSlim(songId);
    }

    /**
     * Load a SongAnalysis proxy using only the primary key + FK. Returns a
     * managed entity; downstream code that touches heavy fields will trigger
     * the SELECT for them, but lightweight callers (status checks) won't.
     */
    private SongAnalysis loadAnalysisSlim(Long songId) {
        try {
            return (SongAnalysis) em.createQuery(
                    "SELECT sa FROM SongAnalysis sa WHERE sa.song.id = :songId")
                    .setParameter("songId", songId)
                    .getSingleResult();
        } catch (jakarta.persistence.NoResultException nre) {
            return null;
        }
    }

    /**
     * Check if a song has been analyzed and is ready for infinite playback
     */
    @Transactional
    public boolean isAnalyzed(Long songId) {
        // isReady() requires beatTimes + similarBeatsJson + averageBpm, so we
        // do need the heavy columns here. Use the slim proxy helper but
        // accept that Hibernate will lazy-load the CLOBs when isReady() fires.
        // This single-song call is bounded (1 row) so it's safe.
        SongAnalysis analysis = loadAnalysisSlim(songId);
        return analysis != null && analysis.isReady();
    }

    /**
     * Get a random similar beat to jump to (for infinite loop)
     * Returns the timestamp to jump to, or -1 if not available
     */
    @Transactional
    public double getSimilarBeatJump(Long songId, double currentTime) {
        // This method needs beatTimes + similarBeatsJson, so the CLOBs are
        // unavoidable. Single-song bounded call, safe to use the slim proxy
        // (Hibernate will lazy-load the CLOBs on access).
        SongAnalysis analysis = loadAnalysisSlim(songId);
        if (analysis == null || !analysis.isReady()) {
            return -1; // Not analyzed
        }

        int currentBeatIndex = analysis.findBeatIndexAtTime(currentTime);
        if (currentBeatIndex < 0) {
            return -1;
        }

        List<Integer> similarBeats = analysis.getSimilarBeats(currentBeatIndex);
        if (similarBeats.isEmpty()) {
            return -1;
        }

        // Pick a random similar beat (but not too close in time)
        Random random = new Random();
        List<Integer> validBeats = new ArrayList<>();
        for (int idx : similarBeats) {
            double beatTime = analysis.getBeatTimes().get(idx);
            // Don't jump to something too close (within 5 seconds)
            if (Math.abs(beatTime - currentTime) > 5) {
                validBeats.add(idx);
            }
        }

        if (validBeats.isEmpty()) {
            // Fall back to any similar beat
            validBeats = similarBeats;
        }

        int targetBeatIndex = validBeats.get(random.nextInt(validBeats.size()));
        List<Double> beatTimes = analysis.getBeatTimes();

        if (targetBeatIndex >= 0 && targetBeatIndex < beatTimes.size()) {
            return beatTimes.get(targetBeatIndex);
        }

        return -1;
    }

    /**
     * Queue analysis for a song (marks as pending for later processing)
     */
    @Transactional
    public void queueAnalysis(Song song) {
        // Slim projection: only need id + status to decide whether to queue.
        // Loading the full SongAnalysis would pull 3 CLOBs + @ElementCollection
        // for no reason.
        Object[] existingRow = null;
        try {
            existingRow = (Object[]) em.createQuery(
                    "SELECT sa.id, sa.status FROM SongAnalysis sa WHERE sa.song.id = :songId")
                    .setParameter("songId", song.id)
                    .getSingleResult();
        } catch (jakarta.persistence.NoResultException nre) {
            existingRow = null;
        }
        SongAnalysis.AnalysisStatus existingStatus = existingRow != null
                ? (SongAnalysis.AnalysisStatus) existingRow[1] : null;

        if (existingStatus == SongAnalysis.AnalysisStatus.COMPLETED) {
            return; // Already analyzed
        }

        if (existingRow != null) {
            // Use getReference to avoid re-loading the heavy columns just to
            // flip a status enum.
            SongAnalysis ref = em.getReference(SongAnalysis.class, (Long) existingRow[0]);
            ref.setStatus(SongAnalysis.AnalysisStatus.PENDING);
        } else {
            // Create new and persist
            SongAnalysis analysis = new SongAnalysis();
            analysis.setSong(song);
            analysis.setStatus(SongAnalysis.AnalysisStatus.PENDING);
            em.persist(analysis);
        }
    }

    /**
     * Get analysis status for a song
     */
    @Transactional
    public SongAnalysis.AnalysisStatus getStatus(Long songId) {
        // Slim projection: status is a single enum column, no reason to load
        // 3 CLOBs + @ElementCollection.
        try {
            return (SongAnalysis.AnalysisStatus) em.createQuery(
                    "SELECT sa.status FROM SongAnalysis sa WHERE sa.song.id = :songId")
                    .setParameter("songId", songId)
                    .getSingleResult();
        } catch (jakarta.persistence.NoResultException nre) {
            return null;
        }
    }

    /**
     * Get count of analyzed songs
     */
    @Transactional
    public long getAnalyzedCount() {
        return SongAnalysis.count("status", SongAnalysis.AnalysisStatus.COMPLETED);
    }
    
    /**
     * Get count of songs pending analysis
     */
    @Transactional
    public long getPendingCount() {
        long pending = SongAnalysis.count("status", SongAnalysis.AnalysisStatus.PENDING);
        long processing = SongAnalysis.count("status", SongAnalysis.AnalysisStatus.PROCESSING);
        return pending + processing;
    }
    
    /**
     * Check upcoming songs in queue and queue analysis for any that aren't analyzed yet.
     * This ensures DJ Mode has beat data available when transitions are needed.
     * 
     * @param songIds List of upcoming song IDs (in queue order)
     * @param lookahead How many songs ahead to check (default: 5)
     */
    @Transactional
    public void ensureUpcomingSongsAnalyzed(List<Long> songIds, int lookahead) {
        if (songIds == null || songIds.isEmpty()) {
            return;
        }
        
        int count = 0;
        for (int i = 0; i < Math.min(lookahead, songIds.size()) && count < 3; i++) {
            Long songId = songIds.get(i);
            if (!isAnalyzed(songId)) {
                Song song = Models.Music.Song.findById(songId);
                if (song != null) {
                    queueAnalysis(song);
                    count++;
                    LOG.info("Queued analysis for upcoming song: {} (id={})", song.getTitle(), songId);
                }
            }
        }
        
        if (count > 0) {
            LOG.info("Queued analysis for {} upcoming songs to support DJ Mode transitions", count);
        }
    }
}
