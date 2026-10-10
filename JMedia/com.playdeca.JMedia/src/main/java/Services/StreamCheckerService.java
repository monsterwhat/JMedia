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

    private static final Duration CHECK_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration RECHECK_INTERVAL = Duration.ofHours(6);

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

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(CHECK_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();

        long start = System.currentTimeMillis();

        for (LiveChannel ch : channels) {
            completion.submit(() -> {
                String status;
                if (ch.streamUrl == null || ch.streamUrl.isBlank()) {
                    status = "dead";
                } else {
                    status = checkUrl(client, ch);
                }
                if (status == null) {
                    // Inconclusive probe (timeout / transient network error): keep the
                    // previous verdict. Writing null here used to ERASE the stored
                    // status, so a flaky check resurrected dead channels and the
                    // Xtream list showed everything again.
                    liveChannelService.touchLastChecked(ch.id);
                    return "unchecked";
                }
                liveChannelService.updateStreamStatus(ch.id, status);
                return status;
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
