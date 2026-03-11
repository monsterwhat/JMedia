class SubtitleManager {
    constructor() {
        this.currentVideoId = null;
        this.currentVideoTitle = '';
    }

    openModal(videoId, videoTitle, videoPath) {
        this.currentVideoId = videoId;
        this.currentVideoTitle = videoTitle;
        
        document.getElementById('subtitleSearchInput').value = videoTitle;
        const pathEl = document.getElementById('subtitle-modal-path');
        if (pathEl) {
            pathEl.textContent = videoPath || '';
        }
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
        const manualBtn = document.getElementById('manual-tab-btn');
        const aiBtn = document.getElementById('ai-tab-btn');
        const searchContent = document.getElementById('search-tab-content');
        const manualContent = document.getElementById('manual-tab-content');
        const aiContent = document.getElementById('ai-tab-content');

        searchBtn.classList.toggle('is-active', tab === 'search');
        manualBtn.classList.toggle('is-active', tab === 'manual');
        aiBtn.classList.toggle('is-active', tab === 'ai');

        searchContent.style.display = tab === 'search' ? 'block' : 'none';
        manualContent.style.display = tab === 'manual' ? 'block' : 'none';
        aiContent.style.display = tab === 'ai' ? 'block' : 'none';

        if (tab === 'manual') {
            this.scanLocal();
        }
    }

    async scanLocal() {
        const resultsBody = document.getElementById('subtitleManualResultsBody');
        resultsBody.innerHTML = `
            <tr>
                <td colspan="4" class="has-text-centered py-6">
                    <i class="pi pi-spin pi-spinner" style="font-size: 2rem;"></i>
                    <p class="mt-2">Scanning local directory...</p>
                </td>
            </tr>
        `;

        try {
            const response = await fetch(`/api/video/subtitles/${this.currentVideoId}/local-files`);
            if (!response.ok) throw new Error('Scan failed');
            
            const results = await response.json();
            
            if (results.length === 0) {
                resultsBody.innerHTML = `
                    <tr>
                        <td colspan="4" class="has-text-centered has-text-warning py-6">
                            <i class="pi pi-exclamation-triangle" style="font-size: 2rem;"></i>
                            <p class="mt-2">No subtitle files found in the directory or its sub-folders.</p>
                        </td>
                    </tr>
                `;
                return;
            }

            resultsBody.innerHTML = '';
            results.forEach(res => {
                const tr = document.createElement('tr');
                tr.innerHTML = `
                    <td class="is-vcentered" style="max-width: 250px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap;" title="${res.filename}">
                        ${res.filename}
                    </td>
                    <td class="is-vcentered">${res.languageName || 'Unknown'}</td>
                    <td class="is-vcentered" style="max-width: 300px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-size: 0.75rem; color: #aaa;" title="${res.fullPath}">
                        ${res.fullPath}
                    </td>
                    <td class="is-vcentered">
                        <button class="button is-small is-info is-rounded add-local-btn" data-path="${res.fullPath.replace(/"/g, '&quot;')}">
                            <i class="pi pi-plus"></i>&nbsp;Add
                        </button>
                    </td>
                `;
                resultsBody.appendChild(tr);
            });

            // Add event listeners to the buttons instead of using inline onclick
            resultsBody.querySelectorAll('.add-local-btn').forEach(btn => {
                btn.onclick = (e) => {
                    const filePath = e.currentTarget.getAttribute('data-path');
                    this.addLocal(filePath, e.currentTarget);
                };
            });

        } catch (error) {
            console.error('Local scan error:', error);
            resultsBody.innerHTML = `
                <tr>
                    <td colspan="4" class="has-text-centered has-text-danger py-6">
                        <i class="pi pi-times-circle" style="font-size: 2rem;"></i>
                        <p class="mt-2">Error scanning local files: ${error.message}</p>
                    </td>
                </tr>
            `;
        }
    }

    async addLocal(filePath, button) {
        const originalContent = button.innerHTML;
        button.classList.add('is-loading');
        button.disabled = true;

        try {
            const userId = localStorage.getItem('activeProfileId') || '1';
            const response = await fetch(`/api/video/subtitles/${this.currentVideoId}/add-local`, {
                method: 'POST',
                headers: { 
                    'Content-Type': 'application/json',
                    'X-User-ID': userId
                },
                body: JSON.stringify({ filePath: filePath })
            });
            
            const result = await response.json();
            if (!response.ok) {
                const msg = result.error || 'Add failed (Server Error)';
                throw new Error(msg);
            }
            
            if (window.showToast) {
                window.showToast('Subtitle added successfully!', 'success');
            }
            
            // Mark as added in UI
            button.classList.remove('is-info');
            button.classList.add('is-success');
            button.innerHTML = '<i class="pi pi-check"></i>&nbsp;Added';
            button.disabled = true;

            // Refresh tracks in the active player
            if (window.currentPlayerInstance && typeof window.currentPlayerInstance.loadSubtitles === 'function') {
                await window.currentPlayerInstance.loadSubtitles(result.tracks);
            }
            
        } catch (error) {
            console.error('Add local error:', error);
            const displayMsg = error.message.includes('Failed to fetch') ? 'Network error or server down' : error.message;
            if (window.showToast) {
                window.showToast('Error: ' + displayMsg, 'danger');
            } else {
                alert('Error adding local subtitle: ' + displayMsg);
            }
            button.classList.remove('is-loading');
            button.disabled = false;
            button.innerHTML = originalContent;
        } finally {
            button.classList.remove('is-loading');
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
                        <button class="button is-small is-success is-rounded" onclick="subtitleManager.download('${res.id}', this, '${lang}')">
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

    async download(fileId, button, language) {
        const originalContent = button.innerHTML;
        button.classList.add('is-loading');
        button.disabled = true;

        try {
            const response = await fetch(`/api/video/subtitles/${this.currentVideoId}/download?fileId=${fileId}&language=${language}`, {
                method: 'POST'
            });
            
            const result = await response.json();
            if (!response.ok) throw new Error(result.error || 'Download failed');
            
            if (window.showToast) {
                window.showToast('Subtitle downloaded: ' + result.message, 'success');
            }
            
            // Mark as downloaded in UI
            button.classList.remove('is-success');
            button.classList.add('is-info');
            button.innerHTML = '<i class="pi pi-check"></i>&nbsp;Downloaded';
            button.disabled = true;

            // Refresh tracks in the active player
            if (window.currentPlayerInstance && typeof window.currentPlayerInstance.loadSubtitles === 'function') {
                console.log('[SubtitleManager] Refreshing player subtitles with new tracks:', result.tracks);
                await window.currentPlayerInstance.loadSubtitles(result.tracks);
            }
            
        } catch (error) {
            console.error('Download error:', error);
            if (window.showToast) {
                window.showToast('Error: ' + error.message, 'danger');
            }
            // Only reset if it failed
            button.classList.remove('is-loading');
            button.disabled = false;
            button.innerHTML = originalContent;
        } finally {
            // Remove loading even if disabled (success case)
            button.classList.remove('is-loading');
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
