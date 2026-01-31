package Services;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Service for enriching metadata using free, no-authentication APIs.
 * Prioritizes MusicBrainz -> Deezer -> TheAudioDB for comprehensive metadata.
 */
@ApplicationScoped
public class FreeMetadataService {

    private static final Logger LOGGER = LoggerFactory.getLogger(FreeMetadataService.class);
    
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();
    
    private final ObjectMapper objectMapper = new ObjectMapper();

    // API Endpoints
    private static final String MUSICBRAINZ_API = "https://musicbrainz.org/ws/2/recording/?query=%s&fmt=json";
    private static final String DEEZER_API = "https://api.deezer.com/search/track/?q=%s&limit=5";
    private static final String THEAUDIODB_API = "https://www.theaudiodb.com/api/v1/json/1/search.php?s=%s";
    
    // Patterns for extracting data
    private static final Pattern RELEASE_DATE_PATTERN = Pattern.compile("(\\d{4})");
    private static final Pattern GENRE_PATTERN = Pattern.compile("[\\w\\s-]+");
    private static final Pattern ARTIST_PATTERN = Pattern.compile("^([^\\[]+)");
    
    /**
     * Enriches metadata for a given artist and title combination.
     * Searches multiple APIs and merges results with confidence scoring.
     */
    public MetadataResult enrichMetadata(String artist, String title) {
        try {
            LOGGER.info("Starting metadata enrichment for: {} - {}", artist, title);
            
            MetadataResult result = new MetadataResult();
            
            // Primary: MusicBrainz search
            Optional<MusicBrainzData> mbResult = searchMusicBrainz(artist, title);
            if (mbResult.isPresent()) {
                MusicBrainzData mb = mbResult.get();
                result.setArtist(mb.getArtist());
                result.setTitle(mb.getTitle());
                result.setReleaseDate(mb.getReleaseDate());
                result.setTrackNumber(mb.getTrackNumber());
                result.setAlbum(mb.getAlbum());
                result.addAllGenres(mb.getGenres());
                result.addSource("MusicBrainz");
                result.setConfidence(0.9);
                LOGGER.info("Found MusicBrainz data: {} - {}", mb.getArtist(), mb.getTitle());
            }
            
            // Secondary: Deezer for album art and additional genres
            Optional<DeezerData> deezerResult = searchDeezer(artist, title);
            if (deezerResult.isPresent()) {
                DeezerData dz = deezerResult.get();
                
                // Update album art if higher quality
                if (dz.hasAlbumArt() && !result.hasAlbumArt()) {
                    result.setAlbumArtUrl(dz.getAlbumArtUrl());
                    result.setAlbumArtSize(dz.getAlbumArtSize());
                    result.addSource("Deezer (Art)");
                    LOGGER.info("Added album art from Deezer");
                }
                
                // Merge additional genres
                if (dz.hasGenres()) {
                    result.addAllGenres(dz.getGenres());
                    result.addSource("Deezer (Genres)");
                }
            }
            
            // Tertiary: TheAudioDB for backup album art
            if (!result.hasAlbumArt()) {
                Optional<TheAudioDbData> audioDbResult = searchTheAudioDb(artist, title);
                if (audioDbResult.isPresent()) {
                    TheAudioDbData adb = audioDbResult.get();
                    result.setAlbumArtUrl(adb.getAlbumArtUrl());
                    result.setAlbumArtSize(adb.getAlbumArtSize());
                    result.addSource("TheAudioDB (Art)");
                    LOGGER.info("Added backup album art from TheAudioDB");
                }
            }
            
            // Deduplicate genres
            result.deduplicateGenres();
            
            if (result.isEnriched()) {
                LOGGER.info("Successfully enriched metadata for {} - {}", artist, title);
            } else {
                LOGGER.warn("Could not enrich metadata for {} - {}", artist, title);
            }
            
            return result;
            
        } catch (Exception e) {
            LOGGER.error("Error enriching metadata for: {} - {}", artist, title, e);
            return new MetadataResult(); // Return empty result on error
        }
    }
    
    /**
     * Searches MusicBrainz API for recording information.
     */
    private Optional<MusicBrainzData> searchMusicBrainz(String artist, String title) {
        try {
            String query = String.format("artist:\"%s\" AND title:\"%s\"", 
                artist.replace(" ", "+"), title.replace(" ", "+"));
            String url = String.format(MUSICBRAINZ_API, 
                java.net.URLEncoder.encode(query, "UTF-8"));
            
            Thread.sleep(1000); // Rate limiting: 1 request per second
            
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("User-Agent", "JMedia/1.0")
                    .timeout(Duration.ofSeconds(30))
                    .GET()
                    .build();
            
            HttpResponse<String> response = httpClient.send(request, 
                HttpResponse.BodyHandlers.ofString());
            
            if (response.statusCode() == 200) {
                JsonNode root = objectMapper.readTree(response.body());
                JsonNode recordings = root.path("recordings");
                
                if (recordings.isArray() && recordings.size() > 0) {
                    JsonNode firstRecording = recordings.get(0);
                    return Optional.of(parseMusicBrainzResult(firstRecording));
                }
            }
            
        } catch (Exception e) {
            LOGGER.error("MusicBrainz search failed", e);
        }
        
        return Optional.empty();
    }
    
    /**
     * Searches Deezer API for track information and album art.
     */
    private Optional<DeezerData> searchDeezer(String artist, String title) {
        try {
            String query = artist + " " + title;
            String url = String.format(DEEZER_API, 
                java.net.URLEncoder.encode(query, "UTF-8"));
            
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("User-Agent", "JMedia/1.0")
                    .timeout(Duration.ofSeconds(30))
                    .GET()
                    .build();
            
            HttpResponse<String> response = httpClient.send(request, 
                HttpResponse.BodyHandlers.ofString());
            
            if (response.statusCode() == 200) {
                JsonNode root = objectMapper.readTree(response.body());
                JsonNode tracks = root.path("data");
                
                if (tracks.isArray() && tracks.size() > 0) {
                    JsonNode firstTrack = tracks.get(0);
                    return Optional.of(parseDeezerResult(firstTrack));
                }
            }
            
        } catch (Exception e) {
            LOGGER.error("Deezer search failed", e);
        }
        
        return Optional.empty();
    }
    
    /**
     * Searches TheAudioDB API for album art.
     */
    private Optional<TheAudioDbData> searchTheAudioDb(String artist, String title) {
        try {
            String query = artist + " " + title;
            String url = String.format(THEAUDIODB_API, 
                java.net.URLEncoder.encode(query, "UTF-8"));
            
            Thread.sleep(500); // Rate limiting
            
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("User-Agent", "JMedia/1.0")
                    .timeout(Duration.ofSeconds(30))
                    .GET()
                    .build();
            
            HttpResponse<String> response = httpClient.send(request, 
                HttpResponse.BodyHandlers.ofString());
            
            if (response.statusCode() == 200) {
                JsonNode root = objectMapper.readTree(response.body());
                JsonNode tracks = root.path("track");
                
                if (tracks.isArray() && tracks.size() > 0) {
                    JsonNode firstTrack = tracks.get(0);
                    return Optional.of(parseTheAudioDbResult(firstTrack));
                }
            }
            
        } catch (Exception e) {
            LOGGER.error("TheAudioDB search failed", e);
        }
        
        return Optional.empty();
    }
    
    /**
     * Parses MusicBrainz API response into a structured result.
     */
    private MusicBrainzData parseMusicBrainzResult(JsonNode recording) {
        MusicBrainzData result = new MusicBrainzData();
        
        // Extract title
        JsonNode titleNode = recording.path("title");
        if (!titleNode.isMissingNode()) {
            result.setTitle(titleNode.asText());
        }
        
        // Extract artist information
        JsonNode artistCredits = recording.path("artist-credit");
        if (artistCredits.isArray() && artistCredits.size() > 0) {
            JsonNode firstArtist = artistCredits.get(0);
            JsonNode artistName = firstArtist.path("name");
            if (!artistName.isMissingNode()) {
                result.setArtist(artistName.asText());
            }
        }
        
        // Extract release information
        JsonNode releases = recording.path("release-list");
        if (releases.isArray() && releases.size() > 0) {
            JsonNode firstRelease = releases.get(0);
            
            // Album name
            JsonNode title = firstRelease.path("title");
            if (!title.isMissingNode()) {
                result.setAlbum(title.asText());
            }
            
            // Release date
            JsonNode date = firstRelease.path("date");
            if (!date.isMissingNode()) {
                result.setReleaseDate(date.asText());
            }
            
            // Track number
            JsonNode trackNumber = firstRelease.path("track-number");
            if (!trackNumber.isMissingNode()) {
                result.setTrackNumber(trackNumber.asInt());
            }
        }
        
        // Extract genre from tag list
        JsonNode tags = recording.path("tag-list");
        if (tags.isArray()) {
            List<String> genres = new ArrayList<>();
            for (JsonNode tag : tags) {
                JsonNode tagName = tag.path("name");
                if (!tagName.isMissingNode() && looksLikeGenre(tagName.asText())) {
                    genres.add(tagName.asText());
                }
            }
            result.setGenres(genres);
        }
        
        return result;
    }
    
    /**
     * Parses Deezer API response into a structured result.
     */
    private DeezerData parseDeezerResult(JsonNode track) {
        DeezerData result = new DeezerData();
        
        // Extract basic info
        result.setTitle(track.path("title").asText());
        result.setAlbum(track.path("album").path("title").asText());
        
        // Extract artist
        JsonNode artist = track.path("artist");
        if (artist.isArray() && artist.size() > 0) {
            result.setArtist(artist.get(0).path("name").asText());
        }
        
        // Extract genres
        List<String> genres = new ArrayList<>();
        JsonNode genreList = track.path("genre");
        if (genreList.isArray()) {
            for (JsonNode genre : genreList) {
                genres.add(genre.path("name").asText());
            }
        }
        result.setGenres(genres);
        
        // Extract album art
        JsonNode album = track.path("album");
        if (!album.isMissingNode()) {
            JsonNode cover = album.path("cover");
            if (!cover.isMissingNode()) {
                result.setAlbumArtUrl(cover.asText());
                // Deezer typically provides good quality art
                result.setAlbumArtSize("medium");
            }
        }
        
        return result;
    }
    
    /**
     * Parses TheAudioDB API response into a structured result.
     */
    private TheAudioDbData parseTheAudioDbResult(JsonNode track) {
        TheAudioDbData result = new TheAudioDbData();
        
        // Extract basic info
        result.setTitle(track.path("strTrack").asText());
        result.setAlbum(track.path("strAlbum").asText());
        result.setArtist(track.path("strArtist").asText());
        
        // Extract album art
        JsonNode albumThumb = track.path("strAlbumThumb");
        if (!albumThumb.isMissingNode() && !albumThumb.asText().isEmpty()) {
            result.setAlbumArtUrl(albumThumb.asText());
            result.setAlbumArtSize("medium");
        }
        
        return result;
    }
    
    /**
     * Checks if a tag looks like a genre (not too generic).
     */
    private boolean looksLikeGenre(String tagName) {
        String lower = tagName.toLowerCase();
        return !lower.contains("seen live") && 
               !lower.contains("acoustic") && 
               !lower.contains("instrumental") &&
               !lower.contains("demo") &&
               tagName.length() < 30; // Reasonable length
    }
    
    // Result classes for API responses
    public static class MetadataResult {
        private String artist;
        private String title;
        private String album;
        private String releaseDate;
        private Integer trackNumber;
        private String albumArtUrl;
        private String albumArtSize;
        private List<String> genres = new ArrayList<>();
        private Set<String> sources = new HashSet<>();
        private double confidence = 0.0;
        
        // Getters and setters
        public String getArtist() { return artist; }
        public void setArtist(String artist) { this.artist = artist; }
        public String getTitle() { return title; }
        public void setTitle(String title) { this.title = title; }
        public String getAlbum() { return album; }
        public void setAlbum(String album) { this.album = album; }
        public String getReleaseDate() { return releaseDate; }
        public void setReleaseDate(String releaseDate) { this.releaseDate = releaseDate; }
        public Integer getTrackNumber() { return trackNumber; }
        public void setTrackNumber(Integer trackNumber) { this.trackNumber = trackNumber; }
        public String getAlbumArtUrl() { return albumArtUrl; }
        public void setAlbumArtUrl(String albumArtUrl) { this.albumArtUrl = albumArtUrl; }
        public String getAlbumArtSize() { return albumArtSize; }
        public void setAlbumArtSize(String albumArtSize) { this.albumArtSize = albumArtSize; }
        public List<String> getGenres() { return genres; }
        public void setGenres(List<String> genres) { this.genres = genres; }
        public void addAllGenres(List<String> newGenres) { 
            if (newGenres != null) {
                this.genres.addAll(newGenres);
            }
        }
        public void addSource(String source) { this.sources.add(source); }
        public double getConfidence() { return confidence; }
        public void setConfidence(double confidence) { this.confidence = confidence; }
        
        public boolean hasAlbumArt() { return albumArtUrl != null && !albumArtUrl.isEmpty(); }
        public boolean hasGenres() { return !genres.isEmpty(); }
        public boolean isEnriched() { 
            return hasAlbumArt() || hasGenres() || 
                   (releaseDate != null && !releaseDate.isEmpty()) ||
                   (album != null && !album.isEmpty());
        }
        
        public void deduplicateGenres() {
            Set<String> uniqueGenres = new LinkedHashSet<>(genres);
            genres.clear();
            genres.addAll(uniqueGenres);
        }
        
        public Set<String> getSources() { return sources; }
    }
    
    // MusicBrainz result wrapper
    private static class MusicBrainzData {
        private String artist;
        private String title;
        private String album;
        private String releaseDate;
        private Integer trackNumber;
        private List<String> genres = new ArrayList<>();
        
        // Getters and setters
        public String getArtist() { return artist; }
        public void setArtist(String artist) { this.artist = artist; }
        public String getTitle() { return title; }
        public void setTitle(String title) { this.title = title; }
        public String getAlbum() { return album; }
        public void setAlbum(String album) { this.album = album; }
        public String getReleaseDate() { return releaseDate; }
        public void setReleaseDate(String releaseDate) { this.releaseDate = releaseDate; }
        public Integer getTrackNumber() { return trackNumber; }
        public void setTrackNumber(Integer trackNumber) { this.trackNumber = trackNumber; }
        public List<String> getGenres() { return genres; }
        public void setGenres(List<String> genres) { this.genres = genres; }
    }
    
    // Deezer result wrapper
    private static class DeezerData {
        private String artist;
        private String title;
        private String album;
        private String albumArtUrl;
        private String albumArtSize;
        private List<String> genres = new ArrayList<>();
        
        // Getters and setters
        public String getArtist() { return artist; }
        public void setArtist(String artist) { this.artist = artist; }
        public String getTitle() { return title; }
        public void setTitle(String title) { this.title = title; }
        public String getAlbum() { return album; }
        public void setAlbum(String album) { this.album = album; }
        public String getAlbumArtUrl() { return albumArtUrl; }
        public void setAlbumArtUrl(String albumArtUrl) { this.albumArtUrl = albumArtUrl; }
        public String getAlbumArtSize() { return albumArtSize; }
        public void setAlbumArtSize(String albumArtSize) { this.albumArtSize = albumArtSize; }
        public List<String> getGenres() { return genres; }
        public void setGenres(List<String> genres) { this.genres = genres; }
        public boolean hasAlbumArt() { return albumArtUrl != null && !albumArtUrl.isEmpty(); }
        public boolean hasGenres() { return !genres.isEmpty(); }
    }
    
    // TheAudioDB result wrapper
    private static class TheAudioDbData {
        private String artist;
        private String title;
        private String album;
        private String albumArtUrl;
        private String albumArtSize;
        
        // Getters and setters
        public String getArtist() { return artist; }
        public void setArtist(String artist) { this.artist = artist; }
        public String getTitle() { return title; }
        public void setTitle(String title) { this.title = title; }
        public String getAlbum() { return album; }
        public void setAlbum(String album) { this.album = album; }
        public String getAlbumArtUrl() { return albumArtUrl; }
        public void setAlbumArtUrl(String albumArtUrl) { this.albumArtUrl = albumArtUrl; }
        public String getAlbumArtSize() { return albumArtSize; }
        public void setAlbumArtSize(String albumArtSize) { this.albumArtSize = albumArtSize; }
        public boolean hasAlbumArt() { return albumArtUrl != null && !albumArtUrl.isEmpty(); }
    }
}