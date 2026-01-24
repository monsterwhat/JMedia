// Mobile Compatibility Layer - Ensures musicBar.js works with mobile.html
class MobileCompatibility {
    constructor() {
        this.init();
    }
    
    init() {
        console.log('[MobileCompatibility] Setting up mobile compatibility...');
        this.setupMobileSync();
    }
    
    setupMobileSync() {
        // Simple sync with existing musicBar.js state
        setInterval(() => {
            if (window.musicState) {
                this.updateMobilePlayer();
                this.updateMobileControls();
                this.updateMobileProgress();
            }
        }, 150);
    }
    
    updateMobilePlayer() {
        const mobileTitle = document.getElementById('songTitle');
        const mobileArtist = document.getElementById('songArtist');
        const mobileArtwork = document.getElementById('songCoverImage');
        
        if (mobileTitle) {
            mobileTitle.textContent = window.musicState.songName || 'Select a song';
        }
        
        if (mobileArtist) {
            mobileArtist.textContent = window.musicState.artist || 'No artist';
        }
        
        if (mobileArtwork) {
            if (window.musicState.coverArt) {
                mobileArtwork.style.backgroundImage = `url(${window.musicState.coverArt})`;
                mobileArtwork.style.backgroundSize = 'cover';
                mobileArtwork.style.backgroundPosition = 'center';
                mobileArtwork.innerHTML = '';
            } else {
                mobileArtwork.style.backgroundImage = '';
                mobileArtwork.innerHTML = '<i class="pi pi-music"></i>';
            }
        }
        
        // Update media session
        if (typeof window.updateMediaSessionMetadata === 'function') {
            window.updateMediaSessionMetadata(
                window.musicState.songName || 'Unknown Title',
                window.musicState.artist || 'Unknown Artist',
                window.musicState.coverArt || '/logo.png'
            );
        }
    }
    
    updateMobileControls() {
        const mobilePlayPause = document.getElementById('playPauseBtn');
        const mobileShuffle = document.getElementById('shuffleBtn');
        const mobileRepeat = document.getElementById('repeatBtn');
        
        if (mobilePlayPause) {
            const icon = mobilePlayPause.querySelector('i');
            if (icon) {
                icon.className = window.musicState.playing ? 'pi pi-pause' : 'pi pi-play';
            }
        }
        
        if (mobileShuffle) {
            mobileShuffle.style.color = window.musicState.shuffleMode === 'ON' ? 'var(--mobile-primary)' : 'var(--mobile-text)';
            mobileShuffle.style.opacity = window.musicState.shuffleMode === 'ON' ? '1' : '0.7';
        }
        
        if (mobileRepeat) {
            mobileRepeat.style.color = window.musicState.repeatMode === 'OFF' ? 'var(--mobile-text)' : 'var(--mobile-primary)';
            mobileRepeat.style.opacity = window.musicState.repeatMode === 'OFF' ? '0.7' : '1';
        }
    }
    
    updateMobileProgress() {
        const mobileCurrentTime = document.getElementById('currentTime');
        const mobileTotalTime = document.getElementById('totalTime');
        const mobileProgress = document.getElementById('playbackProgressBar');
        
        if (mobileCurrentTime) {
            mobileCurrentTime.textContent = window.formatTime ? 
                window.formatTime(window.musicState.currentTime) : 
                this.formatTime(window.musicState.currentTime);
        }
        
        if (mobileTotalTime) {
            mobileTotalTime.textContent = window.formatTime ? 
                window.formatTime(window.musicState.duration) : 
                this.formatTime(window.musicState.duration);
        }
        
        if (mobileProgress && window.musicState.duration > 0) {
            const progress = (window.musicState.currentTime / window.musicState.duration) * 100;
            mobileProgress.value = progress;
        }
    }
    
    formatTime(seconds) {
        if (!seconds || isNaN(seconds)) return '0:00';
        const minutes = Math.floor(seconds / 60);
        const secs = Math.floor(seconds % 60);
        return `${minutes}:${secs.toString().padStart(2, '0')}`;
    }
}

// Initialize compatibility layer after musicBar.js loads
document.addEventListener('DOMContentLoaded', () => {
    setTimeout(() => {
        window.mobileCompatibility = new MobileCompatibility();
        console.log('[MobileCompatibility] Mobile compatibility initialized');
    }, 300);
});