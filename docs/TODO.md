# Hoshi Android Agent TODO

Last updated: 2026-05-18

This file is the short operational handoff for future agents.

## Maintenance Rules

- Keep this file under 150 lines.
- Record only current state, next actionable work, active blockers, and durable validation requirements.
- Do not paste long emulator transcripts, adb details, screenshot observations, release notes, or per-commit history here.
- Put user-visible shipped changes in `docs/CHANGELOG.md`.
- Keep architecture-refactor slice state out of tracked docs; use the local `.codex/skills/hoshi-refactoring-workflow` skill when available.
- Put detailed reproduction, verification logs, and investigation notes in the relevant issue, PR, commit message, or a focused doc.
- When completing a task, update the smallest relevant line here in the same commit.

## Open Alignment Work

### Architecture And Hardening

- Make screen-level Compose Flow collection lifecycle-aware with `collectAsStateWithLifecycle()` where the UI lifecycle is the right owner.
- Continue Reader state/WebView bridge extraction in behavior-protected slices from `docs/ARCHITECTURE_REFACTORING.md`; keep `ReaderWebView` focused on composition and wiring.
- Replace remaining brittle source-string tests in touched areas with behavior, API, state-flow, or structured-config coverage where possible.
- Add EPUB/WebView regression fixtures for cover pages, multi-image pages, vertical text, horizontal text, complex spines, and broken resources.
- Add repeatable benchmark or baseline-profile entry points for cold start, EPUB import/open reader, reader page turn, dictionary search, and lookup popup open.

### Bookshelf, Import, And Backup

- Device-validate shelf-name entry and multi-EPUB DocumentsUI import in a session where text input and picker interaction can be driven reliably.
- Cross-validate Android-created `Books` and `Dictionaries` `.hoshi` archives restored by iOS.

### Reader And Lookup

- Device-validate vertical lookup selection on ruby text, confirming E-ink underlines, regular highlights, and popup placement share one furigana-aware selection area.
- Device-validate continuous-mode lookup popup placement with nonzero reader padding in both vertical and horizontal writing.
- Device-validate paginated page turns with top and bottom progress counters enabled on E-ink, confirming the counter no longer refreshes before the page flip.
- Device-validate E-ink reader lookup underlines in horizontal and vertical text, confirming the line sits close to selected text without obscuring glyphs.
- Device-validate reader popup Reduced Motion Scrolling on an E-ink target, including vertical swipe threshold, 40%-100% scroll amount, mouse wheel/page-wheel input, and coexistence with horizontal swipe-to-dismiss.
- Device-validate the warm reader root lookup popup shell, confirming repeated root lookups reuse the popup without breaking child popups, redirects, action-bar history, or popup dismiss behavior.
- Device-validate popup-to-popup lookup selections, confirming child popup display syncs with Compose-drawn parent selection marks, E-ink mode uses underlines, and scrolling a parent popup dismisses child popups.
- Device-validate reader lookup popup open and dismiss on a slow E-ink target, confirming popup content does not flash a blank white shell, the Compose-drawn selected-word highlight appears and disappears with the popup, and highlighted text stays readable.
- Finish remaining iOS `PopupWebView` Anki mining behavior beyond selected popup text export.
- Validate paginated and continuous reader modes together for cover image pages, multi-image illustration pages, long text paging, forward/backward progress monotonicity, per-page progress updates and restore landing inside large text nodes, forward and backward chapter boundaries, reverse cross-chapter landing, lookup popup open, and bookmark restore.
- Re-check forward chapter-boundary landings at chapter start, visual-state-gated chapter jumps, and stable progress counters during rapid boundary flips after reader pagination changes.
- When touching Sasayaki reader highlighting, validate reader open/restore remains fast and stable at positions with matched cues.
- Re-run diagonal popup swipe validation once a Reader or nested Dictionary popup state is reliably reachable.
- Future reader fixes must start from `reference/Hoshi-Reader-iOS/Features/Reader/ReaderWebView/ReaderWebView.swift` plus the matching JS/CSS, and must keep WebView-based reading and lookup.

### Dictionary

- Device-validate recommended dictionary downloads from the Dictionaries screen, covering JMdict, Jiten, and Jitendex individual downloads and confirming each imported dictionary remains updatable.
- Keep frequency and pitch dictionaries type-specific; do not treat metadata dictionaries as term fallback dictionaries.
- Do not reimplement Yomitan import, lookup, media, or style extraction outside `third_party/hoshidicts-kotlin-bridge` unless the bridge gap is documented first.

### Highlights And Notes

- Store highlight anchors from WebView range data.
- Restore highlights after chapter load through JS.
- Align highlight tap, delete, and color behavior with iOS.

### Anki

- Add Android AnkiConnect parity behind the existing Anki backend boundary: connection, fetch, duplicate checks, media storage, add-note, and optional force-sync behavior.
- Keep backend coverage for duplicate checks, AnkiDroid fetch failures, and the future AnkiConnect implementation.
- Keep popup mining decoupled from direct HTTP calls; route backend differences through the Anki backend boundary.

### Sync

- Preserve the shared lifecycle-aware loaded-settings collection pattern when adding settings pages so controls do not flash default values before saved preferences load.
- Keep reader auto-export save/upload work on a scope that survives reader route disposal so close and background flushes can finish after navigation.
- Keep HTTP Sync `payload.zip` upload/download file-backed; large Mokuro manga must not be materialized as a single `ByteArray` in production sync paths.
- Keep HTTP Sync large `payload.zip` uploads on the multipart KV API with Cloudflare-safe part sizes; do not fall back to one oversized HTTP request.
- Preserve HTTP Sync manual-progress callbacks when adding reconciliation phases; long-running work should update the Settings screen with a real phase and item counter.
- Preserve HTTP Sync shelf/folder placement in book metadata with per-book `shelfUpdatedAt`; legacy metadata without `shelfName` must not unshelve local books.
- Harden the HTTP Sync KV server with mutation-safe pagination and conditional bookmark PUTs so concurrent writes cannot be missed or overwritten between Android reconciliation passes.
- Device-validate the first Android Google Drive sync slice with `testdata/test.epub` on a user-configured Device Code OAuth client from the same project as iOS/ッツ: connect/sign-out state, long-press manual import/export result dialogs, reader-open import-only, iOS-aligned paginated/continuous auto-export timing, close/background flush export, statistics Merge/Replace, and Sasayaki last-position sync.

### Release Distribution

- Before F-Droid distribution, split update behavior by distribution channel so F-Droid builds do not bypass F-Droid update checks.
- Device-validate GitHub update prompts after the check/download split, covering skip-version, manual checks, completed-download prompts, user-triggered install, and same-version APK cleanup.

## Mokuro Manga Support (Android-only)

Branch `codex/mokuro-manga-support`. A parallel content path for mokuro manga (JSON +
page images) that reuses the bookshelf, dictionary lookup, and Anki mining.

- Working end to end, emulator-verified: import (`.zip`/`.cbz` bundle or SAF folder),
  bookshelf entry + cover, page WebView rendering, visible+selectable OCR text wired to
  the shared dictionary lookup, right-to-left navigation, volume-key paging, per-page
  resume. Content type is derived from disk (`mokuro.json` sidecar), never stored in the
  iOS-shared `metadata.json`; `Bookmark.chapterIndex` carries the page index.
- Architecture invariants for future work: keep using the shared `ReaderSelectionScripts`
  / `ReaderSelectionBridge` / `LookupPopupStackView` for lookup; the manga page WebView is
  sized from the host-provided viewport dimensions (CSS `vw`/`vh` resolve to 0 in this
  WebView config) — do not reintroduce `useWideViewPort`/`loadWithOverviewMode` or
  `vh`-based sizing.
- Emulator-verified: OCR text is hidden until a bubble is tapped (a tap reveals that
  bubble on a near-opaque plate and looks the tapped word up; tapping empty artwork hides
  revealed bubbles again), a revealed bubble shows a copy button that copies its whole
  text, and page turns play a right-to-left slide animation by default while Behavior can
  disable that animation for instant swaps without forcing E-ink black-and-white mode.
- ChatGPT bubble lookup, emulator-verified end to end: a revealed bubble shows a ChatGPT
  button that sends a configurable prompt + the bubble's OCR text to OpenAI and shows the
  Markdown-rendered reply in a closable popup; API key / model / prompt and a per-manga
  chat history are reached from the manga reader's overflow (⋯) menu. Kept deliberately
  self-contained in `features/ai/` (own settings store, own `ai_chat_log.json` per book,
  no shared-file edits) so it stays easy to merge alongside upstream.
- ChatGPT screenshot translation, emulator-verified end to end: the manga overflow menu can
  open a crop overlay, map the selected zoomed/panned viewport back to source page pixels,
  send that crop to OpenAI, save it on the history entry, and use a separate
  customizable/synced image prompt while keeping the API key local-only.
- Zoom/chrome interactions, emulator-verified: one-finger swipes turn pages only when the
  page is not zoomed or pannable, two-finger pinch zooms and one-finger panning do not turn
  pages, zoomed OCR bubble taps hit the visible bubble position, and the floating controls
  / page chip have subtle independent backgrounds without full-width input bars.
- Boox/Onyx fullscreen reader bars still need real-device validation with a tall manga
  page after emulator validation: the top status strip should hide while reading, and if
  a device keeps a system bar visible then page artwork must be inset below it.
- Not yet done: reader appearance/settings sheet for manga, two-page spreads, manga
  reading statistics. Manual validation should cover import of both source layouts and RTL
  paging boundaries.

## Required Validation

On a fresh machine, run `./bootstrap.sh` (macOS/Homebrew) to install the JDK 21, Android
SDK 36 + NDK + CMake, and Rust + cargo-ndk toolchain, then `source ./.bootstrap-env` before
any Gradle command (it exports `ANDROID_NDK_HOME`, which the Rust/UniFFI build needs).

Before claiming implementation complete, run:

```bash
./gradlew test
./gradlew assembleDebug
```

Also run `./gradlew lint` when changing resources, manifest, UI, packaging, or release-facing build behavior.

For settings/navigation changes, verify settings controls update immediately and route changes avoid fade transitions on e-ink displays.

For dark-theme cold-start regressions, use emulator screen recording with the App Appearance theme set to Dark and confirm no light `No Books` app frame appears before the bookshelf loads.

For bookshelf tab-switch regressions, use real-device screen recording to confirm cover placeholders do not flash white when returning to Books from the bottom tab bar.

For bookshelf-to-reader regressions, use real-device continuous screenshots or screen recording to confirm no Bookshelf loading spinner or dark-mode white loading frame appears between tapping a book and showing the Reader.

For reader/dictionary/audio user flows, perform targeted emulator or device validation using the test data listed in `AGENTS.md`; for dictionary external-link regressions, use the `pixivで読む` definition link case.

For reader/dictionary theme regressions, verify open Dictionary tab results, the Dictionary search cursor, open reader lookup popups, and System theme's Use Sepia as Light Theme toggle update immediately when switching between Light, Dark, System, and E-ink appearance modes.

For Dictionary tab input regressions, verify opening the tab focuses the search field, shows the soft keyboard, and hints Japanese input when a Japanese-capable keyboard is installed.

For reader appearance chrome regressions, verify Show Title off, Progress Position Bottom, compact bottom buttons, Sasayaki top-right toggle spacing, top title centering with asymmetric top buttons, bottom reader-menu spacing, focus mode status-bar hiding without text reflow, and all progress indicators hidden against the paginated reader text area.

For reader appearance controls, verify Layout Mode shows both Paginated and Continuous labels without truncation in the settings page and reader sheet.

For reader statistics regressions, verify Advanced -> Statistics defaults off, enabling it turns on the three Appearance statistics toggles, Off/Page Turn/On autostart modes, the reader Statistics sheet without an extra header close row, single 70%-height reader sheet behavior without detent jitter, compact reader sheet row density, smooth Appearance and Chapters sheet scrolling, the Chapters sheet without the extra large title/close row while keeping the book cover header, untruncated Appearance segmented labels with clear selected-state contrast in E-ink mode, compact single-row Appearance font selection, the top-left session toggle using chart/timer icons, bottom speed/time display, page-turn delayed saves, close/background `statistics.json` persistence, and background pause/resume without counted elapsed time.

For Sasayaki settings regressions, verify fresh installs default Sasayaki, Show Sasayaki Toggle, Auto-Scroll, and Auto-Pause on Lookup on, and that Appearance can toggle the reader Sasayaki button.

For Sasayaki matching regressions, verify short low-confidence `＊` subtitle cues are skipped while longer `＊` cues still match and advance playback alignment.

For Sasayaki skip-control regressions, verify the reader bottom skip buttons flank the existing Back/Menu buttons, and the same cue/5s/10s/15s/30s action applies from reader chrome, Sasayaki sheet controls, and Android system media controls.

For Sasayaki volume-key regressions, verify volume-key seek with loaded audiobook audio, fallback without loaded audio, priority over Volume Keys Turn Pages, and Reverse Volume Key Direction affecting both seek and page-turn controls.

For reader keep-screen-on regressions, verify Behavior -> Keep Screen On defaults off, persists after leaving settings, keeps the display awake while the reader is foregrounded when enabled, clears after closing the reader when disabled, and still keeps Sasayaki playback awake only while playback and Auto-Scroll are active.

For reader text layout regressions, verify Appearance -> Layout changes such as Vertical Padding reload the current chapter at the displayed position and visibly affect text spacing.

For continuous reader layout regressions, verify vertical-writing Horizontal Padding and horizontal-writing Vertical Padding inset the current visible viewport rather than only the chapter ends.

For reader popup settings regressions, verify changing every Popup section control while a continuous reader is open does not rebuild the WebView and does not stop scroll progress updates.
