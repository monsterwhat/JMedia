package Models.Video;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "live_channel", indexes = {
    @Index(name = "idx_livechannel_profile", columnList = "profile_id"),
    @Index(name = "idx_livechannel_group", columnList = "groupTitle"),
    @Index(name = "idx_livechannel_playlist", columnList = "playlist_id")
})
public class LiveChannel extends PanacheEntity {

    @Column(name = "profile_id")
    public Long profileId;

    @ManyToOne
    @JoinColumn(name = "playlist_id")
    public M3uPlaylist playlist;

    @Column(length = 500)
    public String name;

    @Column(length = 2000)
    public String streamUrl;

    @Column(length = 2000)
    public String logoUrl;

    @Column(length = 200)
    public String groupTitle;

    /**
     * Extra group memberships for a multi-group channel, stored as {@code ;A;B;}
     * (leading/trailing separator so SQL LOCATE can match a whole segment).
     * Null for single-group channels, where {@link #groupTitle} is authoritative.
     */
    @Column(length = 2000)
    public String groupTitles;

    @Column(length = 200)
    public String tvgId;

    @Column(length = 200)
    public String tvgName;

    @Column(length = 100)
    public String country;

    public Integer channelNumber;

    public Boolean isFavorite = false;

    /** True for radio stations: Xtream reports them as stream_type "radio_streams". */
    public Boolean isRadio = false;

    public String streamStatus;

    public LocalDateTime lastChecked;

    public LocalDateTime lastWatched;

    public LocalDateTime createdAt;

    /** Primary group followed by any extra memberships, de-duplicated. */
    public List<String> allGroupTitles() {
        return parseGroupTitles(groupTitle, groupTitles);
    }

    public static List<String> parseGroupTitles(String primary, String stored) {
        List<String> out = new ArrayList<>();
        if (stored != null && !stored.isBlank()) {
            for (String part : stored.split(";")) {
                String trimmed = part.trim();
                if (!trimmed.isEmpty() && !out.contains(trimmed)) {
                    out.add(trimmed);
                }
            }
        }
        if (out.isEmpty() && primary != null && !primary.isBlank()) {
            out.add(primary);
        }
        return out;
    }

    /** Value for the {@link #groupTitles} column, or null for a single group. */
    public static String encodeGroupTitles(List<String> groups) {
        if (groups == null || groups.size() <= 1) {
            return null;
        }
        // Keep each membership whole: the column is 2000 chars, and a partial
        // group name would create a phantom category.
        StringBuilder sb = new StringBuilder(";");
        for (String group : groups) {
            if (group == null || group.isBlank()) {
                continue;
            }
            if (sb.length() + group.length() + 1 > 2000) {
                break;
            }
            sb.append(group).append(';');
        }
        return sb.length() <= 1 ? null : sb.toString();
    }
}
