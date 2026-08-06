package Services;

import Models.DTOs.M3uImportResponse;
import Models.LiveChannel;
import Models.M3uPlaylist;
import Models.Profile;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Owns all transactional M3uPlaylist / LiveChannel writes previously performed
 * directly in {@code API.Rest.M3uImportApi}. Every write method runs in its own
 * transaction so the API layer stays free of {@code @Transactional}.
 */
@ApplicationScoped
public class M3uService {

    /** Outcome of {@link #updateChannelStatus(Long, String)}, mirroring the API's error precedence. */
    public enum ChannelStatusUpdate {
        CHANNEL_NOT_FOUND,
        INVALID_STATUS,
        UPDATED
    }

    @Transactional
    public Profile findProfile(Long profileId) {
        return Profile.findById(profileId);
    }

    @Transactional
    public M3uImportResponse createPlaylistAndImportChannels(Long profileId, String url, String name, String type,
            List<M3uParserService.M3uEntry> entries) {
        Profile profile = Profile.findById(profileId);
        if (profile == null) {
            return null;
        }

        // Create playlist entity
        M3uPlaylist playlist = new M3uPlaylist();
        playlist.profile = profile;
        playlist.url = url;
        playlist.name = name != null ? name : "Imported Playlist";
        playlist.type = type;
        playlist.channelCount = 0;
        playlist.lastRefreshed = LocalDateTime.now();
        playlist.isActive = true;
        playlist.createdAt = LocalDateTime.now();
        playlist.persist();

        // Import channels
        return importEntries(playlist, profile, entries);
    }

    @Transactional
    public boolean deletePlaylist(Long id) {
        M3uPlaylist playlist = M3uPlaylist.findById(id);
        if (playlist == null) {
            return false;
        }

        deleteChannelsForPlaylist(id);
        playlist.delete();
        return true;
    }

    @Transactional
    public M3uPlaylist updatePlaylist(Long id, String name, String url, String type) {
        M3uPlaylist playlist = M3uPlaylist.findById(id);
        if (playlist == null) {
            return null;
        }

        if (name != null) {
            playlist.name = name;
        }
        if (url != null) {
            playlist.url = url;
        }
        if (type != null) {
            playlist.type = type;
        }
        playlist.persist();
        return playlist;
    }

    @Transactional
    public boolean deleteChannel(Long id) {
        LiveChannel channel = LiveChannel.findById(id);
        if (channel == null) {
            return false;
        }

        Long playlistId = channel.playlist != null ? channel.playlist.id : null;
        channel.delete();

        if (playlistId != null) {
            M3uPlaylist playlist = M3uPlaylist.findById(playlistId);
            if (playlist != null) {
                // Recompute channelCount before persisting, same ordering as the original API flow.
                playlist.channelCount = (int) LiveChannel.count("playlist.id = ?1", playlistId);
                playlist.persist();
            }
        }
        return true;
    }

    @Transactional
    public ChannelStatusUpdate updateChannelStatus(Long id, String status) {
        LiveChannel channel = LiveChannel.findById(id);
        if (channel == null) {
            return ChannelStatusUpdate.CHANNEL_NOT_FOUND;
        }

        if (status == null || (!status.equals("working") && !status.equals("dead"))) {
            return ChannelStatusUpdate.INVALID_STATUS;
        }

        channel.streamStatus = status;
        channel.lastChecked = LocalDateTime.now();
        channel.persist();
        return ChannelStatusUpdate.UPDATED;
    }

    @Transactional
    public Boolean toggleFavorite(Long id) {
        LiveChannel channel = LiveChannel.findById(id);
        if (channel == null) {
            return null;
        }

        channel.isFavorite = channel.isFavorite == null || !channel.isFavorite;
        channel.persist();
        return channel.isFavorite;
    }

    @Transactional
    public M3uImportResponse refreshPlaylist(Long id, List<M3uParserService.M3uEntry> entries) {
        M3uPlaylist playlist = M3uPlaylist.findById(id);
        if (playlist == null) {
            return null;
        }

        // Delete existing channels and re-import
        deleteChannelsForPlaylist(id);
        playlist.lastRefreshed = LocalDateTime.now();
        playlist.persist();

        return importEntries(playlist, playlist.profile, entries);
    }

    @Transactional
    public M3uImportResponse importEntries(M3uPlaylist playlist, Profile profile,
            List<M3uParserService.M3uEntry> entries) {
        M3uImportResponse response = new M3uImportResponse();
        response.playlistId = playlist.id;
        response.playlistName = playlist.name;
        response.totalEntries = entries.size();
        response.channelsCreated = 0;
        response.failedEntries = 0;

        int channelNumber = 1;
        for (M3uParserService.M3uEntry entry : entries) {
            try {
                if (entry.streamUrl == null || entry.streamUrl.isBlank()) {
                    response.failedEntries++;
                    response.errors.add("Skipping entry with no URL: " + (entry.name != null ? entry.name : "unknown"));
                    continue;
                }

                LiveChannel channel = new LiveChannel();
                channel.profile = profile;
                channel.playlist = playlist;
                channel.name = entry.name;
                channel.streamUrl = entry.streamUrl;
                channel.logoUrl = entry.logoUrl;
                channel.groupTitle = entry.groupTitle;
                channel.tvgId = entry.tvgId;
                channel.tvgName = entry.tvgName;
                channel.country = entry.country;
                channel.channelNumber = channelNumber++;
                channel.isFavorite = false;
                channel.createdAt = LocalDateTime.now();
                channel.persist();

                response.channelsCreated++;
            } catch (Exception e) {
                response.failedEntries++;
                response.errors.add("Error importing '" + entry.name + "': " + e.getMessage());
            }
        }

        playlist.channelCount = response.channelsCreated;
        playlist.persist();

        response.message = "Import complete: " + response.channelsCreated + " channels created"
                + (response.failedEntries > 0 ? ", " + response.failedEntries + " failed" : "");
        return response;
    }

    @Transactional
    public void deleteChannelsForPlaylist(Long playlistId) {
        LiveChannel.delete("playlist.id = ?1", playlistId);
    }
}
