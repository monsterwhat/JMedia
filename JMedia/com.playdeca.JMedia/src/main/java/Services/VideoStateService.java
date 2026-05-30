package Services;

import Models.Profile;
import Models.Video;
import Models.VideoState;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.time.LocalDateTime;
import java.util.*;

@ApplicationScoped
public class VideoStateService {

    @Inject
    SettingsService settingsService;

    @Transactional
    public VideoState getOrCreate(Video video) {
        Profile activeProfile = settingsService.getActiveProfile();
        if (activeProfile == null || video == null) {
            return null;
        }

        VideoState state = VideoState.find("profile = ?1 AND video = ?2", activeProfile, video).firstResult();
        if (state == null) {
            state = new VideoState();
            state.profile = activeProfile;
            state.video = video;
            state.persist();
        }
        return state;
    }

    @Transactional
    public Map<Long, VideoState> getOrCreateBatch(List<Video> videos) {
        Profile activeProfile = settingsService.getActiveProfile();
        if (activeProfile == null || videos == null || videos.isEmpty()) {
            return Collections.emptyMap();
        }

        List<VideoState> existing = VideoState.list("profile = ?1 AND video IN ?2", activeProfile, videos);
        Map<Long, VideoState> result = new HashMap<>();
        for (VideoState vs : existing) {
            result.put(vs.video.id, vs);
        }

        for (Video video : videos) {
            if (!result.containsKey(video.id)) {
                VideoState newState = new VideoState();
                newState.profile = activeProfile;
                newState.video = video;
                newState.persist();
                result.put(video.id, newState);
            }
        }

        return result;
    }

    @Transactional
    public void updateProgress(Video video, double currentTimeSeconds) {
        Profile activeProfile = settingsService.getActiveProfile();
        if (activeProfile == null || video == null) {
            return;
        }

        VideoState state = getOrCreate(video);
        state.currentTime = currentTimeSeconds;
        state.lastUpdated = LocalDateTime.now();

        // Calculate watch progress
        double durationSeconds = video.duration != null ? video.duration / 1000.0 : 0;
        if (durationSeconds > 0) {
            state.watchProgress = Math.min(1.0, currentTimeSeconds / durationSeconds);
            state.watched = state.watchProgress >= 0.95;
        }
        state.persist();
    }

    public List<VideoState> getInProgressVideos() {
        Profile activeProfile = settingsService.getActiveProfile();
        if (activeProfile == null) {
            return List.of();
        }

        return VideoState.list("profile = ?1 AND watchProgress > 0 AND watchProgress < 0.95 ORDER BY lastUpdated DESC", activeProfile);
    }

    @Transactional
    public void deleteForProfile(Profile profile) {
        if (profile != null) {
            VideoState.delete("profile = ?1", profile);
        }
    }
}
