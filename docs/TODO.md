# Hoshi Android Agent TODO

Last updated: 2026-09-10

This file is the short operational handoff for future agents.

## Maintenance Rules

- Keep this file under 150 lines.
- Record only current state, next actionable work, active blockers, and durable validation requirements.
- Do not paste long emulator transcripts, adb details, screenshot observations, release notes, or per-commit history here.
- Put user-visible shipped changes in `docs/CHANGELOG.md`.
- Keep architecture-refactor slice state out of tracked docs; use the local `.codex/skills/hoshi-refactoring-workflow` skill when available.
- Put detailed reproduction, verification logs, and investigation notes in the relevant issue, PR, commit message, or a focused doc.
- When completing a task, update the smallest relevant line here in the same commit.
- Keep `docs/CHANGELOG.md` `[Unreleased]` free of fixup notes for not-yet-released features; fold them into the original feature entry or omit them until they describe a fix to already shipped user-visible behavior.

## Open Alignment Work

### Architecture And Hardening

- Make screen-level Compose Flow collection lifecycle-aware with `collectAsStateWithLifecycle()` where the UI lifecycle is the right owner.
- Continue Reader state/WebView bridge extraction in behavior-protected slices from `docs/ARCHITECTURE_REFACTORING.md`; `ReaderWebView` is now split into focused WebView host, chrome, popup layer, and fullscreen image modules.
- Replace remaining brittle source-string tests in touched areas with behavior, API, state-flow, or structured-config coverage where possible.
- Add EPUB/WebView regression fixtures for cover pages, multi-image pages, vertical text, horizontal text, complex spines, and broken resources.
- Add repeatable benchmark or baseline-profile entry points for cold start, EPUB import/open reader, reader page turn, dictionary search, and lookup popup open.

### Bookshelf, Import, And Backup

- EPUB is again a first-class format alongside mokuro manga: SAF file/folder import and content-URI Open-with are available; bookshelf open events carry the selected on-disk format directly into its matching reader, unsupported import errors remain localized, single-file folder imports retain blocking progress through parsing, selected EPUB tree grants are persisted, historical content/file Open-with coverage is restored, and Appearance, Sasayaki, and ッツ sync expose the iOS-aligned EPUB workflows.
- Bookshelf covers now publish stable cover sources with shelf state, decode iOS-sized 768px thumbnails, reuse cached bitmaps when returning to Books, and fill the cover frame without letterboxing.
- Device-validate bookshelf multi-select markers in E-ink mode, confirming unselected books show an empty circle and selected books show a check mark.
- Device-validate shelf-name entry, including user shelves named Reading alongside the virtual Reading Shelf, multi-EPUB DocumentsUI import, and recursive EPUB folder import in a session where text input and picker interaction can be driven reliably.
- Device-validate editable text fields in dark and E-ink themes, confirming visible cursors and cursor-driven horizontal scrolling for long search, Audio source, Sync, Anki, shelf, and book-title values.
- Cross-validate Android-created `Books` and `Dictionaries` `.hoshi` archives restored by iOS.
- Keep corrupt-backup rejection before library replacement covered by `HoshiBackupRepositoryTest`.

### Reader And Lookup

- Audiobook replacement stages the complete copy before an atomic move; retain `SasayakiAudioRepositoryTest` and `SasayakiAudioRepositoryInstrumentedTest`.

- EPUB URL paths decode once, contents links resolve from their navigation document, and resource fallbacks stay inside the imported book. Regression entries: `EpubBookParserTest`, `EpubBookModelTest`, `ReaderInternalLinkTest`, and `ReaderWebResourceBridgeTest`.
- Native dictionary popup teardown invalidates queued JavaScript callbacks before WebView destruction and cancels superseded or dismissed nested lookups. Regression entry: `PopupCallbackDispatcherTest`. Keep these checks when changing popup ownership.
- Shared native dictionary reads/rebuilds use one monitor; regression entry: `DictionaryNativeConcurrencyInstrumentedTest`. Because that monitor makes a lookup wait for a rebuild, every reader lookup runs through `ReaderLookupRunner` (EPUB), the manga reader's `lookupSelectionJob`, or the popup overlay's `lookupScope`, never on the main thread (`ReaderLookupRunnerTest`). Text reaching the native engine is sanitized in `LookupEngine` (`LookupTextTest`). Unicode selection keeps complete characters with UTF-16 DOM offsets (`ReaderSelectionUnicodeWebViewTest`, `SentenceLookupQueryTest`); manga font search terminates on integer bounds (`MangaWrapFallbackInstrumentedTest`).
- Sentence mode (experimental, EPUB only, `features/reader/sentence/`): segments chapters exactly like `tools/pretranslate` (`EpubSentenceSegmenterTest` pins the rules) so stored sentence translations resolve; validated on the emulator (word-tap popups, tap-outside dismissal, swipe navigation, font and theme controls); promote it out of experimental after it has been used on a few real books.

- Use `docs/IOS_UPSTREAM_SYNC_QUEUE.md` as the current iOS upstream sync queue; checked through `61306c7`, with popup scaling/vertical anchors, reader image/selection follow-up fixes, Dictionary pull-to-clear/auto-update, Anki/IPA glossary behavior, and TTU/Google Drive bookdata sync pending.
- iOS upstream slices 1–5 and 7 are aligned: viewport padding, image interactions, paragraph spacing, immersive chrome, recursive scan length, and publisher CSS sanitization. Keep their remaining device matrices below.
- Reader Appearance now supports iOS-style Custom theme colors with a separate Interface setting; real-device smoke covered immediate Background, Text, and Info color updates from the reader sheet, with the full theme regression matrix still tracked below.
- Device-validate the reader lookup iframe popup path across paged and continuous mode, vertical and horizontal writing, recursive child lookup, parent-scroll child dismissal, duplicate state, audio error/autoplay, popup scale levels, redirect history, Sasayaki popup controls, dark-mode action button contrast, E-ink selection marks, swipe dismiss, outside tap/stylus dismiss, dictionary media images, and absence of invisible touch blockers after dismissal.
- Reader lookup iframe now preloads/reuses the root iframe, gates visibility on first renderable content plus root selection highlight readiness, restores E-ink underline-style root marks, keeps action/Sasayaki controls aligned with the native popup layout, and lazy-loads popup dictionary media; it has real-device smoke coverage for vertical lookup, Sasayaki control-bar layout, popup bottom overscroll isolation, and swipe dismiss, while the full validation matrix above remains open.
- Smoke-test Dictionary tab and Process Text lookup popups after reader iframe work, confirming their cold native overlay path still supports recursive lookup, audio/Anki buttons, redirects, selection marks, and touch passthrough.
- Device-validate vertical lookup selection on ruby text, confirming E-ink underlines, regular highlights, and popup placement share one furigana-aware selection area.
- Device-validate continuous-mode lookup popup placement with nonzero reader padding in both vertical and horizontal writing.
- Device-validate paginated page turns with top and bottom progress counters enabled on E-ink, confirming the counter no longer refreshes before the page flip.
- Device-validate E-ink reader lookup underlines in horizontal and vertical text, confirming the line sits close to selected text without obscuring glyphs.
- Device-validate reader popup Reduced Motion Scrolling on an E-ink target, including vertical swipe threshold, 40%-100% scroll amount, mouse wheel/page-wheel input, and coexistence with horizontal swipe-to-dismiss.
- Device-validate popup-to-popup lookup selections, confirming child popup display syncs with iframe/native parent selection marks, E-ink mode uses underlines, and scrolling a parent popup dismisses child popups.
- Device-validate reader lookup with a real tablet stylus, confirming hover plus tap opens lookup, tapping outside closes the lookup popup, and finger taps and popup interactions still work.
- Device-validate reader lookup popup open and dismiss on a slow E-ink target, confirming popup content appears before interaction, autoplay does not outrun first visible content, iframe selection marks appear and disappear with the popup, and highlighted text stays readable.
- Device-validate lookup popup Anki mining after the v1.1.2 diagnostics fix, covering reader iframe popups, Dictionary tab popups, AnkiDroid, and AnkiConnect without main-thread freezes.
- Validate paginated and continuous reader modes together for cover image pages, multi-image illustration pages, long text paging, chapter-list jumps into mid-book chapters, forward/backward progress monotonicity, per-page progress updates and restore landing inside large text nodes, forward and backward chapter boundaries, reverse cross-chapter landing at the previous chapter end, lookup popup open, and bookmark restore.
- Device-validate bookshelf-to-reader open latency after the reader route stopped doing duplicate EPUB text parsing when valid `bookinfo.json` sidecars are present.
- Device-validate iOS-style reader jump return controls after chapter, character, highlight, and internal-link jumps, confirming back/forward targets remain stable through paginated and continuous manual movement.
- Re-check forward chapter-boundary landings at chapter start, restore-gated chapter jumps, and stable progress counters during rapid boundary flips after reader pagination changes.
- Sasayaki reader highlighting keeps non-E-ink scrolling alignment, preserves EPUB emphasis marks, uses ruby-aware E-ink cue and lookup overlays, restores colored highlights after disabling E-ink Mode, and no longer blocks reader WebView creation on match sidecar loading. Blocked: device-validate horizontal/vertical furigana cues and lookup boxes on an E-ink target, plus popup close, next lookup, navigation clearing, delayed restore/sidecar cue display, continuous-mode non-E-ink cue following, and the span fallback on older WebViews. Next open-to-text performance target: WebView prewarm after bookshelf first paint.
- Re-run diagonal popup swipe validation once a Reader or nested Dictionary popup state is reliably reachable.
- Future reader fixes must start from `reference/Hoshi-Reader-iOS/Features/Reader/ReaderWebView/ReaderWebView.swift` plus the matching JS/CSS, and must keep WebView-based reading and lookup.

### Dictionary

- Device-validate recommended dictionary downloads from the Dictionaries screen, covering JMdict, JMnedict, Jiten, and Jitendex individual downloads and confirming each imported dictionary remains updatable.
- Device-validate manual multi-dictionary import with one invalid archive, confirming later archives still import and the failed file list is reported at the end.
- Device-validate Dictionaries row long-press deletion, confirming the title area reveals the delete button while the left reorder handle still only drags.
- Device-validate Low Memory Usage Mode with a large Yomitan archive, confirming the setting defaults off, persists, reduces peak memory when enabled, and keeps imported term/frequency/pitch dictionaries usable.
- Device-validate settings segmented controls in Dictionaries, Dictionary Settings, and Advanced Audio, confirming selected labels no longer shift.
- Device-validate local audio database source ordering with imported MP3 and Opus `android.db` files, confirming default order generation, up/down moves, lookup playback, and Anki audio export.
- For deinflection regressions, verify conjugated lookup results such as `食べた` show iOS-style explanation overlays when tapping deinflection tags.
- Device-validate lookup popup theme contrast for deinflection explanation overlays and JMdict forms tables in Light, Sepia Light, and Dark themes.
- Keep frequency and pitch dictionaries type-specific; do not treat metadata dictionaries as term fallback dictionaries.
- Do not reimplement Yomitan import, lookup, media, or style extraction outside `third_party/hoshidicts-kotlin-bridge` unless the bridge gap is documented first.

### Highlights And Notes

- Blocked: device-validate reader Highlights sheet grouping for highlights in unlabeled EPUB spine entries once a repeatable fixture or saved reader state exists.
- Device-validate remaining reader highlight restore, jump, delete, all-color, compact color-picker swatches, and continuous-mode behavior against iOS after paginated creation, primary Highlight toolbar placement, anchored color-picker placement, and native selection drag page-locking were verified on Android targets.
- Blocked: real-device WebView selection can enter a valid native text-selection state while Samsung/Android does not show the floating selection toolbar; keep this tracked as a platform interaction issue before adding more highlight toolbar patches.
- Add note editing only if/when iOS exposes a user-visible notes flow.

### Anki

- Keep note-type refresh and stale-ID recovery covered by `AnkiRepositoryTest` and `AnkiRepositoryBackendSelectionTest`; removed fields must not survive a mapping refresh.
- Device-validate Android AnkiConnect against both an HTTPS internet host and a private HTTP host: connect, fetch, duplicate check, referenced-only media storage (including no unused cover/audio uploads), add-note, and optional force-sync behavior.
- Blocked: device-validate AnkiDroid add-card sync on an Android target with AnkiDroid installed, confirming the new Anki setting starts `com.ichi2.anki.DO_SYNC` only after a successful add and respects AnkiDroid's 5-minute sync limit.
- Keep backend coverage for duplicate checks, AnkiDroid fetch failures, and AnkiConnect request shaping.
- Keep popup mining decoupled from direct HTTP calls; route backend differences through the Anki backend boundary.

### Sync

- AI chat appends serialize across reader/sync store instances; settings compare timestamp instants (`AiChatHistoryStoreTest`, `AiChatSettingsRepositoryTest`).

- HTTP sync payload content hash: iOS builds through 0.11.3 published mis-derived `contentSha256` manifests, so every download failed the content check; the 22 server manifests were repaired on 2026-09-04. Both clients now correct a wrong manifest hash on download (only while the server still serves the exact manifest bytes the archive was checked against) and re-hash from disk before any replacement; every proof that the local bytes equal the server's archive records its sha256 in `.payload.zip.sha256.cache`, so pre-existing books gain the baseline that stops cross-platform hash disagreements from re-downloading them (`SyncIntegrationTest.aBookHeldBeforeTheArchiveBaselineExisted…`). Cross-platform golden vectors: `HttpSyncPayloadTest.contentHashMatchesCrossPlatformGoldenVector` and iOS `Tests/Regression/test_payload_content_hash.py` (compiles the real Swift function); change fixtures on both or neither.
- Preserve the shared lifecycle-aware loaded-settings collection pattern when adding settings pages so controls do not flash default values before saved preferences load.
- Keep reader auto-export save/upload work on a scope that survives reader route disposal so close and background flushes can finish after navigation.
- Keep HTTP Sync `payload.zip` upload/download file-backed; large Mokuro manga must not be materialized as a single `ByteArray` in production sync paths.
- Keep HTTP Sync large `payload.zip` uploads on the multipart KV API with Cloudflare-safe part sizes; do not fall back to one oversized HTTP request.
- HTTP Sync must keep Android interoperable with the current iOS EPUB wire contract: `epub.zip` + `epub.manifest` materialize remote-only EPUBs, `sentences` installs the validated offline sentence-translation sidecar, and the legacy v2 fallback must upload EPUB content instead of reporting metadata-only success.
- iOS ZIP64 payloads use central-directory extraction with entry size/CRC validation; retain the iOS-produced fixture in `HttpSyncPayloadTest` and `HttpSyncPayloadArchiveInstrumentedTest` when changing archive handling.
- Books has a token-gated HTTP cloud shortcut with shared app-owned manual sync, progress/results, and shelf refresh after completion; manga document loads reset zoom/pan before becoming ready. Validated with unit tests, build, lint, and a dedicated emulator covering zoomed page jumps/reopen/swipe plus cloud-button visibility and sync success/errors.
- Preserve HTTP Sync manual-progress callbacks when adding reconciliation phases; long-running work should update the Settings screen with a real phase and item counter.
- Preserve HTTP Sync per-key revision sidecars for bookmark/metadata edits; manual sync and auto-push paths must keep tombstones, shelf placement, imports, and bookmark writes revisioned so stale devices cannot overwrite newer remote state.
- Keep never-moved unshelved metadata timestamp-free (`V3PlannerTest`). Keep the existing-KV logical two-map HTTP sync and durable five-second EPUB/manga bookmark outbox covered: unchanged libraries stay at one metadata GET, any number of dirty positions use one per-install shard PUT, concurrent devices cannot overwrite each other, and upgraded installs retain already-downloaded books after one content-hash computation.
- Device-validate the first Android Google Drive sync slice with `testdata/test.epub` on a user-configured Device Code OAuth client from the same project as iOS/ッツ: connect/sign-out state, transient network backoff and another-device authorization guidance, long-press manual import/export result dialogs, reader-open import-only, iOS-aligned paginated/continuous auto-export timing, close/background flush export, statistics Merge/Replace, and Sasayaki last-position sync.
- Investigate `CrossEngineIntegrationTest.shelfLwwBetweenV2AndV3PicksNewerSide`: it intermittently fails in the full JVM suite (Shelf A instead of B) while the isolated class passes.

### News

- The News tab (`features/news/`) is additive: saved articles are written as extracted EPUB trees (`NewsArticleEpubWriter`, EPUB 3 + NCX for iOS) and registered through `BookshelfRepository.importExtractedEpubDirectory`, so the reader, sync and shelves see plain books; the only news-owned state lives in `files/News/` (`NewsFeedStore`). Listings come from RSS (`RssFeedParser`) or a hidden WebView (`WebViewNewsExtractor` + `assets/hoshi-news/extract.js`) because NHK's 2025 site is client-rendered behind a session token. Regression entries: `RssFeedParserTest`, `NewsFeedStoreTest`, `NewsArticleXhtmlTest`, `NewsArticleEpubWriterTest`.
- Pre-translation (`features/news/pretranslate/`) is the app's first writer of `sentence_translations.json`: `PretranslationPlanner` segments with `EpubSentenceSegmenter`, `PretranslationRunner` batches through `CloudChat` or the on-device model, the blob is validated with `EpubTranslationStore.validationError` before `SentenceTranslationsWriter` stores it, and `SentenceTranslationsUploader` PUTs `books/{syncId}/sentences` (sync itself stays download-only and, by design, re-validates the listed blob on every sync even when the local copy matches: `HttpEpubSyncInteropTest` pins that an identical malformed blob is still rejected). Cost estimates come from `ModelPricing` (approximate list prices, dated) and `TokenEstimator`. Regression entries: `PretranslationPlannerTest`, `SentenceBatchPromptTest`, `PretranslationRunnerTest`, `SentenceTranslationsWriterTest`; incomplete reruns preserve other-model translations until replacement is complete.
- Shared links (`MainActivity` "Save as article" share target, `NewsSharedUrl`, `NewsRepository.saveSharedUrl`) reuse the same extractor; a URL is attributed to the built-in source that owns its host so its hints apply, otherwise to the shared-link pseudo-source.
- NHK NEWS WEB EASY (NHK ONE) serves its article list only inside Japan; the source starts disabled and is labeled Japan-only. `extract.js` clicks the site's "For users abroad" notice once (only for a source with `acknowledgeSelector`), which was not enough from the US. Blocked on access from Japan or a VPN; the public sitemap (`/news/easy/sitemap/sitemap.xml`) is the fallback listing if one is ever needed.
- Blocked: the pre-translation job has not been run against a real model on a device (no API key or downloaded on-device model on the test emulator). The dialog, planning, cost estimate, batch runner, blob writer and reader consumption are covered by unit tests; the first real run should check a cloud model with notes on and the on-device path.
- News defaults include current dated NHK Easier and Slow Communication RSS feeds; Watanoc remains an optional archive. Publication dates determine ordering, with undated guides last, including offline caches. Overlapping custom/built-in feeds retain source filters without duplicate list keys. Regression entries: `NewsSourceCatalogTest`, `NewsFeedStoreTest`, `NewsUiStateTest`; re-check extraction hints after site redesigns.

### Release Distribution

- Update transfers reconcile with DownloadManager on startup and while About is visible; queued/paused/progress/failure states, retry/cancel, and the always-available latest-release link are covered by `UpdateDownloadCoordinatorTest`, `UpdateDownloadDestinationTest`, `AboutUpdateStatusTest`, `UpdateDownloadManagerInstrumentedTest`, and `AboutUpdateLinkInstrumentedTest`. `Application.onCreate` blocks only on `UpdateStartup.snapshot()`; the DownloadManager query and APK hashing in `UpdateStartup.reconcile()` stay in the background. Cancel, Retry and Skip never discard a verified download (`UpdateDownloadCoordinatorTest`).

- Before F-Droid distribution, split update behavior by distribution channel so F-Droid builds do not bypass F-Droid update checks.
- Device-validate GitHub update prompts after the check/download split, covering skip-version, manual checks, completed-download prompts, user-triggered install, and same-version APK cleanup.
- Device-validate split GitHub release APK updates on arm64-v8a and armeabi-v7a targets, including the transitional arm64 legacy-name APK alias.

## Mokuro Manga Support (Android-only)

Branch `codex/mokuro-manga-support`. A parallel content path for mokuro manga (JSON +
page images) that reuses the bookshelf, dictionary lookup, and Anki mining.

- Working end to end, emulator-verified: import (`.zip`/`.cbz` bundle or SAF folder), bookshelf entry + cover, page WebView rendering, visible+selectable OCR text wired to the shared dictionary lookup, right-to-left navigation, volume-key paging, per-page resume. Content type is derived from disk (`mokuro.json` sidecar), never stored in the iOS-shared `metadata.json`; `Bookmark.chapterIndex` carries the page index. Accepted manga archive layout is documented in `docs/MOKURO_ZIP_FORMAT.md`; ambiguous fallback image paths are rejected instead of silently binding a page from another volume.
- Architecture invariants for future work: keep using the shared `ReaderSelectionScripts` / `ReaderSelectionBridge` / `LookupPopupStackView` for lookup; the manga page WebView is sized from the host-provided viewport dimensions (CSS `vw`/`vh` resolve to 0 in this WebView config) — do not reintroduce `useWideViewPort`/`loadWithOverviewMode` or `vh`-based sizing. Keep `.page` overflow clipped and WebView initial scaling at its default: edge OCR and rounded initial zoom must not make fitted artwork horizontally pannable and block swipes (`MangaPageZoomInstrumentedTest`).
- Emulator-verified: OCR text is hidden until a bubble is tapped (a tap reveals that bubble on a near-opaque plate and looks the tapped word up; tapping empty artwork hides revealed bubbles again), a revealed bubble shows a copy button that copies its whole text, and page turns play a right-to-left slide animation by default while Behavior can disable that animation for instant swaps without forcing E-ink black-and-white mode.
- ChatGPT bubble lookup, emulator-verified end to end: a revealed bubble shows a ChatGPT button that sends a configurable prompt + the bubble's OCR text to OpenAI and shows the Markdown-rendered reply in a closable popup; API key / model / prompt and a per-manga chat history with compact Yomitan-style dictionary context are reached from the manga reader's overflow (⋯) menu. Kept deliberately self-contained in `features/ai/` (own settings store, own `ai_chat_log.json` per book, no shared-file edits) so it stays easy to merge alongside upstream.
- ChatGPT screenshot translation, emulator-verified end to end: the manga overflow menu can open a crop overlay, map the selected zoomed/panned viewport back to source page pixels, send that crop to OpenAI, save it on the history entry, and use a separate customizable/synced image prompt while keeping the API key local-only.
- Zoom/chrome interactions, emulator-verified: one-finger swipes turn pages only when the page is not zoomed or pannable, two-finger pinch zooms and one-finger panning do not turn pages, zoomed OCR bubble taps hit the visible bubble position, and the floating controls / page chip have subtle independent backgrounds without full-width input bars.
- Manga statistics are wired to the shared `statistics.json` sidecar while presenting manga-specific page units in the reader overflow Statistics sheet; statistics are always on for EPUB and manga (no enable/autostart settings) and the Statistics screens (`features/statistics`, reached from the Books bar and Settings) aggregate every book's `statistics.json` (`ReadingStatisticsOverviewTest`, `ReadingStatisticsConsistencyTest` guard sheet/screen agreement); manga OCR characters read per day live in `manga_statistics.json` (`MangaTextReadCounterTest`), fed only by forward turns while tracking; both sidecars sync over HTTP as `books/{syncId}/statistics` and `.../manga_statistics` with a per-day merge (`HttpSyncStatisticsSync`, debounced reader pushes via `HttpSyncStatisticsPushScheduler`, `SyncIntegrationTest.statisticsMergePerDayAcrossDevices…`); adjacent manga pages are also preloaded through a small generated-HTML cache and bounded image-file warmup.
- Boox/Onyx fullscreen reader bars still need real-device validation with a tall manga page after emulator validation: the top status strip should hide while reading, and if a device keeps a system bar visible then page artwork must be inset below it.
- Not yet done: reader appearance/settings sheet for manga and two-page spreads. Manual validation should cover import of both source layouts, RTL paging boundaries, rapid page turns, tall/zoomed OCR bubble popup placement, short-landscape full-width popups, and ChatGPT history rendering on slow devices.

## Required Validation

- Follow [Validation entry points](VALIDATION.md) for build/test/lint commands, cross-platform sync checks, and the reader, theme, localization, audio, and device regression matrices.
