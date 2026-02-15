package Services;

import Models.UserSubtitlePreferences;
import Models.Video;
import Models.User;
import Models.SubtitleTrack;
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
    public void updateWatchProgress(Long videoId, Long userId, double progress) {
        Video video = Video.findById(videoId);
        if (video != null) {
            video.watchProgress = progress;
            video.lastWatched = LocalDateTime.now();
            video.watched = progress >= 1.0;
            video.dateModified = LocalDateTime.now();
            video.persist();
        }
    }
    
    @Transactional
    public void markAsFavorite(Long videoId, Long userId) {
        Video video = Video.findById(videoId);
        if (video != null) {
            video.favorite = true;
            video.favoritedAt = LocalDateTime.now();
            video.dateModified = LocalDateTime.now();
            video.persist();
        }
    }
    
    @Transactional
    public void removeFavorite(Long videoId, Long userId) {
        Video video = Video.findById(videoId);
        if (video != null) {
            video.favorite = false;
            video.favoritedAt = null;
            video.dateModified = LocalDateTime.now();
            video.persist();
        }
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