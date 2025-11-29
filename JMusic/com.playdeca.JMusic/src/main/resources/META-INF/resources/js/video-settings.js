document.addEventListener("DOMContentLoaded", () => {
    const browseVideoFolderBtn = document.getElementById("browseVideoFolderBtn");
    if (browseVideoFolderBtn) {
        browseVideoFolderBtn.onclick = async () => {
            const res = await fetch(`/api/settings/browse-video-folder`);
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
            htmx.ajax('POST', '/api/settings/video-library-path', {
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
    
    const toggleVideoLibraryManagementBtn = document.getElementById("toggleVideoLibraryManagementBtn");
    if (toggleVideoLibraryManagementBtn) {
        toggleVideoLibraryManagementBtn.onclick = (event) => window.toggleCardContent(event.currentTarget, "videoLibraryManagementContent");
    }

    async function refreshVideoSettingsUI() {
        const res = await fetch(`/api/settings`);
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
