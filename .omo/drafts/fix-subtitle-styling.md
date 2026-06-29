---
slug: fix-subtitle-styling
status: awaiting-approval
intent: clear
pending-action: write .omo/plans/fix-subtitle-styling.md
approach: Add getStyle(), fix saveStyle() param, align element IDs between the two subtitle style UIs, load subtitle-styling.css globally
---

# Draft: fix-subtitle-styling

## Components (topology ledger)
| id | outcome | status | evidence |
|----|---------|--------|----------|
| C1 | SubtitleManager.getStyle() is MISSING — called by SubtitleSettingsUI.init() and SubtitleController.restoreAfterFullscreen() | active | SubtitleSettingsUI.js:27, SubtitleController.js:326 call mgr.getStyle(); method absent in subtitle-manager.js |
| C2 | SubtitleManager.saveStyle() ignores its parameter — called with style object from SubtitleSettingsUI.save() but reads DOM instead | active | subtitle-manager.js:225 (no param), SubtitleSettingsUI.js:161 (passes object) |
| C3 | Element ID mismatch — inline panel (subtitleSettingsComponent.html) uses different IDs than SubtitleManager methods expect | active | subtitleSettingsComponent.html IDs vs subtitle-manager.js:172-177 references; preview element ID mismatch (subPreviewText vs subSettingsPreviewText) |
| C4 | subtitle-styling.css not loaded globally — only in playbackFragment.html | active | playbackFragment.html:3 vs index.html (missing) |

## Open assumptions (announced defaults)
| assumption | adopted default | rationale | reversible? |
|------------|----------------|-----------|-------------|
| The inline settings panel should remain as a separate overlay, not merged into the modal | Keep both UIs functional; fix the integration layer | Two UIs exist for different contexts (player quick-settings vs full management modal) | Yes — can merge later |
| saveStyle() should accept optional style param | Accept param when provided; fall back to DOM reads | Cleanest backward-compatible approach | Yes |

## Findings (cited - path:lines)

### Root cause: two disconnected style UIs sharing one backend incorrectly

The subtitle styling system has two UI surfaces:
1. **Inline settings overlay** (subtitleSettingsComponent.html) — triggered by 	oggleSubtitleSettings() from the player's settings menu
2. **Subtitle Management Modal Style tab** (in index.html lines 476-559) — triggered via subtitleManager.switchTab('style') from the management modal

Both use SubtitleManager (subtitle-manager.js) as their backend, but:

**Finding 1: getStyle() method missing** — SubtitleSettingsUI.js:27 and SubtitleController.js:326 both call window.subtitleManager.getStyle(). The SubtitleManager class has no getStyle() method; it tracks 	his.currentStyle privately. This causes JS TypeError when inline panel initializes or when subtitles are restored after fullscreen exit.

**Finding 2: saveStyle() signature mismatch** — SubtitleSettingsUI.save() at SubtitleSettingsUI.js:161 calls window.subtitleManager.saveStyle({font: ..., size: ..., ...}) passing a style object. But SubtitleManager.saveStyle() at subtitle-manager.js:225 takes no parameters and reads directly from DOM elements (subStyleFont, subStyleSize, etc.). The argument is silently ignored.

**Finding 3: Element ID mismatch** — SubtitleManager methods (loadStyle(), updateStyle()) reference DOM IDs that exist in the modal's Style tab (subStyleFont, subStyleSize, subStyleColor, subStyleBgOpacity, subStyleLineHeight, subStyleBottom, ontSizeVal, gOpacityVal, ottomDistVal, subPreviewText). But the inline panel (subtitleSettingsComponent.html) uses different IDs: subtitleSize, subtitleColor, subtitleBgOpacity, subtitleBottom, subtitleSizeValue, subtitleBgOpacityValue, subtitleBottomValue, subSettingsPreviewText. When the inline panel calls SubtitleManager.updateStyle(), it updates subPreviewText which doesn't exist in the inline panel.

**Finding 4: CSS not global** — subtitle-styling.css is loaded in playbackFragment.html:3 but not in index.html. The base CSS variables (--subtitle-*) and ideo::cue styles only apply once the player loads.

## Decisions (with rationale)

1. **Add getStyle() method** — returns 	his.currentStyle merged with defaults if null. 1-line addition. Rationale: both callers need it.

2. **Fix saveStyle() to accept optional param** — when called with a style object, use it directly instead of reading DOM; always saves to localStorage and applies. Rationale: SubtitleSettingsUI already passes the correct data; makes the method usable from both UIs.

3. **Align element IDs** — Add the missing IDs expected by SubtitleManager to the inline panel (subtitleSettingsComponent.html), or make SubtitleManager.updateStyle() work with both sets of IDs.

4. **Globalize subtitle-styling.css** — Add <link rel="stylesheet" href="/css/subtitle-styling.css"/> to index.html <head>.

## Scope IN
- Add getStyle() method to SubtitleManager
- Fix saveStyle() to accept an optional style parameter
- Align element IDs so the inline settings panel works correctly with SubtitleManager API
- Load subtitle-styling.css globally in index.html
- Verify both UIs (inline panel + modal Style tab) work end-to-end

## Scope OUT (Must NOT have)
- Do NOT merge or eliminate either of the two style UI surfaces
- Do NOT change the ASS subtitle renderer (JASSUB)
- Do NOT touch backend Java subtitle services
- Do NOT refactor the SubtitleManager into a different architecture — only fix the methods and ID references

## Open questions
None — all issues confirmed by code exploration.

## Approval gate
status: awaiting-approval
