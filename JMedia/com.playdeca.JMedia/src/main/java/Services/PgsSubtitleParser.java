package Services;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure-Java parser for HDMV PGS (.sup) subtitle streams.
 *
 * <p>PGS is a bitmap-based subtitle format used on Blu-ray discs and in MKV rips
 * (codec {@code hdmv_pgs_subtitle}). It cannot be converted to text without OCR;
 * this parser extracts each subtitle event (display set) as a decoded ARGB bitmap
 * together with its presentation timestamp, so an OCR pass can reconstruct text
 * and timing (WebVTT/SRT).
 *
 * <p>Format reference (packet framing, palette and RLE layout) verified against
 * real-world PGS streams and the open-source pgsrip implementation:
 * <pre>
 *   Packet:  "PG" (2) | PTS (4, 90 kHz clock) | DTS (4) | segment type (1) | size (2) | payload
 *   Segments: 0x14 = PDS (palette), 0x15 = ODS (bitmap), 0x16 = PCS (composition),
 *             0x17 = WDS (window), 0x80 = END
 * </pre>
 * A display set is a PCS...END run that renders one subtitle image.
 */
public final class PgsSubtitleParser {

    private PgsSubtitleParser() {
    }

    /** PGS presentation clock rate (ticks per second). */
    public static final double PTS_CLOCK = 90000.0;

    // Segment types
    private static final int SEG_PDS = 0x14;
    private static final int SEG_ODS = 0x15;
    private static final int SEG_PCS = 0x16;
    private static final int SEG_END = 0x80;

    // ODS sequence flags (bit 6 = first, bit 7 = last in a multi-segment object)
    private static final int SEQ_LAST = 0x40;
    private static final int SEQ_FIRST = 0x80;
    private static final int SEQ_FIRST_AND_LAST = 0xC0;

    /**
     * One subtitle event: the decoded bitmap(s) plus the time they should appear.
     */
    public static final class PgsSubtitle {
        /** PTS of the display set, in 90 kHz ticks. */
        public long startTicks;
        /** Start time in seconds. */
        public double startSeconds;
        /** Decoded subtitle bitmap(s); usually one per event. */
        public List<BufferedImage> images = new ArrayList<>();

        @Override
        public String toString() {
            return String.format("PgsSubtitle[t=%.3fs, images=%d]", startSeconds, images.size());
        }
    }

    /**
     * Parse a complete .sup stream into ordered subtitle events.
     * Empty display sets (no bitmap data) are skipped.
     */
    public static List<PgsSubtitle> parse(byte[] data) {
        List<PgsSubtitle> subtitles = new ArrayList<>();
        int pos = 0;

        long currentStartTicks = 0;
        boolean inDisplaySet = false;
        int[] palette = null;
        // ODS accumulation state: object id -> (width, height, accumulated image bytes)
        Map<Integer, int[]> odsMeta = new HashMap<>();
        Map<Integer, byte[]> odsBuffers = new HashMap<>();

        while (pos + 13 <= data.length) {
            if (data[pos] != 'P' || data[pos + 1] != 'G') {
                break; // not a PGS packet — stop (stream may be truncated)
            }
            long pts = readUint32(data, pos + 2);
            int type = data[pos + 10] & 0xFF;
            int size = ((data[pos + 11] & 0xFF) << 8) | (data[pos + 12] & 0xFF);
            int segStart = pos + 13;
            if (segStart + size > data.length) {
                break; // truncated segment
            }

            switch (type) {
                case SEG_PCS -> {
                    // A new composition starts a new display set.
                    inDisplaySet = true;
                    currentStartTicks = pts;
                    palette = null;
                    odsMeta.clear();
                    odsBuffers.clear();
                }
                case SEG_PDS -> palette = parsePalette(data, segStart, size);
                case SEG_ODS -> {
                    if (inDisplaySet) {
                        accumulateOds(data, segStart, size, odsMeta, odsBuffers);
                    }
                }
                case SEG_END -> {
                    if (inDisplaySet) {
                        BufferedImage image = renderObjects(odsMeta, odsBuffers, palette);
                        if (image != null) {
                            PgsSubtitle sub = new PgsSubtitle();
                            sub.startTicks = currentStartTicks;
                            sub.startSeconds = currentStartTicks / PTS_CLOCK;
                            sub.images.add(image);
                            subtitles.add(sub);
                        }
                        inDisplaySet = false;
                    }
                }
                default -> {
                    // Unknown segment type — ignore.
                }
            }
            pos = segStart + size;
        }
        return subtitles;
    }

    /**
     * Decode one ODS segment into the accumulation state. A subtitle bitmap may
     * span multiple ODS segments (first / continuation / last); we buffer the
     * image bytes per object id until the display set ends.
     */
    private static void accumulateOds(byte[] data, int off, int size,
                                      Map<Integer, int[]> odsMeta, Map<Integer, byte[]> odsBuffers) {
        if (size < 4) {
            return;
        }
        int objectId = ((data[off] & 0xFF) << 8) | (data[off + 1] & 0xFF);
        int sequenceType = data[off + 3] & 0xFF;

        if (sequenceType == SEQ_FIRST_AND_LAST) {
            // Complete image in one segment: id(2) version(1) seq(1) len(3) w(2) h(2) data
            if (size < 11) {
                return;
            }
            int width = ((data[off + 7] & 0xFF) << 8) | (data[off + 8] & 0xFF);
            int height = ((data[off + 9] & 0xFF) << 8) | (data[off + 10] & 0xFF);
            byte[] imageBytes = new byte[size - 11];
            System.arraycopy(data, off + 11, imageBytes, 0, imageBytes.length);
            odsMeta.put(objectId, new int[]{width, height});
            odsBuffers.put(objectId, imageBytes);
        } else if (sequenceType == SEQ_FIRST) {
            // First segment of a split image: carries width/height then data
            if (size < 11) {
                return;
            }
            int width = ((data[off + 7] & 0xFF) << 8) | (data[off + 8] & 0xFF);
            int height = ((data[off + 9] & 0xFF) << 8) | (data[off + 10] & 0xFF);
            byte[] imageBytes = new byte[size - 11];
            System.arraycopy(data, off + 11, imageBytes, 0, imageBytes.length);
            odsMeta.put(objectId, new int[]{width, height});
            odsBuffers.put(objectId, imageBytes);
        } else if (sequenceType == SEQ_LAST) {
            // Last segment of a split image: data only, appended to the buffer
            byte[] tail = new byte[size - 4];
            System.arraycopy(data, off + 4, tail, 0, tail.length);
            byte[] existing = odsBuffers.get(objectId);
            if (existing == null) {
                odsBuffers.put(objectId, tail);
            } else {
                byte[] merged = new byte[existing.length + tail.length];
                System.arraycopy(existing, 0, merged, 0, existing.length);
                System.arraycopy(tail, 0, merged, existing.length, tail.length);
                odsBuffers.put(objectId, merged);
            }
        }
        // Intermediate continuation segments without FIRST/LAST flags are appended too.
        else {
            byte[] tail = new byte[size - 4];
            System.arraycopy(data, off + 4, tail, 0, tail.length);
            byte[] existing = odsBuffers.get(objectId);
            if (existing == null) {
                odsBuffers.put(objectId, tail);
            } else {
                byte[] merged = new byte[existing.length + tail.length];
                System.arraycopy(existing, 0, merged, 0, existing.length);
                System.arraycopy(tail, 0, merged, existing.length, tail.length);
                odsBuffers.put(objectId, merged);
            }
        }
    }

    /**
     * Render all accumulated objects of a display set onto a single ARGB canvas.
     * Returns null when the display set contained no bitmap data (screen clear).
     */
    private static BufferedImage renderObjects(Map<Integer, int[]> odsMeta,
                                               Map<Integer, byte[]> odsBuffers,
                                               int[] palette) {
        if (odsBuffers.isEmpty()) {
            return null;
        }
        int[] firstMeta = odsMeta.values().iterator().next();
        int width = firstMeta[0];
        int height = firstMeta[1];
        if (width <= 0 || height <= 0 || width > 4096 || height > 4096) {
            return null;
        }

        BufferedImage canvas = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        for (Map.Entry<Integer, byte[]> entry : odsBuffers.entrySet()) {
            int[] meta = odsMeta.get(entry.getKey());
            if (meta == null) {
                continue;
            }
            decodeRle(entry.getValue(), meta[0], meta[1], palette,
                    canvas, 0, 0);
        }
        return canvas;
    }

    /**
     * Decode PGS RLE image data into {@code canvas} at ({@code destX}, {@code destY}).
     *
     * <p>RLE encoding (per Blu-ray spec): a byte with the high bit clear is a
     * literal pixel value; a {@code 0x00} byte introduces a run whose length and
     * color follow:
     * <pre>
     *   0x00 next &lt; 0x40       : transparent run of length next
     *   0x00 next &lt; 0x80       : transparent run, length = ((next-0x40)&lt;&lt;8) | third
     *   0x00 next &lt; 0xC0       : colored run, length = next-0x80, color = third
     *   0x00 next &gt;= 0xC0      : colored run, length = ((next-0xC0)&lt;&lt;8) | third, color = fourth
     * </pre>
     */
    static void decodeRle(byte[] rle, int width, int height, int[] palette,
                          BufferedImage canvas, int destX, int destY) {
        int x = 0;
        int y = 0;
        int i = 0;
        int[] argb = new int[]{0x00000000};
        int[] runLength = new int[]{0};

        while (i < rle.length && y < height) {
            decodeRun(rle, i, runLength, argb, palette);
            int length = runLength[0];
            int color = argb[0];
            i += consumedBytes(rle, i, length);

            for (int k = 0; k < length && y < height; k++) {
                int px = destX + x;
                int py = destY + y;
                if (px >= 0 && px < canvas.getWidth() && py >= 0 && py < canvas.getHeight()) {
                    canvas.setRGB(px, py, color);
                }
                x++;
                if (x >= width) {
                    x = 0;
                    y++;
                }
            }
        }
    }

    private static void decodeRun(byte[] rle, int i, int[] runLength, int[] argb, int[] palette) {
        int first = rle[i] & 0xFF;
        int length;
        int colorIndex;
        if (first != 0) {
            // Literal pixel: the byte itself is the palette index.
            runLength[0] = 1;
            argb[0] = paletteIndexToArgb(palette, first);
            return;
        }
        if (i + 1 >= rle.length) {
            runLength[0] = 0;
            argb[0] = 0;
            return;
        }
        int second = rle[i + 1] & 0xFF;
        if (second < 0x40) {
            length = second;
            colorIndex = 0; // transparent
        } else if (second < 0x80) {
            int third = i + 2 < rle.length ? (rle[i + 2] & 0xFF) : 0;
            length = ((second - 0x40) << 8) + third;
            colorIndex = 0; // transparent
        } else if (second < 0xC0) {
            int third = i + 2 < rle.length ? (rle[i + 2] & 0xFF) : 0;
            length = second - 0x80;
            colorIndex = third;
        } else {
            int third = i + 2 < rle.length ? (rle[i + 2] & 0xFF) : 0;
            int fourth = i + 3 < rle.length ? (rle[i + 3] & 0xFF) : 0;
            length = ((second - 0xC0) << 8) + third;
            colorIndex = fourth;
        }
        runLength[0] = length;
        argb[0] = paletteIndexToArgb(palette, colorIndex);
    }

    private static int consumedBytes(byte[] rle, int i, int length) {
        int first = rle[i] & 0xFF;
        if (first != 0) {
            return 1;
        }
        int second = i + 1 < rle.length ? (rle[i + 1] & 0xFF) : 0;
        if (second < 0x40) {
            return 2;
        }
        if (second < 0x80) {
            return 3;
        }
        if (second < 0xC0) {
            return 3;
        }
        return 4;
    }

    private static int paletteIndexToArgb(int[] palette, int index) {
        if (palette != null && index >= 0 && index < palette.length) {
            return palette[index];
        }
        // No palette info: render as opaque white so OCR still sees the glyph.
        return 0xFFFFFFFF;
    }

    /**
     * Parse a PDS segment into a 256-entry ARGB palette.
     * Entry layout (5 bytes): index, Y, Cr, Cb, alpha.
     */
    private static int[] parsePalette(byte[] data, int off, int size) {
        int[] palette = new int[256];
        int entries = (size - 2) / 5;
        for (int e = 0; e < entries; e++) {
            int p = off + 2 + e * 5;
            if (p + 5 > off + size) {
                break;
            }
            int index = data[p] & 0xFF;
            int y = data[p + 1] & 0xFF;
            int cr = data[p + 2] & 0xFF;
            int cb = data[p + 3] & 0xFF;
            int alpha = data[p + 4] & 0xFF;
            palette[index] = yCrCbToArgb(y, cr, cb, alpha);
        }
        return palette;
    }

    /**
     * Convert YCrCb (as stored in PGS palettes) to ARGB, BT.601 full-range.
     */
    static int yCrCbToArgb(int y, int cr, int cb, int alpha) {
        int r = (int) Math.round(y + 1.402 * (cr - 128));
        int g = (int) Math.round(y - 0.344136 * (cb - 128) - 0.714136 * (cr - 128));
        int b = (int) Math.round(y + 1.772 * (cb - 128));
        r = Math.max(0, Math.min(255, r));
        g = Math.max(0, Math.min(255, g));
        b = Math.max(0, Math.min(255, b));
        return (alpha << 24) | (r << 16) | (g << 8) | b;
    }

    private static long readUint32(byte[] data, int off) {
        return ((long) (data[off] & 0xFF) << 24)
                | ((long) (data[off + 1] & 0xFF) << 16)
                | ((long) (data[off + 2] & 0xFF) << 8)
                | (long) (data[off + 3] & 0xFF);
    }
}
