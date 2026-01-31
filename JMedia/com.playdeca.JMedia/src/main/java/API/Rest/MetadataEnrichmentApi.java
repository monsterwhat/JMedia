package API.Rest;

import Controllers.ImportController;
import Models.Song;
import Services.FreeMetadataService;
import Services.SongService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

/**
 * REST API for metadata enrichment operations.
 */
@Path("/api/metadata")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class MetadataEnrichmentApi {

    private static final Logger LOGGER = LoggerFactory.getLogger(MetadataEnrichmentApi.class);

    @Inject
    ImportController importController;

    @Inject
    FreeMetadataService freeMetadataService;

    @Inject
    SongService songService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Enriches metadata for a single song.
     */
    @POST
    @Path("/enrich/{songId}")
    public Response enrichSingleSong(@PathParam("songId") Long songId, 
                                @QueryParam("profileId") Long profileId) {
        System.out.println("=== METADATA ENRICHMENT API CALLED ===");
        System.out.println("Song ID: " + songId);
        System.out.println("Profile ID: " + profileId);
        LOGGER.info("METADATA ENRICHMENT REQUEST - Song ID: {}, Profile ID: {}", songId, profileId);
        
        try {
            // Check if song needs enrichment
            var songOptional = Song.findByIdOptional(songId);
            if (songOptional.isEmpty()) {
                LOGGER.warn("Song not found for enrichment - Song ID: {}", songId);
                return Response.status(Response.Status.NOT_FOUND)
                        .entity("{\"error\": \"Song not found\"}")
                        .build();
            }

            Song song = (Song) songOptional.get();
            LOGGER.info("Song found for enrichment - ID: {}, Title: '{}', Artist: '{}', Album: '{}', Genre: '{}', ReleaseDate: '{}'", 
                song.id, song.getTitle(), song.getArtist(), song.getAlbum(), song.getGenre(), song.getReleaseDate());
            
            // Check if song already has good metadata
            boolean needsEnrichment = needsEnrichment(song);
            LOGGER.info("Enrichment needed check - NeedsEnrichment: {}, Current metadata completeness: Artist={}, Title={}, Album={}, Genre={}, ReleaseDate={}", 
                needsEnrichment, song.getArtist() != null, song.getTitle() != null, song.getAlbum() != null, song.getGenre() != null, song.getReleaseDate() != null);
            
            if (!needsEnrichment) {
                LOGGER.info("Song already has complete metadata - Skipping enrichment for Song ID: {}", songId);
                return Response.ok()
                        .entity("{\"message\": \"Song already has complete metadata\", \"needsEnrichment\": false}")
                        .build();
            }

            // Attempt enrichment
            String artist = song.getArtist();
            String title = song.getTitle();
            
            LOGGER.info("Starting metadata enrichment process for song ID {} - Artist: '{}', Title: '{}'", songId, artist, title);
            
            FreeMetadataService.MetadataResult enriched = freeMetadataService.enrichMetadata(artist, title);
            
            if (enriched.isEnriched()) {
                // Update song with enriched metadata
                LOGGER.info("FreeMetadataService returned successful enrichment for song ID {} - Sources: {}, Confidence: {}", 
                    songId, enriched.getSources(), enriched.getConfidence());
                updateSongMetadata(song, enriched);
                
                LOGGER.info("Successfully enriched and updated metadata for song ID {}: {} - {}", songId, artist, title);
                return Response.ok()
                        .entity(json(enriched))
                        .build();
            } else {
                LOGGER.warn("FreeMetadataService could not enrich metadata for song ID {}: {} - {} - Sources: {}", 
                    songId, artist, title, enriched.getSources());
                return Response.ok()
                        .entity("{\"message\": \"Could not enrich metadata\", \"needsEnrichment\": true}")
                        .build();
            }

        } catch (Exception e) {
            LOGGER.error("Error enriching metadata for song ID " + songId, e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                        .entity("{\"error\": \"" + e.getMessage() + "\"}")
                        .build();
        }
    }

    /**
     * Enriches metadata for multiple songs.
     */
    @POST
    @Path("/batch-enrich")
    public Response enrichBatchSongs(String requestBody,
                                @QueryParam("profileId") Long profileId) {
        try {
            JsonNode request = objectMapper.readTree(requestBody);
            
            if (!request.has("songIds")) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity("{\"error\": \"Missing songIds array\"}")
                        .build();
            }

            List<Long> songIds = new ArrayList<>();
            request.get("songIds").forEach(songId -> songIds.add(songId.asLong()));
            
            List<EnrichmentResult> results = new ArrayList<>();
            
            for (Long songId : songIds) {
                try {
                    var songOptional = Song.findByIdOptional(songId);
                    if (songOptional.isPresent() && needsEnrichment((Song) songOptional.get())) {
                        
                        Song song = (Song) songOptional.get();
                        String artist = song.getArtist();
                        String title = song.getTitle();
                        
                        FreeMetadataService.MetadataResult enriched = freeMetadataService.enrichMetadata(artist, title);
                        
                        if (enriched.isEnriched()) {
                            updateSongMetadata(song, enriched);
                            results.add(new EnrichmentResult(songId, true, "Successfully enriched"));
                            LOGGER.info("Enriched metadata for song ID {}: {} - {}", songId, artist, title);
                        } else {
                            results.add(new EnrichmentResult(songId, false, "No enrichment found"));
                        }
                    } else {
                        results.add(new EnrichmentResult(songId, false, "Song not found or no enrichment needed"));
                    }
                } catch (Exception e) {
                    LOGGER.error("Error enriching song ID " + songId, e);
                    results.add(new EnrichmentResult(songId, false, e.getMessage()));
                }
            }
            
            return Response.ok()
                    .entity(json(results))
                    .build();

        } catch (Exception e) {
            LOGGER.error("Error in batch enrichment", e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                        .entity("{\"error\": \"" + e.getMessage() + "\"}")
                        .build();
        }
    }

    /**
     * Enriches all songs in the library that need metadata enrichment.
     */
    @POST
    @Path("/enrich-all-missing")
    public Response enrichAllMissingSongs(@QueryParam("profileId") Long profileId) {
        try {
            // Find all songs that need enrichment
            var allSongs = songService.findAll();
            List<Song> songsNeedingEnrichment = allSongs.stream()
                    .filter(this::needsEnrichment)
                    .collect(Collectors.toList());

            if (songsNeedingEnrichment.isEmpty()) {
                return Response.ok()
                        .entity("{\"message\": \"No songs need metadata enrichment\"}")
                        .build();
            }

            LOGGER.info("Found {} songs needing metadata enrichment", songsNeedingEnrichment.size());

            List<EnrichmentResult> results = new ArrayList<>();
            
            for (Song song : songsNeedingEnrichment) {
                try {
                    String artist = song.getArtist();
                    String title = song.getTitle();
                    
                    FreeMetadataService.MetadataResult enriched = freeMetadataService.enrichMetadata(artist, title);
                    
                    if (enriched.isEnriched()) {
                        updateSongMetadata(song, enriched);
                        results.add(new EnrichmentResult(song.id, true, "Successfully enriched"));
                        LOGGER.info("Enriched metadata for song ID {}: {} - {}", song.id, artist, title);
                    } else {
                        results.add(new EnrichmentResult(song.id, false, "No enrichment found"));
                    }
                } catch (Exception e) {
                    LOGGER.error("Error enriching song ID " + song.id, e);
                    results.add(new EnrichmentResult(song.id, false, e.getMessage()));
                }
            }

            return Response.ok()
                    .entity(json(results))
                    .build();

        } catch (Exception e) {
            LOGGER.error("Error in library-wide enrichment", e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                        .entity("{\"error\": \"" + e.getMessage() + "\"}")
                        .build();
        }
    }

    /**
     * Gets metadata enrichment status for the library.
     */
    @GET
    @Path("/enrichment-status/{profileId}")
    public Response getEnrichmentStatus(@PathParam("profileId") Long profileId) {
        try {
            var allSongs = songService.findAll();
            
            long totalSongs = allSongs.size();
            long enrichedSongs = allSongs.stream()
                    .filter(song -> song.getArtworkBase64() != null || song.getGenre() != null)
                    .count();
            
            long songsNeedingEnrichment = allSongs.stream()
                    .filter(this::needsEnrichment)
                    .count();

            StatusResult status = new StatusResult();
            status.totalSongs = totalSongs;
            status.enrichedSongs = enrichedSongs;
            status.songsNeedingEnrichment = songsNeedingEnrichment;
            status.enrichmentPercentage = totalSongs > 0 ? (double) enrichedSongs / totalSongs * 100 : 0;

            return Response.ok()
                    .entity(json(status))
                    .build();

        } catch (Exception e) {
            LOGGER.error("Error getting enrichment status", e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                        .entity("{\"error\": \"" + e.getMessage() + "\"}")
                        .build();
        }
    }

    /**
     * Checks if a song needs metadata enrichment.
     */
    private boolean needsEnrichment(Song song) {
        return song.getArtworkBase64() == null || 
               song.getGenre() == null ||
               song.getReleaseDate() == null ||
               song.getAlbum() == null;
    }

    /**
     * Updates song metadata with enriched information.
     */
    private void updateSongMetadata(Song song, FreeMetadataService.MetadataResult enriched) {
        if (enriched.getArtist() != null) {
            song.setArtist(enriched.getArtist());
        }
        if (enriched.getTitle() != null) {
            song.setTitle(enriched.getTitle());
        }
        if (enriched.getAlbum() != null) {
            song.setAlbum(enriched.getAlbum());
        }
        if (enriched.getReleaseDate() != null) {
            song.setReleaseDate(enriched.getReleaseDate());
        }
        if (enriched.hasGenres()) {
            // Set primary genre (first one)
            List<String> genres = enriched.getGenres();
            if (!genres.isEmpty()) {
                song.setGenre(genres.get(0));
            }
        }
        
        // Save updated song
        songService.save(song);
    }

    /**
     * Helper method to convert object to JSON.
     */
    private String json(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            LOGGER.error("Error converting to JSON", e);
            return "{\"error\": \"JSON conversion error\"}";
        }
    }

    // Result classes for API responses
    private static class EnrichmentResult {
        public Long songId;
        public boolean success;
        public String message;
        
        public EnrichmentResult(Long songId, boolean success, String message) {
            this.songId = songId;
            this.success = success;
            this.message = message;
        }
    }

    private static class StatusResult {
        public long totalSongs;
        public long enrichedSongs;
        public long songsNeedingEnrichment;
        public double enrichmentPercentage;
    }
}