<div align="center">

# Sui Manga Reader

![Platform](https://img.shields.io/badge/platform-Android-lightgrey)
![License](https://img.shields.io/badge/license-GPLv3-blue)
[![Download](https://img.shields.io/github/downloads/MKLOL/Hoshi-Reader-Android/total?label=download)](https://github.com/MKLOL/Hoshi-Reader-Android/releases)
[![Latest download](https://img.shields.io/github/downloads/MKLOL/Hoshi-Reader-Android/latest/total?label=latest%20download)](https://github.com/MKLOL/Hoshi-Reader-Android/releases/latest)

A native Android **mokuro manga reader** for Japanese learners, with tap-to-reveal OCR
speech bubbles, Yomitan dictionary lookup, AnkiDroid card mining, in-bubble ChatGPT
translation, and an e-ink reading mode.

</div>

---

## Fork &amp; attribution

**Sui Manga Reader is a fork** of [HuangAntimony / Hoshi-Reader-Android](https://github.com/HuangAntimony/Hoshi-Reader-Android),
which is itself a native Android recreation of [Manhhao / Hoshi-Reader](https://github.com/Manhhao/Hoshi-Reader)
(the original Hoshi Reader web + iOS project).

This fork narrows the app's focus from "Japanese EPUB + manga reader" to **manga-only**.
The original Hoshi Reader supports EPUBs, audiobook read-along, highlights, vertical-text
appearance settings, and Google Drive sync — if you want any of that, install upstream
instead. This fork strips the EPUB-flavored UI to keep the manga reading flow tight, and
adds:

- A Gnathonic-style OCR shrink-to-fit + word-wrap fallback for mis-detected horizontal bubbles
- An adaptive font-size clamp tuned to mokuro's tendency to overshoot character heights
- Per-bubble ChatGPT translation button + ChatGPT history popup
- Tap-to-reveal then tap-to-lookup, with a single-tap toggle for users who don't use the action buttons
- "Go to page…" jump dialog
- Native dictionary lookup engine ([hoshidicts](https://github.com/Manhhao/hoshidicts), Rust + C++)

Huge credit to **HuangAntimony** for the Kotlin/Compose Android codebase this stands on, and
to **Manhhao** for the original Hoshi Reader. Both are GPLv3, as is this fork.

## Features

- **Mokuro manga reading.** Imports a mokuro `.zip` / `.cbz` bundle and renders each page
  with selectable OCR text overlaid on the artwork. Tap a bubble to reveal it (white plate
  + black text); tap again to look the word up in your dictionaries.
- **Dictionary lookup.** Yomitan term/frequency/pitch dictionaries are imported as `.zip`s
  and queried via the native [hoshidicts](https://github.com/Manhhao/hoshidicts) engine.
  Recursive lookup inside definitions; configurable popup size and swipe-to-dismiss.
- **ChatGPT bubble translation.** Each revealed bubble has a "✨" button that sends the
  bubble text to your OpenAI account with a configurable prompt. Per-volume history is
  kept and can be reviewed from the overflow menu. Bring your own API key.
- **Screenshot translation.** Crop any region of the page and send it to ChatGPT as an
  image (separate prompt, separate history).
- **AnkiDroid card mining.** Tap a word, then "Add" — fields are populated from the
  manga's metadata, the page image, and the surrounding bubble. Supports
  [Lapis](https://github.com/donkuri/lapis)-style configurations and duplicate checks.
- **E-ink mode.** Pure black-and-white rendering, immersive system bars, no
  page-turn animation, high-contrast matched-word highlight via the CSS Custom Highlight API.
- **HTTP sync.** Optional self-hosted key/value blob server keeps reading progress and
  ChatGPT history in sync across devices. See [docs/HTTP_SYNC.md](docs/HTTP_SYNC.md).
- **Right-to-left manga reading**, configurable single-tap-to-lookup, optional Noto Sans
  JP font for the OCR overlay, and a "Go to page…" jump dialog under the overflow menu.

## Requirements

- Android 9 (API 28) or later
- ~150 MB free storage per typical manga volume (mokuro images)
- A Yomitan dictionary `.zip` — the official JMdict from
  [yomidevs/jmdict-yomitan](https://github.com/yomidevs/jmdict-yomitan) is a good start

## Download

Latest signed APK: [GitHub Releases](https://github.com/MKLOL/Hoshi-Reader-Android/releases/latest).
Sideload-install (Settings → Apps → Special access → Install unknown apps → allow
your file manager / browser).

## Building from source

Prerequisites:

- JDK 21
- Android SDK with build-tools 36.0.0 and platform 36, NDK r28+
- Rust toolchain + `cargo-ndk` (used to build the native hoshiepub/hoshidicts bridges)
- CMake 3.22.1+
- Git with submodule support — the [`hoshidicts-kotlin-bridge`](third_party/hoshidicts-kotlin-bridge)
  is a submodule

Setup:

```bash
git clone --recurse-submodules https://github.com/MKLOL/Hoshi-Reader-Android.git
cd Hoshi-Reader-Android
./bootstrap.sh                       # installs Rust targets etc.
./gradlew :app:assembleDebug         # builds app/build/outputs/apk/debug/app-debug.apk
```

Release builds default to a fallback debug-signing setup (same keystore each build) so
the unsigned upstream APK still installs side-by-side with your debug builds. To use
your own signing key, set `ANDROID_KEYSTORE_FILE`, `ANDROID_KEYSTORE_PASSWORD`,
`ANDROID_KEY_ALIAS`, and `ANDROID_KEY_PASSWORD` in your environment.

## Privacy

Sui Manga Reader stores imported manga, dictionaries, fonts, reading progress, ChatGPT
history, and settings **locally in app storage**. No telemetry, no analytics.

- **ChatGPT integration** is opt-in: you provide your own OpenAI API key, and the app
  posts directly to `api.openai.com`.
- **HTTP sync** is opt-in and points at your own server. The protocol is documented in
  [docs/HTTP_SYNC_KV.md](docs/HTTP_SYNC_KV.md).
- **Update checks** read GitHub release metadata from `api.github.com` (with optional
  mirrors); no other network traffic happens by default.

## License

Distributed under the **GNU General Public License v3.0**. See [LICENSE](LICENSE).

Sui Manga Reader, like the upstream Hoshi Reader projects it forks from, is free software.
Source code, license terms, and full third-party attribution are available in this repo
and in **Settings → About** inside the app.

## Acknowledgements

In addition to the two upstream projects called out above, this app stands on:

- [hoshidicts](https://github.com/Manhhao/hoshidicts) + [hoshidicts-kotlin-bridge](https://github.com/Manhhao/hoshidicts-kotlin-bridge) — native Yomitan dictionary lookup
- [Yomitan](https://github.com/yomidevs/yomitan) — dictionary format
- [mokuro](https://github.com/kha-white/mokuro) — the OCR pipeline that produces the manga sidecars this reader consumes
- [mokuro-reader (Gnathonic fork)](https://github.com/Gnathonic/mokuro-reader) — the reference web reader whose OCR shrink-to-fit + word-wrap algorithm this app ports
- [AnkiDroid](https://github.com/ankidroid/Anki-Android) — host for the Anki card mining integration
- [AnkiConnect Android](https://github.com/KamWithK/AnkiconnectAndroid) — local-audio behavior + duplicate-scope query references
- [ッツ Ebook Reader](https://github.com/ttu-ttu/ebook-reader) — sync compatibility reference (upstream EPUB-only feature, still wired in the data layer of this fork)
