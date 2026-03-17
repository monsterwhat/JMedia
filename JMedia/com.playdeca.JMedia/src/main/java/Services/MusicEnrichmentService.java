package Services;

import Models.Song;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@ApplicationScoped
public class MusicEnrichmentService {

    private static final Logger LOGGER = LoggerFactory.getLogger(MusicEnrichmentService.class);

    private static final String MUSICBRAINZ_API_URL = "https://musicbrainz.org/ws/2/recording";
    private static final String ACOUSTICBRAINZ_API_URL = "https://acousticbrainz.org/api/v1/high-level";
    private static final String DEEZER_API_URL = "https://api.deezer.com/search/track/";
    private static final String THEAUDIODB_API_URL = "https://www.theaudiodb.com/api/v1/json/2/search.php";

    private static final Duration MUSICBRAINZ_REQUEST_DELAY = Duration.ofSeconds(1);
    private static final Duration ACOUSTICBRAINZ_REQUEST_DELAY = Duration.ofMillis(100);
    private static final Duration DEEZER_REQUEST_DELAY = Duration.ofMillis(100);
    private static final Duration THEAUDIODB_REQUEST_DELAY = Duration.ofMillis(100);

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    @Inject
    LoggingService loggingService;

    @Inject
    AlbumArtService albumArtService;

    public record MusicBrainzResult(String mbid, String genre) {}

    public record AcousticBrainzResult(int bpm, String key, double danceability, double energy) {}

    public record DeezerResult(String artist, String title, String album, String genre, String artworkUrl) {}

    public record TheAudioDbResult(String artworkUrl) {}

    public record EnrichedMetadataResult(
            String artist,
            String title,
            String album,
            String genre,
            String artworkUrl,
            int bpm,
            boolean isEnriched,
            List<String> sources
    ) {}

    public void enrichSong(Song song) {
        enrichSong(song, false);
    }

    public void enrichSong(Song song, boolean overwriteBasicInfo) {
        if (song == null) {
            return;
        }

        String artist = song.getArtist();
        String title = song.getTitle();

        if (artist == null || title == null || artist.isBlank() || title.isBlank()) {
            LOGGER.debug("Cannot enrich song without artist and title: {}", song.getPath());
            return;
        }

        boolean needsMusicBrainz = song.getMusicbrainzId() == null;
        boolean needsAcousticBrainz = song.getMusicbrainzId() != null && 
            (song.getBpm() <= 0 || (song.getGenre() == null || song.getGenre().isBlank()));
        boolean needsDeezer = song.getArtworkBase64() == null || 
            (song.getGenre() == null || song.getGenre().isBlank() || "Unknown Genre".equals(song.getGenre()));
        boolean needsTheAudioDb = song.getArtworkBase64() == null;

        if (!needsMusicBrainz && !needsAcousticBrainz && !needsDeezer && !needsTheAudioDb && !overwriteBasicInfo) {
            return;
        }

        if (needsMusicBrainz) {
            try {
                MusicBrainzResult result = searchMusicBrainz(artist, title);
                if (result != null) {
                    if (result.mbid() != null) {
                        song.setMusicbrainzId(result.mbid());
                    }
                    if (result.genre() != null && !result.genre().isBlank() && 
                        (song.getGenre() == null || song.getGenre().isBlank() || "Unknown Genre".equals(song.getGenre()))) {
                        song.setGenre(result.genre());
                    }
                    song.persist();
                    LOGGER.info("Enriched song from MusicBrainz: {} - {}", artist, title);
                }
            } catch (Exception e) {
                LOGGER.error("Failed to query MusicBrainz for {} - {}", artist, title, e);
            }

            try {
                Thread.sleep(MUSICBRAINZ_REQUEST_DELAY.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }

        String mbid = song.getMusicbrainzId();
        if (mbid != null && (song.getBpm() <= 0 || overwriteBasicInfo)) {
            try {
                AcousticBrainzResult result = getAcousticBrainz(mbid);
                if (result != null) {
                    if (result.bpm() > 0) {
                        song.setBpm(result.bpm());
                    }
                    song.persist();
                    LOGGER.info("Enriched BPM from AcousticBrainz for {} - {}", artist, title);
                }
            } catch (Exception e) {
                LOGGER.error("Failed to query AcousticBrainz for MBID: {}", mbid, e);
            }

            try {
                Thread.sleep(ACOUSTICBRAINZ_REQUEST_DELAY.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        if (needsDeezer || overwriteBasicInfo) {
            try {
                DeezerResult result = searchDeezer(artist, title);
                if (result != null) {
                    boolean updated = false;
                    
                    if (overwriteBasicInfo) {
                        if (result.artist() != null && !result.artist().isBlank()) {
                            song.setArtist(result.artist());
                            updated = true;
                        }
                        if (result.title() != null && !result.title().isBlank()) {
                            song.setTitle(result.title());
                            updated = true;
                        }
                        if (result.album() != null && !result.album().isBlank()) {
                            song.setAlbum(result.album());
                            updated = true;
                        }
                    }
                    
                    if (result.genre() != null && !result.genre().isBlank() && 
                        (song.getGenre() == null || song.getGenre().isBlank() || "Unknown Genre".equals(song.getGenre()))) {
                        song.setGenre(result.genre());
                        updated = true;
                    }
                    
                    if (result.artworkUrl() != null && !result.artworkUrl().isBlank() && song.getArtworkBase64() == null) {
                        String base64 = downloadArtwork(result.artworkUrl());
                        if (base64 != null) {
                            song.setArtworkBase64(base64);
                            updated = true;
                        }
                    }
                    
                    if (updated) {
                        song.persist();
                        LOGGER.info("Enriched song from Deezer: {} - {}", artist, title);
                    }
                }
            } catch (Exception e) {
                LOGGER.error("Failed to query Deezer for {} - {}", artist, title, e);
            }

            try {
                Thread.sleep(DEEZER_REQUEST_DELAY.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        if ((needsTheAudioDb || overwriteBasicInfo) && song.getArtworkBase64() == null) {
            try {
                TheAudioDbResult result = searchTheAudioDb(artist, title);
                if (result != null && result.artworkUrl() != null && !result.artworkUrl().isBlank()) {
                    String base64 = downloadArtwork(result.artworkUrl());
                    if (base64 != null) {
                        song.setArtworkBase64(base64);
                        song.persist();
                        LOGGER.info("Enriched artwork from TheAudioDB for {} - {}", artist, title);
                    }
                }
            } catch (Exception e) {
                LOGGER.error("Failed to query TheAudioDB for {} - {}", artist, title, e);
            }

            try {
                Thread.sleep(THEAUDIODB_REQUEST_DELAY.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    public EnrichedMetadataResult enrichMetadata(String artist, String title) {
        List<String> sources = new ArrayList<>();
        
        String parsedArtist = artist;
        String parsedTitle = title;

        if ((artist == null || artist.trim().isEmpty() || "Unknown Artist".equals(artist))
                && title != null && title.contains(" - ")) {
            String[] parts = title.split(" - ", 2);
            if (parts.length == 2) {
                parsedArtist = parts[0].trim();
                parsedTitle = parts[1].trim();
                LOGGER.info("Parsed artist from title: '{}' - '{}'", parsedArtist, parsedTitle);
            }
        }

        String resultArtist = null;
        String resultTitle = null;
        String resultAlbum = null;
        String resultGenre = null;
        String resultArtworkUrl = null;
        int resultBpm = 0;
        boolean isEnriched = false;

        try {
            MusicBrainzResult mbResult = searchMusicBrainz(parsedArtist, parsedTitle);
            if (mbResult != null) {
                if (mbResult.mbid() != null) {
                    sources.add("MusicBrainz");
                }
                if (mbResult.genre() != null && !mbResult.genre().isBlank()) {
                    resultGenre = mbResult.genre();
                    isEnriched = true;
                }
            }

            Thread.sleep(MUSICBRAINZ_REQUEST_DELAY.toMillis());
        } catch (Exception e) {
            LOGGER.error("Failed to query MusicBrainz for {} - {}", parsedArtist, parsedTitle, e);
        }

        try {
            DeezerResult deezerResult = searchDeezer(parsedArtist, parsedTitle);
            if (deezerResult != null) {
                sources.add("Deezer");
                isEnriched = true;
                
                if (deezerResult.artist() != null && !deezerResult.artist().isBlank()) {
                    resultArtist = deezerResult.artist();
                }
                if (deezerResult.title() != null && !deezerResult.title().isBlank()) {
                    resultTitle = deezerResult.title();
                }
                if (deezerResult.album() != null && !deezerResult.album().isBlank()) {
                    resultAlbum = deezerResult.album();
                }
                if (deezerResult.genre() != null && !deezerResult.genre().isBlank()) {
                    resultGenre = deezerResult.genre();
                }
                if (deezerResult.artworkUrl() != null && !deezerResult.artworkUrl().isBlank()) {
                    resultArtworkUrl = deezerResult.artworkUrl();
                }
            }

            Thread.sleep(DEEZER_REQUEST_DELAY.toMillis());
        } catch (Exception e) {
            LOGGER.error("Failed to query Deezer for {} - {}", parsedArtist, parsedTitle, e);
        }

        if (resultArtworkUrl == null || resultArtworkUrl.isBlank()) {
            try {
                TheAudioDbResult taDbResult = searchTheAudioDb(parsedArtist, parsedTitle);
                if (taDbResult != null && taDbResult.artworkUrl() != null && !taDbResult.artworkUrl().isBlank()) {
                    sources.add("TheAudioDB");
                    resultArtworkUrl = taDbResult.artworkUrl();
                    isEnriched = true;
                }

                Thread.sleep(THEAUDIODB_REQUEST_DELAY.toMillis());
            } catch (Exception e) {
                LOGGER.error("Failed to query TheAudioDB for {} - {}", parsedArtist, parsedTitle, e);
            }
        }

        String mbid = null;
        try {
            MusicBrainzResult mbResult = searchMusicBrainz(parsedArtist, parsedTitle);
            if (mbResult != null && mbResult.mbid() != null) {
                mbid = mbResult.mbid();
            }
        } catch (Exception e) {
            LOGGER.error("Failed to get MBID for {} - {}", parsedArtist, parsedTitle, e);
        }

        if (mbid != null) {
            try {
                AcousticBrainzResult abResult = getAcousticBrainz(mbid);
                if (abResult != null && abResult.bpm() > 0) {
                    sources.add("AcousticBrainz");
                    resultBpm = abResult.bpm();
                    isEnriched = true;
                }
            } catch (Exception e) {
                LOGGER.error("Failed to query AcousticBrainz for MBID: {}", mbid, e);
            }
        }

        return new EnrichedMetadataResult(
                resultArtist,
                resultTitle,
                resultAlbum,
                resultGenre,
                resultArtworkUrl,
                resultBpm,
                isEnriched,
                sources
        );
    }

    public MusicBrainzResult searchMusicBrainz(String artist, String title) {
        try {
            String query = String.format("artist:%s AND recording:%s",
                URLEncoder.encode(artist, StandardCharsets.UTF_8),
                URLEncoder.encode(title, StandardCharsets.UTF_8));

            String url = MUSICBRAINZ_API_URL + "?query=" + query + "&fmt=json&limit=1";

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("User-Agent", "JMedia/1.0 (contact: dev@example.com)")
                    .header("Accept", "application/json")
                    .timeout(Duration.ofSeconds(15))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                return parseMusicBrainzResponse(response.body());
            } else {
                LOGGER.warn("MusicBrainz API returned status {} for {} - {}", response.statusCode(), artist, title);
            }
        } catch (Exception e) {
            LOGGER.error("Error querying MusicBrainz for {} - {}", artist, title, e);
        }

        return null;
    }

    @SuppressWarnings("unchecked")
    private MusicBrainzResult parseMusicBrainzResponse(String json) {
        try {
            org.json.JSONObject root = new org.json.JSONObject(json);
            org.json.JSONArray recordings = root.optJSONArray("recordings");

            if (recordings != null && !recordings.isEmpty()) {
                org.json.JSONObject recording = recordings.getJSONObject(0);
                String mbid = recording.optString("id", null);

                String genre = null;
                org.json.JSONArray tags = recording.optJSONArray("tags");
                if (tags != null && !tags.isEmpty()) {
                    int maxCount = 0;
                    for (int i = 0; i < tags.length(); i++) {
                        org.json.JSONObject tag = tags.getJSONObject(i);
                        String name = tag.optString("name", "");
                        int count = tag.optInt("count", 0);
                        if (count > maxCount) {
                            genre = name;
                            maxCount = count;
                        }
                    }
                }

                return new MusicBrainzResult(mbid, genre);
            }
        } catch (Exception e) {
            LOGGER.error("Error parsing MusicBrainz response", e);
        }

        return null;
    }

    public AcousticBrainzResult getAcousticBrainz(String mbid) {
        try {
            String url = ACOUSTICBRAINZ_API_URL + "?mbid=" + mbid;

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("User-Agent", "JMedia/1.0 (contact: dev@example.com)")
                    .header("Accept", "application/json")
                    .timeout(Duration.ofSeconds(15))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                return parseAcousticBrainzResponse(response.body());
            } else {
                LOGGER.warn("AcousticBrainz API returned status {} for MBID: {}", response.statusCode(), mbid);
            }
        } catch (Exception e) {
            LOGGER.error("Error querying AcousticBrainz for MBID: {}", mbid, e);
        }

        return null;
    }

    @SuppressWarnings("unchecked")
    private AcousticBrainzResult parseAcousticBrainzResponse(String json) {
        try {
            org.json.JSONObject root = new org.json.JSONObject(json);
            
            org.json.JSONObject highLevel = root.optJSONObject("highlevel");
            if (highLevel == null) {
                return null;
            }

            int bpm = 0;
            double danceability = 0.0;
            double energy = 0.0;
            String key = null;

            org.json.JSONObject bpmObj = highLevel.optJSONObject("bpm");
            if (bpmObj != null) {
                bpm = (int) Math.round(bpmObj.optDouble("value", 0.0));
            }

            org.json.JSONObject danceabilityObj = highLevel.optJSONObject("danceability");
            if (danceabilityObj != null) {
                danceability = danceabilityObj.optDouble("value", 0.0);
            }

            org.json.JSONObject energyObj = highLevel.optJSONObject("energy");
            if (energyObj != null) {
                energy = energyObj.optDouble("value", 0.0);
            }

            org.json.JSONObject keyObj = highLevel.optJSONObject("key_key");
            if (keyObj != null) {
                key = keyObj.optString("value", null);
            }

            return new AcousticBrainzResult(bpm, key, danceability, energy);
        } catch (Exception e) {
            LOGGER.error("Error parsing AcousticBrainz response", e);
        }

        return null;
    }

    public DeezerResult searchDeezer(String artist, String title) {
        try {
            String query = artist + " " + title;
            String url = DEEZER_API_URL + "?q=" + URLEncoder.encode(query, StandardCharsets.UTF_8) + "&limit=5";

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("User-Agent", "JMedia/1.0 (contact: dev@example.com)")
                    .header("Accept", "application/json")
                    .timeout(Duration.ofSeconds(15))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                return parseDeezerResponse(response.body());
            } else {
                LOGGER.warn("Deezer API returned status {} for {} - {}", response.statusCode(), artist, title);
            }
        } catch (Exception e) {
            LOGGER.error("Error querying Deezer for {} - {}", artist, title, e);
        }

        return null;
    }

    private DeezerResult parseDeezerResponse(String json) {
        try {
            org.json.JSONObject root = new org.json.JSONObject(json);
            org.json.JSONArray data = root.optJSONArray("data");

            if (data != null && !data.isEmpty()) {
                org.json.JSONObject track = data.getJSONObject(0);
                
                String artist = track.optString("artist_name", null);
                String title = track.optString("title", null);
                
                org.json.JSONObject album = track.optJSONObject("album");
                String albumName = null;
                String artworkUrl = null;
                
                if (album != null) {
                    albumName = album.optString("title", null);
                    artworkUrl = album.optString("cover_medium", null);
                    if (artworkUrl == null || artworkUrl.isBlank()) {
                        artworkUrl = album.optString("cover", null);
                    }
                }

                return new DeezerResult(artist, title, albumName, null, artworkUrl);
            }
        } catch (Exception e) {
            LOGGER.error("Error parsing Deezer response", e);
        }

        return null;
    }

    public TheAudioDbResult searchTheAudioDb(String artist, String title) {
        try {
            String query = artist + " " + title;
            String url = THEAUDIODB_API_URL + "?s=" + URLEncoder.encode(query, StandardCharsets.UTF_8);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("User-Agent", "JMedia/1.0 (contact: dev@example.com)")
                    .header("Accept", "application/json")
                    .timeout(Duration.ofSeconds(15))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                return parseTheAudioDbResponse(response.body());
            } else {
                LOGGER.warn("TheAudioDB API returned status {} for {} - {}", response.statusCode(), artist, title);
            }
        } catch (Exception e) {
            LOGGER.error("Error querying TheAudioDB for {} - {}", artist, title, e);
        }

        return null;
    }

    private TheAudioDbResult parseTheAudioDbResponse(String json) {
        try {
            org.json.JSONObject root = new org.json.JSONObject(json);
            org.json.JSONArray tracks = root.optJSONArray("track");

            if (tracks != null && !tracks.isEmpty()) {
                org.json.JSONObject track = tracks.getJSONObject(0);
                
                String artworkUrl = track.optString("strAlbumThumb", null);
                if (artworkUrl == null || artworkUrl.isBlank()) {
                    artworkUrl = track.optString("strAlbumThumbHQ", null);
                }

                return new TheAudioDbResult(artworkUrl);
            }
        } catch (Exception e) {
            LOGGER.error("Error parsing TheAudioDB response", e);
        }

        return null;
    }

    private String downloadArtwork(String artworkUrl) {
        if (artworkUrl == null || artworkUrl.isBlank()) {
            return null;
        }

        try {
            String base64 = albumArtService.convertUrlToBase64(artworkUrl).get();
            return base64;
        } catch (Exception e) {
            LOGGER.error("Error downloading artwork from: {}", artworkUrl, e);
            return null;
        }
    }
}
