package Services.Platform;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Wording of the refusal shown instead of a mutable direct download.
 */
class InstallerGuidanceTest {

    @Test
    void refusalNamesTheToolTheReasonAndTheAlternative() {
        String message = InstallerGuidance.directDownloadRefusal(
                "FFmpeg", InstallerGuidance.ffmpegCommand(), InstallerGuidance.ffmpegManualUrl());

        assertTrue(message.contains("FFmpeg"));
        assertTrue(message.contains("checksum"));
        assertTrue(message.contains(InstallerGuidance.ffmpegCommand()));
        assertTrue(message.contains(InstallerGuidance.ffmpegManualUrl()));
    }

    @Test
    void tesseractRefusalCarriesItsOwnCommand() {
        String message = InstallerGuidance.directDownloadRefusal(
                "Tesseract", InstallerGuidance.tesseractCommand(), InstallerGuidance.tesseractManualUrl());

        assertTrue(message.contains("Tesseract"));
        assertTrue(message.contains(InstallerGuidance.tesseractCommand()));
        assertTrue(message.contains(InstallerGuidance.tesseractManualUrl()));
    }

    @Test
    void commandsArePinnedToAPackageIdAndAcceptAgreements() {
        for (String command : new String[]{InstallerGuidance.ffmpegCommand(), InstallerGuidance.tesseractCommand()}) {
            assertTrue(command.startsWith("winget install --id "), command);
            assertTrue(command.contains(" --accept-package-agreements"), command);
        }
    }

    @Test
    void noGuidanceOffersALatestDownload() {
        String message = InstallerGuidance.directDownloadRefusal(
                "FFmpeg", InstallerGuidance.ffmpegCommand(), InstallerGuidance.ffmpegManualUrl());

        assertFalse(message.contains("/latest/"));
        assertFalse(message.contains("Invoke-WebRequest"));
    }
}
