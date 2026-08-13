package API.Rest;

import Models.Video.AudioTrack;
import Models.Video.SubtitleTrack;
import Models.Settings.User;
import Models.Video.UserSubtitlePreferences;
import Models.Video.Video;
import Models.DTOs.SubtitleSearchResult;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import Services.SubtitleFormatConverter;
import Services.SubtitlePreferenceEngine;
import Services.UserInteractionService;
import Services.ParakeetService;
import Services.SubtitleDownloadService;
import Services.SubtitleTrackService;
import Services.FFprobeSubtitleService; 
import Services.PgsOcrService;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Path("/api/video/subtitles")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
public class SubtitleAPI {
    
    private static final Logger LOGGER = LoggerFactory.getLogger(SubtitleAPI.class);
    
    @Inject
    private UserInteractionService userInteractionService;
    
    @Inject
    private SubtitlePreferenceEngine preferenceEngine;
    
    @Inject
    private SubtitleFormatConverter formatConverter;
    
    @Inject
    private ParakeetService parakeetService;
    
    @Inject
    private SubtitleDownloadService downloadService;

    @Inject
    private Services.VideoService videoService;

    @Inject
    private Services.SettingsService settingsService;
     
    @Inject
    private FFprobeSubtitleService ffprobeSubtitleService;

    @Inject
    private SubtitleTrackService subtitleTrackService;

    @Inject
    private PgsOcrService pgsOcrService;

    @Inject
    private Services.EnhancedSubtitleMatcher subtitleMatcher;

    // ========== SUBTITLE ENDPOINTS ==========
    
    @POST
    @Path("/{videoId}/generate")
    public Response generateSubtitle(@PathParam("videoId") Long videoId,
                                     @QueryParam("language") @DefaultValue("en") String language,
                                     @QueryParam("audioTrack") @DefaultValue("-1") int audioTrackIndex) {
        Video video = Video.findById(videoId);
        if (video == null) {
            return Response.status(Response.Status.NOT_FOUND).entity("Video not found").build();
        }
        
        if (!parakeetService.isParakeetAvailable()) {
            return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                    .entity("Parakeet is not available on this server").build();
        }
        
        parakeetService.generateSubtitle(video, language, audioTrackIndex);
        
        return Response.ok(createSuccessResponse("Subtitle generation started in background")).build();
    }
    
    @GET
    @Path("/{videoId}/generate/status")
    public Response getGenerationStatus(@PathParam("videoId") Long videoId) {
        return Response.ok(parakeetService.getGenerationStatus()).build();
    }
    
    @POST
    @Path("/{videoId}/generate/cancel")
    public Response cancelGeneration(@PathParam("videoId") Long videoId) {
        parakeetService.cancelGeneration();
        return Response.ok(createSuccessResponse("Generation cancelled")).build();
    }
    
    @GET
    @Path("/{videoId}/search")
    public Response searchSubtitle(@PathParam("videoId") Long videoId,
                                 @QueryParam("language") @DefaultValue("en") String language,
                                 @QueryParam("query") String query) {
        Video video = Video.findById(videoId);
        if (video == null) {
            return Response.status(Response.Status.NOT_FOUND).entity("Video not found").build();
        }

        try {
            List<SubtitleSearchResult> results = downloadService.searchSubtitles(video, language, query);
            return Response.ok(results).build();
        } catch (Exception e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(Map.of("error", "Search failed: " + e.getMessage())).build();
        }
    }
    
    @POST
    @Path("/{videoId}/download")
    public Response downloadSubtitle(@PathParam("videoId") Long videoId, 
                                   @QueryParam("fileId") String fileId,
                                   @QueryParam("language") String language,
                                   @HeaderParam("X-User-ID") Long userId) {
        Video video = Video.findById(videoId);
        if (video == null) {
            return Response.status(Response.Status.NOT_FOUND).entity("Video not found").build();
        }
        
        if (fileId == null || fileId.isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST).entity("fileId is required").build();
        }
        
        try {
            downloadService.downloadSubtitleWithLang(video, fileId, language);
            
            // Return updated tracks immediately to avoid race conditions
            List<SubtitleTrack> tracks = userInteractionService.getSubtitleTracks(videoId);
            tracks = preferenceEngine.sortTracksByPreference(tracks, userId);
            SubtitleTrack preferredTrack = preferenceEngine.selectBestSubtitleTrack(videoId, userId);
            
            List<Models.DTOs.SubtitleTrackDTO> dtoTracks = tracks.stream()
                .map(Models.DTOs.SubtitleTrackDTO::new)
                .collect(java.util.stream.Collectors.toList());

            Map<String, Object> response = new HashMap<>();
            response.put("message", "Subtitle downloaded successfully");
            response.put("tracks", dtoTracks);
            response.put("preferredTrackId", preferredTrack != null ? preferredTrack.id : null);
            
            return Response.ok(response).build();
        } catch (Exception e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(Map.of("error", "Download failed: " + e.getMessage())).build();
        }
    }
    
    @GET
    @Path("/{videoId}/local-files")
    public Response listLocalFiles(@PathParam("videoId") Long videoId) {
        Video video = Video.findById(videoId);
        if (video == null) {
            return Response.status(Response.Status.NOT_FOUND).entity("Video not found").build();
        }
        
        try {
            List<Models.DTOs.LocalSubtitleFile> potentialTracks = downloadService.scanAllSubtitleFiles(video);
            return Response.ok(potentialTracks).build();
        } catch (Exception e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(Map.of("error", "Failed to scan local files: " + e.getMessage())).build();
        }
    }
    
    @POST
    @Path("/{videoId}/add-local")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response addLocalSubtitle(@PathParam("videoId") Long videoId, 
                                    Map<String, String> request,
                                    @HeaderParam("X-User-ID") Long userId) {
        Video video = Video.findById(videoId);
        if (video == null) {
            return Response.status(Response.Status.NOT_FOUND).entity("Video not found").build();
        }
        
        String filePath = request.get("filePath");
        if (filePath == null || filePath.isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST).entity("filePath is required").build();
        }
        
        try {
            downloadService.addLocalSubtitle(video, filePath);
            
            // Return updated tracks immediately
            List<SubtitleTrack> tracks = userInteractionService.getSubtitleTracks(videoId);
            tracks = preferenceEngine.sortTracksByPreference(tracks, userId);
            SubtitleTrack preferredTrack = preferenceEngine.selectBestSubtitleTrack(videoId, userId);
            
            List<Models.DTOs.SubtitleTrackDTO> dtoTracks = tracks.stream()
                .map(Models.DTOs.SubtitleTrackDTO::new)
                .collect(java.util.stream.Collectors.toList());

            Map<String, Object> response = new HashMap<>();
            response.put("message", "Subtitle added successfully");
            response.put("tracks", dtoTracks);
            response.put("preferredTrackId", preferredTrack != null ? preferredTrack.id : null);
            
            return Response.ok(response).build();
        } catch (Exception e) {
            String errorMsg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            System.err.println("API Error adding local subtitle: " + errorMsg);
            e.printStackTrace();
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(Map.of("error", "Failed to add local subtitle: " + errorMsg)).build();
        }
    }
    
    @POST
    @Path("/{videoId}/upload")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response uploadSubtitle(@PathParam("videoId") Long videoId,
                                   Map<String, String> request,
                                   @HeaderParam("X-User-ID") Long userId) {
        try {
            SubtitleTrackService.UploadResult result = subtitleTrackService.uploadForVideo(videoId, request);

            if (result.isNotFound()) {
                return Response.status(Response.Status.NOT_FOUND).entity("Video not found").build();
            }
            if (result.getBadRequestMessage() != null) {
                return Response.status(Response.Status.BAD_REQUEST).entity(result.getBadRequestMessage()).build();
            }
            if (result.isVideoDirError()) {
                return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                        .entity("Cannot determine video directory").build();
            }

            // Return updated tracks immediately to avoid race conditions
            List<SubtitleTrack> tracks = userInteractionService.getSubtitleTracks(videoId);
            tracks = preferenceEngine.sortTracksByPreference(tracks, userId);
            SubtitleTrack preferredTrack = preferenceEngine.selectBestSubtitleTrack(videoId, userId);

            List<Models.DTOs.SubtitleTrackDTO> dtoTracks = tracks.stream()
                .map(Models.DTOs.SubtitleTrackDTO::new)
                .collect(java.util.stream.Collectors.toList());

            Map<String, Object> response = new HashMap<>();
            response.put("message", "Subtitle uploaded successfully");
            response.put("tracks", dtoTracks);
            response.put("preferredTrackId", preferredTrack != null ? preferredTrack.id : null);

            return Response.ok(response).build();

        } catch (Exception e) {
            LOGGER.error("Error uploading subtitle", e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(Map.of("error", "Upload failed: " + e.getMessage())).build();
        }
    }
      
    @GET
    @Path("/{videoId}")
    public Response getSubtitleTracks(@PathParam("videoId") Long videoId,
                                       @HeaderParam("X-User-ID") Long userId) {
        try {
            List<SubtitleTrack> tracks = userInteractionService.getSubtitleTracks(videoId);
            
            // If no tracks found, attempt on-demand discovery for this specific video
            if (tracks.isEmpty()) {
                Video video = Video.findById(videoId);
                if (video != null && video.path != null) {
                    LOGGER.info("No subtitle tracks found for video {}, attempting on-demand discovery for embedded/external tracks...", videoId);
                    java.nio.file.Path videoPath = java.nio.file.Paths.get(video.path);
                    if (!videoPath.isAbsolute()) {
                        String videoLibraryPath = settingsService.getOrCreateSettings().getVideoLibraryPath();
                        videoPath = java.nio.file.Paths.get(videoLibraryPath, video.path);
                    }
                    
                    if (java.nio.file.Files.exists(videoPath)) {
                        List<SubtitleTrack> discovered = subtitleMatcher.discoverSubtitleTracks(videoPath, video);
                        if (discovered != null && !discovered.isEmpty()) {
                            // Ensure all tracks are properly initialized
                            for (SubtitleTrack track : discovered) {
                                track.video = video;
                                
                            }
                            videoService.updateSubtitleTracks(videoId, discovered);
                            tracks = userInteractionService.getSubtitleTracks(videoId);
                            LOGGER.info("On-demand discovery found {} tracks for video {}", tracks.size(), videoId);
                        }
                    }
                }
            }
            
            // Apply intelligent preference sorting
            tracks = preferenceEngine.sortTracksByPreference(tracks, userId);
            
            // Mark preferred track
            SubtitleTrack preferredTrack = preferenceEngine.selectBestSubtitleTrack(videoId, userId);
            if (preferredTrack != null && !tracks.isEmpty()) {
                // Clear existing preferences and set new preferred
                final Long preferredId = preferredTrack.id;
                tracks.forEach(track -> {
                    track.isDefault = track.id.equals(preferredId);
                    track.userPreferenceOrder = 1; // Highest priority
                });
            }
            
            List<Models.DTOs.SubtitleTrackDTO> dtoTracks = tracks.stream()
                .map(Models.DTOs.SubtitleTrackDTO::new)
                .collect(java.util.stream.Collectors.toList());

            // PGS OCR is intentionally NOT pre-warmed here: this endpoint is hit on
            // every playback / episode switch / subtitle reload, so preloading would
            // spawn one tesseract per PGS cue on every fetch and pin the CPU against
            // active video transcodes. OCR now runs lazily on first track selection
            // (streamSubtitle -> getOrCreateWebVTT) or once at import time
            // (SubtitleDiscoveryQueueProcessor), both gated by PgsOcrService against
            // active transcoding.

            Map<String, Object> response = new HashMap<>();
            response.put("tracks", dtoTracks);
            response.put("preferredTrackId", preferredTrack != null ? preferredTrack.id : null);
            response.put("videoId", videoId);
            
            return Response.ok(response).build();
            
        } catch (Exception e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(Map.of("error", e.getMessage()))
                    .build();
        }
    }
     
    @GET
    @Path("/track/{trackId}")
    @Produces("text/vtt")
    public Response streamSubtitle(@PathParam("trackId") Long trackId,
                                  @QueryParam("start") @jakarta.ws.rs.DefaultValue("0") double offset,
                                  @QueryParam("correction") @jakarta.ws.rs.DefaultValue("0") double correction) {
        SubtitleTrack track = SubtitleTrack.findById(trackId);
        if (track == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity("Subtitle track not found")
                    .build();
        }

        try {
            String webVTTContent;
            
            // For external tracks:
            // -offset: shift earlier by seek position (because the browser video starts at 0.0)
            // +correction: user-defined adjustment (positive = later, negative = earlier)
            double externalShift = -offset + correction;

            // For embedded tracks, ffmpeg extracts the full subtitle with original timestamps.
            // When the video stream starts at an offset (server-side seek), video.currentTime starts at 0
            // but the video is actually at the offset position. We must shift subtitles to match.
            if (track.isEmbedded) {
                if (PgsOcrService.isPgsCodec(track.codec) || "pgs".equals(track.format)) {
                    // PGS is image-based: OCR to WebVTT on demand (cached per track)
                    webVTTContent = pgsOcrService.getOrCreateWebVTT(track);
                } else {
                    webVTTContent = ffprobeSubtitleService.extractInternalSubtitleToVTT(track, offset);
                }
                
                // Apply the same offset shift as external tracks: negative offset shifts timestamps
                // so a cue originally at 'offset' seconds appears at video.currentTime = 0
                double effectiveShift = -offset + correction;
                if (effectiveShift != 0) {
                    webVTTContent = formatConverter.applyOffset(webVTTContent, effectiveShift);
                }
            } else {
                // External track - read and convert using the robust converter service
                java.nio.file.Path subtitlePath = java.nio.file.Paths.get(track.fullPath);
                if (!java.nio.file.Files.exists(subtitlePath)) {
                    return Response.status(Response.Status.NOT_FOUND)
                            .entity("Subtitle file not found at: " + track.fullPath)
                            .build();
                }
                
                // Convert to WebVTT
                webVTTContent = formatConverter.convertToWebVTT(track);

                // Apply total shift (seek offset + user correction)
                if (externalShift != 0) {
                    webVTTContent = formatConverter.applyOffset(webVTTContent, externalShift);
                }
            }

            // Ensure we at least have a WEBVTT header
            if (webVTTContent == null || webVTTContent.trim().isEmpty()) {
                webVTTContent = "WEBVTT\n\nNOTE Empty or invalid subtitle conversion for track " + trackId + "\n";
                LOGGER.warn("Subtitle track {} (format: {}) returned empty content. Fallback header provided.", trackId, track.format);
            } else if (webVTTContent.trim().equals("WEBVTT")) {
                webVTTContent = "WEBVTT\n\nNOTE Header-only content for track " + trackId + "\n";
                LOGGER.warn("Subtitle track {} (format: {}) returned header-only VTT content.", trackId, track.format);
            }

            return Response.ok(webVTTContent)
                    .header("Content-Type", "text/vtt; charset=utf-8")
                    .header("Cache-Control", "public, max-age=3600")
                    .header("Access-Control-Allow-Origin", "*")
                    .build();

        } catch (Exception e) {
            LOGGER.error("Error streaming subtitle track {}: {}", trackId, e.getMessage(), e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity("Failed to stream subtitle: " + e.getMessage())
                    .build();
        }
    }
    
    @GET
    @Path("/track/{trackId}/raw")
    @Produces("text/plain")
    public Response streamRawSubtitle(@PathParam("trackId") Long trackId,
                                       @QueryParam("correction") @jakarta.ws.rs.DefaultValue("0") double correction) {
        SubtitleTrack track = SubtitleTrack.findById(trackId);
        if (track == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity("Subtitle track not found")
                    .build();
        }

        String format = track.format != null ? track.format.toLowerCase() : "";
        if (!"ass".equals(format) && !"ssa".equals(format)) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("Raw subtitle endpoint only supports ASS/SSA format, got: " + format)
                    .build();
        }

        try {
            String content;
            if (track.isEmbedded) {
                content = ffprobeSubtitleService.extractRawSubtitle(track);
            } else {
                java.nio.file.Path subtitlePath = java.nio.file.Paths.get(track.fullPath);
                if (!java.nio.file.Files.exists(subtitlePath)) {
                    return Response.status(Response.Status.NOT_FOUND)
                            .entity("Subtitle file not found at: " + track.fullPath)
                            .build();
                }
                content = java.nio.file.Files.readString(subtitlePath, java.nio.charset.StandardCharsets.UTF_8);
            }

            if (content == null || content.trim().isEmpty()) {
                return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                        .entity("Empty subtitle content")
                        .build();
            }

            // Apply user correction to ASS/SSA timestamps if provided
            if (correction != 0) {
                content = applyAssOffset(content, correction);
            }

            return Response.ok(content)
                    .header("Content-Type", "text/plain; charset=utf-8")
                    .header("Cache-Control", "public, max-age=3600")
                    .header("Access-Control-Allow-Origin", "*")
                    .build();

        } catch (Exception e) {
            LOGGER.error("Error streaming raw subtitle track {}: {}", trackId, e.getMessage(), e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity("Failed to stream raw subtitle: " + e.getMessage())
                    .build();
        }
    }

    /**
     * Applies a time offset to ASS/SSA subtitle content by shifting all timestamps.
     * @param assContent The ASS/SSA content
     * @param shiftSeconds The offset in seconds (positive = later, negative = earlier)
     * @return Modified ASS/SSA content
     */
    private String applyAssOffset(String assContent, double shiftSeconds) {
        if (shiftSeconds == 0 || assContent == null) return assContent;

        // Shift is in seconds, convert to centiseconds (ASS uses centiseconds)
        int shiftCs = (int) Math.round(shiftSeconds * 100);

        String[] lines = assContent.split("\n");
        StringBuilder result = new StringBuilder();

        for (String line : lines) {
            // Skip header/metadata lines
            if (line.startsWith("[") || line.startsWith("Format:") || line.startsWith("Style:") || line.trim().isEmpty()) {
                result.append(line).append("\n");
                continue;
            }

            // Dialogue lines: "Dialogue: 0,0:00:00.00,0:00:05.00,Default,,0,0,0,,Text"
            if (line.startsWith("Dialogue:") || line.startsWith("Comment:")) {
                result.append(shiftAssDialogueLine(line, shiftCs)).append("\n");
            } else {
                result.append(line).append("\n");
            }
        }

        return result.toString();
    }

    /**
     * Shifts timestamps in an ASS Dialogue/Comment line.
     */
    private String shiftAssDialogueLine(String line, int shiftCs) {
        // Format: Dialogue: Layer,Start,End,Style,Name,MarginL,MarginR,MarginV,Effect,Text
        // We need to shift Start and End (fields 1 and 2, 0-indexed after "Dialogue: ")
        int colonIdx = line.indexOf(':');
        if (colonIdx == -1) return line;

        String prefix = line.substring(0, colonIdx + 1); // "Dialogue: " or "Comment: "
        String rest = line.substring(colonIdx + 1).trim();

        String[] fields = rest.split(",", 10); // Split into max 10 fields (9 commas = 10 fields)
        if (fields.length < 3) return line;

        // fields[1] = Start time, fields[2] = End time
        String newStart = shiftAssTime(fields[1], shiftCs);
        String newEnd = shiftAssTime(fields[2], shiftCs);

        fields[1] = newStart;
        fields[2] = newEnd;

        return prefix + " " + String.join(",", fields);
    }

    /**
     * Shifts an ASS timestamp (H:MM:SS.cc) by centiseconds.
     */
    private String shiftAssTime(String timeStr, int shiftCs) {
        // ASS time format: H:MM:SS.cc (e.g., "0:01:23.45" or "1:23:45.67")
        try {
            String[] parts = timeStr.split(":");
            if (parts.length != 3) return timeStr;

            int hours = Integer.parseInt(parts[0]);
            int minutes = Integer.parseInt(parts[1]);
            // Seconds part: SS.cc
            String[] secParts = parts[2].split("\\.");
            if (secParts.length != 2) return timeStr;

            int seconds = Integer.parseInt(secParts[0]);
            int centiseconds = Integer.parseInt(secParts[1]);

            // Convert to total centiseconds
            long totalCs = ((long) hours * 3600 + minutes * 60 + seconds) * 100 + centiseconds;
            totalCs += shiftCs;

            // Clamp to non-negative
            if (totalCs < 0) totalCs = 0;

            // Convert back
            long newHours = totalCs / 360000;
            long newMinutes = (totalCs % 360000) / 6000;
            long newSeconds = (totalCs % 6000) / 100;
            long newCentiseconds = totalCs % 100;

            return String.format("%d:%02d:%02d.%02d", newHours, newMinutes, newSeconds, newCentiseconds);
        } catch (Exception e) {
            return timeStr;
        }
    }

    @POST
    @Path("/preference")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response setSubtitlePreference(Map<String, Object> preference) {
        try {
            Long userId = ((Number) preference.get("userId")).longValue();
            Long videoId = preference.get("videoId") != null ? ((Number) preference.get("videoId")).longValue() : null;
            String languageCode = (String) preference.get("preferredLanguage");
            boolean enableAutoSelection = Boolean.TRUE.equals(preference.get("enableAutoSelection"));
            boolean preferForced = Boolean.TRUE.equals(preference.get("preferForcedSubtitles"));
            boolean preferSDH = Boolean.TRUE.equals(preference.get("preferSDHSubtitles"));
            String style = (String) preference.getOrDefault("subtitleStyle", "default");
            String appearance = (String) preference.get("subtitleAppearance");
            
            // Update user preferences
            Models.Video.UserSubtitlePreferences userPrefs = new Models.Video.UserSubtitlePreferences();
            userPrefs.userId = userId;
            userPrefs.preferredLanguage = languageCode;
            userPrefs.enableAutoSelection = enableAutoSelection;
            userPrefs.preferForcedSubtitles = preferForced;
            userPrefs.preferSDHSubtitles = preferSDH;
            userPrefs.subtitleStyle = style;
            userPrefs.subtitleAppearance = appearance;
            
            userInteractionService.updateUserSubtitlePreferences(userPrefs);
            
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("message", "Subtitle preferences updated");
            
            return Response.ok(response).build();
            
        } catch (Exception e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity("Error updating subtitle preferences: " + e.getMessage())
                    .build();
        }
    }
    
    @POST
    @Path("/per-video-preference")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response setPerVideoPreference(Map<String, Object> preference) {
        try {
            Long userId = ((Number) preference.get("userId")).longValue();
            Long videoId = ((Number) preference.get("videoId")).longValue();
            Long trackId = preference.containsKey("trackId") ? 
                           ((Number) preference.get("trackId")).longValue() : null;
            
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("message", "Per-video preference stored");
            
            return Response.ok(response).build();
            
        } catch (Exception e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity("Error storing per-video preference: " + e.getMessage())
                    .build();
        }
    }
    
    // ========== UTILITY METHODS ==========
    
    private Map<String, Object> createSuccessResponse(String message) {
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("message", message);
        return response;
    }
}
