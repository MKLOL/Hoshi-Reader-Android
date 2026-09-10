# Validation entry points

- HTTP sync: `./gradlew :app:testDebugUnitTest --tests 'moe.antimony.hoshi.features.sync.integration.*'` runs the production engines against the real `tools/sync-test-server` over HTTP (needs `python3`); the iOS repo's `python3 -m unittest Tests.Regression.test_sync_integration` does the same for iOS in the simulator. Both must pass before any sync release. Run the iOS scenarios only with `HOSHI_IOS_SIMULATOR` naming a dedicated disposable simulator; they replace its test library.

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

For build label regressions, verify build variant manifest labels override localized app name resources: release builds keep the launcher label `Sui Manga Reader` and debug builds show `Sui Manga Reader Debug` on English and Simplified Chinese devices.

For bookshelf tab-switch regressions, use real-device screen recording to confirm cover placeholders do not flash white when returning to Books from the bottom tab bar.

For bookshelf-to-reader regressions, use real-device continuous screenshots or screen recording to confirm no Bookshelf loading spinner or dark-mode white loading frame appears between tapping a book and showing the Reader.

For reader/dictionary/audio user flows, perform targeted emulator or device validation using the test data listed in `AGENTS.md`; include external AnkiconnectAndroid Local Audio URL add behavior, built-in Local Audio enable behavior, MP3 and Opus `android.db` playback, and use the `pixivで読む` definition link case for dictionary external-link regressions.

For reader/dictionary theme regressions, verify open Dictionary tab results, the Dictionary search cursor, reader lookup taps and open reader lookup popups, system status/navigation icon contrast in Light, Sepia Light, Dark, Sepia Dark, and Custom interface modes under Android system dark mode, reader theme-family switches update colors without WebView reload, Custom background/text/info colors update immediately, and System theme's Use Sepia as Light Theme toggle update immediately when switching between Light, Dark, System, Custom, and E-ink appearance modes.

For reader process-restore regressions, verify returning directly to an open book after app process eviction still rebuilds dictionary lookup and opens reader lookup popups without first visiting the bookshelf.

For Dictionary tab input regressions, verify opening the tab focuses the search field, shows the soft keyboard, and hints Japanese input when a Japanese-capable keyboard is installed.

For reader appearance chrome regressions, verify Show Title off, Show Back Button on/off, Progress Position Bottom, compact bottom buttons, Sasayaki top-right toggle spacing, top title centering with asymmetric top buttons, bottom reader-menu spacing, iOS visual item order, light-mode menu outline visibility, focus mode status-bar hiding without text reflow, Android Back revealing chrome before closing the reader, and all progress indicators hidden against the paginated reader text area.

For reader appearance controls, verify Layout Mode shows both Paginated and Continuous labels without truncation in the settings page and reader sheet.

For reader statistics regressions, verify Advanced -> Statistics defaults off, enabling it turns on the three Appearance statistics toggles, Off/Page Turn/On autostart modes, the reader Statistics sheet without an extra header close row, single 70%-height reader sheet behavior without detent jitter, compact reader sheet row density, smooth Appearance and Chapters sheet scrolling, the Chapters sheet without the extra large title/close row while keeping the book cover header, untruncated Appearance segmented labels with clear selected-state contrast in E-ink mode, compact single-row Appearance font selection, the top-left session toggle using chart/timer icons, bottom speed/time display, page-turn delayed saves, close/background `statistics.json` persistence, and background pause/resume without counted elapsed time.

For Sasayaki settings regressions, verify fresh installs default Sasayaki, Show Sasayaki Toggle, Auto-Scroll, and Auto-Pause on Lookup on, and that Appearance can toggle the reader Sasayaki button.

For Sasayaki matching regressions, verify short low-confidence `＊` subtitle cues are skipped while longer `＊` cues still match and advance playback alignment.

For Sasayaki skip-control regressions, verify the same cue/5s/10s/15s/30s action applies from reader safe-area playback controls, Sasayaki sheet controls, and Android system media controls.

Blocked: device-validate Sasayaki bottom safe-area playback controls once an Android target is available, covering the inherited/default-on Pin Playback Controls to Safe Area toggle in the Sasayaki menu, left-aligned rewind/play-or-pause/fast-forward controls with corner padding, vertical-writing reverse action behavior, right-aligned bottom progress when both are enabled, centered progress when only progress is fixed, and absence of the old Back/Menu-flanking skip buttons.

For Sasayaki volume-key regressions, verify volume-key seek with loaded audiobook audio, fallback without loaded audio, priority over Volume Keys Turn Pages, and Reverse Volume Key Direction affecting both seek and page-turn controls.

For reader keep-screen-on regressions, verify Behavior -> Keep Screen On defaults off, persists after leaving settings, keeps the display awake while the reader is foregrounded when enabled, clears after closing the reader when disabled, and still keeps Sasayaki playback awake only while playback and Auto-Scroll are active.

For reader text layout regressions, verify Appearance -> Layout changes such as Vertical Padding reload the current chapter at the displayed position and visibly affect text spacing; also spot-check vertical ruby text near the bottom of a line so furigana-adjacent text continues in the current column when there is room.

For continuous reader layout regressions, verify vertical-writing Horizontal Padding and horizontal-writing Vertical Padding inset the current visible viewport rather than only the chapter ends, and continuous reader chrome only re-enters focus mode from a new drag gesture after tapping to reveal controls.

For continuous reader gesture regressions, verify a long drag that reverses direction still scrolls, that dragging backwards at the start of a chapter reaches the previous chapter's end, and that lifting a finger mid-chapter never turns a chapter on its own.

For reader popup settings regressions, verify changing every Popup section control while a continuous reader is open does not rebuild the WebView and does not stop scroll progress updates.

For localization changes, run `./gradlew :app:testDebugUnitTest --tests moe.antimony.hoshi.LocalizationResourceTest` and keep `docs/TRANSLATING.md` aligned with supported locale resource directories.

For app-language regressions, verify the Advanced settings Language card appears only on Android 13+, selection persists through Android system App Language, Follow system clears the app locale, and Android 12 or lower continues to follow the system language without showing the card.
