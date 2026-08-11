package Services;

import API.WS.LogSocket;
import Models.Video.LiveChannel;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;
import io.quarkus.scheduler.Scheduled;

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

    @Inject
    LogSocket logSocket;

    @Scheduled(cron = "0 0 */6 * * ?")
    void scheduledCheck() {
        LocalDateTime cutoff = LocalDateTime.now().minus(RECHECK_INTERVAL);
        List<LiveChannel> channels = LiveChannel.find(
                "lastChecked IS NULL OR lastChecked < ?1", cutoff
        ).list();

        if (channels.isEmpty()) return;

        addLog("Stream checker: checking " + channels.size() + " channels");
        checkChannelsBatched(channels);
    }

    @Transactional
    public void checkAllChannels() {
        List<LiveChannel> channels = LiveChannel.findAll().list();
        if (channels.isEmpty()) {
            addLog("Stream checker: no channels found");
            return;
        }

        addLog("Stream checker: checking " + channels.size() + " channels");
        checkChannelsBatched(channels);
    }

    private void checkChannelsBatched(List<LiveChannel> channels) {
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
                    status = checkUrl(client, ch.streamUrl);
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
                    addLog("Stream check: processed " + (i + 1) + " / " + channels.size() + " channels...");
                }
            } catch (Exception e) {
                LOG.warnf("[StreamChecker] Error checking channel: %s", e.getMessage());
                unchecked++;
            }
        }

        long elapsed = (System.currentTimeMillis() - start) / 1000;
        addLog("Stream check completed in " + elapsed + "s — " + working + " working, " + dead + " dead, " + unchecked + " unchecked out of " + channels.size());
    }

    private String checkUrl(HttpClient client, String url) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(CHECK_TIMEOUT)
                    .method("HEAD", HttpRequest.BodyPublishers.noBody())
                    .build();

            HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());

            if (response.statusCode() == 200) {
                return "working";
            }

            if (response.statusCode() == 403 || response.statusCode() == 404) {
                return "dead";
            }
            return "dead";
        } catch (java.net.http.HttpTimeoutException | java.net.ConnectException e) {
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    private void addLog(String message) {
        LOG.info(message);
        logSocket.broadcast(message);
    }
}
