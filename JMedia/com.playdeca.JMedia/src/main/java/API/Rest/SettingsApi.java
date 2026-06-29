package API.Rest;

import API.ApiResponse;
import Controllers.SettingsController;
import Models.Settings;
import Models.DTOs.ImportInstallationStatus;
import Services.PlaybackHistoryService;
import Services.SongService;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import javax.swing.JFileChooser;
import javax.swing.SwingUtilities;
import java.io.File;
import java.util.concurrent.CompletableFuture;
import org.eclipse.microprofile.context.ManagedExecutor;
import Models.DTOs.ImportSettingsDTO;
import Services.SettingsService;
import Services.InstallationService;
import Services.Platform.PlatformOperations;
import Services.Platform.PlatformOperationsFactory;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import Services.MediaDirectoryService;
import Services.VideoImportService;
import java.nio.file.Paths;

@Path("/api/settings")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class SettingsApi {

    private static final Logger LOGGER = LoggerFactory.getLogger(SettingsApi.class);
    
    @Inject
    private SettingsController settingsController;

    @Inject
    private PlaybackHistoryService playbackHistoryService;

    @Inject
    private SongService songService;
    
    @Inject
    private SettingsService settingsService;
    
    @Inject
    private PlatformOperationsFactory platformOperationsFactory;
    
    @Inject
    private InstallationService installationService;

    @Inject
    private Services.ProfileService profileService;

    @Inject
    ManagedExecutor executor;

    @Inject
    Services.CertificateService certificateService;

    @Inject
    VideoImportService videoImportService;

    @GET
    @Path("/https/status")
    public Response getHttpsStatus(@Context HttpHeaders headers) {
        if (!checkAdmin(headers)) {
            return Response.status(Response.Status.FORBIDDEN).entity(ApiResponse.error("Admin access required")).build();
        }
        return Response.ok(ApiResponse.success(certificateService.isHttpsConfigured())).build();
    }

    @POST
    @Path("/https/generate")
    public Response generateHttpsCert(@Context HttpHeaders headers) {
        if (!checkAdmin(headers)) {
            return Response.status(Response.Status.FORBIDDEN).entity(ApiResponse.error("Admin access required")).build();
        }
        boolean success = certificateService.generateSelfSignedCertificate();
        if (success) {
            return Response.ok(ApiResponse.success("Certificate generated and applied. Restart required.")).build();
        } else {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity(ApiResponse.error("Failed to generate certificate")).build();
        }
    }

    private boolean checkAdmin(HttpHeaders headers) {
        Models.User user = getCurrentUser(headers);
        return user != null && "admin".equals(user.getGroupName());
    }

    private Models.User getCurrentUser(HttpHeaders headers) {
        String sessionId = null;
        if (headers.getCookies() != null && headers.getCookies().containsKey("JMEDIA_SESSION")) {
            sessionId = headers.getCookies().get("JMEDIA_SESSION").getValue();
        }
        if (sessionId == null) return null;
        Models.Session session = Models.Session.findBySessionId(sessionId);
        if (session == null || !session.active) return null;
        return Models.User.find("username", session.username).firstResult();
    }

    private boolean checkProfileOwnerOrAdmin(HttpHeaders headers, Long profileId) {
        Models.User user = getCurrentUser(headers);
        if (user == null) return false;
        if ("admin".equals(user.getGroupName())) return true;
        Models.Profile profile = profileService.findById(profileId);
        return profile != null && profile.userId != null && profile.userId.equals(user.id);
    }

    @Inject
    Controllers.SetupController setupController;

    @GET
    @Path("/browse/list-folders")
    public Response listFolders(@QueryParam("path") String path, @Context HttpHeaders headers) {
        // Allow access if it's first-time setup OR if the user is an admin
        if (!setupController.isFirstTimeSetup() && !checkAdmin(headers)) {
            return Response.status(Response.Status.FORBIDDEN).entity(ApiResponse.error("Admin access required")).build();
        }

        try {
            PlatformOperations ops = platformOperationsFactory.getPlatformOperations();
            java.util.List<Map<String, String>> folders = ops.listFolders(path);
            
            Map<String, Object> response = new HashMap<>();
            response.put("folders", folders);
            
            // Handle current path and parent path for navigation
            File currentDir = (path == null || path.trim().isEmpty()) ? null : new File(path);
            if (currentDir == null || !currentDir.exists()) {
                response.put("currentPath", "");
                response.put("parentPath", null);
            } else {
                response.put("currentPath", currentDir.getAbsolutePath());
                response.put("parentPath", currentDir.getParent() != null ? currentDir.getParent() : "");
            }
            
            return Response.ok(ApiResponse.success(response)).build();
        } catch (Exception e) {
            LOGGER.error("Error listing folders", e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity(ApiResponse.error(e.getMessage())).build();
        }
    }

    @POST
    @Path("/{profileId}/sidebar-position")
    public Response updateSidebarPosition(@PathParam("profileId") Long profileId, Map<String, String> data, @Context HttpHeaders headers) {
        if (!checkProfileOwnerOrAdmin(headers, profileId)) return Response.status(Response.Status.FORBIDDEN).build();
        String position = data.get("position");
        if (position == null || (!position.equals("left") && !position.equals("right"))) {
            return Response.status(Response.Status.BAD_REQUEST).entity(ApiResponse.error("Invalid position")).build();
        }
        profileService.updateSidebarPosition(profileId, position);
        return Response.ok(ApiResponse.success("Sidebar position updated")).build();
    }

    @GET
    @Path("/{profileId}/sidebar-position")
    public Response getSidebarPosition(@PathParam("profileId") Long profileId, @Context HttpHeaders headers) {
        if (!checkProfileOwnerOrAdmin(headers, profileId)) return Response.status(Response.Status.FORBIDDEN).build();
        Models.Profile profile = profileService.findById(profileId);
        if (profile == null) {
            return Response.status(Response.Status.NOT_FOUND).entity(ApiResponse.error("Profile not found")).build();
        }
        return Response.ok(ApiResponse.success(profile.sidebarPosition)).build();
    }

    @GET
    @Path("/{profileId}/install-status")
    public Response getInstallationStatus(@PathParam("profileId") Long profileId) {
        try {
            ImportInstallationStatus status = installationService.getInstallationStatus();
            return Response.ok(ApiResponse.success(status)).build();
        } catch (Exception e) {
            LOGGER.error("Error getting installation status", e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(ApiResponse.error("Error getting installation status: " + e.getMessage()))
                    .build();
        }
    }

    @GET
    @Path("/{profileId}/browse-folder")
    public Response browseFolder(@PathParam("profileId") Long profileId, @Context HttpHeaders headers) {
        if (!checkAdmin(headers)) return Response.status(Response.Status.FORBIDDEN).build();
        LOGGER.info("[SettingsApi] browseFolder called for profile {}", profileId);
        if (java.awt.GraphicsEnvironment.isHeadless()) {
            LOGGER.error("Cannot open folder browser: Environment is headless");
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(ApiResponse.error("Folder browser is not available on this server configuration")).build();
        }

        CompletableFuture<String> selectedPathFuture = new CompletableFuture<>();

        SwingUtilities.invokeLater(() -> {
            LOGGER.info("[SettingsApi] SwingUtilities.invokeLater executing for music folder");
            try {
                try {
                    javax.swing.UIManager.setLookAndFeel(javax.swing.UIManager.getSystemLookAndFeelClassName());
                } catch (Exception ignore) {}

                JFileChooser fileChooser = new JFileChooser();
                fileChooser.setDialogTitle("Select Music Folder");
                fileChooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
                fileChooser.setAcceptAllFileFilterUsed(false);

                String currentPath = settingsController.getMusicLibraryPath();
                if (currentPath != null && !currentPath.isBlank()) {
                    File currentDir = new File(currentPath);
                    if (currentDir.exists() && currentDir.isDirectory()) {
                        fileChooser.setCurrentDirectory(currentDir);
                    }
                }

                LOGGER.info("[SettingsApi] Showing JFileChooser dialog...");
                int userSelection = fileChooser.showOpenDialog(null);
                LOGGER.info("[SettingsApi] JFileChooser dialog closed with selection: {}", userSelection);

                if (userSelection == JFileChooser.APPROVE_OPTION) {
                    File selectedFile = fileChooser.getSelectedFile();
                    selectedPathFuture.complete(selectedFile.getAbsolutePath());
                } else {
                    selectedPathFuture.complete(""); 
                }
            } catch (Exception e) {
                LOGGER.error("Swing Error in browse-folder", e);
                selectedPathFuture.completeExceptionally(e);
            }
        });

        try {
            LOGGER.info("[SettingsApi] Waiting for folder selection (timeout 5m)...");
            String selectedPath = selectedPathFuture.get(5, java.util.concurrent.TimeUnit.MINUTES);
            LOGGER.info("[SettingsApi] Folder selection completed: {}", selectedPath);
            return Response.ok(ApiResponse.success(selectedPath != null ? selectedPath : "")).build();
        } catch (Exception e) {
            LOGGER.error("Error waiting for folder selection", e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity(ApiResponse.error("Error opening folder chooser: " + e.getMessage())).build();
        }
    }

    @GET
    @Path("/{profileId}/browse-video-folder")
    public Response browseVideoFolder(@PathParam("profileId") Long profileId, @Context HttpHeaders headers) {
        if (!checkAdmin(headers)) return Response.status(Response.Status.FORBIDDEN).build();
        LOGGER.info("[SettingsApi] browseVideoFolder called for profile {}", profileId);
        if (java.awt.GraphicsEnvironment.isHeadless()) {
            LOGGER.error("Cannot open folder browser: Environment is headless");
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(ApiResponse.error("Folder browser is not available on this server configuration")).build();
        }

        CompletableFuture<String> selectedPathFuture = new CompletableFuture<>();

        SwingUtilities.invokeLater(() -> {
            LOGGER.info("[SettingsApi] SwingUtilities.invokeLater executing for video folder");
            try {
                try {
                    javax.swing.UIManager.setLookAndFeel(javax.swing.UIManager.getSystemLookAndFeelClassName());
                } catch (Exception ignore) {}

                JFileChooser fileChooser = new JFileChooser();
                fileChooser.setDialogTitle("Select Video Folder");
                fileChooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
                fileChooser.setAcceptAllFileFilterUsed(false);

                String currentPath = settingsController.getOrCreateSettings().getVideoLibraryPath();
                if (currentPath != null && !currentPath.isBlank()) {
                    File currentDir = new File(currentPath);
                    if (currentDir.exists() && currentDir.isDirectory()) {
                        fileChooser.setCurrentDirectory(currentDir);
                    }
                }

                LOGGER.info("[SettingsApi] Showing JFileChooser dialog...");
                int userSelection = fileChooser.showOpenDialog(null);
                LOGGER.info("[SettingsApi] JFileChooser dialog closed with selection: {}", userSelection);

                if (userSelection == JFileChooser.APPROVE_OPTION) {
                    File selectedFile = fileChooser.getSelectedFile();
                    selectedPathFuture.complete(selectedFile.getAbsolutePath());
                } else {
                    selectedPathFuture.complete("");
                }
            } catch (Exception e) {
                LOGGER.error("Swing Error in browse-video-folder", e);
                selectedPathFuture.completeExceptionally(e);
            }
        });

        try {
            LOGGER.info("[SettingsApi] Waiting for video folder selection (timeout 5m)...");
            String selectedPath = selectedPathFuture.get(5, java.util.concurrent.TimeUnit.MINUTES);
            LOGGER.info("[SettingsApi] Video folder selection completed: {}", selectedPath);
            return Response.ok(ApiResponse.success(selectedPath != null ? selectedPath : "")).build();
        } catch (Exception e) {
            LOGGER.error("Error waiting for video folder selection", e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity(ApiResponse.error("Error opening folder chooser: " + e.getMessage())).build();
        }
    }
    
    @POST
    @Path("/{profileId}/video-library-path")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response setVideoLibraryPath(@PathParam("profileId") Long profileId, 
                                       @FormParam("videoLibraryPathInput") String path,
                                       @FormParam("tmdbApiKey") String tmdbApiKey,
                                       @Context HttpHeaders headers) {
        if (!checkAdmin(headers)) {
            return Response.status(Response.Status.FORBIDDEN).entity(ApiResponse.error("Admin access required")).build();
        }
        if (path == null || path.isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST).entity(ApiResponse.error("Video library path cannot be empty")).build();
        }

        Settings settings = settingsController.getOrCreateSettings();
        settings.setVideoLibraryPath(path);

        if (tmdbApiKey != null && !tmdbApiKey.isBlank()) {
            settings.setTmdbApiKey(tmdbApiKey);
        }

        settingsService.save(settings);
        settingsController.addLog("Video library settings updated. Path: " + path);

        return Response.ok(ApiResponse.success("Video library settings updated.")).build();
    }
    
    // -----------------------------
    // VALIDATE PATHS
    // -----------------------------
    @POST
    @Path("/{profileId}/validate-paths")
    public Response validatePaths(@PathParam("profileId") Long profileId, Map<String, String> paths, @Context HttpHeaders headers) {
        if (!setupController.isFirstTimeSetup() && !checkAdmin(headers)) {
            return Response.status(Response.Status.FORBIDDEN).entity(ApiResponse.error("Admin access required")).build();
        }
        Map<String, Boolean> validation = new HashMap<>();
        
        String musicPath = paths.get("musicLibraryPath");
        String videoPath = paths.get("videoLibraryPath");
        
        if (musicPath != null && !musicPath.isBlank()) {
            validation.put("musicLibraryValid", validatePath(musicPath));
        }
        
        if (videoPath != null && !videoPath.isBlank()) {
            validation.put("videoLibraryValid", validatePath(videoPath));
        }
        
        return Response.ok(ApiResponse.success(validation)).build();
    }

    private boolean validatePath(String path) {
        if (path == null || path.isBlank()) {
            return false;
        }
        java.io.File folder = new java.io.File(path);
        return folder.exists() && folder.isDirectory();
    }

    // -----------------------------
    // GET CURRENT SETTINGS
    // -----------------------------
    @GET
    @Path("/{profileId}")
    public Response getSettings(@PathParam("profileId") Long profileId) {
        Settings settings = settingsController.getOrCreateSettings();
        return Response.ok(ApiResponse.success(settings)).build();
    }

    // -----------------------------
    // BPM TOLERANCE SETTINGS
    // -----------------------------
    @GET
    @Path("/{profileId}/bpm-tolerance")
    public Response getBpmTolerance(@PathParam("profileId") Long profileId) {
        Settings settings = settingsController.getOrCreateSettings();
        Map<String, Object> bpmSettings = new HashMap<>();
        bpmSettings.put("default", settings.getBpmTolerance());
        bpmSettings.put("overrides", settings.getBpmToleranceOverrides());
        return Response.ok(ApiResponse.success(bpmSettings)).build();
    }

    @POST
    @Path("/{profileId}/bpm-tolerance")
    public Response setBpmTolerance(@PathParam("profileId") Long profileId, Map<String, Object> bpmSettings, @Context HttpHeaders headers) {
        if (!checkAdmin(headers)) return Response.status(Response.Status.FORBIDDEN).build();
        Settings settings = settingsController.getOrCreateSettings();
        
        if (bpmSettings.containsKey("default")) {
            Object defaultVal = bpmSettings.get("default");
            if (defaultVal instanceof Number) {
                settings.setBpmTolerance(((Number) defaultVal).intValue());
            }
        }
        
        if (bpmSettings.containsKey("overrides")) {
            Object overridesVal = bpmSettings.get("overrides");
            if (overridesVal instanceof String) {
                settings.setBpmToleranceOverrides((String) overridesVal);
            }
        }
        
        settingsService.save(settings);
        return Response.ok(ApiResponse.success("BPM tolerance settings updated")).build();
    }

    @POST
    @Path("/{profileId}/import-sources")
    public Response updateImportSources(@PathParam("profileId") Long profileId, 
                                 ImportSettingsDTO sourcesDTO, @Context HttpHeaders headers) {
        if (!checkAdmin(headers)) return Response.status(Response.Status.FORBIDDEN).build();
        try {
            Settings settings = settingsController.getOrCreateSettings();
            
            if (sourcesDTO.getPrimarySource() != null) {
                settings.setPrimarySource(sourcesDTO.getPrimarySource());
            }
            if (sourcesDTO.getSecondarySource() != null) {
                settings.setSecondarySource(sourcesDTO.getSecondarySource());
            }
            settings.setYoutubeEnabled(sourcesDTO.isYoutubeEnabled());
            settings.setSpotdlEnabled(sourcesDTO.isSpotdlEnabled());
            if (sourcesDTO.getMaxRetryAttempts() > 0) {
                settings.setMaxRetryAttempts(sourcesDTO.getMaxRetryAttempts());
            }
            if (sourcesDTO.getRetryWaitTimeMs() > 0) {
                settings.setRetryWaitTimeMs(sourcesDTO.getRetryWaitTimeMs());
            }
            if (sourcesDTO.getSwitchStrategy() != null) {
                settings.setSwitchStrategy(sourcesDTO.getSwitchStrategy());
            }
            if (sourcesDTO.getSwitchThreshold() > 0) {
                settings.setSwitchThreshold(sourcesDTO.getSwitchThreshold());
            }
            settings.setEnableSmartRateLimitHandling(sourcesDTO.isEnableSmartRateLimitHandling());
            settings.setFallbackOnLongWait(sourcesDTO.isFallbackOnLongWait());
            if (sourcesDTO.getMaxAcceptableWaitTimeMs() > 0) {
                settings.setMaxAcceptableWaitTimeMs(sourcesDTO.getMaxAcceptableWaitTimeMs());
            }
            
            settings.setYoutubeForceIpv4(sourcesDTO.isYoutubeForceIpv4());
            settings.setYoutubeForceIpv6(sourcesDTO.isYoutubeForceIpv6());
            if (sourcesDTO.getYoutubeUserAgent() != null) {
                settings.setYoutubeUserAgent(sourcesDTO.getYoutubeUserAgent());
            }
            if (sourcesDTO.getYoutubeExtractorArgs() != null) {
                settings.setYoutubeExtractorArgs(sourcesDTO.getYoutubeExtractorArgs());
            }
            if (sourcesDTO.getYoutubeImpersonate() != null) {
                settings.setYoutubeImpersonate(sourcesDTO.getYoutubeImpersonate());
            }
            if (sourcesDTO.getYoutubeUpdateChannel() != null) {
                settings.setYoutubeUpdateChannel(sourcesDTO.getYoutubeUpdateChannel());
            }
            if (sourcesDTO.getYoutubePlayerClient() != null) {
                settings.setYoutubePlayerClient(sourcesDTO.getYoutubePlayerClient());
            }
            if (sourcesDTO.getTmdbApiKey() != null) {
                settings.setTmdbApiKey(sourcesDTO.getTmdbApiKey());
            }

            if (sourcesDTO.getPrimarySource() != null && sourcesDTO.getSecondarySource() != null
                && sourcesDTO.getPrimarySource() != Settings.DownloadSource.NONE
                && sourcesDTO.getSecondarySource() != Settings.DownloadSource.NONE
                && sourcesDTO.getPrimarySource().equals(sourcesDTO.getSecondarySource())) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(ApiResponse.error("Primary and secondary sources must be different"))
                        .build();
            }
            
            settingsService.save(settings);
            settingsController.addLog("Import source configuration updated");
            
            return Response.ok(ApiResponse.success(settings)).build();
             
        } catch (Exception e) {
            settingsController.addLog("Failed to update import sources: " + e.getMessage(), e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(ApiResponse.error("Failed to update import sources"))
                    .build();
        }
    }

    @POST
    @Path("/{profileId}/update-yt-dlp")
    public Response updateYtDlp(@PathParam("profileId") Long profileId, 
                                 @QueryParam("channel") String channel, @Context HttpHeaders headers) {
        if (!checkAdmin(headers)) return Response.status(Response.Status.FORBIDDEN).build();
        try {
            Settings.YtDlpUpdateChannel updateChannel;
            if (channel == null || channel.isBlank()) {
                Settings settings = settingsController.getOrCreateSettings();
                updateChannel = settings.getYoutubeUpdateChannel();
            } else {
                updateChannel = Settings.YtDlpUpdateChannel.valueOf(channel.toUpperCase());
            }
            
            LOGGER.info("Updating yt-dlp to channel: {}", updateChannel.getChannelName());
            
            CompletableFuture.runAsync(() -> {
                try {
                    installationService.updateYtDlp(updateChannel);
                    settingsController.addLog("yt-dlp updated to " + updateChannel.getChannelName() + " channel");
                } catch (Exception e) {
                    LOGGER.error("Failed to update yt-dlp", e);
                    settingsController.addLog("Failed to update yt-dlp: " + e.getMessage(), e);
                }
            });
            
            return Response.ok(ApiResponse.success("yt-dlp update initiated to " + updateChannel.getChannelName() + " channel")).build();
            
        } catch (Exception e) {
            settingsController.addLog("Failed to update yt-dlp: " + e.getMessage(), e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(ApiResponse.error("Failed to update yt-dlp: " + e.getMessage()))
                    .build();
        }
    }

    @POST
    @Path("/{profileId}/toggle-run-as-service")
    @Consumes(MediaType.WILDCARD)
    public Response toggleRunAsService(@PathParam("profileId") Long profileId, @Context HttpHeaders headers) {
        if (!checkAdmin(headers)) return Response.status(Response.Status.FORBIDDEN).build();
        settingsController.toggleAsService();
        return Response.ok(ApiResponse.success(settingsController.getOrCreateSettings())).build();
    }

    @POST
    @Path("/{profileId}/music-library-path") 
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response setMusicLibraryPath(@PathParam("profileId") Long profileId, @FormParam("musicLibraryPathInput") String path, @Context HttpHeaders headers) {
        if (!checkAdmin(headers)) return Response.status(Response.Status.FORBIDDEN).build();
        if (path == null || path.isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST).entity(ApiResponse.error("Music library path cannot be empty")).build();
        }

        settingsController.setMusicLibraryPath(path);
        playbackHistoryService.clearHistoryForAllProfiles();
        songService.clearAllSongs();
        settingsController.addLog("Cleared existing songs and history.");

        executor.submit(() -> {
            settingsController.scanLibrary();
        });

        return Response.ok(ApiResponse.success("Music library path updated and scan started.")).build();
    }

    @POST
    @Path("/{profileId}/resetLibrary")
    public Response resetLibrary(@PathParam("profileId") Long profileId, @Context HttpHeaders headers) {
        if (!checkAdmin(headers)) return Response.status(Response.Status.FORBIDDEN).build();
        settingsController.resetMusicLibrary();
        return Response.ok(ApiResponse.success(settingsController.getOrCreateSettings())).build();
    }

    @POST
    @Path("/{profileId}/scanLibrary")
    public Response scanLibrary(@PathParam("profileId") Long profileId, @QueryParam("directoryId") Long directoryId, @Context HttpHeaders headers) {
        if (!checkAdmin(headers)) return Response.status(Response.Status.FORBIDDEN).build();
        
        String dirPath = null;
        if (directoryId != null) {
            Models.MediaDirectory dir = mediaDirectoryService.findById(directoryId);
            if (dir != null && dir.enabled && dir.mediaType == Models.MediaDirectory.MediaType.MUSIC) {
                dirPath = dir.path;
            }
        }
        
        final String finalDirPath = dirPath;
        executor.submit(() -> {
            settingsController.scanLibrary(finalDirPath);
        }, "LibraryScanThread");

        String msg = directoryId != null ? "Scan started for directory" : "Full scan started";
        return Response.ok(ApiResponse.success(msg)).build();
    }

    @POST
    @Path("/{profileId}/scanVideo")
    public Response scanVideo(@PathParam("profileId") Long profileId, @QueryParam("directoryId") Long directoryId, @Context HttpHeaders headers) {
        if (!checkAdmin(headers)) return Response.status(Response.Status.FORBIDDEN).build();
        
        String dirPath = null;
        if (directoryId != null) {
            Models.MediaDirectory dir = mediaDirectoryService.findById(directoryId);
            if (dir != null && dir.enabled && dir.mediaType == Models.MediaDirectory.MediaType.VIDEO) {
                dirPath = dir.path;
            }
        }
        
        final String finalDirPath = dirPath;
        executor.submit(() -> {
            try {
                if (finalDirPath != null) {
                    videoImportService.scanAndProcess(Paths.get(finalDirPath));
                } else {
                    String videoPath = settingsService.getOrCreateSettings().getVideoLibraryPath();
                    if (videoPath != null && !videoPath.isBlank()) {
                        videoImportService.scanAndProcess(Paths.get(videoPath));
                    }
                }
                settingsController.addLog("Video scan completed");
            } catch (Exception e) {
                LOGGER.error("Video scan failed", e);
                settingsController.addLog("Video scan failed: " + e.getMessage());
            }
        }, "VideoScanThread");

        String msg = directoryId != null ? "Video scan started for directory" : "Video scan started";
        return Response.ok(ApiResponse.success(msg)).build();
    }

    @POST
    @Path("/{profileId}/clearLogs")
    public Response clearLogs(@PathParam("profileId") Long profileId, @Context HttpHeaders headers) {
        if (!checkAdmin(headers)) return Response.status(Response.Status.FORBIDDEN).build();
        settingsController.clearLogs();
        return Response.ok(ApiResponse.success(settingsController.getOrCreateSettings())).build();
    }

    @POST
    @Consumes(MediaType.WILDCARD)
    @Path("/clearPlaybackHistory/{profileId}")
    public Response clearPlaybackHistory(@PathParam("profileId") Long profileId, @Context HttpHeaders headers) {
        if (!checkAdmin(headers)) return Response.status(Response.Status.FORBIDDEN).build();
        playbackHistoryService.clearHistory(profileId);
        return Response.ok(ApiResponse.success("Playback history cleared")).build();
    }

    @POST
    @Path("/{profileId}/clearSongs")
    public Response clearSongs(@PathParam("profileId") Long profileId, @QueryParam("directoryId") Long directoryId, @Context HttpHeaders headers) {
        if (!checkAdmin(headers)) return Response.status(Response.Status.FORBIDDEN).build();
        try {
            String dirPath = null;
            if (directoryId != null) {
                Models.MediaDirectory dir = mediaDirectoryService.findById(directoryId);
                if (dir != null) dirPath = dir.path;
            }
            songService.clearSongsByDirectory(dirPath);
            String msg = directoryId != null ? "Songs cleared for directory" : "All songs cleared";
            settingsController.addLog(msg + " from database.");
            return Response.ok(ApiResponse.success(msg)).build();
        } catch (Exception e) {
            settingsController.addLog("Failed to clear songs DB: " + e.getMessage());
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity(ApiResponse.error("Failed to clear songs")).build();
        }
    }

    @POST
    @Path("/{profileId}/reloadMetadata")
    public Response reloadMetadata(@PathParam("profileId") Long profileId, @QueryParam("directoryId") Long directoryId, @Context HttpHeaders headers) {
        if (!checkAdmin(headers)) return Response.status(Response.Status.FORBIDDEN).build();
        
        String dirPath = null;
        if (directoryId != null) {
            Models.MediaDirectory dir = mediaDirectoryService.findById(directoryId);
            if (dir != null) dirPath = dir.path;
        }
        
        final String finalDirPath = dirPath;
        executor.submit(() -> {
            settingsController.reloadAllSongsMetadata(finalDirPath);
        }, "MetadataReloadThread");

        String msg = directoryId != null ? "Metadata reload started for directory" : "Metadata reload started";
        return Response.ok(ApiResponse.success(msg)).build();
    }

    @POST
    @Path("/{profileId}/deleteDuplicates")
    public Response deleteDuplicates(@PathParam("profileId") Long profileId, @QueryParam("directoryId") Long directoryId, @Context HttpHeaders headers) {
        if (!checkAdmin(headers)) return Response.status(Response.Status.FORBIDDEN).build();
        
        String dirPath = null;
        if (directoryId != null) {
            Models.MediaDirectory dir = mediaDirectoryService.findById(directoryId);
            if (dir != null) dirPath = dir.path;
        }
        
        final String finalDirPath = dirPath;
        executor.submit(() -> {
            settingsController.deleteDuplicateSongs(finalDirPath);
        }, "DeleteDuplicatesThread");

        String msg = directoryId != null ? "Duplicate check started for directory" : "Duplicate check started";
        return Response.ok(ApiResponse.success(msg)).build();
    }

    @POST
    @Path("/{profileId}/install-requirements")
    @Consumes(MediaType.WILDCARD)
    public Response installRequirements(@PathParam("profileId") Long profileId, @Context HttpHeaders headers) {
        if (!checkAdmin(headers)) return Response.status(Response.Status.FORBIDDEN).build();
        try {
            settingsController.addLog("Installation process started for profile: " + profileId);
            executor.submit(() -> {
                try {
                    settingsController.getImportService().installRequirements(profileId);
                    settingsController.addLog("Installation process completed successfully");
                } catch (Exception e) {
                    settingsController.addLog("Installation failed: " + e.getMessage(), e);
                }
            }, "InstallationThread");
            return Response.ok(ApiResponse.success("Installation process started")).build();
        } catch (Exception e) {
            settingsController.addLog("Failed to start installation: " + e.getMessage(), e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity(ApiResponse.error("Failed to start installation: " + e.getMessage())).build();
        }
    }

    @POST
    @Path("/{profileId}/upload-cookies")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response uploadCookies(@PathParam("profileId") Long profileId, Map<String, String> request, @Context HttpHeaders headers) {
        if (!checkAdmin(headers)) return Response.status(Response.Status.FORBIDDEN).build();
        try {
            String cookiesContent = request.get("cookiesContent");
            if (cookiesContent == null || cookiesContent.trim().isEmpty()) {
                return Response.status(Response.Status.BAD_REQUEST).entity(ApiResponse.error("No cookies content provided")).build();
            }

            PlatformOperations platformOps = platformOperationsFactory.getPlatformOperations();
            String cookiesStoragePath = platformOps.getCookiesStoragePath();
            java.nio.file.Path cookiesDir = java.nio.file.Paths.get(cookiesStoragePath).getParent();
            if (!java.nio.file.Files.exists(cookiesDir)) java.nio.file.Files.createDirectories(cookiesDir);
            java.nio.file.Files.writeString(java.nio.file.Paths.get(cookiesStoragePath), cookiesContent);

            if (!platformOps.validateCookiesFile(cookiesStoragePath)) {
                java.nio.file.Files.deleteIfExists(java.nio.file.Paths.get(cookiesStoragePath));
                return Response.status(Response.Status.BAD_REQUEST).entity(ApiResponse.error("Invalid cookies file format.")).build();
            }

            Settings settings = settingsController.getOrCreateSettings();
            settings.setCookiesFilePath(cookiesStoragePath);
            settingsService.save(settings);
            return Response.ok(ApiResponse.success("Cookies file uploaded successfully")).build();
        } catch (Exception e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity(ApiResponse.error("Failed to upload cookies file: " + e.getMessage())).build();
        }
    }

    @POST
    @Path("/{profileId}/rescan-song/{id}")
    public Response rescanSong(@PathParam("profileId") Long profileId, @PathParam("id") Long id, @Context HttpHeaders headers) {
        if (!checkAdmin(headers)) return Response.status(Response.Status.FORBIDDEN).build();
        try {
            songService.rescanSong(id);
            settingsController.addLog("Rescan started for song ID: " + id);
            return Response.ok(ApiResponse.success("Rescan started")).build();
        } catch (Exception e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity(ApiResponse.error("Failed to rescan song: " + e.getMessage())).build();
        }
    }

    @DELETE
    @Path("/{profileId}/songs/{id}")
    public Response deleteSong(@PathParam("profileId") Long profileId, @PathParam("id") Long id, @Context HttpHeaders headers) {
        if (!checkAdmin(headers)) return Response.status(Response.Status.FORBIDDEN).build();
        try {
            songService.deleteSong(id);
            settingsController.addLog("Song deleted with ID: " + id);
            return Response.ok(ApiResponse.success("Song deleted")).build();
        } catch (Exception e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity(ApiResponse.error("Failed to delete song: " + e.getMessage())).build();
        }
    }

    @DELETE
    @Path("/{profileId}/cookies")
    public Response deleteCookies(@PathParam("profileId") Long profileId, @Context HttpHeaders headers) {
        if (!checkAdmin(headers)) return Response.status(Response.Status.FORBIDDEN).build();
        try {
            Settings settings = settingsController.getOrCreateSettings();
            String cookiesFilePath = settings.getCookiesFilePath();
            if (cookiesFilePath != null && !cookiesFilePath.isBlank()) {
                java.nio.file.Path cookiesPath = java.nio.file.Paths.get(cookiesFilePath);
                if (java.nio.file.Files.exists(cookiesPath)) java.nio.file.Files.delete(cookiesPath);
            }
            settings.setCookiesFilePath(null);
            settingsService.save(settings);
            return Response.ok(ApiResponse.success("Cookies file deleted successfully")).build();
        } catch (Exception e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity(ApiResponse.error("Failed to delete cookies file")).build();
        }
    }

    // -----------------------------
    // MEDIA DIRECTORY MANAGEMENT
    // -----------------------------
    @Inject
    MediaDirectoryService mediaDirectoryService;

    @GET
    @Path("/{profileId}/directories")
    public Response listDirectories(@PathParam("profileId") Long profileId, @Context HttpHeaders headers) {
        if (!checkAdmin(headers)) return Response.status(Response.Status.FORBIDDEN).build();
        List<Models.MediaDirectory> dirs = mediaDirectoryService.listAll();
        return Response.ok(ApiResponse.success(dirs)).build();
    }

    @POST
    @Path("/{profileId}/directories")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response addDirectory(@PathParam("profileId") Long profileId, Map<String, String> data, @Context HttpHeaders headers) {
        if (!checkAdmin(headers)) return Response.status(Response.Status.FORBIDDEN).build();
        try {
            String path = data.get("path");
            String typeStr = data.get("type"); // "MUSIC" or "VIDEO"
            if (path == null || path.isBlank()) {
                return Response.status(Response.Status.BAD_REQUEST).entity(ApiResponse.error("Path is required")).build();
            }
            if (!mediaDirectoryService.validatePath(path)) {
                return Response.status(Response.Status.BAD_REQUEST).entity(ApiResponse.error("Invalid directory path")).build();
            }
            Models.MediaDirectory.MediaType type = Models.MediaDirectory.MediaType.VIDEO;
            if (typeStr != null && "MUSIC".equalsIgnoreCase(typeStr)) {
                type = Models.MediaDirectory.MediaType.MUSIC;
            }
            Models.MediaDirectory dir = mediaDirectoryService.addDirectory(path, type);
            return Response.ok(ApiResponse.success(dir)).build();
        } catch (Exception e) {
            LOGGER.error("Error adding directory", e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity(ApiResponse.error("Failed to add directory")).build();
        }
    }

    @DELETE
    @Path("/{profileId}/directories/{id}")
    public Response deleteDirectory(@PathParam("profileId") Long profileId, @PathParam("id") Long id, @Context HttpHeaders headers) {
        if (!checkAdmin(headers)) return Response.status(Response.Status.FORBIDDEN).build();
        try {
            mediaDirectoryService.removeDirectory(id);
            return Response.ok(ApiResponse.success("Directory removed")).build();
        } catch (Exception e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity(ApiResponse.error("Failed to remove directory")).build();
        }
    }

    @PUT
    @Path("/{profileId}/directories/{id}/enable")
    public Response toggleDirectory(@PathParam("profileId") Long profileId, @PathParam("id") Long id, Map<String, Boolean> data, @Context HttpHeaders headers) {
        if (!checkAdmin(headers)) return Response.status(Response.Status.FORBIDDEN).build();
        Boolean enabled = data.get("enabled");
        if (enabled == null) enabled = true;
        Models.MediaDirectory dir = mediaDirectoryService.updateEnabled(id, enabled);
        if (dir == null) {
            return Response.status(Response.Status.NOT_FOUND).entity(ApiResponse.error("Directory not found")).build();
        }
        return Response.ok(ApiResponse.success(dir)).build();
    }

    @POST
    @Path("/{profileId}/auto-skip")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response updateAutoSkip(@PathParam("profileId") Long profileId, Map<String, Object> data, @Context HttpHeaders headers) {
        if (!checkAdmin(headers)) return Response.status(Response.Status.FORBIDDEN).build();
        Settings settings = settingsController.getOrCreateSettings();

        if (data.containsKey("autoSkipIntro")) {
            settings.setAutoSkipIntro(Boolean.TRUE.equals(data.get("autoSkipIntro")));
        }
        if (data.containsKey("autoSkipRecap")) {
            settings.setAutoSkipRecap(Boolean.TRUE.equals(data.get("autoSkipRecap")));
        }
        if (data.containsKey("autoSkipOutro")) {
            settings.setAutoSkipOutro(Boolean.TRUE.equals(data.get("autoSkipOutro")));
        }

        settingsService.save(settings);
        settingsController.addLog("Auto-skip settings updated");
        return Response.ok(ApiResponse.success("Auto-skip settings updated")).build();
    }

    @POST
    @Path("/{profileId}/hardware-acceleration")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response setHardwareAcceleration(@PathParam("profileId") Long profileId, Map<String, Object> data, @Context HttpHeaders headers) {
        if (!checkAdmin(headers)) return Response.status(Response.Status.FORBIDDEN).build();
        try {
            Settings settings = settingsController.getOrCreateSettings();
            Object enabledVal = data.get("enabled");
            if (enabledVal instanceof Boolean) {
                settings.setHardwareAccelerationEnabled((Boolean) enabledVal);
            } else {
                return Response.status(Response.Status.BAD_REQUEST)
                    .entity(ApiResponse.error("'enabled' field must be a boolean"))
                    .build();
            }
            settingsService.save(settings);
            LOGGER.info("Hardware acceleration set to: {}", settings.getHardwareAccelerationEnabled());
            return Response.ok(ApiResponse.success("Hardware acceleration " + (settings.getHardwareAccelerationEnabled() ? "enabled" : "disabled"))).build();
        } catch (Exception e) {
            LOGGER.error("Failed to update hardware acceleration setting", e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                .entity(ApiResponse.error("Failed to update hardware acceleration setting: " + e.getMessage()))
                .build();
        }
    }

    @GET
    @Path("/{profileId}/default-player")
    public Response getDefaultPlayer(@PathParam("profileId") Long profileId) {
        Settings settings = settingsController.getOrCreateSettings();
        return Response.ok(ApiResponse.success(settings.getDefaultPlayer())).build();
    }

    @POST
    @Path("/{profileId}/default-player")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response setDefaultPlayer(@PathParam("profileId") Long profileId, Map<String, Object> data, @Context HttpHeaders headers) {
        if (!checkAdmin(headers)) return Response.status(Response.Status.FORBIDDEN).build();
        try {
            Object playerVal = data.get("defaultPlayer");
            if (playerVal == null || !(playerVal instanceof String)) {
                return Response.status(Response.Status.BAD_REQUEST)
                    .entity(ApiResponse.error("'defaultPlayer' must be a string: 'simple', 'videojs', or 'oplayer'"))
                    .build();
            }
            String player = (String) playerVal;
            if (!"simple".equals(player) && !"videojs".equals(player) && !"oplayer".equals(player)) {
                return Response.status(Response.Status.BAD_REQUEST)
                    .entity(ApiResponse.error("Invalid player. Must be 'simple', 'videojs', or 'oplayer'"))
                    .build();
            }
            Settings settings = settingsController.getOrCreateSettings();
            settings.setDefaultPlayer(player);
            settingsService.save(settings);
            settingsController.addLog("Default player set to: " + player);
            return Response.ok(ApiResponse.success("Default player updated to " + player)).build();
        } catch (Exception e) {
            LOGGER.error("Failed to update default player", e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                .entity(ApiResponse.error("Failed to update default player: " + e.getMessage()))
                .build();
        }
    }

    @POST
    @Path("/{profileId}/fixAlbums")
    public Response fixAlbums(@PathParam("profileId") Long profileId, @Context HttpHeaders headers) {
        if (!checkAdmin(headers)) return Response.status(Response.Status.FORBIDDEN).build();
        executor.submit(() -> settingsController.fixAlbums(), "FixAlbumsThread");
        return Response.ok(ApiResponse.success("Album fix started")).build();
    }
}
