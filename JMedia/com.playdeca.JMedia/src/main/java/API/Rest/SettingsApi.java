package API.Rest;

import API.ApiResponse;
import Controllers.SettingsController;
import Models.Settings;
import Models.DTOs.ImportInstallationStatus;
import Services.PlaybackHistoryService;
import Services.SongService;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
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
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

    @POST
    @Path("/{profileId}/sidebar-position")
    public Response updateSidebarPosition(@PathParam("profileId") Long profileId, Map<String, String> data) {
        String position = data.get("position");
        if (position == null || (!position.equals("left") && !position.equals("right"))) {
            return Response.status(Response.Status.BAD_REQUEST).entity(ApiResponse.error("Invalid position")).build();
        }
        profileService.updateSidebarPosition(profileId, position);
        return Response.ok(ApiResponse.success("Sidebar position updated")).build();
    }

    @GET
    @Path("/{profileId}/sidebar-position")
    public Response getSidebarPosition(@PathParam("profileId") Long profileId) {
        Models.Profile profile = profileService.findById(profileId);
        if (profile == null) {
            return Response.status(Response.Status.NOT_FOUND).entity(ApiResponse.error("Profile not found")).build();
        }
        return Response.ok(ApiResponse.success(profile.sidebarPosition)).build();
    }

    @GET
    @Path("/{profileId}/browse-folder")
    public Response browseFolder(@PathParam("profileId") Long profileId) {
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
    public Response browseVideoFolder(@PathParam("profileId") Long profileId) {
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
                                       @FormParam("tmdbApiKey") String tmdbApiKey) {
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
    public Response validatePaths(@PathParam("profileId") Long profileId, Map<String, String> paths) {
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
    @Transactional
    public Response getSettings(@PathParam("profileId") Long profileId) {
        Settings settings = settingsController.getOrCreateSettings();
        return Response.ok(ApiResponse.success(settings)).build();
    }

    // -----------------------------
    // BPM TOLERANCE SETTINGS
    // -----------------------------
    @GET
    @Path("/{profileId}/bpm-tolerance")
    @Transactional
    public Response getBpmTolerance(@PathParam("profileId") Long profileId) {
        Settings settings = settingsController.getOrCreateSettings();
        Map<String, Object> bpmSettings = new HashMap<>();
        bpmSettings.put("default", settings.getBpmTolerance());
        bpmSettings.put("overrides", settings.getBpmToleranceOverrides());
        return Response.ok(ApiResponse.success(bpmSettings)).build();
    }

    @POST
    @Path("/{profileId}/bpm-tolerance")
    @Transactional
    public Response setBpmTolerance(@PathParam("profileId") Long profileId, Map<String, Object> bpmSettings) {
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

    // -----------------------------
    // IMPORT SOURCES CONFIGURATION
    // -----------------------------
    @POST
    @Path("/{profileId}/import-sources")
    @Transactional
    public Response updateImportSources(@PathParam("profileId") Long profileId, 
                                 ImportSettingsDTO sourcesDTO) {
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
    @Transactional
    public Response updateYtDlp(@PathParam("profileId") Long profileId, 
                                 @QueryParam("channel") String channel) {
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
    
    @GET
    @Path("/{profileId}/music-library-path")
    public Response getMusicLibraryPath(@PathParam("profileId") Long profileId) {
        String path = settingsController.getMusicLibraryPath();
        if (path != null && !path.isBlank()) {
            return Response.ok(ApiResponse.success(path)).build();
        } else {
            return Response.status(Response.Status.NOT_FOUND).entity(ApiResponse.error("Music library path not configured")).build();
        }
    } 

    @POST
    @Path("/{profileId}/toggle-run-as-service")
    @Transactional
    @Consumes(MediaType.WILDCARD)
    public Response toggleRunAsService(@PathParam("profileId") Long profileId) {
        settingsController.toggleAsService();
        return Response.ok(ApiResponse.success(settingsController.getOrCreateSettings())).build();
    }

    @POST
    @Path("/{profileId}/music-library-path") 
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response setMusicLibraryPath(@PathParam("profileId") Long profileId, @FormParam("musicLibraryPathInput") String path) {
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
    @Transactional
    public Response resetLibrary(@PathParam("profileId") Long profileId) {
        settingsController.resetMusicLibrary();
        return Response.ok(ApiResponse.success(settingsController.getOrCreateSettings())).build();
    }

    @POST
    @Path("/{profileId}/scanLibrary")
    public Response scanLibrary(@PathParam("profileId") Long profileId) {
        executor.submit(() -> {
            settingsController.scanLibrary();
        }, "LibraryScanThread");

        return Response.ok(ApiResponse.success("Full scan started")).build();
    }

    @POST
    @Path("/{profileId}/scanLibraryIncremental")
    public Response scanLibraryIncremental(@PathParam("profileId") Long profileId) {
        executor.submit(() -> {
            settingsController.scanLibraryIncremental();
        }, "IncrementalScanThread");

        return Response.ok(ApiResponse.success("Incremental scan started")).build();
    }

    @POST
    @Path("/{profileId}/clearLogs")
    @Transactional
    public Response clearLogs(@PathParam("profileId") Long profileId) {
        settingsController.clearLogs();
        return Response.ok(ApiResponse.success(settingsController.getOrCreateSettings())).build();
    }

    @GET
    @Path("/{profileId}/logs")
    @Transactional
    public Response getLogs(@PathParam("profileId") Long profileId) {
        return Response.ok(ApiResponse.success(settingsController.getLogs())).build();
    }

    @POST
    @Consumes(MediaType.WILDCARD)
    @Path("/clearPlaybackHistory/{profileId}")
    @Transactional
    public Response clearPlaybackHistory(@PathParam("profileId") Long profileId) {
        playbackHistoryService.clearHistory(profileId);
        return Response.ok(ApiResponse.success("Playback history cleared")).build();
    }

    @POST
    @Path("/{profileId}/clearSongs")
    @Transactional
    public Response clearSongs(@PathParam("profileId") Long profileId) {
        try {
            songService.clearAllSongs();
            settingsController.addLog("All songs cleared from database.");
            return Response.ok(ApiResponse.success("All songs deleted")).build();
        } catch (Exception e) {
            settingsController.addLog("Failed to clear songs DB: " + e.getMessage());
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity(ApiResponse.error("Failed to clear songs")).build();
        }
    }

    @POST
    @Path("/{profileId}/reloadMetadata")
    public Response reloadMetadata(@PathParam("profileId") Long profileId) {
        executor.submit(() -> {
            settingsController.reloadAllSongsMetadata();
        }, "MetadataReloadThread");

        return Response.ok(ApiResponse.success("Metadata reload started")).build();
    }

    @POST
    @Path("/{profileId}/rescan-song/{id}")
    public Response rescanSong(@PathParam("profileId") Long profileId, @PathParam("id") Long id) {
        try {
            songService.rescanSong(id);
            return Response.ok(ApiResponse.success("Song re-scan initiated for ID: " + id)).build();
        } catch (NotFoundException e) {
            return Response.status(Response.Status.NOT_FOUND).entity(ApiResponse.error(e.getMessage())).build();
        } catch (Exception e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity(ApiResponse.error("Failed to re-scan song: " + e.getMessage())).build();
        }
    }

    @DELETE
    @Path("/{profileId}/songs/{id}")
    public Response deleteSong(@PathParam("profileId") Long profileId, @PathParam("id") Long id) {
        try {
            songService.deleteSong(id);
            return Response.ok(ApiResponse.success("Song deleted with ID: " + id)).build();
        } catch (NotFoundException e) {
            return Response.status(Response.Status.NOT_FOUND).entity(ApiResponse.error(e.getMessage())).build();
        } catch (Exception e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity(ApiResponse.error("Failed to delete song: " + e.getMessage())).build();
        }
    }

    @POST
    @Path("/{profileId}/deleteDuplicates")
    public Response deleteDuplicates(@PathParam("profileId") Long profileId) {
        executor.submit(() -> {
            settingsController.deleteDuplicateSongs();
        }, "DeleteDuplicatesThread");

        return Response.ok(ApiResponse.success("Duplicate deletion started")).build();
    }

    @POST
    @Path("/{profileId}/install-requirements")
    @Consumes(MediaType.WILDCARD)
    public Response installRequirements(@PathParam("profileId") Long profileId) {
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
    
    @GET
    @Path("/{profileId}/install-status")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getInstallationStatus(@PathParam("profileId") Long profileId) {
        try {
            ImportInstallationStatus status;
            try {
                java.util.concurrent.Future<ImportInstallationStatus> future = java.util.concurrent.Executors.newSingleThreadExecutor().submit(() -> {
                    return settingsController.getImportService().getInstallationStatus();
                });
                status = future.get(10, java.util.concurrent.TimeUnit.SECONDS);
            } catch (Exception e) {
                status = new ImportInstallationStatus(false, false, false, false, false, false, false, "Status check failed", "Status check failed", "Status check failed", "Status check failed", "Status check failed", "Status check failed", "Status check failed");
            }
            
            java.util.Map<String, Object> response = new java.util.HashMap<>();
            response.put("chocoInstalled", status.chocoInstalled);
            response.put("pythonInstalled", status.pythonInstalled);
            response.put("spotdlInstalled", status.spotdlInstalled);
            response.put("ffmpegInstalled", status.ffmpegInstalled);
            response.put("whisperInstalled", status.whisperInstalled);
            response.put("allInstalled", status.isAllInstalled());
            response.put("messages", java.util.List.of(status.chocoMessage, status.pythonMessage, status.spotdlMessage, status.ffmpegMessage, status.whisperMessage));
            
            return Response.ok(ApiResponse.success(response)).build();
        } catch (Exception e) {
            return Response.ok(ApiResponse.success(new java.util.HashMap<>())).build();
        }
    }
    
    @GET
    @Path("/{profileId}/import-capability")
    public Response getImportCapabilityStatus(@PathParam("profileId") Long profileId) {
        boolean isInstalled = settingsController.getImportService().getInstallationStatus().isAllInstalled();
        return Response.ok(ApiResponse.success(isInstalled)).build();
    }

    @POST
    @Path("/{profileId}/upload-cookies")
    @Consumes(MediaType.APPLICATION_JSON)
    @Transactional
    public Response uploadCookies(@PathParam("profileId") Long profileId, Map<String, String> request) {
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

    @GET
    @Path("/{profileId}/cookies-status")
    @Transactional
    public Response getCookiesStatus(@PathParam("profileId") Long profileId) {
        try {
            Settings settings = settingsController.getOrCreateSettings();
            String cookiesFilePath = settings.getCookiesFilePath();
            java.util.Map<String, Object> status = new java.util.HashMap<>();
            if (cookiesFilePath != null && !cookiesFilePath.isBlank()) {
                java.nio.file.Path cookiesPath = java.nio.file.Paths.get(cookiesFilePath);
                status.put("configured", true);
                status.put("exists", java.nio.file.Files.exists(cookiesPath));
                status.put("path", cookiesFilePath);
            } else {
                status.put("configured", false);
                status.put("exists", false);
            }
            status.put("isLinux", System.getProperty("os.name").toLowerCase().contains("linux"));
            return Response.ok(ApiResponse.success(status)).build();
        } catch (Exception e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity(ApiResponse.error("Failed to get cookies status")).build();
        }
    }

    @DELETE
    @Path("/{profileId}/cookies")
    @Transactional
    public Response deleteCookies(@PathParam("profileId") Long profileId) {
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
}
