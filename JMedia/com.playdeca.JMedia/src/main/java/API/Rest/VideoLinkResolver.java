package API.Rest;

import Models.Video.Series;
import Models.Video.Video;
import Services.VideoMetadataService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves a shareable video link id to a library entry.
 *
 * <p>Links look like {@code /video/info/tt1234567} and {@code /video/watch/2609/s01e02}.
 * The id is either an external id (IMDb {@code tt…}, TMDb digits, TVDb with an explicit
 * kind) or an internal row id ({@code db123}). The SPA calls this once per deep link and
 * then uses the existing title/id based endpoints, so no other API changed.
 *
 * <p>Series ids are resolved to a canonical row when several rows share one TMDb id —
 * repeated scans leave sibling rows for the same show, and the one with the most
 * episodes is the representative the rest of the app already uses.
 */
@Path("/api/video/resolve")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
public class VideoLinkResolver {

    private static final Logger LOG = Logger.getLogger(VideoLinkResolver.class);

    private static final Pattern IMDB = Pattern.compile("^tt\\d{5,}$", Pattern.CASE_INSENSITIVE);
    private static final Pattern DIGITS = Pattern.compile("^\\d+$");
    private static final Pattern INTERNAL = Pattern.compile("^db(\\d+)$", Pattern.CASE_INSENSITIVE);

    private final ObjectMapper mapper = new ObjectMapper();

    @Inject
    VideoMetadataService metadataService;

    /** Not-found lookups are rare: cache hits briefly, misses longer (TMDb gap or outage). */
    private static final long META_TTL_MS = 10 * 60_000L;
    private static final long META_NEGATIVE_TTL_MS = 30 * 60_000L;
    private static final int META_CACHE_MAX = 500;

    private record MetaCacheEntry(ObjectNode meta, long expiresAt) {
    }

    private final Map<String, MetaCacheEntry> metaCache = new ConcurrentHashMap<>();

    /** Auto-detected id: tt… → IMDb, digits → TMDb, dbN → internal row id. */
    @GET
    @Path("/{token}")
    public Response resolveAuto(@PathParam("token") String token,
                                @QueryParam("season") Integer season,
                                @QueryParam("episode") Integer episode) {
        return resolve(null, token, season, episode);
    }

    /** Explicit kind: imdb | tmdb | tvdb | db. */
    @GET
    @Path("/{kind}/{token}")
    public Response resolveTyped(@PathParam("kind") String kind,
                                 @PathParam("token") String token,
                                 @QueryParam("season") Integer season,
                                 @QueryParam("episode") Integer episode) {
        return resolve(kind, token, season, episode);
    }

    private Response resolve(String kind, String token, Integer season, Integer episode) {
        if (token == null || token.isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(error("Missing id")).build();
        }
        String kindNorm = kind == null ? null : kind.trim().toLowerCase(Locale.ROOT);

        ObjectNode result;
        if (kindNorm == null) {
            Matcher internal = INTERNAL.matcher(token);
            if (IMDB.matcher(token).matches()) {
                result = byImdb(token);
            } else if (internal.matches()) {
                result = byInternal(Long.parseLong(internal.group(1)));
            } else if (DIGITS.matcher(token).matches()) {
                result = byTmdb(Integer.parseInt(token));
                if (result == null) {
                    result = byInternal(Long.parseLong(token));
                }
            } else {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(error("Unrecognised id: " + token)).build();
            }
        } else {
            switch (kindNorm) {
                case "imdb" -> result = byImdb(token);
                case "tmdb" -> result = byTmdb(parseIntOrNull(token));
                case "tvdb" -> result = byTvdb(parseIntOrNull(token));
                case "db" -> result = byInternal(parseLongOrNull(token));
                default -> {
                    return Response.status(Response.Status.BAD_REQUEST)
                            .entity(error("Unknown id kind: " + kind)).build();
                }
            }
        }

        if (result == null) {
            ObjectNode missing = mapper.createObjectNode();
            missing.put("found", false);
            missing.put("token", token);
            String detectedKind = kindNorm == null ? detectKind(token) : kindNorm;
            missing.put("kind", detectedKind);
            // Best-effort TMDb metadata so the SPA can render a friendly "not in your
            // library" page instead of a dead end. Never fails the response.
            attachMeta(missing, detectedKind, token);
            return Response.ok(missing).build();
        }

        if (season != null && episode != null && "series".equals(result.path("type").asText())) {
            attachEpisode(result, season, episode);
        }
        return Response.ok(result).build();
    }

    // ── lookups ────────────────────────────────────────────────────────────

    private ObjectNode byImdb(String imdbId) {
        Series series = Series.find("imdbId", imdbId).firstResult();
        if (series != null) {
            return forSeries(series);
        }
        Video movie = Video.find("type = 'movie' and imdbId = ?1", imdbId).firstResult();
        return movie != null ? forMovie(movie) : null;
    }

    private ObjectNode byTmdb(Integer tmdbId) {
        if (tmdbId == null) {
            return null;
        }
        List<Series> candidates = Series.<Series>find("tmdbId", tmdbId).list();
        if (!candidates.isEmpty()) {
            return forSeries(pickCanonicalSeries(candidates));
        }
        Video movie = Video.find("type = 'movie' and tmdbId = ?1", String.valueOf(tmdbId)).firstResult();
        return movie != null ? forMovie(movie) : null;
    }

    private ObjectNode byTvdb(Integer tvdbId) {
        if (tvdbId == null) {
            return null;
        }
        Series series = Series.find("tvdbId", tvdbId).firstResult();
        return series != null ? forSeries(series) : null;
    }

    private ObjectNode byInternal(Long id) {
        if (id == null) {
            return null;
        }
        Series series = Series.findById(id);
        if (series != null) {
            return forSeries(series);
        }
        Video video = Video.findById(id);
        if (video == null) {
            return null;
        }
        if (video.seriesTitle != null && !video.seriesTitle.isBlank()) {
            Series owning = Series.find("title", video.seriesTitle).firstResult();
            if (owning != null) {
                ObjectNode node = forSeries(owning);
                attachEpisode(node, video.seasonNumber, video.episodeNumber);
                return node;
            }
        }
        return forMovie(video);
    }

    /** Repeated scans leave sibling Series rows for one show; the biggest one wins. */
    private Series pickCanonicalSeries(List<Series> candidates) {
        Series best = null;
        long bestCount = -1;
        for (Series candidate : candidates) {
            long count = candidate.title == null ? 0
                    : Video.count("seriesTitle = ?1 and type = 'episode'", candidate.title);
            if (count > bestCount || (count == bestCount && best != null
                    && candidate.id != null && candidate.id < best.id)) {
                best = candidate;
                bestCount = count;
            }
        }
        if (candidates.size() > 1) {
            LOG.debugf("TMDb id %s resolves to %d Series rows; picked '%s' (%d episodes)",
                    candidates.get(0).tmdbId, candidates.size(),
                    best == null ? "?" : best.title, bestCount);
        }
        return best == null ? candidates.get(0) : best;
    }

    private void attachEpisode(ObjectNode node, Integer season, Integer episode) {
        String title = node.path("title").asText(null);
        if (title == null || season == null || episode == null) {
            return;
        }
        Video ep = Video.find("seriesTitle = ?1 and type = 'episode' and seasonNumber = ?2 "
                        + "and episodeNumber = ?3 order by id",
                title, season, episode).firstResult();
        ObjectNode epNode = mapper.createObjectNode();
        if (ep == null) {
            epNode.put("found", false);
            node.set("episode", epNode);
            node.put("playable", false);
            return;
        }
        epNode.put("found", true);
        epNode.put("videoId", ep.id);
        if (ep.seasonNumber != null) epNode.put("season", ep.seasonNumber);
        if (ep.episodeNumber != null) epNode.put("episode", ep.episodeNumber);
        epNode.put("title", ep.episodeTitle != null && !ep.episodeTitle.isBlank()
                ? ep.episodeTitle : (ep.title == null ? "" : ep.title));
        node.set("episode", epNode);
        node.put("playable", true);
        node.put("watch", watchUrl(node.path("token").asText(null))
                + "/s" + String.format(Locale.ROOT, "%02d", season)
                + "e" + String.format(Locale.ROOT, "%02d", episode));
    }

    // ── not-found metadata ─────────────────────────────────────────────────

    /**
     * Adds a {@code meta} object ({title, year, posterUrl, mediaType, overview}) for
     * external ids TMDb knows but our library does not. In-memory cached; a TMDb
     * outage or unknown id simply leaves the body unchanged.
     */
    private void attachMeta(ObjectNode node, String kind, String token) {
        if (token == null || (!"imdb".equals(kind) && !"tmdb".equals(kind) && !"tvdb".equals(kind))) {
            return;
        }
        String cacheKey = kind + ':' + token.toLowerCase(Locale.ROOT);
        try {
            long now = System.currentTimeMillis();
            MetaCacheEntry cached = metaCache.get(cacheKey);
            if (cached != null && now < cached.expiresAt) {
                if (cached.meta != null) {
                    node.set("meta", cached.meta);
                }
                return;
            }
            ObjectNode meta = metadataService.lookupExternalTitle(kind, token)
                    .map(this::buildMeta).orElse(null);
            metaCache.put(cacheKey, new MetaCacheEntry(meta,
                    now + (meta != null ? META_TTL_MS : META_NEGATIVE_TTL_MS)));
            if (metaCache.size() > META_CACHE_MAX) {
                long cutoff = now;
                metaCache.entrySet().removeIf(e -> cutoff >= e.getValue().expiresAt);
            }
            if (meta != null) {
                node.set("meta", meta);
            }
        } catch (RuntimeException e) {
            // A provider problem must never turn a shareable link into an error page.
            LOG.warnf(e, "Resolve: TMDb metadata lookup failed for %s/%s", kind, token);
        }
    }

    private ObjectNode buildMeta(VideoMetadataService.ExternalTitleMeta meta) {
        ObjectNode node = mapper.createObjectNode();
        node.put("title", meta.title());
        if (meta.year() != null) node.put("year", meta.year());
        node.put("posterUrl", meta.posterUrl());
        node.put("mediaType", meta.mediaType());
        node.put("overview", meta.overview());
        if (meta.tmdbId() != null) node.put("tmdbId", meta.tmdbId());
        if (meta.imdbId() != null) node.put("imdbId", meta.imdbId());
        return node;
    }

    // ── shaping ────────────────────────────────────────────────────────────

    private ObjectNode forSeries(Series series) {
        ObjectNode node = base("series", series.title, series.imdbId,
                series.tmdbId == null ? null : String.valueOf(series.tmdbId),
                series.tvdbId, series.id);
        node.put("playable", false);
        node.putNull("watch");
        return node;
    }

    private ObjectNode forMovie(Video movie) {
        ObjectNode node = base("movie", movie.title != null && !movie.title.isBlank()
                        ? movie.title
                        : (movie.seriesTitle == null ? movie.path : movie.seriesTitle),
                movie.imdbId, movie.tmdbId, null, movie.id);
        node.put("playable", true);
        node.put("watch", watchUrl(node.path("token").asText(null)));
        return node;
    }

    private ObjectNode base(String type, String title, String imdbId, String tmdbId,
                            Integer tvdbId, Long internalId) {
        String token = canonicalToken(imdbId, tmdbId, tvdbId, internalId);
        ObjectNode node = mapper.createObjectNode();
        node.put("found", true);
        node.put("type", type);
        node.put("id", internalId);
        node.put("title", title);
        node.put("token", token);
        if (imdbId != null && !imdbId.isBlank()) node.put("imdbId", imdbId);
        if (tmdbId != null && !tmdbId.isBlank()) node.put("tmdbId", tmdbId);
        if (tvdbId != null) node.put("tvdbId", tvdbId);
        node.put("info", "/video/info/" + token);
        return node;
    }

    /** IMDb when present (tt… is unambiguous), else TMDb digits, else an explicit kind. */
    private String canonicalToken(String imdbId, String tmdbId, Integer tvdbId, Long internalId) {
        if (imdbId != null && !imdbId.isBlank()) {
            return imdbId.toLowerCase(Locale.ROOT);
        }
        if (tmdbId != null && !tmdbId.isBlank()) {
            return tmdbId;
        }
        if (tvdbId != null) {
            return "tvdb/" + tvdbId;
        }
        return "db/" + internalId;
    }

    private String watchUrl(String token) {
        return token == null ? null : "/video/watch/" + token;
    }

    private String detectKind(String token) {
        if (IMDB.matcher(token).matches()) return "imdb";
        if (INTERNAL.matcher(token).matches()) return "db";
        if (DIGITS.matcher(token).matches()) return "tmdb";
        return "unknown";
    }

    private Integer parseIntOrNull(String value) {
        try {
            return Integer.valueOf(value);
        } catch (NumberFormatException e) {
            LOG.warnf("Resolve: '%s' is not a number", value);
            return null;
        }
    }

    private Long parseLongOrNull(String value) {
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException e) {
            LOG.warnf("Resolve: '%s' is not a number", value);
            return null;
        }
    }

    private ObjectNode error(String message) {
        ObjectNode node = mapper.createObjectNode();
        node.put("found", false);
        node.put("error", message);
        return node;
    }
}
