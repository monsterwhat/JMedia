package Services;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.TimeUnit;

@ApplicationScoped
public class GpuDetectionService {

    private static final Logger LOG = LoggerFactory.getLogger(GpuDetectionService.class);

    public enum GpuVendor { NVIDIA, INTEL, AMD, UNKNOWN }
    public enum GpuType { DISCRETE, INTEGRATED, UNKNOWN }

    public record GpuInfo(
        GpuVendor vendor,
        GpuType type,
        String name,
        String devicePath,
        int deviceIndex,
        int vramMb,
        boolean hasEncoder,
        boolean hasDecoder,
        String driverVersion
    ) {}

    public record BestGpuSelection(
        Optional<GpuInfo> nvidia,
        Optional<GpuInfo> amd,
        Optional<GpuInfo> intel,
        Optional<GpuInfo> bestOverall
    ) {}

    private BestGpuSelection bestSelection;

    @PostConstruct
    public void init() {
        detectGpus();
    }

    public synchronized BestGpuSelection getBestGpuSelection() {
        return bestSelection;
    }

    public synchronized void refresh() {
        detectGpus();
    }

    private void detectGpus() {
        LOG.info("Starting GPU detection...");
        List<GpuInfo> allGpus = new ArrayList<>();

        // 1. Detect NVIDIA
        allGpus.addAll(detectNvidia());

        // 2. Detect Intel/AMD (VAAPI)
        allGpus.addAll(detectVaapi());

        // 3. Select best
        bestSelection = selectBest(allGpus);
        LOG.info("GPU detection completed. Best GPU: {}", bestSelection.bestOverall().map(GpuInfo::name).orElse("None"));
    }

    private List<GpuInfo> detectNvidia() {
        List<GpuInfo> gpus = new ArrayList<>();
        try {
            // nvidia-smi --query-gpu=index,name,memory.total,driver_version --format=csv,noheader,nounits
            ProcessBuilder pb = new ProcessBuilder("nvidia-smi", "--query-gpu=index,name,memory.total,driver_version", "--format=csv,noheader,nounits");
            Process process = pb.start();
            if (process.waitFor(5, TimeUnit.SECONDS) && process.exitValue() == 0) {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        String[] parts = line.split(",");
                        if (parts.length >= 4) {
                            int index = Integer.parseInt(parts[0].trim());
                            String name = parts[1].trim();
                            int vram = Integer.parseInt(parts[2].trim());
                            String driver = parts[3].trim();

                            // For NVIDIA, we'll assume it's DISCRETE
                            gpus.add(new GpuInfo(
                                GpuVendor.NVIDIA,
                                GpuType.DISCRETE,
                                name,
                                null, // NVIDIA uses index
                                index,
                                vram,
                                true,
                                true,
                                driver
                            ));
                        }
                    }
                }
            }
        } catch (Exception e) {
            LOG.debug("NVIDIA detection failed: {}", e.getMessage());
        }
        return gpus;
    }

    private List<GpuInfo> detectVaapi() {
        List<GpuInfo> gpus = new ArrayList<>();
        // On Linux, we scan /dev/dri/renderD*
        File driDir = new File("/dev/dri");
        if (!driDir.exists() || !driDir.isDirectory()) {
            return gpus;
        }

        File[] renderNodes = driDir.listFiles((dir, name) -> name.startsWith("renderD"));
        if (renderNodes == null) return gpus;

        for (File node : renderNodes) {
            try {
                String devicePath = node.getAbsolutePath();
                String vendorHex = getVendorFromDevice(node.getName());
                GpuVendor gpuVendor = parseVendor(vendorHex);
                
                if (gpuVendor == GpuVendor.UNKNOWN) continue;

                gpus.add(new GpuInfo(
                    gpuVendor,
                    GpuType.INTEGRATED,
                    "VAAPI Device: " + node.getName(),
                    devicePath,
                    -1,
                    0,
                    true,
                    true,
                    "unknown"
                ));
            } catch (Exception e) {
                LOG.warn("Failed to detect VAAPI device {}: {}", node.getName(), e.getMessage());
            }
        }
        return gpus;
    }

    private String getVendorFromDevice(String nodeName) {
        try {
            java.nio.file.Path vendorPath = java.nio.file.Paths.get("/sys/class/drm", nodeName, "device/vendor");
            if (Files.exists(vendorPath)) {
                return Files.readString(vendorPath).trim();
            }
        } catch (IOException e) {
            LOG.debug("Could not read vendor for {}: {}", nodeName, e.getMessage());
        }
        return "";
    }

    private GpuVendor parseVendor(String vendorHex) {
        if (vendorHex.equalsIgnoreCase("0x8086")) return GpuVendor.INTEL;
        if (vendorHex.equalsIgnoreCase("0x1002")) return GpuVendor.AMD;
        return GpuVendor.UNKNOWN;
    }

    private BestGpuSelection selectBest(List<GpuInfo> gpus) {
        Optional<GpuInfo> nvidia = gpus.stream()
            .filter(g -> g.vendor == GpuVendor.NVIDIA)
            .max(Comparator.comparingInt(g -> g.vramMb));

        Optional<GpuInfo> amd = gpus.stream()
            .filter(g -> g.vendor == GpuVendor.AMD)
            .max(Comparator.comparingInt(g -> g.vramMb));

        Optional<GpuInfo> intel = gpus.stream()
            .filter(g -> g.vendor == GpuVendor.INTEL)
            .max(Comparator.comparingInt(g -> g.vramMb));

        Optional<GpuInfo> bestOverall = nvidia.isPresent() ? nvidia : (amd.isPresent() ? amd : intel);

        return new BestGpuSelection(nvidia, amd, intel, bestOverall);
    }
}
