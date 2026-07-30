function createEpisodeSidebarHTML() {
  return '\
  <div class="episode-sidebar-backdrop" id="episode-sidebar-backdrop" data-click="closeEpisodeSidebar"></div>\
  <div class="episode-sidebar" id="episode-sidebar" data-click="noop" data-stop-propagation>\
    <div class="episode-sidebar-glass">\
      <div class="episode-sidebar-glass-shimmer"></div>\
      <div class="episode-sidebar-glass-blob"></div>\
    </div>\
    <div class="episode-sidebar-header">\
      <span class="episode-sidebar-title">Episodes</span>\
      <div class="episode-sidebar-audio" id="episodeSidebarAudio" style="display:none;">\
        <button class="episode-sidebar-audio-btn" id="sidebarAudioBtn" data-click="toggleSidebarAudioMenu">\
          <i class="fa-solid fa-language"></i>\
          <span id="sidebarAudioLabel">Audio</span>\
          <i class="fa-solid fa-chevron-down" style="font-size:0.6rem;"></i>\
        </button>\
        <div class="episode-sidebar-audio-menu" id="sidebarAudioMenu" style="display:none;">\
          <div class="episode-sidebar-audio-header">Audio Tracks</div>\
          <div class="episode-sidebar-audio-list" id="sidebarAudioList"></div>\
        </div>\
      </div>\
      <button class="episode-sidebar-close" data-click="closeEpisodeSidebar">\
        <i class="fa-solid fa-xmark"></i>\
      </button>\
    </div>\
    <div class="episode-sidebar-seasons-wrap">\
      <button class="episode-sidebar-scroll-btn episode-sidebar-scroll-left" id="season-scroll-left" aria-label="Scroll seasons left" data-click="scrollSeasonsLeft" data-stop-propagation>\
        <i class="fa-solid fa-chevron-left"></i>\
      </button>\
      <div class="episode-sidebar-seasons" id="episode-sidebar-seasons"></div>\
      <button class="episode-sidebar-scroll-btn episode-sidebar-scroll-right" id="season-scroll-right" aria-label="Scroll seasons right" data-click="scrollSeasonsRight" data-stop-propagation>\
        <i class="fa-solid fa-chevron-right"></i>\
      </button>\
    </div>\
    <div class="episode-sidebar-list" id="episode-sidebar-list"></div>\
  </div>';
}
