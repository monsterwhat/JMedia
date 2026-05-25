package Services;

import Models.Profile;
import Models.VideoSuggestion;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.util.List;

@ApplicationScoped
public class VideoSuggestionService {

    @Inject
    SettingsService settingsService;

    @Transactional
    public void addSuggestion(String content) {
        Profile profile = settingsService.getActiveProfile();
        if (profile == null || content == null || content.trim().isEmpty()) {
            return;
        }
        VideoSuggestion suggestion = new VideoSuggestion(profile, content.trim());
        suggestion.persist();
    }

    public List<VideoSuggestion> findAll() {
        return VideoSuggestion.list("ORDER BY createdAt DESC");
    }

    public List<VideoSuggestion> findByProfile(Long profileId) {
        return VideoSuggestion.list("profile.id = ?1 ORDER BY createdAt DESC", profileId);
    }

    @Transactional
    public void delete(Long id) {
        VideoSuggestion.delete("id", id);
    }

    @Transactional
    public void deleteAll() {
        VideoSuggestion.deleteAll();
    }
}