/**
 * Shareable music URLs - single source of truth for building and parsing them.
 *
 *   /music                      the library (All Music)
 *   /music/playlist/{id}        a playlist            (numeric row id)
 *   /music/song/{id}            a song's detail view  (numeric row id)
 *   /music/album/{id}           an album              (numeric Album row id)
 *   /music/artist/{id}          an artist             (numeric Artist row id)
 *   /music/genre/{key}          a genre               (canonical genre key)
 *
 * Every id is the row id: albums and artists stopped being name-keyed groups,
 * so a link survives a rename and never needs URL-encoding of a display name.
 */
(function (window) {
    'use strict';

    var PATH_RE = /^\/music(?:\/(playlist|song|album|artist|genre)\/([^\/]+))?\/?$/i;
    var KINDS = { playlist: 1, song: 1, album: 1, artist: 1, genre: 1 };

    function parse(pathname) {
        var m = PATH_RE.exec(pathname || '');
        if (!m || !m[1]) {
            return { view: 'all' };
        }
        return { view: m[1].toLowerCase(), id: decodeURIComponent(m[2]) };
    }

    function build(kind, id) {
        if (!KINDS[kind] || id == null || id === '') {
            return '/music';
        }
        return '/music/' + kind + '/' + encodeURIComponent(String(id));
    }

    window.MusicRoutes = {
        parse: parse,
        allUrl: function () { return '/music'; },
        playlistUrl: function (id) { return (Number(id) > 0) ? build('playlist', id) : '/music'; },
        songUrl: function (id) { return build('song', id); },
        albumUrl: function (id) { return build('album', id); },
        artistUrl: function (id) { return build('artist', id); },
        genreUrl: function (key) { return build('genre', key); }
    };
})(window);
