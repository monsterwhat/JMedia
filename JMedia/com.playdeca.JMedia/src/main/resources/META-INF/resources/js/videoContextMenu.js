/**
 * Video Context Menu Manager
 * Handles right-click context menus for video cards with thumbnail and metadata operations
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
        this.contextMenu = document.createElement('div');
        this.contextMenu.id = 'video-context-menu';
        this.contextMenu.className = 'video-context-menu';
        this.contextMenu.innerHTML = `
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
        document.body.appendChild(this.contextMenu);
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

        // Hide context menu on contextmenu
        document.addEventListener('contextmenu', (e) => {
            if (!e.target.closest('.video-card') && !e.target.closest('.video-entry')) {
                this.hide();
            }
        });

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
        
        const x = e.clientX;
        const y = e.clientY;
        
        // Check for dark theme
        const isDarkTheme = document.body.classList.contains('dark-theme') || 
                           document.documentElement.getAttribute('data-theme') === 'dark';
        
        // Position context menu
        this.contextMenu.style.left = x + 'px';
        this.contextMenu.style.top = y + 'px';
        this.contextMenu.style.display = 'block';
        
        // Apply dark theme class if needed
        if (isDarkTheme) {
            this.contextMenu.classList.add('dark');
        } else {
            this.contextMenu.classList.remove('dark');
        }
        
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
                method: 'POST',
                headers: {
                    'Content-Type': 'application/json'
                }
            });
            
            const result = await response.json();
            if (response.ok) {
                this.showNotification('Thumbnail fetch started', 'success');
                // Refresh the thumbnail after a delay
                setTimeout(() => this.refreshThumbnail(), 2000);
            } else {
                this.showNotification(result.message || 'Failed to fetch thumbnail', 'error');
            }
        } catch (error) {
            console.error('Error fetching thumbnail:', error);
            this.showNotification('Error fetching thumbnail', 'error');
        }
    }

    async extractThumbnail() {
        try {
            this.showNotification('Extracting thumbnail...', 'info');
            const response = await fetch(`/api/video/thumbnail/${this.currentVideoId}/extract`, {
                method: 'POST',
                headers: {
                    'Content-Type': 'application/json'
                }
            });
            
            const result = await response.json();
            if (response.ok) {
                this.showNotification('Thumbnail extracted successfully', 'success');
                this.refreshThumbnail();
            } else {
                this.showNotification(result.message || 'Failed to extract thumbnail', 'error');
            }
        } catch (error) {
            console.error('Error extracting thumbnail:', error);
            this.showNotification('Error extracting thumbnail', 'error');
        }
    }

    async uploadThumbnail(file) {
        try {
            this.showNotification('Upload feature coming soon - please use extract thumbnail for now', 'info');
            
            // TODO: Implement proper upload when Quarkus multipart support is fully working
            // For now, redirect user to extract thumbnail
            
            setTimeout(() => {
                this.extractThumbnail();
            }, 1000);
            
        } catch (error) {
            console.error('Error uploading thumbnail:', error);
            this.showNotification('Error uploading thumbnail', 'error');
        }
        
        // Reset file input
        this.uploadInput.value = '';
    }

    async reloadMetadata() {
        try {
            this.showNotification('Reloading metadata...', 'info');
            const response = await fetch(`/api/video/metadata/${this.currentVideoId}/reload`, {
                method: 'POST',
                headers: {
                    'Content-Type': 'application/json'
                }
            });
            
            const result = await response.json();
            if (response.ok) {
                this.showNotification('Metadata reload started', 'success');
                // Refresh the page content after a delay
                setTimeout(() => {
                    window.location.reload();
                }, 3000);
            } else {
                this.showNotification(result.message || 'Failed to reload metadata', 'error');
            }
        } catch (error) {
            console.error('Error reloading metadata:', error);
            this.showNotification('Error reloading metadata', 'error');
        }
    }

    refreshThumbnail() {
        // Find all images that might be thumbnails for this video
        const videoId = this.currentVideoId;
        console.log('Refreshing thumbnails for video ID:', videoId, 'Data:', this.currentVideoData);
        
        // Only refresh images that directly relate to this video ID
        const allImages = document.querySelectorAll('img');
        console.log('Found images:', allImages.length);
        
        allImages.forEach((img, index) => {
            const src = img.src;
            console.log(`Image ${index}:`, src);
            
            // Only refresh if this image is specifically for this video ID
            if (src.includes(videoId)) {
                console.log('Refreshing image with video ID:', videoId);
                if (!src.includes('picsum.photos')) {
                    // Add timestamp to force refresh existing thumbnails
                    const separator = src.includes('?') ? '&' : '?';
                    const newSrc = src + separator + 't=' + Date.now();
                    console.log('Updated image src:', newSrc);
                    img.src = newSrc;
                }
            }
            
            // Also update any picsum.photos images for this video to use real thumbnail endpoint
            if (this.currentVideoData && img.alt && img.alt.includes(this.currentVideoData.title) && src.includes('picsum.photos')) {
                console.log('Converting picsum image to real thumbnail for:', this.currentVideoData.title);
                img.src = `/api/video/thumbnail/${videoId}?t=${Date.now()}`;
            }
        });
    }

    showNotification(message, type = 'info') {
        // Create notification element
        const notification = document.createElement('div');
        notification.className = `video-context-notification is-${type}`;
        notification.innerHTML = `
            <div class="notification-content">
                <i class="pi ${this.getNotificationIcon(type)}"></i>
                <span>${message}</span>
            </div>
        `;
        
        // Add styles
        notification.style.cssText = `
            position: fixed;
            top: 20px;
            right: 20px;
            z-index: 9999;
            padding: 12px 16px;
            border-radius: 6px;
            background: ${this.getNotificationColor(type)};
            color: white;
            font-size: 14px;
            box-shadow: 0 4px 12px rgba(0,0,0,0.15);
            display: flex;
            align-items: center;
            gap: 8px;
            max-width: 300px;
            animation: slideIn 0.3s ease;
        `;
        
        document.body.appendChild(notification);
        
        // Remove after 3 seconds
        setTimeout(() => {
            if (notification.parentNode) {
                notification.parentNode.removeChild(notification);
            }
        }, 3000);
    }

    getNotificationIcon(type) {
        switch (type) {
            case 'success': return 'pi-check';
            case 'error': return 'pi-times';
            case 'info': return 'pi-info';
            default: return 'pi-info';
        }
    }

    getNotificationColor(type) {
        switch (type) {
            case 'success': return '#48c774';
            case 'error': return '#ff3860';
            case 'info': return '#3298dc';
            default: return '#3298dc';
        }
    }
}

// Initialize the context menu
let videoContextMenu;

document.addEventListener('DOMContentLoaded', () => {
    videoContextMenu = new VideoContextMenu();
    
    // Add right-click handlers to existing video cards
    addContextMenuHandlers();
    
    // Watch for dynamically added content
    const observer = new MutationObserver((mutations) => {
        mutations.forEach((mutation) => {
            if (mutation.addedNodes.length) {
                addContextMenuHandlers();
            }
        });
    });
    
    observer.observe(document.body, {
        childList: true,
        subtree: true
    });
});

function addContextMenuHandlers() {
    // Add handlers to content cards in carousels
    const contentCards = document.querySelectorAll('.content-card:not([data-context-added])');
    contentCards.forEach(card => {
        card.addEventListener('contextmenu', (e) => {
            e.preventDefault();
            const videoData = extractVideoDataFromCard(card);
            if (videoData) {
                videoContextMenu.show(e, videoData);
            }
        });
        card.setAttribute('data-context-added', 'true');
    });
    
    // Add handlers to video entries
    const videoEntries = document.querySelectorAll('.video-entry:not([data-context-added])');
    videoEntries.forEach(entry => {
        entry.addEventListener('contextmenu', (e) => {
            e.preventDefault();
            const videoData = extractVideoDataFromEntry(entry);
            if (videoData) {
                videoContextMenu.show(e, videoData);
            }
        });
        entry.setAttribute('data-context-added', 'true');
    });
    
    // Add handlers to show tiles
    const showTiles = document.querySelectorAll('.show-tile:not([data-context-added])');
    showTiles.forEach(tile => {
        tile.addEventListener('contextmenu', (e) => {
            e.preventDefault();
            const videoData = extractVideoDataFromShowTile(tile);
            if (videoData) {
                videoContextMenu.show(e, videoData);
            }
        });
        tile.setAttribute('data-context-added', 'toe');
    });
}

function extractVideoDataFromCard(card) {
    try {
        // Try to extract video data from onclick attribute
        const onclick = card.getAttribute('onclick');
        if (onclick && onclick.includes('selectItem')) {
            const match = onclick.match(/selectItem\(\s*({.*})\s*,\s*'([^']*)'\s*\)/);
            if (match) {
                const videoData = JSON.parse(match[1]);
                return {
                    id: videoData.id,
                    title: videoData.title || videoData.seriesTitle || 'Unknown',
                    type: videoData.type || 'Video',
                    ...videoData
                };
            }
        }
        
        // Fallback: extract from data attributes
        const videoId = card.dataset.videoId;
        const img = card.querySelector('.card-image');
        const titleElement = card.querySelector('.card-title');
        
        return {
            id: videoId || generateIdFromElement(card),
            title: titleElement ? titleElement.textContent.trim() : (img ? img.alt : 'Unknown'),
            type: 'Video'
        };
    } catch (error) {
        console.error('Error extracting video data from card:', error);
        return null;
    }
}

function extractVideoDataFromEntry(entry) {
    try {
        // Try to extract from onclick attribute
        const onclick = entry.getAttribute('onclick');
        if (onclick && onclick.includes('playVideo')) {
            const match = onclick.match(/playVideo\(this,\s*({.*})\s*\)/);
            if (match) {
                const videoData = JSON.parse(match[1]);
                return {
                    id: videoData.id,
                    title: videoData.title || videoData.episodeTitle || 'Unknown',
                    type: 'Video',
                    ...videoData
                };
            }
        }
        
        // Fallback: extract from content
        const titleElement = entry.querySelector('.video-title');
        return {
            id: generateIdFromElement(entry),
            title: titleElement ? titleElement.textContent.trim() : 'Unknown',
            type: 'Video'
        };
    } catch (error) {
        console.error('Error extracting video data from entry:', error);
        return null;
    }
}

function extractVideoDataFromShowTile(tile) {
    try {
        const titleElement = tile.querySelector('.show-title');
        const cssId = tile.id || generateIdFromElement(tile);
        
        return {
            id: cssId.replace(/[^\d]/g, ''),
            title: titleElement ? titleElement.textContent.trim() : 'Unknown Show',
            type: 'Show'
        };
    } catch (error) {
        console.error('Error extracting video data from show tile:', error);
        return null;
    }
}

function generateIdFromElement(element) {
    // Use actual video data if available, fallback to simple ID generation
    if (element.dataset.videoId) return element.dataset.videoId;
    if (element.id) return element.id;
    
    // Fallback: generate simple ID without position-based logic
    return element.id || element.className.split(' ')[0] || 'unknown';
}

// Add CSS styles
const contextMenuStyles = document.createElement('style');
contextMenuStyles.textContent = `
    .video-context-menu {
        position: fixed;
        z-index: 9999;
        background: white;
        border: 1px solid #dbdbdb;
        border-radius: 6px;
        box-shadow: 0 4px 12px rgba(0,0,0,0.15);
        padding: 4px 0;
        min-width: 180px;
        font-size: 14px;
        display: none;
    }
    
    .video-context-menu.dark {
        background: #363636;
        border-color: #4a4a4a;
        color: white;
    }
    
    .context-menu-item {
        padding: 8px 16px;
        cursor: pointer;
        display: flex;
        align-items: center;
        gap: 8px;
        transition: background-color 0.2s ease;
    }
    
    .context-menu-item:hover {
        background-color: #f5f5f5;
    }
    
    .video-context-menu.dark .context-menu-item:hover {
        background-color: #4a4a4a;
    }
    
    .context-menu-separator {
        height: 1px;
        background-color: #dbdbdb;
        margin: 4px 0;
    }
    
    .video-context-menu.dark .context-menu-separator {
        background-color: #4a4a4a;
    }
    
    .context-menu-item i {
        width: 16px;
        text-align: center;
    }
    
    @keyframes slideIn {
        from {
            transform: translateX(100%);
            opacity: 0;
        }
        to {
            transform: translateX(0);
            opacity: 1;
        }
    }
    
    .notification-content {
        display: flex;
        align-items: center;
        gap: 8px;
    }
`;

document.head.appendChild(contextMenuStyles);