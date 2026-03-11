class SubtitleManager {
    constructor() {
        this.currentVideoId = null;
        this.currentVideoTitle = '';
    }

    openModal(videoId, videoTitle) {
        this.currentVideoId = videoId;
        this.currentVideoTitle = videoTitle;
        
        document.getElementById('subtitleSearchInput').value = videoTitle;
        document.getElementById('subtitleManagementModal').classList.add('is-active');
        
        // Clear previous results
        document.getElementById('subtitleSearchResultsBody').innerHTML = `
            <tr>
                <td colspan="5" class="has-text-centered has-text-grey">
                    Click search to find subtitles for "${videoTitle}"
                </td>
            </tr>
        `;
        
        this.switchTab('search');
    }

    closeModal() {
        document.getElementById('subtitleManagementModal').classList.remove('is-active');
    }

    switchTab(tab) {
        const searchBtn = document.getElementById('search-tab-btn');
        const aiBtn = document.getElementById('ai-tab-btn');
        const searchContent = document.getElementById('search-tab-content');
        const aiContent = document.getElementById('ai-tab-content');

        if (tab === 'search') {
            searchBtn.classList.add('is-active');
            aiBtn.classList.remove('is-active');
            searchContent.style.display = 'block';
            aiContent.style.display = 'none';
        } else {
            searchBtn.classList.remove('is-active');
            aiBtn.classList.add('is-active');
            searchContent.style.display = 'none';
            aiContent.style.display = 'block';
        }
    }

    async search() {
        const query = document.getElementById('subtitleSearchInput').value;
        const lang = document.getElementById('subtitleLanguageSelect').value;
        const resultsBody = document.getElementById('subtitleSearchResultsBody');

        resultsBody.innerHTML = `
            <tr>
                <td colspan="5" class="has-text-centered py-6">
                    <i class="pi pi-spin pi-spinner" style="font-size: 2rem;"></i>
                    <p class="mt-2">Searching OpenSubtitles...</p>
                </td>
            </tr>
        `;

        try {
            const response = await fetch(`/api/video/subtitles/${this.currentVideoId}/search?language=${lang}`);
            if (!response.ok) throw new Error('Search failed');
            
            const results = await response.json();
            
            if (results.length === 0) {
                resultsBody.innerHTML = `
                    <tr>
                        <td colspan="5" class="has-text-centered has-text-warning py-6">
                            <i class="pi pi-exclamation-triangle" style="font-size: 2rem;"></i>
                            <p class="mt-2">No subtitles found. Try a different search term or language.</p>
                        </td>
                    </tr>
                `;
                return;
            }

            resultsBody.innerHTML = '';
            results.forEach(res => {
                const tr = document.createElement('tr');
                tr.innerHTML = `
                    <td class="is-vcentered" style="max-width: 300px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap;" title="${res.filename}">
                        ${res.filename}
                    </td>
                    <td class="is-vcentered">${res.language}</td>
                    <td class="is-vcentered">⭐ ${res.rating}</td>
                    <td class="is-vcentered">${res.downloadCount}</td>
                    <td class="is-vcentered">
                        <button class="button is-small is-success is-rounded" onclick="subtitleManager.download('${res.id}', this)">
                            <i class="pi pi-download"></i>&nbsp;Download
                        </button>
                    </td>
                `;
                resultsBody.appendChild(tr);
            });
        } catch (error) {
            console.error('Subtitle search error:', error);
            resultsBody.innerHTML = `
                <tr>
                    <td colspan="5" class="has-text-centered has-text-danger py-6">
                        <i class="pi pi-times-circle" style="font-size: 2rem;"></i>
                        <p class="mt-2">Error searching subtitles: ${error.message}</p>
                    </td>
                </tr>
            `;
        }
    }

    async download(fileId, button) {
        const originalContent = button.innerHTML;
        button.classList.add('is-loading');
        button.disabled = true;

        try {
            const response = await fetch(`/api/video/subtitles/${this.currentVideoId}/download?fileId=${fileId}`, {
                method: 'POST'
            });
            
            if (!response.ok) throw new Error('Download failed');
            
            if (window.showToast) {
                window.showToast('Subtitle downloaded successfully!', 'success');
            } else {
                alert('Subtitle downloaded successfully!');
            }
            
            // If we're in the player, refresh tracks
            if (typeof window.loadAvailableTracks === 'function') {
                window.loadAvailableTracks();
            }
            
        } catch (error) {
            console.error('Download error:', error);
            if (window.showToast) {
                window.showToast('Error: ' + error.message, 'danger');
            } else {
                alert('Error downloading: ' + error.message);
            }
        } finally {
            button.classList.remove('is-loading');
            button.disabled = false;
            button.innerHTML = originalContent;
        }
    }

    async generateAiSubtitles() {
        const btn = document.getElementById('startAiSubtitleBtn');
        const originalContent = btn.innerHTML;
        btn.classList.add('is-loading');
        btn.disabled = true;

        try {
            const response = await fetch(`/api/video/subtitles/${this.currentVideoId}/generate`, {
                method: 'POST'
            });
            
            if (!response.ok) {
                const text = await response.text();
                throw new Error(text || 'Generation failed');
            }
            
            if (window.showToast) {
                window.showToast('Whisper generation started in background!', 'info');
            } else {
                alert('Whisper generation started in background!');
            }
            
            this.closeModal();
            
        } catch (error) {
            console.error('AI Generation error:', error);
            if (window.showToast) {
                window.showToast('Error: ' + error.message, 'danger');
            } else {
                alert('Error starting AI generation: ' + error.message);
            }
        } finally {
            btn.classList.remove('is-loading');
            btn.disabled = false;
            btn.innerHTML = originalContent;
        }
    }
}

const subtitleManager = new SubtitleManager();
window.subtitleManager = subtitleManager;
