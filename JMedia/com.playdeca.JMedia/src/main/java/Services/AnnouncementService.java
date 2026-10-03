package Services;

import Models.Settings.User;
import Models.Video.Video;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Login announcements for Xtream players: reports library uploads added since
 * the user's previous login via user_info.message. No uploads since last login
 * means an empty message — never a greeting, never stale news.
 *
 * First login ever only sets the baseline (announcing the whole library would
 * spam thousands of entries).
 */
@ApplicationScoped
public class AnnouncementService {

    private static final Logger LOG = LoggerFactory.getLogger(AnnouncementService.class);
    private static final int MAX_ITEMS = 6;
    private static final int MAX_MESSAGE_CHARS = 220;

    @Transactional
    public String loginAnnouncement(User user) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime since = user.getLastLoginAt();
        user.setLastLoginAt(now);
        try {
            // The user instance comes from the auth query (detached here), so
            // merge rather than persist — persist throws on detached entities
            // and would silently disable announcements forever.
            Models.Settings.User.getEntityManager().merge(user);
        } catch (Exception e) {
            LOG.warn("Could not persist lastLoginAt for user {}: {}", user.getUsername(), e.getMessage());
            if (since == null) return "";
        }
        if (since == null) return "";

        List<Video> fresh;
        try {
            fresh = Video.find("dateAdded > ?1 order by dateAdded desc", since).page(0, MAX_ITEMS + 1).list();
        } catch (Exception e) {
            LOG.warn("New-uploads query failed for user {}: {}", user.getUsername(), e.getMessage());
            return "";
        }
        if (fresh.isEmpty()) return "";

        List<String> labels = new ArrayList<>();
        for (Video v : fresh) {
            if (labels.size() >= MAX_ITEMS) break;
            String label = labelFor(v);
            if (label != null && !label.isBlank()) labels.add(label);
        }
        if (labels.isEmpty()) return "";

        StringBuilder message = new StringBuilder("New: ");
        int extra = fresh.size() - labels.size();
        for (int i = 0; i < labels.size(); i++) {
            String part = (i == 0 ? "" : ", ") + labels.get(i);
            if (message.length() + part.length() > MAX_MESSAGE_CHARS) {
                extra = fresh.size() - i;
                break;
            }
            message.append(part);
        }
        if (extra > 0 || fresh.size() > labels.size()) {
            int remaining = Math.max(extra, fresh.size() - labels.size());
            message.append(" (+").append(remaining).append(" more)");
        }
        return message.toString();
    }

    private String labelFor(Video v) {
        boolean isEpisode = "episode".equalsIgnoreCase(v.type) || "episode".equalsIgnoreCase(v.contentType);
        if (isEpisode) {
            String show = v.seriesTitle != null && !v.seriesTitle.isBlank() ? v.seriesTitle.strip() : v.title;
            if (show == null || show.isBlank()) return null;
            if (v.seasonNumber != null && v.episodeNumber != null) {
                return String.format("%s S%02dE%02d", show, v.seasonNumber, v.episodeNumber);
            }
            if (v.episodeTitle != null && !v.episodeTitle.isBlank()) {
                return show + " - " + v.episodeTitle.strip();
            }
            return show;
        }
        if (v.title != null && !v.title.isBlank()) return v.title.strip();
        return null;
    }
}
