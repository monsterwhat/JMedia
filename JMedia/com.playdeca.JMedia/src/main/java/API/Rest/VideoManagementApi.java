package API.Rest;

import Services.VideoService;
import Services.VideoImportService;
import Services.SettingsService;
import Services.VideoConversionService;
import Services.VideoMetadataService;
import Services.MetadataEnrichmentWorker;
import Models.Video;
import Models.DTOs.TvShowDTO;
import Models.DTOs.VerificationPreview;
import io.quarkus.qute.Template;
import io.smallrye.common.annotation.Blocking;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
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

    @GET
    @Blocking
    public String getManagePanel(@QueryParam("search") String search, @QueryParam("type") String type) {
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

        List<TvShowDTO> shows = null;
        List<Video> videosToDisplay = null;
        int totalCount = filteredVideos.size();

        if ("episode".equalsIgnoreCase(type)) {
            // Group by series
            Map<String, List<Video>> grouped = filteredVideos.stream()
                    .filter(v -> v.seriesTitle != null)
                    .collect(Collectors.groupingBy(v -> v.seriesTitle));
            
            shows = grouped.entrySet().stream()
                    .map(entry -> new TvShowDTO(entry.getKey(), entry.getValue()))
                    .sorted((a, b) -> a.seriesTitle.compareToIgnoreCase(b.seriesTitle))
                    .collect(Collectors.toList());
            totalCount = shows.size();
        } else {
            // Limit results for management panel to avoid crashing UI
            int limit = 100;
            videosToDisplay = filteredVideos.stream().limit(limit).collect(Collectors.toList());
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
    @Transactional
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

            return seriesEpisodesFragment
                    .data("seriesTitle", seriesTitle)
                    .data("episodes", episodes)
                    .data("jsonEpisodes", jsonEpisodes)
                    .data("posterPath", representative.posterPath)
                    .data("backdropPath", representative.backdropPath)
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
            @FormParam("showImdbId") String showImdbId) {
        
        if (newTitle != null && !newTitle.isBlank() && !newTitle.equals(seriesTitle)) {
            videoService.updateSeriesTitle(seriesTitle, newTitle);
            seriesTitle = newTitle; // Use new title for metadata update
        }
        
        videoService.updateSeriesMetadata(seriesTitle, posterPath, backdropPath, showImdbId);
        return Response.ok("Series updated successfully").build();
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
            List<Models.Video> videos = videoImportService.scanAndCreate(dir, true);
            totalCreated += videos.size();
        }

        return Response.ok("Rescan completed. Created/updated " + totalCreated + " videos.").build();
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
            List<Models.Video> videos = videoImportService.scanAndCreate(dir, true);
            totalCreated += videos.size();
        }

        return Response.ok("Force refresh completed. Re-imported " + totalCreated + " videos.").build();
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
    @Transactional
    public String getEditSeriesFragment(@PathParam("seriesTitle") String seriesTitle) {
        List<Video> episodes = videoService.findEpisodesForSeries(seriesTitle);
        if (episodes.isEmpty()) return "<div class='notification is-danger'>Series not found</div>";
        
        Video representative = episodes.get(0);
        
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
                " <div class='field mt-5'><div class='control'><button class='button is-info is-fullwidth' type='submit'>" +
                " <i class='pi pi-save mr-2'></i> Save Series Changes</button></div></div>" +
                " </form>";
    }

    @POST
    @Path("/update/{id}")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Transactional
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
        
        videoService.updateMetadata(id, title, seriesTitle, episodeTitle, seasonNumber, episodeNumber, type, showImdbId, imdbId);
        
        // Also update TMDb ID and intro/outro timestamps if provided
        if (tmdbId != null || introStart != null || introEnd != null || outroStart != null || outroEnd != null) {
            Video video = videoService.find(id);
            if (video != null) {
                if (tmdbId != null && !tmdbId.isBlank()) video.tmdbId = tmdbId;
                if (introStart != null) video.introStart = introStart;
                if (introEnd != null) video.introEnd = introEnd;
                if (outroStart != null) video.outroStart = outroStart;
                if (outroEnd != null) video.outroEnd = outroEnd;
                video.dateModified = java.time.LocalDateTime.now();
                video.persist();
            }
        }
        
        return Response.ok("Metadata updated successfully").build();
    }

    // ── Admin: Needs Attention ───────────────────────────────────────────

    @GET
    @Path("/needs-attention")
    @Blocking
    public String getNeedsAttentionPanel() {
        List<Video> problematic = videoService.findVideosNeedingAttention(100);
        
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
    @Transactional
    public Response searchAndEnrich(
            @PathParam("id") Long id,
            @FormParam("title") String title,
            @FormParam("seriesTitle") String seriesTitle,
            @FormParam("seasonNumber") Integer seasonNumber,
            @FormParam("episodeNumber") Integer episodeNumber,
            @FormParam("imdbId") String imdbId,
            @FormParam("showImdbId") String showImdbId) {
        
        Video video = videoService.find(id);
        if (video == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity("Video not found")
                    .build();
        }

        // Apply overrides — only update non-null, non-blank values the user explicitly set
        if (title != null && !title.isBlank()) video.title = title;
        if (seriesTitle != null && !seriesTitle.isBlank()) video.seriesTitle = seriesTitle;
        if (seasonNumber != null && seasonNumber > 0) video.seasonNumber = seasonNumber;
        if (episodeNumber != null && episodeNumber > 0) video.episodeNumber = episodeNumber;
        if (imdbId != null && !imdbId.isBlank()) video.imdbId = imdbId;
        if (showImdbId != null && !showImdbId.isBlank()) video.showImdbId = showImdbId;
        video.dateModified = java.time.LocalDateTime.now();

        try {
            videoMetadataService.fetchAndEnrichMetadata(video);
            LOG.info("Search-and-enrich completed for video {} ('{}')", id, video.title);
            return Response.ok("Search and enrichment completed for '" + video.title + "'")
                    .build();
        } catch (Exception e) {
            LOG.error("Search-and-enrich failed for video {}: {}", id, e.getMessage());
            return Response.serverError()
                    .entity("Search failed: " + e.getMessage())
                    .build();
        }
    }

    // ── Verification (side-by-side metadata comparison) ──────────────────────

    @GET
    @Path("/verification")
    @Blocking
    public String getVerificationPanel() {
        List<Video> candidates = videoService.findVideosForVerification(100);
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
    @Transactional
    public Response applyVerification(@PathParam("id") Long id, Map<String, Object> selections) {
        Video video = videoService.find(id);
        if (video == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity("{\"error\":\"Video not found\"}")
                    .build();
        }
        try {
            if (selections.containsKey("title")) {
                video.title = (String) selections.get("title");
                if (selections.containsKey("_titleManual"))
                    video.titleManuallyEdited = Boolean.TRUE.equals(selections.get("_titleManual"));
            }
            if (selections.containsKey("seriesTitle")) {
                video.seriesTitle = (String) selections.get("seriesTitle");
                if (selections.containsKey("_seriesTitleManual"))
                    video.seriesTitleManuallyEdited = Boolean.TRUE.equals(selections.get("_seriesTitleManual"));
            }
            if (selections.containsKey("episodeTitle")) {
                video.episodeTitle = (String) selections.get("episodeTitle");
            }
            if (selections.containsKey("seasonNumber")) {
                Object val = selections.get("seasonNumber");
                video.seasonNumber = val instanceof Number ? ((Number) val).intValue() : null;
            }
            if (selections.containsKey("episodeNumber")) {
                Object val = selections.get("episodeNumber");
                video.episodeNumber = val instanceof Number ? ((Number) val).intValue() : null;
            }
            if (selections.containsKey("imdbId")) {
                video.imdbId = (String) selections.get("imdbId");
            }
            if (selections.containsKey("showImdbId")) {
                video.showImdbId = (String) selections.get("showImdbId");
            }
            if (selections.containsKey("tmdbId")) {
                video.tmdbId = (String) selections.get("tmdbId");
            }
            video.dateModified = java.time.LocalDateTime.now();
            video.persist();
            LOG.info("Applied verification selections for video {} ({})", id, video.title);
            return Response.ok("{\"status\":\"ok\"}").build();
        } catch (Exception e) {
            LOG.error("Failed to apply verification for {}: {}", id, e.getMessage());
            return Response.serverError()
                    .entity("{\"error\":\"" + e.getMessage().replace("\"", "\\\"") + "\"}")
                    .build();
        }
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
    public Response startBatchConversion(List<Long> videoIds) {
        if (videoIds == null || videoIds.isEmpty()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("{\"error\":\"No video IDs provided\"}")
                    .build();
        }

        String batchId = videoConversionService.startBatchConversion(videoIds);
        if (batchId == null) {
            return Response.serverError()
                    .entity("{\"error\":\"Could not start batch conversion\"}")
                    .build();
        }

        VideoConversionService.BatchInfo batch = videoConversionService.getBatchInfo(batchId);
        int total = batch != null ? batch.total() : videoIds.size();
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
    @Blocking
    @Produces(MediaType.APPLICATION_JSON)
    public Response startConversion(@PathParam("id") Long id) {
        Video video = videoService.find(id);
        if (video == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity("{\"error\":\"Video not found\"}")
                    .build();
        }

        VideoConversionService.ConversionJob job = videoConversionService.startConversion(id);
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
}
