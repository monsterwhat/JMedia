package Utils;

/**
 * Shared resolution of tri-state thread-pool settings stored on the Settings
 * entity: -1 = off (worker paused), 0 = auto (hardware-derived), N = manual.
 */
public final class PoolSizeResolver {

    public static final int OFF = -1;
    public static final int AUTO = 0;

    private static final long GB = 1024L * 1024 * 1024;

    private PoolSizeResolver() {}

    public static boolean isOff(Integer configured) {
        return configured != null && configured == OFF;
    }

    /**
     * Resolves a tri-state setting to an effective pool size.
     *
     * @return the effective thread count; 0 means "off"
     */
    public static int resolve(Integer configured, int autoSize) {
        if (configured == null || configured == AUTO) {
            return autoSize;
        }
        if (configured < 0) {
            return 0;
        }
        return configured;
    }

    /**
     * Auto size for TarsosDSP audio analysis pools. Each concurrent analysis
     * buffers full decoded PCM plus FFT spectral maps (hundreds of MB per
     * song), so scaling is gated by JVM heap first and cores second.
     */
    public static int autoAudioAnalysisThreads() {
        Runtime rt = Runtime.getRuntime();
        int cores = rt.availableProcessors();
        long heapGb = rt.maxMemory() / GB;
        int threads = 1;
        if (heapGb >= 4 && cores >= 8) threads = 2;
        if (heapGb >= 8 && cores >= 16) threads = 3;
        return Math.min(threads, 4);
    }

    /** Auto size for I/O-bound scan pools. */
    public static int autoIoThreads(int min, int max) {
        return Math.max(min, Math.min(max, Runtime.getRuntime().availableProcessors() / 2));
    }
}
