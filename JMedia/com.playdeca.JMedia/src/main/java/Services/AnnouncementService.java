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
 * First tracked login catches up on the last 14 days once, so recently added
 * content is never silently skipped; anything older predates announcements.
 */
@ApplicationScoped
public class AnnouncementService {

    private static final Logger LOG = LoggerFactory.getLogger(AnnouncementService.class);
    private static final int MAX_ITEMS = 6;
    private static final int MAX_MESSAGE_CHARS = 220;

    @jakarta.inject.Inject
    VideoService videoService;

    // Login touches the settings DB (lastLoginAt) while the new-uploads query
    // reads the video DB. Two non-XA datasources cannot share one JTA
    // transaction ("Exception in association of connection"), so the two steps
    // run in separate transactions: this orchestrator is intentionally NOT
    // @Transactional; each step below opens and commits its own.
    public String loginAnnouncement(User user) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime since = user.getLastLoginAt();
        touchLogin(user, now);
        LocalDateTime effectiveSince = (since != null) ? since : now.minusDays(FIRST_LOGIN_CATCHUP_DAYS);

        List<Video> fresh;
        try {
            fresh = videoService.findAddedSince(effectiveSince, MAX_ITEMS + 1);
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

    @Transactional
    void touchLogin(User user, LocalDateTime at) {
        // Merge: the instance comes from the auth query (detached here).
        user.setLastLoginAt(at);
        Models.Settings.User.getEntityManager().merge(user);
    }

    // First tracked login has no baseline: catch up on the last 14 days once
    // instead of staying silent forever about recently added content.
    // Anything older predates announcements and is never reported.
    private static final int FIRST_LOGIN_CATCHUP_DAYS = 14;

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
