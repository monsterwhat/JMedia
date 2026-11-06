package Services;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression tests for the HLS hardware-frame command decisions.
 *
 * The bug: emitting -pix_fmt nv12 while frames stay on the GPU made FFmpeg
 * insert an auto_scale from qsv to software and fail with code 218.
 */
class HlsServiceCommandTest {

    @Test
    void neverWritesSoftwarePixFmtForHardwareFrames() {
        assertFalse(HlsService.shouldWriteSoftwarePixFmt(false, true),
                "hardware frames must not get a software -pix_fmt");
        assertFalse(HlsService.shouldWriteSoftwarePixFmt(true, true));
    }

    @Test
    void keepsPixFmtForSoftwareFrames() {
        assertTrue(HlsService.shouldWriteSoftwarePixFmt(false, false));
    }

    @Test
    void skipsPixFmtWhenAFormatFilterWasAlreadyAdded() {
        assertFalse(HlsService.shouldWriteSoftwarePixFmt(true, false));
    }

    @Test
    void hardwareFramesAlwaysNeedTheVendorFormatFilter() {
        assertTrue(HlsService.shouldUseVendorFormatFilter(false, true),
                "10-bit P010 must be converted to NV12 even without downscale");
        assertTrue(HlsService.shouldUseVendorFormatFilter(true, true));
    }

    @Test
    void softwareFramesOnlyNeedTheFilterWhenScaling() {
        assertTrue(HlsService.shouldUseVendorFormatFilter(true, false));
        assertFalse(HlsService.shouldUseVendorFormatFilter(false, false));
    }
}
