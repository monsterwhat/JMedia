package Services;

import Models.Settings.Profile;
import Models.Video.ProfileSessionState;
import Models.Video.Video;
import Models.Video.VideoState;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.time.LocalDateTime;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ApplicationScoped
public class VideoStateService {

    private static final Logger LOG = LoggerFactory.getLogger(VideoStateService.class);

    @Inject
    SettingsService settingsService;

    @Inject
    ProfileSessionStateService profileSessionStateService;

    @Transactional
    public VideoState getOrCreate(Video video) {
        Profile activeProfile = settingsService.getActiveProfile();
        if (activeProfile == null || video == null) {
            return null;
        }

        VideoState state = VideoState.find("profileId = ?1 AND video = ?2", activeProfile.id, video).firstResult();
        if (state == null) {
            state = new VideoState();
            state.profileId = activeProfile.id;
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

        List<VideoState> existing = VideoState.list("profileId = ?1 AND video IN ?2", activeProfile.id, videos);
        Map<Long, VideoState> result = new HashMap<>();
        for (VideoState vs : existing) {
            result.put(vs.video.id, vs);
        }

        for (Video video : videos) {
            if (!result.containsKey(video.id)) {
                VideoState newState = new VideoState();
                newState.profileId = activeProfile.id;
                newState.video = video;
                newState.persist();
                result.put(video.id, newState);
            }
        }

        return result;
    }

    @Transactional
    public void updateProgress(Video video, double currentTimeSeconds) {
        updateProgress(video, currentTimeSeconds, null);
    }

    @Transactional
    public void updateProgress(Video video, double currentTimeSeconds, Long profileId) {
        Profile activeProfile = profileId != null ? settingsService.getActiveProfile(profileId) : settingsService.getActiveProfile();
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

        return VideoState.list("profileId = ?1 AND watchProgress > 0 AND watchProgress < 0.95 ORDER BY lastUpdated DESC", activeProfile.id);
    }

    /**
     * Removes a video (or an entire series when the video is an episode) from the
     * Continue Watching list by resetting its progress. Reset states are excluded
     * from getInProgressVideos() since that query requires watchProgress > 0.
     */
    @Transactional
    public void removeFromContinueWatching(Video video) {
        Profile activeProfile = settingsService.getActiveProfile();
        if (activeProfile == null || video == null) {
            return;
        }

        // For an episode, target every episode of the series so the whole show
        // leaves Continue Watching instead of the previous episode reappearing.
        List<Video> targets = new ArrayList<>();
        if ("episode".equalsIgnoreCase(video.type)) {
            if (video.series != null) {
                targets = Video.list("series = ?1 AND isActive = ?2", video.series, true);
            }
            if (targets.isEmpty() && video.seriesTitle != null && !video.seriesTitle.isBlank()) {
                targets = Video.list("lower(seriesTitle) = ?1 AND isActive = ?2",
                        video.seriesTitle.toLowerCase(Locale.ROOT).trim(), true);
            }
        }
        if (targets.isEmpty()) {
            targets = List.of(video);
        }

        for (Video target : targets) {
            VideoState state = VideoState.find("profileId = ?1 AND video = ?2", activeProfile.id, target).firstResult();
            if (state == null) continue;
            boolean hasProgress = (state.watchProgress != null && state.watchProgress > 0)
                    || state.currentTime > 0
                    || Boolean.TRUE.equals(state.watched);
            if (hasProgress) {
                state.watchProgress = 0.0;
                state.currentTime = 0.0;
                state.watched = false;
                state.persist();
            }
        }
    }

    @Transactional
    public void deleteForProfile(Profile profile) {
        if (profile != null) {
            VideoState.delete("profileId = ?1", profile.id);
        }
    }

    @Transactional
    public Boolean toggleWatched(Long videoId) {
        Video video = Video.findById(videoId);
        if (video == null) return null;
        VideoState state = getOrCreate(video);
        if (state == null) return null;
        state.watched = !Boolean.TRUE.equals(state.watched);
        if (Boolean.TRUE.equals(state.watched)) {
            state.watchProgress = 1.0;
        } else {
            state.watchProgress = 0.0;
            state.currentTime = 0.0;
        }
        state.persist();
        return Boolean.TRUE.equals(state.watched);
    }

    @Transactional
    public Boolean removeFromContinueWatching(Long videoId) {
        Video video = Video.findById(videoId);
        if (video == null) return null;
        removeFromContinueWatching(video);
        return true;
    }

    @Transactional
    public boolean reportProgress(Long videoId, double progressSeconds) {
        Video video = Video.findById(videoId);
        if (video != null) {
            updateProgress(video, progressSeconds);
        }

        // Sync ProfileSessionState for real-time UI synchronization
        try {
            ProfileSessionState state = profileSessionStateService.getOrCreate();
            if (state != null && videoId.equals(state.currentVideoId)) {
                state.currentTime = progressSeconds;
                profileSessionStateService.save(state);
            }
        } catch (Exception e) {
            LOG.warn("Could not sync ProfileSessionState for video {}: {}", videoId, e.getMessage());
        }
        return true;
    }
}
