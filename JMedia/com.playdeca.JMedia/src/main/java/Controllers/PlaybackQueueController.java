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

    public List<PlaybackHistory> getHistory(int page, int pageSize, Long profileId) {
        return playbackHistoryService.getHistory(page, pageSize, profileId);
    }

    @Inject
    private PlaybackHistoryService playbackHistoryService;

    @Inject
    private SongService songService;

    @Inject
    private SettingsController settingsController;



public void populateCue(PlaybackState state, List<Long> songIds, Long profileId) {
        state.setCue(new ArrayList<>(songIds));
        state.setOriginalCue(new ArrayList<>()); // Clear any previously saved original cue
        state.setCueIndex(songIds.isEmpty() ? -1 : 0);
        state.setCurrentSongId(songIds.isEmpty() ? null : songIds.get(0));
        state.setPlaying(!songIds.isEmpty());
        state.setCurrentTime(0);
    }

    public void initializeSecondaryQueue(PlaybackState state, Long profileId) {
        List<Song> allSongs = songService.findAll();
        List<Long> allSongIds = allSongs.stream().map(s -> s.id).collect(java.util.stream.Collectors.toList());
        
        state.setSecondaryCue(new ArrayList<>(allSongIds));
        state.setSecondaryOriginalCue(new ArrayList<>());
        state.setSecondaryCueIndex(0);
        
        // Apply current shuffle mode to secondary queue
        if (state.getShuffleMode() == PlaybackState.ShuffleMode.SHUFFLE) {
            initSecondaryShuffle(state);
        } else if (state.getShuffleMode() == PlaybackState.ShuffleMode.SMART_SHUFFLE) {
            initSecondarySmartShuffle(state, profileId);
        }
    }

    public void switchToPrimaryQueue(PlaybackState state, Long newSongId, Long profileId) {
        state.setUsingSecondaryQueue(false);
        state.setCueIndex(state.getCue().indexOf(newSongId));
        state.setCurrentSongId(newSongId);
        
        // Update song metadata
        Song newSong = songService.find(newSongId);
        state.setArtistName(newSong != null ? newSong.getArtist() : "Unknown Artist");
        state.setSongName(newSong != null ? newSong.getTitle() : "Unknown Title");
        state.setDuration(newSong != null ? newSong.getDurationSeconds() : 0);
        state.setCurrentTime(0);
        state.setPlaying(true);
    }

public Long advance(PlaybackState state, boolean forward, boolean skippedEarly, Long profileId) {
        // Priority 1: Check primary queue first
        if (!state.getCue().isEmpty()) {
            state.setUsingSecondaryQueue(false);
            return advanceInQueue(state, forward, true, skippedEarly, profileId); // true = primary queue
        }
        
        // Priority 2: Fallback to secondary queue
        if (state.getSecondaryCue().isEmpty()) {
            initializeSecondaryQueue(state, profileId);
        }
        
        if (!state.getSecondaryCue().isEmpty()) {
            state.setUsingSecondaryQueue(true);
            return advanceInQueue(state, forward, false, skippedEarly, profileId); // false = secondary queue
        }
        
        // Priority 3: No songs available
        state.setPlaying(false);
        return null;
    }

    private Long advanceInQueue(PlaybackState state, boolean forward, boolean usePrimaryQueue, boolean skippedEarly, Long profileId) {
        List<Long> cue = usePrimaryQueue ? state.getCue() : state.getSecondaryCue();
        int cueIndex = usePrimaryQueue ? state.getCueIndex() : state.getSecondaryCueIndex();
        
        if (!forward) {
            // 'previous' logic
            if (cue == null || cue.isEmpty()) return null;
            int prevIndex = cueIndex - 1;
            if (prevIndex < 0) {
                // At the beginning, wrap to end if repeating, otherwise stay at 0
                prevIndex = state.getRepeatMode() == PlaybackState.RepeatMode.ALL ? cue.size() - 1 : 0;
            }
            if (usePrimaryQueue) {
                state.setCueIndex(prevIndex);
            } else {
                state.setSecondaryCueIndex(prevIndex);
            }
            return cue.get(prevIndex);
        }

        // --- FORWARD ADVANCEMENT ---
        // Smart shuffle: find a suitable next song and move it to the next position in the cue
        if (state.getShuffleMode() == PlaybackState.ShuffleMode.SMART_SHUFFLE && usePrimaryQueue) {
            findAndPrepareNextSmartSong(state, skippedEarly, profileId);
        }

        if (cue == null || cue.isEmpty()) return null;

        // Logic for RepeatMode.OFF (song removal)
        if (state.getRepeatMode() == PlaybackState.RepeatMode.OFF) {
            int playedIndex = cueIndex;
            if (playedIndex >= 0 && playedIndex < cue.size()) {
                cue.remove(playedIndex);
            }
            // After removal, the next song is at the same index, unless we removed the last item.
            if (playedIndex >= cue.size() || cue.isEmpty()) {
                // We removed the last item, or the queue is now empty.
                if (usePrimaryQueue) {
                    state.setPlaying(false);
                }
                return null;
            }
            // The new index is the one we just removed from.
            if (playedIndex < 0 && !cue.isEmpty()) {
                if (usePrimaryQueue) {
                    state.setCueIndex(0);
                } else {
                    state.setSecondaryCueIndex(0);
                }
                return cue.get(0);
            }
            if (usePrimaryQueue) {
                state.setCueIndex(playedIndex);
            } else {
                state.setSecondaryCueIndex(playedIndex);
            }
            return cue.get(playedIndex);
        }

        // Logic for RepeatMode.ALL (no removal, just advance index)
        int nextIndex = cueIndex + 1;
        if (nextIndex >= cue.size()) {
            nextIndex = 0; // Wrap around
        }

        if (usePrimaryQueue) {
            state.setCueIndex(nextIndex);
        } else {
            state.setSecondaryCueIndex(nextIndex);
        }
        return cue.get(nextIndex);
    }

    public void initShuffle(PlaybackState state, Long profileId) {
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

    public void initSmartShuffle(PlaybackState state, Long profileId) {
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

        // Get current song's BPM for sorting reference
        int currentBpm = 0;
        Song currentSong = null;
        if (state.getCurrentSongId() != null) {
            currentSong = allSongs.stream()
                    .filter(s -> s.id.equals(state.getCurrentSongId()))
                    .findFirst()
                    .orElse(null);
            if (currentSong != null && currentSong.getBpm() > 0) {
                currentBpm = currentSong.getBpm();
            }
        }

        // 2. Group songs by genre
        java.util.Map<String, List<Song>> songsByGenre = allSongs.stream()
                .collect(java.util.stream.Collectors.groupingBy(song ->
                        song.getGenre() == null || song.getGenre().isBlank() ? "Unknown" : song.getGenre()
                ));

        // 3. Shuffle the order of genres
        List<String> genres = new ArrayList<>(songsByGenre.keySet());
        Collections.shuffle(genres);

        // 4. Build the new queue with BPM sorting within genre groups
        List<Long> newCue = new ArrayList<>();
        for (String genre : genres) {
            List<Song> songsInGenre = songsByGenre.get(genre);
            
            // Sort by BPM similarity to current song's BPM
            final int targetBpm = currentBpm;
            if (targetBpm > 0) {
                songsInGenre.sort((a, b) -> {
                    int aBpm = a.getBpm() > 0 ? a.getBpm() : targetBpm;
                    int bBpm = b.getBpm() > 0 ? b.getBpm() : targetBpm;
                    int diffA = Math.abs(aBpm - targetBpm);
                    int diffB = Math.abs(bBpm - targetBpm);
                    
                    // If BPM difference is equal, shuffle to add variety
                    if (diffA == diffB) {
                        return Math.random() > 0.5 ? 1 : -1;
                    }
                    return Integer.compare(diffA, diffB);
                });
            } else {
                // No current BPM to reference, just shuffle
                Collections.shuffle(songsInGenre);
            }
            
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

    public void clearShuffle(PlaybackState state, Long profileId) {
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

public void addToQueue(PlaybackState state, List<Long> songIds, boolean playNext, Long profileId) {
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
        
        // NEW: Immediately switch to primary queue if currently using secondary
        if (state.isUsingSecondaryQueue() && !songIds.isEmpty()) {
            switchToPrimaryQueue(state, songIds.get(0), profileId);
        }
    }

    public void removeFromQueue(PlaybackState state, Long songId, Long profileId) {
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

    public void clear(PlaybackState state, Long profileId) {
        state.setCue(new ArrayList<>());
        state.setOriginalCue(new ArrayList<>());
        state.setCueIndex(-1);
        state.setCurrentSongId(null);
        state.setPlaying(false);
        state.setCurrentTime(0);
    }

    public void moveInQueue(PlaybackState state, int fromIndex, int toIndex, Long profileId) {
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

    public void togglePlay(PlaybackState state, Long profileId) {
        state.setPlaying(!state.isPlaying());
    }

    public void toggleRepeat(PlaybackState state, Long profileId) {
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

    public void changeVolume(PlaybackState state, float level, Long profileId) {
        state.setVolume(Math.max(0f, Math.min(1f, level)));
    }

    public void setSeconds(PlaybackState state, double seconds, Long profileId) {
        state.setCurrentTime(Math.max(0, seconds));
    }

public void songSelected(Long songId, Long profileId) {
        // Note: History will be added when song starts playing via PlaybackController
    }

  public void skipToQueueIndex(PlaybackState state, int index, Long profileId) {
        List<Long> cue = state.getCue();
        if (cue == null || index < 0 || index >= cue.size()) {
            return;
        }

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

private void findAndPrepareNextSmartSong(PlaybackState state, boolean skippedEarly, Long profileId) {
        Song currentSong = songService.find(state.getCurrentSongId());
        if (currentSong == null) {
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

        // If skipped early (< 20% played), change to a different genre
        // Otherwise, stay in the same genre with BPM matching
        Song nextSong = null;
        
        if (skippedEarly && (currentSong.getGenre() != null && !currentSong.getGenre().isBlank())) {
            // User skipped early - pick a song from a DIFFERENT genre
            // Get all genres in the pool and pick one that's not the current genre
            List<Song> allSongsInPool = songService.findByIds(songPool);
            java.util.Map<String, List<Song>> songsByGenre = allSongsInPool.stream()
                    .collect(java.util.stream.Collectors.groupingBy(song ->
                            song.getGenre() == null || song.getGenre().isBlank() ? "Unknown" : song.getGenre()
                    ));
            
            // Remove current genre from options
            String currentGenre = currentSong.getGenre();
            List<String> otherGenres = songsByGenre.keySet().stream()
                    .filter(g -> !g.equalsIgnoreCase(currentGenre))
                    .collect(java.util.stream.Collectors.toList());
            
            if (!otherGenres.isEmpty()) {
                // Pick a random different genre
                java.util.Collections.shuffle(otherGenres);
                String newGenre = otherGenres.get(0);
                
                // Get BPM tolerance for the new genre
                int bpmTolerance = 10;
                if (settingsController != null) {
                    bpmTolerance = settingsController.getOrCreateSettings().getBpmToleranceForGenre(newGenre);
                }
                
                // Try to find a song with similar BPM in the new genre
                if (currentSong.getBpm() > 0) {
                    nextSong = songService.findRandomSongByGenreAndBpm(
                            newGenre,
                            currentSong.getBpm(),
                            bpmTolerance,
                            currentSong.id,
                            songPool
                    );
                }
                
                // Fall back to random in new genre if no BPM match
                if (nextSong == null) {
                    nextSong = songService.findRandomSongByGenre(newGenre, currentSong.id, songPool);
                }
            }
        } else {
            // Normal case (song ended naturally OR skipped after 20%) - stay in same genre with BPM matching
            
            if (currentSong.getGenre() == null || currentSong.getGenre().isBlank()) {
                return; // No genre info, can't do smart shuffle
            }
            
            // Get BPM tolerance from settings
            int bpmTolerance = 10;
            if (settingsController != null) {
                bpmTolerance = settingsController.getOrCreateSettings().getBpmToleranceForGenre(currentSong.getGenre());
            }

            // Try to find a song with similar BPM in the same genre
            if (currentSong.getBpm() > 0) {
                nextSong = songService.findRandomSongByGenreAndBpm(
                        currentSong.getGenre(), 
                        currentSong.getBpm(), 
                        bpmTolerance, 
                        currentSong.id, 
                        songPool
                );
            }

            // Fall back to genre-only if no BPM match found
            if (nextSong == null) {
                nextSong = songService.findRandomSongByGenre(currentSong.getGenre(), currentSong.id, songPool);
            }
        }

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

    public void initSecondaryShuffle(PlaybackState state) {
        // Save the original order before shuffling if it's not already saved
        if (state.getSecondaryOriginalCue() == null || state.getSecondaryOriginalCue().isEmpty()) {
            state.setSecondaryOriginalCue(new ArrayList<>(state.getSecondaryCue()));
        }

        List<Long> secondaryCue = state.getSecondaryCue();
        Long currentSongId = state.getCurrentSongId();

        if (secondaryCue == null || secondaryCue.isEmpty()) {
            return; // Nothing to shuffle
        }

        // Keep current song at the top, shuffle the rest
        if (currentSongId != null && secondaryCue.contains(currentSongId)) {
            secondaryCue.remove(currentSongId);
            Collections.shuffle(secondaryCue);
            secondaryCue.add(0, currentSongId);
            state.setSecondaryCueIndex(0);
        } else {
            // If no current song or it's not in the cue, just shuffle everything
            Collections.shuffle(secondaryCue);
            state.setSecondaryCueIndex(0);
        }
    }

    public void initSecondarySmartShuffle(PlaybackState state, Long profileId) {
        // 1. Get the pool of songs (all songs for secondary queue)
        List<Song> allSongs = songService.findAll();
        if (allSongs.isEmpty()) {
            return;
        }

        // Save original secondary cue if it wasn't saved before
        if (state.getSecondaryOriginalCue() == null || state.getSecondaryOriginalCue().isEmpty()) {
            state.setSecondaryOriginalCue(new ArrayList<>(state.getSecondaryCue()));
        }

        // Get current song's BPM for sorting reference
        int currentBpm = 0;
        if (state.getCurrentSongId() != null) {
            Song currentSong = allSongs.stream()
                    .filter(s -> s.id.equals(state.getCurrentSongId()))
                    .findFirst()
                    .orElse(null);
            if (currentSong != null && currentSong.getBpm() > 0) {
                currentBpm = currentSong.getBpm();
            }
        }

        // 2. Group songs by genre
        java.util.Map<String, List<Song>> songsByGenre = allSongs.stream()
                .collect(java.util.stream.Collectors.groupingBy(song ->
                        song.getGenre() == null || song.getGenre().isBlank() ? "Unknown" : song.getGenre()
                ));

        // 3. Shuffle the order of genres
        List<String> genres = new ArrayList<>(songsByGenre.keySet());
        Collections.shuffle(genres);

        // 4. Build the new secondary queue with BPM sorting within genre groups
        List<Long> newSecondaryCue = new ArrayList<>();
        for (String genre : genres) {
            List<Song> songsInGenre = songsByGenre.get(genre);
            
            // Sort by BPM similarity to current song's BPM
            final int targetBpm = currentBpm;
            if (targetBpm > 0) {
                songsInGenre.sort((a, b) -> {
                    int aBpm = a.getBpm() > 0 ? a.getBpm() : targetBpm;
                    int bBpm = b.getBpm() > 0 ? b.getBpm() : targetBpm;
                    int diffA = Math.abs(aBpm - targetBpm);
                    int diffB = Math.abs(bBpm - targetBpm);
                    
                    // If BPM difference is equal, shuffle to add variety
                    if (diffA == diffB) {
                        return Math.random() > 0.5 ? 1 : -1;
                    }
                    return Integer.compare(diffA, diffB);
                });
            } else {
                Collections.shuffle(songsInGenre);
            }
            
            for (Song song : songsInGenre) {
                newSecondaryCue.add(song.id);
            }
        }

        // 5. Update the state
        state.setSecondaryCue(newSecondaryCue);
        int newIndex = newSecondaryCue.indexOf(state.getCurrentSongId());
        state.setSecondaryCueIndex(newIndex != -1 ? newIndex : 0);

        // If current song was not found, set to first song
        if (newIndex == -1 && !newSecondaryCue.isEmpty()) {
            state.setCurrentSongId(newSecondaryCue.get(0));
        }
    }
}
