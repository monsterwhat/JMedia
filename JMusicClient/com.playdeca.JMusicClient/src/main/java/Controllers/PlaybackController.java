package Controllers;

import API.WS.MusicSocket;
import Models.PlaybackState;
import Models.Playlist;
import Models.Settings;
import Models.Song;
import Services.PlaybackStateService;
import Services.PlaylistService;
import Services.SongService;
import Services.PlaybackHistoryService;
import Models.PlaybackHistory;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;
import java.util.stream.Collectors;

@ApplicationScoped
public class PlaybackController {

    private static final long MIN_SAVE_INTERVAL_MS = 1000; // 1 second between saves

    private final ObjectMapper mapper = new ObjectMapper();
    private PlaybackState memoryState;
    private long lastSaveTime = 0;
    private List<Long> shuffledCue;
    private int shuffledCueIndex = -1;

    @Inject
    PlaybackStateService playbackStateService;
    @Inject
    SettingsController currentSettings;
    @Inject
    SongService songService;
    @Inject
    PlaylistService playlistService;
    @Inject
    MusicSocket ws;
    @Inject
    PlaybackHistoryService playbackHistoryService;

    private static final Logger LOGGER = Logger.getLogger(PlaybackController.class.getName());

    public PlaybackController() {
        System.out.println("[PlaybackController] PlaybackController instance created");
    }

    @PostConstruct
    public void init() {
        memoryState = playbackStateService.getState();
        if (memoryState == null) {
            memoryState = new PlaybackState();
        }

        // Ensure a valid initial state if cue is empty or currentSongId is null
        if (memoryState.getCue().isEmpty()) {
            List<Song> allSongs = getSongs();
            if (!allSongs.isEmpty()) {
                memoryState.setCue(allSongs.stream().map(s -> s.id).collect(Collectors.toList()));
                memoryState.setCueIndex(0);
                memoryState.setCurrentSongId(allSongs.get(0).id);
            }
        }

        if (memoryState.getCurrentSongId() == null && !memoryState.getCue().isEmpty()) {
            memoryState.setCurrentSongId(memoryState.getCue().get(0));
            memoryState.setCueIndex(0);
        }

        memoryState.setPlaying(false); // Always start in a paused state
        updateState(memoryState, false); // Persist initial state without broadcasting

        System.out.println("[PlaybackController] Initial state loaded: " + safeSummary(memoryState));
    }

    @PreDestroy
    public void onShutdown() {
        System.out.println("[PlaybackController] Shutdown: forcing final persist...");
        persistMemoryState(true);
    }

    // -----------------------------
    // Playback state methods
    // -----------------------------
    public synchronized PlaybackState getState() {
        if (memoryState == null) {
            memoryState = playbackStateService.getState();
            if (memoryState == null) {
                memoryState = new PlaybackState();
            }
        }
        return memoryState;
    }

    public synchronized void updateState(PlaybackState newState, boolean shouldBroadcast) {
        if (memoryState == null) {
            memoryState = playbackStateService.getState();
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
                && newState.getCurrentSongId().equals(memoryState.getCurrentSongId())
                && newState.getCurrentTime() == 0) {
            newState.setCurrentTime(memoryState.getCurrentTime());
        }

        if (newState.getCue() == null) {
            newState.setCue(new ArrayList<>());
        }
        if (newState.getLastSongs() == null) {
            newState.setLastSongs(new ArrayList<>());
        }

        memoryState = newState;

        maybePersistThrottled(); // persist only

        if (shouldBroadcast && ws != null) {
            ws.broadcastAll();
        }
    }

    // Updated maybePersistThrottled()
    private synchronized void maybePersistThrottled() {
        long now = System.currentTimeMillis();
        if (now - lastSaveTime >= MIN_SAVE_INTERVAL_MS) {
            persistMemoryState(false);
        }
    }

    private PlaybackState lastPersistedState = null;

    // Updated persistMemoryState()
    private synchronized void persistMemoryState(boolean force) {
        if (memoryState == null) {
            return;
        }
        long now = System.currentTimeMillis();

        if (!force && now - lastSaveTime < MIN_SAVE_INTERVAL_MS) {
            return;
        }

        // Avoid persisting if nothing has changed
        if (!force && memoryState.equals(lastPersistedState)) {
            return;
        }

        System.out.println("[PlaybackController] Persisting in-memory state: " + safeSummary(memoryState));

        playbackStateService.updateState(memoryState);

        try {
            lastPersistedState = mapper.readValue(mapper.writeValueAsString(memoryState), PlaybackState.class);
        } catch (IOException e) {
            System.out.println("Error Persisting memory!");
            lastPersistedState = memoryState;
        }

        lastSaveTime = now;

    }

    // -----------------------------
    // JSON serialization/deserialization
    // -----------------------------
    public PlaybackState fromJson(String json) throws IOException {
        if (json == null || json.isEmpty()) {
            return new PlaybackState();
        }
        return mapper.readValue(json, PlaybackState.class);
    }

    public synchronized String toJson() {
        try {
            return mapper.writeValueAsString(getState());
        } catch (IOException e) {
            System.err.println("[PlaybackController] ❌ Failed to serialize state: " + e.getMessage());
            return "{}";
        }
    }

    public synchronized void applyMessage(String json) throws IOException {
        if (json == null || json.isEmpty()) {
            return;
        }

        var node = mapper.readTree(json);
        if (node.has("action")) {
            handleAction(node);
        } else {
            PlaybackState incoming = mapper.treeToValue(node, PlaybackState.class);
            incoming.setVolume(memoryState.getVolume()); // preserve existing
            updateState(incoming, true);
        }
    }

    public synchronized void selectSong(Long id) {
        PlaybackState st = getState();
        Song current = getCurrentSong();

        if (current != null && current.id.equals(id)) {
            // Toggle play/pause
            st.setPlaying(!st.isPlaying());
            currentSettings.addLog("Playback toggled for song: " + current.getTitle());
        } else {
            st.setCurrentSongId(id);
            Song newSong = findSong(id);
            st.setArtistName(newSong != null ? newSong.getArtist() : "Unknown Artist");
            st.setSongName(newSong != null ? newSong.getTitle() : "Unknown Title");
            st.setDuration(newSong != null ? newSong.getDurationSeconds() : 0);
            st.setPlaying(true);
            st.setCurrentTime(0);
            currentSettings.addLog("Song selected: " + (newSong != null ? newSong.getTitle() : "Unknown Title"));
            if (newSong != null) {
                playbackHistoryService.add(newSong);
            }

            addSongToCueIfNotPresent(st, id);

            // Update recent songs
            List<Long> last = st.getLastSongs();
            if (last == null) {
                last = new ArrayList<>();
            }
            last.add(id);
            if (last.size() > 5) {
                last.remove(0);
            }
            st.setLastSongs(last);
        }

        updateState(st, true);
        System.out.println("Updated Selection");
    }

    private void addSongToCueIfNotPresent(PlaybackState st, Long songId) {
        List<Long> cue = st.getCue();
        if (cue == null) {
            cue = new ArrayList<>();
            st.setCue(cue);
        }
        if (!cue.contains(songId)) {
            cue.add(Math.min(st.getCueIndex() + 1, cue.size()), songId);
        }
        st.setCueIndex(cue.indexOf(songId));
    }



    private synchronized void updateLastSongs(PlaybackState state, Long songId) {
        if (state == null || songId == null) {
            return;
        }

        List<Long> last = state.getLastSongs();
        if (last == null) {
            last = new ArrayList<>();
            state.setLastSongs(last);
        }

        last.add(songId); // append newest
        final int MAX_LAST_SONGS = 5;

        if (last.size() > MAX_LAST_SONGS) {
            // remove oldest
            last.remove(0);
        }
    }



    private synchronized List<Long> generateShuffledCue(List<Long> originalCue) {
        List<Long> newShuffledCue = new ArrayList<>(originalCue);
        java.util.Collections.shuffle(newShuffledCue);
        return newShuffledCue;
    }

    private synchronized void stopPlayback() {
        PlaybackState st = getState();
        st.setCurrentSongId(null);
        st.setPlaying(false);
        st.setCueIndex(-1);
        updateState(st, true);
    }

    private void handleAction(com.fasterxml.jackson.databind.JsonNode node) {
        String action = node.get("action").asText();
        PlaybackState state = getState();

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
                togglePlay();
                break;
            case "next":
                next();
                break;
            case "previous":
                previous();
                break;
            case "shuffle":
                toggleShuffle();
                break;
            case "repeat":
                toggleRepeat();
                break;
        }

        // Persist throttled to DB
        maybePersistThrottled();
    }

    // -----------------------------
    // Playlist + Song helpers
    // -----------------------------
    public Playlist findPlaylist(Long id) {
        return playlistService.find(id);
    }

    public List<Playlist> getPlaylists() {
        return playlistService.findAll();
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

    public Song findSong(Long id) {
        return songService.find(id);
    }

    public Settings getSettings() {
        return currentSettings.getOrCreateSettings();
    }

    private String safeSummary(PlaybackState s) {
        if (s == null) {
            return "null";
        }
        return String.format("{playing=%s, songId=%s, time=%.2f, vol=%.2f}",
                s.isPlaying(), s.getCurrentSongId(), s.getCurrentTime(), s.getVolume());
    }

    // Helper method to advance song
    private synchronized void advanceSong(boolean forward) {
        PlaybackState st = getState();
        List<Long> cue = st.getCue(); // Original queue

        // Populate cue if empty (initial state)
        if (cue == null || cue.isEmpty()) {
            List<Song> allSongs = getSongs();
            cue = new ArrayList<>(allSongs.stream().map(s -> s.id).toList());
            st.setCue(cue);
            st.setCueIndex(cue.isEmpty() ? -1 : 0);
            if (cue.isEmpty()) {
                stopPlayback();
                return;
            }
        }

        Long oldCurrentSongId = st.getCurrentSongId();
        Long determinedNextSongId = null; // This will hold the ID of the song that *should* play next
        int determinedNextCueIndex = -1; // This will hold the index in the *original* cue

        // --- Determine the potential next song and its index ---
        if (st.getRepeatMode() == PlaybackState.RepeatMode.ONE) {
            determinedNextSongId = oldCurrentSongId;
            determinedNextCueIndex = st.getCueIndex();
            st.setCurrentTime(0); // Reset time for repeat one
        } else if (st.isShuffleEnabled()) {
            // SHUFFLE is ON
            if (shuffledCue == null || shuffledCue.isEmpty()) {
                shuffledCue = generateShuffledCue(cue);
                shuffledCueIndex = 0;
            }

            if (forward) {
                shuffledCueIndex++;
                if (shuffledCueIndex >= shuffledCue.size()) {
                    if (st.getRepeatMode() == PlaybackState.RepeatMode.ALL) {
                        shuffledCue = generateShuffledCue(cue); // Reshuffle and start again
                        shuffledCueIndex = 0;
                    } else { // Shuffle ON, Repeat OFF (no wrap, stop playback)
                        stopPlayback();
                        return;
                    }
                }
            } else { // Backward in shuffled mode
                shuffledCueIndex--;
                if (shuffledCueIndex < 0) {
                    if (st.getRepeatMode() == PlaybackState.RepeatMode.ALL) {
                        shuffledCue = generateShuffledCue(cue); // Reshuffle and go to the end
                        shuffledCueIndex = shuffledCue.size() - 1;
                    }
                    else { // Shuffle ON, Repeat OFF (no wrap, stay at beginning)
                        shuffledCueIndex = 0;
                    }
                }
            }
            determinedNextSongId = shuffledCue.get(shuffledCueIndex);
            determinedNextCueIndex = cue.indexOf(determinedNextSongId); // Index in original cue

        } else {
            // SHUFFLE is OFF
            int currentSongIndexInOriginalCue = st.getCueIndex();
            if (forward) {
                determinedNextCueIndex = currentSongIndexInOriginalCue + 1;
                if (determinedNextCueIndex >= cue.size()) {
                    if (st.getRepeatMode() == PlaybackState.RepeatMode.ALL) {
                        determinedNextCueIndex = 0; // Wrap around to the beginning
                    } else { // Shuffle OFF, Repeat OFF (no wrap, stop playback)
                        stopPlayback();
                        return;
                    }
                }
            }
            else { // Backward in sequential mode
                determinedNextCueIndex = currentSongIndexInOriginalCue - 1;
                if (determinedNextCueIndex < 0) {
                    if (st.getRepeatMode() == PlaybackState.RepeatMode.ALL) {
                        determinedNextCueIndex = cue.size() - 1; // Wrap around to the end
                    }
                    else { // Shuffle OFF, Repeat OFF (no wrap, stay at beginning)
                        determinedNextCueIndex = 0;
                    }
                }
            }
            determinedNextSongId = cue.get(determinedNextCueIndex);
        }

        // --- Handle Song Removal (if applicable) ---
        // Songs should be removed from the queue after playback, ONLY if RepeatMode.OFF
        boolean shouldRemoveSong = (st.getRepeatMode() == PlaybackState.RepeatMode.OFF);

        if (shouldRemoveSong && oldCurrentSongId != null && forward) {
            if (st.isShuffleEnabled()) {
                // Remove from shuffledCue
                int indexToRemove = shuffledCue.indexOf(oldCurrentSongId);
                if (indexToRemove != -1) {
                    shuffledCue.remove(indexToRemove);
                    // Adjust shuffledCueIndex if the removed song was before the determined next song
                    if (indexToRemove < shuffledCueIndex) {
                        shuffledCueIndex--;
                    }
                    // After removal, re-determine next song based on adjusted shuffledCueIndex
                    if (!shuffledCue.isEmpty()) {
                        determinedNextSongId = shuffledCue.get(shuffledCueIndex);
                        determinedNextCueIndex = cue.indexOf(determinedNextSongId);
                    } else {
                        stopPlayback();
                        return;
                    }
                }
            } else {
                // Remove from original cue
                int indexToRemove = cue.indexOf(oldCurrentSongId);
                if (indexToRemove != -1) {
                    cue.remove(indexToRemove);
                    // Adjust determinedNextCueIndex if the removed song was before the determined next song
                    if (indexToRemove < determinedNextCueIndex) {
                        determinedNextCueIndex--;
                    }
                    // After removal, re-determine next song based on adjusted determinedNextCueIndex
                    if (!cue.isEmpty()) {
                        determinedNextSongId = cue.get(determinedNextCueIndex);
                    } else {
                        stopPlayback();
                        return;
                    }
                }
            }
        }

        // If queue becomes empty after removal, stop playback
        if (cue.isEmpty() || (st.isShuffleEnabled() && shuffledCue.isEmpty())) {
            stopPlayback();
            return;
        }

        // Update state with the determined next song
        st.setCueIndex(determinedNextCueIndex);
        st.setCurrentSongId(determinedNextSongId);
        Song newSong = findSong(determinedNextSongId);
        st.setArtistName(newSong != null ? newSong.getArtist() : "Unknown Artist");
        st.setSongName(newSong != null ? newSong.getTitle() : "Unknown Title");
        st.setDuration(newSong != null ? newSong.getDurationSeconds() : 0);
        st.setPlaying(true);
        // currentTime is already set to 0 for RepeatMode.ONE. For others, it should be 0.
        if (st.getRepeatMode() != PlaybackState.RepeatMode.ONE) {
            st.setCurrentTime(0);
        }
        updateLastSongs(st, determinedNextSongId);

        if (newSong != null) {
            playbackHistoryService.add(newSong);
        }

        updateState(st, true); // persists + broadcasts
    }
    public synchronized void next() {
        currentSettings.addLog("Skipped to next song.");
        System.out.println("Next");
        advanceSong(true);
    }

    public synchronized void previous() {
        currentSettings.addLog("Skipped to previous song.");
        System.out.println("Previous");
        advanceSong(false);
    }

    public synchronized void togglePlay() {
        currentSettings.addLog("Playback toggled.");
        System.out.println("Toggle");
        PlaybackState state = getState();
        state.setPlaying(!state.isPlaying());
        updateState(state, true);
    }

    /**
     * Toggles shuffle mode on/off and persists state
     */
    public synchronized void toggleShuffle() {
        PlaybackState state = getState();
        boolean newShuffleState = !state.isShuffleEnabled();
        state.setShuffleEnabled(newShuffleState);

        if (newShuffleState) {
            // When shuffle is enabled, generate a new shuffled cue, keeping current song at the start
            List<Long> originalCue = state.getCue();
            Long currentSongId = state.getCurrentSongId();

            if (originalCue != null && !originalCue.isEmpty() && currentSongId != null) {
                List<Long> remainingSongs = new ArrayList<>(originalCue);
                remainingSongs.remove(currentSongId); // Remove current song

                java.util.Collections.shuffle(remainingSongs); // Shuffle the rest

                shuffledCue = new ArrayList<>();
                shuffledCue.add(currentSongId); // Add current song to the beginning
                shuffledCue.addAll(remainingSongs); // Add shuffled remaining songs

                shuffledCueIndex = 0; // Current song is now at index 0 of shuffledCue
            } else {
                // Fallback if no current song or empty cue
                shuffledCue = generateShuffledCue(originalCue);
                shuffledCueIndex = 0;
            }
        } else {
            // When shuffle is disabled, clear the shuffled cue and reset its index
            shuffledCue = null;
            shuffledCueIndex = -1;
            // The current song and cueIndex should remain unchanged until advanceSong is called.
        }

        currentSettings.addLog("Shuffle toggled to: " + state.isShuffleEnabled());
        updateState(state, true);
    }

    /**
     * Cycles through repeat modes: OFF, ONE, ALL.
     */
    public synchronized void toggleRepeat() {
        PlaybackState state = getState();
        PlaybackState.RepeatMode currentMode = state.getRepeatMode();
        PlaybackState.RepeatMode nextMode;

        switch (currentMode) {
            case OFF:
                nextMode = PlaybackState.RepeatMode.ONE;
                break;
            case ONE:
                nextMode = PlaybackState.RepeatMode.ALL;
                break;
            case ALL:
                nextMode = PlaybackState.RepeatMode.OFF;
                break;
            default:
                nextMode = PlaybackState.RepeatMode.OFF;
                break;
        }
        state.setRepeatMode(nextMode);
        currentSettings.addLog("Repeat mode toggled to: " + nextMode);
        updateState(state, true);
    }

    /**
     * Sets the playback volume (0.0 - 1.0) and persists state
     */
    public synchronized void changeVolume(float level) {
        PlaybackState st = getState();
        st.setVolume(Math.max(0f, Math.min(1f, level)));
        updateState(st, true);
    }

    /**
     * Sets the playback position in seconds and persists state
     */
    public synchronized void setSeconds(double seconds) {
        PlaybackState st = getState();
        st.setCurrentTime(Math.max(0, seconds));
        updateState(st, true);
    }

    /**
     * Returns the currently playing song, or null if none
     */
    public synchronized Song getCurrentSong() {
        PlaybackState st = getState();
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

    public synchronized void replaceQueueWithPlaylist(Playlist playlist) {
        PlaybackState st = getState();
        populateCueFromPlaylist(st, playlist);
        updateState(st, true); // persist + broadcast 
    }

    private void populateCueFromPlaylist(PlaybackState st, Playlist playlist) {
        if (playlist == null) {
            // Deselect any playlist
            st.setCurrentPlaylistId(null);

            List<Long> cue = st.getCue();
            // If queue is empty, populate with all songs
            if (cue == null || cue.isEmpty()) {
                List<Song> allSongs = getSongs();
                cue = allSongs.stream().map(s -> s.id).toList();
                st.setCue(new ArrayList<>(cue));
                st.setCueIndex(cue.isEmpty() ? -1 : 0);
                st.setCurrentSongId(cue.isEmpty() ? null : cue.get(0));
                st.setPlaying(!cue.isEmpty());
                st.setCurrentTime(0);
            }

        } else {
            // Set the current playlist normally
            st.setCurrentPlaylistId(playlist.id);

            List<Song> songs = playlist.getSongs();
            if (songs != null && !songs.isEmpty()) {
                List<Long> cue = songs.stream().map(s -> s.id).toList();
                st.setCue(new ArrayList<>(cue));
                st.setCueIndex(0);
                st.setCurrentSongId(cue.get(0));
                st.setCurrentTime(0);
                st.setPlaying(true);
            } else {
                // empty playlist → stop playback
                st.setCue(new ArrayList<>());
                st.setCueIndex(-1);
                st.setCurrentSongId(null);
                st.setCurrentTime(0);
                st.setPlaying(false);
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
    public synchronized void addToQueue(List<Long> songIds, boolean playNext) {
        if (songIds == null || songIds.isEmpty()) {
            return;
        }

        PlaybackState st = getState();
        List<Long> cue = st.getCue();
        if (cue == null) {
            cue = new ArrayList<>();
            st.setCue(cue);
        }

        int insertIndex = playNext && st.getCueIndex() != -1
                ? st.getCueIndex() + 1
                : cue.size();

        for (Long id : songIds) {
            if (!cue.contains(id)) {
                cue.add(insertIndex, id);
                insertIndex++;
            }
        }

        st.setCue(cue);
        updateState(st, true);
    }

    /**
     * Removes a specific song from the queue. If it's currently playing, moves
     * to next or stops.
     */
    public synchronized void removeFromQueue(Long songId) {
        PlaybackState st = getState();
        List<Long> cue = st.getCue();
        if (cue == null || cue.isEmpty()) {
            return;
        }

        int currentIndex = st.getCueIndex();
        Long currentId = st.getCurrentSongId();

        int removeIndex = cue.indexOf(songId);
        if (removeIndex == -1) {
            return;
        }

        cue.remove(removeIndex);

        if (songId.equals(currentId)) {
            // If we removed the currently playing song
            if (cue.isEmpty()) {
                stopPlayback();
                return;
            }
            // Try to move to next song (wrap if necessary)
            // If the removed song was the last one, play the new last song
            currentIndex = Math.min(removeIndex, cue.size() - 1);
            st.setCueIndex(currentIndex);
            st.setCurrentSongId(cue.get(currentIndex));
            st.setCurrentTime(0);
            st.setPlaying(true); // Continue playing with the new current song
        } else if (removeIndex < currentIndex) {
            // If a song before the current one was removed, adjust the index
            st.setCueIndex(currentIndex - 1);
        }

        st.setCue(cue);
        updateState(st, true);
    }

    /**
     * Clears the entire queue and stops playback.
     */
    public synchronized void clearQueue() {
        PlaybackState st = getState();
        st.setCue(new ArrayList<>());
        st.setCueIndex(-1);
        st.setCurrentSongId(null);
        st.setPlaying(false);
        st.setCurrentTime(0);
        updateState(st, true);
    }

    /**
     * Moves a song within the queue (drag-and-drop style reordering).
     */
    public synchronized void moveInQueue(int fromIndex, int toIndex) {
        PlaybackState st = getState();
        List<Long> cue = st.getCue();
        if (cue == null || cue.isEmpty()) {
            return;
        }

        if (fromIndex < 0 || fromIndex >= cue.size()
                || toIndex < 0 || toIndex >= cue.size()) {
            return;
        }

        Long songId = cue.remove(fromIndex);
        cue.add(toIndex, songId);

        // Adjust cue index if needed
        int currentIdx = st.getCueIndex();
        if (currentIdx == fromIndex) {
            st.setCueIndex(toIndex);
        } else if (fromIndex < currentIdx && toIndex >= currentIdx) {
            st.setCueIndex(currentIdx - 1);
        } else if (fromIndex > currentIdx && toIndex <= currentIdx) {
            st.setCueIndex(currentIdx + 1);
        }

        st.setCue(cue);
        updateState(st, true);
    }

    /**
     * Sets the currently selected playlist for UI purposes only. Does NOT alter
     * the current queue or playback.
     */
    public synchronized void selectPlaylistForBrowsing(Playlist playlist) {
        PlaybackState st = getState();
        st.setCurrentPlaylistId(playlist != null ? playlist.id : null);
        updateState(st, true); // only persists & broadcasts selection
    }

    /**
     * Adds all songs from a playlist to the queue.
     *
     * @param playlist the playlist whose songs to add
     * @param playNext if true, insert after current song; else append at end
     */
    public synchronized void addPlaylistToQueue(Playlist playlist, boolean playNext) {
        if (playlist == null || playlist.getSongs() == null || playlist.getSongs().isEmpty()) {
            return;
        }
        List<Long> songIds = playlist.getSongs().stream()
                .map(s -> s.id)
                .toList();
        addToQueue(songIds, playNext);
    }

    public synchronized List<Song> getQueue() {
        PlaybackState st = getState();
        List<Long> cueIds = st.getCue();
        if (cueIds == null) {
            return new ArrayList<>();
        }
        return cueIds.stream()
                .map(this::findSong)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toList());
    }

    public synchronized void skipToQueueIndex(int index) {
        LOGGER.info("skipToQueueIndex called with index: " + index);
        PlaybackState st = getState();
        List<Long> cue = st.getCue();
        LOGGER.info("skipToQueueIndex: Original cue: " + cue);
        if (cue == null || index < 0 || index >= cue.size()) {
            LOGGER.warning("skipToQueueIndex: Invalid index or empty cue. Index: " + index + ", Cue size: " + (cue != null ? cue.size() : "null"));
            return;
        }

        st.setCueIndex(index);

        Long songId = cue.get(index);
        st.setCurrentSongId(songId);
        LOGGER.info("skipToQueueIndex: Setting current songId to: " + songId);
        Song newSong = findSong(songId);
        LOGGER.info("skipToQueueIndex: Found song for ID " + songId + ": " + (newSong != null ? newSong.getTitle() : "null"));
        st.setArtistName(newSong != null ? newSong.getArtist() : "Unknown Artist");
        st.setSongName(newSong != null ? newSong.getTitle() : "Unknown Title");
        st.setDuration(newSong != null ? newSong.getDurationSeconds() : 0);
        st.setPlaying(true);
        st.setCurrentTime(0);
        updateLastSongs(st, songId);
        updateState(st, true);
    }

    public synchronized void removeFromQueue(int index) {

        PlaybackState st = getState();

        List<Long> cue = st.getCue();

        if (cue == null || cue.isEmpty() || index < 0 || index >= cue.size()) {

            return;

        }

        Long songIdToRemove = cue.get(index);

        removeFromQueue(songIdToRemove); // Reuse existing logic

    }

    public List<PlaybackHistory> getHistory() {

        return PlaybackHistory.listAll();

    }

}
