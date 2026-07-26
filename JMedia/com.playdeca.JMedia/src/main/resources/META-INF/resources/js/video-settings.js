window.initVideoSettingsView = function() {
    console.log("[VideoSettings] Initializing...");
    
    const browseVideoFolderBtn = document.getElementById("browseVideoFolderBtn");
    if (browseVideoFolderBtn) {
        browseVideoFolderBtn.onclick = async () => {
            try {
                const res = await fetch(`/api/settings/${window.globalActiveProfileId}/browse-video-folder`);
                if (res.status === 204) return;
                if (!res.ok) throw new Error(`HTTP ${res.status}`);
                
                const json = await res.json();
                if (json.data) {
                    const input = document.getElementById("videoLibraryPathInput");
                    if (input) input.value = json.data;
                }
            } catch (error) {
                console.error("[Settings] Failed to browse video folder:", error);
                Toast.error("Failed to browse video folder");
            }
        };
    }

    const saveVideoLibraryPathBtn = document.getElementById("saveVideoLibraryPathBtn");
    if (saveVideoLibraryPathBtn) {
        saveVideoLibraryPathBtn.addEventListener('htmx:configRequest', function(evt) {
            const tmdbApiKey = document.getElementById('tmdbApiKeyInput')?.value;
            if (tmdbApiKey) evt.detail.parameters['tmdbApiKey'] = tmdbApiKey;
        });
        saveVideoLibraryPathBtn.addEventListener('htmx:afterRequest', function(evt) {
            if (evt.detail.successful) Toast.success("Video settings saved");
            else Toast.error("Failed to save video settings");
        });
    }

    const saveTmdbApiKeyBtn = document.getElementById("saveTmdbApiKeyBtn");
    if (saveTmdbApiKeyBtn) {
        saveTmdbApiKeyBtn.onclick = async () => {
            const apiKey = document.getElementById('tmdbApiKeyInput')?.value?.trim() || '';
            try {
                const res = await fetch('/api/settings/tmdb-api-key', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
                    body: 'tmdbApiKey=' + encodeURIComponent(apiKey)
                });
                const json = await res.json();
                if (json.success !== false) Toast.success("TMDB API key saved");
                else Toast.error("Failed to save API key");
            } catch (err) {
                Toast.error("Failed to save API key");
            }
        };
    }

    const scanVideoLibraryBtn = document.getElementById("scanVideoLibrary");
    if (scanVideoLibraryBtn) {
        scanVideoLibraryBtn.addEventListener('htmx:afterRequest', function (evt) {
            if (evt.detail.successful) {
                Toast.success("Video scan started");
                startScanPolling();
            } else {
                Toast.error("Failed to start scan");
            }
        });
    }
    
    const reloadVideoMetadataBtn = document.getElementById("reloadVideoMetadata");
    if (reloadVideoMetadataBtn) {
        reloadVideoMetadataBtn.addEventListener('htmx:afterRequest', function (evt) {
            if (evt.detail.successful) {
                Toast.success("Metadata reload started");
                startScanPolling();
            } else {
                Toast.error("Failed to start reload");
            }
        });
    }

    function startScanPolling() {
        if (window.scanPollingInterval) clearInterval(window.scanPollingInterval);
        
        // Initial progress toast
        Toast.progress("Initializing scan...", 0);
        
        let idleCount = 0;
        window.scanPollingInterval = setInterval(async () => {
            try {
                const res = await fetch("/api/video/scan-status");
                if (!res.ok) throw new Error("Status fetch failed");
                
                const json = await res.json();
                const progress = json.data;
                
                if (progress && progress.isRunning) {
                    idleCount = 0;
                    const percent = progress.total > 0 ? Math.round((progress.current / progress.total) * 100) : 0;
                    Toast.progress(`Scanning: ${progress.current} / ${progress.total}`, percent);
                } else {
                    idleCount++;
                    // If idle for 3 checks (approx 6-9 seconds), stop polling
                    if (idleCount >= 3) {
                        clearInterval(window.scanPollingInterval);
                        Toast.progress("Scan complete", 100);
                    }
                }
            } catch (error) {
                console.error("[ScanPolling] Error:", error);
                idleCount++;
                if (idleCount >= 5) clearInterval(window.scanPollingInterval);
            }
        }, 3000);
    }

    const resetVideoDbBtn = document.getElementById("resetVideoDb");
    if (resetVideoDbBtn) {
        resetVideoDbBtn.onclick = async () => {
            if (confirm("Reset video database? This cannot be undone.")) {
                try {
                    const res = await fetch("/api/video/reset-database", { method: "POST" });
                    if (res.ok) Toast.success("Video database reset");
                    else Toast.error("Failed to reset database");
                } catch (error) {
                    Toast.error("Error resetting database");
                }
            }
        };
    }

    // Metadata Toggles
    const metadataToggles = ['tmdbEnabledToggle', 'omdbEnabledToggle', 'tvmazeEnabledToggle', 'imdbDevEnabledToggle', 'introDbEnabledToggle'];
    const toggleFieldMap = {
        'tmdbEnabledToggle': 'tmdbEnabled',
        'omdbEnabledToggle': 'omdbEnabled',
        'tvmazeEnabledToggle': 'tvmazeEnabled',
        'imdbDevEnabledToggle': 'imdbDevEnabled',
        'introDbEnabledToggle': 'introDbEnabled'
    };

    // Load current toggle states
    fetch('/api/settings/metadata-toggles')
        .then(function(r) { return r.json(); })
        .then(function(json) {
            if (json.data) {
                metadataToggles.forEach(function(id) {
                    var el = document.getElementById(id);
                    var field = toggleFieldMap[id];
                    if (el && json.data[field] !== undefined) {
                        el.checked = json.data[field];
                    }
                });
            }
        })
        .catch(function(err) {
            console.warn('[VideoSettings] Failed to load metadata toggles:', err);
        });

    // Save metadata toggles when the video library save button is clicked
    var saveVideoBtn = document.getElementById('saveVideoLibraryPathBtn');
    if (saveVideoBtn) {
        saveVideoBtn.addEventListener('htmx:afterRequest', function(evt) {
            if (evt.detail.successful) {
                var toggles = {};
                metadataToggles.forEach(function(id) {
                    var el = document.getElementById(id);
                    var field = toggleFieldMap[id];
                    if (el) toggles[field] = el.checked;
                });
                fetch('/api/settings/metadata-toggles', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify(toggles)
                }).catch(function(err) {
                    console.error('[VideoSettings] Failed to save metadata toggles:', err);
                });
            }
        });
    }

    // Default Player selector
    const defaultPlayerSelect = document.getElementById("defaultPlayerSelect");
    if (defaultPlayerSelect) {
        // Load current value
        fetch(`/api/settings/${window.globalActiveProfileId}/default-player`)
            .then(function(r) { return r.json(); })
            .then(function(json) {
                if (json.data) {
                    defaultPlayerSelect.value = json.data;
                }
            })
            .catch(function(err) {
                console.warn("[VideoSettings] Failed to load default player:", err);
            });

        // Save on change
        defaultPlayerSelect.addEventListener("change", function() {
            var player = this.value;
            fetch(`/api/settings/${window.globalActiveProfileId}/default-player`, {
                method: "POST",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify({ defaultPlayer: player })
            })
            .then(function(r) { return r.json(); })
            .then(function(json) {
                if (json.success !== false) {
                    Toast.success("Default player set to " + player);
                } else {
                    Toast.error("Failed to update player");
                }
            })
            .catch(function(err) {
                console.error("[VideoSettings] Failed to save default player:", err);
                Toast.error("Failed to save player preference");
            });
        });
    }
};
