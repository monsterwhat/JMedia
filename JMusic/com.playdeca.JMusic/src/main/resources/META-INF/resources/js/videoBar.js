document.addEventListener('DOMContentLoaded', () => {
    const videoPlayer = document.getElementById('videoPlayer');
    const playPauseBtn = document.getElementById('playPauseBtn');
    const playPauseIcon = document.getElementById('playPauseIcon');
    const playbackProgressBar = document.getElementById('playbackProgressBar');
    const currentTimeSpan = document.getElementById('currentTime');
    const totalTimeSpan = document.getElementById('totalTime');
    const volumeProgressBar = document.getElementById('volumeProgressBar');
    const volumeIcon = document.querySelector('.pi-volume-up');
    const videoCurrentTitleDisplay = document.getElementById('videoCurrentTitle');
    const videoCurrentArtistDisplay = document.getElementById('videoCurrentArtist');
    const skipBack10Btn = document.getElementById('skipBack10Btn');
    const prevBtn = document.getElementById('prevBtn');
    const nextBtn = document.getElementById('nextBtn');
    const skipForward10Btn = document.getElementById('skipForward10Btn');

    // New buttons
    const fullscreenBtn = document.getElementById('fullscreenBtn');
    const fullscreenIcon = fullscreenBtn ? fullscreenBtn.querySelector('i') : null;
    const playbackSpeedBtn = document.getElementById('playbackSpeedBtn');
    const playbackSpeedDropdownItems = document.querySelectorAll('#dropdown-menu-speed .dropdown-item');
    const subtitlesBtn = document.getElementById('subtitlesBtn');

    // localStorage Keys
    const LAST_PLAYED_VIDEO_KEY = 'jmusic_lastPlayedVideo';
    const VOLUME_KEY = 'jmusic_videoVolume';
    const LAST_TAB_KEY = 'jmusic_videoTab';
    const SHOWS_PAGE_KEY = 'jmusic_showsPage';
    const MOVIES_PAGE_KEY = 'jmusic_moviesPage';
    const PLAYBACK_SPEED_KEY = 'jmusic_playbackSpeed';

    let isDraggingProgressBar = false;
    let isDraggingVolumeBar = false;
    let saveTimeDebounceTimeout;

    // Global variables to store remembered state before actual video object is available
    window.rememberedVideoId = null;
    window.rememberedVideoTime = null;

    // Helper to format time
    function formatTime(seconds) {
        const minutes = Math.floor(seconds / 60);
        const remainingSeconds = Math.floor(seconds % 60);
        return `${minutes}:${remainingSeconds < 10 ? '0' : ''}${remainingSeconds}`;
    }

    // Update play/pause icon
    function updatePlayPauseIcon() {
        if (videoPlayer.paused) {
            playPauseIcon.classList.remove('pi-pause');
            playPauseIcon.classList.add('pi-play');
        } else {
            playPauseIcon.classList.remove('pi-play');
            playPauseIcon.classList.add('pi-pause');
        }
    }

    // Set initial volume icon
    function setVolumeIcon() {
        if (!volumeIcon) return;
        volumeIcon.classList.remove('pi-volume-up', 'pi-volume-down', 'pi-volume-off');
        if (videoPlayer.volume === 0 || videoPlayer.muted) {
            volumeIcon.classList.add('pi-volume-off');
        } else if (videoPlayer.volume < 0.5) {
            volumeIcon.classList.add('pi-volume-down');
        } else {
            volumeIcon.classList.add('pi-volume-up');
        }
    }

    // Save playback state to localStorage
    function saveVideoPlaybackState() {
        if (videoPlayer.src && window.currentVideoId) { // Ensure a video is actually loaded and we have its ID
            const playbackState = {
                id: window.currentVideoId, // window.currentVideoId will be set by playVideo function
                currentTime: videoPlayer.currentTime
            };
            localStorage.setItem(LAST_PLAYED_VIDEO_KEY, JSON.stringify(playbackState));
        } else {
            localStorage.removeItem(LAST_PLAYED_VIDEO_KEY); // Clear if no video is playing
        }
    }

    // Save volume state to localStorage
    function saveVolumeState() {
        localStorage.setItem(VOLUME_KEY, videoPlayer.volume.toString());
    }
    
    // Save playback speed state to localStorage
    function savePlaybackSpeedState(speed) {
        localStorage.setItem(PLAYBACK_SPEED_KEY, speed.toString());
    }


    // Load volume state from localStorage
    function loadVolumeState() {
        const savedVolume = localStorage.getItem(VOLUME_KEY);
        if (savedVolume !== null) {
            videoPlayer.volume = parseFloat(savedVolume);
            volumeProgressBar.value = videoPlayer.volume * 100;
            volumeProgressBar.style.setProperty('--progress-value', `${videoPlayer.volume * 100}%`);
        } else {
            // If no saved volume, use current default (which is usually 1) and save it
            videoPlayer.volume = 1; // Default to full volume
            volumeProgressBar.value = 100;
            volumeProgressBar.style.setProperty('--progress-value', `100%`);
            saveVolumeState();
        }
        setVolumeIcon(); // Update icon based on loaded/default volume
    }

    // Load last played video state from localStorage
    function loadVideoPlaybackState() {
        const savedPlaybackState = localStorage.getItem(LAST_PLAYED_VIDEO_KEY);
        if (savedPlaybackState) {
            const { id, currentTime } = JSON.parse(savedPlaybackState);
            window.rememberedVideoId = id;
            window.rememberedVideoTime = currentTime;
        }
    }

    // Load playback speed state from localStorage
    function loadPlaybackSpeedState() {
        const savedSpeed = localStorage.getItem(PLAYBACK_SPEED_KEY);
        if (savedSpeed !== null) {
            const speed = parseFloat(savedSpeed);
            videoPlayer.playbackRate = speed;
            playbackSpeedDropdownItems.forEach(item => {
                if (parseFloat(item.dataset.speed) === speed) {
                    item.classList.add('is-active');
                } else {
                    item.classList.remove('is-active');
                }
            });
        } else {
            // Default to 1.0x if not saved
            videoPlayer.playbackRate = 1.0;
            playbackSpeedDropdownItems.forEach(item => {
                if (parseFloat(item.dataset.speed) === 1.0) {
                    item.classList.add('is-active');
                } else {
                    item.classList.remove('is-active');
                }
            });
            savePlaybackSpeedState(1.0);
        }
    }

    // Video Player Event Listeners
    if (videoPlayer) {
        videoPlayer.addEventListener('timeupdate', () => {
            if (!isDraggingProgressBar && videoPlayer.duration) {
                const progress = (videoPlayer.currentTime / videoPlayer.duration) * 100;
                playbackProgressBar.value = progress;
                playbackProgressBar.style.setProperty('--progress-value', `${progress}%`);
                currentTimeSpan.textContent = formatTime(videoPlayer.currentTime);

                // Debounce saving current time
                clearTimeout(saveTimeDebounceTimeout);
                saveTimeDebounceTimeout = setTimeout(saveVideoPlaybackState, 2000); // Save every 2 seconds
            }
        });

        videoPlayer.addEventListener('loadedmetadata', () => {
            totalTimeSpan.textContent = formatTime(videoPlayer.duration);
            playbackProgressBar.max = 100;
            playbackProgressBar.value = 0;
            playbackProgressBar.style.setProperty('--progress-value', `0%`);
            currentTimeSpan.textContent = '0:00';
            updatePlayPauseIcon();
            volumeProgressBar.style.setProperty('--progress-value', `${videoPlayer.volume * 100}%`);
            
            // If a video was remembered, seek to its position
            if (window.rememberedVideoId && videoPlayer.src.includes(window.rememberedVideoId) && window.rememberedVideoTime) {
                 videoPlayer.currentTime = window.rememberedVideoTime;
                 // Clear remembered state after seeking
                 window.rememberedVideoId = null;
                 window.rememberedVideoTime = null;
                 localStorage.removeItem(LAST_PLAYED_VIDEO_KEY);
            }
        });

        videoPlayer.addEventListener('play', updatePlayPauseIcon);
        videoPlayer.addEventListener('pause', updatePlayPauseIcon);
        videoPlayer.addEventListener('ended', () => {
            saveVideoPlaybackState(); // Save final state on end
            // Logic for playing next video in queue, if any
            // For now, just reset the play button
            videoPlayer.currentTime = 0;
            updatePlayPauseIcon();
            playbackProgressBar.value = 0;
            playbackProgressBar.style.setProperty('--progress-value', `0%`);
        });

        videoPlayer.addEventListener('volumechange', () => {
            if (!isDraggingVolumeBar) {
                volumeProgressBar.value = videoPlayer.volume * 100;
                volumeProgressBar.style.setProperty('--progress-value', `${videoPlayer.volume * 100}%`);
            }
            setVolumeIcon();
            saveVolumeState(); // Save volume immediately
        });
    }


    // Play/Pause Button
    if (playPauseBtn) {
        playPauseBtn.addEventListener('click', () => {
            if (videoPlayer.paused) {
                videoPlayer.play();
            } else {
                videoPlayer.pause();
            }
        });
    }

    // Playback Progress Bar (seeking)
    if (playbackProgressBar) {
        playbackProgressBar.addEventListener('mousedown', () => isDraggingProgressBar = true);
        playbackProgressBar.addEventListener('mouseup', () => isDraggingProgressBar = false);
        playbackProgressBar.addEventListener('input', () => {
            if (videoPlayer.duration) {
                const seekTime = (playbackProgressBar.value / 100) * videoPlayer.duration;
                videoPlayer.currentTime = seekTime;
                currentTimeSpan.textContent = formatTime(seekTime);
                playbackProgressBar.style.setProperty('--progress-value', `${playbackProgressBar.value}%`);
            }
        });
    }

    // Volume Progress Bar
    if (volumeProgressBar) {
        volumeProgressBar.addEventListener('mousedown', () => isDraggingVolumeBar = true);
        volumeProgressBar.addEventListener('mouseup', () => {
            isDraggingVolumeBar = false;
            saveVolumeState(); // Save volume when drag ends
        });
        volumeProgressBar.addEventListener('input', () => {
            videoPlayer.volume = volumeProgressBar.value / 100;
            volumeProgressBar.style.setProperty('--progress-value', `${volumeProgressBar.value}%`);
        });
        // Set initial volume is handled by loadVolumeState
    }

    // Skip Back 10 Seconds Button
    if (skipBack10Btn) {
        skipBack10Btn.addEventListener('click', () => {
            videoPlayer.currentTime = Math.max(0, videoPlayer.currentTime - 10);
        });
    }

    // Skip Forward 10 Seconds Button
    if (skipForward10Btn) {
        skipForward10Btn.addEventListener('click', () => {
            videoPlayer.currentTime = Math.min(videoPlayer.duration, videoPlayer.currentTime + 10);
        });
    }

    if (prevBtn) {
        prevBtn.addEventListener('click', () => {
            alert('Previous video functionality not yet implemented.');
        });
    }
    if (nextBtn) {
        nextBtn.addEventListener('click', () => {
            alert('Next video functionality not yet implemented.');
        });
    }

    // Fullscreen button functionality
    if (fullscreenBtn) {
        fullscreenBtn.addEventListener('click', () => {
            const videoContainer = videoPlayer.parentElement; // The div containing video and overlay
            if (!document.fullscreenElement) {
                if (videoContainer.requestFullscreen) {
                    videoContainer.requestFullscreen();
                } else if (videoContainer.mozRequestFullScreen) { /* Firefox */
                    videoContainer.mozRequestFullScreen();
                } else if (videoContainer.webkitRequestFullscreen) { /* Chrome, Safari & Opera */
                    videoContainer.webkitRequestFullscreen();
                } else if (videoContainer.msRequestFullscreen) { /* IE/Edge */
                    videoContainer.msRequestFullscreen();
                }
            } else {
                if (document.exitFullscreen) {
                    document.exitFullscreen();
                } else if (document.mozCancelFullScreen) { /* Firefox */
                    document.mozCancelFullScreen();
                } else if (document.webkitExitFullscreen) { /* Chrome, Safari and Opera */
                    document.webkitExitFullscreen();
                } else if (document.msExitFullscreen) { /* IE/Edge */
                    document.msExitFullscreen();
                }
            }
        });
    }

    // Update fullscreen icon based on state
    document.addEventListener('fullscreenchange', () => {
        if (document.fullscreenElement) {
            fullscreenIcon.classList.remove('pi-window-maximize');
            fullscreenIcon.classList.add('pi-window-minimize');
        } else {
            fullscreenIcon.classList.remove('pi-window-minimize');
            fullscreenIcon.classList.add('pi-window-maximize');
        }
    });

    // Playback Speed functionality
    playbackSpeedDropdownItems.forEach(item => {
        item.addEventListener('click', (e) => {
            e.preventDefault();
            const speed = parseFloat(e.target.dataset.speed);
            videoPlayer.playbackRate = speed;
            savePlaybackSpeedState(speed);

            // Update active class
            playbackSpeedDropdownItems.forEach(el => el.classList.remove('is-active'));
            e.target.classList.add('is-active');
        });
    });

    // Subtitles functionality
    if (subtitlesBtn) {
        subtitlesBtn.addEventListener('click', () => {
            const textTracks = videoPlayer.textTracks;
            let subtitlesActive = false;
            for (let i = 0; i < textTracks.length; i++) {
                if (textTracks[i].kind === 'subtitles' || textTracks[i].kind === 'captions') {
                    if (textTracks[i].mode === 'showing') {
                        textTracks[i].mode = 'hidden';
                    } else {
                        textTracks[i].mode = 'showing';
                        subtitlesActive = true;
                    }
                }
            }
            if (subtitlesActive) {
                subtitlesBtn.classList.add('has-text-info'); // Highlight if active
            } else {
                subtitlesBtn.classList.remove('has-text-info');
            }
        });
    }


    // Initial state setup for subtitles button based on default track mode
    videoPlayer.addEventListener('loadedmetadata', () => {
        let subtitlesExist = false;
        let subtitlesAreShowing = false;
        for (let i = 0; i < videoPlayer.textTracks.length; i++) {
            if (videoPlayer.textTracks[i].kind === 'subtitles' || videoPlayer.textTracks[i].kind === 'captions') {
                subtitlesExist = true;
                if (videoPlayer.textTracks[i].mode === 'showing') {
                    subtitlesAreShowing = true;
                }
            }
        }
        if (subtitlesBtn) {
            if (subtitlesExist) {
                subtitlesBtn.style.display = ''; // Show button if tracks exist
                if (subtitlesAreShowing) {
                    subtitlesBtn.classList.add('has-text-info');
                } else {
                    subtitlesBtn.classList.remove('has-text-info');
                }
            } else {
                subtitlesBtn.style.display = 'none'; // Hide button if no tracks
            }
        }
    });


    // Clear initial text for title/artist (these elements are now videoCurrentTitleDisplay/videoCurrentArtistDisplay)
    if (videoCurrentTitleDisplay) videoCurrentTitleDisplay.textContent = 'Select a video to play';
    if (videoCurrentArtistDisplay) videoCurrentArtistDisplay.textContent = '';


    // Helper function to apply marquee animation if text overflows
    function checkAndApplyMarquee(element, parent) { // Parent is now passed in
        if (!element || !parent) return;

        // Temporarily remove animation to get accurate scrollWidth
        element.classList.remove('is-marquee-animating');
        element.style.removeProperty('--marquee-duration');
        element.style.removeProperty('--marquee-distance');


        if (element.scrollWidth > parent.clientWidth + 1) { 
            const textLength = element.scrollWidth;
            const containerWidth = parent.clientWidth;
            const overflowAmount = textLength - containerWidth;
            
            // Calculate duration: 1 second per 50 pixels of overflow (adjust as needed)
            const duration = Math.max(5, overflowAmount / 50); 
            
            // Set CSS variables
            element.style.setProperty('--marquee-duration', `${duration}s`);
            element.style.setProperty('--marquee-distance', `-${overflowAmount}px`);
            
            element.classList.add('is-marquee-animating');
        } else {
            element.classList.remove('is-marquee-animating');
            element.style.removeProperty('--marquee-duration');
            element.style.removeProperty('--marquee-distance');
        }
    }

    // Export a function to update video info, which the existing playVideo can call
    window.updateVideoInfo = (video) => {
        window.currentVideoId = video.id; // Store current video ID globally accessible
        
        let displayTitle = video.title;
        let displaySubtext = '';

        if (video.type === 'Episode') {
            displaySubtext = video.seriesTitle || 'TV Show';
            let seasonNum = String(video.seasonNumber || 0).padStart(2, '0');
            let episodeNum = String(video.episodeNumber || 0).padStart(2, '0');
            displayTitle = `S${seasonNum}E${episodeNum}`;
            if (video.episodeTitle) {
                displayTitle += ` - ${video.episodeTitle}`;
            }
        } else if (video.type === 'Movie') {
            // The main title is already correct from the DTO
            if (video.releaseYear) {
                displaySubtext = `Movie (${video.releaseYear})`;
            } else {
                displaySubtext = 'Movie';
            }
        }

        if (videoCurrentTitleDisplay) videoCurrentTitleDisplay.textContent = displayTitle;
        if (videoCurrentArtistDisplay) videoCurrentArtistDisplay.textContent = displaySubtext;

        // Apply marquee effect if needed
        const titleParent = videoCurrentTitleDisplay.parentElement;
        if (titleParent) {
            checkAndApplyMarquee(videoCurrentTitleDisplay, titleParent);
        }
        const artistParent = videoCurrentArtistDisplay.parentElement;
        if (artistParent) {
            checkAndApplyMarquee(videoCurrentArtistDisplay, artistParent);
        }
    };

    // --- New Initialization Logic ---
    loadVolumeState();
    loadVideoPlaybackState(); // This will set window.rememberedVideoId and window.rememberedVideoTime
    loadPlaybackSpeedState(); // Load saved playback speed
});