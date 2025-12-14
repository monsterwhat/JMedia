package API.Rest;

import Models.Song;
import Services.SongService;
import Controllers.SettingsController;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Path("/api/song") // A more general path for song-related operations
@Produces(MediaType.APPLICATION_JSON) // Default to JSON for a REST API
public class SongAPI {

    @Inject
    SongService songService;

    @Inject
    SettingsController settings;

    @GET
    @Path("/{id}/lyrics")
    @Produces(MediaType.TEXT_PLAIN) // Lyrics will be plain text
    public Response getSongLyrics(@PathParam("id") Long id) {
        System.out.println("Received request for lyrics for song ID: " + id);
        Song song = songService.find(id);
        if (song == null) {
            System.out.println("Song with ID " + id + " not found for lyrics request.");
            return Response.status(Response.Status.NOT_FOUND).entity("Song not found").build();
        }
        System.out.println("Song found: " + song.getTitle() + " - " + song.getArtist());
        if (song.getLyrics() == null || song.getLyrics().isBlank()) {
            System.out.println("No lyrics found for song ID: " + id);
            return Response.noContent().build(); // 204 No Content if no lyrics
        }
        System.out.println("Lyrics found for song ID: " + id + ", content length: " + song.getLyrics().length());
        System.out.println("Lyrics content being sent: " + song.getLyrics().substring(0, Math.min(song.getLyrics().length(), 200)) + "..."); // Log first 200 chars
        return Response.ok(song.getLyrics()).build();
    }

    @POST
    @Path("/{id}/generate-lyrics")
    @Produces(MediaType.TEXT_PLAIN)
    public Response generateLyrics(@PathParam("id") Long id, @jakarta.ws.rs.QueryParam("model") String model) {
        System.out.println("Received request to generate lyrics for song ID: " + id);
        Song song = songService.find(id);
        if (song == null) {
            System.out.println("Song with ID " + id + " not found.");
            return Response.status(Response.Status.NOT_FOUND).entity("Song not found").build();
        }

        if (model == null || model.isBlank()) {
            model = "tiny";
        }

        File musicFolder = settings.getMusicFolder();
        if (musicFolder == null) {
            System.out.println("Music folder not configured.");
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity("Music folder not configured.").build();
        }

        java.nio.file.Path songPath = Paths.get(musicFolder.getAbsolutePath(), song.getPath());
        System.out.println("Full path to song: " + songPath.toString());

        try {
            String escapedSongPath = songPath.toString().replace("'", "''"); // Escape single quotes for PowerShell
            ProcessBuilder pb = new ProcessBuilder(
                    "powershell.exe",
                    "-Command",
                    "py -3.13 -m whisper '" + escapedSongPath + "' --model " + model + " --output_format txt --output_dir -"
            );

            pb.redirectErrorStream(true);

            System.out.println("Starting Whisper process with command: " + String.join(" ", pb.command()));
            pb.environment().put("PYTHONIOENCODING", "utf-8");
            pb.environment().put("PYTHONUTF8", "1");
            Process process = pb.start();

            StringBuilder output = new StringBuilder();
            Pattern downloadProgressPattern = Pattern.compile("^\\s*(\\d{1,3})%"); // Corrected regex for leading percentage
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                    System.out.println("Whisper output: " + line);

                    Matcher matcher = downloadProgressPattern.matcher(line);
                    if (matcher.find()) {
                        settings.addLog("[Whisper Download Progress] " + matcher.group(1) + "%");
                    }
                }
            }

            int exitCode = process.waitFor();

            // Delete the folder "-" that Whisper creates
            File outDir = new File("-");
            deleteDirectory(outDir);

            System.out.println("Whisper process finished with exit code: " + exitCode);

            if (exitCode != 0) {
                System.out.println("Whisper process failed. Output:\n" + output.toString());
                return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity("Whisper process failed. See server logs for details.").build();
            }

            // Parse Whisper output to extract only lyrics
            String fullOutput = output.toString();
            StringBuilder parsedLyrics = new StringBuilder();
            Pattern timestampPattern = Pattern.compile("^\\[\\d{2}:\\d{2}\\.\\d{3} --> \\d{2}:\\d{2}\\.\\d{3}\\]");

            try (BufferedReader reader = new BufferedReader(new java.io.StringReader(fullOutput))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    Matcher matcher = timestampPattern.matcher(line);
                    if (matcher.find()) {
                        // Extract just the text part of the line after the timestamp
                        int textStartIndex = matcher.end();
                        if (textStartIndex < line.length()) {
                            parsedLyrics.append(line.substring(textStartIndex).trim()).append("\n");
                        }
                    }
                }
            } catch (IOException e) {
                System.out.println("Error parsing Whisper output: " + e.getMessage());
                // Fallback to full output if parsing fails
                parsedLyrics.append(fullOutput);
            }

            String lyrics = parsedLyrics.toString().trim(); // Use the parsed lyrics

            if (lyrics.isBlank()) {
                System.out.println("Parsed lyrics are empty for song ID: " + id + ". Full Whisper output:\n" + fullOutput);
                return Response.status(Response.Status.NO_CONTENT).entity("No lyrics could be extracted from Whisper output.").build();
            }

            song.setLyrics(lyrics);
            songService.save(song); // Changed from save to update
            System.out.println("Successfully generated and saved lyrics for song ID: " + id);

            return Response.ok(lyrics).build();
        } catch (IOException | InterruptedException e) {
            System.out.println("Failed to execute Whisper process for song ID: " + id);
            e.printStackTrace();
            Thread.currentThread().interrupt(); // Restore interrupted status
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity("Failed to execute Whisper process: " + e.getMessage()).build();
        }
    }

    private void deleteDirectory(File dir) {
        if (!dir.exists()) {
            return;
        }

        if (dir.isDirectory()) {
            for (File f : dir.listFiles()) {
                deleteDirectory(f);
            }
        }
        dir.delete();
    }

}
