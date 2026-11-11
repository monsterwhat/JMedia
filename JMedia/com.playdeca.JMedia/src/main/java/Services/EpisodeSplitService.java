package Services;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Splits a file that holds two episodes into two files, stream-copy only.
 *
 * <p>Detection is delegated to {@link EpisodeSplitPlanner}: black frames, audio
 * silence and the midpoint prior together identify the break. The cut is then
 * snapped to the keyframe before that boundary, so nothing is re-encoded and the
 * join sits inside the black run instead of clipping an episode's opening.
 *
 * <p>Nothing is deleted: the source is archived under
 * {@code ~/.jmedia/split-originals/<timestamp>/} so a bad cut is recoverable.
 */
@ApplicationScoped
public class EpisodeSplitService {

    private static final Logger LOG = LoggerFactory.getLogger(EpisodeSplitService.class);

    private static final Pattern BLACK = Pattern.compile(
            "black_start:\\s*([0-9.]+)\\s+black_end:\\s*([0-9.]+)");
    private static final Pattern SILENCE_START = Pattern.compile("silence_start:\\s*(-?[0-9.]+)");
    private static final Pattern SILENCE_END = Pattern.compile("silence_end:\\s*(-?[0-9.]+)");

    private static final double DURATION_TOLERANCE_SECONDS = 3.0;
    private static final double MIN_PART_SECONDS = 30.0;
    private static final long DETECT_TIMEOUT_SECONDS = 900;
    private static final long SPLIT_TIMEOUT_SECONDS = 3600;

    @Inject
    FFmpegDiscoveryService ffmpegDiscovery;

    /** What the analyzer found. {@code primary} is null when the file is not splittable. */
    public record SplitPlan(Path source, String filename, int firstEpisode, int lastEpisode,
                            double durationSeconds, EpisodeSplitPlanner.Candidate primary,
                            double cutSeconds, double secondCutSeconds, String reason) {
        public boolean isSplittable() {
            return primary != null;
        }

        /** True when a preview sits between the two episodes and must come out. */
        public boolean isThreeWay() {
            return secondCutSeconds > 0 && secondCutSeconds > cutSeconds + 5;
        }
    }

    /** Result of an applied split. {@code promo} is set only for a three-way cut. */
    public record SplitOutcome(boolean ok, String message, Path partOne, Path partTwo,
                               Path archivedOriginal, Path promo,
                               double partOneSeconds, double partTwoSeconds, double promoSec) {
    }

    // ── analysis ────────────────────────────────────────────────────────────

    public SplitPlan analyze(Path file) {
        String filename = file.getFileName() == null ? "" : file.getFileName().toString();
        double duration = probeDuration(file);
        if (duration <= 0) {
            LOG.warn("Split analysis: could not read a duration for {}", file);
            return new SplitPlan(file, filename, 0, 0, 0, null, 0, 0, "could not read the file duration");
        }
        EpisodeSplitPlanner.EpisodeRange range = EpisodeSplitPlanner.episodeRange(filename);
        if (range == null) {
            return new SplitPlan(file, filename, 0, 0, duration, null, 0, 0,
                    "the filename does not declare an episode range");
        }
        if (duration < MIN_PART_SECONDS * 2) {
            return new SplitPlan(file, filename, range.first(), range.last(), duration, null, 0, 0,
                    "the file is too short to hold two episodes");
        }

        double windowStart = duration * EpisodeSplitPlanner.WINDOW_START_FRACTION;
        double windowLength = duration * (EpisodeSplitPlanner.WINDOW_END_FRACTION
                - EpisodeSplitPlanner.WINDOW_START_FRACTION);
        List<EpisodeSplitPlanner.TimeRange> blacks = detectBlacks(file, windowStart, windowLength);
        List<EpisodeSplitPlanner.TimeRange> silences = detectSilences(file, windowStart, windowLength);

        EpisodeSplitPlanner.Candidate candidate =
                EpisodeSplitPlanner.chooseCandidate(blacks, silences, duration);
        if (candidate == null) {
            LOG.info("Split analysis: no black+silence break near the midpoint of {}", file);
            return new SplitPlan(file, filename, range.first(), range.last(), duration, null, 0, 0,
                    "no fade-to-black with a matching audio gap near the midpoint");
        }

        List<Double> keyframes = detectKeyframes(file, windowStart, windowLength);
        double cut = EpisodeSplitPlanner.snapToKeyframe(candidate.boundarySeconds(), keyframes);

        // Some releases put a next-episode preview between the two episodes: a
        // second black+silence break follows shortly after the first. Cutting at
        // either fade alone would bury the preview inside an episode.
        EpisodeSplitPlanner.Candidate trailing =
                EpisodeSplitPlanner.chooseTrailingCandidate(blacks, silences, duration, cut);
        double secondCut = 0;
        if (trailing != null) {
            secondCut = EpisodeSplitPlanner.snapToNearestKeyframe(trailing.boundarySeconds(), keyframes);
            LOG.info("Split analysis: {} -> preview {}s..{}s ({}s) between episodes",
                    file, format(cut), format(secondCut), format(secondCut - cut));
        }

        LOG.info("Split analysis: {} -> cut {}s (black {}s, silence {}s, {}s from midpoint)",
                file, format(cut), format(candidate.blackSeconds()),
                format(candidate.silenceSeconds()), format(candidate.midpointDistanceSeconds()));
        String reason = "black frame with audio gap, snapped to keyframe " + String.format("%.2f", cut);
        if (secondCut > 0) {
            reason += "; preview " + String.format("%.2f", cut) + ".."
                    + String.format("%.2f", secondCut) + " removed";
        }
        return new SplitPlan(file, filename, range.first(), range.last(), duration, candidate,
                cut, secondCut, reason);
    }

    // ── apply ───────────────────────────────────────────────────────────────

    public SplitOutcome apply(SplitPlan plan, boolean archiveOriginal) {
        if (plan == null || !plan.isSplittable()) {
            return new SplitOutcome(false, "nothing to split", null, null, null, null, 0, 0, 0);
        }
        EpisodeSplitPlanner.EpisodeRange range =
                new EpisodeSplitPlanner.EpisodeRange(plan.firstEpisode(), plan.lastEpisode());
        String[] names = EpisodeSplitPlanner.partNames(plan.filename(), range);
        if (names == null) {
            return new SplitOutcome(false, "could not build output names", null, null, null, null, 0, 0, 0);
        }

        Path directory = plan.source().getParent();
        Path partOne = directory.resolve(names[0]);
        Path partTwo = directory.resolve(names[1]);
        if (Files.exists(partOne) || Files.exists(partTwo)) {
            LOG.warn("Split refused for {}: output already exists ({} / {})",
                    plan.source(), partOne.getFileName(), partTwo.getFileName());
            return new SplitOutcome(false, "an output file already exists", partOne, partTwo,
                    null, null, 0, 0, 0);
        }

        boolean threeWay = plan.isThreeWay();
        Path tempOne = directory.resolve(names[0] + ".split.tmp");
        Path tempTwo = directory.resolve(names[1] + ".split.tmp");
        Path tempPromo = threeWay ? directory.resolve(names[0] + ".promo.tmp") : null;
        List<Path> temps = threeWay ? List.of(tempOne, tempTwo, tempPromo)
                : List.of(tempOne, tempTwo);
        try {
            double cut = plan.cutSeconds();
            double secondCut = threeWay ? plan.secondCutSeconds() : cut;
            String ffmpeg = ffmpegDiscovery.findFFmpegExecutable();

            ExecResult first = run(List.of(ffmpeg, "-hide_banner", "-nostats", "-v", "error",
                    "-i", plan.source().toString(), "-map", "0", "-c", "copy",
                    "-t", format(cut), "-avoid_negative_ts", "make_zero",
                    "-movflags", "+faststart", "-y", tempOne.toString()), SPLIT_TIMEOUT_SECONDS);
            if (first.exitCode() != 0) {
                return fail(plan, temps, "first part failed: " + first.tail());
            }

            if (threeWay) {
                ExecResult preview = run(List.of(ffmpeg, "-hide_banner", "-nostats", "-v", "error",
                        "-ss", format(cut), "-t", format(secondCut - cut),
                        "-i", plan.source().toString(), "-map", "0", "-c", "copy",
                        "-avoid_negative_ts", "make_zero",
                        "-movflags", "+faststart", "-y", tempPromo.toString()), SPLIT_TIMEOUT_SECONDS);
                if (preview.exitCode() != 0) {
                    return fail(plan, temps, "preview extraction failed: " + preview.tail());
                }
            }

            ExecResult second = run(List.of(ffmpeg, "-hide_banner", "-nostats", "-v", "error",
                    "-ss", format(secondCut), "-i", plan.source().toString(), "-map", "0", "-c", "copy",
                    "-avoid_negative_ts", "make_zero",
                    "-movflags", "+faststart", "-y", tempTwo.toString()), SPLIT_TIMEOUT_SECONDS);
            if (second.exitCode() != 0) {
                return fail(plan, temps, "second part failed: " + second.tail());
            }

            double firstSeconds = probeDuration(tempOne);
            double secondSeconds = probeDuration(tempTwo);
            double promoSec = threeWay ? probeDuration(tempPromo) : 0;
            String problem = threeWay
                    ? verifyThreeWay(firstSeconds, secondSeconds, promoSec, plan.durationSeconds())
                    : verify(cut, firstSeconds, secondSeconds, plan.durationSeconds() - cut);
            if (problem != null) {
                return fail(plan, temps, problem);
            }

            Files.move(tempOne, partOne, StandardCopyOption.REPLACE_EXISTING);
            Files.move(tempTwo, partTwo, StandardCopyOption.REPLACE_EXISTING);

            Path archived = archiveOriginal ? archive(plan.source()) : null;
            Path promo = threeWay ? archivePromo(tempPromo, plan.filename()) : null;

            LOG.info("Split complete: {} -> {} ({}s) + {} ({}s){} cut at {}s",
                    plan.source().getFileName(), partOne.getFileName(), format(firstSeconds),
                    partTwo.getFileName(), format(secondSeconds),
                    threeWay ? ", preview " + format(promoSec) + "s removed," : "",
                    format(cut));
            String message = threeWay
                    ? "split at " + format(cut) + "s with preview " + format(promoSec) + "s removed"
                    : "split at " + format(cut) + "s";
            return new SplitOutcome(true, message, partOne, partTwo, archived, promo,
                    firstSeconds, secondSeconds, promoSec);
        } catch (IOException e) {
            LOG.error("Split failed for {}: {}", plan.source(), e.getMessage(), e);
            return fail(plan, temps, e.getMessage());
        }
    }

    private SplitOutcome fail(SplitPlan plan, List<Path> temps, String message) {
        LOG.warn("Split aborted for {}: {}", plan.source(), message);
        for (Path temp : temps) {
            deleteQuietly(temp);
        }
        return new SplitOutcome(false, message, null, null, null, null, 0, 0, 0);
    }

    /** The three pieces must tile the source: no overlap, no missing seconds. */
    private String verifyThreeWay(double first, double second, double promo, double total) {
        if (first < MIN_PART_SECONDS || second < MIN_PART_SECONDS) {
            return "a part is shorter than " + (int) MIN_PART_SECONDS + "s";
        }
        if (promo < 10) {
            return "the preview segment is shorter than 10s";
        }
        double drift = Math.abs(first + second + promo - total);
        if (drift > DURATION_TOLERANCE_SECONDS) {
            return "parts do not tile the source (drift " + format(drift) + "s)";
        }
        return null;
    }

    private String verify(double cut, double firstSeconds, double secondSeconds, double expectedSecond) {
        if (firstSeconds < MIN_PART_SECONDS || secondSeconds < MIN_PART_SECONDS) {
            return "a part is shorter than " + (int) MIN_PART_SECONDS + "s";
        }
        if (Math.abs(firstSeconds - cut) > DURATION_TOLERANCE_SECONDS) {
            return "first part is " + String.format("%.2f", firstSeconds)
                    + "s but the cut was at " + String.format("%.2f", cut) + "s";
        }
        if (Math.abs(secondSeconds - expectedSecond) > DURATION_TOLERANCE_SECONDS) {
            return "second part is " + String.format("%.2f", secondSeconds)
                    + "s but " + String.format("%.2f", expectedSecond) + "s was expected";
        }
        return null;
    }

    /** One timestamped folder per run, shared by the original and the preview. */
    private Path archiveDirectory() throws IOException {
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        Path dir = Path.of(System.getProperty("user.home"), ".jmedia", "split-originals", stamp);
        Files.createDirectories(dir);
        return dir;
    }

    private Path archive(Path source) throws IOException {
        Path target = archiveDirectory().resolve(source.getFileName().toString());
        Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        LOG.info("Archived original {} -> {}", source, target);
        return target;
    }

    private Path archivePromo(Path temp, String filename) throws IOException {
        String base = filename.replaceFirst("\\.[^.]+$", "");
        Path target = archiveDirectory().resolve(base + " - preview.mp4");
        Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        LOG.info("Archived preview segment {}", target);
        return target;
    }

    // ── ffmpeg plumbing ─────────────────────────────────────────────────────

    private List<EpisodeSplitPlanner.TimeRange> detectBlacks(Path file, double start, double length) {
        String output = run(List.of(ffmpegDiscovery.findFFmpegExecutable(),
                "-hide_banner", "-nostats", "-loglevel", "info",
                "-ss", format(start), "-t", format(length), "-i", file.toString(),
                "-vf", "blackdetect=d=0.35:pix_th=0.12", "-an", "-f", "null", "-"),
                DETECT_TIMEOUT_SECONDS).output();
        List<EpisodeSplitPlanner.TimeRange> ranges = new ArrayList<>();
        Matcher m = BLACK.matcher(output);
        while (m.find()) {
            ranges.add(new EpisodeSplitPlanner.TimeRange(
                    Double.parseDouble(m.group(1)) + start,
                    Double.parseDouble(m.group(2)) + start));
        }
        return ranges;
    }

    private List<EpisodeSplitPlanner.TimeRange> detectSilences(Path file, double start, double length) {
        String output = run(List.of(ffmpegDiscovery.findFFmpegExecutable(),
                "-hide_banner", "-nostats", "-loglevel", "info",
                "-ss", format(start), "-t", format(length), "-i", file.toString(),
                "-af", "silencedetect=n=-40dB:d=0.25", "-vn", "-f", "null", "-"),
                DETECT_TIMEOUT_SECONDS).output();
        List<EpisodeSplitPlanner.TimeRange> ranges = new ArrayList<>();
        double open = -1;
        for (String line : output.split("\\R")) {
            Matcher begin = SILENCE_START.matcher(line);
            if (begin.find()) {
                open = Double.parseDouble(begin.group(1)) + start;
                continue;
            }
            Matcher end = SILENCE_END.matcher(line);
            if (end.find() && open >= 0) {
                ranges.add(new EpisodeSplitPlanner.TimeRange(
                        open, Double.parseDouble(end.group(1)) + start));
                open = -1;
            }
        }
        if (open >= 0) {
            ranges.add(new EpisodeSplitPlanner.TimeRange(open, start + length));
        }
        return ranges;
    }

    private List<Double> detectKeyframes(Path file, double start, double length) {
        String from = String.valueOf((long) Math.floor(start));
        String span = String.valueOf((long) Math.ceil(length) + 2);
        String output = run(List.of(ffmpegDiscovery.findFFprobeExecutable(),
                "-v", "error", "-read_intervals", from + "%+" + span,
                "-select_streams", "v:0",
                "-show_entries", "packet=pts_time,flags", "-of", "csv=p=0",
                file.toString()), DETECT_TIMEOUT_SECONDS).output();
        List<Double> keyframes = new ArrayList<>();
        for (String line : output.split("\\R")) {
            String[] parts = line.split(",");
            if (parts.length < 2 || !parts[1].contains("K")) continue;
            try {
                keyframes.add(Double.parseDouble(parts[0]));
            } catch (NumberFormatException e) {
                LOG.debug("Ignoring unparsable keyframe row '{}'", line);
            }
        }
        return keyframes;
    }

    private double probeDuration(Path file) {
        String output = run(List.of(ffmpegDiscovery.findFFprobeExecutable(),
                "-v", "error", "-show_entries", "format=duration",
                "-of", "default=nw=1:nk=1", file.toString()), DETECT_TIMEOUT_SECONDS).output();
        try {
            return Double.parseDouble(output.trim());
        } catch (NumberFormatException e) {
            LOG.warn("Could not read duration of {}: '{}'", file, output.trim());
            return 0;
        }
    }

    private record ExecResult(int exitCode, String output) {
        String tail() {
            String trimmed = output == null ? "" : output.trim();
            return trimmed.length() <= 300 ? trimmed : trimmed.substring(trimmed.length() - 300);
        }
    }

    /**
     * Runs a short-lived process with its output captured to a temp file. ffmpeg
     * writes its detection markers to stderr, so the streams are merged.
     */
    private ExecResult run(List<String> command, long timeoutSeconds) {
        Path capture = null;
        try {
            capture = Files.createTempFile("jmedia-split-", ".log");
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.redirectErrorStream(true);
            builder.redirectOutput(capture.toFile());
            Process process = builder.start();
            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                LOG.warn("Split command timed out after {}s: {}", timeoutSeconds, command.get(0));
            }
            String output = Files.readString(capture, StandardCharsets.UTF_8);
            return new ExecResult(finished ? process.exitValue() : -1, output);
        } catch (IOException e) {
            LOG.error("Split command failed to start ({}): {}", command.get(0), e.getMessage(), e);
            return new ExecResult(-1, e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.error("Split command interrupted ({}): {}", command.get(0), e.getMessage(), e);
            return new ExecResult(-1, e.getMessage());
        } finally {
            deleteQuietly(capture);
        }
    }

    private void deleteQuietly(Path path) {
        if (path == null) return;
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            LOG.warn("Could not remove temp file {}: {}", path, e.getMessage());
        }
    }

    private static String format(double seconds) {
        return String.format(java.util.Locale.ROOT, "%.3f", seconds);
    }
}
