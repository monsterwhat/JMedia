package Controllers;

import Models.PlaybackState;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import Models.Song;
import Services.PlaybackHistoryService;
import Services.SongService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import Models.PlaybackHistory;

@ApplicationScoped
public class PlaybackQueueController {

    public List<PlaybackHistory> getHistory() {
        return PlaybackHistory.listAll();
    }

    @Inject
    private PlaybackHistoryService playbackHistoryService;

    @Inject
    private SongService songService;

    private void addSongToHistory(Long songId) {
        if (songId == null) {
            return;
        }
        Song song = songService.find(songId);
        if (song != null) {
            playbackHistoryService.add(song);
        }
    }

    public void populateCue(PlaybackState state, List<Long> songIds) {
        state.setCue(new ArrayList<>(songIds));
        state.setOriginalCue(new ArrayList<>()); // Clear any previously saved original cue
        state.setCueIndex(songIds.isEmpty() ? -1 : 0);
        state.setCurrentSongId(songIds.isEmpty() ? null : songIds.get(0));
        state.setPlaying(!songIds.isEmpty());
        state.setCurrentTime(0);
    }

    public Long advance(PlaybackState state, boolean forward) {
        if (!forward) {
            // 'previous' logic can be simple
            List<Long> cue = state.getCue();
            if (cue == null || cue.isEmpty()) return null;
            int prevIndex = state.getCueIndex() - 1;
            if (prevIndex < 0) {
                // At the beginning, wrap to end if repeating, otherwise stay at 0
                prevIndex = state.getRepeatMode() == PlaybackState.RepeatMode.ALL ? cue.size() - 1 : 0;
            }
            state.setCueIndex(prevIndex);
            return cue.get(prevIndex);
        }

        // --- FORWARD ADVANCEMENT ---
        addSongToHistory(state.getCurrentSongId());

        // Smart shuffle: find a suitable next song and move it to the next position in the cue
        if (state.getShuffleMode() == PlaybackState.ShuffleMode.SMART_SHUFFLE) {
            findAndPrepareNextSmartSong(state);
        }

        List<Long> cue = state.getCue();
        if (cue == null || cue.isEmpty()) return null;

        // Logic for RepeatMode.OFF (song removal)
        if (state.getRepeatMode() == PlaybackState.RepeatMode.OFF) {
            int playedIndex = state.getCueIndex();
            if (playedIndex >= 0 && playedIndex < cue.size()) {
                cue.remove(playedIndex);
            }
            // After removal, the next song is at the same index, unless we removed the last item.
            if (playedIndex >= cue.size() || cue.isEmpty()) {
                // We removed the last item, or the queue is now empty.
                state.setPlaying(false);
                return null;
            }
            // The new index is the one we just removed from.
            state.setCueIndex(playedIndex);
            // Ensure playedIndex is valid before accessing cue
            if (playedIndex < 0 && !cue.isEmpty()) {
                state.setCueIndex(0); // Reset to first song if index is invalid but queue is not empty
                return cue.get(0);
            }
            return cue.get(playedIndex);
        }

        // Logic for RepeatMode.ALL (no removal, just advance index)
        // This applies to both shuffle and sequential, as the cue is already in its desired order.
        int nextIndex = state.getCueIndex() + 1;
        if (nextIndex >= cue.size()) {
            nextIndex = 0; // Wrap around
            // NO Collections.shuffle(cue) here, as per user's clarification.
        }

        state.setCueIndex(nextIndex);
        return cue.get(nextIndex);
    }

    public void initShuffle(PlaybackState state) {
        // Save the original order before shuffling if it's not already saved
        if (state.getOriginalCue() == null || state.getOriginalCue().isEmpty()) {
            state.setOriginalCue(new ArrayList<>(state.getCue()));
        }

        List<Long> cue = state.getCue();
        Long currentSongId = state.getCurrentSongId();

        if (cue == null || cue.isEmpty()) {
            return; // Nothing to shuffle
        }

        // Keep current song at the top, shuffle the rest
        if (currentSongId != null && cue.contains(currentSongId)) {
            cue.remove(currentSongId);
            Collections.shuffle(cue);
            cue.add(0, currentSongId);
            state.setCueIndex(0);
        } else {
            // If no current song or it's not in the cue, just shuffle everything
            Collections.shuffle(cue);
            state.setCueIndex(0);
        }
    }

    public void initSmartShuffle(PlaybackState state) {
        // 1. Get the pool of songs
        List<Long> songPoolIds = (state.getOriginalCue() != null && !state.getOriginalCue().isEmpty())
                ? new ArrayList<>(state.getOriginalCue())
                : new ArrayList<>(state.getCue());

        if (songPoolIds.isEmpty()) {
            return;
        }

        // Save original cue if it wasn't saved before
        if (state.getOriginalCue() == null || state.getOriginalCue().isEmpty()) {
            state.setOriginalCue(new ArrayList<>(songPoolIds));
        }

        List<Song> allSongs = songService.findByIds(songPoolIds);

        // 2. Group songs by genre
        java.util.Map<String, List<Song>> songsByGenre = allSongs.stream()
                .collect(java.util.stream.Collectors.groupingBy(song ->
                        song.getGenre() == null || song.getGenre().isBlank() ? "Unknown" : song.getGenre()
                ));

        // 3. Shuffle the order of genres
        List<String> genres = new ArrayList<>(songsByGenre.keySet());
        Collections.shuffle(genres);

        // 4. Build the new queue
        List<Long> newCue = new ArrayList<>();
        for (String genre : genres) {
            List<Song> songsInGenre = songsByGenre.get(genre);
            Collections.shuffle(songsInGenre);
            for (Song song : songsInGenre) {
                newCue.add(song.id);
            }
        }

        // 5. Update the state
        state.setCue(newCue);
        int newIndex = newCue.indexOf(state.getCurrentSongId());
        state.setCueIndex(newIndex != -1 ? newIndex : 0);

        // If current song was not found (shouldn't happen), set to first song
        if (newIndex == -1 && !newCue.isEmpty()) {
            state.setCurrentSongId(newCue.get(0));
        }
    }

    public void clearShuffle(PlaybackState state) {
        List<Long> originalCue = state.getOriginalCue();
        // Check if there is an original cue to restore from
        if (originalCue != null && !originalCue.isEmpty()) {
            state.setCue(new ArrayList<>(originalCue));
            state.setOriginalCue(new ArrayList<>()); // Clear the saved cue
        }

        // After restoring, find the index of the current song
        if (state.getCue() != null && state.getCurrentSongId() != null) {
            int newIndex = state.getCue().indexOf(state.getCurrentSongId());
            state.setCueIndex(newIndex);
        } else {
            state.setCueIndex(-1);
        }
    }

    public void addToQueue(PlaybackState state, List<Long> songIds, boolean playNext) {
        if (songIds == null || songIds.isEmpty()) {
            return;
        }
        List<Long> cue = state.getCue();
        if (cue == null) {
            cue = new ArrayList<>();
            state.setCue(cue);
        }

        int insertIndex = playNext && state.getCueIndex() >= 0
                ? state.getCueIndex() + 1
                : cue.size();

        for (Long id : songIds) {
            if (!cue.contains(id)) {
                cue.add(insertIndex, id);
                insertIndex++;
            }
        }
    }

    public void removeFromQueue(PlaybackState state, Long songId) {
        List<Long> cue = state.getCue();
        if (cue == null || !cue.contains(songId)) {
            return;
        }

        int index = cue.indexOf(songId);
        cue.remove(songId);

        if (Objects.equals(songId, state.getCurrentSongId())) {
            if (cue.isEmpty()) {
                state.setCurrentSongId(null);
                state.setPlaying(false);
                state.setCueIndex(-1);
            } else {
                int nextIndex = Math.min(index, cue.size() - 1);
                state.setCueIndex(nextIndex);
                state.setCurrentSongId(cue.get(nextIndex));
                state.setCurrentTime(0);
            }
        } else if (index < state.getCueIndex()) {
            state.setCueIndex(state.getCueIndex() - 1);
        }
    }

    public void clear(PlaybackState state) {
        state.setCue(new ArrayList<>());
        state.setOriginalCue(new ArrayList<>());
        state.setCueIndex(-1);
        state.setCurrentSongId(null);
        state.setPlaying(false);
        state.setCurrentTime(0);
    }

    public void moveInQueue(PlaybackState state, int fromIndex, int toIndex) {
        List<Long> cue = state.getCue();
        if (cue == null || cue.isEmpty() || fromIndex < 0 || fromIndex >= cue.size() || toIndex < 0 || toIndex >= cue.size()) {
            return;
        }

        Long songId = cue.remove(fromIndex);
        cue.add(toIndex, songId);

        // Adjust cue index if needed
        int currentIdx = state.getCueIndex();
        if (currentIdx == fromIndex) {
            state.setCueIndex(toIndex);
        } else if (fromIndex < currentIdx && toIndex >= currentIdx) {
            state.setCueIndex(currentIdx - 1);
        } else if (fromIndex > currentIdx && toIndex <= currentIdx) {
            state.setCueIndex(currentIdx + 1);
        }
    }

    public void togglePlay(PlaybackState state) {
        state.setPlaying(!state.isPlaying());
    }

    public void toggleRepeat(PlaybackState state) {
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
    }

    public void changeVolume(PlaybackState state, float level) {
        state.setVolume(Math.max(0f, Math.min(1f, level)));
    }

    public void setSeconds(PlaybackState state, double seconds) {
        state.setCurrentTime(Math.max(0, seconds));
    }

    public void songSelected(Long songId) {
        addSongToHistory(songId);
    }

    public void skipToQueueIndex(PlaybackState state, int index) {
        List<Long> cue = state.getCue();
        if (cue == null || index < 0 || index >= cue.size()) {
            return;
        }
        addSongToHistory(state.getCurrentSongId()); // Add the song we are skipping from

        // Create the new truncated cue
        List<Long> newCue = new ArrayList<>(cue.subList(index, cue.size()));

        // If shuffle was active, we must also filter the originalCue to keep it consistent
        List<Long> originalCue = state.getOriginalCue();
        if (originalCue != null && !originalCue.isEmpty()) {
            // Create a set of the IDs that will remain in the active cue for efficient lookup
            java.util.Set<Long> remainingIds = new java.util.HashSet<>(newCue);

            // Filter the originalCue, keeping only the songs that are in the new active cue
            List<Long> newOriginalCue = originalCue.stream()
                    .filter(remainingIds::contains)
                    .collect(java.util.stream.Collectors.toList());
            state.setOriginalCue(newOriginalCue);
        }

        // Set the new active cue
        state.setCue(newCue);

        // The new song is now at index 0 of the new cue
        state.setCueIndex(0);
        state.setCurrentSongId(newCue.get(0));
        state.setCurrentTime(0);
        state.setPlaying(true);
    }

    private void findAndPrepareNextSmartSong(PlaybackState state) {
        Song currentSong = songService.find(state.getCurrentSongId());
        if (currentSong == null || currentSong.getGenre() == null || currentSong.getGenre().isBlank()) {
            return;
        }

        List<Long> cue = state.getCue();
        int cueSize = (cue != null) ? cue.size() : 0;
        if (cueSize <= 1) {
            return;
        }

        // Use originalCue for a wider selection pool if available
        List<Long> songPool = (state.getOriginalCue() != null && !state.getOriginalCue().isEmpty())
                ? state.getOriginalCue() : cue;

        Song nextSong = songService.findRandomSongByGenre(currentSong.getGenre(), currentSong.id, songPool);

        if (nextSong == null) {
            return; // No smart match found, let the default (shuffled) order proceed
        }

        Long nextSongId = nextSong.id;

        // Move the chosen song to be the next one in the cue
        int currentSongIndexInCue = state.getCueIndex();
        int nextSongCurrentIndex = cue.indexOf(nextSongId);

        // If the smart song is already in the cue and not next, move it.
        if (nextSongCurrentIndex != -1 && nextSongCurrentIndex != currentSongIndexInCue + 1) {
            Long songToMove = cue.remove(nextSongCurrentIndex);
            int insertionPoint = Math.min(currentSongIndexInCue + 1, cue.size());
            cue.add(insertionPoint, songToMove);
        }
    }
}
