package Services;

import Models.Video.LiveChannel;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;
import io.quarkus.scheduler.Scheduled;

import java.io.InputStream;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

@ApplicationScoped
public class StreamCheckerService {

    private static final Logger LOG = Logger.getLogger(StreamCheckerService.class);

    private static final Duration CHECK_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration RECHECK_INTERVAL = Duration.ofHours(6);
    /**
     * Cap on probes in flight. The scheduled pass used to fire all 13k probes at once on
     * virtual threads; hosts throttled, the 5s timeout expired, and ~10k channels came back
     * "inconclusive" - which keeps the previous (often null) verdict, so they never got one.
     */
    private static final int MAX_CONCURRENT_PROBES = 32;
    /** Pause before the single retry that keeps a transient failure out of "inconclusive". */
    private static final Duration RETRY_DELAY = Duration.ofSeconds(1);

    @Inject
    StreamCheckExecutor streamCheckExecutor;

    @Inject
    LiveChannelService liveChannelService;

    @Scheduled(cron = "0 0 */6 * * ?")
    void scheduledCheck() {
        LocalDateTime cutoff = LocalDateTime.now().minus(RECHECK_INTERVAL);
        List<LiveChannel> channels = LiveChannel.find(
                "lastChecked IS NULL OR lastChecked < ?1", cutoff
        ).list();

        if (channels.isEmpty()) return;

        LOG.info("Stream checker: checking " + channels.size() + " channels");
        checkChannelsBatched(channels);
    }

    @Transactional
    public void checkAllChannels() {
        List<LiveChannel> channels = LiveChannel.findAll().list();
        if (channels.isEmpty()) {
            LOG.info("Stream checker: no channels found");
            return;
        }

        LOG.info("Stream checker: checking " + channels.size() + " channels");
        checkChannelsBatched(channels);
    }

    private void checkChannelsBatched(List<LiveChannel> channels) {
        if (streamCheckExecutor.isDisabled()) {
            LOG.info("Stream checker: disabled in system settings (streamCheckThreads), skipping "
                + channels.size() + " channels");
            return;
        }
        ExecutorCompletionService<String> completion = new ExecutorCompletionService<>(streamCheckExecutor.getExecutor());
        java.util.concurrent.Semaphore probeSlots = new java.util.concurrent.Semaphore(MAX_CONCURRENT_PROBES);

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(CHECK_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();

        long start = System.currentTimeMillis();

        for (LiveChannel ch : channels) {
            completion.submit(() -> {
                try {
                    probeSlots.acquire();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    LOG.warnf("[StreamChecker] Probe slot wait interrupted; skipping channel %d (%s)", ch.id, ch.name);
                    return "unchecked";
                }
                try {
                    return probeChannel(client, ch);
                } finally {
                    probeSlots.release();
                }
            });
        }

        int working = 0;
        int dead = 0;
        int unchecked = 0;

        for (int i = 0; i < channels.size(); i++) {
            try {
                Future<String> future = completion.take();
                String status = future.get();

                if ("working".equals(status)) working++;
                else if ("dead".equals(status)) dead++;
                else unchecked++;

                if ((i + 1) % 100 == 0) {
                    LOG.info("Stream check: processed " + (i + 1) + " / " + channels.size() + " channels...");
                }
            } catch (Exception e) {
                LOG.warnf("[StreamChecker] Error checking channel: %s", e.getMessage());
                unchecked++;
            }
        }

        long elapsed = (System.currentTimeMillis() - start) / 1000;
        LOG.info("Stream check completed in " + elapsed + "s — " + working + " working, " + dead + " dead, " + unchecked + " unchecked out of " + channels.size());
    }

    /**
     * Probes one channel and records the verdict, retrying once when the first attempt is
     * inconclusive. Only a second inconclusive result keeps the previous verdict; a channel
     * that never had one stays null, which the Xtream API treats as "not dead".
     */
    private String probeChannel(HttpClient client, LiveChannel ch) {
        String status = (ch.streamUrl == null || ch.streamUrl.isBlank()) ? "dead" : checkUrl(client, ch);
        if (status == null) {
            try {
                Thread.sleep(RETRY_DELAY.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                LOG.warnf("[StreamChecker] Retry wait interrupted for channel %d (%s); keeping the previous verdict", ch.id, ch.name);
                liveChannelService.touchLastChecked(ch.id);
                return "unchecked";
            }
            status = checkUrl(client, ch);
        }
        if (status == null) {
            // Still inconclusive (timeout / transient network error): keep the previous
            // verdict. Writing null here used to ERASE the stored status, so a flaky check
            // resurrected dead channels and the Xtream list showed everything again.
            liveChannelService.touchLastChecked(ch.id);
            return "unchecked";
        }
        liveChannelService.updateStreamStatus(ch.id, status);
        return status;
    }

    /**
     * Probes a channel and returns "working", "dead", or null when the result is
     * inconclusive (network-level failure — the caller preserves the previous
     * verdict in that case).
     *
     * HEAD is tried first because it costs nothing, but most IPTV origins answer
     * 405/501 to HEAD while streaming fine — those responses mean "server alive,
     * method unsupported" and must NOT be recorded as dead. They trigger one
     * ranged GET fallback; anything still inconclusive stays null.
     */
    private String checkUrl(HttpClient client, LiveChannel ch) {
        String url = ch.streamUrl == null ? "" : ch.streamUrl.strip();
        if (url.isEmpty()) return "dead";

        String status = probe(client, url, "HEAD", null);
        if ("RETRY".equals(status)) {
            status = probe(client, url, "GET", "bytes=0-255");
        }
        if (status != null && !"RETRY".equals(status)) {
            return status;
        }

        // Inconclusive at the network level. A syntactically invalid URI can never
        // work, so record it as dead instead of leaving it in limbo.
        try {
            URI.create(url);
            return null;
        } catch (IllegalArgumentException e) {
            return "dead";
        }
    }

    /**
     * Single probe. Returns "working", "dead", "RETRY" (server answered but the
     * verdict is inconclusive — try another method), or null (network-level
     * failure / malformed URI is signalled by the caller).
     */
    private String probe(HttpClient client, String url, String method, String rangeHeader) {
        HttpRequest.Builder builder;
        try {
            builder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(CHECK_TIMEOUT)
                    .method(method, HttpRequest.BodyPublishers.noBody());
        } catch (IllegalArgumentException e) {
            return "dead"; // malformed URL — can never work
        }
        if (rangeHeader != null) {
            builder.header("Range", rangeHeader);
        }
        try {
            HttpResponse<InputStream> response =
                    client.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
            // Live streams never reach EOF: read nothing, close immediately.
            try (InputStream body = response.body()) {
                int code = response.statusCode();
                if (code == 200 || code == 206) return "working";
                if (code == 403 || code == 404 || code == 410) return "dead";
                return "RETRY";
            }
        } catch (Exception e) {
            return null; // timeout, connection refused, TLS failure — unknown
        }
    }
}
