package Services;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * HDR to SDR tone mapping for every FFmpeg pipeline (progressive streaming, its
 * gap fills, HLS and permanent conversion).
 *
 * <p>An HDR10/HLG source encoded to 8-bit H.264 without tone mapping comes out
 * washed out and grey. Which tone mapper can run depends on the GPU and on the
 * filters the installed FFmpeg build has, so the method is chosen per command,
 * best first:
 * <ol>
 *   <li>{@link Method#QSV} — Intel (Arc, iGPU): {@code vpp_qsv=tonemap=1}, frames
 *       stay on the GPU. Windows and Linux.</li>
 *   <li>{@link Method#VAAPI} — Intel/AMD on Linux: {@code tonemap_vaapi}, frames
 *       stay on the GPU.</li>
 *   <li>{@link Method#LIBPLACEBO} — any Vulkan GPU, including NVIDIA, when the
 *       build has libplacebo.</li>
 *   <li>{@link Method#OPENCL} — any OpenCL GPU, including NVIDIA, when the build
 *       has {@code tonemap_opencl}.</li>
 *   <li>{@link Method#CPU} — {@code zscale + tonemap}. About 0.85x real time at
 *       4K on a 16-thread CPU, so live streams are tone-mapped at no more than
 *       1080p.</li>
 * </ol>
 * The FFmpeg "essentials" build that {@code choco install ffmpeg} installs has
 * vpp_qsv, tonemap_vaapi and zscale, but neither libplacebo nor OpenCL: NVIDIA
 * cards then use hardware decode/encode with CPU tone mapping.
 */
@ApplicationScoped
public class HdrToneMapService {

    private static final Logger LOG = LoggerFactory.getLogger(HdrToneMapService.class);

    /** Tallest live stream the CPU tone mapper can keep up with. */
    static final int CPU_REALTIME_MAX_HEIGHT = 1080;

    /** HDR10/HLG to SDR BT.709 on the CPU: linearise, BT.2020 to BT.709, Hable curve, back to 8-bit BT.709. */
    static final String CPU_TONE_MAP = "zscale=t=linear:npl=100,format=gbrpf32le,zscale=p=bt709,"
            + "tonemap=tonemap=hable:desat=0,zscale=t=bt709:m=bt709:r=tv,format=yuv420p";

    public enum Method { QSV, VAAPI, LIBPLACEBO, OPENCL, CPU }

    /**
     * How to tone-map one command.
     *
     * @param framesOnDevice  the filter consumes GPU frames: keep
     *                        {@code -hwaccel_output_format}; otherwise decoded frames
     *                        must come back to system memory
     * @param preInputArgs    arguments placed before {@code -i} (device setup)
     * @param filter          the complete {@code -vf}, scaling included
     * @param softwarePixFmt  {@code -pix_fmt} for the encoder, or null for GPU frames
     */
    public record Plan(Method method, boolean framesOnDevice, List<String> preInputArgs,
                       String filter, String softwarePixFmt, int width, int height) {}

    @Inject
    FFmpegDiscoveryService discoveryService;

    private volatile Set<String> filters;
    private final java.util.Map<String, Boolean> hdrCache = new ConcurrentHashMap<>();

    // ── detection ────────────────────────────────────────────────────────

    /** True when the file's main video stream is PQ (HDR10/HDR10+/DV base) or HLG. Cached per file version. */
    public boolean isHdr(String absolutePath) {
        if (absolutePath == null) return false;
        File file = new File(absolutePath);
        String key = absolutePath + "|" + file.lastModified() + "|" + file.length();
        return hdrCache.computeIfAbsent(key, k -> probeHdr(absolutePath));
    }

    static boolean isHdrTransfer(String colorTransfer) {
        if (colorTransfer == null) return false;
        String t = colorTransfer.trim().toLowerCase(Locale.ROOT);
        return t.equals("smpte2084") || t.equals("arib-std-b67");
    }

    private boolean probeHdr(String path) {
        String ffprobe = discoveryService != null ? discoveryService.findFFprobeExecutable() : null;
        if (ffprobe == null) return false;
        Process process = null;
        try {
            process = new ProcessBuilder(ffprobe, "-v", "error", "-select_streams", "V:0",
                    "-show_entries", "stream=color_transfer", "-of", "default=nw=1:nk=1", path)
                    .redirectErrorStream(true).start();
            String transfer;
            try (BufferedReader r = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                transfer = r.readLine();
            }
            if (!process.waitFor(15, TimeUnit.SECONDS)) return false;
            boolean hdr = isHdrTransfer(transfer);
            if (hdr) LOG.info("HDR source ({}): {}", transfer, path);
            return hdr;
        } catch (Exception e) {
            LOG.warn("HDR probe failed for {}: {}", path, e.getMessage());
            return false;
        } finally {
            if (process != null && process.isAlive()) process.destroyForcibly();
        }
    }

    /** Filter names of the installed FFmpeg, read once. */
    public Set<String> availableFilters() {
        Set<String> f = filters;
        if (f != null) return f;
        Set<String> names = new HashSet<>();
        String ffmpeg = discoveryService != null ? discoveryService.findFFmpegExecutable() : null;
        if (ffmpeg != null) {
            Process process = null;
            try {
                process = new ProcessBuilder(ffmpeg, "-hide_banner", "-filters").redirectErrorStream(true).start();
                try (BufferedReader r = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        // " .S zscale  V->V  description": flags, name, io, text
                        String[] parts = line.trim().split("\\s+");
                        if (parts.length >= 3 && parts[2].contains("->")) names.add(parts[1]);
                    }
                }
                process.waitFor(15, TimeUnit.SECONDS);
            } catch (Exception e) {
                LOG.warn("Could not list FFmpeg filters: {}", e.getMessage());
            } finally {
                if (process != null && process.isAlive()) process.destroyForcibly();
            }
        }
        f = Collections.unmodifiableSet(names);
        filters = f;
        LOG.info("Tone mapping available via: {}", describeMethods(f));
        return f;
    }

    private static String describeMethods(Set<String> f) {
        List<String> m = new ArrayList<>();
        if (f.contains("vpp_qsv")) m.add("QSV");
        if (f.contains("tonemap_vaapi")) m.add("VAAPI");
        if (f.contains("libplacebo")) m.add("libplacebo");
        if (f.contains("tonemap_opencl")) m.add("OpenCL");
        if (f.contains("zscale") && f.contains("tonemap")) m.add("CPU");
        return m.isEmpty() ? "none" : String.join(", ", m);
    }

    // ── planning ─────────────────────────────────────────────────────────

    /** True when the output cannot carry HDR: H.264 is always 8-bit SDR here. */
    public static boolean needsToneMap(boolean sourceIsHdr, String videoEncoder) {
        return sourceIsHdr && videoEncoder != null
                && (videoEncoder.contains("h264") || videoEncoder.equals("libx264"));
    }

    /** {@link #plan(Set, String, String, String, int, boolean)} with the installed FFmpeg's filters. */
    public Plan plan(String hwDecoder, String videoEncoder, String resolution, int targetHeight, boolean realtime) {
        return plan(availableFilters(), hwDecoder, videoEncoder, resolution, targetHeight, realtime);
    }

    /**
     * Picks the tone mapper for one command, or null when the build has none.
     *
     * @param resolution   source "WxH" (1920x1080 assumed when unknown)
     * @param targetHeight requested output height, 0 for the source height (never upscales)
     * @param realtime     live streaming: the CPU method is capped at 1080p
     */
    static Plan plan(Set<String> filters, String hwDecoder, String videoEncoder, String resolution,
                     int targetHeight, boolean realtime) {
        int srcW = 1920, srcH = 1080;
        try {
            if (resolution != null && resolution.contains("x")) {
                String[] p = resolution.split("x");
                srcW = Integer.parseInt(p[0].trim());
                srcH = Integer.parseInt(p[1].trim());
            }
        } catch (Exception ignored) {
            // keep 1920x1080
        }
        String dec = hwDecoder == null ? "" : hwDecoder;
        String enc = videoEncoder == null ? "" : videoEncoder;
        boolean x264 = enc.equals("libx264");
        String softFmt = x264 ? "yuv420p" : "nv12";

        int h = targetHeight > 0 && targetHeight < srcH ? targetHeight : srcH;

        if (dec.contains("qsv") && enc.contains("qsv") && filters.contains("vpp_qsv")) {
            int[] wh = dims(srcW, srcH, h);
            return new Plan(Method.QSV, true, List.of(),
                    "vpp_qsv=w=" + wh[0] + ":h=" + wh[1] + ":format=nv12:tonemap=1", null, wh[0], wh[1]);
        }
        if (dec.contains("vaapi") && enc.contains("vaapi") && filters.contains("tonemap_vaapi")) {
            int[] wh = dims(srcW, srcH, h);
            String f = "tonemap_vaapi=format=nv12:t=bt709:m=bt709:p=bt709";
            if (wh[1] != srcH && filters.contains("scale_vaapi")) {
                f += ",scale_vaapi=w=" + wh[0] + ":h=" + wh[1];
            }
            return new Plan(Method.VAAPI, true, List.of(), f, null, wh[0], wh[1]);
        }

        // The remaining methods take frames in system memory. A VAAPI encoder then
        // needs the result uploaded again.
        String upload = enc.contains("vaapi") ? ",format=nv12,hwupload" : "";
        String pixFmt = enc.contains("vaapi") ? null : softFmt;

        if (filters.contains("libplacebo")) {
            int[] wh = dims(srcW, srcH, h);
            String f = "libplacebo=w=" + wh[0] + ":h=" + wh[1] + ":tonemapping=auto:colorspace=bt709"
                    + ":color_primaries=bt709:color_trc=bt709:range=tv:format=yuv420p" + upload;
            return new Plan(Method.LIBPLACEBO, false, List.of(), f, pixFmt, wh[0], wh[1]);
        }
        if (filters.contains("tonemap_opencl")) {
            int[] wh = dims(srcW, srcH, h);
            String scale = wh[1] != srcH ? "scale=" + wh[0] + ":" + wh[1] + "," : "";
            String f = scale + "format=p010le,hwupload,tonemap_opencl=tonemap=hable:desat=0"
                    + ":t=bt709:m=bt709:p=bt709:r=tv:format=nv12,hwdownload,format=nv12" + upload;
            return new Plan(Method.OPENCL, false,
                    List.of("-init_hw_device", "opencl=ocl", "-filter_hw_device", "ocl"),
                    f, pixFmt, wh[0], wh[1]);
        }
        if (filters.contains("zscale") && filters.contains("tonemap")) {
            int cpuH = realtime ? Math.min(h, CPU_REALTIME_MAX_HEIGHT) : h;
            int[] wh = dims(srcW, srcH, cpuH);
            String scale = wh[1] != srcH ? "scale=" + wh[0] + ":" + wh[1] + "," : "";
            return new Plan(Method.CPU, false, List.of(), scale + CPU_TONE_MAP + upload, pixFmt, wh[0], wh[1]);
        }
        return null;
    }

    /** Even width/height for a target height, keeping the source aspect ratio. */
    private static int[] dims(int srcW, int srcH, int h) {
        int w = srcH > 0 ? (int) Math.round(srcW * (double) h / srcH) : srcW;
        if (w % 2 != 0) w--;
        if (h % 2 != 0) h--;
        return new int[] {w, h};
    }

    // ── applying a plan ──────────────────────────────────────────────────

    /**
     * Rewrites a finished FFmpeg command for tone mapping. The builders differ a
     * lot, so this works on the result: it drops {@code -hwaccel_output_format}
     * when the filter needs system-memory frames, puts device setup before
     * {@code -i}, replaces every {@code -vf}/{@code -pix_fmt} the builder chose
     * (scaling is inside the plan's filter) and places the plan's ones right after
     * {@code -c:v}. Commands without {@code -c:v} or {@code -i} are returned as is.
     */
    public static List<String> apply(List<String> command, Plan plan) {
        if (plan == null || command == null) return command;
        int input = command.indexOf("-i");
        int codec = command.indexOf("-c:v");
        if (input < 0 || codec < 0 || codec + 1 >= command.size()) return command;

        List<String> out = new ArrayList<>(command.size() + 8);
        for (int i = 0; i < command.size(); i++) {
            String arg = command.get(i);
            boolean pair = i + 1 < command.size();
            if (pair && (arg.equals("-vf") || arg.equals("-filter:v") || arg.equals("-pix_fmt"))) {
                i++; // drop the builder's choice
                continue;
            }
            if (pair && !plan.framesOnDevice() && arg.equals("-hwaccel_output_format")) {
                i++;
                continue;
            }
            if (i == input) {
                out.addAll(plan.preInputArgs());
            }
            out.add(arg);
            if (i == codec) {
                out.add(command.get(++i)); // encoder name
                out.add("-vf");
                out.add(plan.filter());
                if (plan.softwarePixFmt() != null) {
                    out.add("-pix_fmt");
                    out.add(plan.softwarePixFmt());
                }
            }
        }
        return out;
    }

    /** Plans and applies when the source is HDR and the encoder is 8-bit; otherwise returns the command unchanged. */
    public List<String> toneMapIfNeeded(List<String> command, String sourcePath, String hwDecoder,
                                        String videoEncoder, String resolution, int targetHeight,
                                        boolean realtime) {
        if (!needsToneMap(isHdr(sourcePath), videoEncoder)) return command;
        Plan plan = plan(hwDecoder, videoEncoder, resolution, targetHeight, realtime);
        if (plan == null) {
            LOG.warn("HDR source {} is being encoded to {} without tone mapping: this FFmpeg build has no "
                    + "tone-map filter (needs vpp_qsv, tonemap_vaapi, libplacebo, tonemap_opencl or zscale)",
                    sourcePath, videoEncoder);
            return command;
        }
        LOG.info("Tone-mapping HDR to SDR via {} at {}x{} for {}", plan.method(), plan.width(), plan.height(),
                sourcePath);
        return apply(command, plan);
    }
}
