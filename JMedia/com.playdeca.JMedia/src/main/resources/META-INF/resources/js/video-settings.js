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
};
