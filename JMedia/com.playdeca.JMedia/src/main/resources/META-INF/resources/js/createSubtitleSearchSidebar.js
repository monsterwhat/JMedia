function createSubtitleSearchSidebarHTML() {
  return '\
  <div class="episode-sidebar-backdrop" id="subtitle-search-backdrop" data-click="closeSubtitleSearchSidebar"></div>\
  <div class="episode-sidebar" id="subtitle-search-sidebar" data-click="noop" data-stop-propagation>\
    <div class="episode-sidebar-glass">\
      <div class="episode-sidebar-glass-shimmer"></div>\
      <div class="episode-sidebar-glass-blob"></div>\
    </div>\
    <div class="episode-sidebar-header">\
      <span class="episode-sidebar-title">Subtitles</span>\
      <button class="episode-sidebar-close" data-click="closeSubtitleSearchSidebar">\
        <i class="fa-solid fa-xmark"></i>\
      </button>\
    </div>\
    <div class="episode-sidebar-seasons" id="subtitle-search-tabs">\
      <button class="episode-sidebar-season-btn active" data-click="switchSubtitleSearchTab:search">Search</button>\
      <button class="episode-sidebar-season-btn" data-click="switchSubtitleSearchTab:local">Local Files</button>\
    </div>\
    <div class="subtitle-search-body" id="subtitle-search-search-tab">\
      <form class="subtitle-search-form" onsubmit="return false;">\
        <input class="subtitle-search-input" id="subtitleSearchQuery" placeholder="Search by name..." onkeydown="if(event.key===\'Enter\')runSubtitleSearch()">\
        <select class="subtitle-search-lang" id="subtitleSearchLang">\
          <option value="en">English</option>\
          <option value="es">Español</option>\
          <option value="spl">Español (Latinoamérica)</option>\
          <option value="fr">Français</option>\
          <option value="de">Deutsch</option>\
          <option value="it">Italiano</option>\
          <option value="pt">Português</option>\
          <option value="ru">Русский</option>\
          <option value="ja">日本語</option>\
          <option value="ko">한국어</option>\
          <option value="zh">中文</option>\
        </select>\
        <button class="subtitle-search-go" data-click="runSubtitleSearch" title="Search">\
          <i class="fa-solid fa-magnifying-glass"></i>\
        </button>\
      </form>\
      <div class="episode-sidebar-list" id="subtitle-search-results-body">\
        <div class="subtitle-search-empty">Search to find subtitles online</div>\
      </div>\
    </div>\
    <div class="subtitle-search-body" id="subtitle-search-local-tab" style="display:none;">\
      <form class="subtitle-search-form" onsubmit="return false;">\
        <button class="subtitle-search-go" data-click="scanLocalSubtitles">\
          <i class="fa-solid fa-rotate"></i>\
          <span>Rescan Folder</span>\
        </button>\
      </form>\
      <div class="episode-sidebar-list" id="subtitleLocalResultsBody">\
        <div class="subtitle-search-empty">Scan the video folder for subtitle files</div>\
      </div>\
    </div>\
  </div>';
}
