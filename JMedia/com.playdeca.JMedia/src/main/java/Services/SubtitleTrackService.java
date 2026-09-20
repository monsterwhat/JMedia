package Services;

import Models.Video.SubtitleTrack;
import Models.Video.Video;
import Models.DTOs.LocalSubtitleFile;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

@ApplicationScoped
public class SubtitleTrackService {

    private static final Logger LOG = LoggerFactory.getLogger(SubtitleTrackService.class);
    private static final int MAX_SUBTITLE_BASE64_LENGTH = 40_000_000;

    @Inject
    SettingsService settingsService;

    @Inject
    EnhancedSubtitleMatcher subtitleMatcher;

    @Inject
    VideoService videoService;

    /**
     * Deletes an AI-generated subtitle track: removes the physical file from disk,
     * detaches the track from the owning video's lazy collection, then deletes the
     * entity. Returns false when no track exists for the given id.
     */
    @Transactional
    public boolean deleteAiTrack(Long trackId) {
        SubtitleTrack track = SubtitleTrack.findById(trackId);
        if (track == null) {
            return false;
        }

        // Delete the physical file if it exists
        if (track.fullPath != null) {
            try {
                Files.deleteIfExists(Paths.get(track.fullPath));
            } catch (Exception e) {
                LOG.warn("Could not delete subtitle file: " + track.fullPath, e);
            }
        }

        // Remove from video's track list
        if (track.video != null && track.video.subtitleTracks != null) {
            track.video.subtitleTracks.remove(track);
            track.video.persist();
        }

        track.delete();
        return true;
    }

    /**
     * Persists an uploaded subtitle file for a video. Writes the decoded bytes to a
     * unique file next to the video, persists the new track, and adds it to the
     * video's lazy track collection. Validation failures are returned as
     * {@link UploadResult} states; decode/write/persist failures propagate as
     * runtime exceptions so the transaction rolls back with no partial persist.
     */
    @Transactional
    public UploadResult uploadForVideo(Long videoId, Map<String, String> request) {
        Video video = Video.findById(videoId);
        if (video == null) {
            return UploadResult.notFound();
        }

        String content = request.get("content");
        String filename = request.get("filename");
        String language = request.get("language");
        String languageName = request.getOrDefault("languageName", "");

        if (content == null || content.isBlank()) {
            return UploadResult.badRequest("File content is required");
        }
        if (filename == null || filename.isBlank()) {
            return UploadResult.badRequest("Filename is required");
        }

        String ext = filename.contains(".") ? filename.substring(filename.lastIndexOf('.') + 1).toLowerCase() : "";
        if (!List.of("srt", "vtt", "ass", "ssa", "sub", "idx").contains(ext)) {
            return UploadResult.badRequest("Unsupported subtitle format: " + ext + ". Supported: srt, vtt, ass, ssa, sub, idx");
        }

        String payload = content.contains(",") ? content.split(",")[1] : content;
        if (payload.length() > MAX_SUBTITLE_BASE64_LENGTH) {
            return UploadResult.badRequest("Subtitle file too large");
        }
        byte[] fileBytes;
        try {
            fileBytes = Base64.getDecoder().decode(payload);
        } catch (IllegalArgumentException e) {
            return UploadResult.badRequest("Invalid subtitle file content");
        }

        String videoPathStr = video.path;
        int lastSlash = Math.max(videoPathStr.lastIndexOf('/'), videoPathStr.lastIndexOf('\\'));
        int lastDot = videoPathStr.lastIndexOf('.');
        String videoBasename = videoPathStr.substring(lastSlash + 1, lastDot > lastSlash ? lastDot : videoPathStr.length());
        String langCode = (language != null && !language.isBlank()) ? mapToThreeLetterLanguage(language) : "und";

        String saveFilename = videoBasename + ".upload." + langCode + "." + ext;
        java.nio.file.Path videoDir = java.nio.file.Paths.get(video.path).getParent();
        if (videoDir == null) {
            return UploadResult.videoDirError();
        }
        java.nio.file.Path targetPath = videoDir.resolve(saveFilename);

        int counter = 1;
        while (Files.exists(targetPath)) {
            saveFilename = videoBasename + ".upload." + langCode + "_" + counter + "." + ext;
            targetPath = videoDir.resolve(saveFilename);
            counter++;
        }

        try {
            Files.createDirectories(videoDir);
            Files.write(targetPath, fileBytes);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        SubtitleTrack track = new SubtitleTrack();
        track.filename = saveFilename;
        track.fullPath = targetPath.toString();
        track.format = ext;
        track.video = video;
        track.isManual = true;
        track.fileSize = (long) fileBytes.length;
        track.languageCode = langCode;
        track.languageName = languageName;
        track.displayName = !languageName.isBlank() ? languageName : saveFilename;
        track.persist();

        if (video.subtitleTracks == null) {
            video.subtitleTracks = new ArrayList<>();
        }
        video.subtitleTracks.add(track);
        video.persist();

        return UploadResult.success(track);
    }

    /**
     * Lists AI-generated subtitle tracks for a video, used to back the completed
     * subtitles endpoint without lazy-initialization errors.
     */
    @Transactional
    public List<SubtitleTrack> findAiTracksForVideo(Long videoId) {
        return SubtitleTrack.list("video.id = ?1 and isAiGenerated = ?2", videoId, true);
    }

    /**
     * Returns whether a video has any AI-generated subtitle track.
     */
    @Transactional
    public boolean hasAiSubtitles(Long videoId) {
        if (videoId == null) return false;
        return SubtitleTrack.count("video.id = ?1 and isAiGenerated = ?2", videoId, true) > 0;
    }

    public String mapToThreeLetterLanguage(String lang) {
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

    public List<Models.DTOs.LocalSubtitleFile> scanAllSubtitleFiles(Video video) {
        return subtitleMatcher.scanAllSubtitleFiles(Paths.get(video.path), video);
    }

    @Transactional
    public void addLocalSubtitle(Video video, String filePath) {
        Video managedVideo = Video.findById(video.id);
        if (managedVideo == null) return;

        String ext = getFileExtension(filePath);
        if (!List.of("srt", "vtt", "ass", "ssa", "sub", "idx").contains(ext)) {
            throw new RuntimeException("Unsupported subtitle format: " + ext + ". Supported: srt, vtt, ass, ssa, sub, idx");
        }

        String libraryPath = settingsService.getOrCreateSettings().getVideoLibraryPath();
        Path videoPath = Paths.get(managedVideo.path);
        if (!videoPath.isAbsolute()) {
            videoPath = Paths.get(libraryPath, managedVideo.path);
        }
        Path videoDir = videoPath.toAbsolutePath().normalize().getParent();
        if (videoDir == null) {
            throw new RuntimeException("Cannot determine video directory");
        }

        Path candidate = Paths.get(filePath);
        if (!candidate.isAbsolute()) {
            candidate = videoDir.resolve(candidate);
        }

        Path path;
        Path videoDirCanonical;
        try {
            path = candidate.toRealPath();
            videoDirCanonical = Files.exists(videoDir) ? videoDir.toRealPath() : videoDir;
        } catch (IOException e) {
            throw new RuntimeException("File does not exist: " + filePath);
        }
        if (!path.startsWith(videoDirCanonical)) {
            throw new RuntimeException("Subtitle file must be located in the video directory");
        }

        // Check for duplicates
        if (managedVideo.subtitleTracks != null && managedVideo.subtitleTracks.stream()
                .anyMatch(t -> filePath.equals(t.fullPath) || t.fullPath != null && t.fullPath.equals(path.toString()))) {
            return;
        }

        // Create manual track
        SubtitleTrack track = new SubtitleTrack();
        track.filename = path.getFileName().toString();
        track.fullPath = path.toString();
        track.format = getFileExtension(track.filename);
        track.video = managedVideo;
        track.isManual = true;

        // Extract language and metadata using the matcher
        subtitleMatcher.extractLanguageAndTags(track.filename, track);

        // If still no display name, the matcher fix will fallback to filename
        if (track.displayName == null || track.displayName.equals("Unknown")) {
            track.displayName = track.filename + " (Manual)";
        }

        track.persist();
        
        if (managedVideo.subtitleTracks == null) {
            managedVideo.subtitleTracks = new ArrayList<>();
        }
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
        videoService.mergeSubtitleTracks(managedVideo.id, tracks);
        LOG.info("Refreshed subtitle tracks for video: " + managedVideo.title);
    }

    /**
     * Outcome of {@link #uploadForVideo(Long, Map)}, letting the API layer map each
     * state to the exact status code and body it returned before the migration.
     */
    public static class UploadResult {
        private final boolean notFound;
        private final String badRequestMessage;
        private final boolean videoDirError;
        private final SubtitleTrack track;

        private UploadResult(boolean notFound, String badRequestMessage, boolean videoDirError, SubtitleTrack track) {
            this.notFound = notFound;
            this.badRequestMessage = badRequestMessage;
            this.videoDirError = videoDirError;
            this.track = track;
        }

        public static UploadResult notFound() {
            return new UploadResult(true, null, false, null);
        }

        public static UploadResult badRequest(String message) {
            return new UploadResult(false, message, false, null);
        }

        public static UploadResult videoDirError() {
            return new UploadResult(false, null, true, null);
        }

        public static UploadResult success(SubtitleTrack track) {
            return new UploadResult(false, null, false, track);
        }

        public boolean isNotFound() {
            return notFound;
        }

        public String getBadRequestMessage() {
            return badRequestMessage;
        }

        public boolean isVideoDirError() {
            return videoDirError;
        }

        public SubtitleTrack getTrack() {
            return track;
        }
    }
}
