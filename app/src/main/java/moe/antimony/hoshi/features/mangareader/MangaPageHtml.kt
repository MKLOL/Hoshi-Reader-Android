package moe.antimony.hoshi.features.mangareader

import moe.antimony.hoshi.mokuro.MokuroPage
import moe.antimony.hoshi.mokuro.MokuroTextBox

/**
 * Generates the self-contained HTML document for a single mokuro manga page.
 *
 * Layout strategy — chosen so the absolutely-positioned OCR boxes line up with the image
 * at *any* rendered size, including the letterboxing from `object-fit: contain`:
 *
 *  - Every size is a definite pixel value baked into the CSS, computed in [build] from the
 *    host WebView's viewport size (`viewportCssWidth`/`viewportCssHeight`). Nothing depends
 *    on the CSS layout viewport (`vw`/`vh`, `%`, `position: fixed` + `inset`) or on a JS
 *    `window.innerWidth` read: a WebView resolves those against its layout viewport, which
 *    it can report wrong — and `WebView.draw()` snapshots the page at that wrong size, which
 *    is what made an animated page turn capture a mis-sized, mis-centred outgoing page.
 *  - `.page` is a pixel-sized flexbox covering the viewport that centres a `.frame`.
 *  - `.frame` is the largest box with the image's aspect ratio that fits the viewport. The
 *    background `<img>` fills `.frame` exactly (`object-fit: contain`).
 *  - Every OCR box is a child of `.frame` positioned with **percentages** of the image's
 *    intrinsic pixel size, so it tracks `.frame`'s rendered size. The host reloads the page
 *    with fresh dimensions when the viewport actually changes (rotation), so the baked
 *    sizes are always correct for the current viewport.
 *
 * The OCR text is real, selectable DOM text (`<p>` per box). Every box is invisible by
 * default — the reader sees only the artwork — but it stays present and hit-testable.
 * Tapping a hidden box adds `.revealed`, which paints its text on a translucent plate; a
 * *second* tap on that revealed box runs the shared selection bridge to look the tapped word
 * up. The lookup is held back to the second tap so the dictionary popup can't open on top of
 * the box's action buttons. Tapping empty artwork hides every revealed box again. A revealed
 * box also shows small action buttons — ask ChatGPT about the bubble, and copy the whole
 * bubble's OCR text. All of these outcomes are routed through `window.hoshiManga.handleTap`
 * (see the page script) so a single tap path decides between them. `<p>` is used because
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
    private const val MANGA_MAX_SELECTION_LENGTH = 16

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
     * @param viewportCssWidth the host WebView's viewport width in CSS pixels. Every layout
     *   size is computed from this and baked into the CSS as a definite px value, so nothing
     *   depends on the WebView's (unreliable) self-reported layout viewport — see the class
     *   doc for why `WebView.draw()` snapshots made that matter.
     * @param viewportCssHeight the host WebView's viewport height in CSS pixels (see
     *   [viewportCssWidth]).
     */
    fun build(
        page: MokuroPage,
        backgroundCssColor: String,
        selectionScript: String,
        scanNonJapaneseText: Boolean,
        eInkMode: Boolean,
        viewportCssWidth: Int,
        viewportCssHeight: Int,
    ): String {
        val imageWidth = page.imageWidth.coerceAtLeast(1)
        val imageHeight = page.imageHeight.coerceAtLeast(1)
        // The largest box with the image's aspect ratio that fits the viewport — the
        // `object-fit: contain` fit, computed here so it can be baked into the CSS as a
        // definite px size (no `vw`/`vh`, no JS, no layout-viewport dependency).
        val viewportWidth = viewportCssWidth.coerceAtLeast(1)
        val viewportHeight = viewportCssHeight.coerceAtLeast(1)
        val fitScale = minOf(
            viewportWidth.toDouble() / imageWidth,
            viewportHeight.toDouble() / imageHeight,
        )
        val frameWidthCss = formatNumber(imageWidth * fitScale)
        val frameHeightCss = formatNumber(imageHeight * fitScale)
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
            ${pageCss(backgroundCssColor, eInkMode, viewportWidth, viewportHeight, frameWidthCss, frameHeightCss)}
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
                        var hostRect = window.hoshiManga && window.hoshiManga.hostRectFromViewportRect
                          ? window.hoshiManga.hostRectFromViewportRect(r)
                          : { x: r.x, y: r.y, width: r.width, height: r.height };
                        data.rect = hostRect;
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
            window.hoshiManga && window.hoshiManga.installTapListener($MANGA_MAX_SELECTION_LENGTH);
            </script>
            </body>
            </html>
        """.trimIndent()
    }

    private fun pageCss(
        backgroundCssColor: String,
        eInkMode: Boolean,
        viewportCssWidth: Int,
        viewportCssHeight: Int,
        frameWidthCss: String,
        frameHeightCss: String,
    ): String {
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
          /* A definite pixel-sized box covering the viewport, sized from the host-provided
             viewport (see build()). It deliberately does not pin itself with viewport-edge
             insets: those resolve against the CSS layout viewport, which a WebView can
             report wrong — and WebView.draw() then snapshots the page at that wrong size
             and centring, which made an animated page turn slide a mis-sized outgoing page. */
          position: absolute;
          top: 0;
          left: 0;
          width: ${viewportCssWidth}px;
          height: ${viewportCssHeight}px;
          background: $backgroundCssColor;
          display: flex;
          align-items: center;
          justify-content: center;
        }
        .frame {
          position: relative;
          /* The largest aspect-correct box that fits the viewport, computed in build() and
             baked in as a definite pixel size — that is what makes the percentage-positioned
             OCR boxes and the cqw font units resolve, and inline-size containment makes
             cqw = 1% of .frame's width. */
          width: ${frameWidthCss}px;
          height: ${frameHeightCss}px;
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
             only the artwork. A tap adds `.revealed`, which paints the text on a solid
             white plate. Selectable so a tap looks the word up in the dictionary.

             The mokuro width/height are applied as *minimums* (see textBoxHtml) rather than
             fixed sizes, and a little padding is added: the WebView routinely renders the
             OCR text larger than mokuro's box, and a fixed box left that overflow spilling
             past the plate — invisible black-on-black over dark artwork. As min sizes, the
             box (and therefore the revealed plate) instead grows to fully contain the text. */
          color: transparent;
          background: transparent;
          border-radius: 3px;
          padding: 0.08em;
          -webkit-user-select: text;
          user-select: text;
        }
        .ocr-box.revealed {
          /* A solid white plate behind the black OCR text, plus a white halo on the glyphs
             as a safety net for anything that still pokes past the plate. */
          color: #000;
          background: #fff;
          text-shadow:
            1px 1px 1px #fff, -1px 1px 1px #fff, 1px -1px 1px #fff, -1px -1px 1px #fff,
            2px 0 2px #fff, -2px 0 2px #fff, 0 2px 2px #fff, 0 -2px 2px #fff;
        }
        .ocr-box.vertical {
          writing-mode: vertical-rl;
          text-orientation: upright;
        }
        .ocr-box p {
          margin: 0;
        }
        /* Action buttons (copy, ChatGPT): shown only on a revealed box, in a row floated
           just *above* the box's top-right corner — never over the text. (A box tightly
           bounds its OCR text, and a vertical-rl bubble even starts in the top-right
           corner, so any in-box placement covers characters.) The manga tap handler routes
           a hit on one of these to its native bridge instead of a word lookup. Buttons are
           sized in `em` so they track the box's text, with a px floor so they stay usable
           tap targets on small bubbles. The row resets vertical text-box writing mode so
           the buttons stay side-by-side, then reverses visual order so ChatGPT sits to the
           right of copy. */
        .ocr-actions {
          display: none;
          position: absolute;
          bottom: 100%;
          right: 0;
          margin-bottom: 3px;
          writing-mode: horizontal-tb;
          text-orientation: mixed;
          flex-direction: row-reverse;
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
        // width/height go in as *minimums*: the box grows past them when the WebView
        // renders the OCR text larger than mokuro's box, so the revealed plate always
        // fully covers the text (see the .ocr-box comment).
        return """    <div class="ocr-box$verticalClass" style="left: $leftPct%; top: $topPct%; """ +
            """min-width: $widthPct%; min-height: $heightPct%; font-size: ${fontCqw}cqw;">""" +
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
     *  - a not-yet-revealed text box -> add `.revealed` to paint that bubble's text and show
     *    its action buttons, returning `'__revealed__'` so the caller leaves the popup
     *    untouched; the word lookup is deferred to the next tap, so the dictionary popup can
     *    never open on top of (and cover) the ChatGPT / copy buttons;
     *  - an already-revealed text box -> run the shared `selectText` so the tapped word is
     *    looked up (its return value flows back out);
     *  - empty artwork -> strip `.revealed` from every box and clear the active selection,
     *    returning `null` so the caller dismisses the lookup popup.
     */
    private val MANGA_TAP_HANDLER_SCRIPT: String = """
        (function() {
          window.hoshiManga = {
            hostScaleValue: 1,
            setHostScale: function(scale) {
              if (typeof scale === 'number' && isFinite(scale) && scale > 0) {
                this.hostScaleValue = scale;
              }
            },
            hostScale: function() {
              var scale = this.hostScaleValue;
              return isFinite(scale) && scale > 0 ? scale : 1;
            },
            hostRectFromViewportRect: function(rect) {
              var scale = this.hostScale();
              var viewport = window.visualViewport;
              var offsetLeft = viewport && typeof viewport.offsetLeft === 'number'
                ? viewport.offsetLeft
                : 0;
              var offsetTop = viewport && typeof viewport.offsetTop === 'number'
                ? viewport.offsetTop
                : 0;
              return {
                x: (rect.x - offsetLeft) * scale,
                y: (rect.y - offsetTop) * scale,
                width: rect.width * scale,
                height: rect.height * scale
              };
            },
            clearRevealed: function() {
              var revealed = document.querySelectorAll('.ocr-box.revealed');
              for (var i = 0; i < revealed.length; i++) {
                revealed[i].classList.remove('revealed');
              }
              if (window.hoshiSelection) {
                window.hoshiSelection.clearSelection();
              }
            },
            installTapListener: function(maxLength) {
              if (this.tapListenerInstalled) return;
              this.tapListenerInstalled = true;
              document.addEventListener('click', function(event) {
                if (!window.hoshiManga) return;
                event.preventDefault();
                var result = window.hoshiManga.handleTap(event.clientX, event.clientY, maxLength);
                if ((result === null || typeof result === 'undefined') && window.HoshiMangaTap) {
                  window.HoshiMangaTap.selectedNothing();
                }
              }, true);
            },
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
                if (!box.classList.contains('revealed')) {
                  // First tap: just reveal this bubble's text and action buttons. The word
                  // lookup waits for a second tap so the dictionary popup can't cover the
                  // ChatGPT / copy buttons.
                  box.classList.add('revealed');
                  return '__revealed__';
                }
                // Second tap on an already-revealed bubble: look the tapped word up.
                return window.hoshiSelection.selectText(x, y, maxLength);
              }
              window.hoshiManga.clearRevealed();
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
