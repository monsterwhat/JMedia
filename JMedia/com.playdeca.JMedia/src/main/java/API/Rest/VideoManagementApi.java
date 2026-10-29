package API.Rest;

import Services.VideoService;
import Services.VideoQueryService;
import Services.VideoImportService;
import Services.SettingsService;
import Services.VideoConversionService;
import Services.VideoMetadataService;
import Services.MetadataEnrichmentWorker;
import Models.Video.Video;
import Models.Video.Series;
import Models.DTOs.TvShowDTO;
import Models.DTOs.VerificationPreview;
import io.quarkus.qute.Template;
import io.smallrye.common.annotation.Blocking;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Path("/api/video/manage")
@Produces(MediaType.TEXT_HTML)
public class VideoManagementApi {

    private static final Logger LOG = LoggerFactory.getLogger(VideoManagementApi.class);

    @Inject
    VideoService videoService;

    @Inject
    VideoQueryService videoQueryService;

    @Inject
    VideoImportService videoImportService;

    @Inject
    SettingsService settingsService;

    @Inject
    VideoConversionService videoConversionService;

    @Inject
    com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    @Inject @io.quarkus.qute.Location("manageFragment.html")
    Template manageFragment;

    @Inject @io.quarkus.qute.Location("editVideoFragment.html")
    Template editVideoFragment;

    @Inject @io.quarkus.qute.Location("seriesEpisodesFragment.html")
    Template seriesEpisodesFragment;

    @Inject @io.quarkus.qute.Location("needsAttentionFragment.html")
    Template needsAttentionFragment;

    @Inject @io.quarkus.qute.Location("verificationFragment.html")
    Template verificationFragment;

    @Inject
    MetadataEnrichmentWorker metadataEnrichmentWorker;

    @Inject
    VideoMetadataService videoMetadataService;

    @Inject
    Services.ThumbnailService thumbnailService;

    @GET
    @Blocking
    public String getManagePanel(@QueryParam("search") String search, @QueryParam("type") String type) {
        List<TvShowDTO> shows = null;
        List<Video> videosToDisplay = null;
        int totalCount;

        if ("episode".equalsIgnoreCase(type)) {
            // Grouping happens in the service layer (transactional) so the lazy
            // Video.series association is resolved before entities are detached.
            shows = videoService.findTvShowsForManage(search);
            totalCount = shows.size();
        } else {
            List<Video> allVideos = videoService.findAll();
            List<Video> filteredVideos = allVideos;

            if (search != null && !search.isEmpty()) {
                String lowerSearch = search.toLowerCase();
                filteredVideos = filteredVideos.stream()
                        .filter(v -> (v.title != null && v.title.toLowerCase().contains(lowerSearch)) || 
                                     (v.seriesTitle != null && v.seriesTitle.toLowerCase().contains(lowerSearch)) ||
                                     (v.filename != null && v.filename.toLowerCase().contains(lowerSearch)))
                        .collect(Collectors.toList());
            }

            if (type != null && !type.isEmpty() && !type.equals("all")) {
                final String finalType = type;
                filteredVideos = filteredVideos.stream()
                        .filter(v -> finalType.equalsIgnoreCase(v.type))
                        .collect(Collectors.toList());
            }

            totalCount = filteredVideos.size();
            // Limit results for management panel to avoid crashing UI
            videosToDisplay = filteredVideos.stream().limit(100).collect(Collectors.toList());
        }

        return manageFragment
                .data("videos", videosToDisplay)
                .data("shows", shows)
                .data("totalCount", totalCount)
                .data("search", search)
                .data("type", type)
                .render();
    }

    @GET
    @Path("/series/{seriesTitle}")
    @Blocking
    public String getSeriesEpisodes(@PathParam("seriesTitle") String seriesTitle) {
        try {
            List<Video> episodes = videoService.findEpisodesForSeries(seriesTitle);
            if (episodes.isEmpty()) return "<div class='notification is-danger'>Series not found</div>";
            
            Video representative = episodes.get(0);
            String jsonEpisodes = "[]";
            try {
                List<Models.DTOs.SimpleEpisodeDTO> simpleEpisodes = episodes.stream()
                        .map(Models.DTOs.SimpleEpisodeDTO::new)
                        .collect(Collectors.toList());
                jsonEpisodes = objectMapper.writeValueAsString(simpleEpisodes);
            } catch (Exception e) {
                LOG.error("Failed to serialize episodes", e);
            }

            String tvdbId = null;
            try {
                Series series = Series.find("title", seriesTitle).firstResult();
                if (series != null && series.tvdbId != null) {
                    tvdbId = String.valueOf(series.tvdbId);
                } else if (representative.tvdbId != null && !representative.tvdbId.isBlank()) {
                    tvdbId = representative.tvdbId.trim();
                }
            } catch (Exception e) {
                LOG.warn("Failed to load tvdbId for series '{}': {}", seriesTitle, e.getMessage());
                if (representative.tvdbId != null && !representative.tvdbId.isBlank()) {
                    tvdbId = representative.tvdbId.trim();
                }
            }

            String tmdbId = null;
            try {
                Series series = Series.find("title", seriesTitle).firstResult();
                if (series != null && series.tmdbId != null) {
                    tmdbId = String.valueOf(series.tmdbId);
                } else if (representative.tmdbId != null && !representative.tmdbId.isBlank()) {
                    tmdbId = representative.tmdbId.trim();
                }
            } catch (Exception e) {
                LOG.warn("Failed to load tmdbId for series '{}': {}", seriesTitle, e.getMessage());
                if (representative.tmdbId != null && !representative.tmdbId.isBlank()) {
                    tmdbId = representative.tmdbId.trim();
                }
            }

            return seriesEpisodesFragment
                    .data("seriesTitle", seriesTitle)
                    .data("episodes", episodes)
                    .data("jsonEpisodes", jsonEpisodes)
                    .data("posterPath", representative.posterPath)
                    .data("backdropPath", representative.backdropPath)
                    .data("showImdbId", representative.showImdbId)
                    .data("tvdbId", tvdbId)
                    .data("tmdbId", tmdbId)
                    .render();
        } catch (Exception e) {
            LOG.error("Error loading series episodes for '{}'", seriesTitle, e);
            return "<div class='notification is-danger'><strong>Error loading series:</strong> " + e.getMessage() + "</div>";
        }
    }

    @POST
    @Path("/series/update")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Blocking
    public Response updateSeries(
            @FormParam("seriesTitle") String seriesTitle,
            @FormParam("newTitle") String newTitle,
            @FormParam("posterPath") String posterPath,
            @FormParam("backdropPath") String backdropPath,
            @FormParam("showImdbId") String showImdbId,
            @FormParam("tvdbId") String tvdbId,
            @FormParam("tmdbId") String tmdbId,
            @FormParam("absoluteSeasonLengths") String absoluteSeasonLengths,
            @FormParam("forceAbsoluteNumbering") String forceAbsoluteNumbering) {
        
        if (newTitle != null && !newTitle.isBlank() && !newTitle.equals(seriesTitle)) {
            videoService.updateSeriesTitle(seriesTitle, newTitle);
            seriesTitle = newTitle; // Use new title for metadata update
        }
        
        Integer tvdbIdParsed = parseTvdbId(tvdbId, seriesTitle);
        Integer tmdbIdParsed = parseTmdbId(tmdbId, seriesTitle);
        videoService.updateSeriesMetadata(seriesTitle, posterPath, backdropPath, showImdbId, tvdbIdParsed, tmdbIdParsed);
        // Phase 4b per-show absolute-numbering override (strictly validated;
        // blank clears the override). Only runs when the edit form carries
        // the field, so legacy callers are unaffected.
        if (absoluteSeasonLengths != null) {
            try {
                videoService.updateSeriesAbsoluteOverride(seriesTitle, absoluteSeasonLengths,
                        parseForceAbsoluteNumbering(forceAbsoluteNumbering));
            } catch (IllegalArgumentException e) {
                return Response.status(Response.Status.BAD_REQUEST).entity(e.getMessage()).build();
            }
        }
        return Response.ok("Series updated successfully").build();
    }

    private Integer parseTvdbId(String tvdbId, String seriesTitle) {
        if (tvdbId == null) return null;
        String trimmed = tvdbId.trim();
        if (trimmed.isEmpty()) return null;
        if (!trimmed.matches("\\d+")) {
            LOG.warn("Invalid tvdbId '{}' for series '{}': expected digits", trimmed, seriesTitle);
            return null;
        }
        try {
            return Integer.valueOf(trimmed);
        } catch (NumberFormatException e) {
            LOG.warn("Failed to parse tvdbId '{}' for series '{}': {}", trimmed, seriesTitle, e.getMessage());
            return null;
        }
    }

    private Integer parseTmdbId(String tmdbId, String seriesTitle) {
        if (tmdbId == null) return null;
        String trimmed = tmdbId.trim();
        if (trimmed.isEmpty()) return null;
        if (!trimmed.matches("\\d+")) {
            LOG.warn("Invalid tmdbId '{}' for series '{}': expected digits", trimmed, seriesTitle);
            return null;
        }
        try {
            return Integer.valueOf(trimmed);
        } catch (NumberFormatException e) {
            LOG.warn("Failed to parse tmdbId '{}' for series '{}': {}", trimmed, seriesTitle, e.getMessage());
            return null;
        }
    }

    /**
     * Phase 4b: parses the "Force absolute numbering" checkbox value.
     * Unchecked checkboxes submit nothing (null → null = default-off);
     * checked boxes submit "true"/"on"/"1" depending on the client.
     */
    private Boolean parseForceAbsoluteNumbering(String value) {
        if (value == null) return null;
        String trimmed = value.trim().toLowerCase();
        if (trimmed.isEmpty() || trimmed.equals("false") || trimmed.equals("0") || trimmed.equals("off")) {
            return Boolean.FALSE;
        }
        return Boolean.TRUE;
    }

    @POST
    @Path("/series/update-and-refetch")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Blocking
    public Response updateSeriesAndRefetch(
            @FormParam("seriesTitle") String seriesTitle,
            @FormParam("newTitle") String newTitle,
            @FormParam("posterPath") String posterPath,
            @FormParam("backdropPath") String backdropPath,
            @FormParam("showImdbId") String showImdbId,
            @FormParam("tvdbId") String tvdbId,
            @FormParam("tmdbId") String tmdbId,
            @FormParam("absoluteSeasonLengths") String absoluteSeasonLengths,
            @FormParam("forceAbsoluteNumbering") String forceAbsoluteNumbering) {

        if (newTitle != null && !newTitle.isBlank() && !newTitle.equals(seriesTitle)) {
            videoService.updateSeriesTitle(seriesTitle, newTitle);
            seriesTitle = newTitle;
        }

        Integer tvdbIdParsed = parseTvdbId(tvdbId, seriesTitle);
        Integer tmdbIdParsed = parseTmdbId(tmdbId, seriesTitle);
        if (absoluteSeasonLengths != null) {
            try {
                videoService.updateSeriesAbsoluteOverride(seriesTitle, absoluteSeasonLengths,
                        parseForceAbsoluteNumbering(forceAbsoluteNumbering));
            } catch (IllegalArgumentException e) {
                return Response.status(Response.Status.BAD_REQUEST).entity(e.getMessage()).build();
            }
        }
        int queued = videoService.updateSeriesAndRefetchMetadata(seriesTitle, posterPath, backdropPath, showImdbId, tvdbIdParsed, tmdbIdParsed);
        return Response.ok("Queued " + queued + " episodes for background enrichment").build();
    }

    @POST
    @Path("/series/rescan")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Blocking
    public Response rescanSeries(@FormParam("seriesTitle") String seriesTitle) {
        List<Video> episodes = videoService.findEpisodesForSeries(seriesTitle);
        if (episodes.isEmpty()) {
            return Response.status(Response.Status.NOT_FOUND).entity("Series not found").build();
        }

        String videoLibraryPath = settingsService.getOrCreateSettings().getVideoLibraryPath();
        if (videoLibraryPath == null || videoLibraryPath.isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST).entity("Video library path not configured").build();
        }

        Set<java.nio.file.Path> parentDirs = new HashSet<>();
        for (Video v : episodes) {
            if (v.path != null) {
                try {
                    java.nio.file.Path fullPath = java.nio.file.Paths.get(v.path);
                    if (!fullPath.isAbsolute()) {
                        fullPath = java.nio.file.Paths.get(videoLibraryPath, v.path);
                    }
                    parentDirs.add(fullPath.getParent());
                } catch (Exception e) {
                    LOG.error("Error determining parent path for video: " + v.path, e);
                }
            }
        }

        LOG.info("Forcing rescan for series '{}' in {} directories", seriesTitle, parentDirs.size());
        int totalCreated = 0;
        for (java.nio.file.Path dir : parentDirs) {
            List<Models.Video.Video> videos = videoImportService.scanAndCreate(dir, true);
            totalCreated += videos.size();
        }

        return Response.ok("Rescan completed. Created/updated " + totalCreated + " videos.").build();
    }

    /**
     * Removes database rows whose backing file no longer exists on disk.
     *
     * <p>Deliberately separate from {@code /api/video/scan/tvshows} and
     * {@code /api/video/scan/movies}: those also run a full re-scan and queue
     * every video for thumbnail regeneration. This endpoint touches only the
     * bookkeeping rows (and their state/genre/history relations), so it is
     * cheap enough to run right after files are moved out of the library.
     *
     * @param type {@code episode} (default) or {@code movie}
     */
    @POST
    @Path("/prune-missing")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_PLAIN)
    @Blocking
    public Response pruneMissing(@FormParam("type") String type) {
        String mediaType = (type == null || type.isBlank()) ? "episode" : type.trim().toLowerCase();
        if (!mediaType.equals("episode") && !mediaType.equals("movie")) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("type must be 'episode' or 'movie'").build();
        }

        String videoLibraryPath = settingsService.getOrCreateSettings().getVideoLibraryPath();
        java.nio.file.Path libraryPath = (videoLibraryPath == null || videoLibraryPath.isBlank())
                ? null
                : java.nio.file.Paths.get(videoLibraryPath);

        LOG.info("Prune-missing requested for type '{}' (library path {})", mediaType, libraryPath);
        try {
            int pruned = videoImportService.pruneMissingByType(mediaType, libraryPath);
            LOG.info("Prune-missing removed {} missing '{}' entries", pruned, mediaType);
            return Response.ok("Pruned " + pruned + " missing " + mediaType + " entries.").build();
        } catch (RuntimeException e) {
            LOG.error("Prune-missing failed for type '{}': {}", mediaType, e.getMessage(), e);
            return Response.serverError()
                    .entity("Prune failed: " + e.getMessage()).build();
        }
    }

    @POST
    @Path("/series/force-refresh")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Blocking
    public Response forceRefreshSeries(@FormParam("seriesTitle") String seriesTitle) {
        List<Video> episodes = videoService.findEpisodesForSeries(seriesTitle);
        if (episodes.isEmpty()) {
            return Response.status(Response.Status.NOT_FOUND).entity("Series not found").build();
        }

        // Check if video library path is configured
        String videoLibraryPath = settingsService.getOrCreateSettings().getVideoLibraryPath();
        if (videoLibraryPath == null || videoLibraryPath.isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST).entity("Video library path not configured").build();
        }

        // Collect parent directories of all episodes
        Set<java.nio.file.Path> parentDirs = new HashSet<>();
        for (Video v : episodes) {
            if (v.path != null) {
                try {
                    java.nio.file.Path fullPath = java.nio.file.Paths.get(v.path);
                    if (!fullPath.isAbsolute()) {
                        fullPath = java.nio.file.Paths.get(videoLibraryPath, v.path);
                    }
                    if (fullPath.getParent() != null) {
                        parentDirs.add(fullPath.getParent());
                    }
                } catch (Exception e) {
                    LOG.error("Error determining parent path for video: " + v.path, e);
                }
            }
        }

        // Delete all existing records for this series to remove stale entries
        videoService.forceReload(seriesTitle);

        // Re-scan directories to import only current files
        int totalCreated = 0;
        for (java.nio.file.Path dir : parentDirs) {
            List<Models.Video.Video> videos = videoImportService.scanAndCreate(dir, true);
            totalCreated += videos.size();
        }

        return Response.ok("Force refresh completed. Re-imported " + totalCreated + " videos.").build();
    }

    @POST
    @Path("/movie/force-refresh")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Blocking
    public Response forceRefreshMovie(@FormParam("videoId") Long videoId) {
        if (videoId == null) {
            return Response.status(Response.Status.BAD_REQUEST).entity("videoId is required").build();
        }

        Video movie = videoService.findById(videoId);
        if (movie == null) {
            return Response.status(Response.Status.NOT_FOUND).entity("Movie not found").build();
        }

        String videoLibraryPath = settingsService.getOrCreateSettings().getVideoLibraryPath();
        if (videoLibraryPath == null || videoLibraryPath.isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST).entity("Video library path not configured").build();
        }

        java.nio.file.Path fullPath;
        try {
            fullPath = java.nio.file.Paths.get(movie.path);
            if (!fullPath.isAbsolute()) {
                fullPath = java.nio.file.Paths.get(videoLibraryPath, movie.path);
            }
        } catch (Exception e) {
            LOG.error("Error determining parent path for video: " + movie.path, e);
            return Response.status(Response.Status.BAD_REQUEST).entity("Could not resolve movie path").build();
        }

        java.nio.file.Path parentDir = fullPath.getParent();
        if (parentDir == null) {
            return Response.status(Response.Status.BAD_REQUEST).entity("Could not determine movie directory").build();
        }

        videoService.forceReloadMovie(videoId);

        List<Models.Video.Video> videos = videoImportService.scanAndCreate(parentDir, true);

        return Response.ok("Force refresh completed. Re-imported " + videos.size() + " videos.").build();
    }

    @POST
    @Path("/series/refetch-images")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Blocking
    public Response refetchSeriesImages(@FormParam("seriesTitle") String seriesTitle) {
        VideoService.RefetchImagesResult result = videoService.refetchSeriesImages(seriesTitle);
        switch (result.status()) {
            case NOT_FOUND:
                return Response.status(Response.Status.NOT_FOUND).entity(result.message()).build();
            case BAD_REQUEST:
                return Response.status(Response.Status.BAD_REQUEST).entity(result.message()).build();
            case ERROR:
                return Response.serverError().entity(result.message()).build();
            default:
                return Response.ok(result.message()).build();
        }
    }

    @GET
    @Path("/edit/{id}")
    @Blocking
    public String getEditFragment(@PathParam("id") Long id) {
        Video video = videoService.find(id);
        if (video == null) return "<div class='notification is-danger'>Video not found</div>";
        
        List<String> allSeries = videoService.findAllSeriesTitles();
        
        return editVideoFragment
                .data("video", video)
                .data("allSeries", allSeries)
                .render();
    }

    @GET
    @Path("/edit-series/{seriesTitle}")
    @Blocking
    public String getEditSeriesFragment(@PathParam("seriesTitle") String seriesTitle) {
        List<Video> episodes = videoService.findEpisodesForSeries(seriesTitle);
        if (episodes.isEmpty()) return "<div class='notification is-danger'>Series not found</div>";
        
        Video representative = episodes.get(0);
        String tvdbIdPrefill = "";
        try {
            Series series = Series.find("title", seriesTitle).firstResult();
            if (series != null && series.tvdbId != null) {
                tvdbIdPrefill = String.valueOf(series.tvdbId);
            } else if (representative.tvdbId != null && !representative.tvdbId.isBlank()) {
                tvdbIdPrefill = representative.tvdbId.trim();
            }
        } catch (Exception e) {
            LOG.warn("Failed to load tvdbId for series '{}': {}", seriesTitle, e.getMessage());
            if (representative.tvdbId != null && !representative.tvdbId.isBlank()) {
                tvdbIdPrefill = representative.tvdbId.trim();
            }
        }
        String tmdbIdPrefill = "";
        try {
            Series series = Series.find("title", seriesTitle).firstResult();
            if (series != null && series.tmdbId != null) {
                tmdbIdPrefill = String.valueOf(series.tmdbId);
            } else if (representative.tmdbId != null && !representative.tmdbId.isBlank()) {
                tmdbIdPrefill = representative.tmdbId.trim();
            }
        } catch (Exception e) {
            LOG.warn("Failed to load tmdbId for series '{}': {}", seriesTitle, e.getMessage());
            if (representative.tmdbId != null && !representative.tmdbId.isBlank()) {
                tmdbIdPrefill = representative.tmdbId.trim();
            }
        }
        // Phase 4b per-show absolute-numbering override prefill.
        String absoluteLengthsPrefill = "";
        boolean forceAbsolutePrefill = false;
        try {
            Series series = Series.find("title", seriesTitle).firstResult();
            if (series != null) {
                if (series.absoluteSeasonLengths != null) {
                    absoluteLengthsPrefill = series.absoluteSeasonLengths;
                }
                forceAbsolutePrefill = Boolean.TRUE.equals(series.forceAbsoluteNumbering);
            }
        } catch (Exception e) {
            LOG.warn("Failed to load absolute-numbering override for series '{}': {}", seriesTitle, e.getMessage());
        }
        String absoluteLengthsEscaped = absoluteLengthsPrefill.replace("&", "&amp;").replace("<", "&lt;").replace("'", "&#39;");
        String forceAbsoluteChecked = forceAbsolutePrefill ? " checked" : "";
        
         return " <form hx-post='/api/video/manage/series/update' hx-swap='none' class='p-2'>" +
                " <input type='hidden' name='seriesTitle' value='" + seriesTitle + "'>" +
                " <div class='field'><label class='label' style='color: rgba(255,255,255,0.7);'>Series Name (Rename All)</label>" +
                " <div class='control'><input class='input is-dark' type='text' name='newTitle' value='" + seriesTitle + "' " +
                " style='background: rgba(255,255,255,0.05); border-color: rgba(255,255,255,0.1); color: white;'></div>" +
                " <p class='help has-text-grey'>Renaming here will update all " + episodes.size() + " episodes.</p></div>" +
                " <div class='field'><label class='label' style='color: rgba(255,255,255,0.7);'>Poster Path</label>" +
                " <div class='control'><input class='input is-dark' type='text' name='posterPath' value='" + (representative.posterPath != null ? representative.posterPath : "") + "' " +
                " style='background: rgba(255,255,255,0.05); border-color: rgba(255,255,255,0.1); color: white;'></div></div>" +
                " <div class='field'><label class='label' style='color: rgba(255,255,255,0.7);'>Backdrop Path</label>" +
                " <div class='control'><input class='input is-dark' type='text' name='backdropPath' value='" + (representative.backdropPath != null ? representative.backdropPath : "") + "' " +
                " style='background: rgba(255,255,255,0.05); border-color: rgba(255,255,255,0.1); color: white;'></div></div>" +
                " <div class='field'><label class='label' style='color: rgba(255,255,255,0.7);'>Series IMDb ID</label>" +
                " <div class='control'><input class='input is-dark' type='text' name='showImdbId' value='" + (representative.showImdbId != null ? representative.showImdbId : "") + "' " +
                " style='background: rgba(255,255,255,0.05); border-color: rgba(255,255,255,0.1); color: white;' placeholder='e.g. tt0096697'></div>" +
                " <p class='help has-text-grey'>The IMDb ID of this TV series (e.g. tt0096697 for The Simpsons)</p></div>" +
                " <div class='field'><label class='label' style='color: rgba(255,255,255,0.7);'>TVDB ID</label>" +
                " <div class='control'><input class='input is-dark' type='text' name='tvdbId' value='" + tvdbIdPrefill + "' " +
                " style='background: rgba(255,255,255,0.05); border-color: rgba(255,255,255,0.1); color: white;' placeholder='e.g. 76773'></div>" +
                " <p class='help has-text-grey'>The TVDB ID of this TV series (e.g. 76773 for The Simpsons)</p></div>" +
                " <div class='field'><label class='label' style='color: rgba(255,255,255,0.7);'>TMDB ID</label>" +
                " <div class='control'><input class='input is-dark' type='text' name='tmdbId' value='" + tmdbIdPrefill + "' " +
                " style='background: rgba(255,255,255,0.05); border-color: rgba(255,255,255,0.1); color: white;' placeholder='e.g. 456'></div>" +
                " <p class='help has-text-grey'>The TMDB ID of this TV series (e.g. 456 for The Simpsons)</p></div>" +
                " <div class='field'><label class='label' style='color: rgba(255,255,255,0.7);'>Absolute Season Lengths (JSON override)</label>" +
                " <div class='control'><input class='input is-dark' type='text' name='absoluteSeasonLengths' value='" + absoluteLengthsEscaped + "' " +
                " style='background: rgba(255,255,255,0.05); border-color: rgba(255,255,255,0.1); color: white;' placeholder='e.g. {\"1\":25,\"2\":25}'></div>" +
                " <p class='help has-text-grey'>Optional raw JSON mapping season to episode count. Highest precedence when Force is checked. Blank clears the override; invalid JSON is rejected.</p></div>" +
                " <div class='field'><div class='control'><label class='checkbox' style='color: rgba(255,255,255,0.7);'>" +
                " <input type='checkbox' name='forceAbsoluteNumbering' value='true'" + forceAbsoluteChecked + "> Force absolute numbering</label></div>" +
                " <p class='help has-text-grey'>When checked, the JSON above overrides metadata-derived season lengths for bare-numbered files.</p></div>" +
                " <div class='field mt-5'><div class='control'><button class='button is-info is-fullwidth' type='submit'>" +
                " <i class='pi pi-save mr-2'></i> Save Series Changes</button></div></div>" +
                " </form>";
    }

    @POST
    @Path("/update/{id}")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Blocking
    public Response updateVideo(
            @PathParam("id") Long id,
            @FormParam("title") String title,
            @FormParam("seriesTitle") String seriesTitle,
            @FormParam("episodeTitle") String episodeTitle,
            @FormParam("seasonNumber") Integer seasonNumber,
            @FormParam("episodeNumber") Integer episodeNumber,
            @FormParam("type") String type,
            @FormParam("showImdbId") String showImdbId,
            @FormParam("imdbId") String imdbId,
            @FormParam("tmdbId") String tmdbId,
            @FormParam("introStart") Double introStart,
            @FormParam("introEnd") Double introEnd,
            @FormParam("outroStart") Double outroStart,
            @FormParam("outroEnd") Double outroEnd) {

        videoService.updateVideoFields(id, title, seriesTitle, episodeTitle, seasonNumber, episodeNumber, type, showImdbId, imdbId, tmdbId, introStart, introEnd, outroStart, outroEnd);

        return Response.ok("Metadata updated successfully").build();
    }

    // ── Admin: Needs Attention ───────────────────────────────────────────

    @GET
    @Path("/needs-attention")
    @Blocking
    public String getNeedsAttentionPanel() {
        List<Video> problematic = videoQueryService.findVideosNeedingAttention(100);
        
        boolean workerRunning = metadataEnrichmentWorker.isRunning();
        int pendingFailures = metadataEnrichmentWorker.getPendingFailureCount();
        
        return needsAttentionFragment
                .data("videos", problematic)
                .data("workerRunning", workerRunning)
                .data("pendingFailures", pendingFailures)
                .data("totalCount", problematic.size())
                .render();
    }

    @POST
    @Path("/re-enrich/{id}")
    @Blocking
    public Response reEnrichVideo(@PathParam("id") Long id) {
        Video video = videoService.find(id);
        if (video == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity("Video not found")
                    .build();
        }
        
        try {
            videoMetadataService.fetchAndEnrichMetadata(video);
            return Response.ok("Enrichment completed for '" + video.title + "'")
                    .build();
        } catch (Exception e) {
            LOG.error("Manual re-enrichment failed for video {}: {}", id, e.getMessage());
            return Response.serverError()
                    .entity("Enrichment failed: " + e.getMessage())
                    .build();
        }
    }

    /**
     * Save search overrides and trigger enrichment in one step.
     * Lets users correct auto-detected values before the metadata search runs.
     */
    @POST
    @Path("/search-enrich/{id}")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Blocking
    public Response searchAndEnrich(
            @PathParam("id") Long id,
            @FormParam("title") String title,
            @FormParam("seriesTitle") String seriesTitle,
            @FormParam("seasonNumber") Integer seasonNumber,
            @FormParam("episodeNumber") Integer episodeNumber,
            @FormParam("imdbId") String imdbId,
            @FormParam("showImdbId") String showImdbId) {

        Video video;
        try {
            video = videoService.applySearchEnrichOverrides(id, title, seriesTitle, seasonNumber, episodeNumber, imdbId, showImdbId);
        } catch (Exception e) {
            LOG.error("Search-and-enrich failed for video {}: {}", id, e.getMessage());
            return Response.serverError()
                    .entity("Search failed: " + e.getMessage())
                    .build();
        }
        if (video == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity("Video not found")
                    .build();
        }
        LOG.info("Search-and-enrich completed for video {} ('{}')", id, video.title);
        return Response.ok("Search and enrichment completed for '" + video.title + "'")
                .build();
    }

    // ── Verification (side-by-side metadata comparison) ──────────────────────

    @GET
    @Path("/verification")
    @Blocking
    public String getVerificationPanel() {
        List<Video> candidates = videoQueryService.findVideosForVerification(100);
        return verificationFragment
                .data("videos", candidates)
                .data("totalCount", candidates.size())
                .render();
    }

    @GET
    @Path("/verification/preview/{id}")
    @Produces(MediaType.APPLICATION_JSON)
    @Blocking
    public Response getVerificationPreview(@PathParam("id") Long id,
            @QueryParam("titleBlind") boolean titleBlind) {
        Video video = videoService.find(id);
        if (video == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity("{\"error\":\"Video not found\"}")
                    .build();
        }
        VerificationPreview preview = videoMetadataService.previewEnrichment(video, titleBlind);
        try {
            String json = objectMapper.writeValueAsString(preview);
            return Response.ok(json).build();
        } catch (Exception e) {
            LOG.error("Failed to serialize verification preview for {}: {}", id, e.getMessage());
            return Response.serverError()
                    .entity("{\"error\":\"Failed to generate preview\"}")
                    .build();
        }
    }

    @POST
    @Path("/verification/apply/{id}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @Blocking
    public Response applyVerification(@PathParam("id") Long id, Map<String, Object> selections) {
        Video video;
        try {
            video = videoService.applyVerificationSelections(id, selections);
        } catch (Exception e) {
            LOG.error("Failed to apply verification for {}: {}", id, e.getMessage());
            return Response.serverError()
                    .entity("{\"error\":\"" + e.getMessage().replace("\"", "\\\"") + "\"}")
                    .build();
        }
        if (video == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity("{\"error\":\"Video not found\"}")
                    .build();
        }
        LOG.info("Applied verification selections for video {} ({})", id, video.title);
        return Response.ok("{\"status\":\"ok\"}").build();
    }

    @POST
    @Path("/verification/re-enrich-blind/{id}")
    @Blocking
    public Response reEnrichBlind(@PathParam("id") Long id) {
        Video video = videoService.find(id);
        if (video == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity("Video not found")
                    .build();
        }
        try {
            videoMetadataService.fetchAndEnrichMetadata(video);
            return Response.ok("Blind enrichment completed for '" + video.title + "'").build();
        } catch (Exception e) {
            LOG.error("Blind re-enrichment failed for video {}: {}", id, e.getMessage());
            return Response.serverError()
                    .entity("Enrichment failed: " + e.getMessage())
                    .build();
        }
    }

    @POST
    @Path("/rename-series")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Blocking
    public Response renameSeries(
            @FormParam("oldTitle") String oldTitle,
            @FormParam("newTitle") String newTitle) {
        
        videoService.updateSeriesTitle(oldTitle, newTitle);
        return Response.ok("Series renamed successfully").build();
    }

    @POST
    @Path("/mass-rename-episodes")
    @Consumes(MediaType.APPLICATION_JSON)
    @Blocking
    public Response massRenameEpisodes(List<Models.DTOs.EpisodeRenameDTO> renameRequests) {
        try {
            for (Models.DTOs.EpisodeRenameDTO req : renameRequests) {
                if (req.id != null && req.newTitle != null && !req.newTitle.isBlank()) {
                    videoService.updateTitle(req.id, req.newTitle);
                }
            }
            return Response.ok("Episodes renamed successfully").build();
        } catch (Exception e) {
            LOG.error("Error batch renaming episodes", e);
            return Response.serverError().entity("Failed to rename episodes").build();
        }
    }
    
    @POST
    @Path("/unlock-title/{id}")
    @Blocking
    public Response unlockTitle(@PathParam("id") Long id,
            @QueryParam("unlockSeriesTitle") boolean unlockSeriesTitle,
            @QueryParam("unlockTitle") boolean unlockTitle) {
        try {
            videoService.clearManualOverrideFlags(id, unlockSeriesTitle, unlockTitle);
            return Response.ok("Override flags cleared successfully").build();
        } catch (Exception e) {
            LOG.error("Error clearing override flags", e);
            return Response.serverError().entity("Failed to clear override flags").build();
        }
    }
    
    // ── Conversion endpoints ────────────────────────────────────────────────

    @POST
    @Path("/convert/batch")
    @Consumes(MediaType.APPLICATION_JSON)
    @Blocking
    @Produces(MediaType.APPLICATION_JSON)
    public Response startBatchConversion(BatchConversionRequest request) {
        if (request == null || request.videoIds == null || request.videoIds.isEmpty()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("{\"error\":\"No video IDs provided\"}")
                    .build();
        }

        VideoConversionService.ConversionOptions options = null;
        if (request.options != null) {
            options = new VideoConversionService.ConversionOptions(
                request.options.targetCodec != null ? request.options.targetCodec : "h264",
                request.options.targetContainer != null ? request.options.targetContainer : "mp4",
                request.options.targetAudioCodec != null ? request.options.targetAudioCodec : "aac",
                request.options.crf != null ? request.options.crf : 23,
                request.options.preset,
                request.options.maxHeight != null ? request.options.maxHeight : 1080
            );
        }

        String batchId = videoConversionService.startBatchConversion(request.videoIds, options);
        if (batchId == null) {
            return Response.serverError()
                    .entity("{\"error\":\"Could not start batch conversion\"}")
                    .build();
        }

        VideoConversionService.BatchInfo batch = videoConversionService.getBatchInfo(batchId);
        int total = batch != null ? batch.total() : request.videoIds.size();
        return Response.ok(String.format(
                "{\"batchId\":\"%s\",\"total\":%d}",
                batchId, total
        )).build();
    }

    @GET
    @Path("/convert/batch/status/{batchId}")
    @Produces(MediaType.APPLICATION_JSON)
    @Blocking
    public Response getBatchStatus(@PathParam("batchId") String batchId) {
        VideoConversionService.BatchInfo batch = videoConversionService.getBatchInfo(batchId);
        if (batch == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity("{\"error\":\"Batch not found\"}")
                    .build();
        }

        String json = String.format(
                "{\"batchId\":\"%s\",\"total\":%d,\"completed\":%d,\"failed\":%d,\"remaining\":%d,\"active\":%b,\"cancelled\":%b}",
                batch.batchId, batch.total(), batch.completed.get(), batch.failed.get(),
                batch.remaining(), batch.active, batch.cancelled
        );
        return Response.ok(json).build();
    }

    @POST
    @Path("/convert/{id}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Blocking
    @Produces(MediaType.APPLICATION_JSON)
    public Response startConversion(@PathParam("id") Long id, ConversionOptionsRequest body) {
        Video video = videoService.find(id);
        if (video == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity("{\"error\":\"Video not found\"}")
                    .build();
        }

        VideoConversionService.ConversionOptions options = VideoConversionService.ConversionOptions.defaults();
        if (body != null) {
            options = new VideoConversionService.ConversionOptions(
                body.targetCodec != null ? body.targetCodec : "h264",
                body.targetContainer != null ? body.targetContainer : "mp4",
                body.targetAudioCodec != null ? body.targetAudioCodec : "aac",
                body.crf != null ? body.crf : 23,
                body.preset,
                body.maxHeight != null ? body.maxHeight : 1080
            );
        }

        VideoConversionService.ConversionJob job = videoConversionService.startConversion(id, options);
        if (job == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity("{\"error\":\"Could not start conversion\"}")
                    .build();
        }

        String json = String.format(
                "{\"jobId\":\"%s\",\"videoId\":%d,\"status\":\"%s\"}",
                job.jobId, job.videoId, job.status.name()
        );
        return Response.ok(json).build();
    }

    @GET
    @Path("/convert/status/{jobId}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getConversionStatus(@PathParam("jobId") String jobId) {
        VideoConversionService.ConversionJob job = videoConversionService.getJobStatus(jobId);
        if (job == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity("{\"error\":\"Job not found\"}")
                    .build();
        }

        String errorMsg = job.errorMessage != null ? job.errorMessage.replace("\"", "\\\"") : "";
        String json = String.format(
                "{\"jobId\":\"%s\",\"videoId\":%d,\"status\":\"%s\",\"progressPercent\":%d,\"message\":\"%s\",\"errorMessage\":\"%s\"}",
                job.jobId, job.videoId, job.status.name(),
                job.progressPercent, job.message, errorMsg
        );
        return Response.ok(json).build();
    }

    @POST
    @Path("/unlock-series")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Blocking
    public Response unlockSeries(
            @FormParam("seriesTitle") String seriesTitle,
            @QueryParam("unlockSeriesTitle") boolean unlockSeriesTitle,
            @QueryParam("unlockTitle") boolean unlockTitle) {
        try {
            videoService.clearSeriesManualOverrideFlags(seriesTitle, unlockSeriesTitle, unlockTitle);
            return Response.ok("Override flags cleared for series").build();
        } catch (Exception e) {
            LOG.error("Error clearing series override flags", e);
            return Response.serverError().entity("Failed to clear override flags").build();
        }
    }

    // ── Request DTOs ─────────────────────────────────────────────────────

    public static class ConversionOptionsRequest {
        public String targetCodec;
        public String targetContainer;
        public String targetAudioCodec;
        public Integer crf;
        public String preset;
        public Integer maxHeight;
    }

    public static class BatchConversionRequest {
        public List<Long> videoIds;
        public ConversionOptionsRequest options;
    }
}
