package Services;

import Models.AudioTrack;
import Models.Video;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class HlsService {

    private static final Logger LOG = LoggerFactory.getLogger(HlsService.class);
    private static final String VIDEO_VARIANT = "video_stream";

    @Inject VideoService videoService;
    @Inject SettingsService settingsService;
    @Inject FFmpegDiscoveryService ffmpegDiscoveryService;

    private final Map<String, HlsSession> activeSessions = new ConcurrentHashMap<>();
    private Path hlsBasePath;

    public HlsSession createSession(Long videoId, double startSeconds, Long profileId) throws IOException {
        return createSession(videoId, startSeconds, profileId, null);
    }

    public HlsSession createSession(Long videoId, double startSeconds, Long profileId, Integer preferredAudioTrackIndex) throws IOException {
        String sessionId = "vid-" + videoId;
        HlsSession session = activeSessions.get(sessionId);
        if (session != null) {
            session.markAccessed();
            return session;
        }
        Video video = videoService.findById(videoId);
        if (video == null) throw new IOException("Video not found: " + videoId);
        Path sessionDir = getHlsBasePath().resolve(sessionId).toAbsolutePath();
        Files.createDirectories(sessionDir);
        List<AudioTrack> audioTracks = video.audioTracks;
        session = new HlsSession(sessionId, video, audioTracks != null ? audioTracks : new ArrayList<>(), sessionDir, startSeconds);
        
        // Set preferred audio track if specified
        if (preferredAudioTrackIndex != null && preferredAudioTrackIndex >= 0) {
            session.setPreferredAudioTrackIndex(preferredAudioTrackIndex);
            LOG.info("HLS session created with preferred audio track index: {}", preferredAudioTrackIndex);
        }
        
        activeSessions.put(sessionId, session);
        startVariantEncoder(session, VIDEO_VARIANT, profileId);
        return session;
    }

    private void startVariantEncoder(HlsSession session, String variantName, Long profileId) {
        try {
            String resolvedPath = resolveVideoPath(session.video.path);

            List<String> command = new ArrayList<>();
            command.add(ffmpegDiscoveryService.findFFmpegExecutable());
            command.add("-ss");
            command.add(String.valueOf(session.startSeconds));
            command.add("-i");
            command.add(resolvedPath);
            if (session.audioTracks.isEmpty()) {
                command.add("-map");
                command.add("0:a?");
                command.add("-c:a");
                command.add("aac");
                command.add("-b:a");
                command.add("128k");
                command.add("-ac");
                command.add("2");
            } else if (session.audioTracks.size() == 1) {
                AudioTrack track = session.audioTracks.get(0);
                command.add("-map");
                command.add("0:a:" + track.trackIndex);
                command.add("-c:a");
                if (isCopyableCodec(track.codec)) {
                    command.add("copy");
                } else {
                    command.add("aac");
                    command.add("-b:a");
                    command.add("128k");
                    command.add("-ac");
                    command.add("2");
                }
            } else {
                command.add("-an");
                createAudioStreams(session);
            }
            command.add("-map");
            command.add("0:v:0");
            command.add("-c:v");
            String hardwareEncoder = ffmpegDiscoveryService.detectHardwareEncoder();
            if (!"libx264".equals(hardwareEncoder)) {
                LOG.info("Using hardware encoder for HLS: {}", hardwareEncoder);
                command.add(hardwareEncoder);
                command.add("-preset");
                command.add("fast");
            } else {
                command.add("libx264");
                command.add("-preset");
                command.add("ultrafast");
                command.add("-crf");
                command.add("23");
                command.add("-pix_fmt");
                command.add("yuv420p");
            }
            command.add("-f");
            command.add("hls");
            command.add("-hls_time");
            command.add("4");
            command.add("-hls_list_size");
            command.add("0");
            command.add("-hls_flags");
            command.add("append_list+omit_endlist+discont_start");
            command.add("-hls_segment_filename");
            command.add(session.sessionDir.resolve(variantName + "_%04d.ts").toString());
            command.add(session.sessionDir.resolve(variantName + ".m3u8").toString());
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.directory(session.sessionDir.toFile());
            pb.redirectErrorStream(true);
            Process process = pb.start();
            session.addProcess(variantName, process);
            new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        LOG.debug("[ffmpeg {}] {}", variantName, line);
                    }
                } catch (IOException e) {
                    LOG.warn("Error reading ffmpeg output: {}", e.getMessage());
                }
            }).start();
            LOG.info("Started HLS encoder for session {} variant {}", session.sessionId, variantName);
        } catch (Exception e) {
            LOG.error("Failed to start HLS encoder", e);
        }
    }

    private void createAudioStreams(HlsSession session) {
        String resolvedPath = resolveVideoPath(session.video.path);
        for (AudioTrack track : session.audioTracks) {
            try {
                String audioName = "audio_" + track.trackIndex;
                List<String> command = new ArrayList<>();
                command.add(ffmpegDiscoveryService.findFFmpegExecutable());
                command.add("-ss");
                command.add(String.valueOf(session.startSeconds));
                command.add("-i");
                command.add(resolvedPath);
                command.add("-map");
                command.add("0:a:" + track.trackIndex);
                command.add("-c:a");
                if (isCopyableCodec(track.codec)) {
                    command.add("copy");
                } else {
                    command.add("aac");
                    command.add("-b:a");
                    command.add("128k");
                    command.add("-ac");
                    command.add("2");
                }
                command.add("-f");
                command.add("hls");
                command.add("-hls_time");
                command.add("4");
                command.add("-hls_list_size");
                command.add("0");
                command.add("-hls_flags");
                command.add("append_list+omit_endlist+discont_start");
                command.add("-hls_segment_filename");
                command.add(session.sessionDir.resolve(audioName + "_%04d.ts").toString());
                command.add(session.sessionDir.resolve(audioName + ".m3u8").toString());
                ProcessBuilder pb = new ProcessBuilder(command);
                pb.directory(session.sessionDir.toFile());
                pb.redirectErrorStream(true);
                Process process = pb.start();
                session.addProcess(audioName, process);
                session.audioPlaylistNames.add(audioName);
                new Thread(() -> {
                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            LOG.debug("[ffmpeg {}] {}", audioName, line);
                        }
                    } catch (IOException e) {
                        LOG.warn("Error reading ffmpeg output: {}", e.getMessage());
                    }
                }).start();
                LOG.info("Started audio stream {} for session {}", audioName, session.sessionId);
            } catch (Exception e) {
                LOG.error("Failed to start audio stream for track {}", track.trackIndex, e);
            }
        }
    }

    private boolean isCopyableCodec(String codec) {
        if (codec == null) return false;
        String lower = codec.toLowerCase();
        return lower.contains("aac") || lower.contains("mp3") || lower.contains("opus") == false && lower.contains("vorbis") == false;
    }

    private String resolveVideoPath(String videoPath) {
        java.nio.file.Path vPath = java.nio.file.Paths.get(videoPath);
        if (vPath.isAbsolute()) {
            return vPath.toString();
        }
        try {
            String libraryPath = settingsService.getOrCreateSettings().getVideoLibraryPath();
            if (libraryPath != null && !libraryPath.isEmpty()) {
                return java.nio.file.Paths.get(libraryPath, videoPath).toString();
            }
        } catch (Exception e) {
            LOG.warn("Could not resolve video library path for {}: {}", videoPath, e.getMessage());
        }
        return videoPath;
    }

    public String getMasterPlaylist(String sessionId) {
        HlsSession session = activeSessions.get(sessionId);
        if (session == null) return null;
        StringBuilder sb = new StringBuilder();
        sb.append("#EXTM3U\n");
        sb.append("#EXT-X-VERSION:3\n");
        if (session.audioTracks.size() > 1) {
            for (int i = 0; i < session.audioTracks.size(); i++) {
                AudioTrack track = session.audioTracks.get(i);
                String audioName = "audio_" + track.trackIndex;
                sb.append("#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"audio\",NAME=\"" + track.displayName + "\",LANGUAGE=\"" + (track.languageCode != null ? track.languageCode : "und") + "\",AUTOSELECT=" + (track.isDefault ? "YES" : "NO") + ",DEFAULT=" + (track.isDefault ? "YES" : "NO") + ",URI=\"" + audioName + ".m3u8\"\n");
            }
            sb.append("#EXT-X-STREAM-INF:BANDWIDTH=2500000,RESOLUTION=1920x1080,CODECS=\"avc1.4d4028,mp4a.40.2\",AUDIO=\"audio\"\n");
        } else {
            sb.append("#EXT-X-STREAM-INF:BANDWIDTH=2500000,RESOLUTION=1920x1080,CODECS=\"avc1.4d4028,mp4a.40.2\"\n");
        }
        sb.append(VIDEO_VARIANT + ".m3u8\n");
        return sb.toString();
    }

    public String getMediaPlaylist(String sessionId, String variantName) {
        HlsSession session = activeSessions.get(sessionId);
        if (session == null) return null;
        Path playlistFile = session.sessionDir.resolve(variantName + ".m3u8");
        if (!Files.exists(playlistFile)) {
            return buildPartialPlaylist(session, variantName);
        }
        try {
            String content = Files.readString(playlistFile);
            if (!content.contains("#EXT-X-ENDLIST")) {
                return content + buildPartialPlaylist(session, variantName);
            }
            return content;
        } catch (IOException e) {
            LOG.warn("Error reading playlist {}: {}", playlistFile, e.getMessage());
            return buildPartialPlaylist(session, variantName);
        }
    }

    private String buildPartialPlaylist(HlsSession session, String variantName) {
        StringBuilder sb = new StringBuilder();
        if (variantName.equals(VIDEO_VARIANT)) {
            sb.append("#EXTINF:4.0,\n");
            sb.append(variantName + "_0000.ts\n");
        } else {
            sb.append("#EXTINF:4.0,\n");
            sb.append(variantName + "_0000.ts\n");
        }
        return sb.toString();
    }

    public File getSegment(String sessionId, String variantName, String segmentName) {
        HlsSession session = activeSessions.get(sessionId);
        if (session == null) return null;
        File segment = session.sessionDir.resolve(segmentName).toFile();
        return segment.exists() ? segment : null;
    }

    private synchronized Path getHlsBasePath() {
        if (hlsBasePath == null) {
            try {
                String libraryPath = settingsService.getOrCreateSettings().getVideoLibraryPath();
                if (libraryPath != null && !libraryPath.isEmpty()) {
                    hlsBasePath = java.nio.file.Paths.get(libraryPath, "hls").toAbsolutePath();
                } else {
                    hlsBasePath = Path.of(System.getProperty("user.dir")).resolve("sessions").resolve("hls").toAbsolutePath();
                }
                Files.createDirectories(hlsBasePath);
            } catch (IOException e) {
                hlsBasePath = Path.of(System.getProperty("java.io.tmpdir"), "jmedia-hls").toAbsolutePath();
            }
        }
        return hlsBasePath;
    }

    @PreDestroy
    public void shutdown() {
        activeSessions.values().forEach(HlsSession::stop);
        activeSessions.clear();
        LOG.info("HlsService shutdown complete");
    }

    public static class HlsSession {
        public final String sessionId;
        public final Video video;
        public final List<AudioTrack> audioTracks;
        public final Path sessionDir;
        public final double startSeconds;
        public final List<String> audioPlaylistNames = new ArrayList<>();
        public long lastAccessed;
        private final Map<String, Process> processes = new ConcurrentHashMap<>();
        private Integer preferredAudioTrackIndex = null;

        public HlsSession(String id, Video v, List<AudioTrack> tracks, Path d, double s) {
            sessionId = id;
            video = v;
            audioTracks = tracks;
            sessionDir = d;
            startSeconds = s;
            lastAccessed = System.currentTimeMillis();
        }

        public void markAccessed() {
            lastAccessed = System.currentTimeMillis();
        }

        public void addProcess(String variantName, Process process) {
            processes.put(variantName, process);
        }

        public void stop() {
            processes.values().forEach(p -> {
                try { p.destroyForcibly(); } catch (Exception e) {}
            });
            processes.clear();
        }

        public void setPreferredAudioTrackIndex(Integer trackIndex) {
            this.preferredAudioTrackIndex = trackIndex;
        }

        public Integer getPreferredAudioTrackIndex() {
            return preferredAudioTrackIndex;
        }
    }

    public static class SessionInfo {
        public final String sessionId;
        public final String playlistUrl;
        public SessionInfo(String id, String url) {
            sessionId = id;
            playlistUrl = url;
        }
    }
}
