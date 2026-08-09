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
      <button class="episode-sidebar-season-btn" data-click="switchSubtitleSearchTab:upload">Upload</button>\
      <button class="episode-sidebar-season-btn" data-click="switchSubtitleSearchTab:ai">AI Generation</button>\
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
    <div class="subtitle-search-body" id="subtitle-search-upload-tab" style="display:none;">\
      <form class="subtitle-search-form" onsubmit="return false;">\
        <label class="subtitle-upload-drop" for="subtitleFileInput">\
          <i class="fa-solid fa-cloud-arrow-up"></i>\
          <span class="subtitle-upload-name" id="subtitleUploadFileName">No file selected</span>\
        </label>\
        <input class="subtitle-upload-input" type="file" id="subtitleFileInput" accept=".srt,.vtt,.ass,.ssa,.sub,.idx" onchange="subtitleUploadFileSelected(event)">\
        <select class="subtitle-search-lang" id="subtitleUploadLanguage">\
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
        <input class="subtitle-search-input" id="subtitleUploadName" placeholder="Display name (optional)">\
        <button class="subtitle-search-go" id="uploadSubtitleBtn" data-click="uploadSubtitleFile" disabled>\
          <i class="fa-solid fa-upload"></i>\
          <span>Upload</span>\
        </button>\
      </form>\
      <div class="subtitle-upload-hint">Pick a subtitle file (.srt, .vtt, .ass, .ssa, .sub, .idx) to upload for this video.</div>\
    </div>\
    <div class="subtitle-search-body" id="subtitle-search-ai-tab" style="display:none;">\
      <form class="subtitle-search-form" onsubmit="return false;">\
        <select class="subtitle-search-lang" id="subtitleSearchAiLang">\
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
        <select class="subtitle-search-lang" id="subtitleSearchAiAudio" title="Audio track to transcribe">\
          <option value="">Default audio track</option>\
        </select>\
        <button class="subtitle-search-go generate" id="startAiSidebarBtn" data-click="generateAiSubtitles">\
          <i class="fa-solid fa-magic"></i><span>Generate with AI</span>\
        </button>\
      </form>\
      <div class="subtitle-search-status" id="subtitleAiGenerationProgress" style="display:none;">\
        <i class="fa-solid fa-spinner fa-spin"></i><span id="subtitleAiGenerationStatus">Generating subtitles...</span>\
        <button class="subtitle-search-go" id="cancelAiSidebarBtn" data-click="cancelAiGeneration" style="margin: 0 auto;">\
          <i class="fa-solid fa-times"></i><span>Cancel</span>\
        </button>\
      </div>\
      <div class="subtitle-upload-hint">Generates subtitles from the video audio using NVIDIA Parakeet. Runs in the background and may take several minutes.</div>\
    </div>\
  </div>';
}
