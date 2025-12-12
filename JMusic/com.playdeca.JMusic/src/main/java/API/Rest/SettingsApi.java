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
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import javax.swing.JFileChooser;
import javax.swing.SwingUtilities;
import java.io.File;
import java.util.concurrent.CompletableFuture;
import org.eclipse.microprofile.context.ManagedExecutor;
import Models.DTOs.ImportSettingsDTO;
import Services.SettingsService;
import java.util.HashMap;
import java.util.Map;

@Path("/api/settings")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class SettingsApi {

    @Inject
    private SettingsController settingsController;

    @Inject
    private PlaybackHistoryService playbackHistoryService;

    @Inject
    private SongService songService;
    
    @Inject
    private SettingsService settingsService;

    @Inject
    ManagedExecutor executor;

    // -----------------------------
    // BROWSE FOLDER
    // -----------------------------
    @GET
    @Path("/{profileId}/browse-folder")
    public Response browseFolder(@PathParam("profileId") Long profileId) {
        CompletableFuture<String> selectedPathFuture = new CompletableFuture<>();

        SwingUtilities.invokeLater(() -> {
            JFileChooser fileChooser = new JFileChooser();
            fileChooser.setDialogTitle("Select Music Folder");
            fileChooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            fileChooser.setAcceptAllFileFilterUsed(false); // Disable "All Files" option

            // Set initial directory if available
            String currentPath = settingsController.getMusicLibraryPath();
            if (currentPath != null && !currentPath.isBlank()) {
                File currentDir = new File(currentPath);
                if (currentDir.exists() && currentDir.isDirectory()) {
                    fileChooser.setCurrentDirectory(currentDir);
                }
            }

            int userSelection = fileChooser.showOpenDialog(null); // null for parent component

            if (userSelection == JFileChooser.APPROVE_OPTION) {
                File selectedFile = fileChooser.getSelectedFile();
                selectedPathFuture.complete(selectedFile.getAbsolutePath());
            } else {
                selectedPathFuture.complete(null);
            }
        });

        try {
            String selectedPath = selectedPathFuture.get(); // Block and wait for the result
            if (selectedPath != null) {
                return Response.ok(ApiResponse.success(selectedPath)).build();
            } else {
                return Response.status(Response.Status.NO_CONTENT).entity(ApiResponse.error("No folder selected")).build();
            }
        } catch (Exception e) {
            settingsController.addLog("Error opening folder chooser: " + e.getMessage(), e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity(ApiResponse.error("Error opening folder chooser")).build();
        }
    }

    @GET
    @Path("/{profileId}/browse-video-folder")
    public Response browseVideoFolder(@PathParam("profileId") Long profileId) {
        CompletableFuture<String> selectedPathFuture = new CompletableFuture<>();

        SwingUtilities.invokeLater(() -> {
            JFileChooser fileChooser = new JFileChooser();
            fileChooser.setDialogTitle("Select Video Folder");
            fileChooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            fileChooser.setAcceptAllFileFilterUsed(false); // Disable "All Files" option

            // Set initial directory if available
            String currentPath = settingsController.getOrCreateSettings().getVideoLibraryPath();
            if (currentPath != null && !currentPath.isBlank()) {
                File currentDir = new File(currentPath);
                if (currentDir.exists() && currentDir.isDirectory()) {
                    fileChooser.setCurrentDirectory(currentDir);
                }
            }

            int userSelection = fileChooser.showOpenDialog(null); // null for parent component

            if (userSelection == JFileChooser.APPROVE_OPTION) {
                File selectedFile = fileChooser.getSelectedFile();
                selectedPathFuture.complete(selectedFile.getAbsolutePath());
            } else {
                selectedPathFuture.complete(null);
            }
        });

        try {
            String selectedPath = selectedPathFuture.get(); // Block and wait for the result
            if (selectedPath != null) {
                return Response.ok(ApiResponse.success(selectedPath)).build();
            } else {
                return Response.status(Response.Status.NO_CONTENT).entity(ApiResponse.error("No folder selected")).build();
            }
        } catch (Exception e) {
            settingsController.addLog("Error opening folder chooser: " + e.getMessage(), e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity(ApiResponse.error("Error opening folder chooser")).build();
        }
    }
    
    @POST
    @Path("/{profileId}/video-library-path")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response setVideoLibraryPath(@PathParam("profileId") Long profileId, @FormParam("videoLibraryPathInput") String path) {
        if (path == null || path.isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST).entity(ApiResponse.error("Video library path cannot be empty")).build();
        }
        
        Settings settings = settingsController.getOrCreateSettings();
        settings.setVideoLibraryPath(path);
        settingsService.save(settings);
        settingsController.addLog("Video library path updated to: " + path);

        return Response.ok(ApiResponse.success("Video library path updated.")).build();
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
    // GET MUSIC LIBRARY PATH
    // -----------------------------
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
    @Path("/{profileId}/import")
    @Transactional
    public Response updateImportSettings(@PathParam("profileId") Long profileId, ImportSettingsDTO importSettings) {
        if (importSettings == null) {
            return Response.status(Response.Status.BAD_REQUEST).entity(ApiResponse.error("Invalid import settings data.")).build();
        }
        
        Settings settings = settingsController.getOrCreateSettings();
        
        settings.setOutputFormat(importSettings.getOutputFormat());
        settings.setDownloadThreads(importSettings.getDownloadThreads());
        settings.setSearchThreads(importSettings.getSearchThreads());
        
        settingsService.save(settings);
        
        settingsController.addLog("Import settings updated.");
        
        return Response.ok(ApiResponse.success(settings)).build();
    }

    // -----------------------------
    // TOGGLE RUN AS SERVICE
    // -----------------------------
    @POST
    @Path("/{profileId}/toggle-run-as-service")
    @Transactional
    @Consumes(MediaType.WILDCARD)
    public Response toggleRunAsService(@PathParam("profileId") Long profileId) {
        settingsController.toggleAsService();
        return Response.ok(ApiResponse.success(settingsController.getOrCreateSettings())).build();
    }

    // -----------------------------
    // SET MUSIC LIBRARY PATH
    // -----------------------------
    @POST
    @Path("/{profileId}/music-library-path")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response setMusicLibraryPath(@PathParam("profileId") Long profileId, @FormParam("musicLibraryPathInput") String path) {
        if (path == null || path.isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST).entity(ApiResponse.error("Music library path cannot be empty")).build();
        }

        // Step 1: Update the path. This is transactional within the service.
        settingsController.setMusicLibraryPath(path);

        // Step 2: Clear history and songs. These are transactional within their services.
        playbackHistoryService.clearHistoryForAllProfiles();
        songService.clearAllSongs();
        settingsController.addLog("Cleared existing songs and history.");

        // Step 3: Trigger the scan asynchronously.
        executor.submit(() -> {
            settingsController.scanLibrary();
        });

        return Response.ok(ApiResponse.success("Music library path updated and scan started.")).build();
    }

    // -----------------------------
    // RESET LIBRARY PATH
    // -----------------------------
    @POST
    @Path("/{profileId}/resetLibrary")
    @Transactional
    public Response resetLibrary(@PathParam("profileId") Long profileId) {
        settingsController.resetMusicLibrary();
        return Response.ok(ApiResponse.success(settingsController.getOrCreateSettings())).build();
    }

    // -----------------------------
    // SCAN LIBRARY
    // -----------------------------
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

    // -----------------------------
    // CLEAR LOGS
    // -----------------------------
    @POST
    @Path("/{profileId}/clearLogs")
    @Transactional
    public Response clearLogs(@PathParam("profileId") Long profileId) {
        settingsController.clearLogs();
        return Response.ok(ApiResponse.success(settingsController.getOrCreateSettings())).build();
    }

    // -----------------------------
    // GET LOGS
    // -----------------------------
    @GET
    @Path("/{profileId}/logs")
    @Transactional
    public Response getLogs(@PathParam("profileId") Long profileId) {
        return Response.ok(ApiResponse.success(settingsController.getLogs())).build();
    }

    // -----------------------------
    // CLEAR PLAYBACK HISTORY
    // -----------------------------
    @POST
    @Consumes(MediaType.WILDCARD)
    @Path("/clearPlaybackHistory/{profileId}")
    @Transactional
    public Response clearPlaybackHistory(@PathParam("profileId") Long profileId) {
        playbackHistoryService.clearHistory(profileId);
        return Response.ok(ApiResponse.success("Playback history cleared")).build();
    }

    // -----------------------------
    // CLEAR SONGS DATABASE
    // -----------------------------
    @POST
    @Path("/{profileId}/clearSongs")
    @Transactional
    public Response clearSongs(@PathParam("profileId") Long profileId) {
        try {
            songService.clearAllSongs();
            settingsController.addLog("All songs cleared from database.");
            return Response.ok(ApiResponse.success("All songs deleted")).build();
        } catch (Exception e) {
            e.printStackTrace();
            settingsController.addLog("Failed to clear songs DB: " + e.getMessage());
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity(ApiResponse.error("Failed to clear songs")).build();
        }
    }

    // -----------------------------
    // RELOAD METADATA
    // -----------------------------
    @POST
    @Path("/{profileId}/reloadMetadata")
    public Response reloadMetadata(@PathParam("profileId") Long profileId) {
        executor.submit(() -> {
            settingsController.reloadAllSongsMetadata();
        }, "MetadataReloadThread");

        return Response.ok(ApiResponse.success("Metadata reload started")).build();
    }

    // -----------------------------
    // RESCAN SINGLE SONG METADATA
    // -----------------------------
    @POST
    @Path("/{profileId}/rescan-song/{id}")
    public Response rescanSong(@PathParam("profileId") Long profileId, @PathParam("id") Long id) {
        try {
            songService.rescanSong(id);
            return Response.ok(ApiResponse.success("Song re-scan initiated for ID: " + id)).build();
        } catch (NotFoundException e) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(ApiResponse.error(e.getMessage()))
                    .build();
        } catch (Exception e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(ApiResponse.error("Failed to re-scan song: " + e.getMessage()))
                    .build();
        }
    }

    // -----------------------------
    // DELETE SINGLE SONG
    // -----------------------------
    @DELETE
    @Path("/{profileId}/songs/{id}")
    public Response deleteSong(@PathParam("profileId") Long profileId, @PathParam("id") Long id) {
        try {
            songService.deleteSong(id);
            return Response.ok(ApiResponse.success("Song deleted with ID: " + id)).build();
        } catch (NotFoundException e) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(ApiResponse.error(e.getMessage()))
                    .build();
        } catch (Exception e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(ApiResponse.error("Failed to delete song: " + e.getMessage()))
                    .build();
        }
    }

    // -----------------------------
    // DELETE DUPLICATES
    // -----------------------------
    @POST
    @Path("/{profileId}/deleteDuplicates")
    public Response deleteDuplicates(@PathParam("profileId") Long profileId) {
        executor.submit(() -> {
            settingsController.deleteDuplicateSongs();
        }, "DeleteDuplicatesThread");

        return Response.ok(ApiResponse.success("Duplicate deletion started")).build();
    }
    // -----------------------------
    // INSTALL REQUIREMENTS
    // -----------------------------
    @POST
    @Path("/{profileId}/install-requirements")
    @Consumes(MediaType.WILDCARD)
    public Response installRequirements(@PathParam("profileId") Long profileId) {
        try {
            settingsController.addLog("Installation process started for profile: " + profileId);
            
            // Start installation in background thread
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
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(ApiResponse.error("Failed to start installation: " + e.getMessage()))
                    .build();
        }
    }
    
@GET
    @Path("/{profileId}/install-status")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getInstallationStatus(@PathParam("profileId") Long profileId) {
        try {
            // Check current installation status
            ImportInstallationStatus status = settingsController.getImportService().getInstallationStatus();
            
            java.util.Map<String, Object> response = new java.util.HashMap<>();
            response.put("pythonInstalled", status.pythonInstalled);
            response.put("spotdlInstalled", status.spotdlInstalled);
            response.put("ffmpegInstalled", status.ffmpegInstalled);
            response.put("whisperInstalled", status.whisperInstalled);
            response.put("allInstalled", status.isAllInstalled());
            response.put("messages", java.util.List.of(
                status.pythonMessage,
                status.spotdlMessage,
                status.ffmpegMessage,
                status.whisperMessage
            ));
            
            return Response.ok(ApiResponse.success(response)).build();
        } catch (Exception e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(ApiResponse.error("Failed to get installation status: " + e.getMessage()))
                    .build();
        }
    }
    
    // -----------------------------
    // CHECK IMPORT CAPABILITY
    // -----------------------------
    @GET
    @Path("/{profileId}/import-capability")
    public Response getImportCapabilityStatus(@PathParam("profileId") Long profileId) {
        boolean isInstalled = settingsController.getImportService().getInstallationStatus().isAllInstalled();
        return Response.ok(ApiResponse.success(isInstalled)).build();
    }
   
  
}
