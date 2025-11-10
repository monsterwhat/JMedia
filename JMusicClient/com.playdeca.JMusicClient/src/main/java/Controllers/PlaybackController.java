package Controllers;

import API.WS.MusicSocket;
import Models.PlaybackHistory;
import Models.PlaybackState;
import Models.Playlist;
import Models.Settings;
import Models.Song;
import Services.PlaylistService;
import Services.SongService;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;
import java.util.stream.Collectors;

@ApplicationScoped
public class PlaybackController {

    private PlaybackState memoryState;

    @Inject PlaybackPersistenceController playbackPersistenceController;
    @Inject PlaybackQueueController playbackQueueController;
    @Inject SettingsController currentSettings;
    @Inject SongService songService;
    @Inject PlaylistService playlistService;
    @Inject MusicSocket ws;

    private static final Logger LOGGER = Logger.getLogger(PlaybackController.class.getName());

    public PlaybackController() {
        System.out.println("[PlaybackController] PlaybackController instance created");
    }

    @PostConstruct
    public void init() {
        memoryState = playbackPersistenceController.loadState();
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

        memoryState.setPlaying(false);

        System.out.println("[PlaybackController] Initial state loaded: " + safeSummary(memoryState));
    }

    // -----------------------------
    // Playback state methods
    // -----------------------------
    public synchronized PlaybackState getState() {
        if (memoryState == null) {
            memoryState = playbackPersistenceController.loadState();
            if (memoryState == null) {
                memoryState = new PlaybackState();
            }
        }
        return memoryState;
    }

    public synchronized void updateState(PlaybackState newState, boolean shouldBroadcast) {
        if (memoryState == null) {
            memoryState = playbackPersistenceController.loadState();
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

        playbackPersistenceController.maybePersist(memoryState); // persist only

        if (shouldBroadcast && ws != null) {
            ws.broadcastAll();
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
            playbackQueueController.songSelected(id);

            addSongToCueIfNotPresent(st, id);

            // FIX: Update the cue index to match the selected song
            if (st.getCue() != null) {
                st.setCueIndex(st.getCue().indexOf(id));
            }
        }

        updateState(st, true);
        System.out.println("Updated Selection");
    }

    private void addSongToCueIfNotPresent(PlaybackState st, Long songId) {
        List<Long> songIds = new ArrayList<>();
        songIds.add(songId);
        playbackQueueController.addToQueue(st, songIds, false);
    }

    private synchronized void stopPlayback() {
        PlaybackState st = getState();
        playbackQueueController.clear(st);
        updateState(st, true);
    }

    private void handleAction(com.fasterxml.jackson.databind.JsonNode node) {
        String action = node.get("type").asText();
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
            case "song_ended": // New case for natural song end
                handleSongEnded();
                break;
            case "shuffle":
                toggleShuffle();
                break;
            case "repeat":
                toggleRepeat();
                break;
        }

        // Persist throttled to DB
        playbackPersistenceController.maybePersist(state);
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

    public SongService.PaginatedSongs getSongs(int page, int limit) {
        return songService.findAll(page, limit);
    }

    public PlaylistService.PaginatedPlaylistSongs getSongsByPlaylist(Long playlistId, int page, int limit) {
        return playlistService.findSongsByPlaylist(playlistId, page, limit);
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
    private synchronized void advanceSong(boolean forward, boolean fromSongEnd) { // Added fromSongEnd parameter
        PlaybackState st = getState();

        // Populate cue if empty (initial state)
        if (st.getCue() == null || st.getCue().isEmpty()) {
            List<Song> allSongs = getSongs();
            List<Long> allSongIds = allSongs.stream().map(s -> s.id).toList();
            playbackQueueController.populateCue(st, allSongIds);
            if (st.getCue().isEmpty()) {
                stopPlayback();
                return;
            }
        }

        // Handle RepeatMode.ONE when song ends naturally
        if (fromSongEnd && st.getRepeatMode() == PlaybackState.RepeatMode.ONE) {
            st.setCurrentTime(0); // Restart current song
            st.setPlaying(true); // Ensure it keeps playing
            updateState(st, true); // Persist and broadcast
            return;
        }

        Long nextSongId = playbackQueueController.advance(st, forward);

        if (nextSongId == null) {
            stopPlayback();
            return;
        }

        st.setCurrentSongId(nextSongId);
        Song newSong = findSong(nextSongId);
        st.setArtistName(newSong != null ? newSong.getArtist() : "Unknown Artist");
        st.setSongName(newSong != null ? newSong.getTitle() : "Unknown Title");
        st.setDuration(newSong != null ? newSong.getDurationSeconds() : 0);
        st.setPlaying(true);
        st.setCurrentTime(0); // Always reset time for a new song

        updateState(st, true); // persists + broadcasts
    }

    public synchronized void next() {
        currentSettings.addLog("Skipped to next song.");
        System.out.println("Next");
        advanceSong(true, false); // Explicit user action
    }

    public synchronized void previous() {
        currentSettings.addLog("Skipped to previous song.");
        System.out.println("Previous");
        advanceSong(false, false); // Explicit user action
    }

    public synchronized void handleSongEnded() {
        currentSettings.addLog("Song ended naturally.");
        System.out.println("Song Ended");
        advanceSong(true, true); // Automatic advance due to song end
    }

    public synchronized void togglePlay() {
        currentSettings.addLog("Playback toggled.");
        System.out.println("Toggle");
        PlaybackState state = getState();
        playbackQueueController.togglePlay(state);
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
            playbackQueueController.initShuffle(state);
        } else {
            playbackQueueController.clearShuffle(state);
        }

        currentSettings.addLog("Shuffle toggled to: " + state.isShuffleEnabled());
        updateState(state, true);
    }

    /**
     * Cycles through repeat modes: OFF, ONE, ALL.
     */
    public synchronized void toggleRepeat() {
        PlaybackState state = getState();
        playbackQueueController.toggleRepeat(state);
        currentSettings.addLog("Repeat mode toggled to: " + state.getRepeatMode());
        updateState(state, true);
    }

    /**
     * Sets the playback volume (0.0 - 1.0) and persists state
     */
    public synchronized void changeVolume(float level) {
        PlaybackState st = getState();
        playbackQueueController.changeVolume(st, level);
        updateState(st, true);
    }

    /**
     * Sets the playback position in seconds and persists state
     */
    public synchronized void setSeconds(double seconds) {
        PlaybackState st = getState();
        playbackQueueController.setSeconds(st, seconds);
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
                List<Long> allSongIds = allSongs.stream().map(s -> s.id).toList();
                playbackQueueController.populateCue(st, allSongIds);
            }

        } else {
            // Set the current playlist normally
            st.setCurrentPlaylistId(playlist.id);

            // Re-fetch playlist with songs to avoid LazyInitializationException
            Playlist fullPlaylist = findPlaylistWithSongs(playlist.id);
            List<Song> songs = (fullPlaylist != null) ? fullPlaylist.getSongs() : null;

            if (songs != null && !songs.isEmpty()) {
                List<Long> cue = songs.stream().map(s -> s.id).toList();
                playbackQueueController.populateCue(st, cue);
            } else {
                // empty playlist → stop playback
                playbackQueueController.clear(st);
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
        playbackQueueController.addToQueue(st, songIds, playNext);
        updateState(st, true);
    }

    /**
     * Removes a specific song from the queue. If it's currently playing, moves
     * to next or stops.
     */
    public synchronized void removeFromQueue(Long songId) {
        PlaybackState st = getState();
        playbackQueueController.removeFromQueue(st, songId);
        updateState(st, true);
    }

    /**
     * Clears the entire queue and stops playback.
     */
    public synchronized void clearQueue() {
        PlaybackState st = getState();
        playbackQueueController.clear(st);
        updateState(st, true);
    }

    /**
     * Moves a song within the queue (drag-and-drop style reordering).
     */
    public synchronized void moveInQueue(int fromIndex, int toIndex) {
        PlaybackState st = getState();
        playbackQueueController.moveInQueue(st, fromIndex, toIndex);
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

    public record PaginatedQueue(List<Song> songs, int totalSize) {}

    public PaginatedQueue getQueuePage(int page, int limit) {
        PlaybackState st = getState();
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

    public synchronized void skipToQueueIndex(int index) {
        LOGGER.info("skipToQueueIndex called with index: " + index);
        PlaybackState st = getState();
        LOGGER.info("skipToQueueIndex: Original cue: " + st.getCue());
        playbackQueueController.skipToQueueIndex(st, index);

        Long songId = st.getCurrentSongId();
        LOGGER.info("skipToQueueIndex: Setting current songId to: " + songId);
        Song newSong = findSong(songId);
        LOGGER.info("skipToQueueIndex: Found song for ID " + songId + ": " + (newSong != null ? newSong.getTitle() : "null"));
        st.setArtistName(newSong != null ? newSong.getArtist() : "Unknown Artist");
        st.setSongName(newSong != null ? newSong.getTitle() : "Unknown Title");
        st.setDuration(newSong != null ? newSong.getDurationSeconds() : 0);
        updateState(st, true);
    }

    public synchronized void removeFromQueue(int index) {
        PlaybackState st = getState();
        List<Long> cue = st.getCue();
        if (cue == null || cue.isEmpty() || index < 0 || index >= cue.size()) {
            return;
        }
        Long songIdToRemove = cue.get(index);
        playbackQueueController.removeFromQueue(st, songIdToRemove);
        updateState(st, true);
    }

    public List<PlaybackHistory> getHistory() {

        return PlaybackHistory.listAll();
    }

    public void toggleSongInPlaylist(Long playlistId, Long songId) {
        //TODO should remove the song from a playlist or add it via playlistController -> service
    }
    
}
