package Services;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ApplicationScoped
public class GpuScheduler {

    private static final Logger LOG = LoggerFactory.getLogger(GpuScheduler.class);

    @Inject
    GpuDetectionService gpuDetectionService;

    public record GpuLease(GpuDetectionService.GpuInfo gpu) {}

    private final Map<String, AtomicInteger> loadByKey = new ConcurrentHashMap<>();

    private String keyOf(GpuDetectionService.GpuInfo g) {
        return g.devicePath() != null ? g.devicePath() : g.vendor() + ":" + g.deviceIndex();
    }

    public synchronized GpuLease acquire(String videoCodec, String hwEncoder) {
        List<GpuDetectionService.GpuInfo> pool = gpuDetectionService.getUsableGpus();
        if (pool.isEmpty() || hwEncoder == null || hwEncoder.startsWith("libx")) {
            return null;
        }
        String codecFamily = codecFamilyOf(videoCodec);
        String backend = backendOf(hwEncoder);
        List<GpuDetectionService.GpuInfo> eligible = pool.stream()
            .filter(g -> backendOfGpu(g).equals(backend))
            .filter(g -> g.supportsEncoder(hwEncoder))
            .filter(g -> codecFamily == null || g.supportsEncoder(codecFamily + "_" + backend))
            .toList();
        if (eligible.isEmpty()) {
            LOG.debug("GpuScheduler: no capable GPU for codec={} encoder={}, software fallback", videoCodec, hwEncoder);
            return null;
        }
        GpuDetectionService.GpuInfo best = eligible.stream()
            .min(Comparator.comparingInt(g -> loadByKey.computeIfAbsent(keyOf(g), k -> new AtomicInteger(0)).get()))
            .orElse(null);
        if (best == null) {
            return null;
        }
        int load = loadByKey.computeIfAbsent(keyOf(best), k -> new AtomicInteger(0)).incrementAndGet();
        LOG.info("GpuScheduler: acquired {} for codec={} encoder={} (load={})", keyOf(best), videoCodec, hwEncoder, load);
        return new GpuLease(best);
    }

    public synchronized void release(GpuLease lease) {
        if (lease == null || lease.gpu() == null) {
            return;
        }
        AtomicInteger counter = loadByKey.get(keyOf(lease.gpu()));
        if (counter == null) {
            return;
        }
        int remaining = counter.decrementAndGet();
        if (remaining < 0) {
            counter.set(0);
            remaining = 0;
        }
        LOG.info("GpuScheduler: released {} (load={})", keyOf(lease.gpu()), remaining);
    }

    static String codecFamilyOf(String videoCodec) {
        if (videoCodec == null) {
            return null;
        }
        String c = videoCodec.toLowerCase();
        if (c.contains("av1")) {
            return "av1";
        }
        if (c.contains("hevc") || c.contains("h265") || c.contains("h.265")) {
            return "hevc";
        }
        if (c.contains("h264") || c.contains("avc") || c.contains("h.264")) {
            return "h264";
        }
        if (c.contains("vp9")) {
            return "vp9";
        }
        return null;
    }

    static String backendOf(String encoder) {
        if (encoder == null) {
            return "";
        }
        if (encoder.contains("nvenc") || encoder.contains("cuvid") || encoder.contains("cuda")) {
            return "nvenc";
        }
        if (encoder.contains("qsv")) {
            return "qsv";
        }
        if (encoder.contains("vaapi")) {
            return "vaapi";
        }
        if (encoder.contains("amf")) {
            return "amf";
        }
        if (encoder.contains("videotoolbox")) {
            return "videotoolbox";
        }
        return encoder;
    }

    static String backendOfGpu(GpuDetectionService.GpuInfo g) {
        if (g.vendor() == GpuDetectionService.GpuVendor.NVIDIA) {
            return "nvenc";
        }
        if (g.vendor() == GpuDetectionService.GpuVendor.INTEL) {
            if (g.supportsEncoder("h264_qsv") || g.supportsEncoder("hevc_qsv") || g.supportsEncoder("av1_qsv")) {
                return "qsv";
            }
            return "vaapi";
        }
        if (g.vendor() == GpuDetectionService.GpuVendor.AMD) {
            return "vaapi";
        }
        return "";
    }
}
