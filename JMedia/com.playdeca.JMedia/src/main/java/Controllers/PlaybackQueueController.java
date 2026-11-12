package Controllers;

import Models.Music.PlaybackState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import Models.Music.Song;
import Services.PlaybackHistoryService;
import Services.SongService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import Models.Music.PlaybackHistory;
import Services.AudioAnalysisService;
import Utils.GenreNormalizer;

@ApplicationScoped
public class PlaybackQueueController {

    private static final int MAX_QUEUE_AHEAD = 500;
    private static final int MAX_QUEUE_BEHIND = 100;
    private static final Logger LOG = LoggerFactory.getLogger(PlaybackQueueController.class);

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



public void enforceQueueWindow(PlaybackState st) {
        if (st == null) return;
        List<Long> cue = st.getCue();
        if (cue == null) return;
        int cueIndex = st.getCueIndex();
        int beforeIndex = cueIndex;
        int beforeCue = cue.size();
        int beforeOrig = -1;
        List<Long> orig = st.getOriginalCue();
        if (orig != null) beforeOrig = orig.size();
        boolean trimmed = false;

        // Bound the played prefix too. Keeping the entire prefix let the queue
        // grow to the library size (11k+) as playback advanced.
        if (cueIndex > MAX_QUEUE_BEHIND) {
            int drop = cueIndex - MAX_QUEUE_BEHIND;
            cue.subList(0, drop).clear();
            cueIndex -= drop;
            st.setCueIndex(cueIndex);
            trimmed = true;
        }

        int max = Math.max(0, cueIndex) + 1 + MAX_QUEUE_AHEAD;
        if (cue.size() > max) {
            cue.subList(max, cue.size()).clear();
            trimmed = true;
        }
        // Do NOT trim originalCue: it is the source pool Smart Shuffle refills
        // from. Window only the active cue so a playlist/library source is not
        // silently replaced by the whole library at the end of the window.
        if (trimmed) {
            LOG.info("Trimmed playback queue window for profile {}: cueIndex {} -> {}, cue {} -> {}, originalCue {} (kept), max={}",
                    st.getProfileId(), beforeIndex, cueIndex, beforeCue, cue.size(), beforeOrig, max);
        }
    }

    private void ensureQueueWindow(PlaybackState st) {
        if (st == null) return;
        List<Long> cue = st.getCue();
        if (cue == null || cue.isEmpty()) return;
        // A playlist queue must keep its own order; refillCueFromPool rebuilds
        // from the library pool and would replace it.
        if (st.getCurrentPlaylistId() != null) return;
        int cueIndex = st.getCueIndex();
        if (cueIndex < 0) return;
        // Refill only when the window is truly exhausted (the current song is
        // the last one). Refilling 50 songs early discards the remaining ones.
        if (cue.size() - cueIndex > 1) return;
        boolean shouldRefill = st.getRepeatMode() == PlaybackState.RepeatMode.ALL
                || st.getShuffleMode() == PlaybackState.ShuffleMode.SMART_SHUFFLE;
        if (!shouldRefill) return;
        Long profileId = st.getProfileId();
        if (profileId == null) return;
        refillCueFromPool(st, profileId, true);
    }

    public void populateCue(PlaybackState state, List<Long> songIds, Long profileId) {
        state.setCue(new ArrayList<>(songIds));
        state.setOriginalCue(new ArrayList<>()); // Clear any previously saved original cue
        state.setCueIndex(songIds.isEmpty() ? -1 : 0);
        state.setCurrentSongId(songIds.isEmpty() ? null : songIds.get(0));
        state.setPlaying(!songIds.isEmpty());
        state.setCurrentTime(0);
        enforceQueueWindow(state);
    }

public Long advance(PlaybackState state, boolean forward, boolean skippedEarly, boolean manualAdvance, Long profileId) {
        return advance(state, forward, skippedEarly, manualAdvance, profileId, 0);
    }

    /**
     * Depth-guarded implementation. A manual advance over a single-song cue
     * removes the song and re-enters advance(), which refills and can land on a
     * one-song cue again. Without this guard that mutual recursion overflowed the
     * stack, marked the JTA transaction rollback-only and left the connection
     * pool failing fast for every subsequent request (including login).
     */
    Long advance(PlaybackState state, boolean forward, boolean skippedEarly,
                 boolean manualAdvance, Long profileId, int depth) {
        if (depth > 3) {
            LOG.warn("Playback advance recursion exceeded depth {} (single-song cue, repeat={}); stopping instead of looping",
                    depth, state.getRepeatMode());
            state.setPlaying(false);
            return null;
        }
        // When the single queue is empty, refill from the song pool (DJ-filtered if active).
        // Refill is gated on RepeatMode.ALL OR a manual forward advance. A manual
        // next/play (manualAdvance && forward) with an empty cue starts a fresh
        // library pool, like most music players. A NATURAL song end (manualAdvance=
        // false) with Repeat OFF must STOP: the queue is consumed exactly once, and
        // silently refilling from the library forever was the infinite-refill bug.
        // DJ mode without Repeat ALL also stops on natural end — a DJ set is still a
        // bounded queue, while a manual press is a deliberate request for more music.
        if (state.getCue() == null || state.getCue().isEmpty()) {
            if (state.getRepeatMode() == PlaybackState.RepeatMode.ALL
                    || (manualAdvance && forward)) {
                refillCueFromPool(state, profileId, manualAdvance);
            } else {
                // Repeat OFF (or ONE) natural end: do NOT refill — signal stop so
                // PlaybackController halts playback instead of looping.
                state.setPlaying(false);
                return null;
            }
        }

        if (state.getCue() == null || state.getCue().isEmpty()) {
            // No songs available at all
            state.setPlaying(false);
            return null;
        }

        // Recover a lost cue index: if playback is on a song that is still in the
        // queue but cueIndex is -1 (e.g. after a DJ rebuild), re-locate it so a
        // natural advance removes the finished song instead of replaying the head.
        if (state.getCueIndex() < 0 && state.getCurrentSongId() != null) {
            int found = state.getCue().indexOf(state.getCurrentSongId());
            if (found >= 0) {
                state.setCueIndex(found);
            }
        }

        // Degenerate-pool guard: size==1 must respect RepeatMode.
        // Repeat OFF consumes the last song and stops (see handleSingleSongCue).
        // Smart Shuffle widening below is gated on RepeatMode.ALL only.
        if (state.getCue().size() == 1) {
            if (state.getShuffleMode() == PlaybackState.ShuffleMode.SMART_SHUFFLE && hasDjRestriction(state)
                    && state.getRepeatMode() == PlaybackState.RepeatMode.ALL) {
                List<Long> unrestricted = songService.findAll().stream()
                        .map(s -> s.id)
                        .collect(java.util.stream.Collectors.toList());
                if (unrestricted.size() > 1) {
                    state.setCue(new ArrayList<>(unrestricted));
                    state.setOriginalCue(new ArrayList<>());
                    state.setCueIndex(-1);
                    if (state.getShuffleMode() == PlaybackState.ShuffleMode.SHUFFLE) {
                        initShuffle(state, profileId);
                    } else if (state.getShuffleMode() == PlaybackState.ShuffleMode.SMART_SHUFFLE) {
                        initSmartShuffle(state, profileId);
                    } else {
                        java.util.Collections.shuffle(state.getCue());
                        Long currentId = state.getCurrentSongId();
                        if (currentId != null && state.getCue().contains(currentId)) {
                            state.getCue().remove(currentId);
                            state.getCue().add(0, currentId);
                        }
                        state.setCueIndex(0);
                    }
                    enforceQueueWindow(state);
                    if (state.getCue().size() > 1) {
                        return advanceInQueue(state, forward, skippedEarly, manualAdvance, profileId);
                    }
                }
            }
            return handleSingleSongCue(state, forward, skippedEarly, manualAdvance, profileId, depth);
        }

        Long __r = advanceInQueue(state, forward, skippedEarly, manualAdvance, profileId);
        enforceQueueWindow(state);
        return __r;
    }

    private Long handleSingleSongCue(PlaybackState state, boolean forward, boolean skippedEarly, boolean manualAdvance, Long profileId, int depth) {
        // Single-song cue with Repeat OFF must NOT loop forever.
        // Natural end consumes the last song and stops; manual next
        // consumes it and refills via the empty-cue path above.
        if (!forward) {
            if (state.getRepeatMode() == PlaybackState.RepeatMode.ALL) {
                state.setCueIndex(0);
                state.setCurrentSongId(state.getCue().get(0));
                enforceQueueWindow(state);
            return resolveNextDjSong(state, state.getCue(), 0, state.getCue().get(0));
            }
            return null;
        }
        if (state.getRepeatMode() == PlaybackState.RepeatMode.ONE
                || state.getRepeatMode() == PlaybackState.RepeatMode.ALL) {
            state.setCueIndex(0);
            state.setCurrentSongId(state.getCue().get(0));
            enforceQueueWindow(state);
            return resolveNextDjSong(state, state.getCue(), 0, state.getCue().get(0));
        }
        state.getCue().remove(0);
        state.setCueIndex(-1);
        if (manualAdvance) {
            enforceQueueWindow(state);
            return advance(state, true, skippedEarly, true, profileId, depth + 1);
        }
        state.setPlaying(false);
        enforceQueueWindow(state);
        return null;
    }

    private Long advanceInQueue(PlaybackState state, boolean forward, boolean skippedEarly, boolean manualAdvance, Long profileId) {
        try {
        if (forward) {
            ensureQueueWindow(state);
        }
        List<Long> cue = state.getCue();
        int cueIndex = state.getCueIndex();
        
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
            
            state.setCueIndex(prevIndex);
            return cue.get(prevIndex);
        }

        // --- FORWARD ADVANCEMENT ---
        if (cueIndex < 0) {
            // Fresh or refilled queue with no active position: play the head
            // instead of consuming it (a bare reset to 0 here would remove
            // cue.get(0) in the RepeatMode.OFF path below, skipping a song).
            // Never replay the currently-playing song as the "next" track.
            if (!cue.isEmpty() && cue.get(0).equals(state.getCurrentSongId())) {
                cue.remove(0);
                if (cue.isEmpty()) {
                    state.setCueIndex(-1);
                    return advance(state, true, skippedEarly, manualAdvance, profileId);
                }
            }
            if (cue.isEmpty()) {
                state.setCueIndex(-1);
                return null;
            }
            state.setCueIndex(0);
            return resolveNextDjSong(state, cue, 0, cue.get(0));
        }

        // Smart Shuffle is the only selector. DJ Mode only mixes, so it must not
        // trigger a smart selection when shuffle is off.
        if (state.getShuffleMode() == PlaybackState.ShuffleMode.SMART_SHUFFLE) {
            findAndPrepareNextSmartSong(state, skippedEarly, profileId);
            // Re-enforce the artist cap after the smart pick.
            enforceMaxConsecutiveByArtistInCue(state, state.getCue());
        }

        if (state.getRepeatMode() == PlaybackState.RepeatMode.OFF) {
            // Remove played song
            if (cueIndex >= 0 && cueIndex < cue.size()) {
                cue.remove(cueIndex);
            }
            
            if (cue.isEmpty()) {
                state.setCueIndex(-1);
                return advance(state, true, skippedEarly, manualAdvance, profileId);
            }
            
            // Keep index at current position because list shifted
            int nextIdx = Math.min(cueIndex, cue.size() - 1);
            state.setCueIndex(nextIdx);
            return resolveNextDjSong(state, cue, nextIdx, cue.get(nextIdx));
        }

        // Logic for RepeatMode.ALL or ONE
        int nextIndex = (cueIndex + 1) % cue.size();
        state.setCueIndex(nextIndex);
        return resolveNextDjSong(state, cue, nextIndex, cue.get(nextIndex));
        } finally {
            enforceQueueWindow(state);
        }
    }

    /**
     * DJ Mode pool guard: if a DJ pool restriction is active (genre pool and/or year
     * range), the song that actually plays next must satisfy it. Returns the fallback
     * song when no restriction is active or when the fallback is already pool-valid.
     * Otherwise walks the queue forward (wrapping) from startIndex to the nearest
     * pool-valid song and moves the active index onto it. Returns null when no
     * pool-valid song exists anywhere in the queue, so the caller stops playback
     * instead of playing a song outside the DJ pool.
     */
    private Long resolveNextDjSong(PlaybackState state, List<Long> cue, int startIndex, Long fallback) {
        if (!hasDjRestriction(state) || cue == null || cue.isEmpty()) {
            return fallback;
        }
        Song fallbackSong = songService.find(fallback);
        if (fallbackSong != null && isYearAllowed(state, fallbackSong.getReleaseDate())
                && isGenreAllowed(state, fallbackSong.getGenre())) {
            return fallback;
        }
        java.util.Map<Long, Song> songsById = songService.findByIds(cue).stream()
                .collect(java.util.stream.Collectors.toMap(s -> s.id, s -> s));
        for (int i = 0; i < cue.size(); i++) {
            int idx = (startIndex + i) % cue.size();
            Song candidate = songsById.get(cue.get(idx));
            if (candidate != null && isYearAllowed(state, candidate.getReleaseDate())
                    && isGenreAllowed(state, candidate.getGenre())) {
                state.setCueIndex(idx);
                return candidate.id;
            }
        }
        // No pool-valid song exists anywhere in the queue. Returning the forbidden
        // fallback would play a song outside the DJ pool — instead signal "no valid
        // next song" so the caller (advance -> PlaybackController) stops playback
        // rather than violating the genre/year restriction.
        return null;
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
        enforceQueueWindow(state);
    }

    public void initSmartShuffle(PlaybackState state, Long profileId) {
        // 1. Get the pool of songs
        List<Long> songPoolIds = (state.getOriginalCue() != null && !state.getOriginalCue().isEmpty())
                ? new ArrayList<>(state.getOriginalCue())
                : new ArrayList<>(state.getCue());

        // DJ Mode with a restricted genre pool and no queue yet: seed from the whole
        // library so the pool-aware rebuild produces a playable queue instead of a no-op.
        boolean seededFromDjLibrary = false;
        if (songPoolIds.isEmpty()
                && Boolean.TRUE.equals(state.getDjModeActive())
                && hasDjRestriction(state)) {
            songPoolIds = songService.findAll().stream()
                    .map(s -> s.id)
                    .collect(java.util.stream.Collectors.toList());
            seededFromDjLibrary = true;
        }

        if (songPoolIds.isEmpty()) {
            return;
        }

        // Save original cue if it wasn't saved before. When DJ mode seeded the pool
        // from the whole library (seededFromDjLibrary), do NOT persist that widened
        // list as the original cue — it would pollute the user's real queue order.
        if (!seededFromDjLibrary
                && (state.getOriginalCue() == null || state.getOriginalCue().isEmpty())) {
            state.setOriginalCue(new ArrayList<>(songPoolIds));
        }

        List<Song> allSongs = songService.findByIds(songPoolIds);

        // Smart Shuffle: restrict the pool to the configured genre pool, year
        // range and explicit BPM range before grouping (these were DJ-scoped
        // settings; selection now belongs to Smart Shuffle).
        boolean applySelectionFilters = true;
        if (applySelectionFilters) {
            final int minBpm = state.getDjBpmMin() != null ? state.getDjBpmMin() : 0;
            final int maxBpm = state.getDjBpmMax() != null ? state.getDjBpmMax() : 0;
            allSongs = allSongs.stream()
                    .filter(s -> isDjGenrePoolUnrestricted(state) || isGenreAllowed(state, s.getGenre()))
                    .filter(s -> isYearAllowed(state, s.getReleaseDate()))
                    .filter(s -> minBpm <= 0 || s.getBpm() >= minBpm)
                    .filter(s -> maxBpm <= 0 || s.getBpm() <= maxBpm)
                    .collect(java.util.stream.Collectors.toList());
        }
        if (allSongs.isEmpty()) {
            LOG.warn("Smart Shuffle pool is empty after filters for profile " + profileId
                    + "; keeping the current queue");
            return;
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

        // 2. Group songs by primary canonical genre. Songs sharing a canonical
        // genre (Rock / rock / Rock/Pop) stay in one rotation bucket.
        java.util.Map<String, List<Song>> songsByGenre = allSongs.stream()
                .collect(java.util.stream.Collectors.groupingBy(song -> genreKey(song.getGenre())));

        // 3. Shuffle the order of genres
        List<String> genres = new ArrayList<>(songsByGenre.keySet());
        Collections.shuffle(genres);

        // 4. Build the new queue with BPM sorting within genre groups
        List<Long> newCue = new ArrayList<>();

        // Smart Shuffle block size: songsPerGenre > 0 interleaves genres in
        // blocks. 0 means "off" for the block size, so use a one-song-per-genre
        // rotation instead of playing all songs of one genre in a row.
        int blockSize = (applySelectionFilters && state.getDjSongsPerGenre() != null && state.getDjSongsPerGenre() > 0)
                ? state.getDjSongsPerGenre() : (applySelectionFilters ? 1 : 0);

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
                    // Unknown BPM (0) must sort to the END of the genre group —
                    // treating it as targetBpm made unanalyzed songs rank as
                    // perfect matches ahead of analyzed ones during catch-up.
                    int aDist = a.getBpm() > 0 ? Math.abs(a.getBpm() - targetBpm) : Integer.MAX_VALUE;
                    int bDist = b.getBpm() > 0 ? Math.abs(b.getBpm() - targetBpm) : Integer.MAX_VALUE;
                    return Integer.compare(aDist, bDist);
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

        if (applySelectionFilters) {
            enforceMaxConsecutiveByArtistInCue(state, newCue);
        }

        // 5. Update the state
        state.setCue(newCue);
        int newIndex = newCue.indexOf(state.getCurrentSongId());

        // When the current song is not in the rebuilt pool (e.g. it came from
        // outside the source queue), keep it as the current track instead of
        // swapping the audible/selected song to the head. The next advance
        // starts from the rebuilt pool.
        if (newIndex != -1) {
            state.setCueIndex(newIndex);
        } else if (state.getCurrentSongId() != null) {
            newCue.add(0, state.getCurrentSongId());
            state.setCueIndex(0);
        } else if (!newCue.isEmpty()) {
            state.setCueIndex(0);
            state.setCurrentSongId(newCue.get(0));
        } else {
            state.setCueIndex(-1);
        }
        
        // 6. Pre-analyze upcoming songs for DJ Mode transitions
        audioAnalysisService.ensureUpcomingSongsAnalyzed(newCue, 5);
        enforceQueueWindow(state);
    }

    public void clearShuffle(PlaybackState state, Long profileId) {
        List<Long> originalCue = state.getOriginalCue();
        // Check if there is an original cue to restore from
        if (originalCue != null && !originalCue.isEmpty()) {
            List<Long> restored = new ArrayList<>(originalCue);
            state.setCue(restored);
            // Keep the full source pool for a later Smart Shuffle rebuild. The
            // active cue is windowed separately by enforceQueueWindow.
            state.setOriginalCue(new ArrayList<>(restored));
        }

        // After restoring, find the index of the current song. If it came from
        // outside the restored source (radio/queue selection), put it at the
        // head so the restore does not silently switch the current track.
        if (state.getCue() != null && state.getCurrentSongId() != null) {
            int newIndex = state.getCue().indexOf(state.getCurrentSongId());
            if (newIndex == -1) {
                state.getCue().add(0, state.getCurrentSongId());
                newIndex = 0;
            }
            state.setCueIndex(newIndex);
        } else {
            state.setCueIndex(-1);
        }
        enforceQueueWindow(state);
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
        enforceQueueWindow(state);
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
        enforceQueueWindow(state);
    }

    public void clear(PlaybackState state, Long profileId) {
        state.setCue(new ArrayList<>());
        state.setOriginalCue(new ArrayList<>());
        state.setCueIndex(-1);
        state.setCurrentSongId(null);
        state.setPlaying(false);
        state.setCurrentTime(0);
        state.setLastSongs(new ArrayList<>());
        // Reset the DJ transition plan so no stale crossfade fires after a clear.
        // DJ settings (genre pool, BPM/year bounds, strictness) are persisted
        // preferences and intentionally left untouched.
        state.setDjNextSongId(null);
        state.setDjEntryTime(null);
        state.setDjExitTime(null);
        state.setDjTransitionPlanned(null);
        state.setDjTransitionConfidence(null);
        state.setDjTransitionReason(null);
        state.setDjGenreSkipCount(0);
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
        enforceQueueWindow(state);
    }

    public void togglePlay(PlaybackState state, Long profileId) {
        state.setPlaying(!state.isPlaying());
    }

    public void setRepeat(PlaybackState state, PlaybackState.RepeatMode mode, Long profileId) {
        if (mode == null) {
            mode = PlaybackState.RepeatMode.OFF;
        }
        state.setRepeatMode(mode);
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
        enforceQueueWindow(state);
    }

private void findAndPrepareNextSmartSong(PlaybackState state, boolean skippedEarly, Long profileId) {
    // A natural advance (song ended on its own) breaks any consecutive-skip streak
    if (!skippedEarly) {
        state.setDjGenreSkipCount(0);
    }
    // FIX: If a DJ transition is already planned, DO NOT pick a new random song.
    // We must respect the song the DJ has already analyzed and aligned.
    if (!skippedEarly && Boolean.TRUE.equals(state.getDjTransitionPlanned()) && state.getDjNextSongId() != null) {
        System.out.println("[DJ] Respecting already planned transition to song ID: " + state.getDjNextSongId());
        return;
    }

    // songsPerGenre = 0 is the mixed mode: initSmartShuffle already built a
    // one-song-per-genre rotation. Replacing the queued next with a random
    // same-genre candidate is what made Smart Shuffle feel erratic, so keep
    // the interleaved order. The artist-cap pass still runs after this method.
    boolean mixedMode = state.getDjSongsPerGenre() == null || state.getDjSongsPerGenre() <= 0;
    if (mixedMode) {
        if (skippedEarly) {
            state.setDjGenreSkipCount(0);
        }
        return;
    }

    Song currentSong = songService.find(state.getCurrentSongId());

        if (currentSong == null) {
            return;
        }

        List<Long> cue = state.getCue();
        int cueSize = (cue != null) ? cue.size() : 0;
        List<Long> songPool;
        if (Boolean.TRUE.equals(state.getDjModeActive())) {
            songPool = buildSongPool(state);
            if (songPool.isEmpty()) {
                songPool = songService.findAll().stream()
                        .map(s -> s.id)
                        .collect(java.util.stream.Collectors.toList());
                if (songPool.isEmpty()) {
                    return;
                }
            }
        } else {
            if (cueSize <= 1) {
                return;
            }
            songPool = (state.getOriginalCue() != null && !state.getOriginalCue().isEmpty())
                    ? state.getOriginalCue() : cue;
        }

        // ENHANCED SMART SHUFFLE: Multi-candidate scoring with artist/album awareness
        Song nextSong = null;
        String targetGenre;

        // Smart Shuffle selection settings (formerly DJ-scoped): block size,
        // skip threshold, BPM tolerance/range and artist cap.
        boolean applySelectionFilters = true; // this method only runs for SMART_SHUFFLE
        boolean forceDifferentGenre = applySelectionFilters
                && state.getDjSongsPerGenre() != null && state.getDjSongsPerGenre() > 0
                && currentSong.getGenre() != null && !currentSong.getGenre().isBlank()
                && countConsecutiveSameGenre(state, cue, currentSong) >= state.getDjSongsPerGenre();

        // DJ Mode skip threshold: switch genre on the Nth consecutive early skip.
        // 0 disables genre switching on skip (never switch); N >= 1 switches on the
        // Nth consecutive skip. Default 1 preserves the legacy switch-every-skip behavior.
        Integer skipsBeforeGenreChange = state.getDjSkipsBeforeGenreChange();
        boolean switchGenreOnSkip = false;
        if (skippedEarly && applySelectionFilters && skipsBeforeGenreChange != null && skipsBeforeGenreChange > 0
                && currentSong.getGenre() != null && !currentSong.getGenre().isBlank()) {
            int skipCount = state.getDjGenreSkipCount() + 1;
            state.setDjGenreSkipCount(skipCount);
            switchGenreOnSkip = skipCount >= skipsBeforeGenreChange;
        }

        if (switchGenreOnSkip || forceDifferentGenre) {
            // User skipped early (threshold reached) - pick a song from a DIFFERENT genre
            // Reset the consecutive-skip streak: it restarts after the genre switch
            state.setDjGenreSkipCount(0);
            List<Song> allSongsInPool = songService.findByIds(songPool);
            java.util.Map<String, List<Song>> songsByGenre = allSongsInPool.stream()
                    .collect(java.util.stream.Collectors.groupingBy(song -> genreKey(song.getGenre())));

            String currentGenre = genreKey(currentSong.getGenre());
            List<String> otherGenres = songsByGenre.keySet().stream()
                    .filter(g -> !g.equals(currentGenre))
                    .collect(java.util.stream.Collectors.toList());

            if (otherGenres.isEmpty()) {
                targetGenre = currentGenre; // Fallback to current genre if no others
            } else {
                java.util.Collections.shuffle(otherGenres);
                targetGenre = otherGenres.get(0);
            }
        } else {
            // Normal case - stay in the same canonical genre
            targetGenre = genreKey(currentSong.getGenre());
        }

        if (targetGenre == null || targetGenre.isBlank()) {
            return; // No genre info, can't do smart shuffle
        }

        // Get BPM tolerance from settings
        int bpmTolerance = 10;
        if (settingsController != null) {
            bpmTolerance = settingsController.getOrCreateSettings().getBpmToleranceForGenre(targetGenre);
        }
        // Smart Shuffle strictness overrides tolerance: LOW=15, MEDIUM=djModeBpmTolerance (default 5), HIGH=3
        if (applySelectionFilters) {
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

        // Smart Shuffle: enforce explicit BPM range (min <= songBpm <= max) when set
        if (applySelectionFilters) {
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

        if (nextSongCurrentIndex == -1) {
            // Scored candidate is not in the active cue (e.g. Repeat OFF shrinking
            // queue) — insert it right after the current song so it actually plays
            // next instead of being silently discarded.
            int insertionPoint = Math.min(currentSongIndexInCue + 1, cue.size());
            cue.add(insertionPoint, nextSongId);
        } else if (nextSongCurrentIndex != currentSongIndexInCue + 1) {
            // If the smart song is already in the cue and not next, move it.
            Long songToMove = cue.remove(nextSongCurrentIndex);
            // Removal shifts the current song left when the moved song sat before
            // it — re-locate it or the insertion lands past "next" and a wrong
            // song plays.
            int refreshedCurrent = cue.indexOf(state.getCurrentSongId());
            int anchor = refreshedCurrent != -1 ? refreshedCurrent : currentSongIndexInCue;
            int insertionPoint = Math.min(anchor + 1, cue.size());
            cue.add(insertionPoint, songToMove);
        }

        // Re-sync cueIndex after cue manipulation — removing from an index < cueIndex
        // shifts the current song left, invalidating the stored index.
        int refreshed = cue.indexOf(state.getCurrentSongId());
        if (refreshed != -1) {
            state.setCueIndex(refreshed);
        }
        enforceQueueWindow(state);
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


    private boolean isDjGenrePoolUnrestricted(PlaybackState state) {
        List<String> allowed = state.getDjGenrePool();
        return allowed == null || allowed.isEmpty();
    }

    private boolean hasDjRestriction(PlaybackState state) {
        return !isDjGenrePoolUnrestricted(state)
                || (state.getDjYearMin() != null && state.getDjYearMin() > 0)
                || (state.getDjYearMax() != null && state.getDjYearMax() > 0);
    }

    boolean isGenreAllowed(PlaybackState state, String genre) {
        if (isDjGenrePoolUnrestricted(state)) {
            return true;
        }
        java.util.Set<String> songKeys = canonicalKeys(genre);
        java.util.List<String> pool = state.getDjGenrePool();
        if (pool == null) {
            return false;
        }
        // Canonical match first: Rock/rock/Rock-Pop and Hip-Hop/Hip Hop all
        // resolve to the same key, so a pool chip matches every variant.
        for (String allowed : pool) {
            if (allowed == null) {
                continue;
            }
            for (String key : canonicalKeys(allowed)) {
                if (songKeys.contains(key)) {
                    return true;
                }
            }
        }
        // Compatibility fallback for stale/unknown pool labels: token overlap.
        java.util.Set<String> songTokens = genreTokens(genre);
        for (String allowed : pool) {
            if (allowed == null) {
                continue;
            }
            for (String token : genreTokens(allowed)) {
                if (songTokens.contains(token)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Primary canonical genre key, e.g. "Rock/Pop" -> "rock". */
    private static String genreKey(String genre) {
        return GenreNormalizer.resolveAll(genre).get(0).key();
    }

    /** Every canonical genre key in a raw label. */
    private static java.util.Set<String> canonicalKeys(String genre) {
        java.util.Set<String> keys = new java.util.HashSet<>();
        for (GenreNormalizer.Genre g : GenreNormalizer.resolveAll(genre)) {
            keys.add(g.key());
        }
        return keys;
    }

    /**
     * Splits a genre label into matchable tokens ("Hip-Hop/Rap" becomes
     * {hip, hop, rap}) after the standard normalization. Empty for
     * null/blank input.
     */
    private static java.util.Set<String> genreTokens(String genre) {
        java.util.Set<String> tokens = new java.util.HashSet<>();
        String normalized = SongService.normalizeGenre(genre);
        if (!normalized.isEmpty()) {
            for (String part : normalized.split(" ")) {
                if (!part.isEmpty()) {
                    tokens.add(part);
                }
            }
        }
        return tokens;
    }

    private boolean isYearAllowed(PlaybackState state, String releaseDate) {
        Integer minYear = state.getDjYearMin();
        Integer maxYear = state.getDjYearMax();
        if ((minYear == null || minYear <= 0) && (maxYear == null || maxYear <= 0)) {
            return true; // No year range configured - all songs allowed
        }
        if (releaseDate == null || releaseDate.isBlank()) {
            return false; // Strict: songs without year data are excluded when a range is set
        }
        int year;
        try {
            year = Integer.parseInt(releaseDate.substring(0, 4));
        } catch (NumberFormatException | StringIndexOutOfBoundsException e) {
            return false; // Unparseable year - excluded when a range is set
        }
        if (minYear != null && minYear > 0 && year < minYear) {
            return false;
        }
        if (maxYear != null && maxYear > 0 && year > maxYear) {
            return false;
        }
        return true;
    }

    private int countConsecutiveSameGenre(PlaybackState state, List<Long> cue, Song currentSong) {
        if (currentSong == null || cue == null || cue.isEmpty()) {
            return 0;
        }
        String currentGenre = genreKey(currentSong.getGenre());
        int currentIndex = state.getCueIndex();
        if (currentIndex < 0 || currentIndex >= cue.size()) {
            return 1;
        }
        int streak = 1;
        int i = currentIndex - 1;
        while (i >= 0) {
            int from = Math.max(0, i - 49);
            java.util.Map<Long, Song> batchById = songService.findByIds(cue.subList(from, i + 1)).stream()
                    .collect(java.util.stream.Collectors.toMap(s -> s.id, s -> s, (a, b) -> a));
            for (int j = i; j >= from; j--) {
                Song song = batchById.get(cue.get(j));
                if (song != null && genreKey(song.getGenre()).equals(currentGenre)) {
                    streak++;
                } else {
                    return streak;
                }
            }
            i = from - 1;
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
        if (recent.isEmpty()) {
            return 0;
        }
        java.util.Map<Long, Song> recentById = songService.findByIds(recent).stream()
                .collect(java.util.stream.Collectors.toMap(s -> s.id, s -> s, (a, b) -> a));
        int count = 0;
        for (Long id : recent) {
            if (id == null) {
                break;
            }
            Song song = recentById.get(id);
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

    private List<Long> buildSongPool(PlaybackState state) {
        // The genre-pool / year / BPM filters are Smart Shuffle selection settings.
        boolean applyFilters = state.getShuffleMode() == PlaybackState.ShuffleMode.SMART_SHUFFLE;

        // Keep the source the user started from (playlist or library). Using the
        // whole library here made Smart Shuffle jump context at the end of the
        // 500-song window.
        List<Long> sourceIds = state.getOriginalCue();
        List<Song> sourceSongs = (sourceIds != null && !sourceIds.isEmpty())
                ? songService.findByIds(sourceIds)
                : songService.findAll();

        final int minBpm = state.getDjBpmMin() != null ? state.getDjBpmMin() : 0;
        final int maxBpm = state.getDjBpmMax() != null ? state.getDjBpmMax() : 0;

        return sourceSongs.stream()
                .filter(s -> !applyFilters
                        || ((isDjGenrePoolUnrestricted(state) || isGenreAllowed(state, s.getGenre()))
                            && isYearAllowed(state, s.getReleaseDate())
                            && (minBpm <= 0 || s.getBpm() >= minBpm)
                            && (maxBpm <= 0 || s.getBpm() <= maxBpm)))
                .map(s -> s.id)
                .distinct()
                .collect(java.util.stream.Collectors.toList());
    }

    private void refillCueFromPool(PlaybackState state, Long profileId, boolean manualAdvance) {
        // Refill is gated on RepeatMode.ALL OR a manual forward advance. With Repeat
        // OFF + natural end the queue is consumed exactly once and must NOT be
        // silently refilled from the library — the caller (advance) returns null
        // for OFF and stops playback. Only Repeat ALL (continuous playback) or a
        // manual next/play (manualAdvance) refills from the pool.
        if (state.getRepeatMode() != PlaybackState.RepeatMode.ALL && !manualAdvance) {
            return;
        }
        List<Long> poolIds = buildSongPool(state);
        boolean smartShuffle = state.getShuffleMode() == PlaybackState.ShuffleMode.SMART_SHUFFLE;
        if (poolIds.isEmpty() && smartShuffle && hasDjRestriction(state)) {
            poolIds = songService.findAll().stream()
                    .map(s -> s.id)
                    .collect(java.util.stream.Collectors.toList());
        }
        if (poolIds.isEmpty()) {
            return;
        }
        if (poolIds.size() == 1 && smartShuffle && hasDjRestriction(state)) {
            List<Long> widened = songService.findAll().stream()
                    .map(s -> s.id)
                    .collect(java.util.stream.Collectors.toList());
            if (widened.size() > 1) {
                poolIds = widened;
            }
        }
        state.setCue(new ArrayList<>(poolIds));
        state.setOriginalCue(new ArrayList<>());
        state.setCueIndex(-1);

        if (state.getShuffleMode() == PlaybackState.ShuffleMode.SHUFFLE) {
            initShuffle(state, profileId);
        } else if (state.getShuffleMode() == PlaybackState.ShuffleMode.SMART_SHUFFLE) {
            initSmartShuffle(state, profileId);
        }
        enforceQueueWindow(state);
    }
}
