document.addEventListener("DOMContentLoaded", () => {
    const browseVideoFolderBtn = document.getElementById("browseVideoFolderBtn");
    if (browseVideoFolderBtn) {
        browseVideoFolderBtn.onclick = async () => {
            const res = await fetch(`/api/settings/${globalActiveProfileId}/browse-video-folder`);
            const json = await res.json();
            if (res.ok && json.data) {
                const pathInputElem = document.getElementById("videoLibraryPathInput");
                if (pathInputElem) {
                    pathInputElem.value = json.data;
                }
            } else {
                console.error("[Settings] Failed to browse video folder:", json.error);
            }
        };
    }

    const saveVideoLibraryPathBtn = document.getElementById("saveVideoLibraryPathBtn");
    if(saveVideoLibraryPathBtn) {
        saveVideoLibraryPathBtn.addEventListener("click", () => {
            const path = document.getElementById("videoLibraryPathInput").value;
            htmx.ajax('POST', `/api/settings/${globalActiveProfileId}/video-library-path`, {
                values: { 'videoLibraryPathInput': path },
                swap: 'none'
            });
        });
    }

    const scanVideoLibraryBtn = document.getElementById("scanVideoLibrary");
    if (scanVideoLibraryBtn) {
        scanVideoLibraryBtn.addEventListener('htmx:afterRequest', function (evt) {
            alert("Video library scan started.");
        });
    }
    
    const reloadVideoMetadataBtn = document.getElementById("reloadVideoMetadata");
    if (reloadVideoMetadataBtn) {
        reloadVideoMetadataBtn.addEventListener('htmx:afterRequest', function (evt) {
            alert("Video metadata reload started.");
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
                        alert(result.data || "Video database reset successfully.");
                    } else {
                        alert("Error resetting video database: " + (result.error || "Unknown error"));
                    }
                } catch (error) {
                    alert("An unexpected error occurred while resetting the video database: " + error.message);
                }
            }
        });
    }
    
    const toggleVideoLibraryManagementBtn = document.getElementById("toggleVideoLibraryManagementBtn");
    if (toggleVideoLibraryManagementBtn) {
        toggleVideoLibraryManagementBtn.onclick = (event) => window.toggleCardContent(event.currentTarget, "videoLibraryManagementContent");
    }

    async function refreshVideoSettingsUI() {
        const res = await fetch(`/api/settings/${globalActiveProfileId}`);
        const json = await res.json();
        if (res.ok && json.data) {
            const pathInputElem = document.getElementById("videoLibraryPathInput");
            if (pathInputElem && json.data.videoLibraryPath)
                pathInputElem.value = json.data.videoLibraryPath;
        } else {
            console.error("[Settings] Failed to refresh video settings UI:", json.error);
        }
    }

    refreshVideoSettingsUI();
});
