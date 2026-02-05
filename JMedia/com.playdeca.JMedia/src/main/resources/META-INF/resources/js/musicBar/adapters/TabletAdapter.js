/**
 * TabletAdapter - Tablet-specific UI adaptations and optimizations
 * Handles tablet-specific behavior, gestures, and UI adjustments
 */
(function(window) {
    'use strict';
    
    window.TabletAdapter = {
        // Tablet-specific settings
        settings: {
            touchTargetSize: 40, // Slightly smaller than mobile
            swipeThreshold: 60, // Slightly larger than mobile
            dragThreshold: 10, // Drag sensitivity
            splitViewThreshold: 900, // Minimum width for split view
            landscapeThreshold: 1024, // Landscape mode threshold
            hoverDelay: 150, // Hover feedback delay
            sidebarWidth: 320 // Default sidebar width
        },
        
        // Tablet state tracking
        state: {
            isTablet: false,
            isLandscape: false,
            splitViewActive: false,
            sidebarVisible: true,
            touchActive: false,
            keyboardConnected: false
        },
        
        /**
         * Initialize tablet adapter
         */
        init: function() {
            this.detectTabletCapabilities();
            this.setupTabletOptimizations();
            this.setupOrientationHandling();
            this.setupKeyboardHandling();
            this.setupTabletEventListeners();
            window.Helpers.log('TabletAdapter initialized');
        },
        
        /**
         * Detect tablet capabilities
         */
        detectTabletCapabilities: function() {
            this.state.isTablet = window.DeviceManager ? 
                window.DeviceManager.getDeviceType() === 'tablet' :
                (window.innerWidth >= 768 && window.innerWidth <= 1366);
            
            // Detect orientation
            this.state.isLandscape = window.innerWidth > window.innerHeight;
            
            // Detect keyboard connection
            this.state.keyboardConnected = this.detectKeyboard();
            
            // Determine if split view should be active
            this.state.splitViewActive = window.innerWidth >= this.settings.splitViewThreshold;
            
            window.Helpers.log('TabletAdapter: Tablet capabilities detected', {
                isTablet: this.state.isTablet,
                isLandscape: this.state.isLandscape,
                splitViewActive: this.state.splitViewActive,
                keyboardConnected: this.state.keyboardConnected
            });
        },
        
        /**
         * Detect keyboard connection
         */
        detectKeyboard: function() {
            // Check for physical keyboard
            const hasKeyboard = !('ontouchstart' in window) || 
                              (window.matchMedia('(pointer: fine)').matches);
            
            // Check for device orientation changes that might indicate keyboard
            let initialHeight = window.innerHeight;
            setTimeout(() => {
                const heightChanged = window.innerHeight !== initialHeight;
                return hasKeyboard || !heightChanged;
            }, 500);
        },
        
        /**
         * Setup tablet-specific optimizations
         */
        setupTabletOptimizations: function() {
            if (!this.state.isTablet) return;
            
            // Optimize for tablet layouts
            this.optimizeForTabletLayout();
            
            // Setup hover states
            this.setupHoverStates();
            
            // Setup split view
            this.setupSplitView();
            
            // Setup touch and mouse hybrid
            this.setupHybridInput();
        },
        
        /**
         * Optimize for tablet layouts
         */
        optimizeForTabletLayout: function() {
            // Adjust main content area
            const mainContent = document.querySelector('.mobile-main');
            if (mainContent) {
                if (this.state.splitViewActive) {
                    mainContent.style.maxWidth = 'calc(100% - ' + this.settings.sidebarWidth + 'px)';
                    mainContent.style.marginLeft = this.settings.sidebarWidth + 'px';
                } else {
                    mainContent.style.maxWidth = '';
                    mainContent.style.marginLeft = '';
                }
            }
            
            // Optimize player layout for tablet
            const player = document.querySelector('.mobile-player');
            if (player) {
                player.style.maxWidth = this.state.splitViewActive ? '600px' : '';
                player.style.margin = '0 auto';
            }
            
            // Adjust modal sizing
            this.adjustModalSizes();
        },
        
        /**
         * Setup hover states for tablet
         */
        setupHoverStates: function() {
            const interactiveElements = document.querySelectorAll(
                '.mobile-btn, .mobile-song-item, .mobile-playlist-item, .tab'
            );
            
            interactiveElements.forEach(element => {
                // Hover feedback
                element.addEventListener('mouseenter', () => {
                    if (!this.state.touchActive) {
                        element.classList.add('tablet-hover');
                    }
                });
                
                element.addEventListener('mouseleave', () => {
                    element.classList.remove('tablet-hover');
                });
                
                // Touch feedback (remove hover on touch)
                element.addEventListener('touchstart', () => {
                    this.state.touchActive = true;
                    element.classList.remove('tablet-hover');
                });
                
                element.addEventListener('touchend', () => {
                    setTimeout(() => {
                        this.state.touchActive = false;
                    }, this.settings.hoverDelay);
                });
            });
        },
        
        /**
         * Setup split view for landscape tablets
         */
        setupSplitView: function() {
            if (!this.state.isLandscape || window.innerWidth < this.settings.splitViewThreshold) {
                this.disableSplitView();
                return;
            }
            
            this.enableSplitView();
        },
        
        /**
         * Enable split view
         */
        enableSplitView: function() {
            const sidebar = document.querySelector('.mobile-side-panel');
            const mainContent = document.querySelector('.mobile-main');
            const player = document.querySelector('.mobile-player');
            
            if (sidebar && mainContent) {
                this.state.splitViewActive = true;
                this.state.sidebarVisible = true;
                
                // Position sidebar as permanent element
                sidebar.classList.add('tablet-split-view');
                sidebar.style.position = 'fixed';
                sidebar.style.left = '0';
                sidebar.style.top = '0';
                sidebar.style.width = this.settings.sidebarWidth + 'px';
                sidebar.style.height = '100vh';
                sidebar.style.zIndex = '100';
                sidebar.style.transform = 'translateX(0)';
                
                // Adjust main content
                mainContent.style.marginLeft = this.settings.sidebarWidth + 'px';
                mainContent.style.width = 'calc(100% - ' + this.settings.sidebarWidth + 'px)';
                
                // Adjust player position
                if (player) {
                    player.style.position = 'fixed';
                    player.style.bottom = '0';
                    player.style.left = this.settings.sidebarWidth + 'px';
                    player.style.width = 'calc(100% - ' + this.settings.sidebarWidth + 'px)';
                }
                
                // Add tablet-specific controls
                this.addTabletControls();
                
                window.Helpers.log('TabletAdapter: Split view enabled');
            }
        },
        
        /**
         * Disable split view
         */
        disableSplitView: function() {
            const sidebar = document.querySelector('.mobile-side-panel');
            const mainContent = document.querySelector('.mobile-main');
            const player = document.querySelector('.mobile-player');
            
            if (sidebar && mainContent) {
                this.state.splitViewActive = false;
                
                // Reset sidebar
                sidebar.classList.remove('tablet-split-view');
                sidebar.style.position = '';
                sidebar.style.left = '';
                sidebar.style.top = '';
                sidebar.style.width = '';
                sidebar.style.height = '';
                sidebar.style.zIndex = '';
                sidebar.style.transform = '';
                
                // Reset main content
                mainContent.style.marginLeft = '';
                mainContent.style.width = '';
                
                // Reset player
                if (player) {
                    player.style.position = '';
                    player.style.bottom = '';
                    player.style.left = '';
                    player.style.width = '';
                }
                
                // Remove tablet-specific controls
                this.removeTabletControls();
                
                window.Helpers.log('TabletAdapter: Split view disabled');
            }
        },
        
        /**
         * Add tablet-specific controls
         */
        addTabletControls: function() {
            const sidebar = document.querySelector('.mobile-side-panel');
            if (!sidebar) return;
            
            // Add sidebar toggle button
            const toggleBtn = document.createElement('button');
            toggleBtn.className = 'tablet-sidebar-toggle';
            toggleBtn.innerHTML = '<i class="pi pi-bars"></i>';
            toggleBtn.title = 'Toggle Sidebar';
            toggleBtn.style.cssText = `
                position: fixed;
                top: 10px;
                right: 10px;
                z-index: 1000;
                width: 40px;
                height: 40px;
                background: var(--mobile-primary);
                border: none;
                border-radius: 5px;
                color: white;
                cursor: pointer;
                display: flex;
                align-items: center;
                justify-content: center;
            `;
            
            // Add click handler
            toggleBtn.addEventListener('click', () => this.toggleSidebar());
            
            // Add to page
            document.body.appendChild(toggleBtn);
            
            // Store reference
            this.sidebarToggleBtn = toggleBtn;
        },
        
        /**
         * Remove tablet-specific controls
         */
        removeTabletControls: function() {
            if (this.sidebarToggleBtn) {
                this.sidebarToggleBtn.remove();
                this.sidebarToggleBtn = null;
            }
        },
        
        /**
         * Setup hybrid input handling (touch + mouse)
         */
        setupHybridInput: function() {
            // Enhanced touch handling
            this.setupTabletTouchHandling();
            
            // Enhanced mouse handling
            this.setupTabletMouseHandling();
            
            // Drag and drop for tablet
            this.setupDragAndDrop();
        },
        
        /**
         * Setup tablet-specific touch handling
         */
        setupTabletTouchHandling: function() {
            let touchStartX = 0;
            let touchStartY = 0;
            let isDrag = false;
            
            document.addEventListener('touchstart', (e) => {
                if (e.touches.length === 1) {
                    touchStartX = e.touches[0].clientX;
                    touchStartY = e.touches[0].clientY;
                    isDrag = false;
                }
            });
            
            document.addEventListener('touchmove', (e) => {
                if (e.touches.length === 1) {
                    const deltaX = e.touches[0].clientX - touchStartX;
                    const deltaY = e.touches[0].clientY - touchStartY;
                    
                    if (Math.abs(deltaX) > this.settings.dragThreshold || 
                        Math.abs(deltaY) > this.settings.dragThreshold) {
                        isDrag = true;
                    }
                }
            });
            
            document.addEventListener('touchend', (e) => {
                const touchEndX = e.changedTouches[0].clientX;
                const touchEndY = e.changedTouches[0].clientY;
                const deltaX = touchEndX - touchStartX;
                const deltaY = touchEndY - touchStartY;
                
                if (!isDrag && Math.abs(deltaX) < 10 && Math.abs(deltaY) < 10) {
                    // It's a tap, not a drag
                    this.handleTabletTap(e.target);
                }
            });
        },
        
        /**
         * Setup tablet-specific mouse handling
         */
        setupTabletMouseHandling: function() {
            // Right-click context menus
            document.addEventListener('contextmenu', (e) => {
                const target = e.target.closest('.mobile-song-item, .mobile-playlist-item');
                if (target) {
                    e.preventDefault();
                    
                    const id = target.dataset.songId || target.dataset.playlistId;
                    if (id) {
                        window.dispatchEvent(new CustomEvent('requestContextMenu', {
                            detail: {
                                songId: id,
                                target: target,
                                mousePosition: { x: e.clientX, y: e.clientY },
                                isDesktop: true
                            }
                        }));
                    }
                }
            });
            
            // Double-click actions
            document.addEventListener('dblclick', (e) => {
                const target = e.target.closest('.mobile-song-item');
                if (target) {
                    const songId = target.dataset.songId;
                    if (songId) {
                        // Double-click to play
                        window.dispatchEvent(new CustomEvent('requestPlaySong', {
                            detail: { songId }
                        }));
                    }
                }
            });
        },
        
        /**
         * Setup drag and drop for tablet
         */
        setupDragAndDrop: function() {
            const songItems = document.querySelectorAll('.mobile-song-item');
            
            songItems.forEach(item => {
                item.draggable = true;
                item.addEventListener('dragstart', (e) => {
                    e.dataTransfer.effectAllowed = 'copy';
                    e.dataTransfer.setData('text/plain', item.dataset.songId || '');
                    item.classList.add('dragging');
                });
                
                item.addEventListener('dragend', () => {
                    item.classList.remove('dragging');
                });
            });
            
            // Setup drop zones
            const dropZones = document.querySelectorAll('.mobile-playlist-list, #mobileQueueContent');
            
            dropZones.forEach(zone => {
                zone.addEventListener('dragover', (e) => {
                    e.preventDefault();
                    e.dataTransfer.dropEffect = 'copy';
                    zone.classList.add('drag-over');
                });
                
                zone.addEventListener('dragleave', () => {
                    zone.classList.remove('drag-over');
                });
                
                zone.addEventListener('drop', (e) => {
                    e.preventDefault();
                    zone.classList.remove('drag-over');
                    
                    const songId = e.dataTransfer.getData('text/plain');
                    const playlistId = zone.id === 'mobileQueueContent' ? 'queue' : zone.dataset.playlistId;
                    
                    if (songId && playlistId) {
                        window.dispatchEvent(new CustomEvent('requestAddToTarget', {
                            detail: { songId, targetId: playlistId }
                        }));
                    }
                });
            });
        },
        
        /**
         * Handle tablet tap
         */
        handleTabletTap: function(target) {
            // Add visual feedback
            target.style.transition = 'transform 0.1s ease';
            target.style.transform = 'scale(0.95)';
            
            setTimeout(() => {
                target.style.transform = '';
            }, 100);
        },
        
        /**
         * Setup orientation handling
         */
        setupOrientationHandling: function() {
            window.addEventListener('orientationchange', () => {
                setTimeout(() => {
                    this.handleOrientationChange();
                }, 100);
            });
            
            // Also listen for resize (more reliable on some tablets)
            const debouncedResize = window.Helpers.debounce(() => {
                this.handleOrientationChange();
            }, 250);
            
            window.addEventListener('resize', debouncedResize);
        },
        
        /**
         * Handle orientation change
         */
        handleOrientationChange: function() {
            const newLandscape = window.innerWidth > window.innerHeight;
            const newSplitView = window.innerWidth >= this.settings.splitViewThreshold;
            
            if (newLandscape !== this.state.isLandscape) {
                this.state.isLandscape = newLandscape;
                
                // Emit orientation change event
                window.dispatchEvent(new CustomEvent('tabletOrientationChanged', {
                    detail: {
                        isLandscape: newLandscape,
                        width: window.innerWidth,
                        height: window.innerHeight
                    }
                }));
            }
            
            if (newSplitView !== this.state.splitViewActive) {
                if (newSplitView) {
                    this.enableSplitView();
                } else {
                    this.disableSplitView();
                }
            }
            
            window.Helpers.log('TabletAdapter: Orientation changed', {
                isLandscape: newLandscape,
                splitView: newSplitView
            });
        },
        
        /**
         * Setup keyboard handling for tablets
         */
        setupKeyboardHandling: function() {
            // Keyboard shortcuts for tablet
            document.addEventListener('keydown', (e) => {
                // Global shortcuts
                if (e.ctrlKey || e.metaKey) {
                    switch (e.key) {
                        case 'n':
                            e.preventDefault();
                            window.dispatchEvent(new CustomEvent('requestPlaybackControl', {
                                detail: { action: 'next' }
                            }));
                            break;
                        case 'p':
                            e.preventDefault();
                            window.dispatchEvent(new CustomEvent('requestPlaybackControl', {
                                detail: { action: 'previous' }
                            }));
                            break;
                        case ' ':
                            e.preventDefault();
                            window.dispatchEvent(new CustomEvent('requestPlaybackControl', {
                                detail: { action: 'playPause' }
                            }));
                            break;
                    }
                }
                
                // Escape handling
                if (e.key === 'Escape') {
                    if (this.state.sidebarVisible) {
                        this.toggleSidebar();
                    }
                }
            });
        },
        
        /**
         * Setup tablet event listeners
         */
        setupTabletEventListeners: function() {
            // Listen for device capability changes
            window.addEventListener('deviceCapabilitiesDetected', (e) => {
                if (e.detail.deviceType === 'tablet') {
                    this.setupTabletOptimizations();
                }
            });
            
            // Listen for screen category changes
            window.addEventListener('screenCategoryChanged', (e) => {
                if (e.detail.newCategory === 'large') {
                    this.setupTabletOptimizations();
                }
            });
            
            // Listen for keyboard connection changes
            window.addEventListener('keyboardConnectionChanged', (e) => {
                this.state.keyboardConnected = e.detail.connected;
                this.adjustForKeyboard(e.detail.connected);
            });
        },
        
        /**
         * Adjust modal sizes for tablet
         */
        adjustModalSizes: function() {
            const modals = document.querySelectorAll('.mobile-modal-card');
            
            modals.forEach(modal => {
                if (this.state.splitViewActive) {
                    modal.style.maxWidth = '600px';
                    modal.style.maxHeight = '80vh';
                } else {
                    modal.style.maxWidth = '90vw';
                    modal.style.maxHeight = '90vh';
                }
            });
        },
        
        /**
         * Toggle sidebar visibility
         */
        toggleSidebar: function() {
            const sidebar = document.querySelector('.mobile-side-panel');
            if (!sidebar) return;
            
            this.state.sidebarVisible = !this.state.sidebarVisible;
            
            if (this.state.sidebarVisible) {
                sidebar.style.transform = 'translateX(0)';
                document.querySelector('.mobile-main').style.marginLeft = this.settings.sidebarWidth + 'px';
            } else {
                sidebar.style.transform = 'translateX(-' + this.settings.sidebarWidth + 'px)';
                document.querySelector('.mobile-main').style.marginLeft = '0';
            }
            
            // Emit sidebar toggle event
            window.dispatchEvent(new CustomEvent('tabletSidebarToggled', {
                detail: { visible: this.state.sidebarVisible }
            }));
        },
        
        /**
         * Adjust for keyboard presence
         */
        adjustForKeyboard: function(hasKeyboard) {
            const interactiveElements = document.querySelectorAll('input, textarea, select');
            
            if (hasKeyboard) {
                // Optimize for keyboard input
                interactiveElements.forEach(element => {
                    element.style.padding = '8px 12px';
                    element.style.fontSize = '16px';
                });
            } else {
                // Optimize for touch input
                interactiveElements.forEach(element => {
                    element.style.padding = '12px 16px';
                    element.style.fontSize = '16px';
                    element.style.minHeight = this.settings.touchTargetSize + 'px';
                });
            }
        },
        
        /**
         * Get tablet adapter state
         */
        getAdapterState: function() {
            return {
                ...this.state,
                settings: {...this.settings}
            };
        },
        
        /**
         * Check if running on tablet
         */
        isTablet: function() {
            return this.state.isTablet;
        },
        
        /**
         * Check if split view is active
         */
        isSplitViewActive: function() {
            return this.state.splitViewActive;
        },
        
        /**
         * Check if keyboard is connected
         */
        isKeyboardConnected: function() {
            return this.state.keyboardConnected;
        }
    };
    
    // Auto-initialize when dependencies are available
    if (window.Helpers && window.DeviceManager) {
        window.TabletAdapter.init();
    } else {
        // Wait for dependencies
        const checkDeps = () => {
            if (window.Helpers && window.DeviceManager) {
                window.TabletAdapter.init();
            } else {
                setTimeout(checkDeps, 50);
            }
        };
        setTimeout(checkDeps, 50);
    }
    
})(window);