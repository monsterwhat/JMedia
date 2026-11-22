package Services.Platform;

/**
 * Guidance shown when a Windows installer will not download a binary directly.
 * <p>
 * The installers used to fall back to downloading whatever the vendor served
 * under a {@code latest} path (FFmpeg, Tesseract). Those archives are rebuilt
 * continuously, are not published with a digest, and are executed or extracted
 * with administrator rights, so there is nothing stable to pin or verify. The
 * package managers are the reproducible path: they resolve a versioned package
 * and record what was installed.
 * <p>
 * Pure and static so the wording is unit tested.
 */
public final class InstallerGuidance {

    private InstallerGuidance() {
    }

    /**
     * Message for a tool whose direct download has been removed.
     *
     * @param tool             display name, e.g. "FFmpeg"
     * @param wingetCommand    the package-manager command that installs it
     * @param manualUrl        where the project documents manual installation
     */
    public static String directDownloadRefusal(String tool, String wingetCommand, String manualUrl) {
        return "Not downloading " + tool + " directly: the vendor's archives are rebuilt continuously "
                + "and are not published with a checksum, so the download cannot be pinned or verified. "
                + "Install it from a package manager instead:\n"
                + "  " + wingetCommand + "\n"
                + "or use Chocolatey if that is what this machine has. Manual instructions: " + manualUrl + "\n";
    }

    /** Package-manager command recommended for FFmpeg on Windows. */
    public static String ffmpegCommand() {
        return "winget install --id Gyan.FFmpeg -e --accept-source-agreements --accept-package-agreements";
    }

    /** Package-manager command recommended for Tesseract on Windows. */
    public static String tesseractCommand() {
        return "winget install --id UB-Mannheim.TesseractOCR -e --accept-source-agreements --accept-package-agreements";
    }

    /** Where each tool documents manual installation. */
    public static String ffmpegManualUrl() {
        return "https://ffmpeg.org/download.html#build-windows";
    }

    /** Where each tool documents manual installation. */
    public static String tesseractManualUrl() {
        return "https://github.com/UB-Mannheim/tesseract/wiki";
    }
}
