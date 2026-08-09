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
import jakarta.transaction.Transactional;
import Models.PlaybackHistory;
import Services.AudioAnalysisService;

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

    @Inject
    private AudioAnalysisService audioAnalysisService;



public void populateCue(PlaybackState state, List<Long> songIds, Long profileId) {
        state.setCue(new ArrayList<>(songIds));
        state.setOriginalCue(new ArrayList<>()); // Clear any previously saved original cue
        state.setCueIndex(songIds.isEmpty() ? -1 : 0);
        state.setCurrentSongId(songIds.isEmpty() ? null : songIds.get(0));
        state.setPlaying(!songIds.isEmpty());
        state.setCurrentTime(0);
        state.setUsingSecondaryQueue(false); // Explicitly switch back to primary
    }

    public void initializeSecondaryQueue(PlaybackState state, Long profileId) {
        // Fetch only IDs from the database to ensure they actually exist
        List<Long> allSongIds = songService.findAll().stream()
                // DJ Mode with a restricted genre pool: only pool-valid songs may ever play
                .filter(s -> !Boolean.TRUE.equals(state.getDjModeActive())
                        || isDjGenrePoolUnrestricted(state)
                        || isGenreAllowed(state, s.getGenre()))
                .map(s -> s.id)
                .collect(java.util.stream.Collectors.toList());
        
        state.setSecondaryCue(new ArrayList<>(allSongIds));
        state.setSecondaryOriginalCue(new ArrayList<>());
        state.setSecondaryCueIndex(-1); // Start at -1 so first advance goes to 0
        
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
        if (state.getCue() != null && !state.getCue().isEmpty()) {
            state.setUsingSecondaryQueue(false);
            return advanceInQueue(state, forward, true, skippedEarly, profileId); // true = primary queue
        }
        
        // DJ Mode with a restricted genre pool and empty primary cue: build a pool-aware
        // primary queue instead of falling back to the unfiltered all-songs secondary queue,
        // so the genre pool applies live to whatever actually plays next.
        if (Boolean.TRUE.equals(state.getDjModeActive()) && !isDjGenrePoolUnrestricted(state)) {
            initSmartShuffle(state, profileId);
            if (state.getCue() != null && !state.getCue().isEmpty()) {
                state.setUsingSecondaryQueue(false);
                return advanceInQueue(state, forward, true, skippedEarly, profileId);
            }
        }
        
        // Priority 2: Fallback to secondary queue
        if (state.getSecondaryCue() == null || state.getSecondaryCue().isEmpty()) {
            initializeSecondaryQueue(state, profileId);
        }
        
        if (state.getSecondaryCue() != null && !state.getSecondaryCue().isEmpty()) {
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
        
        if (cue == null || cue.isEmpty()) return null;

        if (!forward) {
            // 'previous' logic
            int prevIndex = cueIndex - 1;
            
            // If we are at the beginning of the queue, try to wrap if repeating all, or signal history check
            if (prevIndex < 0) {
                if (state.getRepeatMode() == PlaybackState.RepeatMode.ALL) {
                    prevIndex = cue.size() - 1;
                } else {
                    return null; // Return null to signal history check in PlaybackController
                }
            }
            
            if (usePrimaryQueue) {
                state.setCueIndex(prevIndex);
            } else {
                state.setSecondaryCueIndex(prevIndex);
            }
            return cue.get(prevIndex);
        }

        // --- FORWARD ADVANCEMENT ---
        if (cueIndex < 0) {
            cueIndex = 0;  // reset to start if no active index
        }

        if (state.getShuffleMode() == PlaybackState.ShuffleMode.SMART_SHUFFLE && usePrimaryQueue) {
            findAndPrepareNextSmartSong(state, skippedEarly, profileId);
        }

        if (state.getRepeatMode() == PlaybackState.RepeatMode.OFF) {
            if (usePrimaryQueue) {
                // Primary queue: Remove played song
                if (cueIndex >= 0 && cueIndex < cue.size()) {
                    cue.remove(cueIndex);
                }
                
                if (cue.isEmpty()) {
                    state.setCueIndex(-1);
                    return advance(state, true, skippedEarly, profileId);
                }
                
                // Keep index at current position because list shifted
                int nextIdx = Math.min(cueIndex, cue.size() - 1);
                state.setCueIndex(nextIdx);
                return resolveNextDjSong(state, usePrimaryQueue, cue, nextIdx, cue.get(nextIdx));
            } else {
                // Secondary queue: Wrap around forever
                int nextIndex = (cueIndex + 1) % cue.size();
                state.setSecondaryCueIndex(nextIndex);
                return resolveNextDjSong(state, usePrimaryQueue, cue, nextIndex, cue.get(nextIndex));
            }
        }

        // Logic for RepeatMode.ALL or ONE
        int nextIndex = (cueIndex + 1) % cue.size();

        if (usePrimaryQueue) {
            state.setCueIndex(nextIndex);
        } else {
            state.setSecondaryCueIndex(nextIndex);
        }
        return resolveNextDjSong(state, usePrimaryQueue, cue, nextIndex, cue.get(nextIndex));
    }

    /**
     * DJ Mode pool guard: if a restricted genre pool is active, the song that actually
     * plays next must belong to the pool. Returns the fallback song when the pool is
     * unrestricted, when the fallback is already pool-valid, or when no pool-valid song
     * can be found in the queue. Otherwise walks the queue forward (wrapping) from
     * startIndex to the nearest pool-valid song and moves the active index onto it.
     */
    private Long resolveNextDjSong(PlaybackState state, boolean usePrimaryQueue, List<Long> cue, int startIndex, Long fallback) {
        if (isDjGenrePoolUnrestricted(state) || cue == null || cue.isEmpty()) {
            return fallback;
        }
        Song fallbackSong = songService.find(fallback);
        if (fallbackSong != null && isGenreAllowed(state, fallbackSong.getGenre())) {
            return fallback;
        }
        java.util.Map<Long, Song> songsById = songService.findByIds(cue).stream()
                .collect(java.util.stream.Collectors.toMap(s -> s.id, s -> s));
        for (int i = 0; i < cue.size(); i++) {
            int idx = (startIndex + i) % cue.size();
            Song candidate = songsById.get(cue.get(idx));
            if (candidate != null && isGenreAllowed(state, candidate.getGenre())) {
                if (usePrimaryQueue) {
                    state.setCueIndex(idx);
                } else {
                    state.setSecondaryCueIndex(idx);
                }
                return candidate.id;
            }
        }
        return fallback;
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

        // DJ Mode with a restricted genre pool and no queue yet: seed from the whole
        // library so the pool-aware rebuild produces a playable queue instead of a no-op.
        if (songPoolIds.isEmpty()
                && Boolean.TRUE.equals(state.getDjModeActive())
                && !isDjGenrePoolUnrestricted(state)) {
            songPoolIds = songService.findAll().stream()
                    .map(s -> s.id)
                    .collect(java.util.stream.Collectors.toList());
        }

        if (songPoolIds.isEmpty()) {
            return;
        }

        // Save original cue if it wasn't saved before
        if (state.getOriginalCue() == null || state.getOriginalCue().isEmpty()) {
            state.setOriginalCue(new ArrayList<>(songPoolIds));
        }

        List<Song> allSongs = songService.findByIds(songPoolIds);

        // DJ Mode: restrict the pool to allowed genres before grouping
        boolean djActive = Boolean.TRUE.equals(state.getDjModeActive());
        if (djActive && !isDjGenrePoolUnrestricted(state)) {
            allSongs = allSongs.stream()
                    .filter(s -> isGenreAllowed(state, s.getGenre()))
                    .collect(java.util.stream.Collectors.toList());
        }

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

        // DJ Mode block size: songsPerGenre > 0 interleaves genres in blocks
        int blockSize = (djActive && state.getDjSongsPerGenre() != null && state.getDjSongsPerGenre() > 0)
                ? state.getDjSongsPerGenre() : 0;

        java.util.Map<String, List<Song>> sortedByGenre = new java.util.HashMap<>();
        for (String genre : genres) {
            List<Song> songsInGenre = new ArrayList<>(songsByGenre.get(genre));
            
            // Sort by BPM similarity to current song's BPM. Equal-BPM songs keep
            // their pre-shuffle order (TimSort is stable), so variety is preserved
            // without a non-deterministic comparator.
            final int targetBpm = currentBpm;
            if (targetBpm > 0) {
                Collections.shuffle(songsInGenre);
                songsInGenre.sort((a, b) -> {
                    int aBpm = a.getBpm() > 0 ? a.getBpm() : targetBpm;
                    int bBpm = b.getBpm() > 0 ? b.getBpm() : targetBpm;
                    return Integer.compare(Math.abs(aBpm - targetBpm), Math.abs(bBpm - targetBpm));
                });
            } else {
                // No current BPM to reference, just shuffle
                Collections.shuffle(songsInGenre);
            }
            
            sortedByGenre.put(genre, songsInGenre);
        }

        if (blockSize > 0) {
            // Block-style rotation: append up to blockSize songs per genre, cycling genres until the pool is exhausted
            List<String> remainingGenres = new ArrayList<>(genres);
            while (!remainingGenres.isEmpty()) {
                java.util.Iterator<String> it = remainingGenres.iterator();
                while (it.hasNext()) {
                    String genre = it.next();
                    List<Song> songsInGenre = sortedByGenre.get(genre);
                    int take = Math.min(blockSize, songsInGenre.size());
                    for (int i = 0; i < take; i++) {
                        newCue.add(songsInGenre.remove(0).id);
                    }
                    if (songsInGenre.isEmpty()) {
                        it.remove();
                    }
                }
            }
        } else {
            for (String genre : genres) {
                for (Song song : sortedByGenre.get(genre)) {
                    newCue.add(song.id);
                }
            }
        }

        if (djActive) {
            enforceMaxConsecutiveByArtistInCue(state, newCue);
        }

        // 5. Update the state
        state.setCue(newCue);
        int newIndex = newCue.indexOf(state.getCurrentSongId());
        state.setCueIndex(newIndex != -1 ? newIndex : 0);

        // If current song was not found (shouldn't happen), set to first song
        if (newIndex == -1 && !newCue.isEmpty()) {
            state.setCurrentSongId(newCue.get(0));
        }
        
        // 6. Pre-analyze upcoming songs for DJ Mode transitions
        audioAnalysisService.ensureUpcomingSongsAnalyzed(newCue, 5);
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
        // Handle Primary Queue
        List<Long> cue = state.getCue();
        if (cue != null && cue.contains(songId)) {
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

        // Handle Secondary Queue
        List<Long> secondaryCue = state.getSecondaryCue();
        if (secondaryCue != null && secondaryCue.contains(songId)) {
            int index = secondaryCue.indexOf(songId);
            secondaryCue.remove(songId);

            if (state.isUsingSecondaryQueue() && Objects.equals(songId, state.getCurrentSongId())) {
                if (secondaryCue.isEmpty()) {
                    state.setCurrentSongId(null);
                    state.setPlaying(false);
                    state.setSecondaryCueIndex(-1);
                } else {
                    int nextIndex = Math.min(index, secondaryCue.size() - 1);
                    state.setSecondaryCueIndex(nextIndex);
                    state.setCurrentSongId(secondaryCue.get(nextIndex));
                    state.setCurrentTime(0);
                }
            } else if (index <= state.getSecondaryCueIndex()) {
                state.setSecondaryCueIndex(state.getSecondaryCueIndex() - 1);
            }
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
    // FIX: If a DJ transition is already planned, DO NOT pick a new random song.
    // We must respect the song the DJ has already analyzed and aligned.
    if (!skippedEarly && Boolean.TRUE.equals(state.getDjTransitionPlanned()) && state.getDjNextSongId() != null) {
        System.out.println("[DJ] Respecting already planned transition to song ID: " + state.getDjNextSongId());
        return;
    }

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

        // DJ Mode: restrict the selection pool to allowed genres
        if (Boolean.TRUE.equals(state.getDjModeActive()) && !isDjGenrePoolUnrestricted(state)) {
            songPool = songService.findByIds(songPool).stream()
                    .filter(s -> isGenreAllowed(state, s.getGenre()))
                    .map(s -> s.id)
                    .collect(java.util.stream.Collectors.toList());
            if (songPool.isEmpty()) {
                return;
            }
        }

        // ENHANCED SMART SHUFFLE: Multi-candidate scoring with artist/album awareness
        Song nextSong = null;
        String targetGenre;

        // DJ Mode block size: after djSongsPerGenre consecutive same-genre songs,
        // force a different allowed genre
        boolean djActive = Boolean.TRUE.equals(state.getDjModeActive());
        boolean forceDifferentGenre = djActive
                && state.getDjSongsPerGenre() != null && state.getDjSongsPerGenre() > 0
                && currentSong.getGenre() != null && !currentSong.getGenre().isBlank()
                && countConsecutiveSameGenre(state, cue, currentSong) >= state.getDjSongsPerGenre();
        
        if ((skippedEarly && (currentSong.getGenre() != null && !currentSong.getGenre().isBlank())) || forceDifferentGenre) {
            // User skipped early - pick a song from a DIFFERENT genre
            List<Song> allSongsInPool = songService.findByIds(songPool);
            java.util.Map<String, List<Song>> songsByGenre = allSongsInPool.stream()
                    .collect(java.util.stream.Collectors.groupingBy(song ->
                            song.getGenre() == null || song.getGenre().isBlank() ? "Unknown" : song.getGenre()
                    ));
            
            String currentGenre = currentSong.getGenre();
            List<String> otherGenres = songsByGenre.keySet().stream()
                    .filter(g -> !g.equalsIgnoreCase(currentGenre))
                    .collect(java.util.stream.Collectors.toList());
            
            if (otherGenres.isEmpty()) {
                targetGenre = currentGenre; // Fallback to current genre if no others
            } else {
                java.util.Collections.shuffle(otherGenres);
                targetGenre = otherGenres.get(0);
            }
        } else {
            // Normal case - stay in same genre
            targetGenre = currentSong.getGenre();
        }

        if (targetGenre == null || targetGenre.isBlank()) {
            return; // No genre info, can't do smart shuffle
        }

        // Get BPM tolerance from settings
        int bpmTolerance = 10;
        if (settingsController != null) {
            bpmTolerance = settingsController.getOrCreateSettings().getBpmToleranceForGenre(targetGenre);
        }
        // DJ Mode strictness overrides tolerance: LOW=15, MEDIUM=djModeBpmTolerance (default 5), HIGH=3
        if (djActive) {
            String strictness = state.getDjStrictness();
            if ("LOW".equalsIgnoreCase(strictness)) {
                bpmTolerance = 15;
            } else if ("HIGH".equalsIgnoreCase(strictness)) {
                bpmTolerance = 3;
            } else {
                if (settingsController != null) {
                    Integer djTolerance = settingsController.getOrCreateSettings().getDjModeBpmTolerance();
                    bpmTolerance = djTolerance != null ? djTolerance : 5;
                } else {
                    bpmTolerance = 5;
                }
            }
        }

        // Get recently played songs (last 12) to exclude
        List<Long> recentSongIds = playbackHistoryService.getRecentlyPlayedSongIds(12, profileId);
        List<Long> exclusions = new ArrayList<>(recentSongIds);
        exclusions.add(currentSong.id);

        // PHASE 1: Get multiple candidates by Genre + BPM
        List<Song> candidates;
        if (currentSong.getBpm() > 0) {
            candidates = songService.findCandidatesByGenreAndBpm(
                    targetGenre,
                    currentSong.getBpm(),
                    bpmTolerance,
                    exclusions,
                    songPool,
                    20 // Get up to 20 candidates for scoring
            );
        } else {
            candidates = songService.findCandidatesByGenre(
                    targetGenre,
                    exclusions,
                    songPool,
                    20
            );
        }

        // DJ Mode: enforce explicit BPM range (min <= songBpm <= max) when set
        if (djActive) {
            int minBpm = state.getDjBpmMin() != null ? state.getDjBpmMin() : 0;
            int maxBpm = state.getDjBpmMax() != null ? state.getDjBpmMax() : 0;
            if (minBpm > 0 || maxBpm > 0) {
                final int lo = minBpm;
                final int hi = maxBpm;
                candidates = candidates.stream()
                        .filter(s -> s.getBpm() >= lo && (hi <= 0 || s.getBpm() <= hi))
                        .collect(java.util.stream.Collectors.toList());
            }
        }

        if (candidates.isEmpty()) {
            return; // No candidates found
        }

        // DJ Mode: enforce the max-consecutive-by-artist cap before scoring
        candidates = excludeSameArtistAtCap(state, profileId, candidates, currentSong.getArtist());
        if (candidates.isEmpty()) {
            return; // No candidates found
        }

        // PHASE 2: Score candidates by Artist + Album
        nextSong = selectBestCandidate(candidates, state, currentSong);

        if (nextSong == null) {
            return; // No smart match found, let the default (shuffled) order proceed
        }

        // Update tracking for consecutive artist/album plays
        updateConsecutiveTracking(state, currentSong, nextSong);

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

    /**
     * Score candidates and select the best one using weighted probability.
     * Scoring system:
     * +3 pts: Same album (if <4 consecutive from this album) - "deep dive" reward
     * -2 pts: Same album (if >=4 consecutive from this album) - "fatigue" penalty  
     * +2 pts: Same artist - artist consistency reward
     * +1 pt:  Different album, same artist - variety bonus
     * 0 pts:  Different artist, different album - neutral
     */
    private Song selectBestCandidate(List<Song> candidates, PlaybackState state, Song currentSong) {
        if (candidates.isEmpty()) {
            return null;
        }
        if (candidates.size() == 1) {
            return candidates.get(0);
        }

        // Score each candidate
        java.util.Map<Song, Integer> songScores = new java.util.HashMap<>();
        String lastAlbum = state.getLastPlayedAlbum();
        String lastArtist = state.getLastPlayedArtist();
        int consecutiveAlbums = state.getConsecutiveAlbumPlays();

        for (Song candidate : candidates) {
            int score = 0;
            String candidateAlbum = candidate.getAlbum();
            String candidateArtist = candidate.getArtist();

            // Album scoring
            if (candidateAlbum != null && !candidateAlbum.isBlank() && 
                lastAlbum != null && candidateAlbum.equalsIgnoreCase(lastAlbum)) {
                if (consecutiveAlbums < 4) {
                    score += 3; // Deep dive reward
                } else {
                    score -= 2; // Fatigue penalty after 4 songs
                }
            }

            // Artist scoring  
            if (candidateArtist != null && !candidateArtist.isBlank() &&
                lastArtist != null && candidateArtist.equalsIgnoreCase(lastArtist)) {
                score += 2; // Same artist reward
                
                // Variety bonus if different album but same artist
                if (candidateAlbum == null || lastAlbum == null || 
                    !candidateAlbum.equalsIgnoreCase(lastAlbum)) {
                    score += 1;
                }
            }

            // BPM similarity scoring — prefers tempo-compatible songs for smoother transitions
            if (currentSong.getBpm() > 0 && candidate.getBpm() > 0) {
                int bpmDiff = Math.abs(candidate.getBpm() - currentSong.getBpm());
                if (bpmDiff <= 3) {
                    score += 2; // Very close BPM match
                } else if (bpmDiff <= 8) {
                    score += 1; // Decent BPM match
                }
            }

            songScores.put(candidate, score);
        }

        // Sort by score descending and take top 5
        List<java.util.Map.Entry<Song, Integer>> sorted = songScores.entrySet().stream()
                .sorted(java.util.Map.Entry.<Song, Integer>comparingByValue().reversed())
                .limit(5)
                .toList();

        if (sorted.isEmpty()) {
            return candidates.get(0);
        }

        // Weighted random selection from top candidates
        // Higher scores get higher probability
        int totalWeight = sorted.stream().mapToInt(java.util.Map.Entry::getValue).map(s -> Math.max(s + 5, 1)).sum();
        int randomWeight = new java.util.Random().nextInt(totalWeight);
        int currentWeight = 0;

        for (java.util.Map.Entry<Song, Integer> entry : sorted) {
            int weight = Math.max(entry.getValue() + 5, 1); // +5 to ensure positive weights
            currentWeight += weight;
            if (randomWeight < currentWeight) {
                return entry.getKey();
            }
        }

        return sorted.get(0).getKey(); // Fallback to highest scored
    }

    /**
     * Update consecutive artist/album tracking in playback state.
     */
    private void updateConsecutiveTracking(PlaybackState state, Song currentSong, Song nextSong) {
        String currentAlbum = currentSong.getAlbum();
        String currentArtist = currentSong.getArtist();
        String nextAlbum = nextSong.getAlbum();
        String nextArtist = nextSong.getArtist();

        // Update album tracking
        if (currentAlbum != null && nextAlbum != null && currentAlbum.equalsIgnoreCase(nextAlbum)) {
            state.setConsecutiveAlbumPlays(state.getConsecutiveAlbumPlays() + 1);
        } else {
            state.setConsecutiveAlbumPlays(1);
        }
        state.setLastPlayedAlbum(nextAlbum);

        // Update artist tracking
        if (currentArtist != null && nextArtist != null && currentArtist.equalsIgnoreCase(nextArtist)) {
            state.setConsecutiveArtistPlays(state.getConsecutiveArtistPlays() + 1);
        } else {
            state.setConsecutiveArtistPlays(1);
        }
        state.setLastPlayedArtist(nextArtist);
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
            state.setSecondaryCueIndex(-1);
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
            List<Song> songsInGenre = new ArrayList<>(songsByGenre.get(genre));

            // Sort by BPM similarity to current song's BPM. Equal-BPM songs keep
            // their pre-shuffle order (TimSort is stable), so variety is preserved
            // without a non-deterministic comparator.
            final int targetBpm = currentBpm;
            if (targetBpm > 0) {
                Collections.shuffle(songsInGenre);
                songsInGenre.sort((a, b) -> {
                    int aBpm = a.getBpm() > 0 ? a.getBpm() : targetBpm;
                    int bBpm = b.getBpm() > 0 ? b.getBpm() : targetBpm;
                    return Integer.compare(Math.abs(aBpm - targetBpm), Math.abs(bBpm - targetBpm));
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
        state.setSecondaryCueIndex(newIndex);
    }
    
    /**
     * Prepare a DJ Mode transition - find a BPM-matched song and position it as next in queue.
     * Called from planNextDjTransition() before calculating the beat-aligned crossfade.
     * Excludes recently played songs to avoid repeats.
     */
    @Transactional
    public void prepareDjModeTransition(PlaybackState state, Long profileId, int djModeBpmTolerance) {
        Song currentSong = songService.find(state.getCurrentSongId());
        if (currentSong == null || currentSong.getBpm() <= 0) {
            return;
        }
        
        // Use the current active cue as the song pool to avoid picking songs that are no longer in the queue
        // (e.g., finished songs that were removed from the cue but still present in originalCue).
        // This also prevents resurrecting stale songs when smart shuffle has reorganized the queue.
        List<Long> songPool = state.getCue();
        
        if (songPool == null || songPool.isEmpty()) {
            return;
        }
        
        // DJ Mode: restrict the selection pool to allowed genres when a genre pool is configured
        if (!isDjGenrePoolUnrestricted(state)) {
            List<Long> allowedInCue = songService.findByIds(songPool).stream()
                    .filter(s -> isGenreAllowed(state, s.getGenre()))
                    .map(s -> s.id)
                    .collect(java.util.stream.Collectors.toList());
            if (allowedInCue.isEmpty()) {
                // Cue has no songs matching the pool — widen to the original cue so the
                // configured genres are playable without waiting for a queue rebuild
                List<Long> widerPool = (state.getOriginalCue() != null && !state.getOriginalCue().isEmpty())
                        ? state.getOriginalCue() : null;
                if (widerPool == null) {
                    return;
                }
                songPool = songService.findByIds(widerPool).stream()
                        .filter(s -> isGenreAllowed(state, s.getGenre()))
                        .map(s -> s.id)
                        .collect(java.util.stream.Collectors.toList());
                if (songPool.isEmpty()) {
                    return;
                }
            } else {
                songPool = allowedInCue;
            }
        }
        
        // Exclude recently played songs (last 12) and current song
        List<Long> recentSongIds = playbackHistoryService.getRecentlyPlayedSongIds(12, profileId);
        List<Long> exclusions = new ArrayList<>(recentSongIds);
        if (currentSong.id != null) {
            exclusions.add(currentSong.id);
        }
        
        Song nextSong = null;
        
        // Try genre + BPM match first (only if the current song's genre is within the allowed pool)
        boolean currentGenreAllowed = currentSong.getGenre() != null && !currentSong.getGenre().isBlank()
                && (isDjGenrePoolUnrestricted(state) || isGenreAllowed(state, currentSong.getGenre()));
        if (currentGenreAllowed) {
            List<Song> candidates = songService.findCandidatesByGenreAndBpm(
                    currentSong.getGenre(),
                    currentSong.getBpm(),
                    djModeBpmTolerance,
                    exclusions,
                    songPool,
                    10
            );
            candidates = excludeSameArtistAtCap(state, profileId, candidates, currentSong.getArtist());
            if (!candidates.isEmpty()) {
                java.util.Collections.shuffle(candidates);
                nextSong = candidates.get(0);
            }
        }
        
        // Fall back to BPM-only search if no genre match
        if (nextSong == null) {
            List<Song> allSongs = songService.findByIds(songPool);
            List<Song> bpmMatched = allSongs.stream()
                    .filter(s -> s.id != null && !exclusions.contains(s.id))
                    .filter(s -> s.getBpm() > 0)
                    .filter(s -> Math.abs(s.getBpm() - currentSong.getBpm()) <= djModeBpmTolerance)
                    .sorted((a, b) -> {
                        int diffA = Math.abs(a.getBpm() - currentSong.getBpm());
                        int diffB = Math.abs(b.getBpm() - currentSong.getBpm());
                        return Integer.compare(diffA, diffB);
                    })
                    .collect(java.util.stream.Collectors.toList());
            bpmMatched = excludeSameArtistAtCap(state, profileId, bpmMatched, currentSong.getArtist());
            
            if (!bpmMatched.isEmpty()) {
                java.util.Collections.shuffle(bpmMatched);
                nextSong = bpmMatched.get(0);
            }
        }
        
        // Final fallback: no BPM match — pick any song from the (pool-restricted) selection pool
        if (nextSong == null) {
            List<Song> allSongs = songService.findByIds(songPool);
            List<Song> available = allSongs.stream()
                    .filter(s -> s.id != null && !exclusions.contains(s.id))
                    .collect(java.util.stream.Collectors.toList());
            available = excludeSameArtistAtCap(state, profileId, available, currentSong.getArtist());
            if (!available.isEmpty()) {
                java.util.Collections.shuffle(available);
                nextSong = available.get(0);
            }
        }
        
        // Position the chosen song as the next one in the active cue
        if (nextSong != null) {
            List<Long> cue = state.getCue();
            if (cue != null) {
                int currentSongIndexInCue = state.getCueIndex();
                int nextSongCurrentIndex = cue.indexOf(nextSong.id);
                
                if (nextSongCurrentIndex == -1) {
                    // Song not in active cue — add it right after current song
                    int insertionPoint = Math.min(currentSongIndexInCue + 1, cue.size());
                    cue.add(insertionPoint, nextSong.id);
                } else if (nextSongCurrentIndex != currentSongIndexInCue + 1) {
                    // Song is elsewhere in cue — move it to next position
                    Long songToMove = cue.remove(nextSongCurrentIndex);
                    int insertionPoint = Math.min(currentSongIndexInCue + 1, cue.size());
                    cue.add(insertionPoint, songToMove);
                }
            }
        }
    }

    private boolean isDjGenrePoolUnrestricted(PlaybackState state) {
        List<String> allowed = state.getDjGenrePool();
        return allowed == null || allowed.isEmpty();
    }

    private boolean isGenreAllowed(PlaybackState state, String genre) {
        if (isDjGenrePoolUnrestricted(state)) {
            return true;
        }
        String normalizedGenre = SongService.normalizeGenre(genre);
        if (normalizedGenre.isEmpty()) {
            return false;
        }
        for (String allowed : state.getDjGenrePool()) {
            if (allowed != null && SongService.normalizeGenre(allowed).equals(normalizedGenre)) {
                return true;
            }
        }
        return false;
    }

    private int countConsecutiveSameGenre(PlaybackState state, List<Long> cue, Song currentSong) {
        if (currentSong == null || cue == null || cue.isEmpty()) {
            return 0;
        }
        String currentGenre = currentSong.getGenre();
        if (currentGenre == null || currentGenre.isBlank()) {
            return 0;
        }
        int currentIndex = state.getCueIndex();
        if (currentIndex < 0 || currentIndex >= cue.size()) {
            return 1;
        }
        int streak = 1;
        for (int i = currentIndex - 1; i >= 0; i--) {
            Song song = songService.find(cue.get(i));
            if (song != null && SongService.normalizeGenre(song.getGenre()).equals(SongService.normalizeGenre(currentGenre))) {
                streak++;
            } else {
                break;
            }
        }
        return streak;
    }

    /**
     * Counts consecutive same-artist songs at the top of the playback history
     * (playedAt DESC). Persisted source of truth for the DJ artist cap — works
     * across restarts, unlike the transient consecutiveArtistPlays counter.
     */
    private int countConsecutiveSameArtistFromHistory(Long profileId, String artist) {
        if (artist == null || artist.isBlank()) {
            return 0;
        }
        List<Long> recent = playbackHistoryService.getRecentlyPlayedSongIds(12, profileId);
        int count = 0;
        for (Long id : recent) {
            if (id == null) {
                break;
            }
            Song song = songService.find(id);
            if (song == null || song.getArtist() == null || song.getArtist().isBlank()) {
                break;
            }
            if (artist.equalsIgnoreCase(song.getArtist())) {
                count++;
            } else {
                break;
            }
        }
        return count;
    }

    /**
     * Removes same-artist candidates when the current artist streak has hit the
     * DJ cap. Keeps the original list when the cap is off, the streak is below
     * the cap, or filtering would empty the list (no alternative available).
     */
    private List<Song> excludeSameArtistAtCap(PlaybackState state, Long profileId, List<Song> candidates, String currentArtist) {
        Integer cap = state.getDjMaxConsecutiveByArtist();
        if (cap == null || cap <= 0 || candidates.isEmpty() || currentArtist == null || currentArtist.isBlank()) {
            return candidates;
        }
        if (countConsecutiveSameArtistFromHistory(profileId, currentArtist) < cap) {
            return candidates;
        }
        List<Song> filtered = candidates.stream()
                .filter(s -> s.getArtist() == null || s.getArtist().isBlank()
                        || !currentArtist.equalsIgnoreCase(s.getArtist()))
                .collect(java.util.stream.Collectors.toList());
        return filtered.isEmpty() ? candidates : filtered;
    }

    /**
     * Breaks same-artist runs longer than the DJ cap by swapping in a later
     * song from a different artist. Applied to the built cue in initSmartShuffle.
     */
    private void enforceMaxConsecutiveByArtistInCue(PlaybackState state, List<Long> cue) {
        Integer cap = state.getDjMaxConsecutiveByArtist();
        if (cap == null || cap <= 0 || cue == null || cue.size() <= 1) {
            return;
        }
        java.util.Map<Long, Song> songsById = songService.findByIds(cue).stream()
                .collect(java.util.stream.Collectors.toMap(s -> s.id, s -> s));
        String lastArtist = null;
        int streak = 0;
        for (int i = 0; i < cue.size(); i++) {
            Song song = songsById.get(cue.get(i));
            String artist = song != null ? song.getArtist() : null;
            if (artist != null && !artist.isBlank() && artist.equalsIgnoreCase(lastArtist)) {
                streak++;
            } else {
                streak = 1;
                lastArtist = artist;
            }
            if (streak > cap) {
                int swapIdx = -1;
                for (int j = i + 1; j < cue.size(); j++) {
                    Song candidate = songsById.get(cue.get(j));
                    String candidateArtist = candidate != null ? candidate.getArtist() : null;
                    if (candidateArtist == null || candidateArtist.isBlank()
                            || !candidateArtist.equalsIgnoreCase(artist)) {
                        swapIdx = j;
                        break;
                    }
                }
                if (swapIdx == -1) {
                    break; // no alternative later in the queue — accept the run
                }
                Long tmp = cue.get(i);
                cue.set(i, cue.get(swapIdx));
                cue.set(swapIdx, tmp);
                streak = 1;
                Song swapped = songsById.get(cue.get(i));
                lastArtist = swapped != null ? swapped.getArtist() : null;
            }
        }
    }
}
