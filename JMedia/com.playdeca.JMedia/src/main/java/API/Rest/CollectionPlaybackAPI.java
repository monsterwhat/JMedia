package API.Rest;

import API.ApiResponse;
import Controllers.VideoController;
import Controllers.VideoQueueController;
import Models.CollectionWatchProgress;
import Models.ProfileSessionState;
import Services.CollectionService;
import Services.CollectionWatchProgressService;
import Services.ProfileSessionStateService;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Path("/api/collections")
@Produces(MediaType.APPLICATION_JSON)
public class CollectionPlaybackAPI {

    @Inject
    CollectionService collectionService;

    @Inject
    CollectionWatchProgressService progressService;

    @Inject
    ProfileSessionStateService sessionStateService;

    @Inject
    VideoQueueController videoQueueController;

    @Inject
    VideoController videoController;

    @POST
    @Path("/{id}/play")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Transactional
    public Response playCollection(
            @PathParam("id") Long collectionId,
            @FormParam("startIndex") @DefaultValue("0") int startIndex) {
        var entries = collectionService.getEntries(collectionId);
        if (entries.isEmpty()) {
            return Response.ok(ApiResponse.error("Collection is empty")).build();
        }

        List<Long> videoIds = new ArrayList<>();
        for (var entry : entries) {
            if (entry.video != null) videoIds.add(entry.video.id);
        }
        if (videoIds.isEmpty()) {
            return Response.ok(ApiResponse.error("No videos in collection")).build();
        }

        if (startIndex < 0 || startIndex >= videoIds.size()) startIndex = 0;

        ProfileSessionState session = sessionStateService.getOrCreate();
        if (session == null) {
            return Response.ok(ApiResponse.error("No active profile")).build();
        }

        videoQueueController.clear(session);
        videoQueueController.populateCue(session, videoIds);
        session.cueIndex = startIndex;
        session.currentVideoId = videoIds.get(startIndex);
        session.currentTime = 0;
        session.playing = true;
        session.collectionId = collectionId;
        sessionStateService.save(session);

        progressService.updateProgress(collectionId, videoIds.get(startIndex), startIndex,
                videoIds.size(), startIndex);

        videoController.selectVideo(videoIds.get(startIndex), 0.0);
        videoController.togglePlay();

        return Response.ok(ApiResponse.success(Map.of(
            "videoId", videoIds.get(startIndex),
            "total", videoIds.size(),
            "startIndex", startIndex
        ))).build();
    }

    @POST
    @Path("/{id}/progress")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Transactional
    public Response updateProgress(
            @PathParam("id") Long collectionId,
            @FormParam("videoId") Long videoId,
            @FormParam("entryIndex") @DefaultValue("0") int entryIndex,
            @FormParam("totalEntries") @DefaultValue("0") int totalEntries,
            @FormParam("completedEntries") @DefaultValue("0") int completedEntries) {
        progressService.updateProgress(collectionId, videoId, entryIndex,
                totalEntries, completedEntries);
        return Response.ok(ApiResponse.success("Progress updated")).build();
    }

    @GET
    @Path("/{id}/progress")
    public Response getProgress(@PathParam("id") Long collectionId) {
        var c = collectionService.getCollection(collectionId);
        if (c == null) return Response.status(404).entity(ApiResponse.error("Collection not found")).build();
        var p = progressService.get(c);
        if (p == null) return Response.ok(ApiResponse.success(Map.of())).build();
        return Response.ok(ApiResponse.success(Map.of(
            "lastVideoId", p.lastVideoId,
            "lastEntryIndex", p.lastEntryIndex,
            "totalEntries", p.totalEntries,
            "completedEntries", p.completedEntries,
            "progress", p.progress
        ))).build();
    }

    @GET
    @Path("/progress/in-progress")
    public Response getInProgressCollections() {
        List<CollectionWatchProgress> list = progressService.getInProgress();
        return Response.ok(ApiResponse.success(list)).build();
    }
}
