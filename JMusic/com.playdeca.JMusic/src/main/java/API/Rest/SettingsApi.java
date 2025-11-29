package API.Rest;

import API.ApiResponse;
import Controllers.SettingsController;
import Models.Settings;
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
    @Path("/browse-folder")
    public Response browseFolder() {
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
    @Path("/browse-video-folder")
    public Response browseVideoFolder() {
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
    @Path("/video-library-path")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response setVideoLibraryPath(@FormParam("videoLibraryPathInput") String path) {
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
    // GET CURRENT SETTINGS
    // -----------------------------
    @GET
    @Transactional
    public Response getSettings() {
        Settings settings = settingsController.getOrCreateSettings();
        return Response.ok(ApiResponse.success(settings)).build();
    }

    // -----------------------------
    // GET MUSIC LIBRARY PATH
    // -----------------------------
    @GET
    @Path("/music-library-path")
    public Response getMusicLibraryPath() {
        String path = settingsController.getMusicLibraryPath();
        if (path != null && !path.isBlank()) {
            return Response.ok(ApiResponse.success(path)).build();
        } else {
            return Response.status(Response.Status.NOT_FOUND).entity(ApiResponse.error("Music library path not configured")).build();
        }
    }
    
    @POST
    @Path("/import")
    @Transactional
    public Response updateImportSettings(ImportSettingsDTO importSettings) {
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
    @Path("/toggle-run-as-service")
    @Transactional
    @Consumes(MediaType.WILDCARD)
    public Response toggleRunAsService() {
        settingsController.toggleAsService();
        return Response.ok(ApiResponse.success(settingsController.getOrCreateSettings())).build();
    }

    // -----------------------------
    // SET MUSIC LIBRARY PATH
    // -----------------------------
    @POST
    @Path("/music-library-path")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response setMusicLibraryPath(@FormParam("musicLibraryPathInput") String path) {
        if (path == null || path.isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST).entity(ApiResponse.error("Music library path cannot be empty")).build();
        }

        // Step 1: Update the path. This is transactional within the service.
        settingsController.setMusicLibraryPath(path);

        // Step 2: Clear history and songs. These are transactional within their services.
        playbackHistoryService.clearHistory();
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
    @Path("/resetLibrary")
    @Transactional
    public Response resetLibrary() {
        settingsController.resetMusicLibrary();
        return Response.ok(ApiResponse.success(settingsController.getOrCreateSettings())).build();
    }

    // -----------------------------
    // SCAN LIBRARY
    // -----------------------------
    @POST
    @Path("/scanLibrary")
    public Response scanLibrary() {
        executor.submit(() -> {
            settingsController.scanLibrary();
        }, "LibraryScanThread");

        return Response.ok(ApiResponse.success("Scan started")).build();
    }

    // -----------------------------
    // CLEAR LOGS
    // -----------------------------
    @POST
    @Path("/clearLogs")
    @Transactional
    public Response clearLogs() {
        settingsController.clearLogs();
        return Response.ok(ApiResponse.success(settingsController.getOrCreateSettings())).build();
    }

    // -----------------------------
    // GET LOGS
    // -----------------------------
    @GET
    @Path("/logs")
    @Transactional
    public Response getLogs() {
        return Response.ok(ApiResponse.success(settingsController.getLogs())).build();
    }

    // -----------------------------
    // CLEAR PLAYBACK HISTORY
    // -----------------------------
    @POST
    @Consumes(MediaType.WILDCARD)
    @Path("/clearPlaybackHistory")
    @Transactional
    public Response clearPlaybackHistory() {
        playbackHistoryService.clearHistory();
        return Response.ok(ApiResponse.success("Playback history cleared")).build();
    }

    // -----------------------------
    // CLEAR SONGS DATABASE
    // -----------------------------
    @POST
    @Path("/clearSongs")
    @Transactional
    public Response clearSongs() {
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
    @Path("/reloadMetadata")
    public Response reloadMetadata() {
        executor.submit(() -> {
            settingsController.reloadAllSongsMetadata();
        }, "MetadataReloadThread");

        return Response.ok(ApiResponse.success("Metadata reload started")).build();
    }

    // -----------------------------
    // RESCAN SINGLE SONG METADATA
    // -----------------------------
    @POST
    @Path("/rescan-song/{id}")
    public Response rescanSong(@PathParam("id") Long id) {
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
    @Path("/songs/{id}")
    public Response deleteSong(@PathParam("id") Long id) {
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
    @Path("/deleteDuplicates")
    public Response deleteDuplicates() {
        executor.submit(() -> {
            settingsController.deleteDuplicateSongs();
        }, "DeleteDuplicatesThread");

        return Response.ok(ApiResponse.success("Duplicate deletion started")).build();
    }
    // -----------------------------
    // CHECK IMPORT CAPABILITY
    // -----------------------------
    @GET
    @Path("/import-capability")
    public Response getImportCapabilityStatus() {
        boolean isInstalled = settingsController.getImportService().getInstallationStatus().isAllInstalled();
        return Response.ok(ApiResponse.success(isInstalled)).build();
    }
  
}
