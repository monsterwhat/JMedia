/**
 * ResponsivePlayer - Unified player handling across device types
 * Manages player expansion, gestures, and responsive UI adaptations
 */
(function(window) {
    'use strict';
    
    window.ResponsivePlayer = {
        // Player elements cache
        elements: {
            player: null,
            expandBtn: null,
            expandIcon: null,
            playerInfo: null,
            playerControls: null,
            progressBar: null,
            volumeControl: null,
            coverImage: null,
            coverFallback: null
        },
        
        // Player state
        state: {
            isExpanded: false,
            expanding: false,
            playerVisible: true,
            touchStartY: 0,
            touchStartTime: 0,
            initialProgressValue: 0,
            volumeSliderVisible: false
        },
        
        // Gesture tracking
        gestures: {
            touchStartX: 0,
            touchStartY: 0,
            isSwiping: false,
            swipeThreshold: 50,
            verticalSwipeThreshold: 100
        },
        
        /**
         * Initialize responsive player
         */
        init: function() {
            this.setupElements();
            this.setupEventListeners();
            this.loadInitialState();
            this.adaptToDevice();
            window.Helpers.log('ResponsivePlayer initialized');
        },
        
        /**
         * Setup player elements
         */
        setupElements: function() {
            const elements = this.elements;
            
            // Main player elements
            elements.player = document.querySelector('.mobile-player');
            elements.expandBtn = document.getElementById('expandPlayerBtn');
            elements.expandIcon = elements.expandBtn?.querySelector('i');
            elements.playerInfo = elements.player?.querySelector('.mobile-player-info');
            elements.playerControls = elements.player?.querySelector('.mobile-player-controls');
            
            // Control elements
            elements.progressBar = document.getElementById('playbackProgressBar');
            elements.volumeControl = document.querySelector('.mobile-volume-mini');
            
            // Cover image elements
            elements.coverImage = document.getElementById('songCoverImage');
            elements.coverFallback = document.getElementById('songCoverFallback');
            
            this.validateElements();
        },
        
        /**
         * Validate required elements
         */
        validateElements: function() {
            const missing = [];
            const elements = this.elements;
            
            Object.keys(elements).forEach(key => {
                if (elements[key] && 
                    (elements[key].length === undefined || elements[key].length > 0)) {
                    return; // Element exists
                }
                
                // Check if it's a collection
                if (elements[key] && elements[key].length === 0) {
                    return; // Empty collection is valid
                }
                
                missing.push(key);
            });
            
            if (missing.length > 0) {
                window.Helpers.log('ResponsivePlayer: Missing elements:', missing);
            }
        },
        
        /**
         * Setup event listeners
         */
        setupEventListeners: function() {
            this.setupPlayerControls();
            this.setupExpansionControls();
            this.setupCoverImageGestures();
            this.setupSwipeGestures();
            this.setupVolumeControls();
            this.setupProgressControls();
            this.setupCustomEventListeners();
            
            window.Helpers.log('ResponsivePlayer: Event listeners configured');
        },
        
        /**
         * Setup player controls
         */
        setupPlayerControls: function() {
            // These will be handled by PlaybackController
            // But we can add responsive adaptations here
            this.adaptControlsToScreenSize();
        },
        
        /**
         * Setup expansion controls
         */
        setupExpansionControls: function() {
            const expandBtn = this.elements.expandBtn;
            if (!expandBtn) return;
            
            // Click/tap to expand
            expandBtn.addEventListener('click', () => this.togglePlayerExpansion());
            expandBtn.addEventListener('touchend', (e) => {
                e.preventDefault();
                this.togglePlayerExpansion();
            }, {passive: true});
            
            // Keyboard support
            document.addEventListener('keydown', (e) => {
                if (e.key === 'Escape' && this.state.isExpanded) {
                    this.collapsePlayer();
                }
            });
        },
        
        /**
         * Auto-initialize when dependencies are available
         */
        initWhenReady: function() {
            try {
                if (window.Helpers && window.DeviceManager && window.StateManager) {
                    window.ResponsivePlayer.init();
                } else {
                    setTimeout(() => window.ResponsivePlayer.initWhenReady(), 50);
                }
            } catch (error) {
                console.error('ResponsivePlayer initialization error:', error);
                setTimeout(() => window.ResponsivePlayer.initWhenReady(), 100);
            }
        },
        
        /**
         * Setup cover image gestures
         */
        setupCoverImageGestures: function() {
            const coverContainer = document.getElementById('songCoverImageContainer');
            if (!coverContainer) return;
            
            let longPressTimer;
            let touchStartTime;
            let touchStartX;
            let touchStartY;
            
            coverContainer.addEventListener('touchstart', (e) => {
                if (e.touches.length === 1) {
                    touchStartTime = Date.now();
                    touchStartX = e.touches[0].clientX;
                    touchStartY = e.touches[0].clientY;
                    
                    // Visual feedback
                    coverContainer.classList.add('long-press-active');
                    
                    // Start long press timer
                    longPressTimer = setTimeout(() => {
                        const songId = window.StateManager?.getProperty('currentSongId');
                        if (songId) {
                            window.dispatchEvent(new CustomEvent('requestContextMenu', {
                                detail: {
                                    songId: songId,
                                    target: coverContainer,
                                    isLongPress: true
                                }
                            }));
                        } else if (window.showToast) {
                            window.showToast('No song currently playing', 'info');
                        }
                    }, 500);
                }
            });
            
            coverContainer.addEventListener('touchend', (e) => {
                clearTimeout(longPressTimer);
                
                // Remove visual feedback
                coverContainer.classList.remove('long-press-active');
                
                // Check if this was a tap (not long press)
                const touchDuration = Date.now() - touchStartTime;
                const touchEndX = e.changedTouches[0].clientX;
                const touchEndY = e.changedTouches[0].clientY;
                const deltaX = Math.abs(touchEndX - touchStartX);
                const deltaY = Math.abs(touchEndY - touchStartY);
                
                if (touchDuration < 300 && deltaX < 10 && deltaY < 10) {
                    // Short tap - toggle play/pause
                    window.dispatchEvent(new CustomEvent('requestPlaybackControl', {
                        detail: { action: 'playPause', profileId: window.globalActiveProfileId }
                    }));
                }
            });
            
            coverContainer.addEventListener('touchmove', () => {
                clearTimeout(longPressTimer);
                coverContainer.classList.remove('long-press-active');
            });
            
            // Desktop right-click support
            coverContainer.addEventListener('contextmenu', (e) => {
                e.preventDefault();
                
                const songId = window.StateManager?.getProperty('currentSongId');
                if (!songId) {
                    if (window.showToast) {
                        window.showToast('No song currently playing', 'info');
                    }
                    return;
                }
                
                window.dispatchEvent(new CustomEvent('requestContextMenu', {
                    detail: {
                        songId: songId,
                        target: coverContainer,
                        mousePosition: { x: e.clientX, y: e.clientY },
                        isLongPress: false,
                        isDesktop: true
                    }
                }));
            });
        },
        
        /**
         * Setup swipe gestures
         */
        setupSwipeGestures: function() {
            const player = this.elements.player;
            if (!player) return;
            
            player.addEventListener('touchstart', (e) => {
                if (e.touches.length === 1) {
                    this.gestures.touchStartX = e.touches[0].clientX;
                    this.gestures.touchStartY = e.touches[0].clientY;
                    this.gestures.isSwiping = true;
                }
            });
            
            player.addEventListener('touchend', (e) => {
                if (!this.gestures.isSwiping) return;
                
                this.gestures.isSwiping = false;
                
                const touchEndX = e.changedTouches[0].clientX;
                const touchEndY = e.changedTouches[0].clientY;
                const deltaX = touchEndX - this.gestures.touchStartX;
                const deltaY = touchEndY - this.gestures.touchStartY;
                
                // Horizontal swipe - next/previous
                if (Math.abs(deltaX) > this.gestures.swipeThreshold && 
                    Math.abs(deltaY) < this.gestures.verticalSwipeThreshold) {
                    
                    if (deltaX > 0) {
                        // Swipe right - previous song
                        window.dispatchEvent(new CustomEvent('requestPlaybackControl', {
                            detail: { action: 'previous', profileId: window.globalActiveProfileId }
                        }));
                    } else {
                        // Swipe left - next song
                        window.dispatchEvent(new CustomEvent('requestPlaybackControl', {
                            detail: { action: 'next', profileId: window.globalActiveProfileId }
                        }));
                    }
                }
                
                // Vertical swipe down in expanded mode - collapse
                if (this.state.isExpanded && deltaY > this.gestures.verticalSwipeThreshold) {
                    this.collapsePlayer();
                }
            });
        },
        
        /**
         * Setup pull-up gesture to expand player
         */
        setupPullUpGesture: function() {
            const player = this.elements.player;
            if (!player || this.state.isExpanded) return;
            
            let pullStartY = 0;
            let isPulling = false;
            
            player.addEventListener('touchstart', (e) => {
                if (e.touches.length === 1 && !this.state.isExpanded) {
                    pullStartY = e.touches[0].clientY;
                    isPulling = true;
                }
            });
            
            player.addEventListener('touchmove', (e) => {
                if (!isPulling) return;
                
                const currentY = e.touches[0].clientY;
                const deltaY = pullStartY - currentY;
                
                // Visual feedback for pull-up
                if (deltaY > 50) {
                    player.style.transform = `translateY(-${Math.min(deltaY * 0.3, 20)}px)`;
                }
            });
            
            player.addEventListener('touchend', (e) => {
                if (!isPulling) return;
                
                isPulling = false;
                player.style.transform = '';
                
                const currentY = e.changedTouches[0].clientY;
                const deltaY = pullStartY - currentY;
                
                // If pulled up enough, expand player
                if (deltaY > 100) {
                    this.expandPlayer();
                }
            });
        },
        
        /**
         * Setup volume controls
         */
        setupVolumeControls: function() {
            const volumeBtn = document.getElementById('volumeToggleBtn');
            const volumeSlider = document.getElementById('volumeProgressBar');
            const volumeIcon = document.getElementById('volumeIcon');
            
            if (!volumeBtn || !volumeSlider || !volumeIcon) return;
            
            let previousVolume = 0.7;
            let sliderTimeout;
            
            // Update icon based on volume
            const updateVolumeIcon = () => {
                const currentVolume = window.StateManager?.getProperty('volume') || 0.7;
                if (currentVolume === 0) {
                    volumeIcon.className = 'pi pi-volume-off';
                } else if (currentVolume < 0.3) {
                    volumeIcon.className = 'pi pi-volume-down';
                } else {
                    volumeIcon.className = 'pi pi-volume-up';
                }
            };
            
            // Sync slider with state
            const syncSlider = () => {
                if (volumeSlider && !volumeSlider.matches(':active')) {
                    const currentVolume = window.StateManager?.getProperty('volume') || 0.7;
                    const linearValue = window.Helpers.volume.calculateLinearSliderValue(currentVolume);
                    volumeSlider.value = linearValue;
                }
            };
            
            // Volume button events
            volumeBtn.addEventListener('click', (e) => {
                if (e.detail === 0 || e.pointerType === 'touch') {
                    // Touch/short click - show slider
                    this.showVolumeSlider();
                } else {
                    // Desktop click - show slider
                    this.showVolumeSlider();
                }
            });
            
            // Long press for mute toggle
            let pressTimer;
            volumeBtn.addEventListener('mousedown', (e) => {
                pressTimer = setTimeout(() => {
                    const currentVolume = window.StateManager?.getProperty('volume') || 0.7;
                    if (currentVolume > 0) {
                        previousVolume = currentVolume;
                        this.setVolume(0);
                        if (window.showToast) {
                            window.showToast('🔇 Muted', 'info', 2000);
                        }
                    } else {
                        this.setVolume(previousVolume);
                        if (window.showToast) {
                            window.showToast('🔊 Unmuted', 'info', 2000);
                        }
                    }
                }, 300);
            });
            
            volumeBtn.addEventListener('mouseup', () => clearTimeout(pressTimer));
            volumeBtn.addEventListener('mouseleave', () => clearTimeout(pressTimer));
            
            // Touch long press support
            volumeBtn.addEventListener('touchstart', (e) => {
                pressTimer = setTimeout(() => {
                    const currentVolume = window.StateManager?.getProperty('volume') || 0.7;
                    if (currentVolume > 0) {
                        previousVolume = currentVolume;
                        this.setVolume(0);
                        if (window.showToast) {
                            window.showToast('🔇 Muted', 'info', 2000);
                        }
                    } else {
                        this.setVolume(previousVolume);
                        if (window.showToast) {
                            window.showToast('🔊 Unmuted', 'info', 2000);
                        }
                    }
                }, 300);
            });
            
            volumeBtn.addEventListener('touchend', () => clearTimeout(pressTimer));
            
            // Volume slider control
            volumeSlider.addEventListener('input', (e) => {
                const sliderValue = parseFloat(e.target.value);
                const exponentialVolume = window.Helpers.volume.calculateExponentialVolume(sliderValue);
                this.setVolume(exponentialVolume);
            });
            
            // Show/hide slider on hover/touch
            volumeBtn.addEventListener('mouseenter', () => this.showVolumeSlider());
            volumeBtn.addEventListener('mouseleave', () => this.hideVolumeSlider());
            volumeSlider.addEventListener('mouseenter', () => this.showVolumeSlider());
            volumeSlider.addEventListener('mouseleave', () => this.hideVolumeSlider());
            
            // Listen for volume changes
            window.addEventListener('statePropertyChanged', (e) => {
                if (e.detail.property === 'volume') {
                    updateVolumeIcon();
                    syncSlider();
                }
            });
            
            // Initial setup
            setTimeout(() => {
                updateVolumeIcon();
                syncSlider();
            }, 200);
        },
        
        /**
         * Setup progress controls
         */
        setupProgressControls: function() {
            const progressBar = this.elements.progressBar;
            if (!progressBar) return;
            
            let isDragging = false;
            
            progressBar.addEventListener('input', (e) => {
                const value = parseFloat(e.target.value);
                const max = parseFloat(e.target.max);
                const percentage = value / max;
                
                // Update visual feedback
                const progress = progressBar.value;
                window.Helpers.log(`ResponsivePlayer: Progress changed to ${progress}%`);
            });
            
            progressBar.addEventListener('change', (e) => {
                const value = parseFloat(e.target.value);
                const max = parseFloat(e.target.max);
                const percentage = value / max;
                
                // Request time update
                window.dispatchEvent(new CustomEvent('requestTimeUpdate', {
                    detail: { percentage: percentage }
                }));
            });
            
            // Touch/drag handling
            progressBar.addEventListener('touchstart', () => {
                isDragging = true;
                this.state.initialProgressValue = parseFloat(progressBar.value);
            });
            
            progressBar.addEventListener('touchend', () => {
                isDragging = false;
            });
            
            // Visual feedback
            progressBar.addEventListener('mousedown', () => {
                progressBar.style.cursor = 'grabbing';
            });
            
            progressBar.addEventListener('mouseup', () => {
                progressBar.style.cursor = 'grab';
            });
        },
        
        /**
         * Setup custom event listeners
         */
        setupCustomEventListeners: function() {
            // Listen for state changes
            window.addEventListener('musicStateChanged', (e) => {
                this.handleStateChange(e.detail.oldState, e.detail.newState);
            });
            
            // Listen for device capability changes
            window.addEventListener('deviceCapabilitiesDetected', (e) => {
                this.adaptToDevice(e.detail);
            });
            
            // Listen for screen category changes
            window.addEventListener('screenCategoryChanged', (e) => {
                this.adaptToScreenCategory(e.detail.newCategory);
            });
            
            // Listen for player requests
            window.addEventListener('requestPlayerAction', (e) => {
                this.handlePlayerAction(e.detail.action, e.detail.params);
            });
        },
        
        /**
         * Load initial player state
         */
        loadInitialState: function() {
            // Load from localStorage if available
            const savedState = localStorage.getItem('responsivePlayerState');
            if (savedState) {
                try {
                    const parsedState = JSON.parse(savedState);
                    this.state.playerVisible = parsedState.playerVisible !== false;
                } catch (e) {
                    window.Helpers.log('ResponsivePlayer: Failed to load saved state:', e);
                }
            }
            
            // Apply initial state
            this.updateUIFromState();
        },
        
        /**
         * Save current player state
         */
        saveState: function() {
            try {
                const stateToSave = {
                    playerVisible: this.state.playerVisible
                };
                localStorage.setItem('responsivePlayerState', JSON.stringify(stateToSave));
            } catch (e) {
                // Ignore storage errors
            }
        },
        
        /**
         * Toggle player expansion
         */
        togglePlayerExpansion: function() {
            if (this.state.expanding) return;
            
            if (this.state.isExpanded) {
                this.collapsePlayer();
            } else {
                this.expandPlayer();
            }
        },
        
        /**
         * Expand player to fullscreen
         */
        expandPlayer: function() {
            if (this.state.expanding || this.state.isExpanded) return;
            
            this.state.expanding = true;
            this.state.isExpanded = true;
            
            const player = this.elements.player;
            const expandIcon = this.elements.expandIcon;
            
            if (player) {
                player.classList.add('expanded');
            }
            
            if (expandIcon) {
                expandIcon.className = 'pi pi-chevron-down';
            }
            
            // Disable body scrolling
            document.body.style.overflow = 'hidden';
            
            // Update expand button
            this.updateExpandButton('pi-chevron-down');
            
            // Emit event
            window.dispatchEvent(new CustomEvent('playerExpansionChanged', {
                detail: {
                    expanded: true,
                    source: 'responsivePlayer'
                }
            }));
            
            // Setup expanded mode features
            this.setupExpandedMode();
            
            // Animation completion
            setTimeout(() => {
                this.state.expanding = false;
            }, 300);
            
            window.Helpers.log('ResponsivePlayer: Player expanded');
        },
        
        /**
         * Collapse player from fullscreen
         */
        collapsePlayer: function() {
            if (this.state.expanding || !this.state.isExpanded) return;
            
            this.state.expanding = true;
            this.state.isExpanded = false;
            
            const player = this.elements.player;
            const expandIcon = this.elements.expandIcon;
            
            if (player) {
                player.classList.add('collapsing');
            }
            
            // Update expand button immediately
            this.updateExpandButton('pi-expand-up');
            
            // Animation and cleanup
            setTimeout(() => {
                if (player) {
                    player.classList.remove('expanded', 'collapsing');
                }
                
                // Re-enable body scrolling
                document.body.style.overflow = '';
                
                // Cleanup expanded mode features
                this.cleanupExpandedMode();
                
                this.state.expanding = false;
            }, 200);
            
            // Emit event
            window.dispatchEvent(new CustomEvent('playerExpansionChanged', {
                detail: {
                    expanded: false,
                    source: 'responsivePlayer'
                }
            }));
            
            window.Helpers.log('ResponsivePlayer: Player collapsed');
        },
        
        /**
         * Setup expanded mode features
         */
        setupExpandedMode: function() {
            // Add keyboard listeners for expanded mode
            document.addEventListener('keydown', this.handleExpandedKeydown);
            
            // Add escape hint (if helpful)
            if (window.showToast) {
                setTimeout(() => {
                    window.showToast('Press Escape to exit fullscreen', 'info', 3000);
                }, 500);
            }
        },
        
        /**
         * Cleanup expanded mode features
         */
        cleanupExpandedMode: function() {
            // Remove keyboard listeners
            document.removeEventListener('keydown', this.handleExpandedKeydown);
        },
        
        /**
         * Handle expanded mode keydown
         */
        handleExpandedKeydown: function(event) {
            if (event.key === 'Escape') {
                window.ResponsivePlayer.collapsePlayer();
            }
        },
        
        /**
         * Update expand button icon
         */
        updateExpandButton: function(iconClass) {
            if (this.elements.expandIcon) {
                this.elements.expandIcon.className = iconClass;
            }
        },
        
        /**
         * Show volume slider
         */
        showVolumeSlider: function() {
            const volumeSlider = document.getElementById('volumeProgressBar');
            if (volumeSlider) {
                volumeSlider.style.display = 'block';
                this.state.volumeSliderVisible = true;
                
                // Sync with current volume
                const currentVolume = window.StateManager?.getProperty('volume') || 0.7;
                const linearValue = window.Helpers.volume.calculateLinearSliderValue(currentVolume);
                volumeSlider.value = linearValue;
                
                // Auto-hide after delay
                clearTimeout(this.state.volumeSliderTimeout);
                this.state.volumeSliderTimeout = setTimeout(() => {
                    if (!volumeSlider.matches(':active')) {
                        this.hideVolumeSlider();
                    }
                }, 2000);
            }
        },
        
        /**
         * Hide volume slider
         */
        hideVolumeSlider: function() {
            const volumeSlider = document.getElementById('volumeProgressBar');
            if (volumeSlider) {
                volumeSlider.style.display = 'none';
                this.state.volumeSliderVisible = false;
            }
        },
        
        /**
         * Set volume
         */
        setVolume: function(volume) {
            // Emit volume change request
            window.dispatchEvent(new CustomEvent('requestVolumeChange', {
                detail: { 
                    volume: volume,
                    source: 'responsivePlayer'
                }
            }));
        },
        
        /**
         * Adapt player to device capabilities
         */
        adaptToDevice: function(deviceInfo) {
            // Get device info from DeviceManager if not provided
            if (!deviceInfo && window.DeviceManager) {
                deviceInfo = {
                    touchSupport: window.DeviceManager.touchSupport,
                    deviceType: window.DeviceManager.deviceType,
                    screenCategory: window.DeviceManager.screenCategory
                };
            }
            
            // Default to desktop if still no device info
            if (!deviceInfo) {
                deviceInfo = {
                    touchSupport: false,
                    deviceType: 'desktop',
                    screenCategory: 'medium'
                };
            }
            
            if (deviceInfo.touchSupport) {
                this.applyTouchOptimizations();
            } else {
                this.applyDesktopOptimizations();
            }
            
            if (deviceInfo.deviceType === 'mobile') {
                this.applyMobileOptimizations();
            } else if (deviceInfo.deviceType === 'desktop') {
                this.applyDesktopSpecificOptimizations();
            }
        },
        
        /**
         * Adapt player to screen category
         */
        adaptToScreenCategory: function(screenCategory) {
            switch (screenCategory) {
                case 'small':
                    this.applySmallScreenOptimizations();
                    break;
                case 'medium':
                    this.applyMediumScreenOptimizations();
                    break;
                case 'large':
                    this.applyLargeScreenOptimizations();
                    break;
            }
        },
        
        /**
         * Apply touch optimizations
         */
        applyTouchOptimizations: function() {
            // Larger touch targets
            const controls = this.elements.playerControls?.querySelectorAll('.mobile-btn');
            if (controls) {
                controls.forEach(btn => {
                    btn.style.minHeight = '44px';
                    btn.style.minWidth = '44px';
                });
            }
        },
        
        /**
         * Apply desktop optimizations
         */
        applyDesktopOptimizations: function() {
            // Hover effects, tooltips
            const controls = this.elements.playerControls?.querySelectorAll('.mobile-btn');
            if (controls) {
                controls.forEach(btn => {
                    btn.addEventListener('mouseenter', () => {
                        btn.style.transform = 'scale(1.05)';
                    });
                    btn.addEventListener('mouseleave', () => {
                        btn.style.transform = 'scale(1)';
                    });
                });
            }
        },
        
        /**
         * Apply mobile-specific optimizations
         */
        applyMobileOptimizations: function() {
            // Compact layout, optimized gestures
            if (this.elements.playerInfo) {
                this.elements.playerInfo.style.flexDirection = 'row';
            }
        },
        
        /**
         * Apply desktop-specific optimizations
         */
        applyDesktopSpecificOptimizations: function() {
            // More spacious layout, hover effects
            if (this.elements.playerInfo) {
                this.elements.playerInfo.style.flexDirection = 'column';
            }
        },
        
        /**
         * Apply small screen optimizations
         */
        applySmallScreenOptimizations: function() {
            // Compact everything
            if (this.elements.playerControls) {
                this.elements.playerControls.style.padding = '8px';
            }
        },
        
        /**
         * Apply medium screen optimizations
         */
        applyMediumScreenOptimizations: function() {
            // Balanced layout
            if (this.elements.playerControls) {
                this.elements.playerControls.style.padding = '12px';
            }
        },
        
        /**
         * Apply large screen optimizations
         */
        applyLargeScreenOptimizations: function() {
            // Spacious layout
            if (this.elements.playerControls) {
                this.elements.playerControls.style.padding = '16px';
            }
        },
        
        /**
         * Adapt controls to screen size
         */
        adaptControlsToScreenSize: function() {
            const screenCategory = window.DeviceManager?.getScreenCategory() || 'medium';
            this.adaptToScreenCategory(screenCategory);
        },
        
        /**
         * Handle state changes from StateManager
         */
        handleStateChange: function(oldState, newState) {
            // React to relevant state changes
            if (oldState.currentSongId !== newState.currentSongId) {
                // Song changed - update cover image
                this.updateCoverImage(newState);
            }
            
            if (oldState.playing !== newState.playing) {
                // Playback state changed
                this.updatePlaybackState(newState.playing);
            }
        },
        
        /**
         * Update cover image display
         */
        updateCoverImage: function(state) {
            if (!this.elements.coverImage || !this.elements.coverFallback) return;
            
            // Get artwork from current song data or use fallback
            const hasArtwork = state.currentSongId && state.hasLyrics; // Using hasLyrics as proxy for artwork
            
            if (hasArtwork) {
                // Load artwork via existing system
                if (window.ImageManager) {
                    window.ImageManager.loadCoverImage(state.currentSongId);
                }
                if (this.elements.coverImage) {
                    this.elements.coverImage.style.display = 'block';
                }
                if (this.elements.coverFallback) {
                    this.elements.coverFallback.style.display = 'none';
                }
            } else {
                if (this.elements.coverImage) {
                    this.elements.coverImage.style.display = 'none';
                }
                if (this.elements.coverFallback) {
                    this.elements.coverFallback.style.display = 'block';
                }
            }
        },
        
        /**
         * Update playback state display
         */
        updatePlaybackState: function(playing) {
            // Update play/pause button
            const playPauseIcon = document.getElementById('playPauseIcon');
            if (playPauseIcon) {
                playPauseIcon.className = playing ? 'pi pi-pause' : 'pi pi-play';
            }
        },
        
        /**
         * Handle player action requests
         */
        handlePlayerAction: function(action, params = {}) {
            switch (action) {
                case 'expand':
                    this.expandPlayer();
                    break;
                case 'collapse':
                    this.collapsePlayer();
                    break;
                case 'toggleExpansion':
                    this.togglePlayerExpansion();
                    break;
                case 'showVolume':
                    this.showVolumeSlider();
                    break;
                case 'hideVolume':
                    this.hideVolumeSlider();
                    break;
                default:
                    if (window.Helpers) {
                        window.Helpers.log('ResponsivePlayer: Unknown player action:', action);
                    }
            }
        },
        
        /**
         * Update UI from current state
         */
        updateUIFromState: function() {
            if (this.state.isExpanded) {
                this.expandPlayer();
            }
            
            if (!this.state.playerVisible) {
                this.hidePlayer();
            } else {
                this.showPlayer();
            }
        },
        
        /**
         * Show player
         */
        showPlayer: function() {
            if (this.elements.player) {
                this.elements.player.style.display = 'block';
            }
        },
        
        /**
         * Hide player
         */
        hidePlayer: function() {
            if (this.elements.player) {
                this.elements.player.style.display = 'none';
            }
        },
        
        /**
         * Get current player state
         */
        getPlayerState: function() {
            return {
                ...this.state,
                deviceType: window.DeviceManager?.getDeviceType() || 'unknown',
                screenCategory: window.DeviceManager?.getScreenCategory() || 'unknown'
            };
        },
        
        /**
         * Reset player to default state
         */
        reset: function() {
            this.state = {
                isExpanded: false,
                expanding: false,
                playerVisible: true,
                touchStartY: 0,
                touchStartTime: 0,
                initialProgressValue: 0,
                volumeSliderVisible: false
            };
            
            this.updateUIFromState();
            this.saveState();
            
            window.Helpers.log('ResponsivePlayer: Reset to default state');
        }
    };
    
    // Auto-initialize when dependencies are available
    try {
        if (window.Helpers && window.DeviceManager && window.StateManager) {
            window.ResponsivePlayer.init();
        } else {
            setTimeout(() => {
                if (window.Helpers && window.DeviceManager && window.StateManager) {
                    window.ResponsivePlayer.init();
                }
            }, 100);
        }
    } catch (error) {
        console.error('ResponsivePlayer initialization failed:', error);
        // Fallback initialization after delay
        setTimeout(() => {
            if (window.Helpers && window.DeviceManager && window.StateManager) {
                window.ResponsivePlayer.init();
            }
        }, 200);
    }
    
})(window);