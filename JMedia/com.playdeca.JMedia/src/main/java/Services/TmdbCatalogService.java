package Services;

import Models.Video.SeasonMetadataCache;
import Models.Video.Series;
import Models.Video.Video;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * TMDb catalogue access for the info view and search: show seasons, per-season
 * episode lists (including unreleased episodes), and external title search.
 *
 * <p>Season snapshots are persisted in {@link SeasonMetadataCache}; show details
 * and search results are kept in short-lived in-memory caches. Nothing here
 * blocks playback: every method degrades to null/empty when TMDb is disabled or
 * unreachable.
 */
@ApplicationScoped
public class TmdbCatalogService {

    private static final Logger LOG = LoggerFactory.getLogger(TmdbCatalogService.class);

    private static final long SHOW_TTL_MS = Duration.ofHours(6).toMillis();
    private static final long SEARCH_TTL_MS = Duration.ofMinutes(10).toMillis();
    private static final int SEARCH_CACHE_MAX = 200;

    private static final long HOUR = 60L * 60L * 1000L;
    private static final long DAY = 24L * HOUR;

    @Inject
    VideoMetadataService metadataService;

    @Inject
    ObjectMapper objectMapper;

    private final Map<Integer, CachedShow> showCache = new ConcurrentHashMap<>();
    private final Map<String, CachedSearch> searchCache = new ConcurrentHashMap<>();
    private volatile boolean refreshRunning = false;

    public record CatalogEpisode(int number, String name, String overview,
                                 String airDate, String stillPath, boolean released) {}

    public record CatalogSeason(int tmdbId, int seasonNumber, List<CatalogEpisode> episodes,
                                int episodeCount, String status, String nextAirDate,
                                String lastAirDate, boolean complete) {}

    public record CatalogShow(int tmdbId, String title, String status, String nextAirDate,
                              List<Integer> seasons, Map<Integer, Integer> counts) {}

    public record SearchResult(int tmdbId, String title, Integer year, String mediaType,
                               String posterUrl, String overview, boolean inLibrary, String token) {}

    private record CachedShow(CatalogShow show, long expiresAt) {}
    private record CachedSearch(List<SearchResult> results, long expiresAt) {}

    // ---------------------------------------------------------------- show

    public CatalogShow getShow(int tmdbId) {
        long now = System.currentTimeMillis();
        CachedShow cached = showCache.get(tmdbId);
        if (cached != null && now < cached.expiresAt) {
            return cached.show;
        }

        JsonNode root = metadataService.tmdbGet("/tv/" + tmdbId, null);
        if (root == null) {
            return cached != null ? cached.show : null;
        }

        List<Integer> seasons = new ArrayList<>();
        Map<Integer, Integer> counts = new LinkedHashMap<>();
        for (JsonNode season : root.path("seasons")) {
            int number = season.path("season_number").asInt(-1);
            if (number < 0) continue;
            seasons.add(number);
            counts.put(number, season.path("episode_count").asInt(0));
        }
        seasons.sort(Integer::compareTo);

        String nextAirDate = null;
        JsonNode next = root.path("next_episode_to_air");
        if (next != null && !next.isMissingNode() && next.hasNonNull("air_date")) {
            nextAirDate = next.path("air_date").asText(null);
        }

        CatalogShow show = new CatalogShow(tmdbId, root.path("name").asText(""),
                root.path("status").asText(""), nextAirDate, seasons, counts);
        showCache.put(tmdbId, new CachedShow(show, now + SHOW_TTL_MS));
        return show;
    }

    // --------------------------------------------------------------- season

    public CatalogSeason getSeason(int tmdbId, int seasonNumber) {
        long now = System.currentTimeMillis();
        SeasonMetadataCache row = findRow(tmdbId, seasonNumber);
        if (row != null && row.payloadJson != null && !row.payloadJson.isBlank()
                && (row.manualComplete || (row.nextCheckAt != null && row.nextCheckAt.toEpochMilli() > now))) {
            return fromRow(row);
        }

        JsonNode root = metadataService.tmdbGet("/tv/" + tmdbId + "/season/" + seasonNumber, null);
        if (root == null) {
            return row != null ? fromRow(row) : null;
        }

        List<CatalogEpisode> episodes = new ArrayList<>();
        LocalDate today = LocalDate.now();
        String lastAirDate = null;
        String nextAirDate = null;
        boolean allAired = true;

        for (JsonNode ep : root.path("episodes")) {
            int number = ep.path("episode_number").asInt(-1);
            if (number < 0) continue;
            String airDate = text(ep, "air_date");
            String still = text(ep, "still_path");
            boolean released = true;
            if (airDate != null) {
                try {
                    LocalDate air = LocalDate.parse(airDate);
                    released = !air.isAfter(today);
                    if (!released) {
                        allAired = false;
                        if (nextAirDate == null || airDate.compareTo(nextAirDate) < 0) {
                            nextAirDate = airDate;
                        }
                    }
                    if (lastAirDate == null || airDate.compareTo(lastAirDate) > 0) {
                        lastAirDate = airDate;
                    }
                } catch (RuntimeException ignored) {
                    // Unparseable air date: treat as TBA.
                }
            } else {
                allAired = false;
            }
            episodes.add(new CatalogEpisode(number, text(ep, "name"), text(ep, "overview"),
                    airDate, still, released));
        }
        episodes.sort((a, b) -> Integer.compare(a.number(), b.number()));

        CatalogShow show = getShow(tmdbId);
        String status = show != null ? show.status() : null;
        if (nextAirDate == null && show != null && show.nextAirDate() != null) {
            nextAirDate = show.nextAirDate();
        }
        boolean showFinished = status != null
                && ("ended".equalsIgnoreCase(status) || "canceled".equalsIgnoreCase(status));
        boolean complete = allAired && showFinished;

        String payload = toPayload(episodes);
        Instant nextCheck = computeNextCheck(complete, allAired, nextAirDate, status, today);
        persistRow(tmdbId, seasonNumber, payload, episodes.size(), lastAirDate, nextAirDate,
                status, complete, nextCheck);

        return new CatalogSeason(tmdbId, seasonNumber, episodes, episodes.size(), status,
                nextAirDate, lastAirDate, complete);
    }

    // ---------------------------------------------------------------- search

    public List<SearchResult> search(String query) {
        String q = query == null ? "" : query.trim();
        if (q.length() < 2) return List.of();
        String key = q.toLowerCase(Locale.ROOT);
        long now = System.currentTimeMillis();

        CachedSearch cached = searchCache.get(key);
        if (cached != null && now < cached.expiresAt) {
            return cached.results;
        }

        JsonNode root = metadataService.tmdbGet("/search/multi",
                "query=" + URLEncoder.encode(q, StandardCharsets.UTF_8) + "&include_adult=false&page=1");
        List<SearchResult> results = new ArrayList<>();
        if (root != null) {
            for (JsonNode node : root.path("results")) {
                String mediaType = node.path("media_type").asText("");
                if (!"movie".equals(mediaType) && !"tv".equals(mediaType)) continue;
                int id = node.path("id").asInt(-1);
                if (id <= 0) continue;

                boolean movie = "movie".equals(mediaType);
                String title = text(node, movie ? "title" : "name");
                if (title == null || title.isBlank()) continue;
                String date = text(node, movie ? "release_date" : "first_air_date");
                Integer year = null;
                if (date != null && date.length() >= 4) {
                    try { year = Integer.parseInt(date.substring(0, 4)); } catch (NumberFormatException ignored) {}
                }
                String posterPath = text(node, "poster_path");
                String posterUrl = (posterPath != null && posterPath.startsWith("/"))
                        ? "https://image.tmdb.org/t/p/w342" + posterPath : null;

                boolean inLibrary;
                try {
                    inLibrary = movie
                            ? Video.count("tmdbId = ?1 and type = 'movie'", String.valueOf(id)) > 0
                            : Series.count("tmdbId = ?1", id) > 0;
                } catch (RuntimeException e) {
                    inLibrary = false;
                }

                results.add(new SearchResult(id, title, year, mediaType, posterUrl,
                        text(node, "overview"), inLibrary, String.valueOf(id)));
            }
        }

        searchCache.put(key, new CachedSearch(results, now + SEARCH_TTL_MS));
        if (searchCache.size() > SEARCH_CACHE_MAX) {
            searchCache.entrySet().removeIf(e -> now >= e.getValue().expiresAt);
        }
        return results;
    }

    // -------------------------------------------------------------- refresh

    /** Re-fetch a bounded number of seasons whose nextCheckAt has passed. */
    public int refreshDue(int limit) {
        if (refreshRunning) return 0;
        refreshRunning = true;
        try {
            List<SeasonMetadataCache> due = SeasonMetadataCache.find(
                            "manualComplete = false and (nextCheckAt is null or nextCheckAt <= ?1)",
                            Instant.now())
                    .page(0, Math.max(1, limit)).list();
            int refreshed = 0;
            for (SeasonMetadataCache row : due) {
                try {
                    getSeason(row.tmdbId, row.seasonNumber);
                    refreshed++;
                } catch (RuntimeException e) {
                    LOG.warn("[SeasonCache] refresh failed for tmdb {} S{}: {}",
                            row.tmdbId, row.seasonNumber, e.getMessage());
                }
            }
            return refreshed;
        } finally {
            refreshRunning = false;
        }
    }

    /** Drop a season (or a whole show's seasons) so the next view re-fetches. */
    public void invalidate(int tmdbId, Integer seasonNumber) {
        showCache.remove(tmdbId);
        try {
            QuarkusTransaction.requiringNew().run(() -> {
                if (seasonNumber == null) {
                    SeasonMetadataCache.delete("tmdbId = ?1", tmdbId);
                } else {
                    SeasonMetadataCache.delete("tmdbId = ?1 and seasonNumber = ?2", tmdbId, seasonNumber);
                }
            });
        } catch (RuntimeException e) {
            LOG.warn("[SeasonCache] invalidate failed for tmdb {}: {}", tmdbId, e.getMessage());
        }
    }

    // -------------------------------------------------------------- helpers

    private SeasonMetadataCache findRow(int tmdbId, int seasonNumber) {
        try {
            return SeasonMetadataCache.find("tmdbId = ?1 and seasonNumber = ?2", tmdbId, seasonNumber)
                    .firstResult();
        } catch (RuntimeException e) {
            LOG.warn("[SeasonCache] lookup failed for tmdb {} S{}: {}", tmdbId, seasonNumber, e.getMessage());
            return null;
        }
    }

    private void persistRow(int tmdbId, int seasonNumber, String payload, int count,
                            String lastAirDate, String nextAirDate, String status,
                            boolean complete, Instant nextCheck) {
        try {
            QuarkusTransaction.requiringNew().run(() -> {
                SeasonMetadataCache row = SeasonMetadataCache.find(
                        "tmdbId = ?1 and seasonNumber = ?2", tmdbId, seasonNumber).firstResult();
                if (row == null) {
                    row = new SeasonMetadataCache();
                    row.tmdbId = tmdbId;
                    row.seasonNumber = seasonNumber;
                }
                row.payloadJson = payload;
                row.episodeCount = count;
                row.lastAirDate = lastAirDate;
                row.nextAirDate = nextAirDate;
                row.showStatus = status;
                row.fetchedAt = Instant.now();
                row.nextCheckAt = nextCheck;
                row.complete = complete;
                row.persist();
            });
        } catch (RuntimeException e) {
            LOG.warn("[SeasonCache] persist failed for tmdb {} S{}: {}", tmdbId, seasonNumber, e.getMessage());
        }
    }

    private CatalogSeason fromRow(SeasonMetadataCache row) {
        List<CatalogEpisode> episodes = new ArrayList<>();
        if (row.payloadJson != null && !row.payloadJson.isBlank()) {
            try {
                JsonNode array = objectMapper.readTree(row.payloadJson);
                for (JsonNode node : array) {
                    episodes.add(new CatalogEpisode(
                            node.path("number").asInt(0),
                            text(node, "name"),
                            text(node, "overview"),
                            text(node, "airDate"),
                            text(node, "stillPath"),
                            node.path("released").asBoolean(true)));
                }
            } catch (Exception e) {
                LOG.warn("[SeasonCache] could not parse payload for tmdb {} S{}: {}",
                        row.tmdbId, row.seasonNumber, e.getMessage());
            }
        }
        return new CatalogSeason(row.tmdbId, row.seasonNumber, episodes,
                row.episodeCount != null ? row.episodeCount : episodes.size(),
                row.showStatus, row.nextAirDate, row.lastAirDate, row.complete);
    }

    private String toPayload(List<CatalogEpisode> episodes) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (CatalogEpisode ep : episodes) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("number", ep.number());
            map.put("name", ep.name());
            map.put("overview", ep.overview());
            map.put("airDate", ep.airDate());
            map.put("stillPath", ep.stillPath());
            map.put("released", ep.released());
            list.add(map);
        }
        try {
            return objectMapper.writeValueAsString(list);
        } catch (Exception e) {
            LOG.warn("[SeasonCache] could not serialize season payload: {}", e.getMessage());
            return "[]";
        }
    }

    private Instant computeNextCheck(boolean complete, boolean allAired,
                                     String nextAirDate, String status, LocalDate today) {
        long now = System.currentTimeMillis();
        if (complete) return Instant.ofEpochMilli(now + 90 * DAY);
        if (nextAirDate != null) {
            try {
                LocalDate next = LocalDate.parse(nextAirDate);
                return next.plusDays(1).atStartOfDay(java.time.ZoneOffset.UTC).toInstant();
            } catch (RuntimeException ignored) {
                // fall through
            }
        }
        if (!allAired) return Instant.ofEpochMilli(now + 12 * HOUR);
        if (status != null && status.toLowerCase(Locale.ROOT).contains("returning")) {
            return Instant.ofEpochMilli(now + 7 * DAY);
        }
        return Instant.ofEpochMilli(now + 3 * DAY);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asText(null);
    }
}
