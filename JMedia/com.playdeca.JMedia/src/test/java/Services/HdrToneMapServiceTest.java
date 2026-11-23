package Services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * Tone-map method choice and command rewriting. Filter sets mirror real builds:
 * the gyan "essentials" build (choco install ffmpeg) has vpp_qsv, tonemap_vaapi
 * and zscale but no libplacebo/OpenCL.
 */
class HdrToneMapServiceTest {

    private static final Set<String> ESSENTIALS = Set.of(
            "vpp_qsv", "scale_qsv", "tonemap_vaapi", "scale_vaapi", "scale_cuda", "zscale", "tonemap", "scale");
    private static final Set<String> FULL = Set.of(
            "vpp_qsv", "tonemap_vaapi", "scale_vaapi", "libplacebo", "tonemap_opencl", "zscale", "tonemap");

    // ── when ──

    @Test
    void onlyHdrToH264IsToneMapped() {
        assertTrue(HdrToneMapService.needsToneMap(true, "h264_nvenc"));
        assertTrue(HdrToneMapService.needsToneMap(true, "libx264"));
        assertTrue(HdrToneMapService.needsToneMap(true, "h264_qsv"));
        assertFalse(HdrToneMapService.needsToneMap(true, "hevc_nvenc"), "HEVC keeps HDR");
        assertFalse(HdrToneMapService.needsToneMap(true, "copy"));
        assertFalse(HdrToneMapService.needsToneMap(false, "h264_nvenc"));
    }

    @Test
    void recognisesPqAndHlg() {
        assertTrue(HdrToneMapService.isHdrTransfer("smpte2084"));
        assertTrue(HdrToneMapService.isHdrTransfer("arib-std-b67"));
        assertFalse(HdrToneMapService.isHdrTransfer("bt709"));
        assertFalse(HdrToneMapService.isHdrTransfer(null));
    }

    // ── which method ──

    @Test
    void intelArcUsesVppQsvOnTheGpu() {
        HdrToneMapService.Plan p = HdrToneMapService.plan(ESSENTIALS, "hevc_qsv", "h264_qsv", "3840x2160", 0, true);
        assertEquals(HdrToneMapService.Method.QSV, p.method());
        assertTrue(p.framesOnDevice());
        assertEquals("vpp_qsv=w=3840:h=2160:format=nv12:tonemap=1", p.filter());
        assertNull(p.softwarePixFmt());
    }

    @Test
    void intelArcScalesInTheSameFilter() {
        HdrToneMapService.Plan p = HdrToneMapService.plan(ESSENTIALS, "hevc_qsv", "h264_qsv", "3840x2160", 720, true);
        assertEquals("vpp_qsv=w=1280:h=720:format=nv12:tonemap=1", p.filter());
    }

    @Test
    void vaapiTonemapsThenScalesOnTheGpu() {
        HdrToneMapService.Plan p = HdrToneMapService.plan(ESSENTIALS, "hevc_vaapi", "h264_vaapi", "3840x2160", 1080, true);
        assertEquals(HdrToneMapService.Method.VAAPI, p.method());
        assertEquals("tonemap_vaapi=format=nv12:t=bt709:m=bt709:p=bt709,scale_vaapi=w=1920:h=1080", p.filter());
    }

    @Test
    void nvidiaOnEssentialsFallsBackToCpuCappedAt1080ForStreaming() {
        HdrToneMapService.Plan p = HdrToneMapService.plan(ESSENTIALS, "hevc_cuvid", "h264_nvenc", "3840x2160", 0, true);
        assertEquals(HdrToneMapService.Method.CPU, p.method());
        assertFalse(p.framesOnDevice());
        assertEquals(1080, p.height());
        assertTrue(p.filter().startsWith("scale=1920:1080,zscale=t=linear"));
        assertEquals("nv12", p.softwarePixFmt());
    }

    @Test
    void cpuConversionKeepsFullResolution() {
        HdrToneMapService.Plan p = HdrToneMapService.plan(ESSENTIALS, null, "libx264", "3840x2160", 0, false);
        assertEquals(2160, p.height());
        assertEquals(HdrToneMapService.CPU_TONE_MAP, p.filter());
        assertEquals("yuv420p", p.softwarePixFmt());
    }

    @Test
    void nvidiaPrefersLibplaceboWhenTheBuildHasIt() {
        HdrToneMapService.Plan p = HdrToneMapService.plan(FULL, "hevc_cuvid", "h264_nvenc", "3840x2160", 0, true);
        assertEquals(HdrToneMapService.Method.LIBPLACEBO, p.method());
        assertEquals(2160, p.height(), "GPU tone mapping is not capped");
    }

    @Test
    void openClWhenThereIsNoLibplacebo() {
        HdrToneMapService.Plan p = HdrToneMapService.plan(Set.of("tonemap_opencl", "zscale", "tonemap"),
                "hevc_cuvid", "h264_nvenc", "3840x2160", 0, true);
        assertEquals(HdrToneMapService.Method.OPENCL, p.method());
        assertEquals(List.of("-init_hw_device", "opencl=ocl", "-filter_hw_device", "ocl"), p.preInputArgs());
    }

    @Test
    void noMethodWithoutAnyToneMapFilter() {
        assertNull(HdrToneMapService.plan(Set.of("scale"), "hevc_cuvid", "h264_nvenc", "3840x2160", 0, true));
    }

    // ── rewriting ──

    @Test
    void cpuPlanReplacesGpuFilterAndBringsFramesToMemory() {
        List<String> built = List.of("ffmpeg", "-hwaccel", "cuda", "-hwaccel_output_format", "cuda",
                "-i", "in.mkv", "-map", "0:v:0", "-c:v", "h264_nvenc", "-preset", "fast",
                "-vf", "scale_cuda=1920:1080:format=nv12", "-f", "mp4", "pipe:1");
        HdrToneMapService.Plan p = HdrToneMapService.plan(ESSENTIALS, "hevc_cuvid", "h264_nvenc", "3840x2160", 1080, true);
        List<String> out = HdrToneMapService.apply(built, p);

        assertFalse(out.contains("-hwaccel_output_format"));
        assertFalse(out.contains("scale_cuda=1920:1080:format=nv12"));
        assertEquals(1, out.stream().filter("-vf"::equals).count());
        int c = out.indexOf("-c:v");
        assertEquals(List.of("-c:v", "h264_nvenc", "-vf", p.filter(), "-pix_fmt", "nv12"), out.subList(c, c + 6));
        assertEquals("pipe:1", out.get(out.size() - 1));
    }

    @Test
    void qsvPlanKeepsFramesOnTheGpu() {
        List<String> built = List.of("ffmpeg", "-hwaccel", "qsv", "-hwaccel_output_format", "qsv",
                "-i", "in.mkv", "-c:v", "h264_qsv", "-pix_fmt", "nv12", "out.mp4");
        HdrToneMapService.Plan p = HdrToneMapService.plan(ESSENTIALS, "hevc_qsv", "h264_qsv", "1920x1080", 0, true);
        List<String> out = HdrToneMapService.apply(built, p);

        assertTrue(out.contains("-hwaccel_output_format"));
        assertFalse(out.contains("-pix_fmt"), "no software pixel format on QSV frames");
        assertEquals(List.of("-c:v", "h264_qsv", "-vf", p.filter()), out.subList(out.indexOf("-c:v"), out.indexOf("-c:v") + 4));
    }

    @Test
    void deviceSetupGoesBeforeTheInput() {
        List<String> built = List.of("ffmpeg", "-i", "in.mkv", "-c:v", "libx264", "out.mp4");
        HdrToneMapService.Plan p = HdrToneMapService.plan(Set.of("tonemap_opencl"), null, "libx264", "1920x1080", 0, true);
        List<String> out = HdrToneMapService.apply(built, p);
        assertEquals(List.of("ffmpeg", "-init_hw_device", "opencl=ocl", "-filter_hw_device", "ocl", "-i"),
                out.subList(0, 6));
    }
}
