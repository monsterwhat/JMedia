package Controllers;

import API.WS.MusicSocket;
import Models.PlaybackHistory;
import Models.PlaybackState;
import Models.Playlist;
import Models.Profile;
import Models.Settings;
import Models.Song;
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

    @Transactional // Ensure database operations run in a transaction
    protected void processPlaybackTick(Long profileId) {
        try {
            PlaybackState st = getState(profileId);
            if (st.isPlaying() && st.getCurrentSongId() != null) {
                double newTime = st.getCurrentTime() + (PLAYBACK_UPDATE_INTERVAL_MS / 1000.0);
                if (newTime >= st.getDuration() && st.getDuration() > 0) {
                    // Song ended naturally, advance to next
                    handleSongEnded(profileId);
                } else {
                    st.setCurrentTime(newTime);
                    broadcastStateIfNecessary(st, profileId); // Use throttled broadcast
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

            // Ensure a valid initial state if cue is empty or currentSongId is null
            if (state.getCue().isEmpty()) {
                List<Song> allSongs = getSongs(); // This still needs to be profile-aware or context-aware
                if (!allSongs.isEmpty()) {
                    state.setCue(allSongs.stream().map(s -> s.id).collect(Collectors.toList()));
                    state.setCueIndex(0);
                    state.setCurrentSongId(allSongs.get(0).id);
                }
            }

            if (state.getCurrentSongId() == null && !state.getCue().isEmpty()) {
                state.setCurrentSongId(state.getCue().get(0));
                state.setCueIndex(0);
            }

            state.setPlaying(false);
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

        if (newState.getCurrentSongId() != null
                && newState.getCurrentSongId().equals(currentState.getCurrentSongId())
                && newState.getCurrentTime() == 0) {
            newState.setCurrentTime(currentState.getCurrentTime());
        }

         
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
                    state.setCurrentTime(node.get("value").asDouble());
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
    private synchronized void advanceSong(boolean forward, boolean fromSongEnd, Long profileId) { // Added fromSongEnd parameter
        PlaybackState st = getState(profileId);

        // Populate cue if empty (initial state)
        if (st.getCue() == null || st.getCue().isEmpty()) {
            List<Song> allSongs = getSongs();
            List<Long> allSongIds = allSongs.stream().map(s -> s.id).toList();
            playbackQueueController.populateCue(st, allSongIds, profileId);
            if (st.getCue().isEmpty()) {
                stopPlayback(profileId);
                return;
            }
        }

        // Handle RepeatMode.ONE when song ends naturally
        if (fromSongEnd && st.getRepeatMode() == PlaybackState.RepeatMode.ONE) {
            st.setCurrentTime(0); // Restart current song
            st.setPlaying(true); // Ensure it keeps playing
            updateState(profileId, st, true); // Persist and broadcast
            return;
        }

        Long nextSongId = playbackQueueController.advance(st, forward, profileId);

        if (nextSongId == null) {
            stopPlayback(profileId);
            return;
        }

        // Save the current song to history before advancing to the next one
        if (st.getCurrentSongId() != null) {
            Song currentSong = findSong(st.getCurrentSongId());
            if (currentSong != null) {
                playbackHistoryService.add(currentSong, profileId);
                // Broadcast history update to all connected clients
                ws.broadcastHistoryUpdate(profileId);
            }
        }

        st.setCurrentSongId(nextSongId);
        Song newSong = findSong(nextSongId);
        st.setArtistName(newSong != null ? newSong.getArtist() : "Unknown Artist");
        st.setSongName(newSong != null ? newSong.getTitle() : "Unknown Title");
        st.setDuration(newSong != null ? newSong.getDurationSeconds() : 0);
        st.setPlaying(true);
        st.setCurrentTime(0); // Always reset time for a new song
        startPlaybackTimer(profileId); // Ensure timer is running for new song

        updateState(profileId, st, true); // persists + broadcasts
    }

    public synchronized void next(Long profileId) {
        currentSettings.addLog("Skipped to next song.");
        System.out.println("Next");
        advanceSong(true, false, profileId); // Explicit user action
    }

    public synchronized void previous(Long profileId) {
        currentSettings.addLog("Skipped to previous song.");
        System.out.println("Previous");
        PlaybackState st = getState(profileId);

        // If song has been playing for more than 3 seconds, just restart it.
        if (st.getCurrentTime() > 3) {
            st.setCurrentTime(0);
            updateState(profileId, st, true);
            return;
        }

        // Try to go to previous in queue first
        if (st.getCueIndex() > 0) {
            advanceSong(false, false, profileId);
            return;
        }

// We are at the beginning of the queue, try history.
        List<Long> historyIds = playbackHistoryService.getRecentlyPlayedSongIds(2, profileId);

        Long songIdToPlay = null;
        if (!historyIds.isEmpty()) {
            if (historyIds.get(0).equals(st.getCurrentSongId()) && historyIds.size() > 1) {
                songIdToPlay = historyIds.get(1);
            } else if (!historyIds.get(0).equals(st.getCurrentSongId())) {
                songIdToPlay = historyIds.get(0);
            }
        }

        if (songIdToPlay != null) {
            Song songFromHistory = findSong(songIdToPlay);
            if (songFromHistory != null) {
                // Save the current song to history before playing from history
                if (st.getCurrentSongId() != null) {
                    Song currentSong = findSong(st.getCurrentSongId());
                    if (currentSong != null && !currentSong.id.equals(songFromHistory.id)) {
                        playbackHistoryService.add(currentSong, profileId);
                        // Broadcast history update to all connected clients
                        ws.broadcastHistoryUpdate(profileId);
                    }
                }

                // We found a song in history. Let's play it.
                // We should also probably put it at the beginning of the cue.
                List<Long> cue = st.getCue();
                if (cue == null) {
                    cue = new ArrayList<>();
                    st.setCue(cue);
                }

                Long songId = songFromHistory.id;

                // remove from cue if it exists
                cue.remove(songId);
                // add to beginning
                cue.add(0, songId);
                st.setCueIndex(0);

                st.setCurrentSongId(songId);
                st.setCurrentTime(0);
                st.setPlaying(true);

                // Need to update song details in state
                st.setArtistName(songFromHistory.getArtist());
                st.setSongName(songFromHistory.getTitle());
                st.setDuration(songFromHistory.getDurationSeconds());

                updateState(profileId, st, true);
                startPlaybackTimer(profileId);
                return; // We are done
            }
        }

        // No previous song in queue and no suitable history, just restart current song.
        st.setCurrentTime(0);
        updateState(profileId, st, true);
    }

    @Transactional
    public synchronized void handleSongEnded(Long profileId) {
        currentSettings.addLog("Song ended naturally.");
        System.out.println("Song Ended");
        advanceSong(true, true, profileId); // Automatic advance due to song end
    }

    @Transactional
    public synchronized void togglePlay(Long profileId) {
        currentSettings.addLog("Playback toggled.");
        System.out.println("Toggle");
        PlaybackState state = getState(profileId);
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
    @Transactional
    public synchronized void toggleShuffle(Long profileId) {
        PlaybackState state = getState(profileId);
        PlaybackState.ShuffleMode currentMode = state.getShuffleMode();
        if (currentMode == null) {
            currentMode = PlaybackState.ShuffleMode.OFF;
        }
        PlaybackState.ShuffleMode newMode;

        switch (currentMode) {
            case OFF:
                newMode = PlaybackState.ShuffleMode.SHUFFLE;
                playbackQueueController.initShuffle(state, profileId);
                break;
            case SHUFFLE:
                newMode = PlaybackState.ShuffleMode.SMART_SHUFFLE;
                playbackQueueController.initSmartShuffle(state, profileId); // New method call
                break;
            case SMART_SHUFFLE:
            default:
                newMode = PlaybackState.ShuffleMode.OFF;
                playbackQueueController.clearShuffle(state, profileId);
                break;
        }

        state.setShuffleMode(newMode);
        currentSettings.addLog("Shuffle mode set to: " + newMode);
        updateState(profileId, state, true);
    }

    /**
     * Cycles through repeat modes: OFF, ONE, ALL.
     */
    @Transactional
    public synchronized void toggleRepeat(Long profileId) {
        PlaybackState state = getState(profileId);
        playbackQueueController.toggleRepeat(state, profileId);
        currentSettings.addLog("Repeat mode toggled to: " + state.getRepeatMode());
        updateState(profileId, state, true);
    }

    /**
     * Sets the playback volume (0.0 - 1.0) and persists state
     */
    @Transactional
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
    @Transactional
    public synchronized void setSeconds(double seconds, Long profileId) {
        System.out.println("[PlaybackController] setSeconds called with: " + seconds + " for profile: " + profileId);
        PlaybackState st = getState(profileId);
        playbackQueueController.setSeconds(st, seconds, profileId);
        System.out.println("[PlaybackController] PlaybackState currentTime after setSeconds for profile " + profileId + ": " + st.getCurrentTime());
        updateState(profileId, st, true);
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
        PlaybackState st = getState(profileId);
        List<Long> cueIds = st.getCue();
        if (cueIds == null || cueIds.isEmpty()) {
            return new PaginatedQueue(new ArrayList<>(), 0);
        }

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

}
