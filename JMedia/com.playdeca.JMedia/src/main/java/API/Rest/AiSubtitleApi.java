package API.Rest;

import Models.SubtitleTrack;
import Models.Video;
import Services.AiSubtitleJobService;
import Services.AiSubtitleJobService.AiSubtitleJob;
import Services.VideoService;
import Services.ParakeetService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Path("/api/ai-subtitles")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class AiSubtitleApi {

    private static final Logger LOG = LoggerFactory.getLogger(AiSubtitleApi.class);

    @Inject
    VideoService videoService;

    @Inject
    ParakeetService parakeetService;

    @Inject
    AiSubtitleJobService jobService;

    @GET
    @Path("/videos")
    @Transactional
    public Response listVideos(@QueryParam("page") @DefaultValue("0") int page,
                               @QueryParam("limit") @DefaultValue("50") int limit,
                               @QueryParam("search") String search,
                               @QueryParam("filter") @DefaultValue("all") String filter) {
        try {
            List<Video> videos = videoService.findAllPaginated(page, limit, search, filter);
            long total = videoService.countAllPaginated(search, filter);

            List<Map<String, Object>> videoList = new ArrayList<>();
            for (Video v : videos) {
                Map<String, Object> item = new HashMap<>();
                item.put("id", v.id);
                item.put("title", v.title != null ? v.title : v.filename);
                item.put("filename", v.filename);
                item.put("type", v.type);
                item.put("hasAiSubtitles", hasAiSubtitles(v));
                item.put("hasSubtitles", v.hasSubtitles);
                item.put("thumbnailPath", v.thumbnailPath);
                videoList.add(item);
            }

            Map<String, Object> response = new HashMap<>();
            response.put("videos", videoList);
            response.put("total", total);
            response.put("page", page);
            response.put("limit", limit);
            response.put("parakeetAvailable", parakeetService.isParakeetAvailable());

            return Response.ok(response).build();
        } catch (Exception e) {
            LOG.error("Error listing videos for AI subtitles", e);
            return Response.serverError().entity(Map.of("error", e.getMessage())).build();
        }
    }

    @GET
    @Path("/shows")
    @Transactional
    public Response listShows(@QueryParam("search") String search,
                              @QueryParam("filter") @DefaultValue("all") String filter) {
        try {
            List<Object[]> showData = videoService.findAllShowsWithAiStats(search, filter);
            List<Map<String, Object>> showList = new ArrayList<>();

            for (Object[] row : showData) {
                String seriesTitle = (String) row[0];
                long totalEpisodes = ((Number) row[1]).longValue();
                long aiEpisodes = ((Number) row[2]).longValue();
                long hasSubs = ((Number) row[3]).longValue();

                Map<String, Object> item = new HashMap<>();
                item.put("seriesTitle", seriesTitle);
                item.put("totalEpisodes", totalEpisodes);
                item.put("aiEpisodes", aiEpisodes);
                item.put("hasSubtitles", hasSubs);
                item.put("allHaveAi", aiEpisodes >= totalEpisodes);
                showList.add(item);
            }

            Map<String, Object> response = new HashMap<>();
            response.put("shows", showList);
            response.put("total", showList.size());
            response.put("parakeetAvailable", parakeetService.isParakeetAvailable());

            return Response.ok(response).build();
        } catch (Exception e) {
            LOG.error("Error listing shows for AI subtitles", e);
            return Response.serverError().entity(Map.of("error", e.getMessage())).build();
        }
    }

    @GET
    @Path("/shows/{seriesTitle}/episodes")
    @Transactional
    public Response listShowEpisodes(@PathParam("seriesTitle") String seriesTitle,
                                     @QueryParam("page") @DefaultValue("0") int page,
                                     @QueryParam("limit") @DefaultValue("100") int limit,
                                     @QueryParam("search") String search,
                                     @QueryParam("filter") @DefaultValue("all") String filter) {
        try {
            List<Video> episodes = videoService.findEpisodesForShow(seriesTitle, page, limit, search, filter);
            long total = videoService.countEpisodesForShow(seriesTitle, search, filter);

            List<Map<String, Object>> episodeList = new ArrayList<>();
            for (Video v : episodes) {
                Map<String, Object> item = new HashMap<>();
                item.put("id", v.id);
                item.put("title", v.title != null ? v.title : v.filename);
                item.put("episodeTitle", v.episodeTitle);
                item.put("filename", v.filename);
                item.put("seasonNumber", v.seasonNumber);
                item.put("episodeNumber", v.episodeNumber);
                item.put("hasAiSubtitles", hasAiSubtitles(v));
                item.put("hasSubtitles", v.hasSubtitles);
                item.put("thumbnailPath", v.thumbnailPath);
                episodeList.add(item);
            }

            Map<String, Object> response = new HashMap<>();
            response.put("episodes", episodeList);
            response.put("total", total);
            response.put("page", page);
            response.put("limit", limit);
            response.put("seriesTitle", seriesTitle);

            return Response.ok(response).build();
        } catch (Exception e) {
            LOG.error("Error listing episodes for show '{}'", seriesTitle, e);
            return Response.serverError().entity(Map.of("error", e.getMessage())).build();
        }
    }

    @POST
    @Path("/generate")
    public Response generateSubtitles(Map<String, Object> request) {
        if (!parakeetService.isParakeetAvailable()) {
            return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                    .entity(Map.of("error", "Parakeet is not available on this server")).build();
        }

        @SuppressWarnings("unchecked")
        List<Integer> videoIdsRaw = (List<Integer>) request.get("videoIds");
        String language = (String) request.getOrDefault("language", "en");

        if (videoIdsRaw == null || videoIdsRaw.isEmpty()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "No video IDs provided")).build();
        }

        List<Long> videoIds = videoIdsRaw.stream().map(Integer::longValue).collect(Collectors.toList());

        int jobId = jobService.startBatch(videoIds, language);

        Map<String, Object> response = new HashMap<>();
        response.put("jobId", jobId);
        response.put("message", "Batch generation started for " + videoIds.size() + " videos");
        response.put("total", videoIds.size());

        return Response.ok(response).build();
    }

    @GET
    @Path("/status")
    public Response getStatus() {
        AiSubtitleJob job = jobService.getCurrentJob();

        Map<String, Object> response = new HashMap<>();
        if (job == null) {
            response.put("running", false);
            response.put("status", "idle");
            return Response.ok(response).build();
        }

        response.put("running", "running".equals(job.status));
        response.put("jobId", job.id);
        response.put("status", job.status);
        response.put("total", job.videoIds.size());
        response.put("completed", job.completedCount);
        response.put("failed", job.failedCount);
        response.put("currentVideoIndex", job.currentVideoIndex);
        response.put("currentVideoId", job.currentVideoId);
        response.put("currentVideoTitle", job.currentVideoTitle);
        response.put("currentVideoSeries", job.currentVideoSeries);
        response.put("currentVideoSeason", job.currentVideoSeason);
        response.put("overallProgress", Math.round(job.overallProgress * 10.0) / 10.0);
        response.put("errors", List.copyOf(job.errors));
        response.put("elapsed", System.currentTimeMillis() - job.startTime);

        return Response.ok(response).build();
    }

    @GET
    @Path("/completed")
    @Transactional
    public Response getCompleted(@QueryParam("page") @DefaultValue("0") int page,
                                 @QueryParam("limit") @DefaultValue("50") int limit) {
        try {
            List<Video> videos = videoService.findVideosWithAiSubtitles(page, limit);
            long total = videoService.countVideosWithAiSubtitles();

            List<Map<String, Object>> videoList = new ArrayList<>();
            for (Video v : videos) {
                Map<String, Object> item = new HashMap<>();
                item.put("id", v.id);
                item.put("title", v.title != null ? v.title : v.filename);
                item.put("filename", v.filename);
                item.put("thumbnailPath", v.thumbnailPath);

                // Query AI subtitle tracks separately to avoid lazy init
                List<SubtitleTrack> aiTracks = SubtitleTrack.list("video.id = ?1 and isAiGenerated = ?2", v.id, true);
                List<Map<String, Object>> tracks = new ArrayList<>();
                for (SubtitleTrack st : aiTracks) {
                    Map<String, Object> trackInfo = new HashMap<>();
                    trackInfo.put("id", st.id);
                    trackInfo.put("languageCode", st.languageCode);
                    trackInfo.put("languageName", st.languageName);
                    trackInfo.put("filename", st.filename);
                    trackInfo.put("format", st.format);
                    tracks.add(trackInfo);
                }
                item.put("aiTracks", tracks);
                videoList.add(item);
            }

            Map<String, Object> response = new HashMap<>();
            response.put("videos", videoList);
            response.put("total", total);
            response.put("page", page);
            response.put("limit", limit);

            return Response.ok(response).build();
        } catch (Exception e) {
            LOG.error("Error listing completed AI subtitles", e);
            return Response.serverError().entity(Map.of("error", e.getMessage())).build();
        }
    }

    @DELETE
    @Path("/track/{trackId}")
    @Transactional
    public Response deleteAiTrack(@PathParam("trackId") Long trackId) {
        try {
            SubtitleTrack track = SubtitleTrack.findById(trackId);
            if (track == null) {
                return Response.status(Response.Status.NOT_FOUND)
                        .entity(Map.of("error", "Subtitle track not found")).build();
            }

            // Delete the physical file if it exists
            if (track.fullPath != null) {
                try {
                    Files.deleteIfExists(Paths.get(track.fullPath));
                } catch (Exception e) {
                    LOG.warn("Could not delete subtitle file: " + track.fullPath, e);
                }
            }

            // Remove from video's track list
            if (track.video != null && track.video.subtitleTracks != null) {
                track.video.subtitleTracks.remove(track);
                track.video.persist();
            }

            track.delete();

            return Response.ok(Map.of("success", true, "message", "Subtitle track deleted")).build();
        } catch (Exception e) {
            LOG.error("Error deleting AI subtitle track", e);
            return Response.serverError().entity(Map.of("error", e.getMessage())).build();
        }
    }

    @POST
    @Path("/cancel")
    public Response cancelGeneration() {
        jobService.cancelCurrentJob();
        return Response.ok(Map.of("success", true, "message", "Generation cancelled")).build();
    }

    @GET
    @Path("/languages")
    public Response getLanguages() {
        Map<String, String> languages = ParakeetService.getSupportedLanguages();
        List<Map<String, String>> result = new ArrayList<>();
        for (Map.Entry<String, String> entry : languages.entrySet()) {
            Map<String, String> lang = new HashMap<>();
            lang.put("code", entry.getKey());
            lang.put("name", entry.getValue());
            result.add(lang);
        }
        return Response.ok(result).build();
    }

    private boolean hasAiSubtitles(Video video) {
        if (video == null || video.id == null) return false;
        return SubtitleTrack.count("video.id = ?1 and isAiGenerated = ?2", video.id, true) > 0;
    }
}