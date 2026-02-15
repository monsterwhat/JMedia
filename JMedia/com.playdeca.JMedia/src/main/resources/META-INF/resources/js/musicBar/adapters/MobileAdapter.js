/**
 * MobileAdapter - Mobile-specific UI adaptations and optimizations
 * Handles mobile-specific behavior, gestures, and UI adjustments
 */
(function(window) {
    'use strict';
    
    window.MobileAdapter = {
        // Mobile-specific settings
        settings: {
            touchTargetSize: 44, // Minimum touch target size in pixels
            swipeThreshold: 50, // Minimum swipe distance
            longPressDuration: 500, // Long press duration in ms
            pullToRefreshThreshold: 100, // Pull to refresh threshold
            autoHideDelay: 2000, // Auto-hide UI elements delay
            animationDuration: 300 // Animation duration in ms
        },
        
        // Mobile state tracking
        state: {
            isMobile: false,
            touchActive: false,
            swipeInProgress: false,
            lastTouchTime: 0,
            autoHideTimer: null,
            statusBarHeight: 0,
            safeAreaInsets: { top: 0, right: 0, bottom: 0, left: 0 }
        },
        
        /**
         * Initialize mobile adapter
         */
        init: function() {
            this.detectMobileCapabilities();
            this.setupMobileOptimizations();
            this.setupSafeAreaHandling();
            this.setupMobileGestures();
            this.setupMobileEventListeners();
            window.Helpers.log('MobileAdapter initialized');
        },
        
        /**
         * Detect mobile capabilities
         */
        detectMobileCapabilities: function() {
            this.state.isMobile = window.DeviceManager ? 
                window.DeviceManager.shouldUseMobileUI() : 
                ('ontouchstart' in window && window.innerWidth <= 768);
            
            // Detect safe area insets (for notched devices)
            this.detectSafeAreaInsets();
            
            // Detect status bar height
            this.detectStatusBarHeight();
            
            window.Helpers.log('MobileAdapter: Mobile capabilities detected', {
                isMobile: this.state.isMobile,
                safeAreaInsets: this.state.safeAreaInsets,
                statusBarHeight: this.state.statusBarHeight
            });
        },
        
        /**
         * Detect safe area insets for notched devices
         */
        detectSafeAreaInsets: function() {
            const computedStyle = getComputedStyle(document.documentElement);
            
            // CSS custom properties for safe areas
            const safeAreaTop = parseInt(computedStyle.getPropertyValue('--safe-area-inset-top')) || 0;
            const safeAreaRight = parseInt(computedStyle.getPropertyValue('--safe-area-inset-right')) || 0;
            const safeAreaBottom = parseInt(computedStyle.getPropertyValue('--safe-area-inset-bottom')) || 0;
            const safeAreaLeft = parseInt(computedStyle.getPropertyValue('--safe-area-inset-left')) || 0;
            
            this.state.safeAreaInsets = {
                top: safeAreaTop,
                right: safeAreaRight,
                bottom: safeAreaBottom,
                left: safeAreaLeft
            };
        },
        
        /**
         * Detect status bar height
         */
        detectStatusBarHeight: function() {
            // Use CSS custom property if available
            const computedStyle = getComputedStyle(document.documentElement);
            const statusBarHeight = parseInt(computedStyle.getPropertyValue('--status-bar-height')) || 0;
            
            this.state.statusBarHeight = statusBarHeight;
        },
        
        /**
         * Setup mobile-specific optimizations
         */
        setupMobileOptimizations: function() {
            if (!this.state.isMobile) return;
            
            // Optimize touch targets
            this.optimizeTouchTargets();
            
            // Setup viewport handling
            this.setupViewportHandling();
            
            // Setup scroll behavior
            this.setupScrollBehavior();
            
            // Setup input handling
            this.setupInputHandling();
            
            // Setup performance optimizations
            this.setupPerformanceOptimizations();
        },
        
        /**
         * Optimize touch targets for mobile
         */
        optimizeTouchTargets: function() {
            // Ensure minimum touch target size
            const touchElements = document.querySelectorAll('button, .mobile-btn, input[type="range"], .mobile-song-item');
            
            touchElements.forEach(element => {
                const computedStyle = getComputedStyle(element);
                const width = parseInt(computedStyle.width);
                const height = parseInt(computedStyle.height);
                
                // Apply minimum touch target size
                if (width < this.settings.touchTargetSize) {
                    element.style.minWidth = this.settings.touchTargetSize + 'px';
                }
                if (height < this.settings.touchTargetSize) {
                    element.style.minHeight = this.settings.touchTargetSize + 'px';
                }
                
                // Add touch feedback
                element.addEventListener('touchstart', () => {
                    element.classList.add('touch-active');
                });
                
                element.addEventListener('touchend', () => {
                    setTimeout(() => {
                        element.classList.remove('touch-active');
                    }, 150);
                });
            });
        },
        
        /**
         * Setup viewport handling for mobile
         */
        setupViewportHandling: function() {
            // Prevent zoom on input focus
            const inputs = document.querySelectorAll('input, textarea, select');
            inputs.forEach(input => {
                input.addEventListener('focus', () => {
                    document.documentElement.style.fontSize = '16px';
                });
                
                input.addEventListener('blur', () => {
                    document.documentElement.style.fontSize = '';
                });
            });
            
            // Handle orientation changes
            window.addEventListener('orientationchange', () => {
                setTimeout(() => {
                    this.adjustForOrientation();
                }, 100);
            });
        },
        
        /**
         * Setup scroll behavior for mobile
         */
        setupScrollBehavior: function() {
            // Smooth scrolling for mobile
            document.addEventListener('touchstart', () => {
                this.state.touchActive = true;
            });
            
            document.addEventListener('touchend', () => {
                this.state.touchActive = false;
                this.state.lastTouchTime = Date.now();
            });
            
            // Prevent overscroll in modals
            this.preventOverscroll();
        },
        
        /**
         * Setup input handling for mobile
         */
        setupInputHandling: function() {
            // Auto-hide virtual keyboard when scrolling
            let scrollTimer;
            document.addEventListener('scroll', () => {
                clearTimeout(scrollTimer);
                scrollTimer = setTimeout(() => {
                    // Blur active input to hide keyboard
                    if (document.activeElement && 
                        (document.activeElement.tagName === 'INPUT' || 
                         document.activeElement.tagName === 'TEXTAREA')) {
                        document.activeElement.blur();
                    }
                }, 1000);
            });
        },
        
        /**
         * Setup performance optimizations for mobile
         */
        setupPerformanceOptimizations: function() {
            // Reduce motion for better performance
            if (window.matchMedia('(prefers-reduced-motion: reduce)').matches) {
                document.documentElement.style.setProperty('--animation-duration', '0.1s');
            }
            
            // Optimize images for mobile
            this.optimizeImages();
            
            // Setup lazy loading
            this.setupLazyLoading();
        },
        
        /**
         * Setup safe area handling for notched devices
         */
        setupSafeAreaHandling: function() {
            const safeArea = this.state.safeAreaInsets;
            
            // Apply safe area insets to relevant elements
            const elements = [
                '.mobile-header',
                '.mobile-player',
                '.mobile-side-panel',
                '.modal-card'
            ];
            
            elements.forEach(selector => {
                const element = document.querySelector(selector);
                if (element) {
                    // Apply padding for safe areas
                    if (safeArea.top > 0) {
                        element.style.paddingTop = `calc(${element.style.paddingTop || '0px'} + ${safeArea.top}px)`;
                    }
                    if (safeArea.bottom > 0) {
                        element.style.paddingBottom = `calc(${element.style.paddingBottom || '0px'} + ${safeArea.bottom}px)`;
                    }
                    if (safeArea.left > 0) {
                        element.style.paddingLeft = `calc(${element.style.paddingLeft || '0px'} + ${safeArea.left}px)`;
                    }
                    if (safeArea.right > 0) {
                        element.style.paddingRight = `calc(${element.style.paddingRight || '0px'} + ${safeArea.right}px)`;
                    }
                }
            });
        },
        
        /**
         * Setup mobile-specific gestures
         */
        setupMobileGestures: function() {
            // Pull to refresh
            this.setupPullToRefresh();
            
            // Swipe to dismiss
            this.setupSwipeToDismiss();
            
            // Double tap to like/favorite
            this.setupDoubleTapGestures();
        },
        
        /**
         * Setup pull to refresh gesture
         */
        setupPullToRefresh: function() {
            let startY = 0;
            let isPulling = false;
            const pullThreshold = this.settings.pullToRefreshThreshold;
            
            document.addEventListener('touchstart', (e) => {
                if (window.scrollY === 0) {
                    startY = e.touches[0].clientY;
                    isPulling = true;
                }
            });
            
            document.addEventListener('touchmove', (e) => {
                if (!isPulling) return;
                
                const currentY = e.touches[0].clientY;
                const deltaY = currentY - startY;
                
                if (deltaY > 0 && window.scrollY === 0) {
                    e.preventDefault();
                    
                    // Visual feedback
                    const songList = document.getElementById('mobileSongList');
                    if (songList) {
                        songList.style.transform = `translateY(${Math.min(deltaY * 0.5, pullThreshold)}px)`;
                    }
                }
            });
            
            document.addEventListener('touchend', (e) => {
                if (!isPulling) return;
                
                isPulling = false;
                
                const currentY = e.changedTouches[0].clientY;
                const deltaY = currentY - startY;
                
                // Reset transform
                const songList = document.getElementById('mobileSongList');
                if (songList) {
                    songList.style.transform = '';
                }
                
                // Trigger refresh if threshold exceeded
                if (deltaY > pullThreshold) {
                    this.triggerRefresh();
                }
            });
        },
        
        /**
         * Setup swipe to dismiss gesture
         */
        setupSwipeToDismiss: function() {
            const dismissibleElements = document.querySelectorAll('.mobile-song-item, .mobile-playlist-item');
            
            dismissibleElements.forEach(element => {
                let startX = 0;
                let isSwiping = false;
                
                element.addEventListener('touchstart', (e) => {
                    startX = e.touches[0].clientX;
                    isSwiping = true;
                });
                
                element.addEventListener('touchmove', (e) => {
                    if (!isSwiping) return;
                    
                    const currentX = e.touches[0].clientX;
                    const deltaX = currentX - startX;
                    
                    if (Math.abs(deltaX) > 10) {
                        e.preventDefault();
                        element.style.transform = `translateX(${deltaX}px)`;
                    }
                });
                
                element.addEventListener('touchend', (e) => {
                    if (!isSwiping) return;
                    
                    isSwiping = false;
                    
                    const currentX = e.changedTouches[0].clientX;
                    const deltaX = currentX - startX;
                    
                    // Reset transform
                    element.style.transform = '';
                    
                    // Trigger dismiss if swiped far enough
                    if (Math.abs(deltaX) > this.settings.swipeThreshold * 2) {
                        this.triggerDismiss(element, deltaX < 0);
                    }
                });
            });
        },
        
        /**
         * Setup double tap gestures
         */
        setupDoubleTapGestures: function() {
            const tapTargets = document.querySelectorAll('.mobile-song-item, .mobile-player-artwork');
            
            tapTargets.forEach(target => {
                let lastTap = 0;
                
                target.addEventListener('touchend', (e) => {
                    const currentTime = Date.now();
                    const tapLength = currentTime - lastTap;
                    
                    if (tapLength < 300 && tapLength > 0) {
                        // Double tap detected
                        e.preventDefault();
                        this.handleDoubleTap(target);
                    }
                    
                    lastTap = currentTime;
                });
            });
        },
        
        /**
         * Setup mobile event listeners
         */
        setupMobileEventListeners: function() {
            // Listen for device capability changes
            window.addEventListener('deviceCapabilitiesDetected', (e) => {
                if (e.detail.deviceType === 'mobile') {
                    this.setupMobileOptimizations();
                }
            });
            
            // Listen for screen category changes
            window.addEventListener('screenCategoryChanged', (e) => {
                if (e.detail.newCategory === 'small') {
                    this.setupMobileOptimizations();
                }
            });
            
            // Handle visibility changes
            document.addEventListener('visibilitychange', () => {
                if (document.hidden) {
                    this.pauseBackgroundTasks();
                } else {
                    this.resumeBackgroundTasks();
                }
            });
            
            // Re-setup swipe handlers after HTMX swaps (for dynamically added content)
            document.body.addEventListener('htmx:afterSwap', (e) => {
                if (e.detail && e.detail.target) {
                    const target = e.detail.target;
                    if (target.querySelector('.mobile-playlist-item') || target.classList?.contains('mobile-playlist-item')) {
                        this.setupSwipeToDismiss();
                    }
                }
            });
        },
        
        /**
         * Adjust for orientation change
         */
        adjustForOrientation: function() {
            // Re-detect safe areas
            this.detectSafeAreaInsets();
            
            // Re-apply safe area handling
            this.setupSafeAreaHandling();
            
            // Adjust viewport
            const viewport = document.querySelector('meta[name="viewport"]');
            if (viewport) {
                viewport.setAttribute('content', 'width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no');
            }
            
            window.Helpers.log('MobileAdapter: Adjusted for orientation change');
        },
        
        /**
         * Prevent overscroll in modals
         */
        preventOverscroll: function() {
            const modals = document.querySelectorAll('.modal, .mobile-modal, .mobile-context-menu');
            
            modals.forEach(modal => {
                modal.addEventListener('touchmove', (e) => {
                    const { scrollTop, scrollHeight, clientHeight } = modal;
                    
                    if (scrollTop === 0 && e.deltaY < 0) {
                        e.preventDefault();
                    } else if (scrollTop + clientHeight >= scrollHeight && e.deltaY > 0) {
                        e.preventDefault();
                    }
                }, { passive: false });
            });
        },
        
        /**
         * Optimize images for mobile
         */
        optimizeImages: function() {
            const images = document.querySelectorAll('img');
            
            images.forEach(img => {
                // Add loading="lazy" for performance
                if (!img.hasAttribute('loading')) {
                    img.setAttribute('loading', 'lazy');
                }
                
                // Optimize for mobile screens
                if (this.state.isMobile) {
                    img.style.maxWidth = '100%';
                    img.style.height = 'auto';
                }
            });
        },
        
        /**
         * Setup lazy loading
         */
        setupLazyLoading: function() {
            if ('IntersectionObserver' in window) {
                const lazyImages = document.querySelectorAll('img[data-src]');
                
                const imageObserver = new IntersectionObserver((entries) => {
                    entries.forEach(entry => {
                        if (entry.isIntersecting) {
                            const img = entry.target;
                            img.src = img.dataset.src;
                            img.removeAttribute('data-src');
                            imageObserver.unobserve(img);
                        }
                    });
                });
                
                lazyImages.forEach(img => imageObserver.observe(img));
            }
        },
        
        /**
         * Trigger refresh
         */
        triggerRefresh: function() {
            window.Helpers.log('MobileAdapter: Triggering refresh');
            
            // Emit refresh event
            window.dispatchEvent(new CustomEvent('requestRefresh', {
                detail: { source: 'mobileAdapter' }
            }));
            
            // Visual feedback
            if (window.showToast) {
                window.showToast('Refreshing...', 'info', 2000);
            }
        },
        
        /**
         * Trigger dismiss action
         */
        triggerDismiss: function(element, swipeLeft) {
            const songId = element.dataset.songId;
            const playlistId = element.dataset.playlistId;
            
            window.Helpers.log('MobileAdapter: Triggering dismiss', {
                element: element.tagName,
                songId: songId,
                playlistId: playlistId,
                swipeLeft: swipeLeft
            });
            
            // Check if this is a playlist item - trigger delete instead
            if (playlistId && !songId && playlistId !== '0') {
                const playlistName = element.querySelector('.mobile-playlist-name')?.textContent || 'this playlist';
                if (confirm(`Delete playlist "${playlistName}"?`)) {
                    deleteMobilePlaylist(playlistId, encodeURIComponent(playlistName));
                }
                return;
            }
            
            // Emit dismiss event
            window.dispatchEvent(new CustomEvent('requestDismiss', {
                detail: {
                    element: element,
                    songId: songId,
                    playlistId: playlistId,
                    swipeLeft: swipeLeft
                }
            }));
        },
        
        /**
         * Handle double tap gesture
         */
        handleDoubleTap: function(target) {
            window.Helpers.log('MobileAdapter: Double tap detected on', target.tagName);
            
            // Emit double tap event
            window.dispatchEvent(new CustomEvent('doubleTap', {
                detail: { target: target }
            }));
            
            // Visual feedback
            target.classList.add('double-tap-active');
            setTimeout(() => {
                target.classList.remove('double-tap-active');
            }, 300);
        },
        
        /**
         * Pause background tasks for performance
         */
        pauseBackgroundTasks: function() {
            // Pause animations
            document.body.style.animationPlayState = 'paused';
            
            // Clear auto-hide timers
            if (this.state.autoHideTimer) {
                clearTimeout(this.state.autoHideTimer);
            }
            
            window.Helpers.log('MobileAdapter: Paused background tasks');
        },
        
        /**
         * Resume background tasks
         */
        resumeBackgroundTasks: function() {
            // Resume animations
            document.body.style.animationPlayState = 'running';
            
            window.Helpers.log('MobileAdapter: Resumed background tasks');
        },
        
        /**
         * Get mobile adapter state
         */
        getAdapterState: function() {
            return {
                ...this.state,
                settings: {...this.settings}
            };
        },
        
        /**
         * Check if running on mobile
         */
        isMobile: function() {
            return this.state.isMobile;
        },
        
        /**
         * Get safe area insets
         */
        getSafeAreaInsets: function() {
            return {...this.state.safeAreaInsets};
        },
        
        /**
         * Get status bar height
         */
        getStatusBarHeight: function() {
            return this.state.statusBarHeight;
        }
    };
    
    // Auto-initialize when dependencies are available
    if (window.Helpers && window.DeviceManager) {
        window.MobileAdapter.init();
    } else {
        // Wait for dependencies
        const checkDeps = () => {
            if (window.Helpers && window.DeviceManager) {
                window.MobileAdapter.init();
            } else {
                setTimeout(checkDeps, 50);
            }
        };
        setTimeout(checkDeps, 50);
    }
    
})(window);