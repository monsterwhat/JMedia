package Services;

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
import java.util.Arrays;
import java.util.Comparator;

@ApplicationScoped
public class HlsService {

    private static final Logger LOG = LoggerFactory.getLogger(HlsService.class);

    @Inject
    VideoService videoService;

    @Inject
    SettingsService settingsService;

    @Inject
    FFmpegDiscoveryService ffmpegDiscoveryService;

    private final Map<String, HlsSession> activeSessions = new ConcurrentHashMap<>();
    private String cachedEncoder = null;
    private Path hlsBasePath;
    private String videoLibraryBasePath = null;

    private synchronized Path getHlsBasePath() {
        if (hlsBasePath == null) {
            // Store HLS files alongside the video library in a /hls/ subfolder
            // This makes it easier to manage - same drive/partition as videos
            try {
                String libraryPath = settingsService.getOrCreateSettings().getVideoLibraryPath();
                if (libraryPath != null && !libraryPath.isEmpty()) {
                    videoLibraryBasePath = libraryPath;
                    hlsBasePath = java.nio.file.Paths.get(libraryPath, "hls").toAbsolutePath();
                } else {
                    // Fallback to project sessions folder
                    hlsBasePath = Path.of(System.getProperty("user.dir")).resolve("sessions").resolve("hls").toAbsolutePath();
                }
                Files.createDirectories(hlsBasePath);
                LOG.info("HLS: Using base path: {}", hlsBasePath);
            } catch (IOException e) {
                hlsBasePath = Path.of(System.getProperty("java.io.tmpdir"), "jmedia-hls").toAbsolutePath();
                LOG.warn("HLS: Failed to use library path, using temp: {}", hlsBasePath);
            }
        }
        return hlsBasePath;
    }

    public HlsSession createSession(Long videoId, double startSeconds, Long profileId) throws IOException {
        // Session is per VIDEO, not per time bucket - the same transcoding serves all seek positions
        // We use video ID only as the session key (no time in the key)
        String sessionId = "vid-" + videoId;
        
        // CHECK: Is there already a process for this video that's alive?
        // If yes, return that session instead of creating a new one
        for (HlsSession existing : activeSessions.values()) {
            if (existing.video.id.equals(videoId)) {
                Process p = existing.activeProcesses.get("main");
                if (p != null && p.isAlive()) {
                    LOG.info("HLS: Reusing existing session {} for video {}", existing.sessionId, videoId);
                    existing.markAccessed();
                    return existing;
                }
            }
        }
        
        // KILL ANY STALE PROCESSES FOR THIS VIDEO (just in case)
        activeSessions.values().removeIf(s -> {
            if (s.video.id.equals(videoId)) {
                s.activeProcesses.values().forEach(p -> {
                    if (p.isAlive()) {
                        LOG.info("HLS: Killing stale process for video {}", videoId);
                        p.destroyForcibly();
                    }
                });
                return true; // Remove from map
            }
            return false;
        });

        HlsSession session = activeSessions.get(sessionId);
        if (session != null) {
            session.markAccessed();
            Process p = session.activeProcesses.get("main");
            // If process is still running, return it (it will handle seeking to any position)
            if (p != null && p.isAlive()) return session;
        }

        Video video = videoService.findById(videoId);
        if (video == null) throw new IOException("Video not found: " + videoId);

        String videoLibraryPath = settingsService.getOrCreateSettings().getVideoLibraryPath();
        java.nio.file.Path filePath = java.nio.file.Paths.get(video.path).isAbsolute() 
            ? java.nio.file.Paths.get(video.path) 
            : java.nio.file.Paths.get(videoLibraryPath, video.path).toAbsolutePath();

        File videoFile = filePath.toFile();
        if (!videoFile.exists()) throw new IOException("Video file not found: " + videoFile);

        Path sessionDir = getHlsBasePath().resolve(sessionId).toAbsolutePath();
        
        // Check if directory already has completed transcoding
        boolean alreadyCompleted = false;
        File playlistFile = sessionDir.resolve("playlist.m3u8").toFile();
        if (playlistFile.exists()) {
            try {
                String content = Files.readString(playlistFile.toPath());
                if (content.contains("#EXT-X-ENDLIST")) {
                    alreadyCompleted = true;
                    LOG.info("HLS: Found completed transcoding for {} in {}", sessionId, sessionDir);
                }
            } catch (IOException ignored) {}
        }
        
        // If already completed, don't start a new process - just return the session
        if (alreadyCompleted) {
            if (session == null) {
                session = new HlsSession(sessionId, video, videoFile, sessionDir, startSeconds);
                activeSessions.put(sessionId, session);
            }
            session.markAccessed();
            return session;
        }
        
        Files.createDirectories(sessionDir);

        // Start transcoding (no need to check again - we know it's not complete)
        if (session == null) {
            session = new HlsSession(sessionId, video, videoFile, sessionDir, startSeconds);
            activeSessions.put(sessionId, session);
        }

        if (video.videoCodec == null) videoService.probeVideoMetadata(video);
        startFFmpegProcesses(session);

        return session;
    }

    private void startFFmpegProcesses(HlsSession session) throws IOException {
        String ffmpegPath = ffmpegDiscoveryService.findFFmpegExecutable();
        if (ffmpegPath == null) throw new IOException("FFmpeg not found");
        startVariantEncoder(session, ffmpegPath, new QualityVariant("main", 2500, -1, -1));
    }

    private synchronized String getHardwareEncoder() {
        if (cachedEncoder != null) return cachedEncoder;
        String ffmpegPath = ffmpegDiscoveryService.findFFmpegExecutable();
        if (ffmpegPath == null) return "libx264";

        try {
            Process process = new ProcessBuilder(ffmpegPath, "-encoders").start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                StringBuilder encoders = new StringBuilder();
                while ((line = reader.readLine()) != null) encoders.append(line).append("\n");
                String all = encoders.toString();
                if (all.contains("h264_nvenc")) cachedEncoder = "h264_nvenc";
                else if (all.contains("h264_qsv")) cachedEncoder = "h264_qsv";
                else if (all.contains("h264_videotoolbox")) cachedEncoder = "h264_videotoolbox";
                else if (all.contains("h264_amf")) cachedEncoder = "h264_amf";
                else if (all.contains("h264_vaapi")) cachedEncoder = "h264_vaapi";
                else cachedEncoder = "libx264";
                LOG.info("HLS: Detected hardware encoder: {}", cachedEncoder);
            }
        } catch (IOException e) {
            cachedEncoder = "libx264";
        }
        return cachedEncoder;
    }

    private void startVariantEncoder(HlsSession session, String ffmpegPath, QualityVariant variant) throws IOException {
        String inputPathStr = session.videoFile.getAbsolutePath().replace("\\", "/");
        String playlistPathStr = session.sessionDir.resolve("playlist.m3u8").toAbsolutePath().toString();
        String encoder = getHardwareEncoder();
        
        LOG.info("HLS: Using encoder '{}' for session {}", encoder, session.sessionId);
        
        // Determine hardware acceleration for DECODING based on encoder
        String hwAccel = null;
        if (encoder.equals("h264_nvenc")) {
            hwAccel = "cuda";
        } else if (encoder.equals("h264_qsv")) {
            hwAccel = "qsv";
        } else if (encoder.equals("h264_vaapi")) {
            hwAccel = "vaapi";
        } else if (encoder.equals("h264_amf")) {
            hwAccel = "auto";
        }
        
        List<String> command = new ArrayList<>();
        command.add(ffmpegPath);
        command.add("-loglevel"); command.add("info");
        command.add("-hide_banner");
        command.add("-y");
        
        // Add hardware acceleration for decoding if available
        if (hwAccel != null) {
            command.add("-hwaccel"); command.add(hwAccel);
            if (encoder.equals("h264_vaapi")) {
                command.add("-vaapi_device"); command.add("/dev/dri/renderD128");
            }
        }
        
        command.add("-i"); command.add(inputPathStr);
        
        // If using VAAPI, we need to use -vf for hardware upload
        boolean useVaapiFilter = false;
        if (encoder.equals("h264_vaapi") && hwAccel != null) {
            command.add("-vf"); command.add("format=nv12,hwupload");
            useVaapiFilter = true;
        }
        
        // Include video and audio streams only - skip subtitles for now
        command.add("-map"); command.add("0:v:0");
        command.add("-map"); command.add("0:a:0");
        command.add("-sn"); // Skip subtitles
        
        // Encoder-specific settings
        if (encoder.equals("libx264")) {
            command.add("-c:v"); command.add("libx264");
            command.add("-preset"); command.add("ultrafast");
            command.add("-profile:v"); command.add("main");
            command.add("-level"); command.add("4.0");
            command.add("-crf"); command.add("23");
        } else if (encoder.equals("h264_nvenc")) {
            command.add("-c:v"); command.add("h264_nvenc");
            command.add("-preset"); command.add("p1");
            command.add("-cq"); command.add("23");
            command.add("-rc"); command.add("vbr");
        } else if (encoder.equals("h264_qsv")) {
            command.add("-c:v"); command.add("h264_qsv");
            command.add("-preset"); command.add("veryfast");
            command.add("-global_quality"); command.add("23");
            command.add("-load_plugin"); command.add("hevc");
        } else if (encoder.equals("h264_amf")) {
            command.add("-c:v"); command.add("h264_amf");
            command.add("-quality"); command.add("speed");
            command.add("-rc"); command.add("cbr");
        } else if (encoder.equals("h264_videotoolbox")) {
            command.add("-c:v"); command.add("h264_videotoolbox");
            command.add("-preset"); command.add("fast");
            command.add("-profile:v"); command.add("main");
        } else if (encoder.equals("h264_vaapi")) {
            command.add("-c:v"); command.add("h264_vaapi");
            command.add("-rc_mode"); command.add("CBR");
        } else {
            // fallback
            command.add("-c:v"); command.add("libx264");
            command.add("-preset"); command.add("ultrafast");
            command.add("-crf"); command.add("23");
        }

        command.add("-pix_fmt"); command.add("yuv420p");
        command.add("-c:a"); command.add("aac");
        command.add("-b:a"); command.add("128k");
        command.add("-ac"); command.add("2");

        // No subtitle copy - HLS muxer doesn't support ASS/SSA
        // Subtitles will be loaded from original video/DB instead
        
        command.add("-f"); command.add("hls");
        command.add("-hls_time"); command.add("4");
        command.add("-hls_list_size"); command.add("0");
        command.add("-hls_playlist_type"); command.add("vod");  // Change from 'event' to 'vod' for full file
        command.add("-hls_segment_type"); command.add("mpegts");
        command.add("-hls_flags"); command.add("independent_segments");
        command.add(playlistPathStr);

        LOG.info("HLS: Starting FFmpeg for session {}", session.sessionId);
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        Process process = pb.start();
        session.activeProcesses.put("main", process);
        
        new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) LOG.info("[FFmpeg-{}] {}", session.sessionId, line);
            } catch (IOException ignored) {}
        }).start();
    }

    @io.quarkus.scheduler.Scheduled(every = "12h")
    public void scheduledCleanup() {
        long now = System.currentTimeMillis();
        long expiryTime = 48L * 60 * 60 * 1000;
        
        // 1. Cleanup in-memory sessions
        activeSessions.entrySet().removeIf(entry -> {
            HlsSession session = entry.getValue();
            if (now - session.lastAccessed > expiryTime) {
                for (Process p : session.activeProcesses.values()) { if (p.isAlive()) p.destroyForcibly(); }
                deleteDirectory(session.sessionDir.toFile());
                LOG.info("HLS: Cleaned up expired session {}", session.sessionId);
                return true;
            }
            return false;
        });

        // 2. Cleanup orphaned folders on disk (sessions no longer tracked in memory but still exist)
        cleanupOrphanedDiskSessions();
    }

    private void cleanupOrphanedDiskSessions() {
        try {
            Path hlsPath = getHlsBasePath();
            if (!Files.exists(hlsPath)) return;
            
            File[] dirs = hlsPath.toFile().listFiles(File::isDirectory);
            if (dirs == null) return;
            
            for (File dir : dirs) {
                String sessionId = dir.getName();
                if (!activeSessions.containsKey(sessionId)) {
                    LOG.info("HLS: Removing orphaned disk session {}", sessionId);
                    deleteDirectory(dir);
                }
            }
        } catch (Exception e) {
            LOG.warn("HLS: Failed to cleanup orphaned sessions: {}", e.getMessage());
        }
    }

    public HlsSession getSession(String sessionId) {
        HlsSession session = activeSessions.get(sessionId);
        if (session != null) {
            session.markAccessed();
            return session;
        }
        
        // Session not in memory - check if there's a completed directory on disk
        if (sessionId.startsWith("vid-")) {
            try {
                Long videoId = Long.parseLong(sessionId.substring(4));
                Path sessionDir = getHlsBasePath().resolve(sessionId).toAbsolutePath();
                File playlistFile = sessionDir.resolve("playlist.m3u8").toFile();
                
                if (playlistFile.exists()) {
                    String content = Files.readString(playlistFile.toPath());
                    if (content.contains("#EXT-X-ENDLIST")) {
                        // Completed transcoding exists on disk - re-create session in memory
                        LOG.info("HLS: Reactivating completed session {} from disk", sessionId);
                        Video video = videoService.findById(videoId);
                        if (video != null) {
                            String videoLibraryPath = settingsService.getOrCreateSettings().getVideoLibraryPath();
                            java.nio.file.Path filePath = java.nio.file.Paths.get(video.path).isAbsolute() 
                                ? java.nio.file.Paths.get(video.path) 
                                : java.nio.file.Paths.get(videoLibraryPath, video.path).toAbsolutePath();
                            File videoFile = filePath.toFile();
                            
                            session = new HlsSession(sessionId, video, videoFile, sessionDir, 0.0);
                            activeSessions.put(sessionId, session);
                            session.markAccessed();
                            return session;
                        }
                    }
                }
            } catch (Exception e) {
                LOG.debug("HLS: Could not reactivate session from disk: {}", e.getMessage());
            }
        }
        
        return null;
    }

    public SessionInfo checkSession(Long videoId) {
        for (HlsSession session : activeSessions.values()) {
            if (session.video.id.equals(videoId)) {
                session.markAccessed();
                return new SessionInfo(session.sessionId, "/api/video/hls/" + session.sessionId + "/playlist.m3u8");
            }
        }
        return null;
    }

    private void deleteDirectory(File dir) {
        File[] files = dir.listFiles();
        if (files != null) { for (File f : files) deleteDirectory(f); }
        dir.delete();
    }

    public String getMasterPlaylist(String sessionId) {
        if (sessionId.startsWith("vid-") && !activeSessions.containsKey(sessionId)) {
            try {
                Long videoId = Long.parseLong(sessionId.substring(4));
                createSession(videoId, 0.0, null);
            } catch (Exception e) { LOG.warn("HLS: Auto-init failed: {}", e.getMessage()); }
        }

        HlsSession session = getSession(sessionId);
        if (session == null) return null;

        int w = 1920;
        int h = 1080;
        
        if (session.video.resolution != null && session.video.resolution.contains("x")) {
            try {
                String[] parts = session.video.resolution.split("x");
                w = Integer.parseInt(parts[0]);
                h = Integer.parseInt(parts[1]);
            } catch (Exception ignored) {}
        }

        return "#EXTM3U\n#EXT-X-VERSION:3\n" +
               "#EXT-X-STREAM-INF:BANDWIDTH=2500000,RESOLUTION=" + w + "x" + h + ",CODECS=\"avc1.4d4028,mp4a.40.2\"\n" +
               "media.m3u8\n";
    }

    public String getMediaPlaylist(String sessionId, String variantName) {
        HlsSession session = getSession(sessionId);
        if (session == null) return null;

        Path playlistPath = session.sessionDir.resolve("playlist.m3u8").toAbsolutePath();
        File playlistFile = playlistPath.toFile();
        
        // Check if FFmpeg is still running
        Process process = session.activeProcesses.get("main");
        boolean isStillGenerating = process != null && process.isAlive();
        
        // If still generating, ALWAYS use our partial playlist builder
        // FFmpeg writes the full playlist upfront but segments aren't ready yet
        if (isStillGenerating) {
            return buildPartialPlaylist(session);
        }
        
        // FFmpeg is done - check if completed playlist exists
        if (playlistFile.exists() && playlistFile.length() > 0) {
            try {
                String content = Files.readString(playlistPath);
                // Only use FFmpeg's playlist if it has ENDLIST (fully complete)
                if (content.contains("#EXT-X-ENDLIST")) {
                    session.markAccessed();
                    return content;
                }
            } catch (IOException ignored) {}
        }
        
        // Fallback to partial playlist if something went wrong
        return buildPartialPlaylist(session);
    }
    
    private String buildPartialPlaylist(HlsSession session) {
        StringBuilder playlist = new StringBuilder();
        playlist.append("#EXTM3U\n");
        playlist.append("#EXT-X-VERSION:3\n");
        playlist.append("#EXT-X-TARGETDURATION:4\n");
        playlist.append("#EXT-X-MEDIA-SEQUENCE:0\n");
        
        File[] files = session.sessionDir.toFile().listFiles((dir, name) -> name.matches("playlist\\d+\\.ts"));
        if (files != null) {
            // Sort numerically, not alphabetically (to avoid playlist10 coming before playlist2)
            Arrays.sort(files, (a, b) -> {
                int aNum = Integer.parseInt(a.getName().replaceAll("\\D+", ""));
                int bNum = Integer.parseInt(b.getName().replaceAll("\\D+", ""));
                return Integer.compare(aNum, bNum);
            });
            for (File segment : files) {
                long duration = 4; // Approximate - hls_time is 4 seconds
                playlist.append("#EXTINF:").append(duration).append(".0,\n");
                playlist.append(segment.getName()).append("\n");
            }
        }
        
        // Don't add ENDLIST - stream is still being generated
        LOG.debug("HLS: Returning partial playlist with {} segments", files != null ? files.length : 0);
        return playlist.toString();
    }

    public File getSegment(String sessionId, String variantName, String segmentName) {
        HlsSession session = getSession(sessionId);
        if (session == null) return null;
        File segment = session.sessionDir.resolve(segmentName).toFile();
        if (segment.exists()) {
            session.markAccessed();
            return segment;
        }
        return null;
    }

    @PreDestroy
    public void cleanupAll() {
        for (HlsSession s : activeSessions.values()) {
            for (Process p : s.activeProcesses.values()) { if (p.isAlive()) p.destroyForcibly(); }
        }
    }

    public static class HlsSession {
        public final String sessionId;
        public final Video video;
        public final File videoFile;
        public final Path sessionDir;
        public final double startSeconds;
        public final Map<String, Process> activeProcesses = new ConcurrentHashMap<>();
        public long lastAccessed;

        public HlsSession(String id, Video v, File f, Path d, double s) {
            sessionId = id; video = v; videoFile = f; sessionDir = d; startSeconds = s; lastAccessed = System.currentTimeMillis();
        }
        public void markAccessed() { lastAccessed = System.currentTimeMillis(); }
    }

    public static class QualityVariant {
        public final String name;
        public final int bitrate;
        public final int width;
        public final int height;
        public QualityVariant(String n, int b, int w, int h) { name = n; bitrate = b; width = w; height = h; }
    }

    public static class SessionInfo {
        public final String sessionId;
        public final String playlistUrl;
        public SessionInfo(String id, String url) { sessionId = id; playlistUrl = url; }
    }
}