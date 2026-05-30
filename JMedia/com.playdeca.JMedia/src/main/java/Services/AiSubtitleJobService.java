package Services;

import Models.SubtitleTrack;
import Models.Video;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@ApplicationScoped
public class AiSubtitleJobService {

    @Inject
    ParakeetService parakeetService;

    @Inject
    VideoService videoService;

    private final AtomicInteger jobIdCounter = new AtomicInteger(0);
    private final ConcurrentHashMap<Integer, AiSubtitleJob> jobs = new ConcurrentHashMap<>();
    private volatile AiSubtitleJob currentJob;

    public static class AiSubtitleJob {
        public final int id;
        public final List<Long> videoIds;
        public final List<VideoProgress> progressList = new ArrayList<>();
        public final java.util.Map<Long, Video> videoCache = new java.util.HashMap<>();
        public volatile String status = "pending"; // pending, running, completed, cancelled, failed
        public volatile int completedCount = 0;
        public volatile int failedCount = 0;
        public volatile int currentVideoIndex = -1;
        public volatile String currentVideoTitle = "";
        public volatile String currentVideoSeries = "";
        public volatile Integer currentVideoSeason = null;
        public volatile long currentVideoId = -1;
        public volatile double overallProgress = 0.0;
        public final String languageCode;
        public final List<String> errors = new ArrayList<>();
        public volatile long startTime;

        public AiSubtitleJob(int id, List<Long> videoIds, String languageCode) {
            this.id = id;
            this.videoIds = videoIds;
            this.languageCode = languageCode;
        }
    }

    public static class VideoProgress {
        public final long videoId;
        public final String videoTitle;
        public volatile double progress = 0.0;
        public volatile String status = "pending"; // pending, running, completed, failed, cancelled
        public volatile String error;

        public VideoProgress(long videoId, String videoTitle) {
            this.videoId = videoId;
            this.videoTitle = videoTitle;
        }
    }

    public synchronized int startBatch(List<Long> videoIds, String languageCode) {
        // Cancel any running job
        if (currentJob != null && "running".equals(currentJob.status)) {
            parakeetService.cancelGeneration();
        }

        int jobId = jobIdCounter.incrementAndGet();
        AiSubtitleJob job = new AiSubtitleJob(jobId, videoIds, languageCode);
        // Pre-fetch and cache videos (within the request's Hibernate session)
        for (Long vid : videoIds) {
            Video v = Video.findById(vid);
            job.progressList.add(new VideoProgress(vid, v != null ? (v.title != null ? v.title : v.filename) : "Unknown"));
            if (v != null) {
                job.videoCache.put(vid, v);
            }
        }
        // Set the first video info immediately so the frontend shows it
        if (!videoIds.isEmpty() && !job.videoIds.isEmpty()) {
            job.currentVideoTitle = job.progressList.get(0).videoTitle;
            job.currentVideoId = videoIds.get(0);
            Video first = job.videoCache.get(videoIds.get(0));
            if (first != null) {
                job.currentVideoSeries = first.seriesTitle != null ? first.seriesTitle : "";
                job.currentVideoSeason = first.seasonNumber;
            }
        }
        jobs.put(jobId, job);
        currentJob = job;

        // Start processing in background
        CompletableFuture.runAsync(() -> processJob(job));

        return jobId;
    }

    private void processJob(AiSubtitleJob job) {
        job.status = "running";
        job.startTime = System.currentTimeMillis();

        for (int i = 0; i < job.videoIds.size(); i++) {
            if (!"running".equals(job.status)) break;

            Long videoId = job.videoIds.get(i);
            VideoProgress vp = job.progressList.get(i);
            job.currentVideoIndex = i;
            job.currentVideoId = videoId;

            Video video = job.videoCache.get(videoId);
            if (video == null) {
                job.currentVideoTitle = vp.videoTitle;
                job.currentVideoSeries = "";
                job.currentVideoSeason = null;
                vp.status = "failed";
                vp.error = "Video not found in cache";
                job.failedCount++;
                job.errors.add(vp.videoTitle + ": Video not found");
                job.overallProgress = calculateOverallProgress(job);
                continue;
            }

            job.currentVideoTitle = video.title != null ? video.title : (video.episodeTitle != null ? video.episodeTitle : video.filename);
            job.currentVideoSeries = video.seriesTitle != null ? video.seriesTitle : "";
            job.currentVideoSeason = video.seasonNumber;
            job.currentVideoId = video.id;
            vp.status = "running";

            try {
                parakeetService.generateSubtitle(video, job.languageCode, progress -> {
                    vp.progress = progress;
                    job.overallProgress = calculateOverallProgress(job);
                }).get();

                vp.status = "completed";
                vp.progress = 100.0;
                job.completedCount++;
            } catch (Exception e) {
                String msg = e.getCause() != null ? e.getCause().getMessage() : e.getMessage();
                if (msg != null && msg.contains("cancelled")) {
                    vp.status = "cancelled";
                    job.status = "cancelled";
                    return;
                }
                vp.status = "failed";
                vp.error = msg;
                job.failedCount++;
                job.errors.add(vp.videoTitle + ": " + msg);
            }

            job.overallProgress = calculateOverallProgress(job);
        }

        if ("running".equals(job.status)) {
            job.status = "completed";
            job.overallProgress = 100.0;
        }
    }

    private double calculateOverallProgress(AiSubtitleJob job) {
        if (job.videoIds.isEmpty()) return 100.0;
        double total = 0;
        for (VideoProgress vp : job.progressList) {
            total += vp.progress;
        }
        return total / job.videoIds.size();
    }

    public void cancelJob(int jobId) {
        AiSubtitleJob job = jobs.get(jobId);
        if (job != null && "running".equals(job.status)) {
            job.status = "cancelled";
            parakeetService.cancelGeneration();
        }
    }

    public void cancelCurrentJob() {
        if (currentJob != null) {
            cancelJob(currentJob.id);
        }
    }

    public AiSubtitleJob getJob(int jobId) {
        return jobs.get(jobId);
    }

    public AiSubtitleJob getCurrentJob() {
        return currentJob;
    }
}