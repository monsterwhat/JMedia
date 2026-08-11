package API.Rest;

import Controllers.VideoController;
import Models.Video.ProfileSessionState;
import Models.Video.Video;
import Models.Video.VideoState;
import Services.VideoStateService;
import Services.ProfileSessionStateService;
import io.smallrye.common.annotation.Blocking;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.concurrent.ExecutorService;

@Path("/api/video/playback")
@Produces(MediaType.APPLICATION_JSON)
public class VideoPlaybackAPI {

    @Inject
    private VideoController videoController;

    @Inject
    private VideoStateService videoStateService;

    @Inject
    private ProfileSessionStateService profileSessionStateService;
    
    @Inject
    Services.VideoService videoService;
    
    @Inject
    Services.VideoMetadataService videoMetadataService;
    
    @Inject
    ExecutorService executor;
    
    @Inject
    API.WS.VideoSocket videoSocket;

    @POST
    @Path("/toggle")
    @Blocking
    public Response togglePlay() {
        try {
            // togglePlay() already broadcasts the authoritative state (playing=true/false)
            // to every video WS session; a redundant broadcastCommand("toggle-play") would
            // make clients apply the state AND toggle again, net-undoing the pause/play.
            videoController.togglePlay();
            return Response.ok("{\"success\":true,\"message\":\"Playback toggled\"}").build();
        } catch (Exception e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                       .entity("{\"success\":false,\"error\":\"" + e.getMessage() + "\"}").build();
        }
    }

    @POST
    @Path("/play/{videoId}")
    @Blocking
    public Response playVideo(@PathParam("videoId") Long videoId, @QueryParam("startTime") Double startTime) {
        try {
            videoController.selectVideo(videoId, startTime);
            
            // Check if we need to enrich with IntroDB data on-demand
            executor.submit(() -> {
                io.quarkus.arc.ManagedContext requestContext = io.quarkus.arc.Arc.container().requestContext();
                if (!requestContext.isActive()) {
                    requestContext.activate();
                }
                try {
                    Models.Video.Video video = videoService.findById(videoId);
                    if (video != null && "episode".equalsIgnoreCase(video.type)) {
                        // If intro data is missing, try to fetch it now
                        if (video.introStart == null) {
                            System.out.println("Triggering on-demand IntroDB fetch for video: " + video.title);
                            videoMetadataService.enrichVideoWithIntroData(video);
                        }
                    }
                } catch (Exception e) {
                    System.err.println("Error in on-demand IntroDB fetch: " + e.getMessage());
                } finally {
                    if (requestContext.isActive()) {
                        requestContext.deactivate();
                    }
                }
            });
            
            return Response.ok("{\"success\":true,\"message\":\"Video playing\"}").build();
        } catch (Exception e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                       .entity("{\"success\":false,\"error\":\"" + e.getMessage() + "\"}").build();
        }
    }

    @POST
    @Path("/play")
    @Blocking
    public Response play() {
        try {
            var currentState = profileSessionStateService.getOrCreate();
            if (currentState != null && currentState.currentVideoId != null) {
                videoController.togglePlay();
                return Response.ok("{\"success\":true,\"message\":\"Resumed playback\"}").build();
            } else {
                return Response.status(Response.Status.BAD_REQUEST)
                               .entity("{\"success\":false,\"error\":\"No video selected\"}").build();
            }
        } catch (Exception e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                       .entity("{\"success\":false,\"error\":\"" + e.getMessage() + "\"}").build();
        }
    }

    @POST
    @Path("/pause")
    @Blocking
    public Response pauseVideo() {
        try {
            videoController.togglePlay(); // toggle will pause if playing
            return Response.ok("{\"success\":true,\"message\":\"Video paused\"}").build();
        } catch (Exception e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                       .entity("{\"success\":false,\"error\":\"" + e.getMessage() + "\"}").build();
        }
    }

    @POST
    @Path("/next")
    @Blocking
    public Response nextVideo() {
        try {
            videoController.next();
            videoSocket.broadcastCommand("next", new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode());
            return Response.ok("{\"success\":true,\"message\":\"Next video\"}").build();
        } catch (Exception e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                       .entity("{\"success\":false,\"error\":\"" + e.getMessage() + "\"}").build();
        }
    }

    @POST
    @Path("/previous")
    @Blocking
    public Response previousVideo() {
        try {
            videoController.previous();
            videoSocket.broadcastCommand("previous", new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode());
            return Response.ok("{\"success\":true,\"message\":\"Previous video\"}").build();
        } catch (Exception e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                       .entity("{\"success\":false,\"error\":\"" + e.getMessage() + "\"}").build();
        }
    }

    @POST
    @Path("/seek/{seconds}")
    @Blocking
    public Response seekTo(@PathParam("seconds") double seconds) {
        try {
            videoController.setSeconds(seconds);
            com.fasterxml.jackson.databind.node.ObjectNode seekPayload = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
            seekPayload.put("value", seconds);
            videoSocket.broadcastCommand("seek", seekPayload);
            return Response.ok("{\"success\":true,\"message\":\"Seeked to " + seconds + " seconds\",\"position\":" + seconds + "}").build();
        } catch (Exception e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                       .entity("{\"success\":false,\"error\":\"" + e.getMessage() + "\"}").build();
        }
    }

    @POST
    @Path("/volume/{level}")
    @Blocking
    public Response setVolume(@PathParam("level") float level) {
        try {
            // Validate volume level (0.0 to 1.0)
            if (level < 0.0f || level > 1.0f) {
                return Response.status(Response.Status.BAD_REQUEST)
                           .entity("{\"success\":false,\"error\":\"Volume level must be between 0.0 and 1.0\"}").build();
            }
            
            videoController.changeVolume(level);
            return Response.ok("{\"success\":true,\"message\":\"Volume set to " + (level * 100) + "%\",\"volume\":" + level + "}").build();
        } catch (Exception e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                       .entity("{\"success\":false,\"error\":\"" + e.getMessage() + "\"}").build();
        }
    }

    @POST
    @Path("/volume")
    @Blocking
    public Response setVolumeFromQuery(@QueryParam("level") Float level) {
        if (level == null) {
            return Response.status(Response.Status.BAD_REQUEST)
                       .entity("{\"success\":false,\"error\":\"Volume level parameter required\"}").build();
        }
        return setVolume(level.floatValue());
    }

    @POST
    @Path("/progress")
    @Blocking
    public Response reportProgress(@QueryParam("videoId") Long videoId, @QueryParam("time") double seconds, @QueryParam("playing") boolean playing) {
        try {
            if (videoId != null) {
                Video video = Video.findById(videoId);
                if (video != null) {
                    // Update per-profile progress (no global Video writes)
                    videoStateService.updateProgress(video, seconds);

                    // Update current session state if this video is active
                    ProfileSessionState currentState = videoController.getState();
                    if (videoId.equals(currentState.currentVideoId)) {
                        currentState.currentTime = seconds;
                        currentState.playing = playing;
                        videoController.updateState(currentState, true);
                    }
                }
            }

            return Response.ok("{\"success\":true}").build();
        } catch (Exception e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                       .entity("{\"success\":false,\"error\":\"" + e.getMessage() + "\"}").build();
        }
    }

    @GET
    @Path("/current")
    @Blocking
    public Response getCurrentVideo() {
        try {
            var currentState = videoController.getState();
            if (currentState == null) {
                return Response.ok("{\"success\":true,\"video\":null,\"message\":\"No current video\"}").build();
            }
            
            Long currentVideoId = currentState.currentVideoId;
            if (currentVideoId == null) {
                return Response.ok("{\"success\":true,\"video\":null,\"message\":\"No current video\"}").build();
            }
            
            Video video = Video.findById(currentVideoId);
            String title = video != null ? video.title : "Unknown";
            String seriesTitle = video != null ? video.seriesTitle : null;
            String episodeTitle = video != null && "episode".equals(video.type) ? video.episodeTitle : title;
            double duration = video != null && video.duration != null ? video.duration / 1000.0 : 0;
            
            StringBuilder response = new StringBuilder();
            response.append("{\"success\":true,")
                   .append("\"video\":{")
                   .append("\"id\":").append(currentVideoId).append(",")
                   .append("\"title\":\"").append(safeString(title)).append("\",")
                   .append("\"seriesTitle\":\"").append(safeString(seriesTitle)).append("\",")
                   .append("\"episodeTitle\":\"").append(safeString(episodeTitle)).append("\",")
                   .append("\"seasonNumber\":").append(video != null && video.seasonNumber != null ? video.seasonNumber : "null").append(",")
                   .append("\"currentTime\":").append(currentState.currentTime).append(",")
                   .append("\"duration\":").append(duration).append(",")
                   .append("\"playing\":").append(currentState.playing).append(",")
                   .append("\"volume\":").append(currentState.volume)
                   .append("},")
                   .append("\"message\":\"Current video state retrieved\"")
                   .append("}");
            
            return Response.ok(response.toString()).build();
        } catch (Exception e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                       .entity("{\"success\":false,\"error\":\"" + e.getMessage() + "\"}").build();
        }
    }
    
    private String safeString(String str) {
        return str != null ? str.replace("\"", "\\\"") : "";
    }

    @POST
    @Path("/audio-preference")
    @Blocking
    public Response updateAudioPreference(@QueryParam("videoId") Long videoId, 
                                          @QueryParam("trackId") Long trackId,
                                          @QueryParam("language") String language) {
        try {
            if (videoId == null) {
                return Response.status(Response.Status.BAD_REQUEST)
                           .entity("{\"success\":false,\"error\":\"videoId required\"}").build();
            }
            
            videoService.updateAudioTrackPreference(videoId, trackId, language);
            return Response.ok("{\"success\":true,\"message\":\"Audio preference updated\"}").build();
        } catch (Exception e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                       .entity("{\"success\":false,\"error\":\"" + e.getMessage() + "\"}").build();
        }
    }

    @GET
    @Path("/next/{videoId}")
    @Blocking
    public Response getNextEpisode(@PathParam("videoId") Long videoId) {
        try {
            Models.Video.Video current = Models.Video.Video.findById(videoId);
            if (current == null || current.seriesTitle == null || current.episodeNumber == null) {
                return Response.ok("{\"nextVideoId\":null}").build();
            }

            // Find next episode in the same series
            Models.Video.Video next = Models.Video.Video.find(
                "seriesTitle = ?1 and seasonNumber = ?2 and episodeNumber > ?3 and type = 'episode' and (folder is null or folder = '') order by episodeNumber asc",
                current.seriesTitle, current.seasonNumber, current.episodeNumber
            ).firstResult();

            if (next == null && current.seasonNumber != null) {
                // Try next season
                next = Models.Video.Video.find(
                    "seriesTitle = ?1 and seasonNumber > ?2 and type = 'episode' and (folder is null or folder = '') order by seasonNumber asc, episodeNumber asc",
                    current.seriesTitle, current.seasonNumber
                ).firstResult();
            }

            return Response.ok("{\"nextVideoId\":" + (next != null ? next.id : "null") + "}").build();
        } catch (Exception e) {
            return Response.ok("{\"nextVideoId\":null}").build();
        }
    }

    @GET
    @Path("/previous/{videoId}")
    @Blocking
    public Response getPreviousEpisode(@PathParam("videoId") Long videoId) {
        try {
            Models.Video.Video current = Models.Video.Video.findById(videoId);
            if (current == null || current.seriesTitle == null || current.episodeNumber == null) {
                return Response.ok("{\"previousVideoId\":null}").build();
            }

            // Find previous episode in the same series
            Models.Video.Video prev = Models.Video.Video.find(
                "seriesTitle = ?1 and seasonNumber = ?2 and episodeNumber < ?3 and type = 'episode' and (folder is null or folder = '') order by episodeNumber desc",
                current.seriesTitle, current.seasonNumber, current.episodeNumber
            ).firstResult();

            if (prev == null && current.seasonNumber != null && current.seasonNumber > 1) {
                // Try previous season - get last episode
                prev = Models.Video.Video.find(
                    "seriesTitle = ?1 and seasonNumber < ?2 and type = 'episode' and (folder is null or folder = '') order by seasonNumber desc, episodeNumber desc",
                    current.seriesTitle, current.seasonNumber
                ).firstResult();
            }

            return Response.ok("{\"previousVideoId\":" + (prev != null ? prev.id : "null") + "}").build();
        } catch (Exception e) {
            return Response.ok("{\"previousVideoId\":null}").build();
        }
    }

    // ==================== SUBTITLE/AUDIO TRACK SELECTION ====================

    @POST
    @Path("/select-subtitle")
    @Blocking
    public Response selectSubtitle(@QueryParam("videoId") Long videoId,
                                   String body) {
        try {
            if (videoId == null) {
                return Response.status(Response.Status.BAD_REQUEST)
                       .entity("{\"success\":false,\"error\":\"videoId required\"}").build();
            }
            
            Video video = Video.findById(videoId);
            if (video == null) {
                return Response.status(Response.Status.NOT_FOUND)
                       .entity("{\"success\":false,\"error\":\"Video not found\"}").build();
            }
            
            com.fasterxml.jackson.databind.ObjectMapper objMapper = new com.fasterxml.jackson.databind.ObjectMapper();
            com.fasterxml.jackson.databind.JsonNode bodyNode = objMapper.readTree(body);
            int index = bodyNode.has("index") ? bodyNode.get("index").asInt(-1) : -1;
            
            com.fasterxml.jackson.databind.node.ObjectNode payload = objMapper.createObjectNode();
            payload.put("index", index);
            videoSocket.broadcastCommand("select-subtitle", payload);
            
            return Response.ok("{\"success\":true}").build();
        } catch (Exception e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                       .entity("{\"success\":false,\"error\":\"" + e.getMessage() + "\"}").build();
        }
    }

    @POST
    @Path("/select-audio")
    @Blocking
    public Response selectAudio(@QueryParam("videoId") Long videoId,
                                String body) {
        try {
            if (videoId == null) {
                return Response.status(Response.Status.BAD_REQUEST)
                       .entity("{\"success\":false,\"error\":\"videoId required\"}").build();
            }
            
            Video video = Video.findById(videoId);
            if (video == null) {
                return Response.status(Response.Status.NOT_FOUND)
                       .entity("{\"success\":false,\"error\":\"Video not found\"}").build();
            }
            
            com.fasterxml.jackson.databind.ObjectMapper objMapper = new com.fasterxml.jackson.databind.ObjectMapper();
            com.fasterxml.jackson.databind.JsonNode bodyNode = objMapper.readTree(body);
            int index = bodyNode.has("index") ? bodyNode.get("index").asInt(0) : 0;
            
            com.fasterxml.jackson.databind.node.ObjectNode payload = objMapper.createObjectNode();
            payload.put("index", index);
            videoSocket.broadcastCommand("select-audio", payload);
            
            return Response.ok("{\"success\":true}").build();
        } catch (Exception e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                       .entity("{\"success\":false,\"error\":\"" + e.getMessage() + "\"}").build();
        }
    }

    @GET
    @Path("/subtitle-tracks")
    @Blocking
    public Response getSubtitleTracks(@QueryParam("videoId") Long videoId) {
        try {
            if (videoId == null) {
                return Response.ok("{\"tracks\":[],\"activeTrackId\":null}").build();
            }
            
            Video video = Video.findById(videoId);
            if (video == null || video.subtitleTracks == null || video.subtitleTracks.isEmpty()) {
                return Response.ok("{\"tracks\":[],\"activeTrackId\":null}").build();
            }
            
            StringBuilder sb = new StringBuilder();
            sb.append("{\"tracks\":[");
            boolean first = true;
            Long activeTrackId = null;
            for (Models.Video.SubtitleTrack track : video.subtitleTracks) {
                if (!track.isActive) continue;
                if (!first) sb.append(",");
                first = false;
                sb.append("{")
                  .append("\"id\":").append(track.id).append(",")
                  .append("\"languageCode\":\"").append(safeString(track.languageCode)).append("\",")
                  .append("\"languageName\":\"").append(safeString(track.languageName)).append("\",")
                  .append("\"displayName\":\"").append(safeString(track.displayName)).append("\",")
                  .append("\"isForced\":").append(track.isForced).append(",")
                  .append("\"isSDH\":").append(track.isSDH).append(",")
                  .append("\"isDefault\":").append(track.isDefault).append(",")
                  .append("\"trackIndex\":").append(track.trackIndex != null ? track.trackIndex : "null")
                  .append("}");
                if (track.isDefault && activeTrackId == null) {
                    activeTrackId = track.id;
                }
            }
            sb.append("],\"activeTrackId\":").append(activeTrackId != null ? activeTrackId : "null").append("}");
            
            return Response.ok(sb.toString()).build();
        } catch (Exception e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                       .entity("{\"success\":false,\"error\":\"" + e.getMessage() + "\"}").build();
        }
    }

    @GET
    @Path("/audio-tracks")
    @Blocking
    public Response getAudioTracks(@QueryParam("videoId") Long videoId) {
        try {
            if (videoId == null) {
                return Response.ok("{\"tracks\":[],\"activeTrackId\":null}").build();
            }
            
            Video video = Video.findById(videoId);
            if (video == null || video.audioTracks == null || video.audioTracks.isEmpty()) {
                return Response.ok("{\"tracks\":[],\"activeTrackId\":null}").build();
            }
            
            StringBuilder sb = new StringBuilder();
            sb.append("{\"tracks\":[");
            boolean first = true;
            Long activeTrackId = null;
            for (Models.Video.AudioTrack track : video.audioTracks) {
                if (!track.isActive) continue;
                if (!first) sb.append(",");
                first = false;
                sb.append("{")
                  .append("\"id\":").append(track.id).append(",")
                  .append("\"languageCode\":\"").append(safeString(track.languageCode)).append("\",")
                  .append("\"languageName\":\"").append(safeString(track.languageName)).append("\",")
                  .append("\"displayName\":\"").append(safeString(track.displayName)).append("\",")
                  .append("\"isDefault\":").append(track.isDefault).append(",")
                  .append("\"trackIndex\":").append(track.trackIndex != null ? track.trackIndex : "null")
                  .append("}");
                if (track.isDefault && activeTrackId == null) {
                    activeTrackId = track.id;
                }
            }
            sb.append("],\"activeTrackId\":").append(activeTrackId != null ? activeTrackId : "null").append("}");
            
            return Response.ok(sb.toString()).build();
        } catch (Exception e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                       .entity("{\"success\":false,\"error\":\"" + e.getMessage() + "\"}").build();
        }
    }
}
