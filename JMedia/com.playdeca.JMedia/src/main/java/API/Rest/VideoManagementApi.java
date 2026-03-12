package API.Rest;

import Services.VideoService;
import Services.VideoImportService;
import Services.SettingsService;
import Models.Video;
import Models.DTOs.TvShowDTO;
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
    VideoImportService videoImportService;

    @Inject
    SettingsService settingsService;

    @Inject
    com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    @Inject @io.quarkus.qute.Location("manageFragment.html")
    Template manageFragment;

    @Inject @io.quarkus.qute.Location("editVideoFragment.html")
    Template editVideoFragment;

    @Inject @io.quarkus.qute.Location("seriesEpisodesFragment.html")
    Template seriesEpisodesFragment;

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
    public String getSeriesEpisodes(@PathParam("seriesTitle") String seriesTitle) {
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
    }

    @POST
    @Path("/series/update")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Blocking
    public Response updateSeries(
            @FormParam("seriesTitle") String seriesTitle,
            @FormParam("newTitle") String newTitle,
            @FormParam("posterPath") String posterPath,
            @FormParam("backdropPath") String backdropPath) {
        
        if (newTitle != null && !newTitle.isBlank() && !newTitle.equals(seriesTitle)) {
            videoService.updateSeriesTitle(seriesTitle, newTitle);
            seriesTitle = newTitle; // Use new title for metadata update
        }
        
        videoService.updateSeriesMetadata(seriesTitle, posterPath, backdropPath);
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
        for (java.nio.file.Path dir : parentDirs) {
            videoImportService.scan(dir, false);
        }

        return Response.ok("Rescan started for series directories").build();
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
            @FormParam("type") String type) {
        
        videoService.updateMetadata(id, title, seriesTitle, episodeTitle, seasonNumber, episodeNumber, type);
        return Response.ok("Metadata updated successfully").build();
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
}
