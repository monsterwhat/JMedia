package Services;

import Models.Video.UserSubtitlePreferences;
import Models.Video.UserPerVideoSubtitlePreference;
import Models.Video.Video;
import Models.Settings.User;
import Models.Video.SubtitleTrack;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.time.LocalDateTime;
import java.util.List;

@ApplicationScoped
public class UserInteractionService {
    
    // User preference methods
    @Transactional
    public UserSubtitlePreferences getUserSubtitlePreferences(Long userId) {
        return UserSubtitlePreferences.find("userId", userId).firstResult();
    }
    
    @Transactional
    public void updateUserSubtitlePreferences(UserSubtitlePreferences preferences) {
        if (preferences.id != null) {
            UserSubtitlePreferences existing = UserSubtitlePreferences.findById(preferences.id);
            if (existing != null) {
                existing.userId = preferences.userId;
                existing.preferredLanguage = preferences.preferredLanguage;
                existing.enableAutoSelection = preferences.enableAutoSelection;
                existing.preferForcedSubtitles = preferences.preferForcedSubtitles;
                existing.preferSDHSubtitles = preferences.preferSDHSubtitles;
                existing.subtitleStyle = preferences.subtitleStyle;
                existing.subtitleAppearance = preferences.subtitleAppearance;
                existing.persist();
            }
        } else {
            preferences.persist();
        }
    }
    
    // Video interaction methods
    @Transactional
    public SubtitleTrack getPerVideoPreferenceTrack(Long userId, Long videoId) {
        UserPerVideoSubtitlePreference pref = UserPerVideoSubtitlePreference.findByUserAndVideo(userId, videoId);
        if (pref == null || pref.trackId == null) return null;
        return SubtitleTrack.findById(pref.trackId);
    }

    @Transactional
    public void setPerVideoPreference(Long userId, Long videoId, Long trackId) {
        if (videoId == null) return;
        UserPerVideoSubtitlePreference pref = UserPerVideoSubtitlePreference.findByUserAndVideo(userId, videoId);
        if (pref == null) {
            pref = new UserPerVideoSubtitlePreference();
            pref.userId = userId;
            pref.videoId = videoId;
        }
        pref.trackId = trackId;
        pref.persist();
    }

    /**
     * Per-profile watchlist add. The {@code profileId} parameter is load-bearing:
     * the flag lives on the caller's VideoState row, not on the shared Video
     * entity. The legacy global Video.favorite column is maintained as an
     * OR-aggregate ("favorited by at least one profile") so cross-server sync,
     * which exchanges that column, keeps working during the transition.
     */
    @Transactional
    public void markAsFavorite(Long videoId, Long profileId) {
        Video video = Video.findById(videoId);
        if (video == null || profileId == null) return;
        Models.Video.VideoState state = Models.Video.VideoState.find(
                "profileId = ?1 AND video = ?2", profileId, video).firstResult();
        if (state == null) {
            state = new Models.Video.VideoState();
            state.profileId = profileId;
            state.video = video;
        }
        state.favorite = true;
        if (state.favoritedAt == null) state.favoritedAt = LocalDateTime.now();
        state.persist();
        refreshAggregateFavorite(video);
    }

    /** Per-profile watchlist remove. See {@link #markAsFavorite} for the model. */
    @Transactional
    public void removeFavorite(Long videoId, Long profileId) {
        Video video = Video.findById(videoId);
        if (video == null || profileId == null) return;
        Models.Video.VideoState state = Models.Video.VideoState.find(
                "profileId = ?1 AND video = ?2", profileId, video).firstResult();
        if (state != null) {
            state.favorite = false;
            state.favoritedAt = null;
            state.persist();
        }
        refreshAggregateFavorite(video);
    }

    /**
     * Recomputes the legacy global Video.favorite flag as "favorited by at
     * least one profile", preserving the newest favoritedAt. Keeps consumers
     * of the shared column (sync exchange, older clients) working while
     * per-profile VideoState rows are the source of truth.
     */
    private void refreshAggregateFavorite(Video video) {
        boolean anyFavorite = Models.Video.VideoState.count(
                "video = ?1 AND favorite = true", video) > 0;
        video.favorite = anyFavorite;
        if (anyFavorite) {
            Models.Video.VideoState newest = Models.Video.VideoState.find(
                    "video = ?1 AND favorite = true ORDER BY favoritedAt DESC", video).firstResult();
            if (newest != null && newest.favoritedAt != null) {
                video.favoritedAt = newest.favoritedAt;
            } else if (video.favoritedAt == null) {
                video.favoritedAt = LocalDateTime.now();
            }
        } else {
            video.favoritedAt = null;
        }
        video.dateModified = LocalDateTime.now();
        video.persist();
    }
    
    @Transactional
    public void rateVideo(Long videoId, Long userId, int rating) {
        Video video = Video.findById(videoId);
        if (video != null) {
            video.userRatingStars = rating;
            video.userRatingDate = LocalDateTime.now();
            video.dateModified = LocalDateTime.now();
            video.persist();
        }
    }
    
    @Transactional
    public void incrementPlayCount(Long videoId, Long userId) {
        Video video = Video.findById(videoId);
        if (video != null) {
            video.playCount = (video.playCount != null ? 0 : video.playCount) + 1;
            video.dateModified = LocalDateTime.now();
            video.persist();
        }
    }
    
    // Subtitle management methods
    @Transactional
    public List<SubtitleTrack> getSubtitleTracks(Long videoId) {
        return SubtitleTrack.list("video.id = ?1 and isActive = true", videoId);
    }
    
    @Transactional
    public SubtitleTrack getDefaultSubtitleTrack(Long videoId) {
        return SubtitleTrack.find("video.id = ?1 and isDefault = true and isActive = true", videoId).firstResult();
    }
    
    @Transactional
    public List<SubtitleTrack> getSubtitleTracksByLanguage(Long videoId, String languageCode) {
        return SubtitleTrack.list("video.id = ?1 and languageCode = ?2 and isActive = true", videoId, languageCode);
    }
}
