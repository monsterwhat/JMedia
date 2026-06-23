(function(window) {
    'use strict';

    const JMedia = window.JMedia = window.JMedia || {};

    class App {
        constructor() {
            this.routes = {
                '/': 'music', '/music': 'music', '/video': 'video',
                '/settings': 'settings', '/import': 'import'
            };
            this.currentView = null;
        }

        async init() {
            window.addEventListener('popstate', () => this.handleRoute());

            const layout = document.getElementById('standard-layout');
            if (layout && localStorage.getItem('sidebarCollapsed') === 'true') {
                layout.classList.add('collapsed');
            }

            await this.applySidebarPref();
            await this.checkAdmin();
            this.handleRoute();
        }

        async checkAdmin() {
            try {
                const res = await fetch('/api/auth/is-admin');
                const json = await res.json();
                const isAdmin = json.data && json.data.isAdmin;
                document.querySelectorAll('.admin-only').forEach(el => {
                    el.style.display = isAdmin ? (el.classList.contains('nav-item') ? 'flex' : 'block') : 'none';
                });
            } catch (e) {
                console.error('[App] Failed to check admin status:', e);
                document.querySelectorAll('.admin-only').forEach(el => el.style.display = 'none');
            }
        }

        async applySidebarPref() {
            try {
                const profileId = JMedia.Helpers.getActiveProfileId();
                const res = await fetch(`/api/settings/${profileId}/sidebar-position`);
                if (!res.ok) return;
                const json = await res.json();
                if (json && json.data) {
                    const layout = document.getElementById('standard-layout');
                    if (layout) {
                        if (json.data === 'right') layout.classList.add('sidebar-right');
                        else layout.classList.remove('sidebar-right');
                    }
                }
            } catch (e) {
                console.error('[App] Failed to load sidebar preference:', e);
            }
        }

        navigate(path) {
            if (window.location.pathname === path && !path.includes('?')) {
                if (path === '/video' && window.videoSPA) {
                    window.videoSPA.goHome();
                } else if (path === '/' && window.loadMobilePlaylistSongs) {
                    window.loadMobilePlaylistSongs(0);
                    history.pushState(null, null, '/');
                } else {
                    this.handleRoute();
                }
                return;
            }
            history.pushState(null, null, path);
            this.handleRoute();
        }

        handleRoute() {
            const path = window.location.pathname;
            let viewName = this.routes[path] || 'music';
            if (path.startsWith('/video')) viewName = 'video';
            if (path.startsWith('/settings')) viewName = 'settings';
            if (path.startsWith('/import')) viewName = 'import';
            this.loadView(viewName);
        }

        async loadView(viewName) {
            if (this.currentView === viewName) {
                if (viewName === 'video') {
                    const urlParams = new URLSearchParams(window.location.search);
                    const section = urlParams.get('section') || 'home';
                    const params = {};
                    urlParams.forEach((v, k) => { if (k !== 'section') params[k] = v; });
                    if (window.videoSPA) window.videoSPA.switchSection(section, params, true);
                }
                return;
            }

            if (this.currentView === 'video' && viewName !== 'video') {
                sessionStorage.setItem('videoSuppressAutoResume', 'true');
                if (window.videoSPA && typeof window.videoSPA.destroyCurrentPlayer === 'function') {
                    window.videoSPA.destroyCurrentPlayer();
                }
            }

            const container = document.getElementById('app-content');
            container.innerHTML = '<div class="has-text-centered p-6" style="margin-top: 100px;"><i class="pi pi-spin pi-spinner" style="font-size: 3rem; color: #48c774;"></i></div>';

            try {
                const response = await fetch(`/views/${viewName}.html`);
                if (!response.ok) throw new Error(`View not found: ${viewName}`);
                const html = await response.text();

                document.body.className = `${viewName}-page`;
                container.innerHTML = html;
                this.currentView = viewName;

                const isVideoPage = viewName === 'video';
                const musicPlayer = document.querySelector('.persistent-music-player') ||
                                   document.querySelector('.mobile-player') ||
                                   document.getElementById('musicPlayerContainer');

                if (isVideoPage) {
                    window.videoPlaying = true;
                    document.body.classList.add('video-active');
                    document.body.setAttribute('data-video-active', 'true');

                    if (musicPlayer) {
                        musicPlayer.style.setProperty('display', 'none', 'important');
                        musicPlayer.classList.add('video-active');
                    }

                    const audioElements = document.querySelectorAll('audio');
                    const wasPlaying = Array.from(audioElements).some(a => !a.paused);
                    window.musicWasPlayingBeforeVideo = wasPlaying;
                    audioElements.forEach(a => a.pause());

                    await JMedia.PlaybackApi.pause();
                } else {
                    window.videoPlaying = false;
                    document.body.classList.remove('video-active');
                    document.body.setAttribute('data-video-active', 'false');

                    if (musicPlayer) {
                        musicPlayer.style.removeProperty('display');
                        musicPlayer.classList.remove('video-playing', 'video-active');
                    }

                    if (window.musicWasPlayingBeforeVideo === true) {
                        await JMedia.PlaybackApi.play();
                        setTimeout(() => {
                            if (window.AudioEngine && typeof window.AudioEngine.play === 'function' && window.AudioEngine.isPaused()) {
                                window.AudioEngine.play().catch(() => {});
                            }
                        }, 300);
                        window.musicWasPlayingBeforeVideo = false;
                    }
                }

                this.updateSidebar(viewName);
                this.executeScripts(container);
                if (window.htmx) htmx.process(container);

                if (viewName === 'video' && window.videoSPA) window.videoSPA.init();
                if (viewName === 'import' && window.initImportView) window.initImportView();
                if (viewName === 'music') {
                    if (window.loadMobilePlaylists) window.loadMobilePlaylists();
                    if (window.initEventBindings) window.initEventBindings();
                    const urlParams = new URLSearchParams(window.location.search);
                    const tab = urlParams.get('tab');
                    if (tab && window.switchToTab) {
                        window.switchToTab(tab);
                    } else if (window.loadMobilePlaylistSongs) {
                        window.loadMobilePlaylistSongs(0);
                    }
                }
                if (viewName === 'settings' && typeof window.initSettingsView === 'function') window.initSettingsView();
                if (viewName === 'settings' && typeof window.initVideoSettingsView === 'function') window.initVideoSettingsView();

            } catch (error) {
                console.error('Failed to load view:', error);
                container.innerHTML = `<div class="notification is-danger">Failed to load view: ${error.message}</div>`;
            }
        }

        executeScripts(container) {
            const scripts = container.querySelectorAll('script');
            scripts.forEach(script => {
                const newScript = document.createElement('script');
                if (script.src) newScript.src = script.src;
                else newScript.textContent = script.textContent;
                document.head.appendChild(newScript).parentNode.removeChild(newScript);
            });
        }

        updateSidebar(viewName) {
            document.querySelectorAll('.nav-item').forEach(el => el.classList.remove('active'));

            const settingsLibs = document.getElementById('settings-libraries-group');
            const videoGroup = document.getElementById('video-nav-group');
            const videoMusic = document.getElementById('video-music-group');
            const musicGroup = document.getElementById('music-nav-group');
            const personalGroup = document.getElementById('personal-nav-group');
            const musicVideoLink = document.getElementById('music-video-link-group');
            const settingsTabs = document.getElementById('settingsSideTabs');

            const isMusic = (viewName === 'music');
            const isImport = (viewName === 'import');
            const isVideo = (viewName === 'video');
            const isSettings = (viewName === 'settings');

            if (settingsLibs) settingsLibs.style.display = isSettings ? 'block' : 'none';
            if (videoGroup) videoGroup.style.display = isVideo ? 'block' : 'none';
            if (musicGroup) {
                musicGroup.style.display = (isMusic || isImport) ? 'block' : 'none';
                const playlistLabel = musicGroup.querySelector('.nav-label.mt-3');
                const playlistList = document.getElementById('sidebarPlaylistList');
                const createBtn = musicGroup.querySelector('.create-playlist-btn');
                const displayPlaylists = isMusic ? 'block' : 'none';
                if (playlistLabel) playlistLabel.style.display = displayPlaylists;
                if (playlistList) playlistList.style.display = displayPlaylists;
                if (createBtn) createBtn.style.display = displayPlaylists;
            }
            if (personalGroup) {
                personalGroup.style.display = (isMusic || isImport) ? 'block' : 'none';
                const queueItem = document.getElementById('nav-music-queue');
                const historyItem = document.getElementById('nav-music-history');
                const importItem = document.getElementById('nav-import');
                if (queueItem) queueItem.style.display = isImport ? 'none' : 'flex';
                if (historyItem) historyItem.style.display = isImport ? 'none' : 'flex';
                if (importItem) importItem.style.display = 'flex';
            }
            if (musicVideoLink) musicVideoLink.style.display = (isMusic || isImport) ? 'block' : 'none';
            if (videoMusic) videoMusic.style.display = isVideo ? 'block' : 'none';
            if (settingsTabs) settingsTabs.style.display = isSettings ? 'block' : 'none';

            const videoSubNav = document.getElementById('video-sub-nav');
            if (videoSubNav) videoSubNav.style.display = isVideo ? 'block' : 'none';

            if (viewName === 'music') document.getElementById('nav-music')?.classList.add('active');
            if (viewName === 'video') {
                const urlParams = new URLSearchParams(window.location.search);
                const section = urlParams.get('section') || 'home';
                const sidebarItems = ['movies', 'shows', 'history', 'watchlist', 'manage', 'adminHistory'];
                if (sidebarItems.includes(section)) {
                    const id = section === 'history' || section === 'watchlist' ? `nav-video-${section}` : `nav-${section}`;
                    document.getElementById(id)?.classList.add('active');
                } else if (section === 'home') {
                    document.getElementById('nav-home')?.classList.add('active');
                }
            }
            if (viewName === 'import') document.getElementById('nav-import')?.classList.add('active');
            if (viewName === 'settings') document.getElementById('nav-settings')?.classList.add('active');
        }
    }

    JMedia.App = new App();
    window.app = JMedia.App;

    document.addEventListener('DOMContentLoaded', () => {
        JMedia.App.init();
    });

})(window);
