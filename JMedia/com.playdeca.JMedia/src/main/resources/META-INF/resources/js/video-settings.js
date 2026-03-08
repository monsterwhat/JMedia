document.addEventListener("DOMContentLoaded", () => {
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
            if (evt.detail.successful) Toast.success("Video scan started");
            else Toast.error("Failed to start scan");
        });
    }
    
    const reloadVideoMetadataBtn = document.getElementById("reloadVideoMetadata");
    if (reloadVideoMetadataBtn) {
        reloadVideoMetadataBtn.addEventListener('htmx:afterRequest', function (evt) {
            if (evt.detail.successful) Toast.success("Metadata reload started");
            else Toast.error("Failed to start reload");
        });
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

    // Video library toggle functionality
    const toggle = document.getElementById("enableVideoLibraryToggle");
    const options = document.getElementById("videoLibraryOptions");
    
    if (toggle && options) {
        const applyState = (enabled) => {
            if (enabled) options.classList.remove('is-hidden');
            else options.classList.add('is-hidden');
        };

        // Initial state from localStorage
        const isEnabled = localStorage.getItem('videoLibraryEnabled') === 'true';
        toggle.checked = isEnabled;
        applyState(isEnabled);

        toggle.addEventListener('change', function() {
            applyState(this.checked);
            localStorage.setItem('videoLibraryEnabled', this.checked);
            if (this.checked) Toast.success("Video library enabled");
            else Toast.info("Video library disabled");
        });
    }
});
