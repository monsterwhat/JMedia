# fix-subtitle-styling - Work Plan

## TL;DR (For humans)

**What you'll get:** Subtitles on your videos will actually show your chosen font, size, color, background, and position — not the browser default white-on-gray. The subtitle settings popup (opened from the player controls) will work properly: sliders and color pickers will change the subtitles in real time, and your preferences will be saved.

**Why this approach:** The subtitle styling code had two separate settings panels that talked to the same backend in different languages with different element IDs. Rather than rewriting everything, we fix the missing method (getStyle()), make saveStyle() listen to both callers, and align the element IDs so both panels see the same controls.

**What it will NOT do:** It won't change how ASS/SSA subtitles render (those use a separate engine). It won't touch the Java backend. It won't merge or eliminate either settings UI — both will keep working.

**Effort:** Small
**Risk:** Low — changes are additive/fix-only in 4 files; the existing modal Style tab keeps working unchanged
**Decisions to sanity-check:** the element ID mapping — should the inline panel adopt the modal's IDs (changes subtitleSettingsComponent.html) or should SubtitleManager learn two ID sets (changes subtitle-manager.js)

---

> TL;DR (machine): Small effort, low risk. 4 bugs in the subtitle styling system: missing getStyle(), saveStyle ignores param, element ID mismatch between two UIs, CSS not global. Fix: 4 files, additive changes.

## Scope

### Must have
- Add getStyle() method to SubtitleManager class (returns current style or defaults)
- Fix saveStyle() to accept an optional style parameter (use param when provided, DOM-read as fallback)
- Align element IDs so inline settings overlay (subtitleSettingsComponent.html) works with SubtitleManager API
- Load subtitle-styling.css globally in index.html head
- Verify both UIs (inline player overlay + management modal Style tab) work end-to-end

### Must NOT have (guardrails, anti-slop, scope boundaries)
- Do NOT change JASSUB/ASS subtitle renderer or any Java backend code
- Do NOT merge/eliminate either of the two style UI surfaces
- Do NOT refactor or restructure SubtitleManager architecture
- Do NOT change the Subtitle Management Modal Style tab (index.html lines 476-559)
- Do NOT add new features (e.g., font weight, outline, drop shadow)
- Do NOT modify tests or test infrastructure

## Verification strategy
> Zero human intervention — all verification is agent-executed.
- Test decision: tests-after (manual verification via browser console + visual inspection)
- Evidence: each todo includes its acceptance criteria with exact tool + invocation

## Execution strategy

### Dependency matrix
| Todo | Depends on | Blocks | Can parallelize with |
|------|-----------|--------|---------------------|
| T1 (getStyle) | — | T4 | T2, T3, T4 |
| T2 (saveStyle param) | — | T4 | T1, T3, T4 |
| T3 (element IDs) | — | T4 | T1, T2, T4 |
| T4 (CSS global) | — | — | T1, T2, T3 |

All 4 todos are independent and can run in parallel.

## Todos

- [ ] T1. Add getStyle() method to SubtitleManager
  What to do / Must NOT do:
  Add a public getStyle() method to the SubtitleManager class in subtitle-manager.js that returns 	his.currentStyle merged with full defaults if null. Must NOT change any existing method signature or any other code.
  Parallelization: Wave 1 | Blocked by: none | Blocks: Final verification
  References (executor has NO interview context - be exhaustive):
  - F:\Documents\GitHub\JMusic\JMedia\com.playdeca.JMedia\src\main\resources\META-INF\resources\js\subtitle-manager.js — The class currently has 	his.currentStyle tracked at line 6, but NO getStyle() method. It IS called at:
    - SubtitleSettingsUI.js:27 — const style = mgr.getStyle();
    - SubtitleController.js:326 — window.subtitleManager.getStyle()
  - The pplySavedStyle() method (line 26) merges saved+defaults — use the same defaults object pattern.
  Acceptance criteria (agent-executable):
  After adding getStyle():
  - In browser console: window.subtitleManager.getStyle() returns an object with {font, size, color, bgOpacity, lineHeight, bottom} matching the saved style or defaults.
  - PlayerSubtitleSettingsUI.init() no longer throws TypeError: mgr.getStyle is not a function
  QA scenarios (happy + failure):
  - Happy: Open video, open subtitle settings overlay — no console error, sliders show correct values from localStorage.
  - Failure: No saved style in localStorage — getStyle() returns defaults, not undefined/null.
  Evidence: Browser console screenshot + console.log output capture.
  Commit: Y | fix(player): add getStyle() method to SubtitleManager

- [ ] T2. Fix saveStyle() to accept optional style parameter
  What to do / Must NOT do:
  Modify SubtitleManager.saveStyle() in subtitle-manager.js to accept an optional style parameter. When provided, use it directly instead of reading DOM elements. Always save to localStorage and call pplyGlobalStyle(). Must NOT break the existing caller (the Style tab modal which calls saveStyle() with no argument — must fall back to DOM reading).
  Parallelization: Wave 1 | Blocked by: none | Blocks: Final verification
  References:
  - subtitle-manager.js:225 — current saveStyle() takes no params, reads getVal('subStyleFont', ...) etc. from DOM
  - SubtitleSettingsUI.js:161 — calls window.subtitleManager.saveStyle({font: ..., size: ..., color: ..., bgOpacity: ..., lineHeight: ..., bottom: ...})
  - The pplyGlobalStyle() method (line 260) accepts a style object — call it with the style param
  Acceptance criteria (agent-executable):
  - When called with a style object, that object is saved to localStorage key jmedia_subtitle_style
  - When called with no argument, reads DOM elements as before (backward compatible)
  - SubtitleSettingsUI.save() successfully saves settings
  QA scenarios:
  - Happy: From inline panel, change values and click Save — localStorage jmedia_subtitle_style reflects changes, subtitles update.
  - Happy: From modal Style tab, change values and click Save — same result.
  - Failure: Call with null/undefined — falls back to DOM read, no TypeError.
  Evidence: localStorage content before/after.
  Commit: Y | fix(player): make saveStyle() accept optional style param

- [ ] T3. Align element IDs so inline settings panel works with SubtitleManager
  What to do / Must NOT do:
  The inline settings overlay (subtitleSettingsComponent.html) calls SubtitleManager.updateStyle() and SubtitleManager.loadStyle() but those methods reference DOM IDs that exist in the modal's Style tab, NOT in the overlay. Two approaches possible:
  
  **Option A (preferred — minimally invasive):** Add hidden <span>/<input> elements with the expected IDs (subStyleFont, subStyleSize, subStyleColor, subStyleBgOpacity, subStyleLineHeight, subStyleBottom, ontSizeVal, gOpacityVal, ottomDistVal, subStyleColorHex, subPreviewText) to subtitleSettingsComponent.html, synced to the visible controls' values. This way SubtitleManager methods find their expected targets.
  
  **Option B:** Modify SubtitleManager.updateStyle() and SubtitleManager.loadStyle() to accept optional element ID overrides or use a fallback set of IDs.
  
  Must NOT break the modal Style tab functionality. Must NOT rename the modal's existing element IDs.
  Parallelization: Wave 1 | Blocked by: none | Blocks: Final verification
  References:
  - subtitleSettingsComponent.html — The inline overlay, uses IDs: subtitleSize, subtitleColor, subtitleBgOpacity, subtitleBottom, subtitleSizeValue, subtitleBgOpacityValue, subtitleBottomValue, subSettingsPreviewText, subtitleCorrectionVal, subtitleTrack
  - subtitle-manager.js:172-177 — loadStyle() reads: subStyleFont, subStyleSize, subStyleColor, subStyleBgOpacity, subStyleLineHeight, subStyleBottom
  - subtitle-manager.js:198-206 — updateStyle() sets: ontSizeVal, gOpacityVal, ottomDistVal, subStyleColorHex, subPreviewText
  - index.html:476-559 — The modal Style tab with the "correct" IDs
  Acceptance criteria (agent-executable):
  - Opening inline subtitle settings overlay shows current saved style values
  - Changing a slider/color in inline panel updates the preview AND the video subtitles in real time
  - Clicking Save & Close saves the style
  - Modal Style tab still works identically
  QA scenarios:
  - Happy (inline): Open inline panel, change font size to 32px — preview text gets larger, video subtitle ::cue text gets larger
  - Happy (modal): Open management modal > Style tab — values match, changing there also works
  - Failure: Missing expected DOM element — method logs a warning and continues rather than throwing
  Evidence: Screenshots of both UIs showing matching values + video screenshot with styled subtitles.
  Commit: Y | fix(player): align inline subtitle settings IDs with SubtitleManager expectations

- [ ] T4. Load subtitle-styling.css globally
  What to do / Must NOT do:
  Add <link rel="stylesheet" href="/css/subtitle-styling.css"/> to the <head> of index.html so the base CSS variables (--subtitle-font-family, --subtitle-font-size, --subtitle-text-color, --subtitle-bg-color, etc.) and ideo::cue rule are available globally, not just after playbackFragment.html loads.
  Must NOT remove the existing include from playbackFragment.html (harmless duplicate — browser handles it).
  Parallelization: Wave 1 | Blocked by: none | Blocks: Final verification
  References:
  - index.html:28 — insert after existing CSS <link> entries (after line 28, before the SPA scripts)
  - playbackFragment.html:3 — current location of the include
  - subtitle-styling.css — defines :root CSS variables and ideo::cue rule
  Acceptance criteria (agent-executable):
  - After page load (before any video/playback fragment loads), getComputedStyle(document.documentElement).getPropertyValue('--subtitle-font-family') returns a non-empty string
  - The ideo::cue rule is in the stylesheet list
  QA scenarios:
  - Happy: Load index.html ? inspect stylesheets ? subtitle-styling.css present
  - Happy: Load video page ? playback fragment loads ? no duplicate/redundant CSS issues
  - Failure: File missing ? 404 for CSS (verify file exists at F:\Documents\GitHub\JMusic\JMedia\com.playdeca.JMedia\src\main\resources\META-INF\resources\css\subtitle-styling.css)
  Evidence: Browser devtools screenshot of CSS source list.
  Commit: Y | fix(player): load subtitle-styling.css globally in index.html

## Final verification wave
> Runs in parallel after ALL todos. ALL must APPROVE. Surface results and wait for the user's explicit okay before declaring complete.
- [ ] F1. Plan compliance audit — verify all 4 todos completed, nothing done outside scope
- [ ] F2. Code quality review — verify no console errors, no broken existing functionality
- [ ] F3. Real manual QA — open video, verify both subtitle style UIs work, verify styles apply to video subtitles
- [ ] F4. Scope fidelity — confirm no Java backend, no JASSUB, no architecture changes were made

## Commit strategy
4 commits, one per todo (fix(player): add getStyle method / make saveStyle accept param / align element IDs / load subtitle CSS globally). Squash into a single PR if desired:
ix: repair subtitle styling — add getStyle(), fix saveStyle() param, align UI IDs, load CSS globally

## Success criteria
- SubtitleManager.getStyle() exists and returns correct style object
- saveStyle() works both when called with a param and without
- Inline subtitle settings overlay (from player controls) shows correct values and changes subtitles in real time
- Subtitle Management Modal Style tab still works identically
- subtitle-styling.css is available globally
- No console TypeErrors related to subtitle styling
- Subtitles show user-chosen font, size, color, background, position on video
