# Changelog

All notable user-visible changes to Sui Manga Reader (a manga-only fork of
[HuangAntimony / Hoshi-Reader-Android](https://github.com/HuangAntimony/Hoshi-Reader-Android))
are documented here. The format follows Keep a Changelog and release sections use
Semantic Versioning.

## [Unreleased]

## [v0.9.9] - 2026-07-30

## [v0.9.8] - 2026-07-21

### Added
- **E-ink Mode toggle is back**, now under Settings → Behavior. It had been unreachable
  since the Appearance screen (its old home) was hidden in the manga fork, even though
  the mode still drives the black-&-white theme and disables the page-turn animation.

### Changed
- **HTTP sync auto-push is always on once configured.** The separate "Enabled" switch is
  gone — filling in the base URL and bearer token is all it takes. The offline circuit
  breaker still applies.

### Fixed
- **Dictionary popups stay clear of text whenever a usable gap is available.** Tall or
  zoomed bubbles no longer have the 120dp minimum forced into a smaller-but-usable gap,
  vertical-writing popups follow the same rule, and oversized full-width popups are capped
  to the inset-safe viewport instead of clipping off-screen.
- **Ambiguous mokuro page-image paths no longer import an arbitrary volume.** If a fallback
  path matches images in multiple folders, import now stops instead of silently choosing the
  lexicographically first file and potentially showing the wrong page or cover.

## [v0.9.7] - 2026-07-13

### Fixed
- **Dictionary popup no longer covers the top of a very tall or zoomed-in bubble.**
  0.9.6's popup min-size change could push the popup to the side with *less* room and
  clamp it onto the top of a screen-filling bubble; it now always opens on the side
  with more room.
- **Full-width dictionary popup no longer sits under the navigation bar** on screens
  with a bottom system inset.

## [v0.9.6] - 2026-07-13

### Fixed
- **Dictionary popup no longer covers the tapped word.** For bubbles whose revealed
  OCR text renders slightly taller than mokuro's detected box, trailing characters
  (e.g. ！？) spill just past the box edge; the popup positions itself clear of the
  box, so it used to sit on top of those spilled characters. It now clears the full
  painted text of the bubble.
- **Dictionary popup no longer collapses to an invisible sliver on bubbles that fill
  the screen.** A bubble tall enough to leave no room above/below (or wide enough, for
  vertical text, to leave no room beside it) — or any bubble zoomed until it fills the
  screen — used to shrink the popup to a 1px, effectively invisible strip. The popup now
  keeps a usable minimum size.
- **Importing a manga is more reliable when page images share a filename.** A mokuro
  archive whose pages reference the same image basename across folders could bind a page
  to an arbitrary image (the match depended on unspecified filesystem ordering); the
  resolution is now deterministic.

## [v0.9.5] - 2026-06-13

### Added
- **More cloud AI providers** — alongside ChatGPT/OpenAI you can now pick Anthropic (Claude),
  Google Gemini, DeepSeek, Qwen, and Kimi (Moonshot) for chat and bubble translation. Each
  provider keeps its own API key and model list, and you can still enter a custom
  OpenAI-style model id. The AI settings screen is now labelled "Translation model".

## [v0.9.4] - 2026-06-13

### Changed
- Renamed the app to **Sui Manga Reader** (kanji: 彗漫画) with a new launcher icon. The
  application id, `.hoshi` backup format, FileProvider authority, and in-app updater asset
  naming are all unchanged, so existing installs upgrade in place and keep their data.
  Credit to the upstream Hoshi Reader projects (HuangAntimony, Manhhao) is unchanged.
- HTTP Sync now pushes bookshelf imports, shelf moves, deletes, reading progress, and
  ChatGPT prompt changes sooner, and protects bookmark/metadata conflicts with per-item
  edit revisions so stale devices are less likely to overwrite newer sync state.

## [v0.9.3] - 2026-06-04

### Added
- **Offline on-device translation** — translate manga speech bubbles fully on-device via a
  downloadable model (llama.cpp), with no API key or internet, as an alternative to ChatGPT.
  Pick a fast translate-only model or a larger model that also explains grammar/vocabulary in
  Settings → ChatGPT; the reply popup shows a live tokens/sec counter. The model downloads in the
  background (keeps going with the screen off and resumes from where it left off if interrupted).
- The installed build's version + git commit are shown in Settings (the offline-translation
  section and Advanced), so a build can be identified at a glance.

## [v0.9.2] - 2026-06-03

### Changed
- **Merged upstream Hoshi Reader v1.1.3** (HuangAntimony/Hoshi-Reader-Android, six
  upstream releases). This is a maintenance sync that rebases the manga build on top of
  upstream's reworked reader and dictionary-lookup internals. Upstream's EPUB-reader
  additions (custom themes, full-screen mode, fullscreen image viewer, EPUB folder
  import) stay hidden in this manga-only build.

### Fixed
- **Dictionary lookup is more robust** thanks to upstream's in-WebView popup rework: the
  lookup popup now survives process recreation and e-ink mode toggles, dismisses
  correctly on stylus (S Pen) taps, and no longer freezes when mining a card.
- **EPUB/Calibre CSS sanitization** keeps publisher stylesheets from crashing the
  WebView (carried in for completeness; affects the hidden EPUB path).

### Added
- Optional **AnkiDroid auto-sync after adding a card** and **Opus/Ogg word-audio**
  support, carried in from upstream.

## [v0.9.1] - 2026-05-28

### Added
- **Dictionary lookup in the ChatGPT history** (reader's overflow ⋯ menu →
  "Past ChatGPT messages"). The history is now a tappable WebView; tapping a
  Japanese word in a response looks it up against your dictionaries, with the
  same popup, highlight, and nested-lookup behaviour as the reader.
- **Manga overflow-menu toggles moved to Settings → Behavior.** "Single-tap to
  look up" and "Use Noto Sans JP font" are durable preferences, not
  per-session knobs, so they now live alongside the other reader preferences
  (volume keys, keep screen on, etc.) instead of in the reader's ⋯ menu.

### Fixed
- **OCR text size no longer depends on character count.** Two speech bubbles
  whose drawn glyphs are the same size now reveal at the same OCR text size,
  regardless of how many characters fit beside them. The previous clamp scaled
  by box width ÷ character count, which made short bubbles look much larger
  than long ones at the same artwork scale. The new boost is purely a
  function of mokuro's reported drawn-glyph height: tiny artwork is bumped up
  toward a comfortably tappable target (~30 px), artwork already at or above
  the target reveals at mokuro's reported size, and there are no thresholds.
- **Wrap-fallback no longer fights the parser.** The runtime fallback that
  switches a tall narrow bubble to multi-row wrap mode used to re-fit nowrap
  too, which grew short bubbles past their parser size and shrank long ones.
  It now leaves the parser's size alone and only promotes to wrap when wrap
  gives a meaningfully larger glyph.

## [v0.9.0] - 2026-05-26

First batch of "**Hoshi Manga**" fork changes. Hoshi Manga is a manga-focused fork of
HuangAntimony's [Hoshi Reader Android](https://github.com/HuangAntimony/Hoshi-Reader-Android),
which is itself a recreation of [Manhhao / Hoshi-Reader](https://github.com/Manhhao/Hoshi-Reader).

### Renamed
- Package display name is now **Hoshi Manga** (kanji: 星漫画). The "Books" bottom-nav
  tab, the bookshelf top-bar title, the import dropdown, the empty-state copy, the
  About card, the update notification, and the install-permission prompts all use the
  new name. The Android applicationId stays `moe.antimony.hoshi.debug` for now so
  existing installs auto-update in place.
- Launcher icon replaced with the manga speech-bubble artwork (`星漫画` + furigana on
  a dark sky background, adaptive icon).

### Removed (UI-only — data plumbing kept)
- EPUB import is no longer surfaced in the UI: the bookshelf hides any non-mokuro
  book and the import + button only accepts mokuro `.zip` / `.cbz` bundles. Existing
  EPUBs in app storage are **not** deleted but can no longer be opened from the
  bookshelf. (Restore them by reverting to upstream HuangAntimony/Hoshi-Reader-Android.)
- Settings → Appearance and Settings → Behavior screens (font picker, line height,
  vertical-text toggle, chapter swipe distance, page-turn animation toggle, etc.)
  are EPUB-only and have been removed from the Settings menu. Their underlying
  ReaderSettings fields and DataStore keys remain to keep migration safe.
- Settings → Advanced → "Sasayaki (Audiobooks)" row hidden. Sasayaki was an
  audiobook ↔ EPUB text matcher and isn't useful in a manga-only build.
- Settings → Advanced → "ッツ Sync" (TTU Google Drive sync) row hidden — TTU sync
  targets the ttu-reader webapp, which is EPUB-only.
- `application/epub+zip` VIEW intent-filter removed from the Android manifest so the
  app no longer claims to handle `.epub` files in Open-with menus.
- Bookshelf book context-menu "Match Sasayaki" item hidden (only ever appeared for
  EPUB books, which are now filtered out).

### Added
- **Per-bubble Noto Sans JP font toggle** (overflow menu in the reader).
- **Single-tap to look up** toggle (overflow menu): on by default the reader uses the
  upstream two-tap pattern (first tap reveals so the action buttons surface, second
  tap looks up); flipping the toggle merges both into one tap for users who don't
  use the bubble action buttons.
- **Go to page…** dialog in the reader's overflow menu — jump directly to any page
  by number instead of swiping repeatedly.
- **Adaptive font-size clamp** for the OCR overlay: mokuro's per-block `font_size` is
  OCR-derived and frequently overshoots actual glyph height; the parser now caps it
  at a fit-to-box value so revealed bubbles don't grow into giant white plates over
  the artwork. The clamp is permissive (1.5× safety) for small fonts and strict (1.0×)
  for large fonts, preserving the slight-zoom feel on small text.
- **Runtime wrap-fallback** on first reveal: when mokuro mistagged a tall narrow
  bubble as horizontal (so the text was a one-line strip overflowing the bubble),
  the JS now binary-searches the largest fitting nowrap and wrap font sizes and
  switches to wrap mode if it gets a meaningfully larger glyph. Ports the algorithm
  from [Gnathonic's mokuro-reader](https://github.com/Gnathonic/mokuro-reader).
- **`WebSettings.minimumFontSize = 1`** in the manga WebView so sub-8-px clamped
  fonts actually render at their requested size instead of getting bumped to 8.
- **WebView debugging** automatically enabled on debug builds (gated by
  `BuildConfig.DEBUG`) for easier `chrome://inspect` development.
- **About → Credits card** with attribution to HuangAntimony, Manhhao, mokuro,
  Gnathonic mokuro-reader, Yomitan, hoshidicts, and AnkiDroid, plus a GPLv3 link.
- **Yomitan-style lookup context in ChatGPT history**: speech-bubble chats now save and
  display the matching dictionary entries, glossaries, frequency, pitch, and deinflection
  context captured when the bubble was sent.

### Fixed
- Bookshelf import → file picker no longer offers EPUBs; the dropdown is now just
  "Manga file" + "Manga folder".
- Bookshelf displayed strings ("Sort books", "Move selected books", "Delete N book(s)?",
  "Shows books you've started…", backup section "Books", etc.) renamed to "manga".
- ChatGPT error popup: when the error is "Set your OpenAI API key", the dialog now
  shows a "Dismiss" button + a "Settings → ChatGPT" hint instead of an unhelpful
  "Retry" button.
- HTTP Sync description text no longer claims to work for EPUBs.
- `SasayakiPlaybackEngine` `when (playbackState)` block now covers `STATE_IDLE` and
  `STATE_BUFFERING` explicitly (lint `SwitchIntDef` cleanup).
- Dictionary panel reveal-swipe no longer recomposes every fling frame: the
  `requireOffset()` read moved from composition into the `Modifier.offset {}` lambda.
- New unit test `HoshiDictsNativeApiContractTest` reflection-checks that all 8 host
  Kotlin classes the native JNI bridge constructs match the C++ `<init>` signatures,
  so a future submodule rollback that broke the dictionary import crash will fail at
  test time instead of crashing at runtime.
- Manga reader page turns and OCR lookups no longer run the expensive WebView snapshot /
  dictionary lookup path on the main thread, avoiding ANRs during rapid page turns and
  bubble lookups on large screens.

### Migration notes for users coming from upstream 0.8.x
- Existing EPUBs on disk are not deleted — they just don't appear in the bookshelf
  anymore. If you want to keep reading EPUBs, install
  [HuangAntimony/Hoshi-Reader-Android](https://github.com/HuangAntimony/Hoshi-Reader-Android)
  instead (the upstream supports both formats).
- Existing reading progress on mokuro manga is preserved.
- Reader-settings fields (selected font, line height, chapter swipe distance, etc.)
  remain in your DataStore but are no longer reachable from the UI; they'll start
  working again if you switch back to upstream.
- HTTP sync and Google Drive sync still round-trip mokuro payloads; legacy EPUB
  sync entries are ignored.

## [v0.8.2] - 2026-05-22

### Added

- Add a per-file transfer progress bar for HTTP sync: payload uploads and downloads now report byte-level progress for the file in flight, not just an overall "book N of M" count.

### Fixed

- Stop manga screen taps forcing a full e-ink refresh: the manga page no longer flashes Chromium's tap-highlight, and a tap that selects nothing no longer mutates the document.

## [v0.8.1] - 2026-05-22

### Fixed

- Fix Samsung S Pen input being dead inside the reader: the lookup popup overlay (rewritten in v0.7.4) stayed full-screen on top of the reader even with no popup shown, and silently swallowed all S Pen touches bound for the reader WebView. It is now kept out of the input path entirely until a popup is actually shown.
- Fix tapping outside the lookup popup not dismissing it: the overlay now handles the outside press directly instead of relying on it falling through to the reader, which an S Pen press does not do.

## [v0.8.0] - 2026-05-21

### Added

- Add manga reading statistics from the manga reader overflow menu, with a page-based statistics sheet for session, today, and all-time progress plus optional tracking.
- Merge upstream Hoshi Reader through v1.0.1: Simplified Chinese localization, a Reader Appearance option to blur large reader images until tapped, a Dictionaries Low Memory Usage Mode import setting, Advanced AnkiConnect settings and mining support, iOS-style reader highlights with a Highlights sheet, jump-return controls, lookup popup font CSS and scale, book title renaming from the Books long-press menu, JMnedict in recommended downloads, deinflection explanation popups, dictionary archive type detection on import, and a Sasayaki setting to reverse the reader bottom skip buttons in vertical writing mode.

### Changed

- Smooth manga page turns by caching generated page HTML and preloading adjacent page images before navigation.
- Move ChatGPT settings (OpenAI API key, model, and prompts) into the main Settings tab so the key and prompts can be edited without opening a manga.
- Promote manga screenshot translation to its own button next to the manga reader ⋯ menu once a ChatGPT API key is configured, instead of keeping it inside the overflow menu.
- Merge upstream Hoshi Reader through v1.0.1: rewrite lookup popup presentation around a shared native Android overlay, and allow Sasayaki audiobook playback speed up to 2x.

### Fixed

- Merge upstream Hoshi Reader through v1.0.1: use the selected EPUB file name as the book title when metadata has none, keep editable text-field cursors visible in dark and E-ink themes, keep continuous reader backward chapter turns landing at the previous chapter end, allow AnkiconnectAndroid's Local Audio URL as an external audio source, harden Google Drive Device Code authorization, and assorted lookup popup rendering and alignment fixes.

## [v0.7.21] - 2026-05-18

### Fixed

- Fix manga screenshot translation after zooming and panning so the image sent to ChatGPT matches the visible page region.

## [v0.7.20] - 2026-05-18

### Added

- Save manga screenshot translation crops in ChatGPT history, so past image questions show the exact screenshot sent to ChatGPT.

## [v0.7.19] - 2026-05-17

### Changed

- HTTP Sync now runs on the new v3 engine by default. v3 is the deterministic plan-then-execute redesign documented in `docs/SYNC_REDESIGN.md` / `docs/SYNC_V3_SPEC.md`. The legacy v2 reconciler stays in the binary as a per-device rollback path (`useV3Sync = false` in DataStore).

### Fixed

- Fix nine v3-engine bugs surfaced by an external review: re-import after delete no longer wipes the live book; remote-only books on first import now honor the server's shelf placement; the per-book lock is held across `ApplyRemoteBookmark` so a page turn during sync can't be overwritten by an older remote bookmark; malformed remote blobs are surfaced as errors and never overwritten by local data; cross-content-type `syncId` collisions emit an error instead of corrupting remote metadata; fresh installs now pull existing remote AI chat settings; tombstone state survives concurrent user deletes during sync; `CancellationException` is rethrown so structured concurrency works.
- Fix the v2 reconciler losing a fresh delete: deleting a synced book and then immediately hitting `Sync now` could resurrect the book on every other device because the inbound pass re-imported the still-live remote payload before the outbound pass pushed the tombstone.
- Fix bookshelf covers showing blank after an HTTP Sync download. Both engines now resolve `metadata.cover` during the sync import itself, so the cover renders without the user opening the book.
- Fix re-importing a previously deleted book getting deleted again across devices. Both engines now compare the local `importedAt` against the remote `deletedAt` and keep / push the re-imported book when its import is newer than the tombstone.

## [v0.7.18] - 2026-05-17

### Fixed

- Fix HTTP Sync not downloading newly-uploaded books from another device when the receiving device's local sync cursor was ahead of the server-stamped upload time. Manual `Sync now` now always does a full listing pass, so server-state drift can't hide new manga.

## [v0.7.17] - 2026-05-17

### Changed

- Show live HTTP Sync progress while a manual sync is running, including the current phase and book/item counters for payload checks, reading data, chat history, and local uploads.
- Sync bookshelf shelf/folder placement and deletion markers over HTTP Sync, so moved or removed manga converge on another device.

### Fixed

- Fix HTTP Sync uploads for large manga payloads by splitting `payload.zip` into 64 MiB multipart upload parts, avoiding Cloudflare's per-request body limit.
- Exclude local OpenAI, HTTP Sync, and Google Drive credentials from Android app backup.

## [v0.7.16] - 2026-05-17

### Added

- Add manga screenshot translation from the reader overflow menu: draw a crop rectangle, send the selected image to ChatGPT, and customize the synced image-translation prompt separately from the speech-bubble prompt.

### Fixed

- Fix HTTP Sync out-of-memory failures when uploading or downloading large Mokuro manga payloads by streaming `payload.zip` through temporary files instead of holding the full zip in memory.

## [v0.7.15] - 2026-05-16

### Fixed

- Keep manga pages from being covered by the Boox/Onyx top system bar by entering immersive reader mode for manga, and keep the manga page viewport inset-safe if a device keeps system bars visible.

## [v0.7.14] - 2026-05-16

### Fixed

- Hide the top system status bar while reading on Boox/Onyx devices so manga pages keep the full viewport instead of sitting under a persistent strip.

## [v0.7.13] - 2026-05-15

### Fixed

- Fix mokuro manga zoom follow-up: zoomed pages pan without turning pages, OCR bubble taps follow the visible zoomed/panned position, and floating reader controls remain visible on white page artwork.

## [v0.7.12] - 2026-05-15

### Fixed

- Fix HTTP Sync so manual sync does not advance past retryable remote book, bookmark, or ChatGPT chat items; manga ChatGPT history skipped by an older bad cursor is backfilled, Japanese-only titles get stable sync IDs, newer remote bookmarks discovered during upload are applied locally, and sync URL encoding works on Android API 28+.
- Fix mokuro manga reader gestures and chrome: two-finger pinch gestures no longer turn pages, OCR bubble hit-testing and lookup anchors stay aligned while zoomed, transparent top and bottom controls no longer create full-width input bars, and the ChatGPT bubble button sits to the right of copy.

## [v0.6.3] - 2026-05-15

### Added

- Add a Behavior setting to keep the screen awake while reading books without Sasayaki audio.
- Add iOS-style dictionary update checks for installed updatable dictionaries, including revision comparison, download/install progress, preservation of dictionary order and enabled state, and Anki single-glossary field migration when a dictionary title changes.
- Add recommended dictionary downloads for JMdict, Jiten, and Jitendex, with each dictionary downloaded individually.

### Fixed

- Keep continuous-mode reader lookup popups aligned with selected text when reader padding is applied.
- Skip low-confidence short Sasayaki subtitle cues during matching, matching iOS behavior and avoiding poor read-along alignments.

## [v0.6.2] - 2026-05-14

### Added

- Read mokuro manga alongside EPUBs: import a mokuro `.zip`/`.cbz` bundle or a mokuro output folder from the bookshelf, then read it with the page artwork plus selectable OCR text wired to dictionary lookup. Turn pages with on-screen previous/next buttons, swipes, or the volume / hardware page keys (taps are reserved for word lookup so they never move the page); right-to-left reading order, per-page resume, and an e-ink-friendly high-contrast lookup highlight.
- Manga OCR text now stays hidden until you tap a speech bubble: the first tap reveals that bubble — on a solid white plate that grows to fully contain the text, with a white halo on the glyphs, so it stays legible even over solid-black artwork — and a second tap on the revealed bubble looks the tapped word up. Holding the lookup back to a second tap keeps the dictionary popup from covering the bubble's action buttons; tapping empty artwork hides the revealed bubbles again. A revealed bubble shows a small copy button that copies its whole text. Page turns play a right-to-left slide animation by default — a forward turn slides the page off to the right and brings the next page in from the left — and a right swipe moves forward while a left swipe moves back.
- Ask ChatGPT about a manga speech bubble: a revealed bubble shows a ChatGPT button next to the copy button that sends a configurable prompt plus the bubble's OCR text to OpenAI and shows the reply in a closable popup over the page, with the reply rendered as formatted Markdown. Set the API key, model, and prompt from the manga reader's overflow (⋯) menu, which also opens a per-manga history of past ChatGPT exchanges.
- Add a reader popup Reduced Motion Scrolling option that scrolls lookup popups by a configurable percentage of the current popup height.

### Changed

- Change GitHub release updates to automatically check only, prompt before downloading, support skipping a version, and clean up installed-version APKs.
- Draw reader lookup selection marks as close underlines in E-ink mode instead of filled highlights.
- Split manga page-turn animation from E-ink Mode: Behavior now has a Disable Page-Turn Animation toggle for instant page swaps without forcing black-and-white rendering.
- Gate the GitHub-release auto-updater behind a compile-time flag (`UpdateConfig.AUTO_UPDATE_ENABLED`, off by default) and route the GitHub owner/repo through the same config. While the flag is off the periodic update check, the "Update Downloaded" install prompt, and the "Automatically Check for Updates" toggle are all dormant; flipping the flag (and pointing the owner/repo at your fork) brings the original updater back online.

### Fixed

- Fix animated mokuro page turns shrinking the outgoing page mid-slide: the page now sizes its layout boxes from the host-provided viewport in definite pixels, instead of viewport-edge insets / JS that resolve against the WebView's layout viewport — which `WebView.draw()` was snapshotting at the wrong size and centring.
- Prevent the About update section from flashing stale default status while loading saved update state, and keep update prompt actions aligned on one row.
- Reuse a warm reader root lookup popup shell so repeated reader lookups avoid rebuilding the popup WebView.
- Keep reader popup internal dictionary redirects from rendering stale entries from the previous popup result.
- Synchronize popup-to-popup selection marks with child popup display and draw E-ink popup selections as underlines.
- Keep vertical lookup selection marks and popup placement aligned to one ruby-aware selection area so furigana is not covered.
- Keep reader progress counters from refreshing ahead of paginated page turns on slow E-ink screens by waiting for the WebView page state to be ready to draw.
- Synchronize reader lookup popup visibility with the selected-word highlight on slow E-ink screens, while keeping highlighted text readable.
- Prevent reader lookup popups from briefly showing a blank white shell while opening or dismissing on slow E-ink screens.

## [v0.6.1] - 2026-05-14

### Fixed

- Replace Google Drive sync authorization with user-configured Device Code flow so Android can use the same Google Cloud project as iOS/ッツ sync.

## [v0.6.0] - 2026-05-14

### Added

- Add iOS-compatible Google Drive sync with Advanced -> Syncing settings, Google Cloud OAuth setup guidance, bookshelf long-press manual sync, reader auto import/export triggers, statistics sync options, and Sasayaki playback-position sync.

### Fixed

- Keep the reader top title centered with the progress text when only one top chrome button is visible.
- Prevent paginated reader progress from moving backward on page turns when vertical text layout reorders text nodes across columns, while still updating and restoring progress inside large text nodes.
- Prevent books from shifting text after opening at positions with Sasayaki matches, without slowing reader restore.
- Align the Advanced Backup entry with iOS by moving it into its own section with a storage icon.
- Prevent Behavior, Statistics, Sasayaki, Audio, and other settings pages from briefly rendering default switch values before saved settings load.

## [v0.5.0] - 2026-05-13

### Added

- Add iOS-compatible reader reading statistics with Advanced settings, reader Statistics sheet, optional session toggle, bottom speed/time display, and per-book `statistics.json` sidecar storage.

### Changed

- Align the reader Statistics sheet and top-left statistics toggle with iOS by removing the sheet header close row and using a timer icon while tracking.
- Stabilize reader bottom sheets so Chapters, Appearance, Statistics, and Sasayaki open as a single 70%-height panel with a top dismiss area, and hand fast internal scrolling to their content instead of jittering between detents.
- Make reader sheets denser and smooth Appearance sheet scrolling by reducing row heights across Chapters, Appearance, Statistics, and Sasayaki, tightening Appearance rows, and preventing Appearance segmented controls from truncating labels or losing selected-state contrast in E-ink mode.
- Keep the reader Chapters sheet cover header while removing the extra large Chapters title and close button, and reduce boundary scroll jank in the chapter list.

### Fixed

- Pause active reader statistics tracking while the reader is backgrounded, persist the flushed values, and resume the active session on return so background time is not counted.

## [v0.4.4] - 2026-05-13

### Added

- Focus the Dictionary tab search field when opening it and hint Japanese input to installed keyboards.
- Add an optional Behavior setting that lets volume keys seek Sasayaki playback when the current book has an audiobook loaded.
- Add an Appearance switch for System theme to use Sepia as the light reader theme, matching iOS.

### Fixed

- Show clear AnkiDroid setup errors when fetching decks and note types fails, distinguish missing AnkiDroid from denied access, and offer a shortcut to app settings after permission denial.
- Keep reader popup appearance changes from rebuilding the open reader WebView, preserving continuous-mode scroll progress updates.

## [v0.4.3] - 2026-05-12

### Added

- Add an iOS-style reader focus mode that hides reader chrome and the Android status bar while keeping the reading layout stable.

### Changed

- Move the reader Sasayaki play/pause shortcut to the top-right chrome and let its visible state reserve top reader space like iOS.
- Turn on Sasayaki, the reader Sasayaki toggle, auto-scroll, and lookup auto-pause by default, and expose the reader Sasayaki toggle from Appearance settings.
- Add Sasayaki reader skip controls that can show bottom rewind/fast-forward buttons and choose whether all Sasayaki skip actions jump by cue or by 5, 10, 15, or 30 seconds.

## [v0.4.2] - 2026-05-12

### Fixed

- Keep the Dictionary tab search cursor visible in dark theme. #54
- Refresh Dictionary tab results and open reader lookup popups immediately when the app or reader theme changes. #55
- Keep the reader text area aligned with iOS when hiding the title or moving progress to the bottom, avoiding unused top space and progress text overlapping the book text.
- Shrink the reader bottom buttons and menu, keeping the reader text area and bottom menu aligned to the compact controls so the bottom progress text is not covered.
- Apply saved reader text layout settings such as Vertical Padding to already-open reader WebViews instead of leaving the text rendered with stale defaults.
- Make Continuous reader padding affect each visible viewport: Horizontal Padding in vertical writing and Vertical Padding in horizontal writing. #52
- Keep the Appearance Layout Mode control wide enough to show the Continuous label without truncation.

## [v0.4.1] - 2026-05-12

### Fixed

- Open dictionary definition web links in Android's external default browser, matching iOS popup behavior.
- Update the paginated reader's top progress counter on every page turn when the next page begins exactly at a text boundary.
- Let pitch dictionaries whose content banks have bad ZIP CRC metadata import successfully when their `index.json` is readable.

## [v0.4.0] - 2026-05-11

### Added

- Add iOS-style bookshelf management with custom shelves, a Reading shelf toggle, shelf previews, toolbar action grouping, single-book and batch moves, batch deletion, and Mark Read.
- Add iOS-compatible Books and Dictionaries backup restore in Advanced -> Backup using `.hoshi` archives.
- Add GitHub release update checks with automatic APK downloads, mirror fallbacks, Settings -> About manual checks, update-ready startup prompts, and Android package-installer handoff.
- Add a GitHub repository link to Settings -> About for starring this app's project.
- Add a Settings -> About storage cleanup tool that scans app-private leftovers by category and asks for confirmation before deleting them.
- Add e-ink-friendly blocking progress overlays for EPUB, dictionary, font, Sasayaki audio, local audio database, and backup file tasks, including current archive names during bulk dictionary imports.
- Let Books import multiple EPUB files in one picker session, with batch progress and automatic return to the bookshelf when finished.

### Changed

- Refresh the Android launcher icon with the iOS Hoshi artwork, circular-mask padding, and no bundled generated PNG assets.

### Fixed

- Show concrete Java crash stack traces in Settings -> Diagnostics after Hoshi restarts from an uncaught exception.
- Prevent reader lookup popups from crashing when the popup is taller than the available screen area.
- Make dictionary imports safer by staging data before committing it and skipping already-installed dictionary archives whose title and type already match an installed dictionary.
- Release persisted external Sasayaki audio permissions when deleting a book.

## [v0.3.4] - 2026-05-10

### Added

- Let selected or shared text from Android Translate and Share actions open directly in Hoshi's lookup popup.
- Add AnkiDroid duplicate checking settings for collection, deck, or deck-root scope and optional checks across all note models. #49

### Fixed

- Keep reader lookup popups responsive after configuring AnkiDroid by checking duplicate status asynchronously.
- Export Sasayaki sentence audio for Anki cards through Media3 Transformer, improving compatibility with `.m4b` audiobooks that Android's legacy extractor cannot parse.
- Export Sasayaki sentence audio for AnkiDroid as playable ADTS AAC, avoiding broken AAC-in-MP4 sentence clips and misleading `.mp3` filenames.

## [v0.3.3] - 2026-05-09

### Added

- Add Hoshi to Android's selected-text context menu so text selected in other apps opens a lookup popup over the current app.

## [v0.3.2] - 2026-05-08

### Added

- Add a Dictionary setting to stop lookup scanning at non-Japanese text, matching the latest iOS selection behavior.

### Changed

- Replace the old auto-collapse dictionary toggle with iOS-style Expand All, Collapse All, and Custom dictionary collapse modes.
- Match iOS Sasayaki subtitle matching by considering cue length in the search window and allowing a wider configurable matching window.

### Fixed

- Speed up paginated reader page turns by caching chapter page bounds after layout, triggering swipe page turns during quick drags or short fast flicks, updating visible progress from memory immediately, debouncing bookmark saves until page turning is idle, flushing pending page-turn saves before closing or backgrounding the reader, skipping no-op selection bridge calls, and avoiding bookshelf refresh invalidations while the reader is open.
- Speed up lookup popup rendering by fetching dictionary entries in small batches instead of one at a time.
- Keep lookup popups scrolled to the top after cross-reference redirects finish rendering.
- Let dictionary section headers expand and collapse without triggering a lookup or dismissing the popup.
- Preserve `em` sizing for structured glossary images after their natural dimensions load.

## [v0.3.1] - 2026-05-06

### Changed

- Raise the reader Appearance maximum font size to 60 for larger text. #46
- Move Sasayaki audiobook playback and dictionary word audio onto Media3 ExoPlayer for more consistent playback, seeking, speed control, and system media integration.

### Fixed

- Make paginated reader swipes match iOS text orientation: horizontal text advances on left swipe, while vertical text continues to advance on right swipe.
- Keep Sasayaki previous/next cue controls available from Android system media controls, and make paused cue seeks reveal the correct text without flickering back to the old highlight.
- Make dictionary word audio respect the Background Audio setting, so Interrupt can hand audio focus back afterward while Lower Volume uses Android's best-effort ducking request and Keep Volume keeps background audio playing.
- Let local audio `android.db` imports appear in stricter vendor Android file pickers while still rejecting non-database files after selection.

## [v0.3.0] - 2026-05-05

### Added

- Add the iOS-style reader continuous scroll mode with an Appearance layout toggle and chapter boundary swipe distance control.
- Add AnkiDroid mining from dictionary lookup results, including Lapis field defaults, iOS-compatible handlebars, duplicate checks, tags, and media export support.

### Fixed

- Keep dictionary popup entries readable when Android is in system night mode but Hoshi is using the light app theme.
- Make dictionary management reordering use an explicit drag handle and require tapping the revealed trash button after a left swipe, reducing accidental dictionary deletion.
- Keep repeated dictionary reordering aligned with the row being dragged when several dictionaries are installed, and prevent the release animation from snapping back before settling.
- Match the bookshelf cover loading placeholder to the shelf background, preventing white cover flashes when returning to Books from the bottom tab bar in dark mode.
- Wait for saved reader appearance settings and the first bookshelf load before drawing the app shell, avoiding light-theme empty-library flashes during dark-theme cold starts.
- Let the Android launch screen follow the system light or dark mode before the app UI is ready.
- Match the reader loading screen to the active reader background, preventing a white flash when opening books in dark mode.
- Keep the bookshelf visible while opening an existing book, preventing a brief loading-spinner flash before the reader appears.
- Refresh Settings detail controls immediately after toggles or option changes, and remove Navigation3 fade transitions so page switches are e-ink friendly.
- Keep external EPUB opens and Sasayaki media-control returns in the existing Hoshi task, and ignore duplicate EPUB import requests while an import is already running.

## [v0.2.1] - 2026-05-03

### Added

- Add Android system media controls for Sasayaki audiobook playback, including the current book cover, so play/pause, previous/next cue, and seeking can be controlled from the media controls area while reading.

### Fixed

- Keep the paged reader snapped to full pages while selecting text, so Android WebView selection handles no longer leave vertical text split between page offsets. #43
- Keep Sasayaki cue highlighting aligned after compatibility ideographs such as `猪`, so subsequent audiobook cues no longer drift by one character.
- Keep the reader open when returning from Android system media controls during Sasayaki audiobook playback, preventing overlapping audio from a second app entry.
- Keep the screen awake during Sasayaki audiobook playback only when Auto-Scroll is enabled, matching iOS and restoring normal sleep behavior when playback pauses or Auto-Scroll is off.
- Keep reader Appearance and Sasayaki sheets fixed at half height so their internal settings lists scroll without fighting bottom-sheet expansion gestures, while preserving drag-handle swipe-down dismissal and matching the Appearance handle background to the settings page. #42

## [v0.2.0] - 2026-05-03

### Added

- Add Sasayaki audiobook read-along support, including SRT matching, cue highlighting, audiobook playback controls, delay and speed adjustment, auto-scroll, and saved per-book playback state.
- Add Sasayaki controls in lookup popups and reader settings for replaying or continuing from the selected cue, pausing playback during lookup, and choosing whether audiobooks stay linked as external files or are copied into app storage.
- Add an Appearance option to invert Sepia reader pages in system dark mode.
- Add fixed Page Up/Page Down reader paging and a Settings -> Behavior page for optional volume-key paging with reversible direction.
- Add lookup popup link redirects with back/forward history, optional popup action bar controls, and a Compact Pitch Accents dictionary setting.

### Fixed

- Reject mismatched import files before reading them, so EPUB, Sasayaki SRT, Sasayaki audiobook, local audio database, dictionary, and reader font imports only accept their supported file extensions.
- Use the same compact Settings detail header for Dictionaries, Appearance, and Advanced so subpage content starts directly below a single top bar.
- Prevent dictionary lookups from starting when tapping links, ruby text, or popup cross-reference links, and align reader WebView text sizing with iOS.
- Keep reader progress and page turns in sync after tapping EPUB internal chapter links from the in-book table of contents. #39
- Keep lookup popup text, tags, and controls readable when E-ink Mode and Dark theme are both enabled. #37
- Let long Dictionary tab lookup results scroll normally after search. #40
- Keep Dictionary tab cross-reference links responsive after repeated redirects and fast when swiping back to prior results. #41

## [v0.1.6] - 2026-05-02

### Added

- Add a Settings -> Diagnostics page that shows Android process exit diagnostics, saves them as a `.txt` file, and shares them as text for issue reports.

### Fixed

- Reduce excess top spacing in Books, Settings, and the Advanced settings header so those screens use more visible space near the status bar. #36
- Make Android system Back return from Settings -> Diagnostics to Settings instead of closing the app.
- Render lookup popups in e-ink mode with high-contrast square black/white styling for dictionary tags, frequency labels, and popup controls.
- Fix Dictionary tab lookup results rendering blank after search in v0.1.5 by loading popup CSS and JavaScript from the shared WebView bridge regardless of the result page base URL. #33
- Render SVG dictionary media in lookup popups, restoring icons embedded in structured dictionary definitions. #35
- Fix a crash when opening `また、同じ夢を見ていた.epub` by normalizing EPUB-private CSS before Android WebView renders reader chapters. #34

## [v0.1.5] - 2026-05-02

### Added

- Add an Appearance e-ink mode that maps app chrome, reader surfaces, selected controls, and progress indicators to pure black/white colors for e-ink displays.

### Fixed

- Improve reader sheet contrast for e-ink displays: Chapters is opaque, Chapters and Appearance no longer dim the page behind them, and both use a visible top outline boundary.
- Restore readable selected Appearance segmented controls and their Material selected check indicator in e-ink light/dark reader settings.
- Keep dictionary imports from dimming the Dictionaries page while the import spinner is shown.
- Remove the outline from the reader bottom Back/Menu buttons in light and dark themes.
- Reduce extra top and bottom spacing in the reader so page content uses more of the visible reading area. #29
- Prevent the reader from briefly flashing the start of a chapter before restoring the saved reading position. #30
- Delay lookup popup display until its first rendered entry is ready, avoiding a blank white popup flash on slow-refresh e-ink screens.
- Speed up dictionary results and lookup popups by serving shared popup assets from the WebView bridge and fetching entries lazily instead of embedding every entry in the initial HTML.
- Reflow reader pages after device orientation changes so the chapter is rendered for the new screen ratio. #31

## [v0.1.4] - 2026-05-01

### Changed

- Redesign the Books, Dictionary, and Settings shell with Material 3 adaptive navigation, responsive bookshelf sizing, and constrained large-screen settings layouts.
- Tighten the Books screen chrome by reducing book title weight, compacting shelf and row spacing, matching the phone bottom navigation surface to the page background with a divider, disabling bookshelf overscroll stretch, and smoothing bookshelf scrolling by caching scaled cover thumbnails and loading reading progress outside each grid item.
- Reduce decorative shadows, elevation, transparency, and reader fade animation across the main shell, dictionary search/popup, reader chrome, and settings groups for lower-power e-ink devices, while keeping low-cost outline borders for visible control boundaries.
- Use full-width Material-style dividers between Settings entries instead of iOS-style inset separators.
- Remove the duplicate large-screen navigation rail inset so tablet and landscape layouts do not waste extra blank space beside the left navigation rail.
- Align the Books shelf content to the start of the large-screen content area instead of centering the constrained grid with a wide empty gutter.

### Fixed

- Fix EPUBs with XHTML self-closing script tags rendering blank in the reader by loading chapter XHTML directly before injecting reader assets, matching iOS image sizing, and skipping blank pages produced by malformed short/image chapters. #24
- Clear reader and nested popup word highlights when dismissing lookup popups, so tapping the same word again opens a fresh popup instead of only clearing stale highlight state. #25
- Replace iOS-only reader font presets with Android Japanese Mincho and Gothic system font presets so switching fonts changes reader rendering. #26
- Keep the reader open when the device display orientation changes, instead of returning to the bookshelf. #27
- Import large local audio databases in the background with progress, require deleting the existing `android.db` before importing another one, and explain the extra free-space requirement for the copied database. #28

## [v0.1.2] - 2026-04-30

### Fixed

- Preserve embedded cover image ratios for Calibre SVG cover wrappers instead of stretching the cover image. #4
- Make Android system Back return from Settings -> Appearance to Settings instead of closing the app. #21
- Let EPUB, dictionary, font, and local audio database imports show Android providers that appear under "Browse files in other apps".
- Make dictionary popup swipe-to-dismiss default on with a lower threshold range, tolerate natural diagonal horizontal swipes, and apply Appearance changes immediately inside an open reader.

## [v0.1.1] - 2026-04-30

### Fixed

- Fix the Settings `Report an Issue` link so it opens the Android issue tracker. #2
- Fix dark-mode readability across the app, reader, and settings surfaces. #1
- Fix release APK EPUB import and reader startup failures.
- Keep the Background Audio segmented control evenly sized when labels wrap. #3
- Disable the Android stretch effect when dragging past the edge of the reader or dictionary popups.

## [v0.1.0] - 2026-04-28

### Added

- Add EPUB import through Android DocumentsUI with multi-book bookshelf storage.
- Add bookshelf covers, title metadata, recent/title sorting, duplicate import handling, progress display, and single-book deletion.
- Add the reader with saved position restore, page and chapter navigation, vertical Japanese text support, cover/image page handling, and reader chrome.
- Add reader appearance controls for theme, text orientation, font size, spacing, line height, furigana visibility, progress/title display, popup sizing, and imported reader fonts.
- Add the reader Chapters sheet for table-of-contents navigation and full-book progress.
- Add text selection in the reader with dictionary lookup popup positioning and selection highlighting.
- Add Yomitan dictionary import and management for term, frequency, and pitch dictionaries.
- Add the Dictionary tab search experience with iOS-style result rendering, nested lookup popups, dictionary ordering, enable/disable, and swipe delete.
- Add dictionary settings for default tab behavior, lookup limits, scan length, dictionary collapse, compact glossaries, expression tags, harmonic frequency, pitch deduplication, and custom CSS.
- Add local and remote word audio playback from dictionary result audio buttons.

### Changed

- Align Books, Dictionary, Settings, reader chrome, Appearance, dictionary management, and lookup popups with the iOS user-visible behavior.
- Use Material icons and compact iOS-style controls across the main shell, search chrome, reader menu, and settings pages.

### Fixed

- Restore saved reader progress without briefly flashing the beginning of the chapter.
- Keep lookup popups correctly positioned below the top system area.
- Keep reader and dictionary popup interactions consistent for menus, nested lookups, scrolling, and tap-outside dismissal.
- Preserve dictionary result scroll position when opening nested lookups.
- Let long dictionary popup content scroll consistently.
- Keep popup backgrounds and dictionary text readable in light and dark themes.
- Apply reader popup sizing and swipe-dismiss settings consistently in both Reader and Dictionary flows.
- Improve reader and bookshelf chrome spacing.
- Polish popup audio button alignment and pressed feedback.
