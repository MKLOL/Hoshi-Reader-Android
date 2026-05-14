package moe.antimony.hoshi.features.mangareader

import moe.antimony.hoshi.mokuro.MokuroPage
import moe.antimony.hoshi.mokuro.MokuroTextBox

/**
 * Generates the self-contained HTML document for a single mokuro manga page.
 *
 * Layout strategy — chosen so the absolutely-positioned OCR boxes line up with the image
 * at *any* rendered size, including the letterboxing from `object-fit: contain`:
 *
 *  - A `.page` flexbox, pinned to the viewport with `position: fixed`, centres a `.frame`.
 *  - `.frame`'s pixel size is set by the page script to the largest box with the image's
 *    aspect ratio that fits the viewport, computed from `window.innerWidth/innerHeight`.
 *    The background `<img>` fills `.frame` exactly (`object-fit: contain`).
 *  - Every OCR box is a child of `.frame` positioned with **percentages** of the image's
 *    intrinsic pixel size. Percent positioning tracks `.frame`'s real rendered size, and
 *    the script re-runs on resize so rotation keeps the text aligned.
 *
 * The OCR text is real, selectable DOM text (`<p>` per box). Every box is invisible by
 * default — the reader sees only the artwork — but it stays present and hit-testable.
 * Tapping a box adds `.revealed`, which paints its text on a translucent plate and runs the
 * shared selection bridge to look the tapped word up; tapping empty artwork hides every
 * revealed box again. A revealed box also shows small action buttons — ask ChatGPT about the
 * bubble, and copy the whole bubble's OCR text. All four outcomes are routed through
 * `window.hoshiManga.handleTap` (see the page script) so a single tap path decides between
 * them. `<p>` is used because
 * the shared selection script ([moe.antimony.hoshi.features.reader.ReaderSelectionScripts])
 * scopes its sentence scan to the nearest `p`, which conveniently bounds a scan to one box.
 *
 * This deliberately does **not** reuse `ReaderContentStyles` / the EPUB pagination JS:
 * those inject `column-width` / `writing-mode` / `!important` rules that fight absolute
 * positioning. The CSS here is minimal and manga-specific.
 *
 * Image URLs are emitted relative to the `https://hoshi.local/manga/` base URL so
 * [MangaWebResourceBridge] can intercept them via `WebViewClient.shouldInterceptRequest`.
 */
internal object MangaPageHtml {
    const val BASE_URL: String = "https://hoshi.local/manga/"

    /**
     * Builds the full HTML document for [page].
     *
     * @param backgroundCssColor CSS colour for the letterbox area around the image.
     * @param selectionScript JS to inject (the shared selection mechanism); injected verbatim
     *   inside a `<script>` tag so the page's `HoshiTextSelection` interface works on tap.
     * @param scanNonJapaneseText forwarded to `window.scanNonJapaneseText`, mirroring the
     *   EPUB reader so the selection scanner respects the dictionary setting.
     * @param eInkMode picks a high-contrast matched-word highlight (black-on-white) instead
     *   of the colour highlight, which is indistinct on a greyscale e-ink display.
     */
    fun build(
        page: MokuroPage,
        backgroundCssColor: String,
        selectionScript: String,
        scanNonJapaneseText: Boolean,
        eInkMode: Boolean,
    ): String {
        val imageWidth = page.imageWidth.coerceAtLeast(1)
        val imageHeight = page.imageHeight.coerceAtLeast(1)
        val boxes = page.textBoxes.joinToString("\n") { box ->
            textBoxHtml(box, imageWidth, imageHeight)
        }
        return """
            <!DOCTYPE html>
            <html>
            <head>
            <meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=5, user-scalable=yes">
            <style>
            ${pageCss(backgroundCssColor, eInkMode)}
            </style>
            </head>
            <body>
            <div class="page">
              <div class="frame">
                <img class="page-image" src="${escapeAttribute(encodeImagePath(page.imagePath))}" alt="">
                <div class="ocr-layer">
            $boxes
                </div>
              </div>
            </div>
            <script>
            (function() {
              // .frame is sized in pixels from window.innerWidth/innerHeight rather than CSS
              // vw/vh units. A host WebView can report a 0-height CSS layout viewport while
              // the JS viewport size is still correct, which collapses vw/vh- and
              // percentage-based heights; pixel sizing from JS is the reliable path.
              var IMG_W = $imageWidth, IMG_H = $imageHeight;
              var frame = document.querySelector('.frame');
              function sizeFrame() {
                var vw = window.innerWidth, vh = window.innerHeight;
                if (!vw || !vh) return;
                var scale = Math.min(vw / IMG_W, vh / IMG_H);
                frame.style.width = (IMG_W * scale) + 'px';
                frame.style.height = (IMG_H * scale) + 'px';
              }
              sizeFrame();
              window.addEventListener('resize', sizeFrame);
              window.addEventListener('orientationchange', sizeFrame);
            })();
            window.scanNonJapaneseText = $scanNonJapaneseText;
            (function() {
              // Place the dictionary popup clear of the whole OCR text box (the sentence
              // being read), not just the tapped character. The shared selection script
              // reports the tapped character's tiny rect; here HoshiTextSelection.postMessage
              // is wrapped so the containing .ocr-box's rect is substituted before the
              // payload reaches the bridge, and LookupPopupLayout then positions the popup
              // above or below the entire bubble.
              var native = window.HoshiTextSelection;
              if (native) {
                window.HoshiTextSelection = {
                  postMessage: function(json) {
                    try {
                      var sel = window.hoshiSelection && window.hoshiSelection.selection;
                      var node = sel && sel.startNode;
                      var el = node && (node.nodeType === 1 ? node : node.parentElement);
                      var box = el && el.closest && el.closest('.ocr-box');
                      if (box) {
                        var r = box.getBoundingClientRect();
                        var data = JSON.parse(json);
                        data.rect = { x: r.x, y: r.y, width: r.width, height: r.height };
                        json = JSON.stringify(data);
                      }
                    } catch (e) {}
                    native.postMessage(json);
                  }
                };
              }
            })();
            $selectionScript
            $MANGA_TAP_HANDLER_SCRIPT
            </script>
            </body>
            </html>
        """.trimIndent()
    }

    private fun pageCss(backgroundCssColor: String, eInkMode: Boolean): String {
        // On a greyscale e-ink display a colour highlight is nearly indistinguishable from
        // the OCR plate, so the matched word is shown inverted (black plate, white text) for
        // maximum contrast; on a colour display it gets the usual amber highlight.
        val matchedWordHighlight = if (eInkMode) {
            "::highlight(hoshi-selection) { background: #000; color: #fff; }"
        } else {
            "::highlight(hoshi-selection) { background: #ffd400; color: #000; }"
        }
        return """
        * { margin: 0; padding: 0; box-sizing: border-box; }
        html, body {
          width: 100%;
          height: 100%;
          background: $backgroundCssColor;
          overflow: hidden;
          -webkit-text-size-adjust: 100%;
        }
        .page {
          /* Pinned to the viewport rather than chained off html/body height, so the page
             can never collapse if a host WebView reports an odd document height. */
          position: fixed;
          inset: 0;
          display: flex;
          align-items: center;
          justify-content: center;
        }
        .frame {
          position: relative;
          /* Width/height are set in pixels by the page script (see build()): the largest
             aspect-correct box that fits the viewport. A definite pixel size is what makes
             the percentage-positioned OCR boxes and the cqw font units resolve;
             inline-size containment makes cqw = 1% of .frame's width. */
          container-type: inline-size;
        }
        .page-image {
          display: block;
          width: 100%;
          height: 100%;
          object-fit: contain;
          -webkit-user-select: none;
          user-select: none;
          pointer-events: none;
        }
        .ocr-layer {
          position: absolute;
          inset: 0;
        }
        .ocr-box {
          position: absolute;
          line-height: 1.1;
          white-space: pre;
          /* Invisible by default: the OCR text stays in the DOM (so a tap can hit-test a
             word and the box itself is hit-testable) but transparent, so the reader sees
             only the artwork. A tap adds `.revealed`, which paints the text on a
             translucent plate. Selectable so a tap looks the word up in the dictionary. */
          color: transparent;
          background: transparent;
          border-radius: 3px;
          -webkit-user-select: text;
          user-select: text;
        }
        .ocr-box.revealed {
          /* A near-opaque white plate behind the black OCR text so a revealed bubble stays
             legible over *any* artwork — including solid-black panels, where the previous
             more-translucent plate left the text barely visible. */
          color: #000;
          background: rgba(255, 255, 255, 0.95);
        }
        .ocr-box.vertical {
          writing-mode: vertical-rl;
          text-orientation: upright;
        }
        .ocr-box p {
          margin: 0;
        }
        /* Action buttons (ChatGPT, copy): shown only on a revealed box, in a row floated
           just *above* the box's top-right corner — never over the text. (A box tightly
           bounds its OCR text, and a vertical-rl bubble even starts in the top-right
           corner, so any in-box placement covers characters.) The manga tap handler routes
           a hit on one of these to its native bridge instead of a word lookup. Buttons are
           sized in `em` so they track the box's text, with a px floor so they stay usable
           tap targets on small bubbles. */
        .ocr-actions {
          display: none;
          position: absolute;
          bottom: 100%;
          right: 0;
          margin-bottom: 3px;
          flex-direction: row;
          gap: 3px;
          z-index: 2;
        }
        .ocr-box.revealed .ocr-actions {
          display: flex;
        }
        .ocr-action-btn {
          box-sizing: border-box;
          width: 1.7em;
          height: 1.7em;
          min-width: 20px;
          min-height: 20px;
          padding: 0.3em;
          display: flex;
          align-items: center;
          justify-content: center;
          border: none;
          border-radius: 5px;
          background: #1b1b1b;
          color: #fff;
          cursor: pointer;
          -webkit-user-select: none;
          user-select: none;
        }
        .ocr-action-btn svg {
          width: 100%;
          height: 100%;
          display: block;
          /* Let elementFromPoint resolve to the button itself, not the inner SVG. */
          pointer-events: none;
        }
        ::selection { background: rgba(70, 130, 220, 0.45); }
        /* The dictionary-matched word is highlighted through the CSS Custom Highlight API
           (the shared selection script does CSS.highlights.set('hoshi-selection', ...)).
           Without this rule the highlight paints nothing, so a tap gives no feedback about
           which word it landed on — this makes the matched word visibly light up. */
        $matchedWordHighlight
    """.trimIndent()
    }

    private fun textBoxHtml(
        box: MokuroTextBox,
        imageWidth: Int,
        imageHeight: Int,
    ): String {
        val leftPct = percent(box.left, imageWidth)
        val topPct = percent(box.top, imageHeight)
        val widthPct = percent(box.width, imageWidth)
        val heightPct = percent(box.height, imageHeight)
        // Font size is in image pixels; express it relative to image width so it scales
        // with the rendered frame (cqw = 1% of the container's width).
        val fontCqw = percent(box.fontSize, imageWidth)
        val verticalClass = if (box.vertical) " vertical" else ""
        val text = box.lines.joinToString("\n").let(::escapeHtmlText)
        return """    <div class="ocr-box$verticalClass" style="left: $leftPct%; top: $topPct%; """ +
            """width: $widthPct%; height: $heightPct%; font-size: ${fontCqw}cqw;">""" +
            """<p>$text</p>$ACTION_BUTTONS_HTML</div>"""
    }

    /**
     * The action buttons baked into every box; CSS keeps them hidden until the box is
     * `.revealed`. A tap on one is recognised by [MANGA_TAP_HANDLER_SCRIPT], which routes it
     * to a native bridge instead of starting a word lookup:
     *
     *  - the ChatGPT button (a sparkles glyph) -> `HoshiMangaAi`;
     *  - the copy button (the standard two-rectangle glyph) -> `HoshiMangaClipboard`.
     */
    private const val ACTION_BUTTONS_HTML: String =
        """<div class="ocr-actions">""" +
            """<button class="ocr-action-btn ocr-ai-btn" type="button" """ +
            """aria-label="Ask ChatGPT about this bubble">""" +
            """<svg viewBox="0 0 24 24" fill="currentColor">""" +
            """<path d="M11 2.5l1.8 4.7L17.5 9l-4.7 1.8L11 15.5l-1.8-4.7L4.5 9l4.7-1.8L11 2.5z"></path>""" +
            """<path d="M18 13l.95 2.05L21 16l-2.05.95L18 19l-.95-2.05L15 16l2.05-.95L18 13z"></path>""" +
            """</svg></button>""" +
            """<button class="ocr-action-btn ocr-copy-btn" type="button" """ +
            """aria-label="Copy bubble text">""" +
            """<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" """ +
            """stroke-linecap="round" stroke-linejoin="round">""" +
            """<rect x="9" y="9" width="11" height="11" rx="2"></rect>""" +
            """<path d="M5 15V5a2 2 0 0 1 2-2h10"></path></svg></button>""" +
            """</div>"""

    /**
     * The single tap entry point for the manga page, called by [MangaReaderWebView.selectAt].
     * It inspects the element under the tap and picks one of four outcomes:
     *
     *  - ChatGPT button -> hand the whole bubble's OCR text to `HoshiMangaAi`, returning
     *    `'__ai__'` so the caller leaves the lookup popup untouched;
     *  - copy button -> copy the whole bubble's OCR text via `HoshiMangaClipboard`, and
     *    return `'__copied__'` so the caller leaves the lookup popup untouched;
     *  - text box -> add `.revealed` to paint that bubble's text, then run the shared
     *    `selectText` so the tapped word is looked up (its return value flows back out);
     *  - empty artwork -> strip `.revealed` from every box and clear the active selection,
     *    returning `null` so the caller dismisses the lookup popup.
     */
    private val MANGA_TAP_HANDLER_SCRIPT: String = """
        (function() {
          window.hoshiManga = {
            handleTap: function(x, y, maxLength) {
              var el = document.elementFromPoint(x, y);
              var aiBtn = el && el.closest && el.closest('.ocr-ai-btn');
              if (aiBtn) {
                var aiBox = aiBtn.closest('.ocr-box');
                var aiText = aiBox && aiBox.querySelector('p');
                if (aiText && window.HoshiMangaAi) {
                  window.HoshiMangaAi.askAboutBubble(aiText.textContent || '');
                }
                return '__ai__';
              }
              var copyBtn = el && el.closest && el.closest('.ocr-copy-btn');
              if (copyBtn) {
                var copyBox = copyBtn.closest('.ocr-box');
                var copyText = copyBox && copyBox.querySelector('p');
                if (copyText && window.HoshiMangaClipboard) {
                  window.HoshiMangaClipboard.copyBubbleText(copyText.textContent || '');
                }
                return '__copied__';
              }
              var box = el && el.closest && el.closest('.ocr-box');
              if (box) {
                box.classList.add('revealed');
                return window.hoshiSelection.selectText(x, y, maxLength);
              }
              var revealed = document.querySelectorAll('.ocr-box.revealed');
              for (var i = 0; i < revealed.length; i++) {
                revealed[i].classList.remove('revealed');
              }
              window.hoshiSelection.clearSelection();
              return null;
            }
          };
        })();
    """.trimIndent()

    private fun percent(value: Int, total: Int): String =
        formatNumber(percentValue(value, total))

    private fun percentValue(value: Int, total: Int): Double =
        if (total <= 0) 0.0 else value.toDouble() * 100.0 / total.toDouble()

    private fun formatNumber(value: Double): String {
        val rounded = Math.round(value * 1000.0) / 1000.0
        return if (rounded == rounded.toLong().toDouble()) {
            rounded.toLong().toString()
        } else {
            rounded.toString()
        }
    }

    private fun escapeHtmlText(text: String): String =
        text.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")

    private fun escapeAttribute(value: String): String =
        value.replace("&", "&amp;")
            .replace("\"", "&quot;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")

    /**
     * Percent-encodes a book-root-relative image path (per `/`-separated segment) so it
     * survives URL resolution against [BASE_URL]: a `#`, `?`, `%` or space in an image
     * filename would otherwise be parsed as URL syntax and the image would fail to load.
     * [MangaWebResourceBridge] resolves the request back through `URI.path`, which
     * percent-decodes, so the encoded form round-trips to the original on-disk path.
     */
    private fun encodeImagePath(path: String): String =
        path.split('/').joinToString("/") { segment ->
            buildString {
                for (byte in segment.toByteArray(Charsets.UTF_8)) {
                    val code = byte.toInt() and 0xFF
                    val ch = code.toChar()
                    if (ch in PATH_SEGMENT_SAFE) {
                        append(ch)
                    } else {
                        append('%')
                        append(HEX_DIGITS[code shr 4])
                        append(HEX_DIGITS[code and 0x0F])
                    }
                }
            }
        }

    private const val HEX_DIGITS = "0123456789ABCDEF"

    /** RFC 3986 unreserved characters — safe to leave un-encoded in a URL path segment. */
    private val PATH_SEGMENT_SAFE: Set<Char> =
        (('A'..'Z') + ('a'..'z') + ('0'..'9') + listOf('-', '_', '.', '~')).toSet()
}
