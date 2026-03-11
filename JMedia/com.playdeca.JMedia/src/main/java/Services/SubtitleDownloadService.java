package Services;

import Models.Video;
import Models.SubtitleTrack;
import Models.DTOs.SubtitleSearchResult;
import Models.Settings;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ApplicationScoped
public class SubtitleDownloadService {

    private static final Logger LOG = LoggerFactory.getLogger(SubtitleDownloadService.class);
    private static final String OPENSUBTITLES_API_BASE = "https://api.opensubtitles.com/api/v1";
    private static final String USER_AGENT = "JMedia v1.0";

    @Inject
    SettingsService settingsService;
    
    @Inject
    EnhancedSubtitleMatcher subtitleMatcher;
    
    @Inject
    VideoService videoService;

    private final HttpClient httpClient = HttpClient.newBuilder().build();
    private final ObjectMapper objectMapper = new ObjectMapper();

    public List<SubtitleSearchResult> searchSubtitles(Video video, String language) throws Exception {
        // OpenSubtitles requires 3-letter ISO 639-2 language codes (e.g., 'eng' instead of 'en')
        String osLanguage = mapToThreeLetterLanguage(language);
        LOG.info("Searching OpenSubtitles.org for video: " + video.title + " in language: " + osLanguage);
        
        // Build URL segments
        StringBuilder urlSegments = new StringBuilder();
        
        // 1. Add IMDb ID if available (strip 'tt' prefix)
        if (video.imdbId != null && !video.imdbId.isBlank()) {
            String cleanImdbId = video.imdbId.replace("tt", "");
            urlSegments.append("/imdbid-").append(cleanImdbId);
        }
        
        // 2. Add Language
        urlSegments.append("/sublanguageid-").append(osLanguage);
        
        // 3. Add Movie Name
        String query = video.type.equalsIgnoreCase("episode") ? video.seriesTitle : video.title;
        urlSegments.append("/moviename-").append(URLEncoder.encode(query, StandardCharsets.UTF_8));

        // Construct final URL with /xml suffix for parsing
        String searchUrl = "https://www.opensubtitles.org/en/search" + urlSegments.toString() + "/xml";
        LOG.info("OpenSubtitles Search URL: " + searchUrl);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(searchUrl))
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36")
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        
        if (response.statusCode() != 200) {
            LOG.error("OpenSubtitles search failed with status " + response.statusCode());
            return new ArrayList<>();
        }

        return parseWebSearchResults(response.body(), osLanguage);
    }

    private String mapToThreeLetterLanguage(String lang) {
        if (lang == null || lang.isBlank()) return "all";
        
        // Handle explicit SPL request from user
        if (lang.equalsIgnoreCase("spl")) return "spl";
        
        if (lang.length() == 3) return lang.toLowerCase();
        
        // Common mappings for 2-letter to 3-letter codes
        return switch (lang.toLowerCase()) {
            case "en" -> "eng";
            case "es" -> "spa";
            case "fr" -> "fre";
            case "de" -> "deu";
            case "it" -> "ita";
            case "pt" -> "por";
            case "ru" -> "rus";
            case "ja" -> "jpn";
            case "ko" -> "kor";
            case "zh" -> "chi";
            default -> lang;
        };
    }

    private List<SubtitleSearchResult> parseWebSearchResults(String xml, String searchLang) throws Exception {
        List<SubtitleSearchResult> results = new ArrayList<>();
        
        try {
            javax.xml.parsers.DocumentBuilderFactory factory = javax.xml.parsers.DocumentBuilderFactory.newInstance();
            javax.xml.parsers.DocumentBuilder builder = factory.newDocumentBuilder();
            org.w3c.dom.Document doc = builder.parse(new org.xml.sax.InputSource(new java.io.StringReader(xml)));
            
            org.w3c.dom.NodeList subtitleNodes = doc.getElementsByTagName("subtitle");
            LOG.info("Found " + subtitleNodes.getLength() + " subtitles in XML response");

            for (int i = 0; i < subtitleNodes.getLength(); i++) {
                org.w3c.dom.Element element = (org.w3c.dom.Element) subtitleNodes.item(i);
                
                SubtitleSearchResult result = new SubtitleSearchResult();
                result.id = getTagValue("IDSubtitle", element);
                result.filename = getTagValue("MovieReleaseName", element);
                if (result.filename == null || result.filename.isEmpty()) {
                    result.filename = getTagValue("MovieName", element);
                }
                
                result.language = getTagValue("LanguageName", element);
                result.languageCode = searchLang; // Store the code used for the search
                
                String ratingStr = getTagValue("SubRating", element);
                result.rating = (ratingStr != null) ? Double.parseDouble(ratingStr) : 0.0;
                
                String downloadsStr = getTagValue("SubDownloadsCnt", element);
                result.downloadCount = (downloadsStr != null) ? Integer.parseInt(downloadsStr) : 0;
                
                result.format = "srt"; // OpenSubtitles standard
                results.add(result);
            }
        } catch (Exception e) {
            LOG.error("Failed to parse OpenSubtitles XML: " + e.getMessage());
            // If XML parsing fails, it might be an HTML error page or different format
            throw new IOException("Failed to parse subtitle search results");
        }
        
        return results;
    }

    private String getTagValue(String tagName, org.w3c.dom.Element element) {
        org.w3c.dom.NodeList nodeList = element.getElementsByTagName(tagName);
        if (nodeList != null && nodeList.getLength() > 0) {
            return nodeList.item(0).getTextContent();
        }
        return null;
    }

    public String downloadSubtitleWithLang(Video video, String fileId, String lang) {
        try {
            LOG.info("Downloading subtitle from OpenSubtitles.org with ID: " + fileId + " (" + lang + ")");

            String videoPathStr = video.path;
            int lastSlash = Math.max(videoPathStr.lastIndexOf('/'), videoPathStr.lastIndexOf('\\'));
            int lastDot = videoPathStr.lastIndexOf('.');
            String videoBasename = videoPathStr.substring(lastSlash + 1, lastDot);
            
            // Normalize language code for filename (e.g. 'spl' -> 'spl', 'en' -> 'eng')
            String fileLang = (lang != null && !lang.isBlank()) ? mapToThreeLetterLanguage(lang) : "und";
            
            // Format: videoBasename.lang.os-id.srt
            String filename = videoBasename + "." + fileLang + ".os-" + fileId + ".srt";
            Path targetPath = Paths.get(video.path).getParent().resolve(filename);
            
            LOG.info("Target download path: " + targetPath);
            
            // Check if file already exists to avoid redundant downloads
            if (Files.exists(targetPath)) {
                LOG.info("Subtitle file already exists, skipping download: " + filename);
                refreshSubtitleTracks(video);
                return filename;
            }

            String downloadUrl = "https://www.opensubtitles.org/en/download/sub/" + fileId;
            
            HttpRequest fileRequest = HttpRequest.newBuilder()
                    .uri(URI.create(downloadUrl))
                    .header("User-Agent", "Mozilla/5.0")
                    .timeout(java.time.Duration.ofSeconds(30))
                    .GET()
                    .build();

            // Using sendAsync to avoid InterruptedException blocking issues and add a timeout
            HttpResponse<Path> fileResponse = httpClient.sendAsync(fileRequest, 
                    HttpResponse.BodyHandlers.ofFile(targetPath))
                    .get(45, java.util.concurrent.TimeUnit.SECONDS);

            LOG.info("Downloaded subtitle to: " + fileResponse.body());
            
            refreshSubtitleTracks(video);
            return filename;
            
        } catch (java.util.concurrent.TimeoutException e) {
            LOG.error("Subtitle download timed out");
            throw new RuntimeException("Download timed out");
        } catch (Exception e) {
            LOG.error("Error downloading subtitle", e);
            throw new RuntimeException("Download failed: " + e.getMessage());
        }
    }

    public String downloadSubtitleSync(Video video, String fileId) {
        return downloadSubtitleWithLang(video, fileId, null);
    }

    public CompletableFuture<String> downloadSubtitle(Video video, String fileId) {
        return CompletableFuture.supplyAsync(() -> downloadSubtitleSync(video, fileId));
    }

    public List<Models.DTOs.LocalSubtitleFile> scanAllSubtitleFiles(Video video) {
        return subtitleMatcher.scanAllSubtitleFiles(Paths.get(video.path), video);
    }
    
    @Transactional
    public void addLocalSubtitle(Video video, String filePath) {
        Video managedVideo = Video.findById(video.id);
        if (managedVideo == null) return;
        
        Path path = Paths.get(filePath);
        if (!Files.exists(path)) {
            throw new RuntimeException("File does not exist: " + filePath);
        }
        
        // Create manual track
        SubtitleTrack track = new SubtitleTrack();
        track.filename = path.getFileName().toString();
        track.fullPath = filePath;
        track.format = getFileExtension(track.filename);
        track.video = managedVideo;
        track.isManual = true;
        track.isActive = true;
        
        // Extract tags for better display
        subtitleMatcher.extractLanguageAndTags(track.filename, track);
        
        if (managedVideo.subtitleTracks == null) {
            managedVideo.subtitleTracks = new ArrayList<>();
        }
        
        // Check for duplicates
        if (managedVideo.subtitleTracks.stream().anyMatch(t -> t.fullPath.equals(filePath))) {
            return;
        }
        
        track.persist();
        managedVideo.subtitleTracks.add(track);
        managedVideo.persist();
        
        LOG.info("Manually added subtitle track: " + filePath + " to video: " + managedVideo.title);
    }

    private String getFileExtension(String filename) {
        int lastDot = filename.lastIndexOf('.');
        if (lastDot > 0 && lastDot < filename.length() - 1) {
            return filename.substring(lastDot + 1).toLowerCase();
        }
        return "";
    }

    @Transactional
    public void refreshSubtitleTracks(Video video) {
        Video managedVideo = Video.findById(video.id);
        if (managedVideo == null) return;
        
        List<SubtitleTrack> tracks = subtitleMatcher.discoverSubtitleTracks(Paths.get(managedVideo.path), managedVideo);
        videoService.updateSubtitleTracks(managedVideo.id, tracks);
        LOG.info("Refreshed subtitle tracks for video: " + managedVideo.title);
    }
}
