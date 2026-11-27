package API.Rest;

import API.ApiResponse;
import Models.Music.Song;
import Models.Settings.Profile;
import Services.MetadataWriteService;
import Services.SettingsService;
import Services.SongService;
import Controllers.SettingsController;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Album metadata operations for the drag-and-drop drawer.
 *
 * <p>Albums are not entities: the app groups songs by (album, albumArtist).
 * "Create album" and "add to album" therefore assign those two fields to the
 * selected songs. File tags are optional and only written when requested.
 */
@Path("/api/music/albums")
@Produces(MediaType.APPLICATION_JSON)
public class AlbumAPI {

    private static final Logger LOG = LoggerFactory.getLogger(AlbumAPI.class);
    private static final int TAG_WRITE_LIMIT = 200;

    @Inject
    SongService songService;

    @Inject
    SettingsService settingsService;

    @Inject
    SettingsController settings;

    @Inject
    MetadataWriteService metadataWriteService;

        public record AlbumOption(Long id, String album, String albumArtist, Long firstSongId,
                              Long songCount, String year) {}

    public static class AssignAlbumRequest {
                public List<Long> songIds;
        /** Existing album to drop into (preferred); name/artist is for "New album". */
        public Long albumId;
        public String album;
        public String albumArtist;
        public boolean writeTags;
    }

    @GET
    public Response listAlbums(
            @QueryParam("offset") @DefaultValue("0") int offset,
            @QueryParam("limit") @DefaultValue("200") int limit,
            @QueryParam("search") @DefaultValue("") String search,
            @Context HttpHeaders headers) {
        if (settingsService.getActiveProfileFromHeaders(headers) == null) {
            return Response.status(Response.Status.UNAUTHORIZED).build();
        }

        int safeLimit = Math.max(1, Math.min(limit, 500));
        int safeOffset = Math.max(0, offset);
        int page = (safeOffset / safeLimit) + 1;

                SongService.PaginatedAlbums result = songService.findAlbums(page, safeLimit, search, "album", "asc");
        List<AlbumOption> items = new ArrayList<>();
        for (SongService.AlbumCard card : result.albums()) {
            items.add(new AlbumOption(
                    card.id(),
                    card.name(),
                    card.artist(),
                    card.firstSongId(),
                    card.songCount(),
                    card.getYear()
            ));
        }

        return Response.ok(ApiResponse.success(new AlbumList(items, result.totalCount(), safeOffset))).build();
    }

    public record AlbumList(List<AlbumOption> items, long total, int offset) {}

    @POST
    @Path("/assign")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response assignAlbum(AssignAlbumRequest request, @Context HttpHeaders headers) {
        Profile profile = settingsService.getActiveProfileFromHeaders(headers);
        if (profile == null) {
            return Response.status(Response.Status.UNAUTHORIZED).build();
        }
        if (request == null || request.songIds == null || request.songIds.isEmpty()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(ApiResponse.error("songIds is required")).build();
        }
                String album;
        String albumArtist;
        if (request.albumId != null) {
            Models.Music.Album target = Models.Music.Album.findById(request.albumId);
            if (target == null) {
                return Response.status(Response.Status.NOT_FOUND)
                        .entity(ApiResponse.error("Album not found")).build();
            }
            album = target.name;
            albumArtist = (target.artist == null || target.artist.isBlank()) ? null : target.artist;
        } else {
            if (request.album == null || request.album.isBlank()) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(ApiResponse.error("albumId or album is required")).build();
            }
            album = request.album.trim();
            albumArtist = request.albumArtist == null || request.albumArtist.isBlank()
                    ? null : request.albumArtist.trim();
        }

        int updated = songService.assignAlbum(request.songIds, album, albumArtist);
        LOG.info("Assigned {} songs to album '{}' (artist={}) for profile {}",
                updated, album, albumArtist, profile.id);

        int tagsWritten = 0;
        int tagFailures = 0;
        int tagSkipped = 0;

        if (request.writeTags) {
            int limit = Math.min(request.songIds.size(), TAG_WRITE_LIMIT);
            tagSkipped = request.songIds.size() - limit;
            if (tagSkipped > 0) {
                LOG.warn("Only writing tags for {} of {} songs (limit {})",
                        limit, request.songIds.size(), TAG_WRITE_LIMIT);
            }
            File musicFolder = settings.getMusicFolder();
            if (musicFolder == null) {
                LOG.warn("Cannot write tags: music folder is not configured");
                tagFailures = limit;
            } else {
                java.nio.file.Path root = musicFolder.toPath().toAbsolutePath().normalize();
                for (int i = 0; i < limit; i++) {
                    Long songId = request.songIds.get(i);
                    Song song = songService.find(songId);
                    if (song == null || song.getPath() == null) {
                        tagFailures++;
                        LOG.warn("Skipping tag write for missing song {}", songId);
                        continue;
                    }
                    java.nio.file.Path file = root.resolve(song.getPath()).normalize();
                    if (!file.startsWith(root) || !Files.exists(file)) {
                        tagFailures++;
                        LOG.warn("Skipping tag write for song {} (file not found: {})", songId, file);
                        continue;
                    }
                    try {
                        Boolean ok = metadataWriteService.writeMetadataToFile(song, file.toString())
                                .get(30, TimeUnit.SECONDS);
                        if (Boolean.TRUE.equals(ok)) {
                            tagsWritten++;
                        } else {
                            tagFailures++;
                            LOG.warn("Tag write returned false for song {}", songId);
                        }
                    } catch (Exception e) {
                        tagFailures++;
                        LOG.error("Tag write failed for song {}", songId, e);
                    }
                }
            }
        }

        return Response.ok(ApiResponse.success(new AssignResult(updated, tagsWritten, tagFailures, tagSkipped)))
                .build();
    }

    public record AssignResult(int updated, int tagsWritten, int tagFailures, int tagSkipped) {}
}
