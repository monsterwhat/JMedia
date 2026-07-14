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

            this.applyCachedSidebarPref();
            this.applySidebarPref().catch(e => console.error('[App] Failed to load sidebar preference:', e));

            await this.checkAdmin();
            this.handleRoute();
        }

        applyCachedSidebarPref() {
            const layout = document.getElementById('standard-layout');
            if (!layout) return;
            const cached = localStorage.getItem('sidebarPosition');
            if (cached === 'right') layout.classList.add('sidebar-right');
            else if (cached === 'left') layout.classList.remove('sidebar-right');
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
                    localStorage.setItem('sidebarPosition', json.data);
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

            container.innerHTML = this.getViewSkeleton(viewName);

            try {
                const response = await fetch(`/views/${viewName}.html`);
                if (!response.ok) throw new Error(`View not found: ${viewName}`);
                const html = await response.text();

                document.body.className = `${viewName}-page`;
                container.innerHTML = html;
                this.currentView = viewName;

                const viewLabels = { music: 'Music', video: 'Video', settings: 'Settings', import: 'Import' };
                if (window.Breadcrumbs) window.Breadcrumbs.set([viewLabels[viewName] || viewName]);

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

        getViewSkeleton(viewName) {
            var skeletons = {
                music: '<div class="skeleton-shell"><div class="field has-addons m-2"><div class="control is-expanded"><div class="skeleton-block is-skeleton" style="height:2em;border-radius:999px;"></div></div><div class="control"><div class="skeleton-block is-skeleton" style="width:60px;height:2em;border-radius:999px;"></div></div></div><div class="is-flex is-flex-wrap-wrap px-3 py-2" style="gap:0.75rem;"><div style="flex:0 0 auto;width:calc(33.33% - 0.5rem);"><div class="card is-skeleton" style="aspect-ratio:1;border-radius:8px;"></div><div class="skeleton-lines mt-2"><div></div></div></div><div style="flex:0 0 auto;width:calc(33.33% - 0.5rem);"><div class="card is-skeleton" style="aspect-ratio:1;border-radius:8px;"></div><div class="skeleton-lines mt-2"><div></div></div></div><div style="flex:0 0 auto;width:calc(33.33% - 0.5rem);"><div class="card is-skeleton" style="aspect-ratio:1;border-radius:8px;"></div><div class="skeleton-lines mt-2"><div></div></div></div><div style="flex:0 0 auto;width:calc(33.33% - 0.5rem);"><div class="card is-skeleton" style="aspect-ratio:1;border-radius:8px;"></div><div class="skeleton-lines mt-2"><div></div></div></div><div style="flex:0 0 auto;width:calc(33.33% - 0.5rem);"><div class="card is-skeleton" style="aspect-ratio:1;border-radius:8px;"></div><div class="skeleton-lines mt-2"><div></div></div></div><div style="flex:0 0 auto;width:calc(33.33% - 0.5rem);"><div class="card is-skeleton" style="aspect-ratio:1;border-radius:8px;"></div><div class="skeleton-lines mt-2"><div></div></div></div></div></div>',
                video: '<div class="skeleton-shell">' +
                    '<div class="mb-5 px-4"><div class="level is-mobile mb-3"><div class="level-left"><div class="level-item"><div class="is-flex is-align-items-center" style="gap:0.75rem;"><div class="card is-skeleton" style="width:28px;height:28px;border-radius:6px;"></div><div class="skeleton-lines" style="width:200px;"><div></div></div></div></div></div><div class="level-right"><div class="level-item"><div class="buttons are-small"><button class="button is-skeleton is-rounded" style="width:40px;height:40px;border:none;"></button><button class="button is-skeleton is-rounded" style="width:40px;height:40px;border:none;"></button></div></div></div></div><div class="is-flex" style="gap:1rem;overflow:hidden;"><div class="card is-skeleton" style="width:200px;flex-shrink:0;border-radius:12px;"><div class="card-image" style="height:300px;"></div><div class="card-content" style="padding:1rem;"><div class="tags mb-2"><span class="tag is-skeleton" style="width:40px;height:18px;border:none;"></span></div><div class="skeleton-lines"><div></div><div></div></div></div></div><div class="card is-skeleton" style="width:200px;flex-shrink:0;border-radius:12px;"><div class="card-image" style="height:300px;"></div><div class="card-content" style="padding:1rem;"><div class="tags mb-2"><span class="tag is-skeleton" style="width:40px;height:18px;border:none;"></span></div><div class="skeleton-lines"><div></div><div></div></div></div></div><div class="card is-skeleton" style="width:200px;flex-shrink:0;border-radius:12px;"><div class="card-image" style="height:300px;"></div><div class="card-content" style="padding:1rem;"><div class="tags mb-2"><span class="tag is-skeleton" style="width:40px;height:18px;border:none;"></span></div><div class="skeleton-lines"><div></div><div></div></div></div></div><div class="card is-skeleton" style="width:200px;flex-shrink:0;border-radius:12px;"><div class="card-image" style="height:300px;"></div><div class="card-content" style="padding:1rem;"><div class="tags mb-2"><span class="tag is-skeleton" style="width:40px;height:18px;border:none;"></span></div><div class="skeleton-lines"><div></div><div></div></div></div></div><div class="card is-skeleton" style="width:200px;flex-shrink:0;border-radius:12px;"><div class="card-image" style="height:300px;"></div><div class="card-content" style="padding:1rem;"><div class="tags mb-2"><span class="tag is-skeleton" style="width:40px;height:18px;border:none;"></span></div><div class="skeleton-lines"><div></div><div></div></div></div></div></div></div>' +
                    '<div class="mb-5 px-4"><div class="level is-mobile mb-3"><div class="level-left"><div class="level-item"><div class="is-flex is-align-items-center" style="gap:0.75rem;"><div class="card is-skeleton" style="width:28px;height:28px;border-radius:6px;"></div><div class="skeleton-lines" style="width:240px;"><div></div></div></div></div></div><div class="level-right"><div class="level-item"><div class="buttons are-small"><button class="button is-skeleton is-rounded" style="width:40px;height:40px;border:none;"></button><button class="button is-skeleton is-rounded" style="width:40px;height:40px;border:none;"></button></div></div></div></div><div class="is-flex" style="gap:1rem;overflow:hidden;"><div class="card is-skeleton" style="width:200px;flex-shrink:0;border-radius:12px;"><div class="card-image" style="height:300px;"></div><div class="card-content" style="padding:1rem;"><div class="tags mb-2"><span class="tag is-skeleton" style="width:40px;height:18px;border:none;"></span></div><div class="skeleton-lines"><div></div><div></div></div></div></div><div class="card is-skeleton" style="width:200px;flex-shrink:0;border-radius:12px;"><div class="card-image" style="height:300px;"></div><div class="card-content" style="padding:1rem;"><div class="tags mb-2"><span class="tag is-skeleton" style="width:40px;height:18px;border:none;"></span></div><div class="skeleton-lines"><div></div><div></div></div></div></div><div class="card is-skeleton" style="width:200px;flex-shrink:0;border-radius:12px;"><div class="card-image" style="height:300px;"></div><div class="card-content" style="padding:1rem;"><div class="tags mb-2"><span class="tag is-skeleton" style="width:40px;height:18px;border:none;"></span></div><div class="skeleton-lines"><div></div><div></div></div></div></div><div class="card is-skeleton" style="width:200px;flex-shrink:0;border-radius:12px;"><div class="card-image" style="height:300px;"></div><div class="card-content" style="padding:1rem;"><div class="tags mb-2"><span class="tag is-skeleton" style="width:40px;height:18px;border:none;"></span></div><div class="skeleton-lines"><div></div><div></div></div></div></div><div class="card is-skeleton" style="width:200px;flex-shrink:0;border-radius:12px;"><div class="card-image" style="height:300px;"></div><div class="card-content" style="padding:1rem;"><div class="tags mb-2"><span class="tag is-skeleton" style="width:40px;height:18px;border:none;"></span></div><div class="skeleton-lines"><div></div><div></div></div></div></div></div></div>' +
                    '<div class="mb-5 px-4"><div class="level is-mobile mb-3"><div class="level-left"><div class="level-item"><div class="is-flex is-align-items-center" style="gap:0.75rem;"><div class="card is-skeleton" style="width:28px;height:28px;border-radius:6px;"></div><div class="skeleton-lines" style="width:180px;"><div></div></div></div></div></div><div class="level-right"><div class="level-item"><div class="buttons are-small"><button class="button is-skeleton is-rounded" style="width:40px;height:40px;border:none;"></button><button class="button is-skeleton is-rounded" style="width:40px;height:40px;border:none;"></button></div></div></div></div><div class="is-flex" style="gap:1rem;overflow:hidden;"><div class="card is-skeleton" style="width:200px;flex-shrink:0;border-radius:12px;"><div class="card-image" style="height:300px;"></div><div class="card-content" style="padding:1rem;"><div class="tags mb-2"><span class="tag is-skeleton" style="width:40px;height:18px;border:none;"></span></div><div class="skeleton-lines"><div></div><div></div></div></div></div><div class="card is-skeleton" style="width:200px;flex-shrink:0;border-radius:12px;"><div class="card-image" style="height:300px;"></div><div class="card-content" style="padding:1rem;"><div class="tags mb-2"><span class="tag is-skeleton" style="width:40px;height:18px;border:none;"></span></div><div class="skeleton-lines"><div></div><div></div></div></div></div><div class="card is-skeleton" style="width:200px;flex-shrink:0;border-radius:12px;"><div class="card-image" style="height:300px;"></div><div class="card-content" style="padding:1rem;"><div class="tags mb-2"><span class="tag is-skeleton" style="width:40px;height:18px;border:none;"></span></div><div class="skeleton-lines"><div></div><div></div></div></div></div><div class="card is-skeleton" style="width:200px;flex-shrink:0;border-radius:12px;"><div class="card-image" style="height:300px;"></div><div class="card-content" style="padding:1rem;"><div class="tags mb-2"><span class="tag is-skeleton" style="width:40px;height:18px;border:none;"></span></div><div class="skeleton-lines"><div></div><div></div></div></div></div><div class="card is-skeleton" style="width:200px;flex-shrink:0;border-radius:12px;"><div class="card-image" style="height:300px;"></div><div class="card-content" style="padding:1rem;"><div class="tags mb-2"><span class="tag is-skeleton" style="width:40px;height:18px;border:none;"></span></div><div class="skeleton-lines"><div></div><div></div></div></div></div></div></div>' +
                    '</div>',
                settings: '<div class="skeleton-shell" style="max-width:600px;margin:2rem auto;padding:1.5rem;"><div class="skeleton-block" style="height:2rem;width:40%;margin-bottom:1.5rem;"></div><div class="skeleton-lines mb-3"><div></div><div></div><div></div></div><div class="skeleton-block" style="height:3rem;width:100%;margin-bottom:1rem;"></div><div class="skeleton-lines mb-3"><div></div><div></div><div></div></div><div class="skeleton-block" style="height:3rem;width:100%;margin-bottom:1rem;"></div><div class="skeleton-lines mb-3"><div></div><div></div></div><div class="skeleton-block" style="height:3rem;width:200px;margin-top:1rem;"></div></div>',
                import: '<div class="skeleton-shell" style="display:grid;grid-template-columns:repeat(auto-fill,minmax(280px,1fr));gap:1rem;padding:1.5rem;"><div><div class="skeleton-block" style="height:180px;border-radius:8px;"></div><div class="skeleton-lines mt-2"><div></div><div></div></div></div><div><div class="skeleton-block" style="height:180px;border-radius:8px;"></div><div class="skeleton-lines mt-2"><div></div><div></div></div></div><div><div class="skeleton-block" style="height:180px;border-radius:8px;"></div><div class="skeleton-lines mt-2"><div></div><div></div></div></div></div>'
            };
            return skeletons[viewName] || '<div class="has-text-centered p-6" style="margin-top:100px;"><i class="pi pi-spin pi-spinner" style="font-size:3rem;color:#48c774;"></i></div>';
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
