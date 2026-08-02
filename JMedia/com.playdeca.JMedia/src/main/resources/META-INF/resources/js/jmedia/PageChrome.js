/**
 * PageChrome - browser tab title + favicon management
 *
 * Central owner of the <title id="pageTitle"> element and the
 * <link id="favicon">. Keeps the tab chrome in sync with what the user
 * is looking at:
 *   - Music:  song title + album artwork (driven by musicBar/ImageManager)
 *   - Video:  video title + thumbnail while playing, video-home branding
 *             whenever the video section is shown without an active player
 */
(function(window) {
    'use strict';

    const JMedia = window.JMedia = window.JMedia || {};

    const DEFAULT_FAVICON = '/logo.png';

    const PageChrome = {
        /** Tab title used while browsing the video section. */
        VIDEO_HOME_TITLE: 'Videos - JMedia',
        /** Tab title used on the music home when nothing is playing. */
        MUSIC_HOME_TITLE: 'Music - JMedia',
        DEFAULT_FAVICON: DEFAULT_FAVICON,

        /**
         * Set the browser tab title and favicon.
         * @param {string} title - Tab title (falls back to 'JMedia')
         * @param {string} [faviconUrl] - URL for the favicon (defaults to the JMedia logo)
         */
        set: function(title, faviconUrl) {
            const safeTitle = title || 'JMedia';
            const titleEl = document.getElementById('pageTitle');
            if (titleEl) {
                titleEl.textContent = safeTitle;
                titleEl.title = safeTitle;
            }
            document.title = safeTitle;

            const faviconEl = document.getElementById('favicon');
            if (faviconEl) {
                faviconEl.href = faviconUrl || DEFAULT_FAVICON;
            }
        },

        /**
         * Set the chrome for a video being watched: title + thumbnail favicon.
         * Non-numeric ids (external videos, live channels) have no thumbnail,
         * so they fall back to the JMedia logo.
         * @param {string} title - Video title
         * @param {number|string} videoId - Numeric video id used for the thumbnail URL
         */
        setForVideo: function(title, videoId) {
            const vid = String(videoId ?? '');
            const favicon = /^\d+$/.test(vid) ? `/api/video/thumbnail/${vid}` : DEFAULT_FAVICON;
            this.set(title || 'JMedia', favicon);
        },

        /**
         * Set the chrome for a details view (movie/show modal or SPA details
         * page): title + the JMedia logo favicon. Media logos are not used as
         * favicons - they are designed for large displays and look too big in
         * the tab, so the regular JMedia logo is kept.
         * @param {string} title - Tab title
         */
        setForVideoDetails: function(title) {
            this.set(title || 'JMedia', DEFAULT_FAVICON);
        },

        /** Video-section branding, used whenever no video player is active. */
        setVideoHome: function() {
            this.set(this.VIDEO_HOME_TITLE, DEFAULT_FAVICON);
        },

        /** Music-home branding, used when nothing is playing. */
        setMusicHome: function() {
            this.set(this.MUSIC_HOME_TITLE, DEFAULT_FAVICON);
        }
    };

    JMedia.PageChrome = PageChrome;

    // Global convenience alias for legacy scripts and inline handlers
    window.setPageChrome = function(title, faviconUrl) {
        PageChrome.set(title, faviconUrl);
    };

})(window);
