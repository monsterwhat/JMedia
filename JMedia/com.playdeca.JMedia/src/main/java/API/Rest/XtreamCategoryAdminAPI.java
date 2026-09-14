package API.Rest;

import API.ApiResponse;
import Models.Settings.Session;
import Models.Settings.User;
import Models.Video.Genre;
import Models.Video.Series;
import Models.Video.Video;
import Models.Video.VideoGenre;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.enterprise.context.ApplicationScoped;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Admin-only curation API backing the Xtream Categories poster-grid UI.
 * Titles are browsed (movies + series), genres are created/renamed/hidden,
 * and genres are bulk-assigned to titles. Writes keep the curated
 * VideoGenre join and the free-text genre lists in agreement, with the
 * primary genre first (first genre wins as the Xtream primary category).
 */
@Path("/api/admin/xtream-categories")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@ApplicationScoped
public class XtreamCategoryAdminAPI {

    private static final Logger LOG = LoggerFactory.getLogger(XtreamCategoryAdminAPI.class);

    private boolean isAdmin(HttpHeaders headers) {
        String sessionId = getSessionId(headers);
        if (sessionId == null) {
            return false;
        }
        Session session = Session.findBySessionId(sessionId);
        if (session == null || !session.active) {
            return false;
        }
        User user = User.find("username", session.username).firstResult();
        if (user == null) {
            return false;
        }
        return "admin".equals(user.getGroupName());
    }

    private String getSessionId(HttpHeaders headers) {
        if (headers.getCookies() != null && headers.getCookies().containsKey("JMEDIA_SESSION")) {
            return headers.getCookies().get("JMEDIA_SESSION").getValue();
        }
        return null;
    }

    private Response forbidden() {
        return Response.status(Response.Status.FORBIDDEN)
                .entity(ApiResponse.error("Admin access required"))
                .build();
    }

    @GET
    @Path("/titles")
    public Response listTitles(@Context HttpHeaders headers,
                               @QueryParam("type") @DefaultValue("movie") String type,
                               @QueryParam("q") @DefaultValue("") String q,
                               @QueryParam("genreId") String genreId,
                               @QueryParam("offset") @DefaultValue("0") int offset,
                               @QueryParam("limit") @DefaultValue("48") int limit) {
        if (!isAdmin(headers)) {
            return forbidden();
        }
        int safeLimit = Math.max(1, Math.min(limit, 200));
        int safeOffset = Math.max(0, offset);
        String query = q != null ? q.strip().toLowerCase() : "";

        try {
            if ("series".equalsIgnoreCase(type)) {
                return Response.ok(listSeriesTitles(query, genreId, safeOffset, safeLimit)).build();
            }
            return Response.ok(listMovieTitles(query, genreId, safeOffset, safeLimit)).build();
        } catch (Exception e) {
            LOG.error("Failed to list Xtream titles type={}: {}", type, e.getMessage());
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(ApiResponse.error("Failed to list titles"))
                    .build();
        }
    }

    private Map<String, Object> listMovieTitles(String query, String genreId, int offset, int limit) {
        StringBuilder hql = new StringBuilder("SELECT DISTINCT v FROM Video v");
        List<Object> params = new ArrayList<>();
        List<String> clauses = new ArrayList<>();
        Long genreFilterId = parseLongOrNull(genreId);
        if (genreFilterId != null) {
            hql.append(" JOIN VideoGenre vg ON vg.video.id = v.id");
            clauses.add("vg.genre.id = ?" + (params.size() + 1));
            params.add(genreFilterId);
        }
        clauses.add("v.type = 'movie'");
        if (!query.isEmpty()) {
            clauses.add("LOWER(v.title) LIKE ?" + (params.size() + 1));
            params.add("%" + query.replace("%", "\\%").replace("_", "\\_") + "%");
        }
        hql.append(" WHERE ").append(String.join(" AND ", clauses)).append(" ORDER BY v.title");
        String hqlString = hql.toString();

        long total = Video.<Video>find(hqlString, params.toArray()).count();
        int pageIndex = offset / limit;
        List<Video> videos = Video.<Video>find(hqlString, params.toArray()).page(pageIndex, limit).list();

        List<Map<String, Object>> items = new ArrayList<>();
        for (Video v : videos) {
            Map<String, Object> item = new HashMap<>();
            item.put("id", v.id);
            item.put("title", v.title != null ? v.title : "");
            item.put("posterUrl", "/api/video/thumbnail/" + v.id);
            item.put("genres", v.genres != null ? new ArrayList<>(v.genres) : new ArrayList<>());
            item.put("year", yearOf(v.releaseDate));
            items.add(item);
        }
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("data", items);
        result.put("total", total);
        return result;
    }

    private Map<String, Object> listSeriesTitles(String query, String genreId, int offset, int limit) {
        Long genreFilterId = parseLongOrNull(genreId);
        String genreFilterName = null;
        if (genreFilterId != null) {
            Genre g = Genre.findById(genreFilterId);
            if (g != null) {
                genreFilterName = g.name;
            } else {
                Map<String, Object> result = new HashMap<>();
                result.put("success", true);
                result.put("data", new ArrayList<>());
                result.put("total", 0L);
                return result;
            }
        }
        List<Series> all = Series.<Series>list("ORDER BY title");
        List<Series> filtered = new ArrayList<>();
        final String wantedGenre = genreFilterName;
        for (Series s : all) {
            if (!query.isEmpty() && (s.title == null || !s.title.toLowerCase().contains(query))) {
                continue;
            }
            if (wantedGenre != null && (s.genres == null || s.genres.stream()
                    .noneMatch(g -> g != null && g.equalsIgnoreCase(wantedGenre)))) {
                continue;
            }
            filtered.add(s);
        }
        long total = filtered.size();
        int from = Math.min(offset, filtered.size());
        int to = Math.min(from + limit, filtered.size());
        List<Map<String, Object>> items = new ArrayList<>();
        for (Series s : filtered.subList(from, to)) {
            Map<String, Object> item = new HashMap<>();
            item.put("id", s.id);
            item.put("title", s.title != null ? s.title : "");
            item.put("posterUrl", firstEpisodeThumbnail(s));
            item.put("genres", s.genres != null ? new ArrayList<>(s.genres) : new ArrayList<>());
            item.put("year", yearOf(s.releaseDate));
            items.add(item);
        }
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("data", items);
        result.put("total", total);
        return result;
    }

    private String firstEpisodeThumbnail(Series ser) {
        // Mirror the video view (VideoUiApi series list): the poster is the first
        // episode matching seriesTitle, in database order — NOT season/episode
        // order. Anything else shows a different still than the library shows.
        try {
            if (ser.title != null && !ser.title.isBlank()) {
                List<Video> eps = Video.<Video>find(
                        "LOWER(seriesTitle) = LOWER(?1)", ser.title.strip()).list();
                if (!eps.isEmpty() && eps.get(0).id != null) {
                    return "/api/video/thumbnail/" + eps.get(0).id;
                }
            }
        } catch (Exception e) {
            LOG.debug("firstEpisodeThumbnail failed for series {}: {}", ser.id, e.getMessage());
        }
        return "";
    }

    private String yearOf(String releaseDate) {
        if (releaseDate != null && releaseDate.length() >= 4) {
            return releaseDate.substring(0, 4);
        }
        return "";
    }

    private Long parseLongOrNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(value.strip());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private int ensurePresetGenres() {
        java.util.Set<String> names = new java.util.TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        try {
            // Collect in Java, not via DISTINCT scalar query: scalar projections
            // fail connection enlistment under multi-PU JTA here, while entity
            // reads work. Episodes inherit series genres, so movies + series cover it.
            List<Video> movies = Video.<Video>find("type = 'movie'").list();
            for (Video v : movies) {
                if (v.genres == null) {
                    continue;
                }
                for (String n : v.genres) {
                    if (n != null && !n.isBlank()) {
                        names.add(n.strip());
                    }
                }
            }
        } catch (Exception e) {
            LOG.warn("ensurePresetGenres movie scan failed: {}", e.toString());
        }
        try {
            List<Series> all = Series.<Series>listAll();
            for (Series s : all) {
                if (s.genres == null) {
                    continue;
                }
                for (String n : s.genres) {
                    if (n != null && !n.isBlank()) {
                        names.add(n.strip());
                    }
                }
            }
        } catch (Exception e) {
            LOG.warn("ensurePresetGenres series scan failed: {}", e.toString());
        }
        java.util.Set<String> existing = new java.util.TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        int maxSort = 0;
        boolean hasSort = false;
        for (Genre g : Genre.<Genre>listAll()) {
            if (g.name != null) {
                existing.add(g.name.strip());
            }
            if (g.sortOrder != null && (!hasSort || g.sortOrder > maxSort)) {
                maxSort = g.sortOrder;
                hasSort = true;
            }
        }
        int created = 0;
        for (String name : names) {
            if (existing.contains(name)) {
                continue;
            }
            Genre g = new Genre();
            g.name = name;
            g.slug = name.toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
            if (hasSort) {
                maxSort++;
                g.sortOrder = maxSort;
            } else {
                g.sortOrder = 0;
                hasSort = true;
            }
            g.isActive = true;
            g.persist();
            existing.add(name);
            created++;
        }
        return created;
    }

    @GET
    @Path("/genres")
    public Response listGenres(@Context HttpHeaders headers) {
        if (!isAdmin(headers)) {
            return forbidden();
        }
        int backfilled = 0;
        try {
            backfilled = io.quarkus.narayana.jta.QuarkusTransaction.requiringNew()
                    .call(() -> ensurePresetGenres());
        } catch (Exception e) {
            LOG.warn("Genre backfill failed: {}", e.toString());
        }
        if (backfilled > 0) {
            LOG.info("Backfilled {} preset genres missing entities", backfilled);
        }
        List<Genre> genres = Genre.<Genre>listAll();
        genres.sort((a, b) -> {
            int ao = a.sortOrder != null ? a.sortOrder : Integer.MAX_VALUE;
            int bo = b.sortOrder != null ? b.sortOrder : Integer.MAX_VALUE;
            int cmp = Integer.compare(ao, bo);
            if (cmp != 0) return cmp;
            String an = a.name != null ? a.name : "";
            String bn = b.name != null ? b.name : "";
            return an.compareToIgnoreCase(bn);
        });
        List<Map<String, Object>> items = new ArrayList<>();
        for (Genre g : genres) {
            Map<String, Object> item = new HashMap<>();
            item.put("id", g.id);
            item.put("name", g.name);
            item.put("color", g.color);
            item.put("icon", g.icon);
            item.put("sortOrder", g.sortOrder);
            item.put("isActive", g.isActive);
            long titleCount = 0;
            if (g.name != null) {
                titleCount = Video.<Video>find(
                        "SELECT v FROM Video v JOIN v.genres vg WHERE LOWER(vg) = LOWER(?1) AND v.type = 'movie'", g.name).count();
                titleCount += Series.<Series>find(
                        "SELECT s FROM Series s JOIN s.genres g WHERE LOWER(g) = LOWER(?1)", g.name).count();
            }
            item.put("titleCount", titleCount);
            items.add(item);
        }
        return Response.ok(ApiResponse.success(items)).build();
    }

    @PUT
    @Path("/titles/bulk-genres")
    public Response bulkAssignGenres(@Context HttpHeaders headers, Map<String, Object> body) {
        if (!isAdmin(headers)) {
            return forbidden();
        }
        if (body == null) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(ApiResponse.error("Request body required")).build();
        }
        String kind = body.get("kind") != null ? String.valueOf(body.get("kind")) : "movie";
        List<Long> ids = toLongList(body.get("ids"));
        List<Long> add = toLongList(body.get("add"));
        List<Long> remove = toLongList(body.get("remove"));
        if (ids.isEmpty() || (add.isEmpty() && remove.isEmpty())) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(ApiResponse.error("ids and at least one of add/remove are required")).build();
        }
        Map<Long, Genre> addGenres = new java.util.LinkedHashMap<>();
        for (Long gid : add) {
            Genre g = Genre.findById(gid);
            if (g != null) {
                addGenres.put(gid, g);
            } else {
                LOG.warn("bulkAssignGenres: unknown genre id {} ignored", gid);
            }
        }
        java.util.Set<Long> removeSet = new java.util.HashSet<>(remove);
        int updated;
        final boolean series = "series".equalsIgnoreCase(kind);
        try {
            updated = io.quarkus.narayana.jta.QuarkusTransaction.requiringNew().call(() -> {
                int n = 0;
                for (Long id : ids) {
                    boolean changed;
                    if (series) {
                        changed = applySeriesGenres(id, addGenres, removeSet);
                    } else {
                        changed = applyMovieGenres(id, addGenres, removeSet);
                    }
                    if (changed) {
                        n++;
                    }
                }
                return n;
            });
        } catch (Exception e) {
            LOG.error("bulkAssignGenres failed kind={}: {}", kind, e.toString());
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(ApiResponse.error("Bulk update failed")).build();
        }
        LOG.info("bulkAssignGenres kind={} titles={} updated={} add={} remove={}",
                kind, ids.size(), updated, add.size(), remove.size());
        Map<String, Object> data = new HashMap<>();
        data.put("updated", updated);
        return Response.ok(ApiResponse.success(data)).build();
    }

    @PUT
    @Path("/titles/bulk-primary")
    public Response bulkSetPrimary(@Context HttpHeaders headers, Map<String, Object> body) {
        if (!isAdmin(headers)) {
            return forbidden();
        }
        if (body == null) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(ApiResponse.error("Request body required")).build();
        }
        String kind = body.get("kind") != null ? String.valueOf(body.get("kind")) : "movie";
        List<Long> ids = toLongList(body.get("ids"));
        Long genreId = null;
        Object rawGenre = body.get("genreId");
        if (rawGenre instanceof Number) {
            genreId = ((Number) rawGenre).longValue();
        } else if (rawGenre != null) {
            try {
                genreId = Long.parseLong(String.valueOf(rawGenre));
            } catch (NumberFormatException e) {
                LOG.debug("bulkSetPrimary ignoring non-numeric genreId: {}", rawGenre);
            }
        }
        if (ids.isEmpty() || genreId == null) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(ApiResponse.error("ids and genreId are required")).build();
        }
        Genre genre = Genre.findById(genreId);
        if (genre == null || genre.name == null || genre.name.isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(ApiResponse.error("Unknown genre")).build();
        }
        int updated;
        final boolean series = "series".equalsIgnoreCase(kind);
        final Genre primaryGenre = genre;
        try {
            updated = io.quarkus.narayana.jta.QuarkusTransaction.requiringNew().call(() -> {
                int n = 0;
                for (Long id : ids) {
                    boolean changed;
                    if (series) {
                        changed = promoteSeriesPrimary(id, primaryGenre.name.strip());
                    } else {
                        changed = promoteMoviePrimary(id, primaryGenre);
                    }
                    if (changed) {
                        n++;
                    }
                }
                return n;
            });
        } catch (Exception e) {
            LOG.error("bulkSetPrimary failed kind={}: {}", kind, e.toString());
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(ApiResponse.error("Set primary failed")).build();
        }
        LOG.info("bulkSetPrimary kind={} titles={} updated={} genre={}", kind, ids.size(), updated, genre.name);
        Map<String, Object> data = new HashMap<>();
        data.put("updated", updated);
        return Response.ok(ApiResponse.success(data)).build();
    }

    @GET
    @Path("/titles/{kind}/{id}")
    public Response getTitleGenres(@Context HttpHeaders headers,
                                   @PathParam("kind") String kind,
                                   @PathParam("id") Long id) {
        if (!isAdmin(headers)) {
            return forbidden();
        }
        boolean series = "series".equalsIgnoreCase(kind);
        String title;
        List<String> names;
        if (series) {
            Series s = Series.findById(id);
            if (s == null) {
                return Response.status(Response.Status.NOT_FOUND)
                        .entity(ApiResponse.error("Title not found")).build();
            }
            title = s.title != null ? s.title : "";
            names = s.genres != null ? new ArrayList<>(s.genres) : new ArrayList<>();
        } else {
            Video v = Video.findById(id);
            if (v == null) {
                return Response.status(Response.Status.NOT_FOUND)
                        .entity(ApiResponse.error("Title not found")).build();
            }
            title = v.title != null ? v.title : "";
            names = v.genres != null ? new ArrayList<>(v.genres) : new ArrayList<>();
        }
        Map<String, Long> entityIds = new HashMap<>();
        for (Genre g : Genre.<Genre>listAll()) {
            if (g.id != null && g.name != null && !entityIds.containsKey(g.name.strip().toLowerCase())) {
                entityIds.put(g.name.strip().toLowerCase(), g.id);
            }
        }
        List<Map<String, Object>> genres = new ArrayList<>();
        for (String n : names) {
            if (n == null || n.isBlank()) {
                continue;
            }
            Map<String, Object> g = new HashMap<>();
            g.put("id", entityIds.get(n.strip().toLowerCase()));
            g.put("name", n.strip());
            genres.add(g);
        }
        Map<String, Object> data = new HashMap<>();
        data.put("id", id);
        data.put("title", title);
        data.put("genres", genres);
        return Response.ok(ApiResponse.success(data)).build();
    }

    @PUT
    @Path("/titles/{kind}/{id}")
    public Response setTitleGenres(@Context HttpHeaders headers,
                                   @PathParam("kind") String kind,
                                   @PathParam("id") Long id,
                                   Map<String, Object> body) {
        if (!isAdmin(headers)) {
            return forbidden();
        }
        List<Long> genreIds = toLongList(body != null ? body.get("genreIds") : null);
        if (genreIds.isEmpty()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(ApiResponse.error("genreIds must not be empty")).build();
        }
        List<Genre> ordered = new ArrayList<>();
        for (Long gid : genreIds) {
            Genre g = Genre.findById(gid);
            if (g == null || g.name == null || g.name.isBlank()) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(ApiResponse.error("Unknown genre id: " + gid)).build();
            }
            boolean dup = false;
            for (Genre kept : ordered) {
                if (kept.id.equals(g.id)) {
                    dup = true;
                    break;
                }
            }
            if (!dup) {
                ordered.add(g);
            }
        }
        final boolean series = "series".equalsIgnoreCase(kind);
        int updated;
        try {
            updated = io.quarkus.narayana.jta.QuarkusTransaction.requiringNew().call(() -> {
                if (series) {
                    Series s = Series.findById(id);
                    if (s == null) {
                        return 0;
                    }
                    List<String> names = new ArrayList<>();
                    for (Genre g : ordered) {
                        names.add(g.name.strip());
                    }
                    s.genres = names;
                    s.persist();
                    return 1;
                }
                Video v = Video.findById(id);
                if (v == null) {
                    return 0;
                }
                List<VideoGenre> joins = VideoGenre.<VideoGenre>find(
                        "video.id = ?1 ORDER BY orderIndex", id).list();
                for (VideoGenre j : joins) {
                    j.delete();
                }
                List<String> names = new ArrayList<>();
                int order = 0;
                for (Genre g : ordered) {
                    VideoGenre j = new VideoGenre();
                    j.video = v;
                    j.genre = g;
                    j.orderIndex = order++;
                    j.persist();
                    names.add(g.name.strip());
                }
                v.genres = names;
                v.persist();
                return 1;
            });
        } catch (Exception e) {
            LOG.error("setTitleGenres failed kind={} id={}: {}", kind, id, e.toString());
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(ApiResponse.error("Save failed")).build();
        }
        if (updated == 0) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(ApiResponse.error("Title not found")).build();
        }
        Map<String, Object> data = new HashMap<>();
        data.put("updated", updated);
        return Response.ok(ApiResponse.success(data)).build();
    }

    private boolean promoteMoviePrimary(Long videoId, Genre genre) {
        Video v = Video.findById(videoId);
        if (v == null) {
            return false;
        }
        java.util.LinkedHashMap<Long, Genre> ordered = new java.util.LinkedHashMap<>();
        ordered.put(genre.id, genre);
        for (Genre g : currentMovieGenres(v)) {
            if (!g.id.equals(genre.id)) {
                ordered.putIfAbsent(g.id, g);
            }
        }
        saveMovieGenreOrder(v, new ArrayList<>(ordered.values()));
        return true;
    }

    private boolean promoteSeriesPrimary(Long seriesId, String genreName) {
        Series s = Series.findById(seriesId);
        if (s == null) {
            return false;
        }
        List<String> result = new ArrayList<>();
        result.add(genreName);
        if (s.genres != null) {
            for (String n : s.genres) {
                if (n == null || n.isBlank() || n.strip().equalsIgnoreCase(genreName)) {
                    continue;
                }
                boolean dup = false;
                for (String kept : result) {
                    if (kept.equalsIgnoreCase(n.strip())) {
                        dup = true;
                        break;
                    }
                }
                if (!dup) {
                    result.add(n.strip());
                }
            }
        }
        s.genres = result;
        s.persist();
        return true;
    }

    private List<Genre> currentMovieGenres(Video v) {
        Map<String, Genre> byName = new HashMap<>();
        int maxSort = 0;
        boolean hasSort = false;
        for (Genre g : Genre.<Genre>listAll()) {
            if (g.name != null) {
                byName.putIfAbsent(g.name.strip().toLowerCase(), g);
            }
            if (g.sortOrder != null && (!hasSort || g.sortOrder > maxSort)) {
                maxSort = g.sortOrder;
                hasSort = true;
            }
        }
        java.util.LinkedHashMap<Long, Genre> ordered = new java.util.LinkedHashMap<>();
        if (v.genres != null) {
            for (String n : v.genres) {
                if (n == null || n.isBlank()) {
                    continue;
                }
                String key = n.strip().toLowerCase();
                Genre g = byName.get(key);
                if (g == null) {
                    g = new Genre();
                    g.name = n.strip();
                    g.slug = g.name.toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
                    if (hasSort) {
                        maxSort++;
                        g.sortOrder = maxSort;
                    } else {
                        g.sortOrder = 0;
                        hasSort = true;
                    }
                    g.isActive = true;
                    g.persist();
                    byName.put(key, g);
                    LOG.info("Auto-created genre id={} name={} during title curation", g.id, g.name);
                }
                if (g.id != null) {
                    ordered.putIfAbsent(g.id, g);
                }
            }
        }
        for (VideoGenre j : VideoGenre.<VideoGenre>find("video.id = ?1 ORDER BY orderIndex", v.id).list()) {
            if (j.genre != null && j.genre.id != null) {
                ordered.putIfAbsent(j.genre.id, j.genre);
            }
        }
        return new ArrayList<>(ordered.values());
    }

    private void saveMovieGenreOrder(Video v, List<Genre> ordered) {
        List<VideoGenre> joins = VideoGenre.<VideoGenre>find("video.id = ?1", v.id).list();
        for (VideoGenre j : joins) {
            j.delete();
        }
        List<String> names = new ArrayList<>();
        int order = 0;
        for (Genre g : ordered) {
            VideoGenre j = new VideoGenre();
            j.video = v;
            j.genre = g;
            j.orderIndex = order++;
            j.persist();
            if (g.name != null) {
                names.add(g.name);
            }
        }
        v.genres = names;
        v.persist();
    }

    private boolean applyMovieGenres(Long videoId, Map<Long, Genre> addGenres, java.util.Set<Long> removeSet) {
        Video v = Video.findById(videoId);
        if (v == null) {
            return false;
        }
        java.util.LinkedHashMap<Long, Genre> ordered = new java.util.LinkedHashMap<>();
        for (Genre g : currentMovieGenres(v)) {
            if (!removeSet.contains(g.id)) {
                ordered.putIfAbsent(g.id, g);
            }
        }
        for (Map.Entry<Long, Genre> e : addGenres.entrySet()) {
            ordered.putIfAbsent(e.getKey(), e.getValue());
        }
        if (ordered.isEmpty()) {
            LOG.debug("bulkAssignGenres: skipping video {} to avoid leaving it genre-empty", videoId);
            return false;
        }
        saveMovieGenreOrder(v, new ArrayList<>(ordered.values()));
        return true;
    }

    private boolean applySeriesGenres(Long seriesId, Map<Long, Genre> addGenres, java.util.Set<Long> removeSet) {
        Series s = Series.findById(seriesId);
        if (s == null) {
            return false;
        }
        Map<Long, String> idToName = new HashMap<>();
        for (Genre g : Genre.<Genre>listAll()) {
            if (g.id != null && g.name != null) {
                idToName.put(g.id, g.name);
            }
        }
        List<String> current = s.genres != null ? new ArrayList<>(s.genres) : new ArrayList<>();
        java.util.Set<String> removeNames = new java.util.HashSet<>();
        for (Long gid : removeSet) {
            String n = idToName.get(gid);
            if (n != null) {
                removeNames.add(n.toLowerCase());
            }
        }
        List<String> result = new ArrayList<>();
        for (String n : current) {
            if (n == null || n.isBlank()) {
                continue;
            }
            if (removeNames.contains(n.strip().toLowerCase())) {
                continue;
            }
            boolean dup = false;
            for (String kept : result) {
                if (kept.equalsIgnoreCase(n.strip())) {
                    dup = true;
                    break;
                }
            }
            if (!dup) {
                result.add(n.strip());
            }
        }
        for (Genre g : addGenres.values()) {
            if (g.name == null || g.name.isBlank()) {
                continue;
            }
            boolean dup = false;
            for (String kept : result) {
                if (kept.equalsIgnoreCase(g.name.strip())) {
                    dup = true;
                    break;
                }
            }
            if (!dup) {
                result.add(g.name.strip());
            }
        }
        if (result.isEmpty()) {
            LOG.debug("bulkAssignGenres: skipping series {} to avoid leaving it genre-empty", seriesId);
            return false;
        }
        s.genres = result;
        s.persist();
        return true;
    }

    private List<Long> toLongList(Object value) {
        List<Long> out = new ArrayList<>();
        if (value instanceof List) {
            for (Object o : (List<?>) value) {
                if (o instanceof Number) {
                    out.add(((Number) o).longValue());
                } else if (o != null) {
                    try {
                        out.add(Long.parseLong(String.valueOf(o)));
                    } catch (NumberFormatException e) {
                        LOG.debug("Ignoring non-numeric id: {}", o);
                    }
                }
            }
        }
        return out;
    }

    private Map<String, Object> genreToMap(Genre g) {
        Map<String, Object> item = new HashMap<>();
        item.put("id", g.id);
        item.put("name", g.name);
        item.put("color", g.color);
        item.put("icon", g.icon);
        item.put("sortOrder", g.sortOrder);
        item.put("isActive", g.isActive);
        return item;
    }
}
