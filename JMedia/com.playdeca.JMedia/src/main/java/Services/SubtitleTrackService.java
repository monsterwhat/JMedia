package Services;

import Models.Video.SubtitleTrack;
import Models.Video.Video;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

@ApplicationScoped
public class SubtitleTrackService {

    private static final Logger LOG = LoggerFactory.getLogger(SubtitleTrackService.class);

    @Inject
    SubtitleDownloadService downloadService;

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

        byte[] fileBytes;
        if (content.contains(",")) {
            fileBytes = Base64.getDecoder().decode(content.split(",")[1]);
        } else {
            fileBytes = Base64.getDecoder().decode(content);
        }

        String videoPathStr = video.path;
        int lastSlash = Math.max(videoPathStr.lastIndexOf('/'), videoPathStr.lastIndexOf('\\'));
        int lastDot = videoPathStr.lastIndexOf('.');
        String videoBasename = videoPathStr.substring(lastSlash + 1, lastDot > lastSlash ? lastDot : videoPathStr.length());
        String langCode = (language != null && !language.isBlank()) ? downloadService.mapToThreeLetterLanguage(language) : "und";

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
