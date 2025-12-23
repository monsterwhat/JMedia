package Services;

import Models.Profile;
import Models.VideoState;
import Services.VideoService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.transaction.Transactional;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

@ApplicationScoped
public class VideoStateService {

    @PersistenceContext
    private EntityManager em;

    @Inject
    SettingsService settingsService;
    
    @Inject
    VideoService videoService;

    @Transactional
    public synchronized VideoState getOrCreateState() {
        Profile activeProfile = settingsService.getActiveProfile();
        if (activeProfile == null) {
            return createDefaultState(null);
        }

        VideoState state = VideoState.find("profile", activeProfile).firstResult();

        if (state == null) {
            state = createDefaultState(activeProfile);
            try {
                state.persistAndFlush();
            } catch (jakarta.persistence.PersistenceException e) {
                if (e.getCause() instanceof org.hibernate.exception.ConstraintViolationException) {
                    state = VideoState.find("profile", activeProfile).firstResult();
                    if (state == null) {
                        throw new IllegalStateException("Failed to create or retrieve VideoState for profile: " + activeProfile.name, e);
                    }
                } else {
                    throw e;
                }
            }
        }
        return state;
    }

    private VideoState createDefaultState(Profile profile) {
        VideoState state = new VideoState();
        state.setProfile(profile);
        state.setVolume(0.8f);
        state.setCue(new ArrayList<>());
        state.setLastVideos(new ArrayList<>());
        state.setCueIndex(-1);
        return state;
    }

    @Transactional
    public synchronized void saveState(VideoState newState) {
        if (newState == null) {
            return;
        }
        
        Profile activeProfile = settingsService.getActiveProfile();
        if (activeProfile == null) {
            return; 
        }
        
        VideoState existingState = getOrCreateState();
        
        existingState.setPlaying(newState.isPlaying());
        existingState.setCurrentVideoId(newState.getCurrentVideoId());
        existingState.setCurrentPlaylistId(newState.getCurrentPlaylistId());
        existingState.setVideoTitle(newState.getVideoTitle());
        existingState.setSeriesTitle(newState.getSeriesTitle());
        existingState.setEpisodeTitle(newState.getEpisodeTitle());
        existingState.setCurrentTime(newState.getCurrentTime());
        existingState.setDuration(newState.getDuration());
        existingState.setVolume(newState.getVolume());
        existingState.setLastUpdateTime(newState.getLastUpdateTime());
        existingState.setCue(new ArrayList<>(newState.getCue()));
        existingState.setLastVideos(new ArrayList<>(newState.getLastVideos()));
        existingState.setOriginalCue(new ArrayList<>(newState.getOriginalCue()));
        existingState.setCueIndex(newState.getCueIndex());
        existingState.setRepeatMode(newState.getRepeatMode());

        em.merge(existingState);
        em.flush();
    }

    @Transactional
    public synchronized void resetState() {
        VideoState state = getOrCreateState();
        Profile profile = state.getProfile(); 
        VideoState defaultState = createDefaultState(profile);
        
        state.setPlaying(defaultState.isPlaying());
        state.setCurrentVideoId(defaultState.getCurrentVideoId());
        state.setCurrentPlaylistId(defaultState.getCurrentPlaylistId());
        state.setVideoTitle(defaultState.getVideoTitle());
        state.setSeriesTitle(defaultState.getSeriesTitle());
        state.setEpisodeTitle(defaultState.getEpisodeTitle());
        state.setCurrentTime(defaultState.getCurrentTime());
        state.setDuration(defaultState.getDuration());
        state.setVolume(defaultState.getVolume());
        state.setLastUpdateTime(defaultState.getLastUpdateTime());
        state.setCue(new ArrayList<>(defaultState.getCue()));
        state.setLastVideos(new ArrayList<>(defaultState.getLastVideos()));
        state.setCueIndex(defaultState.getCueIndex());
        state.setRepeatMode(defaultState.getRepeatMode());

        em.merge(state);
        em.flush();
    }

    // ==================== CONTINUE WATCHING ALGORITHM METHODS ====================
    
    public List<VideoService.VideoDTO> getContinueWatching(int count) {
        VideoState currentState = getOrCreateState();
        if (currentState == null || currentState.getCurrentVideoId() == null) {
            return List.of();
        }
        
        // Get current video with progress
        VideoService.VideoDTO currentVideo = videoService.find(currentState.getCurrentVideoId());
        if (currentVideo != null && isInProgress(currentState)) {
            return List.of(currentVideo);
        }
        
        // Check recently accessed videos from lastVideos
        List<VideoService.VideoDTO> continueWatchingVideos = new ArrayList<>();
        
        if (currentState.getLastVideos() != null) {
            for (Long videoId : currentState.getLastVideos()) {
                if (videoId != null && videoId.equals(currentState.getCurrentVideoId())) {
                    continue; // Skip current video as it's already handled
                }
                
                VideoService.VideoDTO video = videoService.find(videoId);
                if (video != null && isInProgress(currentState)) {
                    continueWatchingVideos.add(video);
                    if (continueWatchingVideos.size() >= count) {
                        break;
                    }
                }
            }
        }
        
        return continueWatchingVideos.stream()
                .limit(count)
                .collect(Collectors.toList());
    }
    
    public List<VideoService.VideoDTO> getRecentlyAccessed(int count) {
        VideoState currentState = getOrCreateState();
        if (currentState == null) {
            return List.of();
        }
        
        List<VideoService.VideoDTO> recentlyAccessed = new ArrayList<>();
        
        // Add current video first
        if (currentState.getCurrentVideoId() != null) {
            VideoService.VideoDTO currentVideo = videoService.find(currentState.getCurrentVideoId());
            if (currentVideo != null) {
                recentlyAccessed.add(currentVideo);
            }
        }
        
        // Add from lastVideos
        if (currentState.getLastVideos() != null) {
            for (Long videoId : currentState.getLastVideos()) {
                if (videoId != null && videoId.equals(currentState.getCurrentVideoId())) {
                    continue; // Skip current video as it's already added
                }
                
                VideoService.VideoDTO video = videoService.find(videoId);
                if (video != null) {
                    recentlyAccessed.add(video);
                    if (recentlyAccessed.size() >= count) {
                        break;
                    }
                }
            }
        }
        
        return recentlyAccessed.stream()
                .limit(count)
                .collect(Collectors.toList());
    }
    
    private boolean isInProgress(VideoState state) {
        if (state == null || state.getDuration() <= 0) {
            return false;
        }
        
        double progress = (state.getCurrentTime() / state.getDuration()) * 100.0;
        
        // Consider video "in progress" if between 5% and 95% complete
        return progress >= 5.0 && progress <= 95.0;
    }
}
