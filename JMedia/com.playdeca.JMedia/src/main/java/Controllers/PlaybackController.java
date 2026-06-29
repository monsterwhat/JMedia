package Controllers;

import API.WS.MusicSocket;
import Models.PlaybackHistory;
import Models.PlaybackState;
import Models.Playlist;
import Models.Profile;
import Models.Settings;
import Models.Song;
import Services.AudioAnalysisService;
import Services.DjTransitionService;
import Services.DjTransitionService.DjTransition;
import Services.PlaybackHistoryService;
import Services.PlaylistService;
import Services.ProfileService;
import Services.SongService;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import java.util.stream.Collectors;

@ApplicationScoped
public class PlaybackController {

    private final Map<Long, PlaybackState> memoryStates = new ConcurrentHashMap<>();

    @Inject
    PlaybackPersistenceController playbackPersistenceController;
    @Inject
    PlaybackQueueController playbackQueueController;
    @Inject
    SettingsController currentSettings;
    @Inject
    SongService songService;
    @Inject
    PlaylistService playlistService;
    @Inject
    PlaybackHistoryService playbackHistoryService;
    @Inject
    MusicSocket ws;
    @Inject
    ProfileService profileService;
    @Inject
    AudioAnalysisService audioAnalysisService;
    @Inject
    DjTransitionService djTransitionService;

    private ScheduledExecutorService scheduler;
    private final Map<Long, ScheduledFuture<?>> playbackTasks = new ConcurrentHashMap<>();
    private final Map<Long, Long> lastBroadcastTime = new ConcurrentHashMap<>();
    private static final long BROADCAST_THROTTLE_MS = 250; // Max one broadcast every 250ms per profile
    private static final long PLAYBACK_UPDATE_INTERVAL_MS = 500; // Update every 500ms to prevent stream jumping

    private static final Logger LOGGER = Logger.getLogger(PlaybackController.class.getName());

    public PlaybackController() {
        System.out.println("[PlaybackController] PlaybackController instance created");
    }

    @PostConstruct
    public void init() {
        scheduler = Executors.newSingleThreadScheduledExecutor();
        System.out.println("[PlaybackController] PlaybackController initialized.");
    }

    @jakarta.annotation.PreDestroy
    public void shutdownScheduler() {
        playbackTasks.values().forEach(task -> task.cancel(true));
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    private synchronized void startPlaybackTimer(Long profileId) {
        ScheduledFuture<?> task = playbackTasks.get(profileId);
        if (task != null && !task.isDone()) {
            task.cancel(false); // Allow current task to complete if running
        }

        task = scheduler.scheduleAtFixedRate(() -> {
            processPlaybackTick(profileId);
        }, 0, PLAYBACK_UPDATE_INTERVAL_MS, TimeUnit.MILLISECONDS);
        playbackTasks.put(profileId, task);
        LOGGER.info("Playback timer started for profile: " + profileId);
    }

    protected synchronized void processPlaybackTick(Long profileId) {
        try {
            PlaybackState st = getState(profileId);
            if (st.isPlaying() && st.getCurrentSongId() != null) {
                double newTime = st.getCurrentTime() + (PLAYBACK_UPDATE_INTERVAL_MS / 1000.0);
                
                int crossfadeDuration = st.getCrossfadeDuration() != null ? st.getCrossfadeDuration() : 0;
                double crossfadeThreshold = st.getDuration() - crossfadeDuration;
                
                // REMOVED: DJ Mode auto-activation on Smart Shuffle
                // DJ Mode is now SEPARATED from Smart Shuffle - it stays in whatever state the user set
                // Users can toggle DJ Mode independently, even when Smart Shuffle is off
                
                // Check if we've reached the transition point
                // DJ Mode: wait until djExitTime + crossfadeDuration to give frontend time to crossfade
                if (Boolean.TRUE.equals(st.getDjModeActive()) && Boolean.TRUE.equals(st.getDjTransitionPlanned()) && st.getDjExitTime() != null && st.getDuration() > 0) {
                    double djAdvanceTime = st.getDjExitTime() + crossfadeDuration;
                    if (newTime >= djAdvanceTime) {
                        // Don't advance here — frontend handles the transition via /transition-started/
                        // This prevents a double-advance race where the timer also calls handleSongEnded()
                        // and skips past the DJ-planned next song.
                        // 10-second safeguard: if frontend never reports back, fall back to advance.
                        if (newTime >= djAdvanceTime + 10) {
                            System.out.println("[DJ] ⚠️ Frontend did not report transition within 10s, falling back to server-side advance");
                            handleSongEnded(profileId);
                        } else {
                            st.setCurrentTime(newTime);
                            broadcastStateIfNecessary(st, profileId);
                        }
                    } else {
                        st.setCurrentTime(newTime);
                        broadcastStateIfNecessary(st, profileId);
                    }
                } else if (crossfadeDuration > 0 && newTime >= crossfadeThreshold && st.getDuration() > 0) {
                    // Regular crossfade point reached
                    handleSongEnded(profileId);
                } else if (newTime >= st.getDuration() && st.getDuration() > 0) {
                    // Song ended naturally with no crossfade
                    handleSongEnded(profileId);
                } else {
                    st.setCurrentTime(newTime);
                    broadcastStateIfNecessary(st, profileId);
                }
            }
        } catch (Exception e) {
            LOGGER.severe("Error in playback timer task for profile " + profileId + ": " + e.getMessage());
            e.printStackTrace();
        }
    }

    private synchronized void broadcastStateIfNecessary(PlaybackState state, Long profileId) {
        long now = System.currentTimeMillis();
        Long lastBroadcast = lastBroadcastTime.get(profileId);

        if (lastBroadcast == null || (now - lastBroadcast) >= BROADCAST_THROTTLE_MS) {
            updateState(profileId, state, true); // Broadcast state
            lastBroadcastTime.put(profileId, now);
        }
    }
    
    /**
     * Activate DJ Mode transition using persisted SongAnalysis data.
     * SongAnalysis is already persisted per-song, so transitions calculate instantly from that data.
     */
    private void activateDjModeTransition(PlaybackState st, Settings settings, Long profileId) {
        int djCrossfade = settings.getDjModeCrossfadeSeconds() != null ? settings.getDjModeCrossfadeSeconds() : 8;
        st.setCrossfadeDuration(djCrossfade);
        
        Long currentSongId = st.getCurrentSongId();
        List<Long> cue = st.getCue();
        int cueIndex = st.getCueIndex();
        
        if (cue == null || cueIndex < 0 || cueIndex >= cue.size() - 1) {
            System.out.println("[DJ] No next song in queue - disabling transition for this song");
            st.setDjTransitionPlanned(false); // Mark as attempted/failed so we don't spam
            updateState(profileId, st, true);
            return;
        }
        
        Long nextSongId = cue.get(cueIndex + 1);
        Song currentSong = songService.find(currentSongId);
        Song nextSong = songService.find(nextSongId);
        
        if (currentSong != null && nextSong != null) {
            DjTransition transition = djTransitionService.calculateTransition(currentSong, nextSong, djCrossfade);
            if (transition != null) {
                st.setDjNextSongId(nextSongId);
                st.setDjEntryTime(transition.getEntryTime());
                st.setDjExitTime(transition.getExitTime());
                st.setDjTransitionPlanned(true);
                st.setDjTransitionConfidence(transition.getConfidence());
                st.setDjTransitionReason(transition.getReason());
                
                System.out.println("=== DJ MODE: Transition planned ===");
                System.out.println("  Exit: " + transition.getExitTime() + "s, Entry: " + transition.getEntryTime() + "s");
                System.out.println("  Confidence: " + transition.getConfidence() + ", Reason: " + transition.getReason());
                
                updateState(profileId, st, true);
                return;
            }
        }
        
        System.out.println("[DJ] No transition available - disabling for this song");
        st.setDjTransitionPlanned(false); // Mark as attempted so we don't retry
        updateState(profileId, st, true);
    }
    
    /**
     * Find the next song in the queue after the current one.
     */
    private Song findNextSongInQueue(PlaybackState st) {
        List<Long> cue = st.getCue();
        if (cue == null || cue.isEmpty()) {
            return null;
        }
        
        int currentIndex = st.getCueIndex();
        int nextIndex = currentIndex + 1;
        
        if (nextIndex >= cue.size()) {
            // Wrap around or check secondary queue
            if (st.getRepeatMode() == PlaybackState.RepeatMode.ALL) {
                nextIndex = 0;
            } else {
                // Check secondary queue
                List<Long> secondaryCue = st.getSecondaryCue();
                if (secondaryCue != null && !secondaryCue.isEmpty()) {
                    return songService.find(secondaryCue.get(0));
                }
                return null;
            }
        }
        
        return songService.find(cue.get(nextIndex));
    }
    
    /**
     * Clear DJ transition plan from state.
     */
    private void clearDjTransitionPlan(PlaybackState st) {
        st.setDjNextSongId(null);
        st.setDjEntryTime(null);
        st.setDjExitTime(null);
        st.setDjTransitionPlanned(null); // Use null to indicate "not yet attempted"
        st.setDjTransitionConfidence(null);
        st.setDjTransitionReason(null);
    }
    
    /**
     * Check if current time is near the end of the song (within trigger percent)
     */
    private boolean isTimeNearEnd(double duration, double currentTime, int triggerPercent) {
        double endThreshold = duration * (100.0 - triggerPercent) / 100.0;
        return currentTime >= endThreshold;
    }

    private synchronized void stopPlaybackTimer(Long profileId) {
        ScheduledFuture<?> task = playbackTasks.remove(profileId);
        lastBroadcastTime.remove(profileId); // Clean up broadcast tracking
        if (task != null && !task.isDone()) {
            task.cancel(false); // Allow current task to complete if running
            LOGGER.info("Playback timer stopped for profile: " + profileId);
        }
    }

    // -----------------------------
    // Playback state methods
    // -----------------------------
    public synchronized PlaybackState getState(Long profileId) {
        return memoryStates.computeIfAbsent(profileId, id -> {
            PlaybackState state = playbackPersistenceController.loadState(id);
            if (state == null) {
                state = new PlaybackState();
            }

// DON'T auto-populate primary queue - keep it empty for dual-queue system
            // Only ensure currentSongId is valid if primary queue has songs
            if (!state.getCue().isEmpty() && state.getCurrentSongId() == null) {
                state.setCurrentSongId(state.getCue().get(0));
                state.setCueIndex(0);
            }

            if (state.getCurrentSongId() == null && !state.getCue().isEmpty()) {
                state.setCurrentSongId(state.getCue().get(0));
                state.setCueIndex(0);
            }

            state.setPlaying(false);
            // Default NULL djModeActive to false (existing DB rows have NULL)
            if (state.getDjModeActive() == null) {
                state.setDjModeActive(false);
            }
            System.out.println("[PlaybackController] Initial state loaded for profile " + profileId + ": " + safeSummary(state, profileId));
            return state;
        });
    }

    public synchronized void updateState(Long profileId, PlaybackState newState, boolean shouldBroadcast) {
        PlaybackState currentState = memoryStates.get(profileId);
        if (currentState == null) {
            currentState = playbackPersistenceController.loadState(profileId);
            if (currentState == null) {
                currentState = new PlaybackState();
            }
            memoryStates.put(profileId, currentState);
        }

        if (newState.getCurrentSongId() != null) {
            Song currentSong = songService.find(newState.getCurrentSongId());
            if (currentSong != null) {
                newState.setArtistName(currentSong.getArtist());
                newState.setSongName(currentSong.getTitle());
                newState.setDuration(currentSong.getDurationSeconds());
            } else {
                newState.setArtistName("Unknown Artist");
                newState.setSongName("Unknown Title");
                newState.setDuration(0);
            }
        }

        // REMOVED: The logic that restored old time when new time was 0.
        // This was causing manual seeks to 0:00 to be ignored/overwritten.
        
        newState.setServerTime(System.currentTimeMillis());
        if (newState.getCue() == null) {
            newState.setCue(new ArrayList<>());
        }
        if (newState.getLastSongs() == null) {
            newState.setLastSongs(new ArrayList<>());
        }

        memoryStates.put(profileId, newState); // Update the map with the new state

        newState.setLastUpdateTime(System.currentTimeMillis()); // Set timestamp for latency compensation
        playbackPersistenceController.maybePersist(profileId, newState); // persist only

        if (shouldBroadcast && ws != null) {
            ws.broadcastAll(newState, profileId);
        }
    }

    public synchronized void selectSong(Long id, Long profileId) {
        PlaybackState st = getState(profileId);
        Song current = getCurrentSong(profileId);

        // DJ Mode is now INDEPENDENT of Smart Shuffle - do NOT disable it when selecting songs
        // DJ Mode stays in whatever state the user set it
        clearDjTransitionPlan(st);

        if (current != null && current.id.equals(id)) {
            // Toggle play/pause
            st.setPlaying(!st.isPlaying());
            currentSettings.addLog("Playback toggled for song: " + current.getTitle());
            if (st.isPlaying()) {
                startPlaybackTimer(profileId);
            } else {
                stopPlaybackTimer(profileId);
            }
        } else {
            // Save the current song to history before selecting a new one
            if (st.getCurrentSongId() != null) {
                Song finishedSong = findSong(st.getCurrentSongId());
                if (finishedSong != null) {
                    playbackHistoryService.add(finishedSong, profileId);
                    // Broadcast history update to all connected clients
                    ws.broadcastHistoryUpdate(profileId);
                }
            }

            st.setCurrentSongId(id);
            Song newSong = findSong(id);
            st.setArtistName(newSong != null ? newSong.getArtist() : "Unknown Artist");
            st.setSongName(newSong != null ? newSong.getTitle() : "Unknown Title");
            st.setDuration(newSong != null ? newSong.getDurationSeconds() : 0);
            st.setPlaying(true);
            st.setCurrentTime(0);
            currentSettings.addLog("Song selected: " + (newSong != null ? newSong.getTitle() : "Unknown Title"));
            playbackQueueController.songSelected(newSong.id, profileId);

            addSongToCueIfNotPresent(st, id, profileId);

            // FIX: Update the cue index to match the selected song
            if (st.getCue() != null) {
                st.setCueIndex(st.getCue().indexOf(id));
            }
            
            // Plan DJ Mode transition immediately for the selected song (independent of Smart Shuffle)
            if (Boolean.TRUE.equals(st.getDjModeActive())) {
                planNextDjTransition(st, profileId);
            }
            
            startPlaybackTimer(profileId); // Start timer for new song
        }

        updateState(profileId, st, true);
        System.out.println("Updated Selection for profile: " + profileId);
    }

    private void addSongToCueIfNotPresent(PlaybackState st, Long songId, Long profileId) {
        List<Long> songIds = new ArrayList<>();
        songIds.add(songId);
        playbackQueueController.addToQueue(st, songIds, false, profileId);
    }

    private synchronized void stopPlayback(Long profileId) {
        PlaybackState st = getState(profileId);
        playbackQueueController.clear(st, profileId);
        stopPlaybackTimer(profileId); // Stop the timer when playback is stopped
        updateState(profileId, st, true);
    }

    private void handleAction(com.fasterxml.jackson.databind.JsonNode node, Long profileId) {
        String action = node.get("type").asText();
        PlaybackState state = getState(profileId);

        switch (action) {
            case "seek":
                if (node.has("value")) {
                    setSeconds(node.get("value").asDouble(), profileId);
                }
                break;
            case "volume":
                if (node.has("value")) {
                    state.setVolume((float) node.get("value").asDouble());
                }
                break;
            case "toggle-play":
                togglePlay(profileId);
                break;
            case "next":
                next(profileId);
                break;
            case "previous":
                previous(profileId);
                break;
            case "song_ended": // New case for natural song end
                handleSongEnded(profileId);
                break;
            case "shuffle":
                toggleShuffle(profileId);
                break;
            case "repeat":
                toggleRepeat(profileId);
                break;
        }

        // Persist throttled to DB
        playbackPersistenceController.maybePersist(profileId, state);
    }

    // -----------------------------
    // Playlist + Song helpers
    // -----------------------------
    public Playlist findPlaylist(Long id) {
        return playlistService.find(id);
    }

    public Playlist findPlaylistWithSongs(Long id) {
        return playlistService.findWithSongs(id);
    }

    public List<Playlist> getPlaylists() {
        return playlistService.findAll();
    }

    public List<Playlist> getPlaylistsForProfile(Long profileId) {
        Profile profile = profileService.findById(profileId);
        if (profile == null) {
            return List.of();
        }
        return playlistService.findAllForProfile(profile);
    }

    public List<Playlist> getPlaylistsWithSongStatus(Long songId) {
        return playlistService.findAllWithSongStatus(songId);
    }

    public void createPlaylist(Playlist playlist) {
        playlistService.save(playlist);
    }

    public void updatePlaylist(Playlist playlist) {
        playlistService.save(playlist);
    }

    public void deletePlaylist(Playlist playlist) {
        playlistService.delete(playlist);
    }

    public Playlist getSelectedPlaylist(Long id) {
        return id == null ? null : playlistService.find(id);
    }

    public List<Song> getSongs() {
        return songService.findAll();
    }

    public SongService.PaginatedSongs getSongs(int page, int limit, String search, String sortBy, String sortDirection) {
        return songService.findAll(page, limit, search, sortBy, sortDirection);
    }

    public PlaylistService.PaginatedPlaylistSongs getSongsByPlaylist(Long playlistId, int page, int limit, String search, String sortBy, String sortDirection) {
        return playlistService.findSongsByPlaylist(playlistId, page, limit, search, sortBy, sortDirection);
    }

    public Song findSong(Long id) {
        return songService.find(id);
    }

    public Settings getSettings() {
        return currentSettings.getOrCreateSettings();
    }

    private String safeSummary(PlaybackState s, Long profileId) {
        if (s == null) {
            return "null for profile " + profileId;
        }
        return String.format("{profileId=%d, playing=%s, songId=%s, time=%.2f, vol=%.2f}",
                profileId, s.isPlaying(), s.getCurrentSongId(), s.getCurrentTime(), s.getVolume());
    }

    // Helper method to advance song
    private synchronized void advanceSong(boolean forward, boolean fromSongEnd, Long profileId) {
        PlaybackState st = getState(profileId);

        // Handle RepeatMode.ONE when song ends naturally
        if (fromSongEnd && st.getRepeatMode() == PlaybackState.RepeatMode.ONE) {
            st.setCurrentTime(0);
            st.setPlaying(true);
            updateState(profileId, st, true);
            return;
        }

        // Iterative approach to handle missing songs without recursion depth issues
        int maxAttempts = 2000;
        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            boolean skippedEarly = false;
            if (!fromSongEnd && st.getDuration() > 0) {
                double percentPlayed = (st.getCurrentTime() / st.getDuration()) * 100.0;
                if (percentPlayed < 20.0) {
                    skippedEarly = true;
                }
            }

            Long nextSongId = playbackQueueController.advance(st, forward, skippedEarly, profileId);

            if (nextSongId == null) {
                if (!forward) {
                    // We reached the beginning of the queue and want to go previous
                    List<Long> historyIds = playbackHistoryService.getRecentlyPlayedSongIds(10, profileId);
                    if (!historyIds.isEmpty()) {
                        Long songIdToPlay = null;
                        for (Long hId : historyIds) {
                            if (!hId.equals(st.getCurrentSongId())) {
                                songIdToPlay = hId;
                                break;
                            }
                        }
                        
                        if (songIdToPlay != null) {
                            Song historySong = findSong(songIdToPlay);
                            if (historySong != null) {
                                st.setCurrentSongId(songIdToPlay);
                                st.setArtistName(historySong.getArtist());
                                st.setSongName(historySong.getTitle());
                                st.setDuration(historySong.getDurationSeconds());
                                st.setPlaying(true);
                                st.setCurrentTime(0);
                                updateState(profileId, st, true);
                                return;
                            }
                        }
                    }
                }
                
                LOGGER.info("No next song found for profile " + profileId + ". Stopping playback.");
                st.setPlaying(false);
                st.setCurrentSongId(null);
                updateState(profileId, st, true);
                return;
            }

            Song newSong = findSong(nextSongId);
            if (newSong != null) {
                // Found a valid song!
                if (st.getCurrentSongId() != null && !st.getCurrentSongId().equals(nextSongId)) {
                    Song currentSong = findSong(st.getCurrentSongId());
                    if (currentSong != null) {
                        playbackHistoryService.add(currentSong, profileId);
                        ws.broadcastHistoryUpdate(profileId);
                    }
                }

                st.setCurrentSongId(nextSongId);
                st.setArtistName(newSong.getArtist());
                st.setSongName(newSong.getTitle());
                st.setDuration(newSong.getDurationSeconds());
                st.setPlaying(true);
                
                // Use DJ entry time if planned, otherwise start at 0
                Double entryTime = st.getDjEntryTime();
                st.setCurrentTime(entryTime != null ? entryTime : 0.0);
                
                // Plan DJ Mode transition if active (independent of Smart Shuffle)
                if (Boolean.TRUE.equals(st.getDjModeActive())) {
                    // Ensure crossfade is set for DJ mode (default 8s)
                    if (st.getCrossfadeDuration() == null || st.getCrossfadeDuration() == 0) {
                        st.setCrossfadeDuration(8);
                    }
                    clearDjTransitionPlan(st);
                    planNextDjTransition(st, profileId);
                }
                
                startPlaybackTimer(profileId);
                updateState(profileId, st, true);
                return;
            } else {
                LOGGER.warning("Song with ID " + nextSongId + " not found in database. Removing from queue and trying next (attempt " + (attempt + 1) + ")");
                // nextSongId was invalid, remove it from queue so we don't hit it again
                playbackQueueController.removeFromQueue(st, nextSongId, profileId);
                // the loop continues to the next attempt, advance() will be called again on the updated queue
            }
        }

        LOGGER.severe("Maximum attempts reached while trying to find a valid next song for profile " + profileId);
        stopPlayback(profileId);
    }

    public synchronized void next(Long profileId) {
        // DJ Mode is INDEPENDENT of Smart Shuffle - do NOT disable it on next
        PlaybackState st = getState(profileId);
        clearDjTransitionPlan(st);
        
        currentSettings.addLog("Skipped to next song.");
        advanceSong(true, false, profileId);
    }

    public synchronized void previous(Long profileId) {
        // DJ Mode is INDEPENDENT of Smart Shuffle - do NOT disable it on previous
        PlaybackState st = getState(profileId);
        clearDjTransitionPlan(st);
        
        currentSettings.addLog("Skipped to previous song.");

        // 1. If song has been playing for more than 3 seconds, just restart it.
        if (st.getCurrentTime() > 3) {
            st.setCurrentTime(0);
            updateState(profileId, st, true);
            return;
        }

        // 2. Try to go to previous in current queue (primary or secondary)
        advanceSong(false, false, profileId);
    }

    public synchronized void handleTransitionStarted(Long profileId) {
        PlaybackState st = getState(profileId);
        if (Boolean.TRUE.equals(st.getDjTransitionPlanned()) && st.getDjNextSongId() != null) {
            System.out.println("[DJ] Transition started on frontend, updating server state...");
            
            // Capture old song ID BEFORE overwriting currentSongId
            Long oldSongId = st.getCurrentSongId();
            Long nextSongId = st.getDjNextSongId();
            Double entryTime = st.getDjEntryTime();
            
            // Update history for current song
            if (oldSongId != null) {
                Song current = findSong(oldSongId);
                if (current != null) {
                    playbackHistoryService.add(current, profileId);
                    ws.broadcastHistoryUpdate(profileId);
                }
            }
            
            // Advance state immediately to the next song
            Song nextSong = findSong(nextSongId);
            if (nextSong != null) {
                st.setCurrentSongId(nextSongId);
                st.setArtistName(nextSong.getArtist());
                st.setSongName(nextSong.getTitle());
                st.setDuration(nextSong.getDurationSeconds());
                st.setCurrentTime(entryTime != null ? entryTime : 0);
                st.setPlaying(true);
                
                // Remove the OLD (played) song from the cue so the queue stays accurate
                if (st.getCue() != null && oldSongId != null) {
                    st.getCue().remove(oldSongId);
                }
                // Also clean up originalCue for shuffle consistency
                if (st.getOriginalCue() != null && oldSongId != null) {
                    st.getOriginalCue().remove(oldSongId);
                }
                
                // Set index to the next song (now at oldSongId's former position)
                if (st.getCue() != null) {
                    int idx = st.getCue().indexOf(nextSongId);
                    if (idx != -1) st.setCueIndex(idx);
                }
                
                clearDjTransitionPlan(st);
                
                // Plan next DJ transition for the NEW current song (if DJ Mode still active)
                if (Boolean.TRUE.equals(st.getDjModeActive())) {
                    System.out.println("[DJ] Planning next transition for new song: " + nextSong.getTitle());
                    planNextDjTransition(st, profileId);
                }
                
                updateState(profileId, st, true);
                System.out.println("[DJ] Server state advanced to " + nextSong.getTitle() + " at " + st.getCurrentTime() + "s");
            }
        }
    }

    public synchronized void handleSongEnded(Long profileId) {
        PlaybackState st = getState(profileId);
        
        System.out.println("=== Song Ended (natural) ===");
        System.out.println("  DJ Mode active: " + st.getDjModeActive());
        System.out.println("  DJ Transition planned: " + st.getDjTransitionPlanned());
        System.out.println("  ShuffleMode: " + st.getShuffleMode());
        
        // If DJ Mode was active and a transition was planned, keep DJ Mode active
        // The frontend handled the crossfade, we just need to clear the plan and let advanceSong re-plan
        if (Boolean.TRUE.equals(st.getDjModeActive()) && Boolean.TRUE.equals(st.getDjTransitionPlanned())) {
            System.out.println("[DJ] Transition completed, keeping DJ Mode active for next song");
            LOGGER.info("DJ Mode: Transition completed, planning next transition");
            st.setDjNextSongId(null);
            st.setDjExitTime(null);
            st.setDjTransitionPlanned(null);
            st.setDjTransitionConfidence(null);
            st.setDjTransitionReason(null);
            // Keep djEntryTime — advanceSong needs it for the next song's start position
            // Don't deactivate - keep DJ Mode running for continuous mixing
        } else if (Boolean.TRUE.equals(st.getDjModeActive())) {
            // DJ Mode was active but no transition was planned (e.g., song ended before trigger)
            // Keep DJ Mode active for the next song
            System.out.println("[DJ] No transition was planned, keeping DJ Mode active");
            st.setDjNextSongId(null);
            st.setDjExitTime(null);
            st.setDjTransitionPlanned(null);
            st.setDjTransitionConfidence(null);
            st.setDjTransitionReason(null);
            // Keep djEntryTime — advanceSong needs it for the next song's start position
        }
        
        currentSettings.addLog("Song ended naturally.");
        advanceSong(true, true, profileId); // Automatic advance due to song end
    }

    public synchronized void pausePlayback(Long profileId) {
        PlaybackState state = getState(profileId);
        // Remove the isPlaying() check to ensure we always force a stop/pause state
        state.setPlaying(false);
        stopPlaybackTimer(profileId);
        updateState(profileId, state, true);
        currentSettings.addLog("Playback paused (force).");
    }

    public synchronized void resumePlayback(Long profileId) {
        PlaybackState state = getState(profileId);
        if (state.isPlaying()) return;
        
        // If no song is selected, advance to get one
        if (state.getCurrentSongId() == null) {
            advanceSong(true, false, profileId);
            return;
        }

        state.setPlaying(true);
        startPlaybackTimer(profileId);
        updateState(profileId, state, true);
        currentSettings.addLog("Playback resumed (direct).");
    }

    public synchronized void togglePlay(Long profileId) {
        currentSettings.addLog("Playback toggled.");
        System.out.println("Toggle");
        PlaybackState state = getState(profileId);

        // If no song is selected and we want to start playing, trigger 'advance' to get a song
        if (state.getCurrentSongId() == null && !state.isPlaying()) {
            advanceSong(true, false, profileId);
            return; // advanceSong updates state and starts timer
        }

        playbackQueueController.togglePlay(state, profileId);

        if (state.isPlaying()) {
            startPlaybackTimer(profileId);
        } else {
            stopPlaybackTimer(profileId);
        }

        updateState(profileId, state, true);
    }

    /**
     * Cycles through shuffle modes: OFF, SHUFFLE, SMART_SHUFFLE
     */
    public synchronized void toggleShuffle(Long profileId) {
        PlaybackState state = getState(profileId);
        PlaybackState.ShuffleMode currentMode = state.getShuffleMode();
        if (currentMode == null) {
            currentMode = PlaybackState.ShuffleMode.OFF;
        }
        PlaybackState.ShuffleMode newMode;

        // Clear any stale DJ transition plan before reorganizing the queue
        clearDjTransitionPlan(state);

        switch (currentMode) {
            case OFF:
                newMode = PlaybackState.ShuffleMode.SHUFFLE;
                playbackQueueController.initShuffle(state, profileId);
                break;
            case SHUFFLE:
                newMode = PlaybackState.ShuffleMode.SMART_SHUFFLE;
                playbackQueueController.initSmartShuffle(state, profileId);
                break;
            case SMART_SHUFFLE:
            default:
                newMode = PlaybackState.ShuffleMode.OFF;
                playbackQueueController.clearShuffle(state, profileId);
                break;
        }

        state.setShuffleMode(newMode);

        // Re-plan DJ transition for the reorganized queue if DJ mode is active
        if (Boolean.TRUE.equals(state.getDjModeActive())) {
            planNextDjTransition(state, profileId);
        }

        currentSettings.addLog("Shuffle mode set to: " + newMode);
        updateState(profileId, state, true);
    }

    /**
     * Sets DJ Mode to a specific state (not a toggle).
     * Used by the frontend to restore DJ Mode on page reload without toggling.
     */
    public synchronized void setDjMode(Long profileId, boolean active) {
        PlaybackState state = getState(profileId);
        boolean wasActive = Boolean.TRUE.equals(state.getDjModeActive());
        
        if (wasActive == active) {
            // Already in the desired state, no change needed - but still broadcast
            // to ensure client is in sync
            updateState(profileId, state, true);
            return;
        }
        
        state.setDjModeActive(active);
        
        if (active) {
            // Activate DJ Mode
            state.setOriginalCrossfadeDuration(state.getCrossfadeDuration());
            if (state.getCrossfadeDuration() == null || state.getCrossfadeDuration() == 0) {
                state.setCrossfadeDuration(8);
            }
            planNextDjTransition(state, profileId);
            currentSettings.addLog("DJ Mode activated (set)");
        } else {
            // Deactivate DJ Mode
            Integer originalCrossfade = state.getOriginalCrossfadeDuration();
            state.setCrossfadeDuration(originalCrossfade != null ? originalCrossfade : 0);
            state.setOriginalCrossfadeDuration(0);
            clearDjTransitionPlan(state);
            currentSettings.addLog("DJ Mode deactivated (set)");
        }
        
        // Force-persist immediately (don't rely on throttled maybePersist)
        playbackPersistenceController.persist(profileId, state, true);
        updateState(profileId, state, true);
    }

    /**
     * Toggles DJ Mode independently of shuffle mode.
     */
    public synchronized void toggleDjMode(Long profileId) {
        PlaybackState state = getState(profileId);
        boolean wasActive = Boolean.TRUE.equals(state.getDjModeActive());
        boolean newActive = !wasActive;
        
        state.setDjModeActive(newActive);
        
        if (newActive) {
            // Activate DJ Mode
            state.setOriginalCrossfadeDuration(state.getCrossfadeDuration());
            if (state.getCrossfadeDuration() == null || state.getCrossfadeDuration() == 0) {
                state.setCrossfadeDuration(8);
            }
            // Plan transition for current song
            planNextDjTransition(state, profileId);
            currentSettings.addLog("DJ Mode activated manually");
        } else {
            // Deactivate DJ Mode
            Integer originalCrossfade = state.getOriginalCrossfadeDuration();
            state.setCrossfadeDuration(originalCrossfade != null ? originalCrossfade : 0);
            state.setOriginalCrossfadeDuration(0);
            clearDjTransitionPlan(state);
            currentSettings.addLog("DJ Mode deactivated manually");
        }
        
        // Force-persist immediately - DJ mode must survive page reloads
        playbackPersistenceController.persist(profileId, state, true);
        updateState(profileId, state, true);
    }

    /**
     * Cycles through repeat modes: OFF, ONE, ALL.
     */
    public synchronized void toggleRepeat(Long profileId) {
        PlaybackState state = getState(profileId);
        playbackQueueController.toggleRepeat(state, profileId);
        currentSettings.addLog("Repeat mode toggled to: " + state.getRepeatMode());
        updateState(profileId, state, true);
    }

    /**
     * Sets the playback volume (0.0 - 1.0) and persists state
     */
    public synchronized void changeVolume(float level, Long profileId) {
        // Normalize volume to [0.0, 1.0]
        if (level < 0f || Float.isNaN(level)) {
            level = 0f;
        } else if (level > 1f) {
            level = 1f;
        }
        PlaybackState st = getState(profileId);
        playbackQueueController.changeVolume(st, level, profileId);
        updateState(profileId, st, true);
    }

    /**
     * Sets the playback position in seconds and persists state
     */
    public synchronized void setSeconds(double seconds, Long profileId) {
        System.out.println("[PlaybackController] setSeconds called with: " + seconds + " for profile: " + profileId);
        PlaybackState st = getState(profileId);
        synchronized (st) {
            playbackQueueController.setSeconds(st, seconds, profileId);
            
            // Clear and re-plan DJ transition on seek to align with new position
            if (Boolean.TRUE.equals(st.getDjModeActive())) {
                clearDjTransitionPlan(st);
                planNextDjTransition(st, profileId);
            }
        }
        System.out.println("[PlaybackController] PlaybackState currentTime after setSeconds for profile " + profileId + ": " + st.getCurrentTime());
        updateState(profileId, st, true);
    }

    /**
     * Sets the crossfade duration in seconds
     */
    public synchronized void setCrossfadeDuration(int seconds, Long profileId) {
        PlaybackState st = getState(profileId);
        st.setCrossfadeDuration(Math.max(0, Math.min(10, seconds)));
        updateState(profileId, st, true);
    }

    /**
     * Gets the crossfade duration in seconds
     */
    public synchronized int getCrossfadeDuration(Long profileId) {
        PlaybackState st = getState(profileId);
        return st.getCrossfadeDuration();
    }

    /**
     * Returns the currently playing song, or null if none
     */
    public synchronized Song getCurrentSong(Long profileId) {
        PlaybackState st = getState(profileId);
        Long currentId = st.getCurrentSongId();
        if (currentId != null) {
            return findSong(currentId);
        }
        // If no currentId, fallback to first song in cue or playlist
        List<Long> cue = st.getCue();
        if (cue != null && !cue.isEmpty()) {
            return findSong(cue.get(0));
        }
        List<Song> allSongs = getSongs();
        return allSongs.isEmpty() ? null : allSongs.get(0);
    }

    /**
     * Returns the previous song in the queue, or null if none.
     */
    public synchronized Song getPreviousSong(Long profileId) {
        PlaybackState st = getState(profileId);
        List<Long> cue = st.getCue();
        int cueIndex = st.getCueIndex();

        if (cue == null || cue.isEmpty() || cueIndex <= 0) {
            // If no previous song in queue, try to get from history
            PlaybackHistory lastPlayed = PlaybackHistory.find("order by playedAt desc").firstResult(); // This needs to be profile-aware
            if (lastPlayed != null && lastPlayed.song != null) {
                return lastPlayed.song;
            }
            return null; // No previous song in queue or history
        }
        Long prevSongId = cue.get(cueIndex - 1);
        return findSong(prevSongId);
    }

    /**
     * Returns the next song in the queue, or null if none.
     */
    public synchronized Song getNextSong(Long profileId) {
        PlaybackState st = getState(profileId);
        List<Long> cue = st.getCue();
        int cueIndex = st.getCueIndex();

        if (cue == null || cue.isEmpty() || cueIndex >= cue.size() - 1) {
            return null; // No next song
        }
        Long nextSongId = cue.get(cueIndex + 1);
        return findSong(nextSongId);
    }

    public synchronized void replaceQueueWithPlaylist(Playlist playlist, Long profileId) {
        PlaybackState st = getState(profileId);
        populateCueFromPlaylist(st, playlist, profileId);
        updateState(profileId, st, true); // persist + broadcast
    }

    private void populateCueFromPlaylist(PlaybackState st, Playlist playlist, Long profileId) {
        if (playlist == null) {
            // Deselect any playlist
            st.setCurrentPlaylistId(null);

            List<Long> cue = st.getCue();
            // If queue is empty, populate with all songs
            if (cue == null || cue.isEmpty()) {
                List<Song> allSongs = getSongs();
                List<Long> allSongIds = allSongs.stream().map(s -> s.id).toList();
                playbackQueueController.populateCue(st, allSongIds, profileId);
            }

        } else {
            // Set the current playlist normally
            st.setCurrentPlaylistId(playlist.id);

            // Re-fetch playlist with songs to avoid LazyInitializationException
            Playlist fullPlaylist = findPlaylistWithSongs(playlist.id);

            if (fullPlaylist == null || fullPlaylist.getSongs() == null || fullPlaylist.getSongs().isEmpty()) {
                playbackQueueController.clear(st, profileId); // clear queue if no songs
                updateState(profileId, st, true);
                return;
            }

            if (fullPlaylist != null) {
                List<Long> cue = fullPlaylist.getSongs().stream().map(s -> s.id).toList();
                playbackQueueController.populateCue(st, cue, profileId);
            } else {
                // empty playlist → stop playback
                playbackQueueController.clear(st, profileId);
            }
        }
    }

    // -----------------------------
// Queue management helpers
// -----------------------------
    /**
     * Adds one or more songs to the current queue. If `playNext` is true, songs
     * are inserted after the current index. Otherwise, appended to the end.
     */
    public synchronized void addToQueue(List<Long> songIds, boolean playNext, Long profileId) {
        if (songIds == null || songIds.isEmpty()) {
            return;
        }

        PlaybackState st = getState(profileId);
        playbackQueueController.addToQueue(st, songIds, playNext, profileId);
        updateState(profileId, st, true);
    }

    /**
     * Removes a specific song from the queue. If it's currently playing, moves
     * to next or stops.
     */
    public synchronized void removeFromQueue(Long songId, Long profileId) {
        PlaybackState st = getState(profileId);
        playbackQueueController.removeFromQueue(st, songId, profileId);
        updateState(profileId, st, true);
    }

    /**
     * Clears the entire queue and stops playback.
     */
    public synchronized void clearQueue(Long profileId) {
        PlaybackState st = getState(profileId);
        playbackQueueController.clear(st, profileId);
        updateState(profileId, st, true);
    }

    /**
     * Moves a song within the queue (drag-and-drop style reordering).
     */
    public synchronized void moveInQueue(int fromIndex, int toIndex, Long profileId) {
        PlaybackState st = getState(profileId);
        playbackQueueController.moveInQueue(st, fromIndex, toIndex, profileId);
        updateState(profileId, st, true);
    }

    /**
     * Sets the currently selected playlist for UI purposes only. Does NOT alter
     * the current queue or playback.
     */
    public synchronized void selectPlaylistForBrowsing(Playlist playlist, Long profileId) {
        PlaybackState st = getState(profileId);
        st.setCurrentPlaylistId(playlist != null ? playlist.id : null);
        updateState(profileId, st, true); // only persists & broadcasts selection
    }

    /**
     * Adds all songs from a playlist to the queue.
     *
     * @param playlist the playlist whose songs to add
     * @param playNext if true, insert after current song; else append at end
     */
    public synchronized void addPlaylistToQueue(Playlist playlist, boolean playNext, Long profileId) {
        if (playlist == null) {
            return;
        }
        // Re-fetch playlist with songs to avoid LazyInitializationException
        Playlist fullPlaylist = findPlaylistWithSongs(playlist.id);

        if (fullPlaylist == null || fullPlaylist.getSongs() == null || fullPlaylist.getSongs().isEmpty()) {
            return;
        }
        List<Long> songIds = fullPlaylist.getSongs().stream()
                .map(s -> s.id)
                .toList();
        addToQueue(songIds, playNext, profileId);
    }

    public synchronized List<Song> getQueue(Long profileId) {
        PlaybackState st = getState(profileId);
        List<Long> cueIds = st.getCue();
        if (cueIds == null) {
            return new ArrayList<>();
        }
        return cueIds.stream()
                .map(this::findSong)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toList());
    }

    public record PaginatedQueue(List<Song> songs, int totalSize) {

    }

    public PaginatedQueue getQueuePage(int page, int limit, Long profileId) {
        return getQueuePage(page, limit, profileId, "");
    }

    public PaginatedQueue getQueuePage(int page, int limit, Long profileId, String search) {
        PlaybackState st = getState(profileId);
        List<Long> cueIds;
        synchronized(st) {
            cueIds = new ArrayList<>(st.getCue());
        }
        
        if (cueIds.isEmpty()) {
            return new PaginatedQueue(new ArrayList<>(), 0);
        }

        // If no search, use original pagination logic
        if (search == null || search.isBlank()) {
            int totalSize = cueIds.size();
            int fromIndex = (page - 1) * limit;
            int toIndex = Math.min(fromIndex + limit, totalSize);

            if (fromIndex >= totalSize) {
                return new PaginatedQueue(new ArrayList<>(), totalSize);
            }

            List<Long> pageOfIds = cueIds.subList(fromIndex, toIndex);
            List<Song> songs = songService.findByIds(pageOfIds);
            return new PaginatedQueue(songs, totalSize);
        }

        // With search: get all songs, filter, then paginate
        List<Song> allSongs = songService.findByIds(cueIds);
        String searchLower = search.toLowerCase();
        
        List<Song> filtered = allSongs.stream()
                .filter(s -> (s.getTitle() != null && s.getTitle().toLowerCase().contains(searchLower)) ||
                             (s.getArtist() != null && s.getArtist().toLowerCase().contains(searchLower)))
                .collect(Collectors.toList());

        int totalSize = filtered.size();
        int fromIndex = (page - 1) * limit;
        int toIndex = Math.min(fromIndex + limit, totalSize);

        if (fromIndex >= totalSize || filtered.isEmpty()) {
            return new PaginatedQueue(new ArrayList<>(), totalSize);
        }

        return new PaginatedQueue(filtered.subList(fromIndex, toIndex), totalSize);
    }

    public synchronized void skipToQueueIndex(int index, Long profileId) {
        LOGGER.info("skipToQueueIndex called with index: " + index + " for profile: " + profileId);
        PlaybackState st = getState(profileId);
        LOGGER.info("skipToQueueIndex: Original cue for profile " + profileId + ": " + st.getCue());
        playbackQueueController.skipToQueueIndex(st, index, profileId);

        Long songId = st.getCurrentSongId();
        LOGGER.info("skipToQueueIndex: Setting current songId to: " + songId + " for profile: " + profileId);
        Song newSong = findSong(songId);
        LOGGER.info("skipToQueueIndex: Found song for ID " + songId + " for profile " + profileId + ": " + (newSong != null ? newSong.getTitle() : "null"));
        st.setArtistName(newSong != null ? newSong.getArtist() : "Unknown Artist");
        st.setSongName(newSong != null ? newSong.getTitle() : "Unknown Title");
        st.setDuration(newSong != null ? newSong.getDurationSeconds() : 0);
        updateState(profileId, st, true);
    }

    public synchronized void removeFromQueue(int index, Long profileId) {
        PlaybackState st = getState(profileId);
        List<Long> cue = st.getCue();
        if (cue == null || cue.isEmpty() || index < 0 || index >= cue.size()) {
            return;
        }
        Long songIdToRemove = cue.get(index);
        playbackQueueController.removeFromQueue(st, songIdToRemove, profileId);
        updateState(profileId, st, true);
    }

    public List<PlaybackHistory> getHistory(int page, int pageSize, Long profileId) {
        return playbackHistoryService.getHistory(page, pageSize, profileId);
    }

    public void toggleSongInPlaylist(Long playlistId, Long songId, Long profileId) {
        //TODO should remove the song from a playlist or add it via playlistController -> service
    }

    /**
     * Called by AnalysisWorker after a song finishes analysis.
     * Re-plans the DJ transition for any active DJ mode profile that has
     * this song as the planned next song (djNextSongId).
     * This enables event-driven re-planning when analysis completes asynchronously.
     */
    public synchronized void replanDjTransitionsForAnalyzedSong(Long songId) {
        for (Long profileId : memoryStates.keySet()) {
            PlaybackState st = memoryStates.get(profileId);
            if (st != null && Boolean.TRUE.equals(st.getDjModeActive()) && songId.equals(st.getDjNextSongId())) {
                System.out.println("[DJ] Song " + songId + " just analyzed, re-planning transition for profile " + profileId);
                planNextDjTransition(st, profileId);
            }
        }
    }

    /**
     * Plan the next DJ Mode transition for the current song.
     * Called after advanceSong when DJ Mode is active.
     * First tries to position a BPM-matched song as the next in queue via prepareDjModeTransition(),
     * then calculates the beat-aligned crossfade to that song.
     */
    private void planNextDjTransition(PlaybackState st, Long profileId) {
        // DJ Mode is INDEPENDENT of Smart Shuffle - plan transition if DJ Mode is active
        if (!Boolean.TRUE.equals(st.getDjModeActive())) {
            System.out.println("[DJ] planNextDjTransition: DJ Mode not active, skipping");
            return;
        }

        // STEP 1: Try to find a BPM-matched next song for a better mix
        Settings settings = currentSettings.getOrCreateSettings();
        if (settings.getDjModeBpmTolerance() != null && settings.getDjModeBpmTolerance() > 0) {
            playbackQueueController.prepareDjModeTransition(st, profileId, settings.getDjModeBpmTolerance());
        }

        // STEP 2: Re-read the cue and next song (prepareDjModeTransition may have updated it)
        List<Long> cue = st.getCue();
        int cueIndex = st.getCueIndex();
        if (cue == null || cueIndex < 0 || cueIndex >= cue.size() - 1) {
            System.out.println("[DJ] planNextDjTransition: No next song in queue (cueIndex=" + cueIndex + ", size=" + (cue != null ? cue.size() : 0) + ")");
            return;
        }

        Long nextSongId = cue.get(cueIndex + 1);
        Song currentSong = findSong(st.getCurrentSongId());
        Song nextSong = findSong(nextSongId);

        if (currentSong == null || nextSong == null) {
            System.out.println("[DJ] planNextDjTransition: Song not found (current=" + currentSong + ", next=" + nextSong + ")");
            return;
        }

        boolean currentAnalyzed = audioAnalysisService.isAnalyzed(currentSong.id);
        boolean nextAnalyzed = audioAnalysisService.isAnalyzed(nextSongId);
        System.out.println("[DJ] planNextDjTransition: '" + currentSong.getTitle() + "' → '" + nextSong.getTitle() + "'");
        System.out.println("[DJ]   Current analyzed: " + currentAnalyzed + ", Next analyzed: " + nextAnalyzed);

        // STEP 3: Pre-analyze the next song if needed (triggers async analysis)
        if (!nextAnalyzed) {
            audioAnalysisService.ensureUpcomingSongsAnalyzed(cue.subList(cueIndex, cue.size()), 3);
        }

        if (!currentAnalyzed || !nextAnalyzed) {
            System.out.println("[DJ] planNextDjTransition: Songs not yet analyzed, keeping transition unplanned");
            // Keep djNextSongId so replanDjTransitionsForAnalyzedSong can find it
            // when async analysis completes. Clear only the calculated transition fields.
            if (st.getDjNextSongId() == null) {
                st.setDjNextSongId(nextSongId);
            }
            st.setDjEntryTime(null);
            st.setDjExitTime(null);
            st.setDjTransitionPlanned(null);
            st.setDjTransitionConfidence(null);
            st.setDjTransitionReason(null);
            return;
        }

        int crossfadeSeconds = st.getCrossfadeDuration() != null ? st.getCrossfadeDuration() : 8;
        DjTransition transition = djTransitionService.calculateTransition(currentSong, nextSong, crossfadeSeconds);

        if (transition != null) {
            st.setDjNextSongId(nextSongId);
            st.setDjEntryTime(transition.getEntryTime());
            st.setDjExitTime(transition.getExitTime());
            st.setDjTransitionPlanned(true);
            st.setDjTransitionConfidence(transition.getConfidence());
            st.setDjTransitionReason(transition.getReason());
            System.out.println("[DJ] === TRANSITION PLANNED ===");
            System.out.println("[DJ]   Exit: " + currentSong.getTitle() + " at " + transition.getExitTime() + "s");
            System.out.println("[DJ]   Entry: " + nextSong.getTitle() + " at " + transition.getEntryTime() + "s");
            System.out.println("[DJ]   Crossfade: " + transition.getCrossfadeSeconds() + "s, Confidence: " + transition.getConfidence());
            System.out.println("[DJ]   Reason: " + transition.getReason());
            LOGGER.info(String.format("DJ Mode: Planned transition to '%s' at exit=%.1fs, entry=%.1fs (confidence=%.2f)",
                    nextSong.getTitle(), transition.getExitTime(), transition.getEntryTime(), transition.getConfidence()));
        } else {
            clearDjTransitionPlan(st);
            System.out.println("[DJ] planNextDjTransition: Could not calculate transition");
            LOGGER.fine("DJ Mode: Could not calculate transition, using normal playback");
        }
    }

}
