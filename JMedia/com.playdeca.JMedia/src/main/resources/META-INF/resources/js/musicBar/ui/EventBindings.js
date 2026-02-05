/**
 * EventBindings - Button and slider event handlers
 * Binds all user interface elements to appropriate functions
 */
(function(window) {
    'use strict';
    
    window.EventBindings = {
        /**
         * Initialize event bindings
         */
        init: function() {
            this.bindPlaybackButtons();
            window.Helpers.log('EventBindings initialized');
        },
        
        /**
         * Bind playback button events
         */
        bindPlaybackButtons: function() {
            // Play/Pause button
            const playPauseBtn = document.getElementById('playPauseBtn');
            if (playPauseBtn) {
                // Remove existing event listeners by cloning and replacing element
                const newPlayPauseBtn = playPauseBtn.cloneNode(true);
                playPauseBtn.parentNode.replaceChild(newPlayPauseBtn, playPauseBtn);
                
                newPlayPauseBtn.onclick = () => {
                    window.dispatchEvent(new CustomEvent('requestPlaybackControl', {
                        detail: { action: 'playPause', profileId: window.globalActiveProfileId }
                    }));
                };
            }
            
            // Previous button
            const prevBtn = document.getElementById('prevBtn');
            if (prevBtn) {
                const newPrevBtn = prevBtn.cloneNode(true);
                prevBtn.parentNode.replaceChild(newPrevBtn, prevBtn);
                
                newPrevBtn.onclick = () => {
                    window.dispatchEvent(new CustomEvent('requestPlaybackControl', {
                        detail: { action: 'previous', profileId: window.globalActiveProfileId }
                    }));
                };
            }
            
            // Next button
            const nextBtn = document.getElementById('nextBtn');
            if (nextBtn) {
                const newNextBtn = nextBtn.cloneNode(true);
                nextBtn.parentNode.replaceChild(newNextBtn, nextBtn);
                
                newNextBtn.onclick = () => {
                    window.dispatchEvent(new CustomEvent('requestPlaybackControl', {
                        detail: { action: 'next', profileId: window.globalActiveProfileId }
                    }));
                };
            }
            
            // Shuffle button
            const shuffleBtn = document.getElementById('shuffleBtn');
            if (shuffleBtn) {
                const newShuffleBtn = shuffleBtn.cloneNode(true);
                shuffleBtn.parentNode.replaceChild(newShuffleBtn, shuffleBtn);
                
                newShuffleBtn.onclick = () => {
                    window.dispatchEvent(new CustomEvent('requestPlaybackControl', {
                        detail: { action: 'shuffle', profileId: window.globalActiveProfileId }
                    }));
                };
            }
            
            // Repeat button
            const repeatBtn = document.getElementById('repeatBtn');
            if (repeatBtn) {
                const newRepeatBtn = repeatBtn.cloneNode(true);
                repeatBtn.parentNode.replaceChild(newRepeatBtn, repeatBtn);
                
                newRepeatBtn.onclick = () => {
                    window.dispatchEvent(new CustomEvent('requestPlaybackControl', {
                        detail: { action: 'repeat', profileId: window.globalActiveProfileId }
                    }));
                };
            }
            
            window.Helpers.log('EventBindings: Playback buttons bound');
        },
        
        /**
         * Remove all event bindings
         */
        cleanup: function() {
            const buttons = ['playPauseBtn', 'prevBtn', 'nextBtn', 'shuffleBtn', 'repeatBtn'];
            
            buttons.forEach(btnId => {
                const btn = document.getElementById(btnId);
                if (btn) {
                    btn.onclick = null;
                }
            });
            
            window.Helpers.log('EventBindings: Event bindings cleaned up');
        },
        
        /**
         * Get binding status
         * @returns {Object} Current binding status
         */
        getBindingStatus: function() {
            const buttons = ['playPauseBtn', 'prevBtn', 'nextBtn', 'shuffleBtn', 'repeatBtn'];
            const status = {};
            
            buttons.forEach(btnId => {
                const btn = document.getElementById(btnId);
                status[btnId] = {
                    element: btn,
                    hasOnClick: btn && typeof btn.onclick === 'function'
                };
            });
            
            return status;
        }
    };
    
    // Auto-initialize when dependencies are available
    if (window.Helpers) {
        window.EventBindings.init();
    } else {
        // Wait for dependencies
        const checkDeps = () => {
            if (window.Helpers) {
                window.EventBindings.init();
            } else {
                setTimeout(checkDeps, 50);
            }
        };
        setTimeout(checkDeps, 50);
    }
    
})(window);