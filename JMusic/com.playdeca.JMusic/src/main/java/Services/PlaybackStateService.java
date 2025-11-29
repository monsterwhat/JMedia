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

    @Transactional
    public synchronized PlaybackState getOrCreateState() {
        Profile activeProfile = settingsService.getActiveProfile();
        if (activeProfile == null) {
            // This case should ideally not happen if ProfileService is correctly initializing the main profile
            // But if it does, return a transient default state, or throw an exception.
            // For now, let's return a default, unpersisted state.
            return createDefaultState(null);
        }

        // Try to find the state first
        PlaybackState state = PlaybackState.find("profile", activeProfile).firstResult();

        if (state == null) {
            // If not found, create a new one
            state = createDefaultState(activeProfile);
            try {
                // Persist the new state. This might throw an exception if another thread
                // has already created it for the same profile (race condition).
                state.persistAndFlush(); // Force flush to catch unique constraint violation early
            } catch (jakarta.persistence.PersistenceException e) {
                // If it failed due to a unique constraint violation, it means another thread
                // created it. Fetch the existing one.
                if (e.getCause() instanceof org.hibernate.exception.ConstraintViolationException) {
                    state = PlaybackState.find("profile", activeProfile).firstResult();
                    if (state == null) {
                        // This should not happen, but as a fallback, throw an error.
                        throw new IllegalStateException("Failed to create or retrieve PlaybackState for profile: " + activeProfile.name, e);
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
        state.setCueIndex(-1);
        return state;
    }

    @Transactional
    public synchronized void saveState(PlaybackState newState) {
        if (newState == null) {
            return;
        }
        
        Profile activeProfile = settingsService.getActiveProfile();
        if (activeProfile == null) {
            return; // Cannot save state without an active profile
        }
        
        PlaybackState existingState = getOrCreateState();
        
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
        existingState.setCueIndex(newState.getCueIndex());
        existingState.setRepeatMode(newState.getRepeatMode());

        em.merge(existingState);
        em.flush();
    }

    @Transactional
    public synchronized void resetState() {
        PlaybackState state = getOrCreateState();
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
        state.setCueIndex(defaultState.getCueIndex());
        state.setRepeatMode(defaultState.getRepeatMode());

        em.merge(state);
        em.flush();
    }
}
