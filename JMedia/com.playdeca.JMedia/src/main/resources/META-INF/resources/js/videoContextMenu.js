/**
 * Video Context Menu Manager
 * Handles right-click context menus for video cards with playback, navigation, and metadata operations
 */
class VideoContextMenu {
    constructor() {
        this.currentVideoId = null;
        this.currentVideoData = null;
        this.contextMenu = null;
        this.uploadInput = null;
        this.init();
    }

    init() {
        this.createContextMenu();
        this.createUploadInput();
        this.bindEvents();
    }

    createContextMenu() {
        // Use the global context-menu class for consistent styling with music player
        this.contextMenu = document.createElement('div');
        this.contextMenu.id = 'video-context-menu';
        this.contextMenu.className = 'context-menu video-context-menu';
        this.updateMenuContent();
        document.body.appendChild(this.contextMenu);
    }

    updateMenuContent() {
        const isShow = this.currentVideoData && (this.currentVideoData.type === 'Show' || this.currentVideoData.type === 'Series');
        const isSeason = this.currentVideoData && this.currentVideoData.type === 'Season';
        
        this.contextMenu.innerHTML = `
            <div class="context-menu-item" data-action="play">
                <i class="pi pi-play"></i>
                <span>${isShow || isSeason ? 'View Episodes' : 'Play'}</span>
            </div>
            <div class="context-menu-item" data-action="details">
                <i class="pi pi-info-circle"></i>
                <span>Details</span>
            </div>
            ${!isSeason ? `
            <div class="context-menu-item" data-action="watchlist">
                <i class="pi pi-bookmark"></i>
                <span>Add to Watchlist</span>
            </div>
            ` : ''}
            <div class="context-menu-item" data-action="edit">
                <i class="pi pi-pencil"></i>
                <span>Edit Metadata</span>
            </div>
            <div class="context-menu-separator"></div>
            <div class="context-menu-item" data-action="fetch-thumbnail">
                <i class="pi pi-download"></i>
                <span>Fetch Thumbnail</span>
            </div>
            <div class="context-menu-item" data-action="extract-thumbnail">
                <i class="pi pi-image"></i>
                <span>Extract Thumbnail</span>
            </div>
            <div class="context-menu-item" data-action="upload-thumbnail">
                <i class="pi pi-upload"></i>
                <span>Upload Thumbnail</span>
            </div>
            <div class="context-menu-separator"></div>
            <div class="context-menu-item" data-action="reload-metadata">
                <i class="pi pi-refresh"></i>
                <span>Reload Metadata</span>
            </div>
        `;
    }

    createUploadInput() {
        this.uploadInput = document.createElement('input');
        this.uploadInput.type = 'file';
        this.uploadInput.accept = 'image/*';
        this.uploadInput.style.display = 'none';
        document.body.appendChild(this.uploadInput);
    }

    bindEvents() {
        // Context menu item clicks
        this.contextMenu.addEventListener('click', (e) => {
            const item = e.target.closest('.context-menu-item');
            if (item) {
                const action = item.dataset.action;
                this.handleAction(action);
                this.hide();
            }
        });

        // File input change
        this.uploadInput.addEventListener('change', (e) => {
            const file = e.target.files[0];
            if (file) {
                this.uploadThumbnail(file);
            }
        });

        // Hide context menu on click outside
        document.addEventListener('click', (e) => {
            if (!this.contextMenu.contains(e.target)) {
                this.hide();
            }
        });

        // Hide context menu on scroll (using capture to catch it in scrollable containers)
        document.addEventListener('scroll', () => this.hide(), { passive: true, capture: true });

        // Keyboard shortcuts
        document.addEventListener('keydown', (e) => {
            if (e.key === 'Escape') {
                this.hide();
            }
        });
    }

    show(e, videoData) {
        e.preventDefault();
        e.stopPropagation();
        
        this.currentVideoData = videoData;
        this.currentVideoId = videoData.id;
        
        // Update menu content based on current data (e.g. Show vs Movie)
        this.updateMenuContent();
        
        const x = e.clientX;
        const y = e.clientY;
        
        // Position context menu
        this.contextMenu.style.left = x + 'px';
        this.contextMenu.style.top = y + 'px';
        this.contextMenu.style.display = 'block';
        
        // Adjust position if it goes off screen
        const rect = this.contextMenu.getBoundingClientRect();
        if (rect.right > window.innerWidth) {
            this.contextMenu.style.left = (x - rect.width) + 'px';
        }
        if (rect.bottom > window.innerHeight) {
            this.contextMenu.style.top = (y - rect.height) + 'px';
        }
    }

    hide() {
        this.contextMenu.style.display = 'none';
    }

    handleAction(action) {
        if (!this.currentVideoId) return;

        switch (action) {
            case 'play':
                if (this.currentVideoData.type === 'Show' || this.currentVideoData.type === 'Series') {
                    window.switchSection('seasons', { encodedTitle: encodeURIComponent(this.currentVideoData.title) });
                } else if (this.currentVideoData.type === 'Season') {
                    window.switchSection('episodes', { 
                        seriesTitle: encodeURIComponent(this.currentVideoData.seriesTitle), 
                        seasonNumber: this.currentVideoData.seasonNumber 
                    });
                } else {
                    window.selectItem(this.currentVideoId, 'play');
                }
                break;
            case 'details':
                window.selectItem(this.currentVideoId, 'details');
                break;
            case 'watchlist':
                window.addToWatchlist(this.currentVideoData.title, this.currentVideoId);
                break;
            case 'edit':
                this.editMetadata();
                break;
            case 'fetch-thumbnail':
                this.fetchThumbnail();
                break;
            case 'extract-thumbnail':
                this.extractThumbnail();
                break;
            case 'upload-thumbnail':
                this.uploadInput.click();
                break;
            case 'reload-metadata':
                this.reloadMetadata();
                break;
        }
    }

    async fetchThumbnail() {
        try {
            this.showNotification('Fetching thumbnail...', 'info');
            const response = await fetch(`/api/video/thumbnail/${this.currentVideoId}/fetch`, {
                method: 'POST'
            });
            
            if (response.ok) {
                this.showNotification('Thumbnail fetch started', 'success');
                setTimeout(() => this.refreshThumbnail(), 2000);
            } else {
                this.showNotification('Failed to fetch thumbnail', 'danger');
            }
        } catch (error) {
            console.error('Error fetching thumbnail:', error);
        }
    }

    async extractThumbnail() {
        try {
            this.showNotification('Extracting thumbnail...', 'info');
            const response = await fetch(`/api/video/thumbnail/${this.currentVideoId}/extract`, {
                method: 'POST'
            });
            
            if (response.ok) {
                this.showNotification('Thumbnail extracted successfully', 'success');
                this.refreshThumbnail();
            } else {
                this.showNotification('Failed to extract thumbnail', 'danger');
            }
        } catch (error) {
            console.error('Error extracting thumbnail:', error);
        }
    }

    async uploadThumbnail(file) {
        this.showNotification('Upload feature coming soon', 'info');
        this.uploadInput.value = '';
    }

    async reloadMetadata() {
        try {
            this.showNotification('Reloading metadata...', 'info');
            const response = await fetch(`/api/video/metadata/${this.currentVideoId}/reload`, {
                method: 'POST'
            });
            
            if (response.ok) {
                this.showNotification('Metadata reload started', 'success');
                setTimeout(() => window.location.reload(), 2000);
            } else {
                this.showNotification('Failed to reload metadata', 'danger');
            }
        } catch (error) {
            console.error('Error reloading metadata:', error);
        }
    }

    async editMetadata() {
        const modal = document.getElementById('editVideoModal');
        const modalBody = document.getElementById('editVideoModalBody');
        
        if (!modal || !modalBody) {
            this.showNotification('Edit modal not found', 'danger');
            return;
        }

        let editUrl = `/api/video/manage/edit/${this.currentVideoId}`;

        if (this.currentVideoData.type === 'Show' || this.currentVideoData.type === 'Series') {
            editUrl = `/api/video/manage/edit-series/${encodeURIComponent(this.currentVideoData.title)}`;
        } else if (this.currentVideoData.type === 'Season') {
            if (this.currentVideoData.sampleVideoId) {
                editUrl = `/api/video/manage/edit/${this.currentVideoData.sampleVideoId}`;
            } else {
                this.showNotification('Cannot edit season without sample video', 'warning');
                return;
            }
        } else if (typeof this.currentVideoId === 'string' && this.currentVideoId.startsWith('show-')) {
            const showTitle = this.currentVideoData.title;
            editUrl = `/api/video/manage/edit-series/${encodeURIComponent(showTitle)}`;
        }

        modalBody.innerHTML = '<div class="has-text-centered p-6"><i class="pi pi-spin pi-spinner" style="font-size: 2rem;"></i></div>';
        modal.classList.add('is-active');

        try {
            const response = await fetch(editUrl);
            if (response.ok) {
                const html = await response.text();
                modalBody.innerHTML = html;
                
                // Process HTMX for the newly added content
                if (window.htmx) {
                    window.htmx.process(modalBody);
                }
                
                // Add success listener for the form
                const form = modalBody.querySelector('form');
                if (form) {
                    form.addEventListener('htmx:afterRequest', (evt) => {
                        if (evt.detail.successful) {
                            this.showNotification('Metadata updated successfully', 'success');
                            modal.classList.remove('is-active');
                            // Refresh current view to reflect changes
                            if (window.videoSPA) {
                                window.videoSPA.switchSection(window.videoSPA.currentSection, window.videoSPA.currentParams, true);
                            }
                        } else {
                            this.showNotification('Failed to update metadata', 'danger');
                        }
                    });
                }
            } else {
                modalBody.innerHTML = '<div class="notification is-danger">Failed to load edit form</div>';
            }
        } catch (error) {
            console.error('Error loading edit form:', error);
            modalBody.innerHTML = '<div class="notification is-danger">Error loading edit form</div>';
        }
    }

    refreshThumbnail() {
        const videoId = this.currentVideoId;
        const allImages = document.querySelectorAll('img');
        
        allImages.forEach(img => {
            const src = img.src;
            if (src.includes(videoId) && !src.includes('picsum.photos')) {
                const separator = src.includes('?') ? '&' : '?';
                img.src = src.split(/[?&]t=/)[0] + separator + 't=' + Date.now();
            }
        });
    }

    showNotification(message, type) {
        if (window.showToast) {
            window.showToast(message, type);
        } else if (window.Toast && window.Toast[type]) {
            window.Toast[type](message);
        } else {
            console.log(`[Notification] ${type}: ${message}`);
        }
    }
}

/**
 * Global function to add context menu handlers to elements
 */
function addContextMenuHandlers() {
    // Target all common video card classes
    const cardSelectors = [
        '.plex-card',
        '.streaming-card',
        '.episode-entry',
        '.content-card',
        '.video-entry',
        '.show-tile',
        '.video-card',
        '.series-hero-title',
        '.player-container',
        '#videoElement'
    ];
    
    const elements = document.querySelectorAll(cardSelectors.join(':not([data-context-added]), ') + ':not([data-context-added])');
    
    elements.forEach(el => {
        el.addEventListener('contextmenu', (e) => {
            e.preventDefault();
            const videoData = extractVideoData(el);
            if (videoData && videoData.id) {
                if (!window.videoContextMenu) window.videoContextMenu = new VideoContextMenu();
                window.videoContextMenu.show(e, videoData);
            }
        });
        el.setAttribute('data-context-added', 'true');
    });
}

/**
 * Extracts video ID and metadata from a DOM element
 */
function extractVideoData(el) {
    try {
        // Find the element with video data, potentially going up the tree (e.g. from video element to container)
        const target = el.closest('[data-video-id], [data-sample-video-id], [data-series-title]');
        if (!target) return null;
        
        // 1. Check data attributes (most reliable)
        const data = {
            id: target.dataset.videoId || target.dataset.sampleVideoId,
            title: target.dataset.title || target.dataset.seriesTitle || target.querySelector('.plex-card-title, .card-title, .episode-title-text')?.textContent.trim() || target.textContent.trim(),
            type: target.dataset.type || (target.classList.contains('episode-entry') ? 'Episode' : 'Video')
        };

        if (target.dataset.seriesTitle && target.dataset.seasonNumber) {
            data.type = 'Season';
            data.seriesTitle = target.dataset.seriesTitle;
            data.seasonNumber = target.dataset.seasonNumber;
            data.sampleVideoId = target.dataset.sampleVideoId;
            data.title = 'Season ' + target.dataset.seasonNumber;
        } else if (target.dataset.seriesTitle) {
            data.type = 'Show';
            data.title = target.dataset.seriesTitle;
        }

        return data;
    } catch (error) {
        console.error('Error extracting video data:', error);
        return null;
    }
}

// Initialize and setup observers
document.addEventListener('DOMContentLoaded', () => {
    if (!window.videoContextMenu) window.videoContextMenu = new VideoContextMenu();
    addContextMenuHandlers();
    
    // Support HTMX dynamic loading
    document.addEventListener('htmx:afterSettle', () => {
        addContextMenuHandlers();
    });
    
    // Support other dynamic content via MutationObserver
    const observer = new MutationObserver((mutations) => {
        let shouldRefresh = false;
        for (const mutation of mutations) {
            if (mutation.addedNodes.length > 0) {
                shouldRefresh = true;
                break;
            }
        }
        if (shouldRefresh) addContextMenuHandlers();
    });
    
    observer.observe(document.body, { childList: true, subtree: true });
});
