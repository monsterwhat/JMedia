document.addEventListener("DOMContentLoaded", () => {
    const browseVideoFolderBtn = document.getElementById("browseVideoFolderBtn");
    if (browseVideoFolderBtn) {
        browseVideoFolderBtn.onclick = async () => {
            try {
                const res = await fetch(`/api/settings/${globalActiveProfileId}/browse-video-folder`);
                
                // Handle case where user cancels folder selection (NO_CONTENT status)
                if (res.status === 204) {
                    console.log("[Settings] Video folder selection cancelled by user");
                    return; // Silently return - no notification needed for cancel
                }
                
                if (!res.ok) {
                    throw new Error(`HTTP ${res.status}: ${res.statusText}`);
                }
                
                const json = await res.json();
                if (json.data) {
                    const pathInputElem = document.getElementById("videoLibraryPathInput");
                    if (pathInputElem) {
                        pathInputElem.value = json.data;
                    }
                } else {
                    console.error("[Settings] No data in response:", json);
                    showToast("No folder selected", 'info');
                }
            } catch (error) {
                console.error("[Settings] Failed to browse video folder:", error);
                showToast("Failed to browse video folder: " + error.message, 'error');
            }
        };
    }

    const saveVideoLibraryPathBtn = document.getElementById("saveVideoLibraryPathBtn");
    if(saveVideoLibraryPathBtn) {
        saveVideoLibraryPathBtn.addEventListener('htmx:afterRequest', function(evt) {
            if (evt.detail.successful) {
                showToast("Video library path saved successfully", 'success');
            } else {
                showToast("Failed to save video library path", 'error');
            }
        });
    }

    const scanVideoLibraryBtn = document.getElementById("scanVideoLibrary");
    if (scanVideoLibraryBtn) {
        scanVideoLibraryBtn.addEventListener('htmx:afterRequest', function (evt) {
            if (evt.detail.successful) {
                showToast("Video library scan started", 'success');
            } else {
                showToast("Failed to start video library scan", 'error');
            }
        });
    }
    
    const reloadVideoMetadataBtn = document.getElementById("reloadVideoMetadata");
    if (reloadVideoMetadataBtn) {
        reloadVideoMetadataBtn.addEventListener('htmx:afterRequest', function (evt) {
            if (evt.detail.successful) {
                showToast("Video metadata reload started", 'success');
            } else {
                showToast("Failed to start video metadata reload", 'error');
            }
        });
    }

    const resetVideoDbBtn = document.getElementById("resetVideoDb");
    if (resetVideoDbBtn) {
        resetVideoDbBtn.addEventListener("click", async () => {
            if (confirm("Are you sure you want to reset the video database? This will delete all video, movie, episode, show, and season data from the database. This action cannot be undone.")) {
                try {
                    const response = await fetch("/api/video/reset-database", {
                        method: "POST",
                        headers: {
                            "Content-Type": "application/json"
                        }
                    });
                    const result = await response.json();
                    if (response.ok) {
                        showToast(result.data || "Video database reset successfully", 'success');
                    } else {
                        showToast("Error resetting video database: " + (result.error || "Unknown error"), 'error');
                    }
                } catch (error) {
                    showToast("An unexpected error occurred while resetting the video database: " + error.message, 'error');
                }
            }
        });
    }





});