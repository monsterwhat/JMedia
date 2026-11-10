package Services;

import Models.Music.PlaybackState;
import Models.Settings.Profile;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.NoResultException;
import jakarta.persistence.PersistenceContext;
import jakarta.transaction.Transactional;
import java.util.ArrayList;

@ApplicationScoped
public class PlaybackStateService {

    @PersistenceContext(unitName = "music")
    private EntityManager em;

    @Inject
    SettingsService settingsService;

    @Inject
    ProfileService profileService;

    @Transactional
    public synchronized PlaybackState getOrCreateState(Long profileId) {
        Profile profile = profileService.findById(profileId);
        if (profile == null) {
            throw new IllegalArgumentException("Profile with ID " + profileId + " not found.");
        }

        // Try to find the state first
        PlaybackState state = PlaybackState.find("profileId", profile.id).firstResult();

        if (state == null) {
            // If not found, create a new one
            state = createDefaultState(profile.id);
            try {
                // Persist the new state. This might throw an exception if another thread
                // has already created it for the same profile (race condition).
                state.persistAndFlush(); // Force flush to catch unique constraint violation early
            } catch (jakarta.persistence.PersistenceException e) {
                // If it failed due to a unique constraint violation, it means another thread
                // created it. Fetch the existing one.
                if (e.getCause() instanceof org.hibernate.exception.ConstraintViolationException) {
                    state = PlaybackState.find("profileId", profile.id).firstResult();
                    if (state == null) {
                        // This should not happen, but as a fallback, throw an error.
                        throw new IllegalStateException("Failed to create or retrieve PlaybackState for profile: " + profile.name, e);
                    }
                } else {
                    throw e; // Re-throw other persistence exceptions
                }
            }
        }
        return state;
    }

private PlaybackState createDefaultState(Long profileId) {
        PlaybackState state = new PlaybackState();
        state.setProfileId(profileId);
        state.setVolume(0.8f);
        state.setCue(new ArrayList<>());
        state.setLastSongs(new ArrayList<>());
        state.setOriginalCue(new ArrayList<>());
        state.setCueIndex(-1);
        return state;
    }

    @Transactional
    public synchronized void saveState(Long profileId, PlaybackState newState) {
        if (newState == null) {
            return;
        }
        
        PlaybackState existingState = getOrCreateState(profileId);
        
        // Update existing state with new values from newState
        existingState.setPlaying(newState.isPlaying());
        existingState.setCurrentSongId(newState.getCurrentSongId());
        existingState.setCurrentPlaylistId(newState.getCurrentPlaylistId());
        existingState.setSongName(newState.getSongName());
        existingState.setArtistName(newState.getArtistName());
        existingState.setCurrentTime(newState.getCurrentTime());
        existingState.setDuration(newState.getDuration());
        existingState.setVolume(newState.getVolume());
        existingState.setShuffleMode(newState.getShuffleMode());
        existingState.setLastUpdateTime(newState.getLastUpdateTime());
// Saving progress every second must not rewrite the 11k-element collections:
        // only replace a collection when it actually changed, otherwise Hibernate
        // deletes and reinserts ~22k rows per second (caused DB bloat/corruption).
        if (!java.util.Objects.equals(existingState.getCue(), newState.getCue())) {
            existingState.setCue(newState.getCue() == null ? new ArrayList<>() : new ArrayList<>(newState.getCue()));
        }
        if (!java.util.Objects.equals(existingState.getLastSongs(), newState.getLastSongs())) {
            existingState.setLastSongs(newState.getLastSongs() == null ? new ArrayList<>() : new ArrayList<>(newState.getLastSongs()));
        }
        if (!java.util.Objects.equals(existingState.getOriginalCue(), newState.getOriginalCue())) {
            existingState.setOriginalCue(newState.getOriginalCue() == null ? new ArrayList<>() : new ArrayList<>(newState.getOriginalCue()));
        }
        existingState.setCueIndex(newState.getCueIndex());
existingState.setRepeatMode(newState.getRepeatMode());
        existingState.setDjModeActive(newState.getDjModeActive() != null ? newState.getDjModeActive() : false);
        // null-safe: only replace when newState provides a pool (avoid wiping on null)
        if (newState.getDjGenrePool() != null && !java.util.Objects.equals(existingState.getDjGenrePool(), newState.getDjGenrePool())) {
            existingState.setDjGenrePool(new ArrayList<>(newState.getDjGenrePool()));
        }
        existingState.setDjSongsPerGenre(newState.getDjSongsPerGenre() != null ? newState.getDjSongsPerGenre() : 0);
        existingState.setDjCrossfadeOverride(newState.getDjCrossfadeOverride() != null ? newState.getDjCrossfadeOverride() : -1);
        existingState.setDjStrictness(newState.getDjStrictness() != null ? newState.getDjStrictness() : "MEDIUM");
        existingState.setDjBpmMin(newState.getDjBpmMin() != null ? newState.getDjBpmMin() : 0);
        existingState.setDjBpmMax(newState.getDjBpmMax() != null ? newState.getDjBpmMax() : 0);
        existingState.setDjMaxConsecutiveByArtist(newState.getDjMaxConsecutiveByArtist() != null ? newState.getDjMaxConsecutiveByArtist() : 0);
        existingState.setCrossfadeDuration(newState.getCrossfadeDuration() != null ? newState.getCrossfadeDuration() : 0);
        existingState.setOriginalCrossfadeDuration(newState.getOriginalCrossfadeDuration() != null ? newState.getOriginalCrossfadeDuration() : 0);
        existingState.setDjSkipsBeforeGenreChange(newState.getDjSkipsBeforeGenreChange() != null ? newState.getDjSkipsBeforeGenreChange() : 1);
        existingState.setDjYearMin(newState.getDjYearMin() != null ? newState.getDjYearMin() : 0);
        existingState.setDjYearMax(newState.getDjYearMax() != null ? newState.getDjYearMax() : 0);
        // Persist DJ transition planning so a restart during DJ Mode restores the
        // plan (exit times stay valid relative to persisted currentTime) instead
        // of silently falling back to regular crossfade until the user acts.
        existingState.setDjNextSongId(newState.getDjNextSongId());
        existingState.setDjEntryTime(newState.getDjEntryTime());
        existingState.setDjExitTime(newState.getDjExitTime());
        existingState.setDjTransitionPlanned(newState.getDjTransitionPlanned());
        existingState.setDjTransitionConfidence(newState.getDjTransitionConfidence());
        existingState.setDjTransitionReason(newState.getDjTransitionReason());
        existingState.setServerTime(newState.getServerTime());

        em.merge(existingState);
        em.flush();
    }

    @Transactional
    public synchronized void resetState(Long profileId) {
        PlaybackState state = getOrCreateState(profileId);
        // preserve the profileId (the state itself keeps it)
        PlaybackState defaultState = createDefaultState(profileId);
        
        // copy default values to the managed entity
        state.setPlaying(defaultState.isPlaying());
        state.setCurrentSongId(defaultState.getCurrentSongId());
        state.setCurrentPlaylistId(defaultState.getCurrentPlaylistId());
        state.setSongName(defaultState.getSongName());
        state.setArtistName(defaultState.getArtistName());
        state.setCurrentTime(defaultState.getCurrentTime());
        state.setDuration(defaultState.getDuration());
        state.setVolume(defaultState.getVolume());
        state.setShuffleMode(defaultState.getShuffleMode());
        state.setLastUpdateTime(defaultState.getLastUpdateTime());
// Create new instances of the collections to avoid shared references
        state.setCue(new ArrayList<>(defaultState.getCue()));
        state.setLastSongs(new ArrayList<>(defaultState.getLastSongs()));
        state.setOriginalCue(new ArrayList<>(defaultState.getOriginalCue()));
        state.setCueIndex(defaultState.getCueIndex());
state.setRepeatMode(defaultState.getRepeatMode());
        state.setDjModeActive(defaultState.getDjModeActive() != null ? defaultState.getDjModeActive() : false);
        state.setDjGenrePool(defaultState.getDjGenrePool() != null ? new ArrayList<>(defaultState.getDjGenrePool()) : new ArrayList<>());
        state.setDjSongsPerGenre(defaultState.getDjSongsPerGenre() != null ? defaultState.getDjSongsPerGenre() : 0);
        state.setDjCrossfadeOverride(defaultState.getDjCrossfadeOverride() != null ? defaultState.getDjCrossfadeOverride() : -1);
        state.setDjStrictness(defaultState.getDjStrictness() != null ? defaultState.getDjStrictness() : "MEDIUM");
        state.setDjBpmMin(defaultState.getDjBpmMin() != null ? defaultState.getDjBpmMin() : 0);
        state.setDjBpmMax(defaultState.getDjBpmMax() != null ? defaultState.getDjBpmMax() : 0);
        state.setDjMaxConsecutiveByArtist(defaultState.getDjMaxConsecutiveByArtist() != null ? defaultState.getDjMaxConsecutiveByArtist() : 0);
        state.setCrossfadeDuration(defaultState.getCrossfadeDuration() != null ? defaultState.getCrossfadeDuration() : 0);
        state.setOriginalCrossfadeDuration(defaultState.getOriginalCrossfadeDuration() != null ? defaultState.getOriginalCrossfadeDuration() : 0);
        state.setDjSkipsBeforeGenreChange(defaultState.getDjSkipsBeforeGenreChange() != null ? defaultState.getDjSkipsBeforeGenreChange() : 1);
        state.setDjYearMin(defaultState.getDjYearMin() != null ? defaultState.getDjYearMin() : 0);
        state.setDjYearMax(defaultState.getDjYearMax() != null ? defaultState.getDjYearMax() : 0);
        state.setServerTime(defaultState.getServerTime());

        em.merge(state);
        em.flush();
    }
}
