package API.Rest;

import API.ApiResponse;
import Controllers.PlaybackController;
import Models.Settings;
import Models.Song;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.StreamingOutput;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.logging.Level;
import java.util.logging.Logger;

@Transactional
@Path("/api/music/stream")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class StreamAPI {

    @Inject
    private PlaybackController playbackController;

    private static final Logger LOGGER = Logger.getLogger(StreamAPI.class.getName());

    @GET
    @Path("/{profileId}/{id}")
    @Produces({"audio/mpeg", "application/octet-stream"})
    public Response streamMusicById(@PathParam("profileId") Long profileId, @PathParam("id") Long id, @HeaderParam("Range") String rangeHeader, @Context HttpHeaders headers) {
        try {
            Song song = playbackController.findSong(id);
            if (song == null) {
                LOGGER.warning("Stream requested for missing song ID " + id);
                return Response.status(Response.Status.NOT_FOUND).build();
            }
            String songPath = song.getPath();
            
            // Validate and sanitize the path to prevent directory traversal
            if (songPath == null || songPath.trim().isEmpty()) {
                LOGGER.warning("Invalid song path: null or empty");
                return Response.status(Response.Status.BAD_REQUEST).build();
            }
            
            // Normalize the path and remove any directory traversal attempts
            String normalizedSongPath = songPath.replace("..", "").replace("//", "/");
            // Remove any leading slashes to prevent absolute paths
            normalizedSongPath = normalizedSongPath.replaceFirst("^[/\\\\]+", "");
            
            File musicFolder = getMusicFolder();
            File file = new File(musicFolder, normalizedSongPath);
            
            // Additional security check: ensure the resolved file is within the music folder
            try {
                File canonicalMusicFolder = musicFolder.getCanonicalFile();
                File canonicalFile = file.getCanonicalFile();
                
                if (!canonicalFile.toPath().startsWith(canonicalMusicFolder.toPath())) {
                    LOGGER.warning("Path traversal attempt detected: " + songPath + " resolves to " + canonicalFile.getAbsolutePath());
                    return Response.status(Response.Status.FORBIDDEN).build();
                }
            } catch (IOException e) {
                LOGGER.warning("Error resolving canonical paths for security check: " + e.getMessage());
                return Response.status(Response.Status.INTERNAL_SERVER_ERROR).build();
            }
            LOGGER.info("Streaming song: " + song.getTitle() + " from path: " + file.getAbsolutePath());

            if (!file.exists() || file.isDirectory()) {
                LOGGER.warning("File not found or is directory: " + file.getAbsolutePath());
                return Response.status(Response.Status.NOT_FOUND).build();
            }

            long len = file.length(), start = 0, end = len - 1;
            if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
                String[] parts = rangeHeader.replace("bytes=", "").split("-");
                try {
                    start = Long.parseLong(parts[0]);
                    if (parts.length > 1 && !parts[1].isEmpty()) {
                        end = Long.parseLong(parts[1]);
                    }
                } catch (NumberFormatException e) {
                    LOGGER.warning("Invalid Range header: " + rangeHeader);
                }
            }

            if (start >= len) {
                LOGGER.warning("Requested range start beyond file length: start=" + start + ", len=" + len);
                return Response.status(Response.Status.REQUESTED_RANGE_NOT_SATISFIABLE)
                        .header("Content-Range", "bytes */" + len)
                        .build();
            }

            long contentLength = end - start + 1;
            final long trueStart = start;

            StreamingOutput stream = out -> {
                try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
                    raf.seek(trueStart);
                    byte[] buf = new byte[calculateOptimalBufferSize(file.length(), headers)];
                    long left = contentLength;
                    int read;
                    while ((read = raf.read(buf)) != -1 && left > 0) {
                        int toWrite = (int) Math.min(read, left);
                        try {
                            out.write(buf, 0, toWrite);
                        } catch (IOException e) {
                            // Client disconnected: just stop streaming, log at fine/debug level
                            LOGGER.fine(() -> "Client disconnected while streaming " + file.getName() + ": " + e.getMessage());
                            break;
                        }
                        left -= toWrite;
                    }
                    // No flush needed: container handles it
                } catch (IOException e) {
                    // Other I/O errors
                    LOGGER.log(Level.WARNING, "Error streaming file " + file.getName(), e);
                }
            };

            LOGGER.info("Streaming response prepared: bytes=" + start + "-" + end + "/" + len);
            return Response.status(rangeHeader != null ? 206 : 200)
                    .header("Accept-Ranges", "bytes")
                    .header("Content-Range", "bytes " + start + "-" + end + "/" + len)
                    .header("Cache-Control", "public,max-age=3600")
                    .entity(stream)
                    .build();

        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Error streaming music file", e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * Calculates optimal buffer size based on file size and client characteristics
     */
    private int calculateOptimalBufferSize(long fileLength, HttpHeaders headers) {
        // Default to 32KB for modern connections
        int defaultSize = 32 * 1024;
        int maxSize = 64 * 1024;
        
        // For smaller files, use proportionally smaller buffers
        if (fileLength < 1024 * 1024) { // < 1MB files
            return Math.min(16 * 1024, fileLength > 0 ? (int) fileLength : 16 * 1024);
        }
        
        // Detect connection type from User-Agent or headers
        String userAgent = headers.getHeaderString("User-Agent");
        if (userAgent != null && (userAgent.contains("Mobile") || userAgent.contains("Android") || userAgent.contains("iPhone"))) {
            return 16 * 1024; // Smaller for mobile to reduce memory usage
        }
        
        return defaultSize;
    }

    private File getMusicFolder() {
        Settings settings = playbackController.getSettings();
        if (settings == null || settings.getLibraryPath() == null) {
            return new File("./"); // fallback
        }
        return new File(settings.getLibraryPath());
    }
}
