package Utils;

import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Pure-JDK walker over the ISO-BMFF (MP4) box structure of a fragmented MP4
 * (fMP4) file. Maps the video timeline to byte offsets, probes the file's end
 * time, and computes truncation boundaries. No external libraries, no logging,
 * fully deterministic.
 *
 * <p>Timeline contract: every time-based method ({@link #byteOffsetForTime(Path,double)},
 * {@link #truncateLengthAtTime(Path,double)}, {@link #endTimeSeconds(Path)}) takes
 * seconds relative to the file's own 0-based timeline. Segments are produced with a
 * fresh timeline that starts at 0, so callers must convert video-absolute times by
 * subtracting the segment's startSeconds before probing.
 *
 * <p>Defensive toward growing / partially-written files: any read that hits EOF
 * is treated as "not yet written" — parsing stops at the last complete
 * structure and never throws mid-walk. A short read at any point simply means
 * less data is available than expected.
 */
public final class FragmentedMp4Seeker {

    /** Defensive cap on how much of a single container (moov/moof) payload is read into memory. */
    private static final int MAX_CONTAINER_PAYLOAD = 1 << 26; // 64 MiB

    /** Chunk size for streaming copies (tfdt-shifted concat). */
    private static final int COPY_CHUNK_SIZE = 64 * 1024;

    private FragmentedMp4Seeker() {
    }

    /**
     * Byte offset of the first moof whose tfdt decode time is &gt;= targetSeconds * trackTimescale.
     * Returns null when no such moof exists yet — the target is beyond the file's written end
     * (e.g. a still-growing file). As a pragmatic exception, when the target falls inside the
     * span of the LAST complete fragment (lastTfdt &lt; target &lt; lastTfdt + duration), the
     * last fragment's moof offset is returned instead of null, because that moof is the one the
     * reader needs.
     */
    public static Long byteOffsetForTime(Path file, double targetSeconds) throws IOException {
        try (RandomAccess raf = new RandomAccess(file)) {
            TrackInfo track = readVideoTrack(raf);
            if (track == null) {
                return null;
            }
            long first = firstFragmentOffset(raf);
            if (first < 0) {
                return null;
            }
            double targetTicks = targetSeconds * track.timescale;
            long pos = first;
            long lastMoofOffset = -1;
            long lastEndTicks = -1;
            while (true) {
                Box box = readTopBox(raf, pos);
                if (box == null) {
                    break;
                }
                if ("moof".equals(box.type)) {
                    Fragment frag = readFragment(raf, box, track.trackId);
                    if (frag != null) {
                        lastMoofOffset = pos;
                        lastEndTicks = frag.decodeTime + frag.duration;
                        if ((double) frag.decodeTime >= targetTicks) {
                            return pos;
                        }
                    }
                }
                // mdat and other non-moof top-level boxes are skipped by size.
                if (box.size <= 0) {
                    break;
                }
                pos += box.size;
            }
            // No moof has tfdt >= target. If the target still lies inside the last complete
            // fragment (decode-time wise), the last moof is the one to hand out.
            if (lastMoofOffset >= 0 && (double) lastEndTicks > targetTicks) {
                return lastMoofOffset;
            }
            return null;
        }
    }

    /**
     * Decode time (seconds, in the video track's timescale) of the last fragment plus its
     * duration — i.e. the end of the written timeline. Returns 0.0 when no fragments exist.
     */
    public static double endTimeSeconds(Path file) throws IOException {
        try (RandomAccess raf = new RandomAccess(file)) {
            TrackInfo track = readVideoTrack(raf);
            if (track == null) {
                return 0.0;
            }
            long first = firstFragmentOffset(raf);
            if (first < 0) {
                return 0.0;
            }
            long pos = first;
            long lastEndTicks = -1;
            while (true) {
                Box box = readTopBox(raf, pos);
                if (box == null) {
                    break;
                }
                if ("moof".equals(box.type)) {
                    Fragment frag = readFragment(raf, box, track.trackId);
                    if (frag != null) {
                        lastEndTicks = frag.decodeTime + frag.duration;
                    }
                }
                if (box.size <= 0) {
                    break;
                }
                pos += box.size;
            }
            if (lastEndTicks < 0) {
                return 0.0;
            }
            return lastEndTicks / (double) track.timescale;
        }
    }

    /**
     * Byte length of the file truncated at the first moof whose tfdt decode time is &gt;= target.
     * Keeps [0, that moof's offset) — the header region plus every earlier moof (and their mdat
     * payloads). When no moof qualifies (all fragments start before the target, or the file is
     * unparseable), the whole file length is returned. The result is never less than
     * {@link #firstFragmentOffset(Path)}.
     */
    public static long truncateLengthAtTime(Path file, double targetSeconds) throws IOException {
        try (RandomAccess raf = new RandomAccess(file)) {
            long fileLen = raf.length();
            TrackInfo track = readVideoTrack(raf);
            if (track == null) {
                return fileLen;
            }
            long first = firstFragmentOffset(raf);
            if (first < 0) {
                return fileLen;
            }
            double targetTicks = targetSeconds * track.timescale;
            long pos = first;
            while (true) {
                Box box = readTopBox(raf, pos);
                if (box == null) {
                    break;
                }
                if ("moof".equals(box.type)) {
                    Fragment frag = readFragment(raf, box, track.trackId);
                    if (frag != null && (double) frag.decodeTime >= targetTicks) {
                        return pos; // >= firstFragmentOffset by construction
                    }
                }
                if (box.size <= 0) {
                    break;
                }
                pos += box.size;
            }
            return fileLen;
        }
    }

    /**
     * Length of the file truncated just past the last fully-written top-level box.
     *
     * <p>Used to repair fMP4 cache files produced by transcodes killed mid-write
     * (client disconnect, EPIPE, latest-seek-wins supersede): the trailing
     * moof+mdat is frequently partial and serving it makes Firefox reject the
     * stream ("H264ChangeMonitor: Invalid H264 content") because the sample
     * table points at mdat bytes that aren't on disk. Returns the start offset
     * of the first box whose declared size exceeds available bytes; truncating
     * there leaves a chain of fully-intact fragments. Returns the file length
     * when every box is whole.
     */
    public static long completeLength(Path file) throws IOException {
        try (RandomAccess raf = new RandomAccess(file)) {
            long fileLen = raf.length();
            long pos = 0;
            while (true) {
                Box box = readTopBox(raf, pos);
                if (box == null) {
                    return pos;
                }
                if (box.size > 0 && pos + box.size > fileLen) {
                    return pos;
                }
                if (box.size <= 0) {
                    return fileLen;
                }
                pos += box.size;
            }
        }
    }

    /**
     * Byte offset of the first moof box in the file (end of the ftyp+moov header region).
     * Returns the file size when no moof box is present.
     */
    public static long firstFragmentOffset(Path file) throws IOException {
        try (RandomAccess raf = new RandomAccess(file)) {
            long off = firstFragmentOffset(raf);
            return off < 0 ? raf.length() : off;
        }
    }

    /**
     * Timescale of every trak in the moov, keyed by track ID (empty map when the moov is
     * unparseable). Used when shifting fragment timelines at concat time: each traf's tfdt
     * is expressed in its own track's timescale, so a delta in seconds must be multiplied
     * per track.
     */
    public static Map<Integer, Integer> readTrackTimescales(Path file) throws IOException {
        Map<Integer, Integer> timescales = new HashMap<>();
        try (RandomAccess raf = new RandomAccess(file)) {
            long pos = 0;
            while (true) {
                Box box = readTopBox(raf, pos);
                if (box == null) {
                    break;
                }
                if ("moov".equals(box.type)) {
                    byte[] moov = readPayload(raf, box);
                    int limit = moov.length;
                    int p = 0;
                    while (true) {
                        MemBox trak = memBox(moov, limit, p);
                        if (trak == null) {
                            break;
                        }
                        if ("trak".equals(trak.type)) {
                            Integer trackId = null;
                            Integer timescale = null;
                            int trakEnd = trak.payloadOffset + trak.payloadLen;
                            int q = trak.payloadOffset;
                            while (true) {
                                MemBox sub = memBox(moov, trakEnd, q);
                                if (sub == null) {
                                    break;
                                }
                                if ("tkhd".equals(sub.type)) {
                                    trackId = readTkhdTrackId(moov, sub);
                                } else if ("mdia".equals(sub.type)) {
                                    MediaInfo m = scanMdia(moov, sub);
                                    if (m != null) {
                                        timescale = m.timescale;
                                    }
                                }
                                if (sub.size <= 0) {
                                    break;
                                }
                                q += (int) sub.size;
                            }
                            if (trackId != null && timescale != null) {
                                timescales.put(trackId, timescale);
                            }
                        }
                        if (trak.size <= 0) {
                            break;
                        }
                        p += (int) trak.size;
                    }
                    break;
                }
                if (box.size <= 0) {
                    break;
                }
                pos += box.size;
            }
        }
        return timescales;
    }

    /**
     * Copies {@code length} bytes starting at {@code offset} to {@code out}, adding
     * {@code deltaSeconds} (in each traf's own track timescale) to every tfdt
     * baseMediaDecodeTime. Used when concatenating segments: every segment carries a
     * 0-based relative timeline, so appending raw fragments would restart the merged
     * timeline at 0 for each constituent. Shifting by the absolute start delta makes the
     * merged timeline monotonic. Non-moof boxes are copied verbatim; a version-0 tfdt
     * whose shifted value would overflow 32 bits is left untouched rather than corrupted.
     */
    public static long copyFragmentsShifted(Path file, long offset, long length, double deltaSeconds,
                                            Map<Integer, Integer> trackTimescales,
                                            OutputStream out, long outStartPos) throws IOException {
        if (length <= 0) {
            return 0;
        }
        try (RandomAccess raf = new RandomAccess(file)) {
            long end = offset + length;
            long pos = offset;
            long outPos = outStartPos;
            byte[] chunk = new byte[COPY_CHUNK_SIZE];
            while (pos < end) {
                Box box = readTopBox(raf, pos);
                if (box == null || box.size <= 0 || pos + box.size > end) {
                    copyRaw(raf, out, pos, end - pos, chunk);
                    outPos += end - pos;
                    break;
                }
                if ("moof".equals(box.type)) {
                    writeMoofShifted(raf, box, outPos, deltaSeconds, trackTimescales, out, chunk);
                } else {
                    copyRaw(raf, out, pos, box.size, chunk);
                }
                pos += box.size;
                outPos += box.size;
            }
            return outPos - outStartPos;
        }
    }

    /**
     * Writes a moof (header + payload) to out, adding deltaTicks to every traf's tfdt
     * baseMediaDecodeTime and rebasing each traf's data offsets to the moof's new
     * absolute output position. Per-track delta ticks are derived from the timescale in
     * {@code trackTimescales}. Version-1 (64-bit) tfdt values are always shifted;
     * version-0 values are shifted only when the result fits in 32 bits.
     * <p>Rebasing is required because fragments are byte-appended from independently
     * transcoded segments: any tfhd {@code base_data_offset} (absolute) must point at the
     * moof's new file position, and moofs without one must carry the {@code default-base-
     * is-moof} flag so demuxers (Firefox's MoofParser in particular) treat trun offsets as
     * relative to the moof rather than to a stale absolute base.
     */
    private static void writeMoofShifted(RandomAccess raf, Box moof, long moofOutPos, double deltaSeconds,
                                         Map<Integer, Integer> trackTimescales,
                                         OutputStream out, byte[] chunk) throws IOException {
        byte[] payload = readPayload(raf, moof);
        int limit = payload.length;
        int pos = 0;
        while (true) {
            MemBox box = memBox(payload, limit, pos);
            if (box == null) {
                break;
            }
            if ("traf".equals(box.type)) {
                int trafEnd = box.payloadOffset + box.payloadLen;
                int q = box.payloadOffset;
                Integer trackId = null;
                Integer tfdtVersion = null;
                int tfdtFieldOffset = -1;
                while (true) {
                    MemBox sub = memBox(payload, trafEnd, q);
                    if (sub == null) {
                        break;
                    }
                    if ("tfhd".equals(sub.type)) {
                        TfhdInfo info = readTfhd(payload, sub);
                        if (info != null) {
                            trackId = info.trackId;
                        }
                        int tfhdFlags = (int) (u32(payload, sub.payloadOffset) & 0xFFFFFF);
                        if ((tfhdFlags & 0x01) != 0) {
                            // Absolute base_data_offset present: it pointed at some position
                            // relative to the moof in the source file (typically the mdat).
                            // The merged file preserves that moof-relative geometry, so keep
                            // the delta and move the base to the moof's new absolute position.
                            long origBase = i64(payload, sub.payloadOffset + 8);
                            putI64(payload, sub.payloadOffset + 8, moofOutPos + (origBase - moof.offset));
                        } else {
                            // No explicit base: force default-base-is-moof (0x020000 in the
                            // low 24-bit flags field) so the base is the moof position
                            // wherever the moof lands in the merged file.
                            putU32(payload, sub.payloadOffset, u32(payload, sub.payloadOffset) | 0x00020000L);
                        }
                    } else if ("tfdt".equals(sub.type)) {
                        if (sub.payloadLen >= 8) {
                            tfdtVersion = payload[sub.payloadOffset] & 0xff;
                            tfdtFieldOffset = sub.payloadOffset + 4;
                        }
                    }
                    if (sub.size <= 0) {
                        break;
                    }
                    q += (int) sub.size;
                }
                if (trackId != null && tfdtFieldOffset >= 0 && tfdtVersion != null && deltaSeconds != 0.0) {
                    Integer timescale = trackTimescales.get(trackId);
                    if (timescale != null && timescale > 0) {
                        long deltaTicks = Math.round(deltaSeconds * timescale);
                        if (deltaTicks != 0) {
                            if (tfdtVersion == 1) {
                                putI64(payload, tfdtFieldOffset, i64(payload, tfdtFieldOffset) + deltaTicks);
                            } else {
                                long shifted = u32(payload, tfdtFieldOffset) + deltaTicks;
                                if (shifted <= 0xFFFFFFFFL) {
                                    putU32(payload, tfdtFieldOffset, shifted);
                                }
                            }
                        }
                    }
                }
            }
            if (box.size <= 0) {
                break;
            }
            pos += (int) box.size;
        }
        copyRaw(raf, out, moof.offset, moof.headerLen, chunk);
        out.write(payload, 0, limit);
    }

    /** Copies {@code length} bytes from raf at {@code offset} to out, emitting fewer bytes only on EOF. */
    private static void copyRaw(RandomAccess raf, OutputStream out, long offset, long length, byte[] chunk)
            throws IOException {
        long remaining = length;
        long pos = offset;
        while (remaining > 0) {
            int want = (int) Math.min(chunk.length, remaining);
            byte[] buf = want == chunk.length ? chunk : new byte[want];
            int got = raf.read(pos, buf);
            if (got <= 0) {
                break; // truncated source — emit what exists
            }
            out.write(buf, 0, got);
            pos += got;
            remaining -= got;
        }
    }

    // ── Top-level box walking ──────────────────────────────────────────────

    /** Offset of the first moof box, or -1 when none exists (or the file is too short). */
    private static long firstFragmentOffset(RandomAccess raf) throws IOException {
        long pos = 0;
        while (true) {
            Box box = readTopBox(raf, pos);
            if (box == null) {
                break;
            }
            if ("moof".equals(box.type)) {
                return pos;
            }
            if (box.size <= 0) {
                break;
            }
            pos += box.size;
        }
        return -1;
    }

    /**
     * Finds the timescale + track id of the first VIDEO trak (hdlr handler_type 'vide'),
     * falling back to the first trak with a parseable mdhd when no video trak is found.
     * Returns null when the moov cannot be parsed (e.g. not yet fully written).
     */
    private static TrackInfo readVideoTrack(RandomAccess raf) throws IOException {
        long pos = 0;
        while (true) {
            Box box = readTopBox(raf, pos);
            if (box == null) {
                break;
            }
            if ("moov".equals(box.type)) {
                return scanMoov(readPayload(raf, box));
            }
            if (box.size <= 0) {
                break;
            }
            pos += box.size;
        }
        return null;
    }

    private static TrackInfo scanMoov(byte[] moov) {
        int limit = moov.length;
        int pos = 0;
        TrackInfo first = null;
        TrackInfo video = null;
        while (true) {
            MemBox box = memBox(moov, limit, pos);
            if (box == null) {
                break;
            }
            if ("trak".equals(box.type)) {
                TrackInfo t = scanTrak(moov, box);
                if (t != null) {
                    if (first == null) {
                        first = t;
                    }
                    if (t.video) {
                        video = t;
                    }
                }
            }
            if (box.size <= 0) {
                break;
            }
            pos += (int) box.size;
        }
        return video != null ? video : first;
    }

    private static TrackInfo scanTrak(byte[] trak, MemBox trakBox) {
        int limit = trakBox.payloadOffset + trakBox.payloadLen;
        int pos = trakBox.payloadOffset;
        Integer trackId = null;
        Integer timescale = null;
        boolean video = false;
        while (true) {
            MemBox box = memBox(trak, limit, pos);
            if (box == null) {
                break;
            }
            if ("tkhd".equals(box.type)) {
                Integer id = readTkhdTrackId(trak, box);
                if (id != null) {
                    trackId = id;
                }
            } else if ("mdia".equals(box.type)) {
                MediaInfo m = scanMdia(trak, box);
                if (m != null) {
                    timescale = m.timescale;
                    video = m.video;
                }
            }
            if (box.size <= 0) {
                break;
            }
            pos += (int) box.size;
        }
        if (trackId == null || timescale == null) {
            return null;
        }
        return new TrackInfo(trackId, timescale, video);
    }

    private static MediaInfo scanMdia(byte[] mdia, MemBox mdiaBox) {
        int limit = mdiaBox.payloadOffset + mdiaBox.payloadLen;
        int pos = mdiaBox.payloadOffset;
        Integer timescale = null;
        boolean video = false;
        while (true) {
            MemBox box = memBox(mdia, limit, pos);
            if (box == null) {
                break;
            }
            if ("mdhd".equals(box.type)) {
                Integer ts = readMdhdTimescale(mdia, box);
                if (ts != null) {
                    timescale = ts;
                }
            } else if ("hdlr".equals(box.type)) {
                String handler = readHdlrType(mdia, box);
                if (handler != null) {
                    video = "vide".equals(handler);
                }
            }
            if (box.size <= 0) {
                break;
            }
            pos += (int) box.size;
        }
        if (timescale == null) {
            return null;
        }
        return new MediaInfo(timescale, video);
    }

    // ── Fragment walking ───────────────────────────────────────────────────

    /**
     * Parses one moof into the video traf's tfdt decode time and fragment duration
     * (in track timescale ticks). Returns null when the moof has no parseable video
     * tfdt (incomplete fragment, or no traf for the video track).
     *
     * <p>Duration resolution, in order of preference: tfhd default_sample_duration
     * (multiplied by the fragment's total sample count when known); otherwise the sum
     * of trun per-sample durations; otherwise 0 (unknown, clamps to what exists).
     */
    private static Fragment readFragment(RandomAccess raf, Box moof, int trackId) throws IOException {
        byte[] buf = readPayload(raf, moof);
        int limit = buf.length;
        int pos = 0;
        Long decodeTime = null;
        Long tfhdDefault = null;
        long trunDurationSum = 0L;
        long totalSampleCount = 0L;
        boolean anyTrunDurations = false;
        while (true) {
            MemBox box = memBox(buf, limit, pos);
            if (box == null) {
                break;
            }
            if ("traf".equals(box.type)) {
                int trafEnd = box.payloadOffset + box.payloadLen;
                int q = box.payloadOffset;
                int trafTrackId = -1;
                Long trafDefault = null;
                while (true) {
                    MemBox sub = memBox(buf, trafEnd, q);
                    if (sub == null) {
                        break;
                    }
                    if ("tfhd".equals(sub.type)) {
                        TfhdInfo info = readTfhd(buf, sub);
                        if (info != null) {
                            trafTrackId = info.trackId;
                            trafDefault = info.defaultDuration;
                        }
                        break;
                    }
                    if (sub.size <= 0) {
                        break;
                    }
                    q += (int) sub.size;
                }
                if (trafTrackId == trackId) {
                    if (trafDefault != null) {
                        tfhdDefault = trafDefault;
                    }
                    q = box.payloadOffset;
                    while (true) {
                        MemBox sub = memBox(buf, trafEnd, q);
                        if (sub == null) {
                            break;
                        }
                        if ("tfdt".equals(sub.type)) {
                            Long d = readTfdt(buf, sub);
                            if (d != null && decodeTime == null) {
                                decodeTime = d;
                            }
                        } else if ("trun".equals(sub.type)) {
                            TrunInfo tr = readTrun(buf, sub);
                            if (tr != null) {
                                totalSampleCount += tr.sampleCount;
                                if (tr.hasSampleDurations) {
                                    anyTrunDurations = true;
                                    trunDurationSum += tr.durationSum;
                                }
                            }
                        }
                        if (sub.size <= 0) {
                            break;
                        }
                        q += (int) sub.size;
                    }
                }
            }
            if (box.size <= 0) {
                break;
            }
            pos += (int) box.size;
        }
        if (decodeTime == null) {
            return null;
        }
        long duration;
        if (tfhdDefault != null) {
            duration = totalSampleCount > 0 ? tfhdDefault * totalSampleCount : tfhdDefault;
        } else if (anyTrunDurations) {
            duration = trunDurationSum;
        } else {
            duration = 0L;
        }
        return new Fragment(decodeTime, duration);
    }

    // ── Leaf box readers (in-memory, big-endian) ───────────────────────────

    /** tkhd: version+flags(4) + creation/modification (8 or 16) + track_ID(4). */
    private static Integer readTkhdTrackId(byte[] b, MemBox box) {
        if (box.payloadLen < 4) {
            return null;
        }
        int version = b[box.payloadOffset] & 0xff;
        int need = version == 1 ? 24 : 16;
        if (box.payloadLen < need) {
            return null;
        }
        return (int) u32(b, box.payloadOffset + (version == 1 ? 20 : 12));
    }

    /** mdhd: version+flags(4) + creation/modification (8 or 16) + timescale(4). */
    private static Integer readMdhdTimescale(byte[] b, MemBox box) {
        if (box.payloadLen < 4) {
            return null;
        }
        int version = b[box.payloadOffset] & 0xff;
        int need = version == 1 ? 24 : 16;
        if (box.payloadLen < need) {
            return null;
        }
        return (int) u32(b, box.payloadOffset + (version == 1 ? 20 : 12));
    }

    /** hdlr: version+flags(4) + pre_defined(4) + handler_type(4). */
    private static String readHdlrType(byte[] b, MemBox box) {
        if (box.payloadLen < 12) {
            return null;
        }
        return new String(b, box.payloadOffset + 8, 4, StandardCharsets.ISO_8859_1);
    }

    /**
     * tfhd: version+flags(4) + track_ID(4), then optional fields in fixed order per flags.
     * Only default_sample_duration (flag 0x08) is of interest.
     */
    private static TfhdInfo readTfhd(byte[] b, MemBox box) {
        if (box.payloadLen < 8) {
            return null;
        }
        int flags = (int) (u32(b, box.payloadOffset) & 0xFFFFFF);
        int trackId = (int) u32(b, box.payloadOffset + 4);
        int off = box.payloadOffset + 8;
        int end = box.payloadOffset + box.payloadLen;
        if ((flags & 0x01) != 0) {
            off += 8; // base_data_offset (64-bit)
        }
        if ((flags & 0x02) != 0) {
            off += 4; // sample_description_index
        }
        Long defaultDuration = null;
        if ((flags & 0x08) != 0) {
            if (off + 4 > end) {
                return null;
            }
            defaultDuration = u32(b, off);
        }
        return new TfhdInfo(trackId, defaultDuration);
    }

    /** tfdt: version 0 → unsigned 32-bit; version 1 → signed 64-bit baseMediaDecodeTime. */
    private static Long readTfdt(byte[] b, MemBox box) {
        if (box.payloadLen < 8) {
            return null;
        }
        int version = b[box.payloadOffset] & 0xff;
        if (version == 1) {
            if (box.payloadLen < 12) {
                return null;
            }
            return i64(b, box.payloadOffset + 4);
        }
        return u32(b, box.payloadOffset + 4);
    }

    /**
     * trun: version+flags(4) + sample_count(4), optional data_offset (0x01) and
     * first_sample_flags (0x04), then per-sample fields. sample_duration (0x100),
     * sample_size (0x200), sample_flags (0x400) and composition offset (0x800) are all
     * 32-bit fields in every version (ISO 14496-12). Stops summing early when the
     * payload is truncated (growing file).
     */
    private static TrunInfo readTrun(byte[] b, MemBox box) {
        if (box.payloadLen < 8) {
            return null;
        }
        int flags = (int) (u32(b, box.payloadOffset) & 0xFFFFFF);
        long sampleCount = u32(b, box.payloadOffset + 4);
        int off = box.payloadOffset + 8;
        int end = box.payloadOffset + box.payloadLen;
        if ((flags & 0x01) != 0) {
            off += 4;
        }
        if ((flags & 0x04) != 0) {
            off += 4;
        }
        boolean hasDurations = (flags & 0x100) != 0;
        if (!hasDurations) {
            return new TrunInfo(sampleCount, false, 0L);
        }
        int perSample = 4;
        if ((flags & 0x200) != 0) {
            perSample += 4;
        }
        if ((flags & 0x400) != 0) {
            perSample += 4;
        }
        if ((flags & 0x800) != 0) {
            perSample += 4;
        }
        long sum = 0L;
        long remaining = sampleCount;
        while (remaining > 0) {
            if (off + perSample > end) {
                break; // truncated payload → sum the complete entries
            }
            sum += u32(b, off); // sample_duration is the first per-sample field
            off += perSample;
            remaining--;
        }
        return new TrunInfo(sampleCount, true, sum);
    }

    // ── I/O and box-header primitives ──────────────────────────────────────

    /** RandomAccessFile wrapper whose reads report short reads instead of throwing on EOF. */
    private static final class RandomAccess implements AutoCloseable {
        private final RandomAccessFile raf;

        RandomAccess(Path file) throws IOException {
            this.raf = new RandomAccessFile(file.toFile(), "r");
        }

        @Override
        public void close() throws IOException {
            raf.close();
        }

        long length() throws IOException {
            return raf.length();
        }

        /**
         * Reads up to buf.length bytes at pos. Returns the number of bytes actually read;
         * fewer than requested means end-of-available-data (truncated / growing file).
         */
        int read(long pos, byte[] buf) throws IOException {
            raf.seek(pos);
            int n = 0;
            while (n < buf.length) {
                int got = raf.read(buf, n, buf.length - n);
                if (got < 0) {
                    break;
                }
                n += got;
            }
            return n;
        }
    }

    /**
     * Reads a top-level box header at pos. Handles size==1 (8-byte largesize) and size==0
     * (extends to EOF). Returns null when fewer than 8 bytes are available (not yet written)
     * or the header is malformed.
     */
    private static Box readTopBox(RandomAccess raf, long pos) throws IOException {
        if (pos < 0) {
            return null;
        }
        byte[] h = new byte[8];
        if (raf.read(pos, h) < 8) {
            return null;
        }
        long size = u32(h, 0);
        String type = new String(h, 4, 4, StandardCharsets.ISO_8859_1);
        long headerLen = 8;
        if (size == 1L) {
            byte[] big = new byte[8];
            if (raf.read(pos + 8, big) < 8) {
                return null;
            }
            size = u64(big, 0);
            headerLen = 16;
        } else if (size == 0L) {
            size = raf.length() - pos; // extends to EOF
        }
        if (size < headerLen) {
            return null;
        }
        return new Box(pos, size, headerLen, type);
    }

    /** Reads a box payload; the returned buffer may be short when the file is truncated/growing. */
    private static byte[] readPayload(RandomAccess raf, Box box) throws IOException {
        long len = box.size - box.headerLen;
        if (len <= 0 || len > MAX_CONTAINER_PAYLOAD) {
            return new byte[0]; // empty or absurd size → unreadable
        }
        byte[] buf = new byte[(int) len];
        int got = raf.read(box.offset + box.headerLen, buf);
        if (got < buf.length) {
            byte[] shortBuf = new byte[got];
            System.arraycopy(buf, 0, shortBuf, 0, got);
            return shortBuf;
        }
        return buf;
    }

    /**
     * Parses the box at bufPos within a buffer; boxes that extend past the available data
     * (truncated/growing file) are clamped to what is present. Returns null when the header
     * itself is not fully available.
     */
    private static MemBox memBox(byte[] buf, int limit, int pos) {
        if (pos < 0 || pos + 8 > limit) {
            return null;
        }
        long size = u32(buf, pos);
        String type = new String(buf, pos + 4, 4, StandardCharsets.ISO_8859_1);
        int headerLen = 8;
        if (size == 1L) {
            if (pos + 16 > limit) {
                return null;
            }
            size = u64(buf, pos + 8);
            headerLen = 16;
        } else if (size == 0L) {
            size = limit - pos; // extends to end of available data
        }
        if (size < headerLen) {
            return null;
        }
        long avail = limit - pos;
        if (size > avail) {
            size = avail;
        }
        return new MemBox(size, headerLen, type, pos + headerLen, (int) (size - headerLen));
    }

    // ── Big-endian primitive readers ───────────────────────────────────────

    private static long u32(byte[] b, int off) {
        return ((b[off] & 0xffL) << 24) | ((b[off + 1] & 0xffL) << 16)
                | ((b[off + 2] & 0xffL) << 8) | (b[off + 3] & 0xffL);
    }

    private static long u64(byte[] b, int off) {
        return (u32(b, off) << 32) | u32(b, off + 4);
    }

    private static long i64(byte[] b, int off) {
        long hi = (int) u32(b, off); // sign-extend the high 32 bits
        return (hi << 32) | u32(b, off + 4);
    }

    private static void putU32(byte[] b, int off, long v) {
        b[off] = (byte) (v >>> 24);
        b[off + 1] = (byte) (v >>> 16);
        b[off + 2] = (byte) (v >>> 8);
        b[off + 3] = (byte) v;
    }

    private static void putI64(byte[] b, int off, long v) {
        putU32(b, off, v >>> 32);
        putU32(b, off + 4, v & 0xFFFFFFFFL);
    }

    // ── Small value holders ────────────────────────────────────────────────

    private static final class TrackInfo {
        final int trackId;
        final int timescale;
        final boolean video;

        TrackInfo(int trackId, int timescale, boolean video) {
            this.trackId = trackId;
            this.timescale = timescale;
            this.video = video;
        }
    }

    private static final class MediaInfo {
        final int timescale;
        final boolean video;

        MediaInfo(int timescale, boolean video) {
            this.timescale = timescale;
            this.video = video;
        }
    }

    private static final class Fragment {
        final long decodeTime; // ticks
        final long duration;   // ticks; 0 when unknown

        Fragment(long decodeTime, long duration) {
            this.decodeTime = decodeTime;
            this.duration = duration;
        }
    }

    private static final class TfhdInfo {
        final int trackId;
        final Long defaultDuration;

        TfhdInfo(int trackId, Long defaultDuration) {
            this.trackId = trackId;
            this.defaultDuration = defaultDuration;
        }
    }

    private static final class TrunInfo {
        final long sampleCount;
        final boolean hasSampleDurations;
        final long durationSum;

        TrunInfo(long sampleCount, boolean hasSampleDurations, long durationSum) {
            this.sampleCount = sampleCount;
            this.hasSampleDurations = hasSampleDurations;
            this.durationSum = durationSum;
        }
    }

    private static final class Box {
        final long offset;
        final long size;      // total size incl. header (clamped to EOF for size==0 boxes)
        final long headerLen; // 8 or 16
        final String type;

        Box(long offset, long size, long headerLen, String type) {
            this.offset = offset;
            this.size = size;
            this.headerLen = headerLen;
            this.type = type;
        }
    }

    private static final class MemBox {
        final long size;
        final String type;
        final int payloadOffset;
        final int payloadLen;

        MemBox(long size, int headerLen, String type, int payloadOffset, int payloadLen) {
            this.size = size;
            this.type = type;
            this.payloadOffset = payloadOffset;
            this.payloadLen = payloadLen;
            assert size >= headerLen;
        }
    }
}
