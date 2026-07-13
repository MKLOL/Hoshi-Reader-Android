# Manga reader debug tooling

Deterministic UI debugging for the manga reader — because `adb shell input tap`
on the reader is unreliable (OCR bubbles are tiny; screen pixels ≠ what you
eyeball off a scaled screenshot, so taps miss the boxes) and because "does the
popup cover the word?" **cannot be answered by eye** — the OCR box's
`getBoundingClientRect()` can read "clear" while the popup sits on top of glyphs
that overflow the box.

Both tools drive the reader's WebView over the Chrome DevTools Protocol (CDP),
using the page's own DOM coordinates. WebView debugging is enabled in debug
builds (`HoshiWebView.kt`).

## Setup

```bash
pip3 install websocket-client pillow
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools   # or your SDK
```
Have the emulator running the debug build, a Term dictionary imported, and a
mokuro manga open in the reader.

## `manga_debug.py` — interactive probing

```bash
python3 tools/manga_debug.py boxes        # list OCR boxes (idx, text, css+device rects)
python3 tools/manga_debug.py lookup 1     # reveal + look up box 1 (exact handleTap the app uses)
python3 tools/manga_debug.py cover 1      # <-- deterministic "does the popup cover the text?"
```

`cover <idx>` is the important one. It compares:
- the box's **rendered text extent** — a `Range` over the box contents, which
  includes trailing punctuation (e.g. `！？`) that overflows mokuro's
  fixed-height OCR box (`overflow:visible`), and
- the dictionary popup's **actual on-screen view bounds** (from `uiautomator`),

and prints the overlap in device px. Example of the bug it caught:

```
box[1] OCR-box rect (dev)      = (804,1393)-(938,1681)
       rendered text extent    = (788,1326)-(938,1789)   [overflow below box: 108px]
       popup view rect (dev)   = (224,1691)-(1064,2348)
       popup ∩ OCR box     = 0px²       clear          <- why the code thought it was fine
       popup ∩ TEXT extent = 14700px²   COVERS by 98px  <- the actual bug
```

## `manga_popup_regression.py` — sweep test

```bash
python3 tools/manga_popup_regression.py [pages]
```
Looks up every OCR box on the current page (and the next `pages-1` pages) and
asserts the popup never overlaps the box's rendered text extent. Exit code 0 =
all clear, 1 = at least one box covered. This is the end-to-end guard the JVM
layout unit tests can't be — the text overflow is a browser rendering artifact,
so it only reproduces in a real WebView.

## Why not just unit-test it?

The popup position is computed by `LookupPopupLayout` (JVM-testable, and covered
in `LookupPopupTest`), but the *rect it is given* comes from JS measuring the
live DOM. The overflow only exists once text is laid out in a real WebView, so
the faithful regression test runs on-device. `LookupPopupTest` locks the layout
invariant (popup never overlaps the rect it's told to avoid); these tools lock
the on-device behaviour (the JS hands the layout the full painted extent).
```
