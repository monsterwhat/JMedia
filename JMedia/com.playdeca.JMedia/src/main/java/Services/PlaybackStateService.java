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
        state.setSecondaryCue(new ArrayList<>());
        state.setSecondaryOriginalCue(new ArrayList<>());
        state.setCueIndex(-1);
        state.setSecondaryCueIndex(-1);
        state.setUsingSecondaryQueue(false);
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
// Create new instances of the collections to avoid shared references
        existingState.setCue(new ArrayList<>(newState.getCue()));
        existingState.setLastSongs(new ArrayList<>(newState.getLastSongs()));
        existingState.setOriginalCue(new ArrayList<>(newState.getOriginalCue()));
        existingState.setSecondaryCue(new ArrayList<>(newState.getSecondaryCue()));
        existingState.setSecondaryOriginalCue(new ArrayList<>(newState.getSecondaryOriginalCue()));
        existingState.setCueIndex(newState.getCueIndex());
        existingState.setSecondaryCueIndex(newState.getSecondaryCueIndex());
        existingState.setUsingSecondaryQueue(newState.isUsingSecondaryQueue());
existingState.setRepeatMode(newState.getRepeatMode());
        existingState.setDjModeActive(newState.getDjModeActive() != null ? newState.getDjModeActive() : false);
        existingState.setDjGenrePool(newState.getDjGenrePool() != null ? new ArrayList<>(newState.getDjGenrePool()) : new ArrayList<>());
        existingState.setDjSongsPerGenre(newState.getDjSongsPerGenre() != null ? newState.getDjSongsPerGenre() : 0);
        existingState.setDjCrossfadeOverride(newState.getDjCrossfadeOverride() != null ? newState.getDjCrossfadeOverride() : -1);
        existingState.setDjStrictness(newState.getDjStrictness() != null ? newState.getDjStrictness() : "MEDIUM");
        existingState.setDjBpmMin(newState.getDjBpmMin() != null ? newState.getDjBpmMin() : 0);
        existingState.setDjBpmMax(newState.getDjBpmMax() != null ? newState.getDjBpmMax() : 0);
        existingState.setDjMaxConsecutiveByArtist(newState.getDjMaxConsecutiveByArtist() != null ? newState.getDjMaxConsecutiveByArtist() : 0);

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
        state.setSecondaryCue(new ArrayList<>(defaultState.getSecondaryCue()));
        state.setSecondaryOriginalCue(new ArrayList<>(defaultState.getSecondaryOriginalCue()));
        state.setCueIndex(defaultState.getCueIndex());
        state.setSecondaryCueIndex(defaultState.getSecondaryCueIndex());
        state.setUsingSecondaryQueue(defaultState.isUsingSecondaryQueue());
state.setRepeatMode(defaultState.getRepeatMode());
        state.setDjModeActive(defaultState.getDjModeActive() != null ? defaultState.getDjModeActive() : false);
        state.setDjGenrePool(defaultState.getDjGenrePool() != null ? new ArrayList<>(defaultState.getDjGenrePool()) : new ArrayList<>());
        state.setDjSongsPerGenre(defaultState.getDjSongsPerGenre() != null ? defaultState.getDjSongsPerGenre() : 0);
        state.setDjCrossfadeOverride(defaultState.getDjCrossfadeOverride() != null ? defaultState.getDjCrossfadeOverride() : -1);
        state.setDjStrictness(defaultState.getDjStrictness() != null ? defaultState.getDjStrictness() : "MEDIUM");
        state.setDjBpmMin(defaultState.getDjBpmMin() != null ? defaultState.getDjBpmMin() : 0);
        state.setDjBpmMax(defaultState.getDjBpmMax() != null ? defaultState.getDjBpmMax() : 0);
        state.setDjMaxConsecutiveByArtist(defaultState.getDjMaxConsecutiveByArtist() != null ? defaultState.getDjMaxConsecutiveByArtist() : 0);

        em.merge(state);
        em.flush();
    }
}
