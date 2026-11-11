/**
 * Shareable video URLs — single source of truth for building and parsing them.
 *
 *   /video                                  the cinema home
 *   /video/info/{id}                        detail modal (series or movie)
 *   /video/info/{kind}/{id}                 explicit: imdb | tmdb | tvdb | db
 *   /video/watch/{id}                       movie player
 *   /video/watch/{id}/s01e02                episode player
 *   /video/watch/{id}/1/2                   season/episode alias
 *
 * {id} is either tt… (IMDb), digits (TMDb), tvdb/N or dbN (internal row id). The
 * server resolves it (GET /api/video/resolve/…) and returns the canonical form,
 * so links written by the app stay identical to links typed by hand.
 */
(function (window) {
    'use strict';

    var KIND_RE = /^(imdb|tmdb|tvdb|db)$/i;
    var PATH_RE = /^\/video(?:\/(info|watch)(?:\/(.*))?)?\/?$/i;
    var SEASON_EPISODE_RE = /^s(\d{1,2})e(\d{1,3})$/i;

    function pad(n) {
        return (n < 10 ? '0' : '') + n;
    }

    /** Best available id token for a series/video payload. */
    function tokenFor(obj) {
        if (!obj) return null;
        if (typeof obj === 'string') return obj;
        var imdb = obj.imdbId || obj.imdb_id;
        if (imdb && /^tt\d+/i.test(String(imdb))) return String(imdb).toLowerCase();
        var tmdb = obj.tmdbId || obj.tmdb_id;
        if (tmdb) return String(tmdb);
        var tvdb = obj.tvdbId || obj.tvdb_id;
        if (tvdb) return 'tvdb/' + tvdb;
        if (obj.id) return 'db' + obj.id;
        return null;
    }

    function infoUrl(token) {
        return token ? '/video/info/' + token : '/video';
    }

    function watchUrl(token, season, episode) {
        if (!token) return '/video';
        var url = '/video/watch/' + token;
        if (season != null && episode != null) {
            url += '/s' + pad(Number(season)) + 'e' + pad(Number(episode));
        }
        return url;
    }

    /**
     * @returns {{area: ?string, section: string, params: Object, kind: ?string,
     *            token: ?string, season: ?number, episode: ?number}}
     */
    function parse(pathname, search) {
        var path = pathname != null ? pathname : (window.location.pathname || '');
        var qs = search != null ? search : (window.location.search || '');
        var m = PATH_RE.exec(path);
        var query = new URLSearchParams(qs);

        if (!m || !m[1]) {
            var params = {};
            query.forEach(function (v, k) { if (k !== 'section') params[k] = v; });
            return {
                area: null,
                section: query.get('section') || 'home',
                params: params,
                kind: null, token: null, season: null, episode: null, mediaType: null
            };
        }

        var area = m[1].toLowerCase();
        var rest = (m[2] || '').split('/').filter(Boolean);
        var kind = null;
        var token = null;
        var season = null;
        var episode = null;

        if (rest.length && KIND_RE.test(rest[0])) {
            kind = rest.shift().toLowerCase();
        }
        token = rest.shift() || null;

        if (area === 'watch' && rest.length) {
            var joined = rest.join('/');
            var se = SEASON_EPISODE_RE.exec(joined);
            if (se) {
                season = parseInt(se[1], 10);
                episode = parseInt(se[2], 10);
            } else if (rest.length >= 2) {
                season = parseInt(rest[0], 10);
                episode = parseInt(rest[1], 10);
            }
        }

        return {
            area: area,
            section: area === 'watch' ? 'playback' : 'details',
            params: {},
            kind: kind,
            token: token,
            season: (season != null && !isNaN(season)) ? season : null,
            episode: (episode != null && !isNaN(episode)) ? episode : null,
            mediaType: query.get('type') || null
        };
    }

    /** True when the current URL is a deep link (so the app must not push it again). */
    function isDeepLink(parsed) {
        return !!(parsed && parsed.area && parsed.token);
    }

    /** Resolve a parsed link against the library. Resolves to null on any failure. */
    async function resolve(parsed) {
        if (!parsed || !parsed.token) return null;
        var url = '/api/video/resolve/';
        if (parsed.kind) {
            url += encodeURIComponent(parsed.kind) + '/' + encodeURIComponent(parsed.token);
        } else {
            url += encodeURIComponent(parsed.token);
        }
        var qs = [];
        if (parsed.season != null && parsed.episode != null) {
            qs.push('season=' + encodeURIComponent(parsed.season));
            qs.push('episode=' + encodeURIComponent(parsed.episode));
        }
        if (parsed.mediaType) {
            qs.push('type=' + encodeURIComponent(parsed.mediaType));
        }
        if (qs.length) url += '?' + qs.join('&');
        try {
            var res = await fetch(url, { credentials: 'include' });
            if (!res.ok) return null;
            var json = await res.json();
            // Return found:false payloads too: they may carry `meta` so the caller can
            // render the "not in your library" page instead of a dead end.
            return json || null;
        } catch (e) {
            console.warn('[VideoRoutes] resolve failed', e);
            return null;
        }
    }

    window.VideoRoutes = {
        tokenFor: tokenFor,
        infoUrl: infoUrl,
        watchUrl: watchUrl,
        parse: parse,
        isDeepLink: isDeepLink,
        resolve: resolve
    };
})(window);
