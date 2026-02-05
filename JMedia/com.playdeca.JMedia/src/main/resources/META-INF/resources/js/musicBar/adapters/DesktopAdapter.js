/**
 * DesktopAdapter - Desktop-specific UI adaptations and optimizations
 * Handles desktop-specific behavior, hover states, and UI enhancements
 */
(function(window) {
    'use strict';
    
    window.DesktopAdapter = {
        // Desktop-specific settings
        settings: {
            hoverDelay: 100, // Hover feedback delay
            doubleClickDelay: 500, // Double click detection delay
            dragThreshold: 5, // Drag sensitivity
            keyboardShortcuts: true, // Enable keyboard shortcuts
            rightClickMenu: true, // Enable right-click context menus
            tooltipDelay: 800, // Tooltip appearance delay
            scrollSpeed: 'smooth', // Smooth scrolling
            animationDuration: 200 // Animation duration
        },
        
        // Desktop state tracking
        state: {
            isDesktop: false,
            mouseConnected: true,
            keyboardConnected: true,
            fullscreenActive: false,
            sidebarPinned: false,
            tooltipsEnabled: true
        },
        
        /**
         * Initialize desktop adapter
         */
        init: function() {
            this.detectDesktopCapabilities();
            this.setupDesktopOptimizations();
            this.setupKeyboardShortcuts();
            this.setupDesktopEventListeners();
            window.Helpers.log('DesktopAdapter initialized');
        },
        
        /**
         * Detect desktop capabilities
         */
        detectDesktopCapabilities: function() {
            this.state.isDesktop = window.DeviceManager ? 
                window.DeviceManager.getDeviceType() === 'desktop' :
                (window.innerWidth >= 1024 && !('ontouchstart' in window));
            
            // Detect input capabilities
            this.state.mouseConnected = window.matchMedia('(pointer: fine)').matches;
            this.state.keyboardConnected = this.detectKeyboard();
            
            // Detect fullscreen status
            this.state.fullscreenActive = !!(document.fullscreenElement || document.webkitFullscreenElement);
            
            window.Helpers.log('DesktopAdapter: Desktop capabilities detected', {
                isDesktop: this.state.isDesktop,
                mouseConnected: this.state.mouseConnected,
                keyboardConnected: this.state.keyboardConnected,
                fullscreenActive: this.state.fullscreenActive
            });
        },
        
        /**
         * Detect keyboard connection
         */
        detectKeyboard: function() {
            try {
                // Various methods to detect keyboard
                return !('ontouchstart' in window) || 
                       window.matchMedia('(pointer: fine)').matches ||
                       navigator.keyboard || 
                       navigator.maxTouchPoints === 0;
            } catch (error) {
                console.warn('DesktopAdapter: Keyboard detection failed:', error);
                return true; // Assume keyboard present
            }
        },
        
        /**
         * Setup desktop-specific optimizations
         */
        setupDesktopOptimizations: function() {
            if (!this.state.isDesktop) return;
            
            // Optimize for desktop layouts
            this.optimizeForDesktopLayout();
            
            // Setup hover states and tooltips
            this.setupHoverAndTooltips();
            
            // Setup right-click menus
            this.setupRightClickMenus();
            
            // Setup drag and drop
            this.setupDragAndDrop();
            
            // Setup desktop player controls
            this.setupDesktopPlayerControls();
            
            // Setup desktop navigation
            this.setupDesktopNavigation();
        },
        
        /**
         * Optimize for desktop layouts
         */
        optimizeForDesktopLayout: function() {
            // Use larger screen space efficiently
            const mainContent = document.querySelector('.mobile-main');
            if (mainContent) {
                mainContent.style.maxWidth = '1200px';
                mainContent.style.margin = '0 auto';
            }
            
            // Optimize player for desktop
            const player = document.querySelector('.mobile-player');
            if (player) {
                player.style.position = 'fixed';
                player.style.bottom = '0';
                player.style.left = '50%';
                player.style.transform = 'translateX(-50%)';
                player.style.maxWidth = '800px';
                player.style.width = '100%';
                player.style.margin = '0';
            }
            
            // Adjust modal sizes for desktop
            this.adjustModalSizes();
            
            // Enable more spacious layouts
            document.body.classList.add('desktop-layout');
        },
        
        /**
         * Setup hover states and tooltips
         */
        setupHoverAndTooltips: function() {
            const interactiveElements = document.querySelectorAll(
                '.mobile-btn, .mobile-song-item, .mobile-playlist-item, .tab'
            );
            
            interactiveElements.forEach(element => {
                // Enhanced hover states
                element.addEventListener('mouseenter', () => {
                    element.classList.add('desktop-hover');
                    this.showTooltip(element);
                });
                
                element.addEventListener('mouseleave', () => {
                    element.classList.remove('desktop-hover');
                    this.hideTooltip();
                });
                
                // Focus states for keyboard navigation
                element.addEventListener('focus', () => {
                    element.classList.add('desktop-focus');
                });
                
                element.addEventListener('blur', () => {
                    element.classList.remove('desktop-focus');
                });
            });
        },
        
        /**
         * Setup right-click context menus
         */
        setupRightClickMenus: function() {
            document.addEventListener('contextmenu', (e) => {
                if (!this.settings.rightClickMenu) return;
                
                e.preventDefault();
                
                const target = e.target.closest('.mobile-song-item, .mobile-playlist-item');
                if (!target) return;
                
                const id = target.dataset.songId || target.dataset.playlistId;
                const type = target.classList.contains('mobile-song-item') ? 'song' : 'playlist';
                
                // Show context menu at cursor position
                window.dispatchEvent(new CustomEvent('requestContextMenu', {
                    detail: {
                        songId: id,
                        target: target,
                        type: type,
                        mousePosition: { x: e.clientX, y: e.clientY },
                        isDesktop: true
                    }
                }));
            });
        },
        
        /**
         * Setup drag and drop for desktop
         */
        setupDragAndDrop: function() {
            const draggableItems = document.querySelectorAll('.mobile-song-item, .mobile-playlist-item');
            
            draggableItems.forEach(item => {
                item.draggable = true;
                item.style.cursor = 'grab';
                
                item.addEventListener('dragstart', (e) => {
                    e.dataTransfer.effectAllowed = 'copy';
                    e.dataTransfer.setData('text/plain', item.dataset.songId || item.dataset.playlistId || '');
                    item.classList.add('dragging');
                    item.style.cursor = 'grabbing';
                });
                
                item.addEventListener('dragend', () => {
                    item.classList.remove('dragging');
                    item.style.cursor = 'grab';
                });
            });
            
            // Enhanced drop zones
            const dropZones = document.querySelectorAll('.mobile-playlist-list, #mobileQueueContent');
            
            dropZones.forEach(zone => {
                zone.addEventListener('dragover', (e) => {
                    e.preventDefault();
                    e.dataTransfer.dropEffect = 'copy';
                    zone.classList.add('drag-over-desktop');
                });
                
                zone.addEventListener('dragleave', () => {
                    zone.classList.remove('drag-over-desktop');
                });
                
                zone.addEventListener('drop', (e) => {
                    e.preventDefault();
                    zone.classList.remove('drag-over-desktop');
                    
                    const data = e.dataTransfer.getData('text/plain');
                    const songId = data;
                    const targetType = zone.id.includes('queue') ? 'queue' : 'playlist';
                    const targetId = zone.dataset.playlistId || zone.id;
                    
                    if (songId && targetId) {
                        window.dispatchEvent(new CustomEvent('requestAddToTarget', {
                            detail: { songId, targetId, targetType }
                        }));
                    }
                });
            });
        },
        
        /**
         * Setup desktop player controls
         */
        setupDesktopPlayerControls: function() {
            // Enhanced player with desktop features
            const player = document.querySelector('.mobile-player');
            if (!player) return;
            
            // Add desktop player enhancements
            this.addDesktopPlayerFeatures(player);
            
            // Mouse wheel volume control
            this.setupMouseWheelVolume(player);
            
            // Space bar play/pause
            this.setupSpaceBarControl();
            
            // Arrow key controls
            this.setupArrowKeyControls();
        },
        
        /**
         * Add desktop player features
         */
        addDesktopPlayerFeatures: function(player) {
            // Add mini/maximize toggle
            const minimizeBtn = document.createElement('button');
            minimizeBtn.className = 'desktop-player-minimize';
            minimizeBtn.innerHTML = '<i class="pi pi-window-minimize"></i>';
            minimizeBtn.title = 'Minimize Player';
            minimizeBtn.style.cssText = `
                position: absolute;
                top: 5px;
                right: 5px;
                background: transparent;
                border: none;
                color: var(--mobile-text);
                cursor: pointer;
                width: 30px;
                height: 30px;
                border-radius: 5px;
                display: flex;
                align-items: center;
                justify-content: center;
            `;
            
            minimizeBtn.addEventListener('click', () => this.togglePlayerSize());
            player.appendChild(minimizeBtn);
            this.minimizeBtn = minimizeBtn;
            
            // Add seek preview on hover
            const progressBar = document.getElementById('playbackProgressBar');
            if (progressBar) {
                progressBar.addEventListener('mousemove', (e) => {
                    this.showSeekPreview(e, progressBar);
                });
                
                progressBar.addEventListener('mouseleave', () => {
                    this.hideSeekPreview();
                });
            }
        },
        
        /**
         * Setup mouse wheel volume control
         */
        setupMouseWheelVolume: function(player) {
            player.addEventListener('wheel', (e) => {
                if (e.ctrlKey || e.metaKey) {
                    e.preventDefault();
                    
                    const currentVolume = window.StateManager?.getProperty('volume') || 0.8;
                    const delta = e.deltaY > 0 ? -0.05 : 0.05;
                    const newVolume = window.Helpers.clamp(currentVolume + delta, 0, 1);
                    
                    window.dispatchEvent(new CustomEvent('requestVolumeChange', {
                        detail: { volume: newVolume, source: 'desktopAdapter' }
                    }));
                }
            });
        },
        
        /**
         * Setup space bar control
         */
        setupSpaceBarControl: function() {
            document.addEventListener('keydown', (e) => {
                if (e.code === 'Space' && e.target.tagName !== 'INPUT' && e.target.tagName !== 'TEXTAREA') {
                    e.preventDefault();
                    window.dispatchEvent(new CustomEvent('requestPlaybackControl', {
                        detail: { action: 'playPause' }
                    }));
                }
            });
        },
        
        /**
         * Setup arrow key controls
         */
        setupArrowKeyControls: function() {
            document.addEventListener('keydown', (e) => {
                switch (e.key) {
                    case 'ArrowLeft':
                        if (e.ctrlKey || e.metaKey) {
                            e.preventDefault();
                            window.dispatchEvent(new CustomEvent('requestPlaybackControl', {
                                detail: { action: 'previous' }
                            }));
                        }
                        break;
                    case 'ArrowRight':
                        if (e.ctrlKey || e.metaKey) {
                            e.preventDefault();
                            window.dispatchEvent(new CustomEvent('requestPlaybackControl', {
                                detail: { action: 'next' }
                            }));
                        }
                        break;
                    case 'ArrowUp':
                        if (e.ctrlKey || e.metaKey) {
                            e.preventDefault();
                            window.dispatchEvent(new CustomEvent('requestVolumeChange', {
                                detail: { volume: 'up', source: 'desktopAdapter' }
                            }));
                        }
                        break;
                    case 'ArrowDown':
                        if (e.ctrlKey || e.metaKey) {
                            e.preventDefault();
                            window.dispatchEvent(new CustomEvent('requestVolumeChange', {
                                detail: { volume: 'down', source: 'desktopAdapter' }
                            }));
                        }
                        break;
                }
            });
        },
        
        /**
         * Setup desktop navigation
         */
        setupDesktopNavigation: function() {
            // Enhanced sidebar behavior
            const sidebar = document.querySelector('.mobile-side-panel');
            if (sidebar) {
                // Add pin/unpin functionality
                this.addSidebarPin(sidebar);
                
                // Keyboard navigation in sidebar
                this.setupSidebarKeyboardNav(sidebar);
            }
            
            // Tab navigation with arrow keys
            this.setupTabKeyboardNav();
            
            // Enhanced search behavior
            this.setupDesktopSearch();
        },
        
        /**
         * Add sidebar pin functionality
         */
        addSidebarPin: function(sidebar) {
            const pinBtn = document.createElement('button');
            pinBtn.className = 'desktop-sidebar-pin';
            pinBtn.innerHTML = '<i class="pi pi-thumbtack"></i>';
            pinBtn.title = 'Pin Sidebar';
            pinBtn.style.cssText = `
                position: absolute;
                top: 5px;
                left: 5px;
                background: transparent;
                border: none;
                color: var(--mobile-text);
                cursor: pointer;
                width: 30px;
                height: 30px;
                border-radius: 5px;
                display: flex;
                align-items: center;
                justify-content: center;
            `;
            
            pinBtn.addEventListener('click', () => this.toggleSidebarPin());
            sidebar.appendChild(pinBtn);
            this.pinBtn = pinBtn;
        },
        
        /**
         * Setup sidebar keyboard navigation
         */
        setupSidebarKeyboardNav: function(sidebar) {
            const tabElements = sidebar.querySelectorAll('.mobile-tab');
            const songItems = sidebar.querySelectorAll('.mobile-song-item');
            
            document.addEventListener('keydown', (e) => {
                if (!sidebar.contains(document.activeElement)) return;
                
                switch (e.key) {
                    case 'ArrowUp':
                        e.preventDefault();
                        this.focusPreviousItem();
                        break;
                    case 'ArrowDown':
                        e.preventDefault();
                        this.focusNextItem();
                        break;
                    case 'Enter':
                        e.preventDefault();
                        this.activateFocusedItem();
                        break;
                }
            });
        },
        
        /**
         * Setup tab keyboard navigation
         */
        setupTabKeyboardNav: function() {
            document.addEventListener('keydown', (e) => {
                if (e.ctrlKey || e.metaKey) {
                    switch (e.key) {
                        case '1':
                        case '2':
                        case '3':
                        case '4':
                        case '5':
                        case '6':
                        case '7':
                        case '8':
                        case '9':
                            e.preventDefault();
                            const tabIndex = parseInt(e.key) - 1;
                            this.switchToTab(tabIndex);
                            break;
                    }
                }
            });
        },
        
        /**
         * Setup desktop search behavior
         */
        setupDesktopSearch: function() {
            const searchInput = document.getElementById('mobileSearch');
            if (!searchInput) return;
            
            // Enhanced search with keyboard shortcuts
            searchInput.addEventListener('keydown', (e) => {
                if (e.key === 'Enter' && e.ctrlKey) {
                    e.preventDefault();
                    // Search with filters
                    window.dispatchEvent(new CustomEvent('requestAdvancedSearch', {
                        detail: { query: searchInput.value }
                    }));
                } else if (e.key === 'Escape') {
                    searchInput.blur();
                } else if (e.key === 'ArrowDown') {
                    e.preventDefault();
                    // Focus search results
                    this.focusSearchResults();
                }
            });
            
            // Clear button behavior
            const clearBtn = document.getElementById('mobileSearchClear');
            if (clearBtn) {
                clearBtn.addEventListener('click', () => {
                    searchInput.focus();
                });
            }
        },
        
        /**
         * Setup keyboard shortcuts
         */
        setupKeyboardShortcuts: function() {
            if (!this.settings.keyboardShortcuts) return;
            
            document.addEventListener('keydown', (e) => {
                // Global shortcuts
                if (e.ctrlKey || e.metaKey) {
                    switch (e.key.toLowerCase()) {
                        case 'f':
                            e.preventDefault();
                            // Focus search
                            document.getElementById('mobileSearch')?.focus();
                            break;
                        case 'm':
                            e.preventDefault();
                            // Toggle mute
                            const currentVolume = window.StateManager?.getProperty('volume') || 0.8;
                            const newVolume = currentVolume > 0 ? 0 : currentVolume;
                            window.dispatchEvent(new CustomEvent('requestVolumeChange', {
                                detail: { volume: newVolume, source: 'desktopAdapter' }
                            }));
                            break;
                        case 'l':
                            e.preventDefault();
                            // Toggle playlist sidebar
                            this.toggleSidebar();
                            break;
                        case 'h':
                            e.preventDefault();
                            // Show help shortcuts
                            this.showKeyboardShortcuts();
                            break;
                        case 'q':
                            e.preventDefault();
                            // Toggle queue
                            this.switchToTab('queue');
                            break;
                        case 'p':
                            e.preventDefault();
                            // Toggle playlists
                            this.switchToTab('playlists');
                            break;
                        case 'r':
                            e.preventDefault();
                            // Toggle repeat mode
                            window.dispatchEvent(new CustomEvent('requestPlaybackControl', {
                                detail: { action: 'repeat' }
                            }));
                            break;
                        case 's':
                            e.preventDefault();
                            // Toggle shuffle mode
                            window.dispatchEvent(new CustomEvent('requestPlaybackControl', {
                                detail: { action: 'shuffle' }
                            }));
                            break;
                    }
                }
                
                // Function keys
                if (e.key.startsWith('F') && !e.ctrlKey && !e.metaKey && !e.altKey && !e.shiftKey) {
                    switch (e.key) {
                        case 'F5':
                            e.preventDefault();
                            // Refresh content
                            window.dispatchEvent(new CustomEvent('requestRefresh', {
                                detail: { source: 'desktopAdapter' }
                            }));
                            break;
                        case '11':
                            // Toggle fullscreen
                            this.toggleFullscreen();
                            break;
                    }
                }
            });
        },
        
        /**
         * Setup desktop event listeners
         */
        setupDesktopEventListeners: function() {
            // Listen for device capability changes
            window.addEventListener('deviceCapabilitiesDetected', (e) => {
                if (e.detail.deviceType === 'desktop') {
                    this.setupDesktopOptimizations();
                }
            });
            
            // Listen for fullscreen changes
            document.addEventListener('fullscreenchange', () => {
                this.state.fullscreenActive = !!(document.fullscreenElement || document.webkitFullscreenElement);
                
                window.dispatchEvent(new CustomEvent('fullscreenChanged', {
                    detail: { isFullscreen: this.state.fullscreenActive }
                }));
            });
            
            // Listen for window focus changes
            window.addEventListener('focus', () => {
                // Resume desktop-specific behaviors
                this.resumeDesktopBehaviors();
            });
            
            window.addEventListener('blur', () => {
                // Pause desktop-specific behaviors
                this.pauseDesktopBehaviors();
            });
        },
        
        /**
         * Show tooltip for element
         */
        showTooltip: function(element) {
            if (!this.state.tooltipsEnabled) return;
            
            const tooltipText = element.title || element.getAttribute('aria-label');
            if (!tooltipText) return;
            
            // Remove existing tooltip
            this.hideTooltip();
            
            const tooltip = document.createElement('div');
            tooltip.className = 'desktop-tooltip';
            tooltip.textContent = tooltipText;
            tooltip.style.cssText = `
                position: absolute;
                background: rgba(0, 0, 0, 0.9);
                color: white;
                padding: 4px 8px;
                border-radius: 4px;
                font-size: 12px;
                z-index: 10000;
                pointer-events: none;
                transition: opacity 0.2s ease;
                opacity: 0;
            `;
            
            document.body.appendChild(tooltip);
            
            // Position tooltip
            const rect = element.getBoundingClientRect();
            tooltip.style.left = rect.left + rect.width / 2 - tooltip.offsetWidth / 2 + 'px';
            tooltip.style.top = rect.top - tooltip.offsetHeight - 5 + 'px';
            
            // Show with delay
            setTimeout(() => {
                tooltip.style.opacity = '1';
            }, this.settings.tooltipDelay);
            
            this.currentTooltip = tooltip;
        },
        
        /**
         * Hide tooltip
         */
        hideTooltip: function() {
            if (this.currentTooltip) {
                this.currentTooltip.remove();
                this.currentTooltip = null;
            }
        },
        
        /**
         * Show seek preview
         */
        showSeekPreview: function(e, progressBar) {
            const rect = progressBar.getBoundingClientRect();
            const percentage = ((e.clientX - rect.left) / rect.width) * 100;
            
            // Create or update seek preview
            let preview = document.getElementById('seekPreview');
            if (!preview) {
                preview = document.createElement('div');
                preview.id = 'seekPreview';
                preview.style.cssText = `
                    position: absolute;
                    background: rgba(0, 0, 0, 0.8);
                    color: white;
                    padding: 2px 6px;
                    border-radius: 3px;
                    font-size: 11px;
                    z-index: 10000;
                    pointer-events: none;
                    transition: opacity 0.2s ease;
                    opacity: 0;
                `;
                document.body.appendChild(preview);
            }
            
            const currentTime = window.StateManager?.getProperty('currentTime') || 0;
            const duration = window.StateManager?.getProperty('duration') || 100;
            const targetTime = (percentage / 100) * duration;
            
            preview.textContent = window.Helpers.formatTime(targetTime);
            preview.style.left = e.clientX + 10 + 'px';
            preview.style.top = rect.top - 25 + 'px';
            
            setTimeout(() => {
                preview.style.opacity = '1';
            }, 0);
        },
        
        /**
         * Hide seek preview
         */
        hideSeekPreview: function() {
            const preview = document.getElementById('seekPreview');
            if (preview) {
                preview.remove();
            }
        },
        
        /**
         * Toggle player size
         */
        togglePlayerSize: function() {
            const player = document.querySelector('.mobile-player');
            if (!player) return;
            
            if (this.state.fullscreenActive) {
                // Exit fullscreen
                document.exitFullscreen?.() || document.webkitExitFullscreen?.();
            } else {
                // Minimize player
                player.classList.toggle('minimized');
                
                const isMinimized = player.classList.contains('minimized');
                if (this.minimizeBtn) {
                    this.minimizeBtn.innerHTML = isMinimized ? 
                        '<i class="pi pi-window-maximize"></i>' : 
                        '<i class="pi pi-window-minimize"></i>';
                    this.minimizeBtn.title = isMinimized ? 
                        'Maximize Player' : 'Minimize Player';
                }
            }
        },
        
        /**
         * Toggle fullscreen
         */
        toggleFullscreen: function() {
            const documentElement = document.documentElement;
            
            if (!this.state.fullscreenActive) {
                documentElement.requestFullscreen?.() || 
                documentElement.webkitRequestFullscreen?.() || 
                documentElement.mozRequestFullscreen?.() ||
                documentElement.msRequestFullscreen?.();
            } else {
                document.exitFullscreen?.() || 
                document.webkitExitFullscreen?.() ||
                document.mozCancelFullScreen?.() ||
                document.msExitFullscreen?.();
            }
        },
        
        /**
         * Toggle sidebar pin
         */
        toggleSidebarPin: function() {
            this.state.sidebarPinned = !this.state.sidebarPinned;
            
            const sidebar = document.querySelector('.mobile-side-panel');
            if (sidebar) {
                sidebar.classList.toggle('pinned', this.state.sidebarPinned);
            }
            
            if (this.pinBtn) {
                this.pinBtn.style.color = this.state.sidebarPinned ? 
                    'var(--mobile-primary)' : '';
            }
            
            window.dispatchEvent(new CustomEvent('desktopSidebarPinToggled', {
                detail: { pinned: this.state.sidebarPinned }
            }));
        },
        
        /**
         * Switch to specific tab
         */
        switchToTab: function(tabName) {
            window.dispatchEvent(new CustomEvent('requestNavigation', {
                detail: { action: 'switchTab', params: { tabName } }
            }));
        },
        
        /**
         * Focus previous item
         */
        focusPreviousItem: function() {
            const focusable = document.querySelectorAll('[tabindex]:not([tabindex="-1"])');
            const currentIndex = Array.from(focusable).findIndex(el => el === document.activeElement);
            const prevIndex = currentIndex > 0 ? currentIndex - 1 : focusable.length - 1;
            focusable[prevIndex]?.focus();
        },
        
        /**
         * Focus next item
         */
        focusNextItem: function() {
            const focusable = document.querySelectorAll('[tabindex]:not([tabindex="-1"])');
            const currentIndex = Array.from(focusable).findIndex(el => el === document.activeElement);
            const nextIndex = (currentIndex + 1) % focusable.length;
            focusable[nextIndex]?.focus();
        },
        
        /**
         * Activate focused item
         */
        activateFocusedItem: function() {
            const active = document.activeElement;
            if (active) {
                active.click();
            }
        },
        
        /**
         * Focus search results
         */
        focusSearchResults: function() {
            const firstResult = document.querySelector('.mobile-song-item');
            if (firstResult) {
                firstResult.focus();
            }
        },
        
        /**
         * Show keyboard shortcuts help
         */
        showKeyboardShortcuts: function() {
            // Create help modal
            const modal = document.createElement('div');
            modal.className = 'desktop-keyboard-shortcuts-modal';
            modal.innerHTML = `
                <div class="keyboard-shortcuts-content">
                    <h3>Keyboard Shortcuts</h3>
                    <div class="shortcuts-grid">
                        <div class="shortcut-group">
                            <h4>Playback</h4>
                            <div><kbd>Space</kbd> - Play/Pause</div>
                            <div><kbd>Ctrl</kbd> + <kbd>←/→</kbd> - Previous/Next</div>
                            <div><kbd>Ctrl</kbd> + <kbd>↑/↓</kbd> - Volume Up/Down</div>
                            <div><kbd>Ctrl</kbd> + <kbd>R</kbd> - Repeat Mode</div>
                            <div><kbd>Ctrl</kbd> + <kbd>S</kbd> - Shuffle Mode</div>
                        </div>
                        <div class="shortcut-group">
                            <h4>Navigation</h4>
                            <div><kbd>Ctrl</kbd> + <kbd>F</kbd> - Search</div>
                            <div><kbd>Ctrl</kbd> + <kbd>L</kbd> - Toggle Sidebar</div>
                            <div><kbd>Ctrl</kbd> + <kbd>1-9</kbd> - Switch Tabs</div>
                            <div><kbd>Ctrl</kbd> + <kbd>Q/P</kbd> - Queue/Playlists</div>
                        </div>
                        <div class="shortcut-group">
                            <h4>Other</h4>
                            <div><kbd>Ctrl</kbd> + <kbd>M</kbd> - Mute/Unmute</div>
                            <div><kbd>Ctrl</kbd> + <kbd>H</kbd> - Show Help</div>
                            <div><kbd>F5</kbd> - Refresh</div>
                            <div><kbd>F11</kbd> - Toggle Fullscreen</div>
                        </div>
                    </div>
                    <button class="desktop-btn" onclick="this.parentElement.parentElement.remove()">Close</button>
                </div>
            `;
            
            modal.style.cssText = `
                position: fixed;
                top: 50%;
                left: 50%;
                transform: translate(-50%, -50%);
                background: white;
                border-radius: 8px;
                box-shadow: 0 10px 30px rgba(0,0,0,0.3);
                padding: 20px;
                z-index: 10000;
                max-width: 600px;
                max-height: 80vh;
                overflow-y: auto;
            `;
            
            document.body.appendChild(modal);
        },
        
        /**
         * Adjust modal sizes for desktop
         */
        adjustModalSizes: function() {
            const modals = document.querySelectorAll('.mobile-modal-card');
            
            modals.forEach(modal => {
                modal.style.maxWidth = '600px';
                modal.style.maxHeight = '70vh';
                modal.style.width = '';
                modal.style.height = '';
            });
        },
        
        /**
         * Resume desktop behaviors
         */
        resumeDesktopBehaviors: function() {
            // Resume animations
            document.body.style.animationPlayState = 'running';
            
            // Resume tooltips
            this.state.tooltipsEnabled = true;
        },
        
        /**
         * Pause desktop behaviors
         */
        pauseDesktopBehaviors: function() {
            // Pause animations
            document.body.style.animationPlayState = 'paused';
            
            // Hide tooltips
            this.state.tooltipsEnabled = false;
            this.hideTooltip();
        },
        
        /**
         * Get desktop adapter state
         */
        getAdapterState: function() {
            return {
                ...this.state,
                settings: {...this.settings}
            };
        },
        
        /**
         * Check if running on desktop
         */
        isDesktop: function() {
            return this.state.isDesktop;
        },
        
        /**
         * Check if mouse is connected
         */
        isMouseConnected: function() {
            return this.state.mouseConnected;
        },
        
        /**
         * Check if keyboard is connected
         */
        isKeyboardConnected: function() {
            return this.state.keyboardConnected;
        },
        
        /**
         * Check if fullscreen is active
         */
        isFullscreenActive: function() {
            return this.state.fullscreenActive;
        }
    };
    
    // Auto-initialize when dependencies are available
    if (window.Helpers && window.DeviceManager) {
        window.DesktopAdapter.init();
    } else {
        // Wait for dependencies
        const checkDeps = () => {
            if (window.Helpers && window.DeviceManager) {
                window.DesktopAdapter.init();
            } else {
                setTimeout(checkDeps, 50);
            }
        };
        setTimeout(checkDeps, 50);
    }
    
})(window);