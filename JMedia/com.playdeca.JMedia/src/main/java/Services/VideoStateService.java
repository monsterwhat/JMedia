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
    ProfileService profileService;

    @Inject
    ProfileSessionStateService profileSessionStateService;

    @Transactional
    public VideoState getOrCreate(Video video) {
        return getOrCreate(video, null);
    }

    @Transactional
    public VideoState getOrCreate(Video video, Long profileId) {
        Profile activeProfile = profileId != null ? profileService.findById(profileId) : settingsService.getActiveProfile();
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
        Profile activeProfile = profileId != null ? profileService.findById(profileId) : settingsService.getActiveProfile();
        if (activeProfile == null || video == null) {
            return;
        }

        VideoState state = getOrCreate(video, profileId);
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

    /**
     * Per-profile watchlist flag. Unlike progress, toggling favorite has no
     * side effects on watch state — a favorited-but-unwatched row keeps
     * watchProgress 0 and stays out of Continue Watching (which requires
     * watchProgress &gt; 0).
     *
     * @return the new favorite state, or null when the video is missing or no
     *         profile is active
     */
    @Transactional
    public Boolean toggleFavorite(Long videoId) {
        Video video = Video.findById(videoId);
        if (video == null) return null;
        VideoState state = getOrCreate(video);
        if (state == null) return null;
        state.favorite = !Boolean.TRUE.equals(state.favorite);
        state.favoritedAt = Boolean.TRUE.equals(state.favorite) ? LocalDateTime.now() : null;
        state.persist();
        return Boolean.TRUE.equals(state.favorite);
    }

    /**
     * Read-only favorite check for the active profile. Does NOT create a
     * VideoState row (unlike getOrCreate) so mere reads never materialize
     * state.
     */
    @Transactional
    public boolean isFavorite(Video video) {
        if (video == null) return false;
        Profile activeProfile = settingsService.getActiveProfile();
        if (activeProfile == null) return false;
        VideoState state = VideoState.find("profileId = ?1 AND video = ?2",
                activeProfile.id, video).firstResult();
        return state != null && Boolean.TRUE.equals(state.favorite);
    }

    /**
     * One-time backfill from the legacy global Video.favorite column into
     * per-profile VideoState rows. Idempotent: only fills rows that are not
     * already favorited, and preserves the original favoritedAt timestamp for
     * watchlist ordering. Every profile receives the current globals, which
     * preserves exactly what everyone sees today.
     *
     * @return number of VideoState rows newly marked favorite
     */
    @Transactional
    public int backfillFavoritesFromLegacyColumn() {
        List<Video> favorited = Video.list("favorite = true");
        if (favorited.isEmpty()) return 0;
        List<Profile> profiles = Profile.listAll();
        if (profiles.isEmpty()) return 0;
        int filled = 0;
        for (Video video : favorited) {
            for (Profile profile : profiles) {
                VideoState state = VideoState.find("profileId = ?1 AND video = ?2",
                        profile.id, video).firstResult();
                if (state != null && Boolean.TRUE.equals(state.favorite)) continue;
                if (state == null) {
                    state = new VideoState();
                    state.profileId = profile.id;
                    state.video = video;
                }
                state.favorite = true;
                if (state.favoritedAt == null) state.favoritedAt = video.favoritedAt;
                if (state.favoritedAt == null) state.favoritedAt = LocalDateTime.now();
                state.persist();
                filled++;
            }
        }
        return filled;
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
