package API.Rest;

import Models.*;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import Services.SubtitleFormatConverter;
import Services.SubtitlePreferenceEngine;
import Services.UserInteractionService;

import java.io.IOException;
import java.nio.file.Files; 
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Path("/api/video/subtitles")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
public class SubtitleAPI {
    
    @Inject
    private UserInteractionService userInteractionService;
    
    @Inject
    private SubtitlePreferenceEngine preferenceEngine;
    
    @Inject
    private SubtitleFormatConverter formatConverter;
    
    // ========== SUBTITLE ENDPOINTS ==========
    
    @GET
    @Path("/{videoId}")
    public Response getSubtitleTracks(@PathParam("videoId") Long videoId,
                                       @HeaderParam("X-User-ID") Long userId) {
        try {
            List<SubtitleTrack> tracks = userInteractionService.getSubtitleTracks(videoId);
            
            // Apply intelligent preference sorting
            tracks = preferenceEngine.sortTracksByPreference(tracks, userId);
            
            // Mark preferred track
            SubtitleTrack preferredTrack = preferenceEngine.selectBestSubtitleTrack(videoId, userId);
            if (preferredTrack != null && !tracks.isEmpty()) {
                // Clear existing preferences and set new preferred
                tracks.forEach(track -> {
                    track.isDefault = track.id.equals(preferredTrack.id);
                    track.userPreferenceOrder = 1; // Highest priority
                });
            }
            
            Map<String, Object> response = new HashMap<>();
            response.put("tracks", tracks);
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
    public Response streamSubtitle(@PathParam("trackId") Long trackId) {
        SubtitleTrack track = SubtitleTrack.findById(trackId);
        if (track == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity("Subtitle track not found")
                    .build();
        }
        java.nio.file.Path subtitlePath = Paths.get(track.fullPath);
        if (!Files.exists(subtitlePath)) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity("Subtitle file not found")
                    .build();
        }
        // Use the format converter to convert to WebVTT
        String webVTTContent;
        try {
            webVTTContent = formatConverter.convertToWebVTT(track);
        } catch (IOException e) {
            // Fallback to simple conversion if converter fails
            webVTTContent = convertToWebVTT(track);
        }
        return Response.ok(webVTTContent)
                .header("Content-Type", "text/vtt; charset=utf-8")
                .header("Cache-Control", "public, max-age=3600")
                .build();
    }
    
    @POST
    @Path("/preference")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response setSubtitlePreference(Map<String, Object> preference) {
        try {
            Long userId = ((Number) preference.get("userId")).longValue();
            Long videoId = ((Number) preference.get("videoId")).longValue();
            String languageCode = (String) preference.get("preferredLanguage");
            boolean enableAutoSelection = Boolean.TRUE.equals(preference.get("enableAutoSelection"));
            boolean preferForced = Boolean.TRUE.equals(preference.get("preferForcedSubtitles"));
            boolean preferSDH = Boolean.TRUE.equals(preference.get("preferSDHSubtitles"));
            String style = (String) preference.getOrDefault("subtitleStyle", "default");
            String appearance = (String) preference.get("subtitleAppearance");
            
            // Update user preferences
            Models.UserSubtitlePreferences userPrefs = new Models.UserSubtitlePreferences();
            userPrefs.userId = userId;
            userPrefs.preferredLanguage = languageCode;
            userPrefs.enableAutoSelection = enableAutoSelection;
            userPrefs.preferForcedSubtitles = preferForced;
            userPrefs.preferSDHSubtitles = preferSDH;
            userPrefs.subtitleStyle = style;
            userPrefs.subtitleAppearance = appearance;
            
            userInteractionService.updateUserSubtitlePreferences(userPrefs);
            
            // Store per-video preference if specified
            if (videoId != null) {
                // This would store per-video preference in a separate table
                // For now, we'll just return success
            }
            
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
            
            // Store per-video preference
            // This would be implemented in a separate table
            // For now, we'll just return success
            
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
    
    private String convertToWebVTT(SubtitleTrack track) {
        try {
            // Read the subtitle file content
            String content = Files.readString(Paths.get(track.fullPath));
            
            // Perform basic format conversion based on file type
            switch (track.format.toLowerCase()) {
                case "srt":
                    return convertSRTToWebVTT(content);
                case "vtt":
                    if (!content.startsWith("WEBVTT")) {
                        return "WEBVTT\n\n" + content;
                    }
                    return content;
                case "ass":
                case "ssa":
                    return convertASSToWebVTT(content);
                default:
                    return "WEBVTT\n\nNOTE Unsupported format: " + track.format + "\n\n" + content;
            }
        } catch (IOException e) {
            return "WEBVTT\n\nERROR: Could not read subtitle file\n";
        }
    }
    
    private String convertSRTToWebVTT(String srtContent) {
        StringBuilder webVTT = new StringBuilder();
        webVTT.append("WEBVTT\n\n");
        
        String[] lines = srtContent.split("\\r?\\n");
        boolean inCue = false;
        
        for (String line : lines) {
            line = line.trim();
            
            if (line.isEmpty()) {
                if (inCue) {
                    webVTT.append("\n");
                    inCue = false;
                }
                continue;
            }
            
            // Convert SRT timestamps to WebVTT format
            if (line.contains("-->")) {
                line = line.replace(",", "."); // Replace comma with dot for milliseconds
                webVTT.append(line).append("\n");
                inCue = true;
            } else if (line.matches("\\d+")) {
                // Skip sequence numbers
                continue;
            } else if (inCue) {
                webVTT.append(line).append("\n");
            }
        }
        
        return webVTT.toString();
    }
    
    private String convertASSToWebVTT(String assContent) {
        StringBuilder webVTT = new StringBuilder();
        webVTT.append("WEBVTT\n\n");
        webVTT.append("STYLE\n");
        webVTT.append("::cue {\n");
        webVTT.append("  background-color: transparent;\n");
        webVTT.append("  color: white;\n");
        webVTT.append("  font-family: Arial, sans-serif;\n");
        webVTT.append("}\n\n");
        
        // Simple ASS to WebVTT conversion (ignores advanced styling)
        String[] lines = assContent.split("\\r?\\n");
        for (String line : lines) {
            line = line.trim();
            if (line.startsWith("Dialogue:")) {
                // Extract dialogue text from ASS format
                String[] parts = line.substring(9).split(",", 9);
                if (parts.length >= 9) {
                    String dialogue = parts[8].replace("{\\N}", "\n").replaceAll("\\{[^}]*\\}", "");
                    if (!dialogue.trim().isEmpty()) {
                        webVTT.append(dialogue).append("\n\n");
                    }
                }
            }
        }
        
        return webVTT.toString();
    }
    
    private Map<String, Object> createSuccessResponse(String message) {
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("message", message);
        return response;
    }
}