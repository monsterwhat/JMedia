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
                    Toast.info("No folder selected");
                }
            } catch (error) {
                console.error("[Settings] Failed to browse video folder:", error);
                Toast.error("Failed to browse video folder: " + error.message);
            }
        };
    }

    const saveVideoLibraryPathBtn = document.getElementById("saveVideoLibraryPathBtn");
    if(saveVideoLibraryPathBtn) {
        saveVideoLibraryPathBtn.addEventListener('htmx:afterRequest', function(evt) {
            if (evt.detail.successful) {
                Toast.success("Video library path saved successfully");
            } else {
                Toast.error("Failed to save video library path");
            }
        });
    }

    const scanVideoLibraryBtn = document.getElementById("scanVideoLibrary");
    if (scanVideoLibraryBtn) {
        scanVideoLibraryBtn.addEventListener('htmx:afterRequest', function (evt) {
            if (evt.detail.successful) {
                Toast.success("Video library scan started");
            } else {
                Toast.error("Failed to start video library scan");
            }
        });
    }
    
    const reloadVideoMetadataBtn = document.getElementById("reloadVideoMetadata");
    if (reloadVideoMetadataBtn) {
        reloadVideoMetadataBtn.addEventListener('htmx:afterRequest', function (evt) {
            if (evt.detail.successful) {
                Toast.success("Video metadata reload started");
            } else {
                Toast.error("Failed to start video metadata reload");
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
                        Toast.success(result.data || "Video database reset successfully");
                    } else {
                        Toast.error("Error resetting video database: " + (result.error || "Unknown error"));
                    }
                } catch (error) {
                    Toast.error("An unexpected error occurred while resetting the video database: " + error.message);
                }
            }
        });
    }





});