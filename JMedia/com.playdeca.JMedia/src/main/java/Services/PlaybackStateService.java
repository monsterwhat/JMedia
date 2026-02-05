package Services;

import Models.PlaybackState;
import Models.Profile;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.NoResultException;
import jakarta.persistence.PersistenceContext;
import jakarta.transaction.Transactional;
import java.util.ArrayList;

@ApplicationScoped
public class PlaybackStateService {

    @PersistenceContext
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
        PlaybackState state = PlaybackState.find("profile", profile).firstResult();

        if (state == null) {
            // If not found, create a new one
            state = createDefaultState(profile);
            try {
                // Persist the new state. This might throw an exception if another thread
                // has already created it for the same profile (race condition).
                state.persistAndFlush(); // Force flush to catch unique constraint violation early
            } catch (jakarta.persistence.PersistenceException e) {
                // If it failed due to a unique constraint violation, it means another thread
                // created it. Fetch the existing one.
                if (e.getCause() instanceof org.hibernate.exception.ConstraintViolationException) {
                    state = PlaybackState.find("profile", profile).firstResult();
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

private PlaybackState createDefaultState(Profile profile) {
        PlaybackState state = new PlaybackState();
        state.setProfile(profile);
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

        em.merge(existingState);
        em.flush();
    }

    @Transactional
    public synchronized void resetState(Long profileId) {
        PlaybackState state = getOrCreateState(profileId);
        Profile profile = state.getProfile(); // preserve the profile
        PlaybackState defaultState = createDefaultState(profile);
        
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

        em.merge(state);
        em.flush();
    }
}
