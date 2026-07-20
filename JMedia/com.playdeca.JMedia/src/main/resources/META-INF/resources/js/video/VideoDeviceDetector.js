(function(window) {
    'use strict';

    class VideoDeviceDetector {
        constructor() {
            this._isMobile = null;
            this._isDesktop = null;
        }

        isMobile() {
            if (this._isMobile !== null) return this._isMobile;

            const ua = navigator.userAgent || '';

            // UA-only detection: screen size is unreliable (desktop narrow windows,
            // tablets with large screens, mobile browsers reporting desktop UA).
            const mobileUA = /android|webos|iphone|ipod|blackberry|iemobile|opera mini/i.test(ua);

            this._isMobile = mobileUA;
            this._isDesktop = !this._isMobile;
            return this._isMobile;
        }

        isDesktop() {
            if (this._isDesktop !== null) return this._isDesktop;
            this.isMobile();
            return this._isDesktop;
        }

        async hasActiveDesktopSession(profileId) {
            try {
                console.log(`[VideoDeviceDetector] Checking for active desktop session, profileId: ${profileId}`);
                const url = profileId ? `/api/video/playback/current?profileId=${encodeURIComponent(profileId)}` : `/api/video/playback/current`;
                const resp = await fetch(url);
                console.log(`[VideoDeviceDetector] Response status: ${resp.status}`);

                if (!resp.ok) {
                    console.log(`[VideoDeviceDetector] Response not OK: ${resp.status} ${resp.statusText}`);
                    return false;
                }

                const data = await resp.json();
                console.log(`[VideoDeviceDetector] Response data:`, data);
                const video = data.video;

                if (!video || !video.currentVideoId) {
                    console.log(`[VideoDeviceDetector] No active session found in response (video: ${video}, currentVideoId: ${video && video.currentVideoId})`);
                    return false;
                }

                const hasActive = data.desktopPlayerOpen === true;
                console.log(`[VideoDeviceDetector] Desktop player page open: ${hasActive}`);
                return hasActive;
            } catch (e) {
                console.error(`[VideoDeviceDetector] Error checking for active desktop session:`, e);
                return false;
            }
        }
    }

    window.VideoDeviceDetector = new VideoDeviceDetector();
})(window);
