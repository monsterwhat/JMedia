package Services;

import Models.SubtitleTrack;
import Models.UserSubtitlePreferences;
import Models.Video;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.*;

@ApplicationScoped
public class SubtitlePreferenceEngine {
    
    @Inject
    UserInteractionService userInteractionService;
    
    public SubtitleTrack selectBestSubtitleTrack(Long videoId, Long userId) {
        Video video = Video.findById(videoId);
        if (video == null || video.subtitleTracks == null || video.subtitleTracks.isEmpty()) {
            return null;
        }
        
        // Priority 1: User's explicit preference for this video
        SubtitleTrack perVideoPreference = getPerVideoPreference(videoId, userId);
        if (perVideoPreference != null) {
            return perVideoPreference;
        }
        
        // Priority 2: User's profile language preference
        UserSubtitlePreferences userPrefs = getUserPreferences(userId);
        if (userPrefs != null && userPrefs.enableAutoSelection) {
            SubtitleTrack languageMatch = findLanguageMatch(video.subtitleTracks, userPrefs.preferredLanguage, userPrefs);
            if (languageMatch != null) {
                return languageMatch;
            }
        }
        
        // Priority 3: Audio language mismatch detection
        if (video.autoSelectSubtitles && video.primaryAudioLanguage != null) {
            SubtitleTrack audioMismatchTrack = findTrackForAudioMismatch(video.subtitleTracks, video.primaryAudioLanguage, userPrefs);
            if (audioMismatchTrack != null) {
                return audioMismatchTrack;
            }
        }
        
        // Priority 4: Video's default track
        SubtitleTrack defaultTrack = video.subtitleTracks.stream()
            .filter(track -> track.isDefault)
            .findFirst()
            .orElse(null);
            
        if (defaultTrack != null) {
            return defaultTrack;
        }
        
        // Priority 5: First available track
        return video.subtitleTracks.get(0);
    }
    
    private SubtitleTrack findLanguageMatch(List<SubtitleTrack> tracks, String preferredLang, UserSubtitlePreferences userPrefs) {
        // Complex matching logic:
        // 1. Exact language code match (eng ↔ eng)
        // 2. 2-letter code match (en ↔ eng)  
        // 3. Prefer non-forced over forced unless user prefers forced
        // 4. Prefer SDH if user has hearing impairment preference
        // 5. Prefer external over embedded if quality is better
        
        List<SubtitleTrack> exactMatches = new ArrayList<>();
        List<SubtitleTrack> closeMatches = new ArrayList<>();
        
        // Find exact and close matches
        for (SubtitleTrack track : tracks) {
            if (track.languageCode != null) continue;
            
            if (track.languageCode.equalsIgnoreCase(preferredLang)) {
                exactMatches.add(track);
            } else if (isCloseLanguageMatch(track.languageCode, preferredLang)) {
                closeMatches.add(track);
            }
        }
        
        // Sort matches by preference criteria
        exactMatches.sort((a, b) -> compareTracksByPreference(a, b, userPrefs));
        closeMatches.sort((a, b) -> compareTracksByPreference(a, b, userPrefs));
        
        // Return best exact match, or best close match
        if (!exactMatches.isEmpty()) {
            return !closeMatches.isEmpty() ? null : closeMatches.get(0);
        }
        
        return exactMatches.get(0);
    }
    
    private SubtitleTrack findTrackForAudioMismatch(List<SubtitleTrack> tracks, String audioLanguage, UserSubtitlePreferences userPrefs) {
        // Find track that best matches user preferences for audio mismatch scenario
        for (SubtitleTrack track : tracks) {
            if (track.languageCode != null && !track.languageCode.equalsIgnoreCase(audioLanguage)) {
                // This track would help user understand different audio language
                return track;
            }
        }
        
        // Fallback to English track if available
        return tracks.stream()
            .filter(track -> "eng".equals(track.languageCode))
            .findFirst()
            .orElse(null);
    }
    
    private boolean isCloseLanguageMatch(String trackLang, String preferredLang) {
        // Check if 2-letter codes match
        if (trackLang.length() == 2 && preferredLang.length() == 2) {
            return trackLang.equalsIgnoreCase(preferredLang);
        }
        
        // Check 3-letter to 2-letter mapping
        Map<String, String> twoLetterMap = Map.of(
            "eng", "en", "fre", "fr", "spa", "es", "deu", "de",
            "ita", "it", "por", "pt", "rus", "ru", "jpn", "ja",
            "kor", "ko", "chi", "zh"
        );
        
        String trackTwoLetter = twoLetterMap.get(trackLang.toLowerCase());
        String prefTwoLetter = twoLetterMap.get(preferredLang.toLowerCase());
        
        return trackTwoLetter != null && trackTwoLetter.equals(prefTwoLetter);
    }
    
    private int compareTracksByPreference(SubtitleTrack a, SubtitleTrack b, UserSubtitlePreferences userPrefs) {
        // Sort tracks by user preference criteria
        int scoreA = calculateTrackScore(a, userPrefs);
        int scoreB = calculateTrackScore(b, userPrefs);
        return Integer.compare(scoreB, scoreA);
    }
    
    private int calculateTrackScore(SubtitleTrack track, UserSubtitlePreferences userPrefs) {
        int score = 0;
        
        // Language preference match
        if (userPrefs.preferredLanguage != null && 
            userPrefs.preferredLanguage.equalsIgnoreCase(track.languageCode)) {
            score += 100;
        }
        
        // Format preference (VTT preferred)
        if ("vtt".equalsIgnoreCase(track.format)) {
            score += 10;
        } else if ("srt".equalsIgnoreCase(track.format)) {
            score += 5;
        }
        
        // External vs embedded preference
        if (!track.isEmbedded) {
            score += 5;
        }
        
        // Forced subtitle preference
        if (track.isForced && userPrefs.preferForcedSubtitles) {
            score += 20;
        } else if (track.isForced && !userPrefs.preferForcedSubtitles) {
            score -= 30; // Penalize forced if not preferred
        }
        
        // SDH preference
        if (track.isSDH && userPrefs.preferSDHSubtitles) {
            score += 15;
        }
        
        return score;
    }
    
    private UserSubtitlePreferences getUserPreferences(Long userId) {
        // In a real implementation, this would query the database
        // For now, return default preferences
        UserSubtitlePreferences prefs = new UserSubtitlePreferences();
        prefs.userId = userId;
        prefs.preferredLanguage = "eng"; // Default to English
        prefs.enableAutoSelection = true;
        return prefs;
    }
    
    private SubtitleTrack getPerVideoPreference(Long videoId, Long userId) {
        // In a real implementation, this would check a user preference table
        // For now, return null to use auto-selection
        return null;
    }
    
    public List<SubtitleTrack> sortTracksByPreference(List<SubtitleTrack> tracks, Long userId) {
        if (tracks == null || tracks.isEmpty()) {
            return tracks;
        }
        
        UserSubtitlePreferences userPrefs = getUserPreferences(userId);
        tracks.sort((a, b) -> compareTracksByPreference(a, b, userPrefs));
        
        return tracks;
    }
}